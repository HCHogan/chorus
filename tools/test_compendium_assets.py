"""Image provenance must survive merged cells, source drift and failed downloads."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import compendium_assets as a


class CompendiumAssetsTest(unittest.TestCase):
    HTML = b'''<table class="waffle"><tr><th></th><th>A</th><th>B</th></tr>
      <tr><th class="freezebar-cell"></th><td class="freezebar-cell"></td></tr>
      <tr><th id="123R13">14</th><td colspan="2" rowspan="2">Section</td>
      <td>Marksman&#39;s<br>Dodge</td><td><img src="https://docs.google.com/icon.png?a=1&amp;b=2"></td></tr>
      <tr><th id="123R14">15</th><td>Second</td><td>Other</td></tr>
      <tr><th id="123R20">21</th><td>After hidden rows</td></tr></table>'''
    ENTRY = {"id": "abilities/marksman_dodge", "sheet": "Class Abilities", "gid": "123",
             "label": "Marksman's Dodge", "label_cell": "C14", "image_cell": "D14"}

    def test_original_rows_merges_freezebars_and_entities(self):
        sheet = a.Sheet("123", self.HTML)
        self.assertEqual("Second", sheet.cells["C15"].text)
        self.assertEqual("After hidden rows", sheet.cells["A21"].text)
        self.assertNotIn("B14", sheet.cells)
        self.assertEqual("https://docs.google.com/icon.png?a=1&b=2", a.selected_image(sheet, self.ENTRY))

    def test_wrong_missing_or_duplicate_headers_do_not_become_guessed_coordinates(self):
        for html in (self.HTML.replace(b"123R13", b"321R13"),
                     self.HTML.replace(b'id="123R13"', b''),
                     self.HTML.replace(b"123R20", b"123R14")):
            with self.subTest(), self.assertRaises(ValueError):
                a.Sheet("123", html)
        with self.assertRaisesRegex(ValueError, "No labelled sheet cells"):
            a.Sheet("123", b"<html>Please sign in</html>")

    def test_moved_labels_and_ambiguous_images_require_review(self):
        for html in (self.HTML.replace(b"Marksman", b"Gambler"),
                     self.HTML.replace(b"<img", b"<img src='duplicate'><img"),
                     self.HTML.replace(b"<img", b"<not-an-image")):
            with self.subTest(), self.assertRaises(ValueError):
                a.selected_image(a.Sheet("123", html), self.ENTRY)

    def test_saved_sources_and_resources_verify_offline_and_reject_tampering(self):
        original = a.DATA / "assets/2026-10-10/manifest.json"
        manifest = json.loads(original.read_text())
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            snapshot = root / "snapshot"
            snapshot.mkdir()
            target = snapshot / "manifest.json"
            target.write_bytes(original.read_bytes())
            for source in manifest["sources"]:
                destination = snapshot / source["file"]
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes((original.parent / source["file"]).read_bytes())
            for asset in manifest["assets"]:
                destination = root / asset["file"]
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes((a.ROOT / asset["file"]).read_bytes())
            with patch.object(a, "ROOT", root):
                a.check(target)
                changed = copy.deepcopy(manifest)
                changed["assets"][0]["label"] = "Wrong name"
                target.write_text(json.dumps(changed))
                with self.assertRaisesRegex(ValueError, "label moved/changed"):
                    a.check(target)
                target.write_bytes(original.read_bytes())
                image = root / manifest["assets"][0]["file"]
                image.write_bytes(image.read_bytes() + b"changed")
                with self.assertRaisesRegex(ValueError, "Texture changed"):
                    a.check(target)

    def test_network_failure_does_not_leave_a_partial_import(self):
        png = next((a.ROOT / a.TEXTURES).rglob("*.png")).read_bytes()
        second = {**self.ENTRY, "id": "abilities/second"}
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with patch.object(a, "ROOT", root), patch.object(a, "download", side_effect=[
                    (self.HTML, "text/html"), (png, "image/png"), OSError("connection lost")]):
                with self.assertRaisesRegex(OSError, "connection lost"):
                    a.import_assets([self.ENTRY, second], root / "snapshot")
            self.assertEqual([], list(root.iterdir()))


if __name__ == "__main__":
    unittest.main()
