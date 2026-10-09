#!/usr/bin/env python3
"""Import, inspect and audit the supplied CSV-derived Compendium snapshot. Standard library only."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data/compendium"
SNAPSHOT = DATA / "2026-10-05"
REPORT = ROOT / "docs/compendium-coverage.md"


def digest(data):
    return hashlib.sha256(data).hexdigest()


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def require(ok, message):
    if not ok:
        raise ValueError(message)


def inventory(manifest, raw):
    sheets = {s["name"]: s for s in manifest["sheets"]}
    require(len(sheets) == len(manifest["sheets"]), "Duplicate sheet")
    cells, rows, counts = {}, set(), Counter()
    for line in raw.decode("utf-8").splitlines():
        row = json.loads(line)
        name, index = row["sheet"], row["snapshot_row"]
        require(name in sheets, f"Unknown sheet {name}")
        sheet = sheets[name]
        require((name, index) not in rows, f"Duplicate row {name}/{index}")
        rows.add((name, index))
        require(row["schema_version"] == 1 and row["coordinate_system"] == "csv_snapshot_not_original_sheet", "Unexpected coordinate schema")
        for key in ("gid", "source_url", "archived"):
            require(row[key] == sheet[key], f"Conflicting {key} in {name}/{index}")
        require(row["snapshot_date"] == manifest["snapshot_date"], "Mixed snapshot dates")
        require(0 < index <= sheet["snapshot_rows"], "Row outside declared dimensions")
        for address, text in row["cells"].items():
            match = re.fullmatch(r"([A-Z]+)([1-9][0-9]*)", address)
            require(match is not None and int(match[2]) == index, f"Invalid address {name}/{address}")
            column = 0
            for c in match[1]:
                column = column * 26 + ord(c) - ord("A") + 1
            require(column <= sheet["snapshot_columns"], "Column outside declared dimensions")
            require(isinstance(text, str) and text != "", "Expected original nonempty cell string")
            require((name, address) not in cells, f"Duplicate cell {name}/{address}")
            cells[name, address] = text
            counts[name] += 1
    for name, sheet in sheets.items():
        require(counts[name] == sheet["nonempty_cells"], f"Cell count differs in {name}")
        require(sum(bool(text.strip()) for (s, _), text in cells.items() if s == name) == sheet["nonwhitespace_cells"], f"Text count differs in {name}")
    return sheets, cells, rows


def workbook_cells(path):
    """Read literal OOXML cells for an independent text audit; reject formulas, do not recalculate."""
    main = "{http://schemas.openxmlformats.org/spreadsheetml/2006/main}"
    rel = "{http://schemas.openxmlformats.org/officeDocument/2006/relationships}"
    with ZipFile(path) as archive:
        relationships = {r.attrib["Id"]: r.attrib["Target"] for r in ET.fromstring(archive.read("xl/_rels/workbook.xml.rels"))}
        strings = []
        if "xl/sharedStrings.xml" in archive.namelist():
            strings = ["".join(t.text or "" for t in x.iter(main + "t")) for x in ET.fromstring(archive.read("xl/sharedStrings.xml"))]
        result, names = {}, []
        for sheet in ET.fromstring(archive.read("xl/workbook.xml")).find(main + "sheets"):
            name = sheet.attrib["name"]; names.append(name)
            target = relationships[sheet.attrib[rel + "id"]]
            part = target.lstrip("/") if target.startswith("/") else str(PurePosixPath("xl") / target)
            for cell in ET.fromstring(archive.read(part)).iter(main + "c"):
                require(cell.find(main + "f") is None, f"Formula requires a different audit: {name}/{cell.attrib['r']}")
                if cell.attrib.get("t") == "inlineStr":
                    value = "".join(t.text or "" for t in cell.iter(main + "t"))
                else:
                    value = cell.findtext(main + "v", "")
                    if cell.attrib.get("t") == "s":
                        value = strings[int(value)]
                if value != "":
                    result[name, cell.attrib["r"]] = value
        return names, result


def import_snapshot(args):
    with ZipFile(args.archive) as archive:
        raw_manifest = archive.read("manifest.json")
        raw_rows = archive.read("rows.jsonl")
    manifest = json.loads(raw_manifest)
    require(manifest["snapshot_date"] == SNAPSHOT.name, "Use a separate directory and review migration for a new snapshot")
    sheets, cells, rows = inventory(manifest, raw_rows)
    names, actual = workbook_cells(args.workbook)
    # Only source sheets participate; unrelated workbook metadata tabs cannot become effects.
    require(set(sheets) <= set(names), "Workbook is missing source worksheets")
    actual = {key: value for key, value in actual.items() if key[0] in sheets}
    normalized = lambda text: text.replace("\r\n", "\n").replace("\r", "\n")
    require(actual.keys() == cells.keys() and all(normalized(actual[key]) == normalized(text) for key, text in cells.items()), "Workbook text differs from archive snapshot beyond XML newline normalization")
    newline_differences = [{"sheet": key[0], "cell": key[1]} for key in cells if actual[key] != cells[key]]
    provenance = {
        "snapshot_date": manifest["snapshot_date"], "coordinate_system": "csv_snapshot_not_original_sheet",
        "archive_file": args.archive.name, "archive_sha256": digest(args.archive.read_bytes()),
        "workbook_file": args.workbook.name, "workbook_sha256": digest(args.workbook.read_bytes()),
        "manifest_sha256": digest(raw_manifest), "rows_sha256": digest(raw_rows),
        "verified_worksheets": len(sheets), "verified_nonempty_rows": len(rows), "verified_cells": len(cells),
        "workbook_check": "All source-sheet nonempty literal cell strings match after XML newline normalization; no formulas accepted",
        "newline_normalized_cells": newline_differences,
        "limitations": ["CSV snapshot coordinates are not original online-sheet coordinates", "No original formulas, images, colors, merged boundaries, comments or rich-text link targets", "OLD sheets are historical; not evidence of current values"],
    }
    for name, raw in (("manifest.json", raw_manifest), ("rows.jsonl", raw_rows)):
        target = SNAPSHOT / name
        require(not target.exists() or target.read_bytes() == raw, f"Refusing to replace pinned source {target}")
    SNAPSHOT.mkdir(parents=True, exist_ok=True)
    (SNAPSHOT / "manifest.json").write_bytes(raw_manifest)
    (SNAPSHOT / "rows.jsonl").write_bytes(raw_rows)
    write_json(SNAPSHOT / "provenance.json", provenance)
    print(f"Verified {len(sheets)} sheets, {len(rows)} rows, {len(cells)} cells against workbook")


def load():
    provenance = json.loads((SNAPSHOT / "provenance.json").read_text(encoding="utf-8"))
    raw_manifest = (SNAPSHOT / "manifest.json").read_bytes(); raw_rows = (SNAPSHOT / "rows.jsonl").read_bytes()
    require(digest(raw_manifest) == provenance["manifest_sha256"], "Pinned manifest changed")
    require(digest(raw_rows) == provenance["rows_sha256"], "Pinned source text changed")
    manifest = json.loads(raw_manifest); sheets, cells, rows = inventory(manifest, raw_rows)
    require(len(cells) == provenance["verified_cells"] and len(rows) == provenance["verified_nonempty_rows"] and len(sheets) == provenance["verified_worksheets"], "Provenance counts differ")
    return manifest, sheets, cells


def reviews(cells, sheets):
    data = json.loads((DATA / "review.json").read_text(encoding="utf-8"))
    require(data["schema_version"] == 1 and data["snapshot_date"] == SNAPSHOT.name, "Wrong review schema/snapshot")
    ids = set()
    for entry in data["entries"]:
        require(entry["id"] not in ids, "Duplicate review id"); ids.add(entry["id"])
        require(entry["kind"] in {"effect", "reference", "header", "navigation"}, "Invalid reviewed kind")
        require(entry["status"] in {"unimplemented", "partial", "verified", "not_applicable"}, "Invalid reviewed status")
        require(entry["sources"], "Review has no source")
        for source in entry["sources"]:
            key = source["sheet"], source["cell"]
            require(key in cells, f"Missing source {key}")
            require(digest(cells[key].encode("utf-8")) == source["text_sha256"], f"Stale review text {key}")
            require(sheets[key[0]]["archived"] == entry["archived"], "Current and historical sources mixed in one review")
        for evidence in entry["evidence"]:
            relative = PurePosixPath(evidence["path"])
            require(not relative.is_absolute() and ".." not in relative.parts, "Evidence must stay in the repository")
            path = ROOT / relative
            require(path.is_file(), f"Missing evidence file {relative}")
            if "symbol" in evidence:
                require(re.search(r"\b" + re.escape(evidence["symbol"]) + r"\s*\(", path.read_text(encoding="utf-8")), f"Missing test symbol {evidence['symbol']}")
        if entry["status"] == "partial":
            require(entry["gaps"] and entry["evidence"], "Partial effect needs evidence and explicit gaps")
        if entry["status"] == "verified":
            require(entry["acceptance"] and not entry["gaps"] and {e["kind"] for e in entry["evidence"]} >= {"definition", "unit_test", "world_test"}, "Verified effect requires complete acceptance evidence")
    return data["entries"]


def report(manifest, sheets, cells, entries):
    reviewed = { (source["sheet"], source["cell"]) for e in entries for source in e["sources"] }
    lines = ["# Compendium 来源与覆盖清单", "", "由 `python3 tools/compendium.py report` 生成；人工判断保存在 `data/compendium/review.json`。", "",
        f"基线为 {manifest['snapshot_date']} 的 CSV 数据副本。{len(sheets)} 张来源表、{len(cells)} 个非空单元格已与用户工作簿逐格比对；XML 换行规范化差异另行记录，原始文本未改写。坐标不是在线原表行号。原文、URL、历史标志和摘要校验保存在 [固定快照](../data/compendium/2026-10-05/provenance.json)。", "",
        "单元格数量不是效果数量。一格可能包含多条机制、表头或公式说明；未人工审阅的内容一律保留为待审阅，不用关键词把它们算成已覆盖。下表统计来源审阅进度，不能据此计算全效果覆盖百分比。OLD 表单独保存与统计，不能混作当前数值。", "",
        "| 来源表 | 历史 | 文本单元格 | 已引用审阅 | 待审阅 |", "| --- | --- | ---: | ---: | ---: |"]
    for name, sheet in sheets.items():
        keys = {key for key, value in cells.items() if key[0] == name and value.strip()}
        done = len(keys & reviewed)
        lines.append(f"| {name} | {'是' if sheet['archived'] else '否'} | {len(keys)} | {done} | {len(keys)-done} |")
    lines += ["", "## 已建立的需求与证据", "", "状态由审阅者填写；脚本验证源文本未漂移、文件与测试方法存在，不把文件存在或通用单测通过自动升级为效果验收通过。实际测试结果见 [实现记录](engine-implementation.md)。", "",
        "| 条目 | 状态 | 来源（快照） | 验证范围 / 未完成项 |", "| --- | --- | --- | --- |"]
    for entry in entries:
        sources = "; ".join(f"{s['sheet']} {s['cell']}" for s in entry["sources"])
        scope = entry["scope"] + ("；缺口：" + "；".join(entry["gaps"]) if entry["gaps"] else "")
        lines.append(f"| {entry['name']} | {entry['status']} | {sources} | {scope.replace('|', '/')} |")
    lines += ["", "## 使用", "", "```sh", "python3 tools/compendium.py check", "python3 tools/compendium.py show --sheet 'Weapon Perks' --cell C10", "python3 tools/compendium.py pending --sheet 'Weapon Perks' --limit 20", "python3 tools/compendium.py report --check", "```", "",
        "新增条目时人工区分名称、定义与上下文，并在 review.json 记录逐项验收要求、数据定义、具体测试方法及剩余缺口。新快照另建目录并复审文本变化，不沿用旧坐标自动宣称已覆盖。", ""]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    imp = sub.add_parser("import"); imp.add_argument("--archive", type=Path, required=True); imp.add_argument("--workbook", type=Path, required=True)
    sub.add_parser("check")
    rep = sub.add_parser("report"); rep.add_argument("--check", action="store_true")
    show = sub.add_parser("show"); show.add_argument("--sheet", required=True); show.add_argument("--cell", required=True)
    pending = sub.add_parser("pending"); pending.add_argument("--sheet", required=True); pending.add_argument("--limit", type=int, default=20)
    args = parser.parse_args()
    if args.command == "import":
        import_snapshot(args); return
    manifest, sheets, cells = load()
    if args.command == "show":
        require((args.sheet, args.cell) in cells, "No such snapshot cell")
        print(json.dumps({"sheet": args.sheet, "cell": args.cell, "archived": sheets[args.sheet]["archived"], "text": cells[args.sheet, args.cell]}, ensure_ascii=False, indent=2)); return
    entries = reviews(cells, sheets)
    if args.command == "check":
        print(f"Validated {len(sheets)} sheets, {len(cells)} preserved cells and {len(entries)} reviewed entries; no automatic coverage claim")
    elif args.command == "report":
        content = report(manifest, sheets, cells, entries)
        if args.check:
            require(REPORT.read_text(encoding="utf-8") == content, "Coverage report is stale; regenerate it")
        else:
            REPORT.write_text(content, encoding="utf-8")
    elif args.command == "pending":
        require(args.sheet in sheets and args.limit > 0, "Unknown sheet or invalid limit")
        known = {(s["sheet"], s["cell"]) for e in entries for s in e["sources"]}
        for (sheet, cell), text in [(key, value) for key, value in cells.items() if key[0] == args.sheet and key not in known and value.strip()][:args.limit]:
            print(json.dumps({"sheet": sheet, "cell": cell, "text": text}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, KeyError, OSError) as error:
        raise SystemExit(str(error)) from error
