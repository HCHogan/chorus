# Chorus 引擎实现与覆盖记录

目标：实现核心 Chorus 引擎，以覆盖 Compendium 全部效果为最终目标。当前阶段不缩减这个目标，也不把“可用 Java 扩展”视为已经支持某条效果。

需求基线：[rule-engine-design.md](rule-engine-design.md)、[d2-ruleset.md](d2-ruleset.md)，以及本地 2026-10-05 Compendium 快照。当前数值测试主要使用设计文档给定的验收输入，不代表所有命运 2 数值已经校准。

## 当前证据

2026-10-10：纯数值、执行器、Buff 生命周期、逻辑时间轴及首批效果 DSL 已落地；JSON 可编译成规则、条件动作分支、查询期修饰、周期效果和按通道互斥的连续生命恢复。`MinecraftEffectRuntime` 显式安装到维度后，普通原版伤害自动进入规则队列，服务器 tick 推进逻辑时间，嵌套命中随世界回执排队。攻击 / 防御 Profile、Buff 护盾层和治疗命令已接入 Fabric、NeoForge 世界流程；两端运行相同的 203 项 Chorus 场景，NeoForge 另有 10 项伤害 / 治疗阶段和装备掉落测试通过。已验证 Kill Clip / Disruption Break 修饰实际扣血、按实际 HP 损失回血，以及 JSON Cure、Restoration 示例由真实 tick 调度，连续恢复保留到期残段。完整程序已通过数据包注册表加载、覆盖和重载，管理命令可以启动运行时并绑定来源；已有运行时保留旧定义。装备槽位与词条元数据已能原子投影为来源；真实玩家物品容器、玩家 NBT 读写及死亡 / respawn 已接线。独立装备展示协议与最小配装页已接入两端；技能定义、基础选择、条件替换、成本 / 参数快照和服务端施放命令也已接入。武器原型已能为实际容器实例初始化弹药，并通过普通玩家命令接受整弹匣手动换弹；真实 tick 到期复核持握 / 存活资格并结算当前容量与储备，服务端单次开火、扣弹 / 射速与实际投射物击杀到 Kill Clip 下一发伤害已验。真实散布 / 精准射击、逐发 / 技能换弹、实体效果持久化、活动状态迁移、技能按键及子职业自动装配尚未完成，不能当成完整模组行为。

| 能力 | 状态 | 代码与验证 |
| --- | --- | --- |
| 单位、百分比基准、有序 Profile、计算轨迹 | 已有纯核心测试 | `stat/CalculationProfile`，`CalculationProfileTest` |
| SUM / MAX / PRODUCT / RESIST、嵌套分组 | 已有纯核心测试 | `stat/NumericGroup`、`Reduction` |
| 独立实例、唯一家族、按来源取高、优先级、按份数表 | 已有纯核心测试 | `stat/FamilyPolicy`；优先级与独立实例、抗性模组份数测试 |
| 释放时数值快照、目标条件延后与 on_hit 贡献合并 | 纯核心及双加载器 GameTest 通过 | `DamageSnapshot`、`captureDamage`；来源操作数冻结、逐层贡献保留、旧 Profile 固定，实际延迟扣血；已可跨物理投射物保存；来源反应选择见 ReactionSnapshot，多版本与派生继承仍待实现 |
| 命中期测量进入冻结攻击的有序阶段 | 纯核心及双加载器 GameTest 通过 | `ImpactData`、`impact_number`；逐目标距离、冻结加伤、实时 MAX / 防御 / 盾层查询及实际事实隔离；字段由内容 / 宿主提供，具体 D2 曲线仍待校准 |
| 派生伤害 / 治疗选择绑定或事件来源 | 纯核心及双加载器 GameTest 通过 | `ActionOrigin`；状态施加者与触发者分开，完整来源、数值查询、快照、击杀 / 治疗事实和信用标签独立；尚非完整 Jolt 内容 |
| JSON 数值捕获、伤害快照与延迟动作体 | 纯核心及双加载器 GameTest 通过 | `capture_value / capture_damage / damage_snapshot / after`；类型化绑定、来源生命周期、脱离来源、嵌套延迟、未来查询与实际伤害后回血；成本账本不能跨帧复制 |
| 精确 / 阶梯 / 线性查表，多项式曲线 | 已实现类型；测试覆盖部分边界 | `stat/Curve`；显式插值和边界策略 |
| Profile JSON 读写、类型分派和验证 | 已有 Codec 测试 | `stat/codec/StatCodecs`、`core/codec/TypeRegistry`；作为完整程序加载，独立定义注册表与客户端同步尚未实现 |
| 护盾分层预算、Buff 容量写集、原版溢出 | 纯核心及双加载器 GameTest 通过 | `effect/combat/ShieldPlan`、`ShieldDamage`；数据声明、FIFO / priority、免疫层、原版护甲 / Absorption、嵌套消费与破盾事实；NeoForge 额外验证前置、护甲与后置阶段修改 |
| 攻击者针对当前盾层的独立倍率 | 纯核心及双加载器 GameTest 通过 | `shield_scaling_profile / ShieldQuery / layer_tag`；逐层预算、攻击快照、当前 on_hit 分组合并及原版溢出；Under-Over 部分内容，自动分类和原作跨层校准未完成 |
| 明确因子与护盾按层排除精准增伤 | 纯核心及双加载器 GameTest 通过 | `Apply.factor / DamageBasis / excluded_attack_factors`；已求值贡献重放、固定加值 / 上限、快照、原版准入预算及层内轨迹；自动弱点判定和原作跨层校准未完成 |
| 有上限的护盾补充、正容量与 Buff 资格查询 | 纯核心及双加载器 GameTest 通过 | `ShieldRestoration / restore_shield`；现有层写回、容量上限、溢出不储存、动态上限作用域、去重、Rift 满血脉冲与 Void 阻止；连续回充另见下行，Rift 精细时序待校准 |
| 护盾层连续回充与受击延迟规则 | 纯核心及双加载器 GameTest 通过 | `ShieldRecovery / shield.recovery / own_shield`；段首积分、容量微秒边界、到期残段、来源保留、停伤重置和破盾终止；恢复 Profile 与基础生命自然恢复待完成 |
| 实际损失、原版 Absorption、确认死亡、死亡保护 | 纯核心及双加载器 GameTest 通过 | `effect/combat/DamageReceipt`、`DamageFacts`、`platform/minecraft/DamageCapture`；真实图腾成功无 death / kill，实际 death / kill 共享 ID |
| 顺序充能、收益裁剪、已支付成本返还、分段积分 | 已有纯核心测试 | `effect/resource/Resources`；并行 / 联动多充能未实现 |
| 数据资源定义、恢复 Profile、阈值事实与成本分支 | 纯核心及双加载器 GameTest 通过 | `ResourceDefinition`、`ResourceProgramTest`；幂等初始化、环境倍率、Buff 到期分段、增减跨阈值、真实 tick 与实际治疗、解绑重挂不补能 |
| 同序列成本返还、完整充能与回执额度 | 纯核心及双加载器 GameTest 通过 | `refund_cost / grant_full_charge`、`ResourceRefundTest`；分支 / 未绑定结果 / 世界等待保留额度、重复回执、不向错误账户返还、溢出消耗额度、免费 / 失败支付不生能量 |
| 按武器实例的整数弹药、有限 / 无限储备、补弹与生成 | 纯核心及双加载器 GameTest 通过 | `AmmoState / Ammunition / AmmoActions`；守恒转移、溢出保留、失败不部分扣弹、显式取整、幂等初始化、延迟 / 世界异常保留；有效基础容量已接查询期 Profile；单次开火已接入，弹药存档同步未实现 |
| 武器原型、实际容器账户及整弹匣手动换弹 | 纯核心及双加载器 GameTest 通过 | `WeaponDefinition / WeaponReload / CompiledWeapons`；普通玩家命令、接受时长快照、到期物品 / 存活校验、当前容量 / 储备结算、切枪取消、完成事实与 Kill Clip；接受的开火会中断，逐发装填 / 技能换弹和弹药存档待实现 |
| 武器单次开火、扣弹 / 射速与实际击杀 | 纯核心及双加载器 GameTest 通过 | `WeaponFire / weapons.fire / on_fire`；实际容器、普通玩家命令、每实例间隔、换弹中断、已提交成本、物理投射物 / 快照及 Kill Clip 真实链路；精准区域待实现；显式多弹丸结算见下项 |
| 显式整枪、成员弹丸与实际回执聚合 | 纯核心及双加载器 GameTest 通过 | `ShotGroups / begin_shot / projectile.shot / damage_snapshot.pellet`；每目标唯一命中、完整性、截止时间、收枪归属、物理移除与未知结果；自动 burst、精准与完整 perk 内容待实现 |
| 纯函数迁移、Frame、世界动作等待与恢复 | 已有执行协议测试 | `rule/RuleEngine`，`RuleEngineTest` |
| 球形目标查询、集合计数、可恢复逐目标执行 | 纯核心及双加载器 GameTest 通过 | `TargetQuery`、`for_each`、`IterationTest`、`TargetIterationTest`；嵌套、冻结列表、每次动作独立编号、退款额度跨迭代保留 |
| 球 / 圆柱 / 定向圆锥、方向和锚点快照、方块视线 | 纯核心及双加载器 GameTest 通过 | `TargetArea / TargetShape / WorldDirection / MinecraftVisibility`；视线后排序与截断、碰撞形状 / 流体 / 未加载路径、转身后的延迟方向；未实现碰撞箱相交与物理射弹 |
| 电弧箭扫描与最多四目标连锁 | 纯核心及双加载器 GameTest 通过 | `arcbolt.json / ArcboltTest / ArcboltGameTest`；12 米可见最近锁定、1 秒延迟、10 米逐次排除、来源保存、两种伤害 / 致死 / 取消 / 移除；投掷、冷却和完整数值仍未完成 |
| 物理投射物、参数 / 动作体捕获、逐接触观察 | 纯核心及双加载器 GameTest 通过；Fabric 客户端渲染通过 | `ProjectileFlight / ProjectileSpec / EffectProjectile`；真实技能支付后发射、扫掠实体 / 方块碰撞、精确接触点 / 法线、寿命和未知地形、来源卸下、失败不重放；支持反弹、穿透、限速追踪和接触后选敌转向；持久化仍待实现 |
| Buff 保存成员、类型化集合差分与来源独立清理 | 纯核心及双加载器 GameTest 通过 | `Targets / target_sets`、`read_targets / sync_targets / difference_targets`；真实移动、缺失、结束快照和恢复时序；身份不含距离，完整固定空间场仍待实现 |
| 固定世界位置、维度校验与延迟多次范围查询 | 纯核心及双加载器 GameTest 通过 | `PositionQuery / WorldPosition`、`capture_position`；原目标消失后仍按固定位置爆发，每次重新读取成员，错误维度和缺失位置显式返回 |
| 固定位置组件、跨事件保存与 Rift 部分内容 | 纯核心及双加载器 GameTest 通过 | `PositionResult / positions / read_position / write_position`；固定 5 米 / 15 秒场、两种恢复率、成员来源隔离、结束快照与解绑后继续；离散满血补盾已接入，施放 / 阵营保存仍待完成 |
| 显式实体观测、缺失与死亡区分 | 纯核心及双加载器 GameTest 通过 | `EntityQuery / inspect_entity`；原版 HP / 上限 / Absorption / 存活 / 玩家类别，类型化不可变回执；没有影子生命，缺失不能当成零或非玩家 |
| 来源标签数值表、条件数值与 Kinetic Tremors 部分内容 | 纯核心及双加载器 GameTest 通过 | `by_source_tag / choose / source_tag`；12 类武器的普通 / 强化门槛、直击去重、超时 / 收枪、固定点三波、触发时类别与攻击快照、冷却；完整缩放 / 衰减及来源装配未完成 |
| 相同 OpId 的回执幂等，新事件允许再次触发 | 已有执行协议测试 | 同 root 的 40 次合法重触发，以及重复 / 冲突回执 |
| 动作顺序、事实广度优先、失败保留已提交部分 | 已有执行协议测试 | 当前帧完成后再处理派生事实；已执行世界操作的回执保留 |
| 同边界批量事实、回执携带原版嵌套事实 | 纯核心及双加载器 GameTest 通过 | `ObservedFactsTest`；同次观察提交共用 root，当前规则先完成，原版子命中与受管父命中各发布一次 |
| 显式逻辑伤害批次与按批计数 | 纯核心及双加载器 GameTest 通过 | `DamageBatchTest` / `DamageBatchGameTest`；每个伤害保留 damage_id，内容共享 batch_id；独立于 root / shot / tick；覆盖实际伤害、原版描述器、延迟与物理投射物。全批增益资格和完成聚合未实现 |
| 事件武器 / 类别、Buff 获得回执投影、Bolt Charge | 独立 partial 内容及双端实际命中通过 | `event_weapon` / `event_source_tag`；生命周期 requested / credited / stored_delta / before / after；`BoltChargeTest` 16 项与共享 `BoltChargeGameTest` 5 项验证容量计数、同批限层、溢出回能与延迟中心两段伤害。未知范围与完整分类未实现 |
| Rolling Storm 装备到放电流程 | 独立 partial 内容及真实玩家链路通过 | `RollingStormTest` 7 项与共享 `RollingStormGameTest` 3 项；普通 / 强化、当前 Amplified 条件、同武器击杀、满层回能、真实开火 / 换弹到近战放电。复用现有 DSL，无专用核心分支；Amplified 标记为测试输入 |
| Buff 计时、独立到期、暂停、历史值、绑定 | 已有纯状态测试 | `effect/buff/Buffs`、`BuffsTest`；独立层、暂停取整、历史时间与强度、溢出收益、来源隔离 |
| Buff 动态规则、结束快照、到期反应 | 已有解释器组合测试 | `effect/buff/BuffRules`、`BuffRulesTest`；到期挂冷却、源被消耗后继续当前帧、满层刷新只执行一次 |
| Buff 定义 JSON、精确到微秒的时长转换 | 已有 Codec 测试 | `effect/buff/BuffCodecs`；JSON 驱动收枪暂停、读写往返和无效计时策略拒绝 |
| 外部事件前自动追赶、分段恢复、周期事实 | 已有纯宿主测试 | `rule/TimelineEngine`、`effect/EffectClock`、`EffectTimelineTest`；中途速率变化、资源阈值、70.001 ms 周期、生命周期绑定、跨时间点回执去重 |
| JSON 延迟 / 周期调度、归属和暂停恢复 | 纯核心及双加载器 GameTest 通过 | `schedule / cancel_timer / own_timer / by_buff_tier`；KEEP / REPLACE / ERROR、上下文保留、实例隔离、暂停余量、到期取消、Cure 示例 |
| 连续生命恢复、互斥通道与到期残段 | 纯核心及双加载器 GameTest 通过 | `effect/combat/Recovery`、`Bundle.health_recovery`；旧速率积分、优先级 / 取高、来源保留、暂停、被覆盖来源继续计时、实际回血与过量治疗 |
| JSON 事件规则、条件、动作、Value 的首批类型 | 已有编译与执行测试 | `effect/data/EffectCodecs`、`CompiledEffects`、`EffectProgramTest`；引用、结果先后关系、单位与来源种类校验；完整类型集合未完成 |
| 条件动作分支、布尔结果、分支内结果绑定 | 已有编译与恢复测试 | `EffectBranchTest`；嵌套分支、等待期间保留决定、分支结果不能逃逸或互串 |
| 状态施加确认、成功才消耗、刷新而不增加层数 | 已有 JSON 时间线测试 | `StatusComponentTest`、`slice.json`；致死跳过、伤害免疫仍可施加、拒绝不消耗、最后一层不重建 |
| 带单位的组件、初始化与旧监听的指定身份去重 | 已有 JSON 时间线测试 | `BuffSchema`、`update_component`；刷新保留累计值，新 generation 重置；Volatile 与 Jolt 部分内容已接入，完整来源装配及校准未完成 |
| 只读状态资格与 Volatile 示例 | 纯核心及双加载器 GameTest 通过 | `check_status`、`volatile.json`；施加击排除、逐伤害累计、阈值 / 致死引爆、目标共享冷却、来源保留及相邻目标连锁；实际技能来源与部分数值策略待校准 |
| Jolt 状态与按实际玩家损失决定中心链伤 | 纯核心及双加载器 GameTest 通过 | `jolt.json`；施加击累计 / 顺序例外、刷新、阈值、冷却、事件来源、玩家回执资格、致死阈值及相邻连锁；余量策略、眩晕、完整缩放与来源装配未完成 |
| 程序片段链接与跨片段引用验证 | 已有纯核心及内容组合世界测试 | `CompiledEffects.link`；不复制共享状态，拒绝版本 / 同名定义 / 防御选择冲突；输出完整程序，数据包 imports 与独立内容注册表未接入 |
| Voltshot 两窗口与共享 Jolt | 纯核心及双加载器 GameTest 通过 | `voltshot.json`；武器击杀、5.3 秒换弹窗、7 / 8 秒下一击、收枪保留、消费和真实 Jolt 非武器击杀；重复换弹 / 异常命中策略与装备接线仍待校准 |
| 静态来源与 Buff 的查询期数值修饰 | 已有组合测试 | JSON 规则施加 Buff 后，通过 `CalculationProfile` 算出伤害；每层独立贡献与明确资格条件 |
| 攻击 / 防御 Profile 到实际世界伤害 | 纯核心及双加载器 GameTest 通过 | `CompiledEffects.outgoing / defense`、`DamageCapture`；显式 scaling_profile、目标 defense_profile、完整轨迹、玩家委托不重算、原版门槛与嵌套命中 |
| origin/current 反应绑定 | 纯核心及双加载器 GameTest 通过 | `ReactionSnapshot`；来源规则在释放时捕获身份 / 标签，命中和击杀可在解绑后执行；当前规则独立读取新来源；多版本目录并存与 Buff 规则继承未实现；proc 排除已另行接入 |
| 世界命令执行、会话内去重、失败停留 | 纯宿主及双加载器 GameTest 通过 | `EffectSession`、`OperationLedger`；结果未知不重试，跨重启恢复未实现 |
| Minecraft 显式伤害命令、死亡确认、状态资格查询 | 双加载器 GameTest 通过 | 原版护甲、吸收、图腾、玩家死亡、格挡、免疫、取消、非致死下限及 Chorus 护盾 |
| 显式生命治疗、实际量 / 过量治疗、结果依赖 | 纯核心及双加载器 GameTest 通过 | `HealingCommand / Receipt / Facts`、`MinecraftHealingExecutor`、`HealingCapture`；生命上限、玩家、周期信号、伤害后回血与去重；NeoForge 取消 / 改量 / 重入隔离；自然治疗观察尚未接入 |
| 维度运行时、普通伤害自动分发、tick 与卸载接线 | 双加载器 GameTest 通过 | `MinecraftEffectRuntime`；原版来源归属、玩家继承链去重、嵌套回调、自动到期与故障隔离；运行时仍需显式安装 |
| 数据包程序目录、覆盖与重载、管理命令 | 双加载器 GameTest 通过 | `EffectPrograms`、`EffectCommands`；实际 `/reload`、坏包保留原目录、运行时固定旧定义、命令权限、原版伤害驱动数据规则；程序目录客户端同步 / 活动状态迁移未实现 |
| 动态来源绑定、批量替换与解绑 | 纯核心及双加载器 GameTest 通过 | `SourceChange / SourceBatch`、`own_source`；先结算旧状态、全量旧值校验、原子更新、相等无操作、旧来源快照清理、静态定时器取消与独立 Buff 保留 |
| 装备元数据、槽位 / 词条选项、持握与来源投影 | 纯核心及双加载器 GameTest 通过 | `EquipmentSchema / Loadout / EquipmentChange`；数量限制、同实例移动不重置、强化替换、切枪暂停 / 恢复、原版实际伤害与故障保留；元数据 API 仅供可信宿主使用，玩家物品由独立容器持有 |
| 玩家独立 ItemStack 容器、存档入口和死亡 / respawn | 双加载器 GameTest 通过 | `PlayerEquipment`、物品组件与玩家 Mixins；真实交换、revision、组件保留、未知定义取回、keepInventory / 消失、反应内死亡及失败后的所有权；NeoForge 死亡掉落事件另有专测 |
| 技能定义 / 基础选择、施放替换、成本 / 参数解析和命令入口 | 纯核心及双加载器 GameTest 通过 | `AbilityDefinition / AbilityLoadout / AbilityChange / AbilityUse`；source / Buff 条件替换、冲突拒绝、类型化参数 / 成本 Profile、先扣费后动作、实际回血 / tick、免费 / 退款、延迟快照和未知结果保留 |
| 装备主手 / 属性适配、完整子职业、独立场物体和移动技能 | 未实现 | 独立容器与即时动作技能入口已有；原版主手 / 枪械、技能种类、子职业 / 解锁、技能按键 / 网络与活动状态迁移仍待完成 |
| 装备网络协议与最小配装页 | 双加载器编译 / 服务端 GameTest 通过，Fabric 图形客户端测试通过 | `EquipmentPayloads / EquipmentNetworkServer / EquipmentScreen`；会话与旧展示校验、实际背包交换、移槽 / 持握、资源包配色与槽位排序 |
| HUD / 完整 D2 配装 / 技能 UI | 未实现 | 通用客户端组件 + `chorus_d2` 布局和资源；现有基础配装模板不包含人物预览、属性比较或技能页 |
| 全 Compendium 逐条定义和时间线验收 | 未完成 | 金标准只是首批用例；已固定全来源并开始人工映射，不代表全表覆盖 |

Compendium 固定来源已导入 `data/compendium/2026-10-05/`：22 张表、2073 个非空行、6666 个非空单元格已与用户工作簿核对；13 处仅有 XML 换行规范化差异，原始文本保留。`review.json` 已建立首批 37 个人工审阅条目，逐项记录验收要求、来源文本摘要、数据定义、具体测试方法和缺口；其余单元格保持待审阅。6 张 OLD 表独立标记，源单元格数不冒充效果数，也不据此生成覆盖百分比。工具及生成报告见 [Compendium 覆盖清单](compendium-coverage.md)。

