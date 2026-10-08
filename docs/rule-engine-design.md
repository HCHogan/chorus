# Chorus 规则引擎设计草案 v0

2026-10-09 · Hank Hogan

## 目标与范围

Chorus 是一个类命运 2 的 Minecraft 模组，核心是一个数据驱动的规则引擎：武器词条、护甲模组、碎片、星象、金装、技能效果、元素状态、敌人能力，都用同一套 DSL 描述。

- **目标平台**：Minecraft 26.3，Java 25。MultiLoader 结构（common / fabric / neoforge），Fabric 先做，NeoForge 按里程碑补齐。
- **v0 范围**：规则 DSL、buff、属性管线、技能、世界物体、移动。
- **v0 不做**：射击手感（瞄准、后坐力、第一人称动画）和自定义渲染。前期用原版风格模型、粒子和原版展示实体（Display）。遭遇战脚本和 UI 只定接口。
- **需求来源**：Destiny Data Compendium（2026-10-05 副本）里 15 个现行 sheet 约 1,476 条效果描述，用正则粗略统计。

设计原则：

1. 能写成数据就写成数据。Java 只写"种类"（新机制），JSON 写"实例"（具体内容）。
2. 离散的状态（有没有、几层、还剩多久）都是 buff，包括玩家看不到的隐藏 buff；连续的量（技能能量、超能能量、超凡条）是资源。
3. 规则只在服务端计算，客户端只负责显示。唯一的例外是玩家自身的移动。
4. 纯函数核心：`(状态, 事件) → (新状态, 动作列表)`，副作用集中执行，规则可以直接用 JUnit 测试。
5. DSL 保持声明式，不追求图灵完备。表达不了的，用 Java 注册自定义类型，JSON 照样按名字引用。
6. 优先照抄原版：附魔效果组件（`fire_aspect.json`、`lunge.json`）、战利品谓词、`LevelBasedValue`、`ExperienceOrb`、`ApplyEntityImpulse`。

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
  effect ................ buff、资源：离散状态与连续量，带来源
     │
  rule .................. 【核心】DSL：事件、条件、动作、数值的类型注册表
     │
  stat .................. 属性管线：修饰、叠加分组
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
  "modifiers": [ { "stat": "minecraft:movement_speed", "op": "base_percent", "value": 0.1 } ] }
```

规则还可以带 `cooldown`。它是语法糖，等价于"触发时挂一个隐藏 buff，有这个 buff 时不触发"。

### 事件（v0）

表里有 753 种不同的触发写法，实际是少量事件类型乘以各种条件。事件本身带数据，条件对数据做判断。

| 事件 | 携带的数据 | 表里的例子 |
| --- | --- | --- |
| `chorus:kill` | 来源链（owner 谁、via 哪个武器实例或技能、object 哪个世界物体、tags）和攻击方快照、是否精准、目标（等级、状态、元素） | On Weapon Kill（40 条）、On Precision Kill |
| `chorus:hit` | 同上，加 dealt（打出多少）和 effective（实际扣掉多少，不含溢出） | Upon dealing Melee Damage |
| `chorus:shot_resolved` | 哪把武器、命中的弹丸数；一枪只发一次 | One-Two Punch（12 颗弹丸全中） |
| `chorus:damage_taken` | 攻击者、伤害类型、数值 | Feedback |
| `chorus:ability_used` | 槽位、技能 id | On Class Ability Usage（29 条）、On Super Cast |
| `chorus:reload_finished` | 哪把武器 | Kill Clip、Voltshot |
| `chorus:pickup` | 物体类型（能量球、弹药砖、离子痕迹……） | On Orb of Power Pickup（15 条） |
| `chorus:finisher` | 目标 | On Finisher（15 条） |
| `chorus:health_threshold` | 跨过的阈值 | Upon reaching Critical Health |
| `chorus:shield_broken` | 目标、护盾元素 | Press The Advantage |
| `chorus:buff_gained` / `buff_expired` / `buff_consumed` | buff id、层数 | Frenzy |
| `chorus:movement` | 冲刺、滑铲、离地、落地 | Haste、While Sliding |
| `chorus:grapple_attached` / `grapple_end` | 钩住了什么 | Grappling a Tangle |
| `chorus:tick` | 周期 | 周期性效果 |

同一次动作造成的多次伤害共享一个批次 id，用于"每个同时伤害实例只计一次"这类规则（电光充能）。毒池、丝线、闪电打击造成的伤害也归到背后的武器或技能：武器词条的事件规则按 via 匹配，而不是看手里拿着谁。

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
- **资源**：`grant_energy`（槽位、数值、是否按能量块系数缩放）、`refill_magazine`、`grant_ammo`。
- **世界**：`spawn_object`（世界物体）、`apply_impulse`、`play_sound`、`particles`。
- **逃生口**：`run_function`（原版 mcfunction），或 Java 注册的自定义动作。

动作作用于谁，由选择器决定：`self`、`victim`、`attacker`、`buff_source`（buff 是谁挂的）、`area`（半径 + 过滤条件）、`same_source`（和我来源相同的其他持有者）。

动作列表严格按顺序执行，后面的动作看得到前面动作的结果。例：闪电激涌先挂增幅再落雷，落雷击杀才能触发牺牲琢面。

### 数值表达式

数值不是常量而是表达式，思路同原版 `LevelBasedValue`，上下文更多。

| 类型 | 含义 | 表里出现的比例 |
| --- | --- | --- |
| `enhanced` | 按是否强化取值（↑） | 31% |
| `pvp` | 按目标是否玩家取值（方括号） | 32% |
| `by_rank` | 按目标等级查表 | 12.5% |
| `by_stacks` | 按层数或份数查表（a \| b \| c） | 7% |
| `by_weapon_type` | 按武器类型查表 |  |
| `stat` | 读一个属性 |  |
| `add` / `mul` / `min` / `max` / `clamp` | 算术组合 |  |
| `by_loadout` | 按装配取值。例：使命琢面按已装备超能的元素给不同增益 |  |
| `resource` | 读一个资源的当前值。例：一条超凡条满了以后，动能给另一条充能变慢 |  |
| `diminishing` | 递减：base × factor^层数。例：超凡期间每次击杀延长的时间按 0.9 递减 |  |
| `by_tier` | 按目标档次（Tier 1–6）查表，和 by\_rank 分开。例：吞食按档次返还手雷能量 |  |
| `by_target_class` | 按目标类别：玩家 / 战斗人员 / 构造物。例：Ignition 676，对玩家 120，对构造物 250。pvp 是其中“玩家”的简写 |  |
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

// 纯函数核心：不碰世界，直接用 JUnit 测。按执行帧一步一步推进，见「核心语义 v0.2」的「动作执行」
Outcome step(RuleState state, Frame frame, RuleContext ctx);
```

## Buff：唯一的离散状态原语

计时、层数、时间窗口、计数器、一次性充能、元素状态，全都用 buff 表达，DSL 不单独发明"序列"或"计数器"语法。命运 2 自己也大量使用玩家看不到的隐藏 buff。

### buff 定义

