#!/usr/bin/env python3
"""Import selected public Compendium images without changing the numerical snapshot."""
import argparse
from dataclasses import dataclass, field
from datetime import datetime, timezone
import gzip
import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import struct
from urllib.parse import urlparse
from urllib.request import urlopen

ROOT = Path(__file__).resolve().parents[1]
DATA = ROOT / "data/compendium"
BOOK = "https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4"
TEXTURES = Path("common/src/main/resources/assets/chorus_d2/textures/gui")


def require(condition, message):
    if not condition:
        raise ValueError(message)


def digest(raw):
    return hashlib.sha256(raw).hexdigest()


def normalized(text):
    return " ".join(text.split())


def column_name(column):
    result = ""
    while column:
        column, remainder = divmod(column - 1, 26)
        result = chr(65 + remainder) + result
    return result


@dataclass
class Cell:
    colspan: int
    rowspan: int
    text: str = ""
    images: list[str] = field(default_factory=list)


class Sheet(HTMLParser):
    """Read original row-header ids and merged cells; never infer rows from CSV."""
    def __init__(self, gid, raw):
        super().__init__(convert_charrefs=True)
        self.gid, self.cells, self.occupied, self.rows = gid, {}, set(), set()
        self.table_depth, self.active_depth = 0, None
        self.row, self.cell, self.number = [], None, None
        self.feed(raw.decode("utf-8"))
        self.close()
        require(self.cells, f"No labelled sheet cells for gid {gid}; expected headers=true HTML")

    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        if tag == "table":
            self.table_depth += 1
            if "waffle" in attrs.get("class", "").split():
                require(self.active_depth is None, "Nested sheet tables are unsupported")
                self.active_depth = self.table_depth
        if self.active_depth is None:
            return
        require(self.table_depth == self.active_depth, "Nested sheet tables are unsupported")
        if tag == "tr":
            self.row, self.cell, self.number = [], None, None
        elif tag == "th" and re.fullmatch(r"\d+R\d+", attrs.get("id", "")):
            gid, index = attrs["id"].split("R")
            require(gid == self.gid and self.number is None, "Wrong or duplicate row header")
            self.number = int(index) + 1
        elif tag == "td":
            if "freezebar-cell" in attrs.get("class", "").split():
                return
            self.cell = Cell(int(attrs.get("colspan", "1")), int(attrs.get("rowspan", "1")))
            require(0 < self.cell.colspan <= 1000 and 0 < self.cell.rowspan <= 10000, "Invalid merged cell")
            self.row.append(self.cell)
        elif self.cell is not None:
            if tag == "img":
                self.cell.images.append(attrs.get("src", ""))
            elif tag == "br":
                self.cell.text += "\n"

    def handle_data(self, text):
        if self.cell is not None:
            self.cell.text += text

    def handle_endtag(self, tag):
        if self.active_depth is not None:
            if tag == "td":
                self.cell = None
            elif tag == "tr":
                if self.row:
                    require(self.number is not None, "Sheet row has no original row header")
                    require(self.number not in self.rows, "Duplicate original row")
                    self.rows.add(self.number)
                    column = 1
                    for cell in self.row:
                        while (self.number, column) in self.occupied:
                            column += 1
                        address = f"{column_name(column)}{self.number}"
                        require(address not in self.cells, f"Duplicate cell {address}")
                        self.cells[address] = cell
                        for row in range(self.number, self.number + cell.rowspan):
                            for col in range(column, column + cell.colspan):
                                require((row, col) not in self.occupied, "Overlapping merged cells")
                                self.occupied.add((row, col))
                        column += cell.colspan
                self.row, self.cell, self.number = [], None, None
            elif tag == "table" and self.table_depth == self.active_depth:
                self.active_depth = None
        if tag == "table":
            self.table_depth -= 1


def selected_image(sheet, entry):
    label = sheet.cells.get(entry["label_cell"])
    require(label is not None and normalized(label.text) == entry["label"],
            f"Source label moved/changed: {entry['sheet']}!{entry['label_cell']} ({entry['label']})")
    image = sheet.cells.get(entry["image_cell"])
    require(image is not None and len(image.images) == 1,
            f"Expected exactly one image: {entry['sheet']}!{entry['image_cell']}")
    return image.images[0]


def png_size(raw):
    require(len(raw) >= 33 and raw[:8] == b"\x89PNG\r\n\x1a\n" and raw[12:16] == b"IHDR",
            "Expected PNG bytes; do not save an error page or transcode silently")
    width, height = struct.unpack(">II", raw[16:24])
    require(0 < width <= 8192 and 0 < height <= 8192, "Invalid PNG dimensions")
    return [width, height]


def download(url, limit):
    parsed = urlparse(url)
    require(parsed.scheme == "https" and parsed.username is None and parsed.password is None
            and parsed.port in (None, 443)
            and (parsed.hostname == "docs.google.com" or (parsed.hostname or "").endswith(".googleusercontent.com")),
            f"Unexpected public image/sheet host: {url}")
    with urlopen(url, timeout=40) as response:
        raw = response.read(limit + 1)
        content_type = response.headers.get_content_type()
    require(len(raw) <= limit, "Response exceeds import size limit")
    return raw, content_type