代码位置均相对于 `common/src/main/java/com/imdomestic/chorus/`。纯核心测试位于 `common/src/test/java/com/imdomestic/chorus/`。两端共享的服务端场景位于 `common/src/gametest/java/com/imdomestic/chorus/test/`：`DamageGameTest`、`NativeRuntimeGameTest`、`ShieldGameTest`、`CombatProfileGameTest`、`HealingGameTest`、`ScheduledEffectsGameTest`、`RecoveryGameTest`、`EffectProgramsGameTest`、`ResourceRefundGameTest`、`AdrenalineJunkieGameTest`、`TargetIterationGameTest`、`TargetSelectionGameTest`、`VolatileGameTest`、`DamageSnapshotGameTest`、`ContinuationGameTest`、`FixedPositionGameTest`、`KineticTremorsGameTest`、`ImpactSnapshotGameTest` 与 `ActionOriginGameTest`；各加载器的 `src/gametest` 只提供注册、事件桥接及加载器特有场景。共用结构使用标准 NBT，并保留可读 SNBT 源。构建出的两端发布 jar 已检查，不含测试类、测试模组或效果夹具。

## 执行协议的具体约定

数据包根路径、最小示例及 `/chorus engine list / start / status / attach / detach / stop` 见 [加载和调试说明](engine-data-packs.md)。当前加载单位为一个完整 EffectProgram；管理来源固定 holder 和 instance，不伪造武器 / 技能归属。资源定义与恢复 Profile 已能加载并驱动账户；实际技能账户装配、状态免疫策略和表现 cue 的完整世界绑定仍需内容层提供。

- `RuleEngine<S>.transition(State<S>, Input)` 保留 `(状态, 事件) → (新状态, 动作列表)`。领域状态、事件负载、动作结果与世界命令必须是不可变数据；解释器内部临时可变集合不逃逸。
- `Start` 开始一个结算边界，`Completed` 携实际回执恢复，`Pump` 继续纯核心尚未结束的工作。一次迁移可最多走固定数量的内部步骤，超出时保存完整队列和帧，返回 `needsPump`。
- `Start(time, facts)` 接收同一世界边界已提交的一批事实：用内部 observed 事件建立共同 root，事实按提交顺序作为其子事件入队，随后派生的反应排在这批事实之后。`WorldReceipt(value, observed)` 携带动作实际回执和执行期间的原版子事实；恢复动作只读取 value，整个封套参与回执幂等比较。子事实先于该动作完成产生的事实入队，但都等当前规则序列完成后才分发。
- `Start` 与 `WorldReceipt` 还可携带不可变 committed 写集。通用引擎的纯 `Reconciler` 先校验并应用已确认世界写入，再执行规则或恢复动作；默认拒绝未知写集。Effect 领域接受 `ShieldDamage.Commit` 和已完成实际物品转移的 `EquipmentChange.Commit`。因此当前帧的下一条动作立即读到剩余盾量，装备反应看到完整新装配；对应事实仍按队列处理，写集也参与回执幂等比较。动作完成逻辑随后失败时，已经确认的变化与回执均保留。
- `Await` 把实际发出的不可变命令保存为 `Frame.pendingCommand`，完成动作通过 `Context.command(type)` 读取。回执提交护盾变化后不能重新求值旧命令参数，否则“扣盾前容量”会漂移为扣盾后的值。damage / damage_snapshot 发布事实、heal 完整命令匹配、状态资格及目标 / 位置查询确认均使用已发出的命令；推进下一动作时清除此字段，下一动作仍读取新状态。3 项新增纯核心回归及既有双端护盾场景验证此边界，治疗 offered 与 requested 可以不同，但回执携带的原命令必须完全匹配。
- **Pump 不是推迟到下一 tick，也不是按 root 截断。**宿主必须在同一结算边界继续推进，直到完成、等待世界回执或明确失败。未完成边界拒绝新的外部命令。运行保护应由宿主诊断并报告，不把工作量大自动判成内容错误。
- Frame 使用状态中稳定递增的序号，关联事件与规则实例。`OperationId = (frame, pc, invocation)`；每个动作执行最多发出一个世界命令；同一 PC 在 `for_each` 中再次执行时递增 invocation，计数保存于 Frame，等待回执期间不变。首次执行为 0。独立的一次动作展开多个世界命令仍需扩展协议，不能重用同一个 OpId。
- 当前只在一个结算边界内保留完成回执，下一个 `Start` 清理旧边界回执。相同 OpId 的相同回执无动作输出，冲突回执明确拒绝。`EffectSession` 另用 `OperationLedger` 对世界命令去重：同 OpId / 同命令返回缓存结果，不重做副作用；同 OpId / 不同命令拒绝。已退休边界的旧命令拒绝执行；当前账本只在内存中，不能保证跨重启幂等。
- 规则动作失败时保留此前领域状态和已提交世界回执，丢弃本边界剩余工作并记录失败位置及丢弃数量，不作为成功结算。世界操作结果未知时不能自动重试。
- 当前规则条件在轮到该规则时求值一次，动作继续时不重复求值；前一规则的状态修改对后一规则可见。`RuleResolver` 为每条事件返回有序的 `RuleBinding(instance, definition, scope)`，Frame 固定定义和来源 scope；绑定列表先整体校验，再分发。相同绑定仅在同一事件内去重，不限制后续事件再次触发。
- `if / then / else` 编译成只向前跳转的指令，条件在进入分支时求值一次。分支内移除条件依赖的 Buff 后再等待世界回执，恢复时仍继续原分支；PC 与已绑定结果保存在 Frame。分支内结果只对同一分支后续动作及其子分支可见，不能从外层或另一个分支读取，也不能遮蔽已有绑定；互斥的两个分支可以各自使用同名结果。

## 伤害与状态回执

- `damage` 先返回 `DamageCommand`；输入实际 `DamageReceipt` 后，才绑定数值 / 布尔结果并由 `DamageFacts` 产生事实。请求伤害再大也不推断致死；原版执行器已在 HP 写入时实施 `non_lethal`，最低保留 1 HP，原本低于 1 HP 时保留原值，不通过抬高生命制造治疗。
- applied / immune / blocked 都可产生 `hit`；只有实际护盾 + Absorption + 生命损失大于零才产生 `damage_taken`。cancelled / failed 不产生这批伤害事实。实际损失投影来自回执，不用请求值或图腾恢复后的 HP 净差替代。
- `deathId` 存在才表示 lethal，产生 `death`；有归属 owner 时再产生同一 ID 的 `kill`。`kill_tags` 由调用方明确提供，仅加到 kill，不从 weapon 引用推断武器击杀资格。没有攻击来源时仍可发 death。
- `deathPrevented=true` 与 deathId 互斥，产生 `death_prevented`，不会产生 death / kill。世界采集器在原版 `DeathProtection.applyEffects` 实际执行后记录消耗物品 ID，回执的 `protectionSource` / 事实的 `protection_source` 保留此来源；不从背包物品或最终 HP 猜测。自定义保护组件的完整快照、其他模组自身的救命机制及跨分量 DamageResult 聚合仍待实现。
- `apply_status` 返回 `StatusResult.Check` 世界资格查询，始终要求目标存活，等待 allowed / denied / dead / missing。恢复时校验回执对应完全相同的请求；仅 allowed 才在纯核心提交 Buff 并绑定 `StatusResult.applied=true`、前后层数及 generation 引用。回执 cause 保留触发事件的 damage_id。宿主必须保持这个结算边界，不能在确认和提交间插入其他战斗命令。
- `check_status` 使用相同资格协议但只返回 allowed / denied / dead / missing，不修改 Buff、不发布生命周期事实，也没有 applied 字段。默认同样要求存活；显式 `allow_dead=true` 允许仍存在的死亡目标接受宿主资格策略检查，不跳过免疫策略，也不允许 missing。dead 是此次检查的拒绝原因，不是目标死亡状态的独立投影。检查不预留未来施加资格；后续 `apply_status` 仍须重新检查，回执也不能修改原请求的 allowDead。
- 当前帧可以先按 damage / status 的结果分支，派生的 hit / death / Buff 生命周期事实仍在帧完成后按广度优先处理。重复相同 OpId 回执不会重复扣层或发事实，冲突回执明确失败。

## 已接入的 Minecraft 世界边界

`EffectSession` 组合纯 `TimelineEngine` 与世界执行函数。在同一服务器线程、同一结算边界内循环 Pump → 世界请求 → 实际回执，直到空闲或失败，不把内部预算变成下一 tick 延迟或连锁上限。每次进入世界前先保存纯核心已提交状态；执行异常时保留 pending 帧，账本保留结果未知标记，不继续后续动作、不自动重试。宿主仍拒绝重入的外部 Start；`MinecraftEffectRuntime` 通过批量观察输入和世界回执封套接收原版回调，不递归调用 Start。

`MinecraftEffectRuntime.install` 显式安装一个维度运行时，固定规则集、初始状态、速率解析与世界绑定。Fabric 的 `END_LEVEL_TICK`、NeoForge 的 `LevelTickEvent.Post` 推进已安装运行时；卸载维度和服务器停止时移除注册。未安装运行时的维度不会自动装配规则。逻辑时间以安装时的状态时钟为起点，按维度 `gameTime` 每 tick 增加 50,000 微秒；普通伤害进入前先追赶到期反应，当前世界动作内部的回调保持该动作的逻辑时刻。`chorus:tick` 当前携空负载，内容不能把它当成具有 actor / victim 的战斗事实。

`MinecraftWorldActions` 实现当前 damage、heal、状态资格查询、目标集合查询、位置捕获、实体观测和 cue 世界命令。宿主显式提供实体引用解析、DamageSource 映射、状态免疫策略与 cue 实现；不存在 / 已移除 / 跨维度的目标不会被静默替换。状态检查区分 missing / dead / denied / allowed，实际 Buff 仍由纯核心收到回执后提交。原版伤害是否免疫与状态能否施加是两个条件。

`MinecraftDamageExecutor` 走目标的原版 `hurtServer`，保留护甲、附魔、格挡、无敌帧与加载器取消逻辑。边界转换为原版 float，超出 float 范围在执行前报错。`DamageCapture` 与三个 common Mixin 在显式请求或已注册维度的原版伤害作用域内采集：

- 记录实际每次减少的 HP / Absorption，恢复不从损失中倒扣；因此图腾恢复不会掩盖这一击已经扣掉的生命。Chorus 护盾损失与逐层轨迹单独记录，不能用输入伤害减溢出来替代容量损失。
- 原版确认死亡并广播死亡实体事件时记录 death；覆盖 LivingEntity 和不调用其 die 的 ServerPlayer。图腾成功单独记录，不以 HP 曾经归零触发死亡。
- 免疫查询、抗火阻断和物品格挡有明确采集点；加载器取消及其他无损拒绝保持 cancelled，不把未知拒绝原因猜成免疫。普通原版伤害流程保持不变；非致死约束只作用于对应显式请求。
- ThreadLocal 的请求 / 调用栈在 finally 中退出。ServerPlayer → Player → LivingEntity 的父类委托共享一次采集，真正重入的原版 hurt 则创建独立作用域，即使打同一目标也不混进父命中的损失。原版子命中先收集回执，最外层 hurt 结束后一起投递；由 Chorus 世界动作触发时附在 `WorldReceipt` 中。受管父命中由动作完成逻辑发布，采集器不再重复发布它。
- 原版来源映射默认保留攻击者 UUID、直接来源 UUID（无直接来源时用伤害类型）与原版 damage type，仅标记 `chorus:native_damage`。weapon / ability、kill_tags 与 scaling_profile 留空；宿主 `NativeSource` 可以明确绑定攻击 Profile，不能从命中时的手持物品猜武器归属。目标防御 Profile 不依赖攻击是否声明 scaling_profile。
- 原版伤害过程抛异常时继续向原版调用方抛出，运行时保留异常前已完成的子命中批次，停止规则推导；不能把部分伤害伪装成零损失 cancelled。规则或世界动作失败也停止后续推导，世界结果未知的操作保留 pending，绝不自动重试。此后原版伤害仍正常执行；运行时只累计跳过的事实数量，避免无限积累新历史。当前没有自动恢复或完整故障回放接口。
- 同一套 GameTest 已分别运行于 Fabric 与 NeoForge 服务端，实际加载 common Mixin、各自的护盾 Mixin 与事件接线。其他模组改写伤害来源、替换完整 hurt / die 实现的兼容性仍需单独验收。

当前持有者状态仍由调用方传入，维度运行时注册表已接入；实体附加数据、装备 / 数据包自动绑定、持久化和断线恢复尚未实现。当前原版观察覆盖经过上述 hurt / die 链的活体伤害；不经过这些路径的特殊实体覆写、直接销毁以及跨维度嵌套回调顺序需要另行适配或验收。

## 治疗命令与回执

`chorus:heal` 接收 target（默认 self）、单位为 damage 的非负 amount 及 tags；单位在这里沿用生命 / 伤害的相同尺度，不把治疗当成资源充能。动作等待真实 `HealingReceipt`，结果字段为 requested / offered / effective / overheal，布尔字段为 applied / changed / overhealed。例如按前一条伤害实际扣血治疗自己：

```json
{
  "action": {
    "type": "chorus:heal", "target": "self",
    "amount": { "type": "chorus:result", "binding": "hit", "field": "health_loss" }
  },
  "as": "healing"
}
```

- 执行器走真实 `LivingEntity.heal`，遵守原版最大生命值及 NeoForge `LivingHealEvent` 的取消 / 改量。不存在、移除或跨维度目标返回 missing，死亡目标返回 dead，不复活实体；零请求返回 rejected，不发布治疗事实。治疗不直接改变原版 Absorption 或 Chorus 护盾。
- requested 是原始 double 请求；offered 是原版 heal 内部调用 setHealth 时实际提供的正增量，已含加载器改量及 float 精度。effective 是这次直接生命写入的实际正增量。overheal 是 offered 超出该次写入前可用生命容量的部分；不能把加载器拒绝的量算成过量治疗，也不强求 requested = effective + overheal。其他模组若进一步拦截 setHealth，也可能让 offered 与 effective + overheal 不相等。
- `HealingCapture` 只采集 heal 自身调用 setHealth 的位置，按调用栈隔离嵌套治疗。回调中先发生的伤害不会从治疗量中倒扣，嵌套原版或显式治疗不会混进父治疗。作用域在 finally 中清理；异常继续抛出，世界操作账本保持结果未知且不自动重试。
- 已接受的正治疗发布 `chorus:heal`；实际回血大于零再发布 `chorus:health_restored`，容量溢出大于零再发布 `chorus:overheal`。这些事实共享 heal_id、来源、目标、tags 及四个数值。满血时可以有 heal / overheal，但没有 health_restored；拒绝、missing、dead 不发布这批事实。当前帧的结果依赖动作先执行完，再按既有事实队列处理反应。
- 回执保存实际执行的 HealingCommand；完成动作校验目标、来源和标签，不在嵌套世界回调已经改变领域状态后重求 amount。相同 OpId 的回执和世界操作沿用既有幂等协议。

`healing.json` 为通用机制样例，验证伤害回执的 health_loss 驱动治疗、结果绑定及实际回血反应，不主张任何具体吸血词条的参数。双加载器另验证 70.001 ms 逻辑周期信号执行真实治疗；`periodic.json` 已用 schedule 把周期创建、刷新保留和暂停恢复也接到 DSL。普通自然恢复 / 其他模组治疗的自动事实观察、治疗 Profile 尚未接入；离散补盾另用 restore_shield，不混入生命治疗。

Compendium 快照 Solar D4 的 Cure 有 0.1 秒恢复过程及 1 秒激活冷却。`cure.json` 已用隐藏冷却 Buff + 两次定时 heal 表达 x1 / x2 / x3：快照总量为每级 60 [PvP 30] HP，测试内容采用 0.1 的 Minecraft 缩放，分别在 50 ms、100 ms 恢复一半；冷却从激活时开始，期间重复请求不加血、不延长冷却。**两次等量脉冲是 Chorus 的实现选择，快照没有提供原作更细的恢复曲线**。这是由明确输入驱动的可执行内容示例，尚未接入凤凰俯冲等真实技能 / 装备来源；不声称原作完整体感已经校准。

### 连续生命恢复

来源或 Buff 的 bundle 可以声明 `health_recovery`，作用于自身持有者。rate 的单位为 `damage_per_second`，可以按当前 Buff 强度、层数、组件或 PvE / PvP 模式取值；它是无应用事件上下文的纯查询，不能读取一次旧命中的 event_number。负数、非有限值、错误单位、重复局部 id 和未知字段均拒绝。

```json
"health_recovery": [{
  "id": "restore", "channel": "example:healing", "priority": 0,
  "rate": { "type": "chorus:constant", "value": 3.5, "unit": "damage_per_second" },
  "tags": ["example:restoration"]
}]
```

- 对每个持有者、每个 channel 先选择最高 priority，再取最高正速率，同值按稳定来源标识选择。不同 channel 分别结算、分别保留来源；被覆盖的来源继续计时，不暂停或重置。零速率不提供治疗，也不产生恢复采样边界。
- 每段 `[from, until)` 使用段首状态计算 `rate × (until-from)/1_000_000`。存在恢复来源时，逻辑时钟增加对齐到 50 ms 网格的采样边界；Buff 到期、外部事件或其他已有边界可以提前分段。50 ms 是 Chorus 的世界治疗采样选择，不是 Compendium 声称的原作恢复间隔。到期残段照常结算，暂停期间不累计；恢复来源切换或删除前须经时间轴追赶。
- `Recovery.Batch` 保存获选来源、被覆盖候选与每段分配。先提交该边界的新领域状态，再执行已分配的各个 HealingCommand；所有命令回执完成后，按队列分发治疗 / 原版嵌套事实，再分发该边界的 Buff 生命周期、资源变化与定时信号。反应读取的是边界后的当前状态；已到期来源仍保留它在过去区间挣得的治疗量，但不会继续参与下一段查询。
- 每个治疗命令使用独立 OpId，校验完整回执命令，沿用重复回执去重与失败停留。暂停、到期或后续回调不会重复积分已经提交的时间段；失败不自动重试世界操作。满血、被取消或无法治疗的量不会存成以后可用的恢复额度。
- 生命上限、真实 effective / overheal 仍由原版执行器决定；double 积分不能消除原版生命值的 float 精度。恢复仅作用于生命，不创建护盾、自然恢复状态或空间场；恢复速率的 Profile 装配、伤害打断 / 延迟通道、独立层恢复归因仍待补齐。

`restoration.json` 使用 Solar D7 的 x1 / x2 = 35 / 50 [PvP 17.5 / 25] HP/s，以及 Class Abilities D15 的 Healing Rift = 40 [PvP 35] HP/s；测试缩放为 0.1。已接入历史最长持续时间、保留最高强度、互斥恢复与原版治疗，真实服务器 tick 验证了 70.001 ms 到期的最后 20.001 ms。**快照只明确二者不叠加，示例同优先级取高为暂定选择，尚未验证原作覆盖优先级。**凤凰俯冲的 4+2 秒例外、真实技能触发、Rift 脉冲护盾的精细时序仍待校准；固定场进出与补盾另由 healing_rift.json 组合验证，不能视为完整技能验收。

## Profile 与实际伤害的连接

`chorus:damage` 可声明 `scaling_profile`；`EffectProgram` 顶层可声明 `defense_profile`。两者必须引用当前版本中已存在、输入和输出均为 damage 的 Profile，缺失或单位错误在编译规则时拒绝。已有无 Profile 的伤害动作保持原有行为；带显式攻击 Profile 的世界命令在没有安装维度运行时时拒绝执行，不能静默忽略它。

```json
{
  "type": "chorus:damage",
  "amount": { "type": "chorus:constant", "value": 16, "unit": "damage" },
  "damage_type": "minecraft:mob_attack",
  "scaling_profile": "example:weapon_damage",
  "tags": ["example:kinetic_damage"]
}
```

已实现的阶段顺序为：

```text
基础 amount → 攻击者 scaling_profile
  → 原版 / 加载器取消、免疫、物品格挡、难度处理与无敌帧差额
  → 目标 defense_profile → Chorus 各护盾层倍率与扣除
  → 原版护甲、附魔、药水、Absorption、HP 与确认回执
```

- 攻击查询收集 `command.source.owner` 持有的来源和 Buff，防御查询只收集 `command.target` 持有的来源和 Buff；因此目标的攻击增益和攻击者的防御 Buff 不会串入另一端。目标易伤在防御 Profile 中分组；攻击者输出削弱在所选攻击 Profile 中定义。命中 query 保留 source、tags、damage_type，以及该阶段输入的 `incoming_damage`。精准、距离、目标等级等更多上下文字段尚未自动装配。
- 外层 `hurtServer` 进入时计算攻击 Profile 一次；ServerPlayer → Player → LivingEntity 的父类委托不重复缩放。真正嵌套的新命中有独立查询和轨迹。无敌帧先比较缩放后的输入，仅超出上一击的差额进入防御查询，不重复应用攻击倍率。
- 防御查询与扣盾共用护甲前接入点，且只处理正输入。取消、免疫、无敌帧拒绝，以及物品完全格挡后的零输入均不进入防御；Profile 中的加值、替换或曲线不能把被完全格挡的伤害重新抬高。正输入被防御 Profile 降至零返回 blocked，不扣盾、不生成 damage_taken。
- `DamageReceipt.outgoing / defense` 分别保留可选 `CalculationProfile.Result`，包括 Profile 版本、每阶段输入输出、贡献来源与选中情况。未走到的阶段保持缺席，不能用零伪装成已经计算；HP / Absorption / 盾损仍取实际世界回执，不取 Profile 输出。运行时把有限非负结果转换为原版 float，超出范围在继续该伤害阶段前拒绝。
- 查询使用已追赶的状态以及本次边界已消费的临时护盾视图，不引入新的效果副作用或反应事件。显式攻击快照已接入此路径；更多原版投射物适配、复合攻击聚合、生命层专属 Profile、穿透层策略或自动规则集装配尚未完成。

`combat_profiles.json` 复用已有 Kill Clip 25% 与 Disruption Break 50% 定义，另加明确标为测试参数的 50% 减伤与 5 点盾。纯测试和两端真实世界测试验证 `16 → 20 → 30 → 15 → 扣盾5 → 10 → 护甲后4 → Absorption扣3 → HP扣1`。Buff 由测试宿主预置，不能据此声称完整击杀 / 换弹 / 破盾触发来源已自动装配。

## 派生动作的来源与信用

