# Compendium 需求来源

`2026-10-05/rows.jsonl` 和 `manifest.json` 是用户提供的 AI 导出包原始文件，不在导入时改写、翻译或合并单元格。已将 22 张来源表的全部 6666 个非空单元格与用户的 `.xlsx` 数据副本核对。13 处 CRLF / LF 换行差异列在 `provenance.json`，除此以外文本与坐标一致；工作簿和压缩包的 SHA-256 均已记录。原工作簿未修改。

该工作簿本身是 CSV 数据副本，不含原始公式、图片、颜色、合并边界、批注或富文本链接目标。`snapshot_row`、单元格坐标只能定位此快照，不能当成在线表原始行号。6 张 OLD 表是历史来源，单独保留，不能和当前规则集数值混用。

`review.json` 是人工维护的需求清单：每个条目记录具体来源单元格和原文摘要、验收要求、对应数据定义、测试方法、验证范围与缺口。一个效果可跨多个单元格；一格也可能含多条效果。未审阅单元格仍是未知内容，不自动分类成效果或覆盖率分母。

常用命令在仓库根目录执行，无第三方 Python 依赖：

```sh
python3 tools/compendium.py check
python3 tools/compendium.py show --sheet 'Weapon Perks' --cell C10
python3 tools/compendium.py pending --sheet 'Armor Perks' --limit 10
python3 tools/compendium.py report
python3 tools/compendium.py report --check
python3 -m unittest discover -s tools -p 'test_compendium.py'
```

导入命令需提供 `--archive` 和 `--workbook`，它逐格核验后才保存。已有固定快照内容不同则拒绝覆盖；更新日期时应建新目录并显式复审，不能靠同名词条沿用旧文本的验证状态。

审计器检查来源摘要、历史标志、定义文件及测试方法存在性。它不执行 Java 测试，也不自动把这些证据判为完整实现。`partial` 必須保留未完成项，`verified` 需要人工确认所有验收要求、完整定义与纯核心 / 世界证据；当前无已完整验收条目。生成报告见 [覆盖清单](../../docs/compendium-coverage.md)。
