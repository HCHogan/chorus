# Chorus 命运 2 规则集（chorus_d2）

2026-10-10 · Hank Hogan

本文件记录内置内容包 `chorus_d2` 的参考数值和校准资料：命运 2 的乘区分组、伤害与回能公式、资料来源与冲突，以及一次完整的 build 走查。引擎机制（计算 Profile、贡献、归约、血池算法、执行协议）见 [rule-engine-design.md](rule-engine-design.md)。

目标是玩起来像命运 2，适配 Minecraft 优先于 1:1 复刻。所以本文的数值在模组默认配置里是**参考值**：比例和规则直接沿用，绝对量按下一节缩放。整合包的"体验模式"则直接使用这里的校准值和置信度，见设计文档的「分发形态」。

## 换算到 Minecraft

- **直接沿用**：百分比增减、持续时间、层数、冷却、叠加与互斥规则、触发条件。一份技能充能记为 1.0，能量收益也直接沿用。距离按 1 米 = 1 格。
- **按比例缩放**：绝对伤害、生命和护盾容量、治疗量，以及阈值类数值（Jolt 的 115 点、冰冻的 200 点碎裂）。这些要用**同一个**缩放因子，否则"几枪打死""几下触发"的手感会变。
- 缩放因子是玩法调参：以原版生物为锚点来定，比如主武器几枪打死一只僵尸。见设计文档的「开放问题」。

## 装备来源

装备来源现已可由数据声明的槽位、物品原型和词条选项生成。三武器槽和 helmet / arms / chest / legs / class_item 五护甲槽属于 chorus_d2 内容定义；引擎不依赖原版护甲槽。金装武器 / 护甲分别以标签和槽位集合声明数量限制。当前合成装备夹具证明装配、强化标签、收枪保留、旧词条清理及实际伤害联动；真实独立物品容器也已接入背包交换、玩家保存 / 加载和死亡 / respawn。独立容器已有服务端展示同步与按 K 打开的最小配装页，配色与槽位翻译在 assets/chorus_d2。通用技能入口也已支持基础选择、施放时的来源 / Buff 替换、参数与成本 Profile、实际支付回执及 on_use；目前使用合成定义和服务端命令验证，未将其视为任何 D2 技能的完整内容验收。服务端单次开火、扣弹 / 射速和物理投射物已经接入实际容器。全部 D2 物品原型、插槽、职业限制、精准区域、真实武器散布及完整 HUD / 技能 UI 仍未落地；显式多弹丸已支持逐颗回执和整枪聚合。