| 字段 | 取值 | 表里对应的写法（出现比例） |
| --- | --- | --- |
| `duration` | 数值表达式 | for N seconds（35%） |
| `max_stacks` | 整数 | stacks、up to xN（17.5%） |
| `refresh` | `reset` 重置 / `extend`（加多少、上限）/ `none` 到期前不可刷新 | refresh / extend（13%） |
| `decay` | `all` 一次全掉 / `one_by_one`（可带延迟） | Stacks decay one at a time |
| `scope` | 已拆成 `attach`、`instanced_by`、`affects`、`on_stow` 四个字段，见「核心语义 v0.2」 | removed on stow / persists through stow（16.5%） |
| `per_source` | 并入 instanced\_by | Kinetic Tremors、Deadfall 拴住 |
| `hidden` | 是否对玩家隐藏 | 窗口、计数器 |
| `tags` | 类别标签 | "Arc 类 debuff"、互斥、叠加分组 |
| `bundle` | 存在期间生效的效果包 | Jolt 自带连锁闪电规则；Stormtrance 自带减伤和技能替换 |
| `display` | 图标、名称、是否显示层数 |  |

buff 实例用「持有者 × buff 定义 × 来源」区分，状态除了层数和计时器，还可以带累加器、去重集合、引用、历史值等组件，见「核心语义 v0.2」。来源可以是实体、武器实例，或者为空。

### 常见模式怎么用 buff 表达

| 表里的写法 | 做法 |
| --- | --- |
| reload within 3.6s / 5.3s of a kill（Kill Clip / Voltshot） | 击杀时挂一个隐藏的"窗口"buff |
| 3 kills within 6 seconds of each | 隐藏计数 buff：每次 +1 层并刷新，层数到 3 时触发并消耗 |
| The next hit inflicts…（Voltshot） | 一次性 buff，下一次命中时消耗 |
| 对同一目标多次命中（Kinetic Tremors） | 隐藏 buff 挂在**目标**身上，按来源区分 |
| 持续交战 12 秒（Frenzy） | 隐藏 buff，加上 `buff_expired` 事件 |
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
                                "value": 0.25, "group": "weapon_perk" } ] } }
```

元素状态也是 buff，而且可以自带规则。以 Jolt 为例，表里的规则是"受到 115 点伤害时，对 12 米内的敌人释放连锁闪电，0.8 秒冷却"，直接写在 Jolt 的 `bundle` 里。表里出现最多的状态：Scorch 80 条、Slow 75、Overshield 58、Weaken 53、Jolt 51。

### 资源

连续变化的量不用 buff，用资源：技能能量、超能能量、超凡光条和暗条。资源 = 名字 + 当前值 + 上限 + 回复速率（回复速率是一个属性）。

- 写：`add_resource`。技能槽的 `grant_energy` 是它的特例，多了按能量块系数缩放。
- 读：数值表达式 `resource`。
- 超凡充能几乎每次命中都会触发，是全游戏最热的规则，实现时要保证它便宜。

## 属性与数值管线

所有数值走同一条管线：基础值 → 按叠加分组合并修饰 → 最终值。玩家属性、武器属性、技能参数共用这一套。

### 三类属性

| 类别 | 例子 | 实现 |
| --- | --- | --- |
| 实体属性 | `minecraft:movement_speed`、`gravity`、`max_health`；自定义的手雷/近战/职业技能/超能属性、能量回复速率 | 原版 `Attribute` + `AttributeModifier`，自动同步到客户端 |
| 武器属性 | 换弹速度、操控性、稳定性、射程、弹匣、伤害 | 在武器实例上计算。表里 +N 属性出现最多的：换弹速度 94 次、操控性 72、稳定性 72、射程 50 |
| 技能参数 | Arc Soul 持续时间、飞刀弹跳次数、手雷半径 | 按「技能 id / 参数名」寻址 |

### 修饰与叠加分组

```json
{ "stat": "chorus:reload_speed", "op": "add", "value": 70, "group": "flat" }
```

`op` 取 `add`（固定加值）、`base_percent`（基础比例）、`multiply`（外部倍率）、`resist`（减伤）。数值一律写成原文里的增量：+25% 写 0.25，15% 减伤写 0.15。`multiply` 修饰属于一个叠加分组，组内按各自的规则合并（求和、取最大、相乘，即一个 monoid），合并结果 x 以 1 + x 参与组间相乘；`resist` 以 1 − r 相乘。完整阶段见「核心语义 v0.2」。

```latex
v = \mathrm{curve}\Big(\mathrm{clamp}\big((b + \sum a) \times (1 + \sum p) \times \prod_{g} (1 + x_g) \times \prod_{i} (1 - r_i)\big)\Big)
```

b 是基础值，a 是固定加值，p 是基础比例，x\_g 是分组 g 合并后的增量，r\_i 是各个减伤来源。每个属性声明自己经过哪些阶段。

| 分组 | 组内合并 | 依据 |
| --- | --- | --- |
| `flat` | 求和 | +70 Reload Speed 这类 |
| `empowering` | 取最大 | 已确认：同类增伤取最高 |
| `weaken` | 取最大 | 已确认：目标身上的同类易伤 debuff 取最高 |
| `weapon_perk` | 每个各自相乘 | 命运 2 里武器词条彼此相乘 |
| `resist` | 相乘 | 表里 Resistance 模组：不同来源的减伤互相相乘；保护琢面 15% 与超凡 20% 合计 32%（1 − 0.85 × 0.8），再次确认 |

参照：命运 2 当前的乘区（PvE）。

| 乘区 | 区内怎么合并 | 例子 | 来源 |
| --- | --- | --- | --- |
| 精准 | 单个倍率 | 每种武器各自的爆头倍率 |  |
| 敌人等级 | 单个倍率 | 异域主武器对小怪 +30% | Compendium |
| 光等差 | 查表 | -30：输出 ×0.446、承伤 +85%；-50：×0.144、+150% | Compendium 首页 |
| 6 维属性（超过 100 的部分） | 按伤害来源取对应属性 | 武器：对 Boss 0–15%（重武 0–10%）。手雷 0–65%。近战 0–30%，算作基础伤害提升。超能 0–45% | [Armor 3.0](https://www.pcgamer.com/games/fps/destiny-2-armour-3-0-stats-archetypes/) |
| 强化 buff | 取最高 | Radiant 20%（对冠军 30%）、Well of Radiance 25% | Compendium |
| 目标 debuff | 取最高 | Weaken 15%；特殊削弱 30%（如 Moebius 拴住） | Compendium |
| 武器激涌 | 取最高（按层数） | x1–x4：10% / 17% / 22% / 25% | [2024 年的文章](https://www.blueberries.gg/leveling/destiny-2-buffs-debuffs/)，数值可能已变 |
| 近战增伤 | **加算** | Edge of Fate（2025-07）起所有近战增伤相加 | [Game Rant](https://gamerant.com/destiny-2-melee-combat-changes-edge-of-fate-expansion/) |
| 其他（武器词条、金装内置、神器、活动修饰） | **每个各自相乘** | Rampage、Kill Clip；表里大量标注 "Stacks with everything" | Compendium |
| 对冰冻目标 | 相乘 | Whisper of Rending 等对冰冻目标的增伤彼此相乘 | Compendium |

- 区内合并有三种：取最高、加算、相乘，正好对应"每个分组一个 monoid"。
- 边界情况：Well of Radiance 的 25% 会覆盖 Radiant 对冠军的 30%，所以"取最高"之外还要支持可选的优先级覆盖。
- 破隐攻击和碎冰不是乘区。破隐是规则：隐身结束时触发 buff（如 Gyrfalcon's Hauberk）。碎冰是冰冻 buff 自带规则触发的一次独立伤害（基础最多 361），再被"对冰冻目标"乘区放大。

叠加分组可以嵌套成树，每个节点一个 monoid。例：近战组相加，但雪上加霜和"对冰冻目标的近战增伤"互斥、取高（Compendium）：

```
melee: sum
 ├─ warlords_sigil      300%
 ├─ max                  ← 互斥子组
 │   ├─ one_two_punch   150%
 │   └─ frozen_melee    120%
 └─ …
