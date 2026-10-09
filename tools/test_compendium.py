"""Provenance failures must stay visible instead of becoming apparent effect coverage."""
import copy
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import compendium as c


class CompendiumTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest, cls.sheets, cls.cells = c.load()
        cls.raw = (c.SNAPSHOT / "rows.jsonl").read_bytes()
        cls.review = json.loads((c.DATA / "review.json").read_text(encoding="utf-8"))

    def test_all_source_cells_are_preserved_including_historical_and_whitespace(self):
        self.assertEqual(22, len(self.sheets))
        self.assertEqual(6666, len(self.cells))
        self.assertEqual(6, sum(s["archived"] for s in self.sheets.values()))
        self.assertEqual(" ", self.cells["Landing", "A1"])
        self.assertIn("\r\n", self.cells["Armor Mods", "T21"])

    def test_duplicate_rows_mismatched_archive_flags_and_coordinates_are_rejected(self):
        rows = self.raw.decode("utf-8").splitlines()
        with self.assertRaisesRegex(ValueError, "Duplicate row"):
            c.inventory(self.manifest, ("\n".join(rows + [rows[0]])).encode("utf-8"))
        for key, value in (("archived", True), ("snapshot_row", 2)):
            first = json.loads(rows[0]); first[key] = value
            changed = (json.dumps(first) + "\n" + "\n".join(rows[1:])).encode("utf-8")
            with self.assertRaises(ValueError):
                c.inventory(self.manifest, changed)

    def test_modified_source_requires_a_new_review_instead_of_reusing_old_hashes(self):
        with tempfile.TemporaryDirectory() as directory:
            snapshot = Path(directory)
            for name in ("provenance.json", "manifest.json", "rows.jsonl"):
                (snapshot / name).write_bytes((c.SNAPSHOT / name).read_bytes())
            (snapshot / "rows.jsonl").write_bytes(self.raw.replace(b"Adrenaline Junkie", b"Changed Perk", 1))
            with patch.object(c, "SNAPSHOT", snapshot), self.assertRaisesRegex(ValueError, "source text changed"):
                c.load()

    def test_stale_missing_and_misclassified_evidence_are_rejected(self):
        variants = []
        stale = copy.deepcopy(self.review); stale["entries"][0]["sources"][0]["text_sha256"] = "changed"; variants.append(stale)
        missing = copy.deepcopy(self.review); missing["entries"][0]["evidence"][1]["symbol"] = "thisMethodDoesNotExist"; variants.append(missing)
        archived = copy.deepcopy(self.review); archived["entries"][0]["archived"] = True; variants.append(archived)
        premature = copy.deepcopy(self.review); premature["entries"][0]["status"] = "verified"; variants.append(premature)
        for data in variants:
            with self.subTest(), tempfile.TemporaryDirectory() as directory:
                root = Path(directory); c.write_json(root / "review.json", data)
                with patch.object(c, "DATA", root), self.assertRaises(ValueError):
                    c.reviews(self.cells, self.sheets)

    def test_unreviewed_source_is_reported_without_claiming_coverage(self):
        entries = c.reviews(self.cells, self.sheets)
        report = c.report(self.manifest, self.sheets, self.cells, entries)
        self.assertIn("| OLD Armor Mods | 是 | 537 | 0 | 537 |", report)
        self.assertIn("不能据此计算全效果覆盖百分比", report)
        self.assertFalse(any(e["status"] == "verified" for e in entries))


if __name__ == "__main__":
    unittest.main()
