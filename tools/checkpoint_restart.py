#!/usr/bin/env python3
"""Run real, sequential save/restore processes in uniquely named disposable GameTest worlds."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import subprocess
import uuid


ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def world(loader, fixture):
    if loader == "fabric":
        return ROOT / "fabric/build/run/checkpointRestart" / fixture / "world"
    return ROOT / "neoforge/build/runs/checkpointRestart" / fixture / "gametestserver/gametestworld"


def checkpoint(folder):
    return json.loads((folder / "chorus-checkpoint.json").read_text(encoding="utf-8"))


def committed(folder, coordinated=False):
    saved = checkpoint(folder)
    require(saved["phase"] == "COMMITTED" and saved["failure"] is None, f"Uncommitted checkpoint: {folder}")
    require(set(saved["required"]) == set(saved["confirmed"]) == {
        "native_files", "region_stores", "saved_data", "rule_boundary"
    }, f"Missing real save/close evidence: {folder}")
    entries = saved["archive"]["dimensions"]
    require({row["dimension"] for row in entries} == {"minecraft:overworld", "minecraft:the_nether"},
            f"Missing dimension archive: {folder}")
    player = (folder / "chorus-restart-fixture-player.txt").read_text(encoding="utf-8")
    require(saved["archive"]["format"] == (5 if coordinated else 3), f"Player-configuration archive format missing: {folder}")
    if coordinated:
        require("resource_directory" in saved["archive"], f"Missing joint resource directory: {folder}")
        owners = saved["archive"]["resource_directory"]["holders"]
        require(len(owners) == 1 and owners[0]["holder"] == player and owners[0]["domain"] == "minecraft:the_nether",
                f"Remote resource ownership changed: {folder}")
        require("participant_placement" in saved["archive"], f"Missing independent participant placement: {folder}")
        placement = saved["archive"]["participant_placement"]
        require(set(placement) == {"format", "next_generation", "characters", "buffs"} and placement["format"] == 1,
                f"Unexpected participant placement format: {folder}")
        generations = []
        for facet in ("characters", "buffs"):
            locations = placement[facet]
            require(len(locations) == 1 and locations[0]["holder"] == player and locations[0]["domain"] == "minecraft:overworld",
                    f"Resource-only migration moved player {facet}: {folder}")
            generation = locations[0]["generation"]
            require(isinstance(generation, str) and str(int(generation)) == generation and int(generation) > 0,
                    f"Invalid player {facet} placement generation: {folder}")
            generations.append(int(generation))
        next_generation = placement["next_generation"]
        require(isinstance(next_generation, str) and str(int(next_generation)) == next_generation
                and int(next_generation) > max(generations) and len(set(generations)) == 2,
                f"Invalid participant placement generation allocator: {folder}")
        require(all(row["effects"]["format"] == 2 for row in entries), f"Lost coordinated compilation mode: {folder}")
    overworld = next(row for row in entries if row["dimension"] == "minecraft:overworld")
    require(player in overworld["actor_owners"], f"Player actor ownership missing: {folder}")
    require(player in overworld["ability_owners"], f"Player ability ownership missing: {folder}")
    require((folder / "players/data" / f"{player}.dat").is_file(), f"No native player file: {folder}")
    return saved


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--loader", choices=["fabric", "neoforge", "both"], default="both")
    parser.add_argument("--java-home", help="Optional Gradle Java home; JDK21 is inherited from the environment")
    parser.add_argument("--coordinated", action="store_true", help="Exercise catalogue-backed coordinated resources and remote ownership")
    args = parser.parse_args()
    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
    output = ROOT / "build/checkpoint-restart" / stamp
    output.mkdir(parents=True)
    rows = []
    print(f"Restart acceptance artifacts: {output}", flush=True)

    def execute(loader, fixture, phase, expected_failure=False):
        command = [str(ROOT / "gradlew")]
        if args.java_home:
            command.append("-Dorg.gradle.java.home=" + args.java_home)
        task = ":fabric:runGameTest" if loader == "fabric" else ":neoforge:runGameTestServer"
        command += [f"-PchorusRestartRun={fixture}", f"-PchorusRestartPhase={phase + ('_coordinated' if args.coordinated else '')}", task, "--console=plain"]
        log = output / f"{loader}-{phase}.log"
        print(f"{loader}: {phase}", flush=True)
        with log.open("w", encoding="utf-8") as stream:
            process = subprocess.run(command, cwd=ROOT, stdout=stream, stderr=subprocess.STDOUT, timeout=300)
        text = log.read_text(encoding="utf-8")
        require((process.returncode != 0) == expected_failure, f"Unexpected exit {process.returncode}; inspect {log}")
        if expected_failure:
            require("World checkpoint has no completed save" in text, f"Startup failed for an unrelated reason: {log}")
            require("All 1 required tests passed" not in text, f"Unclean checkpoint reached live tests: {log}")
        else:
            require("All 1 required tests passed" in text, f"Restart scenario did not actually pass: {log}")
        rows.append({"loader": loader, "phase": phase, "returncode": process.returncode, "log": str(log.relative_to(ROOT))})

    for loader in ([args.loader] if args.loader != "both" else ["fabric", "neoforge"]):
        good = stamp + "-clean"
        folder = world(loader, good)
        require(not folder.exists(), f"Refusing to reuse existing test world: {folder}")
        execute(loader, good, "save")
        first = committed(folder, args.coordinated)
        execute(loader, good, "restore")
        second = committed(folder, args.coordinated)
        require(first["run"] != second["run"] and first["save"] != second["save"], "Restart reused old run/save identity")
        require(first["archive"]["dimensions"][0]["equipment_owners"] == second["archive"]["dimensions"][0]["equipment_owners"],
                "Real player's equipment ownership changed across restart")
        require(first["archive"]["dimensions"][0]["actor_owners"] == second["archive"]["dimensions"][0]["actor_owners"],
                "Real player's actor ownership changed across restart")
        require(first["archive"]["dimensions"][0]["ability_owners"] == second["archive"]["dimensions"][0]["ability_owners"],
                "Real player's ability ownership changed across restart")
        if args.coordinated:
            require(first["archive"]["participant_placement"] == second["archive"]["participant_placement"],
                    "Player participant placement or generations changed across restart and resource-only round trip")

        bad = stamp + "-failure"
        folder = world(loader, bad)
        require(not folder.exists(), f"Refusing to reuse existing test world: {folder}")
        execute(loader, bad, "save_failure")
        failed = checkpoint(folder)
        require(failed["phase"] == "PREPARED" and failed["failure"], f"Failed player write was marked recoverable: {folder}")
        file = folder / "chorus-checkpoint.json"
        before = file.read_bytes()
        execute(loader, bad, "restore_failure", expected_failure=True)
        require(file.read_bytes() == before, f"Rejected startup changed retained failed checkpoint: {folder}")
        rows[-1]["retained_checkpoint_sha256"] = hashlib.sha256(before).hexdigest()

    report = output / "report.json"
    report.write_text(json.dumps({"status": "passed", "coordinated": args.coordinated, "processes": rows}, indent=2) + "\n", encoding="utf-8")
    print(f"Passed clean restart and rejected failed-save restart: {report}", flush=True)


if __name__ == "__main__":
    main()