```

修饰的条件只看持有者状态时可以缓存；看命中上下文（目标、伤害类型）时每次命中现算，见「运行时：控制流」。

修饰还有一个 `evaluate` 字段：默认 `on_use`，在武器或技能使用时冻结进快照；少数是 `on_hit`，命中时才取值，见「运行时：控制流」里的「快照」。

### 其他

- **属性曲线**：命运的"属性 → 实际效果"（比如 Game Mechanics 里的机动性档位 → 行走速度）做成单独的数据类型：分段线性表，不属于规则 DSL。
- **PvP**：数值差异用 `pvp` 数值表达式。性质差异用"目标是玩家"条件分支。比如 Blind：对敌人是 10 秒无法攻击，对玩家是 HUD 消失、屏幕变白 3 秒。
- **元素伤害**：用原版的数据包伤害类型（`damage_type`）定义动能、电弧、日光、虚空、静止、缠绕。
- **无敌帧**：原版生物受伤后约半秒内的后续伤害会被吞掉。把自己的伤害类型加进 `#minecraft:bypasses_cooldown` 标签（26.3 里这个标签是空的）。
- **精准命中**：原版碰撞箱只是一个长方体，按命中点的高度自己判断。

## 技能

技能 = Java 写的"种类" + JSON 写的"定义"。参数暴露成可修饰的技能属性，运行时发出带技能 id 的事件，并且可以被整个替换。表里 77 个星象有 51 个、138 条金装护甲描述有 79 条会点名修改技能，所以技能从一开始就要设计成可被修改的。

### 种类与定义

- **种类**（Java，数量少）：投掷型手雷、近战突进、闪避/冲刺、放置物（屏障、裂隙）、漫游超能、钩爪、滑翔、瞬移。每个种类声明自己有哪些参数、会发出哪些事件。
- **定义**（JSON，数量多）：用哪个种类、参数、冷却、充能次数、元素、标签，以及在关键时刻执行的动作。

```json
// chorus:arcbolt_grenade（冷却和能量块系数取自表，其余数字只是示意）
{ "kind": "chorus:projectile_grenade", "slot": "grenade", "element": "chorus:arc",
  "cooldown": 151.5, "chunk_scalar": 0.75, "charges": 1,
  "params": { "fuse": 0.6, "radius": 6 },
  "on_detonate": [
    { "type": "chorus:damage", "targets": { "type": "chorus:area", "radius": 6 }, "amount": 120, "damage_type": "chorus:arc" },
    { "type": "chorus:apply_status", "targets": { "type": "chorus:area", "radius": 6 }, "status": "chorus:jolted" } ] }
```

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

装配解析：基础装配（子职业选择）→ 按优先级应用各效果包的替换 → 实际生效的技能。变化时重算，并同步到客户端供 UI 显示。金装护甲限 1 件，可以避免大部分替换冲突。

替换优先级从低到高：基础装配 → 星象的条件替换（如滑铲中近战变闪电激涌）→ 超凡 → 超能。条件替换在按键时现算，不进缓存。

### 能量与充能

- 每个技能槽是一份资源：基础冷却、能量块系数、充能次数。表里的例子：Acrobat's Dodge 基础冷却 56 秒、系数 0.5x。
- 回复速率是一个属性，受手雷/近战/职业技能/超能属性和 buff 影响。比如 Recharge 碎片的"基础回复速度 +400%"就是一个修饰。
- Demolitionist 这类"返还一块能量"的动作，按技能的能量块系数缩放。

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
- **拴住类效果**（Deadfall、Shadowshot）：锚点的 `aura` 给敌人挂 `tethered` buff，来源记为锚点。buff 自带削弱、压制、向来源靠拢的拉力，以及"伤害的 50% 分摊给 `same_source` 的其他敌人"。持有者死亡时，延长 `buff_source` 的寿命。
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
- **规则**：表里的 "Grappling a Tangle fully refunds Grenade Energy" = 钩住的是缠结时，返还 100% 手雷能量。
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

- **血量上限**：原版 `max_health` 最大 1024（26.3 `Attributes.java`）。命运的血量要按比例缩放，或者自己做生命值体系，见下方待定。
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

- 血量体系：缩放命运的数值塞进原版血量（上限 1024），还是自己做一套生命值。
- 是否引入光等和光等差。
- 模型动画：依赖 GeckoLib，还是只用原版模型。

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
| 属性曲线 | 数据包注册表 |  |
| 掉落与词条 roll 规则 | 数据包（可复用战利品表） |  |

数据包注册表都注册成"同步"的，客户端进入世界时自动拿到定义。

### 运行时状态

| 放在哪 | 放什么 | 注意 |
| --- | --- | --- |
| 物品上（数据组件） | 随机出的词条、强化、大师级、击杀数 | 不放每帧都在变的东西，否则频繁同步、物品比较失败 |
| 实体上（附加数据） | buff 实例、资源（技能能量和充能、超能能量、超凡条）、子职业选择、编译后的规则缓存 | 只同步 UI 需要的部分（可见 buff、能量条、实际生效的技能） |
| 世界上（存档数据） | 遭遇战进度 |  |
| 不存档 | 世界物体 | 短命，重进世界即消失 |

## 运行时：控制流

引擎是事件驱动的：每 tick 只处理和时间流逝有关的事，绝大部分逻辑在事件发生时一次跑完一整串级联。

### 四种运行时机

| 时机 | 做什么 | 例子 |
| --- | --- | --- |
| 装备变化时（编译） | 收集静态层的效果包，解析技能装配，建事件索引，归类修饰 | 换金装、换碎片 |
| 事件发生时（反应） | 找到监听该事件的规则，判断条件，按顺序执行动作；动作产生的新事件入队 | 击杀、命中、拾取能量球、放技能 |
| 结算伤害时（查询） | 沿属性管线当场算出一个数，同步返回 | 一次近战打多少；一次受击剩多少 |
| 每 tick | buff 到期与衰减、资源回复、低频采样、对齐 | 冰霜护甲掉层、技能能量回复、"被包围"采样 |

修饰是查询，规则是反应。查询是拉取式的：原版的伤害调用正等着这个数，所以不能排队。反应是推送式的：事件进队列，排空时执行。

### 一个 tick 的顺序