def selection(path):
    entries = json.loads(path.read_text(encoding="utf-8"))["entries"]
    sheets = {s["name"]: s for s in json.loads((DATA / "2026-10-05/manifest.json").read_text())["sheets"]}
    ids = set()
    require(entries, "Empty asset selection")
    for entry in entries:
        require(re.fullmatch(r"(?:perks|abilities)/[a-z0-9_]+", entry["id"]), "Invalid texture id")
        require(entry["id"] not in ids, "Duplicate texture id")
        ids.add(entry["id"])
        require(entry["sheet"] in sheets and entry["gid"] == sheets[entry["sheet"]]["gid"]
                and not sheets[entry["sheet"]]["archived"], "Wrong or historical source sheet")
        for key in ("label_cell", "image_cell"):
            require(re.fullmatch(r"[A-Z]+[1-9][0-9]*", entry[key]), f"Invalid {key}")
        require(entry["label"] and normalized(entry["label"]) == entry["label"], "Invalid source label")
    return entries


def import_assets(entries, destination):
    require(not destination.exists(), "Use a new source directory; refusing to overwrite an asset snapshot")
    sources, parsed, pending, assets = {}, {}, {}, []
    for entry in entries:
        gid = entry["gid"]
        if gid not in sources:
            url = f"{BOOK}/htmlview/sheet?headers=true&gid={gid}"
            raw, media_type = download(url, 8 * 1024 * 1024)
            require(media_type == "text/html", "Expected public sheet HTML")
            parsed[gid] = Sheet(gid, raw)
            source_file = f"sheets/{gid}.html.gz"
            pending[destination / source_file] = gzip.compress(raw, mtime=0)
            sources[gid] = {"gid": gid, "sheet": entry["sheet"], "url": url,
                            "retrieved_at": datetime.now(timezone.utc).isoformat(),
                            "file": source_file, "html_sha256": digest(raw)}
        url = selected_image(parsed[gid], entry)
        raw, media_type = download(url, 2 * 1024 * 1024)
        require(media_type == "image/png", "Expected image/png response")
        size = png_size(raw)
        resource_file = TEXTURES / (entry["id"] + ".png")
        target = ROOT / resource_file
        require(not target.exists() or target.read_bytes() == raw,
                f"Texture changed: review explicitly before replacing {resource_file}")
        pending[target] = raw
        assets.append({**entry, "source_url": f"{BOOK}/edit?gid={gid}&range={entry['image_cell']}",
                       "image_url": url, "retrieved_at": datetime.now(timezone.utc).isoformat(),
                       "file": resource_file.as_posix(), "texture": f"chorus_d2:textures/gui/{entry['id']}.png",
                       "sha256": digest(raw), "size": size, "media_type": media_type})
    manifest = {"schema_version": 1, "coordinate_system": "original_google_sheet",
                "image_variant": "html_preview_unmodified_bytes", "sources": list(sources.values()), "assets": assets}
    # Validate every response before writing any source or resource. No partial network import.
    for path, raw in pending.items():
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
    (destination / "manifest.json").write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Imported {len(assets)} PNG images from {len(sources)} sheets into chorus_d2")


def check(manifest_path):
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    require(manifest["schema_version"] == 1 and manifest["coordinate_system"] == "original_google_sheet"
            and manifest["image_variant"] == "html_preview_unmodified_bytes", "Unexpected asset provenance schema")
    sheets, names = {}, {}
    for source in manifest["sources"]:
        gid = source["gid"]
        require(gid.isdigit() and source["file"] == f"sheets/{gid}.html.gz" and gid not in sheets, "Invalid source path/gid")
        require(source["url"] == f"{BOOK}/htmlview/sheet?headers=true&gid={gid}", "Unexpected sheet URL")
        raw = gzip.decompress((manifest_path.parent / source["file"]).read_bytes())
        require(digest(raw) == source["html_sha256"], "Source HTML changed")
        sheets[gid] = Sheet(gid, raw)
        names[gid] = source["sheet"]
    ids = set()
    for asset in manifest["assets"]:
        asset_id = asset["id"]
        require(re.fullmatch(r"(?:perks|abilities)/[a-z0-9_]+", asset_id) and asset_id not in ids, "Invalid/duplicate asset id")
        ids.add(asset_id)
        require(asset["sheet"] == names[asset["gid"]], "Source sheet name differs")
        require(asset["file"] == (TEXTURES / (asset_id + ".png")).as_posix(), "Unexpected resource path")
        require(asset["texture"] == f"chorus_d2:textures/gui/{asset_id}.png", "Texture id differs")
        require(asset["image_url"] == selected_image(sheets[asset["gid"]], asset), "Source image URL differs")
        require(asset["source_url"] == f"{BOOK}/edit?gid={asset['gid']}&range={asset['image_cell']}", "Source coordinate differs")
        raw = (ROOT / asset["file"]).read_bytes()
        require(digest(raw) == asset["sha256"] and png_size(raw) == asset["size"]
                and asset["media_type"] == "image/png", f"Texture changed: {asset_id}")
    require(ids, "No imported assets")
    print(f"Verified {len(ids)} images against saved HTML and resource hashes (offline)")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    fetch = commands.add_parser("fetch")
    fetch.add_argument("--selection", type=Path, default=DATA / "assets/selection.json")
    fetch.add_argument("--output", type=Path, required=True)
    audit = commands.add_parser("check")
    audit.add_argument("manifest", type=Path)
    args = parser.parse_args()
    if args.command == "fetch":
        import_assets(selection(args.selection), args.output)
    else:
        check(args.manifest)


if __name__ == "__main__":
    main()
