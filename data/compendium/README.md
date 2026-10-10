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

## 在线原表与图标

用户提供的[在线原表](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit?gid=1038486120)用于补充图片、链接和后续数值核对。在线内容会更新；核对后的新数值仍需独立快照及效果复审，不能自动覆盖 `2026-10-05` 的验收来源。

首批图标见 [素材清单](assets/2026-10-10/manifest.json)：Clown Cartridge、Kill Clip、Overflow、Voltshot，以及 Marksman's Dodge、Healing Rift。PNG 位于 `common/src/main/resources/assets/chorus_d2/textures/gui/{perks,abilities}/`，可用清单中的 `texture` id 引用。当前只完成资源导入，还未把这些图标绑定到 perk 卡片、技能选择页或 HUD，也不代表对应玩法已实现。

第二批补充 [Demolitionist、Pugilist、Wellspring](assets/2026-10-10-energy-perks/manifest.json)，对应原表图像 B63 / B170 / B247，均保留 70×70 PNG 原始字节与新抓取的 HTML。累计 9 个图标已打包；这批来源独立保存，不覆盖首批来源或数值快照。选择清单为 [selection-energy-perks.json](assets/selection-energy-perks.json)。

`tools/compendium_assets.py` 读取原表的 `htmlview/sheet?headers=true&gid=…`。导入清单中的名称、名称单元格与图片单元格必须同时匹配；坐标依据网页原始行头和合并单元格还原，不借用 CSV 行号。例如原表 Overflow 图标在 B161，而既有数值快照的描述在 C160。名字移动或图片缺失 / 歧义会终止导入，先校对 `assets/selection.json` 再重试。

下载保留 PNG 原始响应字节、尺寸、SHA-256、抓取时间和图片 URL；来源 HTML 压缩保存在同批目录，可离线复核图片与名称的对应。当前来源是网页预览图：perk 为 70×70、技能为 64×64，没有放大、重绘或假设为最高分辨率。Google 图片 URL 可能失效，运行时使用已打包的本地资源，不请求 Google。

```sh
# 离线校验已导入的图标及来源，不访问网络。
python3 tools/compendium_assets.py check data/compendium/assets/2026-10-10/manifest.json
python3 tools/compendium_assets.py check data/compendium/assets/2026-10-10-energy-perks/manifest.json
python3 -m unittest discover -s tools -p 'test_compendium*.py'

# 后续按需扩展 selection，在新的来源目录导入。
python3 tools/compendium_assets.py fetch --output data/compendium/assets/<new-capture>
```

导入先下载并校验所有选定图片，再写入文件。已有来源目录不会覆盖；已有纹理内容若变化也会拒绝覆盖，需要显式复审。后续高分辨率版本应另记其真实来源及尺寸。素材保持原有归属，不因纳入仓库而改变授权。