26.3 服务端每 tick 先处理排队的网络包，再 tick 各维度，再 tick 玩家连接（`MinecraftServer.processPacketsAndTick`、`tickChildren`）。引擎 tick 挂在整服 tick 末尾。

1. 网络包：技能输入到达，解析技能槽，dispatch `ability_used`。
2. 各维度 tick：怪物 AI、世界物体（毒池跳伤害、追踪弹）。伤害走查询，结果 dispatch `hit` / `kill`。
3. 玩家连接 tick（`ServerPlayer.doTick`）。
4. 引擎 tick（Fabric `END_SERVER_TICK`，NeoForge `ServerTickEvent.Post`）：
   1. 推进时间：buff 到期、逐层衰减，产生 `buff_expired`。
   2. 资源积分：技能能量、超能能量按回复速率增加。
   3. 低频采样：每 10 tick 算一次环境条件，结果存成隐藏 buff（保护琢面的 `surrounded`，持续 12 tick）。
   4. 排空事件队列。
   5. 对齐：变脏的派生效果写回原版属性；同步 UI 需要的状态。

```text
所有入口都汇入同一个事件队列；伤害数值走同步查询

① 网络包                技能输入、开火 ──► 命令
② 各维度 tick           怪物攻击、世界物体周期伤害 ──► 命令
                        原版造成的伤害从“受伤之后”事件接入 ──► 事实
③ 玩家连接 tick         原版 ServerPlayer.doTick，引擎不介入
④ 引擎 tick（末尾）     追赶到期转换 ──► 事实；推迟的命令留到下一 tick

命令
 │   每 tick 的全局预算只管命令，超出就推到下一 tick
 ▼
伤害结算（查询）        同步：攻击方快照 × 目标方修饰 → 免疫 → 护盾层 FIFO → 生命 → 是否致死
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

结算边界就是“执行一条命令”的调用返回之前。原版也这样做：怪物攻击时先调用 `target.hurtServer` 结算伤害，然后在同一次调用里立刻执行荆棘这类攻击后效果（`Mob.java:1391`）。原版造成的外部伤害（怪物打玩家）从加载器的“受伤之后”事件接入，在那里同步跑完结算边界。

```
runCommand(c):                         // 每 tick 的全局预算只管命令
  if 本 tick 命令预算用完: deferred.add(c); return
  facts = execute(c)                   // 例如结算一次伤害，得到 DamageResult
  queue.addAll(facts)
  drain()                              // 结算边界：反应全部跑完才返回

drain():
  while queue 非空:
    e = queue.poll()
    if chainWork[e.root] 超过上限: 截断并告警; continue   // 正常玩法碰不到，碰到就是 bug
    chainWork[e.root]++
    process(e)                         // 反应里再造成的伤害同样当场结算，新事实追加到队尾

process(e):
  rules = 静态索引[e.type] ++ 持有者每个带包 buff 的索引[e.type] ++ e 携带的已结束实例的规则
  同一实例的同一规则在一个事件里最多执行一次
  for r in rules:
    冷却中、条件不成立、或 e.proc_policy 禁止 r → 跳过
    按执行帧协议运行 r.actions（见「核心语义 v0.2」的「动作执行」）
```

- 广度优先：Unravel 丝线、Jolt 连锁、连环碎冰这类级联不会爆栈，顺序确定，可以用 JUnit 测。用 Haskell 的话说是 worklist 不动点：`process :: Event -> State -> (State, [Event])`。
- 引擎不做通用的防循环。命运里的循环是玩法：无限点燃；Riskrunner 的 Chain Reaction 可以自己触发 Arc Conductor（Exotic Weapons!D96）。它们由各效果自己的冷却、触发即消耗、阈值、敌人死亡和原文的排除列表收敛。因果链上限只防数据写错造成的零延迟死循环：截断并打出整条因果链，正常玩法碰不到。
- 过载时只允许推迟命令。推迟反应会改变玩法：击杀已经发生，但 Rampage 晚一 tick 才生效，同 tick 的下一枪就吃不到增伤。如果以后确实需要，只能作为显式的降级策略，并写进日志。

### 编译只针对静态层

- 静态层：子职业、星象、碎片、护甲、武器槽里的武器。变化时重新编译技能装配、事件索引和修饰归类。
- buff 层：每个带效果包的 buff 定义预先建好自己的小索引，分发时拼进来。吞食、增幅这类几秒一得一失的 buff 不触发重编译。
- 技能槽在按键时解析：按优先级叠加替换（见「技能」）。"滑铲中"这类条件替换按键时现算，不进缓存。

### 修饰的两种求值

- 只看持有者状态的（被包围、超凡中）：算一次缓存，变脏再算。
- 看命中上下文的（目标身上有什么减益、这次伤害的元素和标签）：每次命中现算。例：勇气琢面。

### 快照

武器和技能在使用时冻结攻击方状态，命中时只现算目标方。例：超能属性 200 时放出超新星炸弹（超能伤害 +45%），飞行途中换成超能 100 的护甲，命中仍按 ×1.45；目标此时被 Weaken，再 ×1.15。

参照：Compendium 有词条注明 "Checks on-hit, instead of snapshotting on-usage"，说明原作至少有两种行为。默认在使用时快照是 Chorus 自己的约定，每个效果仍要单独核对。原版箭生成时复制射出它的武器（`AbstractArrow.firedFromWeapon`），命中时用这份拷贝对当下的目标算附魔增伤。

| 内容 | 何时取值 | 例子 |
| --- | --- | --- |
| 来源链（owner、via、tags） | 使用时 | 这颗炸弹是谁、用哪个技能放的 |
| 技能定义和参数（替换、参数修饰之后） | 使用时 | 炸弹半径、基础伤害 |
| 只看攻击方状态的出伤修饰 | 使用时，算成数 | 超能属性 200 → +45%；Radiant |
| 条件看目标的攻击方修饰 | 使用时决定有无，命中时判条件 | 勇气琢面 |
| 目标方修饰 | 命中时 | Weaken、冰冻、目标自身的减伤 |
| `evaluate: on_hit` 的修饰 | 命中时 | Compendium 里注明 on-hit 的例外 |

```java
record DamageSnapshot(
        SourceChain source,                     // owner、via、tags
        ResolvedAbility ability,                // 替换和参数修饰之后的技能定义
        Map<GroupId, GroupPartial> attacker,    // 按叠加分组存已冻结的部分
        List<Modifier> deferred) {}             // 命中时再判条件的修饰
