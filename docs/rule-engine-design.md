# Chorus 规则引擎设计草案 v0.4

2026-10-09 · Hank Hogan

## 目标与范围

Chorus 是一个类命运 2 的 Minecraft 模组，核心是一个数据驱动的规则引擎：武器词条、护甲模组、碎片、星象、金装、技能效果、元素状态、敌人能力，都用同一套 DSL 描述。

- **目标平台**：Minecraft 26.3，Java 25。MultiLoader 结构（common / fabric / neoforge），Fabric 先做，NeoForge 按里程碑补齐。
- **v0 范围**：规则 DSL、buff、属性管线、技能、世界物体、移动。
- **v0 不做**：射击手感（瞄准、后坐力、第一人称动画）和自定义渲染。前期用原版风格模型、粒子和原版展示实体（Display）。遭遇战脚本和 UI 只定接口。
- **需求来源**：Destiny Data Compendium（2026-10-05 副本）里 15 个现行 sheet 约 1,476 条效果描述，用正则粗略统计。

设计原则：

1. 能写成数据就写成数据。Java 只写"种类"（新机制），JSON 写"实例"（具体内容）。
2. 效果的离散状态（有没有、几层、还剩多久）用 buff，包括玩家看不到的隐藏 buff；连续的量（技能能量、超能能量、超凡条）是资源。装备、技能选择及武器弹药属于各自的领域状态；弹匣和储备是整数账户，不用 buff 层数模拟两池转移。
3. 规则只在服务端计算，客户端只负责显示。唯一的例外是玩家自身的移动。
4. 纯函数核心：`(状态, 事件) → (新状态, 动作列表)`。动作是待执行的描述，副作用集中执行；执行结果作为输入回到纯核心，规则可以直接用 JUnit 测试。`step / resume` 是动作解释器内部处理结果依赖的实现方式，不替代这个顶层模型。
5. DSL 保持声明式，不追求图灵完备。表达不了的，用 Java 注册自定义类型，JSON 照样按名字引用。
6. 优先照抄原版：附魔效果组件（`fire_aspect.json`、`lunge.json`）、战利品谓词、`LevelBasedValue`、`ExperienceOrb`、`ApplyEntityImpulse`。
7. 效果循环是正常玩法。root / parent 用于因果追踪，不用于禁止重触发；冷却、消耗和触发排除由具体效果声明。
8. 数学算法与沙盒数据分离。计算 Profile 定义步骤，版本化规则集提供数值、来源和置信度；不能把快照日期当成所有条目的验证日期。
9. 玩起来像命运 2，而不是 1:1 复刻。适配 Minecraft、能和原版及其他模组联动组合，优先于精确复刻命运 2 的行为和数值。
10. 引擎与内容分离。引擎的 Java 代码不出现任何命运 2 的内容 id；命运 2 内容放在内置数据包的 `chorus_d2:` 命名空间，参考数值和校准资料见 [d2-ruleset.md](d2-ruleset.md)。文中示例为了简短，内容 id 仍写成 `chorus:`。

v0.3 将数值设计整合进现有引擎：计算 Profile、带单位和基准的贡献、分量伤害、分层防御、资源收益、快照和可恢复动作执行。v0.4 把目标定为"玩起来像"、适配 Minecraft 优先：生命值以原版为准，Chorus 只加护盾层和修饰；命运 2 的乘区、公式、资料来源和走查拆到 [d2-ruleset.md](d2-ruleset.md)。本文仍是设计草案，完整金标准验收尚未完成。

实现已开始，实际完成范围和验证证据以 [engine-implementation.md](engine-implementation.md) 为准。攻击侧分层倍率与 Under-Over 的部分内容也已通过纯核心和双加载器世界验证，护盾专属倍率不传递到盾后的生命预算。纯数值、回执、事件解释器、Buff 生命周期、统一逻辑时间轴、首批 JSON 规则 / 查询期修饰、条件动作分支与组件累积已有测试；攻击 / 防御 Profile 到世界伤害、Buff 护盾提交、治疗与连续恢复、状态确认、普通伤害观察和服务器 tick 已通过 Fabric 与 NeoForge GameTest，NeoForge 另已验证伤害阶段修改及治疗取消 / 改量 / 重入隔离。完整程序的数据包加载、覆盖重载及管理来源绑定也已接入。同版本程序片段可通过 CompiledEffects.link 组合后统一校验，Voltshot 已用这一入口复用 Jolt，并通过击杀 / 换弹 / 命中及两个收枪保留窗口的部分内容验收。球形目标查询、固定世界位置捕获与维度校验、距离快照、排除 / 最近目标上限、曲线表达式、集合计数与可恢复的嵌套 `for_each` 已通过纯核心和双加载器测试，持续场的采样成员差分与固定位置保存也已接入，显式视线、球 / 圆柱 / 圆锥及可冻结的方向已接入；通用物理投射物已能保存动作体 / 伤害快照并在实体或方块碰撞后恢复；碰撞箱区域相交、独立场物体和完整 D2 投掷物仍待实现。实体只读观测已提供生命 / Absorption / 存活 / MC 玩家类别的不可变回执，缺失与死亡分开；Jolt 的施加击、刷新、阈值、目标冷却、触发者归属、实际玩家损失后的中心链伤及相邻连锁已有部分内容验收。只读状态资格与 Volatile 的施加击排除、阈值 / 致死爆炸、目标冷却及相邻连锁也已有部分内容验收；数值假设和来源接线缺口保留在覆盖记录。Kinetic Tremors 的直击窗口、武器门槛、固定位置三波和冷却已有部分 JSON / 游戏验收，衰减和完整等级缩放待完成。攻击数值快照及 on_hit 合并、JSON capture_value / capture_damage、带来源生命周期或 detached 的 after 延迟动作已通过纯核心与双加载器验证；电弧箭的落地扫描 / 延迟锁定 / 四目标连锁已由现有动作组合验证；真实投掷和完整数值仍待接入。完整效果类型、生命层专属修饰、更多投射物行为与完整反应规则快照、其他世界事件、完整装备 / 技能自动装配、HUD 与技能 UI 尚未完成。下面的目标协议不能整体视为已经实现，Compendium 全覆盖仍是最终目标。

## 总体架构

Chorus 只做一个模组（mod id `chorus`），不拆分。common 里分 8 层，依赖只能向下；具体内容全部走数据包。

```text
common 子项目内的分层：上层只把自己的类型注册进 rule 层，依赖只能向下

  client/ui ............. HUD、装备界面、技能树界面
     │
  encounter ............. 遭遇战：状态机，复用同一套规则
     │
  gear / ability / mob .. 武器护甲、技能与子职业、敌人：规则的来源
     │
  object ................ 世界物体：通用实体 + 组件
     │
  effect ................ buff、资源、弹药、战斗结算：领域状态，带来源
     │
  rule .................. 【核心】DSL：事件、条件、动作、数值的类型注册表
     │
  stat .................. 纯数值计算：计算 Profile、贡献归约、曲线、计算轨迹
     │
  core .................. 注册抽象、平台服务、网络、Codec 工具
     ▲
     │ 实现 core 里的平台服务
  fabric/ neoforge/ ..... 加载器接线：注册、事件转发、网络包、附加数据、客户端渲染注册
```

- **开放的 sum type**：上层把自己的事件、条件、动作、组件类型注册进 rule 层的注册表。rule 层不需要认识它们，所以即使世界物体会执行规则、规则又能生成世界物体，依赖仍然是单向的。
- **不拆成多个模组**：在多加载器结构下，每拆出一个模组就多三个子项目，发版成本也翻倍；现在引擎只有自己在用。引擎稳定后再考虑拆出单独的 API 模组。
- **内容即数据**：具体词条、buff、技能、武器原型作为模组内置的数据包随 jar 发布。玩家和整合包作者不需要 API 模组，用数据包就能覆盖或新增。

## 核心模型：效果包

所有来源都产出同一种"效果包"。一个实体当前生效的规则集，就是它身上所有生效中效果包的并集。

```
效果包 = 规则[] + 属性修饰[] + 技能替换[] + 授予的 buff[]
```

| 来源 | 挂在哪 | 何时生效 | 备注 |
| --- | --- | --- | --- |
| 武器词条 | 武器实例 | 装备在武器槽时（收起也生效；"在手中"只是部分规则的条件） | 有普通版和强化版数值 |
| 护甲模组 | 玩家 | 穿戴时 | 同一模组可装多份，数值按份数分档 |
| 套装效果 | 玩家 | 穿够 2 件 / 4 件 |  |
| 碎片 | 玩家 | 子职业选中时 | 100 个碎片里 58 个带属性 ±10 |
| 星象 | 玩家 | 子职业选中时 | 提供碎片槽位；77 个里 51 个点名修改技能 |
| 金装 | 武器或玩家 | 装备时 | 金装护甲同时只能穿 1 件 |
| 赛季神器 | 玩家 | 解锁时 |  |
| buff（含超能形态、钩爪中） | buff 的持有者 | buff 存在期间 | buff 自己也能携带效果包，所以是递归的 |

静态来源一变化（换装备、改子职业），就重新"编译"一次并缓存：

1. 收集所有生效的效果包。
2. 按优先级应用技能替换，得出实际生效的技能。
3. 把事件规则按事件类型建索引，事件分发时只看相关的规则。
4. 把属性修饰按属性归类，交给属性管线。

带效果包的 buff（吞食、超凡、漫游超能）得失时不重编译：每个 buff 定义预先建好自己的索引，分发事件时拼进来，见「运行时：控制流」。

词条、模组、碎片、星象、金装各自是一个数据包注册表，条目都是效果包加上各自的元数据（名称、图标、槽位、是否强化）。

## 规则 DSL

规则是用 JSON 编码的 ADT：事件、条件、动作、数值各有一个"类型注册表"，用 `Codec.dispatch` 按 `"type"` 字段解码。这和原版编码战利品条件、附魔效果的方式一致，解析、校验、引用检查、数据包覆盖、同步到客户端都是现成的。

当前已实现 Value / Condition / Action 的开放类型分派和编译，程序根结构及完整 JSON / 需先链接的片段示例见 [engine-implementation.md](engine-implementation.md) 和 `common/src/test/resources/effects/`。完整 EffectProgram 已注册到服务端可重载的 `chorus:effect_program`，数据包覆盖、坏包拒绝、运行时保留旧定义和管理命令均通过两端 GameTest，具体使用见 [engine-data-packs.md](engine-data-packs.md)。同版本片段先用 PROGRAM 解码，再由 CompiledEffects.link 在完整目录上校验引用 / 单位；不覆盖重复定义，不在导入依赖内混合版本。数据包可用 imports / fragment 在同一 effect_program 目录声明模块；所有文件解码后统一链接可执行根，整次重载成功才发布。菱形与互引按模块身份去重，运行时效果链不受导入图限制；已有运行时保留旧编译目录。本节其他片段仍是目标 DSL 示意，客户端同步与细粒度内容注册表尚未接入。当前 `while` 行为可通过查询期 modifier 的 `if` 表达，顶层 `while` 语法糖尚未实现。

### 两种规则

- **事件规则**（`on`）：事件发生的那一刻触发一次。
- **状态规则**（`while`）：条件成立期间，携带的修饰和技能替换一直生效。表里的触发语句有 274 条是 While 开头，约占四分之一，比任何一种事件都多。

```json
// 事件规则：Outlaw（精准击杀 → 换弹加速）
{ "on": "chorus:kill",
  "if": { "type": "chorus:all", "of": [
    { "type": "chorus:source_is", "source": "this_weapon" },
    { "type": "chorus:precision" } ] },
  "do": [ { "type": "chorus:grant_buff", "buff": "chorus:outlaw" } ] }

// 状态规则：处于 Amplified 时加移速
{ "while": { "type": "chorus:has_buff", "buff": "chorus:amplified" },
  "modifiers": [ { "stat": "minecraft:movement_speed", "op": "base_percent", "percent_of": "base", "value": 0.1 } ] }
```

规则还可以带 `cooldown`。它是语法糖，等价于"触发时挂一个隐藏 buff，有这个 buff 时不触发"。

### 事件（v0）

表里有 753 种不同的触发写法，实际是少量事件类型乘以各种条件。事件本身带数据，条件对数据做判断。

| 事件 | 携带的数据 | 表里的例子 |
| --- | --- | --- |
| `chorus:kill` | 来源链（owner 谁、via 哪个武器实例或技能、object 哪个世界物体、tags）和攻击方快照、是否精准、目标（等级、状态、元素） | On Weapon Kill（40 条）、On Precision Kill |
| `chorus:death` | death id、死亡者、伤害原因及可选攻击来源 | 持有者死亡时触发；无攻击者的死亡也可以分发 |
| `chorus:death_prevented` | damage id、被救活者及真正生效的保护来源 | 图腾等不死保护实际成功；不算 death / kill。当前原版适配器记录实际消耗物品 ID；其他模组的保护来源仍待接入 |
| `chorus:hit` | 同上，加 damage id、分量结果、命名伤害投影、immune / lethal；effective 明确指实际扣除的护盾与生命之和，不含溢出 | Upon dealing Melee Damage |
| `chorus:fire_accepted` | 服务端接受的持有者 / 物品实例 / shot 标识、已提交成本及间隔；不等于实际发射或命中 | 已接实际容器的单次开火，执行 on_fire 并保留真实伤害来源 |
| `chorus:shot_progress` | 显式组内实际回执使每目标唯一命中 / 有效弹丸计数增加；含前后计数，不必等待其余弹丸结束 | One-Two Punch：同一目标普通 12 / 强化 10 颗，从未达标跨至达标时触发 |
| `chorus:shot_resolved` | 所有成员终止或寿命截止；各目标的唯一命中 / 有效弹丸数与 complete；一组只结算一次 | 需要整枪最终结果的效果；不完整截止不能冒充全数命中 |
| `chorus:damage_taken` | 攻击者、伤害类型、数值 | Feedback |
| `chorus:heal` / `chorus:health_restored` / `chorus:overheal` | heal id、来源、目标、requested / offered / effective / overheal；分别代表接受的正治疗、实际回血、容量溢出 | 当前已接显式 heal 动作；满血不发 health_restored，取消不算 overheal；自然治疗观察尚未接入 |
| `chorus:ability_used` | 槽位、技能 id | On Class Ability Usage（29 条）、On Super Cast |
| `chorus:reload_finished` | 哪个持有者的哪把武器完成合格换弹；不等同于开始换弹或弹药补充 | Kill Clip、Voltshot；已接实际容器的服务端整弹匣手动换弹，技能换弹 / 逐发装填 / 排热待扩展 |
| `chorus:pickup` | 物体类型（能量球、弹药砖、离子痕迹……） | On Orb of Power Pickup（15 条） |
| `chorus:finisher` | 目标 | On Finisher（15 条） |
| `chorus:health_threshold` | 跨过的阈值 | Upon reaching Critical Health |
| `chorus:shield_damaged` / `chorus:shield_broken` | 目标、护盾定义与 generation、层标签、实际容量损失和剩余容量、关联 damage id | 回充延迟、Press The Advantage；破盾只在实际减少到零时产生 |
| `chorus:shield_restored` | 补充者来源、接收层定义 / generation / 标签、requested / effective / overflow、补前和补后容量、maximum / full | 显式补盾正增加时产生；不刷新层生命周期，不与生命治疗共用 heal id |
| `chorus:buff_gained` / `buff_stacks_changed` / `buff_refreshed` / `buff_ended` | buff id、before / after、reason、请求 / 收益 / 实际层数变化 | Frenzy；到期和消耗通过 ended 的 reason 区分 |
| `chorus:movement` | 冲刺、滑铲、离地、落地 | Haste、While Sliding |
| `chorus:grapple_attached` / `grapple_end` | 钩住了什么 | Grappling a Tangle |
| `chorus:tick` | 周期 | 周期性效果 |

目标协议区分 event id、attack id、component id、batch id、cast id、root / parent。attack 表示一次攻击，batch 表示效果定义指定的同时发生批次，不能混为一谈。规则声明自己按分量、射击、批次还是目标计数。毒池、丝线、闪电可以保留背后的武器或技能来源；是否算武器伤害、能否触发词条仍由 credit 和 proc_policy 决定，不能仅凭 via 判断。