武器效果默认在已装备时绑定，触发规则仍按该武器实例、伤害信用及自身条件判断；明确要求在手的规则另查 weapon_drawn。词条的强化标签只附在相应来源上，不能让另一词条同时获得强化资格。独立 Buff 是否随卸装清除由该效果的 source_detached 规则决定，不统一抹除已释放的持续效果或伤害快照。接口和当前边界见 [装备装配说明](engine-data-packs.md#装备定义与原子装配投影)。

## 武器输入与 Kill Clip 集成边界

实际容器现可通过服务端单次开火入口执行武器声明的 on_fire。弹药成本与每实例射击间隔先提交，接受时取消手动换弹；空弹 / 冷却 / 条件失败不取消。间隔固定接受时的 Profile 结果，后续切枪 / 换手不重置。这是当前通用宿主策略，具体武器原型、长按、蓄力、burst、精准区域和真实散布仍需校准与实现；显式多弹丸结算已接入下述回执事件。

[weapon_fire.json](../common/src/test/resources/effects/weapon_fire.json) 与 Kill Clip 片段链接，已在双加载器完成真实武器输入 → 投射物击杀 → 服务器换弹完成 → 下一发 25% 增伤；发射后收枪移除 Buff 仍保留已捕获攻击的增伤和原武器身份。对照场景从伤害声明中去掉 weapon_kill 标签，真实击杀仍携带武器来源，却不会触发 Kill Clip 窗口。因此信用不能仅凭背后存在武器来推断。

Kill Clip 继续标 partial：窗口收枪保留仍为内容假设，夹具 5 发容量、0.15 秒射击间隔、0.2 秒换弹与 10 点基础伤害均为合成测试参数，不是 D2 原型数值。fire_accepted 仅指请求已接受；发射成功看世界回执，命中 / 击杀看实际伤害回执，显式 begin_shot 与成员投射物提供基于回执的 shot_progress / shot_resolved，分别表示确认命中后的进度与整枪最终结果。One-Two Punch 的独立内容验收及边界见下一节。

## One-Two Punch（雪上加霜）

[one_two_punch.json](../common/src/test/resources/effects/one_two_punch.json) 已接入独立装备词条、真实开火弹丸和受管近战。来源为 2026-10-05 CSV 快照 Weapon Perks A154 / C154；2026-10-10 核对公开原表时对应 A155 / C155，数值相同，不能混用两套坐标。

| 武器类别 | 普通 / 强化门槛 | PvE 近战加成 | PvP 近战加成 |
| --- | --- | --- | --- |
| 霰弹枪 | 同一枪对同一目标 12 / 10 颗唯一命中弹丸 | +150% | +100% |
| 手炮 | 同上 | +75% | +50% |

达到门槛即获得 3 秒增益，下一次合格近战命中后消费，收枪移除。内容监听 shot_progress 的门槛跨越；强化版不等待另外两颗结束，同枪第 11 / 12 颗不重置计时。新一枪再次达标刷新时长但不叠层，恰好到期时已无增益。旧持有者的弹丸不能触发新持有者；更换词条 / 卸下来源会清除就绪。

近战组按 `1 + max(One-Two Punch 增量, 冰冻非 Boss 被动近战增量) + 其他可相加近战增量` 求倍率，再用于该阶段输入伤害。互斥关系来自 C154，近战 buildcrafting 增伤改为相加及霰弹枪 PvE 的 +150% 由 [Bungie 9.0.0.1](https://www.bungie.net/7/en/News/article/destiny_update_9_0_0_1) 支持。测试注入的冰冻 50% / 200% 和其他 40% 只验证取高与相加，不是原作冰冻数值。

11 项纯核心、6 项双加载器共享场景覆盖两种武器 / 活动模式、实际玩家命令与扣血、分散命中 / 不同枪次 / 重复接触、三秒到期、收枪、来源移交、未结束弹丸、免疫 / 取消与未知回执。下一击通过 consume_on_damage 在回执完成时消费；同一动作体的第二次独立近战已无此加成，复用攻击快照也不能复制增益。未知结果保留待确认状态并停止，不重放已经发生的扣血。

内容已声明 sharing=group：受管技能可用 begin_damage_group 把同一次攻击的多个分量明确关联。合成 split_melee 把 10 点近战拆为 5 + 5，普通 PvE 霰弹枪 Buff 下分别造成 12.5 + 12.5，后续独立 10 点近战不再增伤；第一段回执即消费 Buff。原有 double_melee 继续表示两次独立打击，结果为 25 + 10。这个对照验证引擎可以表达两种语义，不代表已校准每个 D2 近战技能的分量归组。

**当前为 partial。** 枪械速度 / 散布、容量 / 间隔和基础伤害是合成夹具参数，近战 Profile 仅验证增益组合。引擎已支持原版观察入口自动消费和嵌套伤害预留；默认原版来源不猜测 D2 标签，完整 D2 原型、技能近战分类 / 攻击归组和冰冻非 Boss 被动来源仍未完成。以 hit 计入免疫 / 格挡及消费、冻结增益更高时仍消费、新枪刷新和词条替换清除，是明确的当前内容策略；原表未说明的边界仍需原作校准。

## 弹药生成、补充与换弹

固定快照 Weapon Perks C250 将 Generate / Refill / Reload 分开：生成弹药不消耗储备，不能超过基础弹匣；Refill 在特殊 / 威能武器上从储备转移，不播放换弹动画或触发换弹词条；Reload 才有合格换弹触发。手动换弹、Marksman's Dodge 和 Dragon's Shadow 在该注释中属于 Reload，热量武器又区分散热与完成手动排热。这里采用 2026-10-05 快照术语，尚未据此实现这些技能或热量武器。

引擎现有 `grant_ammo / refill_magazine / spend_ammo` 整数账户，以及保持单位的显式取整；整弹匣手动换弹已接入实际独立容器：服务端固定接受时长、到期复核持握与存活等资格，按当时容量 / 储备实际装入后发出 `reload_finished`。切枪或接受的开火取消；速度修饰在接受时冻结，容量在完成时读取，这是当前明确的宿主时机策略，仍需按具体原型校准。百分比和整数发数不乘生命值的 0.1 投影因子。部分词条需求可映射如下，坐标均为 CSV 快照坐标：

| 效果 / 来源 | 账户与规则要求 | 尚需内容或宿主完成 |
| --- | --- | --- |
| Fourth Time's The Charm，C99 | 四次精准命中、相邻命中 2 秒 / 强化 3 秒窗，生成 2 发，基础弹匣上限 | 精准命中去重 / 信用、计数窗、收枪保留和武器装配 |
| Triple Tap，C235 | 三次精准命中、相同 2 / 3 秒窗，生成 1 发，基础弹匣上限 | 对应计数、精准命中与真实枪械接线 |
| Overflow，C160 | 拾取特殊 / 重型弹药砖后 refill，临时 ceiling 为基础容量的 2 / 强化 2.2 倍；收枪可触发 | 拾取资格 / 储备增加与 refill 的顺序、真实物品和词条装配 |
| Reconstruction，C178 | 停火 6 / 强化 5.5 秒后按相同周期 refill 基础容量的 25%，最多溢出到 2 倍；收枪保留 | 首次时点、停火重置、25% 发数取整须明确；不能把合成的 1 秒定时测试当成该词条 |
| Rewind Rounds，C187 | 射空弹匣后 refill 命中伤害次数的 60% / 强化 70%，上取整；至少射出 ceil(基础容量 × 28.5%) | 每发 / 每 bolt 计数与射空资格；适应爆发线融为 14%；补弹后计数屏蔽 `1?` 秒仍是未知 |
| Clown Cartridge，C47 | 合格换弹后一次随机取值，以有效基础容量计算溢出上限并上取整，从储备 refill | partial：独立 JSON / 实际装备与换弹 / 随机回执已验；分布与组合顺序仍待校准 |

除下文 Clown Cartridge 已推进到 partial，其他五项仍是需求审阅，状态为 unimplemented：通用 [ammunition.json](../common/src/test/resources/effects/ammunition.json) 证明账户、转移、取整及动作结果组合，不代表已提供这些 perk 的可执行内容定义。Overflow 的基础容量倍数和转移规则来自 C160 / C250 的组合解释，具体顺序仍需验收；来源有问号的参数保留未知。

### Clown Cartridge 的随机溢出与验收边界

固定快照 Weapon Perks A47 / C47 / C250 提供触发、普通 / 强化范围、上取整及 Reload / Refill 术语；2026-10-10 再次读取 [在线原表](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit?gid=1662574278#gid=1662574278)，对应 A48 / C48 的描述相同。原表坐标与固定 CSV 坐标分开记录，不改写既有快照。

[clown_cartridge.json](../common/src/test/resources/effects/clown_cartridge.json) 监听同持有者的本武器 reload_finished，执行一次 sample_random，再以 `ceil(该次换弹有效容量 × (1 + 随机增幅))` 为本次 refill.ceiling。额外子弹继续从有限储备转移，不生成弹药，不再次发布 reload_finished；基础容量保持不变，已装入的溢出弹数在收枪或容量 Buff 到期后保留。普通范围 0.10～0.50，强化下界 0.13，由装备来源 enhanced 标签选择。

当前分布明确采用 uniform_real 的 `[lower, upper)`，这是**内容假设**：原文给出范围和均值，没有证明连续 / 整数百分比、步长或权重，不能以 30% / 31.5% 的均值代替抽样，也不能把现有均匀模型视为完成校准。固定种子仅用于测试和复算；生产运行时生成服务端种子。

先完成基础换弹，再使用完成事实中的 capacity 快照计算溢出；若剩余储备不足，只装入实际剩余发数，储备已经耗尽仍记录该次抽样但不产生额外 refill 事实。拒绝或取消的换弹、普通 refill 不抽样。允许下一次合格换弹重新抽样；当前手动宿主要求弹匣低于有效基础容量才开始换弹，因此满弹或已有超额弹药不会靠重复输入重抽。

8 项纯核心测试覆盖持有者移交后的旧事实拒绝、普通 / 强化下界、动态容量、再次抽样、两实例、有限 / 无限储备、补弹与取消隔离、未知结果保留；3 项共享游戏测试把真实物品的词条选择、普通玩家换弹命令和真实 tick 接起来，检查最终弹数 / 储备以及以实际转移量驱动的世界治疗。另修正 DSL arithmetic 的规范十进制加乘后再输出 double，使 `ceil(100 × 1.1)` 为 110，`ceil(50 × 0.28)` 为 14；真正高于整数的量仍向上取整，不使用容差吞掉分数。

覆盖为 partial。除随机分布外，上述基础换弹 / 溢出阶段、容量快照、基础容量不变及多种 on-reload 效果的顺序仍需原作交互验收。技能换弹、完整 D2 原型、弹药存档与 HUD 尚未完成；clown_weapon.json 的 5 发初始容量与 0.2 秒换弹为合成夹具。

### 动态基础容量与溢出

还要区分临时溢出上限与**改变基础容量**。C364 Fail-Deadly、C409 Timelost Magazine 明确指出基础容量变化还影响其他按弹匣计算的效果，并能与 Overflow 叠加。现有 capacity_profile 已从固定输入、当前来源和 Buff 求有效基础容量，默认补弹 / 生成上限及按容量百分比统一读取它；refill.ceiling 另表达这一次的溢出上限。加成结束后不需要把容量改回旧值，也不删除已装弹数。具体 DSL 见 [动态基础容量](engine-data-packs.md#动态基础容量与数值快照)。

两项已加入需求审阅，仍为 unimplemented。Fail-Deadly 需要每个伤害实例叠层、武器击杀清层、收枪保留、普通武器与火箭 / Bipod 的特殊层数映射，以及至多 +100% 基础容量、射程 / 辅瞄；强化属性中有 `?`，完整曲线也不能从最大值猜测。Timelost Magazine 需要超能结束时 refill、+100% 基础容量持续 20 / 强化 21 秒、Found Verdict 每次装两发，武器击杀额外回超能的 `1?% / +?%` 仍待核对。两者对 Bolt Charge 等按容量效果的交互也需要内容定义。

`ammo_capacity.json` 的 5 发输入、200 ms 翻倍和两倍补弹上限只验证机制。实际加成 / refill 的先后、过期后已有子弹的原作策略、各容量阶段的取整、逐发装填 / 热量 / 特殊射击模式、原型容量随武器配置变化及完整存档同步仍待校准和接线，不能据此宣称两个词条已完整实现。

## 乘区分组

| 组 | 规则 | 例外处理 |
| --- | --- | --- |
| empowering | 通常取最高 | Well 的 25% 覆盖 Radiant 对勇士的 30%，先按优先级选来源（`Solar!D6`） |
| global_debuff | 通常取最高 | Deadfall 等特殊来源和持续时间刷新策略单独定义 |
| weapon_surge / element | 同元素各来源先求档位，再取有效最高档 | 金装与模组不各乘一次；各自计时继续独立运行 |
| weapon_perk | 合格的独立效果相乘 | 先检查分量资格；Kill Clip 不提高爆炸 perk 伤害（`C132`） |
| melee_bonus | 相加，可嵌套互斥组 | One-Two Punch 与冰冻非 Boss 近战增伤取最高（`C154`） |
| activity_surge_or_overcharge | 只取一个有效效果 | 与 armor weapon_surge 分组不同，可以同时存在 |
| resist | 同家族先归约，独立家族的剩余伤害比例相乘 | 同种模组 1 / 2 / 3 份是 15% / 25% / 30%（`Armor Mods!J7`） |

这些类别以 Court 当前表与 Compendium 为基线，来源见「数值资料与冲突」。不能把“金装”“神器”“对冰冻目标”笼统定义为永远独立相乘；具体效果必须指定组和资格。碎冰、点燃是新的伤害动作，使用自己的 Profile，不自动继承一个所谓“对冰冻目标乘区”。

## 武器伤害 Profile

普通武器分量的模板如下；不适用的项为 1，特殊攻击可选择不同步骤：

```text
D_out = B_normalized × M_environment × M_weapon_stat
        × M_precision × M_falloff
        × M_empowering × M_weapon_surge × M_weapon_perks
        × M_vulnerability × M_other
```

- `B_normalized`：明确口径的基础分量，不是随便找一个 Boss 上显示的伤害数字。
- `M_environment`：命名的活动、光等、目标类型等因子；输出与承伤使用不同表。活动伤害数字缩放与生命值使用同一单位。
- `M_weapon_stat`：按 Weapons 属性、弹药类别、目标 rank / class 取值，不是所有目标都统一加 15%。
- `M_precision` / `M_falloff`：每分量分别计算，可有目标弱点、距离及爆炸半径曲线。
- 其余贡献按资格及分组树归约；一个因子只能在其指定阶段应用一次。

基础值记录测量目标、活动、光等、已包含因子与归一化方式。实测数字已含某个倍率时，应先归一化或明确标记 absorbed，不能重复应用。Mossy 的资料用于校准这种分离，旧版光等公式不硬编码为全局规则。

验收例：普通 PvE、基础值 1000、无额外属性或环境倍率，Radiant 20%、三份匹配 Surge 22%、Kill Clip 25%、Weaken 15%，且该分量满足全部资格：

```text
1000 × 1.20 × 1.22 × 1.25 × 1.15 = 2104.5
```

数值依据：`Solar!D6`、`Armor Mods!M15`、`Weapon Perks!C132`、`Void!D11`。计算轨迹必须能列出每个倍率的来源，以及为何排除了不适用的其他贡献。

## 近战与混合技能 Profile

普通 PvE 近战：

```text
h(S) = clamp(S - 100, 0, 100)
M_melee_stat = 1 + 0.003 × h(Melee)
D_melee = B × M_melee_stat × (1 + SUM(eligible_melee_bonuses)) × M_external

melee_bonus: sum
 ├─ 当前有效的近战增伤，例如督军印记或督军之怒
 ├─ max
 │   ├─ one_two_punch
 │   └─ frozen_non_boss_melee_bonus
 └─ 其他合格贡献
```

近战增伤加算，Melee 属性作为独立基础伤害提升；近战增伤不自动缩放该攻击产生的 Scorch / Ignition（Bungie 2025-06-19）。冰冻的上述近战加成已经进入子组，不再在目标阶段重复乘入。PvP 系数使用单独数据。

常见 PvE 的增强属性增量，按来源 Profile 选择：Melee 为 `0.003×h(S)`（`Game Mechanics!E29`），Grenade 为 `0.0065×h(S)`（`E35`），Super 为 `0.0045×h(S)`（`E41`）。Class 属性超过 100 的强化效果是施放职业技能时获得 overshield（`E42`），不是伤害增量；职业技能攻击是否按手雷属性增伤目前没有来源，记为 assumed 待核。Well / Ward 的超能属性加成改的是持续时间而不是伤害（`E41`），不能仅凭 super credit 强行套伤害增量。

抓钩近战对 Boss 使用专用 Profile（Bungie 9.7.0）：

```text
M_grapple_boss_stats = 1 + 0.003×h(Melee) + 0.5×0.0065×h(Grenade)
Melee=200, Grenade=200 → 1 + 0.30 + 0.325 = 1.625
```

减半的是手雷属性的增伤部分；两种属性增量相加。不能因为 credit 同时是 grenade / melee，就自动套两个相乘的普通 Profile。对其他目标和特殊分量另选明确规则。

## 资源回能

基线规则采用 2026-06 的社区研究与 9.7.0 补丁。令 `s=clamp(stat,0,100)`，当前社区表的回能倍率为：

```text
F(s) = 1 + 0.625 × (1 - cos(π × s / 100))
F(0)=1；F(70)≈1.992365783；F(100)=2.25

ΔE_chunk = q0 × F(s) × CES_recipient × CMS_trigger × M_other
```

q0 是当前版本在 0 属性下、以一份充能为单位的基础值。CES 是接收技能的 chunk scalar，CMS 是某些职业技能触发模组额外使用的系数；未参与的因子为 1。以 q0=0.04、其余系数为 1 为例，100 属性收益为 0.09。不要把已经在参考属性下测得的 9% 再乘 2.25。

Clarity 2024 文章只用于理解 CES / CMS 与例外结构，旧数值不直接当当前值。

Engineeeer 原始计算表中的被动曲线如下。它们是社区拟合，不是官方精确函数，记录为 fitted；端点按分支选取，不擅自消除拟合产生的细小跳变：

```text
Grenade / Melee:
  0 <= s < 70:
    P = 1 + 0.004273626*s + 0.000300195*s² - 0.000000637618*s³
  70 <= s < 100:
    P = 2.10898698 + 0.00639461*s
  s = 100: P = 2.75

Class:
  0 <= s < 70:
    P = 1 + 0.009567518*s + 0.00000731402*s² + 0.00000110491*s³
  70 <= s < 100:
    P = 1.310897 + 0.011073*s
  s = 100: P = 2.42
```

Super 使用独立 Profile：属性不缩短其基础被动冷却；造成伤害、受到伤害、击杀、拾球分别按已提交的事实计算主动收益。具体收益系数、漫游超能系数和目标修正必须逐条校准，不能拿上述 chunk 公式代替完整 Super 生成机制。

## Cure 恢复与冷却

本地 2026-10-05 Compendium 快照 `Solar!D4` 记录：Cure 每级恢复 60 HP（PvP 为 30），恢复过程为 0.1 秒，激活冷却为 1 秒；冷却期间再次激活不恢复生命。

可执行示例 [cure.json](../common/src/test/resources/effects/cure.json) 已覆盖 x1 / x2 / x3、活动规则集的 PvE / PvP 分支及重复激活冷却。测试采用 0.1 的绝对量缩放：PvE 总量为 6 / 12 / 18 Minecraft HP，PvP 为 3 / 6 / 9；这不是已经校准的全局玩法缩放因子。恢复在激活后 50 ms、100 ms 各执行一半，冷却从激活时开始。**两次等量恢复是 Chorus 的实现选择，快照没有给出更细的原作恢复曲线。**

示例由明确的 Cure 请求驱动，已通过纯核心及双加载器真实服务器 tick 验证；凤凰俯冲等技能和装备来源尚未自动装配。

## Restoration 与 Healing Rift

本地快照 `Solar!D7` 的 Restoration x1 / x2 分别恢复 35 / 50 HP/s，PvP 为 17.5 / 25；恢复不可被打断，默认可延长至 15 秒，重新施加恢复历史最长持续时间并保留最高强度。`Class Abilities!D15` 的 Healing Rift 为 40 [PvP 35] HP/s。快照明确两者的治疗不叠加。

[restoration.json](../common/src/test/resources/effects/restoration.json) 使用 0.1 的测试缩放和 `health_recovery` 声明，已验证强度变化时分段积分、历史刷新、被覆盖来源继续计时，以及获选来源到期后重新选择。例如 PvE 下 Restoration x1 持续 0.2 秒，Rift 同时存在 0.070001 秒，示例治疗量为 `4×0.070001 + 3.5×0.129999 = 0.7350005` Minecraft HP；最终有效回血仍受生命上限及原版 float 精度约束。

**同优先级取较高速率是当前示例的暂定覆盖规则。**快照只证明“不叠加”，未证明原作总是取高；需补原作优先级证据后再校准数据。引擎支持显式 priority 覆盖。连续恢复使用对齐的 50 ms 采样边界并积分到期残段，双加载器真实 tick 已验证 70.001 ms 的恢复过程不会丢失最后 20.001 ms；这不证明原作也使用同样采样频率。

[healing_rift.json](../common/src/test/resources/effects/healing_rift.json) 与 restoration.json 链接后，已将该恢复定义用于固定范围场：捕获一次位置、5 米范围、15 秒生命周期，按 50 ms 采样对原版友方与自身授予来源独立的 presence；离开、主动销毁或到期清理。新进入者的恢复从观测时刻开始，场到期不会让其额外保留 15 秒。同一持有者的不同施放保存不同位置，重叠时沿用恢复通道；该输入要求宿主提供每次施放独立的来源身份，尚未接技能按键或成本。

官方 [9.5.0](https://www.bungie.net/7/en/News/Article/destiny_update_9_5_0) 明确把 Rift 半径改为 5 米，允许施放时移动，并记载 PvP 治疗降低 10%；[9.7.0](https://www.bungie.net/7/en/News/Article/destiny_update_9_7_0) 又修复了部分地形下生成到地下的问题。本内容仍以固定快照的 40 / 35 HP/s 为数值基线；官方相对改动没有在此证明绝对终值，不在 35 上重复乘 0.9。脚底点代替地面接触点、球形范围、50 ms 采样与 0.1 生命缩放均需继续校准，不把这两份更新说明视为对所有当前数值的完整复核。

同一快照 `Class Abilities!D15` 还声明：满血时生成 3 Overshield HP/s，上限 15；Void Overshield 阻止生成；可与其他护盾并存，层次按 FIFO；处于 Rift 中可获得具有护盾的词条交互资格。上述坐标均为 CSV 快照坐标。

healing_rift.json 已用 `restore_shield` 接入每 50 ms 的离散补盾：观测到实体存活且满血、没有带 Void Overshield 标签的正容量护盾时，补充 0.015 Minecraft HP，上限 1.5。多个 Rift 对同一受益者共享一个池和计时器；受伤 / Void 盾阻止后续生成，但不删除既有容量，不累计受阻期间的额度。presence 的资格标签与护盾正容量分开，尚未生成护盾时也可以满足相应词条条件。双加载器已验证护盾实际承伤、溢出进入原版生命、恢复后补盾、重叠池、Void 阻止与 Absorption 独立；测试中的 Void 层是合成内容，未实现完整 Void Overshield 数值。

**这仍是部分内容，脉冲和生命周期策略需要校准。**本次采样刚恢复满血也会得到一个完整脉冲，尚未按满血的实际持续时间分段积分；首次补盾时刻、最后一个场退出就删盾、空层创建即占 FIFO 顺序，以及重叠池保留首个来源，均不是上述快照完整规定的时序。当前行为已测试并显式记录，不能据此宣称复刻完成。

固定位置不依赖施放实体继续存在；当前友方筛选却仍需在原维度解析该实体。示例选择在阵营参照缺失时结束场，不能当作原作死亡 / 离线策略；保存阵营及跨维度生命周期尚未完成。尚未覆盖 Phoenix Dive 在 Healing Grenade 后的 4+2 秒例外、真实技能施加、118.7 秒基础冷却与 0.5 主动回能系数装配，以及施放动画的 20% 减伤和移动。

## Under-Over 的护盾专属增伤

固定 CSV 快照 `Weapon Perks!A239 / C239` 给出以下直击增伤，排除 Explosive Damage Perks；这里的坐标不是原在线表的保证行号：

| 接收对象 | 普通 / 强化倍率 | 查询位置 |
| --- | --- | --- |
| 战员元素盾、Barrier Champion 屏障 | 1.5 / 1.55 | 当前护盾层 |
| 战员 overshield | 2.25 / 2.4 | 当前护盾层 |
| Guardian overshield | 1.2 / 1.22 | 当前护盾层 |
| 具有 Woven Mail 的 Guardian，躯干命中 | 1.2 / 1.22 | 全局武器伤害 |

官方 [7.2.0.1 更新](https://www.bungie.net/7/en/News/article/season-witch-update-7-2-0-1) 支持 Woven Mail 躯干分支、提高战员护盾增伤，以及对 Dark Cabal / Lucent Moth overshield 更高增伤的分类。它没有给出此表的绝对百分比、强化值或跨层溢出算法，不能当作这些数值的独立验证，也不证明后续版本从未修改。

[under_over.json](../common/src/test/resources/effects/under_over.json) 在攻击的 `shield_scaling_profile` 中按当前 layer_tag 选择倍率；Woven Mail 分支在全局 weapon Profile 中检查 victim 的 Buff 标签及明确的 Guardian / bodyshot 标签。两种活动模式均按实际声明类别选择，PvE 模式本身不会把所有目标视作战员。普通 / 强化、具体武器与所有者相互隔离，来源侧快照冻结后仍可命中新获得的不同类型护盾。

[shield_scaling.json](../common/src/test/resources/effects/shield_scaling.json) 使用合成容量和局部减伤验证引擎：20 点输入依次通过容量 3 / 本地倍率 1.5 的元素层、容量 4.5 / 减伤倍率 0.3 / 攻击倍率 2.25 的 overshield，剩余预算为 `20 − 3 / 1.5 − 4.5 / (0.3 × 2.25)`，再交给原版护甲、Absorption 与生命。9 项纯核心测试及 3 项共享世界场景通过；这证明 Chorus 契约的一致性，不能反推原作的全部跨层行为。

覆盖保持 partial：全局 Woven Mail 分支与护盾分支并存时的叠加、MAX 分组、盾标签分类和跨层预算都是待校准的内容选择，计算贡献保留 assumed 标记。完整 Woven Mail 减伤、实际勇士屏障 / 弱点、武器弹丸 / 爆炸分量分类、真实装备和 Guardian 身份自动装配尚未实现。合成 Woven Mail Buff 仅用于资格查询，未实现其完整内容。

## Eternal Warrior 护盾回充

固定 CSV 快照 `Exotic Armors!D34 / F34` 记录：Fists of Havoc 施放时获得 75 HP 护盾，护盾仍存在时，停伤 5 秒后按 7 秒满量回充；另外还有精准伤害处理、Arc Weapon Surge 和击杀延长超能等机制。官方 [2025-01-23 更新预览](https://www.bungie.net/7/en/News/Article/twid-01-23-25) 明确新增 Fists of Havoc 击杀延长超能；[官方物品描述](https://www.bungie.net/en/Explore/Detail/DestinyInventoryItemDefinition/1643575148) 也保留施放获得护盾与超能结束后 Arc 增伤。上述官方来源未给出回充数值，不把它们当作对 75 / 5 / 7 的独立验证。

[eternal_warrior.json](../common/src/test/resources/effects/eternal_warrior.json) 接入护盾部分：0.1 缩放后初始容量 7.5，最大值取定义初值，恢复率为 `7.5 / 7` HP/s 的有限 double。任何持有者实际 damage_taken 都把 ready 关闭，替换 5 秒一次性计时器；到期后才开始积分，下一次受伤重新计时。护盾自身破坏时按 definition / generation / victim 匹配移除层和定时器，不能因容量归零后经过足够时间自行回满。显式超能结束和死亡也清理该层，来源解绑后仍可以接收结束输入。

9 项通用回充测试、3 项效果内容测试与两端真实服务器场景验证了段末残量、停伤重置、同一层容量承伤、护盾破坏及剩余伤害进入原版生命。快照的“7 秒回充”在此解释为线性的每秒满容量 / 7；精细恢复曲线、任何层实际损失都重置计时、破盾移除和超能结束清理等生命周期选择仍需原作校准，不能凭测试通过视作原作测量。

该层现已声明 `excluded_attack_factors: [chorus:precision]`。快照中的 negates Precision Damage 在此解释为“这层不承受被明确标记的精准增伤”，保留躯干伤害、其他增伤与命中身份。攻击 Profile 必须显式标记精准乘法阶段；仅有精准标签或阶段名不能反推已经应用了多少倍率。[precision_damage.json](../common/src/test/resources/effects/precision_damage.json) 提供合成武器及防御数值，以保存的贡献重算排除精准后的数值，验证后续固定加值、另一普通盾、原版护甲 / Absorption 和延迟命中。护盾专用 Under-Over 与层减伤也可同时参与。

**精准抑制及破盾溢出的精确行为仍需原作校准。** 当前按照 Chorus 比例预算解释：本层用排除因子后的数值消耗容量，破盾后剩余预算继续保留原攻击基准；原版已经接纳的格挡 / 无敌帧部分按比例换算，不再模拟一遍原版命中。官方物品描述和上述更新预览不足以独立验证这一算法。精准碰撞 / 弱点判定、原版暴击自动映射与完整分量聚合仍未实现。

当前只消费宿主明确的 test:fists_start / test:fists_end，尚未实现真实装备 / 超能施放、2–3 次 Arc 击杀的等级门槛与 Surge、击杀延长超能、结束后 30 [PvP 6] 秒增伤。完整 Precious Scars 与 Shielded Bane 也尚未装配；通用 shield.recovery 能表达其持续回充，不代表其范围、破坏与来源联动已经验收。

## Bolt Charge 的计数与中心放电

独立内容投影：[bolt_charge.json](../common/src/test/resources/effects/bolt_charge.json)。来源为固定快照 Arc B5 / D5；2026-10-10 重新读取 [在线原表 Arc D7](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit#gid=618967225&range=D7)，相关文本一致。[Bungie 的机制说明](https://www.bungie.net/7/en/News/Article/twid-01-16-25)确认先取得至少一层、武器伤害继续积累、每层回近战能量和十层后技能触发；该历史公告不替代当前数值表。

状态存储上限 10 层，外部效果可直接 `grant_buff: chorus_d2:bolt_charge`，或由宿主发送 `chorus_d2:grant_bolt_charge`，在 numbers.stacks 提供 count。系统监听自己的 buff_gained 事实，按回执 credited × 0.025 回复 `chorus_d2:melee`；不是按实际存储增量计算。因此 x9 获得 x4 存到 x10，但回能 10%；已经满层时再获得收益层仍有回能。这里使用基础一格近战账户，未加入完整技能回能修正。

至少有一层且未满层时，武器实际伤害先累计 `floor(0.15 × capacity) + 1` 次，然后由下一次任意来源的有效伤害加一层。剑与榴弹发射器阈值为 2，三发弹匣狙击为 1。容量从该事件实际武器读取，不用状态初始来源的武器，也不用当前弹数或溢出弹数；类别读取事件 source.tags。当前明确选择在每次计入武器伤害时更新容量阈值，支持已有容量 Profile。计数窗口在每次武器进度后续到五秒，区间半开，换枪清计数而保留 Bolt Charge。按 damage_id 避免窗口内重复累计，按 batch_id 限制每批最多一次加层。

放电规则先检查命中前是否已有十层，再消费并安排 0.5 秒后的两段中心伤害；刚获得第十层的那次命中不同时放电。技能 hit 与造成实际损失的普通近战可触发；表列的 Volatile、非 Boss 自动碎裂的 Freeze Shatter、Tangle、Threadling、Unravel、Boss Suspend Snap 和 Forerunner's The Rock 使用明确事件标签。`proc_key: chorus_d2:bolt_discharge` 单独接受派生伤害排除，不阻止已经备妥的普通加层逻辑。消费后其他同批命中不会重复安排放电。

合成世界中 D2 伤害采用 0.1 比例映射：PvE 中心两段 40.5 + 27，PvP 两段 3.6 + 3，属于同一逻辑批次的通用 Arc 伤害，不带技能 / 武器伤害或击杀信用。16 项纯核心测试和 5 项共享 GameTest 覆盖实际原版命中、溢出回能、两模式真实 tick / 扣血、显式排除、未知世界结果保留已消费层数及不重放。

**仍为 partial。** 原表半径为 `?`，当前只作用于原始受击目标，尚无周围目标覆盖、两段空间分布或衰减。下一任意伤害要求正有效损失（含 Absorption）、同批后续分量可完成阈值但最多加一层、五秒窗口也约束备妥后的下一击，均是待原作校准的当前策略。触发标签和同时批次由宿主明确声明；Boss 自动碎裂用单独标记排除，非技能 Scorch / Ignition、Crystal 与其他明确禁用来源须保留正确分类 / proc 策略。Rolling Storm 已作为装备词条接入下述真实流程；Storms Keep、其他片段和装备授予、子职业装配、跨重启恢复和活动迁移尚未接入。它不能被当成完整 Bolt Charge 范围效果。

## Rolling Storm 与 Bolt Charge 联动

[rolling_storm.json](../common/src/test/resources/effects/rolling_storm.json) 与 Bolt Charge 片段链接，复用现有条件、计数表达式和 grant_buff；核心未增加词条专用分支。固定快照为 Weapon Perks A190 / C190；2026-10-10 读取[公开原表 A191 / C191](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit#gid=1662574278&range=C191)，文本一致。

| 击杀时的状态 | 普通版获得 | 强化版获得 |
| --- | --- | --- |
| 无 Bolt Charge，无 Amplified | 1 | 2 |
| 无 Bolt Charge，有 Amplified | 2 | 3 |
| 已有 Bolt Charge，无 Amplified | 1 | 1 |
| 已有 Bolt Charge，有 Amplified | 2 | 2 |

只处理自己这把武器的 weapon_kill，不能仅凭伤害来源中保留的 weapon 字段授予。Amplified 读取持有者当前 Buff 标签；强化读装备来源标签。所有武器向持有者的同一个 Bolt Charge 状态授予层数，回能只由系统的 buff_gained 监听器结算，因此武器击杀和命中计数可以各自贡献，但同一次获得不会回能两遍。满层继续获得仍按 credited 回能，强化版不会把满层视为“未激活”。

当前声明 equipped 激活和默认 current_owner_bundle 策略：飞行过程中收枪仍可由原武器击杀触发，卸下词条则不再响应。两者及读取命中时 Amplified 的时机，是显式内容选择，仍需原作边界校准。没有额外增加 Arc 子职业限制。

7 项 RollingStormTest 验证条件矩阵、Amplified 精确到期、外来来源隔离、另一把武器、收枪 / 卸装、溢出回能、先 hit 后 kill 的组合顺序，以及放电后重新开始下一轮。3 项共享 RollingStormGameTest 使用真实玩家容器、普通 fire / reload / ability 命令和实体死亡，验证七次合成武器击杀达到十层、普通近战消费后延迟放电击杀，以及收枪期间强化 Amplified 击杀和无 weapon_kill 信用的反例。

**仍为 partial。** `rolling_storm_weapon.json` 中五发弹匣、10 点伤害、射速 / 换弹时间及一秒 test:amplified 均为验收输入。Amplified 的实际获得、完整持续时间和其他收益未实现；系统来源由测试宿主显式安装，尚无玩家系统自动装配。Bolt Charge 的空间范围、数值映射与迁移缺口仍适用。这些测试资源未进入发布 jar。

## Jolt 的归属与施加顺序

本地快照 `Arc!D8` 给出：状态持续 10 [PvP 5] 秒，重施加刷新；累计伤害阈值 115 [45]，放电伤害 119 [51]，范围 12 米，放电间隔至少 0.8 秒。一般施加击计入阈值；Guardian 作为放电中心时，只有该次连锁对其他 Guardian 造成伤害才会受连锁伤害；Jolt 伤害可眩晕 Overload。冷却期伤害是否储存、超过阈值后的余量如何处理，在该单元格中没有明确说明，仍待校准。

施加者与放电伤害归属必须分开。Bungie [7.1.0 更新](https://www.bungie.net/7/en/News/Article/season-deep-update-7-1-0) 明确修正为触发放电的攻击者拥有该伤害；Compendium `Weapon Perks!C243` 则明确 Voltshot 的 Jolt 不算武器伤害。这两项分别约束 origin 与 credit，不意味着放电自动吃触发武器的全部增伤。

施加击是否计入也不能硬编码为所有来源统一行为。Bungie [8.0.0.1 更新](https://www.bungie.net/7/en/News/article/destiny_2_update_8_0_0_1) 将 Touch of Thunder 的 Lightning Grenade 改为第一次伤害事件后施加 Jolt，首次爆发因此不会立刻触发放电。内容须显式选择施加及初始化顺序；该历史来源例外仍需随目标版本复核，不能覆盖工作簿中的一般规则。

引擎现有 `origin:event` 可让派生伤害归触发者，而不改写目标状态的施加记录；[action_origin.json](../common/src/test/resources/effects/action_origin.json) 及双端测试验证这项能力。其中 4 点基础、50% / 100% 修饰与伤害后回血都是合成验收，不是 Jolt 的缩放规则。

[jolt.json](../common/src/test/resources/effects/jolt.json) 已有独立的部分内容定义：

| 参数 | 快照 PvE / PvP | 当前 Minecraft 夹具 |
| --- | --- | --- |
| 累计阈值 | 115 / 45 | 11.5 / 4.5 |
| 每次链伤 | 119 / 51 | 11.9 / 5.1 |
| 状态持续时间 | 10 / 5 秒，重施加刷新 | 不变，刷新保留累计和首次施加来源 |
| 范围 / 冷却 | 12 米 / 0.8 秒 | 12 格球形 / 每目标 0.8 秒 |

施加击初始化和已有状态监听按 damage_id 去重；`test:jolt_after_damage` 明确选择首次施加不计入的顺序，刷新已有状态时该击仍由旧状态计入。达到阈值后先挂冷却，再依次处理周围实体的实际伤害回执，最后判断中心：非玩家可以受链伤；玩家只有在另一玩家实际损失 HP、Chorus 护盾或 Absorption 后才会受链伤。纯核心验证免疫、格挡、取消、失败和零损失均不满足条件；双端测试验证真实玩家取消及 Absorption 损失。这一类别判断独立于规则集的 PvE / PvP 模式。

链伤使用触发事件的完整来源，不复制武器伤害 / 击杀标签，也未配置武器攻击 Profile。两个相邻 Jolt 可在同一因果链内互相触发，各目标冷却阻止这一状态在同刻重复放电。已有状态的致死一击达到阈值时，hit 规则先完成邻居链伤，随后 death 事实移除状态；死亡本身和自然到期不额外放电。新状态施加需要存活资格，首次致死施加暂不创建状态或放电，该边界尚待原作校准。

Jolt 链伤另声明 `proc.deny: ["chorus_d2:bolt_discharge"]`，只排除 Bolt Charge 的放电反应，不抹除真实伤害、一般命中事实或其他合格效果。依据为固定 CSV 快照 Arc D5；2026-10-10 核对 [在线原表 Arc D7](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit#gid=618967225&range=D7)，排除列表相同。该列表也区分非技能来源的灼烧 / 点燃与水晶碎裂，不能泛化为所有元素派生伤害都禁止后续触发。

新增纯核心与双加载器场景用相同 proc_key 的接收探针验证：原生触发击能进入放电探针，两次实际 Jolt 链伤只进入其他探针；两种活动模式的实际扣血保持原值。探针本身不是完整 Bolt Charge；后续已加入独立 Bolt Charge 内容与中心放电验收，空间范围仍缺失，见上节。Jolt 继续标 partial。

Bolt Charge 已实际使用 batch_id 限制每批加层，范围与分量边界仍按上节标记为 partial。批次身份不改变 Jolt 的按 damage_id 累积规则，也不改变 One-Two Punch 的逐命中消费。

**0.1 单位缩放、触发后累计清零 / 不保留超额、冷却内伤害丢弃但记录身份、刷新保留首次来源，均为当前明确的内容选择。** 阈值暂计实际 HP + Chorus 护盾损失，不含 Absorption；玩家链伤资格则包括实际 Absorption 损失。关系过滤暂依赖仍在本维度的原施加者；来源缺失或跨维度导致查询 unavailable 时，本次不发出任何链伤，但已启动的冷却保留。查询无视线过滤，按原版 not_allied 处理阵营。

完整 D2 数值投影、目标等级 / Guardian 分类、冷却累计及余量证据、多来源及离线阵营、Overload 眩晕、真实武器 / 技能施加意图与正式伤害类型仍待完成。夹具只有测试宿主赋予上述 test 标签，并将伤害类型映射为测试用的绕过原版受伤冷却类型；没有全局绕过原版保护。覆盖状态为 partial。

## Voltshot 的两个窗口与共享 Jolt

本地 CSV 快照 `Weapon Perks!C243` 给出：武器击杀后 5.3 秒内完成换弹，获得下一次武器命中施加 Jolt 的状态；普通持续 7 秒，强化 8 秒。击杀后的换弹窗口和就绪状态都保留到收枪之后，Jolt 不计为武器伤害。2026-10-10 核对[在线原表 C244](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit#gid=1662574278&range=C244)，文本与固定快照一致；在线坐标不替换快照坐标。

[voltshot.json](../common/src/test/resources/effects/voltshot.json) 作为程序片段与共享 Jolt 链接后，按持有者和武器实例分别保存这两个计时器。击杀需要武器击杀信用，击杀 / 换弹 / 命中都同时检查 owner 和 weapon；武器来源身份不自动赋予武器信用。收枪保留状态并继续计时。边界遵循引擎的半开区间：5.3 秒时窗口已失效，7 / 8 秒时对应就绪状态已失效。

当前按原文“在击杀后窗口内完成换弹”解释：换弹授予 / 刷新就绪状态，但不消耗或延长击杀窗口；因此同一窗口内再次完成合格换弹可重新就绪，窗口到期后则不能。新武器击杀刷新窗口。这个重复换弹策略是当前明确的内容解释，表格未单独解释其边界，仍待实测校准。

下一次具有武器伤害信用的 hit 先消耗就绪，再请求 Jolt 资格，成功后发布初始化计数事件。施加击进入共享 Jolt 累计，已有 Jolt 的监听与初始化按 damage_id 去重。被取消 / 失败的伤害不产生 hit，所以保留就绪；immune / blocked 仍是 hit，当前会消耗并尝试施加状态。目标拒绝、缺失或死亡时就绪仍已消耗；致死武器命中随后可正常开启新击杀窗口。同一批多个目标按事实顺序只消耗第一击；Voltshot 已接入实际容器的手动换弹与单次物理开火，多弹丸的具体资格策略仍未验收。这些失败和批次边界也属于待校准的内容策略。

9 项纯核心测试和 4 项共享游戏测试验证了两窗口、普通 / 强化、武器 / 持有者隔离、收枪、重载、施加 / 刷新累计、其他人触发后的归属、真实取消与死亡，以及 **Jolt 实际击杀不会重新开启 Voltshot 的武器击杀窗口**。真实 tick 验证两个就绪时长分别到期。原有游戏测试由宿主显式提供武器身份和 reload_finished，保留为时序及两种活动模式的对照。

新增 [voltshot_weapon.json](../common/src/test/resources/effects/voltshot_weapon.json) 模块把两把独立武器的词条选项、弹药、手动换弹及物理开火连接到共享 Voltshot / Jolt；所有身份与击杀、换弹、命中事实来自现有容器和世界回执。5 项 VoltshotWeaponTest 检查普通 / 强化、完成换弹的精确 5.3 秒边界、切枪取消、飞行归属、武器信用和未知状态回执。4 项共享 VoltshotWeaponGameTest 使用真实玩家低权限命令、实际弹药转移和飞行碰撞；两类测试合计验收：

- 另一把武器完成换弹不会借用击杀窗口；已满弹匣的拒绝不会重新授予就绪。
- 击杀后换弹，开火再立即切枪，旧武器弹丸仍消耗该武器的就绪并施加 Jolt；普通伤害继续推动 Jolt 实际链伤和击杀，链伤不刷新武器击杀窗口。
- 换弹完成前切枪会取消装填，保留击杀窗口但不凭开始换弹授予效果；之后真正完成的换弹才生效。
- 只有武器身份而没有 weapon_kill 信用的真实击杀不能启动窗口。施加状态结果未知时，已扣弹药、实际伤害和已消费的就绪不回滚，停止后续推导且不重放。

覆盖状态为 partial。共享 Jolt 的数值 / 勇士 / 来源缺口仍然适用；实际独立容器、手动换弹与单次物理开火链已验收；夹具弹匣 5 发、初始 1 发、20 m/s 弹丸、0.15 秒间隔、0.2 秒装填及 10 点基础伤害均为合成参数，未代表任何 D2 武器原型。多弹丸事务、技能换弹及玩家死亡 / 卸下装备时的内容清理策略尚未完成。Java 片段链接与数据包 imports 均已实现；共享 Jolt / Voltshot 通过真实数据包重载后的两模式实际链伤验收。完整 D2 武器原型、独立内容注册表和默认自动装配仍待接入。

## Volatile 累计与爆炸

本地快照 `Void!D9` 与可执行夹具 [volatile.json](../common/src/test/resources/effects/volatile.json) 的对应如下，PvE / PvP 由活动规则集决定：

| 参数 | 快照 PvE / PvP | 当前 Minecraft 夹具 |
| --- | --- | --- |
| 后续伤害引爆阈值 | 190 / 200 | 19 / 20 |
| 最大爆炸伤害 | 145 / 80 | 14.5 / 8 |
| 持续时间 | 10 / 6 秒 | 不变 |
| 爆炸半径 | 5 米 | 5 格 |
| 引爆后再次施加冷却 | 1 秒 | 不变，按目标共享 |

施加击不计入阈值；达到阈值或死亡时引爆。首次施加击本身致死也有独立资格检查和爆炸路径。已通过纯核心及双加载器真实命中、实际范围扣血和相邻 Volatile 连锁测试，爆炸的伤害 / 击杀资格显式配置为虚空技能来源。

**伤害与阈值的 0.1 缩放是测试内容选择，尚未确定全局玩法因子。** 快照给出最大伤害和范围，没有给出完整距离函数；夹具暂用中心为最大、5 米为零的线性衰减。累计暂取实际 HP 与 Chorus 护盾损失之和，不含 Absorption；该投影及目标等级修正尚未完成原作校准。已有状态不刷新并保留首次施加归因，也是当前明确的内容选择，不能视为完整多来源重施加规则的验证。

真实武器 / 技能施加意图、正式伤害类型、状态免疫、阵营与离线来源快照仍待装配，覆盖状态保持 partial。

## 数值资料与冲突

本次核查日期为 2026-10-09，资料基线如下；引用仅证明数值和研究结论，不声称复原了 Bungie 内部全部实现。

| 来源 | 用途与适用边界 |
| --- | --- |
| 本地 `Destiny Data Compendium - data copy 2026-10-05.xlsx`，[原始工作簿](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit) | 22-sheet CSV 数据快照，文中坐标指本地快照；不保留原版公式、图片和布局；OLD 为历史资料。保存日期不等于逐条验证日期 |
| [Bungie 9.7.0，2026-06-09](https://www.bungie.net/7/en/News/Article/destiny_update_9_7_0) / [9.7.0.3](https://www.bungie.net/7/en/News/Article/destiny_2_update_9_7_0_3) | 本次核实到的补丁基线；包括属性回能调整与抓钩近战对 Boss 的例外 |
| [Bungie 近战重构说明，2025-06-19](https://www.bungie.net/7/en/News/Article/twid_06_19_2025) | 近战增伤加算、Melee 属性独立乘算、日光状态不继承普通近战增伤；后续例外以对应补丁 / 实测覆盖 |
| [Court Damage Stacking](https://docs.google.com/spreadsheets/d/1i1KUwgVkd8qhwYj481gkV9sZNJQCE-C3Q-dpQutPCi4/edit) | STACKING GUIDE 标注数值最后变动 2026-06-09、检查至 9.7.0.3；用于乘区与互斥，不把所有金装笼统归入独立乘区 |
| [Engineeeer 2026-06 回能解析](https://www.reddit.com/r/DestinyTheGame/comments/1u6czmi/the_final_armor_ability_stats_update_monument_of/) / [原始计算表](https://docs.google.com/spreadsheets/d/1g5JSR7oa5P2DHwGDBALjQNWD5M8fwXwli_I5naAzSOQ/edit) | 当前回能模型；公式来自 Armor Stat Info 的 D9、D10、D25 与 Z13:AC18，含拟合和外推，不能全部标为 measured |
| [Clarity CES 解析，2024-12-22](https://www.d2clarity.com/blog/destiny-science-6/chunk-energy-scalars-breakdown-12) | CES / CMS 和完整返还等例外的机制参考；数值需用当前规则覆盖 |
| [Mossy Outgoing Damage Scaling](https://docs.google.com/spreadsheets/d/1b57Hb8m1L3daFfUckQQqvvN6VOpD03KEssvQLMFpC5I/edit) | 归一化基础伤害、目标和活动倍率；公开表标为 Edge of Fate in Progress，不能保证所有活动已完成当前校准 |

明确保留的冲突：`Game Mechanics!E35` 的回能描述与当前最高 +125% 不符；`Stasis!D6` 五层冰霜护甲为 31.25%，9.7.0 写约 35%，暂以官方约值作示例、完整逐层值待测；`Game Mechanics!C9` 与其附近恢复时间表也有不一致。不能仅因工作簿时间较晚就自动覆盖明确的补丁证据，也不能把官方近似值写成实测精确值。

## 数值验收（命运 2 参考值）

以下用例验证命运 2 参考数值的计算，状态均为未实现。近似的社区曲线使用明确误差界。

| 用例 | 预期结果 |
| --- | --- |
| 普通 PvE 基础 1000，Radiant / Surge x3 / Kill Clip / Weaken | 2104.5，具体资格见武器例 |
| 同元素 Surge x3 与 x4 同时生效 | 取 25%，不算 1.22×1.25；低档计时不停止 |
| Radiant 对勇士 30% 与 Well 25% | empowering 选 Well 的 25%，不是 MAX 得 30% |
| Melee=200、Grenade=200，抓钩近战对 Boss | 属性乘区 1.625 |
| 两个同类抗性模组 + 独立 15% 减伤 | 36.25% |
| chunk q0=0.04、100 属性、CES=CMS=1 | 0.09；若输入已是参考属性收益，先归一化，不能二次放大 |
| 100 属性被动曲线 / 70 属性主动回能曲线 | Grenade / Melee=2.75、Class=2.42；F(70)≈1.992365783 |

## 走查：棱镜术士 build

用一套棱镜术士 build 走查机制覆盖与执行顺序，原走查暴露的 10 处问题见本节末尾。名称沿用此前对 [Bungie manifest](https://www.bungie.net/Platform/Destiny2/Manifest/)（2026-06-29 版）的核对，内容来自 Compendium 快照；v0.3 按新协议修正计算口径。下列基础伤害和收益文案仍需归一化，示例不是已经校准的当前实战伤害预测。

### 部件

| 部件 | 类型 | 关键规则 |
| --- | --- | --- |
| 闪电激涌 | 星相 | 滑铲中按近战：前移 10 米，在出口前 7 米落 5 道闪电（各 761 伤害，施加 Jolt）；获得增幅和电光充能 x1 |
| 虚空供养 | 星相 | 技能击杀、冰冻碎裂击杀等 → 强化吞食（+140 HP，10 秒，击杀刷新） |
| 保护琢面 | 碎片 | 15 米内 ≥3 个敌人 → 减伤 15% |
| 使命琢面 | 碎片 | 拾取能量球 → 按已装备超能的元素给增益；静止 → 冰霜护甲 x2 |
| 平衡琢面 | 碎片 | 3 秒内连续 3 次光属性击杀 → 近战 chunk；暗属性 → 手雷 chunk；快照写 10%，录入时核对属性基准与 CES |
| 勇气琢面 | 碎片 | 对带静止/缠绕减益的目标：光能技能 +10%，光能强化近战 +50% |
| 牺牲琢面 | 碎片 | 身上有电弧/烈日/虚空增益时技能击杀 → 暗超凡能量 +1% |
| 凤凰俯冲 | 职业技能 | 俯冲，9 米内友军 Cure x2 |
| 神秘织针 | 近战（缠绕） | 3 格充能，追踪，505 伤害并施加 Unravel |
| 急冻手雷 | 手雷 | 落地后放出追踪弹，冻住目标就再放一个，最多连锁 2 次 |
| 严冬之怒 | 超能 | 漫游超能，90% 减伤；轻攻击冰冻，重攻击冲击波击碎 |
| 狡诈严冬（Winter's Guile） | 金装臂铠 | 近战击杀叠督军印记（4.75 秒，最多 10 层，+300% 近战）；满层返还一格近战并进入督军之怒就绪（8 秒），下一次近战触发督军之怒（+400%，10 秒） |
| 庆典飞行 | 区域拒止榴弹（缠绕，特殊弹药） | 每发留下持续伤害的毒池。切割：施放职业技能后，5 次命中给目标挂 Sever（输出 -40%，10 秒），收起时也能触发。羸弱能量球：5 秒窗口内 26 次伤害后生成能量球 |
| 喷子 + 雪上加霜（One-Two Punch） | 武器词条 | 一枪命中 12 颗弹丸（强化 10 颗）→ 下一次合格近战 +150%（3 秒，收起即移除） |
| 超凡 | 棱镜机制 | 光暗两条都满后激活 20 秒：手雷换成急冻奇点，武器伤害 +5%，减伤 20% |

### 级联

缩进表示上一层动作产生的事件。

```
[包] 职业技能 → 凤凰俯冲：服务端冲量 + 宽限期；9 米内 Cure x2
  → ability_used{class}
      切割：挂 slice_ready（5 次，8 秒，收起时保留）       ← 手里可能拿的是喷子
[包] 切庆典飞行点射 → 射弹落地 → 生成毒池（lifetime + 周期伤害）

[世界物体] 毒池跳伤害（查询结算）
  → hit{owner=玩家, via=庆典飞行, object=毒池, credit=[weapon], kind=dot, element=strand, damage_result}
      超凡充能：缠绕=暗 → 暗条 += 指定的有效伤害投影 × 已校准系数（不计过量）
      切割：本次不致死且目标无 Sever → apply_status Sever（-40%，10 秒）；施加成功才 slice_ready -1
        → status_applied{Sever} → 超凡充能：暗条 +
      羸弱能量球：隐藏计数 +1 …… 第 26 次后下一次伤害 → spawn 能量球（0.5 秒冷却）

[世界物体] 能量球飞向玩家
  → pickup{orb_of_power}
      超能 grant_chunk（快照给球的标称值 0.87%，实际入账由球的收益 Profile 求值）
      使命琢面：by_loadout(超能元素)=静止 → 冰霜护甲 x2（减伤按版本化层数表，每 5 秒掉一层）

[包] 滑铲中按近战 → 解析：while 滑铲中，近战 = 闪电激涌；消耗一格近战充能
  1. 挂增幅（电弧增益）  2. 电光充能 x1  3. 前移 10 米 + 宽限期  4. spawn 5 道闪电
  闪电命中（查询）：归一化基础分量 × Melee 属性乘区 × (1 + 合格近战增伤之和) × 外部组
      目标有 Sever → 勇气琢面的光能强化近战 +50% 进入近战加算组；不再另乘其 +10% 分支
    → hit → Jolt；光条 +
    → kill
        虚空供养 → 强化吞食（虚空增益）
        平衡琢面 → 光属性击杀计数 +1
        牺牲琢面 → 有增幅（第 1 步挂的）→ 暗条 +1%
        督军印记 → 闪电带 melee 标签时 +1 层

[包] 喷子一枪 12 颗全中
  → shot_progress{previous_max_pellets_on_target=11, max_pellets_on_target=12} → 雪上加霜：挂 one_two_punch（3 秒，下一次近战命中消耗）
[包] 近战（不在滑铲）→ 神秘织针 → 命中（查询）：
     归一化基础分量 × Melee 属性乘区 × (1 + 督军增伤 + 合格的雪上加霜 150% + …) × 外部组
  → hit → Unravel；消耗 one_two_punch
```

- 闪电激涌先挂增幅再落雷，所以落雷击杀时牺牲琢面成立；顺序反过来就不触发。之后吞食本身也是虚空增益，10 秒内牺牲琢面持续成立。
- 切割挂的 Sever（缠绕减益）让闪电激涌（光能技能）吃到勇气琢面。两条规则互不提及，通过 buff 状态间接耦合。
- 神秘织针是缠绕（暗），吃不到勇气琢面。元素、credit 与 scaling_profile 分开配置，不能通过一组泛用 tags 自动推导全部增伤。

### 超凡

光、暗两条是资源，不是 buff。充能规则写在棱镜子职业的效果包里，是全游戏最热的一条 `on hit`：

- 电弧/烈日/虚空 → 光，静止/缠绕 → 暗，动能两条各 50%；一条满后，动能给另一条再降 40%。
- 使用经过校准的有效伤害投影，溢出伤害不算；有不同血池承伤倍率时，不能拿输入减剩余预算代替实际损失。
- 施加子职业增益或减益也会给对应的条充能。

两条都满后激活，挂一个带效果包的 buff，和漫游超能同一机制：

```
transcendence（20 秒）
  on_gain: 手雷、近战各 +2 格充能
  bundle:
    override: 手雷 → 急冻奇点
    modifiers: 武器伤害 +5%（other 组）；被动恢复修饰按资源 Profile；受到伤害 -20%（resist 组）
    rules:
      on hit{grenade} → 挂 melee_surge（2 秒，固定每秒 0.35 份近战充能；替换通道按定义）
      on hit{melee}   → 挂 grenade_surge（2 秒，固定每秒 0.35 份棱镜手雷充能；不再套 chunk scalar）
      on kill → extend(self, by_tier(T1/T2 5%、T3/T4 10%) 与 by_rank(精英、小 Boss、Boss 15%) 组合 × 0.9^n)
                n = 本次超凡的击杀数，存在隐藏计数 buff 里
```

急冻奇点是一个世界物体：force 把 12 米内的敌人拉向中心；aura 每 5 tick 对 6 米内造成 18 点静止伤害并减速；100 tick 后对 7 米内造成 570 点虚空伤害。第二段结算时，目标身上有第一段挂的减速（静止减益），所以吃到勇气琢面 +10%。

替换优先级：平时近战 = 神秘织针、手雷 = 急冻手雷；滑铲中近战 = 闪电激涌；超凡中手雷 = 急冻奇点；严冬之怒期间两者都由超能接管。

### 防御侧

一个被 Sever 的 Vandal 打你一下，基础伤害 100 点：

| 修饰 | 所在实体 | 倍率 |
| --- | --- | --- |
| Sever（输出 -40%，PvE） | 攻击方 | ×0.6 |
| 冰霜护甲 x5 | 你 | 约 ×0.65（9.7.0 官方约值，完整逐层表待测） |
| 保护琢面（被包围） | 你 | ×0.85 |
| 超凡 | 你 | ×0.8 |
| 增幅（对战斗人员） | 你 | ×0.85 |
| 合计 |  | 约 ×0.22542，进入血池前约 22.54 点 |

这是指定独立减伤组的计算示例，尚未加基础护盾、生命或 overshield 的层内规则。冰霜护甲采用官方约值而非快照中的 31.25%，所以总值也只作近似。实际测试要同时验证来源互斥、血池和取整，不把它当作已实测的最终扣血。

### 走查发现的修改

"改在"一列指 [rule-engine-design.md](rule-engine-design.md) 里的章节。

| # | 发现 | 依据 | 改在 |
| --- | --- | --- | --- |
| 1 | 武器效果包在装备在武器槽时生效，事件规则按伤害来源匹配，"在手中"只是条件 | 切割收起时可触发；羸弱能量球计数收起保留；毒池切枪后仍在伤害 | 核心模型：效果包；开放问题 |
| 2 | 事件带来源链 owner / via / object / tags，hit 带 dealt 和 effective | 毒池、丝线、闪电都是世界物体造成的伤害；超凡不计溢出 | 规则 DSL：事件 |
| 3 | 修饰分两种求值：持有者状态可缓存，命中上下文每次现算 | 保护琢面 vs 勇气琢面 | 运行时：控制流 |
| 4 | 叠加分组是树，节点非空归约，空值表示不贡献 | 雪上加霜与对冰冻目标的近战增伤互斥取高；max 不以 0 吞掉负值 | 属性与数值管线 |
| 5 | 连续量用资源，不用 buff | 超凡条、技能能量、超能能量 | 目标与范围；Buff；数据与状态放在哪 |
| 6 | 聚合事件：一枪、同一伤害批次 | 雪上加霜看一枪的弹丸数；电光充能每个同时伤害实例只给 1 层 | 规则 DSL：事件 |
| 7 | 事件队列：重入保护，预算只管未开始的外部命令；不截断合法循环 | Unravel、Jolt、连环碎冰；闪电激涌先挂增幅再落雷 | 运行时：控制流；规则 DSL：动作 |
| 8 | 编译只针对静态层，带包 buff 在分发时拼进来；技能替换有固定优先级 | 吞食、增幅几秒一得一失；滑铲、超凡、超能都会替换技能 | 核心模型：效果包；技能 |
| 9 | 数值表达式新增 by\_loadout、resource、diminishing | 使命琢面；超凡充能；超凡击杀延长递减 | 规则 DSL：数值表达式 |
| 10 | 两个加载器的引擎 tick 统一挂在整服 tick 末尾 | hello world 第 5 步里 NeoForge 用了 PlayerTickEvent.Post | 多加载器接线清单 |

## 扫描与定向范围的宿主能力

固定快照 Arc D28（Arcbolt）、Solar D29 / D30（Firebolt / Touch of Flame）、Void D30（Axion Bolt）明确要求视线内扫描；Arc D58（Thunderclap）与 Solar D40（Tripmine）要求锥形范围。引擎已提供方块碰撞视线、形状、实体取样点和方向快照，且在过滤后才应用最近排序 / 数量限制。

这些机制由合成场景验证，尚未装配为上述完整技能。当前“COLLIDER 挡射线、流体 / 实体不挡”“用一个脚底 / 身体 / 眼睛取样点判断区域”是 Minecraft 宿主策略；扫描端点、玻璃等材质、部分露出目标、具体锥角和原作碰撞箱处理仍须校准。空间能力可表达需求，不代表这些内容已完成验收。

## 电弧箭手雷（Arcbolt Grenade）

依据固定快照 Arc B28 / D28 / N28，`arcbolt.json` 已验证扫描和最多四目标连锁的部分内容：

| 参数 | 固定快照 | 当前测试投影 |
| --- | --- | --- |
| 落地扫描 | 12 米内视线可见的最近敌人 | 身体点球形查询，先视线筛选后 nearest / limit 1 |
| 首次攻击延迟 | 1 秒 | 锁定身份与攻击快照，detached 延迟 1 秒 |
| 连锁 | 每次 10 米内最近的未命中敌人，最多伤害 4 个 | 每跳新查询，从伤害前保存的身体点起算，显式排除已命中身份 |
| PvE / PvP 伤害 | 521 / 85 | 每目标 52.1 / 8.5 Minecraft HP；0.1 缩放尚未校准 |
| 基础冷却 / chunk scalar | 151.5 秒 / 0.75x | 尚未装配到技能资源 |

“造成伤害后”目前用实际 HP + Chorus 护盾 + Absorption 损失大于 0 表示。未命中 / 被取消 / 免疫 / 零损失会结束本次连锁，不重新选择替代目标。初始目标锁定后不会重查视线或距离；后续跳跃在同一逻辑时刻结算且不要求视线。这些边界没有在源单元格给出足够细节，是明确的 Chorus 内容选择，不能当成已核对的原作行为。

落地事件由测试宿主提供，落点暂用独立 LivingEntity 表示并从扫描排除；尚无投掷轨迹、碰撞接触点和飞行实体。敌我关系使用当前仍在本维度的施加者，未包含 D2 阵营或离线关系快照。来源卸下后的延迟攻击保留本次施放归属；每次施放各自排除已命中目标，不限制其他技能循环或下一次施放。

官方 [9.0.0.1 更新](https://www.bungie.net/7/en/News/article/destiny_update_9_0_0_1) 记录该手雷对 Boss、Miniboss 与勇士的相对增伤调整，不能据此把快照中的 521 再乘一次，也不足以确定完整目标等级倍率。官方 [9.5.0 更新](https://www.bungie.net/7/en/News/Article/destiny_update_9_5_0) 对 Lucky Raspberry 的 Jolt 施加与回能另有改动；本基础内容没有加入该异域护甲交互。上述补丁不独立验证固定快照的绝对伤害、范围或冷却，也不证明之后没有改动。

## 物理飞行与内容校准边界

引擎已有独立物理投射物和碰撞后动作体：实际位置 / 方向发射、捕获伤害与来源、扫掠碰撞、方块接触点 / 法线、寿命以及未知地形终止；与即时技能支付和原版实体同步已接通。直击或落点扫描可以继续使用相同 DSL，程序不必把落点伪装成可伤害实体。

`projectile.json` 的 20 米/秒、重力 0、drag 1、1 秒寿命和基础 10 伤害均为合成验收参数，不能套用到 Arcbolt / Firebolt 的 Heavy Trajectory。现有 Arcbolt 内容夹具仍从测试落地事件开始；实际出手位置、重轨迹、碰撞时序与技能冷却尚未逐项校准和装配。当前中心射线 / 实体碰撞箱扩张、50 ms 离散积分和施加者排除也是宿主策略，不是原作数值断言。通用物理测试不增加已审阅 Compendium 条目数。


墙面反弹、直线穿透、每目标命中上限与逐接触计数已接入通用 DSL。需求参考快照 `Weapon Perks!C20/C188`（穿甲弹一次穿透、Ricochet Rounds 反弹）、`Exotic Weapons!D27/D83`（Khvostov 同目标两次、Hard Light 墙面反弹）、`Void!D57`、`Stasis!D38`、`Strand!D38`（技能弹跳 / 追踪 / 回能）。通用限速追踪与接触后目标间转向也已接入；这些内容仍依赖各自数值、资格、计数语义及技能装配，合成夹具不将它们标为已实现。


Shield Throw、Withering Blade、Threaded Spike 的名称 / 机制 / 冷却单元格已逐项纳入覆盖清单（Void B/D/N57、Stasis B/D/N38、Strand B/D/N38），状态均为 unimplemented：尚无完整内容数据定义。已有追踪策略能够表达半径、扫描半角、速率限制、当前关系 / 视线过滤和接触后转向，但这些宿主策略不是原作校准结论。Withering Blade 的 12 [8] 米是固定来源值；Threaded Spike 的追踪半径与 Sever 持续时间仍为未知，不以测试值代替。

Shield Throw 的“4 次弹跳”和 Withering Blade 的“3 次表面 / 最多4敌人”还需确认共享次数语义，当前墙面与实体预算独立。Threaded Spike 的返回、接回、按命中次数返还资源、按击杀授予 Woven Mail，以及来源效果 / 技能输入仍需实现；普通成本回执不能直接跨物理飞行帧引用，现可用 retain_cost 把剩余额度转交到有限期共享账本。该通用机制已通过真实技能 / 飞行 / 延迟验收，但没有装配 Threaded Spike 的返回和命中档位；原表的 Melee % 不自动等同于实际成本百分比，固定充能比例仍应使用 grant_resource。完整验收要求和明确缺口保存在 `data/compendium/review.json`。