```

- 攻击方按分组存，不乘成一个数：取最高的分组里，命中时才确定的修饰要和已冻结的部分一起取最高。
- 派生物按自己定义里的继承规则继承父快照（见「核心语义 v0.2」）：急冻手雷连锁出的追踪弹、庆典飞行的毒池。
- 挂到目标身上的状态记录施加时的快照：Unravel 丝线、Jolt 连锁的后续伤害用它。
- 即时命中的武器走同一路径，快照只存在一瞬间。
- 快照是数据，不是闭包：可以打印、比较、在 JUnit 里重放。世界物体不写存档，快照暂时不需要存盘用的 Codec。

反应规则（on hit / on kill）不用快照，用 owner 当前的规则集，因为它们改变的是 owner 现在的状态。owner 死亡或下线时，伤害照常结算，作用于 owner 的反应跳过。来源武器自己的词条是否跟着来源走，见「开放问题」。

## 核心语义 v0.2

下面是写解释器之前必须定下来的“形状”。改形状贵，加种类便宜：事件、条件、动作、数值、组件的新类型以后随时能加，但这些 record 和协议一旦有内容依赖就很难改。本节来自 2026-10-09 对照 Compendium 的两轮机制审查，和前文冲突时以本节为准。

### 伤害上下文

来源链只回答“从哪里来”。来自某把武器，不代表它算武器伤害，也不代表它吃全部武器增伤，所以伤害上下文分五个维度：

| 维度 | 回答什么 | 例子（Compendium） |
| --- | --- | --- |
| origin | 谁制造、哪个武器或技能实例、root / parent 事件 | 所有伤害 |
| damage\_kind | 直接命中、爆炸、持续伤害、状态派生伤害…… | 庆典飞行的毒池是持续伤害 |
| credit | 对规则算武器、手雷、近战还是超能伤害 | Voltshot 施加的 Jolt 不算武器伤害（`Weapon Perks!C243`）；Kinetic Tremors 的震波算（`C135`） |
| scaling\_profile | 接受哪些增伤分组 | Hellion 能触发手雷交互，但施加的日光效果不吃手雷伤害缩放（`Prismatic!D60`）；近战增伤不缩放灼烧和点燃（`Solar!D8`） |
| proc\_policy | 能触发哪些后续效果 | 原文的排除列表，默认全部允许：Jolt 的连锁闪电、非技能的灼烧和点燃不触发电光充能放电（Arc!D5） |

实现上可以继续用标签，但每个维度是一组独立的标签，规则只查自己关心的那一组。

派生物和状态的继承写在它们自己的定义里，不再一律继承父快照。Threadling 停栖后失去来源继承（`Strand!D6`）；点燃可以继承初始灼烧来源的增伤，非技能来源的点燃不触发电光充能放电（`Solar!D8`）：

```json
"inherit": {
  "origin": "keep",
  "credit": ["grenade"],
  "scaling": { "exclude_groups": ["grenade_damage"] },
  "proc": { "deny": ["chorus:bolt_discharge"] },
  "lose_on": ["perch"]
}
```

### 伤害结算结果

一次伤害按固定阶段结算，结果作为 record 返回，规则声明自己读哪个阶段：

```
攻击方出伤（快照）→ 目标方修饰 → 免疫判定 → 护盾层（FIFO，每层自己的减伤）→ 生命 → 溢出 → 是否致死
```

状态施加不在伤害结算里。`apply_status` 是单独的动作，返回自己的结果，用 damage id 关联触发它的伤害。否则会形成循环依赖：伤害等状态结果，状态由 hit 规则施加，hit 又在队列里等伤害结束。

```java
record DamageResult(DamageId id, DamageContext ctx,
        Map<DamageStage, Double> stages,           // 各个命名阶段的数值
        List<ShieldLayerHit> shields, double health, double overflow,
        boolean immune, boolean lethal) {}

record StatusResult(Optional<DamageId> cause,      // 触发它的那次伤害
        boolean applied, int stacksBefore, int stacksAfter,
        Optional<BuffInstanceRef> instance) {}
```

- 伤害阶段要命名：`definition`（定义里的数值，没有任何修饰）、`attacker`（乘上攻击方快照后）、`target`（再乘上目标方修饰后，即准备扣到护盾和生命上的值）。精准倍率在哪个阶段乘入也要写明。
- 分享类效果必须显式写明读哪个阶段、是否去掉精准倍率。Deadfall 分享“Bodyshot (Base) Damage”的 50%（`Void!D47`），它对应哪个阶段、是否已含增伤，要实测核对。
- 施加状态时可以显式传入初始累计值：Jolt 把施加那一下的伤害计入阈值（`Arc!D8`），Volatile 不计入（`Void!D9`）。这样不需要让伤害的返回值等反应链结束。
- 切割：hit 规则先读 `lethal`，不致死才施加 Sever；`StatusResult.applied` 为真才消耗层数；免疫目标照样施加（`Weapon Perks!C198`）。
- 护盾是 buff 带的护盾层，不是给整个实体挂减伤：虚空 overshield 的 70% 减伤只作用于它自己的血池，可以和其他 overshield 分层共存，按 FIFO 消耗（`Void!D6`）。
- “下一次命中消耗”的 buff 要写明消耗时机（施放、命中确认、造成伤害、状态施加成功），以及同一批次（多弹丸、范围命中）是只消耗一次，还是每个实例各消耗一次。

### buff 实例

buff 仍是统一的效果载体，但实例不再只有层数和剩余时间。实例 = 持有者 × 定义 × 实例键，外加少量有明确语义的状态组件。组件是 Java 定义的种类、JSON 配实例，不把 DSL 做成通用编程语言。

| 组件 | 保存什么 | 例子（Compendium） |
| --- | --- | --- |
| 计时器 | 整体到期、逐层衰减、每层独立到期、可暂停 | Disruption Break 每层各自计时并相乘（`Weapon Perks!C71`）；Frame of Reference 收起时暂停（`C369`） |
| 累加器 | 伤害、命中进度、已返还能量 | Jolt 的累计伤害和触发冷却，属于目标身上的这个实例（`Arc!D8`） |
| 去重集合 | 命中过的目标、用过的武器或元素 | Bait and Switch 每把武器各一次（`C25`）；Elemental Honing 每种元素一层（`C80`） |
| 引用 | 标记目标、关联锚点、所属技能施放实例 | Deadfall 拴住指向锚点 |
| 历史值 | 原始持续时间、最长持续时间、最高等级 | Restoration 重新施加时恢复到曾达到的最长持续时间，并保留最高强度（`Solar!D7`） |

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
- 获得事件分三个量：请求数量、计入收益的数量、实际存储变化。Bolt Charge 在 x9 时获得 x4，只存到 x10，但能量按 x4 计算（`Arc!D5`）。
- 刷新单独发事件，满层刷新也能被监听：狡诈严冬达到或刷新 10 层时进入督军之怒就绪（`Exotic Armors!I92`）。

金标准案例已经依赖、要随对应案例一起实现的入口：武器拿出与收起（Frame of Reference）、长按输入（Getaway Artist）、buff 刷新（狡诈严冬）。其余以后按需添加，属于加种类：开火、未命中、弹匣耗尽、弹药变化原因；治疗量与过量治疗；冠军眩晕；构造物摧毁；技能开始、提交、结束、取消。

### 动作执行

规则的动作列表按顺序执行。动作可以把结果绑定到名字上，后面的动作用数值表达式 `chorus:result` 读取。这相当于 do-notation 里的 `x <- action`，但不允许写任意函数。造成世界伤害的动作返回 `DamageResult` 之后，序列才继续。

```json
"do": [
  { "type": "chorus:consume_buff", "buff": "chorus:example", "all": true, "as": "consumed" },
  { "type": "chorus:add_resource", "resource": "grenade",
    "amount": { "type": "chorus:mul",
                "a": { "type": "chorus:result", "ref": "consumed.stacks" }, "b": 0.05 } }
]
```

纯核心不一次算完整条动作序列，而是按执行帧一步一步推进：

```java
record Frame(Rule rule, int pc, Map<String, ActionResult> bindings) {}

