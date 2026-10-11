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

## Rampage

[rampage.json](../common/src/test/resources/effects/rampage.json) 按固定快照 Weapon Perks C172 实现普通 / 强化 Rampage：同持有者、同武器且具有 weapon_kill 信用的确认击杀加一层，上限三层，提供 10 / 21 / 33.1% 伤害。满层仍重置共享计时器；收枪保留并继续计时。2026-10-10 重新抓取的原表 A173 / C173 与 CSV A172 / C172 规范化文本一致，原始坐标不混用。

[官方 9.5.0 更新](https://www.bungie.net/7/en/News/Article/destiny_update_9_5_0) 确认到期只丢一层；[开发预告](https://www.bungie.net/7/en/News/Article/dev_insight_renegades_weapons) 说明此计时改动也适用于 Huckleberry。原表未单列后续间隔的测量值。当前实现明确推断每次掉层后重开对应的 4.5 / 5 秒计时，而不使用早期通用单测的 1 秒示例：从三层最后刷新起，普通在 4.5 / 9 / 13.5 秒、强化在 5 / 10 / 15 秒依次掉层。仍需原作独立校准，不能把通过时间线测试当作测得原作周期；也不把这一预告当成 Huckleberry 等异域伤害表的依据。

两种计时定义共用一个增伤 bundle。装备词条替换 / 卸下清除对应武器的两种旧状态，普通与强化不会并存相乘；其他武器不受影响。没有武器信用、取消 / 免疫 / 未致死伤害不授予。当前采用 on_use 冻结层数、排除 explosive_perk_damage、已装备但收枪后的飞行击杀仍可授予、卸下后旧击杀不再触发的内容政策。已发射伤害保留旧快照，不因卸下回滚；这些边界与截止点先过期再处理击杀的策略需继续校准。

7 项 RampageTest 验证两种模式 / 变体的档位与封顶刷新、精确微秒掉层、边界击杀、实例隔离、换 perk 清理、发射快照与编解码。4 项共享 RampageGameTest 用实际玩家容器和投射物验证逐次击杀、下一发实际伤害、真实换弹 / 储备、收枪计时与全部衰减周期，另确认 Buff 先过期、子弹后命中的时序；缺少信用、飞行中移除词条及未知致死回执不会伪造或重放授予。

[rampage_weapon.json](../common/src/test/resources/effects/rampage_weapon.json) 的 10 点基础伤害、5 发弹匣、0.15 秒射击间隔、0.2 秒换弹和 20 m/s 飞行速度都是合成验收参数。普通玩家自动装配、完整武器 / 异域特例、存档与 HUD 仍未完成，覆盖保持 partial。原表图标已原样导入，尚未绑定界面；来源与政策集中于 [研究记录](../data/d2-research/2026-10-10/rampage.json)。

## Frenzy

[frenzy.json](../common/src/test/resources/effects/frenzy.json) 按固定 CSV `Weapon Perks!C101` 与 2026-10-10 抓取的[原表 C102](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit?gid=1662574278&range=C102) 实现持续交战：普通 / 强化每 5 / 5.5 秒内造成或受到有效伤害，连续保持 12 秒后获得 15% 武器增伤、100 操控和 100 装填属性。两项属性经过共用 Profile 的 0–100 限幅，装填再进入武器自己的秒数曲线；操控尚无拿枪 / 开镜动画消费者。

三个按武器实例隔离的 Buff 分别表达接触窗口、不可刷新的 12 秒预热和激活状态。每次合格伤害只刷新接触窗口，断档会取消预热；预热到期且接触窗口仍存活时自动激活，不要求第 12 秒另有命中。接触与预热同时到期时不激活。激活后独立使用 7 秒计时并由合格伤害刷新，不再受 5 / 5.5 秒预热窗口限制；收枪继续计时，卸下 / 替换词条清除三个状态，重新装备重新累计。

[官方 9.7.0 更新](https://www.bungie.net/7/en-us/News/Article/destiny_update_9_7_0) 确认修复强化版刷新时没有延长持续时间的问题，但未给出精确秒数。因此强化刷新要求外部提供 `chorus_d2:frenzy_enhanced_refresh_duration` Profile，输入基础 7 second、输出 second，没有数值默认值。[测试校准文件](../common/src/test/resources/effects/frenzy_test_calibration.json) 的 7.8 秒是纯合成值。普通刷新用 7 秒；首次激活的 7 秒从激活时刻开始计，是当前对原表的解释，仍待原作时序校准。

战斗资格从单一 `damage_taken` 事实判断持有者是攻击者或受害者，不要求这把枪造成伤害；HP、原版 Absorption 或 Chorus 护盾的正损失均可计入。身份缺失、自伤、显式 environmental_damage 标签及零损失不计入。真实无攻击者的摔落伤害已验证，但带归属的环境伤害、友军及特殊伤害分类仍需校准。增伤使用同实例 weapon_damage 信用、on_use 快照，不沿用 Kill Clip 的特殊爆炸排除；具体武器衍生伤害资格仍由内容声明。

8 项 FrenzyTest 和 4 项共享 FrenzyGameTest 验证精确时间边界、普通 / 强化断档差异、输入 / 承伤、无击杀激活、收枪、物理卸装重装、真实子弹 10→11.5 伤害及换弹接受后 Buff 到期仍按已捕获秒数完成。[武器夹具](../common/src/test/resources/effects/frenzy_weapon.json) 的 5 发容量、4 发初始弹量和 `2 − 0.01 × 属性` 换弹曲线均为合成参数。自动内容装配、完整武器曲线、HUD 和活动存档尚未完成，覆盖保持 partial；来源、已知数值与假设见 [研究记录](../data/d2-research/2026-10-10/frenzy.json)。

## Firesprite 与 Ember of Tempering

2026-10-10 核对原表 Solar：Firesprite 为 B7 / D7，Tempering 为 B28 / D28 / N28；对应固定 CSV 快照 B5 / D5 与 B25 / D25 / N25，不能混用坐标。两者原表正文与快照归一化后一致。[原表](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit#gid=1186062409)及 [HTML 哈希与政策记录](../data/d2-research/2026-10-10/firesprite-tempering.json)保留了可核查依据。

| 内容 | 表中参数 | 已实现的表达 |
|---|---|---|
| Firesprite | 私有手雷回能拾取物，25 秒寿命，5 秒生成间隔 | pickup + 确认生成回执 + 持有者共享冷却 + 收集时 grant_ability_energy |
| Tempering 触发 | 太阳武器击杀，自身及 15 米队友，8 秒最多 3 层 | 武器击杀标签 / 事件来源元素 + allied 查询 + 共享 Buff |
| Tempering 属性 | Health +20/40/60，AE +20，碎片 Class -10 | 当前持有者查询期修饰；Health / Class 限幅 0–200，AE 限幅 0–100 |
| Tempering 拾取物生成 | Buff 活跃期间的太阳武器击杀 | 已有 Buff 的规则发出 spawn_firesprite 请求，交统一生成系统处理 |

第一次击杀先获得 Buff，后续击杀才看到已绑定的 active 规则；这与 [Bungie 的历史机制说明](https://www.bungie.net/7/en/News/Article/ability-changes-lightfall-d2)相符，但首次激活和队友交互仍需当前游戏验证。当前共享政策允许收到 Tempering 的队友用自己的太阳武器击杀生成自己的私有 Firesprite，采用各自冷却，不借用原施加者额度。各生产者给同一持有者加层 / 刷新同一个 8 秒 Buff；卸下碎片后既有 Buff 自然到期，碎片的 Class 罚值立即消失。以上跨来源及卸装政策不是已完成的原作校准。

Firesprite 的 11.25% 没有标明参考属性。当前内容因此要求两个外部定义：`firesprite_collection_radius`（meter → meter）及 `firesprite_base_energy`（charge_fraction → charge_fraction）。后者把原表的 .1125 转成当前属性零点、CES 之前的基础量，之后才让接收方 gain Profile 施加属性和 CES。缺失任一 Profile 会在编译 / 链接时失败；不会把未说明基准的百分比再直接乘一次属性，也不套用其他 perk 的旧版本换算系数。

`firesprite_test_calibration.json` 使用 0.5 米和 .1125 / 2.25 = .05。该换算只是用于贯通执行流程的参考点假设；[当前能力属性研究](https://www.reddit.com/r/DestinyTheGame/comments/1u6czmi/the_final_armor_ability_stats_update_monument_of/)支持通用回能曲线，不足以证明 Firesprite 的参考基准。测试在 Arcbolt .75 CES 和 100 Grenade 属性 2.25 倍下得到 .084375，这证明一次归一化、一次接收方修饰的执行次序，不宣称每个 Firesprite 当前实测回复 8.4375%。

每名角色需要恰好一个 firesprite_system 来源。生成系统以 event_position_observed + read_event_position 读取触发伤害回执中的 victim / feet，死亡反应移动或删除尸体后仍在确认时的位置生成，不实时重查尸体。没有该锚点观察时发 firesprite_position_unobserved，既不生成也不挂冷却；已记录不可用、维度不符或区块未加载则由正常生成回执拒绝。有效坐标不授权加载区块或跨维度生成。确认生成才挂冷却，收集不刷新冷却。回执后脚底取样是当前内容政策，原作具体生成点 / 取样时机仍须校准。拾取物 source 为生成系统，原始击杀仍在 continuation cause 中，尚未宣称 pickup 事实继承武器归因。收集读取当前基础手雷选择；无选择、无成本账户或满能量仍消费物品并发事实。既有物体的动作体可在来源卸下后执行；死亡、旁观者或他人不能领取该私有单位。当前私有性是收集资格，所有追踪客户端仍能看到占位实体。

这两项状态为 partial：定义仍是可组合的验收数据，尚未接入完整生产子职业装配。实际回能基准、接触半径、特殊击杀资格、其他 Firesprite 生成来源、Mercy 复活分支、Health / Class / AE 的实际玩法投影、HUD、真实素材与存档尚未完成。

## Ember of Searing 与回执时目标状态

原表 Solar B25 / D25 / N25（固定 CSV B22 / D22 / N22）给出击杀灼烧目标后的近战收益与 Firesprite，并给碎片 +10 Class。保存的 HTML、对应单元格及政策在 [Searing 来源记录](../data/d2-research/2026-10-10/ember-of-searing.json)。[Bungie 历史说明](https://www.bungie.net/7/en/News/Article/ability-changes-lightfall-d2)可确认生成 Firesprite 的机制，不作为当前百分比的测量依据。

| 原表目标分类 | 表示的近战收益 |
|---|---|
| T1 / T2 / T3 / T4 Combatant | 8% / 15% / 17.5% / 25% |
| Guardian | 20% |

这些值未注明当前属性基准。内容必须链接 `chorus_d2:searing_base_energy`（charge_fraction → charge_fraction），先把表值归一化为当前基础量，再交 `grant_ability_energy` 的当前基础近战选择、属性与 CES。测试暂以除以 2.25 作为参考点假设，使用初始为零、无被动恢复的合成近战账户和 Threaded Spike 的 .8 gain Profile 验证一次路由；不把测试输出当成当前 Searing 实测数值。

资格按已确认 kill 的 actor 持有碎片，且 victim 的回执时 BuffObservation 中存在 Scorch；不要求灼烧也由击杀者施加。death 的清理已经移除实时 Scorch 后仍能判断。未确认死亡、无灼烧或观察时已过期不产生收益；本次 hit 反应后来添加的状态不改写历史。助攻是否应另行获得收益、同击施加 / 点燃的例外仍待校准。[2026-04-10 的一手问题报告](https://www.bungie.net/en/Forums/Post/265221874?page=0&path=1&sort=0)提到同击施加并击杀未回能；官方只要求进一步证据，不能据此当成已经确认的机制或修复。

等级输入从伤害回执的 EntityObservation 读取实际 player，或非玩家的 entity / type 标签中恰好出现一种 `chorus_d2:combatant_tier_1` 至 `_4`。同一种标签同时存在于两处仍只算一种；不同 tier 冲突则未分类。不得把 elite / boss 等敌人 Rank 标签自动当作这里的 Tier，完整目录需要另外装配。未提供事件实体观察、观察时实体不可用、没有 tier 或 tier 冲突会发 `chorus_d2:searing_unclassified`，跳过无法计算的近战收益，但仍请求已知的 Firesprite 分支；这是显式不完整输入，不是零收益的游戏设定，也没有事后补领逻辑。

分类通过 `event_entity_observed` + `read_event_entity` 读取；death 反应删除尸体或把 T1 改成 T4 后，回能仍按回执中的 T1。没有事件观察时不会重新查询当前尸体来补造历史。Firesprite 也读取同一事件的 victim / feet 历史位置，尸体提前移动或移除后仍能在原死亡点生成，确认生成才进入共享冷却。元数据与位置观察独立：缺失分类可报告诊断但继续已知位置的生成分支；没有位置证据则明确诊断，不实时兜底，也不撤销已确认的近战回能。

近战回能与 Firesprite 的持有者级生成冷却独立。多个有效击杀在同一生成冷却中仍各获得近战能量；没有近战选择仍可生成 Firesprite。Class +10 只影响现有属性查询，卸下来源后消失。本项仍为 partial：当前回能基准 / CES 资格、完整等级目录、特殊死亡顺序、助攻、子职业生产装配、Class 实际玩法、HUD 与持久化均未完成。

## Incandescent 与共享 Solar 状态的接入依据

共享 Scorch / Ignition 与 [Incandescent](../common/src/test/resources/effects/incandescent.json) 已接通，并有实际玩家装备、物理子弹击杀到范围爆炸、Scorch 和后续点燃的验收；覆盖保持 partial。2026-10-10 抓取的[原表 Weapon Perks A125 / C125](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit#gid=1662574278&range=C125)与固定 CSV A124 / C124 一致：普通目标范围 4 米，精英以上或 Guardian 范围 8 米，爆炸基础伤害最多 30 Solar。Scorch 层数按原表逐项展开，Ashes 不套用通用 50%：

| 击杀目标 | 普通 | 普通 + Ashes | 强化 | 强化 + Ashes |
| --- | ---: | ---: | ---: | ---: |
| 普通目标 | 30 | 40 | 30 | 45 |
| 精英以上 / Guardian | 40 | 50 | 45 | 60 |

每把装备实例的 Bundle 只接受同持有者、同武器的 weapon_kill；切枪不移除仍装备的词条，卸掉词条后才到达的子弹击杀不触发。伤害回执中受击者的玩家标记，或 entity / type 的 `chorus_d2:elite / champion / miniboss / boss` 标签选择强分支；未标记的可观测非玩家按普通目标处理。分类和 feet 中心读取不可变事件观察；死亡反应删除尸体、移动它或修改标签不改变该次爆炸。缺失或不可用的分类 / 位置发 incandescent_unresolved 并跳过，不重查当前尸体来猜数据。Ashes、强化选择及攻击仍在击杀规则运行时取得，再由原分类选择半径和层数；当前声明立即爆炸，原作延迟和完整取样时机仍待校准。

范围查询排除施加者、死亡中心和盟友；其余目标逐一观测存活，计算距离倍率并结算独立伤害回执。实际正损失（含 Absorption）后才检查 Scorch 禁用窗口和世界状态资格；免疫、取消、零损失、死亡或资格拒绝不加层。爆炸在禁用窗口内仍能伤害目标，只禁止重新施加 Scorch。此处“正损失后上灼烧”是当前内容政策，原作对免疫 / 护盾等边界需进一步验证。

必须提供两个数值 Profile：`incandescent_falloff` 为 meter → multiplier，输入当前距离，并提供 radius 测量；`incandescent_outgoing` 为 damage → damage，必须有 payload / payload 的 ADD 阶段，接收 `incandescent_payload`，随后声明允许的武器增益、目标减益和世界单位映射。词条 Bundle 自带同武器的 payload 贡献，按爆炸创建时捕获攻击，逐目标传入 `30 × 距离倍率`。缺少校准定义会链接失败。[incandescent_test_calibration.json](../common/src/test/resources/effects/incandescent_test_calibration.json) 的 `1 − 0.1 × 米数` 与世界伤害乘 0.1 是合成值，不代表原作距离衰减。

装备词条来源须带 `chorus:weapon_damage` 和 `chorus_d2:scorch_rank_exempt` 标签，前者明确后续 Scorch / Ignition 的武器信用，后者保存 Incandescent 的等级倍率排除资格。来源身份继续指向该武器。**原表没有明确说明 Scorch 自身的非 Boss 20% 是否属于这里排除的等级倍率**，因此使用下表的 `scorch_nonboss_factor` 显式校准；测试中将豁免来源设为 1，也验证改成 1.2 后无需修改效果即可保留增幅。宿主还须在具体伤害 Profile 中排除不适用的武器敌人等级乘区；scorch_rank_exempt 只声明 Scorch 的资格，Ignition 的乘区资格须单独判断。不把这一推断当作已实测的完整 D2 算法。

`ashes_equipped` Profile 由 Solar 定义、Ashes Bundle 提供 1，MAX 合并重复装配；Incandescent 读取持有者的该结果后查上述专属层数表。12 项 IncandescentTest 验证全部八种组合、Guardian / 类型分类、武器隔离、收枪 / 卸装、两枪共同叠到点燃、来源快照、独立禁用、死亡 / 免疫 / 拒绝 / 未知回执和校准替换。6 项共享 IncandescentGameTest 通过实际容器和无权限开火命令产生物理击杀，验证真实 4 / 8 米、友军排除、叠层点燃、扣弹及卸装后的冻结 Scorch。新增尸体删除 / 反向改级 / 移动以及两个连续死亡的验收：每次爆炸击杀取得自己回执的中心，不沿用最初尸体，也不因同一条链而禁止再次触发。当前赋予爆炸 weapon_kill 信用，合格派生击杀可继续产生爆炸；原作全部派生来源的特殊触发排除仍需核对。

[incandescent_weapon.json](../common/src/test/resources/effects/incandescent_weapon.json) 的 10 点子弹、5 发弹匣、0.15 秒射击间隔和 50% 验收增益均为合成原型。完整武器目录、正式敌人标签映射、准确伤害 / 来源倍率、自动子职业装配、HUD 和状态存档尚未完成；不能把测试文件当成完整生产规则包。

Buff 的 [damage_snapshots 组件](engine-data-packs.md#在-buff-中保存伤害快照) 已能将完整攻击保存到后续独立事件，保留来源、信用和所捕获的数值贡献。共享 Solar 现在用它保存首次施加的攻击；后续施加保留该值。具体修饰取样时机仍须按来源逐项校准。

**Scorch 不能直接套用纯文本导出的统一衰减参数。** 原表 Landing F22 的红色 #ea9999 表示 PvP，M22 的蓝色 #a4c2f4 表示 PvE。Solar D11 的蓝色段落给出 `2.7 + 0.175 × 层数`、非 Boss 额外 20%，并说明持续时间和衰减率受难度影响；红色段落才给出 2.3 秒后每 0.04 秒减一层和另一组伤害样例。固定 CSV 对应 Solar D9，颜色已经丢失，不能把两个模式的数据拼成一条公式。非致命文字的目标范围、第二次 tick 的 0.93 秒延迟含义及具体难度表还需校准。

共享状态还要表达 100 层清除并触发 Ignition、1.6 秒禁止重新施加 Scorch、首次来源决定武器 / 技能信用，以及各来源独立的缩放资格。[官方系统说明](https://www.bungie.net/7/en/News/Article/twid_06_19_2025) 已明确近战增伤不再增加近战产生的 Scorch / Ignition；不能直接继承整条近战倍率。原表 Incandescent 的等级倍率排除也须与一般 Solar 缩放分开核对。允许的后续链式触发仍按正常事件队列执行。

原始 Solar / Landing HTML、颜色图例、各模式参数、未知值和下一步验收要求保存在 [研究记录](../data/d2-research/2026-10-10/incandescent-solar.json)。Incandescent 图标已按原表 B125 原样导入，尚未绑定 HUD。

### 共享 Scorch / Ignition 的当前实现

[solar.json](../common/src/test/resources/effects/solar.json) 仅组合现有 Buff、定时器、类型化快照、世界观测与范围动作，没有专用 Java 状态机。Scorch 按受击目标共享，上限 100 层；首次授予保存普通 / 非致命 Scorch 及 Ignition 三份攻击描述，后续授予保留来源及来源侧 on_use 贡献。伤害信用由最初来源的互斥 weapon / grenade / melee / super 标签声明；技能同时保留通用 ability 信用，武器身份本身不推导武器信用。

伤害 tick、叠层衰减和点燃禁用窗口各自计时。当前时序政策：首 tick 在 0.5 秒，第二 tick 在 1.43 秒，之后间隔 0.56 秒；重复施加刷新衰减计时，保留伤害 tick 时序；到衰减等待时间的边界即首次减一层。第二 tick 和首次衰减的边界解释仍须原作校准。每次 tick 显式观测目标，按当前层数及 Boss 标签计算 PvE 原始伤害，再使用首次保存的攻击；目标死亡 / 缺失或层数耗尽会移除状态并取消自有定时器。

达到 100 层后，保存当前位置和点燃攻击，先授予原目标 1.6 秒禁用状态并移除 Scorch，再按显式 `ignition_delay` 执行点燃。零表示立即爆炸，正值使用 detached 延迟动作；负值失败，不静默当作立即爆炸。爆炸以保存位置和阈值时取得的 ignition_radius 查询当前非盟友，基础半径 8 米，Eruption 为 10 米；每个目标独立观测、独立结算伤害及实际回执。PvE 使用 676 原始伤害且无距离衰减；PvP 对原版玩家采用 Guardian 分支，对显式 `chorus_d2:construct` 实体标签采用构造体分支，未分类非玩家不猜为构造体。阵营参照缺失时没有可授权目标。`chorus_d2:boss` 也是明确宿主标签，尚非完整 D2 敌人目录。

**待校准的时序政策：** 当前禁用窗口从达到阈值开始，爆炸中心固定于该时刻的位置；延迟值、原作禁用窗口的起算点、移动 / 死亡后爆炸位置和待爆期间再次施加的准确行为尚未确认。不得用合成延迟声称已经复现当前原作的无限点燃条件。

外部程序必须提供以下校准 Profile；缺少任一项会在链接时失败：

| Profile（chorus_d2 命名空间） | 输入 → 输出 | 契约 |
| --- | --- | --- |
| scorch_decay_delay | second → second | PvE 以 0 请求显式难度值，PvP 基值为 2.3；结果必须为正微秒可表示时长 |
| scorch_decay_interval | second → second | PvE 以 0 请求显式难度值，PvP 基值为 0.04；结果必须为正微秒可表示时长 |
| scorch_pvp_tick | count → damage | 当前层数 → 完整 PvP 原始 tick 伤害，已包含相应的 60 层增幅，内容不再重复乘入 |
| scorch_nonlethal | count → count | 输入为 PvP Guardian 1、其他 0；正结果选择非致命攻击，目标范围须显式校准 |
| scorch_nonboss_factor | count → multiplier | 输入 0 为普通来源、1 为 `scorch_rank_exempt` 来源；返回非 Boss 目标倍率，Boss 使用 1。一般来源表列 1.2，Incandescent 豁免与该 20% 的关系需要显式校准 |
| ignition_falloff | meter → multiplier | 当前被选目标的已测距离 → PvP 距离倍率；另提供 guardian 测量，PvE 不使用该倍率 |
| ignition_delay | second → second | 以 0 请求校准的阈值至爆炸时长；结果为 0 或正微秒可表示时长，不提供隐式默认值 |
| ignition_radius | meter → meter | Solar 自带，基础 8 米；原始 Scorch 来源持有者在达到阈值时取样，Eruption 的 MAX 组增加 25% 至 10 米；结果随待执行爆炸保留 |
| solar_outgoing | damage → damage | 必须包含 payload / payload 的 ADD 阶段，接收 `solar_payload` 命中测量；之后声明来源允许的缩放与世界伤害单位换算 |

宿主须在攻击捕获前为每位施加者绑定 `chorus_d2:solar_scaling`，它把每次 tick / 点燃的原始伤害测量放入 payload 阶段；建议该组使用 MAX，避免重复装配叠加原始伤害。该贡献也随攻击冻结，因此卸下后仍可计算。Solar 使用独立 Profile，不自动套用近战 Profile；武器属性、武器 New Gear Bonus 的 5%、战斗单位等级倍率、Radiant、Surge、Verity 等具体资格及特殊来源仍需装配，当前没有声明这些 perk 全部生效。

**施加入口契约：** 所有生产者在 apply_status 前检查目标没有 `chorus_d2:scorch_lockout`。共享 Buff 生命周期不提供隐式的状态授予否决；直接 grant_buff 绕过该契约属于错误装配。[solar_test_source.json](../common/src/test/resources/effects/solar_test_source.json) 只是合成输入及反应探针，未连接生产武器 / 技能。Incandescent 已从真实武器击杀入口遵守同一契约，见上节。

[solar_test_calibration.json](../common/src/test/resources/effects/solar_test_calibration.json) 中 PvE 等待 3 秒 / 衰减 0.1 秒、PvP tick `1 + 0.1 × 层数`、点燃距离倍率 `1 − 0.1 × 米数`、点燃延迟 0 秒和世界伤害乘 0.1 都是**合成验收参数**。Char 测试另外将延迟显式替换为 1 / 2 秒，分别验证持续反馈和中心排除；这些也不是原作实测值。非致命示例只对 PvP Guardian 启用，用于验证健康下限机制，不替代原作范围证据。正式难度表、完整 PvP 曲线及来源修饰取样未校准前，不把这些文件作为完整游戏数值发布。

12 项 SolarTest 覆盖来源与信用、当前层数 / 分类、独立计时、刷新 / 衰减、同帧多次授予、100 层点燃、禁用到期、源增益到期后的冻结值、缺失 / 死亡、校准依赖与未知结果。5 项共享 SolarGameTest 覆盖真实 tick、原版伤害 / 击杀归属、实际玩家非致命伤害后被点燃击杀、允许的连续点燃，以及已扣血后的未知回执不重放。连续点燃测试用合成反应在点燃命中后再施加 100 层，验证事件队列不全局禁用链式效果；这不是 Ember of Char 的数值实现。

仍未实现：Unstoppable 眩晕、非叠层直接点燃来源、全部片段与等级 / 来源特例、完整生产装配及效果状态持久化。Scorch 和 Ignition 均保持 partial。

### Char / Ashes 与持续互相点燃

[ember_of_char.json](../common/src/test/resources/effects/ember_of_char.json) 为原施加者提供 `ember_of_char` / `ember_of_ashes` 来源 Bundle，修改 Solar 自带的 `char_stacks` Profile。依据[原表 Solar D19](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit#gid=1186062409&range=D19)，Char 向被点燃爆炸伤害到的其他敌人施加 40 层 Scorch；有 Ashes 时为 60 层。不向这次点燃的中心目标施加，即使中心的禁用窗口已经结束。固定 CSV 的对应位置为 D16，不将两套坐标混用。Ashes 的通用说明在原表 D15 / 快照 D12，本文件的倍率修饰只用于 Char 的明确 40 → 60 映射，另提供 Ashes 选择查询供 Incandescent 使用专属表，不对其他 Scorch 来源一律乘 1.5。

爆炸实际回执中的有效损失为正才进入 Char（包括 Absorption），并再次检查目标禁用状态及世界状态施加资格。取消 / 免疫 / 零损失、已经死亡的目标不获得 Scorch。Char 查询初始施加者在本次爆炸命中时装备的片段；后续灼烧保留该施加者完整来源和武器 / 技能信用，不改成片段自己的来源。片段取样时机是当前明确政策，仍需原作校准。

这条反馈链属于合法玩法：点燃 → 周围目标叠灼烧 → 达到阈值 → 后续点燃 → 原目标再次获得灼烧。引擎不按 root、递归深度、访问过的目标或总触发次数截断；仍遵守每个目标自己的层数、禁用窗口、伤害资格和空间条件。[Bungie 2024-07-25 官方配装介绍](https://www.bungie.net/7/en/News/article/twid-07-25-2024)也展示过 Char / Ashes 与 Solar Fulmination 在强敌群间持续传播点燃的组合；该历史介绍不是当前所有配装和时序参数的数值证据。

7 项 EmberOfCharTest 包括真实 40 / 60 层、其他施加者片段隔离、中心排除、正损失 / 死亡资格、解绑后延迟攻击与信用、爆炸期选择片段，以及四目标交替八轮点燃。该连锁输入为两目标初始各 100 层、另外两目标初始零层，Ashes 使每轮两个爆炸分别施加 60 层，后续无需额外输入。没有 Ashes 的 80 层、只有一次种子爆炸的 60 层均不会凭空点燃。卸下 Char 后已排队的一轮完成，后续自然停止。3 项共享 EmberOfCharGameTest 验证实际四目标五轮扣血、第六轮完成后停止、中心排除和真实死亡。验证的是引擎可表达持续反馈，不是靠提高 Char 层数来制造连锁。

组合回归另在两次种子点燃已启动、尚未爆炸时，给施加者绑定禁止技能 / 开火 / 手动换弹的来源。三类操作的资格查询在每轮均拒绝，但四目标仍交替完成八轮点燃，保留原来源和空的 proc 排除集合。行动限制不隐式取消既有攻击，也不阻止这条反馈链产生下一代灼烧与点燃；显式移除 Char、目标死亡或其他内容条件仍按自身规则生效。

Char / Ashes 均为 partial：Char 的 +10 Grenade 已接入下述共享属性与回能曲线；Ashes 已有 Char 和 Incandescent 的专属层数映射，其他来源及特例尚未实现。原作时间窗口、移动中心、来源数值快照的代际继承、Solar Fulmination、多种直接点燃来源、生产子职业装配与跨重启恢复仍待完成。

### Eruption 的点燃范围

[ember_of_eruption.json](../common/src/test/resources/effects/ember_of_eruption.json) 通过共享 ignition_radius Profile 增加 25% 半径，8 → 10 米。2026-10-11 本地日期读取的[原表 Solar B22 / D22 / N22](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit#gid=1186062409&range=D22)与固定 CSV B19 / D19 / N19 的文本一致；HTML、UTC 抓取时间和哈希保存在[研究记录](../data/d2-research/2026-10-11/ember-of-eruption.json)。重复装配采用 MAX，不累乘。

当前政策是在 100 层阈值时，查询第一位 Scorch 施加者的当前碎片并捕获半径。最后补层者或被点燃目标的配装不代替该持有者；延迟期间卸下碎片、解绑原来源不改变已排队爆炸，下一代达到阈值时重新取样。该时机尚未经过原作校准。即时及 detached 分支共用该结果，实际执行时才选择当前范围内的敌人；Char 随实际命中扩大传播范围，40 / 60 层及各目标禁用窗口均不改变。

5 项 EmberOfEruptionTest 验证模式、重复装配、来源隔离、待爆期间换装、解绑、下一代重采样、边界和 Char 联动。2 项共享 EmberOfEruptionGameTest 验证真实 8 / 10 米边界、范围外目标不受伤、原施加者信用，以及待爆期间进入范围的敌人被命中。测试用 ignition_falloff 的合成曲线定义域从 8 扩展至 10 米；当前流程两种模式均查询距离曲线，只有 PvP 使用其倍率，所以宿主校准必须覆盖整个可选范围。测试曲线在 10 米为零，PvP 的该边界虽被选择但不因零损失获得 Char；PvE 仍按自己的伤害计算。

Eruption 的 +10 Melee 已接入下述共享属性与回能曲线。覆盖保持 partial：其他直接点燃生产者、特殊来源、准确时序 / 衰减、完整子职业装配、HUD 和状态持久化仍待完成。

### 碎片属性进入技能回能与冷却

[character_stats.json](../common/src/test/resources/effects/character_stats.json) 增加 grenade_stat / melee_stat Profile，按“基础点数 → 来源加值 SUM → 0–200 限幅”计算；Char / Eruption 各自的家族用 MAX，避免同一碎片重复绑定多次加 10。原表 Char N19 / 快照 N16 为 +10 Grenade，Eruption N22 / 快照 N19 为 +10 Melee，见[核对与验收记录](../data/d2-research/2026-10-11/solar-fragment-stats.json)。基础 Buff 的 points 只保存宿主输入，不因换装增减或限幅而改写。

threaded_spike_energy / arcbolt_energy 的主动收益及被动恢复修饰现在使用 `attribute` 读取最终点数，再进入原有曲线。属性 Profile 的 200 上限与现有回能曲线的 100 饱和点各自保留；0.8 / 0.75 CES 不并入属性，自身 fixed 返能继续绕过收益 Profile。拾取或外部收益使用接收者当前属性；换装前先结算旧速率，之后用新属性积分。使用这些片段的程序须链接 character_stats，并由实际基础技能的 effects 挂载对应 energy_scaling 来源。曲线查询零输入的最终属性；裸装和仅有碎片时无需初始化属性 Buff。可选 grenade_stat / melee_stat Buff 只提供不含装备的固有点数，护甲贡献由 armor_stats 的六项参数读取。

4 项 SolarFragmentStatsTest 验证玩家隔离、重复 / 卸下、基础值不变、0 / 100 / 200 边界、CES / fixed 分离及换装时的分段积分。2 项共享 SolarFragmentStatsGameTest 验证物理 Firesprite 拾取时的当前 Char 属性，以及实际服务器 tick 上 Eruption 换装对近战恢复和外部收益的影响。这里用已有 Arcbolt / Threaded Spike 账户作为属性消费者验收，跨子职业组合是合成装配，不表示这些碎片可在原作中与这两项技能同时装备；生产子职业约束及 Solar 技能目录仍未接入。

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

近战组按 `1 + max(One-Two Punch 增量, 冰冻非 Boss 被动近战增量) + 其他可相加近战增量` 求倍率，再用于该阶段输入伤害。互斥关系来自 C154，近战 buildcrafting 增伤改为相加及霰弹枪 PvE 的 +150% 由 [Bungie 9.0.0.1](https://www.bungie.net/7/en/News/article/destiny_update_9_0_0_1) 支持。早期测试注入的冰冻 50% / 200% 和其他 40% 继续用于算子回归；现在实际 Freeze 通过 provider:victim 把符合等级 / Basic 或 Glaive 资格的 +120% 放进同组。两片段统一链接 combat_damage.json 的 chorus_d2:outgoing。

11 项 OneTwoPunchTest、7 项双加载器共享 OneTwoPunchGameTest 场景覆盖两种武器 / 活动模式、实际玩家命令与扣血、分散命中 / 不同枪次 / 重复接触、三秒到期、收枪、来源移交、未结束弹丸、免疫 / 取消与未知回执。下一击通过 consume_on_damage 在回执完成时消费；同一动作体的第二次独立近战已无此加成，复用攻击快照也不能复制增益。未知结果保留待确认状态并停止，不重放已经发生的扣血。

内容已声明 sharing=group：受管技能可用 begin_damage_group 把同一次攻击的多个分量明确关联。合成 split_melee 把 10 点近战拆为 5 + 5，普通 PvE 霰弹枪 Buff 下分别造成 12.5 + 12.5，后续独立 10 点近战不再增伤；第一段回执即消费 Buff。原有 double_melee 继续表示两次独立打击，结果为 25 + 10。这个对照验证引擎可以表达两种语义，不代表已校准每个 D2 近战技能的分量归组。

**当前为 partial。** 枪械速度 / 散布、容量 / 间隔和基础伤害是合成夹具参数，近战 Profile 已装配目标 Freeze 的共享取高分组。引擎已支持原版观察入口自动消费和嵌套伤害预留；默认原版来源不猜测 D2 标签，完整 D2 原型、技能近战分类 / 攻击归组仍未完成。以 hit 计入免疫 / 格挡及消费、冻结增益更高时仍消费、新枪刷新和词条替换清除，是明确的当前内容策略；原表未说明的边界仍需原作校准。

当前冻结近战归组仍是明确的 Compendium 内容政策。[2026-06-16 的玩家问题报告](https://www.bungie.net/co/Forums/Post/265280642?page=0&sort=0)给出了冻结加成仍与其他近战乘算、且被 One-Two Punch 存在状态排除的对照结果，与原表“较高者优先”的简单加法模型不一致。这是第一手报告，不是已复现的当前版本结论；官方通用加法改动也不能单独证明该交互已修复。保留 partial 与该冲突，相关数值、分类、政策和来源见 [冻结伤害记录](../data/d2-research/2026-10-11/freeze-damage.json)。

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

常见 PvE 的增强属性增量，按来源 Profile 选择：Melee 为 `0.003×h(S)`（`Game Mechanics!E29`），Grenade 为 `0.0065×h(S)`（`E35`），Super 为 `0.0045×h(S)`（`E41`）。Class 超过 100 时提供施放护盾，原表也列出伤害型职业技能按 Class 属性每点增加 PvE 0.65% 伤害，PvP 写作 `0.1?%`，仍待核实（固定 CSV `E42`；2026-10-11 原表 `E196`）。它不能直接复用 Grenade 属性输入；护盾、职业技能增伤和具体技能资格尚未接入。Well / Ward 的超能属性加成改的是持续时间而不是伤害（`E41`），不能仅凭 super credit 强行套伤害增量。

当前普通近战 / 手雷的增强伤害已由 [ability_stat_damage.json](../common/src/test/resources/effects/ability_stat_damage.json) 表达，原表 `Game Mechanics!E180/E187` 复核与限制保存在 [来源记录](../data/d2-research/2026-10-11/enhanced-ability-damage.json)。PvP 两者均为 `1 + 0.002×h(S)`；PvE 分别为 `1 + 0.003×h(Melee)` 和 `1 + 0.0065×h(Grenade)`。零输入查询最终属性，装备 / 固有值 / 碎片先相加再限幅，100 以下无伤害加成，200 以上不继续增加。Melee 覆盖带明确近战信用的未充能、充能和偃月近战，武器 / 技能身份字符串本身不决定资格。

共享 `combat_damage.json` 的 outgoing Profile 使用独立 ability_stat 乘算阶段，再结算既有 perk 与 Sever；这只验证属性倍率独立于其他因子，没有完成上文全部近战加算子组。宿主须给角色绑定 `chorus_d2:ability_stat_damage` 来源，独立于装备和技能选择；未实现生产角色自动装配。普通路径要求 melee_damage / grenade_damage 恰好一个；两者兼有不加普通属性倍率，须走下述抓钩等专用 Profile，不能把中性结果当作已校准的双信用伤害。

属性修饰声明 on_use：Arcbolt 在宿主提供 impact 时捕获，之后一秒延迟和连锁保留原属性；Threaded Spike 的基础值随施放保存，但每次直接接触伤害查询当前属性。两个策略均已有纯核心 / 双加载器实际扣血验收，**取样时机仍是内容策略，未证明等同原作**。Arcbolt 的能量施放与落地事件仍未组装成完整手雷，Scorch / Ignition 也不因来源身份自动继承这些倍率。

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

Clarity 2024 文章只用于理解 CES / CMS 与例外结构，旧数值不直接当当前值；[当前页面](https://www.d2clarity.com/blog/destiny-science-6/chunk-energy-scalars-breakdown-12) 也标注部分过时。当前基准来自 [Engineeeer 的 2026 研究](https://www.reddit.com/r/DestinyTheGame/comments/1u6czmi/the_final_armor_ability_stats_update_monument_of/) 及其原始计算表。

2026-10-10 复核 [Armor Stat Info](https://docs.google.com/spreadsheets/d/1g5JSR7oa5P2DHwGDBALjQNWD5M8fwXwli_I5naAzSOQ/edit) 的 D2:N2 属性表头、D8:N8 被动倍率、D9:N9 主动收益增量、Z12:AC12 / Z16:AA16 近战与手雷拟合系数，并保存 [原始数值及显示文本摘录](../data/d2-research/2026-10-10/armor-energy.json)。坐标来自 headers=0 的 GViz 响应，不包含原公式。测试使用未四舍五入的 v，而不是显示文本 f。C37 仍称 70 属性等同旧版收益，与当前数值不符：F(70)≈1.992，按研究给出的旧值 ×0.4 转为当前 0 属性后，70 属性约为旧值的 79.7%。保留冲突，不据此旧注释再次修改公式。

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

当前可执行装配见 [threaded_spike_energy.json](../common/src/test/resources/effects/threaded_spike_energy.json)：独立 gain_profile 使用 0.8 CES 和 F(s)，rate_profile 使用 P(s)/145.2。threaded_spike 的基础选择挂载 threaded_spike_energy_scaling 来源，查询 character_stats 汇总的当前近战点数；护甲和碎片无需 stat Buff 初始化即可进入曲线。可选 melee_stat 组件作为独立固有点数只加一次，变化前的时间先按旧速率结算。清除选择只卸载其数值修饰，不删除或回满已有账户。BASE / REFERENCE 收益经 gain_profile，Threaded Spike 自身返回 / 接回表显式使用 FIXED，避免把 CES 或属性再次乘入。可选 CMS 由明确查询标签与 trigger_multiplier 提供，测试的 0.5 仅为合成输入，未校准到某个具体职业模组。Arcbolt 手雷资源与 Demolitionist 已另行接入；其余手雷 / 职业技能 / Super 的资源、其他回能 perk 生产者、属性配装 UI、恢复加速通道叠加与技能切换路由仍待装配；这一片段不代表整个资源系统已经完成 D2 校准。

### 真实装备输入与来源核对

[armor_stats.json](../common/src/test/resources/effects/armor_stats.json) 把每件装备提供的 Health / Grenade / Melee / Class / Super / Weapons 数值贡献给角色 Profile；`armor_stat_inputs.json` 用五个独立槽验证真实 ItemStack 输入。两件护甲可分别贡献点数，装备交换前后按旧 / 新速率分段积分，拾取奖励读取收集当时的新装配。物品参数、可选固有组件、最终属性上限和回能曲线各自独立。片段只接已给定的物品数值，不生成随机词条或证明原作合法护甲组合。

2026-10-11 重新读取原表时，Game Mechanics 原坐标 E187 仍写 `211.5%%` 手雷主动回能，部分数值含问号；与 [Bungie 2026-06-04 更新说明](https://www.bungie.net/7/en/News/Article/dev_insights_abilities_armor_preview) 的最高 +125% 不一致。重新按显式范围读取 [Engineeeer Armor Stat Info](https://docs.google.com/spreadsheets/d/1g5JSR7oa5P2DHwGDBALjQNWD5M8fwXwli_I5naAzSOQ/edit)：D9:N9 为被动倍率、D10:N10 为主动加成，末项分别为 2.75 / 1.25，和现有拟合输出一致。因此本次保留现有曲线与 fitted 置信度，并记录原表文字冲突未解决；不把装配迁移视为重新测量。旧 armor-energy.json 的 gviz 数组坐标比本次原表显式范围少一行，定位原表时使用新的范围记录。原始 HTML / gviz 返回与哈希见 [数据来源](../data/d2-research/2026-10-11/armor-stat-inputs.json)。

## Pugilist

固定 CSV `Weapon Perks!A169/C169` 与 2026-10-10 保存的原表 HTML `A170/C170` 一致：武器击杀给予 10% / 强化 11% 近战能量，Fusion Rifle、Glaive、Shotgun、Sniper Rifle 为 20% / 强化 22%；造成近战伤害后 +35 操控，持续 3 秒。两种坐标体系分开记录，不能用 CSV 行号直接定位原表。

上述回能条目没有自行标明当前属性基准。[Engineeeer 当前研究](https://www.reddit.com/r/DestinyTheGame/comments/1u6czmi/the_final_armor_ability_stats_update_monument_of/) 用 Demolitionist 的旧 10% → 当前 0 属性 4% 说明通用回能变化。当前 Pugilist 定义据此显式采用旧值 ×0.4：普通 / 强化为 0.04 / 0.044，四类双倍武器为 0.08 / 0.088。**应用到 Pugilist 是推断，尚不是逐类实测结论**；转换记录见 [pugilist-energy.json](../data/d2-research/2026-10-10/pugilist-energy.json)。内容先完成这个跨版本转换，随后以 base 交给收益管线，不把旧 10% 直接再乘当前 F(s)。例如 100 近战属性、Threaded Spike CES=0.8，普通武器一次击杀入账 `0.04×2.25×0.8=0.072`；满账户按实际空余量裁剪。

[pugilist.json](../common/src/test/resources/effects/pugilist.json) 按持有者、同武器实例与 weapon_kill 信用共同筛选。grant_ability_energy 在击杀收益执行时读取当前基础近战槽，不把 perk 固定绑定某个技能 ID。临时施放替换不改变基础资源归属；空槽或无成本声明跳过，缺失账户 / 缺失 gain_profile 则报配置错误。弹丸飞行期间换技能的双端测试确认能量进入新选择的池；替代技能采用合成 0.5 系数，不能当作另一真实 D2 技能。

操控分支要求持有者的 melee_damage 且实际 HP / Shield / Absorption 正损失；武器击杀本身不授予操控。各已装备实例获得自己的 35 点增益，同实例重施加刷新 3 秒，收枪保留、卸装清除。以上资格、换装 / 刷新政策需原作边界校准，尤其 glaive / 持续伤害的信用分类；不把免疫 / 取消接触当作造成伤害。操控目前只进入 weapon_handling 数值 Profile，尚未映射到真实举枪 / 收枪 / 瞄准动画时长。

[pugilist_weapon.json](../common/src/test/resources/effects/pugilist_weapon.json) 通过真实玩家容器、普通开火命令、扣弹与物理投射物死亡驱动内容；弹量、射速、飞行及测试伤害均为合成参数。覆盖仍为 partial，尚需完整物品 / 属性 / 子职业装配、操控表现、逐类回能实测和状态持久化。

## Demolitionist

固定 CSV `Weapon Perks!A62/C62` 与保存的原表 HTML `A63/C63` 记录两部分效果：武器击杀给予手雷能量；使用手雷补充弹匣，补弹间隔 3 秒。回能分支采用与 Pugilist 相同的四类武器名单及普通 / 强化比例。[当前研究](https://www.reddit.com/r/DestinyTheGame/comments/1u6czmi/the_final_armor_ability_stats_update_monument_of/)直接说明普通 Demolitionist 在 0 属性、CES=1 时为 4%；强化 4.4%、四类武器 8% / 8.8% 是按旧表比例换算，仍需逐类测量。来源与置信度分开保存在 [demolitionist-energy.json](../data/d2-research/2026-10-10/demolitionist-energy.json)。

[demolitionist.json](../common/src/test/resources/effects/demolitionist.json) 的击杀分支匹配持有者、武器实例和 weapon_kill 信用，使用 grant_ability_energy 读取当前基础手雷槽。[Bungie 6.3.0.1](https://www.bungie.net/7/en/News/article/hotfix_6_3_0_1) 确认 Pugilist、Demolitionist、Wellspring 在能力输入被替换时仍应给予能量，例子包括持剑 / 偃月时的近战；这支持保留基础账户，但并未定义所有临时能力或共享池的切换政策。

资源侧新增 [arcbolt_energy.json](../common/src/test/resources/effects/arcbolt_energy.json)：固定 CSV Arc N28 的 151.5 秒与 CES=0.75，手雷基础组件 grenade_stat 与近战组件分开，经 character_stats 属性 Profile 加值 / 限幅后，复用相同的主动函数和被动拟合数值。例如 100 手雷属性时普通武器一次击杀为 `0.04×2.25×0.75=0.0675`，强化 Shotgun 为 `0.088×2.25×0.75=0.1485`。资源定义可以独立验证，不代表 Arcbolt 已有完整投掷 / 落地技能；当前 Arcbolt 连锁内容仍由宿主提供 impact 事件。

补弹监听已接受、已付费后的 ability_started，并要求 grenade_ability 标签和本武器当前手持。refill_magazine 从储备转移至当前有效容量，保留已有溢出；实际转移大于零后才授予按武器实例保存的 3 秒冷却。满弹匣、已有溢出或空储备不启动冷却；部分储备可以部分补充并启动冷却。收枪 / 卸装不清除未到期冷却；另一把武器有自己的冷却。免费但已接受的手雷也触发，条件失败 / 能量不足 / 非手雷使用不触发。**手持要求、正转移计时、每武器独立及免费施放等边界是当前明确的内容策略，原表没有逐项说明，仍需原作校准。**

固定 CSV C250 区分 Refill 与 Reload：这里的补弹不发布 reload_finished，所以不会激活 Kill Clip，也不会为 Clown Cartridge 抽随机数。已开始的手动换弹若到期时已满，只结束其计划，不伪造实际换弹完成。后续技能世界动作结果未知时，已付能量、储备转移及冷却保留，不自动回滚或重放。

[demolitionist_weapon.json](../common/src/test/resources/effects/demolitionist_weapon.json) 用实际玩家容器 / 开火 / 击杀 / 施放入口验证联动，但技能动作体是合成治疗，武器容量与射速也是测试参数；尚未完成真实投掷、Grapple / 消耗手雷等全部资格、武器原型、动画与持久化。原作循环仍允许，3 秒仅限制这条补弹规则，不限制击杀回能或全局事件。

## Wellspring

固定 CSV `Weapon Perks!A246/C246`，对应保存原表 HTML `A247/C247`：普通 / 强化列示 8% / 9%，分配给未充能的技能；多充能技能拥有至少一份后视为已充能，但额外充能仍收到常规能量的三分之一。[当前回能研究](https://www.reddit.com/r/DestinyTheGame/comments/1u6czmi/the_final_armor_ability_stats_update_monument_of/)提供通用旧基准 ×0.4 的转换依据，模板据此采用 0 属性 q=0.032 / 0.036。**Wellspring 的这两个当前基准是推断，尚未逐项实测。** 坐标与政策记录见 [wellspring-energy.json](../data/d2-research/2026-10-10/wellspring-energy.json)。

[wellspring.json](../common/src/test/resources/effects/wellspring.json) 在有效的本武器击杀后，先观察当前基础 grenade / melee / class 三槽，再捕获基础量与分母。未选技能、无 cost、满账户不参与普通分配；Super 不参与。令 n 为有账户、未满且能量小于 1 的槽数，当前内容采用以下规则：

| 观察时的账户 | 缩放前份额 |
| --- | --- |
| 小于一份充能、未满 | q/n |
| 已有至少一份、额外充能未满 | q/3，不计入 n |
| 已满、无选择或无账户 | 不授予 |

每份分别进入接收技能的 gain_profile，应用其 CES / 当前属性等修饰，再按容量裁剪；溢出不重新分给其他槽。同次击杀的分母来自收益前快照，所以先填满第一个槽不会增加后面两个槽的份额。下一次击杀重新观察。全满 / 全缺失时 n=0，捕获的分配系数为 0，分支不会执行除零或读取缺失账户。

**原表没有消除混合多充能情形的分母歧义。** 当前把“常规能量的三分之一”解释为 q/3，与其他未充能槽的 q/n 独立；例如只有一个空手雷，近战已有一份但第二份未满时，手雷取得 q，近战另取得 q/3。缩放前总和可能超过 q，这是一项待校准的内容政策，不能当成已经测得的原作总量。[2020 年第一手测试](https://www.reddit.com/r/DestinyTheGame/comments/jvaolr/)甚至报告额外充能不回能；它仅保留为历史冲突，不用于覆盖当前表述。当前版本的混合情形、强化比例、溢出再分配、技能切换和共享池仍需原作复核。

8 项纯核心测试覆盖全部单充能组合、普通 / 强化、独立接收 Profile、多充能阈值、固定分母、缺失槽、归属与换技能、后续未知世界结果及编解码。3 项双端共享场景使用真实装备 / 开火 / 投射物击杀，其中一项由服务器 tick 自动确认击杀。接收器 [wellspring_targets.json](../common/src/test/resources/effects/wellspring_targets.json) 是合成技能账户，0.75 / 0.8 / 0.5 只是验证独立缩放的输入，尤其 0.5 不是某个职业技能的 D2 校准。武器复用 Pugilist 测试原型并替换词条；当前完整子职业 / 属性 / UI / 存档装配仍未完成，覆盖为 partial。

共用武器属性目录见 [weapon_stats.json](../common/src/test/resources/effects/weapon_stats.json)：Pugilist / Surplus 的贡献在同一操控组相加，随后统一限幅。武器原型曲线独立装配，不因每个 perk 复制属性 Profile。

## Surplus

固定 CSV `Weapon Perks!A214/C214`，对应保存原表 HTML `A215/C215`，给出下列普通版加值，单位为 stat_point：

| 完整充能总数 | 稳定性 | 操控 | 装填属性 |
| --- | --- | --- | --- |
| 0 | 0 | 0 | 0 |
| 1 | 5 | 5 | 10 |
| 2 | 15 | 25 | 25 |
| 3 及以上 | 25 | 60 | 60 |

[surplus.json](../common/src/test/resources/effects/surplus.json) 在属性查询时读取当前基础 grenade / melee / class 的 full_charges，分别 floor 后求和并限制为 3。额外充能按份计数，部分能量不算完整充能；例如只有两个近战充能而另外两槽为空时是二档，不是一档。Super 不计入；无选择 / 无 cost 的槽由明确 available 分支计零，损坏账户仍失败。[2020 年第一手测试及其修正](https://www.reddit.com/r/DestinyTheGame/comments/knzf4b/)支持额外充能逐份计数和三档上限；这只作为历史机制证据，不能覆盖当前所有异域装备、共享池与临时替换边界。来源与未知项见 [surplus-stats.json](../data/d2-research/2026-10-10/surplus-stats.json)。

每个 modifier 限定本武器查询，收枪后仍可查询该实例的当前数值，另一把武器不继承它的词条；移除来源后不再贡献。收益、消耗或技能选择改变后，下一次查询直接看到新状态，无需收到额外事件来更新缓存 Buff。模板声明三个目标 Profile：weapon_stability / weapon_handling / weapon_reload；装配者提供包含 perks 加值阶段和相应 group 的完整管线。词条本身不内置武器动画曲线。

**强化版没有已确认的数值表。** 原表只写效果更强，模板在正档位时要求查询输入 `surplus_enhanced_{stability|handling|reload}_{1|2|3}`，单位 stat_point，表示该档完整加值；不默认复用普通版，也不猜 +5。零档不读取校准值。测试的普通值 +2 是合成输入。当前自动换弹宿主未提供这些校准测量，因此强化版 Surplus 的自动换弹尚不支持：缺失输入会在调度和转移弹药前失败，不能把纯查询可传参当成完整强化玩法。

[surplus_weapon.json](../common/src/test/resources/effects/surplus_weapon.json) 提供合成属性消费者：共用 weapon_reload 属性 Profile 将基础 10 点叠加词条并限制 0–100；两种原型独立用 `2−0.01×属性`（rifle）或 `3−0.02×属性`（shotgun）转换为秒，最后进入共用 reload_animation 秒数倍率。普通三档都先得到 70 属性，两原型分别为 1.3 / 1.6 秒；普通两档的 rifle 为 1.65 秒。三段通过 reload.profiles 装配，Surplus 没有按原型复制，词条属性与原型曲线也不合并在同一 Profile 中。合成动画来源再乘 0.75 时，三档 rifle 为 0.975 秒。上述曲线和倍率用于验证机制，不能当作某个 D2 武器或动画 perk 的参数。

真实服务端换弹保存每段 Profile、版本、贡献和阶段轨迹。换弹开始后消费技能或移除动画来源只改变下一次请求，当前 Plan 保留原时长；这是 Chorus 接受时采样政策，原作是否中途重新取值仍需校准。对保存结果移除命名动画因子或更换数学基础值会重新经过所有下游阶段，但不读取改变后的技能状态。

8 项 SurplusTest 与 3 项共享世界场景覆盖档位 / 额外充能、武器隔离、空槽 / 换选择、缺失强化输入、加值→限幅→曲线，以及实际扣费和两次服务器 tick 换弹。真实 Wellspring 击杀填满技能后，同武器的 Surplus 装填属性立即提高。另有 4 项 ReloadPipelineTest 与 3 项 ProfilePipelineGameTest 验证两种曲线共用属性、动画后乘、非法链与未调度失败、保存轨迹重算以及真实延迟治疗消费查询结果。稳定性与操控目前只有数值输出，尚未连接后坐力、举枪 / 开镜动画；全部武器曲线、强化版装配与原作边界仍未完成，覆盖保持 partial。

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

覆盖保持 partial：全局 Woven Mail 分支与护盾分支并存时的叠加、MAX 分组、盾标签分类和跨层预算都是待校准的内容选择，计算贡献保留 assumed 标记。实际勇士屏障 / 弱点、武器弹丸 / 爆炸分量分类、真实装备和 Guardian 身份自动装配尚未实现。原 shield_scaling.json 的 Woven Mail 标记仍只作资格隔离测试；新增 StrandDefenseTest 将同一 Under-Over 词条接到下节的真实减伤定义，PvP 躯干 100 点分别得到 100 × 1.20 × 0.75 = 90、强化 91.5 点。这里只验证明确指定的组合政策，未据此完成跨层校准。

## Sever 与 Woven Mail

共享定义在 [strand_defense.json](../common/src/test/resources/effects/strand_defense.json)，Slice 通过链接引用同一 Sever。固定基线为 Compendium 2026-10-05 CSV Strand B/D7、B/D8；2026-10-10 也核对了[在线 Strand 原表](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit?gid=1870531554#gid=1870531554)第 9 / 10 行，数值与例外一致。CSV 行号和在线行号分开保存，不重写已有来源摘要。[Bungie 7.3.0](https://www.bungie.net/7/en/News/Article/update-7-3-0-patch-notes)曾明确把 Woven Mail 对战员减伤从 55% 调至 45%；历史更新仅作交叉依据，当前实现仍按固定快照验收。

| 效果 | 已表达的规则 | 归属与边界 |
| --- | --- | --- |
| Sever | 输出减少 40% / PvP 15%；Slice 施加 10 / 5 秒 | 修饰受影响者发出的伤害，原施加者与受击目标不会因此获得该输出修饰 |
| Woven Mail | 受到伤害减少 45% / PvP 25%；基础 10 秒，可按授予来源覆盖时间 | Guardian 的精准命中与近战绕过这一项减伤，其他独立减伤继续生效；战员同类攻击不因此绕过 |
| 超能开始 | 受益者已接受的任何带 super_ability 标签的施放移除旧 Woven Mail | 队友施放、普通技能、条件或成本拒绝不移除；技能动作体新授予的 Woven Mail 保留 |

100 点 PvE 输入在攻击者 Sever 和目标 Woven Mail 下为 100 × 0.6 × 0.55 = 33；PvP 为 100 × 0.85 × 0.75 = 63.75。原版实际扣血、来源解绑后的状态、精确到期、受益者 / 施加者隔离，以及超能清理先于动作体均有测试。

覆盖仍为 partial。上述数值的 PvE / PvP 分支沿用 EffectState.mode；Guardian / 精准 / 近战标签和攻击 Profile 由可信宿主显式提供，尚未做真实玩家子职业装配或 Gambit 混合交战分类。Sever 采用 on_hit，使发射后新增或到期的状态影响延迟命中，这是显式内容政策，尚无原作快照时机验收。两个 Buff 均为单实例。Sever 仍采用显式的 refresh:reset 政策；Woven Mail 使用 refresh:max_remaining，取当前剩余时间与新来源授予时长的较大值。分组和组合也保留 assumed 贡献标记。

Woven Mail 的刷新依据为 Bungie [Ash & Iron 数值预览](https://www.bungie.net/7/en/News/Article/weapon_tuning_preview_ashiron)（2025-09-03），其中明确比较当前剩余时间和新来源时长；[9.1.0 正式补丁](https://www.bungie.net/7/en/News/Article/destiny_update_9_1_0)（2025-09-09）确认修复刷新回原始时长的问题。这条规则补充固定快照，不改写 CSV 来源摘要。例如旧效果余 8 秒时授予 2 秒仍余 8 秒，余 1 秒时授予 2 秒变为 2 秒；不会恢复历史曾有的 10 秒，也不会相加到 3 秒。短来源到期后仍承受实际减伤已有双端世界回归。

Continuity 在 CSV Strand D15 / 在线第 18 行说明通常延长 Strand 减益 50%；当前 Slice 已按下节查询施加者配装，支持 D8 的 10+5 / 5+2.5 秒。完整子职业装配和全部来源特例尚未完成。Threaded Spike 的 D38 特别把 Sever 时长标为未知，不自动套用全局默认值。Threaded Spike 模板现已按击杀授予同一 Woven Mail；物理及持续时间校准仍未完成。

## Continuity 与来源指定的状态延长

[continuity.json](../common/src/test/resources/effects/continuity.json) 提供共享持续时间 Profile 和可装备来源 Bundle。生产者使用 `calculate(target:source_owner)` 输入基础时长，并通过 `continuity_extension` 提供该来源已知的扩展；只有施加者身上存在合格 Continuity 修饰才加入扩展。它不读取受害者或附近队友的 fragment，也不延长 Slice 自身的 8 / 9 秒激活窗口。

固定数值来源为 CSV Strand D8 / D9 / D15；2026-10-10 核对的[在线 Strand 原表](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit?gid=1870531554#gid=1870531554)对应第 10 / 11 / 18 行。不能把“通常 50%”改写成无条件乘 1.5：

| 来源 | 基础 | Continuity 扩展 | 合计 | 当前证据范围 |
| --- | --- | --- | --- | --- |
| Sever PvE | 10 秒 | +5 秒 | 15 秒 | Slice 实际命中、状态授权、伤害输出与持续时间 |
| Sever PvP | 5 秒 | +2.5 秒 | 7.5 秒 | 同上，真实 tick 验证过基础时长仍生效、延长后到期 |
| Suspend 普通/精英战员 | 6 秒 | +2 秒 | 8 秒 | 实际抬升、运动 / 攻击限制与真实 tick 到期 |
| Suspend 小 Boss | 3 秒 | +1 秒 | 4 秒 | 同上，独立等级时间 |
| Suspend Guardian | 2 秒 | +1 秒 | 3 秒 | 状态授权、垂直抬升与到期；横移速度、镜头及腰射限制仍待实现 |

Slice 当前按状态施加动作前的配装计算时间；装备 fragment 后才触发命中会使用新时间，卸下 fragment 只影响新施加，不回头改写已有状态的到期点。重复来源采用 MAX，状态授权拒绝仍不消费或刷新 Slice。这些是明确且已测试的 Chorus 政策，原作换装/快照边界仍待校准。通用查询还支持先保存结果跨回调使用，或回调内读取当前配装；内容必须显式选择。

覆盖保持 partial：Suspend 现已接控制与 Boss 脱离伤害，Guardian 完整控制和勇士晕眩仍未完成；Unravel 的完整生产者、各技能的特定基数、真实子职业选择和 fragment 槽尚未装配。Threaded Spike 的 `?+?` 保留未知。[Bungie 9.1.0](https://origin-static01.bungie.net/7/en/News/Article/destiny_update_9_1_0)曾单独修正 Tear 的 Sever 未受 Continuity 延长的问题，说明生产者资格也需逐项验证；该历史补丁不能替代当前各来源时长。

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

**仍为 partial。** `rolling_storm_weapon.json` 中五发弹匣、10 点伤害、射速 / 换弹时间及一秒 test:amplified 均为验收输入。另已用下述共享 Amplified 定义的获得 / 到期验证每次 2 层 / 1 层的切换；旧 Bolt Charge 合成夹具仅在该组合测试内统一版本，不表示生产数据版本已经全部迁移。系统来源仍由测试宿主显式安装，尚无玩家系统自动装配。Bolt Charge 的空间范围、数值映射与迁移缺口仍适用。这些测试资源未进入发布 jar。

### Amplified 与 Speed Booster

`amplified.json` 提供共享状态、Arc 内在击杀计数和移动状态驱动的计时规则；`amplified_movement.json` 提供需要显式校准参数的原版属性投影。来源为固定 CSV Arc B4 / D4，本轮重新抓取的原表为 B6 / D6，文本已核对一致并保留 HTML 与哈希，见 [来源记录](../data/d2-research/2026-10-11/amplified.json)。官方 [8.2.0](https://www.bungie.net/7/en/News/Article/destiny_update_8_2_0) 支持增幅的 15% PvE 战斗人员减伤及降低敌人瞄准精度；官方 [Season 21 调整](https://www.bungie.net/7/en/News/Article/s21_abilities_tuning) 与 [7.1.0](https://www.bungie.net/7/en/News/article/season-deep-update-7-1-0) 支持极速的 2.5 秒启动和停止后的 2 秒余留。这些历史改动只用于交叉核对，不重复叠加到固定快照。

每名 Arc 持有者显式绑定一个 `chorus_d2:arc_intrinsic` 来源。只有其自身的 `chorus:arc_damage` 击杀推进共享计数：普通目标记 1 个四分之一进度、精英与 Guardian 记 2、Miniboss 及以上记 4。每次合格击杀重置 6 秒窗口；达到 4 后消费计数并获得 15 秒 Amplified。分类来自击杀回执中的 player / entity tag / type tag，未知或不可用发出 `arc_kill_classification_unresolved`，不重查尸体来假定它是普通目标。当前将已观察但未标记的非玩家当普通目标、champion 归入 4 层，均需由正式敌人映射与原作边界复核。

Amplified 使用 `chorus:amplified` / `chorus_d2:amplified` 标签，提供 +50 Mobility、+40 Handling 和 0.95 操控动画倍率的查询贡献。Handling 沿用现有 weapon_handling 的 100 点上限；Mobility 当前只是点数归约，完整移动曲线与上限装配仍待完成。外部星相可直接授予同一共享状态，重施加取剩余时间最大值，不用短应用缩短较长状态；达到阈值后的新击杀重新累计，累计满后才再次授予。计数溢出丢弃、重施加取长及子职业卸下只清理未完成计数，是显式内容政策，尚非原作逐帧校准结论。测试输入 bundle 不代表正式星相。

获得 Amplified 时立即观察当前移动标志，此后每 50 ms 观察一次。确认冲刺且未骑乘 / 睡眠时开始一个 2.5 秒 windup；停止、观察缺失或宿主未提供移动信息则取消 windup。观察未知会发诊断，不会补造“停止冲刺”的事实。windup 到期时再次确认仍有 Amplified 且当前仍在冲刺，才授予独立的 Speed Booster。已经冲刺后再获得 Amplified 也能开始计时；重复授予不重启已有 windup。采样间隔以内的短中断可能无法被观察，本实现不等同于完整输入状态机。

Speed Booster 有自己的周期计时器，每次确认仍在冲刺就将剩余时间刷新至至少 2 秒。Amplified 结束会取消未完成 windup，但不会删除已经获得的 Speed Booster；继续冲刺可持续保持，停止后从最后一次确认冲刺起余留 2 秒。缺失移动证据时不续时，明确缺失 / 死亡的实体清理状态；正常死亡事实也清理计数、Amplified、windup 和 Speed Booster。它们均是同接收者的共享状态，不串到原施加者或别的角色。

两个状态分别提供 15% resistance 贡献，因此同时生效时 `damage × 0.85 × 0.85`。资格要求明确的 `chorus:combatant` 来源标签、没有 `chorus:guardian`，且模式为 PvE；PvP、环境 / 未分类来源不会自动获得这项减伤。默认 Minecraft 原版伤害来源不推断 D2 敌人分类，正式适配器仍须提供这些标签；世界验收用真实生物攻击、明确的来源标签和实际 HP 扣除验证合成规则。

可选 `chorus_d2:arc_movement_calibration` 来源必须提供三个 delta 参数：`amplified_speed / speed_booster_speed / speed_booster_jump`。前两个投影到 movement_speed，极速替换增幅的速度贡献；最后一个投影到 jump_strength。测试中的 0.2 / 0.5 / 0.1 是合成验收输入，不是 D2 参数。特别是原作“跳跃高度 +25%”不能直接写成原版跳跃初速度 +25%；“最大移动速度”也不能以固定 delta 声称准确复现。这些参数应在正式运动曲线与其他效果合成规则确定后校准。

原版属性绑定与两个共享 Profile 现位于 [movement_attributes.json](../common/src/test/resources/effects/movement_attributes.json)，Arc 校准包只提供贡献；组合程序需显式链接此模块。Profile 先累加增速 delta，转为倍率后乘独立状态因子，再转回原版属性 delta。合成输入下 Amplified +20% 与 Slow ×0.5 得到整体 ×0.6，移除 Slow 后恢复 ×1.2；该合成政策已有世界测试，不代表已校准原作的速度上限、滑铲或空中加速度。

7 项 AmplifiedTest 验证加权进度与精确六秒边界、来源 / 分类隔离、连续 / 中断 / 未知冲刺、极速独立寿命、接收者与属性查询、两层减伤、死亡清理、严格校准输入和 Rolling Storm 组合。3 项共享世界场景验证真实电弧击杀、原版属性与真实 tick、实际生物攻击的 HP 结果。真实 Fabric 客户端按键链路的完成证据见实现记录。

**两项均为 partial。** 尚未实现最大移动速度曲线、8.5 / 11 米滑铲、极速滑铲后持续到死亡的基础滑铲提升、敌人瞄准精度降低、真实操控动画、完整生产子职业 / 星相装配、HUD 或存档。原表数值被保留为需求；没有用原版属性临时值替代这些缺口。

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
| 基础冷却 / chunk scalar | 151.5 秒 / 0.75x | arcbolt_energy.json 已装配独立资源与手雷属性曲线；仍未与真实投掷 / 落地连成技能 |

“造成伤害后”目前用实际 HP + Chorus 护盾 + Absorption 损失大于 0 表示。未命中 / 被取消 / 免疫 / 零损失会结束本次连锁，不重新选择替代目标。初始目标锁定后不会重查视线或距离；后续跳跃在同一逻辑时刻结算且不要求视线。这些边界没有在源单元格给出足够细节，是明确的 Chorus 内容选择，不能当成已核对的原作行为。

落地事件由测试宿主提供，落点暂用独立 LivingEntity 表示并从扫描排除；尚无投掷轨迹、碰撞接触点和飞行实体。敌我关系使用当前仍在本维度的施加者，未包含 D2 阵营或离线关系快照。来源卸下后的延迟攻击保留本次施放归属；每次施放各自排除已命中目标，不限制其他技能循环或下一次施放。

官方 [9.0.0.1 更新](https://www.bungie.net/7/en/News/article/destiny_update_9_0_0_1) 记录该手雷对 Boss、Miniboss 与勇士的相对增伤调整，不能据此把快照中的 521 再乘一次，也不足以确定完整目标等级倍率。官方 [9.5.0 更新](https://www.bungie.net/7/en/News/Article/destiny_update_9_5_0) 对 Lucky Raspberry 的 Jolt 施加与回能另有改动；本基础内容没有加入该异域护甲交互。上述补丁不独立验证固定快照的绝对伤害、范围或冷却，也不证明之后没有改动。

## 物理飞行与内容校准边界

引擎已有独立物理投射物和碰撞后动作体：实际位置 / 方向发射、捕获伤害与来源、扫掠碰撞、方块接触点 / 法线、寿命以及未知地形终止；与即时技能支付和原版实体同步已接通。直击或落点扫描可以继续使用相同 DSL，程序不必把落点伪装成可伤害实体。

`projectile.json` 的 20 米/秒、重力 0、drag 1、1 秒寿命和基础 10 伤害均为合成验收参数，不能套用到 Arcbolt / Firebolt 的 Heavy Trajectory。现有 Arcbolt 内容夹具仍从测试落地事件开始；实际出手位置、重轨迹、碰撞时序与技能冷却尚未逐项校准和装配。当前中心射线 / 实体碰撞箱扩张、50 ms 离散积分和施加者排除也是宿主策略，不是原作数值断言。通用物理测试不增加已审阅 Compendium 条目数。


墙面反弹、直线穿透、每目标命中上限与逐接触计数已接入通用 DSL。需求参考快照 `Weapon Perks!C20/C188`（穿甲弹一次穿透、Ricochet Rounds 反弹）、`Exotic Weapons!D27/D83`（Khvostov 同目标两次、Hard Light 墙面反弹）、`Void!D57`、`Stasis!D38`、`Strand!D38`（技能弹跳 / 追踪 / 回能）。通用限速追踪与接触后目标间转向也已接入；这些内容仍依赖各自数值、资格、计数语义及技能装配，合成夹具不将它们标为已实现。


Shield Throw、Withering Blade、Threaded Spike 的名称 / 机制 / 冷却单元格已逐项纳入覆盖清单（Void B/D/N57、Stasis B/D/N38、Strand B/D/N38）。Shield Throw 仍为 unimplemented；后两者已有下节的 partial 模板。已有追踪策略能够表达半径、扫描半角、速率限制、当前关系 / 视线过滤和接触后转向，但这些宿主策略不是原作校准结论。Withering Blade 的 12 [8] 米是固定来源值；Threaded Spike 的追踪半径与 Sever 持续时间仍为未知，不以测试值代替。

Shield Throw 的“4 次弹跳”和 Withering Blade 的“3 次表面 / 最多4敌人”还需确认共享次数语义。核心已有独立预算和可选 `total_continuations` 共享继续次数；具备表达能力不等于已确认原作混合接触顺序。通用 destination 能返回移动中的施放者，ARRIVED 与伤害命中分开；catch 提供接收者、半径 / 时间 / 视线验证与 CAUGHT 分支。Threaded Spike 现用 damage_tally 共享去程已确认的命中 / 击杀，在回程结算；原表的 Melee % 使用 grant_energy 的 fixed 表达固定充能比例，不能替换为实际支付成本的退款。retain_cost 仍专用于共享实付成本预算。完整验收要求和缺口保存在 `data/compendium/review.json`。

## Duskfield 暮域手雷与固定周期场

[duskfield.json](../common/src/test/resources/effects/duskfield.json) 通过实际技能支付、投射物接触、固定位置 Buff、周期计时器和 invoke_bundle 组合实现。它与 grenade_energy、duskfield_energy、Slow / Freeze、stasis_duration、Durance、character_stats、combat_damage、movement_attributes、weapon_stats 及外部碎冰 / 落点衰减 Profile 同版本链接。原表 Stasis B36/D36/N36 与 CSV B30/D30/N30 已逐格核对，新抓取的 HTML 与哈希见 [来源记录](../data/d2-research/2026-10-11/duskfield.json)。[官方 3.4.0 更新](https://www.bungie.net/7/en/News/Article/50880)曾移除引爆拉拽；该历史记录不替代当前表格的冷却和其他数值。

| 阶段 | 伤害 | 战员 / Guardian 的 Slow | 时长 / 周期 |
| --- | --- | --- | --- |
| 落点爆发 | 至多 20，衰减由校准 Profile 提供 | 20 / 10 层 | Slow 2 秒，Durance 延长 2 秒；原表爆炸半径未知 |
| 固定区域 | 每跳 1 | 每跳 10 / 5 层 | PvE / PvP 每 0.35 / 0.3 秒一跳，半径 4 米 |
| 区域寿命 | — | 已授予的 Slow 独立计时 | 7 秒，Durance 延长 2 秒 |

单格能量初始充满，每次施放支付一格，基础恢复为每 131.7 秒一格。duskfield_energy 使用独立 0.875 CES 和已记录的 Grenade 主动 / 被动拟合曲线，由基础技能选择挂载 energy_scaling 来源。100 Grenade 时外部基础 4% 变成 `4% × 0.875 × 2.25 = 7.875%`，被动速率为 `2.75 / 131.7`；清除 / 重新选择不回满账户。完整职业模组和其余回能来源仍需装配。

实体或方块接触先在原施放者上建立按独立施放身份区分的 field Buff，保存精确接触位置和 Slow / Freeze 校准；再执行落点范围伤害。到期 / 未加载地形终止不产生爆炸或区域。Buff 自有周期计时器每次重新查询固定球形范围，施放者移动、技能卸下及旧目标离场不改变中心；新目标和当前阵营关系参与下一跳。生命周期区间暂定为 `[创建, 到期)`，到期不再补一跳，明确移除 field 也会撤销它的计时器，但不会移除已施加的 Slow。

Durance 在落点创建区域时决定 7 / 9 秒的场寿命，在每次 Slow 施加时另行决定 2 / 4 秒的状态时长。后来卸下碎片不缩短已有截止时间；后续新查询使用当前装备。区域独自叠到百层即可调用共享 Freeze，冻结仍使用自己的时长和原始手雷信用。多个独立场保留各自位置 / 生命周期，Slow 层数按已有共享政策归并。

周期伤害显式使用发布资源中的 `chorus_d2:ability_dot`，加入原版 `bypasses_cooldown` 标签，使 0.3 / 0.35 秒的每跳 1 点不被 Minecraft 的受伤冷却吞掉。它没有加入绕过护甲或无敌的标签，仍走正常扣血、护盾、死亡与归属回执。落点爆发使用普通伤害类型。两端游戏测试直接按声明的 damage_type 查询真实注册表，没有用测试伤害类型替换这条周期路径。

8 项 DuskfieldTest 和 5 项共享 DuskfieldGameTest 覆盖两种周期、落点 / 逐跳层数、到期端点、精确四米边界、晚进入 / 离场、施放者移动、两来源独立场、Durance 分别取样、持续叠层到 Freeze，以及未知实际扣血后保留支付 / 区域并停止、不重放。场结束不会取消另一场，来源移除不会重写已有归属。

覆盖保持 **partial**。未知的落点爆炸半径、衰减、物理轨迹和第一跳相位须显式校准；测试的 2 米落点半径、线性衰减、20 m/s、零重力及一周期后首跳不是原作事实。当前用目标脚底点球形查询、无额外视线筛选，Slow 层数按实际目标类型、周期按发射时活动模式选取；原作混合目标、重叠场和端点政策待确认。阵营查询仍依赖可解析的原施放者，关系参照移除后明确结束区域。Touch of Winter、Renewal Grasps、其余金装 / 碎片 / Champion、正式子职业与按键 / 动画 / HUD、持久化及 NeoForge 真实客户端尚未完成。技能模板仍在测试资源中，不能将新增伤害类型等同于完整技能已经发布。

## Withering Blade 两充能技能模板

[withering_blade.json](../common/src/test/resources/effects/withering_blade.json) 与 withering_blade_energy、slow、stasis_duration、durance、freeze、character_stats、combat_damage、movement_attributes 和 weapon_stats 同版本链接；接触伤害及碎冰衰减另由校准 Profile 提供。[来源记录](../data/d2-research/2026-10-11/withering-blade.json) 保留原表 Stasis B51/D51/N51 与固定 CSV B38/D38/N38 的对应和校验和。此定义仍是需校准的测试技能模板，没有随发布 jar 提供正式子职业目录。

资源上限和初值为两格，每次施放支付一格；基础恢复为每 145.2 秒一格，使用同一个连续账户顺序恢复，声明一格阈值。第三次无能量时拒绝；卸下 / 重新选择不会重置余额。选择技能挂载自己的 energy_scaling 来源，以当前 Melee 汇总属性查询被动恢复和块状回能曲线；独立 0.8 CES 只进入 gain_profile。沿用已记录的拟合曲线，100 Melee 时分别为 ×2.75 被动速率和 ×2.25 块状倍率；没有把两格容量乘入恢复速度或外部充能量。与 Threaded Spike 的 Profile / 资源身份分开，可同时链接；具体职业模组的回能贡献尚未装配。

每次实体接触观察实际目标类型，战员 / Guardian 分别提供 296 / 72 基础伤害，再经过必填 `chorus_d2:withering_blade_contact_damage` 和共享 outgoing。接触校准查询携带 `sequence`、`bounces`、`entity_contacts` 和实际 victim，可表达原作后续确认的反弹衰减，不从旧版本的伤害数值推断当前行为。伤害保留 melee_damage / stasis 标签、melee_kill 信用及原施放身份；取消、免疫、格挡和已确认致死不施加 Slow。

成功且非致死的命中使用 `invoke_bundle` 调用共享 slow_application：战员 60 层、3.5 秒、Durance 延长 3.5 秒；Guardian 40 层、1.5 秒、延长 0.5 秒。两枚飞镖对同一战员达到百层后复用 Freeze，最后一次施放拥有冻结信用；两次 Guardian 命中只有 80 层。卸下技能不取消已有弹体，命中时仍以原施放者的当前碎片查询时长。Durance 不延长转换后的 Freeze。目标分类、当前装备取样及共享归属是显式 Chorus 政策，混合玩家 / 战员环境和全部原作边界仍需实测。

追踪半径按发射时活动模式选择 12 / 8 米。独立墙面反弹为 3 次、实体穿透为 3 次（第四次实体接触后结束）；共享继续次数和每目标次数是必填校准参数。速度、重力、阻力、寿命、转向率、获取角度以及 Slow 跳跃 / Freeze 数值也必须明确提供，缺少施放参数在扣费前失败。测试的共享继续次数 3、每目标 1 次、20 m/s、零重力、2 秒寿命和接触不衰减仅为合成配置，不是原作事实；目标重访、混合墙面 / 实体预算、原作飞行轨迹和当前反弹伤害仍未校准。

9 项 WitheringBladeTest 覆盖校准依赖、两充能 / 时间积分、实际目标类型、百层转换与来源、卸下后的 Durance、拒绝 / 致死 / 未知回执、接触测量及近战属性回能。5 项共享 WitheringBladeGameTest 验证第四个真实目标仍受伤及叠层、第五个不受影响、卸下后两枚飞镖冻结战员、真实 Guardian 的 72 / 40、服务器 tick 顺序回能与未知实际伤害不重放。覆盖保持 **partial**：正式职业 / 技能装配、按键、动画 / 图标 / HUD、完整 Champion 和金装 / 碎片组合、技能切换的原作能量政策、持久化及 NeoForge 真实客户端仍待完成。

## Threaded Spike 技能模板

[threaded_spike.json](../common/src/test/resources/effects/threaded_spike.json) 与 strand_defense.json、continuity.json、threaded_spike_energy.json、character_stats.json 同版本链接。依据固定 CSV Strand D38 / N38，声明 427 / PvP 82 基础伤害、最多九个不同敌人、首次弹跳乘 0.82、之后每次乘 0.575，以及 145.2 秒基础冷却。已发生的墙面反弹也计入衰减：当前实体命中的前序弹跳数为 `bounces + entity_contacts - 1`；这是需要原作校准的计数政策，不能将命中回能的 hits 数直接当作弹跳数。

| 确认命中数 | 0 | 1 | 2 | 3 | 4 | 5+ |
| --- | --- | --- | --- | --- | --- | --- |
| 自动返回 | 5% | 10% | 20% | 30% | 35% | 40% |
| 主动接回 | 20% | 30% | 50% | 70% | 85% | 100% |

两种收益均按完整一格近战充能授予，显式绕过收益 Profile；另有按当前近战属性缩放的持续恢复。接回时若持有者当前绑定带 `chorus_d2:strand_subclass` 标签的来源，每次确认击杀给予 2 秒 Woven Mail，最多 10 秒；零击杀不授予。它复用共享 max_remaining 定义，短奖励不会缩短已有长状态。只检查攻击的 Strand 标签会错误地把 Prismatic 的缚丝攻击算成缚丝子职业。[Bungie 9.7.0](https://static01.bungie.net/7/en/News/Article/destiny_update_9_7_0) 在 Threadrunner 的 Rope Dart 条目中确认“接回按击杀授予 Woven Mail”；具体 2 秒 / 10 秒和回能数字来自固定 Compendium，不从该补丁推算。

未知参数没有默认 D2 值。模板的 `calibration.*` 测量包括追踪半径、速度 / 转向 / 寿命、墙面预算、接回窗口与 Sever 基础 / Continuity 扩展时间；缺少测量会在参数求值阶段、扣费前失败。宿主应先装配经过验证的参数定义。独立的 threaded_spike_test_calibration.json 只供测试，0.4+0.2 秒 Sever、15 米追踪等值不是原作测量。数值查询后显式四舍五入到整数微秒，随后 apply_status；没有削弱引擎的精确微秒契约。

当前 partial 边界：去程在第九次接触、无法继续反弹的表面或寿命结束后新建回程；卸载不制造回程，账本到期清理。归还成功前不会提前支付，close 先于收益，重复回调不能重复支付。取消 / 失败不计 hits，免疫 / 格挡计入，Sever 仅尝试施加于 APPLIED 且未确认死亡的目标；这些资格、接回时查询当前子职业及当前输出修饰的取样时机仍待原作验证。基础伤害按施放模式冻结，输出 Profile 在各次伤害时查询；测试以 1:1 数字投影到合成 1000 HP 靶，不代表完成等级 / 目标类型 / 属性缩放。外部收益路径现已装配 0.8 chunk scalar 与属性曲线，本技能自己的回能表显式豁免；Pugilist 及基础技能槽路由已接入，其他回能生产者和共享池的动态变更仍待完成。真实近战输入与 grapple 优先级、Phalanx 盾穿透、完整轨迹 / 转向 / 时机校准、单实体阶段切换、子职业 UI 和表现仍未完成。


## Ember of Mercy / Solace 与恢复时长

本地 CSV 快照 Solar B20/D20/N20 的 Mercy 对应原表 B23/D23/N23；Solace 的 B24/D24/N24 对应原表 B27/D27/N27；Restoration 的 B7/D7 对应原表 B9/D9。保存的原表 HTML 已逐格核对，来源、哈希、历史官方说明与缺口见 [mercy-solace.json](../data/d2-research/2026-10-10/mercy-solace.json)。这些坐标体系不能互换。

[ember_of_mercy.json](../common/src/test/resources/effects/ember_of_mercy.json) 监听已确认的 chorus:pickup，以 pickup_kind 和实际收集者判断资格。收集时没有 Restoration 则授予 x1 2 秒；已有恢复则仅延长 2 秒，不重置历史时长、层级或恢复来源。上限是当前剩余 15 秒，已有 20 秒也会降到 15 秒。以后真正重新施加时，historic_max 仍可恢复到已达到的 20 秒；到期后再拾取则作为新 x1 开始。碎片 +10 Health 暂为属性查询。

[ember_of_solace.json](../common/src/test/resources/effects/ember_of_solace.json) 对 solar_effect_duration 提供 +50% 修饰。Mercy 在收集时查询当前受益者，因此变为每次 3 秒；外部施加来源也可查询目标，使基础 4 秒成为 6 秒。施加者装了碎片并不让未装碎片的受益者多得时间。卸下碎片不会追溯改写既有倒计时；下一次查询读取新装配。这是显式的来源调用协议，尚未自动覆盖全部 Solar Buff；Radiant 的显式施加入口已接入；Empyrean 按独立敌人等级表延长，不经过该 Profile。

[restoration_effect.json](../common/src/test/resources/effects/restoration_effect.json) 将共享恢复定义独立出来，原始速率为快照的 PvE 35/50、PvP 17.5/25 HP/s，要求 restoration_rate Profile 校准到世界生命单位。测试校准仍为 0.1；没有把该值当成实测比例。与旧 restoration.json 是同一 ID 的两套装配示例，不能同时链接；旧文件继续保留 Rift 及低层回归测试入口。新定义沿用 restoration_or_rift 通道，也保留 historic_max / keep_highest_tier。非致死伤害不打断恢复，碎片卸下后已授予的 Buff 正常计时。

官方 [2024-02-15 说明](https://www.bungie.net/7/en/News/Article/this_week_in_destiny_02_15_24) 支持 Mercy 延长 Restoration 2 秒、Solace 下 3 秒及历史计时修复；[7.3.5 补丁](https://www.bungie.net/7/en/News/article/destiny_2_update_7_3_5) 的 Mercy 措辞还提到 Radiant，与详细说明及当前固定快照的 Restoration 分支不一致。本实现以快照和详细说明的交集为准，没有据此添加 Mercy 延长 Radiant 的行为，也没有把历史公告当成当前全部数值的实测。

仍未实现 Mercy 的队友复活分支：原表写 5+2.5 秒并将半径标为 ?，需要真实队友复活事件和明确的范围校准。原版重生、不死图腾不能代替该事件。死亡 / 重生时恢复的清理政策、全部施加来源、真实 HP 缩放、Health 属性游戏投影、子职业装配、HUD 与存档仍待完成；Mercy 和 Solace 均记录为 partial。


## Radiant 与 empowering 来源优先级

[radiant.json](../common/src/test/resources/effects/radiant.json) 按 CSV Solar D6 / 原表 D8 定义共享状态：基础 10 秒，重新施加恢复历史最长时长，延长上限 15 秒。测试施加入口使用 apply_status，并查询受益者 solar_effect_duration；Solace 下基础 10 秒变为 15 秒。完整真实技能 / 装备授予来源仍需装配。

[empowering_damage.json](../common/src/test/resources/effects/empowering_damage.json) 使用现有 NumericGroup 的 family PRIORITY，再在 empowering 中 MAX；独立 perks 在另一阶段相乘。Radiant 与 Well 的数值来源共享 radiance 家族，Well 优先级较高，因此对勇士选择 25% 而不是 Radiant 30%，也不是两者相乘。测试 Well presence 仅验证这一优先级，不代表完整 Well。其他家族的更高 empowering 仍可参与 MAX。

Radiant 对显式 weapon_damage / golden_gun_damage 生效，普通 grenade / melee / super 标签不自动获得资格。活动模式 PvE 为 20%，PvP 为 10%；PvE 对勇士取 30%。Golden Gun 资格由 Solar D51/D52 支持，当前沿用 D6 的共享加成解释，完整超能、Celestial 变种、Well / Lumina 互斥及对勇士的特殊数值仍需单独校准。

攻击可在开火时保存 Radiant 是否有效；radiant_champion 则是每次命中的 count 输入，必须精确为 0 或 1。新武器夹具在实际射弹接触时 inspect_entity，从 entity / type 标记选择该输入，再调用 damage_snapshot。缺观察、单位错误或非二值不会悄悄按普通敌人算；原版未标记实体按普通目标处理是这份内容的分类协议，不代表已有全部 D2 敌人目录。开火后过期 / 收枪仍保留之前射弹的增益，是当前可验证的 Chorus 时序政策，不是对原作全部武器快照行为的完成声明。

官方 [2026-06-04 技能预览](https://www.bungie.net/7/en/News/Article/dev_insights_abilities_armor_preview) 调整了对勇士加成并移除 Radiant 武器伤害的屏障晕眩；数值使用 Compendium 的绝对 30%，不在其上重复乘 1.1。[9.5.0](https://www.bungie.net/7/en/News/Article/destiny_update_9_5_0) 还让 Well 内开火的 Golden Gun 获得 Radiant 资格；[2026-02-19 已知问题](https://www.bungie.net/7/en/News/Article/twid_02_19_2026) 列过 Lumina 覆盖 Golden Gun 的 Radiant 增益，不能据此推断已修复或所有来源同样互斥。完整来源、混合交战模式、非射弹宿主接线、死亡清理、UI 与存档仍待实现。[来源记录](../data/d2-research/2026-10-10/radiant.json) 保留上述边界与坐标映射。


## Ember of Empyrean 的按击杀延长

[ember_of_empyrean.json](../common/src/test/resources/effects/ember_of_empyrean.json) 对应 CSV Solar B18/D18/N18、原表 B21/D21/N21。原表给 T1/T2/T3/T4 各 +1.5/+2.25/+3/+6 秒，Guardian +3 秒，Health -10。官方 [7.3.5](https://www.bungie.net/7/en/News/article/destiny_2_update_7_3_5) 支持 15 秒上限及按敌人等级区分延长量；该历史公告不能代替当前完整敌人目录。[来源与政策](../data/d2-research/2026-10-10/ember-of-empyrean.json) 保存原表映射与缺口。

资格读取确认 kill 的实际 actor、chorus:solar_damage 和当前碎片来源，并要求至少一个相关状态仍在。武器来源带 Solar 标签不足以把 Arc 派生伤害算成 Solar；伤害生产者须声明规范元素标签。没有推断击杀冷却，多个合格击杀各自延长。Scorch 周期伤害保留 Solar 身份和击杀归属，已进入同一规则。

从伤害回执的 EntityObservation 读取 player / entity / type：玩家使用 Guardian 分支，非玩家须恰有一个不同的 combatant_tier_1..4。两处出现同一个 Tier 不算冲突。death 反应删掉尸体或改写标签不影响已记录分类；不再实时查询尸体。未提供事件观察、观察到不可用、没有 Tier、冲突或仅有 boss 等 Rank 时，发 empyrean_unclassified，保持现有计时；不猜延长量，不把 Rank 当 Tier，也不事后补发。

两种状态分别 extend_buff，缺失的一种保持缺失。现有 20/25 秒可以被压回剩余 15 秒，但历史最长值和恢复层级 / 来源保留；之后真正重新施加仍按 historic_max。精确到期后击杀不使状态重新出现。Solace 增加显式施加与 Mercy 的时长，此处按原表没有附加 Solace 数字的等级表执行，不再次乘 1.5；这个交互解释仍需直接计时校准。

世界组合已使用实际武器、物理击杀、Tempering → Firesprite → Mercy 与当前 Solace 验证：T1/T4 击杀延长两种状态，随后的 Mercy 拾取只延长恢复；恢复 x2 保留。另验证 >15 秒上限 / 历史值、未知分类诊断，以及实际 Scorch tick 死亡。Health 仍为查询属性；完整子职业装配、敌人目录、助攻与同击授予时序、死亡生命周期、HUD 和持久化未完成。


## Dual Loader

固定 CSV `Weapon Perks!A74/C74` 与 2026-10-11 重新读取的原表 `A75/C75` 均说明普通版每次换弹额外装入 1 发，强化版额外 2 发；[原表来源与保存哈希](../data/d2-research/2026-10-11/dual-loader.json) 保留了坐标体系。当前描述没有旧式换弹速度惩罚，本定义不另加该惩罚，也不据此推断动画秒数。

[dual_loader.json](../common/src/test/resources/effects/dual_loader.json) 为 this_weapon 提供 round 加值，经 [reload_insert_rounds.json](../common/src/test/resources/effects/reload_insert_rounds.json) 的独立 Profile 供 reload.insert 查询。基础一次一发时，普通 / 强化分别计划装入 2 / 3 发；到期仍按当前弹匣缺口和储备裁剪，例如剩余一发空间时只转移一发。它不增加容量、不生成弹药，不修改整弹匣 refill 或技能换弹资格。

每步接受时取样、下一步在上一步完成反应后读取，以及额外弹数组采用 MAX，均是明确的 Chorus 内容策略；与 Timelost Magazine 等其他装填数量效果的原作组合待校准。验收用真实装备词条、普通手动换弹命令及两名玩家，武器基础一发 / 五发容量 / 0.2 秒首次 / 0.1 秒重复都是合成参数。完整武器目录、动画 / 按键、弹药持久化和所有 reload perk 的原作逐发资格仍未完成。


## Suppression（压制）：行动限制与活动状态结束

[suppression.json](../common/src/test/resources/effects/suppression.json) 提供共享压制 Buff 和技能行动门槛。2026-10-11 抓取的[原表 Void B10 / D10](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit#gid=1907852650&range=D10)与固定快照 B8 / D8 一致：压制持续 10 [5] 秒；Guardian 退出活动 Super / Transcendence，期间不能施放技能；普通和精英战斗人员另有失能及禁止射击，过载勇士受眩晕。[Bungie 4.0.0.1](https://www.bungie.net/7/en/News/article/51110)提供历史上的中断技能、禁止技能 / 移动模式及战斗人员射击说明，不作为当前全部时长的数值依据。原始 HTML、坐标和哈希保存于 [suppression.json 资料记录](../data/d2-research/2026-10-11/suppression.json)。

当前状态禁止受影响者的新 `ability_use`，在替换后的定义资格阶段拒绝，不支付资源。它不添加玩家武器开火或手动换弹限制。中断采用明确内容约定：活动技能的 Buff 带 `chorus_d2:suppression_interruptible` 标签。压制授予 / 重授予时，统一移除目标身上全部这类实例；压制期间随后授予的匹配状态也会结束。每个技能自己的 ended 规则负责清理，生命周期绑定的计时器 / 延迟动作终止，已脱离来源的动作继续；不会从 Buff 名称猜测 Super，也不移除没有该标签的其他增益。

原版生物另有独立 `ranged_attack` 禁止条件：来源明确带 `chorus:combatant`，并带 `chorus_d2:rank_and_file` 或 `chorus_d2:elite`。`chorus:guardian` 和 `chorus_d2:champion / miniboss / boss` 优先排除，即使同时含较低等级标签。原版适配器提供 Mob 角色和当前实体 / 类型标签，不把未标记生物猜成普通目标；这些标签是明确的内容分类约定，不改变原版伤害的归属。该分支不增加玩家 `weapon_fire / weapon_reload` 限制。原版发射、光束、Goal / Brain 接线见 [原版远程攻击资格](engine-data-packs.md#原版生物的远程攻击资格)；不取消旧弹体、DOT 或点燃链。

两项新增共享 SuppressionNativeGameTest 以真实骷髅和明确等级标签验证普通 / 精英拒绝射击、其余等级与未分类目标排除、施加者归属、同一 Buff 下动态换级、驱散，以及世界时钟十秒到期后实际新箭生成。测试用等级标签进行 D2 适配，不声称 Minecraft 骷髅天然对应某个 D2 等级；过载勇士的眩晕也不能由这项射击限制替代。

默认 Buff 为 10 秒，[suppression_inputs.json](../common/src/test/resources/effects/suppression_inputs.json) 的验收施加器通过 apply_status 明确给出 PvE 10 / PvP 5 秒，真实效果生产者也必须提供自己对应的时长。资格拒绝、死亡或缺失不授予状态，因此不触发中断。重复施加当前采用 MAX_REMAINING，同实例保留最初来源；跨来源归属、刷新边界和持续技能精细中断时机仍待原作校准。压制解除不返还旧技能成本，也不会自动恢复已结束技能，新的施放单独支付。

7 项 SuppressionTest 验证两种活动状态的结束、附着 / 脱离工作、其他状态与接收者隔离、拒绝施加、5 / 10 秒精确到期、重施加、后续活动状态、显式清除和 Codec。2 项共享 SuppressionGameTest 使用真实玩家、普通命令与装备容器，验证周期回血确实停止、detached 回血继续、能量不被拒绝请求消耗、玩家仍可扣弹开火和完成换弹，以及实际五秒到期后新技能付费生效。测试中的 Super / Transcendence、回血、能量及武器数值都是合成验收输入，未实现完整原作技能。

覆盖保持 partial：普通 / 精英迷失方向等完整 AI 失能、过载勇士眩晕、全部压制来源及特例、真实 Super / Transcendence 原型与持续耗能、宿主移动模式、HUD / 客户端提示、生产装配和持久化仍未完成。原版射击路径已接线，其他模组自定义攻击、NPC Chorus 武器发射装配及正式 D2 敌人等级目录仍待完成。上述共享状态还不能代表完整压制行为。

## Marksman's Dodge（神射手闪身）：换弹分支

2026-10-11 原表 `Class Abilities!B9/D9/N9` 说明：闪身换弹全部武器、拾取 15 米内弹药，并在动画期间移除 PvE 投射物追踪和对玩家的辅助瞄准；基础冷却 42 秒、Chunk Scalar 1。对应固定 CSV 坐标为 `B6/D6/N6`。原表 `Weapon Perks!C251`（CSV C250）将 Marksman's Dodge 与 Dragon's Shadow 列为触发换弹 perk 的 Reload。[来源记录](../data/d2-research/2026-10-11/marksman-dodge.json) 保存原始 HTML、哈希、坐标和实现边界。

[marksman_dodge.json](../common/src/test/resources/effects/marksman_dodge.json) 只提供换弹分支的技能模板：职业槽单份能量，基础再生率 1/42、默认直接回能路径对应 CES 1；不包含 Class 属性倍率。接受施放先求值并固定 calibration.reload_delay，然后支付 1 份能量，detached 延迟执行 reload_weapons(equipped)。延迟必须为正的整数微秒；未提供校准参数时不能施放，且不会扣费。[测试校准](../common/src/test/resources/effects/marksman_dodge_test_calibration.json) 的 0.2 秒只用于验收，不能作为原作动画时间。

换弹执行时才取得当前配装，经过真实容器 / 存活资格复核后统一转移全部武器弹药，再发布各自完成事件。因此接受后替换武器时，新装备武器会被换弹，已卸下的旧武器保持余额；同一批的第一项完成反应不会改变后续武器的已定转移。接受后的任务在取消技能选择后继续，重新选择不补满能量。这些取样 / 生命周期规则是明确的 Chorus 内容政策，原作边界尚未实测。

当前 completion=transferred：只有实际装入子弹的武器触发换弹 perk，满弹匣 / 无储备不发完成。这是待校准政策；原表只明确它属于 Reload，未证明零转移资格，核心的 verified 政策可表达另一种结果。普通 refill_magazine 不因这条技能定义改变语义。有限储备守恒、无限储备保持无限；本定义不会生成弹药或为另一持有者换弹。

四项纯核心测试和两端共享的实际玩家场景验证了支付 / 延迟、三武器批量转移、实例信用、延迟期间换装、另一玩家隔离、零转移政策及 Kill Clip 激活。测试里的武器弹量、无限弹药武器和治疗观察器为合成输入。闪身位移 / 动画、15 米拾取、追踪 / 辅助瞄准中断、Class 被动 / 块状回能倍率与增强护盾、金装组合、职业选择限制、正式内容装配、按键 / HUD 和弹药持久化仍未完成，审阅状态保持 partial。


## Slow / Freeze / Suspend 的控制接口核对

2026-10-11 重新抓取原表 Stasis B9/D9、B10/D10、B11/D11 和 Strand B11/D11，与固定 CSV 的 Slow、Freeze、Shatter、Suspend 描述归一化一致。原始 HTML、哈希、坐标和待实现要求见 [控制效果资料](../data/d2-research/2026-10-11/control-effects.json)。[Bungie 9.7.0（2026-06-09）](https://www.bungie.net/7/en-us/News/Article/destiny_update_9_7_0)将碎冰伤害恢复为眩晕势不可挡，因此不能沿用 9.0.0.1 的过载映射，也不能把 Freeze 施加事实直接当成 Shatter 伤害。

现有行动门槛可以分别表达技能、武器输入、原版射击和近战资格；它们不会自动实现完整控制状态。Slow 已接通百层到 Freeze 的转换、区分目标的地面移动 / 武器惩罚及移动技能门禁；空中速度与跳跃高度仍需校准；Freeze 已有分级时长、实际损失阈值、Boss 例外、地面 Super 解冻和跨目标碎冰；承伤分类修饰已接线，近战叠加实测与实际技能来源仍未完成；Breakout 已按显式参数接线，原作费用和动画待校准；Suspend 的分级状态、战斗人员控制和 Boss 短暂状态 / 后续伤害现已接线；Guardian 已接入显式参数的水平限速，原作速度 / 腰射限制与镜头仍需进一步完成，见下节。

已增加通用 movement_input / jump 门槛、服务端输入归约和客户端同步过滤，保留惯性、重力与外部冲量。另有 horizontal_motion / vertical_motion，可固定全部坐标或当前高度，覆盖原版位置 / 速度写入与服务端玩家位置包纠正；它们明确阻挡受限轴的外力，和输入门槛不同。另有 displace_entity / world_direction，可按完整碰撞箱逐步抬升、触顶停升并保留当前悬停；Buff 到期或清除后释放。其合成夹具已区分请求、碰撞裁剪和实际距离，尚未校准 Suspend 的抬升高度 / 速度、Guardian 有限水平运动曲线；目标等级、Boss 与状态结束伤害现由下节 Suspend 内容组合。不能用这些通用机制或 NoAI 代替完整 Freeze / Suspend。Slow、Suspend、Freeze 和 Shatter 均保持 partial 审阅；其余通用接口不自动增加控制效果完成数，见 [主动移动输入限制](engine-data-packs.md#原版主动移动输入与跳跃限制)及[碰撞感知位移](engine-data-packs.md#碰撞感知位移与逐步抬升)。


### Slow 的百层转换与属性惩罚

[slow.json](../common/src/test/resources/effects/slow.json) 以原表 Stasis B9/D9、固定 CSV B7/D7 为依据。表中给出百层冻结、战员和 Guardian 移速降低 50%、Guardian 跳跃高度降低 50% 及禁用移动技能；四项武器属性在 perk 后、100 点封顶前降低 75%。战员瞄准精度、Guardian 未知比例的 Flinch 和 Overload 眩晕仍是待实现要求。

`chorus_d2:slow_application` 通过 Bundle includes 复用 Freeze 的施加来源，必须提供 `slow_duration: second`、`slow_durance_extension: second`、`slow_jump_delta: delta` 及继承的 `combatant_threshold / shatter_radius / guardian_shatter_damage`。基础时间和非负 Durance 延长由具体生产者提供，无延长也须显式填 0；链接程序须包含 stasis_duration.json。不把一个技能的时长推广成所有 Slow 的固定值。`apply_slow` 事件须匹配 owner、source_instance、bundle，并提供正整数 `stacks: count`；零 / 负数不施加，单次输入封顶 100，缺失 / 小数不默默取整。参数超出条件范围不施加，缺少参数或单位不符在绑定时拒绝。

施加前观察接收者的存活与实际玩家类别，已 Freeze 的目标不积累新 Slow；权限批准后才累计共享 Slow。达到 100 时保留本次施加来源并发出 `apply_freeze`，沿用既有目标等级、来源、Super、时长及状态授权。只有实际取得 Freeze 后才删除 Slow；直接 Freeze 同样清除它。冻结拒绝或缺少等级时保留 100 层与惩罚，之后合格施加可以重试；未知授权结果则停止运行时，保留已提交 Slow，不自动重放。解冻不还原已消费层数。

实际生产者可通过 `invoke_bundle` 向 slow_application 投递 apply_slow，显式提供上述参数、接收者和 stacks，而不要求发射时的装备来源仍在常驻表中。真实投射物卸下后命中、命中时读取 Durance、以及从被调包继续发出 apply_freeze 的两端世界场景已验证。[bundle_invocation_slow.json](../common/src/test/resources/effects/bundle_invocation_slow.json) 仍仅为合成调用链验收；上述 Withering Blade 模板另有实际技能支付、来源数值和两充能命中的独立验收。

当前内容明确选择：不同来源共享层数，持续时间取现有截止时间与新截止时间较晚者；现有 Slow 保留最初施加者和跳跃参数，触发百层的来源拥有新 Freeze。以上归属、刷新、重复冻结排除与失败重试都是 Chorus 的暂定政策，原表未完整规定这些边界，尚需原作实测。首个来源卸下不会自动删已授予状态；运行时持久化和全体死亡清理仍未完成。

Slow 的实际投影与查询分别为：

| 接收者 | 当前已接线行为 |
| --- | --- |
| 战员、Guardian | 共享 movement_speed Profile 乘 ×0.5，输出至原版 movement_speed；清除和到期移除该贡献 |
| Guardian | jump_strength 乘必填校准因子；`ability_use` 最终技能含 `chorus:movement_ability` 时拒绝，普通方向、跳跃、射击和其他技能仍可使用 |
| Guardian | Stability / Handling / Reload / Recoil Direction 在 perks 之后乘 ×0.25，再 clamp 到 0–100；160→40，80→20；例如 80 Handling + Amplified 40 后再 Slow 得到 30 |

[slow_test_calibration.json](../common/src/test/resources/effects/slow_test_calibration.json) 的 2 秒和 jump delta -0.3 均是合成输入，客户端另用 10 秒以覆盖网络验收过程。真实原版跳跃冲量降到 70% **不等于跳跃高度降到 50%**；玩家空中 `getFlyingSpeed` 也不完全由 movement_speed 属性决定，因此这里仅证明地面减速与参数化跳跃，未宣称完成原作全方向移动。稳定性、操控与后坐力只在数值查询中验证，完整武器物理、动画和射击反馈还需接线。

11 项 SlowTest 覆盖阈值、多人归属、玩家分类、属性顺序、技能资格、清除 / 到期、拒绝和未知结果。5 项共享世界测试覆盖真实属性与跳跃冲量、技能入口、百层控制切换、不同来源刷新、真实 tick 到期和 Amplified 合成。SlowClientGameTest 验证真实地面按键位移减速、本地与远端属性包、持续输入下冻结、解冻恢复及目标隔离。Durance 的来源专属延长、Withering Blade 命中与 Duskfield 周期场已接入。Slow 保持 **partial**：其余技能 / 武器生产者及其完整 Durance 数据、空中速度、50% 跳高校准、敌方精度、Flinch、Champion、存档及正式 HUD / 视觉仍待完成。

### Whisper of Durance：分别计算状态与技能存在时间

[durance.json](../common/src/test/resources/effects/durance.json) 和 [stasis_duration.json](../common/src/test/resources/effects/stasis_duration.json) 使用现有 `calculate → apply_status / grant_buff` 链路，无需新增核心动作。原表 Stasis B18/D18/N18 对应固定 CSV B15/D15/N15；其余技能的原表行号偏移各不相同，不能统一加一个偏移量。原始 HTML、逐格核对和历史官方说明见 [来源记录](../data/d2-research/2026-10-11/durance.json)。

| 来源 / 查询 | 战员：基础 + 延长（秒） | Guardian：基础 + 延长（秒） |
| --- | --- | --- |
| Withering Blade 的 Slow | 3.5 + 3.5 | 1.5 + 0.5 |
| Bleak Watcher 的 Slow | 4.5 + 4.5 | 2 + 1.75 |
| Duskfield 的 Slow | 2 + 2 | 2 + 2 |
| Duskfield 场存在时间 | 7 + 2 | 7 + 2 |
| Bleak Watcher 存在时间 | 25 + 5 | 25 + 5 |

生产者应按来源和接收者选择基础时间与延长值，而不是给所有 Slow 乘统一倍率。Slow 在观察存活 / 非冻结目标后，以 `target:source_owner` 查询 `chorus_d2:stasis_duration`，显式标签 `chorus_d2:slow`，传入 `durance_extension: second`；随后将结果作为实际状态授权和施加的时间。敌人或队友身上的碎片不参与施加者查询。多个相同碎片贡献取 MAX；未装备时保持基础值，装备后缺失延长测量则报错，不能猜默认值。Slow 来源缺少校准参数在绑定时拒绝，负延长不施加。

持续技能可用 `chorus_d2:durance_lingering` 标签查询同一 Profile，传入该技能自己的延长。这个标签须由已核对的生产者显式选择，不是所有冰影技能自动符合。Freeze、通用 stasis 标签、职业技能时间不会自动获得延长。状态与持续技能分别取样，不能把炮台的 +5 秒套进其每发 Slow，也不能让百层转换后的 Freeze 继承 Slow 的延长。

当前政策是施加 / 创建时读取施加者的当前碎片，已提交的截止时间保持不变；卸下只影响之后查询。新 Slow 刷新仍取最晚截止时间并保留首次信用，达到百层的来源拥有 Freeze。Durance 的 +10 Melee 进入共享 character_stats，重复来源按同碎片去重，再与其他加值相加并按 200 点封顶。这些取样、重复装配与刷新规则是 Chorus 内容政策，并非已实测的全部原作边界。

8 项 DuranceTest 验证上表时长、双方装备隔离、卸下 / 刷新 / 精确到期、授权、百层转换、+10 Melee 及缺失参数；3 项共享 DuranceGameTest 验证真实目标、tick 到期后原版移速恢复和 Freeze 控制。Withering Blade 的实际命中、Duskfield 的落点与 7 / 9 秒周期场，以及 Bleak Watcher 的真实 25 / 30 秒构造物、寻敌连发和命中 Slow 均已有独立验收。冰炮台的 Aspect 长按转换与完整子职业装配仍未完成。Winter's Shroud 战员时长在原表带问号，未采为确定校准。生产技能全套来源、原作采样边界、正式子职业装配 / HUD / 持久化仍待完成，Durance 保持 **partial**。

### Freeze / Shatter 的分级控制与范围碎冰

[freeze.json](../common/src/test/resources/effects/freeze.json) 现以共享数据定义组合分级时长、行动门槛、损失累计与碎冰。它链接 combat_damage.json 和外部必填的 chorus_d2:shatter_falloff Profile；原表证据为上述 Stasis B10/D10、B11/D11，固定 CSV 对应 B8/D8、B9/D9。Freeze / Shatter 均为 partial，尚非完整冰影系统。

| 接收者 / 施加来源 | 时长 | 当前行为 |
| --- | --- | --- |
| 明确 Rank-and-File、Elite、Miniboss 或 Champion 标签的战斗人员 | 6 秒 | 禁止主动输入、跳跃、两轴运动、新技能、武器开火 / 换弹及原版射击 / 近战 |
| Boss | 3 秒 | 保留可查询 Freeze，不限制上述行动；自然到期自动碎冰 |
| Guardian，由 Player 的非 Super 来源施加 | 1.35 秒 | 完全冻结；受地面 Super 例外约束 |
| Guardian，由战斗人员或带 Super 标签的来源施加 | 4.75 秒 | 同上；可将职业技能替换为显式参数的 Breakout |
| Guardian，已有 Roaming Super 状态 | 1 秒 | 自动解冻，保留 Roaming 状态 |

分类使用 inspect_entity 的实际 Player 标志、实体 / 类型等级标签及施加者身份；不把整个 PvE 世界中的玩家当成普通怪物。Boss 优先于低等级标签；未知、缺失、死亡目标和宿主拒绝不获得控制。Champion 标签在这里仅赋予六秒冻结模板，不代表已实现其眩晕 / 抗性。已有 Freeze 的再次施加当前直接忽略，保留原信用、generation、累计值与截止时间；这一重施加政策，以及冻结时拒绝换弹，仍待原作校准。

公共施加入口为 chorus_d2:freeze_application。可信宿主发送 chorus_d2:apply_freeze，actor 为该来源持有者，victim 为接收者，references.source_instance / bundle 指明来源实例；chorus_d2:clear_freeze 用于明确清除。模板要求 combatant_threshold（damage，正数）、shatter_radius（meter，正数）、guardian_shatter_damage（damage，非负）三个参数。首次成功施加将参数保存到 Buff；Guardian 碎冰阈值使用原表括号值 200，战斗人员使用来源参数。没有以 Minecraft 默认血量代替 D2 伤害标尺。

受伤累计使用已确认的 effective_damage，即 HP 与 Chorus 护盾实际损失；原版 Absorption 单独记录且不计入当前阈值。按 damage_id 对每代冻结去重，并要求 event_has_buff match:instance，重施加后的新实例不会借用旧命中。达到阈值或收到致死命中后碎冰；可信 chorus_d2:shatter 也可显式触发。损失种类的选择与原作护盾交互仍待校准。普通到期和清除只解冻，Boss 仅自然到期使用自动碎冰分支。

碎冰先保存参数及命中回执里的原位置，然后移除 Freeze，再查询该位置附近仍存活、与原施加者非友方的实体；排除原施加者，包含仍存活的中心目标。显式碎冰或到期没有历史位置时，才向世界捕获当前位置。每个目标按查询距离读取外部衰减 Profile：战斗人员基础峰值 361，实际 Player 使用来源的 guardian_shatter_damage，再进入 outgoing Profile 与原版承伤流程。标签为 chorus:stasis / chorus:freeze_shatter / chorus:derived；仅 Boss 自然到期增加 chorus:boss_auto_shatter。当前信用保留最初冻结者，第三方显式触发也不转移；阵营查询仍需要可用的施加者参照，不能把卸下来源的测试说成施加者实体消失后也已支持。

新的碎冰伤害可以累计邻近目标的冻结阈值并继续碎冰；没有全局事件次数、链深度或根事件截止。移除当前冻结只防止同代重复爆炸，不截断其他目标的有效反应。未知世界结果保留已经结束的 Freeze 与已扣除的生命，停止运行时且不重放。

Guardian 处于冻结时，服务端确认 on_ground 且最终技能带 chorus:super_ability 的输入可以通过；接受后先清除 Freeze，再执行技能。长冻结另允许下述 Breakout，资格与付费流程独立。带 chorus_d2:freeze_interruptible 的一次性施放状态在冻结进入时及冻结期间新建时被结束。当前 test:super、test:roaming 和 test:oneoff 是合成生产者，真实 Super / 子职业装配仍未完成。Breakout 使用独立生命支付；它的具体费用和与原作伤害层的对应关系仍未校准。

[freeze_test_calibration.json](../common/src/test/resources/effects/freeze_test_calibration.json) 明确提供合成阈值 100、半径 4 米和 Guardian 碎冰伤害 80；[freeze_test_falloff.json](../common/src/test/resources/effects/freeze_test_falloff.json) 提供从 0 米的 1 到 4 米的 0 的合成线性曲线。这些均非原作实测参数。12 项纯核心测试、8 项双端共享世界场景和 FreezeClientGameTest 分别覆盖数值 / 生命周期、真实扣血与行动、实际玩家和远端生物的冻结同步与解冻。

冻结承伤修饰现在由该 Buff 的 provider:victim / evaluate:on_hit 声明进入 chorus_d2:outgoing：

| 明确的命中分类 | 当前冻结修饰 |
| --- | --- |
| weapon_damage + primary_ammo | PvE ×0.95，PvP ×0.4；当前按规则集活动模式选择 |
| weapon_damage + special_ammo 或 heavy_ammo | ×1.1 |
| ability_damage + arc / solar / void | ×1.05 |
| melee_damage + basic_melee 或 glaive_melee，且冻结 tier 属于普通 / 精英 / Guardian | +120%，在 melee/one_two_or_frozen 与 One-Two Punch 取高后，再与其他近战增量相加 |

以上标签均使用 chorus: 前缀。武器分类必须恰有一种弹药类别；基础 / 偃月分类也互斥，缺失或冲突时对应项不贡献，不从手持物猜测。普通 / 精英共享 tier 1，Miniboss / Champion 和 Boss 不获得该 +120%。一个同时有基础近战与光能技能资格的攻击按 (1 + 近战组增量) × 1.05 计算；偃月近战不会因为武器本身使用特殊弹药就自动得到 weapon_damage 资格。实际技能 / 枪械生产者仍须提供正确标签。

combat_damage.json 的有序阶段为 ability_stat → melee → perk → frozen_damage_type → outgoing_debuff；冻结类别项按 PRODUCT 合并，随后继续目标防御和护盾 / 原版结算。一次世界受击只结算一次；实际损失而非未缩放输入进入碎冰阈值。攻击快照不捕获目标冻结，未来每次命中读当前目标的 Freeze、tier 与来源；原冻结者仍出现在计算贡献追踪里，伤害 / 击杀信用不因此转移。

6 项 FreezeDamageTest 与 3 项 FreezeDamageGameTest 验证分类、等级、与 Woven Mail 的顺序、捕获后状态变化和实际扣血；新增 OneTwoPunchGameTest 从真实霰弹命中进入冻结目标的两次近战：基础 10 的第一次按较高词条得到 25，词条消费后第二次仍由 Freeze 得到 22。这里验收的是上述可追踪内容政策，冻结近战实测冲突、原作完整近战分类及混合 PvE / Guardian 环境中的主武器政策仍待校准。

[Bungie 9.7.0.1](https://www.bungie.net/7/en/News/Article/destiny_update_9_7_0_1)修复过 Howl of the Storm 的 Boss 冻结类型和 Celestial Nighthawk 对冻结 Boss 的异常增伤，不能将旧异常固化为通用规则。Stasis Crystal 碎裂、实际 Slow / Freeze 技能与武器生产者、Shatter 对势不可挡的眩晕、持久化和正式 HUD / 视觉仍未完成。

#### Breakout：职业技能替换与延迟生命支付

原表长冻结说明明确替换职业技能，但生命消耗写为未知值。[Bungie 3.3.0](https://www.bungie.net/7/en/News/article/50599) 允许空中启动挣脱；[2020 年初始说明](https://www.bungie.net/7/en/News/article/49833)与[3.0.0.3](https://www.bungie.net/7/en/News/article/49861)只证明历史上 Resilience 影响费用、曲线曾调整，不能据此断言当前 Health 属性公式。来源链接、核对短摘录与边界保存在 [Breakout 资料](../data/d2-research/2026-10-11/freeze-breakout.json)。

接收者显式绑定带 `chorus_d2:breakout_calibrated` 标签的 `chorus_d2:breakout_calibration` 来源，必须提供 `health_cost: damage / health_floor: damage / breakout_time: second`。费用与时长非负，下限严格正数，非法范围由门禁在输入接受前拒绝。只有 Freeze tier 5（战员或 Super 造成的 Guardian 长冻结）将 `chorus_d2:class` 替换为 `chorus_d2:breakout`；短冻结、Roaming 的一秒冻结、战员自身冻结及未校准接收者都不提供这一替换。已有基础职业技能选择才有可替换槽位；没有替玩家补造已解锁技能。

Breakout 不携带原职业技能的能量成本或 class_ability 标签。参数通过接收者的校准 Profile 在接受输入时固定，多个校准贡献当前取最大值；推荐每名角色绑定单一校准来源。技能条件复核长冻结资格和本代未开始挣脱，直接选择该定义不能绕过这些条件。Freeze 的 ability_started 反应将本代标记为正在挣脱，之后重复输入返回 CONDITION，不重启计时或再支付。校准来源在接受后卸下，也不会改写已固定的费用与时长。

计时绑定 Freeze 的 generation。提前清除、自然到期、地面 Super 解冻都会取消待执行挣脱；同逻辑键重新冻结不受旧计时影响。计时结束时经 `spend_health mode: up_to` 支付，成功才移除当前 Freeze；缺失 / 死亡等未支付回执保留状态并解除“正在挣脱”标记，后续有效输入可重试。未知回执保留已扣生命、停止运行时，不解冻或重复扣款。支付来源是挣脱者，原冻结 / 碎冰归属仍保留原施加者。

非致死下限、up_to 部分 / 零支付、绕过护盾与伤害链及延迟后扣费，均是明确的 Chorus 内容政策，并非现版本 D2 实测结论。原作可能需要不同生命层、伤害减免或动画阶段，这些仍须校准。独立 [breakout_test_calibration.json](../common/src/test/resources/effects/breakout_test_calibration.json) 使用费用 5、最低生命 1、延迟 0.5 秒；客户端验收改用 1.5 秒，均为合成参数。8 项 BreakoutTest 和 4 项共享世界场景覆盖空中施放、技能恢复、基础能量保留、重复输入、旧冻结代次、提前 / 自然解冻、低血量及未知回执；真实客户端命令、生命同步与移动恢复另由 BreakoutClientGameTest 验收。

## Suspend 的分级状态与脱离伤害

[suspend.json](../common/src/test/resources/effects/suspend.json) 链接 continuity.json 和 combat_damage.json，以现有动作、组件、周期调度与行动门槛表达共享 Suspend。2026-10-11 再次读取[原表 Strand B11 / D11](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit?gid=1870531554&range=D11)，与固定 CSV B9 / D9 归一化一致；HTML、坐标、哈希与未知项保存在 [Suspend 来源记录](../data/d2-research/2026-10-11/suspend.json)。

| 接收者 | 模板时长 | 施加者有 Continuity | 当前动作 |
| --- | --- | --- | --- |
| Rank-and-File / Elite | 6 秒 | 8 秒 | 固定两轴并逐步抬升；拒绝新技能、武器开火、原版射击、近战和跳跃 |
| Miniboss | 3 秒 | 4 秒 | 同上 |
| Boss | 1 秒 | 1 秒 | 保留可查询的 Suspend / Strand debuff，不限制行动、不抬升；自然到期后向自身执行基础 300 Strand 伤害 |
| Guardian（实际 Player） | 2 秒 | 3 秒 | 逐步抬升并固定 Y，禁止跳跃，允许按校准上限横移和使用武器；原作速度、ADS / 其他操作限制及第三人称镜头尚未完成 |

这些是原表所列的最大默认时间，不替代各手雷 / 技能可能更短的来源时长。分类读取 inspect_entity 的实际接收者，不以世界全局 PvE / PvP 模式代替。实际 Player 优先，其后依次为明确 boss、miniboss、rank_and_file / elite 实体或类型标签；Boss 因此胜过同时存在的低等级标签。没有可用观察、死亡或没有等级的非玩家跳过，不把所有原版怪猜作普通战员。Champion 标签本身不是等级；势不可挡眩晕及其他勇士机制未实现。

通用施加模板为 chorus_d2:suspend_application，声明必填的 lift_height / lift_step（meter）和 hover_speed（meter_per_second）来源参数。可信宿主向 chorus_d2:apply_suspend 发送 actor=施加者、victim=接收者，并以 references.source_instance / bundle 指明该模板实例；同持有者的另一施加模板不会重复响应。请求先检查高度非负、步长在 (0,64]、横移上限大于零，再观察分类、查询施加者当前 Continuity，最后经 apply_status 的宿主授权。受害者或队友的 fragment 不参与，重复 Continuity 按已有 MAX 规则归约，Boss 扩展量为零。该模板是可验证的公共施加入口，真实手雷、技能与子职业尚未自动装配；不应绕过模板用 Buff 的六秒默认值代替所有分类。

内部 tier 1/2/3/4 分别表示普通或精英、小 Boss、Boss、Guardian，是此定义的控制模式枚举，不是通用抗性等级。首次授权成功后将几何与横移上限参数写入该状态的组件，随后 gained 反应启动每 50 ms 的附着抬升计时器；执行 min(step, remaining)，仅扣实际 delta_y。触顶、零位移、拒绝或耗尽高度会将 remaining 归零并停止抬升，状态继续保持已达高度。合成测试的 1.05 米高度 / 0.25 米步长 / 2 米每秒横移上限在 [独立校准夹具](../common/src/test/resources/effects/suspend_test_calibration.json) 中，不是 D2 测量；生产模板没有猜测高度和速度。

当前刷新明确采用 reset：重新从本次查得的时长计时，不把多个命中的持续时间相加；同一接收者使用同一状态实例并保留最早施加者信用和原组件。再施加不会从已抬高的位置再累加高度，也不会在移走天花板后重新启动已经归零的升空。等级在每次请求观察、控制模式随成功重施加更新；不存在自动全局敌人分类。刷新、跨来源信用、动态变级和升空恢复规则均是待原作实测的内容政策，未列为已校准结论。

Boss snap 只由 own_buff(reason=expired) 触发。显式清除不制造伤害，原施加来源卸下不会抹掉已授予状态或脱离时的原始信用。命令使用 chorus_d2:suspend_snap 逻辑伤害类型、chorus:strand / chorus:suspend_boss_snap / chorus:derived 标签和共享 outgoing Profile；保持与 Bolt Charge 的派生触发分类一致。测试宿主将逻辑类型映射为原版 generic 并验证真实 300 HP 损失，正式伤害类型、抗性与技能来源装配仍须由 chorus-d2 提供。世界伤害结果未知时保留已结束状态与实际扣血，运行时停止，不重复 snap。

10 项单元测试及 6 项共享世界场景覆盖分级时间、来源隔离、拒绝施加、真实原版射击 / 近战与运动、抬升参数、世界三 / 四 / 六 / 八秒边界、实际 Guardian 两秒到期、Boss 脱离伤害和未知结果不重放。Guardian 的原版空中移动使用 getFlyingSpeed 常量，因此这里通过通用 horizontal_speed_limits 约束 X/Z 合成速度与逐 tick 位移，数值取首次施加写入的 hover_speed 组件；其他 tier 不贡献此上限。来源退出或另一来源刷新不会改写该实例已捕获的速度，到期 / 清除移除其贡献。该首位来源快照政策待原作实测；实际横移速度与加速度曲线、腰射 / ADS、Guardian 其他操作资格、第三人称、Champion 晕眩、HUD、生产技能与敌人目录、持久化仍为缺口，审阅保持 partial。

真实 Fabric 客户端另验收了同一施加者对玩家 / 远端骷髅的不同轴约束、玩家横移期间保持高度、两秒玩家状态先结束而六秒战斗人员状态仍存在，以及清除战斗人员状态后恢复下落。该验收同时检查 2 米每秒合成速度上限的客户端与服务端投影，及到期清理。使用显式测试参数，尚未证明原作移动曲线、高延迟观感、第三人称或 NeoForge 客户端已经完成。

## Bleak Watcher 的独立炮台与五连发

[bleak_watcher.json](../common/src/test/resources/effects/bleak_watcher.json) 组合投射物、构造物、行为 Buff、定时器、关系观测、伤害快照和共享 Slow / Freeze，没有炮台专用 Java 执行器。原表 Stasis B103 / D103 与 CSV B62 / D62、Glacier B39 / N39 与 CSV B32 / N32 已逐格核对；原始 HTML、哈希、坐标及待确认政策见 [来源记录](../data/d2-research/2026-10-11/bleak-watcher.json)。

| 原表项目 | 当前模板 |
| --- | --- |
| 生命与初始保护 | 150 Construct HP，首次成功发射前 67% 减伤 |
| 存在时间 | 25 秒；部署时施加者有 Durance 则 30 秒 |
| 攻击 | 每 2 秒一组，每组五枚追踪弹体，测试校准将首发至第五发分布在 0.7 秒内 |
| 寻敌 | 每枚发射前查询 35 米内当前可见的最近非友方目标 |
| Slow 层数 | 每次实际战员 / Guardian 命中 20 / 10 层；百层调用共享 Freeze |
| Slow 期限 | 战员 4.5 秒，当前 Durance 再加 4.5；Guardian 2 秒，再加 1.75 |
| 来源分类 | 原施放者的 Stasis Grenade Ability Damage；实体发射位置属于炮台 |
| 直接选择本模板时回充 | 单格，基础 175.6 秒、CES 0.625，接入当前 Grenade 属性曲线 |

当前定义可独立选择，也已通过 `cost_from: base_selection` 支持转换时消费基础选择声明的手雷账户。[转换来源](../common/src/test/resources/effects/bleak_watcher_conversion.json) 在松开时读取服务端计量的 `input_hold_time`，达到显式正数 `hold_time: second` 才把所选手雷转换成炮台；无来源、短按或直接 use 不转换。长按本身不扣费或提前投掷。双端场景已验证从真实长按转换暮域，实际扣掉共用账户的一份能量、保持暮域基础选择和常驻来源、保留冰炮台的攻击信用；转换不产生另一份独立账户。

默认 V 的可重绑定手雷键和按下 / 松开协议已接入两加载器，具体取消与重复包语义见 [输入协议](engine-data-packs.md#按下长按与松开)。原表只说明 hold，并未给出阈值；测试的 0.3 秒是合成校准。正式 Aspect / 子职业装备约束仍未实现；下面的组合来源当前显式绑定，来源按松开时的当前装配读取，不在按下时冻结。

[bleak_watcher_aspect.json](../common/src/test/resources/effects/bleak_watcher_aspect.json) includes 长按转换来源，并给共用手雷 Profile 提供两个独立覆盖。`grenade_regeneration` 首先将固有 base_rate 替换为 `1 / 175.6 charge_fraction_per_second`，再应用当前 Grenade 属性恢复倍率；`grenade_recipient_scalar` 将资源固有 gain_scalar 替换为 0.625，之后才由 `grenade_gain` 计算属性和触发来源倍率。它们使用同一优先级家族，重复来源不会累乘；不同覆盖的优先级属于内容政策。短按普通手雷同样受回充覆盖，不要求本次已经转换成炮台。

Arcbolt、Duskfield 和独立 Bleak Watcher 现统一支付 [grenade_energy.json](../common/src/test/resources/effects/grenade_energy.json) 声明的 `chorus_d2:grenade_energy`，每位持有者只有一个基础容量为 1、允许显式扩容的手雷账户。资源自身的 base_rate / gain_scalar 均为 0；所选技能的 energy_scaling 来源在优先级 0 提供原始冷却和 CES，并 includes 共用属性曲线及容量协调规则。Aspect 在同一家族的优先级 10 覆盖基准，因此更换手雷不能找回另一份独立储存的能量，也不会移除仍装备的 Aspect 覆盖。新手雷声明共用成本及自己的基准来源即可加入，不需要引擎维护技能 ID 列表。

选择事务先结算旧时间段，再原子替换常驻来源；余额不重置，后续恢复及外部 BASE / REFERENCE 回能使用新选择的基准。清空槽位保留余额，移除选择期来源，并令 Aspect 的 available 条件不成立：被动恢复和直接 BASE / REFERENCE 收益均为 0。重新选择不重新初始化账户。FIXED 回能、实付退款及完整充能沿用显式账户路径；延迟退款仍退入已支付的同一资源，不乘新 CES。参考数值先去除旧因子，再应用当前 CES；属性与触发收益保留第二段独立轨迹。此政策不改变仅有一个候选的 Threaded Spike 资源配置。

原表 Stasis 第 39 / 103 行已再次在线读取并与存档归一化一致，记录保存在来源文件的 aspect_recheck。**共用余额和空槽暂停是当前 Chorus 装配政策；原作换手雷 / 子职业的额外扣减尚未校准。** 装备来源的额外容量已由下面的 Spirit of the Armamentarium 示例接入，任意 Buff / 条件变化的自动容量投影、跨规则集存档迁移和正式子职业装配仍未完成。旧运行时仍固定旧目录；测试资源改名不会自动迁移旧存档。

额外充能的核心基础现有 `resizable` / `resize_resource`，可在同一账户扩容或缩容，不生成备用独立冷却。[官方 9.5.0 更新](https://www.bungie.net/7/en/News/Article/destiny_update_9_5_0)明确修复 Lightning Grenade 在 Touch of Thunder 与 Armamentarium 组合下被限制为两格的问题，因此引擎不设“两格”的硬上限。Compendium 当前 Exotic Armors F16、Exotic Class F17 声明额外手雷充能，而 Ophidia Spathe 的 C62 另有同时回充及未知秒数限制；它们需要不同的内容策略。Spirit 的装备来源协调已验证，完整 Armamentarium、Touch of Thunder 和联动回充仍未装配。保留绝对余额／缩容裁剪不等于这些金装的完整原作换装规则。

部署弹体在实体或方块接触点尝试生成构造物；无有效接触不生成。当前位置用接触点作为脚底，生成遭遇阻挡时保留已支付能量；落点偏移、弹跳和退款政策未作原作校准。构造物与行为期限同时捕获，Durance 后续卸下不缩短已有期限。每组第一枚直接调用发射 bundle，其余四枚由行为拥有的延迟回调触发；每枚发射前再次观察炮台存活并重新选目标。目标不合格或实际发射被拒绝时保持 67% 减伤，首个 `launch_as.launched` 回执才写入 fired 状态。该开火边界是明确的 Chorus 政策。

每枚成功发射的回调携带独立的数值参数与战员 / Guardian 两份发射时伤害快照，不再读取可能已经销毁的炮台 Buff。命中时读取实际目标类别、当前阵营和存活；仅明确非友方才执行对应伤害快照，成功且非致死后调用 Slow。Durance 在每次 Slow 时独立读取原施加者当前装备。炮台毁坏或到期取消未发射成员，已飞出的弹体继续，原施放者卸下该技能不抹去攻击信用。

原版 `chorus_d2:ability_projectile` 伤害类型加入 `bypasses_cooldown`，保证近间隔的五发分别扣血；仍走正常伤害、防御、护盾与死亡回执，没有绕过所有免疫。目标查询、追踪及命中观测均使用原 owner 的当前阵营；owner 缺失时不会把未知关系当敌人。物理碰撞到友方仍会消耗该弹体。逐发重选、视线、友方拦截、成功伤害后才 Slow、owner 失联和快照时点均需原作实测，不提升为已确认 D2 行为。

单发伤害、部署和射击的速度 / 重力 / 阻力 / 寿命、实体尺寸、第一组相位、四枚延迟及追踪转向参数都是必填校准输入；缺少参数在支付前报错。[独立测试校准](../common/src/test/resources/effects/bleak_watcher_test_calibration.json) 使用每发 1 伤害、0.25 秒首组延迟，以及 0.175 / 0.35 / 0.525 / 0.7 秒组内延迟，均为合成验收值。不要据此宣称复现了原作精确连发节奏或伤害。

10 项 BleakWatcherTest、9 项 BleakWatcherAspectTest 与 13 项双加载器共享场景覆盖真实投掷落地、150 HP / 减伤、连续实际扣血、战员五发和 Guardian 十发冻结、阵营变化、两个炮台的 25 / 30 秒独立寿命、摧毁后的在途弹体、消费共用手雷能量的转换、服务器计时的长按投掷、回充切段与 CES 恢复，以及未知世界结果保留且不重放。Aspect 单元测试还包含合成新手雷、不同接收者、重复来源、固定与参考收益、两段计算轨迹、切换及空槽无额外储能、旧成本延迟退款；共享世界场景验证真实投掷后切换仍因余额不足拒绝，以及 tick 在换技能、空槽和重新选择间分段积分。它不声称已装配 Compendium 的全部手雷。另有 AbilityInputTest 验证来源和阈值资格。定义仍放在测试资源；正式子职业目录、完整金装互动、原作参数校准、技能 HUD、美术和持久化仍待完成，覆盖保持 **partial**。

## Spirit of the Armamentarium 额外手雷充能

[spirit_armamentarium.json](../common/src/test/resources/effects/spirit_armamentarium.json) 通过普通 SOURCE 修饰为共用手雷账户增加一份容量。在线 Compendium Exotic Class D17 / F17 已与快照同坐标核对；[来源记录](../data/d2-research/2026-10-11/spirit-armamentarium.json) 保存原始 HTML、哈希、查询时间及政策边界。此处只验证 Spirit 条目的额外充能，不代表完整 Armamentarium 已完成。

`grenade_capacity` 以一份基础容量为输入，归约当前持有者的全部额外容量来源；同一 Armamentarium 家族取 MAX，其他家族再相加。重复来源取高是明确的 Chorus 政策，不能代替合法金装数量或职业限制。`grenade_capacity_binding` 在自身 source_attached / source_detached 和持有者 abilities_changed 后初始化账户、计算完整容量并 resize。选择期手雷来源与 Spirit 均 includes 此绑定，不增加专用 Java 执行器。来源批次先完整提交，旧来源的卸下反应也读取最终来源集合，因此等效装备替换不会先缩到一格而丢失余额。

装备可以先于技能选择：首次账户仍只给基础 initial=1，增加上限本身不赠送第二份能量。清空技能槽暂停回充，装备仍可保留两格容量；空槽时卸下 Spirit 也会重算并裁剪超额余额。已声明 1 / 2 阈值，上限始终由实际容量参与时钟调度。两格充满后可分别支付两次一格成本，第三次拒绝；容量不乘被动恢复率、CES 或一次性回能。独立合成来源可叠到三格，用于证明无两格硬上限，不作为 Touch of Thunder 的内容验收。

8 项 SpiritArmamentariumTest 和 3 项双加载器共享 SpiritArmamentariumGameTest 覆盖上述组合、选择切换分段回充、Codec 与声明校验。世界场景通过实际物品容器交换到独立 class_item 槽，并验证两枚暮域真实投掷、等效物品替换、保留其他容量来源和空槽卸装；容量观察触发实际治疗后故障时，已转移的物品、容量及生命保留，恢复不会重放。

定义仍位于测试资源，测试物品原型为合成输入。正式 perk 选择、棱镜 / 职业 / 解锁和金装数量限制、美术、HUD、能量持久化、原作装卸 / 死亡能量政策尚未完成。此绑定仅协调 SOURCE 与选择事务；容量受 Buff 生命周期或任意条件变化影响时，内容还需相应重算规则。通用自动投影、parallel 及完整 linked 技能装配仍未实现，审阅保持 **partial**。

## Ophidia Spathe 的联动回充需求与边界

2026-10-11 重新读取[在线原表 Exotic Armors](https://docs.google.com/spreadsheets/d/1WaxvbLx7UoSZaBqdFr1u32F2uWVLo-CJunJB4nlGUE4/edit?gid=1500097863#gid=1500097863)，原表 A91 / C91 与 CSV 快照 A62 / C62 归一化一致。它要求 Solar 近战额外次数、同时恢复全部次数，并保留一个未知秒数的近期使用例外；[来源记录](../data/d2-research/2026-10-11/ophidia-spathe-recharge.json) 保存坐标、原 HTML、哈希及未确定项。[官方 8.0.0.1 更新](https://www.bungie.net/7/en/News/article/destiny_2_update_8_0_0_1)曾明确 Lightweight Knife 额外充能因这种特殊回充方式不与 Ophidia Spathe 叠加；这是历史设计约束，不能据此确定当前全部行为。

核心现可用两个资源加 complete_recharge 表达“一轮进度完成，一起恢复全部次数”：施放只扣可用次数，回能先进入单轮进度，完成时清零进度并补满当前次数上限。合成测试覆盖先用一次、中途再用一次、两次都在同一轮末尾恢复，以及一份外部周期能量恢复全部缺口。详细协议见 [联动回充](engine-data-packs.md#联动回充的独立进度与可用次数)。测试的 2 秒周期、0.5 CES 和首次使用重置 / 再次使用保留进度都是合成政策，不是该金装的原作参数。

技能的 recharge_resource 已接通独立进度路由；原 Pugilist 定义通过真实装备、扣弹和投射物击杀将收益写入进度，完成一轮后可再次施放两次。施放中途改变基础选择时，后来的击杀使用新进度账户及其 CES；临时技能替换不改变外部回能路由，退款仍退实际支付的可用次数账户。Surplus 继续按可用次数计算，Wellspring 按可用账户资格分配后把收益送到进度；这两项已有组合单元测试。

通用完成动作进一步支持可选 amount，由完成时查询的 Profile 决定一轮恢复一份或当前全部缺口。[周期收益合成示例](../common/src/test/resources/effects/resource_cycle_yield.json) 从一开始共用两个账户，让装备 / Buff 改变产出；真实装备中途装卸不重置进度，等效物品替换不赠送能量。纯核心验证当前容量、重复来源、Buff 优先级和精确到期，两端世界验证实际施放、自然回充与未知观察结果保留。它证明这类模式切换无需制造备用技能账户；一秒周期、装卸保留进度和临时 Buff 都不是 Ophidia Spathe 的原作参数。

这些验证使用合成联动技能。Ophidia Spathe 的近期使用时窗、精确重置规则、Gambler's Dodge、飞刀击杀增伤与刷新、职业 / 近战资格及与 Lightweight Knife 的装配尚未实现。满可用次数时按槽回能返回 already_full，是当前防止隐藏储能的 Chorus 路由政策；不能据此宣称复现全部原作换装、重置或特殊退款行为。此阶段增加通用能力和已有 perk 的组合证据，不增加该金装的已实现或 partial 覆盖声明。