当前已实现 event / root / parent、每个世界伤害回执的 damage_id、物理 shot / pellet / contact，以及显式 batch_id。`begin_damage_batch` 生成类型化句柄，由 `damage` / `damage_snapshot` 明确绑定，未绑定时每个回执各算一批。数值快照本身不冻结批次；派生伤害也不自动继承。同 root 或同 tick 不合并，规则可以分别按 damage_id 或 batch_id 更新自己的组件集合。句柄可跨延迟显式保留逻辑关系，但引擎不据此推断物理同时性。显式 damage_group 已提供受管多分量共享一次性增益资格；它独立于 batch，事实携带 attack_group。通用 component / cast 关联与批次完成聚合仍未完成，见 [数据包批次协议](engine-data-packs.md#显式伤害批次)。

death / kill 来自同一次已确认死亡，共享 death id。Fabric 的 `ALLOW_DEATH` 在图腾检查之前，不能作为死亡事实；`AFTER_DEATH` 才确认死亡。图腾成功救活时该击仍可有受伤事实，但 lethal=false，不发 death / kill；救命效果联动监听 death_prevented。不能仅凭伤害超过当前 HP 或扣血过程中曾经归零判定死亡。世界物体的销毁、卸载与生物死亡是不同事件。

这里的条件是“不死保护实际成功”，不是“拥有或手持图腾”。原版先检查 `checkTotemDeathProtection`，失败才进入 `die`；某些伤害原因会绕过保护。死亡事件不负责取消死亡，救命效果必须在死亡确认前完成。`death_prevented` 应携带真正生效的保护来源，不能从最终 HP 或背包物品猜测。当前伤害采集器在原版保护组件执行后记录实际物品 ID，在 LivingEntity / ServerPlayer 确认死亡的广播点采集 death；双加载器 GameTest 已验证显式请求与已注册维度中的普通伤害，包括真实图腾救活、绕过图腾和玩家死亡。其他模组保护机制及不经过这些采集路径的特殊实体仍待验收。

### 条件

- **组合**：`all`、`any`、`not`。
- **事件数据**：来源是什么、是否精准、技能是哪个、武器类型、元素。
- **目标**：等级 ≥ 某档、是否玩家、身上有某个或某类状态、距离。
- **自身**：有某个 buff、层数 ≥ N、生命 ≤ 阈值、开镜、滑铲、滞空、蹲伏。
- **环境**：N 米内有至少 M 个敌人/友军。
- **原版谓词透传**：直接嵌入原版的战利品谓词。

### 动作

- **buff**：`grant_buff`、`remove_buff`、`consume_buff`（可按层数）、`apply_status`（施加给目标）。
- **战斗**：`damage`（单体或范围、伤害类型）、`heal`、`overshield`。
- **资源**：`grant_energy`（槽位、收益种类、数值基准和缩放策略）、`refund_cost`、`grant_full_charge`、`set_energy`、`refill_magazine`、`grant_ammo`。
- **世界**：`spawn_object`（世界物体）、`apply_impulse`、`play_cue`（表现 id，见「美术与表现」）。
- **逃生口**：`run_function`（原版 mcfunction），或 Java 注册的自定义动作。

动作作用于谁，由选择器决定：`self`、`victim`、`attacker`、`buff_source`（buff 是谁挂的）、`area`（半径 + 过滤条件）、`same_source`（和我来源相同的其他持有者）。

动作列表严格按顺序执行，后面的动作看得到前面动作的结果。例：闪电激涌先挂增幅再落雷，落雷击杀才能触发牺牲琢面。

### 数值表达式

数值不是常量而是表达式，思路同原版 `LevelBasedValue`，上下文更多。

| 类型 | 含义 | 表里出现的比例 |
| --- | --- | --- |
| `enhanced` | 按是否强化取值（↑） | 31% |
| `pvp` | 按当前效果在活动规则集中的 PvE / PvP 数值分支取值，不能等同于目标是否玩家 | 32% |
| `by_rank` | 按目标等级查表 | 12.5% |
| `by_stacks` | 按层数或份数查表（a \| b \| c） | 7% |
| `by_buff_tier` | 按当前 Buff 强度查表（已实现）；与目标档次 by_tier、堆叠数 by_stacks 分开 |  |
| `by_source_tag` | 按绑定来源标签选择同单位 Value（已实现）；必须唯一命中，不按事件标签或当前手持物猜测 |  |
| `choose` | 条件决定 then / else 数值（已实现）；两支同单位，运行时仅求选中分支，支持快照部分求值 |  |
| `by_weapon_type` | 按武器类型查表 |  |
| `stat` | 读一个属性 |  |
| `add` / `mul` / `min` / `max` / `clamp` | 算术组合 |  |
| `by_loadout` | 按装配取值。例：使命琢面按已装备超能的元素给不同增益 |  |
| `resource` | 读一个资源的当前值。例：一条超凡条满了以后，动能给另一条充能变慢 |  |
| `diminishing` | 递减：base × factor^层数。例：超凡期间每次击杀延长的时间按 0.9 递减 |  |
| `by_tier` | 按目标档次（Tier 1–6）查表，和 by\_rank 分开。例：吞食按档次返还手雷能量 |  |
| `by_target_class` | 按目标类别：玩家 / 战斗人员 / 构造物。例：快照中的 Ignition 分别有 120 / 676 / 250 三种基础值；与活动规则分开 |  |
| `by_ruleset` / `curve` | 按活动规则查分支，或引用带版本的曲线；曲线节点支持查表与已验证公式 |  |
| `result` | 读同一动作序列里前面动作的结果。例：按实际消耗的层数返还能量 |  |

```json
{ "type": "chorus:pvp", "pve": 119, "pvp": 51 }
{ "type": "chorus:by_stacks", "values": [0.10, 0.21, 0.331] }
```

### Java 类型

`Rule` 是封闭的；条件、动作、数值是开放的，任何一层都能往注册表里加新类型。

```java
public sealed interface Rule permits OnEvent, While {}
public record OnEvent(Holder<EventType<?>> on, Condition when, List<Action> actions, Optional<Value> cooldown) implements Rule {}
public record While(Condition when, List<Modifier> modifiers, List<AbilityOverride> overrides) implements Rule {}

public interface Condition { MapCodec<? extends Condition> codec(); boolean test(RuleContext ctx); }
public interface Action    { MapCodec<? extends Action> codec(); Outcome step(RuleState state, Frame frame, RuleContext ctx); }
public interface Value     { MapCodec<? extends Value> codec(); double eval(RuleContext ctx); }

// 顶层仍是 (状态, 事件) → (新状态, 动作列表)，不碰世界，可直接用 JUnit 测。
// 以下为动作解释器内部的推进接口，见「核心语义 v0.3」的「动作执行」。
Outcome step(RuleState state, Frame frame, RuleContext ctx);
```

## Buff：效果的离散状态原语

计时、层数、时间窗口、效果计数器、一次性效果额度、元素状态，全都用 buff 表达，DSL 不单独发明"序列"或"计数器"语法。命运 2 自己也大量使用玩家看不到的隐藏 buff。这里限定的是效果状态：武器的弹匣 / 储备数量另用整数弹药账户，实际弹数与命中计数、临时加成分开。

### buff 定义

| 字段 | 取值 | 表里对应的写法（出现比例） |
| --- | --- | --- |
| `duration` | 数值表达式 | for N seconds（35%） |
| `max_stacks` | 整数 | stacks、up to xN（17.5%） |
| `refresh` | `reset` 重置 / `extend`（加多少、上限）/ `historic_max` 恢复历史最长时间 / `none` 到期前不可刷新 | refresh / extend（13%）；Restoration 的历史时间 |
| `decay` | `all` 一次全掉 / `one_by_one`（可带延迟） | Stacks decay one at a time |
| `scope` | 已拆成 `attach`、`instanced_by`、`affects`、`on_stow` 四个字段，见「核心语义 v0.3」 | removed on stow / persists through stow（16.5%） |
| `per_source` | 并入 instanced\_by | Kinetic Tremors、Deadfall 拴住 |
| `hidden` | 是否对玩家隐藏 | 窗口、计数器 |
| `tags` | 类别标签 | "Arc 类 debuff"、互斥、叠加分组 |
| `bundle` | 存在期间生效的效果包 | Jolt 自带连锁闪电规则；Stormtrance 自带减伤和技能替换 |
| `display` | 图标、名称、是否显示层数 |  |

buff 实例用「持有者 × buff 定义 × 实例键」区分，状态除了层数和计时器，还可以带累加器、去重集合、引用、历史值等组件，见「核心语义 v0.3」。实例键由 instanced_by 决定，可以是来源实体、武器实例，或者为空。

### 常见模式怎么用 buff 表达

| 表里的写法 | 做法 |
| --- | --- |
| reload within 3.6s / 5.3s of a kill（Kill Clip / Voltshot） | 击杀时挂一个隐藏的"窗口"buff |
| 3 kills within 6 seconds of each | 隐藏计数 buff：每次 +1 层并刷新，层数到 3 时触发并消耗 |
| The next hit inflicts…（Voltshot） | 一次性 buff，下一次命中时消耗 |
| 对同一目标多次命中（Kinetic Tremors） | 隐藏 buff 挂在**目标**身上，按来源区分 |
| 持续交战 12 秒（Frenzy） | 隐藏 buff，加上 `buff_ended` 的 expired 原因 |
| Incurs N second cooldown | 规则的 `cooldown`，本质也是隐藏 buff |

### 例子：Kill Clip

```
// 规则 1：本武器击杀 → 挂 3.6 秒的隐藏窗口，实例按武器区分
{ "on": "chorus:kill", "if": { "type": "chorus:source_is", "source": "this_weapon" },
  "do": [ { "type": "chorus:grant_buff", "buff": "chorus:kill_clip_window", "key": "this_weapon" } ] }

// 规则 2：本武器换弹完成，且本武器的窗口还在 → 消耗窗口，给本武器挂增伤
{ "on": "chorus:reload_finished",
  "if": { "type": "chorus:all", "of": [
    { "type": "chorus:source_is", "source": "this_weapon" },
    { "type": "chorus:has_buff", "buff": "chorus:kill_clip_window", "key": "this_weapon" } ] },
  "do": [ { "type": "chorus:consume_buff", "buff": "chorus:kill_clip_window", "key": "this_weapon" },
          { "type": "chorus:grant_buff", "buff": "chorus:kill_clip", "key": "this_weapon" } ] }

// buff chorus:kill_clip_window：挂在持有者身上，按武器区分实例（收起时是否保留，表里未写，暂定保留）
{ "duration": 3.6, "hidden": true,
  "attach": "holder", "instanced_by": "weapon", "on_stow": "keep" }

// buff chorus:kill_clip：5 秒（强化 5.5），只给绑定的武器增伤 25%，收起即移除
{ "duration": { "type": "chorus:enhanced", "base": 5, "enhanced": 5.5 }, "refresh": "reset",
  "attach": "holder", "instanced_by": "weapon", "affects": "instance_weapon", "on_stow": "remove",
  "bundle": { "modifiers": [ { "stat": "chorus:weapon_damage", "op": "multiply",
                                "value": 0.25, "group": "weapon_perk", "stacking_key": "chorus:kill_clip",
                                "eligibility": { "exclude_tags": ["explosive_perk_damage"] } } ] } }
```

元素状态也是 buff，而且可以自带规则。以 Jolt 为例，表里的规则是"受到 115 点伤害时，对 12 米内的敌人释放连锁闪电，0.8 秒冷却"，直接写在 Jolt 的 `bundle` 里。表里出现最多的状态：Scorch 80 条、Slow 75、Overshield 58、Weaken 53、Jolt 51。

### 资源

连续变化的量不用 buff，用资源：技能能量、超能能量、超凡光条和暗条。资源 = 名字 + 当前值 + 上限 + 恢复 Profile + 充能策略；恢复速率由数值计算器求得。一次技能充能为 1.0，N 次充能上限为 N，百分比默认以一份充能为基准。

- 写：`add_resource` 是已经求值的直接入账；`grant_energy` 按收益种类、属性、CES / CMS 计算后入账。两者不可重复缩放。通用结构见「属性与数值管线」，命运 2 的具体公式见 [d2-ruleset.md](d2-ruleset.md#资源回能)。
- 读：数值表达式 `resource`。
- 超凡充能几乎每次命中都会触发，是全游戏最热的规则，实现时要保证它便宜。

当前已落地的资源 DSL 为程序根级 `resources` 加 `initialize_resource / grant_resource / spend_resource / refund_cost / grant_full_charge`，并以 `resource_crossed` 监听阈值。账户按 holder + resource id 保存，初始化幂等，解绑来源不重置能量；恢复 Profile 自动接入逻辑时钟。上面的 `add_resource / grant_energy` 等名称仍是目标语义，不是另一套已实现的动作类型。具体字段、成本分支和可运行数据包见 [实现记录](engine-implementation.md#数据资源账户与阈值) 与 [加载示例](engine-data-packs.md#声明资源与支付成本)。

### 武器弹药

`AmmoState` 按在当前运行时内唯一的稳定武器实例身份保存整数 magazine、未修饰容量输入和有限或无限 reserves，单位为 `round`。可选 capacity_profile 从未修饰值求当前有效基础容量，收集账户 holder 的来源 / Buff，并按实际武器身份筛选；DSL 的 capacity / missing、默认补弹上限和按容量百分比均读这个派生值。Java 原始账户的 capacity 字段是不可变输入，DSL 的 unmodified_capacity 对应它；有效值与计算轨迹由 AmmoCapacity.View 返回。查询不覆盖输入，不隐式增删弹药；加成到期 / 移除后自然恢复。

magazine 可以超过有效基础容量；每次补弹另有 ceiling，只限制本次可补数量。词条结束或下次 ceiling 降低不会删掉已有溢出弹药。真实基础容量变化会影响后续百分比与其他词条，不能以临时溢出上限代替。Profile 明确取整阶段，最终必须是正整数 round；一次动作的结果保留该动作采用的容量，后续查询按最新状态重算。武器元数据变化导致的未修饰输入替换和账户转移仍待接线。

已实现 `initialize_ammo / observe_ammo / spend_ammo / refill_magazine / grant_ammo`。初始化幂等、来源解绑不重置弹数；spend 余额不足不部分扣款；refill 从储备守恒转移；grant 明确生成弹药。结果包含 requested / applied / unfulfilled 和变化后账户，用实际 applied 驱动后续动作。无限储备显式表示，不暴露虚假的有限弹数；未初始化账户也不按零处理。小数请求必须先用 `round` Value 明确 floor / ceiling / half_up。

实际应用后发布 ammo_spent / ammo_refilled / ammo_generated，状态改变另发 ammo_changed；不会从转移弹药自动推断开火、弹匣打空或合格换弹。已新增武器定义，将实际容器的稳定实例与账户绑定；服务端接受整弹匣手动换弹，在固定时长到期后重新校验持握 / 存活等资格，按当前容量和储备转移弹药，实际装入后才发布 `reload_finished`。切枪 / 改装取消，重复装备不补弹。同一运行时的持有者移交保留余额并更新容量修饰归属。服务端单次开火已提交扣弹与每实例间隔，接受时中断换弹并执行 on_fire；实际投射物保留 owner / weapon / shot 与攻击快照。显式多弹丸结算已接入实际伤害回执，区分总命中数与每个目标的唯一弹丸数；超时会发布不完整结算并停止成员飞行。精准区域、连发控制、逐发装填、技能换弹、弹药存档 / 跨维度迁移和 HUD 仍待实现。协议、字段与边界见 [弹药数据包示例](engine-data-packs.md#整数弹药与弹匣转移)。

## 属性与数值管线

共用数值表达式、贡献归约器和曲线，但不强制所有数值经过同一条固定流水线。效果包决定哪些贡献可用，计算 Profile 决定步骤，Buff 保存状态，动作执行器提交结果。

### 职责与接口

| 现有组件 | 数值职责 |
| --- | --- |
| 效果包 rules / while | 决定何时增减状态、哪些 modifier 适用 |
| Modifier + Value | 保留表达式、条件、来源，求值为带单位的贡献 |
| Buff 组件 | 保存层数、计时、累计值、历史值和引用 |
| 属性定义 / scaling_profile | 引用 CalculationProfile，并声明允许的组、特殊选择规则 |
| stat 层 | 对已解析贡献执行数学步骤，不读世界、不反向依赖 Buff 或 DamageContext |
| effect 层的战斗 / 资源服务 | 建立只读上下文，求值条件与 Value，生成状态写集和结果 |
| 动作执行器 | 提交状态与世界投影，返回实际结果，驱动后续事件 |

新增三个共用结构，均为数据：

```text
CalculationProfile = 有序计算步骤 + 分组树 + 曲线引用 + 边界 / 取整规则
NumericContribution = 数值 + 单位 + 基准 + 阶段 + 分组路径 + stacking_key + 来源
CalculationTrace = 输入 / 版本 + 使用与排除的贡献及原因 + 各阶段输出
```

上下文选择与表达式由 rule 层及上层注册的类型处理；stat 层只接收已解析的数学输入。伤害 Profile 的来源 / 目标资格与派生继承仍属于战斗服务，不把所有 perk 名字硬编码进计算器。Profile 是有限步骤的组合，不是允许任意代码执行的脚本。

### 数值类型与计算顺序

| 类型 | 计算步骤 | 输出 |
| --- | --- | --- |
| 武器属性 | 基础点数 → 固定加值 → 上限前修正 → 属性限幅 → 原型曲线 → 实际量修正 → 实际量边界 | 换弹秒数、射程、操控时间等 |
| 实体属性 / 技能参数 | 参数基础值 → 各阶段贡献归约 → 指定位置的曲线与限幅 | 移速、半径、持续时间等 |
| 伤害分量 | 归一化基础值 → 环境 / 属性 / 精准 / 衰减 / 增伤分组 → 目标修饰 → Chorus 护盾计划 | DamagePlan；适配层执行原版伤害后再形成 DamageResult |
| 资源 | 被动速率 / 收益基础值 → 对应缩放 → 通道组合 → 积分或入账 → 容量裁剪 | ResourceResult |

实体属性可以投影到原版 Attribute 供客户端物理使用，但不得把已经归约的结果再次作为原版倍率叠加。伤害数值和技能资源由 Chorus 计算；生命值以原版为准，见「与原版和其他模组联动」。属性定义声明输入 / 输出单位，例如 `stat_point → second`，不能把装填属性点直接与装填时间倍率相加。

```json
{ "stat": "chorus:reload_speed", "op": "add", "stage": "stat_flat",
  "unit": "stat_point", "value": 70, "group": "flat", "stacking_key": "chorus:outlaw" }
```

`op` 支持 add、base_percent、multiply、resist，以及 Profile 允许的 replace。百分比增伤写增量：+25% 为 0.25；减伤 15% 为 0.15。绝对值带单位，完整倍率与增量不能隐式互转。简写 modifier 可以省略属性定义能唯一确定的 unit / stage；存在歧义必须显式填写，加载后统一展开为完整贡献。修饰上的 `eligibility`（和 buff 上的 `affects` 不同）的 exclude_tags 在伤害 modifier 中只匹配分量能力标签，不混查 origin / credit / proc。

`base_percent` 必须声明 `percent_of`，可指向 base 或 earlier stage。base=100、add=50、percent=0.20 时：

```text
percent_of=base:       100 + 50 + 100×0.20 = 170
percent_of=after_flat: (100 + 50)×1.20     = 180
```

上限和曲线位置由 Profile 决定。Slow 的武器属性 −75% 在属性封顶前，所以 160 装填属性变 40，不是先封顶到 100 再变 25（`Stasis!D7`）。动画时间倍率在属性曲线之后计算。曲线支持精确查表、显式插值和带来源的公式；缺失点不默认为零，也不默认所有表都能线性插值。

### 筛选、互斥与分组归约

一次查询按以下次序进行：

```text
收集静态与 Buff 贡献 → 检查 eligibility / 条件
  → 按 stacking_key 和实例策略整理副本
  → 处理显式替换与优先级 → 递归归约分组树 → 执行 Profile
```

`stacking_key` 表示效果家族，不等于“一律去重”。家族策略可为唯一、按份数查表、按来源取最高、保留独立实例。例如 Disruption Break 每次施加独立计时并相乘（`Weapon Perks!C71`），不能按名称合成一份。

| 合并器 | 两个值怎么合并 | 输出怎么用 | 单位元 |
| --- | --- | --- | --- |
| sum | a + b | 倍率 1 + x，或同单位绝对量 | 0 |
| max | max(a, b) | 倍率 1 + x，或同单位绝对量 | 用空值表示缺席 |
| product | (1 + a)(1 + b) − 1 | 倍率 1 + x | 0 |
| resist | 1 − (1 − a)(1 − b) | 倍率 1 − r | 0 |

product 的输入和输出都是增量：+10% 与 +25% 合并得 0.375。max 非空时才取最高，空组不贡献；全是负数时不能先放入 0。每个分组是 semigroup，提升成可空结果后成为 monoid。运算顺序固定，不能依赖哈希表遍历顺序。

具体的分组（强化增益、易伤、武器激涌、武器词条、近战加算、活动加成、减伤家族）属于规则集，命运 2 的分组见 [d2-ruleset.md](d2-ruleset.md#乘区分组)。具体效果必须指定组和资格；碎冰、点燃这类新的伤害动作使用自己的 Profile。

### 攻击上下文与分量

一次攻击可以包含直击、爆炸、持续伤害等多个分量。每个分量独立声明基础值、元素、精准规则、距离衰减和 scaling_profile，共享 attack id，分别拥有 component id。派生状态伤害创建新的 damage id，并关联 parent。

```text
Attack
  ├─ impact:    precision_allowed=true
  ├─ explosion: precision_allowed=false
  └─ 后续 DOT / 状态伤害：独立的时间与继承定义
```

假设 200 直击、800 爆炸、直击暴击倍率 2，结果为 `200×2+800=1200`，不能先加成 1000 再乘 2。共享攻击身份只用于规则指定的聚合，不自动把所有分量变成一个 hit；死亡状态迁移对同一目标只提交一次。

上下文分别保留 activity_ruleset、attacker_class、target_class、target_rank、reward_tier、precision_hit、damage_number_style。Rank 和 Tier 不混用，黄色伤害数字不等于弱点命中（Rapid Hit 的例外见 `Weapon Perks!C174`）。Gambit 等环境可对部分效果使用 PvP 数值，即使目标是战斗人员；因此 pvp 数值分支必须读规则环境，目标类别条件独立求值。性质差异也用目标类别分支：Blind 对敌人是 10 秒无法攻击，对玩家是 HUD 消失、屏幕变白 3 秒。

### 伤害 Profile

具体的伤害 Profile（武器、近战、抓钩近战对 Boss 等）属于规则集，命运 2 的见 [d2-ruleset.md](d2-ruleset.md#武器伤害-profile)。引擎只规定：Profile 由有序步骤组成；每个因子只在指定阶段应用一次；基础值必须声明口径和已包含的因子，实测数字已含某个倍率时先归一化，不能重复应用。

当前单分量世界接线已实现：`chorus:damage.scaling_profile` 显式选择攻击 Profile，外层原版 `hurtServer` 前仅计算一次；规则集顶层 `defense_profile` 选择目标全局承伤 Profile，在原版取消 / 免疫 / 格挡 / 无敌帧处理之后、Chorus 护盾之前计算。两者必须已定义且输入输出均为 damage。攻击端收集 source.owner 的修饰，防御端收集 target 的修饰；易伤在目标防御 Profile 中归组，不能把两个持有者的所有 Buff 混在一起。未指定 scaling_profile 时不猜测武器 / 技能缩放，但目标防御仍可生效。原版 `NativeSource` 适配器可显式声明攻击 Profile，默认保持缺省。

回执保留两阶段完整计算轨迹，未到达的阶段保持缺席。无敌帧差额以已经缩放的攻击输入计算，父类委托不重复乘倍率；正输入经防御降为零时视为 blocked，不扣盾。完全格挡后的零输入不再进入防御 Profile，防止加值或替换步骤重新制造伤害。双加载器已验证此顺序及嵌套命中。攻击贡献快照已实现，物理 projectile 步骤已绑定伤害快照；来源反应规则选择已可在释放时固定，逻辑批次已有显式身份；完整攻击上下文、多分量完成聚合与共享资格仍待实现。

### 防御与分层血池

独立减伤来源的总减伤 `R = 1 − Π(1 − r_i)`。同家族先按份数、覆盖或档位归约：两个同种抗性模组为 25%，再加独立 15% 得 `1 − 0.75×0.85 = 36.25%`。

先处理攻击者输出削弱、活动承伤和目标全局减伤，再按层处理专属承伤倍率。`global`、`shield_layer`、`health_layer` 是不同作用域；Void Overshield 的专属减伤不能直接挂成全身 resist。基础护盾与生命也可具有不同承伤规则（`Game Mechanics!C9`）。FIFO 为 Chorus 的默认护盾优先级；其他优先级由防御 Profile 指定。

跨层时使用统一的“层专属修正之前的伤害预算”：

```text
Q   = 当前剩余输入伤害（已应用全局修正）
H_i = 本层剩余容量
m_i = 本层承伤倍率 × 攻击者针对本层的倍率

m_i > 0:
  loss_i  = min(H_i, Q × m_i)
  spent_i = loss_i / m_i
  Q       = Q - spent_i
  H_i     = H_i - loss_i
```

`m_i=0` 单独处理：免疫层默认阻断本分量并记录 blocked，不能做除法；穿透 / 跳过血池必须显式配置。非致死伤害把生命可扣额度限制为 `max(0, health - minimum_health)`；剩余预算不制造击杀。

算法验收例：只有 45 容量、70% 层内减伤的盾，后面是容量足够、无专属减伤的基础护盾，输入 200：第一层消耗 `45/0.30=150` 输入，下一层扣 50。实际容量损失为 95，不能把整次攻击先减为 60 再只溢出 15。

上述跨层算法与 FIFO 是 Chorus 的明确约定，不宣称命运 2 所有护盾都已实测验证。特殊护盾可以替换跨层策略，但必须保留各层输入、损失、消耗预算和未消耗预算的计算轨迹。

当前实现由 EffectProgram 的 Buff 条目声明 `shield.capacity`（指向单位为 damage 的数值组件）、`taken_multiplier`（单位为 multiplier 的 Value，默认 1）和 `priority`（默认 0）。优先级数值小的先处理，同优先级按创建 generation 排序；值为零的容量和暂停层跳过。归零保留 Buff，由内容声明移除、修复或回充反应。`ShieldDamage` 输出带旧实例快照的写集和逐层轨迹；提交校验 generation 与旧快照，拒绝把旧写入作用于重新获得的盾。目标全局防御 Profile 已在层前执行；显式补盾与连续回充已接入；穿透策略、生命层专属修饰与内容自动装配仍待实现。

攻击侧层修饰已通过 `damage / capture_damage.shield_scaling_profile` 接入：multiplier 输入 / 输出、基值 1，与 taken_multiplier 相乘。`layer_tag` 读取当前接收层的定义标签，独立于攻击标签；空层和预算未到达的层不求值。每层记录 attackScaling 轨迹，所有查询共享命中前不可变状态，预算逐层递减。攻击快照固定旧层 Profile 及来源贡献，层 / 目标条件命中时求值，与当前 on_hit 贡献在同组归约。Under-Over 的普通 / 强化盾类别和 Woven Mail 躯干分支已有部分数据与双端测试；原作跨层预算和多效果叠加仍待校准。精准因子须明确标记后按下述算法排除，不能从最终伤害反推。

命名因子与层排除已接入：Profile 的 multiply 阶段可标记 `factor: chorus:precision`，护盾声明 `excluded_attack_factors`。Result 保存已求值的贡献、原 Profile 和基值；排除指定阶段后依次重算后续固定加值、percent_of、曲线、上限和取整，不用最终伤害除以猜测的精准倍率。原版通过门槛的预算按攻击前后输出比例换算，再以本次防御贡献重放，得到只作用于当前层的比例；世界门槛、DSL Value 和资格条件不再次执行。DamageBasis 必须来自本次命中的真实计算，未标记的原版攻击不会被猜成精准。每层 factorSuppression 保存轨迹，命中标签和后续层预算保留。该投影是明确的 Chorus 约定，原作跨层细节仍需校准。

`shield.maximum` 提供补充上限，使用接收层作用域的 damage Value，缺省为容量组件声明的初值。`restore_shield` 只增加已有实例的容量，返回 requested / maximum / before / after / effective / overflow 和 changed / full；上限降低不扣已有容量，补盾不刷新期限、来源或 FIFO 顺序。溢出不储存，正增加才发布 shield_restored。此动作不读世界、不自动授予层；实体存活与满血等条件由显式观测决定。连续回充由下述 shield.recovery 声明，受击延迟由数据规则组合。

护盾资格须与实际容量分开：`has_buff` 默认按当前绑定匹配，`match: any` 查询同持有者任一来源的合格实例；`has_buff_tag` 检查已有 Buff 定义标签，包含暂停实例；`has_shield` 检查未暂停且容量大于零的声明层，可按定义标签筛选，不包含原版 Absorption。Rift presence 使用前者表示具有护盾的词条资格，Void 阻止补盾使用后者检查当前正容量。来源侧条件可被冻结到攻击快照，目标侧条件仍在命中时求值。

`shield.recovery` 使用接收层自身的 rate（damage_per_second）与 if，按段首状态积分并复用补盾上限。50 ms 网格、已有到期 / 定时器 / 资源边界以及补满时间划分区间；补满时间向上取到整数微秒。容量先提交，再推进到期与世界治疗，因此 ended 快照保留不足一周期的残量，后续原版嵌套伤害读取新容量。满值、暂停、零速率和禁用不累计额度；动态表达式不自动产生任意内部阈值或精确连续解，完整恢复 Profile 和跨来源通道仍待扩展。

护盾受击延迟由组件开关加 replace 一次性定时器表达：选定损失事件关闭回充、重新计时，到期才开启。`own_shield` 同时匹配 definition / generation / victim，仅在 Buff 规则内使用，避免旧层事实中断同键新层。哪些伤害中断恢复、破盾后删除还是从零回充须由具体效果声明。Eternal Warrior 示例已组合 75 HP、5 秒停伤和 7 秒满量回充，按 0.1 缩放通过双加载器承伤 / 恢复验收；明确标记的精准因子现已可按层排除；自动精准判定、Arc 增伤与实际超能装配仍未完成。

### 资源恢复与收益

一次技能充能为 1.0；有 N 次充能时上限为 N。`charge_fraction` 默认指一份充能，`capacity_fraction` 必须显式声明。资源变化返回 requested（基础请求）、scaled（缩放后请求）、credited（实际入账）、overflow；Buff 的 credited 是效果定义的收益数量，与资源实际入账不是同一概念。

| 操作 | 含义 | 缩放规则 |
| --- | --- | --- |
| passive_regeneration | 自然恢复 | 基础冷却与被动属性曲线 |
| add_base_regeneration | 增加基础恢复速度 | 先统一基础冷却与属性基准，再按组归约 |
| multiply_regeneration | 对指定恢复通道乘倍率 | 声明作用通道，不能默认放大全部收益 |
| fixed_energy_per_second | 固定每秒恢复 | 默认不经过 CES / 属性；替换哪些通道需显式声明 |
| grant_chunk | 一次性比例回能 | 使用接收技能与触发来源的缩放策略 |
| refund_cost | 按实际已支付成本返还 | 引用 cost receipt，避免免费施放再次返还 |
| grant_full_charge | 补足指定份数的充能 | 不作为普通 chunk 缩放 |
| set_energy | 设置资源值 | 明确数值与上限，返回实际变化 |

一次性收益的通用形式：`基础值 × 属性曲线 × 接收方系数 × 触发方系数 × 其他`，未参与的因子为 1。具体曲线和系数属于规则集，命运 2 的见 [d2-ruleset.md](d2-ruleset.md#资源回能)。

每条资源收益必须声明 `value_basis=stat_zero / reference_stat / fixed`；reference_stat 还要带属性值及已包含因子，归一化只做一次。特定技能返还、满充返还等是否豁免 CES，由收益定义记录，不能由名称猜测。

连续恢复的一般形式：

```text
dE/dt = (P(s) + Fold(A0_i × F_i(s))) / T0 × M_regen + R_fixed
```

T0 为 **0 属性下**的一份充能基础冷却，P 为被动倍率，A0_i 为同基准的额外基础恢复倍率。Fold 使用各效果声明的加算 / 互斥 / 替换；固定恢复可按 Profile 替换指定通道。被动曲线、主动收益曲线和伤害增量是三个不同属性函数。

多充能声明 `recharge_policy=sequential / parallel / linked`，以及收益分配策略；parallel 需要每格独立进度，linked 需要明确哪些格一起恢复。总能量相同不代表充能状态相同。Ophidia Spathe 等具体行为要用时间线校准，不能默认所有技能串行回充。

实现进度：资源定义已支持 capacity / initial / base_rate / thresholds / rate_profile，当前仅有共享顺序能量。恢复 Profile 输入 / 输出为 `charge_fraction_per_second`；一次性收益仍由 `grant_resource` 接收已求值的 requested / scaled，不隐式套 CES。`spend_resource` 返回 paid / after / succeeded，余额不足不部分扣款，后续效果可按实际支付结果分支；免费施放的 paid 为 0。`refund_cost` 引用此前的成本或退款结果，按 paid × fraction 申请，同笔成本累计认领最多为 paid，溢出也占用额度。`grant_full_charge` 直接增加整数份数，保留部分进度并按容量裁剪。

普通 refund_cost 引用只在同一动作序列内有效，成本结果与累计认领跨条件分支和世界等待保留；未使用 `as` 的返还也按实际付款身份记录到 Frame.retainedResults，循环清理局部槽位不清除已认领额度，原始 cost 引用不能重复拿回已认领量。返还账户固定取自实际支付回执，免费 / 失败支付返还为 0。Frame 完成后释放记录；显式 retain_cost 已支持将剩余额度转交为有限期句柄，在 after / projectile 中共享 EffectState 账本；转交封存原回执，到期或 close 后撤销所有副本的退款资格。跨独立事件 / Buff 的引用与跨重启账本仍未实现，完整技能回能曲线及 parallel / linked 仍待实现。世界动作失败或取消不会隐式退款，退款资格由内容的结果条件决定。

0 / capacity 和显式 thresholds 是时间轴边界，`resource_crossed` 对自然恢复、入账与消费统一判断，初始化另发独立事实。中间整格必须声明；依赖能量值的速率条件也需声明断点，不从任意表达式自动推导。每段采用段起点速率，不声称支持任意连续状态相关的精确积分。测试已验证倍率到期分段、双向跨阈值、满值不储存与真实服务器 tick 驱动。

### 治疗、取整与输出统计

治疗结果分别保留 requested、offered、effective、overheal：requested 为请求值，offered 为经过加载器改量后进入生命写入的量，effective 为实际正生命增量，overheal 只记录超过当时生命容量的部分。取消和减量不能伪装成过量治疗；回调内发生的伤害不能从治疗量中倒扣。原版 float 精度及其他模组进一步改变写入时，不强求 requested = effective + overheal。

当前 `chorus:heal` 已走原版 `LivingEntity.heal`，在其直接生命写入处采集独立回执并隔离嵌套治疗；缺失 / 死亡目标不治疗，不增加 Absorption 或 Chorus 护盾。结果可直接绑定给后续动作，例如先 damage，再按 health_loss 治疗 self。两端已验证真实生命上限、玩家、周期信号及幂等；NeoForge 已验证取消、放大 / 缩小治疗量、嵌套治疗和回调内伤害。

持续治疗还须声明恢复生命还是护盾、是否启动自然恢复、是否与其他来源互斥；不能因为都写 HP/s 就直接相加。恢复延迟按受伤层和事件原因刷新，命中 overshield 不自动中断生命恢复。当前 schedule / cancel_timer 已让 JSON 声明离散周期并调用 heal；bundle.health_recovery 另提供连续生命恢复，使用 damage_per_second 单位，按持有者和 channel 互斥，先选 priority 再取最高速率，不同 channel 分别结算并保留来源。显式补盾另用 restore_shield，护盾连续回充用 shield.recovery，二者均保存容量变化并独立于生命治疗；护盾受击延迟可由事实与定时器组合。自然治疗观察、基础生命 / 护盾分层的自然恢复策略、恢复专用 Profile 尚未接入。

连续恢复按旧状态对 `[from, until)` 积分，Buff 到期也结算最后不足一周期的区间。时钟以 50 ms 网格提供最大采样间隔，其他事件 / 到期可以进一步分段；这个间隔是 Chorus 选择，不声称原作按 50 ms 恢复。先提交该时刻的领域状态，再执行保存了来源与金额的区间治疗命令；所有回执完成后，按队列先分发直接治疗 / 原版嵌套事实，再分发该时刻的生命周期、资源和定时信号，后续派生事实仍遵循广度优先顺序。反应读取边界后的状态，治疗取消 / 溢出不成为以后可以补发的恢复额度；暂停来源不积分，被覆盖来源继续计时。

`cure.json` 已通过双加载器验证 x1 / x2 / x3 的环境总量、50 / 100 ms 两次恢复与从激活起的 1 秒冷却。数值取快照 Solar D4，每级总量 60 [PvP 30] HP；示例采用 0.1 缩放，两次等量脉冲是 Chorus 的实现选择，原作更细的恢复曲线未知。`restoration.json` 已接入强度 / 环境速率、历史时间及与 Healing Rift 的恢复互斥；两端验证真实生命值、到期残段和 tick 驱动。快照只明确不叠加，示例同优先级取较高速率为暂定规则；Rift 满血补盾已按离散脉冲组合实现，但首次生成与满血切换时序仍待校准；Phoenix Dive 的 4+2 秒例外、真实技能来源仍待实现，完整内容验收未完成。

内部伤害、治疗、资源使用 double，固定归约顺序。资源加减、积分量与阈值截止时间使用有限 double 的规范十进制值运算，截止时间向上取到整数微秒，避免 `0.7 + 0.1` 未到 `0.8` 及截止时间多一微秒；这不改变 Profile 和状态存储的 double 精度边界。DSL 算术表达式的 add / mul 先按有限操作数的规范十进制计算，再返回 double，避免 100 × 1.1 的表示残差在上取整时多给一发；Profile 等其他阶段保持各自契约。弹药数量与层数是整数，各效果声明 floor / ceil / nearest；显示取整与实际扣除分开。Clown Cartridge 已由随机动作和 refill.ceiling 组合，完成实际换弹验收，分布与组合顺序仍待校准；Clown Cartridge、Rewind Rounds 等上取整写在对应效果里，不使用全局整数伤害。随机收益现有显式 sample_random 动作：版本固定的 RNG 种子 / 前后抽样序号随回执和 random_sampled 事实保留，游标属于域状态。均匀实数与无偏整数抽样已实现，数值查询只读取已抽出的结果，不会偷偷重抽；具体 D2 分布仍须内容证据，不能用范围或均值推断。

射速、装填、切枪、DOT、弹药返还与 Buff 覆盖都进入同一时间轴。窗口使用 `[t0,t1)` 且 t1>t0，DPS 为 `窗口内已提交的有效伤害之和 / 窗口时长`，另行报告理论伤害和过量伤害；不能用单发伤害乘理论射速代表完整输出循环。

### Minecraft 结算边界

- **生命值以原版为准**，所有生物（Chorus 敌人、原版和其他模组的生物、玩家）都一样。Chorus 只管护盾层（元素护盾、overshield）和目标方修饰。内容按 Minecraft 尺度编写，边界上不做单位换算。
- **阶段所有权**：Chorus 出伤先计算到 after_source；统一伤害入口接着计算目标修饰和护盾。命令携带已完成阶段，同一次伤害进入原版钩子时不得再次应用易伤等贡献。外部伤害也要根据明确资格应用攻击者上的输出削弱（如 Sever），不能只处理受击者效果。
- **伤害前钩子**：先应用 Chorus 的目标方修饰和护盾层（按「防御与分层血池」的预算算法逐层消耗），剩余伤害交给原版继续处理护甲、附魔、药水和伤害吸收，再扣生命。具体插入点以实现时的源码为准。
  - 当前护盾实际接在 `actuallyHurt` 的护甲计算前：此前已通过原版与加载器取消 / 免疫、物品格挡和无敌帧检查。玩家难度缩放、原版特殊修正、格挡和无敌帧增量先决定进入 Chorus 层的预算，避免取消攻击仍扣盾。完整 Chorus 目标方修饰尚未装配。
  - NeoForge：`LivingIncomingDamageEvent` 可取消或修改攻击；现有适配器在 `actuallyHurt` 的护甲前更新 DamageContainer，不能只改方法参数后让容器恢复旧数值。后置 `LivingDamageEvent.Pre` 修改盾后的伤害，不回滚已经消费的盾。实际 GameTest 已验证前置改量、ARMOR reduction modifier 和后置清零均保持该契约。
  - Fabric：API 的 `ALLOW_DAMAGE` 只能取消，现有 LivingEntity / Player Mixin 在护甲调用参数处处理盾后余量；已通过实际 GameTest。
- **伤害后**发布实际事实（实际扣掉的护盾和生命），在结算边界内跑完反应。
- 护盾计划只是候选写集。适配层必须区分攻击被取消、Chorus 护盾完全吸收、剩余伤害进入原版三条路径，确认后才提交并发布事实；护盾完全吸收也需要完成一次回执，不能依赖原版扣血回调必然出现。取消攻击不能发布计划护盾损失。当前实现先在世界侧本次结算的临时视图消费，使嵌套命中看到剩余容量，再把不可变写集随回执交给纯核心确认；故障后保留尚未协调的写集，不猜测成功或重试。
- Fabric `AFTER_DAMAGE` 不覆盖致死命中，且参数不是经过护甲、附魔后的最终生命损失。适配层需要统一收集实际护盾损失、原版 Absorption 损失、实际生命损失和最终死亡确认；图腾恢复生命不能用调用前后 HP 的净差冒充这一击的扣血量。当前 `MinecraftDamageExecutor` / `DamageCapture` 已采集显式请求及已注册维度内普通伤害的实际护盾 / HP / Absorption 损失、图腾保护与确认死亡，并通过双加载器 GameTest。
- **无敌帧**：原版生物受伤后约半秒内的后续伤害会被吞掉。把 Chorus 的伤害类型加进 `#minecraft:bypasses_cooldown` 标签（26.3 里这个标签是空的）。
- **元素伤害**用原版的数据包伤害类型（`damage_type`）定义动能、电弧、日光、虚空、静止、缠绕，其他模组按伤害类型标签就能识别。
- **精准命中**：原版碰撞箱只是一个长方体，按命中点高度判断。Chorus 敌人在定义里声明弱点区域；原版和其他模组的生物默认以眼睛高度附近为头部，可用数据映射覆盖。
- 原版致死、受伤反馈和击杀奖励只提交一次。世界操作失败时不能把计划伤害当成实际伤害发布，见「动作执行」。

### 数值资料、版本与冲突

每个规则集固定版本及数据引用，运行中的快照固定定义版本；热重载不修改已发射对象持有的旧快照。每条参数可记录：

```text
ruleset_version / source_reference / verified_patch
value_basis / measurement_context / included_factors
confidence = official | measured | fitted | assumed
known_conflicts / selected_resolution
```

来源只支持它实际覆盖的字段，不代表整项机制都已验证。表格中的 `?`、空白、相互冲突的值保留为 unknown / conflict；内容加载时，要么提供明确标为 assumed 的选值，要么禁用依赖该值的未完成内容，不能静默当 0 或 1。

命运 2 规则集的资料来源、核查日期和已知冲突见 [d2-ruleset.md](d2-ruleset.md#数值资料与冲突)。

## 技能

技能 = Java 写的"种类" + JSON 写的"定义"。参数暴露成可修饰的技能属性，运行时发出带技能 id 的事件，并且可以被整个替换。表里 77 个星象有 51 个、138 条金装护甲描述有 79 条会点名修改技能，所以技能从一开始就要设计成可被修改的。

当前已有 `EffectProgram.abilities` 的即时动作入口：基础槽位选择、来源 / Buff 的分级条件替换、参数和成本 Profile、实际支付后派发 ability_used，以及共用 DSL 的 on_use。服务端命令可选择 / 施放，世界效果已通过双加载器验证；按键 / 网络、D2 专用投掷物或移动种类、子职业 / 解锁、持久化与 parent 继承仍未实现。下面的完整种类示例描述目标结构，当前可解析字段和命令以 [技能入口](engine-data-packs.md#技能选择与施放入口) 为准。

### 种类与定义

- **种类**（Java，数量少）：投掷型手雷、近战突进、闪避/冲刺、放置物（屏障、裂隙）、漫游超能、钩爪、滑翔、瞬移。每个种类声明自己有哪些参数、会发出哪些事件。
- **定义**（JSON，数量多）：用哪个种类、参数、冷却、充能次数、元素、标签，以及在关键时刻执行的动作。

```json
// chorus:arcbolt_grenade（全部数字仅为结构示意，实际值由已校准的规则集提供）
{ "kind": "chorus:projectile_grenade", "slot": "grenade", "element": "chorus:arc",
  "resource_profile": "chorus:grenade_regen", "base_cooldown_stat_zero": 150,
  "chunk_scalar": 0.75, "charges": 1, "recharge_policy": "sequential",
  "params": { "fuse": 0.6, "radius": 6 },
  "on_detonate": [
    { "type": "chorus:damage", "targets": { "type": "chorus:area", "radius": 6 }, "amount": 120, "damage_type": "chorus:arc" },
    { "type": "chorus:apply_status", "targets": { "type": "chorus:area", "radius": 6 }, "status": "chorus:jolted" } ] }
```

当前目标选择可声明球、世界竖直圆柱或定向圆锥，并选择脚底 / 身体 / 眼睛取样点以及方块碰撞视线。方向与位置可先捕获、再供延迟动作使用；不会在延迟结束时重新读取施法者朝向。视线先筛选，随后排序 / 截断；默认不启用。它们不是命运 2 全部命中几何的自动映射，具体边界见 [空间查询](engine-data-packs.md#区域形状方向快照与视线)。

通用物理飞行现由 `projectile / as / do` 步骤表达：发射后保留已编译动作体和捕获结果，实体 / 方块碰撞、到期或未知地形提供类型化接触观察。每次接触结果可作为单目标集合或范围查询位置；来源卸下后仍可完成，原运行时停止时移除。速度 / 重力 / 阻力、扫掠碰撞、墙面反弹、实体穿透、每目标命中上限及两端实体注册已验证；每次接触独立执行动作体，终止由 terminal 标志区分。可选 tracking 策略支持保留目标身份的限速追踪，以及非终止接触后的最近选敌 / 即时转向；阵营、半径和视线取当前世界状态。可选 destination 另支持固定实体身份、返回当前施放者位置与 ARRIVED / TARGET_LOST 终态，抵达与伤害命中分开；destination.catch 已支持接回窗口与独立 CAUGHT 结果；共享弹跳预算、持久化和 D2 数值校准仍待实现。见 [物理投射物](engine-data-packs.md#物理投射物与碰撞动作)。

### 修改技能的三个层次

| 层次 | 表里的例子 | 机制 |
| --- | --- | --- |
| 改参数 | Getaway Artist 的一部分：Arc Soul 裂隙刷新到 20 秒而不是 15 秒（它还把长按手雷替换成召唤，并加了返能规则，三个层次都用到）。Abeyant Leap：悬浮 +3 秒，多 2 条鞭子 | 对技能参数挂修饰 |
| 加行为 | Athrys's Embrace：飞刀多弹一次，连续精准命中后强化。Mataiodoxía：奥术针变成穿盾 | 针对技能事件写规则；弹跳次数、能否穿盾也作为参数暴露 |
| 整个替换 | Arbor Warden：手雷换成 Barri-Nade。Hoarfrost-Z：屏障换成 Glacial Fortification。Celestial Nighthawk：黄金枪换成单发高伤版 | 效果包里的 `ability_overrides`；改版技能用 `parent` 继承原定义 |

```json
{ "slot": "grenade", "replace_with": "chorus:barri_nade", "priority": 0 }
{ "ability": "chorus:golden_gun", "replace_with": "chorus:golden_gun_nighthawk" }
```

装配解析：基础装配（子职业选择）→ 按优先级应用各效果包的替换 → 实际生效的技能。变化时重算，并同步到客户端供 UI 显示。当前同优先级解析会收集全部匹配的候选；若指向不同定义则拒绝施放，同一目标可合并。金装护甲限 1 件不能替代这项冲突校验。

替换优先级从低到高：基础装配 → 星象的条件替换（如滑铲中近战变闪电激涌）→ 超凡 → 超能。条件替换在输入时现算，不进缓存。当前实现不内置 Destiny 优先级数字，由内容定义 priority；每级匹配本级开始时的技能，再传给更高一级。实际定义、参数及成本在接受施放时固定；基础选择保留。

### 能量与充能

- 当前即时动作定义可引用成本账户及成本 Profile；先按最终技能检查资格和能量，成功扣费后才派发施放事实。免费施放 paid 为 0，不能从退款创造能量；失败世界回执保留支付，不自动重放。on_use 可在本帧内使用 cast_cost 退款，或显式 retain_cost 后由延迟 / 飞行共享有限期额度；任意独立事件的按施放查找仍待实现。
- 每个技能槽引用恢复 Profile，明确 0 属性基础冷却、CES、充能次数、每格进度与回充策略，见「资源恢复与收益」。改冷却不自动改 CES，两者都是独立数据。
- 被动恢复、额外基础恢复、总速率倍率、固定每秒收益分通道计算。旧文案里的“基础回复速度 +400%”必须先查清基础冷却和属性基准，不能直接贴到当前公式。
- Demolitionist 等使用 grant_chunk；按属性与适用的 CES / CMS 缩放。完整充能、按实际成本退款和固定设置使用不同动作。
- Super 被动冷却不由 Super 属性缩短；伤害 / 击杀 / 拾球的主动收益走单独定义。技能消费返回 cost receipt，记录实际支付量、免费施放和充能来源，供返还规则使用。

### 超能

- **一次性超能**（Nova Bomb）：普通技能。
- **漫游超能**（Stormtrance、Arc Staff）：释放时给玩家挂一个 buff。buff 的效果包里有减伤修饰、技能替换（近战、手雷换成超能招式）和超能自己的规则。buff 结束，超能就结束。

### 子职业

子职业定义：元素、职业，以及可选的超能、手雷、近战、职业技能、跳跃方式、星象、碎片。技能、星象、碎片都按元素打标签，放在共用池里；子职业只是按条件筛选，Prismatic 就是跨元素筛选。玩家的选择存在实体附加数据里，加载时校验"选中的碎片数 ≤ 星象提供的槽位数"。

## 世界物体

能量球、冰影碎片、离子痕迹、涡流、虚空锚点、缠结、屏障、静止水晶，都是一个通用实体 `chorus:effect_entity` 加上 JSON 里的组件。组件用 Java 实现、数量少；组件要执行的动作用同一套 DSL。

| 组件 | 作用 | 表里的例子 |
| --- | --- | --- |
| `lifetime` | 存在多久，到期时触发动作 | Deadfall 锚点 12 秒，拴住的敌人每死一个 +0.5 秒，最多 25 秒 |
| `homing` | 朝目标移动 | 离子痕迹以 6 m/s 飞向使用者 |
| `pickup` | 符合条件的实体碰到时，执行动作并消失 | 能量球、冰影碎片、Void Breach |
| `aura` | 每隔一段时间对范围内的实体执行动作 | 涡流场持续伤害；锚点给 12.5 米内的敌人挂"被拴住" |
| `force` | 对范围内的实体施加速度（拉向中心或推开） | 奇点把敌人吸进去；Ward of Dawn 把 15 米内的敌人拉过来 |
| `collision` | 能不能挡路、站上去、被打坏 | 屏障、静止水晶 |

```json
// chorus:void_vortex（数字只是示意）
{ "lifetime": 3.5,
  "components": [
    { "type": "chorus:force", "radius": 5, "affects": "enemies", "mode": "pull_to_center", "strength": 0.25 },
    { "type": "chorus:aura", "radius": 5, "period": 0.5, "affects": "enemies",
      "do": [ { "type": "chorus:damage", "amount": 40, "damage_type": "chorus:void" } ] } ] }
```

- **吸附范围是收集者的属性**（`chorus:pickup_attraction_radius`），物体的 `homing` 只负责读取。扩大吸附范围 = 挂一个属性修饰。拾取时发出 `chorus:pickup` 事件。
- **参考原版 `ExperienceOrb`**：26.3 里它会飞向 8 格内最近的玩家，相邻的球会合并（`scanForMerges`）。能量球和碎片也要合并，防止实体满地。但只合并视觉实体：逻辑上的数量、能量值、生成者都要保留，否则拾取一次触发几次效果会变（方向，未验证）。
- **拴住类效果**（Deadfall、Shadowshot）：锚点的 aura 给敌人挂 tethered，来源记为锚点。buff 自带削弱、压制、向来源靠拢的拉力，以及按明确伤害投影的 50% 分享给 same_source 的其他敌人；Deadfall 的 Bodyshot (Base) 投影口径待实测，不能直接取最终伤害。持有者死亡时，延长 buff_source 的寿命。
- **用实体，不用方块**：临时构造物做成有碰撞的实体（同原版的船、潜影贝），不改世界、不和领地插件冲突。短命物体不写存档。
- **性能**：`aura` 和 `force` 每 5～10 tick 扫一次，用原版 `level.getEntitiesOfClass(...)` 查询，限制场上物体数量。
- **怪物 AI 会和力场抢控制**：强牵引同时给目标短暂的压制，停掉寻路。Boss 靠 `knockback_resistance` 或专用标签免疫。
- **外观**：先用粒子和原版 `Display` 实体（`item_display`、`block_display`），前期不写自定义渲染器。

object 层把自己的动作类型（`spawn_object`）注册进 rule 层的注册表，所以依赖方向仍然是单向的。

## 移动与执行侧

Minecraft 里玩家的移动以客户端为准，所以每种动作都要明确在哪一侧执行。DSL 永远不逐 tick 模拟物理，只提供参数、对事件做出反应。

| 类型 | 执行侧 | 例子 | 实现要点 |
| --- | --- | --- | --- |
| 默认 | 只在服务端 | 伤害、buff、能量、生成世界物体 |  |
| 改属性 | 服务端修改，自动同步，客户端物理直接生效 | 滑翔（降低 `gravity`、`air_drag_modifier`）、加速、取消摔落伤害（`fall_damage_multiplier = 0`） | 都是 26.3 的原版属性 |
| 一次性冲量 | 服务端发起 | 急切刀锋、闪避、Lightning Surge | 照抄原版 `ApplyEntityImpulse`：直接给玩家发速度包，并调用 `applyPostImpulseGraceTime(10)` |
| 持续的自身移动 | 客户端预测 + 服务端同步模拟 | 钩爪、Strand 摆荡 | 同一个状态机两端运行，参数来自同步的定义；期间服务端放宽移动检查 |
| 移动他人 | 服务端 | 吸怪、击退 | 目标是玩家时调用 `markHurt()`，26.3 里它设置 `syncVelocity` 并发送速度包 |

原版已经有急切刀锋的等价物：1.21.11 长矛的 Lunge 附魔（`lunge.json`），在 `post_piercing_attack` 时执行 `minecraft:apply_impulse`。它的宽限期可以让服务端的 "moved wrongly" 检查跳过，玩家不会被拉回原位。

### 钩爪拆解

- **种类**：Java 状态机「发射 → 钩住 → 拉动 → 结束（到达、取消、超时、被挡住）」。
- **参数**：射程、拉动速度、最长时间、冷却，可被星象和金装修改。
- **事件**：`grapple_attached`（携带钩住的方块、实体或世界物体）、`grapple_end`。
- **规则**：表里的 "Grappling a Tangle fully refunds Grenade Energy" = 钩住缠结时使用 grant_full_charge，补回一份手雷充能，不走普通 chunk 缩放；消费回执标记免费使用供 Kickstart 等规则判断。
- **钩爪近战**：拉动期间挂 `grappling` buff，buff 携带技能替换（近战 → 钩爪近战），和漫游超能同一机制。

### 坑

- 服务端改了玩家速度，却不同步、不给宽限期 → 玩家被拉回，日志刷 "moved too quickly"。AI 很可能写旧字段名 `hurtMarked`。
- 摔落伤害全局决定一次，不要每个技能各自处理。

## 敌人与 NPC

敌人 = 少量 Java 实体种类 × 大量 JSON 敌人定义 × 用规则引擎写的护盾、冠军和词缀。NPC 用同一套实体框架，对话和商店直接复用原版的数据驱动系统。

### 命运 2 的敌人概念怎么落地

| 概念 | 命运 2（数据取自 Compendium） | 在 chorus 里 |
| --- | --- | --- |
| 阵营与兵种 | 堕落者的 Dreg、Vandal、Captain…… | 敌人定义（JSON） |
| 等级 | 小怪、精英、小 Boss、Boss、冠军 | 定义里的字段；决定血条颜色和 `by_rank` 取值 |
| 档次 | Tier 1–4 按血量分；精英 +1；小 Boss、冠军 T5；Boss T6 | 由等级和兵种算出；影响能量返还和词条触发 |
| 元素护盾 | 5 秒不受伤开始回充；元素伤害对盾 2 倍、同元素 3 倍（相对动能） | 护盾 buff：额外血池 + 元素倍率 + 回充规则，破盾发 `shield_broken` |
| 冠军 | 势不可挡：未眩晕时 70% 减伤，可被致盲、点燃、冰冻、悬浮眩晕。过载：回血、频繁放技能，可被 Jolt、Slow、压制眩晕。屏障：血量到阈值开盾并回血 | buff，自带规则；眩晕是另一个 buff，携带"禁止行动"和移除减伤 |
| 词缀（Bane） | 治疗：20 米内友军回血。护盾：20 米内友军得护盾。隐匿：14 米内友军隐形。均可被压制关掉 | buff，带 `aura` 给范围内友军挂 buff |
| 弱点 | 精准伤害、爆头 | 定义里的弱点区域（碰撞箱的高度区间，或多个盒子） |

### 实体：种类 × 定义

- **实体种类**（静态注册，Java）：按行为骨架分，数量少。例如人形远程、人形近战、飞行、重型、炮台、Boss。
- **敌人定义**（数据包注册表 `chorus:combatant`）：用哪个实体种类、阵营、等级、血量、护盾、技能列表（复用技能系统）、AI 参数、弱点、模型贴图、掉落。
- 实体生成时记录定义 id 并同步给客户端，客户端按 id 渲染。原版先例：狼、猫、猪的变种也是"实体种类写死 + 变种数据驱动"（26.3 的 `wolf_variant`、`pig_variant` 等注册表）。

### AI

- 原版有两套 AI：**Goal**（目标选择器，大多数怪物用）和 **Brain**（记忆 + 传感器 + 活动，村民、猪灵、监守者用）。先用 Goal：简单、资料多、AI 写得好。
- 敌人定义里的 AI 参数（交战距离、是否绕圈、低血是否逃跑）去配置 Goal。
- 释放技能是一个通用 Goal：满足条件就释放。条件和效果用规则 DSL。敌人和玩家用同一套技能、buff、规则。比如 Captain 受伤时瞬移是一条规则，Wizard 给友军加盾是一个 `aura`。

### 血量、血条、生成

- **血量体系**：生命值用原版，内容按 Minecraft 尺度编写（`max_health` 上限 1024 足够），命运 2 的数值按比例缩放即可，见 d2-ruleset.md 的「换算到 Minecraft」。元素护盾、overshield 是 Chorus 的护盾层。这样其他模组的伤害、治疗、血量显示都能直接作用于 Chorus 敌人。
- **血条**：Boss 用原版 Boss 栏（`ServerBossEvent`）。普通敌人做头顶血条，客户端渲染，需要同步血量、护盾、等级。
- **生成**：主要敌人不走原版自然生成，由遭遇战和巡逻区域按数据分波生成，遭遇战结束时清理。巡逻小怪可以在自定义维度或生物群系里用原版生成机制。
- **外观和动画**是最大的成本。GeckoLib 已支持 26.3（Fabric 和 NeoForge），配合 Blockbench 做模型和动画。前期可以用原版人形模型换贴图。

### NPC

- **实体**：同一框架，不攻击、不消失、可交互。
- **对话**：用原版的数据驱动对话框（`dialog` 注册表，有通知、确认、多按钮等类型）。按钮可以执行命令，或给服务端发自定义动作，由规则引擎接住（比如接任务）。
- **商店**：26.x 的村民交易已经数据驱动。`villager_trade` 是一条交易（给什么、要什么、次数），`trade_set` 从标签里抽若干条。NPC 商人直接复用原版交易界面。
- **任务**：以后做，基于规则引擎的事件计数。

### 建议加入金标准测试集

- 势不可挡冠军：未眩晕减伤、多种眩晕来源。
- 带电弧护盾的 Captain：护盾血池、元素倍率、回充、`shield_broken`。
- 治疗词缀：`aura` 给友军回血，被压制时关闭。
- 商人 NPC：对话框 + 原版交易界面。

### 待定

- Chorus 护盾层与原版护甲、伤害吸收（Absorption）的先后顺序。
- 是否引入光等和光等差。
- 模型动画：先用原版模型，做敌人时再决定是否引入 GeckoLib，见「美术与表现」。

## 与原版和其他模组联动

做成 Minecraft 模组的意义在于组合：用命运 2 的武器和技能打原版或其他模组的怪物，原版和其他模组的伤害也能打到 Chorus 的护盾。

- **伤害走原版**：Chorus 伤害对任何 `LivingEntity` 都通过 `hurtServer` 提交，带 Chorus 自己的伤害类型。目标的原版护甲、保护附魔、抗性药水和其他模组的减伤照常生效。
- **生命值以原版为准**：其他模组的吸血、治疗、伤害数字、血条都能直接工作。Chorus 只在上面加护盾层，见「Minecraft 结算边界」。
- **外部伤害也进 Chorus**：原版或其他模组造成的伤害同样经过护盾层和 Chorus 的目标方修饰（比如 Weaken 的 +15% 承伤），并作为 credit 为空的 hit 事实进入队列。"受到任何伤害"类的规则（Jolt 阈值、冰冻碎裂）对它们照常生效。
- **状态可以挂在任何生物上**：buff 是附加数据。能用原版手段表达的效果就用原版手段，比如减速用移速属性修饰。需要接管 AI 的效果（冻结、压制、致盲）对非 Chorus 生物要么用 mixin，要么降级，由效果定义声明降级方式。
- **目标分类**：`by_rank` / `by_tier` 对非 Chorus 生物用数据映射（实体类型或标签 → 等级），比如把末影龙、凋灵、监守者映射为 Boss；没有映射时用默认档。
- **词条挂到别的武器上**：词条是数据组件，原则上可以挂到原版或其他模组的武器上，命中和击杀按 via = 那件物品归因（方向，未验证）。

以上都是模组的默认值，全部可以由数据包覆盖，见「分发形态」。

## 分发形态：引擎、内容包、整合包

同一套引擎和内容支持两种玩法：模组默认联动优先；整合包用覆盖数据包把开关拨过去，得到更接近命运 2 的完整体验。所以"适配 Minecraft"的选择都做成数据开关，不写死在代码里。

| 层 | 是什么 | 怎么改 |
| --- | --- | --- |
| `chorus` | 引擎：规则、buff、数值、伤害结算、世界物体 | Java 只写种类 |
| `chorus_d2` | 命运 2 内容：武器、词条、技能、敌人定义、美术 | 可开关的内置数据包 + 资源包 |
| 整合包 | 遭遇战脚本、地图、"体验模式"覆盖包、世界配置 | 只用数据包、资源包和配置 |

`chorus_d2` 注册成可开关的内置数据包：Fabric 用 `ResourceLoader.registerBuiltinPack`（`PackActivationType` 可选 `NORMAL`、`DEFAULT_ENABLED`、`ALWAYS_ENABLED`），NeoForge 用 `AddPackFindersEvent`。整合包可以在它之上叠覆盖包，也可以换成自己的内容。

### 开关

| 选择 | 模组默认（联动） | 整合包（体验） | 开关在哪 |
| --- | --- | --- | --- |
| Chorus 伤害吃不吃原版护甲、附魔、抗性 | 吃 | 不吃 | 原版伤害类型标签 `#minecraft:bypasses_armor`、`bypasses_enchantments`、`bypasses_resistance`、`bypasses_effects`：数据包把 Chorus 的伤害类型加进去即可 |
| 数值缩放因子 | 以原版生物定手感 | 更接近命运原值 | 规则集参数，见 [d2-ruleset.md](d2-ruleset.md#换算到-minecraft) |
| 玩家的生命和护盾 | 原版 20 点血 | 守护者式"护盾 + 生命 + 自动回复" | 给玩家挂常驻 buff（护盾层 + 回复规则），并关掉游戏规则 `natural_health_regeneration` |
| 原版生物的 Rank / Tier | 映射表 | 基本只有 Chorus 敌人 | 数据映射 |
| 数值精度 | d2-ruleset 的参考值 | d2-ruleset 的校准值和置信度 | 同一份资料，用法不同 |

### 按区域切换规则集

伤害上下文里的 `activity_ruleset` 不只用于 PvE / PvP 分支，也可以按区域或遭遇战切换：遭遇战区域里用"体验"规则集，区域外用"沙盒"规则集。同一个存档里既能拿命运的武器打原版僵尸，也能进副本打一场按命运数值走的遭遇战。

这是后续能力。v0 先固定整个世界的玩法模式；同一伤害类型的标签、自然回复游戏规则不是每个区域的独立开关。区域模式需要不同伤害类型变体、按实体控制回复，并明确跨区域攻击的规则集选择与进出区域时的护盾转换，不能只改变 activity_ruleset 就声称切换了全部行为。

### 遭遇战和地图

- **遭遇战是数据**：状态机加同一套规则 DSL，比如"阶段 2：打掉三个护盾节点，Boss 进入可伤害状态"。做遭遇战不需要写 Java。进度放在世界存档数据里，见「数据与状态放在哪」。
- **地图**用原版工具或 WorldEdit 建。刷怪点、触发区、阶段机关这些锚点用标记实体或结构方块放置，由数据引用。
- **版权**：私下玩没有问题。公开发布"完整命运 2 体验"整合包的风险比发布模组大得多：名字可以靠替换 `chorus_d2` 解决，但照着命运地点还原的地图和剧情，不是改个名就能脱敏的。

## 独立装备系统与界面

装备存储、玩法效果、穿戴外观是三层不同职责。目标是由 Chorus 提供独立的真实装备容器，`chorus_d2` 定义 helmet / arms / chest / legs / class_item 五个护甲槽和三武器槽；引擎不写死这些内容 id。命运式槽位无法一一映射到原版四个护甲槽，不能仅换 UI 皮肤，也不能向原版槽复制一套“影子装备”。可以复用 ItemStack、容器与 Slot 基础机制。

已实现装备元数据到效果来源的投影：`EquipmentSchema` 声明槽位 / 原型 / 固定效果 / 插槽选项 / 标签数量限制，`Loadout` 保存实际物品实例引用和持握槽，`EquipmentChange` 验证旧装配后整体更新来源与武器 Buff 生命周期。`equipped` 来源收枪保留，`drawn` 来源只在选中时绑定；`weapon_drawn` 是独立条件。移动同一实例不重置效果，词条替换使用旧来源快照清理后初始化新来源。`SourceBatch` 提供非装备来源的同类原子更新。字段与宿主入口见 [数据包说明](engine-data-packs.md#装备定义与原子装配投影)。

这层不可变元数据不保存真实 ItemStack，也不证明物品所有权。现在由 `PlayerEquipment` 独立容器拥有实际物品，提供带 revision 校验的背包交换 / 移槽 / 持握接口，并通过 `EquipmentChange.Commit` 在物品转移后、反应前同步元数据。玩家 NBT 保存 / 加载、死亡掉落、keepInventory、消失附魔和 respawn 所有权转移已接线；定义缺失时停用投影并保留可取回的物品。裸装配接口仍只供可信宿主使用，不能直接接受客户端提交的任意物品实例。

运行时在事件边界和 tick 同步实体容器，死亡或离开维度后解除来源；独立容器已通过会话 / 展示序号校验的协议同步，最小配装页提供实际交换、移槽和持握操作；活动 Buff / 技能迁移、主手 / 枪械联动和完整 D2 UI 仍未完成。NeoForge 死亡装备通过外层 LivingDropsEvent 统一处理，不走会重置掉落收集器的普通 toss 路径。具体命令、失败后的物品保留语义和验证边界见 [实际容器说明](engine-data-packs.md#实际物品容器与装备命令)。

- 装备槽的接受条件、数量、互斥与金装限制放在 data，由服务端校验。独立容器负责保存、同步、装备事务与死亡掉落；预设引用实际装备实例，不复制物品。
- 装备生效时把效果包加入静态来源编译；需要原版护甲、韧性等时显式投影到 Attribute。原版附魔、耐久、自动穿戴、其他模组的槽位检测需要单独适配，不能假设自动支持。
- 外观绑定身体部位：胸甲与臂铠可以分别控制躯干和手臂，腿甲可覆盖腿与脚，职业物品可采用不同部位。绑定规则与模型在 assets；实际渲染组件由通用客户端代码实现。
- 联动模式可保留原版装备，明确两套装备的效果叠加策略；完整体验模式可在服务端限制混穿。隐藏原版 UI 不能代替玩法限制。

HUD、配装页、装备详情、技能选择页的具体布局和美术属于 `assets/chorus_d2/`。通用客户端代码计划提供资源条、技能槽、装备槽、物品卡、属性比较、人物预览，以及有限的布局、输入与动画能力。当前已实现 equipment_two_panel 配装模板、物品图标 / 提示、分页槽位和按钮操作；presentation 指向资源包中的颜色 / 槽位排序定义，D2 配色与翻译位于 assets/chorus_d2。这些 JSON 是 Chorus 自己读取的格式，不是 Minecraft 自动支持的任意界面描述。

```text
UI 操作 → 类型化请求 → 服务端校验并更新装配 → 效果包重编译 / 技能解析
        → 展示状态同步 → HUD 与页面刷新
```

- 技能候选、解锁与兼容条件、星象提供的碎片槽数放在 data；界面只展示并提交选择。基础装配与临时实际生效技能分开同步，超能 / 超凡替换不能写回基础选择。
- 血量、护盾、充能、当前技能与可见 Buff 是可重建的同步状态，不能只靠 cue 增减；cue 用于闪光、音效和通知。客户端平滑显示不替代服务端技能可用性判断。
- 默认 HUD 与原版共存，完整替换模式逐项配置原版元素的显示；保留食物、氧气、坐骑等必要信息。允许玩家调整缩放、安全边距与文字大小。
- 先实现有限模板和组件，再逐步开放布局。内容包关闭或资源缺失时通用基础界面仍可操作。v0 仍以协议和最小调试界面为先，完整 UI 与穿戴美术不作为已完成能力。

## 美术与表现

美术全部放在 assets（客户端资源包）里。玩法数据只引用表现 id（cue），客户端按 id 解析成粒子、音效、模型和动画。优先使用原版已经数据驱动的能力，自定义渲染代码放到最后：26.x 的渲染层正在变动（`GuiGraphics` 改名为 `GuiGraphicsExtractor`、实验性的 Vulkan 后端），自定义渲染最容易随版本更新失效。

`chorus_d2` 的技能 / perk 图标可从用户提供的 Compendium 在线原表提取。首批已导入 Clown Cartridge、Kill Clip、Overflow、Voltshot、Marksman's Dodge、Healing Rift 六张 PNG，位于 `assets/chorus_d2/textures/gui/`；完整 HUD / 技能页与这些图标的绑定仍待实现。图标引用使用资源 id，运行时不访问在线表。素材单独记录抓取时间、原表坐标、尺寸与哈希，保留可离线核对的来源 HTML；网页预览图不视为最高分辨率原图。导入流程与清单见 [Compendium 来源说明](../data/compendium/README.md#在线原表与图标)。在线素材更新不会自动更新既有数值快照或效果验收状态。

### 表现 id（cue）

```json
// data：规则只写"在受击者身上播放这个表现"
{ "type": "chorus:play_cue", "cue": "chorus_d2:lightning_surge/strike", "at": "victim" }

// assets/chorus_d2/cues/lightning_surge/strike.json：客户端按 id 解析
{ "particles": [ { "type": "minecraft:electric_spark", "count": 40, "spread": [0.3, 2, 0.3] } ],
  "sound": "chorus_d2:ability.lightning_strike",
  "screen_shake": 0.2 }
```

- 服务端只发送 cue id、位置和少量参数（强度、颜色、目标实体），不知道美术长什么样。思路同 Unreal GAS 的 GameplayCue。
- 资源包可以覆盖任何 cue；缺失的 cue 不播放，不报错。
- cue 是通知，可以延后发送，不参与结算边界（见「运行时：控制流」）。

### 分阶段方案

| 类别 | 先用（原版能力，几乎不写代码） | 以后 |
| --- | --- | --- |
| 技能特效、世界物体 | 粒子 + 音效 + 展示实体（`item_display` / `block_display`）。展示实体自带变换与位移插值（`interpolation_duration`、`teleport_duration`），服务端改一次变换，客户端自己平滑过渡 | 自定义粒子贴图；着色器类特效等 26.x 上有可用的渲染库再做 |
| 枪械、装备模型 | Blockbench 做 Java 物品模型（1.21.11 起模型元素旋转不再受限）。物品模型定义按组件切换外观：`condition`（`has_component`、`component`、`using_item`、`keybind_down`）、`select`（`custom_model_data`、`component`）、`range_dispatch`（`use_duration`、`cooldown`） | — |
| 第一人称枪械动画 | v0 不做。第一步只做模型切换表示换弹、开火时镜头上抬、开镜缩小视野（NeoForge 有 `ComputeFovModifierEvent`、`ViewportEvent`；Fabric 需要 mixin） | GeckoLib 物品动画；完整的第一人称手臂加枪骨骼，等渲染层稳定后再做 |
| 敌人模型和动画 | 原版人形模型换贴图 | GeckoLib + Blockbench；AzureLib 备选 |
| HUD 与界面 | GUI 精灵图的 `.mcmeta` 缩放（`stretch`、`tile`、`nine_slice`）和帧动画；HUD 布局写成 assets 里的 JSON，读取数据定义的资源和 buff，不在代码里写死"超凡条"；补间按 partialTick 插值 | — |
| 音效 | `sounds.json` + `.ogg`，按 id 播放，不需要逐个注册 | — |

### 26.3 上的库

2026-10-09 在 Modrinth 和 GitHub 核查：

| 库 | 状态 |
| --- | --- |
| [GeckoLib](https://modrinth.com/mod/geckolib) | 5.5.7（2026-09-22）支持 26.3，Fabric 与 NeoForge |
| [AzureLib](https://modrinth.com/mod/azurelib) | 有 26.3 版本 |
| [owo-lib](https://modrinth.com/mod/owo-lib) | 只到 26.2，且只支持 Fabric；多加载器 UI 自己写一层薄的 |
| [Veil](https://modrinth.com/mod/veil) | 只有 1.21.1 |
| [Animated Java](https://github.com/Animated-Java/animated-java/releases) | 发布说明写到 26.2；用展示实体拼骨骼动画，面向地图作者 |

### 原则

- 风格保持原版：16x / 32x 像素贴图、方块造型，统一且省工。
- 命运 2 的模型、贴图、音效不能直接使用，美术必须原创（见「开放问题」的知识产权一条）。
- 先做 cue 这一层，再用占位美术把玩法循环跑通；真正的美术投入放在玩法定型之后。

## 数据与状态放在哪

代码里只注册"种类"，内容全部走数据包注册表；持久属性放物品，运行时状态放实体。

### 注册表

| 东西 | 注册表 | 原因 |
| --- | --- | --- |
| DSL 节点类型（事件、条件、动作、数值、组件、技能种类） | 静态，Java | 它们是代码 |
| 物品类、实体类型、属性、数据组件类型、附加数据类型 | 静态，Java | Minecraft 要求 |
| 词条、模组、碎片、星象、金装、神器 | 数据包注册表 | 内容，可被数据包覆盖 |
| buff 定义 | 数据包注册表 | 客户端显示需要定义 |
| 技能定义、子职业 | 数据包注册表 | 客户端预测和 UI 需要定义 |
| 世界物体定义 | 数据包注册表 |  |
| 武器原型（射速、弹匣、词条池） | 数据包注册表 |  |
| 元素伤害类型 | 原版 `damage_type` |  |
| 属性曲线、CalculationProfile、环境与资源 Profile | 数据包注册表 | 固定规则集版本、数值基准和来源；客户端只使用同步后的显示 / 预测数据 |
| 掉落与词条 roll 规则 | 数据包（可复用战利品表） |  |
| 表现（cue）定义 | 资源包（assets），客户端解析 | 服务端只发 id；可被资源包覆盖 |

客户端需要的内容定义以同步注册表或明确的同步协议提供。当前 `chorus:effect_program` 使用服务端可重载注册表；26.3 两端 API 的可重载注册表不自动同步到客户端，不能把支持 `/reload` 等同于已经同步。配装页已有独立的最小展示快照协议，包含槽位、物品组件、持握、背包、运行状态与主题 id；技能 / HUD 所需的定义与状态同步仍待实现。已有维度运行时固定旧 CompiledEffects 对象，目录重载只影响后续启动；活动迁移、多版本来源及持久化快照尚未实现。

### 运行时状态

| 放在哪 | 放什么 | 注意 |
| --- | --- | --- |
| 物品上（数据组件） | 随机出的词条、强化、大师级、击杀数 | 不放每帧都在变的东西，否则频繁同步、物品比较失败 |
| 实体上（附加数据） | buff 实例、护盾层、资源与每格充能进度、子职业选择、编译后的规则缓存 | 只同步 UI 需要的部分（可见 buff、护盾、能量条、实际生效的技能） |
| 维度运行时中的弹药账户 | 按稳定武器身份保存弹匣、基础容量、有限 / 无限储备 | 当前仅内存；物品转移 / 销毁 / 跨维度与 NBT / HUD 接线待实现，不能因来源解绑重置弹数 |
| 结算期 / 活跃对象引用 | 执行帧、操作回执、来源快照、计算轨迹 | 固定定义版本；完成后的诊断轨迹可有保留上限，不能用轨迹上限截断玩法事件 |
| 世界上（存档数据） | 遭遇战进度 |  |
| 不存档 | 世界物体 | 短命，重进世界即消失 |

## 运行时：控制流

引擎是事件驱动的：每 tick 只处理和时间流逝有关的事，绝大部分逻辑在事件发生时一次跑完一整串级联。

### 四种运行时机

| 时机 | 做什么 | 例子 |
| --- | --- | --- |
| 装备变化时（编译） | 收集静态层的效果包，解析技能装配，建事件索引，归类修饰 | 换金装、换碎片 |
| 事件发生时（反应） | 找到监听该事件的规则，判断条件，按顺序执行动作；动作产生的新事件入队 | 击杀、命中、拾取能量球、放技能 |
| 结算伤害时（查询） | 沿分量 Profile 生成 DamagePlan，提交后返回 DamageResult | 一次近战打多少；每层血池损失多少 |
| 每 tick | buff 到期与衰减、资源回复、低频采样、对齐 | 冰霜护甲掉层、技能能量回复、"被包围"采样 |

修饰是纯查询，规则是反应。进入查询前先追赶到当前逻辑时间，冻结只读视图；Value.eval 和数值归约本身不能触发状态变化或世界操作。提交后的事实进入队列，在结算边界内排空。

### 一个 tick 的顺序

26.3 服务端每 tick 先处理排队的网络包，再 tick 各维度，再 tick 玩家连接（`MinecraftServer.processPacketsAndTick`、`tickChildren`）。下面描述完整装配后的目标顺序，引擎 tick 计划挂在整服 tick 末尾。当前实现是独立的维度运行时，在各维度 tick 末尾推进该维度的逻辑时间；尚未实现这里的整服输入装配、低频采样及技能 / HUD 同步；独立配装页订阅在维度 tick 后更新。每次外部伤害仍会先追赶到自身逻辑时刻，已开始的结算边界在同边界内处理完毕。

1. 网络包：技能输入到达，解析技能槽、替换、资格和参数，确认并提交成本后 dispatch `ability_used`；拒绝请求不发成功事件。当前已通过服务端 use 命令和宿主 API 跑通，技能专用网络包仍待接入。
2. 各维度 tick：怪物 AI、世界物体（毒池跳伤害、追踪弹）。伤害走查询，结果 dispatch `hit` / `kill`。
3. 玩家连接 tick（`ServerPlayer.doTick`）。
4. 引擎 tick（Fabric `END_SERVER_TICK`，NeoForge `ServerTickEvent.Post`）：
   1. 追赶各持有者的时间线：按到期 / 衰减 / 速率变化时间分段积分资源，并发布相应状态事件。
   2. 推进到期的周期命令；高频事件保留逻辑 due_at，不把每个间隔独立四舍五入为 tick。
   3. 低频采样：每 10 tick 算一次环境条件，结果存成隐藏 buff（保护琢面的 `surrounded`，持续 12 tick）。
   4. 排空事件队列。
   5. 对齐：变脏的派生效果写回原版属性；同步 UI 需要的状态。

```text
所有入口都汇入同一个事件队列；伤害数值走同步查询

① 网络包                技能输入、开火 ──► 命令
② 各维度 tick           怪物攻击、世界物体周期伤害 ──► 命令
                        外部伤害在提交前接入防御；提交后接入 ──► 事实
③ 玩家连接 tick         原版 ServerPlayer.doTick，引擎不介入
④ 引擎 tick（末尾）     追赶到期转换 ──► 事实；推迟的命令留到下一 tick

命令
 │   每 tick 的全局预算只管命令，超出就推到下一 tick
 ▼
伤害计划与提交          同步：按分量 / Profile 归约 → 免疫与全局防御 → 分层血池 → 实际结果
 │
 ▼
事实 hit / kill         进入事件队列
 │
 ▼
drain                   广度优先逐个取出事件；重入时只入队，不递归
 │
 ▼
process(事件)           规则 = 静态索引 ++ 带包 buff 的索引 ++ 已结束实例的规则
 ├─ 内部操作            consume_buff、grant_buff：纯核心直接推进
 └─ 世界操作            damage、spawn_object：执行器当场结算，新事实追加到队尾，回到 drain

队列清空 = 结算边界：反应全部完成后，才执行下一条命令
```

技能输入、世界物体造成的伤害、buff 到期都汇入同一个队列；process 产生的新事件只入队，伤害数值在结算时同步查询。

### 事件队列

每个事件都属于一条因果链：入口产生根事件，规则产生的事件记下 parent 和 root。引擎区分两类东西：

- **命令**：还没发生的事，包括技能输入、开火、世界物体的周期伤害、延迟动作。可以排队，过载时可以推到下一 tick。
- **事实**：已经提交的伤害、击杀、状态施加。一旦提交，影响后续战斗的反应必须在同一个结算边界内跑完，下一条命令执行前状态已经更新。只有通知（UI 同步、音效、粒子）可以延后。

结算边界就是“执行一条命令”的调用返回之前。外部攻击若需要经过 Chorus 防御，在扣血前进入计划 / 提交路径；伤后钩子只转发已提交的事实，并同步跑完反应。仅监听伤后事件不能拦下本应由护盾吸收的伤害。

```
runCommand(c):                         // 每 tick 的全局预算只管命令
  if 本 tick 命令预算用完: deferred.add(c); return
  facts = execute(c)                   // 例如结算一次伤害，得到 DamageResult
  queue.addAll(facts)
  drain()                              // 结算边界：反应全部跑完才返回

drain():
  while queue 非空:
    e = queue.poll()
    if 宿主运行保护要求中止: failPartial(e.root); 打印已提交与未完成轨迹; return
    work[e.root]++
    process(e)                         // 新事件可再次触发祖先规则；不按 root 去重或截断

process(e):
  rules = 当前来源索引[e.type] ++ 明确绑定 origin_bundle 的来源规则
          ++ 持有者每个带包 buff 的索引[e.type] ++ e 携带的已结束实例的规则
  同一实例的同一规则在一个事件里最多执行一次
  for r in rules:
    冷却中、条件不成立、或 e.proc_policy 禁止 r → 跳过
    创建本次事件的独立 Frame，按协议运行 r.actions（见「核心语义 v0.3」的「动作执行」）
```

- 广度优先：Unravel 丝线、Jolt 连锁、连环碎冰这类级联不会爆栈，顺序确定，可以用 JUnit 测。用 Haskell 的话说是 worklist 不动点：`process :: Event -> State -> (State, [Event])`。
- 引擎不做通用防循环，不设置最大触发代数或因果链事件上限。无限点燃、Riskrunner 自触发等按具体效果自己的冷却、消耗、阈值、目标存活和排除列表运行。parent / root 只做追踪；运行诊断不改变合法触发资格。
- 同一 event id 的重复投递用 `(event_id, rule_instance)` 保证幂等；新派生事件有新 id，允许再次触发同一规则。每枪 / 每批一次由该规则自己的计数组件处理。
- 预算只限制尚未开始的外部命令；派生动作与已提交事实的反应不能被作为新外部命令推迟。延后的命令以实际开始执行时刻求值，负载可能增加输入延迟，不承诺等同于未延后的时间线。
- 队列与执行帧保证不爆调用栈，但不保证任意数据定义都会终止。宿主可设运行保护并明确报告未完全结算；大量合法目标也可能超预算，不能据此直接认定数据错误或自动禁用内容。固定工作步数便于复现，耗时保护只能作最后防线。失败不回滚此前实际伤害，不把丢弃的链尾当作成功执行。
- 当前纯核心一次迁移最多推进固定步数，返回完整队列与帧并要求 Pump 继续。Pump 在同一边界内运行、不推进逻辑时间，不是因果链上限或下一 tick 调度；宿主保护与游戏接线仍待实现，见实现记录。

### 编译只针对静态层

- 静态层：子职业、星象、碎片、护甲、武器槽里的武器。变化时重新编译技能装配、事件索引和修饰归类。
- buff 层：每个带效果包的 buff 定义预先建好自己的小索引，分发时拼进来。吞食、增幅这类几秒一得一失的 buff 不触发重编译。
- 技能槽在按键时解析：按优先级叠加替换（见「技能」）。"滑铲中"这类条件替换按键时现算，不进缓存。

### 修饰的两种求值

- 只看持有者状态的（被包围、超凡中）：算一次缓存，变脏再算。
- 看命中上下文的（目标身上有什么减益、这次伤害的元素和标签）：每次命中现算。例：勇气琢面。

### 快照

武器和技能默认在使用时冻结攻击方贡献，命中时补齐目标条件与动态贡献，再归约完整分组树。例：采用 on_use 的超新星炸弹在超能属性 200 时施放，换装后仍保留 +45%；目标命中时被 Weaken，再使用其当时的易伤。此例验证 Chorus 默认约定，不代替原作逐效果的快照测试。

参照：Compendium 有词条注明 "Checks on-hit, instead of snapshotting on-usage"，说明原作至少有两种行为。默认在使用时快照是 Chorus 自己的约定，每个效果仍要单独核对。原版箭生成时复制射出它的武器（`AbstractArrow.firedFromWeapon`），命中时用这份拷贝对当下的目标算附魔增伤。

| 内容 | 何时取值 | 例子 |
| --- | --- | --- |
| 来源链（owner、via、tags） | 使用时 | 这颗炸弹是谁、用哪个技能放的 |
| 技能定义和参数（替换、参数修饰之后） | 使用时 | 炸弹半径、基础伤害 |
| 只看攻击方状态的出伤修饰 | 使用时，算成数 | 超能属性 200 → +45%；Radiant |
| 条件看目标的攻击方修饰 | 使用时决定有无，命中时判条件 | 勇气琢面 |
| 使用时已选中的贡献依赖命中测量 | 来源操作数使用时冻结，测量到每次命中读取 | 冻结爆炸加伤，逐目标距离在 falloff 阶段计算 |
| 目标方修饰 | 命中时 | Weaken、冰冻、目标自身的减伤 |
| `evaluate: on_hit` 的修饰 | 命中时 | Compendium 里注明 on-hit 的例外 |

evaluate 可取 on_use、on_release、on_hit、on_tick、on_proc；只在对应动作存在这些时机时合法。冻结数值与冻结目标条件是两件事：目标依赖尚未满足时，保存冻结的来源操作数、表达式和分组路径，到指定时机补齐目标输入。

当前已实现数值快照的 `on_use`（modifier 默认值）与 `on_hit`，其余时机仍是目标协议，Codec 会拒绝。`captureDamage` 绑定来源操作数及来源条件，保留 victim 表达式到命中时求值；`snapshot.command(target)` 固定伤害归属和参数，命中时合并当前攻击者的 on_hit 贡献并执行原 Profile，目标防御另用当前规则。JSON、10 项纯核心和 3 项共享世界测试已验证来源移除 / 到期、不同目标、MAX 合并及旧 Profile 跨运行时替换。完整行为与限制见 [实现记录](engine-implementation.md)；已由 projectile 步骤绑定到物理飞行；来源层的 origin_bundle 选择现已保存，Buff 规则、跨目录版本和派生筛选继承仍待实现。

命中测量已经实现独立的 `ImpactData` 通道：普通 damage / damage_snapshot 的 `impact` 参数或 Java 的 `snapshot.command(target, impact)` 显式提供带单位的数值。`impact_number` 在 on_use 表达式和条件中保持符号形式，到命中时求值；来源消失不丢失已捕获的曲线或贡献，也不需要把整个贡献改为 on_hit。它能在 Profile 指定阶段中应用逐目标距离衰减，基础伤害及组内归约仍保持原有语义。攻击、防御、盾层查询与实际命中事实携带同一份输入；输入独立于回执测得的 effective_damage 等结果。8 项纯核心与 2 项共享世界测试验证顺序、距离快照、当前 MAX / 防御和真实扣血；测试曲线不是已校准的 D2 数据。完整字段注册表、碰撞自动测量及具体内容装配仍待完成。

JSON 已可用 `capture_damage` 产生类型化结果，经 `after` 保存到未来，再用 `damage_snapshot` 指定命中目标并取得实际回执。普通数值的显式冻结另用 `capture_value`，保留原单位，不自动保存退款权限。11 项延迟执行纯核心测试及 4 项共享世界测试验证生命周期、词法绑定、未来查询、嵌套延迟和实际伤害后回血；这些是通用机制验收，不能等同于炸弹实体或 Kinetic Tremors 已完整实现。

指定实体返回已由 projectile.destination 表达：发射时保存身份与参数，逐 tick 读取目标当前位置；与自动选敌 tracking 互斥。抵达使用闭球的整段扫掠首入点，ARRIVED 带接收者但不算碰撞 hit，目标失联另发 TARGET_LOST。两种结果先消耗弹体再执行动作，内容可显式使用共享成本句柄退款；按键接回由 destination.catch 声明独立的半径 / 时间窗口 / 视线策略，服务端验证当前接收者与最近弹体后产生 CAUGHT，不能把抵达自动当作接住。输入序号在执行前消费，重复输入不能再次领取收益；这仅保证同一输入不重放，不限制内容主动产生的事件循环。返回阶段可由命中点新建投射物，保留来源与词法结果；参数、D2 近战键映射和具体技能仍须各自装配。字段与验收见 [指定目的地](engine-data-packs.md#指定目的地与返回阶段)。

空间位置必须与实体身份分开捕获。已实现 `capture_position` 世界动作，将目标脚底位置保存为带维度的不可变结果；`select_targets.center: {"position":"place"}` 使用该固定点，实体中心则在查询时解析当前位置。固定点不依赖原实体继续存在，每次范围查询仍读取当前成员和距离；缺失位置、关系参照与错误维度分别返回明确结果，不默认替换为原点。7 项纯核心及 3 项共享世界测试验证原目标移除后的三次固定范围爆发、独立施放、循环绑定、边界过滤和成员变化。固定位置已可通过 positions 组件跨事件保存；碰撞接触点、位置运算、方块 / 非生物物体及场物体仍待实现。

```java
record DamageSnapshot(
        RulesetVersion version,
        SourceChain source,
        ResolvedAttack attack,                  // 武器或技能定义、分量及原始参数
        List<FrozenContribution> frozen,        // 值、来源、资格、完整分组路径
        List<DeferredContribution> deferred,    // 已冻结来源操作数，延后补齐目标输入
        OriginBundleRef originBundle) {}        // 源规则固定版本，不引用可变装备对象
```

- 攻击方不压成一个数：MAX / SUM / PRODUCT 内的冻结贡献与延后贡献一起归约。只有已完全确定且不会新增成员的组可以缓存最终值。
- 派生物按定义选择继承哪些贡献和操作数（见「核心语义 v0.3」），不能把父伤害最终数字当新基础后再重复施加相同倍率。
- 挂到目标身上的状态记录施加者和继承策略需要的快照。多来源续期、替换强度、累加层数时，来源归属是否改变也由状态定义决定。
- 即时命中的武器走同一路径，快照只存在一瞬间。
- 快照是数据，不是闭包：可以打印、比较、在 JUnit 里重放。世界物体不写存档，快照暂时不需要存盘用的 Codec。

反应规则默认读取 `current_owner_bundle`；需要随发射来源保留的规则显式声明 `binding: "origin_bundle"`，读取固定版本的来源包。当前已支持 source 作用域的伤害事实：captureDamage 保存攻击所有者的来源身份和标签，命中后只从该选择执行 origin 规则；当前索引不重复加入同一规则。来源解绑 / 替换不改写旧选择，新装备不会追溯进入旧攻击。条件中的当前 Buff / 世界资格仍在命中时判断，不能把来源选择理解成冻结全部状态。字段见 [反应绑定](engine-data-packs.md#保留攻击释放时的反应规则)。

数值快照不自动保留全部反应规则，也不因当前切枪而把来源改成手持武器。多版本反应目录并存、Buff 规则继承和派生物继承筛选仍待实现；当前非空选择要求完整程序相等，不会把旧规则替换为同名新规则。owner 死亡或下线时，伤害按定义继续，要求 owner 存活 / 在线的反应应显式检查资格；具体 perk 采用哪种绑定仍需逐条验证。

## 核心语义 v0.3

下面规定上下文、结果和执行协议；数学规则集中在「属性与数值管线」，避免维护两套相互矛盾的公式。v0.3 在 Compendium 机制审查基础上合入数值算法与版本证据。文中的 Java record / JSON 是接口草图，具体 Codec 和类型注册尚待实现。

### 伤害上下文

来源链只回答“从哪里来”。来自某把武器，不代表它算武器伤害，也不代表它吃全部武器增伤，所以伤害上下文分五个维度：

| 维度 | 回答什么 | 例子（Compendium） |
| --- | --- | --- |
| origin | 谁制造、哪个武器或技能实例、root / parent 事件 | 所有伤害 |
| damage\_kind | 直接命中、爆炸、持续伤害、状态派生伤害…… | 庆典飞行的毒池是持续伤害 |
| credit | 对规则算武器、手雷、近战还是超能伤害 | Voltshot 施加的 Jolt 不算武器伤害（`Weapon Perks!C243`）；Kinetic Tremors 的震波算（`C135`） |
| scaling\_profile | 接受哪些贡献组、使用哪个 CalculationProfile 及数值基准 | Hellion 的手雷归因不等于其日光状态吃手雷缩放；抓钩近战对 Boss 使用专用属性组合 |
| proc\_policy | 能触发哪些后续效果 | 原文的排除列表，默认全部允许：Jolt 的连锁闪电、非技能的灼烧和点燃不触发电光充能放电（Arc!D5） |

实现上可以继续用标签，但每个维度是一组独立的标签，规则只查自己关心的那一组。

派生动作已经支持 `origin: bound / event`：普通 damage、capture_damage 和 heal 默认使用绑定来源，可明确改为当前触发事件的完整 source。这个选择只影响新动作的归属；状态施加者、规则 self / 组件读取、目标选择不变，tags / kill_tags / scaling_profile 仍独立声明。capture_damage 固定所选来源及其 on_use 贡献，后续 damage_snapshot 不再重归属。该通道已经过 6 项纯核心及 2 项共享世界测试，能表达“甲施加、乙触发”并保持数值查询、原版攻击者与击杀事实一致；与独立的 origin_bundle 反应选择分开；该动作来源通道本身不继承父反应规则。Bungie [7.1.0 的 Jolt 归属修正](https://www.bungie.net/7/en/News/Article/season-deep-update-7-1-0) 是这一反例的依据，完整 Jolt 数值及来源例外见 [规则集](d2-ruleset.md)。

另外携带 attack / component / batch / cast id、活动规则集、双方类别、目标 Rank / Tier、命中位置与距离、精准标记和快照引用。精准与黄色数字分开。一次查询从同一只读视图取值，不能由 Value.eval 反向执行其他动作。

派生物和状态的继承写在它们自己的定义里，不再一律继承父快照。Threadling 停栖后失去来源继承（`Strand!D6`）；点燃可以继承初始灼烧来源的增伤，非技能来源的点燃不触发电光充能放电（`Solar!D8`）：

```json
"inherit": {
  "origin": "keep",
  "credit": ["grenade"],
  "basis": "fresh_definition",
  "scaling": { "exclude_groups": ["grenade_damage"] },
  "proc": { "deny": ["chorus:bolt_discharge"] },
  "lose_on": ["perch"]
}
```

上例仅展示字段形状，具体状态选用自己的继承配置。basis 可以是 fresh_definition 或 named_projection。后者要声明投影已包含的倍率，子动作只应用尚未包含且允许的项；用命名投影表达分享 / 转换伤害，不能从已暴击的最终数字粗暴除一次暴击倍率代替定义。proc 默认允许，只有效果原文明确的排除才加入 deny。

其中 proc 已有独立实现：规则声明 proc_key，damage / capture_damage 声明 `proc: {"deny":[...], "inherit":"fresh"|"event"}`；默认 fresh，只保留本动作排除，event 则与触发事件的排除取并集。实际命中事实携带策略，当前来源、已捕获来源和 Buff 规则在求值条件前筛选，其他规则照常执行。数值快照固定策略，命中时不可改写；归属与信用不因排除改变。Jolt 已声明禁止 Bolt Charge 放电，并用真实链伤与接收探针验收；Bolt Charge 已有独立计数、回能与中心放电投影，但完整范围仍未实现。更广的来源 / 数值 / Buff 继承与 lose_on 仍是目标协议。字段见 [proc 说明](engine-data-packs.md#显式排除后续触发)。

### 伤害结算结果

同一结算时刻、同一目标的伤害事务按分量及各自 Profile 计算，在确定顺序下更新血池。每个目标返回独立 DamageResult；延迟爆炸 / DOT 后续命中创建新 damage id，不把跨时间伤害强行并入一个结果。规则声明自己读哪个命名阶段或投影：

```
贡献求值与分组归约 → 各分量出伤 → Chorus 目标修饰 / 护盾计划
  → 原版实际执行 → 护盾 / Absorption / 生命损失回执 → 确认死亡
```

状态施加不在伤害结算里。`apply_status` 是单独的动作，返回自己的结果，用 damage id 关联触发它的伤害。否则会形成循环依赖：伤害等状态结果，状态由 hit 规则施加，hit 又在队列里等伤害结束。

当前实现先输出 `StatusResult.Check`，由世界适配器确认目标存在、存活及状态施加资格，再以 `Checked` 回执恢复；纯核心只在 allowed 时提交 Buff。`StatusResult.applied` 表示提交已完成，包含前后层数与 generation 引用，不能把伤害是否免疫直接当成状态能否施加。`MinecraftWorldActions` 已查询真实实体并调用宿主提供的状态资格策略；纯测试覆盖 allowed / denied / dead / missing，双加载器 GameTest 验证真实图腾救活后的目标仍可收到 Slice 的 Sever。内容免疫策略的自动装配仍待实现。

`check_status` 已实现只读资格查询，返回 allowed / denied / dead / missing，没有 applied，也不提交 Buff。默认要求存活，显式 allow_dead 仅允许仍存在的死亡目标继续接受宿主免疫 / 资格检查，不允许 missing，不预留未来的施加资格。`apply_status` 始终要求存活，并校验恢复回执与原请求完全一致。

Volatile 示例用这个边界处理“施加击本身致死”：宿主在实际命中事实保留施加意图，内容读取实际 lethal，再检查死亡目标的状态资格并直接引爆，不依赖补挂 Volatile。已有状态则按后续伤害累计或死亡引爆，先移除状态并挂冷却，再产生范围伤害；相邻目标可以继续连锁。JSON、纯核心和双加载器测试已通过；施加意图目前来自测试宿主，伤害投影、重施加策略和衰减曲线等校准缺口见 [实现记录](engine-implementation.md)。

```java
record DamageResult(DamageId id, DamageContext ctx,
        List<ComponentDamageResult> components,
        Map<ProjectionId, DamageMeasure> projections,
        List<ShieldLayerHit> shields, double absorptionLoss, double healthLoss,
        DamageMeasure unspent, boolean immune, boolean lethal,
        CalculationTrace trace) {}

record ShieldLayerHit(LayerId layer, ComponentId component,
        double input, double takenMultiplier, double capacityLoss,
        double spentInput, double remainingInput) {}

// DamageMeasure 带单位与基准；unspent 是层修正前的剩余预算，不是可与扣血量混加的另一个血池。
// ComponentDamageResult 含各阶段值、precisionApplied、immune、护盾/生命损失和未消耗预算。

record StatusResult(Optional<DamageId> cause,      // 触发它的那次伤害
        boolean applied, int stacksBefore, int stacksAfter,
        Optional<BuffInstanceRef> instance) {}
```

- 伤害阶段命名由 Profile 声明，例如 definition、normalized、after_source、after_target、before_layer_defense。每个投影注明单位、精准 / 活动 / 来源倍率是否已包含。
- effective 默认为实际护盾损失 + 生命损失；不同池有不同承伤倍率时，它不等于输入减去 unspent。回能和阈值必须选择经过校准的 projection，不能随便把所有阶段都叫 dealt。
- 原版 Absorption 损失单列，各收益 / 阈值投影明确是否包含它。实际分量回执区分 applied / immune / blocked / cancelled / failed，携带可选 death id、deathPrevented 与 protectionSource。当前 DamageFacts 从 DamageReceipt 发布 hit / damage_taken / shield_damaged / shield_broken / death_prevented / death / kill；cancelled / failed 不发布这些事实，immune / blocked 仍可有 hit。护盾事实携带层定义标签、shield_definition、shield_generation、layer_loss 和 layer_remaining，可用 event_tag / event_reference 筛选。显式请求及已注册维度的原版伤害和护盾已接入；聚合 DamageResult 仍待实现。
- 分量分别记录免疫；顶层 immune 表示本事务所有实际尝试的分量均被免疫，零基础伤害或过量伤害不自动算免疫。lethal 只在本事务导致存活到死亡的状态迁移时为真，后续分量不再产生第二个击杀。
- 分享类效果必须显式写明读哪个阶段、是否去掉精准倍率。Deadfall 分享“Bodyshot (Base) Damage”的 50%（`Void!D47`），它对应哪个阶段、是否已含增伤，要实测核对。
- 状态累加器声明 accumulator_basis、include_applying_hit、count_unit、trigger_threshold、consume_policy、cooldown、attribution_policy。Jolt 可传入施加那一下的投影作为初始累计，Volatile 不传；已存在状态的监听与初始化路径不能把同一伤害重复累计。
- 切割：hit 规则先读 `lethal`，不致死才施加 Sever；`StatusResult.applied` 为真才消耗层数；免疫目标照样施加（`Weapon Perks!C198`）。
- 护盾是 Buff 带的血池层；虚空 overshield 的数值参考 `Void!D6`，层内减伤仅作用于该层。跨层预算算法和默认 FIFO 是 Chorus 约定，不能当作此单元格证明了原作所有跨层行为。
- on_hit 获得的增伤默认只影响后续伤害，不回头改变已经提交的这一击。真正需要先行处理的动作显式声明 before_damage，在冻结该击查询视图之前执行。
- “下一次命中消耗”的 buff 要写明消耗时机（施放、命中确认、造成伤害、状态施加成功），以及同一批次（多弹丸、范围命中）是只消耗一次，还是每个实例各消耗一次。 受管 damage / damage_snapshot 已支持 consume_on_damage：发出请求前保存符合条件的 Buff generation，回执完成时消费，下一条指令读取新状态；数值 modifier 必须 on_hit。仅在排队的 hit 反应中消费不足以保证同一动作体的下一次伤害已失去增益。多分量共享通过 sharing=group 与显式 begin_damage_group 声明：首个合格回执消费，后续同组成员保留资格，其他独立攻击读取新状态；组有显式寿命和关闭边界。原版 hurt 入口已采用候选预留：独立嵌套命中读取剩余额度，同组成员只预留一次；回执确认后合入 Buff / 护盾 / 组状态，再驱动纯反应。预留不产生生命周期事件，取消释放额度，未知结果保留确认事实与未决操作，不重放。全组原子世界事务与并行宿主仍待实现，见 [实现边界](engine-implementation.md#伤害回执消费与原版预留)。

### buff 实例

buff 仍是统一的效果载体，但实例不再只有层数和剩余时间。实例 = 持有者 × 定义 × 实例键，外加少量有明确语义的状态组件。组件是 Java 定义的种类、JSON 配实例，不把 DSL 做成通用编程语言。

| 组件 | 保存什么 | 例子（Compendium） |
| --- | --- | --- |
| 计时器 | 整体到期、逐层衰减、每层独立到期、可暂停 | Disruption Break 每层各自计时并相乘（`Weapon Perks!C71`）；Frame of Reference 收起时暂停（`C369`） |
| 累加器 | 伤害、命中进度、已返还能量 | Jolt 的累计伤害和触发冷却，属于目标身上的这个实例（`Arc!D8`） |
| 去重集合 | 命中过的目标、用过的武器或元素 | Bait and Switch 每把武器各一次（`C25`）；Elemental Honing 每种元素一层（`C80`） |
| 目标成员集合 | 上次选择的实体身份，支持进入 / 离开差分；不保存距离 | Healing Rift 的范围成员管理（`Class Abilities!D15`）；固定 5 米 / 15 秒场与共享满血补盾池已有部分内容，精细时序与技能装配仍待完成 |
| 引用 | 标记目标、关联锚点、所属技能施放实例 | Deadfall 拴住指向锚点 |
| 固定位置 | 带维度的可选坐标，跨事件读取；不绑定原实体存活 | Healing Rift 固定施放点；位置组件和部分恢复场已实现 |
| 历史值 | 原始持续时间、最长持续时间、最高等级 | Restoration 重新施加时恢复到曾达到的最长持续时间，并保留最高强度（`Solar!D7`） |

已实现的数值组件在定义中声明名称、单位和初值，例如 `components.numbers.damage = { "initial": 0, "unit": "damage" }`；新 generation 初始化，刷新保留。`component` Value 与 `update_component` 动作校验单位，后者支持 set / add / min / max。通过成对指定 `once_set` 和 `event_reference`，内容可让状态初始化与旧监听共享 damage_id 集合，累计同一次施加击一次。去重仅针对明确指定的身份和本实例，不禁止后续伤害或同 root 循环。通用引用 / 集合操作、长期实例的集合清理策略仍待扩展。

目标成员另外声明 `components.target_sets = ["members"]`，不能拿普通去重字符串集合代替。已实现 `read_targets / sync_targets / difference_targets`：同步显式查询结果或已保存身份，提交成员并返回 before / after / entered / exited；不可用查询保留旧成员，成功的空查询才清空。成员按身份排序，不保存距离；其 for_each 元素可作动作目标，但读取 distance 在加载时报错，空间测量须重新查询。结束规则可读取最终成员快照，即使同键新 generation 已存在也不误读新实例。

固定位置使用 `components.positions = ["anchor"]`。`write_position` 精确保存类型化位置结果，包括可表示清空的缺失值；`read_position` 读取当前值或自身结束快照，两者不读取世界。新实例初始化缺失，刷新保留；available 只表示坐标存在，不代表实体仍在。与 target_sets 和定时器组合的 `healing_rift.json` 已实现固定 5 米 / 15 秒的部分恢复场，并复用 restoration.json 的恢复定义；每次施放以独立来源实例隔离。场结束清理晚进入的成员，来源解绑不删除已授予的场。同一受益者的重叠场共享满血补盾池，presence 即使在零容量时也带具有护盾的资格标签。实例实体 / 阵营失联策略、补盾时序校准、地形与施放动作仍需补齐，不能把位置组件视为完整世界物体。

周期查询与成员差分已通过 10 项纯核心及 3 项共享世界测试，可以由内容规则组合成移动范围效果：进入授予、离开移除、场结束清理，并通过来源实例键保留其他重叠场。它不会自动发布区域事件，也不把成员入选等同于状态成功施加。采样之间沿用旧成员和恢复速率，不回推实际越界时刻；世界动作失败保留已提交成员，遵循现有失败协议。合成移动场见 `membership_aura.json`，固定 Rift 场见 `healing_rift.json`；完整 Rift / Well 内容、施放物体与客户端表现仍待实现。

原来的 `scope` 拆成四个独立字段：

| 字段 | 回答什么 | 取值 | 例子 |
| --- | --- | --- | --- |
| `attach` | 挂在哪 | holder / weapon / target | Rampage 挂在玩家身上 |
| `instanced_by` | 按什么区分实例 | none / source / weapon | Kill Clip 的窗口按武器区分 |
| `affects` | 修饰作用于谁 | all / instance\_weapon / ability | Rampage 只给绑定的武器增伤 |
| `on_stow` | 收起武器时 | keep / remove / pause | Frame of Reference 暂停 |

### 生命周期事件

- 层数变化和实例结束是两种事件：`stacks_changed` 在实例仍存在时发出；`ended`（到期、被消耗、被移除）在实例消失时发出。两者都携带 before / after / reason。
- `ended` 携带被移除实例的最终状态，分发时把它自己的规则显式加进去；`stacks_changed` 的实例还在当前集合里，不再额外加入。同一事件中，同一实例的同一规则最多执行一次。
- 获得事件分三个量：请求数量、计入收益的数量、实际存储变化。Bolt Charge 在 x9 时获得 x4，只存到 x10，但能量按 x4 计算（`Arc!D5`）。当前生命周期事实已向 DSL 暴露 requested / credited / stored_delta 及变化前后层数；Bolt Charge 使用 gained 回执按 credited 回复近战能量。全局状态也可用 event_weapon / event_source_tag 读取实际触发武器，不会误读最初施加状态的来源。
- 刷新单独发事件，满层刷新也能被监听：狡诈严冬达到或刷新 10 层时进入督军之怒就绪（`Exotic Armors!I92`）。

当前纯核心以 `chorus:buff_gained`、`chorus:buff_stacks_changed`、`chorus:buff_refreshed`、`chorus:buff_ended` 等信号表达这些事实。`gained` 记录每次接受的施加请求，包括满层时实际变化为零的请求；规则按 requested / credited / storedDelta 选择口径。消耗最后一层只产生 `ended`，其 before 是最终实例快照、after 为空，不再另发一个零层的 `stacks_changed`。共享计时器按策略刷新；每层独立计时不会刷新已有层。

显式 `refresh_buff` 只刷新已有实例的共享计时器，遵从定义的刷新策略，不增加层数或制造 gained。没有实例时为空操作；因此 Slice 消耗最后一层之后的刷新不会重新创建充能。

Buff 规则定义固定在规则集版本内，分发时按实例 generation 绑定。普通事件读取当前实例状态；一个尚未开始的规则发现该 generation 已被移除时跳过。已开始的动作帧继续执行并可等待世界回执，不因来源被自身动作消耗而丢失后续动作。`ended` 显式带入已移除来源的规则，并通过最终快照读取结束时的组件；新获得的同键实例使用新的 generation，不能冒充旧实例。

金标准案例已经依赖、要随对应案例一起实现的入口：武器拿出与收起（Frame of Reference）、长按输入（Getaway Artist）、buff 刷新（狡诈严冬）。弹药逻辑动作已发布带实际数量和原因的 ammo_spent / ammo_refilled / ammo_generated / ammo_changed，整弹匣手动换弹已由实际容器和服务端命令接线；单次开火已从实际容器接受并执行数据声明的物理投射物，fire_accepted 不证明成功发射或命中；显式成员投射物已有一次性整枪聚合，依据实际伤害回执并报告命中、有效伤害、拒绝、无命中终态与不完整截止；弹匣耗尽的内容资格、技能换弹及逐发装填的真实输入仍待接线。其余以后按需添加，属于加种类：冠军眩晕；构造物摧毁；技能开始、提交、结束、取消。显式治疗已发布实际量与过量治疗事实，自然治疗观察另待实现。

### 动作执行

顶层模型保持 `(状态, 事件) → (新状态, 动作列表)`。这里的“新状态”是处理本次输入后的状态，不要求整条因果链已经结束；“动作列表”是待执行描述，不要求提前把依赖未来执行结果的参数都算成常量。

例如“造成伤害，再按实际扣血量治疗自己”，第一次处理输入时可以保存等待结果的执行帧并产出伤害命令。执行器将实际结果包装成 `ActionCompleted(opId, result)` 输入纯核心，下一次状态迁移再产出治疗命令。这样仍是同一个纯函数模型；执行帧只把尚未完成的工作显式保存在状态中。

规则的动作列表按顺序执行。动作可以把结果绑定到名字上，后面的动作用数值表达式 `chorus:result` 或布尔条件 `chorus:result_flag` 读取。这相当于 do-notation 里的 `x <- action`，但不允许写任意函数。造成世界伤害的动作返回实际结果之后，序列才继续；当前实现返回分量 DamageReceipt，完整聚合 DamageResult 仍待实现。

```json
"do": [
  { "action": { "type": "chorus:apply_status", "buff": "chorus_d2:sever", "target": "victim" },
    "as": "status" },
  { "if": { "type": "chorus:result_flag", "binding": "status", "field": "applied" },
    "then": [ { "type": "chorus:consume_buff", "buff": "chorus_d2:slice" } ] }
]
```

这是已支持的步骤语法，完整定义和非致死等前置条件见 `common/src/test/resources/effects/slice.json`。条件步骤支持嵌套 `if / then / else`，else 可省略。分支条件只在进入时判断一次，世界回执恢复时保留所选分支；分支内声明的结果仅在该分支及子分支可见，不能向外逃逸、从另一个分支读取或遮蔽已有结果。条件步骤编译为作用域内向前跳转；`for_each` 使用配对的循环指令，保存列表、索引和嵌套栈，逐目标执行完整动作序列。因果效果循环仍可通过新事件表达，不按 root 禁止再次触发。

已实现 `chorus:select_targets` 世界查询 → 绑定目标集合 → `{"for_each":"集合名","as":"目标名","do":[...]}`。目标参数可写 `{"binding":"目标名"}`，集合 count 可用于数值比较；循环内部结果不能向外引用。选择列表与距离冻结，默认按 UUID 文本排序，也可按最近距离排序并用 UUID 破同距；过滤后支持数量上限和显式排除目标。循环目标的 distance（meter）可经带单位的 `chorus:curve` 计算衰减；每目标伤害回执可决定其后续状态或回血。Minecraft 适配器当前为已加载存活实体、脚底球形距离、一 meter 对应一格，关系仅为原版 any / allied / not_allied；不是已经校准的 D2 敌人 / 距离系统。查询后目标消失不补选，新目标不加入旧列表。详细语义与可运行样例见 [engine-data-packs.md](engine-data-packs.md) 和 [engine-implementation.md](engine-implementation.md)。

纯核心可以一次处理所有已知输入足够的动作，遇到依赖世界结果的位置再等待回执。本设计用执行帧及 step / resume 实现这种推进；它们是顶层状态迁移内部的协议：

```java
record Frame(FrameId id, EventId event, RuleInstanceId rule,
        int pc, Map<Integer, Integer> invocations, Map<String, ActionResult> bindings,
        List<Iteration> iterations, Map<String, ActionResult> retainedResults,
        Optional<WorldCommand> pendingCommand, RulesetVersion version) {}

sealed interface Outcome permits Advanced, NeedsWorld, Finished {}
record Advanced(RuleState next, Frame frame, List<Event> emitted) implements Outcome {}  // 内部操作：consume_buff、grant_buff……
record NeedsWorld(WorldCommand command, OpId op, Frame frame) implements Outcome {}      // 世界操作：伤害、生成物体、冲量
record Finished(RuleState next, List<Event> emitted) implements Outcome {}

Outcome step(RuleState state, Frame frame, RuleContext ctx);   // 纯函数
Outcome resume(RuleState state, Frame frame, OpId op, ActionResult receipt); // 纯函数
```

- 内部状态操作在纯核心里直接推进并绑定有类型的结果；世界操作交给执行器，返回实际回执后调用 resume。DamageResult、StatusResult、HealResult、ResourceResult、CostReceipt 不混用。chorus:result 的引用在加载时检查前向引用和结果类型，运行时从 bindings 解析。
- 显式只读观测同样走世界命令 / 回执，不能在纯 Value 求值时偷读实体。当前 inspect_entity 返回不可变 HP / 最大 HP / Absorption、alive / player 与 available / missing；缺失结果不提供这些实体字段。先确认 available，再读取类型和数值；同一回执在世界变化或 after 延迟后仍保持原值，实时读取必须再执行观测。player 仅为 MC 类别，不隐式切换 PvE / PvP，也不代替 D2 等级 / 勇士数据。实体观测、位置、目标引用和伤害结果类型互不替代。
- ActionCompleted 是恢复当前执行帧的内部输入，在当前结算边界内处理；不作为普通 hit / kill 反应排到队尾。顶层状态保存待恢复帧，收到回执后由内部 resume 继续推进，保持后文的动作顺序与事实队列约定。
- Frame 是数据，不是闭包：相当于把 continuation 去函数化（defunctionalize），能打印、能比较、能在测试里重放。
- 等待帧保存实际发出的不可变 pendingCommand。resume 使用原命令确认回执和构造事实，不能在 Reconciler 提交护盾等变化后重新求值旧参数；新状态仍供下一动作使用。当前实现通过 Context.command 读取该命令，动作推进后清除，延迟新帧不继承已完成命令。
- OpId = `(frame_id, pc, invocation)`；FrameId 由状态中的稳定序号分配，同时关联本次 event id 与 rule instance，规则因新事件再次触发创建新的 Frame；同一 Frame 内逐目标循环则递增该 PC 的 invocation，并跨等待保留。不能使用 root + rule + pc，否则同一因果链第二次合法触发会被误去重。一个动作展开多个世界命令时，再附加稳定的子操作序号。
- 执行器保存操作回执，重复收到同一 OpId 返回原回执，不重做副作用；resume 也按同一操作确认一次。不自动重试结果未知的操作。规则中途失败时停止其剩余动作，已提交的伤害不回滚、不重复。
- 已实现的 `EffectSession` 在同一线程 / 边界内驱动 Pump、世界动作与回执；`OperationLedger` 缓存当前边界的命令结果，退休边界用单调 Frame 水位拒绝旧命令。世界操作抛异常时保留 pending 帧与未知结果标记，不自动重试；当前账本只在内存中，跨重启恢复未实现。重入的外部 Start 仍明确拒绝，原版伤害回调通过 `MinecraftEffectRuntime` 排队接入。
- 同一原版伤害边界可能包含多次嵌套命中。最外层 hurt 完成后按提交顺序一次投递，共享批次 root；ServerPlayer → Player → LivingEntity 的父类委托只采集一次，真正重入的命中独立记录损失。世界动作期间观察到的子事实随 `WorldReceipt(value, observed)` 返回，恢复动作只读取 value，幂等比较包含整个封套。受管主命中仅由动作完成逻辑发布；原版子事实先于它入队，两者都等当前规则序列完成后才分发。
- `Start` 与 `WorldReceipt` 的 committed 字段可携带已确认领域写集，纯 Reconciler 先校验并应用，随后才运行观察批次的规则或恢复当前动作。当前实现为护盾容量写集，因此同帧下一动作能立即读到扣盾结果，而破盾收益仍须等待事实反应；回执幂等比较同时覆盖写集、子事实与结果。默认解释器拒绝未知写集，已提交写入不会因后续动作错误回滚。
- 维度运行时目前通过 Java API 或管理命令显式安装，完整程序可来自可重载数据包目录；装备 / 技能自动装配仍待接入。原版来源默认记录攻击者、直接来源与 damage type，不推断武器 / 技能实例或武器击杀资格。原版边界异常保留此前完成的子命中证据，规则 / 世界动作异常保留已提交状态，停止该运行时的后续推导，不停止后续原版伤害。未知结果不自动重试；显式恢复、活动迁移与持久化仍待实现。
- DamagePlan 由纯计算产生，携带状态版本、待提交的 Chorus 护盾写集、已完成阶段和交给原版的剩余伤害，不写原版生命或预判死亡。执行器在不插入其他战斗命令的边界内验证并执行，收集实际回执，resume 才发布相应新状态与事实；失败或被外部取消时不能发布计划扣血量。适配层重入产生的回调先排队，不能重复计算同一次伤害。
- 当前规则序列先恢复并完成，产生的新事实按广度优先入队；因此“后续动作读前一动作结果”不等于“等待该动作引起的所有派生事件都结束”。需要在派生事实之后执行的行为应监听该事实，不能隐式递归排空整条链。

### 时间语义

- buff 是否生效一律按逻辑时间判断（`expiresAt > now`），不依赖 tick 末尾的清理。
- 会影响玩法的到期转换，要在后续相关操作之前完成。例：Lucky Pants 的 Illegally Modded Holster 到期时，若层数 ≥ 7 就挂上 10 秒 Out of Luck，阻止重新激活（`Exotic Armors!C44`）。如果 Out of Luck 到 tick 末尾才挂上，中间就有重新激活的空档。
- 做法是“追赶”：读取或修改某个持有者的 buff 状态之前，先把它已到期的转换补跑到 now（在结算边界内）；引擎 tick 末尾再对没被访问的持有者统一追赶一次。通知和 UI 同步可以延后。
- 条件读两类数据：事件事实（伤害值、是否致死、命中了谁）来自事件本身；持有者状态在处理时读取。因为反应在结算边界内跑完，两者之间不会隔着其他命令。
- 追赶在查询入口完成，不藏在 Value.eval 内。资源在 buff 到期、属性变化、消费和入账的各时间点分段积分，不能先移除增益再用新速率积分整个过去 tick。
- 纯核心的 `Buffs.advanceStep(store, until)` 每次只推进到最近一个到期时刻，提交该时刻全部到期迁移并返回生命周期信号；宿主先排空这些信号引起的反应，再重新计算下一个到期点。不能先把所有 Buff 批量清到 until，再补发中间的结束事件。当前 `TimelineEngine` / `EffectClock` 已实现自动追赶、资源分段积分与周期信号；已注册维度按服务器 tick 推进，并在普通伤害进入前追赶。资源定义中的恢复 Profile 与查询期修饰已装配；真实技能属性数据、活动迁移与离线恢复仍待实现。
- 定时器可以绑定 Buff generation；同一时刻先提交到期，失效实例的周期信号不再提交。新获得的同键实例不能接管旧定时器。定时器支持有限次或持续运行，间隔必须为正；该规则不限制事件之间的合法效果循环。
- `schedule` 已支持 JSON 的 name / event / delay / interval / repeat / policy；keep 保留原节奏，replace 重设，error 拒绝重复，`cancel_timer` 取消本来源同名任务。静态来源实例或 Buff generation 决定归属，`own_timer` 条件在回调中确认归属；事件保留调度时的上下文，回调时按当前实例读取 by_buff_tier / by_stacks。
- `after` 已支持嵌套延迟动作体：调度后当前序列继续，未来以新 Frame 执行，保留原事件内容、来源及可见的不可变结果。默认 source 生命周期随来源取消 / 暂停，显式 detached 则独立继续；未捕获状态仍在执行时读取。延迟体绑定不能逃逸，普通付款 / 退款账本不能复制到未来帧，显式 retain_cost 句柄只引用共享账本；嵌套延迟相对执行时刻计算，必须为正且精确到微秒。当前尚无 detached 单独取消句柄或持久恢复。
- `health_recovery` 查询当前恢复来源，按互斥组选择后积分。有效来源会增加对齐的 50 ms 边界，状态变化与到期可提前截断；最后残段不能因为来源在边界消失而被丢弃。分配保存原始归因，并使用世界回执确认 actual / overheal；来源删除须先追赶到删除时刻。
- Buff 暂停时，绑定定时器保存距离下一次触发的剩余时间；恢复后从这一余量继续，不补发暂停期间脉冲。移除 Buff、静态来源解绑或替换会清理对应定时器。schedule 定时器保持此生命周期；after 可显式选择 detached，来源消失后仍执行已调度的动作体。跨重启及独立层调度尚未实现。
- 内部用整数逻辑时间和稳定序号排序；同一时刻先完成到期状态迁移，再执行新的外部命令，这是 Chorus 的边界约定。周期事件按 previous_due_at + interval 递推，不按实际处理时间重置周期。
- 原版物理仍按服务器 tick 推进；同一 tick 内的多个逻辑伤害时刻保留顺序，不声称原版碰撞也获得了更高采样率。计时器恢复、离线、死亡和物体销毁时的推进策略由相应定义声明。

### 数值协议不变量

- 数学步骤以「属性与数值管线」的 CalculationProfile 为准；DSL 修饰必须能解析到 Profile 中存在的阶段和分组，不允许默默忽略拼错的 group。
- 单位和基准在加载时校验：百分比增量、完整倍率、stat_point、second、damage、charge_fraction 不能混加。percent_of 只能引用当前步骤之前已经存在的阶段。
- 同一次查询使用不可变输入视图；冻结贡献与延后贡献在正确的分组位置合并。只有与当前上下文无关的子树能缓存，缓存键包括规则集版本和状态版本。
- Profile 记录哪些因子已应用，派生动作记录继承的数值基准，避免精准、光等、目标或属性增伤重复计算。
- CalculationTrace 保留选中与排除原因、每个分量和血池的中间值、来源 / 版本 / 置信度。带 assumed 或 conflict 的输出显式标记，不能显示成完全验证的命运 2 数值。

## 多加载器接线清单

common 只依赖原版，下面每一项在 fabric 和 neoforge 各写一层薄接线。日常只跑 `:fabric:runClient`，NeoForge 按里程碑批量补齐。其中只有"注册"必须一开始就做对。

| 项目 | Fabric | NeoForge | 备注 |
| --- | --- | --- | --- |
| 静态注册 | 初始化时直接注册进原版注册表 | `DeferredRegister` | **先做**：common 只声明"要注册什么"，加载器在允许的时机注册 |
| 同步的世界数据注册表 | `DynamicRegistries.registerSynced` | `NewDatapackRegistryEvent.worldRegistry` + networkCodec | 世界启动时加载；不是当前 EffectProgram 的重载通道 |
| 服务端可重载程序注册表 | `DynamicRegistries.registerReloadable` | `NewDatapackRegistryEvent.reloadableRegistry` | 已接入 chorus:effect_program；不自动同步客户端 |
| 实体附加数据 | `AttachmentRegistry` | `AttachmentType` |  |
| 网络包 | `PayloadTypeRegistry` + `ServerPlayNetworking` | `RegisterPayloadHandlersEvent` | payload 和 `StreamCodec` 定义在 common |
| 游戏事件来源（击杀、受伤、右键、tick） | Fabric API 回调 / Mixin | NeoForge 事件 / Mixin | common 负责统一语义。当前维度运行时挂在 Fabric END\_LEVEL\_TICK / NeoForge LevelTickEvent.Post，由维度 gameTime 推进，不用 PlayerTickEvent；伤害与死亡由统一采集器输出回执。右键等其他事件尚未接入 |
| 可开关的内置数据包（`chorus_d2`） | `ResourceLoader.registerBuiltinPack` + `PackActivationType` | `AddPackFindersEvent` | 整合包可以覆盖或替换，见「分发形态」 |
| 伤害前接入（护盾层、目标方修饰） | `ServerLivingEntityEvents` 只能取消伤害，改数值要 mixin `LivingEntity.hurtServer` | `LivingIncomingDamageEvent` / `LivingDamageEvent` | 外部伤害也走这里，见「与原版和其他模组联动」 |
| 快捷键 | `KeyMappingHelper` | `RegisterKeyMappingsEvent` |  |
| 配置文件 | 无内置 | `ModConfigSpec` | 见「开放问题」 |
| 客户端渲染注册（实体渲染器、HUD） | Fabric API | NeoForge 事件 | 前期尽量用原版 Display 实体避开 |
| Mixin | 写在 common | 写在 common | 两边都自带 Mixin 和 MixinExtras；只在没有事件时用 |

实现这些接线时要对照本地源码核对 API 名字。AI 容易写出旧名字，比如 Fabric 26.1 把 `playC2S` 改成了 `serverboundPlay`。

## 金标准测试集

下面是持续扩展的首批金标准。每条都要能用 DSL 编码，并通过带时间线的预期结果测试，才说明其纯核心行为被覆盖；涉及游戏世界的条目还需要游戏接线测试。Target Lock（按弹匣百分比逐步增伤）、Ager's Scepter（切换射击模式）这类预期交给 Java 自定义类型，但存在扩展入口不等于已实现对应效果。Kill Clip、Adrenaline Junkie 已有可编译 JSON，Rampage 仍为表下草稿；完整表格目标不缩减为这批用例。全快照来源、首批人工验收要求与缺口映射见 [Compendium 覆盖清单](compendium-coverage.md)；未审阅来源保持未知，不以导入行数充当效果覆盖数。

| 条目 | 类别 | 检验什么 | 状态 |
| --- | --- | --- | --- |
| Rampage | 武器词条 | 层数、刷新、一层层衰减、收起后保留 | 草稿 |
| Adrenaline Junkie | 武器词条 | 武器实例击杀叠层、手雷击杀五层、收枪触发/保留、普通/强化时长、伤害表与固定操控 | JSON / 时间线 / 查询通过；两端真实死亡驱动下一击伤害；武器/手雷归因由测试宿主提供，实际装备、手雷物体与操控动作未接入 |
| Outlaw | 武器词条 | 精准击杀、武器属性修饰、切枪移除 | 未写 |
| Kill Clip | 武器词条 | 用隐藏 buff 实现时间窗口 | JSON / 时间线 / 数值查询通过；双加载器通过预置 Buff 的实际伤害修饰；实际容器开火 → 物理击杀 → 手动换弹 → 下一发真实增伤及收枪后快照已验；窗口收枪保留待校准 |
| Under-Over | 武器词条 | 攻击者按当前护盾层增伤、普通 / 强化与 Woven Mail 躯干分支 | 部分 JSON / 9 项纯核心 / 3 项共享世界场景；快照保留来源、层条件延后、原版溢出；跨层叠加、完整盾 / Woven Mail 内容和来源分类仍待完成 |
| Voltshot | 武器词条 | 下一次命中消耗 + 施加元素状态 | partial；5.3 秒完成窗口、7 / 8 秒下一击、收枪与武器隔离；真实玩家容器 / 换弹 / 物理开火到共享 Jolt 非武器击杀已验；完整武器原型、多弹丸事务与清理策略待完成 |
| Kinetic Tremors | 武器词条 | 按目标计数、固定激活位置的延迟多次范围伤害、按目标冷却 | 部分 JSON / 时间线 / 双加载器真实命中已验：12 类武器普通/强化门槛、直击去重、收枪、三波、冷却、初始类别及攻击快照；完整缩放 / 衰减、触发细节校准与装备来源仍缺失 |
| Incandescent | 武器词条 | 范围施加状态层数、按敌人等级取值 | 未写 |
| Frenzy | 武器词条 | 持续交战计时（`buff_expired` 事件） | 未写 |
| Feeding Frenzy | 武器词条 | 每层数值不同（分档查表） | 未写 |
| Desperate Measures | 武器词条 | 同一 buff 多个等级、各等级触发不同 | 未写 |
| 急切刀锋 | 武器词条 | 冲量（服务端发起 + 宽限期） | 未写 |
| Jolt | 元素状态 | 状态自带规则（伤害阈值、连锁、冷却） | 部分 JSON / 世界验收：施加击 / 刷新、阈值 / 冷却、触发者归属、玩家实际损失资格与相邻连锁；完整投影 / 眩晕 / 来源装配仍待完成 |
| Volatile | 元素状态 | 排除施加击、后续阈值 / 致死引爆、目标共享冷却、首次致死施加、范围连锁与来源保留 | JSON / 11 项纯核心 / 5 项共享世界测试通过；真实技能来源、伤害投影与部分内容策略未校准，仍为部分实现 |
| Healing Rift | 职业技能 / 范围场 | 固定位置、成员进出、来源隔离、连续恢复与满血护盾 | 部分 JSON / 11 项内容单测 / 8 项共享世界场景：5 米 / 15 秒、两种恢复率、重叠与结束清理、满血补盾 / Void 阻止 / 空容量资格；脉冲与层生命周期校准、施放 / 地形 / 技能成本及阵营失联策略仍待完成 |
| Eternal Warrior | 金装 | 自有护盾、精准因子排除、停伤延迟、连续回充与破盾终止 | 部分 JSON / 世界验收：75 HP、5 秒延迟、7 秒满量回充及明确精准因子抑制；自动弱点判定 / 跨层校准、Arc 增伤、超能延长和真实装备 / 技能输入未完成 |
| Bolt Charge | 元素状态 | JSON 按实际事件武器容量计数、按批限层、溢出回能、延迟中心放电 | partial；范围与完整分类未校准 |
| Rolling Storm | 武器词条 | 同武器击杀依当前 Amplified 与强化状态授予 Bolt Charge，复用统一 gained 收益 | partial；真实开火到近战放电链已验，Amplified 本体与自动系统装配未实现 |
| Recharge | 碎片 | 修改能量回复速度 | 未写 |
| Getaway Artist | 金装 | 改参数 + 长按替换 + 返能规则（每次爆发最多一次、吞食期间禁止） | 未写 |
| Athrys's Embrace | 金装 | 给技能加行为 + 规则 | 未写 |
| Arbor Warden | 金装 | 替换技能 | 未写 |
| Stormtrance | 超能 | 超能 = buff（减伤 + 技能替换） | 未写 |
| Deadfall | 超能 + 世界物体 | 带来源的 buff、伤害分摊、延长锚点 | 未写 |
| 能量球 | 世界物体 | 吸附范围读收集者属性、拾取事件、合并 | 未写 |
| 涡流手雷 | 技能 + 世界物体 | 投掷 → 生成物体 → 力场 + 范围伤害 | 未写 |
| 屏障 | 技能 + 世界物体 | 有碰撞、有血量的实体 | 未写 |
| 钩爪 | 技能 | 客户端预测、钩住缠结返还能量、钩爪近战 | 未写 |
| 滑翔 | 技能 | 只改原版属性 | 未写 |
| 棱镜术士 build | 综合 | 跨来源级联、动作顺序、查询期条件、资源、替换优先级、resist 相乘（见 d2-ruleset.md 的走查） | 未写 |
| 超新星炸弹快照 | 运行时 | 放出后切装不影响伤害；目标方修饰命中时取值；派生物继承快照；on\_hit 例外 | 通用数值快照 JSON / 纯核心 / 真实延迟扣血已验；炸弹实体、技能数值、派生继承及该技能的反应绑定未实现 |
| Disruption Break 两层错开施加 | 反例：计时器 | 第一层到期不会带走或刷新第二层 | 基础版 JSON / 时间线 / 数值查询通过；游戏未验 |
| Restoration 延长、倒计时、重新施加 | 反例：历史值 | 重新施加时恢复到历史最长持续时间，保留最高强度 | 纯 Buff 状态与 JSON 连续恢复通过；两端验证互斥 / 到期残段 / 实际回血 / tick；原作覆盖优先级、Phoenix Dive 例外及真实技能来源仍未验 |
| Cure 恢复过程与重复激活冷却 | 元素状态 | PvE / PvP 总量、短时恢复、1 秒内重复请求不恢复也不延长冷却 | JSON / 纯核心及双加载器真实 tick 通过；两次等量恢复为 Chorus 选择，实际技能 / 装备来源尚未装配 |
| Frame of Reference 收枪再拿出 | 反例：计时器 | 倒计时暂停并恢复 | Buff 定义 JSON / 计时测试通过；完整词条 / 游戏未验 |
| 同一次命中施加 Jolt 和 Volatile | 反例：结算阶段 | Jolt 把施加的那一下计入阈值，Volatile 不计入 | 两者各自的初始化 / 去重、阈值 / 冷却已有 JSON / 世界测试；Jolt 另有首次施加后伤害不计入的顺序选择；两种状态同击组合场景仍待验收 |
| Slice 命中致死目标、免疫目标 | 反例：结算阶段 | 致死时不施加、不消耗层数；免疫目标照样施加 | JSON / 时间线通过；双加载器 GameTest 通过真实图腾后的状态施加；Sever 减伤、延长配置与完整游戏装配未验 |
| A 枪击杀后 B 枪换弹 | 反例：实例绑定 | 不串用 Kill Clip 窗口 | JSON / 实际换弹时间线通过；该跨枪反例的游戏场景未验 |
| Threadling 停栖再释放 | 反例：继承 | 停栖后失去来源继承 | 未写 |
| 多人、多锚点 tether 连锁 | 反例：归因与投影 | 不跨来源分享；分享读取明确投影，再触发资格由此效果的实测规则决定 | 未写 |
| 超过命令预算、buff 同 tick 到期 | 反例：队列 | 未开始命令可延后；已提交事实的反应先完成，到期状态不出现空档 | 未写 |
| 无限点燃、Riskrunner 自触发 | 正向：循环 | 新派生事件可再次触发同一规则，产生新的 Frame / OpId；不按 root 截断 | 未写 |
| Chorus 武器打僵尸 | 联动 | 伤害经过原版护甲；Rank 按映射取值；击杀照常触发 Rampage | 未写 |
| 攻击 / 防御 Profile 与护盾、原版减伤联动 | 联动 | Kill Clip 与 Disruption Break 修饰实际进入伤害，再经全局减伤、盾、护甲和吸收；玩家与嵌套命中不重复计算 | 纯核心及双加载器 GameTest 通过；Buff 预置，减伤与盾容量为测试参数；来源与内容自动装配未完成 |
| 造成伤害后按实际 HP 损失回血 | 联动 | damage 回执驱动 heal；护甲与吸收不计入 health_loss；治疗满血溢出、回调改量和实际回血分别记录 | JSON / 纯核心及双加载器 GameTest 通过；通用动作链，未主张某个具体词条的参数 |
| 僵尸打带 overshield 的玩家 | 联动 | 先按预算消耗 Chorus 护盾层，剩余交给原版护甲和生命；作为 credit 为空的 hit 进入队列 | 部分：双加载器已验证玩家盾、原版生物攻击与盾后护甲 / Absorption；尚未覆盖僵尸 AI 自动攻击玩家整条链 |

### 数值与执行协议验收

以下是必须覆盖的预期结果。纯数学与执行协议中的一部分已有单元测试，逐项进度见 [engine-implementation.md](engine-implementation.md)；涉及世界、Buff 和完整内容的用例仍未完成。纯数学例子的基础值是测试输入，不声称某把真实武器具有该值；近似社区曲线使用明确误差界。

| 用例 | 预期结果 |
| --- | --- |
| base=100、add=50、percent=20%，分别引用 base / after_flat | 170 / 180 |
| +10% 与 +25% 使用 product；MAX 只有 −40% | 增量 0.375；MAX 输出 −0.40，不被 0 覆盖 |
| 装填属性 160，Slow −75% 在上限前 | 40；属性曲线之后的时间倍率另算 |
| 200 直击可暴击、800 爆炸不可暴击，暴击倍率 2 | 1200，不是 2000 |
| 45 容量 / 70% 层内减伤盾，之后为足够大的无减伤护盾；输入 200 | 第一层损失 45、下一层损失 50，预算消费 150+50；实际损失 95 |
| m_i=0 的免疫层；非致死伤害打剩 1 HP 目标 | 不除零、不扣该盾；保留最低生命，不制造 kill |
| 两格技能收到 10% charge_fraction | 增加 0.10，不是 0.20；并行与串行回充另测 |
| MAX 组中施放时已冻结 20%、命中时目标条件贡献 30% | 合并为 30%，不乘成 1.20×1.30 |
| 世界伤害已提交后重复返回同 OpId；同 root 新事件再次触发规则 | 前者只提交 / 绑定一次；后者新 OpId 正常执行 |
| 一次攻击多个分量共同致死；状态初始化与旧监听同时存在 | 一次死亡迁移；施加击累计一次，Jolt / Volatile 各按自己的规则 |
| 恢复增益在积分区间中途到期；DOT 间隔不是 tick 整数倍 | 在到期点分段积分；按绝对 due_at 递推不累计漂移 |
| Profile 引用缺失阶段、单位不匹配、数据值 unknown | 明确加载错误 / 禁用未完成内容，不静默算零 |
| 旧投射物飞行中热重载 / 切枪 | 数值快照已保留来源与旧 Profile；当前防御、兼容布局的 on_hit 贡献可实时求值；显式物理实体与来源层反应绑定已接入；不兼容反应目录拒绝，活动版本迁移与派生继承待实现 |

### 例子：Rampage

表里的定义是：武器击杀后增伤 10% / 21% / 33.1%（1～3 层），持续 4.5 秒（强化 5 秒），再次击杀刷新，层数逐层衰减，收起武器后保留。

该片段仍为目标语法示意。当前 Codec 的 one_by_one 要求明确 decay_interval；快照 C172 未单列后续掉层间隔，因此不能靠省略字段得到隐含数值并声称验收通过。该缺口已经记入覆盖清单。

```
// 效果包 chorus:rampage（挂在武器上）
{ "rules": [ { "on": "chorus:kill",
               "if": { "type": "chorus:source_is", "source": "this_weapon" },
               "do": [ { "type": "chorus:grant_buff", "buff": "chorus:rampage", "stacks": 1, "key": "this_weapon" } ] } ] }

// buff chorus:rampage：挂在玩家身上，按武器区分实例，只给那把武器增伤，收起后保留
{ "duration": { "type": "chorus:enhanced", "base": 4.5, "enhanced": 5 },
  "max_stacks": 3, "refresh": "reset", "decay": "one_by_one",
  "attach": "holder", "instanced_by": "weapon", "affects": "instance_weapon", "on_stow": "keep",
  "bundle": { "modifiers": [ {
    "stat": "chorus:weapon_damage", "op": "multiply", "group": "weapon_perk",
    "value": { "type": "chorus:by_stacks", "values": [0.10, 0.21, 0.331] } } ] } }
```

这里有一个细节：buff 挂在玩家身上（所以收起武器不会丢），但增伤只作用于来源那把武器。所以挂在哪（`attach`）和作用于谁（`affects`）是两个独立字段。

## 开放问题与下一步

### 开放问题

- [x] 采用按 Profile 的数值计算；独立武器 perk 相乘，但先检查资格与家族规则，近战增伤另走加算组。
- [x] 生命值以原版为准，Chorus 只加护盾层和目标方修饰，原版护甲等照常生效（适配 Minecraft 优先）。插入点和先后顺序在实现时验证。
- [ ] 定缩放因子：伤害、生命、阈值类数值用同一个因子；验证元素护盾、overshield 与原版伤害吸收的叠加。
- [ ] 原版的力量药水、锋利附魔只作用于原版攻击路径；Chorus 武器要不要吃这些加成。
- [ ] 原版和其他模组生物的 Rank / Tier 映射表（实体类型或标签 → 等级）。
- [ ] 是否全局取消摔落伤害。
- [ ] 配置文件自己用 Codec + JSON 写，还是选一个两边都能用的第三方库。
- [ ] 射击的命中判定：纯服务端，还是客户端判定 + 服务端校验。
- [ ] 遭遇战脚本：只用同一套规则加状态机，还是另外开放 mcfunction 作为逃生口。
- [ ] 公开发布前，把命运 2 的词条名、金装名换成自己的名字（Bungie 知识产权）。
- [x] 装备存储采用独立容器，槽位由内容包定义，支持三武器与五件护甲的声明；不能直接映射原版护甲槽。已实现真实物品转移、玩家保存 / 加载及死亡 / respawn 接线；已有网络校验与最小配装页；完整 D2 物品装配、主手联动、HUD / 技能 UI 和跨模组适配仍待完成。
- [ ] 来源层绑定协议已支持 origin_bundle / current_owner_bundle；仍需逐条验证武器卸下后是否继续触发，例如庆典飞行毒池与羸弱能量球。
- [ ] 校准资源 value_basis、CES / CMS 豁免、Super 主动收益、完整冰霜护甲层数表、Scorch 与生命恢复的冲突数值；区分 measured / fitted / assumed。
- [ ] 多充能已有 sequential / parallel / linked 协议，Ophidia Spathe 等具体时间线尚未验证；漫游超能消耗策略与技能执行实例仍需用例。
- [ ] 世界物体之间的关系：Anarchy 连线、缠结拿取投掷、Briarbinds 回收重部署。

### v0 实现范围

| 部分 | v0 实现 | 先留字段或接口 |
| --- | --- | --- |
| stat 层 | Profile 固定步骤、贡献单位检查、四种合并器、分组树、查表曲线 | 公式曲线、输出上的置信度标记 |
| 伤害 | 分量、精准、护盾层预算算法、`DamageResult`、伤害前后钩子 | 特殊跨层策略、全部命名投影 |
| 资源 | 一份充能 = 1.0；被动恢复、`grant_chunk`、`grant_full_charge`；单格串行回充 | parallel / linked 多充能 |
| 执行 | Frame + step / resume、OpId、命令与事实队列、故障阈值、到期追赶 | 回执持久化（v0 只在一次结算内保留） |
| 版本 | 单一规则集版本 | 热重载保留旧快照、多版本并存 |
| 诊断 | 调试命令打印一次查询的计算轨迹 | 轨迹保留上限、离线分析 |

### 下一步

1. 注册抽象：common 声明，加载器在允许的时机注册。
2. stat 层先实现 CalculationProfile、NumericContribution、分组归约与 CalculationTrace，覆盖数值验收；同时准备带来源的最小规则集。
3. rule / effect 骨架：类型注册表、Codec、纯函数 step / resume、v0.3 结果类型与状态组件、Frame / OpId、事件队列；只对未开始的外部命令使用预算。
4. 用 Rampage 加快照 MAX 合并、分量暴击、分层盾、chunk 回能和合法重触发跑通全链路；调试命令能显示每一步数值与版本。
5. 一把最简单的枪：射线判定命中，原版风格模型。
6. 逐条写金标准测试集，更新上表的状态列。