sealed interface Outcome permits Advanced, NeedsWorld, Finished {}
record Advanced(RuleState next, Frame frame, List<Event> emitted) implements Outcome {}  // 内部操作：consume_buff、grant_buff……
record NeedsWorld(WorldCommand command, OpId op, Frame frame) implements Outcome {}      // 世界操作：伤害、生成物体、冲量
record Finished(RuleState next, List<Event> emitted) implements Outcome {}

Outcome step(RuleState state, Frame frame, RuleContext ctx);   // 纯函数
```

- 内部状态操作在纯核心里直接推进。世界操作交给执行器，执行器返回有类型的结果（`DamageResult`、`StatusResult`……），按名字绑定进 Frame 后继续。`chorus:result` 引用由 `step` 在推进到那个动作时从 bindings 里解析。
- Frame 是数据，不是闭包：相当于把 continuation 去函数化（defunctionalize），能打印、能比较、能在测试里重放。
- 世界操作只执行一次：每个操作有 OpId（根事件 + 规则 + pc）。不做自动重试；某条规则中途失败，就记录并停止它剩下的动作，已经提交的伤害不回滚、不重复。

### 时间语义

- buff 是否生效一律按逻辑时间判断（`expiresAt > now`），不依赖 tick 末尾的清理。
- 会影响玩法的到期转换，要在后续相关操作之前完成。例：Lucky Pants 的 Illegally Modded Holster 到期时，若层数 ≥ 7 就挂上 10 秒 Out of Luck，阻止重新激活（`Exotic Armors!C44`）。如果 Out of Luck 到 tick 末尾才挂上，中间就有重新激活的空档。
- 做法是“追赶”：读取或修改某个持有者的 buff 状态之前，先把它已到期的转换补跑到 now（在结算边界内）；引擎 tick 末尾再对没被访问的持有者统一追赶一次。通知和 UI 同步可以延后。
- 条件读两类数据：事件事实（伤害值、是否致死、命中了谁）来自事件本身；持有者状态在处理时读取。因为反应在结算边界内跑完，两者之间不会隔着其他命令。

### 数值管线

- 写法：修饰值一律写成原文里的增量，+25% 写 0.25；减伤用 `resist`，15% 写 0.15。
- 阶段：基础值 → 固定加值（`add`）→ 基础比例（`base_percent`）→ 外部倍率（`multiply`，按分组树合并后以 1 + x 相乘）→ 减伤（`resist`，以 1 − r 相乘）→ 上下限 → 属性曲线 → 实际效果。每个属性声明自己经过哪些阶段。
- 上下限的位置要单独定：Slow 对武器属性的 −75% 在词条之后、封顶之前，160 换弹变 40，而不是先封顶到 100 再得 25（`Stasis!D7`）。
- 同一种抵抗模组按份数查表（15% / 25% / 30%），不同来源再相乘（`Armor Mods!J7`）。
- 数值表达式里 Rank（小怪、精英、Boss……）和 Tier（1–6）分开取值；按目标类别取值分玩家、战斗人员、构造物三类，`pvp` 只是其中“玩家”的简写。

分组的合并器（输入都是增量或减伤比例）：

| 合并器 | 两个值怎么合并 | 输出怎么用 | 单位元 | 例子 |
| --- | --- | --- | --- | --- |
| sum | a + b | 倍率 1 + x | 0 | 近战增伤相加 |
| max | max(a, b) | 倍率 1 + x | 无，见下 | 强化 buff、目标 debuff 取最高 |
| product | (1 + a)(1 + b) − 1 | 倍率 1 + x | 0 | 武器词条各自相乘：+10% 和 +25% 得 0.375，不是 0.025 |
| resist | 1 − (1 − a)(1 − b) | 倍率 1 − r | 0 | 保护琢面 15% 与超凡 20% 得 0.32 |

max 没有安全的单位元：组里全是负增量（比如 −40%）时，用 0 当单位元会错算成 0。所以分组一律按“非空时合并、空组贡献倍率 1”处理。用 Haskell 的话说，每个分组是一个 semigroup，提升成 `Maybe` 之后才是 monoid。

## 走查：棱镜术士 build

用一套常见的棱镜术士 build 走一遍运行时：模型够用，但暴露出 10 处要改的地方，已改进各节（见本节末尾的表）。名称核对自 [Bungie manifest](https://www.bungie.net/Platform/Destiny2/Manifest/)（2026-06-29 版），数值取自 Compendium（2026-10-05 副本）。

### 部件

| 部件 | 类型 | 关键规则 |
| --- | --- | --- |
| 闪电激涌 | 星相 | 滑铲中按近战：前移 10 米，在出口前 7 米落 5 道闪电（各 761 伤害，施加 Jolt）；获得增幅和电光充能 x1 |
| 虚空供养 | 星相 | 技能击杀、冰冻碎裂击杀等 → 强化吞食（+140 HP，10 秒，击杀刷新） |
| 保护琢面 | 碎片 | 15 米内 ≥3 个敌人 → 减伤 15% |
| 使命琢面 | 碎片 | 拾取能量球 → 按已装备超能的元素给增益；静止 → 冰霜护甲 x2 |
| 平衡琢面 | 碎片 | 3 秒内连续 3 次光属性击杀 → 近战能量 +10%；暗属性 → 手雷能量 +10% |
| 勇气琢面 | 碎片 | 对带静止/缠绕减益的目标：光能技能 +10%，光能强化近战 +50% |
| 牺牲琢面 | 碎片 | 身上有电弧/烈日/虚空增益时技能击杀 → 暗超凡能量 +1% |
| 凤凰俯冲 | 职业技能 | 俯冲，9 米内友军 Cure x2 |
| 神秘织针 | 近战（缠绕） | 3 格充能，追踪，505 伤害并施加 Unravel |
| 急冻手雷 | 手雷 | 落地后放出追踪弹，冻住目标就再放一个，最多连锁 2 次 |
| 严冬之怒 | 超能 | 漫游超能，90% 减伤；轻攻击冰冻，重攻击冲击波击碎 |
| 狡诈严冬（Winter's Guile） | 金装臂铠 | 近战击杀叠督军印记（4.75 秒，最多 10 层，+300% 近战）；满层返还一格近战并进入督军之怒就绪（8 秒），下一次近战触发督军之怒（+400%，10 秒） |
| 庆典飞行 | 区域拒止榴弹（缠绕，特殊弹药） | 每发留下持续伤害的毒池。切割：施放职业技能后，5 次命中给目标挂 Sever（输出 -40%，10 秒），收起时也能触发。羸弱能量球：5 秒窗口内 26 次伤害后生成能量球 |
| 喷子 + 雪上加霜（One-Two Punch） | 武器词条 | 一枪 12 颗弹丸全中 → 下一次近战 +150%（3 秒，收起即移除） |
| 超凡 | 棱镜机制 | 光暗两条都满后激活 20 秒：手雷换成急冻奇点，武器伤害 +5%，减伤 20% |

### 级联

缩进表示上一层动作产生的事件。

```
[包] 职业技能 → 凤凰俯冲：服务端冲量 + 宽限期；9 米内 Cure x2
  → ability_used{class}
      切割：挂 slice_ready（5 次，8 秒，收起时保留）       ← 手里可能拿的是喷子