`ActionOrigin.BOUND / EVENT` 用于 damage、capture_damage 与 heal 的可选 `origin`，默认 bound 保留旧行为。BOUND 读取静态来源 / Buff 施加来源，EVENT 读取触发 EffectEvent 的完整 source；环境事件的空 owner 原样保留，没有可用事件时明确失败，不回退到施加者，也不拿 event.actor 替代 source.owner。

这解决“甲施加状态，乙触发派生伤害”的归属表达。它不改变规则 self、组件键、来源条件或动作参数 Value 的作用域；Buff 的原施加者不变，目标与查询关系也单独声明。新 DamageCommand 的 source.owner 决定攻击 Profile 从谁收集修饰；来源身份、tags / kill_tags 和 scaling_profile 仍彼此独立，event 来源的武器身份不自动带来武器伤害或武器击杀信用。

capture_damage 在捕获时选择来源并冻结对应攻击者的 on_use 修饰；damage_snapshot 不能重写来源。普通 after / schedule 中的 event 仍指其保存的原触发事件，后续新命中不覆盖它。伤害 / 治疗完成使用已发出的命令，事实中的 actor / source 与新动作一致。内置管理运行时按 command.source.owner 构造原版 DamageSource；自定义世界执行器也须遵守这份归属。来源不在线时，Chorus 的标识仍保留，但原版实体解析、阵营查询和“要求在线才触发”的策略不是本次实现范围。

[action_origin.json](../common/src/test/resources/effects/action_origin.json) 是合成状态反应，不是 Jolt 数值定义。6 项纯核心测试验证多次换人触发、默认 / 显式 bound、trigger 的数值快照、状态到期 / 来源解绑、定时和嵌套延迟、完整来源与 actor 区分、环境伤害、错误值及 Codec 往返。2 项共享 GameTest 以真实原版攻击触发范围伤害、死亡和治疗，验证原版 DamageSource、数值贡献、实际损失、事实归属及不自动继承武器信用；另验证状态到期后仍由原触发者快照实际扣血。