[包] 切庆典飞行点射 → 射弹落地 → 生成毒池（lifetime + 周期伤害）

[世界物体] 毒池跳伤害（查询结算）
  → hit{owner=玩家, via=庆典飞行, object=毒池, tags=[weapon,dot,strand], dealt, effective}
      超凡充能：缠绕=暗 → 暗条 += effective × 系数
      切割：本次不致死且目标无 Sever → apply_status Sever（-40%，10 秒）；施加成功才 slice_ready -1
        → status_applied{Sever} → 超凡充能：暗条 +
      羸弱能量球：隐藏计数 +1 …… 第 26 次后下一次伤害 → spawn 能量球（0.5 秒冷却）

[世界物体] 能量球飞向玩家
  → pickup{orb_of_power}
      超能 +0.87%
      使命琢面：by_loadout(超能元素)=静止 → 冰霜护甲 x2（每层 -7% 受伤，每 5 秒掉一层）

[包] 滑铲中按近战 → 解析：while 滑铲中，近战 = 闪电激涌；消耗一格近战充能
  1. 挂增幅（电弧增益）  2. 电光充能 x1  3. 前移 10 米 + 宽限期  4. spawn 5 道闪电
  闪电命中（查询）：761 × 勇气琢面（目标有 Sever = 缠绕减益 → 光能技能 +10%）× …
    → hit → Jolt；光条 +
    → kill
        虚空供养 → 强化吞食（虚空增益）
        平衡琢面 → 光属性击杀计数 +1
        牺牲琢面 → 有增幅（第 1 步挂的）→ 暗条 +1%
        督军印记 → 闪电带 melee 标签时 +1 层

[包] 喷子一枪 12 颗全中
  → shot_resolved{pellets_hit=12} → 雪上加霜：挂 one_two_punch（3 秒，下一次近战命中消耗）
[包] 近战（不在滑铲）→ 神秘织针 → 命中（查询）：
     505 × 近战组（督军印记或督军之怒，加雪上加霜 150%；满层流程见部件表）× …
  → hit → Unravel；消耗 one_two_punch
```

- 闪电激涌先挂增幅再落雷，所以落雷击杀时牺牲琢面成立；顺序反过来就不触发。之后吞食本身也是虚空增益，10 秒内牺牲琢面持续成立。
- 切割挂的 Sever（缠绕减益）让闪电激涌（光能技能）吃到勇气琢面。两条规则互不提及，通过 buff 状态间接耦合。
- 神秘织针是缠绕（暗），吃不到勇气琢面。"算不算光能技能、算不算近战"都是技能定义里的 tags。

### 超凡

光、暗两条是资源，不是 buff。充能规则写在棱镜子职业的效果包里，是全游戏最热的一条 `on hit`：

- 电弧/烈日/虚空 → 光，静止/缠绕 → 暗，动能两条各 50%；一条满后，动能给另一条再降 40%。
- 只计 effective，溢出伤害不算。
- 施加子职业增益或减益也会给对应的条充能。

两条都满后激活，挂一个带效果包的 buff，和漫游超能同一机制：

```
transcendence（20 秒）
  on_gain: 手雷、近战各 +2 格充能
  bundle:
    override: 手雷 → 急冻奇点
    modifiers: 武器伤害 +5%（other 组）；手雷/近战回复 +10%；受到伤害 -20%（resist 组）
    rules:
      on hit{grenade} → 挂 melee_surge（2 秒，近战能量每秒 +35%）
      on hit{melee}   → 挂 grenade_surge（2 秒，棱镜手雷能量每秒 +35%）
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
| 冰霜护甲 x5 | 你 | ×0.6875 |
| 保护琢面（被包围） | 你 | ×0.85 |
| 超凡 | 你 | ×0.8 |
| 增幅（对战斗人员） | 你 | ×0.85 |
| 合计 |  | ≈ ×0.24，受到约 24 点 |

Compendium 注明保护琢面 15% 与超凡 20% 合计 32%（1 − 0.85 × 0.8），证实 resist 组相乘。冰霜护甲和增幅是否同样相乘没有写明，这里按 resist 组假设。

### 走查发现的修改

| # | 发现 | 依据 | 改在 |
| --- | --- | --- | --- |
| 1 | 武器效果包在装备在武器槽时生效，事件规则按伤害来源匹配，"在手中"只是条件 | 切割收起时可触发；羸弱能量球计数收起保留；毒池切枪后仍在伤害 | 核心模型：效果包；开放问题 |
| 2 | 事件带来源链 owner / via / object / tags，hit 带 dealt 和 effective | 毒池、丝线、闪电都是世界物体造成的伤害；超凡不计溢出 | 规则 DSL：事件 |
| 3 | 修饰分两种求值：持有者状态可缓存，命中上下文每次现算 | 保护琢面 vs 勇气琢面 | 运行时：控制流 |
| 4 | 叠加分组是树，每个节点一个 monoid | 雪上加霜与对冰冻目标的近战增伤互斥取高 | 属性与数值管线 |
| 5 | 连续量用资源，不用 buff | 超凡条、技能能量、超能能量 | 目标与范围；Buff；数据与状态放在哪 |
| 6 | 聚合事件：一枪、同一伤害批次 | 雪上加霜看一枪的弹丸数；电光充能每个同时伤害实例只给 1 层 | 规则 DSL：事件 |
| 7 | 事件队列：重入保护 + 每 tick 预算；动作严格有序 | Unravel、Jolt、连环碎冰；闪电激涌先挂增幅再落雷 | 运行时：控制流；规则 DSL：动作 |
| 8 | 编译只针对静态层，带包 buff 在分发时拼进来；技能替换有固定优先级 | 吞食、增幅几秒一得一失；滑铲、超凡、超能都会替换技能 | 核心模型：效果包；技能 |
| 9 | 数值表达式新增 by\_loadout、resource、diminishing | 使命琢面；超凡充能；超凡击杀延长递减 | 规则 DSL：数值表达式 |
| 10 | 两个加载器的引擎 tick 统一挂在整服 tick 末尾 | hello world 第 5 步里 NeoForge 用了 PlayerTickEvent.Post | 多加载器接线清单 |

## 多加载器接线清单

common 只依赖原版，下面每一项在 fabric 和 neoforge 各写一层薄接线。日常只跑 `:fabric:runClient`，NeoForge 按里程碑批量补齐。其中只有"注册"必须一开始就做对。

| 项目 | Fabric | NeoForge | 备注 |
| --- | --- | --- | --- |
| 静态注册 | 初始化时直接注册进原版注册表 | `DeferredRegister` | **先做**：common 只声明"要注册什么"，加载器在允许的时机注册 |
| 同步的数据包注册表 | `DynamicRegistries.registerSynced` | `NewDatapackRegistryEvent` |  |
| 实体附加数据 | `AttachmentRegistry` | `AttachmentType` |  |
| 网络包 | `PayloadTypeRegistry` + `ServerPlayNetworking` | `RegisterPayloadHandlersEvent` | payload 和 `StreamCodec` 定义在 common |
| 游戏事件来源（击杀、受伤、右键、tick） | Fabric API 回调 | NeoForge 事件 | common 里是普通函数，接线只负责转发。引擎 tick 两边都挂在整服 tick 末尾：Fabric END\_SERVER\_TICK，NeoForge ServerTickEvent.Post（不用 PlayerTickEvent，时机不同） |
| 快捷键 | `KeyMappingHelper` | `RegisterKeyMappingsEvent` |  |
| 配置文件 | 无内置 | `ModConfigSpec` | 见「开放问题」 |
| 客户端渲染注册（实体渲染器、HUD） | Fabric API | NeoForge 事件 | 前期尽量用原版 Display 实体避开 |
| Mixin | 写在 common | 写在 common | 两边都自带 Mixin 和 MixinExtras；只在没有事件时用 |

实现这些接线时要对照本地源码核对 API 名字。AI 容易写出旧名字，比如 Fabric 26.1 把 `playC2S` 改成了 `serverboundPlay`。

## 金标准测试集

写解释器之前，先把下面 35 条用 DSL 写出来。全部能编码，并通过带时间线的预期结果测试，才说明这些案例被覆盖；Target Lock（按弹匣百分比逐步增伤）、Ager's Scepter（切换射击模式）这类预期交给 Java 自定义类型。Kill Clip 已在「Buff」一节写好，Rampage 见表下。

| 条目 | 类别 | 检验什么 | 状态 |
| --- | --- | --- | --- |
| Rampage | 武器词条 | 层数、刷新、一层层衰减、收起后保留 | 草稿 |
| Outlaw | 武器词条 | 精准击杀、武器属性修饰、切枪移除 | 未写 |
| Kill Clip | 武器词条 | 用隐藏 buff 实现时间窗口 | 草稿 |
| Voltshot | 武器词条 | 下一次命中消耗 + 施加元素状态 | 未写 |
| Kinetic Tremors | 武器词条 | 按目标计数、延迟的多次范围伤害、按目标冷却 | 未写 |
| Incandescent | 武器词条 | 范围施加状态层数、按敌人等级取值 | 未写 |
| Frenzy | 武器词条 | 持续交战计时（`buff_expired` 事件） | 未写 |
| Feeding Frenzy | 武器词条 | 每层数值不同（分档查表） | 未写 |
| Desperate Measures | 武器词条 | 同一 buff 多个等级、各等级触发不同 | 未写 |
| 急切刀锋 | 武器词条 | 冲量（服务端发起 + 宽限期） | 未写 |
| Jolt | 元素状态 | 状态自带规则（伤害阈值、连锁、冷却） | 未写 |
| Bolt Charge | 元素状态 | 按弹匣比例计数（可能需要 Java） | 未写 |
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
| 棱镜术士 build | 综合 | 跨来源级联、动作顺序、查询期条件、资源、替换优先级、resist 相乘（见「走查：棱镜术士 build」） | 未写 |
| 超新星炸弹快照 | 运行时 | 放出后切装不影响伤害；目标方修饰命中时取值；派生物继承快照；on\_hit 例外 | 未写 |
| Disruption Break 两层错开施加 | 反例：计时器 | 第一层到期不会带走或刷新第二层 | 未写 |
| Restoration 延长、倒计时、重新施加 | 反例：历史值 | 重新施加时恢复到历史最长持续时间，保留最高强度 | 未写 |
| Frame of Reference 收枪再拿出 | 反例：计时器 | 倒计时暂停并恢复 | 未写 |
| 同一次命中施加 Jolt 和 Volatile | 反例：结算阶段 | Jolt 把施加的那一下计入阈值，Volatile 不计入 | 未写 |
| Slice 命中致死目标、免疫目标 | 反例：结算阶段 | 致死时不施加、不消耗层数；免疫目标照样施加 | 未写 |
| A 枪击杀后 B 枪换弹 | 反例：实例绑定 | 不串用 Kill Clip 窗口 | 未写 |
| Threadling 停栖再释放 | 反例：继承 | 停栖后失去来源继承 | 未写 |
| 多人、多锚点 tether 连锁 | 反例：队列 | 不跨来源分享；分享出去的伤害不是基础伤害，所以不会再被分享 | 未写 |
| 超过队列预算、buff 同 tick 到期 | 反例：队列 | 延后执行不改变已确定的战斗事实 | 未写 |
| 无限点燃、Riskrunner 自触发 | 正向：循环 | 合法循环按冷却和敌人数量自然收敛，不被引擎截断 | 未写 |

### 例子：Rampage

表里的定义是：武器击杀后增伤 10% / 21% / 33.1%（1～3 层），持续 4.5 秒（强化 5 秒），再次击杀刷新，层数逐层衰减，收起武器后保留。

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

- [ ] 已确认：武器词条的增伤彼此相乘（见乘区表），这决定了 `weapon_perk` 分组怎么合并。
- [ ] chorus 的伤害要不要经过原版护甲和保护附魔？玩家只有 20 点血，命运的数值怎么换算？
- [ ] 是否全局取消摔落伤害。
- [ ] 配置文件自己用 Codec + JSON 写，还是选一个两边都能用的第三方库。
- [ ] 射击的命中判定：纯服务端，还是客户端判定 + 服务端校验。
- [ ] 遭遇战脚本：只用同一套规则加状态机，还是另外开放 mcfunction 作为逃生口。
- [ ] 公开发布前，把命运 2 的词条名、金装名换成自己的名字（Bungie 知识产权）。
- [ ] 武器槽：Minecraft 没有"装备着的三把武器"。用快捷栏的固定格子，还是自定义装备栏？武器效果包按它判断是否生效。
- [ ] 来源武器自己的词条是否跟着来源走：庆典飞行开火后被卸下，它的毒池伤害还算不算羸弱能量球的计数？倾向算：通过快照里的 via 找到武器实例，直接读它的词条，不经过当前装配。
- [ ] 只有方向、尚未验证：资源交易与多充能策略（Ophidia Spathe 同时恢复多格近战）、技能执行实例（漫游超能的实际持续时间）、世界物体之间的关系（Anarchy 连线、缠结拿取投掷、Briarbinds 回收重部署）。

### 下一步

1. 注册抽象：common 声明，加载器在允许的时机注册。
2. rule 层骨架：类型注册表、Codec、纯函数 `step`、核心语义 v0.2 的 record（DamageResult、buff 实例组件、生命周期事件）、事件队列（根事件、两级预算、动作结果绑定），配 JUnit 测试。
3. stat 和 effect 层，加一个调试命令（给自己加 buff、查看属性）。
4. 用 Rampage 加 3–4 个反例跑通全链路：JSON 定义 → 加载 → 触发 → 更新状态 → 数值修饰 → 伤害确实变了。
5. 一把最简单的枪：射线判定命中，原版风格模型。
6. 逐条写金标准测试集，更新上表的状态列。