需求依据是 Bungie [7.1.0 更新中的 Jolt 归属修正](https://www.bungie.net/7/en/News/Article/season-deep-update-7-1-0)：放电伤害归触发放电的攻击者。该条官方历史说明证明引擎需要独立选择来源，不能据此推断当前 Jolt 的全部数值缩放。Compendium Weapon Perks C243 另明确 Voltshot 的 Jolt 不算武器伤害。独立的 Jolt 部分内容已验证施加、115 / 45 阈值、0.8 秒冷却与玩家自身伤害资格；完整缩放、勇士眩晕及真实来源装配仍待完成，见下文。

## 受管伤害的回执消费

`EffectProgram.Buff.consumeOnDamage` 声明 hit / effective_damage 消费条件与层数，`CompiledEffects.prepareDamage` 只读捕获当前攻击者的 Buff 键 / generation。`Action.Damage` 与 `DamageCaptured` 将候选资格附在实际命令上；`BuffConsumption.finish` 在回执完成时更新 Buff 与生命周期信号，早于下一条指令，但不抢跑广度优先的 hit 反应。取消 / 失败保留资格，未知结果保持待确认状态；重新施加的新 generation 不受旧回执影响。

5 项 DamageConsumptionTest 验证两次串行伤害与快照复用只增伤第一次、hit / 有效伤害的资格差异、未知结果、generation 隔离和只读查询。3 项共享 DamageConsumptionGameTest 验证实际连续扣血、缺失目标后第二击仍增强，以及伤害已发生但回执未知时停止后续动作。实例 modifier 必须 on_hit，不能由冻结贡献延续已消费效果。

当前支持受管 damage / damage_snapshot；原版观察入口自动消费、原版嵌套伤害预留与多分量共用一次资格仍待实现。One-Two Punch 已另行接入该策略并验收受管近战，仍为 partial；该通用能力不自动改变 Voltshot 的内容状态。[字段和边界](engine-data-packs.md#伤害回执时消费-buff)。

## 攻击数值快照

`CompiledEffects.captureDamage(state, attack)` 在已追赶的纯状态上产生不可变 `DamageSnapshot`；`MinecraftEffectRuntime.captureDamage(attack)` 先追赶服务器逻辑时间，再读取当前状态及本边界已经消费的临时护盾视图。宿主保留这份数据，在未来命中时用 `snapshot.command(target)` 指定实际目标，通过原生来源适配或显式世界命令执行。捕获时提供的 target 不参与取值；基础伤害、归属、伤害类型、信用标签、non_lethal 和 Profile 固定，命中命令不能篡改这些字段，也不能把已捕获命令再捕获一次。

modifier 新增 `evaluate`，当前支持 `on_use`（默认）与 `on_hit`。未携带快照的伤害仍执行即时查询；目标防御 Profile 始终按命中时当前状态求值，evaluate 字段只划分攻击快照的取值时机。

- `on_use` 在捕获时决定哪些来源、Buff generation 和独立层参与。来源侧的资源、Buff 层数 / 组件、强度、强化和活动模式被绑定为常量；来源消失、Buff 到期或随后获得更高层数都不会改写它们。
- `victim` 的资源、Buff 层数 / 组件和条件保留为表达式，在每次命中时读取该目标。复合条件与算术只绑定来源部分，例如“释放时资源 0.3 + 命中时每层目标标记 0.05”。快照不保留 EffectState、实体或计算闭包。内建表达式执行部分求值；自定义 Value / Condition 必须显式实现 `snapshot`，未知依赖不会被猜测。
- 事件标签、引用及 `event_number` 按释放上下文绑定，当前捕获 API 提供 incoming_damage 与 damage_type。命中时才能知道的量使用独立 `impact_number`：它在 on_use 快照中保持符号形式，命中时读取此次命令的 ImpactData；因此不必把整个距离修饰改成 on_hit，也不要求发射来源仍装备。未提供的精准、距离或其他测量不会自动补零；世界接线须明确提供字段。
- 命中时只从当前攻击者持有的来源 / Buff 收集显式 on_hit 贡献，与冻结和延后贡献一起进入原 Profile；on_use 贡献不会再次收集。每层仍有独立身份，MAX / SUM / PRODUCT 不被提前压成最终倍率。
- 可选 shield_scaling_profile 也被固定为独立 multiplier Profile。layer_tag 保留到实际盾层查询；目标 has_shield 使用当前规则目录，来源侧条件仍在捕获时固定。每层结果单独记录攻击侧轨迹，具体预算语义见下文。
- 快照固定旧 Profile 和延后表达式所需的定义。替换运行时后，旧攻击可以继续使用旧 Profile，防御使用当前版本；新版本的 on_hit 贡献必须具有相同的输入单位和完整阶段 / 分组结构，否则明确拒绝。计算轨迹逐贡献保留所属目录版本，包括与旧 Profile 一同归约的新版本 on_hit 贡献。模式改变、命中早于捕获也拒绝。这里没有实现活动状态迁移或 origin_bundle 反应规则的多版本共存。

[damage_snapshot.json](../common/src/test/resources/effects/damage_snapshot.json) 以合成参数验证：来源 20%、冻结资源 30% + 目标标记 5%、命中期 40% 在同一 MAX 中选择；基础 10 得 14，而不是把三项相乘。10 项纯核心测试覆盖不同目标重放、条件冻结、逐层贡献、模式 / 元数据错误及目录版本边界；3 项共享 GameTest 验证来源到期后延迟扣血、命中期新增增益及替换运行时后的旧攻击 / 新防御。延迟用例使用独立 test_environment 批次，避免跨 tick 的运行时与同维度其他用例争用。

DSL 已支持 `capture_damage` 绑定类型化快照，再通过 `damage_snapshot` 应用到指定目标；`after` 和显式物理 projectile 可以保存此绑定。Java 宿主也可显式保存并传回快照。原版箭或其他技能实体不会自动附加释放时快照。来源规则的 origin_bundle / current_owner_bundle 已接入下述反应选择；派生物筛选继承、Threadling 丢失继承、on_release / on_tick / on_proc 和持久化仍待实现；proc 排除已独立接入。Compendium `Artifact Perks!E15` 的 Singularity Blade 描述的是命中期施加状态的资格，不是数值修饰；本测试不能冒充该 perk 的完整实现。

### 释放时的来源反应绑定

`ReactionSnapshot` 只保存不可变的程序定义和 EffectSource 列表，不持有 EffectState、世界对象或执行闭包。规则 `binding` 默认 current_owner_bundle，显式 origin_bundle 只允许来源作用域的伤害事实。captureDamage 在释放时捕获；prepareDamage 在即时请求前捕获；原版观察 describe 也捕获即时选择。DamageFacts 将同一份选择带到命中、承伤、护盾与死亡 / 击杀事实。

解析器先加入当前规则，再加入捕获的 origin 规则，后者不会同时出现在当前索引。捕获来源可在解绑 / 替换后执行，并保留强化标签；命中时条件与 Buff 查询仍读取当前状态。空选择不会吸纳飞行后新加的词条。武器身份、伤害信用和来源选择仍彼此独立。

10 项 ReactionBindingTest 验证解绑后命中 / 击杀、单次执行、改装 / 新增词条、空选择、其他持有者 / 武器隔离、即时 / 延迟 / 投射物路径、同 version 不同内容的目录拒绝、取消 / 未知结果、命中期条件和 Codec 限制。4 项共享 ReactionBindingGameTest 验证真实投射物卸装后治疗与击杀收益、改装后的旧强化 / 新当前规则、原版直接伤害入口，以及治疗已经发生但回执未知时不重放。

多版本反应目录尚未并存；非空捕获选择要求完整程序相等，受管请求在进入世界前拒绝不兼容内容。运行时停止仍停止旧实体，派生动作默认捕获新来源；Buff 规则与来源继承筛选 / 丢失另待实现。没有把任何现有 D2 perk 自动切换为 origin_bundle，也不因此增加 Compendium 效果覆盖。[字段和限制](engine-data-packs.md#保留攻击释放时的反应规则)。

### 内容声明的 proc 排除与继承

`ProcPolicy` 是独立于归属、信用、数值与来源绑定的不可变排除集合。规则可声明 proc_key，编译后的条件入口先检查策略再读条件；来源、origin_bundle 和 Buff 规则使用同一入口。damage / capture_damage 的 proc Spec 默认 fresh，显式 event 继承当前事件的排除并添加自身 deny；damage_snapshot 保持捕获值且禁止改写。

10 项 ProcPolicyTest 验证三类规则和击杀反应的筛选、标签 / 信用独立、被排除规则不读取缺失测量、fresh / event、即时 / 延迟 / 投射物、只读数值不受影响、同 root 重复触发、未知结果、事件 / 定时上下文及严格 Codec。3 项共享 ProcPolicyGameTest 验证实际致死与合格治疗、释放后解绑仍保留排除，以及原版入口的策略保留和恢复默认允许。

Jolt 的四处链伤动作明确排除 chorus_d2:bolt_discharge；新增的纯核心及共享世界场景在两种模式下验证原生触发击可到达接收探针，实际中心 / 邻居链伤仍执行且可触发其他探针，只有放电探针被排除。该探针没有实现 Bolt Charge 的层数、武器命中门槛或实际放电，不能算完整效果覆盖。来源与边界见 [Jolt 规则集](d2-ruleset.md#jolt-的归属与施加顺序)，DSL 见 [显式排除后续触发](engine-data-packs.md#显式排除后续触发)。

### 命中期输入与阶段内衰减

下述测量与 proc 策略相互独立；排除后续触发不会改变这些数值。

`DamageCommand.impact` 与 `EffectEvent.impact` 保存不可变的 `ImpactData(Map<String, Measure>)`。`snapshot.command(target, impact)` 允许每次命中提供不同输入，冻结的基础伤害、来源、标签和 Profile 保持不变。captureDamage 拒绝非空 impact；旧构造入口默认空映射，未使用命中测量的既有内容继续工作。

普通 `damage` 与 `damage_snapshot` 都可声明 `impact: {字段名: Value}`，发出命令时求值一次。例如循环目标的 distance 可明确映射为命中字段；查询后移动不重读距离。修饰中用 `{"type":"chorus:impact_number","name":"distance","unit":"meter"}` 经 curve 转为 delta，并放入 Profile 声明的 falloff 阶段。冻结快照只保存候选贡献及来源操作数，包含 impact_number 的值或条件留到各次命中求值。on_hit 贡献、当前防御和护盾层表达式也收到同一份输入。

DamageFacts 在 hit / damage_taken / shield_damaged / shield_broken / death / kill 等实际事实中携带这份输入；实际 effective_damage、health_loss 等仍由 DamageReceipt 生成，两个映射隔离。把 impact 中某个字段也命名为 effective_damage 不会覆盖实际损失。emit、schedule 和 after 保留原事件中的 impact，不会在之后偷偷重测世界。

[impact_snapshot.json](../common/src/test/resources/effects/impact_snapshot.json) 使用合成参数验证 `(10 + 5) × 1.2 × falloff(distance)`：0 / 3 / 5 / 7 米分别得到 18 / 18 / 9 / 0。8 项 ImpactSnapshotTest 覆盖来源移除、逐目标求值、条件延后、on_hit MAX 与防御、盾层预算、数据往返、缺失 / 单位错误、输入与结果隔离。2 项共享 GameTest 验证来源卸下和增益到期后的真实延迟爆炸、查询后移动，以及宿主提供测量的原版命中路径。新增 live 40% 在 MAX 中选择后，5 米出伤为 10.5，再经当前 50% 易伤实际扣 15.75。

字段名非空、值有限且带单位；缺失字段或读写单位不一致明确失败。尚无完整事件 / impact 字段注册表，编译器能检查表达式单位，但不能静态证明所有宿主都会提供字段；不能把运行时缺失变成零。ImpactData 仅承载显式数值，不推断精准、空间距离或目标种类，也不等于已实现实体碰撞、距离 / 爆炸曲线校准或完整 D2 伤害分量。

## 延迟动作与显式捕获

`{ "after": 秒数Value, "lifetime": "source|detached", "do": [步骤] }` 创建一次未来执行，当前序列随即继续；它不让当前 Frame 睡眠。delay 必须为正、有限且能精确表示为整数微秒。嵌套 after 从执行到它的时刻计算延迟。相同截止时刻按调度时的 Frame / PC / invocation 排序，多次施放互不覆盖。

- `source` 是默认生命周期：静态来源解绑 / 替换或所属 Buff generation 结束时取消；Buff 暂停冻结剩余延迟，恢复后继续。Buff 到期与任务同刻时先到期，任务不执行。已不存在的来源不能再创建 source 任务。
- `detached` 显式脱离来源生命周期，保留原始归属，来源解绑、Buff 到期或暂停都不取消它。尚未提供这种任务的单独取消句柄，也没有跨重启恢复。
- `EffectContinuations.Pending` 保存定义 ID、版本、原来源 scope、调度事件及当时可见的不可变结果。执行时创建新 Frame 和 OperationId，表达式看到原事件内容及新的逻辑时间；原事件身份另留作因果证据，不保留 Java 调用栈或可变世界对象。当前目录版本校验不等于活动任务迁移。
- 外层结果在调度时复制到新帧；分支、循环目标及伤害快照都保留各自类型。延迟体局部结果不能逃逸或遮蔽外层绑定。循环内 after 分别捕获每轮目标；在 after 内查询则读取未来的世界成员和距离。实体身份快照不等于空间坐标快照，固定位置须先通过 capture_position 显式捕获。
- 普通状态 Value 仍在执行时读取。`capture_value` 在调度前把某个 Value 求为带单位的 `value` 结果，可显式保留 Buff 结束前的层级 / 组件等操作数；after 不会自动冻结所有状态。`capture_damage` 另保留完整攻击快照，要求明确 scaling_profile；`damage_snapshot` 返回实际伤害回执，可接分支、回血和后续延迟。
- 付款 / 退款结果与累计认领属于原帧，编译器不把这些绑定带入延迟体，Pending 也拒绝 RetainedResult；未来帧内可独立付款。复制 paid 的普通数值不获得退款权，跨事件成本账本仍未实现。

[delayed_snapshot.json](../common/src/test/resources/effects/delayed_snapshot.json) 使用合成参数。11 项纯核心测试覆盖生命周期、暂停余量、词法捕获、14 次同刻施放排序、嵌套相对延迟、单位校验和旧回执边界；4 项共享 GameTest 验证 JSON 捕获后解绑与增益到期仍实际扣血、按实际损失治疗、默认取消、未来范围查询以及两次独立延迟命中。宿主只执行通用世界命令，没有手动替 JSON 保存攻击。

这些能力已用于 Kinetic Tremors 的部分数据实现：命中窗口、武器门槛、固定点三波和重触发冷却已有验收。完整等级 / 动能缩放、衰减及实际装备来源仍缺失，见下文和覆盖清单。

## 已提交的护盾层

外层 Buff 条目可声明 `shield`，引用 definition.components 中单位为 damage 的数值容量；`taken_multiplier` 是单位为 multiplier 的 Value，支持现有 PvE / PvP、组件、算术等表达式，缺省为 1。`priority` 数值小的先承受，同优先级按实例创建 generation 排序（FIFO），不按 Buff 名称排序。没有容量或已暂停的层不吸收伤害；容量扣到零仍保留 Buff，内容可通过破盾反应移除、修复或开始回充。

```json
{
  "definition": {
    "id": "example:overshield", "version": "example-1", "duration": 10,
    "components": { "numbers": { "capacity": { "initial": 45, "unit": "damage" } } }
  },
  "shield": {
    "capacity": "capacity", "priority": 0,
    "taken_multiplier": { "type": "chorus:constant", "value": 0.3, "unit": "multiplier" }
  }
}
```

实际接入点在 `actuallyHurt` 内，通过原版及加载器的取消 / 免疫、物品格挡和无敌帧检查后，原版护甲计算之前。物品格挡、难度 / 特殊原版前处理、无敌帧增量先决定进入 Chorus 层的预算；盾后剩余才经过护甲、附魔、药水与 Absorption。完全被 Chorus 盾吸收时仍完成回执；免疫盾的零倍率阻断输入但不扣容量，不发布 damage_taken / shield_broken。原版无敌帧照常生效，测试连续受管动作时显式清除冷却，正式内容若要绕过必须使用相应伤害类型标签。

- Fabric 分别在 LivingEntity / Player 的护甲调用参数处处理护盾。NeoForge 在其第一次读取护甲前 DamageContainer 时写入盾后数值，防止后续从容器读回旧伤害；后置 `LivingDamageEvent.Pre` 仍可修改盾后的伤害，不等于重新取消已经完成的护盾消费。NeoForge 专项测试已验证：`LivingIncomingDamageEvent` 改量会改变扣盾预算；ARMOR reduction modifier 只处理盾后余量，不能恢复 Chorus 已扣容量；后置 Pre 把剩余伤害清零也不回滚护盾。第三方模组任意改写这些阶段的组合仍需单独验收。
- `ShieldDamage` 先生成预算轨迹和候选写集。只有执行达到该阶段才消费；在它之前取消、免疫或被无敌帧拒绝，均不扣盾。世界侧维护本次结算的不可变临时视图，使扣盾后的嵌套命中立即看到新容量。完成后，按真实消费顺序把写集附在世界回执 / 原版观察批次中，由纯核心校验实例 generation 和完整旧快照后提交；旧计划不能写进重新获得的实例。
- `DamageReceipt.shields` 保留每层旧实例、容量组件和预算轨迹。`shield_damaged` 只在容量实际减少时产生；`shield_broken` 只在该次减少使容量归零时产生。两者保留攻击来源、damage_id、shield_definition、shield_generation、layer_loss、layer_remaining，并加入该层 Buff 定义的标签。`event_reference` 可筛选指定护盾定义，`event_tag` 可筛选元素盾等资格；不把护盾标签加到普通 hit / kill。
- 完整受管动作序列先结束，再处理破盾等反应；盾容量提交无需等反应。因此下一动作读剩余容量与下一动作获得破盾收益是不同的时序。测试验证最后处理破盾反应修复指定层，不会回头改变之前的伤害。
- 若在扣盾后的原版回调中抛异常，不伪造完整命中回执；运行时 Failure 保留尚未协调的护盾写集，停止自动推导和重试。当前没有跨重启日志或恢复命令，不能声称此异常路径已可自动恢复。

显式补盾与类型化结果已接入，见下节。连续护盾回充和数据规则控制的受击延迟见下文；生命层专属 Profile、穿透 / 跳过层策略及 UI 同步仍待完成。声明层内倍率不代表 Void Overshield 或所有元素盾的内容与数值已完整实现。

### 攻击者对当前护盾层的修饰

`DamageCommand.shieldScalingProfile`、DSL 的 `damage / capture_damage.shield_scaling_profile` 提供独立的攻击侧层查询。Profile 输入 / 输出均为 multiplier、基值为 1；由攻击所有者的来源 / Buff 提供贡献，与接收层自身的 taken_multiplier 相乘，再进入 ShieldPlan 的预算算法。全局攻击及目标防御仍只执行一次，护盾专属增伤不带入盾后的生命预算。

`ShieldQuery` 包装不可变攻击事件与当前 Buff 实例；`layer_tag` 只检查当前层定义，普通查询返回 false，不向 hit / kill 标签混入层资格。按 priority / FIFO 仅查询实际到达的非空层，余量为零即停止；因此未到达层的缺失测量不会误使本次伤害失败。所有层表达式读取相同的命中前状态，incoming_damage 为抵达该层的预算；写集在原版实际达到护盾阶段后按既有提交协议处理。

`DamageSnapshot.ShieldScaling` 保存旧 Profile 和部分绑定贡献，来源强化 / 武器被冻结，层类型 / 目标条件 / impact_number 延后。当前 on_hit 贡献在同一分组中归约，不能将已经折算的旧倍率与新倍率直接相乘。快照的目标 has_shield 查询补充当前 CompiledEffects，修复原先缺失规则目录的路径；来源条件仍保持捕获值。每个 LayerHit 的 attackScaling 保存攻击侧完整轨迹。

9 项 `LayerProfileTest` 覆盖四类盾普通 / 强化、武器与所有者隔离、爆炸词条排除、Woven Mail 躯干分支、未到达层、旧快照 / 新目标与 on_hit MAX / 版本校验。3 项 `LayerProfileGameTest` 验证多层专属倍率之后的原版护甲和 Absorption、解绑后延迟命中新护盾，以及免疫 / 无敌帧拒绝和增量预算。Under-Over 内容及剩余校准边界见 [规则集](d2-ruleset.md)。命名因子与精准抑制见下节；生命层专属 Profile、穿透和自动来源 / 盾分类仍未实现。

### 已应用因子与护盾精准抑制

`CalculationStep.Apply.factor` 显式标记乘法阶段，禁止标在 add / replace 等步骤上。Result 中的 `CalculationProfile.Inputs` 保存旧 Profile、基值、已经求值且排序的贡献及已排除集合。Result.withoutFactors / withBase 只重放数值步骤，不重新访问来源、目标或世界。trace.factors 按阶段保留完整倍率与 omitted，贡献轨迹说明因子排除；同一个因子可对应多个阶段。

Buff shield 的 `excluded_attack_factors` 选择当前层要排除的攻击因子。原版边界把已计算的 outgoing / defense 作为 DamageBasis 传入，不能在护盾阶段重新查询全局修饰。算法使用：

```text
O  = 本次实际攻击 Profile 输出
O' = 用相同贡献、排除指定阶段后的攻击输出
rO = O' / O
N  = 已通过原版门槛、进入全局防御 Profile 的实际输入
D  = 本次防御输出
D' = 以 N × rO 为基值，保留本次防御贡献重放所得输出
r  = 有防御 Profile ? D' / D : rO
当前层倍率 = taken_multiplier × shield_attack_output × r
```

没有匹配因子时 r=1。零预算不查询盾；若原输出为零却需要正预算投影则明确拒绝。原版格挡 / 无敌帧的已准入部分按比例保留，不重新执行宿主门槛。固定加值、引用早期阶段的 percent_of、曲线、上限和取整会按新的输入重算，已求值的 DSL Value 与资格条件则保持本次命中的结果。此区别须由内容明确建模，不能把它描述为重新模拟一发躯干命中。

`ShieldDamage.LayerHit.factorSuppression` 保存排除集合、比例及两份重算轨迹，和原始 outgoing / defense、层攻击轨迹并存。比例只影响本层容量消费，普通盾 / 原版生命的余量仍使用原预算；精准 hit / kill 标签保留。攻击快照保存 factor 声明，释放时来源值与命中时 impact_number 的既有语义不变。

7 项 `FactorProjectionTest` 验证算术、重复因子、零基值、单位 / JSON、原版准入预算与防御重放；9 项 `PrecisionShieldTest` 验证 Eternal Warrior 两模式、未标记攻击、冻结来源 / 新盾、Under-Over 与层减伤组合、快照、缺少实际计算依据和数据校验。3 项 `PrecisionShieldGameTest` 在两端验证实际多层溢出、护甲 / Absorption、冻结攻击和无敌帧差额。精准碰撞、原版暴击自动映射、完整分量事务和原作跨层校准尚未实现。

### 护盾补充与交互资格

`shield.maximum` 可声明单位为 damage 的上限 Value，缺省使用容量组件的声明初值。`restore_shield` 只更新已经存在的接收层；上限在该层的 Buff 作用域求值，不能误用补盾来源的组件。实际增加量受剩余容量限制，上限降低不主动扣掉已有容量。补盾保留 generation、来源、剩余时间、其他组件和 FIFO 顺序；溢出及不足以表示的微小增量不储存为后续额度。

这是纯状态动作，不读取原版实体，也不隐式创建盾。内容需要通过 `inspect_entity` 明确存活、满血等资格；显式写入暂停层不会恢复该层的保护。结果保存 requested / maximum / before / after / effective / overflow，以及 changed / full；只有正增加才发布 `chorus:shield_restored`，携带补充者来源、接收层标签和定义 / generation。提交发生在后续世界等待之前，已有帧 / 回执幂等协议阻止重复回执重新补盾，等待期间真正发生的伤害写集仍按顺序协调。

交互条件分为三种：`has_buff` 的默认 bound 匹配保持原行为，显式 `match: any` 可查同持有者任一来源的合格实例；`has_buff_tag` 查存在的 Buff 定义标签，包含暂停实例；`has_shield` 查未暂停且容量为正的已声明层，可按定义标签筛选。前两者可表示具有护盾的词条资格，后一种表示当前有可用护盾容量；原版 Absorption 不计入它。攻击快照冻结来源侧查询，目标侧查询仍推迟到命中时。

`healing_rift.json` 将这些机制组合成共享 Rift 护盾池：不同场各自保存 presence，同一受益者只有一份补盾计时器与容量；每 50 ms 观测存活且满血、没有正容量 Void Overshield 时，按快照 3 HP/s 请求一次增量，最高 15 HP，测试缩放后为每次 0.015 / 上限 1.5。受伤或存在 Void 盾时暂停生成，已有 Rift 容量保留，不积攒补发额度；最后一个场退出才删除共享层。presence 自带 `chorus:counts_as_overshield` 标签，零容量也能获得相应交互资格。

当前明确采用观测时刻满足条件就给完整脉冲的策略，没有对本次间隔内刚恢复满血的时刻分段积分。首次生成时序、离场是否立即清盾、空层创建时决定 FIFO 年龄、共享层保留首个来源，均是待原作校准的内容选择。用于阻止生成的 Void 测试层只验证标签与正容量资格，不代表完整 Void Overshield 内容。8 项 `ShieldRestorationTest` 验证通用核心，5 项 `RiftShieldTest` 验证内容组合；4 项共享 `RiftShieldGameTest` 验证真实扣盾 / 生命溢出、满血恢复、重叠池、Void 阻止、原版 Absorption 独立及空容量资格。

### 连续护盾回充

`EffectProgram.Shield.recovery` 声明 rate（damage_per_second Value）与可选 if。`CompiledEffects.shieldRecoveryOffers` 在每个未暂停的活动接收层作用域计算速率与最大容量，段首条件不满足则无候选；没有额外世界查询。每层一个表达式，不按层数重复，不替所有来源建立隐含通道优先级。

`ShieldRecovery` 按固定 generation 顺序计算十进制的 `rate × elapsedMicros / 1_000_000`，复用 ShieldRestoration 的上限、舍入和结果语义。EffectClock 同时考虑 50 ms 网格、已有到期 / 定时器 / 资源边界与按当前速率补满容量的时间，后者向上取到整数微秒。满容量、禁用和零速率不创建护盾采样，也不保留受阻时间；任意连续状态相关表达式只采用段首值，不自动求解表达式内未声明的变化点。

区间容量先提交，再执行 Buff 到期；因此 ended 快照包含最后残段，原版治疗回调中的嵌套伤害也看到已恢复容量。生命恢复继续积分原段首状态；已有世界治疗命令和事实完成后，才依次分发护盾恢复、生命周期、资源和定时器事实。正恢复沿用 shield_restored，加 continuous_shield_recovery 标签、interval_start / interval_end / rate 测量，归属该层原始来源。回执去重不能重复积分或覆盖后续真实伤害。

受击延迟复用数值组件和可替换的一次性定时器，无第二套冷却时钟。新条件 own_shield 同时核对受益者、定义和 generation，仅用于 Buff 作用域；旧层事实不会命中新实例。内容可监听本层 shield_damaged，或显式监听持有者 damage_taken，决定哪些损失打断回充。零容量是否继续恢复、破盾是否删层也由内容规定。

9 项 `ShieldRecoveryTest` 覆盖段末残量、改速率 / 暂停、容量边界、延迟重置、旧 generation、独立层来源、动态非法速率、DSL 校验和世界等待后的伤害去重；3 项 `EternalWarriorTest` 验证 75 HP / 5 秒 / 7 秒的 0.1 缩放以及重置、破盾、重施加、超能结束和死亡清理。共享世界场景在真实服务器 tick 上进一步验证生成的容量实际挡伤害、余量入原版 HP、到期快照和停伤后的回充；这些战斗测试只运行服务端。Eternal Warrior 的明确精准因子抑制另见上节；完整 Arc 增伤 / 超能延长与装备施放接线仍待完成。

## 世界目标查询与逐目标执行

`select_targets` 是显式世界观察，返回带原始请求的 `TargetQuery.Result`；纯 Value 和数值 Profile 不读取世界。结果携带有序且唯一的目标身份、count 和 available 标志；缺失中心 / 关系参照、维度不匹配与正常空列表通过 outcome 区分，JSON 可读 missing_center / missing_relative / wrong_dimension。回执在恢复时校验请求，错误的请求、乱序或重复目标不能继续执行。

当前 Minecraft 适配器只选择本维度已加载的 LivingEntity，排除已死亡、移除和旁观者；查询不会加载区块。中心可以是仍存在的死亡实体，以支持死亡位置周围的反应，但不是存活候选。范围为脚底坐标之间的球形距离，包含半径边界；一个 `chorus:meter` 对应一格，这只是 Minecraft 适配约定。目标默认按 UUID 文本升序；order = nearest 按捕获的距离升序，同距离用 UUID 文本排序。中心不存在时不退回自身位置。

`relation` 支持 any / allied / not_allied，后两者使用显式 relative_to 实体的原版 isAlliedTo；中心与关系参照可不同。中立生物也可能属于 not_allied，不能直接当成命运 2 的敌人标签。any 不要求世界里存在关系参照。include_center 默认 false；实体中心设为 true 也仍须通过存活和关系过滤，固定位置则没有隐式排除的实体。当前遍历本维度已加载实体；显式视线和更多形状见后文空间查询，空间索引优化、碰撞接触点与 D2 阵营分类仍待实现。

`capture_position` 发出 PositionQuery 世界请求；回执保存原请求及可选 WorldPosition（维度、有限 x / y / z），完整进入动作去重协议。读取的是当前脚底位置，死而未移除的实体也可提供坐标，缺失 / 移除 / 异维度实体返回 missing。坐标通过类型化结果绑定传给 `select_targets.center: {"position":"place"}`，不能误用于实体 target、集合或伤害快照。原有字符串 / 循环目标中心仍为实体语义；核心 Center 以 EntityCenter / PositionCenter 区分，两者不靠字符串猜测。

固定坐标可跨 after 保存，原目标移动或消失后仍然有效。每次查询从该点到当前候选脚底重新测距，位置缺失返回 MISSING_CENTER，维度不符返回 WRONG_DIMENSION，均为空列表；不查其他维度、不加载区块。关系参照和排除仍独立处理，不从位置猜测原目标或阵营；零距离目标不因 include_center=false 被排除。

7 项 FixedPositionTest 覆盖嵌套三次爆发、新成员、独立施放与循环捕获、缺失分支、回执匹配 / 重复、类型与词法作用域。3 项共享 FixedPositionGameTest 验证脚底捕获 / 死亡与移除、固定点过滤 / 边界 / 关系 / 维度，以及原实体移除后的三次真实范围伤害。连续爆发夹具关闭测试生物的重力和击退，另显式移动旧目标并加入新目标；引擎没有禁止正常玩法中的移动或击退。该能力不含碰撞位置采集、坐标变换、方块 / 非生物物体或独立场物体。

`exclude` 接受目标引用数组，包含内建目标和外层循环绑定；缺失目标不会被替换为自身。适配器对可解析别名同时排除对应 UUID。过滤完成后才排序，最后应用 limit；limit 是可求值的非负整数 count，缺省无数量限制，0 明确返回空列表。回执校验唯一性、指定顺序、半径、排除列表与上限；错误不能带着部分目标继续执行。

世界查询集合的循环目标既提供实体引用，也提供 `chorus:result` 的 distance 字段（meter）。距离在世界查询时捕获，世界动作等待、目标移动和后续循环均不重新测量；需要新位置时必须再次显式查询。新 `chorus:curve` Value 复用 stat.Curve，显式指定 from / to 单位，支持 table 的 exact / floor / linear 和 polynomial，以及 error / clamp 边界策略。输入与曲线定义域单位、输出与下游动作单位均检查，不在 Value 求值中读取世界。

Compendium Weapon Perks C41 / C72 / C94 分别要求 Chain Reaction、Dragonfly、Firefly 的范围或距离缩放。`radial_falloff.json` 演示 3–7 米间线性下降至零及真实扣血，基础 10 点为合成测试值；C41 给出重弹药的两个距离端点，没有在该单元格明确插值形状，不能把演示的线性曲线当成已验证原作曲线。三项效果已加入需求审阅，状态仍为 unimplemented，完整触发、元素 / 武器类别、敌人等级和强化数值还需内容定义与游戏接线。

Volatile 的致死施加路径已经由显式施加意图、实际伤害事实和只读资格检查组合表达，具体约定见下一节。[Bungie 8.0.0.1](https://www.bungie.net/7/en/News/article/destiny_2_update_8_0_0_1) 修复过施加击直接击杀时不引爆的问题；该历史补丁仅证明此边界，未用来认证 2026 年全部数值。当前宿主提供施加意图标签，尚未实现通用的伤害前意图捕获及真实武器 / 技能装配。

`for_each` 的集合仅在进入时读取一次，列表与迭代栈保存在不可变 Frame。每个目标的动作序列顺序完成，包括等待世界回执；下一目标开始前清理循环局部结果。嵌套循环可读外层结果，当前目标用 `{"binding":"名称"}` 引用。编译拒绝未来引用、非集合循环、集合当实体、结果逃逸或遮蔽。结束标记只能回到对应循环体，普通条件跳转不能跨循环作用域。

查询后移动的目标仍在列表中；每次 damage / heal / apply_status 执行时重新解析该身份，缺失、死亡或拒绝各走既有回执规则。新进入半径的目标不会补进旧列表。循环不改原事件 victim、来源或信用；派生事实仍在本规则动作结束后广度优先分发。Pump 只让出执行预算，不截断集合、不重查世界、不取消合法效果循环。测试以预算 1 验证嵌套世界等待和 1000 项本地循环全部完成。

资源成本按每次动作的 OperationId 区分。循环外支付一份、对多个目标依次返还时，共享同一累计退款额度；循环内每次支付则各有独立额度。记录跨作用域清理和世界等待保留，Frame 结束后释放。此机制尚不提供跨事件 / Buff 生命周期的成本引用。

完整 JSON 见 [target_iteration.json](../common/src/test/resources/effects/target_iteration.json)，两端 GameTest 验证实际 HP 和每目标的实际回血。该合成测试不代表 Jolt、Volatile、Rift 或任意 Compendium 范围效果已经全部实现。

### 跨帧成员与采样范围效果

`BuffSchema.targetSets` 声明独立的目标身份集合；`BuffComponents.targetSets` 保存不可变 `Targets.Identities`，新 generation 为空、刷新保留。通用去重字符串集合不冒充可执行目标。`TargetQuery.Target` 和身份元素共享目标引用接口，但只有查询元素包含 distance；for_each 按集合类型绑定元素，加载时拒绝从保存身份读取空间测量。

`read_targets` 读取当前 Buff，或当前 ended 作用域对应的最终快照。`sync_targets` 接收查询或身份集合，纯核心一次提交成员并绑定 `Targets.Difference`，后续动作可用 `difference_targets` 提取 before / after / entered / exited。所有身份集合按身份升序排列。成功的空查询会清空；不可用查询返回 observed=false 并保留旧集合，不伪装成所有成员已离开。复制保存集合不再访问世界。

成员差分本身不决定效果策略。[membership_aura.json](../common/src/test/resources/effects/membership_aura.json) 把周期查询、差分、来源独立的 presence Buff 和连续恢复组合：成员进入 / 离开时授予 / 移除；field 结束时从最终快照清理。示例明确在中心不可用时结束；纯同步接口也允许规则选择保留。重复轮询不重复进入，重叠来源各自清理，同一恢复通道不重复积分；来源解绑后已有 field 继续依自身生命周期运行。

10 项 `TargetMembershipTest` 验证空 / 不可用差别、成员复制、组件类型、同键新旧 generation、刷新、重叠、结束快照、世界等待去重和 detached 身份捕获。3 项共享 `TargetMembershipGameTest` 使用真实实体移动、消失和回血，验证查询回执冻结及下一次采样更新。恢复先积分旧成员到采样边界，才提交新成员，不根据当前位置重写过去的恢复。

这是 3 米、0.3 秒、50 ms 采样、2 HP/s 的合成移动场。它提供 Rift 等效果所需的成员管理机制；固定位置保存与 Rift 的组合见下节，独立场物体、施放实例自动装配、专属进入 / 离开事实、持久化或同步仍未完成。成员保存的是查询选择结果，并不等于后续状态施加成功；动作失败沿用保留已提交状态并停止推导的协议。具体 DSL 和采样限制见 [数据包说明](engine-data-packs.md#保存成员与进入--离开差分)。

### 位置组件与 Healing Rift 固定场

`BuffSchema.positions` 声明带维度的位置组件，初始为 Optional.empty；`BuffComponents.positions` 保存不可变 WorldPosition，其他组件更新不会丢失它。`PositionQuery.Result` 与纯状态返回的 `PositionResult.Stored` 共用位置类型接口，只有前者声称携带世界查询请求。`read_position` 读取当前组件或自身 ended 的最终快照；`write_position` 精确复制已有位置绑定，包括缺失值，返回保存后的值，不发出世界动作或隐式事实。新 generation 清空、刷新保留；读到的值在后续覆盖 / 移除后仍固定。

7 项 `PositionComponentTest` 验证声明 / 类型 / Codec、缺失与显式清空、复制与维度保存、其他组件更新、刷新、同键新旧 generation 结束快照以及 detached 延迟。位置不能作实体或集合，missing 不回退到 holder / 原点；写入前是否要求 available 由内容显式分支决定。

`healing_rift.json` 是 test-1 版本片段，与 `restoration.json` 链接并复用其 40 [PvP 35] HP/s 的 0.1 缩放和恢复通道。Rift presence 改为按来源独立实例，避免一个场移除其他场的恢复。每次施放的静态来源经 SourceChange.bind 附加，own_source 匹配自身；捕获成功后创建 15 秒 field 并写入 anchor，随后处理 buff_gained 才开始固定 5 米范围的 50 ms 查询。成员由原版 allied / source_owner 筛选，包含自身；晚进入者、离开者与场结束通过已保存成员清理，不延长 field 本身。

6 项 `HealingRiftTest` 验证两种环境、固定位置、独立施放、重复绑定幂等、解绑后继续、与 Restoration 复用通道、晚进入者和不可用策略。4 项共享世界场景验证实际原版队伍、边界、移动、连续回血和 15 秒到期 / 重叠场分别结束；另用 any 变体验证施放实体移除后固定坐标仍有效。原版 HP 连续 float 写入可能产生累计舍入差，测试分别核对精确逻辑请求积分和实际回执 / 生命变化，不把 requested 当作 offered。

这是部分 Rift 内容：施放来源身份目前由宿主提供；没有技能按键 / 成本、落地点 / 地形处理、施放动画或表现物体；满血补盾已按上述离散脉冲实现。原版队伍查询仍需存在于当前维度中的参照实体（可已死亡但未移除）；参照消失时片段结束场，是明确保留的待校准策略。真实阵营快照、断线 / 跨维度场生命周期及跨重启持久化未完成。原作数据与官方更新的核对边界见 [规则集](d2-ruleset.md#restoration-与-healing-rift)。

## Volatile 的数据规则与验收边界

[volatile.json](../common/src/test/resources/effects/volatile.json) 使用目标 Buff、伤害身份集合、只读资格检查和范围迭代，无专用 Java 词条分支。来源规则要求宿主在真实命中事实中保留 `test:apply_volatile` 施加意图；原版默认来源映射不会自行添加该测试标签。

- 首次存活施加成功后，以零增量记录施加击的 damage_id。后续命中按 `effective_damage` 累计，每个伤害身份只计一次；该投影目前是实际生命与 Chorus 护盾损失，不含原版 Absorption。
- 达到环境阈值或收到实际致死事实时，先挂目标共享的 1 秒冷却并移除 Volatile，再查询范围和造成爆炸。随后到来的 death 事实不会重复引爆；其他目标的 Volatile 可以继续响应爆炸，在同一 root 中自然连锁。自然到期不爆炸、不挂冷却。
- 首次施加击已经致死时，执行 `check_status(allow_dead=true)`；资格允许后直接走引爆序列，不给死亡目标创建 Volatile。冷却仍作为明确的内部状态记录。资格拒绝或目标缺失时不引爆，也不授予冷却。
- 范围以受害者为中心、施加者为队伍关系参照，包含仍存活的中心并排除施加者。任意攻击者可推进已有状态的累计；爆炸保留首次施加来源，显式标记 void / ability damage 和 ability kill，不从武器引用推导 weapon kill。新一次冷却结束后的施加可以归属于另一来源。

快照 Void D9 的阈值为 190 [PvP 200]，最大爆炸伤害为 145 [PvP 80]，半径 5 米、持续 10 [PvP 6] 秒、引爆后 1 秒内不能再次施加。夹具将伤害与阈值都乘以 0.1；距离衰减采用中心最大、5 米为零的线性曲线。**0.1 缩放、线性衰减，以及已有状态不刷新并保留首次来源，均为明确的内容选择；不能当成已经验证的完整原作策略。** 实际伤害投影、目标等级缩放、多来源重施加、状态免疫与阵营规则仍需校准。关系参照依赖施加者仍在本维度，离线 / 跨维度来源的阵营快照尚未接入。

11 项 `VolatileTest` 覆盖两种环境、施加击去重、冷却边界、多人归因、致死施加和回执校验；5 项共享 `VolatileGameTest` 验证原版命中驱动实际爆炸扣血、死亡位置反应、免疫资格及相邻 Volatile 连锁。测试宿主把逻辑伤害类型映射为 `chorus_gametest:volatile`，并仅在测试数据包给它配置 `bypasses_cooldown`，使紧随原伤害的爆炸能实际扣血；引擎没有全局跳过原版受伤冷却。真实技能 / 装备施加意图和正式内容伤害类型仍待装配，因此覆盖状态为 partial。

## 实体观测与 Jolt 部分内容

`EntityQuery` 通过世界回执读取活体类别、存活、原版 HP / 最大 HP / Absorption；`inspect_entity` 的结果绑定提供 available / missing、alive / player 以及带单位数值。仍在世界中的死亡实体与无法解析、已移除或跨维度实体分开。缺失结果只能读 available / missing，其他字段必须先通过可用性分支；不会把未知当成 0、非玩家或死亡。结果不携带可变实体，不创建影子生命，也不是目标 / 位置引用。延迟动作保留已观测值，下一次实时读取须再发命令。

5 项 EntityObservationTest 验证字段、单位、缺失、错误回执、重复 / 冲突回执、延迟捕获和严格 Codec；2 项共享 GameTest 验证真实牛 / 玩家、死亡 / 移除 / 跨维度、原版生命和吸收，以及“观测 8/20 后世界变为 20/40，仍只请求已观测缺口 12”的恢复语义。

[jolt.json](../common/src/test/resources/effects/jolt.json) 使用这些观测与伤害回执实现 Arc D8 的主要状态流程：10 / 5 秒刷新，施加击计入，115 / 45 阈值和 119 / 51 放电伤害共同缩放为 0.1 倍，12 米球形、0.8 秒目标冷却。普通 hit 与新施加后的初始化事件使用相同动作体，并共享 damage_id 去重；已有状态直接在 hit 内累计 / 放电，避免把工作推到 death 事实之后导致致死阈值链伤丢失。额外 `test:jolt_after_damage` 只排除新施加的首次伤害，代表显式的施加顺序选择，不自动识别技能。

放电先设置冷却，逐个确认附近实体可用 / 存活，再等待实际伤害。另一玩家确实损失 HP / Chorus 护盾 / Absorption 才允许玩家中心受链伤；普通生物中心无需这一条件。免疫、格挡、取消、失败和 applied 但零损失均不能满足条件。类别使用 MC Player，活动 PvE / PvP 仍由 ruleset 决定。链伤 origin=event，状态保留原施加来源，伤害 / 击杀信用及攻击 Profile 不继承武器。相邻 Jolt 可以合法连锁，各目标冷却独立。

12 项 JoltTest 验证阈值、施加击去重、持续 / 刷新、精确冷却、伤害资格、顺序例外、来源、环境伤害、缺失 / 死亡、相邻连锁、Bolt Charge 放电排除与 Codec。6 项共享 JoltGameTest 通过真实原版命中验证两模式实际扣血、原版 DamageSource、玩家取消 / Absorption、致死阈值、同 root 连锁、Bolt Charge 放电接收探针的选择性排除及真实 tick 的 0.8 秒边界。测试宿主使用 scaling=never 的原版 generic 输入，避免和平难度将玩家的 mob_attack 输入归零；模拟玩家显式结束入场无敌，伤害动作映射到仅测试数据包含 bypasses_cooldown 的类型，生产适配器不绕过原版保护。

覆盖保持 partial。冷却内伤害丢弃但记住身份、超额清零、刷新保留首次来源、阈值取 HP + Chorus 护盾而不含 Absorption，均为待校准的内容选择。阵营相对原施加者查询，无法判断关系时跳过全部链伤且保留已启动冷却；首次致死施加不建状态、不放电，已有状态的致死阈值可伤邻居而不再伤中心。完整 D2 数值缩放 / 等级分类、离线阵营、Overload 眩晕、技能和武器装配、正式伤害类型仍未完成，详见 [规则集](d2-ruleset.md)。

## 程序片段链接与 Voltshot

`CompiledEffects.link(List<EffectProgram>)` 将已解码的程序片段组成单一固定版本目录，再执行既有的完整编译校验。片段可以引用别的片段的 Buff、bundle、Profile 和资源；同名定义即使完全相同也拒绝，不能依列表顺序覆盖。所有片段版本及内部定义版本须一致，不自动改写。全局 defense_profile 最多由一个片段选择。链接后的 program 可编码回现有完整 JSON，输入片段不受修改；尚未增加数据包自动 imports 或混合版本运行时。

5 项 ProgramLinkTest 验证跨片段依赖、完整 Codec 往返、输入不可变、四类重复 id、空目录 / 版本 / 防御冲突、内部版本、跨片段单位错误及全局防御引用。双加载器使用同一链接入口，把 [voltshot.json](../common/src/test/resources/effects/voltshot.json) 与 [jolt.json](../common/src/test/resources/effects/jolt.json) 组合运行。Voltshot 文件不复制 Jolt 定义，也不能独立编译为完整程序。

Voltshot 片段使用按武器实例隔离的 5.3 秒击杀窗口与 7 / 强化 8 秒就绪 Buff。击杀、换弹、命中都检查持有者与武器身份；前者还需要 weapon_kill，后者需要 weapon_damage。两个状态收枪后继续计时。命中先消费一份就绪，随后确认 Jolt 资格；成功后共享初始化事件与 Jolt 旧监听按 damage_id 去重。Jolt 伤害仍按事件来源归属，不能自动得到武器信用。

9 项 VoltshotTest 覆盖正常 / 强化及精确边界、收枪、武器 / 持有者隔离、重复换弹、连续武器击杀、非对应事件、批次首击消费、取消 / 失败 / 免疫 / 格挡 / 拒绝 / 致死、两活动模式、片段链接顺序以及 Jolt 击杀不能重开窗口。4 项共享 VoltshotGameTest 由真实原版击杀和命中驱动状态与扣血，宿主明确发送 reload_finished；验证实际取消 / 死亡、两个武器的就绪隔离、他人触发链伤归属、窗口到期后的 Jolt 实际击杀无武器信用，以及真实 tick 上的 7 / 8 秒到期。

覆盖仍为 partial。窗口内重复换弹可重置就绪但不消费击杀窗口、资格拒绝也消费已命中的就绪、同批按第一个 hit 消费，是已声明而待原作校准的内容选择。通用实际容器与手动换弹已接入，但尚未替换该 Voltshot 夹具的显式换弹事实；该片段与单次开火入口的集成、多弹丸事务、死亡 / 装备卸下的内容清理尚未接入；共享 Jolt 的勇士眩晕和完整数值缩放等缺口继续保留。详见 [规则集](d2-ruleset.md)。

## 统一逻辑时间轴

`TimelineEngine<S>` 是 `RuleEngine<S>` 的纯宿主，输入仍为 `Start / Completed / Pump`，输出仍为新状态与世界动作列表。一次外部 `Start(t, event)` 会保存在状态中，宿主逐个推进到期时刻、处理所有反应，最后才向规则解释器投递原外部事件。每次调用只推进一个有限步骤，等待世界结果或需要 Pump 时保留完整状态。

- `EffectState` 组合 Buff、顺序资源账户与定时信号。所有账户时钟必须相同，活动定时器必须在当前时刻之后；暂停的 Buff 定时器以 NEVER 保存截止点，并另存剩余等待时间。同刻动作直接派生事实，不能通过零间隔定时器递归推进时间。
- `EffectClock` 在每个边界先用旧状态的速率积分，然后提交该时刻的 Buff 到期。若有连续生命恢复，先执行该区间已经分配的治疗命令；所有回执完成后，直接治疗 / 原版嵌套事实排在生命周期、资源变化、周期信号之前。这三类边界信号仍按这个顺序排队，同类按稳定标识排序。后续派生事实遵循解释器的广度优先规则，反应观察已经到达边界的新状态。
- 周期定时器保存绝对 dueAt，按 previous_due_at + interval 递推，支持有限次数或持续运行；测试把多个 70.001 ms 周期追赶到一秒，世界回执与多次 Pump 均不改变预定发生时间。物理碰撞仍由原版 tick 决定。
- 定时器可以绑定某个 Buff generation。同刻到期先移除 Buff，不再提交该实例的周期信号；同键重新施加的新实例不会接管旧定时器。信号已作为事实提交后不回溯撤销，后续反应仍按各自的来源和条件判断。
- `EffectState.withBuffs` 同步移除失效实例的定时器，暂停时冻结下一次触发的剩余等待，恢复时从当前时刻继续这段等待；暂停期间不产生或补发周期事实。状态构造拒绝定时器与活动所属 Buff 暂停状态不一致的数据。`withSource / withoutSource` 清理不再匹配原始静态来源的 DSL 定时器。
- 自然恢复的 0 / capacity 和显式声明的资源阈值会成为时间边界；多充能的中间整格、其他有触发意义的阈值必须由资源定义提供，不能只在 tick 末检测。阈值过大、逆向时间、跨过未结算时间点均报错。
- `RuleEngine` 的回执缓存仍按其单个 Start 结算边界清理。`TimelineEngine` 额外为一次外部 Start 及其全部追赶边界保留已完成回执，重复回执不重复发世界命令，冲突回执报错。下一次外部 Start 才清理这层缓存。世界执行器自身的持久去重仍未接入。
- 到期反应失败时保留领域状态和实际世界回执，把未执行的外部请求记录在 `abandoned`，不继续执行它。失败时间轴拒绝自动开启下一请求；显式修复 / 恢复策略、重启持久化仍待实现。
- 资源速率由程序的 resources / rate_profile 声明，生命恢复由 bundle.health_recovery 声明，均由 CompiledEffects.engine 自动接入时钟。程序未声明的 Java 账户保留宿主速率接口。真实服务器 tick 已接入；实际技能属性数据装配、活动状态迁移和离线推进策略仍未完成。

### 数据资源账户与阈值

`ResourceDefinition` 定义 id / capacity / initial / base_rate / thresholds / rate_profile；后三项分别默认为 0 / 空列表 / 无 Profile。当前账户是 holder + resource id 下的共享顺序能量，一份充能固定为 1。定义不自动创建账户，`initialize_resource` 在当前逻辑时刻创建，重复调用返回 created = false 并保留现值。解绑来源不销毁实体账户，也不停止基础恢复；需要停用的回复由来源修饰或其他显式状态决定。

rate_profile 的输入 / 输出必须均为 `charge_fraction_per_second`。`CompiledEffects.resourceCalculation` 使用 base_rate 作为输入，收集当前 holder 的静态来源与 Buff 修饰，保留计算轨迹；无 Profile 时直接使用 base_rate。速率查询携带 resource 引用、resource_value / capacity 测量、`chorus:resource_rate_query` 标签，来源为账户持有者，weapon / ability 留空。当前不会从账户名猜测真实技能归属。允许负速率表示持续消耗，满值 / 零值之外的积分被裁剪，不储存为未来收益。

资源在每段起点求速率，按常速积分到下一个边界。Buff 到期、来源变化、外部事件、0 / capacity 和显式 thresholds 都能分段。若速率条件依赖中间能量阈值，作者必须将该边界加入 thresholds；当前不从任意 Value / compare 表达式推导断点，也不求解任意连续状态相关的微分方程。实际服务器 tick 还会以 50 ms 步进，不能把 tick 近似冒充未声明阈值的精确处理。

`resource_crossed` 匹配指定 target（默认 self）的指定资源，up 为 before < threshold 且 after >= threshold，down 相反。0 / capacity 自动可用，中间阈值必须在定义中声明。自然恢复、一次性入账和消费统一发布 resource_changed；停留在满值不重复触发。初始化单独发布 resource_initialized，初始值不作为回满事件。

`grant_resource` 保留 requested / scaled / credited / overflow；它接受已经求值的收益，不自动套 CES。`spend_resource` 要求 amount 为 charge_fraction，余额不足则不扣款，结果为 paid / after / succeeded。payment 是同一规则内唯一的标识（包括各条件分支）；成本回执身份包含事件、规则实例及完整的动作 OperationId，跨世界等待保留；同一支付指令在不同迭代中产生独立的成本。零成本可以成功，但 paid = 0。内容用 succeeded 决定是否执行后续动作，不能把“发出施放请求”直接当成支付成功。

资源事实统一暴露 before / after / delta / capacity（charge_fraction）、changed 标志、resource / reason 引用；reason 为 regeneration / grant / spend，初始化为 initialize。grant 的专用事实还暴露 requested / scaled / credited / overflow。恢复为账户所有者归因，显式入账 / 消费保留执行来源。原 Java 的 ResourceChanged / ResourceGranted 载荷通过 EffectEvent.Carrier 暴露这些测量，已有类型消费者保持可用。

资源引用、恢复 Profile 单位、容量和反应阈值在编译 / 初始化时校验；实际账户缺失不按 0 处理。资源状态仍是有限 double，加减、积分量和阈值时间使用 double 的规范十进制值运算，截止时间向上取到整数微秒；测试验证 `0.7 + 0.1 = 0.8`，以及从 0.7 到 0.8、速率为 1 时恰好 100000 µs。该策略没有把整个数值引擎改成任意精度，Profile 求值和状态存储仍有 double 精度限制。

`refund_cost` 引用此前绑定的 SpendResult 或 RefundResult，fraction 为 [0,1] 的 multiplier，返回 requested / allowed / credited / overflow / paid / claimed / remaining，以及 changed。返还只使用回执中的实际 paid 和账户；allowed 受这笔成本剩余额度限制，credited 再按容量裁剪。已认领但溢出的量也消耗额度。`grant_full_charge` 增加 charges 份完整充能，保留部分进度、不隐式缩放、不设置为满值，结果为普通 ResourceResult；count 必须是非负整数。

`Resources.CostResult` 标识携带成本回执的动作结果，ResultShape 明确是否允许作为退款引用。它实现通用 `RuleEngine.RetainedResult`，引擎按实际付款身份把最新记录保存在 Frame.retainedResults 中，与词法绑定分开；未使用 as 的返还同样记账。私有指令槽保留最近执行结果用于诊断，但迭代清理槽位不影响保留记录。Evaluation 对用户引用执行普通词法作用域校验，再读取同一付款的最新累计认领量。旧 cost 引用、分支退出、循环推进和世界等待都不会丢失已认领额；其他 payment / 账户不共享额度，冲突身份明确拒绝。此记录随 Frame 结束释放，不在 EffectState 无限积累。跨后续事件、Timer 或 Buff 保存成本引用以及跨重启账本仍未实现；引擎不会因为世界动作取消自动退款，必须由内容声明。

返还专门发布 resource_refunded，即使 credited = 0 也保留 requested / allowed / overflow 等测量；值改变时再发 resource_changed，不重复发 resource_granted。完整充能发布 resource_granted / changed，reason 为 full_charge。统一的阈值条件仍可观察其变化。完整 JSON、字段和使用方法见 [数据包资源示例](engine-data-packs.md#声明资源与支付成本)。动态容量、capacity_fraction 自动换算、parallel / linked 和技能 CES / CMS 数据装配仍待实现。

### 整数弹药账户

`EffectState.ammunition` 以稳定武器实例 ID 保存 `AmmoState`，包含 magazine / 未修饰 capacity 输入、可选 capacityProfile（holder / profile ID）和可选有限储备（数量 / 容量）；不存在储备对象明确表示无限储备，不是缺失账户。实际弹数为非负 int，基础容量为正，弹匣可以高于基础容量。所有状态更新及 EffectClock 均保留这些账户，来源解绑 / 重挂不补弹也不销毁余额。

`Ammunition` 提供纯 spend / refill / generate。spend 足额才全部扣除；refill 按本次 ceiling、请求和有限储备共同裁剪并保持两池守恒；generate 明确凭空生成，弹匣生成不扣储备，有限储备生成不超过其容量。默认 ceiling 为基础弹匣 / 储备容量，较低上限不删除已有溢出。结果校验真实前后差值、操作种类和数量，返回 requested / applied / unfulfilled / complete / changed；后续动作使用实际 applied。未完成数量不作为以后可领的权益，无限储备的数值读取明确失败。

DSL 用 initialize_ammo / observe_ammo / spend_ammo / refill_magazine / grant_ammo，单位 round；初始化不发弹药变化事实，重复调用保留现值，账户形态冲突拒绝。Ammo Value 读取指定字段，Round Value 显式选择 floor / ceiling / half_up 并保留单位；常量小数在加载时拒绝，动态小数在写入前拒绝。来源数值的显式快照冻结 ammo 操作数，victim 依赖延后，普通延迟动作仍读取执行时状态。

变更按实际结果发布 ammo_spent / ammo_refilled / ammo_generated，值变化另发 ammo_changed；受影响武器和动作来源独立。它们不模拟开火或完成换弹。`AmmoProgramTest / AmmunitionTest` 共 15 项测试覆盖有限守恒、无限语义、整型边界、伪造回执拒绝、缺失 / 动态错误、快照、两把武器、重绑和延迟。3 项共享 AmmoGameTest 验证真实服务器 tick、已提交余额驱动的原版治疗、补弹不触发换弹规则、来源解绑后继续，以及世界结果未知时不回滚 / 重放。

下文的 weapons 定义已把账户接入实际独立容器和整弹匣手动换弹。同一运行时内的卸下 / 重新装备保留余额，转给另一持有者时保留弹数并更新容量修饰归属。单次开火已接入；多弹丸 / 精准射击、热量武器、武器配置引起的未修饰容量输入替换、账户销毁 / 跨维度迁移、NBT 或客户端同步仍未实现。会话结束时仍丢弃暂态账户。八项已审阅弹药词条中，Clown Cartridge 已有独立内容与实际换弹验收，状态为 partial；其余七项仍是需求映射，不能按通用夹具记为 partial。详见 [弹药 DSL](engine-data-packs.md#整数弹药与弹匣转移) 和 [D2 术语与剩余边界](d2-ruleset.md#弹药生成补充与换弹)。

### 实际武器与手动换弹

程序顶层 weapons 为已有装备原型声明初始弹药、可选容量 Profile 和换弹参数。`CompiledEffects.changeEquipment` 在装配 / 来源反应之前建立所有账户，校验账户形态、武器槽及跨持有者的实例唯一性；实际 ItemStack 转移前执行同一预检。未配置 weapons 的装备维持原有行为。装备来源、账户和换弹取消一起进入已提交域状态，后续世界反应失败不回滚真实物品或账户。

`/chorus weapon reload` 仅操作请求玩家当前持握的独立容器武器；普通玩家不提供实例、弹量或时长。接受时以当前来源 / Buff 计算换弹时间，支持 stat_point 等输入通过 Profile 变成 second；保存 CalculationProfile.Result 和向上取整到微秒的截止时间。重复请求返回 BUSY，不能重启。`EffectState.reloads` 与一次性内部计时器共同保存 Plan，所有状态更新与时间推进均保留它；切枪、卸下或修改该实例的插槽选择取消。

截止时间上的 Buff 到期先结算。内部完成规则发出 `WeaponReload.Verify` 只读世界操作，由 MinecraftEffectRuntime 检查实际玩家仍在本维度、存活、非旁观者且持握相同实例 / 选择。校验通过后按当时有效容量、缺口和储备执行守恒 refill，清除 Plan 和计时器；实际装入大于零才发布 reload_finished，部分装填也可完成。通用 refill、已被补满的弹匣和耗尽储备不能伪造完成。弹数与换弹状态在世界反应前提交，未知世界结果停在原操作，不能自动补弹或重放。

10 项 WeaponReloadTest 覆盖拒绝 / 重复输入、精确完成时刻、两个武器与 Kill Clip 窗口边界、切枪 / 改装 / 重装、速度快照、完成时容量与同刻到期、属性曲线到秒数、有限部分 / 无限储备 / 溢出、实际量、跨持有者移交、非法定义及世界失败。4 项共享 WeaponReloadGameTest 在 Fabric / NeoForge 通过实际容器、普通玩家命令和服务器 tick 验证补弹触发世界治疗、Kill Clip 激活、切枪、死亡 / 旁观者 / 移除，以及反应已执行但结果未知时的弹药保留。新场景使用普通 ServerPlayer 加测试连接；旧 makeMockServerPlayerInLevel 固定 gameMode 为 CREATIVE，不能用于旁观者资格验收。

weapons.json 的弹量、时间和换弹后治疗是合成测试参数，Kill Clip 集成场景的击杀事实仍由测试宿主提供；该夹具单独不证明射击 / 武器击杀信用，后续真实链路见下一节。当前只支持整弹匣手动换弹，开火接受后已中断换弹；逐发装填、冲刺中断、技能换弹、手动排热、按键 / 动画 / HUD 和弹药跨运行时持久化仍待实现。字段和事实见 [数据包入口](engine-data-packs.md#武器定义与服务端换弹)。

### 可复算的随机动作

RandomState 把固定算法、种子与原始抽样游标纳入 EffectState；所有更新与 EffectClock 都保留它。显式 sample_random 动作在提交前校验同单位上下界，产生 Sample 回执并推进随机状态，然后发布带种子 / 前后游标的 random_sampled 事实。uniform_real 使用 53 位分数和半开区间；uniform_integer 包含两个整数端点，并以拒绝采样避免取模偏差。常数区间仍抽样，游标耗尽明确失败。

5 项 RandomStateTest 验证固定序列、独立 JDK 序列对照、游标恢复、半开边界 / 常数区间、拒绝采样、完整 signed int 范围、回执复算与篡改拒绝、非法输入。5 项 RandomActionTest 验证不同执行预算和回放一致、假条件不抽样、事实可审计、重复读取和来源移除后的延迟复用、未知世界结果保留游标 / 回执、重复世界回执不重抽，以及随机动作不能伪装为数值表达式。

生产 EffectPrograms 安装时生成服务端种子；纯核心默认零种子用于可重现测试。当前是运行时内单一有序流，不提供跨运行时存档、独立命名流或加权分布；通用随机能力不等于校准了 D2 具体词条的概率分布。字段见 [显式随机抽样](engine-data-packs.md#显式随机抽样)。

### 显式整枪与弹丸结算

`ShotGroups` 在域状态中保存有限寿命的活动组，与 WeaponFire 的每武器射速记录分开。`begin_shot` 返回稳定 handle；成员投射物在调用世界前提交索引占用；实际 Launch 与 DamageReceipt 决定进度，终止接触动作体执行完后才封闭弹丸。状态与时钟推进保留组，收枪不改写已捕获的归属。所有槽位结束或截止时，移除组与计时器，发布一次 `chorus:shot_resolved`。

汇总分别保留每目标的唯一 hit / 有效伤害弹丸数，避免分散命中、贯穿、多条伤害命令和重复回调膨胀计数。明确拒绝发射、正常终止无 hit 与无法确认结束分开报告；超时为不完整结算并终止残留飞行。到期与延迟发射同边界时也不能新增终态或重开组。未知世界结果保持 pending，不自动退弹、重放或补造 summary。

11 项 ShotGroupTest 覆盖真实域开火、扣弹 / 收枪、每目标聚合、预算无关、重复与贯穿、免疫 / 取消、拒绝 / 超时 / 延迟发射、交错射击、类型与归属检查、未知发射 / 伤害，以及实际新增命中 / 有效伤害才发进度和不可变前后计数。4 项共享 ShotGroupGameTest 验证实际玩家容器和普通 fire 命令、三颗物理弹丸真实扣血、收枪后一次结算、外部移除后的不完整截止、声明寿命停止飞行，以及实际扣血但回执丢失时不重放。

`chorus:shot_progress` 由实际新增回执计数发布，携带前后最大命中 / 有效弹丸数；不等待剩余弹丸结束。同一枪的门槛跨越可触发一次就绪，而 shot_resolved 继续表示最终完整性。两者不改变广度优先反应顺序。[DSL 字段与生命周期](engine-data-packs.md#整枪与弹丸结算) 说明 complete、每目标计数与显式 pellet 记账边界。

One-Two Punch 已另有独立内容与真实世界验收；通用三弹丸夹具本身仍不能作为 Rewind Rounds 的内容证据。精准、跨分量下一击资格、自动 burst 与存档恢复尚未完成。

### One-Two Punch 的当前内容边界

`one_two_punch.json` 与实际武器 / 技能夹具链接，通过 shot_progress 的 12 / 强化 10 颗同目标门槛施加三秒 Buff。来源按持有者与物理武器隔离，收枪或词条解绑清除。on_hit 修饰及回执消费保证后续独立近战看到新状态；手炮 / 霰弹枪与 PvE / PvP 采用各自增量，互斥冰冻被动取高后与其他近战增量相加。

10 项 OneTwoPunchTest 覆盖触发时点、分散目标 / 跨枪 / 重复接触不合计、四组倍率、互斥与相加、新枪刷新 / 精确到期、收枪 / 词条替换 / 换手、未结束弹丸、取消 / 免疫 / 未知回执和复用快照。5 项共享 OneTwoPunchGameTest 由真实玩家容器、普通 fire / ability 命令、物理弹丸、实际 HP 与 tick 验证完整路径；强化手炮场景保留两颗飞行弹丸，确认第十颗命中已触发。

覆盖为 partial：原型与基础伤害参数仍为合成输入，完整近战分类、冰冻被动来源和跨宿主 / 多分量消费未实现；异常命中和替换边界尚需原作校准。[数值来源及详细边界](d2-ruleset.md#one-two-punch雪上加霜)。

### Clown Cartridge 的实际换弹内容

clown_cartridge.json 将同武器 reload_finished、sample_random 和具有显式 ceiling 的 refill_magazine 组合。普通 / 强化下界为 0.10 / 0.13，上界 0.50，结果按完成事实中的有效容量上取整；实际增加量受剩余储备限制，不改变基础容量，也不再次发出换弹完成事件。uniform_real 分布与先基础换弹后溢出的顺序均为已声明、仍待原作校准的内容策略。

8 项 ClownCartridgeTest 覆盖持有者移交后的旧事实隔离、真实域换弹、两实例 / 强化、下界与取整、再次换弹重抽、有限 / 无限储备、普通补弹 / 取消隔离、动态容量到期后的溢出保留、未知世界结果保留抽样和弹数。3 项共享 ClownCartridgeGameTest 验证实际容器词条 / 普通玩家命令 / tick、有限储备不足、实际转移后的世界治疗，以及治疗已经执行但回执未知时不重抽或重放。内容审阅从 unimplemented 推进到 partial；不以合成枪械参数和假设分布宣称完整 verified。

这一用例同时修正 Value.Arithmetic：add / mul 用有限 double 的规范十进制值计算，再在当前节点输出 double。`100 × 1.1` 与 `50 × 0.28` 的上取整不再多发一发，真实非整数仍按显式 round 处理；没有引入全局容差或更改 Profile 的阶段顺序。完整范围 / 假设与来源见 [规则集](d2-ruleset.md#clown-cartridge-的随机溢出与验收边界)。

### 实际武器单次开火

WeaponDefinition.fire 声明整数弹药成本、间隔 Value / Profile、条件、标签和 on_fire。`CompiledEffects.fire` 是纯接受事务；只读校验通过后提交弹匣扣款、按物品实例保存的 Shot 与已接受开火造成的换弹取消，接着分发实际弹药事实和 fire_accepted。免费开火必须显式 cost=0，仍保留间隔；失败或不足不会部分扣弹 / 取消换弹。间隔在接受时冻结并保存 Trace；切枪、重新装备或同一运行时物品移交不重置，边界 now=readyAt 可接受。

MinecraftEffectRuntime.fire 与普通玩家 `/chorus weapon fire` 从实际独立容器取实例和当前服务端视线，客户端不能指定弹数、目标或时刻。on_fire 复用类型化 DSL，通过捕获位置 / 方向 / 伤害后发射物理投射物。专属 WeaponFire.Scope 可进入 detached continuation，保留接受时的 owner / weapon / shot 标识；伤害快照时机由动作声明。射击后的来源切换不把未落地伤害归给新手持武器。武器击杀资格继续由显式 tags / kill_tags 决定。

8 项 WeaponFireTest 验证扣款先于发射、0 / 多发成本、两实例和持有者移交、精确间隔、间隔快照 / 坏值、拒绝不取消换弹、已接受开火中断、普通 spend 不发射、词法体与 codec、发射拒绝和未知结果不重放。4 项共享 WeaponFireGameTest 在两端验证普通玩家命令、真实 Kill Clip 击杀 / 换弹 / 下一发 25% 伤害和收枪后的快照、武器来源但无武器击杀信用、存活 / 旁观资格、真实换弹中断与生成后回执未知的停止。

这些证据推进 Kill Clip 的输入集成，尚不提升为完整 verified：击杀窗口收枪保留仍是内容假设，枪械参数是合成值。fire_accepted 仅表示接受，不表示发射成功或命中；实际发射回执、DamageReceipt 和显式整枪聚合各司其职。精准区域、hitscan、自动 burst 控制、蓄力 / 模式、客户端长按、弹药保存同步 / HUD 仍待实现。协议与事件顺序见 [服务端单次开火](engine-data-packs.md#服务端单次开火)。

### 动态基础弹匣容量

`AmmoState.capacity` 保存固定的未修饰输入，capacityProfile 可指定修饰持有者与 round → round 的 Profile。`CompiledEffects.ammoCapacity` 返回 AmmoCapacity.View，含有效容量与完整 CalculationProfile.Result；读取时收集当前来源和 Buff，以被查询武器身份做资格过滤，不采用补弹发起者的 holder / 强化标签。有效值不写回固定输入，重复查询不会重复放大，也不改变弹数。最终容量必须为正整数且不溢出 int；是否及何时取整由 Profile 声明。

DSL 的 ammo.capacity / missing、observe_ammo 和所有弹药动作统一使用派生视图；unmodified_capacity 暴露固定输入。动作执行前解析当前容量，默认补弹 / 生成上限使用它；显式 ceiling 仍可表达临时溢出。`AmmoActions.Change` 将纯弹药变更与采用的容量视图一起作为不可变回执，事实也保留 capacity / unmodified_capacity。如果本次写弹数改变下一次容量条件，本次结果仍保留原来采用的值，新查询才读取新容量。查询本身不发弹药变化事件。

静态来源解绑、Buff 到期和收枪策略按原有规则生效；观察结果与 capture_value 保留当时值，延迟体中的直接读取则用执行时状态。原始 AmmoState.read(capacity / missing) 对动态账户拒绝，避免宿主把固定输入当成当前值；无 Profile 的旧账户行为保持一致。数值依赖路径通过只读 Query 载荷传播，直接或跨武器的有效容量自引用明确失败；未修饰输入 / 弹数可读取，效果事件循环不受此数值检查限制。

9 项 AmmoCapacityTest 覆盖两武器隔离、来源 / Buff 合并与最后取整、到期不删溢出、百分比生成、接收账户 holder、动作前容量与后续查询区分、快照、非法 Profile / 容量和数值自引用。2 项共享 AmmoCapacityGameTest 在两端验证当前容量驱动实际治疗、真实 tick 到期、20 发溢出保留和来源解绑后的容量快照。夹具是合成参数；Fail-Deadly / Timelost Magazine 的具体计数、输入和数值校准仍未完成。

### JSON 调度约定

`schedule` 以当前来源内的本地 name 定位定时器，name 不在所有持有者之间共享。静态来源使用实例标识；Buff 使用 generation，因此同名新 Buff 无法接管旧定时器。`delay` 和 `interval` 是单位为 second 的正 Value，精确到微秒；repeat 是总触发次数，默认 1，-1 表示持续重复，重复时必须声明 interval。

```json
{
  "type": "chorus:schedule", "name": "pulse", "event": "example:pulse",
  "delay": { "type": "chorus:constant", "value": 0.070001, "unit": "second" },
  "interval": { "type": "chorus:constant", "value": 0.070001, "unit": "second" },
  "repeat": -1, "policy": "keep"
}
```

- policy 默认 keep：已有同名定时器时保留原节奏和事件上下文；replace 重设等待与次数；error 明确失败。`cancel_timer` 只取消当前来源的同名定时器。两类动作都返回 applied，表示本次是否改变了调度状态。
- `own_timer` 条件匹配 name 和完整所属来源；不是只比较字符串事件类型。多个玩家或多个 Buff 同时监听同名 pulse，不会因此执行彼此的定时回调。Buff 规则若不加该条件仍可明确监听其他来源的事件。
- 调度保存当前 EffectEvent 的 source / actor / victim / tags / numbers / flags / references / impact；Buff 生命周期触发时从该生命周期实例构造来源和持有者上下文。回调时间来自实际逻辑 dueAt，不能把原始触发时间冒充回调时间。没有可用上下文时明确报错；不猜测缺失的目标或数值。
- 同一实例刷新可再次 schedule keep，不重复创建周期；回调执行时 `by_buff_tier` 读取当前实例的强度，`by_stacks` 读取层数，二者独立。设计中的 `by_tier` 保留给敌人档次，尚未实现。查表没有对应档位时明确失败，不静默裁到最后一项。
- `schedule` 定时器与所属 Buff / 静态来源共存亡，不能在 ended 规则中为已消失的 Buff 创建新计时器。需要脱离来源的延迟动作体可用 `after` 的 detached 生命周期，见前文。独立物理投射物可恢复已编译动作体；持久化、跨重启恢复和按独立层分别调度尚未完成。连续生命恢复使用独立的区间积分机制，不依赖定时脉冲凑整。

## Buff 状态与时间线

`BuffDefinition` 声明共享 / 每层计时、整体 / 逐层衰减、刷新、暂停取整、实例绑定、层数与强度策略。`BuffInstance` 保存 generation、每层来源和到期时间、原始与历史最长时间、最高强度，以及数字 / 去重集合 / 引用 / 目标成员 / 位置组件；这些都是不可变数据。

- `Buffs.grant` 区分 requested、credited 与 storedDelta。Bolt Charge 的 x9 再请求 x4 可配置成 credited=4、storedDelta=1；资源入账仍使用 `Resources` 自己的裁剪规则。
- 共享计时器支持 reset / extend / historic_max / none；每层独立计时只接受 additive + none，不隐式刷新其他层。逐层衰减间隔必须明确填写。Rampage 的后续衰减间隔在所用单元格中没有明确值，当前测试使用通用示例间隔，不能据此声称 Rampage 已完整实现。
- 延长与重新施加是不同操作：显式延长 cap 可以把已有超长剩余时间截到 cap，历史最长时间仍保留。强度 tier 与堆叠数量独立，避免把 Restoration x2 当成两份独立实例。
- `refresh_buff` 只刷新已有共享计时器，按其 reset / extend / historic_max / none 策略处理，不制造获得层数或 `buff_gained`。实例已消失时为空操作，因此“消耗最后一层后刷新”不会重新获得一次充能。
- 收枪策略按 owner + weapon 实例匹配，支持 keep / remove / pause；暂停可把剩余时间向上取整，拿出时从暂停剩余量继续计时。当前只提供动作接口，尚未接真实武器切换钩子。实例来源默认保留首次施加来源，每层来源另存；不推断各元素状态的特殊归属规则。
- `advanceStep` 停在最近的到期时刻，提交该时刻全部到期迁移。`TimelineEngine` 已负责先处理完返回信号的反应，再继续推进到下一个时刻；`BuffRulesTest` 验证同刻冷却，`EffectTimelineTest` 验证中途改变恢复速率，`NativeRuntimeGameTest` 验证真实服务器 tick 自动到期。离线恢复尚未装配。
- `BuffRules.Scope.current` 对普通规则读取当前 generation 的实例；结束来源读取最终快照。实例被移除后，尚未开始的自身规则跳过，已开始的帧可完成剩余动作与世界回执。相同键重新获得的 Buff 具有不同 generation。
- 生命周期目前输出 `buff_gained / buff_stacks_changed / buff_refreshed / buff_ended / buff_paused / buff_resumed / buff_extended`。已解析的 Buff 定义支持 JSON，查询期修饰已接入；完整规则类型、活动状态迁移、UI 同步和跨重启序列化仍未实现。

`BuffCodecs.DEFINITION` 的 JSON 字段保持扁平：id / version / duration、max_stacks、timer_mode、decay / decay_interval、refresh / extension_cap、pause_rounding、attach / instanced_by / affects / on_stow、stack_mode、keep_highest_tier、credit_overflow、tags，以及嵌套的 components 声明。时间字段用秒，永久持续写 `"permanent"`；微秒以下的时长报错，不默默四舍五入。此 Codec 只处理已解析的定义，严格拒绝未知字段与 duration 表达式。效果包在 `EffectProgram` 的外层 Buff 条目通过 bundle 关联，动态持续时间在 `grant_buff.duration` / `apply_status.duration` 中求值；definition.duration 保留默认常量。示例为 `common/src/test/resources/buffs/frame_of_reference.json`，尚未作为游戏内内容发布。

组件声明例如 `"components": { "numbers": { "damage": { "initial": 0, "unit": "damage" } }, "sets": ["seen_damage"] }`。首次创建 generation 时初始化，刷新不重置；numeric / set / reference / target_set / position 名称互不重复。`component` 读取数值，`update_component` 支持 set / add / min / max，编译时验证名称与单位，并返回 before / after / delta、applied / changed。引用组件的读写 DSL 尚未实现。

更新动作可成对指定 `once_set` 与 `event_reference`，把例如 damage_id 记入本实例的集合，只跳过该动作已经累计过的身份。初始化与旧监听共享集合，避免施加击重复累计；清零数值不会清除身份集合，新 generation 才重新初始化。这不按 root 去重，也不限制后续事件循环。当前集合保留至实例结束，长期刷新的实例还需要有明确安全边界的清理策略；不能随意丢弃仍可能重复送达的身份。

机制反例来自 Compendium 的 CSV 快照坐标：Weapon Perks C71（Disruption Break）、C369（Frame of Reference）、Solar D7（Restoration）、Arc D5（Bolt Charge）。测试只证明相应状态机制，不代表这些 perk / buff 的所有数值、触发条件及游戏接线均已完成。

## 数值数据格式

`StatCodecs.PROFILE` 支持 `chorus:apply`、`chorus:clamp`、`chorus:curve`、`chorus:round` 步骤，示例见 `common/src/test/resources/profiles/reload.json`。这是测试数据，不是已随数据包加载的命运 2 内容。

- `apply` 显式填写 `operation` 与分组树。贡献引用 `stage` 与完整组路径，如 `melee/exclusive`。
- 同一次 `base_percent` 步骤使用同一 `percent_of` 基准；需要不同基准的贡献放在不同有序步骤里，不能先合并成一个百分比。
- 单位的规范形式为 namespaced id，内置单位支持省略 `chorus:`。`delta`、`resistance`、`multiplier` 是不同单位，不隐式转换。
- 分组中的 `families` 按 stacking key 指定策略，默认 `independent`；相同作用家族的独立实例不会自动去重。贡献 `id` 是本次查询内的实例标识，重复 id 明确拒绝，防止把同一份冻结贡献重复并入。
- 家族 `count_table` 必须给出使用到的份数，没有定义的份数报错，不默认取最后一档。`unique` 遇到多份输入报错，`max` / `max_per_source` / `priority` 则作选择。
- 当前轨迹记录阶段输入输出、分组选择、贡献来源和贡献置信度。曲线自身及基础值的来源、置信度汇总仍需补齐，不把贡献的置信度冒充完整输出的置信度。
- 资源逻辑时间以整数微秒表示，一秒为 1,000,000。分段积分需要上层提供真实速率变化时间；该函数自身不会发现 Buff 到期。

## 已实现的效果 DSL

`EffectCodecs.COMPILED` 将 JSON 解析并编译为 `CompiledEffects`；加载错误以 `DataResult` 返回。`PROGRAM` 是可往返编码的数据 AST，`CompiledEffects` 固定引用和类型环境后生成解释器定义。除纯 Java / Codec 外，已注册服务端可重载的 chorus:effect_program；目录覆盖、重载失败保留及活动运行时固定旧定义已通过两端测试。程序注册表本身尚未同步；配装页使用另行裁剪的展示快照，使用方法见 [engine-data-packs.md](engine-data-packs.md)。

程序包含 version、buffs、bundles、profiles、resources，以及可选的顶层 defense_profile。Buff 条目为 `{ "definition": 已解析的Buff定义, "bundle": 可选效果包ID, "shield": 可选护盾声明 }`；效果包声明 `scope: source / buff`，其 rules、modifiers 和 health_recovery 分开保存。静态来源通过 `EffectState.sources` 绑定实际 holder / weapon / ability 实例；收枪与拿出事件在普通内容规则之前执行对应 Buff 策略。

| 类别 | 当前类型 |
| --- | --- |
| Value | constant、result、resource、ammo、round、event_number、impact_number、buff_count、component、by_stacks、by_buff_tier、by_source_tag、choose、arithmetic（add / mul / min / max）、scale、curve、enhanced、pvp |
| Condition | constant、all、any、not、source_is、own_source、source_tag、event_tag、layer_tag、event_reference、event_flag、result_flag、resource_crossed、target_is、has_buff、has_buff_tag、has_shield、compare、own_buff、own_shield、own_timer |
| Action | grant_buff、consume_buff、remove_buff、extend_buff、refresh_buff、apply_status、check_status、select_targets、read_targets、sync_targets、difference_targets、inspect_entity、capture_position、read_position、write_position、capture_value、capture_damage、damage_snapshot、damage、heal、restore_shield、update_component、initialize_resource、grant_resource、spend_resource、refund_cost、grant_full_charge、initialize_ammo、observe_ammo、spend_ammo、refill_magazine、grant_ammo、schedule、cancel_timer、emit、play_cue |
| 控制结构 | if / then / else、for_each、after（嵌套步骤，不是世界动作） |

- 普通动作为 `{ "type": "chorus:grant_buff", ... }`。需要绑定结果时使用 `{ "action": { ... }, "as": "gain" }`；后续 Value 通过 `{ "type": "chorus:result", "binding": "gain", "field": "credited" }` 读取。布尔结果条件为 `{ "type": "chorus:result_flag", "binding": "status", "field": "applied" }`，`is` 默认 true。每一步只看得到此前已声明且处于当前作用域内的结果，绑定跨世界动作等待保留；通用可选 / 引用结果投影仍待扩展。
- 条件步骤为 `{ "if": 条件, "then": [步骤], "else": [步骤] }`，else 可省略。`event_flag` 读取事实中的布尔值，缺失报错，不把未提供的 lethal 当成 false；`target_is` 比较两个明确目标引用，例如 victim 与 self。
- 数值常量显式带 unit；省略 stacks / tier 时默认为一层 / 一级。`arithmetic.mul` 的第一个操作数保留单位，后续项必须为完整 multiplier；count 转 charge_fraction 等转换必须通过 `scale` 明写 from / to / factor，不隐式混算。
- `enhanced` 读取绑定来源的元数据，授予 Buff 时把来源标签带入不可变 Origin，后续查询不会从玩家现在手持的武器猜测。实例来源仍按此前约定保留首次施加来源；不同效果的特殊刷新归因需要另行声明和验证。`pvp` 读取 `EffectState.mode`，不以目标是不是玩家判断模式。
- `source_tag` 判断当前绑定来源的 Origin / 静态来源标签，不读取事件标签。`by_source_tag.values` 由来源标签映射到 Value，必须恰好匹配一个分支；缺失 / 歧义明确失败，不按 map 顺序猜测。各分支单位相同，支持嵌套 enhanced / curve 等表达式；捕获快照时冻结所选标签分支，其 victim 依赖仍延后。
- `choose` Value 使用 if / then / else，条件和两个分支均在加载时校验，运行时只求选中的分支；两个分支必须同单位。Value 与 Condition Codec 支持相互递归。快照若已能决定条件，只绑定所选分支；若条件仍依赖 victim / impact，则保留条件并绑定两侧来源操作数。5 项 ConditionalValueTest 验证分支惰性、单位、来源与事件标签隔离、歧义、递归 Codec 和快照边界。
- modifiers 指定 profile / stage / group / op / stacking_key / value / if，并保留 reference / confidence。编译时校验阶段、分组路径、操作、percent_of 与单位；查询时检查作用武器 / 技能和条件，收集后统一交给 stat 层。`multiplicity: stack` 把每层作为独立贡献，普通 `instance` 只贡献一次。
- 低层 calculate API 仍接受调用方提供的冻结贡献，其身份及重复收集由调用方负责；新 captureDamage / outgoing 路径自动按 evaluate 划分冻结与实时贡献，见攻击数值快照一节。查询输入必须已完成到期迁移，不能靠 Value 求值偷偷补状态。
- Value / Condition / Action 是开放接口，Codec 通过 `TypeRegistry` 扩展。测试注册自定义 Value 类型后，从 JSON 完整编译并执行。已构建的默认 Codec 不会自动吸收后注册类型。
- JSON 的 `emit` 产生新事件，可再次触发同一规则。已有预算为一个内部步骤的 40 次同规则循环测试，所有派生事件在同一逻辑时刻完成；停止条件由内容中的层数条件给出，不由 root 去重或连锁次数上限代替。
- 程序、规则、动作、表达式等记录拒绝未知字段，避免拼写错误被默认值掩盖。缺失 Buff / bundle / profile / resource、混用版本、未绑定或未来结果、单位冲突均报加载错误。事件数值字段尚无完整定义注册表；缺失测量、未初始化资源账户或实际单位错误会明确使结算失败，不能视为零。

`common/src/test/resources/effects/` 已有以下真实 JSON 时间线，执行不依赖针对 perk 的 Java 分支：

| 数据 | 已验证 | 限制 |
| --- | --- | --- |
| `kill_clip.json` | A 击杀 / B 换弹不串窗、3.6 秒边界、普通 / 强化持续时间、刷新、收枪移除、25% 查询修饰、排除爆炸词条伤害 | 窗口收枪保留是当前文档假设；实际容器开火 / 投射物击杀 / 换弹 / 下一发增伤已验；武器原型参数仍为合成值 |
| `adrenaline_junkie.json` | 武器击杀1–5层、手雷击杀直接五层、全部已绑定同持有者武器可触发、4.5 / 强化5秒、满层刷新、收枪保留、五档伤害与固定 +20 操控查询；真实死亡驱动下一击实际伤害 | Weapon Perks C10；武器 / 手雷信用由测试宿主显式给定，未接入实际装备 / 手雷物体；操控尚未连接持枪动作，特殊伤害资格仍需逐武器校准 |
| `disruption_break.json` | 两次错开施加、每层分别相乘 / 到期、动能资格、排除冰影 / 缚丝、PvE / PvP 时间分支 | 基础词条；强化持续时间在 C71 未知，未声称支持该变体；实际破盾已输出层标签，但此词条的武器来源与防御 Profile 尚未自动装配 |
| `combat_profiles.json` | Kill Clip 与 Disruption Break 的修饰接入真实伤害，再经过测试减伤、盾、护甲、吸收；两端验证玩家只计算一次、门槛、完全减伤、嵌套和缺失运行时拒绝 | Buff 预置；50% 减伤和 5 点盾是测试参数；完整触发来源、真实武器与数据包自动装配未完成 |
| `damage_snapshot.json` | 来源值和条件冻结、目标读数延后、on_hit 实时贡献、逐层身份与完整分组、旧 Profile 保留；两端实际延迟伤害与替换运行时后的旧攻击 / 新防御 | 合成参数；本夹具由宿主保存快照；后续 projectile.json 已验证物理投射物携带，完整反应规则和派生继承、更多取值时机及持久化尚未接入 |
| `impact_snapshot.json` | 来源加伤冻结，逐目标距离进入独立 falloff 阶段；实际扣血、查询后移动、on_hit MAX / 当前防御 / 盾层读取和事实中的输入结果隔离 | 曲线与数值是合成验收参数；字段由内容 / 宿主明确提供，不代表任何 D2 perk 已校准 |
| `action_origin.json` | 绑定 / 事件来源独立选择；甲施加乙触发，乙的数值贡献及原版 DamageSource、实际击杀 / 治疗归属、非武器信用、延迟快照与状态到期 | 合成反应及数值，非 Jolt / Voltshot 的完整状态、触发和缩放定义；origin_bundle 另由 reaction_binding.json 验证 |
| `entity_observation.json` | 显式观测原版生命 / 吸收 / 玩家类别，缺失与死亡区分，延迟及世界变化后仍使用观测值 | 合成低血量回血；不隐式决定 PvE / PvP，不含 D2 等级 / 勇士分类或 Chorus 护盾总量 |
| `jolt.json` | 施加击 / 刷新、阈值、冷却、触发者来源、实际玩家损失后的中心链伤、致死阈值和相邻连锁 | 0.1 投影、余量 / 冷却累计及多来源策略待校准，缺少 Overload 眩晕、正式伤害类型和真实施加来源 |
| `voltshot.json` + `jolt.json` | 同版本片段链接；击杀 / 换弹双窗口、普通 / 强化、收枪、武器隔离、命中消费、真实 Jolt 非武器击杀与两个时间边界 | 需先链接；通用容器和手动换弹已接线，该片段与其及单次开火的集成、多弹丸事务尚未验收，失败消费等策略及完整 Jolt 校准待完成 |
| `delayed_snapshot.json` | JSON 捕获攻击、默认来源生命周期或显式 detached、词法结果捕获、循环 / 嵌套延迟、未来查询、暂停与同刻排序；两端实际扣血后回血、解绑取消、未来范围伤害与独立多次命中 | 合成机制；附加纯测试验证 capture_value；本例不是物理投射物；延迟退款与跨重启恢复未接入，不是 Kinetic Tremors 的完整内容 |
| `fixed_position.json` | 一次坐标 / 伤害快照，三次独立查询与范围扣血；原实体移除、来源卸下后仍按激活位置执行，新目标能进入后续波次；位置类型、缺失、维度与过滤验证 | 半径、伤害、100 / 150 / 200 ms 为合成参数；没有命中计数、武器阈值、敌人等级或 Kinetic Tremors 的完整内容；不包含碰撞点和独立场物体 |
| `kinetic_tremors.json` | 12 类武器普通 / 强化门槛、逐目标逐武器计数、同一 damage_id 不重复累计 / 刷新、3 秒窗口、收枪保留、0.25 / 1.25 / 2.25 秒固定点三波与 4.25 秒冷却边界；PvE / PvP 最大值、初始 Miniboss / Boss 因子、攻击快照与来源保留；两端真实命中 / 伤害 / tick 验证 | Weapon Perks C135；0.1 投影、6 米内统一最大伤害为未校准内容选择；完整敌人等级 / 动能倍率、距离衰减、正式伤害类型、武器 / 直击 / 等级来源装配及部分触发策略待完成 |
| `healing.json` | 实际扣血后回血、治疗结果绑定与事实分发、相同回执幂等、被拒绝的量不算 overheal；两端真实治疗与逻辑周期、NeoForge 取消 / 改量 / 嵌套隔离 | 通用机制；不代表完整 Cure / Restoration；自然治疗观察未接入 |
| `periodic.json` | JSON 调度、刷新保留节奏、按 tier 取值、暂停余量、实例隔离、到期取消、上下文保留、重设 / 取消 | 通用离散周期，不代替连续恢复积分或逐独立层调度 |
| `cure.json` | x1 / x2 / x3 的 PvE / PvP 总量、50 / 100 ms 两次恢复、1 秒冷却、重复请求不刷新；两端真实 tick 运行 | Solar D4 总量与冷却；0.1 缩放和两次等量脉冲为测试内容选择；实际技能 / 装备触发与细分曲线未校准 |
| `restoration.json` | 强度 / 环境速率、历史刷新、互斥来源继续计时、到期残段、暂停不补发、原版实际量 / 溢出与真实 tick；过量恢复不储存 | Solar D7 与 Class Abilities D15；取高覆盖为暂定，未含 Phoenix Dive 例外、Rift 空间物体或实际技能输入；共享 presence 只有交互标签，补盾规则在 healing_rift.json 中 |
| `stored_position.json` | 保存 / 复制 / 清空、刷新、新旧 generation 结束快照、延迟与来源解绑；7 项纯核心测试 | 带维度的内存位置值；没有坐标运算、接触点、物体或跨重启序列化 |
| `shield_restoration.json` | 现有层补充、动态 / 默认上限、容量不降低、请求与溢出、generation / FIFO 保留、世界等待 / 嵌套扣盾与去重、资格条件和快照；8 项纯核心 | 合成护盾和 Void 标签测试层；不是完整 Void Overshield 数值或实际来源 |
| `shield_recovery.json` | 每层连续回充、精确到期残段、上限边界、延迟重置、暂停、回执去重与 generation 隔离；9 项单测和 2 项共享世界场景 | 2.5 HP/s 与 70.001 ms 等参数为合成机制验证；原版生命未参与恢复 |
| `under_over.json` + `shield_scaling.json` | 普通 / 强化按四类接收盾查询倍率、Woven Mail 躯干分支、武器隔离、爆炸排除、快照和原版溢出；9 项纯核心 / 3 项共享世界场景 | Weapon Perks C239；盾容量 / 标签和 MAX 为合成验证，原作跨层叠加、真实装备 / 盾分类及完整 Woven Mail 未完成 |
| `eternal_warrior.json` | 75 HP 护盾、停伤 5 秒后以满量 / 7 秒回充、破盾终止、结束 / 死亡清理；3 项内容单测与 1 项共享世界场景；排除精准因子见下一行 | Exotic Armors F34，0.1 测试缩放；明确技能输入与生命周期策略，完整增伤 / 超能延长未完成 |
| `precision_damage.json` + `eternal_warrior.json` | 标记精准阶段、按层排除且保留固定加值、原版溢出、冻结来源与未来命中倍率；9 项效果单测和 3 项共享世界场景，另有 7 项纯数值测试 | 武器倍率 / 固定加值为合成输入；跨层比例为 Chorus 约定，自动精准识别和原作精确行为未校准 |
| `healing_rift.json` + `restoration.json` | 每次施放固定位置、5 米友方范围 / 自身、15 秒、40 / 35 HP/s 的 0.1 缩放、成员进出与晚进入者清理、重叠独立来源和恢复通道、满血脉冲盾 / Void 阻止 / 交互资格；11 项内容纯核心 / 8 项共享世界测试 | 部分 Rift 内容；50 ms 采样、原版队伍与脚底球形范围、参照缺失时结束为当前适配；补盾脉冲时序及共享池策略待校准，施放动作、技能成本和表现未接入 |
| `membership_aura.json` | 采样成员差分、进入 / 离开一次、中心跟随、重叠来源、刷新保留、新 generation 清空、结束快照和实际恢复；10 项纯核心及 3 项共享世界测试 | 3 米 / 0.3 秒 / 50 ms / 2 HP/s 合成场；不是 Rift 数值；该移动场未使用位置组件；完整技能来源及场物体未接入 |
| `source_attachment.json` | 来源附加只初始化自身、相同绑定为空操作、替换取消旧定时器、解绑前结算恢复、已有 Buff 不被擦除 | 通用装配协议；实际装备容器、来源持久化与跨维度迁移未接入 |
| `resource_regeneration.json` | 两份顺序充能、恢复 Profile / PvP 倍率、Buff 到期分段、正反阈值、一次性入账、成本分支、实际 tick 与回血、满值不储存、十进制边界 | 通用合成参数；不是命运 2 技能回能数值或 CES 校准；数据包账户另验证解绑重挂不重置 |
| `resource_refund.json` | 同笔成本分次返还、跨分支 / 世界等待 / 未绑定结果的额度、不补领溢出、免费 / 失败支付为零、退款回原账户、完整充能保留部分进度、实际返还驱动世界治疗 | 通用合成参数；只支持本动作序列引用成本，延迟命中 / Buff / 跨事件保存回执及持久化未接入 |
| `weapons.json` + `kill_clip.json` | 实际容器、弹药初始化、时间快照、当前容量 / 储备结算、切枪 / 资格中断、完成事件与异常保留 | 武器参数和治疗为合成值；Kill Clip 的击杀事实由测试宿主提供；射击 / 逐发 / 技能换弹及保存恢复待完成 |
| `ammunition.json` | 整数弹药、双武器隔离、有限 / 无限储备、转移 / 生成、取整、延迟和实际治疗 | 合成数据；无真实开火、换弹资格、物品持久化或完整弹药 perk |
| `ammo_capacity.json` | 查询期基础容量、当前百分比、叠加溢出、到期保留和显式容量快照 | 合成参数；Fail-Deadly / Timelost Magazine 内容尚未实现 |
| `credited_resource.json` | x9 再请求 x4，实际只加一层但按 credited=4 计算收益，跨世界回执继续后续动作 | 通用机制样例，0.1 能量转换系数是测试输入，不是 Bolt Charge 的真实数值 |
| `expiration_reaction.json` | Buff 已移除后，自身 ended 规则读取最终层数查表，先完成到期收益 / 世界动作，再处理外部事件 | 通用机制样例，无真实游戏效果数值主张 |
| `slice.json` | 职业技能触发五次额度、基础 / 强化时间、收枪保留、非致死与免疫命中、状态成功后消耗 / 刷新、已 Sever 的目标跳过、最后一层不重建、重复或错误回执；双加载器 GameTest 中真实图腾救活后施加并消费一层 | Sever 只验证存在性及基础 PvE / PvP 时长，未实现减伤与延长配置；状态被拒绝时暂按不刷新处理，原作此边界待校准；职业技能与真实武器来源装配未完成，普通命中观察本身已接入 |
| `accumulator_initialization.json` | 施加击初始化与已有监听累计一次、刷新保留数值、清零后旧身份不再累计、重新创建时初始化、单位和集合配置校验 | 通用机制，投影是测试输入；Jolt / Volatile 部分内容另有独立夹具，完整玩法仍未验收 |
| `volatile.json` | 施加击排除、两种环境的阈值 / 时长 / 伤害、每伤害累计一次、目标共享冷却与多人来源、首次致死施加资格、已有状态死亡只引爆一次；两端真实范围伤害与相邻目标连锁 | Void D9；缩放、线性衰减和首次来源 / 不刷新是内容选择；伤害投影、目标等级、真实技能与装备来源、正式伤害类型和免疫 / 阵营仍需校准或装配 |
| `radial_falloff.json` | 每目标捕获距离驱动带单位曲线；3 米内 10 点、5 米 5 点、7 米零伤害；排除指定实体、最近排序、数量限制、移动后仍按捕获距离在两端实际扣血 | 合成基准伤害和线性衰减；不是 Chain Reaction / Dragonfly / Firefly 的完整定义或已校准曲线 |
| `target_iteration.json` | 固定球形查询结果后顺序伤害每个目标，按该目标实际伤害回血；嵌套循环、计数、目标引用作用域、重复回执、分支、跨迭代退款测试；双加载器验证距离边界、队伍关系、移除和新目标 | 通用合成场景；没有视线、D2 敌我分类、空间场进出或投射物机制 |
| `branches.json` | 两个分支可各有同名结果、嵌套结果条件、跨世界等待、条件改变不重选分支、禁止结果跨作用域 | 通用执行协议，无真实游戏效果数值主张 |

上述 Adrenaline Junkie / Kill Clip / Disruption Break / Slice 数值已核对本地 Compendium 2026-10-05 导出的 Weapon Perks C10 / C132 / C71 / C198；Sever 基础时间取 Strand D8 的基础项，未纳入其延长项。坐标仍为 CSV 快照坐标。Arc D8 确认 Jolt 计入施加击，但没有明确冷却期伤害是否储存、溢出如何处理，累积示例不补写这些未知规则。Rampage C172 描述逐层衰减，但未单列后续掉层间隔，待核对，不把固定间隔假设标成已验证数值。球形选择与逐目标动作已接入；更多目标过滤、物理命中 / 投掷物、伤害投影、更多组件操作、完整技能种类与状态规则语法糖仍未完成，不能由这些程序推断全表覆盖。

## Kinetic Tremors 的当前内容边界

`Weapon Perks!C135` 的门槛按来源标签取值：SMG 14/13、Auto 12/11、Pulse 11/10、非点射 Sidearm 8/7、180 Hand Cannon 与其他 Scout 6/5、120 / 150 Scout 与 140 Hand Cannon 5/4、Micro-Missile Sidearm 4/3、Bow / Sniper 3/2，斜线后为强化。分类标签来自宿主，不在 Java 引擎里按 perk 名分支；一份来源命中多个分类会在修改计数前失败。未知类别或缺少目标 rank 标签不会猜测为某一档。

隐藏计数 Buff 挂到目标并按武器实例区分，组件保存 hits 与当前 generation 已处理的 damage_id；只对新增直击刷新 3 秒窗口，收枪不清除。按 Chorus 的到期顺序，恰好到 3 秒边界先清除旧计数。达到门槛时捕获目标位置与攻击，消费计数，显式创建 detached 三波，冷却截止为触发后 4.25 秒。各波重新选择 6 米内的目标，伤害标记为 weapon / kinetic，击杀保留 weapon kill，震波不带 direct_weapon_hit，因此不能反向算作本效果的直击。

**以下是明确的内容选择或缺口，不能当成完整原作数值：**

- 最大伤害 90 [PvP 20] 乘 0.1 转为本夹具的 MC damage；Miniboss / Boss 使用文本的 33.3% 即 1.333，完整 Combatant Rank Modifiers 和 Kinetic Bonus Damage 尚未装配。规则环境决定 PvE / PvP，不按目标是否为玩家自动切换。
- 目前该内容夹具在 6 米内统一使用最大值，没有声明已校准的距离衰减。通用 impact_number 已支持把逐目标距离放入快照 Profile 的独立阶段；具体曲线及 Kinetic Tremors 数据装配仍缺失，不能把合成线性测试当成原作曲线。
- 位置在达到门槛时捕获，攻击修饰与初始目标类别也在此时冻结；冷却按目标 + 武器实例隔离，期间直击不储存新计数。只计 applied 的直击；捕获位置失败时消费本次计数而不制造震波。这些边界还需逐项核对原作。
- 武器实例 / 类别 / 强化、直击信用和目标 rank 标签由测试宿主提供；生产装备、敌人分类与正式伤害类型尚未接入。关系仍用当前施加者的原版队伍信息，离线 / 跨维度阵营快照未提供。

9 项 KineticTremorsTest 覆盖 24 个门槛组合、去重与超时、目标 / 武器隔离、冷却边界、缺失位置、PvE / PvP、初始类别和来源快照。2 项共享 GameTest 由原版真实命中启动 JSON，通过真实 tick 验证收枪 / 卸下 / 原目标移除后的三波实际伤害，以及普通 / 强化武器独立计数与冷却后重新累计。它们证明当前数据规则可执行，覆盖状态为 partial。

## 装备装配与来源生命周期

`EffectProgram.equipment` 可声明内容包自己的槽位、原型、固定效果、可选词条与标签数量限制，编译时验证 bundle 引用和 source 作用域。跨片段链接合并这些声明并拒绝重复 id。`Loadout` 保存 holder 对应的实例 / 原型 / 选项，以及可选的 drawn 武器槽；所有数据不可变，Codec 拒绝未知字段。

`EquipmentChange(holder, before, after)` 在时间轴先结算旧状态，再校验完整 before 装配及其来源，整体提交新装配和全部来源。切枪对已有 Buff 应用 on_stow 策略；随后派发武器、Buff、来源与 equipment_changed 事实。来源实例按 holder + 物品实例 + 效果键 / 插槽键生成，同一实例移动槽位不重新初始化。equipped 来源收枪保留，drawn 来源按当前武器切换；weapon_drawn 条件查询绑定来源的 owner / weapon，on_use 数值快照固定捕获时的持握结果。

`SourceBatch` 对非装备来源提供同样的旧值核对与整体更新；保留不变条目作为前置条件，先提交新集合，再按身份排序发出全部 detached、全部 attached。SourceChange 共用此实现。来源事实保存完整旧 / 新快照，own_source 比较整个来源；旧来源在 detached 事实中可执行自己的清理规则，普通过期绑定仍不获准触发。相同绑定为空操作，替换清理旧来源定时器；已经授予的独立 Buff 和 detached 延迟动作不被默认擦除。来源清理和其他世界动作的失败保留已经提交的状态，不自动重试未知结果。

11 项 EquipmentTest、3 项 SourceBatchTest 和 SourceChangeTest 新增的卸下 / 强化替换回归覆盖：完整来源在第一条反应前可见、旧值冲突拒绝、选项 / 金装限额、跨片段引用、移动不重置、定时器替换、旧标签清理、攻击快照及物品提交写集先于反应。3 项共享 EquipmentGameTest 验证原版实际扣血、初始化与清理的真实回血、非法换装无副作用、真实 tick 中 Buff 暂停 / 恢复，以及世界回血成功但回执异常后的已提交装配和待确认操作保留。

`MinecraftEffectRuntime.equip` 仍是可信元数据入口。现在玩家通过 `PlayerEquipment` 容器转移真实物品，先完成身份 / 数量 / 装配 / revision 校验，再转移物品并提交元数据，随后执行反应。已失败的运行时不自动重试，物品可取回空背包槽。最小 equipment 命令只操作发起者自己的背包；创建物品身份的 stamp 另需管理员权限。

容器存储独立于当前程序：完整 ItemStack 与持握槽 / revision 保存到玩家 NBT，定义缺失时停用效果投影并保留物品。respawn 移动旧玩家剩余装备，而不复制所有权。死亡遵循 keepInventory、消失附魔及原版 / 加载器掉落边界；NeoForge 使用 dropWithoutEvent 加入外层 LivingDropsEvent，避免普通 ItemTossEvent 清空死亡收集器。运行时在 prepare / 事件结束 / tick 跟踪真实玩家容器；反应中发生死亡也会在结束后解除来源，活着但容器暂时为空的已跟踪玩家不会提前丢失观察。

5 项共享 PlayerEquipmentGameTest 覆盖实际命令及权限、单份物品与组件保留、重复身份 / 多件 / stale revision 拒绝、玩家 saveWithoutId / load 往返、未知目录取回、真实死亡保护 / 掉落 / keepInventory / 消失 / restoreFrom、已完成转移后的世界异常，以及 attach 反应内死亡。NeoForgePlayerEquipmentGameTest 额外验证装备只进入一次 LivingDropsEvent、没有 toss 事件，并遵循死亡掉落取消；其他模组取得或取消掉落后，不把一份复制回装备容器。

当前 draw 只改变装备的持握选择；尚未把实际枪械输入、原版主手或攻击信用自动绑定到该选择。EquipmentGameTest 的实际武器伤害仍由测试宿主明确提供来源。独立容器网络快照与基础配装页已实现；HUD / 完整 D2 配装 UI、基础属性 / 装备附魔适配、完整 D2 原型 / 插槽、跨维度 / 离线活动 Buff 迁移及完整服务器重启 / 多客户端游玩仍待完成或验收。合成 equipment.json 不增加 Compendium 内容覆盖条目。

## 装备客户端同步

`EquipmentNetworkServer` 以真实 ServerPlayer 为主体建立页面会话，只接受操作引用。服务端会话 token 与展示序号之外，还核对完整背包 / 组件、装备 revision 和固定运行时对象；同版本替换运行时也会使旧页面请求过期。已处理请求推进序号，重复报文不重复转移物品。页面只接受匹配自身、玩家和维度的展示，等待服务端结果后重建；不会乐观移动物品。订阅有变化才更新，关闭 / 移除 / 换维度后清理。

4 项共享 EquipmentNetworkGameTest 覆盖实际 StreamCodec 往返与组件保留、不可变快照、其他玩家 token、重放 / 旧背包 / 运行时替换、鼠标持物、页面关闭和异常后的已转移物品 / 取回。这些测试直接调用服务器端处理器；真实客户端注册、通信与渲染由单独的客户端测试验收。

`EquipmentScreen` 默认 K 打开，提供分页装备槽、主背包、交换 / 取回 / 移槽 / 持握 / 收起。目录可引用 presentation，客户端加载有限的 equipment_two_panel 主题；缺失资源回退，D2 配色及装备槽翻译位于 assets/chorus_d2。数据格式、协议边界和操作说明见 [装备展示协议](engine-data-packs.md#装备展示协议与最小配装页)。这些通用夹具不增加 Compendium 覆盖条目。

## 技能解析与接受施放

`EffectProgram.abilities` 定义即时动作技能；Bundle.ability_overrides 使装备来源和 Buff 可在施放时替换同槽技能。AbilityLoadout 只保存基础选择，时钟、装备和来源更新保留它。替换分级应用，同优先级不同目标拒绝；条件在当前输入边界执行，不缓存服务器运动状态。只读解析一次固定最终定义、参数和成本 Profile 结果；资源支付提交后才运行资源事实和 ability_used，on_use 使用独立 cast 归属。

`MinecraftEffectRuntime.useAbility` 只接受本维度存活的非旁观玩家，服务端采样运动标志，预先解析纯变更，再通过 AbilityUse.Commit 在事实前提交。拒绝请求没有施放效果或支付。世界动作回执未知保留已扣成本和 pending 操作，并阻止自动重试。最小 choose / clear 管理命令与 self use / status 已接入两端；选择入口尚不实现玩家解锁 / 子职业约束。

7 项 AbilityTest 覆盖支付前后的可见状态、能量不足 / 条件 / 冲突拒绝、基础选择与时钟保存、换技能不回满、source / Buff 优先级替换与到期、免费成本与退款累计上限、Profile 参数 / 成本以及 detached 快照、Codec / 跨片段链接 / 单位和生命周期校验。4 项共享 AbilityGameTest 验证真实命令权限、资源先扣再实际回血、服务器 sprint 状态替换、死亡拒绝、真实 tick 回能和延迟效果，以及世界已回血但回执异常后的扣费保留 / 不重放。

当前不是完整技能系统：即时动作没有持续施放寿命，延迟动作须明确 detached；cast_cost 只在同一 on_use 执行帧内退款，不能跨事件 / after 复用。资源仍是固定目录的顺序能量池；首次选择槽位初始化该槽候选池，后续选择不重置。没有技能 / 资源 NBT、跨维度迁移、自动子职业装配、技能按键 / 网络、完整自动施放上下文装配、D2 专用投掷物 / 移动 / 引导种类、parent 继承及完整 Compendium 技能数值。数据与命令见 [技能入口](engine-data-packs.md#技能选择与施放入口)，合成 abilities.json 不增加已审阅条目数。

## 空间查询与视线筛选

TargetQuery 现有 Sphere / Cylinder / Cone 形状和明确的实体取样点。TargetArea 使用类型化表达式，所有长度为 meter；旧 radius 字段仍表示球形，area 与 radius 同时出现或都不出现时拒绝。圆柱沿世界 Y 轴，圆锥以显式捕获的带维度方向为轴，定义轴长和远端半径；缺失方向与错误维度不回退到默认朝向。DirectionQuery / capture_direction 的不可变结果与已有位置结果可跨 detached 延迟动作保留。

MinecraftWorldActions 在已加载的存活非旁观 LivingEntity 中，依次执行关系 / 排除、形状和可选视线筛选，最后排序与 limit。取样点可选脚底、身体中心或眼睛；位置捕获也能选 anchor。非球形回执保存相对坐标并校验几何与距离。视线使用原版方块 COLLIDER 射线，忽略流体与实体；封装的 BlockGetter 只读取已加载区块，未知端点和路径保守判不可见，不加载新地形。默认不启用视线，不改变既有范围效果的资格。

6 项 SpatialQueryTest 覆盖有限区域边界、轴向 / 斜向归一化、非法几何、回执坐标校验、DSL / 旧 radius 往返、参数单位和引用作用域、错误方向回执、延迟方向 / 位置快照。4 项共享 SpatialQueryGameTest 覆盖遮挡后才取最近目标、墙变化不改写旧结果、半砖 / 玻璃 / 水、未加载端点、取样点差异、缺失 / 跨维度方向，以及施法者转身 / 移动 / 移除后的真实延迟锥形治疗。

扩展测试集合还暴露了旧 ActionOriginGameTest 将目标放到 x=12、超出稳定加载范围的问题；保留实体间距离而改用竖直排列，使该归属测试不依赖邻区块偶然加载。该修正不改变引擎的“只查询已加载实体”规则。

区域查询仍判断明确取样点，而非实体碰撞箱与区域相交；独立射弹和墙面法向见后文物理投射物。碰撞反弹、多个可见点与逐效果遮挡材质策略仍待实现。Compendium 的扫描 / 锥形要求用于确认机制需求；实际技能参数和形状仍须逐项校准，合成空间测试本身不增加内容审阅数；电弧箭的实际内容证据见下一节。格式见 [区域与视线](engine-data-packs.md#区域形状方向快照与视线)。

## 电弧箭的扫描与连锁内容

[arcbolt.json](../common/src/test/resources/effects/arcbolt.json) 依据固定快照 Arc B28 / D28 / N28 建立部分内容。宿主提供落地事实，首先选取落点身体取样点 12 米内视线可见的最近非友方目标；保存该身份和攻击快照，1 秒后执行。每次伤害前保存当前目标身体坐标，实际 HP + Chorus 护盾 + Absorption 损失大于 0 时，再从该点扫描 10 米内最近的未命中目标，最多命中 4 个。范围筛选明确排除施加者、测试落点实体和本次先前目标；目标致死并从世界移除后，已保存的位置仍能作为下一跳中心。

该流程完全由现有 select_targets、capture_position、capture_damage、after、for_each 和条件动作组合。四目标上限属于 Arcbolt 内容，绝不是全局反应次数上限；不同施放有独立的排除列表和来源，仍可再次命中相同实体。使用事件来源保存本次 cast 身份，卸下来源不会取消已经发出的 detached 动作。

4 项纯核心测试验证两种模式 521 / 85 × 0.1、1 秒边界、每次新查询 / 排除、同批独立施放、来源过滤、不可用观察、零损失 / 各类失败与纯吸收伤害。4 项共享游戏测试验证可见目标优先于更近的遮挡目标、目标移出初始范围后仍被命中、致死移除后继续、真实 tick 时序、伤害取消 / 目标消失时停止，以及中途建墙的明确策略。

**仍为 partial。** 初次锁定后不再复验距离或视线、后续连锁无额外延迟与视线、零损失停止而不选下一个，均为当前内容选择，原作尚需校准。投掷重轨迹、真正碰撞点、技能输入 / 成本 / 冷却、完整目标缩放、Lucky Raspberry 与碎片交互尚未实现。测试宿主暂以 LivingEntity 表示落点，不对外声称已实现手雷实体。来源与假设见 [规则集](d2-ruleset.md#电弧箭手雷arcbolt-grenade)。

## 物理投射物与接触动作体

`EffectProgram.Projectile` 使用 `projectile / as / do` 声明独立飞行与接触动作体。位置、方向由类型化快照提供，速度、重力、每 50 ms 阻力和寿命在发射时求值；接触动作体独立编译，保留来源 / 技能上下文、已有不可变结果和伤害快照，付款 / 退款权不跨帧。发射是带精确回执的世界命令，缺失几何、错误维度、未知区块及宿主拒绝明确返回失败，不自动退款或重试。

`chorus:effect_projectile` 是注册到两端的实际实体。服务端每 tick 使用保存参数推进，以扫掠线段检测方块碰撞形状及已加载存活非旁观 LivingEntity，排除施加者；先截断到墙，再比较实体命中，避免高速穿过目标或穿墙直击。方块使用中心射线，实体碰撞箱扩张 0.125，尚非完整弹体体积扫掠。方块接触保留世界坐标和单位法线；实体接触提供单元素身份集合，其他接触类型为空集合。区域动作可直接以碰撞结果作为固定位置中心。

每次碰撞先提交接触计数，再向原运行时提交接触事实；非终止的反弹 / 穿透继续余下飞行，每次动作体使用独立操作身份。终止碰撞、寿命结束和未知地形先消费实体。世界动作失败会停止余下飞行，诊断进度也标为 terminal。世界动作结果未知时，保留已提交伤害、停止该运行时，实体不重放，也不因异常导致整个服务器崩溃。运行时停止 / 替换 / 已失败时，旧飞行物移除，不向新程序提交过期行为。投射物不保存到区块 NBT、不能跨维度 / 传送门；活动迁移与重启恢复尚未实现。

新增 6 项 ProjectileCollisionTest 和 7 项共享 ProjectileCollisionGameTest 验证精确 tick 末端 / 起点面 / 近表面 / 嵌入接触、独立计数预算、无限次数、同 tick 有序穿透与多次反弹、离开碰撞箱后重入、同目标两次、冻结攻击读取逐次衰减、伤害取消仍计接触以及非终止接触失败后停止飞行。计数规则由内容显式声明，不是引擎事件循环限制；追踪 / 敌人间转向由下述独立策略补充。

6 项 ProjectileTest 验证跨来源卸下的伤害 / self 保留、独立飞行乱序命中、接触点与空目标集合、到期分支、Codec / 单位 / 词法范围 / 退款隔离、几何与回执一致性。5 项共享 ProjectileGameTest 验证真实玩家技能支付 / 物理飞行 / 来源卸下后的冻结增伤与回血、撞墙接触点 / 范围效果、重力 / 阻力 / 到期、发射拒绝与未知地形、运行时停止，以及世界结果未知后的消费与不重放。

Fabric 的真实集成客户端验收还验证原版实体跟踪协议中的出现 / 删除、真实墙面反弹后的速度同步和物品渲染，并检查 `chorus-projectile-tracked.png`；当前外观为紫水晶碎片占位。NeoForge 对应实体与渲染器已注册和编译，服务器行为已验收；本轮未做 NeoForge 客户端交互验收。它们不等于命运 2 投掷轨迹或表现已校准，合成参数不增加 Compendium 内容覆盖。具体语法和物理边界见 [投射物 DSL](engine-data-packs.md#物理投射物与碰撞动作)。

`ProjectileTracking.Policy` 保存半径、转向速率、扫描半角、目标取样点、关系、视线和接触重选策略。`EffectProjectile` 每 tick 从已加载实体选择并保持身份锁定，持续转向保留速度大小；接触后可重新选敌并立即转向，继续余下扫掠。选敌沿用当前施加者阵营；命中后排除尚未离开的碰撞箱和耗尽的每目标额度。动作体失败仍在重定向前停止飞行。

6 项 ProjectileTrackingTest 验证最短弧角度预算、零转向、反向 / 近反向、随机方向性质、类型与编解码，以及来源卸下后的捕获策略。8 项共享 ProjectileTrackingGameTest 验证实际转向角、锁定移动目标、扫描锥 / 半径、同 tick 三目标转向、墙面反弹后伤害、实时阵营 / 遮挡 / 失去施加者、any 和目标死亡重选、关闭重定向，以及真实 tick 下来源卸下后命中移动目标。此处只验收通用机制；新增审阅的 Shield Throw、Withering Blade、Threaded Spike 保持 unimplemented，没有用通用夹具冒充各自的技能定义。

## 后续覆盖工作

1. 在已固定并逐格核对的全 Compendium 来源上，继续人工区分效果、说明、表头和公式，扩展 `data/compendium/review.json`。当前只审阅了首批 34 个条目，不把导入完成当成逐条分析完成。
2. 按覆盖清单继续建立 `需求 → 数据定义 / Java 机制 → 时间线测试 → 游戏接线测试` 映射。审计器检查来源是否改变、文件和测试符号是否存在；完整效果验收仍需逐项判断真实执行证据。未知或冲突数值保持显式未知，先覆盖机制，不虚构参数。
3. 继续扩展 DSL：可选 / 引用结果投影、集合及引用组件操作、更多目标过滤 / 排序和真实世界动作；继续跑通 Rampage 及其余金标准，并补齐 Jolt / Volatile 的内容缺口。资源恢复 Profile、阈值、同序列成本返还已由数据声明，下一步需要技能属性 / CES 装配、跨事件成本引用及完整事件测量定义。整数弹药已接入，动态基础容量已由查询期 Profile 提供，物品原型容量输入和整弹匣手动换弹已接入，单次开火已接入，下一步补齐精准 / 多弹丸事务、逐发 / 技能换弹及账户持久化迁移。
4. 在已通过双加载器服务端测试的攻击 / 防御 Profile、伤害、护盾与 tick 运行时上继续扩展物理投射物的显式目标 / 返回 / 接回与共享弹跳次数、完整反应规则绑定、更多战斗上下文、生命层专属修饰、独立装备和技能系统，继续装备 / 技能的数据装配、持久化、活动状态迁移与同步。现有场景通过不代表多人同步、任意第三方模组交互或完整游玩流程已经验收。

## 验证命令

在仓库的 Java 25 开发环境里执行：

```sh
./gradlew :common:test :fabric:compileJava :neoforge:compileJava --console=plain
./gradlew :fabric:runGameTest --console=plain
./gradlew :fabric:runClientGameTest --console=plain
JDK21=/absolute/path/to/jdk21 ./gradlew :neoforge:runGameTestServer --console=plain
python3 tools/compendium.py check
python3 tools/compendium.py report --check
python3 -m unittest discover -s tools -p 'test_compendium.py'
```

若终端仍使用 Java 21，先进入仓库配置的开发环境，或用 `-Dorg.gradle.java.home=/absolute/path/to/jdk25` 指定已安装 JDK 25。NeoForge 的资源下载辅助任务另需 Java 21 工具链；仓库已配置从 `JDK21` 环境变量发现它，macOS 指向相应 JDK 的 `Contents/Home`。这不改变 Gradle、编译和游戏使用 Java 25 的要求。不要降级 Minecraft、Java 或 Loom 来绕过配置。

当前 514 项 JUnit 和 10 项 Compendium 数值 / 素材来源审计测试通过，两端发布 jar 构建成功。Fabric 26.3 服务端的 203 项 Chorus 测试通过，连同原版 `minecraft:always_pass` 共 204 项；NeoForge 26.3.0.45-beta 服务端运行相同 203 项及 10 项专属阶段 / 掉落测试，连同原版共 214 项。日志分别位于 `fabric/build/run/gameTest/logs/latest.log` 与 `neoforge/build/runs/gametest/logs/latest.log`。本轮提交拆分还按暂存树在隔离检出中验证了中间阶段；这些日志路径相对于实际运行的检出目录。

共享场景覆盖原版伤害与状态、普通命中观察和时钟、分层盾、盾后护甲 / 吸收、取消 / 免疫 / 格挡 / 冷却不扣盾、同帧读取已提交盾量、扣盾后的原版重入、玩家盾与异常写集保留，以及攻击 / 防御 Profile 的 8 项、显式治疗的 6 项、JSON 调度的 3 项和连续恢复的 3 项实际运行场景；调度中的真实 tick 测试同时覆盖 Cure、连续恢复到期残段与资源恢复 Profile / 阈值 / 成本分支。NeoForge 专项包括伤害阶段的 3 项与治疗回调的 6 项。另有 3 项数据包 / 管理命令场景覆盖实际加载、覆盖重载、坏包失败保留、固定旧定义与资源解绑重挂不重置；3 项资源返还 / 完整充能场景验证实际额度驱动世界治疗、满容量溢出不能补领、免费和失败成本为零。另有 3 项弹药场景验证两把武器独立、有限转移 / 生成、真实延迟 / 来源解绑、合格换弹事实隔离，以及未知世界回执不重放。另有 4 项手动换弹场景验证实际容器、普通玩家命令、真实 tick 完成、Kill Clip、切枪 / 死亡 / 旁观者 / 移除和未知世界反应后的余额保留。另有 4 项单次开火场景验证真实容器 / 命令、投射物击杀到 Kill Clip 下一发伤害、收枪后快照、信用隔离、存活资格、换弹中断和未知发射不重放。另有 4 项整枪场景验证三颗物理弹丸实际回执 / 收枪归属、外部移除、不完整截止与寿命停止飞行，以及未知伤害结果不重放。另有 5 项 One-Two Punch 场景验证真实装备 / 开火 / 近战、强化第十颗即触发、PvP 手炮倍率、收枪 / 三秒到期和未知近战回执不重放。另有 3 项 Clown Cartridge 场景验证实际换弹 / 词条装配、随机普通 / 强化溢出、储备不足与未知结果不重抽。另有 2 项动态容量场景验证有效容量进入真实治疗、Buff 到期后的溢出保留，以及 detached 容量快照。另有 2 项 Adrenaline Junkie 场景以真实死亡触发 Buff，再验证下一次实际扣血；普通/强化双武器同时绑定，宿主显式提供武器/手雷信用。另有 6 项目标查询 / 迭代场景验证球形边界、队伍关系、实际范围伤害及逐目标回血、列表冻结后目标移动 / 移除 / 新增，以及排除后最近排序 / 截取和捕获距离决定真实衰减扣血。另有 5 项 Volatile 场景覆盖两种环境的真实爆炸、致死施加资格、已有状态单次引爆、相邻目标连锁及只读资格边界。另有 3 项 proc 策略场景验证真实伤害 / 击杀信用保留、当前 / 捕获 / Buff 反应筛选、物理投射物与原版入口。另有 4 项来源反应绑定场景验证真实投射物卸装后的治疗 / 击杀收益、改装前的强化标签、当前规则单次执行、原版即时入口和未知反应回执不重放。另有 3 项攻击快照场景验证来源到期后的延迟伤害、命中期新增贡献及替换运行时后的旧攻击 / 新防御。另有 4 项 JSON 延迟动作场景验证脱离来源后的实际伤害及回血、默认生命周期取消、未来范围查询与嵌套多次命中。另有 3 项固定位置场景验证死亡实体捕获 / 移除、零距离与半径边界 / 排除 / 关系 / 维度，以及固定激活位置的三次范围伤害和各波次成员变化。另有 2 项 Kinetic Tremors 场景以真实原版命中验证直击计数、收枪保留、卸下 / 原目标移除后的三波伤害、PvP 数值、普通 / 强化来源隔离和末次震波后的冷却。另有 2 项命中测量场景验证逐目标距离进入冻结攻击的有序阶段、查询后移动及增益到期、原版宿主测量、命中期 MAX / 当前防御和结果测量隔离。另有 2 项动作来源场景验证甲施加 / 乙触发后的原版攻击者、实际击杀与治疗归属、信用隔离，以及状态到期 / 来源解绑后的事件来源伤害快照。另有 2 项实体观测场景验证玩家 / 死亡 / 缺失 / 跨维度与观测值恢复；6 项 Jolt 场景验证实际阈值、两种模式、原版归属、玩家伤害取消 / 吸收资格、施加顺序、致死阈值、相邻连锁与冷却。另有 4 项 Voltshot 场景验证链接后的共享 Jolt、真实击杀 / 命中、明确换弹事件、武器隔离、取消 / 致死、收枪、Jolt 非武器击杀及 7 / 8 秒到期。另有 3 项成员差分场景验证目标 / 中心移动、快照后变动、重叠场、成员 / 中心移除、结束清理和真实持续恢复。另有 4 项固定场场景验证 Rift 的 15 秒两种模式、施放者移动 / 来源解绑、晚进入者、重叠场与分别到期、阵营参照缺失策略，以及 any 查询在施放实体移除后仍使用保存坐标。另有 4 项 Rift 补盾场景验证原版实际扣盾 / 生命溢出、伤后暂停与满血恢复、PvP 重叠池、Void 阻止 / 分层 FIFO / Absorption 独立、空容量时的交互资格和离场清理。另有 3 项连续护盾回充场景验证原版承伤后的延迟、微秒容量边界、到期残段、暂停与 Eternal Warrior 停伤回充 / 破盾终止。另有 3 项盾层攻击倍率场景验证多层增伤后的原版护甲 / Absorption、来源解绑后的冻结强化命中新盾，以及免疫 / 无敌帧拒绝与增量预算。另有 3 项精准因子抑制场景验证攻击 / 防御固定加值、普通盾及原版护甲 / Absorption、未来命中倍率 / 冻结来源和原版无敌帧增量。另有 3 项装配场景验证原子来源 / 装配可见性、实际伤害与回血、非法变更预检、切枪真实计时，以及清理世界动作异常后的已提交状态保留。另有 5 项实际玩家容器场景验证命令权限 / revision、单份物品与组件、玩家 NBT 往返、目录缺失取回、死亡 / respawn、反应内死亡和异常保留；NeoForge 另有 1 项死亡掉落收集 / 取消专项。另有 4 项装备协议测试覆盖组件编解码、真实连接身份语义、重放 / 过期请求、会话生命周期及失败后同步 / 取回。另有 4 项技能场景覆盖命令 / 权限、服务器条件替换、成本 / 实际世界动作顺序、真实 tick 的回能 / 延迟快照及世界异常。另有 4 项空间观察场景覆盖遮挡筛选 / limit、碰撞形状 / 流体 / 未加载地形、取样点 / 方向错误，以及真实延迟锥形动作。另有 4 项电弧箭场景覆盖真实扫描 / 锁定、1 秒后从移动 / 致死目标逐次连锁、4 目标上限、两种伤害、取消 / 移除与中途建墙策略。另有 5 项投射物场景覆盖技能支付 / 真实飞行、来源卸下 / 移动、扫掠命中 / 接触点、寿命 / 重力 / 阻力、缺失几何 / 未加载地形 / 停止和未知伤害结果不重放；另有 7 项碰撞场景覆盖次数预算、穿透、反弹、几何重入、取消与失败后的停止；另有 8 项追踪场景覆盖限速转向、锁定、过滤、接触重选及真实移动目标。故障注入及故意加载坏包会产生预期的 ERROR 日志，验收以 GameTest 最终结果为准。

GameTest 使用独立测试源集和临时测试世界；NeoForge 普通 client / server / data 配置只加载主模组，`gameTestServer` 才加载测试模组。Fabric 配置参考 [Fabric 自动化测试文档](https://docs.fabricmc.net/develop/automatic-testing)，具体 API 与运行结果以本仓库固定的 26.3 依赖为准。另运行 Fabric 的 EquipmentClientGameTest：真实集成服务器、按键入口、实际按钮输入与双向 payload，验证组件 / 物品身份保留、交换、持握和取回。已检查 1280×720 与 640×480 的实际渲染截图，文件位于 fabric/build/run/clientGameTest/screenshots/；该测试源集与资源不进入发布 jar。NeoForge 图形客户端另已完成启动 / 客户端注册检查，尚未做同等页面交互验收；独立远程服务器、多个真实客户端、跨重启恢复及全 Compendium 内容覆盖仍未验收。
