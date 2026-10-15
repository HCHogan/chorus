# 加载和调试 Chorus 效果程序

当前服务端已注册可重载的 `chorus:effect_program` 注册表。Fabric 与 NeoForge 均从 `data/<namespace>/chorus/effect_program/<path>.json` 加载程序模块，条目 id 为 `<namespace>:<path>`。完整程序保持原有格式，也可通过 imports 组合多个片段。高优先级数据包覆盖同路径的整个模块，不合并内部 bundle / Buff / Profile。格式仍使用 [实现记录](engine-implementation.md) 中已经落地的 DSL。

这是引擎的管理和调试入口。玩家已有独立装备容器和最小装备命令；已有按 K 打开的最小配装页、最小技能命令、单次开火与整弹匣 / 逐次装填的手动换弹命令；子职业 / 解锁和技能按键尚未自动绑定。engine attach 创建的是不保存到玩家档案的管理来源，不伪装成武器或技能信用；equipment 命令操作的实际装备另行持久化。

## 最小可运行数据包

在测试世界的 `datapacks/chorus-demo/` 下建立两个文件。26.3 的服务端数据包格式为 121.0：

`pack.mcmeta`：

```json
{"pack":{"description":"Chorus engine demo","min_format":121,"max_format":121}}
```

`data/example/chorus/effect_program/lifesteal.json`：

```json
{
  "version": "example-1",
  "bundles": [{
    "id": "example:lifesteal",
    "rules": [{
      "id": "recover", "on": "chorus:hit",
      "if": {"type": "chorus:source_is", "source": "owner"},
      "do": [{
        "type": "chorus:heal",
        "amount": {
          "type": "chorus:scale", "factor": 0.5,
          "from": "damage", "to": "damage",
          "of": {"type": "chorus:event_number", "name": "health_loss", "unit": "damage"}
        }
      }]
    }]
  }]
}
```

此示例把该来源持有者造成的实际生命损失的一半恢复给自己；护盾、Absorption、免疫和无效伤害不算生命损失。50% 是机制演示参数，不是某个命运 2 perk 的校准数值。

启用数据包并在目标维度运行以下管理命令：

```mcfunction
datapack enable "file/chorus-demo"
reload
chorus engine list
chorus engine start example:lifesteal pve
chorus engine attach @s example:lifesteal passive
chorus engine status
```

attach / detach 的 target 必须是当前维度中的一个 LivingEntity；控制台应把 `@s` 换成玩家名或单实体选择器。它们需要原版 gamemaster 命令权限。start 默认 PvE，也可明确指定 `pvp`；活动模式与目标是不是玩家无关。

卸下来源或停止测试：

```mcfunction
chorus engine detach @s passive
chorus engine stop
```

- slot 是本目标下的管理来源标识，允许小写字母、数字、下划线、点与连字符。同一目标 + slot 重复绑定相同数据为空操作；不同 slot 是不同来源。
- 同 slot 绑定不同数据会替换来源。绑定与解绑都先经时间轴追赶，结算之前的连续恢复，再改变来源；失效静态来源的定时器会被清理。已经授予的 Buff 依其自身生命周期继续存在，不被一律擦除。
- `chorus:source_attached / source_detached` 事实携带 source_instance / bundle 引用及完整不可变来源快照。静态规则用 `chorus:own_source` 匹配本来源，包含原标签和归属，避免相同 bundle 或同键新词条处理旧词条的清理。被删除来源以旧快照执行自己的 detached 规则；其他活动来源仍可观察该事实。Java 负载为 `SourceChange.Fact`，通过 `EffectEvent.Carrier` 读取事件，不应强转为裸 `EffectEvent`。Buff 结束仍使用已有的 ended 快照协议。
- `stop` 显式丢弃该维度运行时的暂态状态。已有运行时时，start 会拒绝覆盖；不会借重新启动偷偷清空来源、Buff、定时器或失败记录。

## 持有者常驻配置

`ActorBinding` 管理一个持有者独立于装备与技能槽的常驻 SOURCE 根包。规则内容仍使用普通 Bundle 和 `includes`；没有第二套角色规则语法。参数按展开后根包的声明检查，根包的标签 / 固定参数保存在绑定中。给定根包可组合基础伤害、资源修饰、被动规则及其附加 / 移除反应。

```java
var before = Optional.ofNullable(runtime.state().engine().domain().actors().get(owner));
var after = new ActorBinding("chorus_d2:solar_scaling");
runtime.actor(new ActorChange(owner, before, Optional.of(after)));
```

带参数的配置使用 `new ActorBinding(bundle, tags, Map.of("power", new Measure(1, Unit.DELTA)))`。这是可信服务端宿主入口；正式角色、职业、解锁校验和默认配置由内容宿主决定。已有玩家配置的自动同步见下节。它不自动选择技能、转移装备、重置资源、授予武器信用，也不自动给所有世界实体安装 D2 规则。

`EffectState.actors` 保存配置；`actor/<holder.length()>:<holder>` 是唯一受管来源身份，origin.owner 为持有者、origin.source 为该身份，weapon / ability 为空。普通 `bind / unbind / SourceBatch` 不得修改 `actor/` 命名空间。启动和恢复核对配置与完整来源投影，缺失、孤立或错配来源均拒绝，不静默补绑并重放附加动作。

`ActorChange` 必须携带完整 before / after；旧配置不符、未知包、BUFF 包或参数错误在写入前拒绝。同一配置重复提交为空操作，不重复附加、不重启定时器。替换先完整发布新配置和来源，再依次发出旧来源 detached、新来源 attached，旧清理规则保留自己的不可变快照。正常事件入口先结算此前时间；后续恢复 / 属性 / 行动门槛读取新配置。

解除配置用 `after = Optional.empty()`。它结束这个根来源及受其约束的工作，已经授予的 Buff 与 detached 动作沿用各自生命周期；技能选择、装备和账户不会被顺便清空。死亡、离线和维度变更也不会隐式解除配置，具体持有者生命周期政策仍由宿主提供。保存 / 恢复保留绑定且不重复执行 attached。

存储新增 EffectState v6、ActorBinding 和 ActorChange 三项身份；原 v5 字段保持完整，通过显式迁移得到空 actors，不从旧的普通来源猜测角色注册。其余旧状态版本继续沿各自迁移路径恢复。普通状态复制和时钟推进都保留 actors。参考 Solar 技能宿主已通过此入口管理 `solar_scaling`；它不再是可误删的散装管理来源。

## 玩家常驻配置的保存与自动同步

`PlayerActor` 保存一个玩家的可选 ActorBinding 和 revision，原生玩家 NBT 键为 `chorus:actor`。配置修改先进入玩家内存状态，实际落盘由原版玩家保存路径及世界保存协议完成。可信服务端内容模块在选择角色根包后调用：

```java
var configuration = PlayerActor.get(player);
configuration.configure(player, Optional.of(new ActorBinding("chorus_d2:solar_scaling")),
        configuration.revision());
```

程序须已加载这个 SOURCE 包及其依赖。内置管理入口为：

```mcfunction
chorus actor set @s {"bundle":"chorus_d2:solar_scaling"}
chorus actor status
chorus actor clear @s
```

set / clear 需要 gamemaster 权限，目标是一个在线玩家；status 为只读的自身状态。JSON 接受 bundle、tags 和带单位的 parameters，其他字段拒绝。Java 宿主必须传入当前 revision；过期请求、错误单位、缺失参数和未知包均在写入前拒绝。重复相同配置不增加 revision、不重放附加；清空配置也有持久化 revision，后续同步不会把它重新填回。配置和领域投影先一起提交，旧 detached / 新 attached 再执行；未知世界结果保留两边已提交的配置，运行时进入失败状态并拒绝重试。

进入已安装运行时的玩家、运行时首次遇到已保存的配置以及当前维度中的配置变化，会在 prepare / 规则输入时自动同步。配置属于玩家，原版替换 ServerPlayer 时转移到新对象，独立于装备掉落政策；原对象清空所有权。每个维度保留这个 UUID 最近确认的根配置，死亡或暂时离线不会删掉共享数值来源，因此已接受的 Solar 效果仍能使用常驻规则。具体 Buff 和定时工作的结束由内容规则决定；同一维度返回相同配置不会再次 attached。新建一个空运行时会对已配置玩家激活一次，不能把它当作检查点恢复。

玩家入口以 PlayerActor 保存的数据为准，修改 / 清空应调用 configure；底层 runtime.actor 继续供 NPC 或其他可信宿主使用。未配置且未接管的玩家不会覆盖外部宿主直接安装的 ActorBinding。找不到定义或参数不匹配时，保存数据保留为 inactive 并记录原因；换到含有有效定义的运行时后可再次激活。存在但无法解码的 NBT 明确失败，不能把损坏数据视为空配置。

检查点另保存 actor_owners，记录该维度仍负责与玩家文件核对的配置。同一共享宿主在目标实体索引成功并提交角色交接后转移核对责任和当前角色根；移除后异常仍保留源核对记录。独立宿主沿用移除后释放、目标重新同步配置的策略。这样在其他维度合法改配后返回不会被误判为存档错配。恢复前核对已在线玩家的实际配置；仍由该维度负责的离线玩家在恢复后首次出现时也先核对，错配会停止运行时，不用重新 attached 掩盖错配。世界保存冻结期间还检查实际玩家配置未变化。正常停服 / 再启动通过实际玩家文件与登录流程验收，领域恢复不会重复初始化。

该入口只管理常驻根配置；基础技能选择的玩家存储见[下文](#玩家基础技能选择的保存与恢复)。职业默认值、解锁 / 子职业组合、初始技能装配、完整技能进度、跨独立宿主资源 / Buff 和物理投射物迁移、UI 及生产内容目录仍需接入。同一共享宿主已通过下述联合角色协议移动当前状态；独立的配置同步不代表完整迁移。

## 非致死生命支付

`chorus:spend_health` 是独立的世界动作，供内容明确消耗生命，不能通过普通 `damage` 或负数治疗替代：

```json
{
  "action": {
    "type": "chorus:spend_health", "target": "self", "origin": "event",
    "amount": {"type": "chorus:constant", "value": 5, "unit": "damage"},
    "minimum": {"type": "chorus:constant", "value": 1, "unit": "damage"},
    "mode": "up_to", "tags": ["example:health_cost"]
  },
  "as": "payment"
}
```

5 和 1 是示例参数。amount 必须非负，minimum 必须严格大于零，mode 必填：`exact` 在余额不足以保留下限时完全拒绝；`up_to` 扣除最多 `min(amount, max(health - minimum, 0))`。当前生命已经低于下限时不会治疗，也不会再扣血；up_to 可成功支付零。exact 表示完整请求可负担，原版 float 量化统一向保留生命方向舍入，回执以实际写入差额为准，因此可能比请求少不足一个原版精度单位。

类型化结果提供 DAMAGE 字段 `requested / effective / health_before / health_after` 与布尔 `paid / changed / observed / insufficient`。缺失或死亡目标不会支付、没有前后生命观察；读取前后生命须先检查 observed，不能将不可用伪造为零生命。只有确认正扣除才发 `chorus:health_spent`，携带 requested / effective / health_before / health_after、payment_id、原来源和标签；零支付及拒绝不发此事实。

Minecraft 适配器直接写正生命值，绕过护甲、Absorption、Chorus 护盾、伤害免疫和无敌帧；不触发 hurt / heal、hit / death / kill、图腾和碎冰损失累计，也不自动重置受伤回充延迟。需要这些行为的效果应显式使用伤害操作。若第三方改写实际 setHealth 结果或世界回执丢失，视为未知结果：保留已提交状态、停止运行时，不自动重试或回滚。动作本身不是 AbilityDefinition 的原子资源 cost；内容须在 paid 分支执行依赖动作，多种世界支付不自动组成可回滚事务。

## 效果包声明复用

与下节 `invoke_bundle` 不同，includes 是静态声明组合；它本身不会启动任何动作。

Bundle 可声明 `includes`，引用同一链接目录内、相同 scope 的其他 Bundle。例如 Slow 的施加来源复用 Freeze 的施加规则：

```json
{
  "id": "chorus_d2:slow_application",
  "includes": ["chorus_d2:freeze_application"],
  "parameters": {"slow_duration": "second", "slow_durance_extension": "second", "slow_jump_delta": "delta"},
  "rules": []
}
```

此片段需与被引用的 Bundle 一起链接。`imports` 负责加载程序模块，`includes` 负责复用 Bundle 的声明，二者的 id 空间和职责不同。编译器按 includes 顺序先展开依赖，再加入本地声明；菱形依赖按 Bundle id 只展开一次。包含规则、修饰、连续恢复、技能替换、动作门禁、水平速度上限及参数声明。参数同名同单位共享一个绑定值；单位冲突、各类别内局部声明 id 冲突、重复直接引用、缺失引用、跨 scope 引用与静态引用环均拒绝，不作隐式覆盖。

展开后仍只有被实际绑定的来源 / Buff：`self`、组件、owner、origin、`own_source`、`own_buff` 和生命周期均绑定到这个组合实例，不额外创建父来源。继承参数也必须在实际来源上提供，装备与技能常驻来源使用同样的参数校验。引用的公开事件规则会同时成为组合包的能力；例如 Slow 来源也能处理显式 `apply_freeze` / `clear_freeze`。`origin_bundle` 捕获包含展开后的规则，解绑后按已捕获来源继续。`CompiledEffects.program()` 与 Codec 保留原始未展开目录，以便往返、重载和反应快照保持同一声明身份。

静态引用环检查只解决声明无法有限展开的问题。事件执行中的循环、点燃传播和跨目标反馈不受它限制。可执行范例与边界见 [BundleCompositionTest](../common/src/test/java/com/imdomestic/chorus/effect/BundleCompositionTest.java)。

## 显式效果包调用

`chorus:invoke_bundle` 把一个普通事件连同不可变临时来源交给指定 SOURCE Bundle 的匹配规则。它用于投射物命中、延迟技能或其他效果复用一套施加逻辑；不需要把该包挂回当前装备。示例需链接 [bundle_invocation.json](../common/src/test/resources/effects/bundle_invocation.json)，数值为合成输入：

```json
{
  "type": "chorus:invoke_bundle",
  "bundle": "test:callee", "event": "test:apply",
  "holder": "source_owner", "target": "victim", "origin": "bound",
  "parameters": {
    "amount": {"type":"chorus:constant","value":7,"unit":"damage"},
    "duration": {"type":"chorus:constant","value":1,"unit":"second"}
  },
  "tags": ["test:invoked"],
  "numbers": {"counter":{"type":"chorus:constant","value":42,"unit":"count"}}
}
```

| 字段 | 语义 |
| --- | --- |
| bundle / event | 必填；包必须为 SOURCE，包含展开 includes 后的该事件规则；禁止 chorus:internal/ 事件 |
| holder | 临时来源的 self，默认 source_owner；在调用者上下文求值 |
| target | 新事件的 victim，默认原 victim；可用已绑定的逐目标身份 |
| origin | 默认 bound，也可 event；选择信用，不改变调用者状态归属或 holder 表达式的含义 |
| parameters | 在调用时求值一次，名称和单位必须完全符合被调包的展开声明；callee 用 source_parameter 读取 |
| tags | 此次事件的显式标签，默认空；不自动继承触发标签，也不混入原始信用 tags |
| numbers | 在调用者上下文求值，覆盖同名事件测量；未覆盖的测量保留 |

actor、flags、impact、历史观察及其他 references 继续来自当前事件；source_instance / bundle 被替换成调用身份，因此 `own_source` 匹配临时来源，已有同包实例不会冒用这次身份。观察只覆盖原先观察到的实体，不因改写 victim 而补造记录。原 ProcPolicy 保留；原反应快照仅在信用 owner 相同时保留，不能借切换信用转交另一人的快照。

调用身份由实际动作操作号派生；同一 for_each 的多次执行和不同飞行物不会共用参数。运行时复核程序版本和来源参数，然后分发被调包的匹配规则；普通活动来源和 Buff 仍可观察同一事件。callee 的 `emit` 保留调用负载，所以 Slow 能继续调用其继承的 Freeze 规则；新的显式调用则获得新身份。条件、proc_key、状态授权和世界回执照常执行。允许规则再次调用自身，由内容条件或后续事件推进，不增加全局深度 / 次数限制。

这是一笔**排队投递**：现有父事件动作帧先完成，之后处理派生事件，不是有返回值的同步子程序。调用结果为空，不能紧跟调用就读取尚未归约的 callee 结果；需要后续反应时由 callee 发事件或授予状态。调用不写入 state.sources、不触发 source_attached，也不激活该包的修饰、恢复、技能替换或行动门槛。callee 的 detached 延迟可以继续持有参数；`after lifetime:source` 因来源并非常驻而拒绝。需要有生命周期的效果应授予 Buff 或显式创建真正来源，不能用调用冒充装备。

世界动作结果未知时保留此前已提交状态和世界写入，停止当前会话，不重放调用或自动回滚。旧版本调用与非法参数被拒绝。8 项 BundleInvocationTest 覆盖排队顺序、归属 / 事件信用、参数、重复来源隔离、卸下后的 detached 执行、世界故障、256 次内容条件驱动调用、类型校验和临时生命周期。两端共享 BundleInvocationGameTest 用真实投射物验证卸下后命中才读取当前 Durance，以及复用 Slow 的授权、百层 Freeze、最后施加者信用和原版运动控制。此夹具并非完整 Withering Blade。

## 装备定义与原子装配投影

程序可声明可选的 `equipment`，默认空目录。以下为合并到已有程序中的字段示例；`test:gear_perk` 必须在该程序或参与链接的片段中声明为 source bundle。完整可执行合成夹具见 [equipment.json](../common/src/test/resources/effects/equipment.json)，数值仅用于验证机制。

```json
{
  "equipment": {
    "slots": [
      {"id":"test:weapon_a","accepts":["test:weapon"],"weapon":true},
      {"id":"test:arms","accepts":["test:arms"]},
      {"id":"test:class_item","accepts":["test:class_item"]}
    ],
    "items": [{
      "id":"test:rifle","tags":["test:weapon"],
      "sockets":{"perk":{"required":true,"options":{
        "normal":{"bundle":"test:gear_perk"},
        "enhanced":{"bundle":"test:gear_perk","tags":["chorus:enhanced"]}
      }}}
    }],
    "limits":[{"id":"test:exotic_armor","tag":"test:exotic",
               "slots":["test:arms","test:class_item"],"maximum":1}]
  }
}
```

- 槽位接受与物品 `tags` 有交集的原型；每槽一件、同一装配中实例不能重复。`items[].effects` 是固定效果字典，`sockets` 是候选效果字典；可选插槽须显式设 `required:false`。未知槽位 / 原型 / 选项、缺少必选项和超出 limit 均拒绝。limit 的 slots 为空时作用于所有槽；计数只看物品标签，不看词条标签。
- 每个效果的 `activation` 默认 `equipped`，收枪仍绑定。`drawn` 只在当前选定武器槽绑定，不能用于非武器槽。多数武器 perk 应保留 equipped，通过 `source_is:this_weapon` 识别自己的攻击；只有明确要求在手的触发再加 `weapon_drawn`。切枪不等于卸下所有武器来源。
- 来源身份由 holder、物品实例和固定效果键 / 插槽键生成，前缀 `equipment/` 保留给装配事务。物品在兼容槽间移动但实际持握实例不变时，来源、定时器和 Buff 不重置；同插槽换词条或强化标签会替换对应来源。每个来源只合并物品标签与该效果的标签，不把另一词条的强化资格扩散过去。

可信服务端宿主调用：

```java
Loadout before = runtime.state().engine().domain().equipment().getOrDefault(holder, Loadout.EMPTY);
Loadout next = new Loadout(Map.of("test:weapon_a",
    new Loadout.Gear(itemInstanceId, "test:rifle", Map.of("perk", "enhanced"))),
    Optional.of("test:weapon_a"), before.grants());
runtime.equip(new EquipmentChange(holder, before, next));
```

`EquipmentCodecs.LOADOUT` 提供对应的 slots / drawn / grants JSON 读写。普通装配修改应保留持有者既有 grants，显式授权事务才能改变它。`Gear` 保存 instance / definition / choices / parameters 元数据，没有 ItemStack、数量、耐久或授权；`equip` 不证明玩家拥有物品，不能直接作为客户端请求处理器。玩家物品应通过下面的 PlayerEquipment 容器接口转移，物品组件与效果元数据分开保存。

事务先追赶逻辑时间、验证 before 装配和其全部来源，再一次提交新来源、装配及现有武器 Buff 的 stow / draw 迁移。事实随后依次入队：weapon_stowed / weapon_drawn 及其 Buff 生命周期事实、按来源身份排序的全部 source_detached、全部 source_attached、equipment_changed。`equipment_changed` 的 Fact 携带完整 Receipt 和 before / after；事件数字 equipped_count 为 COUNT。清理动作读取旧来源标签，但看到的全局装备与来源已是新装配。世界动作随后失败时保留已提交状态和待确认操作，不自动重放或伪造回滚。

`on_stow` 是切枪时对已有 Buff 的迁移策略；它不阻止收枪后新授予 Buff。需要这项限制的规则显式检查 `weapon_drawn`。该条件按绑定来源的 owner + weapon 查询装配；无装备记录或无武器归属返回 false，攻击 on_use 快照固定捕获时结果。

非装备宿主可用 `runtime.replaceSources(SourceBatch)` 批量更新其他来源。每项带 before / after，先核对全部旧值再整体提交；即使某项不变也参与旧值验证，任何不一致均拒绝。相等重绑无事实且不重置定时器，替换取消旧来源绑定的定时器；独立 Buff 和 detached 延迟动作按其自身生命周期保留，需要清理的状态由旧来源自己的 detached 规则明确处理。`bind / unbind / replaceSources` 均拒绝直接修改 equipment/ 来源。

### 装备和选项的持有者授权

`items[].requires` 可声明整件物品和指定选项需要的授权。所有集合按 AND 判断，未列出的选项没有额外要求；缺省 requires 表示无要求。未知插槽/选项、非法授权 ID 和未知字段在编译时拒绝。例：

```json
"requires": {
  "all": ["example:armor_unlocked"],
  "choices": {
    "mod_0": {
      "normal": ["example:mod_unlocked"],
      "discounted": ["example:mod_unlocked", "example:artifact_selected"]
    }
  }
}
```

授权来源是 `Loadout.grants` 中该持有者的服务端快照，原生宿主由 `PlayerEquipment.grants` 保存并投影。Gear、物品/效果标签、Buff、其他玩家和客户端请求都不能提供授权。选项的授权校验与预算独立：有资格仍可能超预算，有空余额也不能绕过资格。空装备的授权快照会保留；纯核心 equip 是可信宿主接口，不能把客户端自报的 grants 传入。

可信服务端通过 `PlayerEquipment.get(player).grants(player, nextGrants, expectedRevision)` 替换完整集合。管理入口 `chorus equipment grant <id> <revision>` / `revoke <id> <revision>` 需要 GAMEMASTERS 权限，仅操作命令发起玩家；普通玩家没有对应授权写入操作。变化使用同一个装备 revision 和物理提交边界，旧页面请求随后失效；相同集合不改 revision、不重放 attach。资格仍满足的来源保持身份；撤销后非法整套装配沿用 inactive 投影政策，物品保留、效果卸载，合法性恢复时重新挂载，仍可无条件取回。不会自动把优惠选项换为普通版本。

授权属于持有者，存于玩家 `chorus:equipment.grants` NBT，不进入物品组件；死亡不清除，ServerPlayer 替换转移后清空旧对象。空容器加载、领域检查点和切枪阶段都保留授权；checkpoint 与当前玩家授权不同仍拒绝直接恢复。旧 Loadout 存储读为无授权，旧 item/item_v2 读为无 requires，新存储分别写 loadout_v2/item_v3，历史结构不重写。

现有 equipment_view 只传展示信息，仍不传授权集合；revision/status 变化足以让旧请求失效和页面显示非法装备。`equipment_access.json` 验证通用链路，Unflinching 的普通/神器选项已有实际消费者。当前是按玩家持有者保存的通用授权入口，没有实现账号共享、Guardian Rank 进度、神器页面/点数树、赛季可用性、过期策略或外部账号同步；这些生产者须验证自己的条件后调用该入口。

### 玩家编辑装备插槽

`items[].sockets.<name>.editable` 显式允许玩家更换该插槽，缺省为 false。原型允许生成多种选项不等于允许玩家重掷；固定武器词条继续使用默认值，护甲模组等可修改内容写 `"editable": true`。它独立于 `required`：可选且可编辑的插槽可以清空，必选插槽必须保留一个合法选项。空效果选项 `none` 仍是目录中的正常选项。

纯核心 `CompiledEquipment.configure(before, slot, choices)` 返回新的完整 Loadout，`choices` 是一件已持有装备的**完整选项集合**。所有未修改的只读选择也须包含；新增、删除或更换只读选择均拒绝。接口保留实例、原型、数值参数、其他物品、持握槽和服务端授权，验证最终整套装配的选项、预算、资格与数量限制。这样可以一次从 `6+4` 换到 `4+6`，也可以一次替换多个已失去资格的选项，不要求中间状态合法。

原生入口为 `PlayerEquipment.get(player).configure(player, slot, choices, expectedRevision)`；普通玩家可用：

```text
/chorus equipment configure test:chest 7 {"mod_0":"solar","mod_1":"solar","mod_2":"none"}
```

示例槽位和选项来自测试目录；7 必须替换为当前 revision。命令只操作发起玩家当前装备栏内的物品，不接受实例、原型、参数、授权或 ItemStack 输入。宿主只替换该物品的 Gear.choices，名称、耐久、附魔和其他组件保留，背包不参与转移。预检成功后物理组件、revision 和来源在既有事务边界一起提交，反应看到完整新配置。完全相同且合法的选择不递增 revision、不重放效果，但仍要求当前 revision、活动目录与健康空闲运行时。

来源只按实际 Bundle/标签/参数变化更新；仅改价格而仍映射同一效果的选项不重建来源。同实例仍处于同一持握槽时不产生 stow/draw；护甲模组编辑不会重置另一件武器的弹药或 perk。改变武器自身效果时仍执行其既有清理/安装规则。物理提交后的世界动作结果未知时，保留已写入的新选项和 revision，停止运行时，不回滚或重放该动作。

授权过期后可以直接编辑失效物品，只要最终整套装配合法。其他物品仍非法、原型/插槽已从目录移除或只读选项损坏时，编辑不能绕过这些约束；可以先用既有取回操作移出相关物品。没有自动升级、改价、替换选项或消耗材料的隐含行为。

旧 socket 检查点的两字段结构通过 `LegacyEquipmentSocketV1` 显式迁移为 `editable=false`；新目录写 `socket_v2`，不改写历史结构或原始检查点。Gear/玩家 NBT 结构不变。配装页现已接入选项草稿、费用展示和 v2 网络编辑请求，详见[装备展示协议与最小配装页](#装备展示协议与最小配装页)。

### 每件装备的插槽预算

`items[].budgets` 声明本物品的静态装配约束。预算键为本地名称，各预算独立检查，容量不在不同物品之间共享。下例为物品片段，假定已声明 mod_0/mod_1 插槽以及各自的 none/normal/harmonic 选项：

```json
"budgets": {
  "energy": {
    "capacity": 10,
    "costs": {
      "mod_0": {"none": 0, "normal": 3, "harmonic": 2},
      "mod_1": {"none": 0, "normal": 3, "harmonic": 2}
    }
  }
}
```

`capacity` 或 `capacity_parameter` 必须且只能声明一个。后者引用本物品已声明、单位为 count、integral 为 true、范围处于 0–2147483647 的数值参数，由 Gear.parameters 提供容量。常量容量和每项费用也须为该范围内的整数，小数、负数、超界和未知字段均拒绝。容量为零合法；上例的 10 是参考装备配置，不是核心上限。

每个预算至少列一个参与插槽；该插槽的全部选项必须逐项标价，免费选项明确写零，未知/遗漏选项及未知插槽在编译时拒绝。预算未列出的插槽不参与该预算，可选插槽未选择时费用为零。费用按实际选择相加，使用 long 累加避免 int 溢出；`drawn` 未激活、超能/武器元素不匹配或退出 ADS 都不会释放已占费用。它不是可消费/恢复的战斗资源，也不扣 Armor Charge。

所有纯装备投影、管理赋值、物理交换/移动和客户端请求复用 `CompiledEquipment.validate`，超额在物品转移及来源提交前拒绝。拒绝保留背包、装备、revision 和已有来源；规则重载使现有装备超额时，沿用整套投影停用/显示原因的策略，物品仍可取回。物品保存容量参数，规则程序保存预算目录；旧 item 存储类型经显式迁移得到空预算，预算版 item_v2 也继续可读，当前带授权要求的目录写入 item_v3。目录更改后仍需遵循既有程序相等性要求，不能绕过检查恢复旧会话。

`equipment_budget.json` 验证固定/参数容量、多预算、可选插槽、收枪费用、拒绝和存储迁移；双端世界场景验证真实网络请求、物品组件/NBT 及更严目录下的取回。Unflinching 参考胸甲已采用普通固定属性 3 / Harmonic 2 费用，显式神器选项费用为 1 且需要对应授权。账号进度与神器选择的生产者、Tier/升级目录、动态改价仍需后续接入；当前预算和选项费用已显示在改装页。

### 每件装备的数值参数

同一物品原型可承载不同属性词条，不需要为每个数值组合生成一份 Bundle。声明、物品实例值、效果参数绑定分三层：

```json
{
  "id": "test:armor",
  "tags": ["test:armor"],
  "parameters": {
    "grenade_roll": {"unit":"stat_point", "minimum":0, "maximum":100, "integral":true}
  },
  "effects": {
    "stats": {"bundle":"test:armor_stats", "parameters":{"points":"grenade_roll"}}
  }
}
```

这是 `equipment.items[]` 片段；0–100 仅为合成验收范围，不是命运 2 护甲掉落规则。目标 `test:armor_stats` Bundle 声明 `"parameters":{"points":"stat_point"}`，其修饰或动作使用 `{"type":"chorus:source_parameter","name":"points"}` 读取当前绑定来源的值，单位由 Bundle 声明确定。将此表达式用于属性 Profile 的加法修饰，即可按原有 family / group 策略合并多件装备属性，再供 `chorus:attribute` 消费；不需要 attach 时向同一 Buff 手动加值、detach 时猜测减值。

对应物品组件里的 Gear 示例：

```json
{"instance":"unique-item-id", "definition":"test:armor", "choices":{},
 "parameters":{"grenade_roll":{"value":20,"unit":"stat_point"}}}
```

所有声明参数必填；缺少、多余、非有限数、单位不符、越界或要求整数却给小数均拒绝，不静默补零或钳制。范围两端包含在内。Bundle 参数同样要求名称集合和单位完全匹配，直接 `runtime.bind` 的来源及初始状态也会校验；Bundle 只约束单位，物品原型另约束该原型允许的数值范围。固定效果与每个插槽候选的映射都在编译时校验，未选择的候选也不能含无效映射。参数字典与 Measure 均不可变。

- 移动同一实例不改变参数或来源身份。相同 instance 的已投影参数变化会替换对应来源：旧 detach 读取旧参数，attach 读取新参数，规则看到的全局装配已经完整更新。未投影到某效果的字段变化不会重启该效果。
- `on_use` 数值快照把来源参数固定为常量；`on_hit` 读取参与本次查询的来源参数。已接受的 detached 动作和 `origin_bundle` 反应规则保留原始 EffectSource，包括参数；不会向后查同名新物品。
- 参数不自动写入伤害归因 Origin 或事件数字，也不自动传给新 Buff。BUFF 作用域不能声明或读取来源参数；需要跨 Buff 生命周期保留的值须显式写入类型化组件。能力 / 开火动作目前没有装备参数词法环境，不能把本表达式当作任意装备查表。
- 旧 Bundle、Gear 和 Effect 省略 parameters 时仍为空字典。数值字段随真实 ItemStack 组件进行玩家 NBT 和装备展示协议同步；客户端操作依旧只引用服务端背包槽，不能提交自选属性数值。

管理员可使用结构化命令给一件未标记的手持物品赋值，实例 id 仍由服务器分配；此例须先安装上述完整定义：

```mcfunction
chorus equipment stamp_roll test:arms test:armor {"choices":{},"parameters":{"grenade_roll":{"value":20,"unit":"stat_point"}}}
```

`stamp_roll` 的末尾对象只接受 choices / parameters。原 `stamp <slot> <definition> <choices-json>` 语法继续适用于不要求数值参数的原型。两种入口都会先校验整个候选装备再写物品组件；普通玩家无法调用这两个赋值命令。生产掉落分布、跨字段总点数约束、主手属性映射、配装页属性明细和完整 D2 护甲 catalogue 尚未提供。

## 实际物品容器与装备命令

`PlayerEquipment.get(player)` 拥有独立于原版装备槽的真实 ItemStack 集合，槽名来自程序定义。`chorus:equipment` 物品组件保存 Gear 身份 / 原型 / 选项 / 带单位的数值参数；实例 id 在创建物品时分配，普通交换不改变它。容器不向原版护甲槽或主手复制物品，保留原物品的名称、耐久、附魔和其他组件。snapshot / item 查询返回防御性副本，不能借修改查询结果改变持有物。

服务端入口：

```java
PlayerEquipment equipment = PlayerEquipment.get(player);
equipment.swap(player, "test:weapon_a", 0, equipment.revision());
equipment.draw(player, Optional.of("test:weapon_a"), equipment.revision());
equipment.move(player, "test:weapon_a", "test:weapon_b", equipment.revision());
```

swap 交换装备槽与原版主背包指定槽内的整份物品；背包槽为空即取回装备。只能操作本玩家的容器，要求服务器线程、玩家存活、revision 匹配，且不处于另一项装备操作中。装入物品必须恰好一件、有合法组件，并满足整个装配的槽位 / 选项 / 数量限制；与已装备物品或背包另一件物品重复的实例身份会被拒绝。它不提供跨玩家、仓库或全世界的实例唯一性数据库；生成 / 战利品宿主须分配唯一 id，不能复制既有组件冒充新实例。

交换先验证候选装配，再追赶时间并核对当前背包与 revision，完成真实物品转移后以 `EquipmentChange.Commit` 将元数据写入纯状态，最后派发反应。第一条 attach / detach 世界动作已能看到完整的新容器和来源。反应异常不回滚物品、不重放未知世界动作；失败运行时仍允许把装备取回空背包槽，冻结的诊断状态保留到显式停止运行时。

最小调试命令中，status / swap / draw / stow / move 只操作命令发起玩家自己；stamp 需要管理员权限，为手中一件尚未标记的物品创建新身份，并校验指定槽、原型和选项。以下使用 equipment.json 的合成定义，须先把该完整程序放进数据包并启动运行时；发布 jar 不包含测试夹具。示例假设玩家装备 revision 为 0，手中物品位于快捷栏第一个槽（背包索引 0）：

```mcfunction
chorus equipment stamp test:weapon_a test:rifle {"perk":"normal"}
chorus equipment status
chorus equipment swap test:weapon_a 0 0
chorus equipment draw test:weapon_a 1
chorus equipment swap test:weapon_a 0 2
```

最后一条把物品取回此前清空的背包槽。`stow <revision>` 清除持握选择，`move <from> <to> <revision>` 交换两个装备槽，持握选择跟随原物品。每次真实容器变更推进 revision；旧请求拒绝而不重放。stamp 不改变装备容器 revision。

容器保存到玩家 NBT 的 chorus:equipment 字段，与当前规则目录解耦；缺少原型 / 槽位或非法选项会令该玩家的整套效果投影暂时停用，物品保留并显示 inactive 原因，仍可取回。没有活动运行时也可取回已有物品。玩家保存 / 加载和 respawn 的 ServerPlayer 替换已接线，替换时移动剩余装备所有权并清空旧对象。死亡掉落遵循 keepInventory 和 PREVENT_EQUIPMENT_DROP 附魔：NeoForge 加入外层 LivingDropsEvent 收集，不进入 ItemTossEvent；若其他模组取消死亡掉落，遵循该事件结果，不再复制一份放回装备槽。

已安装运行时在 prepare、完整事件边界结束和服务器 tick 同步物理容器投影：死亡或离开该维度会解除旧来源，存活玩家按当前固定目录重新装配。死亡发生在装备反应的世界动作中时，先保留该边界事实，随后同步已掉落的容器，不递归进入解释器。同一共享宿主的跨维度路径先执行[联合交接](#原生玩家传送与长按输入交接)，不先解除再重装角色状态；离线迁移与跨独立宿主仍待实现。玩家 NBT 往返、死亡 / respawn 和异常后所有权已有双加载器测试，真实多客户端 UI 流程尚未验收。

当前 draw 只改变装备持握状态，不把容器里的枪变成原版主手物品，不把空手攻击自动归类为武器命中。枪械输入、完整投射物内容、主手联动、原版属性 / 装备附魔适配及 HUD / 技能 UI 仍需接入。独立容器使用下面的专用展示协议同步。

## 重载与边界

`/reload` 会重新解析、检查引用与单位并编译目录中的程序。实际两端测试已验证：高优先级文件覆盖生效；错误字段使重载失败；失败后目录和活动运行时均保留原状态。

已经启动的运行时持有完整不可变 CompiledEffects 对象。成功重载更新目录，后续启动读取新定义；已有运行时继续使用原定义和原状态。修改内容时应更新 version；当前尚未实现同一运行时内新旧来源版本并存、活动状态迁移、程序内容指纹及跨重启快照。来源规则现已另有 origin_bundle 捕获协议，但非空选择仍要求完整程序相等；重载保留旧运行时不等于多版本反应共存。

本入口的世界适配使用当前维度中的实体 UUID。显式伤害查真实 damage_type 注册表，以仍在本维度中的 owner 作为原版致伤者，不猜测直接投射物。状态施加在目标存在且存活时默认允许；具体内容免疫策略尚未装配。表现 cue 没有处理器时明确失败。资源账户可由下面的声明和初始化动作创建，恢复 Profile 自动参与服务器时钟；技能冷却表、CES / CMS 与真实技能归属仍需内容层装配。

可重载注册表在当前 Fabric / NeoForge API 中不会自动同步到客户端；UI 所需的定义和运行状态同步仍待实现。普通世界没有数据包条目时保持空目录，也不会自行安装运行时。启动、绑定与管理权限已经通过双加载器 GameTest；玩家装备容器与保存 / 加载入口另有测试，完整内容游玩尚未验收。

## 复用定义与链接程序片段

多个词条可以引用同一份元素状态定义。先用 `EffectCodecs.PROGRAM` 解码各片段，再用 `CompiledEffects.link` 统一校验，得到一个完整、固定版本的程序：

```java
EffectProgram jolt = EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, joltJson).getOrThrow();
EffectProgram voltshot = EffectCodecs.PROGRAM.parse(JsonOps.INSTANCE, voltshotJson).getOrThrow();
CompiledEffects program = CompiledEffects.link(List.of(jolt, voltshot));
JsonElement flattened = EffectCodecs.COMPILED.encodeStart(JsonOps.INSTANCE, program).getOrThrow();
```

`joltJson` / `voltshotJson` 是调用方已经读取的 JSON。链接会连接各片段的 Buff、bundle、Profile、资源与装备声明，再执行全部引用、作用域和单位检查；不会复制或改写版本，也不会按片段顺序覆盖同名定义。所有片段及内部带版本的定义须使用同一个 ruleset version；各目录中的重复 id 均拒绝，即使内容相同。装备槽位、物品原型和 limit 各自检查重复，装备效果可引用其他片段的 source bundle。最多一个片段声明全局 defense_profile，另最多一个片段声明 health_profile；两者可引用其他片段定义的 Profile。空目录、混合版本、重复的同类 Profile 选择及缺失依赖均报错。

链接不修改输入片段，结果可直接安装到运行时，也可编码为 flattened 完整程序。数据包另支持模块级 `imports`（默认空数组）和 `fragment`（默认 false）；其余字段仍是现有 EffectProgram。下面三个文件均位于 `data/example/chorus/effect_program/`。

`shared.json`：

```json
{"version":"example-1","fragment":true,
 "buffs":[{"definition":{"id":"example:mark","version":"example-1","duration":1}}]}
```

`perk.json`：

```json
{"version":"example-1","fragment":true,"imports":["example:shared"],
 "bundles":[{"id":"example:marking","rules":[
   {"id":"attach","on":"chorus:source_attached","if":{"type":"chorus:own_source"},
    "do":[{"type":"chorus:grant_buff","buff":"example:mark"}]}
 ]}]}
```

`main.json`：

```json
{"version":"example-1","imports":["example:perk"]}
```

重载后使用 `chorus engine start example:main`，再 `chorus engine attach @s example:marking demo`。`fragment:true` 只供其他模块引用，不出现在 engine list / start 中；未声明 fragment 的模块是可执行根，也允许被其他根导入。imports 使用完整条目 id，不是文件路径；只有显式依赖及其传递依赖参与链接，不扫描目录自动纳入效果。

每个根按声明顺序展开 imports，再收录本文件定义；同一模块身份只纳入一次，因此菱形依赖和相互引用都可统一索引后校验。不同身份里的同名定义仍报错，不按顺序覆盖；重复书写同一个直接 import 也会报错。每条导入边的版本须一致，互不导入的独立程序可以使用不同版本。所有模块的 imports 必须存在；未被根引用的 fragment 只完成结构和导入身份 / 版本校验，其效果引用、作用域和单位在所属完整根中校验。

模块全部解码后，服务端在发布新目录之前链接并编译每个可执行根。缺失导入、冲突定义、错版本、未解析效果引用、单位错误或未知字段都会拒绝整次重载，旧目录和已运行的程序继续有效。成功重载只影响之后启动的运行时；已有运行时保留旧依赖的完整编译结果，不迁移当前 Buff 或飞行物。覆盖共享模块会让新目录中所有依赖它的根重新链接，但不会改变旧运行时。

Java 工具可用 `ProgramModule.CODEC` 解码模块，再把 id → 模块的 Map 传给 `ProgramCatalogue.compile`。`EffectCodecs.PROGRAM / COMPILED` 仍表示无 imports 的纯 AST / 完整程序；需要导出单文件时编码编译结果即可。数据包注册表的 Java 值为 LoadedProgram，宿主通常继续通过 EffectPrograms.find / ids 读取已完成校验的 CompiledEffects。

[voltshot.json](../common/src/test/resources/effects/voltshot.json) 是片段：只声明击杀窗口、下一击就绪状态和触发规则，引用 [jolt.json](../common/src/test/resources/effects/jolt.json) 中的共享 Jolt 状态与计数事件。两者同为 test-jolt-v1；独立编译 Voltshot 会因缺少 Jolt 定义而失败，链接后的完整程序已通过纯核心及双端真实伤害测试。ProgramImportsGameTest 还将这两份原始夹具写为数据包模块，经真实 reload 后直接执行新目录中的两模式击杀、就绪、Jolt 中心与邻居伤害。[voltshot_weapon.json](../common/src/test/resources/effects/voltshot_weapon.json) 另提供完整武器输入模块，imports 同时引用 chorus_d2:voltshot 与 chorus_d2:jolt。需要手工运行这个合成示例时，把三份文件按原文件名放到 `data/chorus_d2/chorus/effect_program/` 目录，并在两个被引用片段上设置 `"fragment": true`；装备原型和来源会由独立容器投影。它已通过真实玩家开火、手动换弹、切枪后的物理命中与 Jolt 链伤验收；弹药 / 射速 / 飞行等参数是测试值，多弹丸具体资格及完整 D2 武器原型仍待完成。

### 持续条件窗口：Frenzy

[frenzy.json](../common/src/test/resources/effects/frenzy.json) 链接 [weapon_stats.json](../common/src/test/resources/effects/weapon_stats.json)、[frenzy_weapon.json](../common/src/test/resources/effects/frenzy_weapon.json) 及显式校准 Profile 后可编译执行。模板同时包含普通 / 强化分支，链接器会检查所有分支；即使只装备普通版，也必须提供 `chorus_d2:frenzy_enhanced_refresh_duration`，输入与输出均为 second。测试使用 [frenzy_test_calibration.json](../common/src/test/resources/effects/frenzy_test_calibration.json) 的合成 7.8 秒；正式内容须以校准值替换，不能把测试文件当作原作数值目录。四个片段的 version 必须一致，共用属性目录只链接一次。

接触 Buff 每次伤害 reset，预热 Buff 使用 refresh:none，接触 expired 时取消预热；预热 expired 且接触仍在时授予激活 Buff，再移除接触。主动移除的 reason 不会触发断档逻辑，两个状态同时过期也不会错误激活。激活阶段只刷新收益计时，不重建预热。三个状态均按 weapon 实例隔离，on_stow:keep；自己的 source_detached 显式清理它们。该组合复用现有生命周期规则，没有专用 Frenzy Java 执行器。

模板在 damage_taken 上同时匹配 owner 致伤与 holder 承伤，先守卫 event_actor / victim 身份，再检查正的 effective_with_absorption、排除自伤与显式环境标签。伤害、操控、装填分别声明在各自 Profile；换弹先限幅属性，再查武器曲线，接受后保存秒数。原作解释与接线限制见 [Frenzy 规则集](d2-ruleset.md#frenzy)。这组文件位于测试资源，不会自动安装到玩家运行时。

## 目标引用是否存在

`{"type":"chorus:target_ref_present","target":"event_actor"}` 只判断目标身份是否已提供，不查询世界，也不证明实体仍存在或存活。原版环境伤害可能没有攻击者；先用此条件守卫，再执行需要该身份的 `target_is` 或动作。`all` 按声明顺序短路，因此守卫应放在引用读取之前。

target 必填，接受普通目标或已声明的词法目标绑定。没有事件事实、空的 event_actor / victim / event_weapon，或来源没有 this_weapon 时返回 false；未知目标名、缺失词法绑定及类型错误仍报错。直接读取必需目标的原有报错行为不变，不会把缺失引用替换成 self。用于 on_use 修饰时，victim 的存在性留到命中求值，来源侧存在性冻结；词法目标仍不能捕获进攻击快照。实体资格另用 inspect_entity / check_status 的世界回执。

## 状态施加与只读资格

`apply_status` 在世界确认目标存在、存活并允许该状态后，才提交 Buff。需要处理致死命中的内容可以先用 `check_status` 查询资格，例如在已有命中规则的致死分支中执行：

```json
[
  {"action": {
    "type": "chorus:check_status", "buff": "example:status", "target": "victim",
    "allow_dead": true
  }, "as": "eligibility"},
  {"if": {"type": "chorus:result_flag", "binding": "eligibility", "field": "allowed"},
   "then": [{"type": "chorus:select_targets", "center": "victim",
     "radius": {"type": "chorus:constant", "value": 5, "unit": "meter"}}]}
]
```

这个片段要求程序已声明 `example:status`；仅演示资格允许后观察死亡位置附近的目标，没有施加状态或造成伤害。

- buff 必填；target 默认 victim；stacks / tier 默认 count = 1；duration 可选，单位 second，缺省使用 Buff 定义的持续时间。这些值一同传给宿主资格策略，恢复回执必须对应完全相同的请求。
- allow_dead 默认 false。设为 true 只放宽存活条件，仍须通过宿主状态免疫 / 资格策略；目标缺失、移除或不在该维度时仍是 missing。
- `check_status` 只有 allowed / denied / dead / missing 四个布尔结果，没有数值结果或 applied。dead 表示检查因存活要求而被拒绝；allow_dead=true 且资格允许时，死亡目标的结果也会是 allowed。
- 检查不修改 Buff、不发施加事实，也不预留资格。之后若执行 `apply_status`，它仍会重新进行要求存活的检查；不能用允许死亡的查询回执代替施加回执。

完整组合见 [volatile.json](../common/src/test/resources/effects/volatile.json)：显式施加意图与实际致死事实触发资格检查，允许后执行冷却、范围查询和爆炸。施加意图 `test:apply_volatile` 由测试宿主提供；测试宿主另把 `chorus_d2:volatile` 映射为测试伤害类型，并配置原版受伤冷却旁路标签。直接加载夹具并绑定来源不会自动获得这些接线；正式内容仍须定义真实伤害类型、来源与资格策略。数值假设见 [规则集](d2-ruleset.md)。

## 补充护盾与查询效果资格

护盾层仍由 Buff 的数值组件保存实际余量。可在该 Buff 条目的 shield 声明中指定补充上限 maximum（damage 单位的 Value）；省略时采用容量组件在定义中的初值。以下为 Buff 条目片段：

```json
{
  "definition": {
    "id":"example:shield", "version":"example-1", "duration":10,
    "components":{"numbers":{"capacity":{"initial":0,"unit":"damage"}}}
  },
  "shield": {
    "capacity":"capacity",
    "maximum":{"type":"chorus:constant","value":5,"unit":"damage"}
  }
}
```

先 grant_buff 创建层，再用下面的动作补充；同一动作还可用于修复被伤害消耗的现有层：

```json
{"action":{
  "type":"chorus:restore_shield", "buff":"example:shield", "target":"self",
  "amount":{"type":"chorus:constant","value":2,"unit":"damage"}
},"as":"restored"}
```

补充量是护盾容量 HP，不是进入该层前的伤害预算，不经过 taken_multiplier。动作纯粹更新既有容量，返回 requested / maximum / before / after / effective / overflow（均为 damage）和 changed / full 标志。只接受已声明为 shield 的 Buff；缺失实例、负数、错误单位或非法上限会明确失败，不自动创建护盾或检查目标世界实体。生命条件应先用 inspect_entity 显式观测。

maximum 在接收护盾层的 Buff 作用域中求值；例如 self / component 指向该层持有者及实例，不误用补充来源的状态。上限只限制本次能新增多少：若已有余量高于当前上限，不扣除旧容量。它不自动改变初始容量，不刷新持续时间、替换 generation、改变原始施加者或重排 FIFO。多余请求和无法表示的极小增量不存成未来额度；允许显式修改暂停层的余量，但不会解除暂停或让它参与当前防御。

effective > 0 时产生 `chorus:shield_restored`，携带恢复来源、受益者、层定义标签、shield_definition / shield_generation，以及 requested / effective / overflow / layer_before / layer_remaining / maximum 和 full。满容量或零量补充不产生此事实。状态先提交，再按原有队列分发事实；与世界动作等待组合时，重复回执不能再次补盾。它不调用原版 heal，也不改变 Absorption。

### 共享 Buff 的小数层数

Buff 的 definition 可显式设置 `"fractional_stacks": true`，默认 false。仅与 shared 计时兼容；max_stacks 仍是正整数上限，tier 仍为正整数。grant_buff、consume_buff、check_status、apply_status 的 stacks 表达式保持 count 单位；小数模式接受有限正数，默认模式要求正整数。请求上限仍为 Integer.MAX_VALUE，超出定义容量的授予按既有封顶 / credit_overflow 政策处理。

例如对同一实例授予 21.5 和 78，buff_count 返回 99.5；继续授予 0.5 才返回 100。ADD 累加、消费和 stored_delta 用有限 double 的规范十进制加减后返回 double，避免每次 .1 累加产生阈值残差；这不提供任意精度小数存储。MAX / REPLACE 比较或替换实际量。共享 one_by_one 衰减使 2.25 → 1.25 → .25 → 0，每步沿用定义间隔。

回执 requested / credited / stored_delta、状态 before / after、生命周期 stacks_before / stacks_after、buff_applied 及移除合计、事件观察都保留实际量。Java BuffInstance.quantity() 是游戏层数，count() 是内部计时槽数 ceil(quantity)，不得据它计算阈值。暂停、刷新、延长和领域检查点保留小数。默认整数 Buff 与独立层计时的既有行为保留；fractional_stacks + per_stack、逐槽 modifier multiplicity:stack 在链接时拒绝。by_stacks 只接受整数实际层数，小数使用 buff_count 配合显式 numeric curve / profile。

has_buff / event_has_buff 的 minimum 省略或为 0 表示存在一个正实际量实例；显式 1 仍要求至少 1 层，因此 .5 的存在性为真、minimum=1 为假。minimum 仍是非负整数；需要 .5、21.5 等阈值时使用 buff_count 数值比较。过期 / 缺失实例不会因 minimum=0 而变成存在，any 仍不跨实例累加。事件查询读取回执时保存的量，后续消费 / 到期不改写观察。

BuffDefinition、BuffInstance、Buffs.Receipt、StatusResult、StatusResult.Check、BuffObservation.Entry 各有独立的 `_v2` 存储 ID；旧 ID 保留原字段形状，旧整数数据迁移成相等的实际量，旧定义 fractional_stacks=false。旧程序已固定的 minimum 值与目录身份不被改写。存储协议顶层版本保持 1，领域恢复仍要求一致的固定目录；原生实体 / 整个世界跨重启是独立验收范围。

| 条件 | 含义 |
| --- | --- |
| has_buff 的 match:bound | 默认行为，按当前来源和 Buff 的实例键匹配 |
| has_buff 的 match:instance | 仅 BUFF 作用域可用，实例键和 generation 必须与当前规则所绑定的 Buff 一致；同键重新授予不算原实例仍存活 |
| has_buff 的 match:any | 此目标任意来源中，至少一个该定义的实例单独达到 minimum；不跨实例相加 |
| has_buff_tag | 此目标至少一个现存 Buff 的定义含 tag；不限施加来源 |
| has_source_tag | 此目标当前绑定的至少一个 EffectSource 的 tags 或 origin.tags 含 tag；不读取动作保留的旧来源或触发事件标签 |
| has_shield | 此目标至少一层活动、未暂停的 Chorus 护盾余量大于零；可用 tag 筛选层的定义标签 |

它们均接受 target（默认 self），只读取已提交的引擎状态。has_buff / has_buff_tag 保留原有 Buff 存在性语义，暂停实例仍可被查询；has_shield 判断实际参与防御的正容量层，不把空层或原版 Absorption 算进去。Buff / shield 标签取自定义；has_source_tag 则明确查询持有者当前来源，用于子职业等装备资格，不把 Strand 攻击标签当成 Strand 子职业。用于 on_use 修饰时，来源侧判断会冻结，victim 判断留到命中时求值；普通动作分支直接查询当时状态。

Rift 内容将这两个概念分开：presence 带 `chorus:counts_as_overshield` 供交互资格使用，即使尚未生成容量；共享 rift_overshield 才保存能挡伤害的实际池。每位成员的多个 Rift 共用一个池和补充计时器：每 50 ms 观测存活且满血、没有带 `chorus_d2:void_overshield` 标签的正容量层后，补充 0.015，封顶 1.5（原表 3 HP/s、15 HP，测试缩放 0.1）。离开一个场保留其他场；最后一个场清理时移除该池，重新进入从零开始。正常观测失败、死亡、受伤或 Void 盾阻止生成时不累计补偿。

这是一份明确的离散脉冲实现。刚在该 tick 恢复满血时会补完整一次脉冲，不计算此前不足 50 ms 的满血时长；首次脉冲、离场是否残留护盾、空层的 FIFO 年龄及多来源共享池保留首次归属仍需原作校准。下文的连续回充只读取引擎状态，没有自动替代这个世界满血观测策略。可运行示例见 healing_rift.json；shield_restoration.json 中的 Void 标签测试层只有合成的 1 点容量，不是完整 Void Overshield 定义。

### 生命层专属伤害 Profile

程序顶层可选 `health_profile: "example:health_damage"`，所引用 Profile 的输入、输出单位都必须为 `damage`。它与 `defense_profile` 独立；组合程序最多选择一个生命 Profile。省略时保持原版生命结算，未知引用、错误单位及重复选择在编译 / 链接时拒绝。

实际单分量顺序：攻击 Profile → 原版准入、格挡与无敌帧差额 → 全局防御 → Chorus 护盾逐层预算 → 原版护甲 / 魔法减伤 → 原版 Absorption → **生命 Profile** → 原版战斗记录、生命写入和死亡保护。这里的生命指 Minecraft 的实际 HP；D2 基础护盾、生命容量、护盾门槛和恢复规则须由内容另行建模，不能把原版 Absorption 直接等同于 D2 基础护盾。

生命查询以伤害目标为 holder，仍保留原攻击的 actor / victim、来源、标签、组身份和 impact。它读取命中时的当前规则视图，包括前面已消耗的 Chorus 护盾写集；来源侧攻击快照不会冻结目标的生命防御。Profile 内仍可使用已有分组、条件、Buff / 装备参数和数值阶段，查询本身没有效果动作。只有最终到达生命的正预算才求值：零预算不运行固定加值、替换或曲线，避免完全格挡、吸收或后置清零之后重新产生生命伤害。

`DamageReceipt.health` 保存可选完整计算轨迹，`healthLoss` 仍来自实际生命写入，不能以 Profile 输出替代。非致死下限、过量伤害和死亡保护均由既有原版路径结算。生命 Profile 将正预算降为零且没有任何其他实际损失时，回执为 BLOCKED；此前盾或吸收已经损失时仍为 APPLIED。

Fabric 在吸收量和统计量已经计算后、生命分支前改变伤害参数；NeoForge 在 `LivingDamageEvent.Pre` 与吸收修饰之后读取容器的生命预算，并写回最终值，使 Post 的 healthDamage 保持一致。不会回写已消耗的盾 / 吸收量，亦不改写 NeoForge 早前捕获的 inflictedDamage。玩家与普通生物各自的原版路径、嵌套受击和父类委托都只执行自己的查询一次。

本次查询出现未知 / 非法结果时异常交给伤害边界，不继续用原始预算扣血，不伪造成功回执，也不自动重放。已发生的世界吸收扣除保留，Chorus 护盾写集保留在失败记录等待显式恢复；不能把失败前领域快照误读为盾已恢复。运行时停止后的后续原版伤害沿用既有政策，停止规则推导并继续原版处理。

可执行示例为 [health_layer.json](../common/src/test/resources/effects/health_layer.json)。全部比例均为合成验收输入：40 × 攻击 2 × 全局 0.5 = 40，扣 10 盾后原版护甲将 30 降至 24，吸收 4 后生命输入 20，再乘 0.25，实际扣 5 HP。这只证明通用表达和宿主顺序，不将任一 Compendium 条目标为完整实现。

### 连续回充与受击延迟

护盾声明可另加 `recovery`，rate 使用 damage_per_second 单位，if 为可选条件，默认 true。例如下面的 shield 片段用于已有容量组件和数值 ready 组件的护盾：

```json
{
  "capacity":"capacity",
  "maximum":{"type":"chorus:constant","value":10,"unit":"damage"},
  "recovery":{
    "rate":{"type":"chorus:constant","value":2.5,"unit":"damage_per_second"},
    "if":{"type":"chorus:compare",
      "left":{"type":"chorus:component","buff":"example:shield","component":"ready"},
      "op":"gt","right":{"type":"chorus:constant","value":0,"unit":"count"}}
  }
}
```

rate / if / maximum 在该接收层的 Buff 作用域求值，每层只有一个恢复表达式，不按 Buff 堆叠层数自动重复。可使用现有环境、组件和条件数值表达式；这不是施加时事件的重放，不能依赖之前事件的数值或动作结果。它不查询实体、不判断原版存活 / 满血，也不启动生命自然恢复。需要这些条件的内容须另行观测和维护资格，失效时结束层或关闭恢复。

时间轴按段首状态对 `[from, until)` 积分。50 ms 网格是最大结算间隔；Buff 到期、定时器、外部输入和补满容量也形成边界，补满时间向上取整到整数微秒。暂停、条件不满足、零速率或满容量不积攒额度；后续改速率只影响后续时间。上限降低不伤害已有容量，容量恢复不改变原始 generation / 来源 / FIFO。任意依赖自身容量或其他连续量的表达式仍是分段常数近似，除了容量上限与已有时间轴边界，不会自动求出表达式内部的所有阈值。

回充先写入真实的领域容量，再推进到期与世界治疗；到期的最后残段保存在 ended 快照中。后续世界回调中的伤害能立即消耗已经恢复的量，重复回执不会再次积分。正增加仍发 shield_restored，另带 `chorus:continuous_shield_recovery` 标签、interval_start / interval_end（second）和 rate（damage_per_second）；保留该层最初来源。若同一时刻也有生命恢复，先完成已分配的原版治疗命令及其事实，再分发护盾恢复事实、生命周期、资源与定时器信号。事件读取的是该边界之后的当前状态，已经结束的层不会为恢复事实重新挂回自身规则。

受击延迟是内容规则：收到指定事实后将 ready 写为 0，用 schedule 的 replace 策略重置一次性计时器；own_timer 到期后写回 1。`chorus:own_shield` 仅能在 Buff 作用域使用，同时匹配层的 definition、generation 和受益者，可筛选 shield_damaged / shield_broken / shield_restored；不会让旧层事实影响同键的新层。它不自动选择事件类型。若任何持有者实际损失都应重置延迟，则监听 damage_taken 并筛选 victim=self；只由本层受伤中断则监听 shield_damaged + own_shield，取消 / 免疫命中不凭空触发这些损失事实。

合成示例 [shield_recovery.json](../common/src/test/resources/effects/shield_recovery.json) 验证 70.001 ms 延迟与到期残段；[eternal_warrior.json](../common/src/test/resources/effects/eternal_warrior.json) 验证快照中的 75 HP 护盾、5 秒停伤延迟和 7 秒满量回充，使用 0.1 测试缩放。后者仍由明确的 test:fists_start / test:fists_end 输入控制，已能对明确标记的精准因子做层内抑制，见下节；真实超能、自动精准判定和武器增伤仍未接入。恢复专用 Profile、跨来源回充通道选择与完整基础护盾 / 自然恢复装配仍待完成。

## 按当前护盾层计算攻击倍率

`damage` 与 `capture_damage` 可声明 `shield_scaling_profile`。它独立于全局 `scaling_profile`，要求输入和输出都为 `multiplier`，每层以 1 为基值计算。省略时攻击侧层倍率为 1。以下动作要求程序已声明两个 Profile：

```json
{
  "type": "chorus:damage", "target": "victim",
  "amount": { "type": "chorus:constant", "value": 20, "unit": "damage" },
  "damage_type": "minecraft:generic", "tags": ["chorus:weapon_direct"],
  "scaling_profile": "test:weapon_damage",
  "shield_scaling_profile": "test:shield_attack"
}
```

攻击者的 modifier 可用 `{ "type": "chorus:layer_tag", "tag": "chorus:guardian_overshield" }` 判断当前接收层的 Buff 定义标签。它也可用于 `taken_multiplier` 中的 choose；普通攻击 / 防御查询没有层上下文，此条件为 false。层标签不会加入伤害标签或击杀信用，目标全身的 `has_shield` 不能代替当前层判断。

层按 priority / FIFO 处理，只查询实际到达的正容量、未暂停层。该层的综合倍率为 `taken_multiplier × shield_scaling_profile.output`，损失容量除以综合倍率得到消耗的输入预算；余量交给下一层，最后才进入原版护甲 / Absorption / 生命。例如输入 20、容量 3、层攻击倍率 1.5：消耗 2 点预算，余下 18 点不再携带这项护盾专属增伤。零综合倍率沿用免疫层阻断规则。原版取消 / 免疫 / 无敌帧拒绝在此之前发生。

所有层读取同一次命中的不可变状态，预算逐层递减，查询本身不先写入容量；`incoming_damage` 在即时层查询中表示抵达该层的剩余预算。沿用普通快照规则，on_use 的 event_number 在捕获时绑定；命中测量应使用 impact_number。零输入、空层和前层已吸收全部预算时不会继续求值后层表达式。

`capture_damage` 同时固定护盾 Profile 和 on_use 来源操作数，保留 layer_tag、目标条件及 impact_number 到命中时。来源解绑也不丢失已捕获贡献；当前 on_hit 贡献与它们一起进入原分组，MAX 不会被提前折成两个相乘的倍率。`damage_snapshot` 使用快照中的选择，不能在命中时更换护盾 Profile。逐层回执的 `attackScaling` 保存独立计算轨迹；跨版本仍要求阶段与分组结构相容，目标 has_shield 使用当前规则目录查询。

[under_over.json](../common/src/test/resources/effects/under_over.json) 与 [shield_scaling.json](../common/src/test/resources/effects/shield_scaling.json) 可链接为测试程序，分别提供词条与合成盾 / 输入。元素盾、勇士屏障、战员和 Guardian overshield 的标签由内容明确声明，宿主还须提供武器身份、直击 / 爆炸词条和 Guardian / bodyshot 标签。精准因子抑制另见下节；生命层专属 Profile 已接入，穿透和自动盾分类尚未提供；测试的 MAX 分组和跨层预算仍需按具体原作组合校准。

### 护盾排除已经应用的攻击因子

攻击 Profile 的 multiply 阶段可声明 `factor`，例如：

```json
{
  "type": "chorus:apply", "id": "precision", "operation": "multiply",
  "group": { "name": "precision", "reduction": "max" },
  "factor": "chorus:precision"
}
```

`factor` 必须是命名空间 ID，只允许标记 multiply 阶段。多个阶段可声明同一因子，排除时全部跳过；其他步骤仍按原顺序执行。阶段叫 precision 或事件带精准标签都不会自动产生因子。命中位置与倍率仍由宿主 / 内容提供，示例用显式 impact_number 读取精准增量。

护盾可声明 `"excluded_attack_factors": ["chorus:precision"]`，仅在该层排除攻击 Profile 的对应阶段；默认空集合。不存在于该攻击的因子不改变伤害，未指定攻击 Profile 的原版伤害也不会被猜成暴击。使用 Java 的 shields 查询时，若攻击选了 Profile 且命中排除因子的层，必须传本次实际的 DamageBasis；不能只传一个已缩放数字。Minecraft 适配器已自动传递这份数据。

排除过程保留本次已求值的贡献与旧 Profile，重新计算剩余数值步骤，包含后续 add、percent_of、曲线、clamp 和 round。例如基础 10，精准 ×2、随后 +5、上限 22：原输出 22，排除精准得 15，不能把 22 除以 2。当目标防御也有固定加值或上限时，同样用其本次已求值的贡献重算。

这不是第二次模拟命中：来源 / 目标条件、Value 表达式和原版取消 / 格挡 / 无敌帧都不会再次执行。已通过原版门槛的预算，先按排除因子前后的攻击输出比例换算，再通过保存的目标防御步骤；层内使用两份防御输出的比例。后续层与生命继续使用原预算，只有当前层按比例扣容量。该比例延续 Chorus 的跨层预算约定，不代表任意第三方模组或原作采用相同算法。原输出为零却收到正预算时无法建立此比例，明确失败。

`CalculationProfile.Result` 保存不可变 Inputs；`withoutFactors` 排除因子，`withBase` 更换同单位基值，均不读取效果状态。trace.factors 记录每个被标记阶段的真实倍率及 omitted 标志，即使阶段输入是零也能保留倍率。每层 factorSuppression 记录请求排除的因子、攻击 / 防御重算轨迹和最终比例，不改写 hit / kill 的精准标签。

[precision_damage.json](../common/src/test/resources/effects/precision_damage.json) 与 Eternal Warrior 片段链接，验证精准后的固定加值、原版护甲 / Absorption、普通盾溢出、无敌帧差额和冻结攻击。精准抑制只覆盖明确标记的数值阶段；需要随阶段结果改变的量应写为 percent_of 等数值步骤，已经求值的 event_number 或条件不会因排除因子重新读取。自动弱点判定、分量聚合、原版暴击适配和 D2 精确跨层校准仍待完成。

## 声明资源与支付成本

程序根级的 `resources` 声明账户类型，具体实体通过 `initialize_resource` 创建账户。一个账户由 holder + resource id 唯一定位；重复初始化保留当前值，解绑来源也保留账户。来源负责授予使用规则和修饰，账户不会因切换来源而免费回满。

例如 `data/example/chorus/effect_program/energy.json`：

```json
{
  "version": "example-1",
  "resources": [{"id": "example:energy", "capacity": 2, "initial": 0,
    "base_rate": 0.5, "thresholds": [1]}],
  "bundles": [{"id": "example:energy", "rules": [
    {"id": "initialize", "on": "chorus:source_attached",
      "if": {"type": "chorus:own_source"},
      "do": [{"type": "chorus:initialize_resource", "resource": "example:energy"}]},
    {"id": "ready", "on": "chorus:resource_changed",
      "if": {"type": "chorus:resource_crossed", "resource": "example:energy", "threshold": 1, "direction": "up"},
      "do": [{"type": "chorus:heal", "amount": {"type": "chorus:constant", "value": 1, "unit": "damage"}}]},
    {"id": "use", "on": "chorus:hit",
      "if": {"type": "chorus:source_is", "source": "owner"},
      "do": [
        {"action": {"type": "chorus:spend_resource", "resource": "example:energy", "payment": "hit",
          "amount": {"type": "chorus:constant", "value": 1, "unit": "charge_fraction"}}, "as": "cost"},
        {"if": {"type": "chorus:result_flag", "binding": "cost", "field": "succeeded"},
          "then": [{"type": "chorus:heal", "amount": {"type": "chorus:constant", "value": 2, "unit": "damage"}}]}
      ]}
  ]}]
}
```

使用 `chorus engine start example:energy`、`chorus engine attach @s example:energy skill` 启动并绑定。该示例每秒恢复半份充能，第一次达到一份时回血；造成命中时尝试消费一份，成功再回血。全部数值都是通用机制演示参数。若已有运行时，先显式 stop；这会清空旧暂态状态。

- 一份充能固定为 1；capacity = 2 不会把 `charge_fraction = 0.1` 翻倍。当前只实现共享能量的顺序回充。capacity / initial 必填，base_rate 默认 0，允许负速率表示持续消耗。capacity 必须为正，initial 必须在初始容量内；thresholds 非负、不重复。默认 resizable = false，此时阈值也不得超过声明容量；可变账户允许预先声明未来扩容后才可达的阈值。
- `rate_profile` 可引用本程序中的 Profile，其输入、输出均须为 `charge_fraction_per_second`。base_rate 是输入，输出是本段积分速率，静态来源与 Buff 的 modifiers 按同一数值管线归约。速率查询携带 resource 引用、resource_value / capacity 测量和 `chorus:resource_rate_query` 标签；归属为账户持有者，不把多来源回能任意算给一把武器。完整示例见 [resource_regeneration.json](../common/src/test/resources/effects/resource_regeneration.json)。
- 独立的 `gain_profile` 用于 `grant_energy`，输入、输出均须为 `charge_fraction`；资源只声明 rate_profile 不会自动获得一次性收益的缩放。可另声明非负 `gain_scalar`（默认 1）和 `gain_scalar_profile`（输入、输出均为 multiplier），在收益 Profile 前计算当前接收系数。所有已声明的 Profile 均在编译时验证引用和单位。
- 0、capacity 以及 thresholds 是精确逻辑时间边界；需要监听的中间整格必须声明。`resource_crossed` 默认匹配 self 的账户，支持 up / down；达到后停留在阈值上不会重复触发。一次性入账或消费跨越阈值同样生效。初始化只发 initialized，初值不冒充恢复过程。
- 初始化返回 value / created；消费返回 paid / after / succeeded，并保留实际成本回执。余额不足时不部分扣款；零成本可以成功但 paid = 0。payment 是规则内唯一的本地标识，完整回执身份另含事件、规则实例及动作 OperationId；同一指令在循环中每次执行也是独立付款，不在不同激活或迭代间复用。
- `chorus:resource_changed` 只在值变化时发布；`resource_granted` 另带请求 / 入账测量，`resource_spent` 在实际扣费后发布。统一测量为 before / after / delta / capacity，单位 charge_fraction；引用为 resource / reason，布尔值为 changed。恢复、普通入账、完整充能、消费、返还的 reason 分别为 regeneration / grant / full_charge / spend / refund。通用规则通常监听 changed，避免同时监听专项事实而重复发放同一收益。
- 定义只描述账户，不自动创建所有玩家的资源。DSL 读取、入账或消费未初始化账户会报错；缺失定义、错误 Profile 单位、未声明的反应阈值在加载时拒绝。
- 已声明资源由程序接管速率；Java 宿主速率接口只为程序外账户保留。账户解绑后仍按定义恢复；若玩法要求停用时停止，应通过来源修饰和 base_rate = 0 等内容规则表达。显式容量变更与联动回充见下节；持久化、任意 Buff / 条件驱动的容量协调、parallel、多份充能分配策略和持久成本账本尚未实现。

### 显式资源容量变更

资源声明 `"resizable":true` 后，`resize_resource` 可改变已存在账户的上限。定义中的 capacity / initial 仅用于首次创建；重复初始化保留当前上限和余额。默认不可变账户在编译和执行时均拒绝 resize，不能通过数据指令绕开声明。

```json
{"action":{"type":"chorus:resize_resource","resource":"example:energy",
  "capacity":{"type":"chorus:constant","value":3,"unit":"charge_fraction"}},
 "as":"resized"}
```

target 默认 self；capacity 必须为有限正数，单位 charge_fraction。新余额严格为 `min(旧余额, 新上限)`，不按比例缩放、补满或保留隐藏溢出；账户身份、逻辑时间、已经支付的成本和退款资格不变。三格只是示例，不是全局上限；连续资源也允许小数容量。技能完整充能阈值依旧是一份能量，不随上限改变。

结果提供 before / after / before_capacity / capacity / discarded（charge_fraction）及 changed（上限是否变化）。上限变化发出 resource_capacity_changed；只有裁剪实际改变余额时才另发 resource_changed，两者 reason 均为 resize。事实包含原有 before / after / delta / capacity，并增加 before_capacity、discarded 和 capacity_changed。相同上限返回未变化结果，不重复发事实。缩容不是付款、收益或退款，没有 paid / credited，不创造可退款额度；resource_crossed 可正常观察实际向下裁剪。

时间轴在外部 resize 前先按旧上限结算；后续恢复使用新上限。thresholds 中超出当前上限的值暂不进入下一截止点计算，扩容后恢复生效，0 / 当前上限始终参与时间边界。需要精确监听的中间整格仍应显式声明。缩容后的延迟退款按当时容量裁剪，溢出消耗原退款额度；重新扩容不能取回已经丢弃的余额、满槽期间的恢复时间或退款溢出。

这是显式动作，不会自动监听任意属性变化。多个来源决定容量时，先用 calculate 按接收者查询完整 Profile，再把结果交给 resize；应在来源或选择事务已提交后按内容规则重算，不能各自卸下时盲目设回基础容量。示例 [resource_capacity.json](../common/src/test/resources/effects/resource_capacity.json) 验证两个 +1 来源合成三格、接收者隔离和移除一个来源后保留另一个。缩容裁剪是该指令的明确语义，是否适用于某项 D2 换装效果仍需原作校准。

### 按当前技能槽调整可用次数容量

通用装备规则可用 `resize_ability_resource` 定位接收者当前基础选择的成本账户，不必列出所有技能的资源 ID：

```json
{"action":{"type":"chorus:resize_ability_resource","slot":"chorus_d2:melee",
  "target":"self","capacity":{"type":"chorus:constant","value":3,"unit":"charge_fraction"}},
 "as":"resized"}
```

slot 必填，target 默认 self，capacity 仍为有限正数 charge_fraction。执行时读取接收者的当前基础选择；临时施放替换不会改写目的账户。存在独立 recharge_resource 时只调整可用次数账户，进度的容量、余额和逻辑时间保留。旧单账户技能沿用同一账户。扩容不补能，缩容裁剪，不创建新账户、不迁移其他技能余额；发布的容量 / 裁剪事实与 resize_resource 相同。

结果有 `resized`、`changed`、`no_selection`、`no_resource`、`not_resizable` 标志。空槽、无成本资源或不可变容量均返回对应结果，不求值 capacity、不改变状态，也不发布容量事实。这里不可变容量是动态目标不支持该动作的显式结果；直接指定资源的 resize_resource 继续在编译 / 执行时严格拒绝不可变声明。成功时 resized=true，即使相同容量使 changed=false；before / after / before_capacity / capacity / discarded 仅成功时可读，其他结果不伪造零值。Java 回执保存 holder、slot、所选 ability 和实际 Resource ResizeResult，可追溯当时选中的账户。

延迟执行会作用于届时所选技能；期间清空槽位就返回 no_selection，不修改旧账户。技能切换或装备变化时是否重算由内容规则决定，动作不会遍历并改写所有未选账户。示例 [ability_capacity.json](../common/src/test/resources/effects/ability_capacity.json) 在 SOURCE / 选择事务后读取完整来源集合；等效物品替换不先缩再扩，重新选择旧技能时显式重算保留账户。两格 / 三格、半份每秒恢复和治疗观察都是合成输入，不是 Ophidia Spathe 的原作换装规则。

D2 [grenade_energy.json](../common/src/test/resources/effects/grenade_energy.json) 已用 grenade_capacity_binding 组合自身 source_attached / source_detached 与持有者 abilities_changed，在完整来源提交后重算容量。手雷选择期来源及 [Spirit](../common/src/test/resources/effects/spirit_armamentarium.json) 共同复用；同一容量多次计算返回未变化结果，不重复发布容量事实。实际装备替换读取最终来源集合，避免等效替换先缩容再扩容；空槽卸装仍由旧来源的卸下反应完成协调。账户声明整格阈值 1 / 2，当前上限由时钟自动纳入。该内容绑定不覆盖任意 Buff / 条件变化，新增容量政策仍需声明对应重算入口；完整限制见 [D2 规则集](d2-ruleset.md#spirit-of-the-armamentarium-额外手雷充能)。

### 联动回充的独立进度与可用次数

联动回充应分别保存可用次数与一轮回充进度。不能把两格总容量的恢复率乘二：后者会在半轮时产生一份可用能量，而联动策略可能要求整轮结束才一起恢复。当前提供显式 `complete_recharge`，由内容组合两个普通资源；`recharge_policy` 仍不是资源声明支持的字段，也没有隐式全局配对表。

```json
{"action":{"type":"chorus:complete_recharge",
  "progress":"example:melee_cycle","resource":"example:melee_uses",
  "target":"self"},"as":"cycle"}
```

progress 必须声明为固定容量 1，禁止 resizable；两账户必须已初始化、属于同一目标、处于同一逻辑时间，且身份不同。进度不足 1 时返回 completed=false，状态和事实均不变，也不求值可选的 amount。进度满时，在一次 Local 迁移中把进度清零并增加可用次数；省略 amount 保持原行为，补满可用账户的**当前**容量。target 默认 self。转换不经过 CES / 收益 Profile，因为进度收到能量时已经完成对应缩放，不因可用次数上限而再乘一次。

可选 amount 是 charge_fraction 表达式，表示这一轮申请恢复的次数；允许有限的非负数，包括零和小数，超出当前缺口的部分作为 overflow 丢弃。负数、非有限结果或单位不符在两账户写入前失败。amount=0 仍会消费完整周期；小数收益会保存在可用账户，但不能支付超出余额的技能成本。若需要由装备 / Buff 决定收益，可在完成动作前用 calculate 查询一个 Profile，再引用其 value；不必替换技能定义或迁移资源账户。

```json
{"type":"chorus:complete_recharge",
 "progress":"example:melee_cycle","resource":"example:melee_uses",
 "amount":{"type":"chorus:constant","value":1,"unit":"charge_fraction"}}
```

结果字段 progress_before / progress_after、before / after、capacity / requested / credited / overflow 均为 charge_fraction，另有 completed 标志。未完成时 requested / credited / overflow 均为零。先发进度账户的 resource_changed（reason=recharge_completed），再发可用账户的 resource_granted（reason=linked_recharge）；有正补入时再发其 resource_changed。所有观察者看到的两个账户均已提交。转换不发布 resource_spent，不生成可退款的 CostResult；重复执行看到清零后的进度，不会重复补入。可用账户已满时仍可显式消费一个完成周期，此时 credited=0。

`chorus:resource` 表达式新增可选 field：value（默认，兼容原读法）、capacity、missing，单位均为 charge_fraction。capacity 读取已调整后的上限，missing 用当前上限减余额；缺失账户仍报错，不默认为零。快照中的 self 读取冻结为常量，victim 读取保留到命中期。这些字段可用于“可用账户未满才让进度继续恢复”等速率条件。

[linked_recharge.json](../common/src/test/resources/effects/linked_recharge.json) 是完整可执行的合成示例。技能只从可用账户支付一份，外部能量送入进度账户；资源越过进度 1 的规则显式完成一轮。示例选择首次从满次数使用时清空旧进度，中途再次使用保留本轮进度，满次数不积存时间或外部回能；解绑提供恢复率的来源会暂停，重挂保留账户。2 秒周期、0.5 CES、0.5 秒加速 Buff 和治疗动作体均是测试校准。联动完成只是一个动作，父帧会先执行完再处理派生事实；需要在同一帧中立即使用新次数时，应在进度入账后显式执行 complete_recharge。

[resource_cycle_yield.json](../common/src/test/resources/effects/resource_cycle_yield.json) 进一步演示同一双账户技能的模式切换：默认每轮恢复一份；装备来源将完成时的收益替换为当前缺口；高优先级 Buff 可临时替换回一份。两种模式从始至终共用同一可用账户与进度账户，换装只改变完成时查询到的修饰，不重置已有进度、不产生新账户或免费次数。容量变化仍由 resize_resource 独立处理。一秒周期和这些换装 / Buff 政策均为合成配置；这不是把任意旧单账户自动迁移成双账户的功能。纯核心测试验证优先级、精确到期、重复来源、其他持有者隔离和当前上限；双加载器还验证真实容器替换 / 卸装、自然回充、实际施放和未知世界观察结果保留。

技能现可通过 recharge_resource 将按槽收益指向独立进度，观察接口同时保留可用次数与进度，见下文。示例把核心周期规则放在 test:linked，由技能的选择期 effects 自动绑定；合成输入另在 test:linked_inputs，避免相同核心来源重复绑定时把一次输入也执行多遍。Pugilist、Wellspring、Surplus 无需改写定义即可组合这类账户。任意分组、每格 parallel 进度、原作时窗、生产金装装配、保存恢复和专用 HUD 仍未实现，这一组合不代表 Ophidia Spathe 的完整行为已验收。

### 归一化后授予能量

`chorus:grant_energy` 使用 resource、target（默认 self）、amount（charge_fraction）和必填的 value_basis。base 以 amount 为基础值；reference 先除以 reference_factors 中所有正倍率的乘积。两者先将基础值乘当前接收系数，再送入资源的 gain_profile；fixed 直接入账，跳过接收系数及全部 Profile。base / fixed 禁止 reference_factors，reference 要求非空，fixed 还禁止查询 tags / numbers。base / reference 缺少 gain_profile 在编译时拒绝，非有限 / 非正参考因子在求值时拒绝；均不会先写账户再报错。

`gain_scalar` 是资源固有倍率，`gain_scalar_profile` 以它为输入，通过当前接收者的来源 / Buff 计算实际倍率。零是有效的零收益；负数或非有限结果在账户写入前报错。接收系数查询与 gain_profile 使用同一账户、来源和查询上下文，各自保留轨迹。卸下覆盖来源只改变未来收益，不改写声明或已有余额；fixed、refund_cost、grant_full_charge 与被动恢复均不走此步骤。旧内容默认倍率为 1，已在 gain_profile 中包含 CES 的内容不能再重复声明同一系数。

例如资源声明 `"gain_profile":"chorus_d2:threaded_spike_gain"` 后，以下动作将已包含 100 属性倍率 2.25 与接收系数 0.8 的 7.2% 还原为 4% 基础值，再按当前接收者计算。它需要链接 [threaded_spike_energy.json](../common/src/test/resources/effects/threaded_spike_energy.json) 及 Threaded Spike 的资源定义：

```json
{
  "type": "chorus:grant_energy",
  "resource": "chorus_d2:threaded_spike_energy",
  "target": "victim",
  "amount": {"type": "chorus:constant", "value": 0.072, "unit": "charge_fraction"},
  "value_basis": "reference",
  "reference_factors": {
    "example:stat": {"type": "chorus:constant", "value": 2.25, "unit": "multiplier"},
    "example:recipient": {"type": "chorus:constant", "value": 0.8, "unit": "multiplier"}
  }
}
```

查询使用目标账户持有者的来源 / Buff 修饰；动作来源与触发事件的 actor / victim 保留，resource 引用设置为接收资源。tags 默认为空，不继承触发事件标签；numbers 继承事件测量后接受显式覆盖。因此 CMS 等来源特例必须明确传入查询标签与测量，不能因为触发事件恰好同名便额外缩放。核心不内置任何 D2 系数。

绑定结果可读 requested / normalized / scaled / credited / overflow / after，单位均为 charge_fraction；另有 `recipient_scalar: multiplier`，只可读取 base / reference 的有效系数，fixed 的该字段缺席且读取报错。Java 结果保留参考因子、接收系数的固有值 / 实际值 / 可选查询轨迹，以及最终 gain_profile 轨迹。满容量仍发布 resource_granted，只有余额变化才发布 resource_changed。参考属性与版本放在内容出处中，reference_factors 只表达确实包含的倍率，不是自动跨版本转换。旧 grant_resource 仍用于内容已经完成缩放的 requested / scaled；固定能量比例也可显式使用 grant_energy 的 fixed，不依赖实付成本。完整合成触发例见 [spike_energy_inputs.json](../common/src/test/resources/effects/spike_energy_inputs.json)，不代表某个回能 perk 已完成装配。

### 按当前技能槽授予能量

`chorus:grant_ability_energy` 接受 slot（命名空间 ID）以及与 grant_energy 相同的 target / amount / value_basis / reference_factors / tags / numbers。它在执行时读取目标的基础技能选择，优先使用该技能的 recharge_resource，省略时使用 cost.resource；收益缩放和轨迹来自实际接收账户。它不重新解析施放时的临时 ability_overrides，外部回能仍属于已选基础技能。施放支付与退款继续使用实际 cost，不因为独立回充路由而改写。

```json
{"id":"example:linked_melee","slot":"example:melee",
 "cost":{"resource":"example:melee_uses","amount":{"type":"chorus:constant","value":1,"unit":"charge_fraction"}},
 "recharge_resource":"example:melee_cycle","on_use":[]}
```

recharge_resource 必须引用目录中已声明的资源，并要求技能自身声明 cost；不能只靠 cost_from:base_selection 补造基础选择的回能含义。两者可以指向相同资源，这时沿用旧行为。指向不同账户时，默认按槽收益进入进度、由相应完成规则恢复可用次数；显式 recharge_yield_profile 可启用下述单次回能跨多周期的结算。首次选择初始化该槽候选技能涉及的扣费和回充账户，重新选择只校验已有状态，不重置余额或进度。周期规则可通过选择期 effects 自动装卸，暂停 / 恢复率仍由内容声明。

未选择该槽时返回 no_selection，已选技能未声明 cost 时返回 no_resource，两者均不求值 amount、不写状态、不发资源事实。声明 cost 且 amount 为零仍有资源账户，不等同于未声明 cost。缺失已选定义、未初始化账户、无 gain_profile 的普通收益等属于配置错误，求值失败，不能伪装成不符合资格。

当路由到独立账户而可用账户已经达到当前上限时，返回 already_full，不求值 amount、不写进度、不发资源事实，防止在满次数背后额外积存一轮。它不会清掉内容已经显式写入的进度；直接 grant_energy / grant_resource 仍保留按指定资源操作的语义。单账户满容量保持原来的 granted 与 credited=0 / overflow 回执，不改变旧内容。

绑定结果提供 granted / no_selection / no_resource / already_full 布尔值；仅 granted 时允许读取 requested / normalized / scaled / credited / overflow / after，缺失收益读取数值会报错。Java 结果保留 holder / slot / ability 和嵌套收益轨迹；该轨迹的 after 是入账完成时的接收账户读数，后续完成周期的事实不会改写旧回执。slot 只校验 ID 格式；已选账户必须有效，即使可用次数已满，也不能掩盖缺失或损坏的回充账户。普通收益所需 gain_profile 在实际结算时检查，显式 resource 版本继续提供固定引用的加载期检查。

例如 [pugilist.json](../common/src/test/resources/effects/pugilist.json) 的击杀分支使用如下动作，数值依据和换算边界见 [Pugilist](d2-ruleset.md#pugilist)：

```json
{"type":"chorus:grant_ability_energy","slot":"chorus_d2:melee","value_basis":"base",
 "amount":{"type":"chorus:constant","value":0.04,"unit":"charge_fraction"}}
```

发射后换技能时，以击杀收益执行时的选择为准。选择变化不会把旧池能量转移到新池，也不会自动重配共享池的容量 / CES；内容可以让同槽技能支付同一资源，并以选择期 effects 提供恢复率和收益系数，D2 手雷片段现采用这一方式。无选中技能时的跳过、基础技能归属和当前时点路由都是显式宿主政策，不能据此宣称已校准全部原作换装行为。独立账户的合成候选示例见 [ability_energy_targets.json](../common/src/test/resources/effects/ability_energy_targets.json)。

[demolitionist.json](../common/src/test/resources/effects/demolitionist.json) 进一步组合两个独立分支：weapon_kill 使用 grant_ability_energy 指向 grenade 槽；已接受的 ability_started 用 grenade_ability、weapon_drawn 和无冷却条件筛选，再执行 refill_magazine。只有结果 applied > 0 才授予 3 秒冷却，不从请求量推测实际补弹。底层不发布 reload_finished，冷却也不限制另一条击杀回能规则。技能原型、数据基准及待校准政策见 [Demolitionist](d2-ruleset.md#demolitionist)。

### 一次回能跨越多个充能周期

技能可显式声明 `recharge_yield_profile`，使 grant_ability_energy 在一次状态迁移内将整笔能量转换为可用次数及下一轮进度，避免先写入容量为 1 的进度账户而丢掉第二轮能量：

```json
{"id":"example:cyclic_melee","slot":"example:melee",
 "cost":{"resource":"example:melee_uses","amount":{"type":"chorus:constant","value":1,"unit":"charge_fraction"}},
 "recharge_resource":"example:melee_cycle",
 "recharge_yield_profile":"example:cycle_yield","on_use":[]}
```

该字段要求独立的 recharge_resource：固定容量 1、禁止 resizable、与 cost.resource 身份不同；Profile 的输入 / 输出均须为 charge_fraction，缺失或不兼容在链接时拒绝。没有此字段的已有技能、直接 grant_energy / grant_resource、自然恢复和 complete_recharge 保持原协议，不自动迁移内容定义。自然周期仍由内容明确完成，若要与外部回能共享收益政策，应查询同一 Profile。

整笔请求先归一化，再应用一次接收者系数和收益 Profile；fixed 跳过这两项缩放，但仍使用周期收益政策。仅当原进度加缩放后能量达到 1 时，以 1 charge_fraction 为输入查询一次 recharge_yield_profile：持有者为接收者，状态为入账前状态，事件为该进度账户 before=after 的资源事实，reason=grant，origin 保留动作来源。结果表示每个完整周期恢复的可用次数，本次所有周期共用该结果；中间不发布可触发换装或修改收益的事件。负数或非有限收益在两个账户写入前失败；零收益仍消耗完整周期，小数收益允许保存但不保证能支付技能。

例如原进度 0.7、收到 1.8、每轮恢复一次且容量足够，结果为两次可用充能和 0.5 进度。可用次数达到当前容量时清零进度，剩余输入才算能量 overflow；一次周期产生的次数超过缺口时，超额次数另记录在 Java 的 uses 回执中。核心用十进制算术直接求周期数，没有逐周期循环，也没有人为限制能量或游戏反应次数。两个账户在同一次 Local 中提交，后续规则和世界动作只能看到最终状态；未知世界结果不回滚、不重放已确认收益。

granted 回执新增 cyclic 和 yield_observed 标志。cyclic 时额外提供 cycles（count）、uses_credited / uses_after / yield（charge_fraction）。不够完成一轮时 yield_observed=false，yield 缺席且读取报错，不能把未查询当成零收益。原有 credited 表示实际接受的整笔输入能量，包含已转换成次数的部分；after 表示最终进度，不是可用次数。Java 用 received() 读取共同收益、cyclic() 读取双账户回执；旧 granted() 只适用于原单账户回执。

事实按以下顺序发布，均观察已提交的最终状态：

1. 进度账户 resource_granted；只有净进度变化才发 resource_changed。reason=cyclic_recharge，保留 before / after / delta / capacity，追加 requested / scaled / credited / overflow / cycles / uses_credited / uses_after，以及 yield_observed 和仅在已查询时存在的 yield；uses_resource 引用可用账户。
2. 有完整周期时发布一次聚合 `chorus:recharge_completed`，携带同一进度事实，然后发布可用账户的 resource_granted，实际补入大于零时再发其 resource_changed。

不会逐轮发布进度达到 1 的中间事实，因此原有阈值完成规则不会再次消费本次周期；需要对外部回能完成周期做反应的内容应读取聚合事实的 cycles / uses_credited，而非猜测一次事件只完成一轮。已满可用账户仍返回 already_full，不查询 amount 或收益 Profile。

[cyclic_recharge.json](../common/src/test/resources/effects/cyclic_recharge.json) 是合成验收：容量 3、一秒自然周期、0.5 接收系数和治疗观察不代表任何 D2 技能。纯核心与两加载器验证一次恢复两次实际施放、保留下一轮进度并继续自然恢复、当前持有者收益、重复完成来源、完整入账后观察、容量溢出与未知世界结果。Lightweight Knife 已通过该字段接入共享 Solar 周期收益，并组合真实 Gambler's Dodge 的近战回能；技能仍需显式时序 / 位移 / 职业曲线校准，内容边界见 [规则集](d2-ruleset.md#gamblers-dodge-的位移与多充能回能)。

### 观察当前技能槽能量

`chorus:observe_ability_energy` 接受 slot 和 target（默认 self），按当前基础选择同时解析扣费账户和回充目的账户，绑定同一时点的不可变观察。不会初始化账户、扣费、运行 gain_profile、发布事实或改变状态。后续回能不改写已有观察；多个技能的分配规则应先观察全部槽，再按这些结果计算各份收益。

原有数值字段 value / capacity / missing 仍读取扣费账户，单位 charge_fraction；full_charges 为 count（向下取整，一份充能恒为 1）。full 也仍表示扣费账户达到上限，Surplus / Wellspring 不会把半轮进度误当成半份可用次数。新增 recharge_value / recharge_capacity / recharge_missing（charge_fraction）读取接收账户，recharge_full 表示其达到上限，separate_recharge 表示两者身份不同。没有独立路由时两组数值指向同一账户。

available / no_selection / no_resource 保留原语义。缺失选择或 cost 时分别设置 no_selection / no_resource，available、full、recharge_full、separate_recharge 均为 false；任何数值读取都会失败，需先用 available 守卫。任一应有账户缺失、容量不匹配或已选定义损坏仍是错误。读取不要求 gain_profile，也不把 cost.amount=0 当成无账户。

```json
{"action":{"type":"chorus:observe_ability_energy","slot":"chorus_d2:melee"},"as":"melee_before"}
```

Java 结果保留 holder / slot / ability、account 与 rechargeAccount，两个 ResourceState 各自保存资源键与相同逻辑时点。它是观察证据，不是冻结路由的支付凭证；之后的 grant_ability_energy 仍解析执行时选择。跨延迟或世界调用的分配需要另外明确路由政策。当前可观察顺序账户和分离的联动进度，仍不能把 full_charges 当作每格并行进度模型。

属性查询也能使用只读 `chorus:ability_energy` Value（slot / target / field）及 `chorus:ability_energy_flag` Condition（同字段，is 默认 true）。字段和缺失语义与观察动作一致；数值字段与布尔字段不可混用，字段名在编译时检查。每次普通查询重新读取当前基础选择，不需要先发布事件或建立 Buff 缓存。以 available 守卫后，内容可以显式选择把未选槽计为零份；原始读数仍没有零默认值。

```json
{"type":"chorus:choose",
 "if":{"type":"chorus:ability_energy_flag","slot":"chorus_d2:melee","field":"available"},
 "then":{"type":"chorus:ability_energy","slot":"chorus_d2:melee","field":"full_charges"},
 "else":{"type":"chorus:constant","value":0,"unit":"count"}}
```

用于 on_use 攻击快照时，self / 来源目标的数值与条件冻结为常量；victim 读数与资格保持符号，在每次命中时读取目标的当前选择。循环绑定目标仍不能捕获进快照。查询不支付 cost、不应用 gain_profile，也不代替宿主推进逻辑时间。

[wellspring.json](../common/src/test/resources/effects/wellspring.json) 展示多个观察的组合：先绑定三个槽，再用 available / full 守卫数值读取，capture_value 保存未充能槽数量与分配系数，最后分别 grant_ability_energy。整个分配无需新增专用 Java 动作。一次收益填满某池不会改变本次的分母；溢出、额外充能份额和未选槽政策由 JSON 明确给出，原作校准边界见 [Wellspring](d2-ruleset.md#wellspring)。

[weapon_stats.json](../common/src/test/resources/effects/weapon_stats.json) 统一声明稳定性、操控、装填属性（加值后限幅 0–100）与秒数动画倍率 Profile。Perk 片段只引用这些 ID，武器原型只提供自己的曲线；链接时载入共用定义一次。Pugilist 与 Surplus 已迁移到同一属性目录，避免组合时重复定义同名 Profile。

[surplus.json](../common/src/test/resources/effects/surplus.json) 展示查询期消费者：按 available 守卫各槽的 full_charges，再以总份数选择属性加值。独立武器 Profile 决定后续限幅与原型曲线，reload.profiles 将共用属性段、原型曲线和秒数修正串联。强化版缺少原表数值，要求显式的分档测量；完整约定与测试曲线边界见 [Surplus](d2-ruleset.md#surplus)。

### 按已付成本返还与完整充能

`refund_cost` 引用当前动作序列中已有的成本结果，fraction 的单位为 multiplier、取值范围为 0–1。返还账户直接取自成本回执，不接受另一个 target / resource，避免把别人的支付返到自身账户。如下步骤可放进已有资源来源的规则中：

```json
[
  {"action": {"type": "chorus:spend_resource", "resource": "example:energy", "payment": "cast",
    "amount": {"type": "chorus:constant", "value": 1, "unit": "charge_fraction"}}, "as": "cost"},
  {"if": {"type": "chorus:result_flag", "binding": "cost", "field": "succeeded"}, "then": [
    {"type": "chorus:heal", "amount": {"type": "chorus:constant", "value": 1, "unit": "damage"}},
    {"action": {"type": "chorus:refund_cost", "cost": "cost",
      "fraction": {"type": "chorus:constant", "value": 0.5, "unit": "multiplier"}}, "as": "refund"}
  ]}
]
```

这段示例支付一份、治疗、显式返还实际成本的 50%。治疗结果若要决定是否返还，需要绑定治疗回执并添加对应条件；引擎不会因世界动作被取消或失败自动退款。示例数值仅用于演示协议。

- 每次 requested = paid × fraction，再由剩余成本额度限制 allowed，最后由资源容量限制 credited；overflow = allowed − credited。返还结果提供 requested / allowed / credited / overflow / paid / claimed / remaining，全部为 charge_fraction，另有 changed 标志。claimed 为这笔成本累计已认领额度，包括因容量不足而溢出的部分。
- 多次引用原始 cost 或上一次 refund 都引用同一笔支付；分支退出、循环推进、未使用 `as` 的返还、世界等待都不会重置额度。例如实付 1，第一次返还 70% 时完全溢出，之后能量被消耗，再请求 70% 也最多只给 0.3。免费或支付失败时 paid = 0，任何比例返还都为 0。
- 成本记录保留在当前规则 Frame 中，完成后释放；`as` 的普通词法作用域不变，分支结果不能向外引用。当前支持同一动作序列内跨世界等待的返还；延迟体 / 投射物须先显式转交为下节的有限期凭据。任意事件 / Buff 引用组件和跨重启恢复尚未实现。不要把 payment 字符串当成可以在任意未来事件中查询的全局账本键。
- 每次显式返还发布 `chorus:resource_refunded`，即使 credited = 0 也带完整原因测量。它具有通用资源测量以及 requested / scaled / allowed / credited / overflow / paid / claimed / remaining，scaled 与 allowed 相同；payment 引用为实际支付身份。值变化时另发 resource_changed，返还不重复发 resource_granted。
- `grant_full_charge` 使用 resource、target（默认 self）、charges（默认 count = 1）。charges 必须是非负整数；增加指定份数并保留已有部分进度，例如 capacity = 2 时 0.4 + 1 → 1.4。它不经过普通 chunk 的缩放，也不把账户设置为满值。结果与 grant_resource 相同，resource_granted 的 reason 为 full_charge。是否属于免费施放仍由实际支付规则决定，不由充能动作倒改成本回执。

上述场景在 [resource_refund.json](../common/src/test/resources/effects/resource_refund.json) 中完整可执行，并有双加载器的实际治疗与资源断言。这是通用合成机制测试，不代表某个 Compendium 效果的全部触发、归因和数值已经验收。

### 跨延迟与飞行的有限期退款凭据

`retain_cost` 将当前成本的**剩余退款额度转交**到 EffectState 账本，返回类型化句柄。duration 是必须声明的有限正 second，精确到微秒；不支持永久凭据。以下片段放在已有 cost 的技能 on_use 中：

```json
[
  {"action": {"type": "chorus:retain_cost", "cost": "cast_cost",
    "duration": {"type": "chorus:constant", "value": 2, "unit": "second"}}, "as": "right"},
  {"after": {"type": "chorus:constant", "value": 0.2, "unit": "second"}, "lifetime": "detached", "do": [
    {"type": "chorus:refund_retained_cost", "cost": "right",
      "fraction": {"type": "chorus:constant", "value": 0.7, "unit": "multiplier"}}
  ]},
  {"after": {"type": "chorus:constant", "value": 0.4, "unit": "second"}, "lifetime": "detached", "do": [
    {"type": "chorus:refund_retained_cost", "cost": "right",
      "fraction": {"type": "chorus:constant", "value": 0.7, "unit": "multiplier"}},
    {"type": "chorus:close_retained_cost", "cost": "right"}
  ]}
]
```

- 原 cost 及其旧 refund 别名在本帧内已封存，不能再认领已转交的额度；再次 retain 原 cost 得到的额度为零。转交本身不修改能量，也不发布退款事实。
- 句柄可由 after / projectile 捕获；所有副本、不同接触和嵌套延迟读取同一账本。每次仍按原始 paid × fraction 申请，受已认领总额约束。上述两个 70% 请求合计最多返还实际支付的 100%；转交之前已经返还的部分也计入总额。
- `refund_retained_cost` 返回 available / changed，以及 requested / allowed / credited / overflow（charge_fraction）。活跃凭据的零收益仍发布 resource_refunded；值改变才发布 resource_changed。它固定返到原付款账户，不接受 target / resource 重定向。免费或失败支付不能创造退款，容量溢出也消耗额度。
- 有效期使用半开区间，到期先于同刻回调清除账本。`close_retained_cost` 提前关闭，返回 removed；关闭或到期后所有旧句柄返回 available=false、四个数值为 0，不发布资源事实、不重建额度，也不会因正常的迟到回调停止运行时。同 id 却内容冲突的句柄仍明确拒绝。
- 凭据期限独立于来源、技能选择和投射物存活；即使未命中、来源取消而没有回调，逻辑时钟也会清理。关闭不会取消飞行或延迟任务，只撤销后续退款资格。账本和余额先于依赖它的世界动作提交，世界结果未知时不回滚或重放。

完整合成示例 [retained_cost.json](../common/src/test/resources/effects/retained_cost.json) 使用真实技能支付、物理命中和独立延迟回调共享额度，再按实际 credited 治疗。普通 paid 数字或 payment 字符串不具备此权利。句柄可经词法捕获及检查点保留；协调模式写入签发领域，Buff 自有延迟工作可按下述显式交接协议迁移。向 Buff 引用组件保存、独立后续事件按施放查找及物理对象迁移仍未实现；同一共享宿主的原生玩家交接已接入下述完整角色协议。固定充能比例的收益继续使用 grant_resource，不能因为发生在返回阶段便自动改为按实际成本退款。

## 跨运行时资源账户协调器

`ResourceAuthority` 是纯 Java 协调边界，负责在多个规则领域之间管理一个持有者的全部资源池。原生共享宿主已通过联合角色交接和协调 archive 接入传送与保存；以下示例描述底层纯协议，具体自动接入见下文。

```java
var directory = ResourceAuthority.claim(ResourceAuthority.Directory.EMPTY,
        holder, "minecraft:overworld", program, overworldState);
var worlds = new ResourceAuthority.Worlds(directory, Map.of(
        "minecraft:overworld", overworldState, "minecraft:the_nether", netherState));
var owner = worlds.directory().owner(holder);
var plan = ResourceAuthority.transfer(worlds.directory(), owner,
        worlds.domain(owner.lease().domain()), "minecraft:the_nether", program,
        worlds.domain("minecraft:the_nether"));
worlds = ResourceAuthority.commit(worlds, plan, program);
```

Directory 保存每个 holder 的唯一 Partition：所属 domain / lease generation、revision、逻辑时间、完整 EffectProgram 和全部 ResourceState。空分区也保留归属，不能在传送后把它误认作从未注册。账户地址继续使用 holder + resource，不随维度变化。Worlds 同时核对目录与各领域投影，任何受管持有者在另一个领域拥有余额都会被拒绝；无法仅凭余额大小猜测哪一份是权威状态。

本地状态步骤完成后使用 `ResourceAuthority.publish(worlds, domain, before, after)` 发布。它核对完整旧领域状态，并一起更新该领域所有受管持有者的时间和余额；同一时钟步有多个玩家时不能只发布其中一人。不能回退时钟或悄悄删除已登记账户。新的资源池可以由已有初始化规则创建后发布，相同状态不增加分区 revision。

transfer 只生成计划，不修改输入。源分区必须已经结算并发布；源 / 目标使用完全相同的程序，不能只比较 version 字符串。目标已有任何同 holder 余额时拒绝，不覆盖、不相加、不补满。commit 校验当前目录与计划 generation，原子返回新目录及源 / 目标两个领域状态，其他领域和持有者保持原状。即使往返后余额、时钟与目的地都相同，也不能重放旧计划。

移交保留所有资源的绝对余额、容量和小数，包括可变容量及 linked 的进度 / 可用次数池；仅把账户 timeMicros 改为目标当前逻辑时间。源领域移除该分区，后续时钟不会再为它回充。目标只从接收时刻开始按自己的规则积分；两个领域时钟的差值不是额外离线恢复。移交不产生 resource_initialized / granted / spent / refunded 等游戏事实，不冒充消费、回能或施放。

`ResourceProjection.Change` 是内部的完整分区写集，核对 before、账户键、时钟和目录声明。它可通过已存在的 `EffectSession.observe(..., committed)` 归约，也进入纯检查点编码。宿主必须先通过协调器提交整个计划，再在一个无外部回调的边界发布目录及双方会话，不能只取一侧写集执行或脱离 generation 核对重放。

延迟奖励通过 `ResourceAuthority.grant(worlds, stableAccountKey, requested, scaled)` 解析当前归属，返回实际 credited / overflow 和接收 lease。scaled 仍应由宿主在正确的接收者上下文中计算，这个接口不自动运行 D2 的 CES / 属性 Profile。游戏事实及原始来源由宿主根据返回结果投递到实际接收领域。

`ResourceAuthority.refund(worlds, issuerDomain, retainedHandle, fraction)` 同时修改签发领域的退款账本与当前接收领域的余额。issuerDomain 是原句柄的签发位置，不能改成玩家当前所在位置；两个领域的本地句柄 ID 不应被当作全局唯一 ID。额度沿用原始付款 / 稳定账户，所有句柄副本共用签发账本；溢出也消耗已认领额度，重复退款不会越过原付款金额。过期 / 已关闭的句柄不入账。过期检查采用签发领域的时间，目标时钟更大或更小都不改变期限。`RetainedCosts.authorize` 与 `Resources.claimRefund / applyRefund` 将这两个纯步骤分开，原有单领域 refund 复用它们，保持原返回值和事实语义。

`ResourceAuthorityCodec` 的独立 format 1 保存 Directory，generation / revision / 时间使用十进制字符串，程序与账户复用已注册的存储编码。重复 holder / 账户 / generation、未知字段、非法数字、分区键或时钟错配拒绝。它不单独证明所属领域的余额和退款账本已经落盘；恢复后仍须以 Worlds 核对所有领域。

### 资源目录与领域的联合检查点

`host.checkpoint()` 捕获 `ResourceSessionAuthority.Checkpoint(directory, domains)`，包含该协调器绑定的全部领域，包括没有受管账户的领域。宿主先完成自己的时间推进并结算 inbox；捕获本身不执行规则、不推进时钟、不排空接收事实。运行中、未结算或结果未知的会话拒绝保存。捕获前后核对每个完整时间线的身份，全部检查通过后才发布本次目录 revision。

检查点将当前已结算状态中的账户表与归属目录一起固定：每个分区必须对应存在的领域、完整 EffectProgram、相同逻辑时间与全部余额 / 容量；其他领域不能保存该受管持有者的副本。源领域的延迟工作、退款账本和事件 / 帧序号仍保存在源领域，未登记的本地账户不因保存自动被 claim。不同领域可保留独立时钟，本地 / 协调编译模式按各会话原样保存。

`CheckpointArchive.coordinated(entries, checkpoint)` 将原生维度名、目录 ID、游戏 tick 和设备 / 角色 / 技能所有者等宿主元数据附到捕获结果。entries 必须恰好包含捕获的全部领域与相同 EffectSession.Checkpoint，不能重新读取另一时刻的效果状态或遗漏空领域。联合 archive 使用 format 5，必填 `resource_directory` 和 `participant_placement`，每个领域的 effects 仍只保存一份；两种目录独立编码均为 format 1。旧 format 4 保留资源目录，位置目录显式迁移为空。既有本地 archive format 1–3 继续可读，本地保存仍写 format 3；协调会话不能装进缺少目录的旧格式，空目录也需要显式保存。

恢复时使用 `ResourceSessionAuthority.restore(checkpoint, policies)`，为每个领域明确提供 RestoreBinding：完全匹配的编译目录 / 模式、纯时钟政策、解释器预算和真实世界执行器。领域集合必须完全一致。它创建新的 EffectSession，再统一核对并绑定资源目录，返回 authority 与 bindings；不复用旧协调会话、不执行或重放世界动作、不补能或按墙钟计算离线收益。物理世界、玩家文件和投射物的恢复由宿主负责，不能仅凭这份数据声称已完成世界对账。

`WorldCheckpointStore.beginCoordinated()` 显式接收联合 archive；现有 prepare → 各项真实 flush 确认 → commit 协议保持不变。默认 `begin()` 仅供本地宿主使用，发现联合 archive 时在改写文件或返回可恢复状态前拒绝；原文件保留。成功开启恢复仍会先把旧 COMMITTED 标记改为 RUNNING，之后崩溃不能再次自动重放该检查点。归属、余额或时钟不匹配的目录 / 账户拼接会被联合校验拒绝。未完成保存也不会因目录已写入而变成可恢复状态。

核心会话与命名目录原生宿主已完成联合捕获 / 恢复和存储接入；installCoordinated 明确选择协调模式，正常停服 / 启动自动保存和恢复联合 archive。旧本地安装入口及格式保持。已索引玩家自动登记与同一共享宿主的原生玩家传送已接通，详见[协调保存与启动恢复](#命名目录的协调保存与启动恢复)。

### 把协调结果发布到运行会话

`ResourceSessionAuthority` 将纯协调器绑定到实际 `EffectSession`，让归属目录与双方会话在同一线程中共同更新。每个 domain 只能绑定一个独立会话，编译程序必须与会话固定的完整程序相同。构造时严格核对传入 Directory 与全部会话；恢复时不能通过重新发布余额来掩盖存档错配。

```java
var host = new ResourceSessionAuthority(ResourceAuthority.Directory.EMPTY, Map.of(
        "minecraft:overworld", new ResourceSessionAuthority.Binding(program, overworldSession),
        "minecraft:the_nether", new ResourceSessionAuthority.Binding(program, netherSession)));
// 首次登记；恢复时改用保存的 Directory，已经登记的持有者不再 claim。
host.claim(holder, "minecraft:overworld");
var handoff = host.prepareTransfer(holder, "minecraft:the_nether");
host.commit(handoff);
```

宿主先用各自正常输入推进会话到所需时间，再调用 snapshot / prepareTransfer；协调器不会推进时间或执行未完成规则。snapshot 将所有受管持有者的已提交账户进度一起发布，检测旧维度重新创建的重复账户；它允许存在尚未处理的接收事实，不等于可保存的检查点。prepareTransfer 要求这些事实先完成，再预检和生成不透明计划；commit 再核对目录身份、参与领域集合及所有参与会话的完整时间线身份。即使中间只处理了同一时刻的空事件、余额完全相同，旧计划也会失效。另一个协调器的计划不能提交。

底层 `EffectSession.prepareDomain(expectedTimeline, domain)` 预检同一已结算时刻的新状态；`commitDomains(updates)` 先核对所有参与会话，再执行不含回调的引用赋值。重复会话、过期快照、其他线程、运行中或失败 / 待回执的会话在任何写入之前拒绝。这个内部接口面向已经验证状态语义的可信宿主，不替代正常技能、装备或世界动作。它保留时钟、计时器、事件 / 帧编号与去重回执，不通过 observe 制造额外边界，也不采用 restore 清除当前回执。后续正常事件才会运行规则，并能同时看到双方已经更新的状态。

`host.grant(...)` 和 `host.refund(...)` 在返回结果前完成实际会话入账；后者同时发布签发会话的额度认领。它们要求所有会话没有正在执行或待回执的帧，已有接收事实保持排队，不能从正在执行的世界回调重入。这两个直接宿主方法没有 origin 参数，不自动生成游戏事实；进行中的请求使用下一节 execute。它们不自动拦截 DSL 动作或计算数值 Profile。

### 武器阶段的跨领域时钟

换弹、切枪、瞄准、蓄力计划以及最近一次射击保留最初接受时的原始时间，另以 `clockOffset` 转换到当前领域时钟。`relocated(sourceNow, targetNow)` 保持已经经过和剩余的时长，不重新付款或规划阶段；可多次往返。运行逻辑使用 `startTime / dueTime`、蓄力的 `fullTime / dueAt`、射击的 `firedTime / readyTime`，原始 `startedAt / fullAt / firedAt / readyAt` 继续表示历史接受事实。换弹 completionId、原武器、来源、已支付弹药回执和已接受的数值计算保持不变。

例如在源领域 10 秒开始的 200 毫秒换弹，经过 50 毫秒后进入时钟为 0 的目标领域，当前 `startTime=-50_000`、`dueTime=150_000`；原始开始时间仍是 `10_000_000`。已完成瞄准和就绪蓄力的本地开始、完成坐标都可能为负；这不代表倒退领域时钟。观察时间仍非负，期限和差值须可用 long 精确表示，有限阶段不能成为永久哨兵。蓄力恢复同时平移所保存的射击冷却，后续新阶段使用新领域时间。

网络 Aim / Charge 投影采用当前领域的坐标，允许负开始时间，并在构造时检查时间顺序与差值溢出；线格式保持。它只投影服务器已经接受的状态，不据此授予跨维度长按许可或改写实体生命观察。原生玩家传送仍须单独交接真实容器、当前实体绑定及临时输入许可。

存储身份分别升级为 `weapon_reload/plan_v2`、`weapon_handling/plan_v2`、`weapon_aim/plan_v2`、`weapon_fire/shot_v2`、`weapon_charge/plan_v4`。五个精确旧字段布局由 Legacy 记录迁移为 offset 0；更早的 charge v1 / v2 迁移继续有效。既有 725 个形状不变，仅新增五个形状，顶层检查点格式保持。

### 角色与 Buff 的独立归属

`ParticipantPlacement.Directory` 独立保存 `characters` 和 `buffs` 两张 holder → Lease 表；每个 Lease 包含 domain 和 generation。CHARACTER 覆盖角色的来源、装备、基础技能、角色配置和武器阶段，BUFFS 覆盖该持有者的全部 Buff。资源账户仍以 ResourceAuthority.Directory 为准；伤害组、退款和统计仍以各自的 issuer 为准。资源在 B、角色在 A、Buff 在 C 是合法状态，不能通过其中一张表推断另两张。

`host.enrollParticipants(characters, buffs)` 显式登记已由宿主确认的分区；需要与资源一起登记时使用 `enroll(resources, characters, buffs)`。空分区也有 lease。同域重复登记保留原对象和代次；已登记到另一领域的分区不能通过重复观察夺取。整批先校验领域存在及全部会话中受管分区没有外域副本，再共同发布。未登记的持有者保持本地语义，不扫描状态猜测归属。`host.participants()` 返回不可变目录，读取本身不建立归属。

迁移严格核对旧 lease；即使 A→B→A 且状态相同，新 generation 仍使旧计划失效。角色和 Buff 使用同一递增序列但各有独立 lease。协调器捕获、候选发布及恢复都验证目录与状态，准备计划还固定全部会话时间线和领域表。只有目录修改而状态未变，也会使旧计划失效。

资源-only transfer 保持两张表不变。`prepareTransferWithBuffs` 只移动资源与指定 Buff 分区，不改变角色归属；sourceDomain 必须匹配 Buff 的显式归属。`prepareCharacterTransfer` 从角色源域移动角色，并从每个 Buff 持有者各自的归属域收集玩家和可信携带装备实例的 Buff。未登记的携带实例由显式源域采纳；有外域副本则拒绝。留在世界中的装备 Buff 不随玩家移动。已经在目的地的 Buff 分区保持原代次、实例和期限，不重新授予；`CharacterHandoff.buffTransfers()` 提供每个携带持有者的计划，`buffs()` 继续表示玩家本身。

联合 Checkpoint 同时固定资源目录、位置目录及全部会话。宿主 archive format 5 增加 `participant_placement`，其独立 codec format 1 用规范十进制字符串保存代次，固定排序并拒绝重复身份 / 代次和额外字段；联合 Checkpoint / Archive 进一步拒绝缺少领域和错置状态。旧 format 4 明确迁移为空位置目录；旧两参核心 / archive 构造器也表达未登记，不从已有来源或 Buff 推断位置。恢复不重放登记、附着或消耗。744 个 DATA 记录形状保持，宿主目录采用独立版本，不将不可达的宿主记录注册进 DATA。

这张目录提供逻辑分区权限，还不能代替原生实体的生命、物理位置与连接绑定。普通本地写入和显式 `combatParticipants()` 查询尚未自动改用这些独立 facet；原生跨域战斗必须再固定实际实体与相应 lease，并共同发布各域消费，不能把本节当作已经接通完整跨域伤害。

### 资源与 Buff 自有工作的共同交接

```java
var handoff = host.prepareTransferWithBuffs(holder, sourceDomain, destinationDomain);
// 宿主在自己的完整迁移协议中提交；准备与提交之间的任何会话变化都会使计划失效。
host.commit(handoff);
```

该入口一次发布资源归属、源 / 目标 Buff 和 Buff 自有计时器。sourceDomain 是 Buff 所在领域，允许资源当前属于第三个领域；资源已经属于目标时保留其 lease。所有参与会话须结算，Buff 源 / 目标及资源所属领域须使用相同的完整目录；目标已有同一个 Buff key、目录不兼容或计划过期，整批拒绝。不会先移动能量再留下旧 Buff，也不触发 gained / ended / 初始化反应。它是完整玩家迁移的组成部分，不执行原生传送，不移动装备、角色、技能选择、弹药、武器阶段、附着或物理实体。

底层 `BuffTransfer.prepare(holder, sourceProgram, sourceState, targetProgram, targetState[, sourceDomain])` 生成不可变 Plan。departure / arrival 是两边候选状态，identities / timerIds 提供旧新身份映射；requireCurrent 核对原始状态对象，最终由宿主联合发布，不能只提交一侧。目标按自己的 nextGeneration 重新分配 Buff 代次，保持 key、来源、层 ID、分数层数、组件、tier、原始 / 最长持续时间。每层有限期限按源时钟的剩余时间接到目标时钟；暂停层按 pausedAt 计算，目标仍暂停，永久层保持永久。已到期而未结算、数值溢出及有限期限变成永久哨兵均拒绝。

只有 lifetime 绑定这些 Buff 的计时器迁移；周期次数 / 间隔和暂停余量保持。命名计时器改用新 Buff 身份，after 工作分配目标内无冲突的 ID。来源自有工作、detached 延迟及其他持有者保持在源领域。纯交接不执行任何计时器，恢复也不重放已发生的动作。

延迟动作的历史事件、原 Buff 观察和词法结果保留。`BuffRules.Scope.binding` 单独指向迁移后的有效实例，实时 Buff 查询使用它；own_buff / own_timer 仍能识别原触发事实。`Pending.relocatedAt` 记录目标领域的因果时间边界，用于未来延迟的有效性检查，不把历史 cause 的时间改写为目标时钟。固定位置、实体生命引用及攻击快照也不因 Buff 迁移改写。

协调模式下 `retain_cost` 生成带 issuer 的句柄；refund_retained_cost 和 close_retained_cost 都路由到签发账本，退款余额仍进入账户当前归属。关闭不产生资源游戏事实，重复或过期关闭不会恢复额度；未知世界回执保留已经退款的余额、原账本认领量和 pending，不执行剩余关闭，也不重试治疗。签发领域的时钟继续决定退款到期。

旧无 issuer 的句柄在交接时由显式 sourceDomain 补齐，原账本及已认领额度不复制、不重建；没有 sourceDomain 的纯交接遇到这种句柄会拒绝。携带保留退款句柄的工作要求目标使用协调资源路由，本地模式无法读取原签发账本，预检会拒绝该目标。已限定 issuer 不随下一次迁移改写，旧别名访问不会清除账本已知的 issuer。Scope / Pending / Handle 分别使用新的 scope_v2 / pending_v2 / handle_v2 存储身份；三个旧字段布局经显式迁移读取，原有顶层检查点格式保持。

直接捕获的 `DamageTallies.Handle` 已能随 Buff 或来源自有工作迁移：协调模式在创建时记录 issuer，旧未限定句柄在明确的交接边界补齐；后续操作回原签发账本，见[伤害统计的签发域路由](#伤害统计的签发域路由)。`DamageGroups.Handle`、`ShotGroups.Handle`、`EntityAttachments.Handle` 也已有可选 issuer 及旧布局迁移，但其伤害、弹丸和世界观察入口尚未全部接入跨域协议，`Pending.relocated` 仍拒绝携带这三类直接活动句柄。不能把同名句柄交给目标领域，也不能静默删除绑定；拒绝不计作完整支持。已完成统计、附着查询结果和 DamageBatch 等只读历史数据继续允许捕获，保留退款句柄走上述 issuer 路由。

### 角色状态的联合交接

```java
// 从可信的实际装备 / 携带容器枚举，包含未装备但携带的武器实例。
var handoff = host.prepareCharacterTransfer(holder, sourceDomain, destinationDomain, carriedGear);
// 原生宿主核验传送与容器交接后，在同一个完整协议内发布。
host.commit(handoff);
```

准备阶段组合资源 / Buff 交接和纯 `CharacterTransfer.prepare`，最终以同一旧快照一次发布所有相关会话。除资源和 Buff 外，还移动该持有者的装备 Loadout、基础技能选择、角色配置、常驻来源、所列武器的弹药 / 最近射击，以及换弹、切枪、瞄准、蓄力计划。它移动已提交状态，不重放 source_attached、初始化弹药、付款或配置反应。底层 Plan 提供 departure / arrival、timerIds 和精确旧快照核验；调用者不能只应用其中一边。

`carriedGear` 是宿主提供的真实携带实例集合，必须包含全部已装备实例；未列出的旧弹药账户留在源领域，不按历史 Shot.holder 猜测物品归属。实例若同时由另一持有者或目标领域装备，或目标已经存在待移入的配置、来源、弹药、射击、阶段、计时器身份，整批拒绝。来源 / 目标须使用相同固定目录并处于已结算边界；过期计划、未完成的换弹插入或收枪提交、已到期而未结算的计时器和时间溢出均拒绝。

武器阶段按[当前领域时钟](#武器阶段的跨领域时钟)保留进度。来源自有的命名计时器和 after 工作随精确匹配的常驻来源移动，保持剩余时间、周期、次数和原历史事件；after 在目标分配无冲突 ID。已完成的 AIMED / READY 状态无需计时器。detached 工作、其他持有者、已发射攻击的分组 / 统计账本、附着实例和保留退款账本留在原领域。两条迁移入口共用上述捕获句柄预检，prepare 失败不发布已经算出的资源 / Buff 候选变化。

显式核心入口已有实际 ServerPlayer 传送、原生 PlayerEquipment / PlayerAbilities / PlayerActor 容器和正式治疗执行器的验收。共享原生宿主现在通过以下自动协议接入；已经发射的投射物、场地实体和附着物继续留在原世界。

### 原生玩家传送与长按输入交接

当源世界和目标世界的 `MinecraftEffectRuntime` 属于同一个已安装的 `MinecraftResourceRuntime` 时，`ServerPlayer.teleport` 自动准备并提交上述角色交接。准备先结算所有领域和该连接已到期的瞄准 / 蓄力 / 技能长按权限，再读取实际独立装备槽、原版背包及菜单光标中的装备实例；每个实例必须是唯一的单件物品。当前玩家 UUID、生命、连接、来源投影、容器 revision 与物品组件须一致；目录缺失造成 inactive 或目标身份冲突时，在物理移动前拒绝。

准备完成后整个宿主暂时禁止规则 / 资源重入和保存。原版完成目标世界实体索引后、任意 post-teleport 回调前，共同发布核心状态，转移装备 / 角色 / 技能配置的物理核对责任，并更新同一生命的当前实体绑定与临时输入归属。随后释放宿主边界，发布输入确认并结算真实离开事实；世界内的旧攻击与附着仍能收到离开事件。回调可以看到完整目标状态，也可以开始另一笔完整传送。已验证回调内 A→B→C；回调内立即 A→B→A 会触发原版观战者尾部的自递归，不能视为受支持场景。两次依次返回的 A→B→A 已单独验证。正常同维度传送和已移除玩家的原版 null 返回不领取新资源 lease。

原生操作抛异常或无法确认结果时，保留已经发生的物理移除 / 插入和已提交的核心写入，停止整个共享宿主，不回滚、不自动重试、不允许成功检查点。即使原生回调捕获并吞掉边界内重入的异常，准备也已记录违规，不能继续发布候选状态。只有确认没有物理变化的 null 返回可撤销准备，保留原手势。

瞄准、蓄力和技能长按以同一连接、玩家、生命和原 gesture 交接；不会重新 PRESS、支付或刷新已消耗的租期。Charge Plan 的历史 `holderReference` 保持原维度，运行时另持有当前 live binding，释放与自动满蓄力均核对当前身份；释放沿用已接受的历史 reference，自动满蓄力还要求当前有效长按许可。网络采用 `weapon_aim_input_v2`、`weapon_charge_input_v2`、`ability_input_v2`：输入带 `transferRevision`，确认包带前后 revision、维度和 gesture。确认必须组成连续链，单凭相同 UUID 或回到原维度不能恢复权限；往返后迟到的旧输入不取消 / 续租新意图。两端须使用同版本协议，旧 wire 不按新布局解码。

客户端只在同一物理按住且收到显式确认后承接新 LocalPlayer；载入画面期间暂停心跳，保留实际键鼠观测。松开、失焦、死亡、更换连接或不相干界面结束操作，不将释放误当作新施放。已结束的手势短暂保留仅可取消的记录：若确认包迟到，可用确认后的 epoch 补发 CANCEL，不能恢复长按或补发 RELEASE；更换连接立即清除。三类长按均以 10 tick 心跳续租，40 tick 没有有效心跳就由服务器结束权限；迁移只换算剩余租期。技能 HOLD 仅属于网络协议，不改变可存档的核心 AbilityInput.Edge，也不会重新 PRESS 或支付。客户端等待阶段最多保留 40 次未消费的传送观察 / 确认，超过后仅结束该长按权限，不终止原生传送或规则反馈链。40 tick 输入租期及客户端等待界限属于 Chorus 传输政策，不是 D2 数值，也不限制规则反馈循环。

PlayerList.respawn 使用单独的替换协议：原版选定目标维度后、移除旧对象前结算并冻结共享宿主；restoreFrom 后按新对象实际保留的背包准备核心迁移，在 addRespawnedPlayer 确认索引后联合发布角色 / Buff / 资源与核对责任。普通死亡更换生命，keep-all 返回保留生命，但两者都退役旧对象的长按输入；新对象不能释放旧手势。原版调用者在返回后再绑定 connection.player，协议不会将这一正常过渡当作错误。若物理替换失败，继续保留已发生写入并停止共享宿主，不生成成功检查点。

当前范围是同一共享宿主内的原生玩家传送和跨域替换。已登记角色前往未安装目标或另一个宿主时，普通 teleport 在物理移除前明确拒绝，不能通过离开再返回绕过归属协议。跨服务器、非玩家及物理投射物 / 场地实体重启仍需专门协议；直接捕获且尚无签发域路由的活动句柄仍在准备阶段拒绝。服务端世界测试和客户端纯状态机测试不等于真实图形客户端的键鼠 / 载入画面验收。

### 后续登记新的资源领域

维度或其他规则领域可以晚于协调器创建。宿主在原协调器上调用 `host.enlist(Map.of(domain, new Binding(program, session)))`，也可以一次登记多个领域；无需重建协调器或重新绑定旧会话的连续时钟。空 additions 是无副作用操作。已有 domain 不可覆盖，即使传入同一会话也拒绝；同一会话不能在这一协调器内有两个领域名，新增协调会话必须尚未绑定时钟捕获。

非空登记要求旧 / 新全部会话健康且已结算，不能有待处理 inbox、正在执行的世界回调或结果未知的操作。它先固定全部时间线身份，核对每个会话完整目录与编译模式，再以当前已结算余额准备目录更新和完整 Worlds。新增领域不得带有已受管持有者的资源副本。不同目录的独立领域可以共存，但跨目录移交继续要求完整程序一致。

所有可能执行时钟校验政策的预检结束后，再统一检查新增时钟均未绑定；随后只绑定新会话并发布不可变领域表及目录，不运行规则或世界动作。失败不发布任何新增领域，也不消耗前面已通过检查的候选会话的绑定资格。校验政策若改变了任一旧 / 新会话的时间线，登记拒绝；已经发生的外部修改不回滚。整个操作不推进时间、不处理 inbox、不自动 claim 持有者，也不补能量。

成功登记使此前的 Handoff 失效，即使目录及原会话完全没有变化；宿主必须在新领域集合上重新 prepareTransfer。已有 ResourceReadView 仍是原来的不可变快照。checkpoint 及后续 restore 包含已登记的所有领域，不新增存储字段；`Restored.bindings()` 是恢复返回时的固定映射，之后 enlist 的会话由调用方保管，不会反向改变它。

本接口只增加领域，不移除或替换会话。没有受管资源的旧领域仍可能保存延迟工作和退款签发账本，不能据此删除。命名目录协调组已接通原生安装、玩家登记和联合加载保存；旧安装入口保持本地模式；同一共享宿主的原生玩家传送已接入，物理效果实体与跨独立宿主交接仍待完成。原生投影测试使用显式协调器登记第二领域后，再验证真实实体上的效果。

### 持有者的幂等登记

`ResourceSessionAuthority.enroll(Map<holder, domain>)` 接受一批已由宿主确认的持有者。没有归属时，从指定领域的当前账户创建分区；没有账户也保留空分区。已有归属时，仅核对观察领域的完整规则目录兼容，返回原归属，不移动账户、改变 lease 或用初始值覆盖余额。`claim` 的重复登记错误语义保持。

整批候选先核对领域、目录及跨领域账户副本，再一次发布目录。任一条目不合法，不发布其他新持有者。参与会话必须健康且非执行中，允许保留已提交的接收 inbox；登记不消费事实、不推进时间、不执行动作，也不绑定新时钟。已提交的账户进度可按既有 capture 协议发布。新增归属使旧 Handoff 失效；状态未变的重复 / 空登记保留原目录身份。检查点沿用原资源目录格式。

Minecraft 的自动调用只接受本维度实际索引的 ServerPlayer 对象，不凭 UUID 字符串、未索引的 NBT 替代对象或来源声称登记玩家。 资源与角色 / Buff 首次登记在同一边界发布：角色归属必须与当前物理域一致，已登记的远端 Buff 归属保持，不按资源账户推断。恢复的角色 / 技能配置先通过一致性校验；分阶段启动未完成及保存已冻结时不允许登记。其他实体和虚拟持有者仍由可信宿主显式 claim / enroll。完整传送协议还需交接技能、角色、装备、Buff、弹药与在途工作；发现玩家出现在另一维度不会自动夺取原有资源归属。

### 执行中的资源请求与回执

`ResourceRequests.Read / Grant / Refund / Close` 是普通 `RuleEngine.WorldCommand`，可由 Java 动作返回 Await。Read 按 holder + resource 查询当前账户与 lease；Grant 携带已解析的 requested / scaled 和原始 origin；Refund 另外携带原签发领域与 RetainedCosts.Handle。`ResourceSessionAuthority.execute(callerDomain, worldRequest)` 只能在该 caller 的当前世界回调中使用，返回与请求绑定的 ReadResult / Granted / Refunded / Closed。它不会按当前技能、当前维度或装备猜测原付款来源。

execute 允许当前调用会话暂停在对应的 pending operation，其他参与会话必须健康、没有正在执行或待回执的帧，可以保留已排队的接收事实。它先发布已提交的账户进度，解析当前接收者，再共同提交调用会话的结果、签发会话的退款账本、接收会话的余额和对应事实。三者可以位于不同领域，也可以重合。同一回调内重复 execute 返回已经提交的同一结果；错误 caller、相同操作编号但不同命令、回调结束后再次调用均拒绝。暂停中的调用方不能借此创建另一个嵌套外部输入。

EffectSession 为每次实际世界回调创建不透明 WorldOperation。可信协调器通过 `prepareOperationDomain` 把已经确定的结果与领域写入加入同一个 `commitDomains` 批次；原 pending 操作、帧、事实、请求中的未来时间和身份编号保持。协调器返回后，会话仍通过原有 Completed 回执协议继续执行。回调可以附带普通 WorldReceipt 的观测事实，但其 value 必须与已提交结果一致；不能用另一份结果恢复规则。入账后回调抛错或返回不同结果时，保留全部已提交状态，待回执操作阻止重启该边界与检查点保存，不自动回滚、退款或重放。后续纯规则失败同样保留已经收到的实际回执。

Receipt 包含实际接收 lease、余额和 credited / overflow，退款还保留实际 claimed / remaining；过期或关闭的句柄返回空退款。目标时钟不替代签发时钟。`ResourceRequests.facts(receipt)` 只生成对应事实数据，Read、Close 和不可用退款不生成事实。execute 已将事实与入账一起提交到 receipt 指定的接收会话，不应再次手动 observe 同一份事实。这个静态工厂本身仍只返回数据。Grant 的数值缩放仍由调用方先解析。

新增命令、结果与 lease 使用显式存储身份，可被已有检查点数据编码；WorldOperation 能力和未完成操作不能据此保存或恢复。当前没有改变普通 DSL 的 initialize / grant / spend / resize、资源表达式或技能支付语义，尚未自动选择请求路由。原生接入必须一并处理这些路径及联合保存，不能仅在传送时移动余额后直接启用。

### 按账户归属执行的资源变更

`ResourceMutations` 扩展同一个 ResourceRequests.Request / Receipt 协议，仍由 `host.execute(callerDomain, worldRequest)` 执行。它不把账户临时复制进调用领域。所有数值输入是已解析的不可变数据；初始化、定义约束、当前余额与容量在实际账户归属领域核对，账户时间使用接收领域当前逻辑时间。

| 请求 | 实际行为与回执 |
| --- | --- |
| Initialize(account, origin) | 从归属分区的固定程序取得定义，只初始化不存在的账户；Initialized 保留 definition / before / account / created。空分区迁移后仍从新归属初始化，已有余额和动态容量不会重置。 |
| Spend(account, amount, origin) | Spent 返回原账户、实际 SpendResult 和付款身份；不足额不扣款，零费用可成功，两者不生成扣费事实。付款身份包含 caller domain 与 frame / pc / invocation，跨调用领域不碰撞。 |
| FullCharges(account, count, origin) | 整数份数直接入账，不应用回能缩放；FullGranted 保留 actual credited / overflow，并使用 full_charge 原因。 |
| Resize(account, capacity, origin) | 固定容量定义拒绝调整；可变账户保留能量单位并丢弃超出新上限的部分。Resized 保留 discarded；无变化不生成事实，纯扩容不生成余额变化事实。 |
| CompleteRecharge(progress, uses, amount?, origin) | 两账户必须属于同一持有者，progress 定义固定为一单位。完整周期同时重置进度并给予显式份数；省略 amount 时补满 uses。不完整周期没有写入收益或事实。 |
| GrantCyclic(progress, uses, requested, scaled, cycleYield, origin) | 用已解析的单次能量包与周期产量，一次计算全部完整周期、余量和溢出；不逐次循环。进度与可用份数共同提交。这里是账户原语，不自动执行 GrantAbility 的选择、满额资格或数值 Profile。 |
| RefundPayment(payment, fraction, origin) | 只接受当前调用帧 retainedResults 中的实际 CostResult，读取该付款的最新认领额度。PaymentRefunded 同时保留认领前记录与实际 RefundResult；仅知道付款字符串、前一个事件的付款或另一帧的付款不能授权退款。 |

Spent 与 PaymentRefunded 实现 CostResult。Completed 先保留实际世界回执中的 RetainedResult，再调用动作的 complete；其上下文已经能读取该实际额度。完成处理器返回 Empty 不会丢掉付款记录。CostResult 合并时核对付款身份、账户和实付量，保留较大的已认领额度；返回旧付款快照也不能重新开放已经退款或封存的额度。局部绑定离开作用域不重置额度，退款溢出也消耗认领额度。已有 RetainCost 可把剩余额度转入签发领域的 RetainedCosts，封存后的原帧直接退款只能得到零额度；跨帧或延迟退款继续用带签发领域的 ResourceRequests.Refund。

上述命令沿用同一 WorldOperation 的重复请求去重、原子余额 / 事实投递与未知结果保护。双账户操作在任何状态发布前完成全部定义和结果验证；第二账户不存在或不合法时，不能只消耗 progress。LinkedRecharge / CyclicRecharge 的事实构造与现有本地 DSL 共用 ResourceFacts，保持原事实次序与数值字段。新增十四个存储身份，原有字段形状保持；这不代表执行中帧或 inbox 已能独立保存。

普通 DSL 的条件、Value.Resource、能量 Profile、技能替换和支付也读取资源，因此不能仅替换上述写入动作就启用原生迁移。下文的不可变视图与显式编译模式已连接规则条件、资源动作以及技能选择 / 施放支付；其余动作表达式、世界查询与原生宿主也已使用相应入口；同一共享宿主通过完整角色协议启用原生玩家迁移。

### 资源查询的不可变视图

`ResourceReadView` 引用一次 ResourceAuthority.Worlds 快照、查询领域与固定程序，不持有会话、可变目录或实时查询回调。`host.readView(domain)` 在健康、没有执行中帧的协调边界取得视图；世界回调内必须使用 `host.readView(caller, worldRequest)`，核对 caller 的当前 WorldOperation。错误会话、不同请求或已结束回调不能使用该能力。读取不发出资源事实，也不推进任何领域时间。视图只反映已提交状态；接收方若还有 inbox，读取不会替它执行反应，也不能把该快照当作已经结算的检查点。

`program.resourceEvaluation(view, context, resultShapes)` 把视图显式交给普通 Value / Condition。Value.Resource 的 value / capacity / missing 读取受管持有者的实际归属账户，保留该账户原来的时间；不把账户复制或重标记到查询领域。未受管的本地账户仍使用查询领域本地状态。查询中的基础技能选择、SelectedAbilityTag / Resource 条件、AbilityEnergy 与 ObserveAbility 读取账户归属领域的基础技能配置，再一次观察可用账户和独立回充账户；不沿用旧领域留下的选择，也不把施放期替换当成基础选择。

可通过 calculate / calculatePipeline / profileValue / attribute / ammoCapacity 的 resourceView 参数传递同一快照。嵌套 Profile、属性、弹匣容量以及本地回能的倍率 / 周期产量查询继续携带它，保留原数值依赖路径。它只改变资源和基础选择的读取；Buff、装备、来源、活动模式等仍取显式传入的查询领域状态，不自动跨领域合并。需要在接收者上下文计算时，调用者显式选用 `view.owner(holder)`，不能把余额所在领域的转移视为其他状态也已迁移。

旧视图始终是旧观察：后续回能、改选或再次迁移不修改它，重新查询才得到新视图。Evaluation 要求视图与传入的领域状态是同一个不可变快照，并核对完整程序；不能仅因版本名相同就混用定义。读取受管账户时若定义不兼容或账户缺失，拒绝查询，不回退到旧领域的余额或零值。

普通本地资源写入使用 localResource / requireLocalResource。带视图的 initialize、grant、spend、refund、full charge、resize、能量回充及双账户转换遇到远端分区时，要求改走按地址执行的请求；只读观察不授权在旧领域重建账户。本地账户的纯转换仍可读取视图中的外部资源条件。动作的提案需要查询新建弹匣等非资源数据时，`withLocalState` 只允许同一时刻、完整资源表完全不变的本地提案；它返回新视图，其他领域保持原快照，不发布任何会话状态。

当前视图是可信 Java 宿主的显式查询输入，不写入 EffectState 或检查点，也不把整个跨领域世界图捕获进延迟动作。查询得到的普通 Measure、AbilityObservation、CalculationProfile.Result 等仍可按原数据协议保存。下节的协调编译模式为规则条件与资源动作取得视图；默认本地编译保持原行为。施放期技能替换 / 支付与原生共享宿主的输入 / tick / 联合保存已接到下述协调接口。

### 普通规则的资源请求路由

可信宿主对已链接目录调用 `compiled.withResourceRouting()`，用返回的 CompiledEffects 构造会话，并将 ResourceRequests 交给 `host.execute(caller, request)`。编译模式不改写 EffectProgram 中的内容定义。ResourceSessionAuthority 的 Binding 必须与实际会话使用同一完整目录和路由模式；写入前须 claim 每个受管持有者，包括尚无账户的空分区。受管账户即使仍在调用领域，也走同一协议。未 claim 的地址不能通过路由写入时偷偷建立归属。

编译器为普通规则的入口条件、if 分支和下列动作生成固定指令位置的 ResourceRoutes.Request。请求只携带 site 字符串，不保存 Evaluation、闭包、会话或完整世界图。execute 用实际 pending 帧的 definition / pc / 操作身份验证该 site，恢复原词法绑定和延迟作用域，然后基于同一次协调快照解析输入、执行资源原语并提交结果与事实。入口仍先检查 proc 与来源存活 / 捕获资格；分支沿用短路和跳转语义。每个动作使用执行到该位置时的已提交余额，接收方尚未执行的 inbox 反应仍须由宿主继续调度。

| 普通动作 | 协调行为 |
| --- | --- |
| initialize_resource、grant_resource、spend_resource、grant_full_charge、resize_resource | 解析实际归属后调用对应原语，返回原 Initialized / ResourceResult / SpendResult / ResizeResult。初始化不在旧领域重建账户。 |
| refund_cost | 从当前帧已有付款及最新认领额度退款；循环每次付款使用独立操作身份，循环结束或旧绑定不能重置额度。 |
| retain_cost / refund_retained_cost / close_retained_cost | 保留在调用领域签发；退款与关闭按 handle.issuer 访问原账本，退款余额进入账户当前归属。来源 / Buff 自有延迟工作可由角色迁移协议移动，账本继续留在原域。 |
| begin_damage_tally / record_damage / read_damage_tally / close_damage_tally | 创建时记录 issuer；之后按句柄回原域统计、读取或关闭，按原域时钟到期，与调用域确定回执共同提交。详见[签发域路由](#伤害统计的签发域路由)。 |
| complete_recharge | 在归属领域共同变更进度与可用份数；周期未完成时不求值可选产量表达式。 |
| grant_energy、grant_ability_energy | 请求量、参考系数和输入测量在调用作用域求值；接收系数、gain Profile 与周期 yield Profile 显式使用归属领域的状态和视图。基础选择与可用 / 回充账户取接收者当前配置。双账户转换共同提交；没有选择 / 资源或独立可用账户已满时保留原 no-op 结果及求值顺序。 |
| resize_ability_resource、observe_ability_energy | 使用接收者当前基础技能选择。未选择、无资源或固定容量的调整不先计算容量表达式。 |
| capture_value | 以本次协调视图求值，返回普通 CapturedValue，可供之后的其他动作使用。 |

ResourceRoutes.Result / Cost 封装实际原语回执与原 DSL 结果，Completed 解包后原有结果字段保持可用。Cost 本身保留真实 CostResult，退款额度在 complete 前已进入帧账本。重复执行同一回调得到同一封装结果，不重复发事实；complete 只交还结果，不再次投递资源事实。回调在提交后抛错或返回另一份结果仍冻结调用方，保留接收方已经提交的余额与事件，禁止重放及检查点导出。

协调模式的 EffectSession.Checkpoint 显式记录 resourceRouting，编码为 format 2 并带严格布尔字段 `resource_routing`；普通本地模式继续写原 format 1。读取旧 format 1 得到本地模式。恢复须同时匹配完整目录与编译模式，不能把协调检查点交给默认编译器悄悄恢复为本地写入。原 707 项存储形状不变，仅增加四种路由命令 / 结果形状；格式扩展仍只保存已结算边界，不保存执行中帧、inbox 或可变视图。

普通动作及结构参数中的资源表达式另走下节的多阶段协议。技能选择 / 施放、显式原生协调组的输入 / tick 及命名目录联合保存已接入；已索引玩家自动登记与同一共享宿主的玩家传送已接入。Buff、装备、携带弹药由联合角色协议移动；物理效果实体继续留在原世界，单独账户路由不会移动这些对象。

### 普通动作与结构参数的资源视图

同一个 `withResourceRouting()` 模式现在为其余普通 Action 生成 ActionRoutes.Begin。它仍实现 ResourceRequests.Request，宿主继续交给 `host.execute(caller, request)`。准备阶段根据实际 definition / pc / invocation / site 恢复词法作用域，在不可变 ResourceReadView 上执行原动作；不会把视图或领域快照存进命令。

若原动作返回 Local，协调器原子发布调用领域的新状态、原有 typed 事实及 Result / Cost，然后原帧绑定原 DSL 结果。Buff、随机流、弹药和 retain_cost 签发账本仍属于调用领域；其中的数值、条件或容量查询可以读取当前归属账户。资源写入仍受 localResource 边界约束，已有显式资源动作继续走归属原语。

若原动作需要世界结果，协议为 Begin → Ready(原世界命令) → 实际世界执行 → Finish(原操作身份、命令、实际结果) → Result / Cost。同一指令通过 resume 逐阶段等待，每次使用新的 invocation。准备时给动作的 operation 是下一次实际世界执行的身份；即时换弹请求、非成员投射物和拾取物身份因此与真实请求对应。准备和完成都核对当前编译位置；Finish 还核对上一阶段的实际回执和此前 Ready 中的命令，不能用改过的命令、旧阶段或伪造结果完成。

完成阶段使用当前协调快照，但 Context.command 仍是已经执行的原命令，不能重新计算其伤害、持续时间等已接受参数。原生 WorldReceipt 的写集和子事实先按解释器协议处理，动作完成产生的事实随后发布；每阶段重复回调只返回已提交结果。未知世界结果、已发布 Local / 完成结果后回调抛错或返回不同结果均保留已有提交并冻结当前阶段，不自动重放、回滚或保存未完成边界。Cost 封装保留真实认领额度，retain_cost 不能因多一次协调等待而重新开放原付款。

覆盖入口包括伤害 / 治疗量、状态层数 / 时间、范围查询、组件与数值计算、伤害快照及伤害消费条件、恢复护盾的层最大值、弹药容量和即时换弹完成查询。calculate / pipeline、攻击快照与嵌套 Profile 传递同一资源视图。普通规则、技能 on_use、武器 on_fire 以及延迟 / 命中 / 拾取动作体共用该编译路径。after 的 delay、普通 / 穿透 / 反弹射线参数、投射物（含 shot 成员）及拾取物生成参数也已接入；for_each 继续遍历原先冻结的集合。

拾取物 collect_if 属于世界宿主领取前的资格查询。协调模式必须调用 `canCollectPickup(view, spawn)`，旧的纯本地状态重载明确拒绝；生成时的半径 / 寿命保持固定，领取条件按本次视图查询。这个重载不领取物品，也不代替宿主复核物理接触与实际领取。

新增 Begin / Finish / Ready / Result / Cost 五种存储形状，原 715 项保持，总计 720；已结算检查点模式仍为本地 format 1 / 协调 format 2。此处没有把任意执行中帧变成可恢复存档。世界侧纯查询和显式会话的连续时钟已有下文的协调入口；旧安装入口保持本地模式；显式共享宿主已接通物理查询、玩家输入、同宿主原生传送与联合保存。本节测试使用显式协调会话和受控世界回执，不代表这些原生路径已经验收。

### 世界侧查询的资源视图

命中计算必须显式传递本次协调快照。`CompiledEffects.outgoing(view, command, input)`、`defense`、`health`、`shields`，以及 `checkAction(view, kind, phase, input)`、`horizontalSpeedLimit(view, input)`、`nativeAttributes(view, holder)` 均接受 ResourceReadView。查询核对完整程序与精确状态；协调编译模式拒绝上述仅传 EffectState 的旧入口，即使没有修饰项、没有护盾或伤害为零也不绕过检查。本地编译的旧入口保持原有行为。

资源表达式、条件与嵌套 Profile 读取同一份不可变视图；读取不转移来源、装备、Buff 或修饰项提供者。它们仍取自查询领域，资源所在领域的其他增益不会自动并入。动作门禁分别按声明的 actor / victim 读取对应持有者资源；速度上限和原生属性贡献继续保持各自的独立查询上下文。原生属性接口只计算贡献，不修改 Minecraft 属性；动作门禁也不替宿主执行或撤销操作。

DamageSnapshot 的 `resolve(view, event, current)` / `resolveShield` 将视图传入仍未绑定的目标条件与表达式。施放时已经冻结的来源值保留，命中时的目标资源及显式 on_hit 项按本次视图读取；保存恢复不把冻结值重算为当前余额。旧快照保留原 Profile 版本，当前视图核对当前完整程序；有实时项时仍要求其 Profile 布局与快照兼容。模式、捕获时刻及数值依赖循环检查继续生效。

`shields(view, command, input, basis, attackState)` 支持世界侧已保留的攻击者消费提案。目标可用层和 taken_multiplier 从 view.state 读取；攻击修饰从 attackState 读取。内部只通过 `view.withLocalState(attackState)` 派生攻击视图，因此两者共享同一批外部资源快照；余额或时刻不同的 attackState 被拒绝，不能分别捕获两份新旧混合的资源值。护盾结果仍为候选写集，由真实世界结果决定是否提交；已确认的本地护盾写集可以在相同余额 / 时刻下派生后续嵌套命中视图。

世界回调使用 `host.readView(caller, request)` 取得视图，不能在执行中改用无请求的重载。宿主应在一次结算中保持同一资源捕获；后续非资源临时状态通过 withLocalState 派生。读取不推进其他领域、不结算 inbox、不发资源事实。原生投影的显式入口见下节；默认 MinecraftEffectRuntime 尚未切到协调模式。连续资源 / 治疗 / 护盾恢复已接下文的显式会话时钟，原生输入、同一共享宿主的玩家传送及目录与世界的联合保存均已接线。未新增存储形状或检查点格式。

### 原生属性与移动投影的资源视图

`MinecraftAttributeProjection`、`MinecraftMovementProjection`、`MinecraftHorizontalSpeedProjection` 可以由显式宿主构造并持有。各自的 `reconcile(ResourceReadView)` 使用调用方捕获的同一资源快照查询全部接收者，沿用现有的真实 Attribute modifier、输入 / 轴限制及每 tick 水平位移预算。宿主必须在服务器线程上为实际世界传入对应的领域视图，负责刷新时机和 close 清理；投影不自动捕获会话、不推进时间或排空 inbox，也不复制资源账户及外域来源。

三个入口在空声明、空持有者集合和清理旧投影之前核对完整程序。协调模式下调用 `reconcile(EffectState)` 会拒绝；错误程序的视图也会拒绝，不会误将已有物理投影清空。本地模式继续支持原接口。内部 MinecraftProjectionQueries 仅在本次刷新中持有视图，不写入实体、会话或检查点。旧视图仍代表旧余额；后续资源变化要由宿主显式提供新视图，不能把旧快照当作实时查询函数。

具体数值求值失败沿用已有投影政策：属性先计算所有接收者及检查 modifier 冲突，全部通过后才修改实体，失败保留上次成功报告；移动按各项失败的门禁限制输入 / 轴，速度查询失败设为零，同时记录错误并向宿主抛出异常。其他成功接收者仍使用自己的决定。非法视图属于刷新入口错误，在查询或清理前拒绝。改变速度上限不会返还本 tick 已使用的位移预算，关闭只清理本投影拥有的状态。

属性还提供 `beforeLoadedHealth(view, entity, savedHealth)`，供宿主在已读原生属性、尚未裁剪保存生命的加载边界调用。它直接处理未索引的新实体，按保存生命判断资格，使用视图中的资源重建上限，不执行治疗、规则或计时。该重载本身不注册加载回调；MinecraftEffectRuntime 已按自身模式选择本地或协调入口。协调启动先恢复全部核心会话和目录，随后在各维度构造尾部安装原生观察器，确保实际 NBT 加载查询到完整资源状态。

显式协调世界回调可以先获取 `host.readView(domain, request)`，用同一视图刷新三个投影，再执行真实治疗等操作。若世界操作已发生但回执未知，原会话的未知结果政策仍保留已提交领域和物理写入，禁止自动重试及保存。命名目录的协调安装与联合原生保存见下文；任意 Java 自定义宿主仍须提供自己的恢复政策，同一共享宿主的原生玩家迁移已接通，物理效果实体与跨独立宿主交接仍待完成。

### 显式安装协调原生运行时

`MinecraftResourceRuntime` 管理同一服务器内多个已安装的 `MinecraftEffectRuntime`。构造和全部操作要求服务器线程。`install(level, program, initial, clock, world, nativeSource)` 显式使用协调编译，先创建未注册会话，登记到同一个 ResourceSessionAuthority，再安装 DamageCapture 与属性 / 移动 / 速度投影。后续维度通过 enlist 加入，旧会话时钟不重新绑定。原有单维度 install / restore 不接受协调编译器，避免创建没有资源目录的运行时。

`claim(holder, ownerLevel)` 保留为非玩家等显式持有者的登记入口，重复 claim 仍拒绝。协调原生运行时在 prepare / start 的时间推进和来源刷新前，批量登记当前维度已索引且未移除的真实玩家；首次装备、角色或技能配置也核对实际玩家身份。安装本身及 NBT 只读加载不登记未索引实体。已有归属完整保留，包括账户为空或资源留在另一维度的情况；登记不补能、不重放初始化、不等同物理迁移。`transfer(holder, destination)` 只交接资源分区，不移动实体、Buff、弹药、装备、角色配置或技能选择。不同维度可以持有各自的规则目录；会话必须匹配自己的完整目录，实际交接另按核心协议校验目标兼容性。`grant`、`snapshot` 与 `drain(maxBoundaries)` 沿用核心提交 / 捕获 / 让出规则，未结束的合法反馈链保留。

原生执行器将 ResourceRequests 直接交给协调器。这些请求之前不刷新物理投影，因此 source_attached 内的资源初始化可以先完成，之后才查询依赖它的属性。真正的世界操作执行前，投影使用当前活动 WorldRequest 的资源视图；普通原生伤害、输入查询、拾取资格 / 吸引以及 NBT 的 beforeLoadedHealth 钩子使用同组当前视图。伤害准备、来源 / 防御 / 健康 / 护盾计算、武器流程与弹药容量沿用显式资源查询。内部终结技 strike 同样通过 ActionRoutes 准备和完成，原延迟计划及伤害回执保持。

玩家装备及技能配置先验证纯状态提案与事实，才写入实际容器或玩家配置；随后共同发布引擎状态和事实，再执行附着 / 卸下反应。协调模式不再通过本地 Commit 重新求值。原生技能选择和输入要求玩家资源归属仍在当前维度；单独转移资源后，必须先完成其他配置迁移或交回账户才能使用。它不把物理玩家留在旧维度时的技能输入转送到没有该玩家的新世界。

`checkpoint()` 捕获核心资源联合检查点及各维度的原生时刻、装备 / 角色 / 技能玩家集合，精确核对领域集合与 effects。调用前须结算接收 inbox；捕获会让各原生运行时 prepare 到当前世界 tick，但不无限排空反馈链。`restore(server, checkpoint, policies)` 要求精确领域集合、完整程序及对应物理世界时刻；全部会话与目录连接后才注册物理观察器。它不回放世界动作、不加载 Minecraft 文件，也没有为这个 Java 包装增加序列化格式。实体真实 NBT 加载已经使用协调属性上限；命名目录另通过下述原生生命周期完成联合世界保存和重启。

成员不能单独 close，必须关闭整个组，保留旧领域可能仍持有的退款签发记录和延迟工作。登记前失败不改变既有组；登记后安装物理观察器失败则保留失败证据、停止该组新操作，必须整体清理。restore 失败清理所有已创建成员。未知世界回执保留已提交资源、玩家配置和真实物理写入，拒绝重试及成功检查点。

任意 Java 自定义组不能由默认目录保存入口接管，因为其时钟与世界执行器无法由目录名称重建。EffectPrograms 的协调安装入口使用明确的默认时钟 / 原生执行器，并支持下述联合保存和启动恢复。已索引玩家自动登记与同一共享宿主传送时的配置联合迁移已接入；旧安装入口仍保持本地模式。已有九项双端共享 GameTest 安装真正的运行时，覆盖初始化顺序、远端资源伤害 / 治疗、未知回执、玩家配置与物品提交、整组恢复及自动 NBT 属性钩子、错误时刻和失败清理；不代表 Compendium 完整效果覆盖或图形客户端验收。

### 命名目录的协调保存与启动恢复

`EffectPrograms.installCoordinated(level, catalogueId, mode)` 显式选用协调模式，按服务器复用一个 MinecraftResourceRuntime；`resourceRuntime(server)` 返回该组以供 claim / transfer / grant。旧 install 继续安装本地会话，旧本地存档不自动更改编译模式或重建满能量。协调组可以与无受管账户副本的旧本地维度同服保存；资源目录只由协调编译会话持有，不把旧本地账户自动吸收为受管分区。

MinecraftCheckpointBoundary 先推进各运行时至当前世界 tick，再按每个参与维度 256 个边界的保存工作预算结算协调 inbox；仍有事件时拒绝本次成功保存，保留全部状态与队列。预算不会截断合法反馈链或丢弃反应。随后固定所有维度、规则、玩家元数据与资源目录，写入 CheckpointArchive format 5，同时固定独立角色 / Buff 归属。无协调组时继续使用本地 format 3。准备期间产生的真实世界写入不会因后续保存失败而回滚。

冻结后，目录 claim、transfer、grant、drain、组关闭以及原生运行时变更均被拒绝；即使调用方捕获异常，也会记录边界违规并阻止提交。最终检查同时核对全部领域状态、玩家配置、完整规则目录、世界时刻和资源目录。原生文件、region stores、SavedData 以及规则边界四项实际完成证据全部成立，才在释放原世界锁之前标记 COMMITTED。自定义 Java 宿主仍不能伪装成命名目录宿主。

启动改用 WorldCheckpointStore.beginCoordinated，继续在接受任何世界工作之前作废上次可恢复标记。先核对保存的全部维度、目录内容及 level.dat 时刻，再用 `MinecraftResourceRuntime.prepareRestore` 创建所有协调核心会话与完整目录。`restoreLevel` 在每个 ServerLevel 构造尾部连接已有会话及原生执行器；其他维度尚未连接时，实体 NBT 属性钩子仍能查询其已恢复的资源。这个阶段只允许加载所需的只读查询，输入、推进、资源写入及保存均拒绝。`finishRestore` 只在全部维度连接成功后开放工作，不重放旧动作。

后续维度连接失败会停止整组并保留已附着的失败证据，整体关闭清理观察器；未完整恢复的启动不生成成功检查点。未知世界结果、未完成保存和文件写入失败仍不能自动重试。未完成保存的启动拒绝发生在文件改写之前。旧本地 archive 1–3 保持原逐维度恢复路径；format 4 / 5 内的独立本地维度同样保留本地模式，但不能作为路由分区的所有者。

验收使用四项共享 GameTest 检查加载顺序、后续连接失败、混合本地 / 协调归档及实际 native save、保存冻结后的目录操作。`tools/checkpoint_restart.py --coordinated` 使用独立保存 / 恢复进程，保留实际 level.dat、玩家文件、装备 / 角色 / 技能配置和远端已花费账户；另注入原生玩家文件写入失败，验证后续启动拒绝且检查点原字节保持。该能力没有自动迁移玩家、装备、技能配置、Buff 或物理效果实体，也不代表完整 D2 内容 / UI 验收。

### 连续状态的协调时钟

ResourceSessionAuthority 构造时，为每个协调编译的 EffectSession 绑定唯一的时间捕获入口。会话推进时间前，先按既有协议结算自己的旧 inbox；之后每到一个新的时间片，用仅在本次规划回调有效的 ClockOperation 捕获目录与所有领域的已提交状态。错误会话、过期能力、执行中重入或第二个协调器重复绑定均被拒绝。接收领域的 inbox 不因读取而执行，其他领域的逻辑时间也不会跟随调用领域前进。

`CompiledEffects.continuousClock(view, fallback)` 从同一不可变视图一次解析本地各账户的回充率 / 阈值、健康恢复声明 / Profile、护盾恢复条件 / 速率 / 最大值；嵌套数值查询继续传递视图。返回的冻结时钟只保留该时间片的状态与已解析结果，不保留资源视图或实时查询函数。已登记资源使用目录定义，未登记的本地账户延续显式 fallback 速率政策。`resourceRate(view, account)` / `resourceCalculation` 拒绝非本地或非当前账户；`recoveryOffers(view)` / `shieldRecoveryOffers(view)` 提供相同的只读入口，协调模式拒绝对应的旧本地查询接口。

纯 TimelineEngine 新增 `needsClock(state, input)`、`prepareAdvance(state, input, capturedClock)` 和 `transition(state, input, proposal)`。准备结果绑定引擎身份、精确时间轴状态与原输入，先取请求时刻和下一截止点的较早者，再生成原 Local 状态 / 事实提案。它不保存闭包或实时世界查询。内部 advance_time 指令核对原状态后应用提案；解释器让出执行权时不重算。下一时间片必须重新捕获，因此阈值 / 到期反应造成的远端余额变化只影响后续片段。拒绝错用另一引擎、相等但非原对象的时间轴或其他输入的提案。

推进仍采用既有分段常量策略：资源阈值、Buff / 定时器到期、连续治疗量子和护盾充满时刻共同分段，片段起点的速率作用于 `[from, until)`。本地资源与护盾容量先更新，恢复世界命令完成后再释放该边界的其他事实；外部请求最后执行。护盾 / 治疗到期前的最后不足量子部分仍结算，已完成片段不在恢复或重试时补算。世界结果未知时保留已推进状态与 pending，阻止继续时间和检查点；纯准备失败且尚未接受新边界时，不发布部分推进。

协调编译的默认时钟只验证状态结构及本地定时截止点，初始化 / 读取已结算检查点不依赖尚未重新连接的外部资源。该结构时钟拒绝实际推进，未绑定协调器的会话也拒绝未来时间输入，不能悄悄按零速率或本地副本计算。恢复后创建新会话并重新绑定完整资源目录，继续原时刻与序号；不允许第二个协调器接管仍绑定旧协调器的会话。推进后的资源目录在下一次协调捕获时按当前领域状态发布，读取、转移与目录快照始终先核对并发布实际状态。

这套接口已用于显式协调会话的真实 start / observe / drain 流程。命名目录已提供显式协调安装及与原生世界联合持久化，旧安装入口仍保持本地模式；已索引玩家的自动归属登记及同一共享宿主内的原生玩家迁移已接入，物理效果实体与跨独立宿主交接仍待完成。Advance 是仅供未结算过程使用的临时提案，不注册为可恢复数据；720 项持久化形状及本地 / 协调检查点格式保持。

### 武器输入与阶段处理的资源视图

显式协调宿主可使用 `changeEquipment(view, change)`、`fire(view, request)`、`reload(view, request)`、`charge(view, request)`、`aim(view, request)` 与 `switchWeapon(view, request)` 计算纯候选状态 / 回执 / 事实；usesHandling、cancelCharge、cancelAim 同样提供视图入口。调用不提交其他会话，不因查询推进时间。协调编译拒绝这些 API 的旧本地重载，包括空手、无待取消动作和未配置行为的提前返回路径。宿主仍须按原输入协议核对当前状态与实际物理结果后提交，不能把纯计算回执当成已经发射或已经切换装备。

原有 EquipmentChange 与武器 Request 事件以及换弹 / 蓄力 / 瞄准 / 切枪的阶段事件，也已自动接入 ActionRoutes。每次输入、物理准备、阶段完成或下一阶段规划，都从当前绑定的资源视图计算；固定编译位置、原上下文和物理回执继续核对。事件中的原始武器、付款与原因身份保留，不把资源领域的装备或 Buff 合并到调用领域。武器弹药与动作计划仍在武器当前领域，资源视图只提供账户查询。

内部 WeaponQueries 将同一份外部资源快照传给动作门禁、条件、弹药消耗量、弹匣容量、时长值及其嵌套 Profile / Pipeline。装备变更、清除旧切枪阶段或蓄力恢复标记等纯候选状态，使用 withLocalState 重新绑定本地来源 / 装备 / 弹药；资源表或逻辑时间发生变化时拒绝派生。该查询上下文是临时值，不存入检查点。

已接受的装填、收枪、举枪、瞄准及蓄力时长保持原计划。完成时门禁和弹匣容量读取当前资源；逐发装填在本次实际补弹及反应结束后，才重新计算下一次时长与装填量。切枪在收枪完成反应之后规划举枪，重复蓄力在射击恢复阶段结束后重新规划下一周期。释放蓄力时保留原蓄力时间，开火费用与间隔读取当时视图。实际世界结果未知时，已扣弹药和已完成物理写入保留，未确认阶段不能重试或保存为已结算状态。

本节已验证显式协调会话，以及真实实体治疗适配器上的付款 / 恢复 / 未知回执场景。显式共享宿主的原生玩家输入、投影与协调器绑定已接线；同一共享宿主通过完整角色协议启用原生跨维度迁移，默认本地模式仍保持。现有本地武器接口和双加载器行为保持，未增加存储类型或格式。

### 技能选择与施放的归属领域

协调编译模式下，原有 `AbilityChange.signal()` 和 `AbilityUse.Request.signal()` 自动产生 `AbilityResources.Select` / `Use` 世界请求，仍由 ResourceSessionAuthority.execute 处理。持有者必须先 claim；目标会话也必须使用协调编译且完整目录兼容。调用领域可以与归属领域不同，但选择、临时替换、门禁、施放条件、参数、费用及其 Profile 都以当前归属领域的状态为准。其嵌套资源表达式继续使用同一 ResourceReadView，可读取其他领域中的目标账户。请求中的宿主观测仍来自原输入，这个接口不凭空重新采样实体物理状态。

Select 在归属领域核对 change.before，预检全部槽位和常驻来源，再共同发布新选择、必要账户及来源。已经存在的账户不补满；空分区只在归属领域初始化。初始化、旧来源移除 / 新来源附加、abilities_changed 按原顺序排队，来源反应看到完整的新选择和账户。相同选择没有重复事实；过期 before 或任何非法槽位不能部分改选或初始化。调用领域残留的选择及来源不会被复制到目标。

Use 以归属领域当前基础选择解析各优先级替换，并使用实际调用技能的事件上下文计算费用。definition / base_selection / previous_replacement 与 cost_slot 沿用原政策：读取被选定义的声明费用，不递归跟随付款槽的替换。门禁或条件拒绝在参数 / 扣费前返回，缺少继承费用不会变成免费施放。付款身份使用 caller + 实际 operation，避免不同领域重用同一 cast 字符串时共享退款身份。

接受后，余额与 resource_spent / resource_changed / ability_started / ability_used 作为同一批次发布到归属会话；失败扣费不发施放事实，零费用接受只发两个技能事实。on_use 在该会话后续处理 inbox 时执行，世界动作也由该领域宿主执行；不会在发起方执行另一个领域的角色动作。Started / Used 和 SourceChange.Fact 保留原有类型、完整事件字段及回执，附加 caller / operation / lease 引用不会把它们压成普通 EffectEvent。同领域沿用当前因果父事件，跨领域使用接收方事件身份。

`AbilityResources.Used` 保存请求、原 AbilityUse.Receipt、付款前账户、可选 Started 和接收 lease；`Selected` 保存请求、原顺序的选择事实和 lease。实际写入与这份结果一起提交，同回调重复请求不重复扣费或初始化。完成处理器只交还原结果，不再次投递事实；提交后未知结果保留已付余额 / 选择与 inbox。后续技能世界动作失败也不自动退款或重放。on_use 的 cast_cost 仍是实际付款，RetainCost 的签发领域是执行技能体的归属会话；后续改选、再次迁移或检查点恢复不改变该退款额度和期限。

显式纯查询 / 提案可使用 `abilityInputAllowed(view, request)`、`useAbility(view, request, paymentIdentity)` 和 `changeAbilities(view, change)`；它们先选择 view.owner(holder)，不直接发布任何会话。协调编译下旧的仅传 EffectState 的施放 / 改选 / 输入资格接口拒绝执行，以免绕过资源归属。默认本地编译的接口和行为保持。

本接口新增的四种技能存储形状保留原 711 项，继续使用已有检查点模式格式。它不更新玩家 NBT，也不把技能配置、来源、Buff、物理效果实体和资源账户一起迁移。原生宿主仍须在账户交接前后同步真实玩家配置、衔接输入 / tick 和联合保存；单独移动账户不会替它完成这些工作。普通动作的资源表达式已接入上节协议。

### 接收会话的事实投递与反馈链

execute 在同一批量提交中为接收会话分配事实身份并追加到时间线，不进入接收方规则。请求重入命中 WorldOperation 的既有结果时，不再次入账或排队。多个请求按提交顺序保留各自的 before / after / credited，事件时刻采用入账领域当时的逻辑时间。同会话请求沿用当前帧的 root / parent；跨会话使用接收领域自己的事件身份，并在事实 references 中保留 resource_caller_domain、resource_operation（frame / pc / invocation）、resource_recipient_domain 和 resource_lease_generation。原始 actor / source / weapon / ability / tags 保留。

EffectSession 的 inbox 标记只表示尚未开始处理的已提交事实。`prepareInboxDomain` 同时预检领域更新、已有队列与新事件编号；批量提交前的任一错误或编号耗尽不会部分写入。普通 prepareDomain 不能覆盖未处理队列。`drain()` 在原时刻运行这些事实，正常 start / observe 也会先处理 inbox，再执行新的外部输入或推进时间；非法时间和保留事件在处理队列之前拒绝。开始新 inbox 边界时清理上一边界的去重回执，继续使用原事件 / 帧序号，避免长期反馈链无限积累旧回执。

跨会话宿主使用 `host.drain(maxBoundaries)` 协作调度，返回 Drained(boundaries, pending)。调度按领域名称轮转，分批耗尽后保留全部剩余事实；pending 为 true 时下一次继续，没有反应次数或循环深度上限。同会话产生的新事实由原正常规则边界继续处理；跨会话反应可以再发请求，把新事实排入另一个会话，而不在世界回调中递归进入它。一次边界的内部执行仍遵循原解释器协议，maxBoundaries 不是中断一个执行中的规则帧。

snapshot 可以读取带队列的已提交余额，但登记 / 归属移交和检查点仍要求先结算队列。若调用方在入账后丢失回执，或接收方执行了结果未知的世界动作，保留已提交余额及其他领域的待处理事实，停止自动 drain / 重放。带未处理事实或失败 / 待回执状态的会话不能导出已结算检查点；当前没有把 inbox 改为可独立持久化的邮件存储。原生宿主接入后必须在正常输入 / tick / 保存边界安排完整的协调结算，不能只保存 Directory。

命名目录协调宿主现在将该目录与独立角色 / Buff 归属写入联合 format 5，并在原生启动前恢复完整核心会话；本地宿主保持 format 3。旧领域资源请求和延迟退款按目录路由，账户回执和签发账本共同保存。实际玩家传送通过[联合角色协议](#原生玩家传送与长按输入交接)共同交接资源、Buff、角色来源、携带弹药与武器阶段，不依靠重新同步选择来恢复。物理实体与跨独立宿主仍需另外实现，资源分区转移自身不承担这些功能。

## 整数弹药与弹匣转移

弹药是以当前运行时内唯一的稳定武器实例 ID 为键的账户，和技能能量的连续 `charge_fraction` 分开。以下步骤放在携带 weapon 身份的 source 规则中；所有动作的 `weapon` 默认 `this_weapon`，也接受其他已验证的 Target。测试使用合成容量，不是某把 D2 武器的数值：

```json
[
  {"action": {"type": "chorus:initialize_ammo",
    "capacity": {"type": "chorus:constant", "value": 6, "unit": "round"},
    "magazine": {"type": "chorus:constant", "value": 2, "unit": "round"},
    "reserves": {
      "amount": {"type": "chorus:constant", "value": 10, "unit": "round"},
      "capacity": {"type": "chorus:constant", "value": 16, "unit": "round"}
    }}, "as": "initial"},
  {"action": {"type": "chorus:refill_magazine",
    "ceiling": {"type": "chorus:constant", "value": 12, "unit": "round"}}, "as": "refill"}
]
```

结果为弹匣 12、储备 0，refill.applied = 10；基础 capacity 仍为 6。省略 amount 时，请求补齐至 ceiling；ceiling 默认基础容量。绑定初始化结果并不意味着每次调用都重新获得弹药。

| 类型 | 字段 / 行为 |
| --- | --- |
| `initialize_ammo` | capacity / magazine / reserves 必填；reserves 为上例的有限账户或字符串 `unlimited`。容量输入须为正，初始弹匣允许溢出，有限储备须在容量内。可选 capacity_profile 和 holder（默认 self）见下文。重复初始化保留现值，容量输入、Profile / holder 或储备种类 / 容量不一致则拒绝。初始化不发布弹药变化事实 |
| `observe_ammo` | 返回 available / created / infinite_reserves / finite_reserves。观察不会创建账户，created 恒为 false；initialize 的新建结果 created 为 true。未初始化时 available 为 false，不能直接读其弹数 |
| `observe_equipped_weapons` | holder 默认 self，tags 默认空集合。返回当前装备中有 WeaponDefinition 的武器实例身份集合，包含收起的武器，按实例 ID 排序去重，可 for_each 后把 binding 用作 weapon。tags 必须全部匹配物品定义标签；不读取 socket / perk 的来源标签。未装备的库存 / 遗留账户、护甲及没有武器行为的物品不参与；已装备匹配武器缺少弹药账户则报错 |
| `observe_drawn_weapon` | holder 默认 self，返回当前独立装备容器中持握且有WeaponDefinition的零或一个武器身份，可for_each后用于refill_magazine等动作。空手、未装备或未配置武器行为返回空集合；已配置的持握武器缺失弹药账户则报错。查询不会使用来源或触发事件中的weapon替代当前选择，也不会创建账户或触发换弹 |
| `spend_ammo` | amount 必填，pool 为 magazine（默认）或 reserves；全部足额才扣除，失败 applied = 0。零请求可 complete，但不会产生实际弹药或开火事实 |
| `refill_magazine` | amount / ceiling 均可省略；实际量受请求、弹匣空间和储备限制。从有限储备扣除同量；无限储备无需保存一个虚构的大数 |
| `grant_ammo` | amount 必填，pool 默认 magazine，ceiling 可省略。给弹匣不会扣储备；给有限储备最多到储备容量；给无限储备 applied = 0。弹匣默认 ceiling 为基础容量，已有溢出不被删除 |
| `ammo` Value | weapon 默认 this_weapon，field 为 magazine / capacity / unmodified_capacity / missing / reserves / reserve_capacity；单位 round。capacity 为当前有效基础容量，unmodified_capacity 为固定输入，missing = max(0, capacity − magazine)。不存在账户或读取无限储备的有限数值均报错 |
| `equipped_reserve_space` Value | holder / tags 与上述装备查询相同，单位 round。累计当前匹配武器的有限储备 capacity − rounds；无限储备和没有匹配武器贡献零。不会把缺失账户当成无限或零，也不根据弹匣缺弹推断储备缺口。普通读取随当前装备 / 弹数变化；on_use 快照固定来源侧数值，victim 查询仍保留到命中时 |
| `round` Value | input 为任意同单位数值，mode 必填 floor / ceiling / half_up。保留单位；half_up 在恰好半数时远离零。它不自动将 charge_fraction 或 count 换成 round |

`observe_drawn_weapon` 在动作执行时读取当前选择。在延迟前保存结果会固定该武器身份；需要执行时持握的武器，应把查询放在延迟动作内部。结果只表示引擎装备状态中的选择，不隐含存活、距离等世界资格检查；来源解绑不会自动撤销已经接受的 detached 延迟动作。将结果逐项交给 `refill_magazine` 只会转移弹药，不会发布 `reload_finished` 或触发 Kill Clip 等换弹词条。

实际弹数、容量、请求和 ceiling 都须为 0 至 2³¹−1 的整数（基础容量至少为 1）。常量在加载时校验，动态值在写状态前校验；不隐式截断小数。按基础容量的 60% 上取整可写为：

```json
{"type": "chorus:round", "mode": "ceiling", "input": {
  "type": "chorus:scale", "of": {"type": "chorus:ammo", "field": "capacity"},
  "factor": 0.6, "from": "round", "to": "round"
}}
```

三个变更动作的结果均提供变化后的 magazine / reserves / reserve_capacity，以及本次采用的 capacity / unmodified_capacity / missing、requested / applied / unfulfilled / magazine_delta（全部 round），和 complete / changed / infinite_reserves 标志。missing 以本次采用的容量减去动作后的弹数计算。无限储备的 reserves / reserve_capacity 不可读取；先用 observe 的 finite_reserves，或变更结果的 infinite_reserves 分支。unfulfilled 是本次未完成数量，不保存为未来可领取额度，也不是消耗弹药的退款权。

applied > 0 时，按动作分别发布 `chorus:ammo_spent / chorus:ammo_refilled / chorus:ammo_generated`；账户改变时另发 `chorus:ammo_changed`。无限储备的显式消费可 applied > 0 且 changed = false。事实携带 requested / applied / unfulfilled / before_magazine / magazine / capacity / unmodified_capacity / magazine_delta；有限储备另有 reserves / reserve_capacity / reserve_delta。布尔值为 complete / changed / infinite_reserves，引用为 weapon / pool / reason，reason 是 spend / refill / generate。actor 为动作持有者，victim 和 weapon 引用指向实际受影响武器，source 保留动作发起来源；跨武器补给不能把收款武器冒充触发者。通用监听通常只监听 changed，避免同时监听专项事实重复发放收益。

这些事实不自动发布 `reload_finished`、shot_fired 或“射空弹匣”。一次逻辑扣弹不能证明真实射击，一次 refill 也不能触发 Kill Clip / Voltshot。下文的独立武器换弹流程在实际完成后发布合格换弹事实。动作本身不设置循环次数限制，内容可以按实际量和状态继续连锁。

弹药写集在后续世界动作前提交；世界结果未知时保留已写弹数和待确认操作，不重新转移、自动退款或重放。显式快照中的来源 ammo 操作数冻结，victim 依赖保留到命中时读取；普通延迟动作内的 ammo 读取则按执行时账户求值。来源解绑和逻辑时钟不会丢失账户，detached 延迟动作可以继续，但默认绑定来源的生命周期规则不变。

完整合成夹具见 [ammunition.json](../common/src/test/resources/effects/ammunition.json)，有两把武器隔离、整数取整和两端真实 tick / 治疗 / 异常验收。`initialize_ammo` 不是任意客户端可调用的装填接口。独立容器已能按下文的 weapons 定义为实际物品实例初始化账户，同一运行时内卸下 / 重新装备不补弹，转给另一持有者时保留弹数并更新容量修饰归属。下文已有服务端单次开火；武器销毁 / 跨维度的账户迁移、未修饰容量输入随武器配置变更、玩家 NBT 弹药保存和弹药同步 / HUD 尚未接入。现有逻辑账户不等于 ItemStack 已保存这些弹数。

### 武器定义与服务端换弹

程序顶层可选 `weapons`，每条绑定已有 equipment.items 的原型 id。示例中的弹数及时间是演示参数：

```json
{"weapons": [{
  "item": "example:rifle",
  "ammunition": {
    "capacity": 30,
    "magazine": 10,
    "reserves": {"rounds": 90, "capacity": 180},
    "capacity_profile": "example:magazine"
  },
  "reload": {
    "value": {"type": "chorus:constant", "value": 2.4, "unit": "second"},
    "profile": "example:reload_time"
  }
}]}
```

item / ammunition / reload 均必填；capacity_profile、reload.profiles 可省略。单段写法 reload.profile 继续接受，但不得与 profiles 同时出现；显式空 profiles 拒绝，省略则直接使用秒数。reserves 必须显式给出 rounds / capacity，或字符串 `unlimited`。所有弹数都是 int；capacity 至少为 1，初始 magazine 可以高于 capacity。定义引用不存在的装备、重复绑定同一原型、Profile 单位错误或未知字段在加载时拒绝。有 weapons 定义的物品必须放在 weapon 槽。

装备事务在发布装配 / 来源事实前初始化该实例的账户，只初始化一次。同一次装配的所有账户和来源先同时就绪，再解析有效容量；重复装备保留余额。已有账户的基础容量、容量 Profile 或储备类型 / 容量不兼容时，在转移真实 ItemStack 前拒绝。仍由另一持有者装备的实例不能重复装配；同一运行时内移交已卸下的实例保留弹数。

reload.value 是接受请求时求值的 Value。没有 Profile 时单位必须为 second；profiles 为非空有序 ID 列表，value 匹配首段输入，相邻段单位一致，末段必须为 second。profile 的旧单段写法解码成一元素列表；编码统一写 profiles。计算采用实际武器持有者、武器实例与同一接受时状态，各段只收集指向自己 Profile 的修饰。

```json
{"reload":{"value":{"type":"chorus:constant","value":10,"unit":"stat_point"},
 "profiles":["chorus_d2:weapon_reload","example:rifle_reload_time","chorus_d2:reload_animation"]}}
```

例如共用装填属性段执行加值与限幅，原型段把 stat_point 变成 second，最后共用动画段乘秒数倍率。Surplus 只声明一次对属性段的贡献，两把枪可以选择不同的原型段。各段的 base / percent_of 作用域仍属于该段；不会把后一段的 base 误指向整条管线最初输入。输入、最终秒数以及所有分段 CalculationProfile.Result 都保存在 Plan.calculation 的 CalculationPipeline.Result 中，Java 调用者通过 steps 查看分段轨迹。没有 Profile 时 calculation 为空。最终秒数必须为正且可表示，调度向上取整到微秒；到期时间不能溢出。任一段失败都不会保存换弹计划或转移弹药。开始后新获得或失去的换弹速度修饰不改写已接受时长。

普通玩家可执行 `/chorus weapon reload` 和 `/chorus weapon status`。reload 不接受客户端指定的武器实例、弹量或时间；服务端从独立真实容器取当前持握武器，并检查存活、维度、旁观者状态与运行时健康。空手、没有 weapons 定义、正在换弹、当前弹匣已满 / 溢出或有限储备为空时拒绝。BUSY 不重置原计时。status 展示当前持握武器的弹匣 / 有效容量、储备和换弹状态。

接受时在 `EffectState.reloads` 保存 Plan 并设置一次到期计时器；此时不转移弹药。切枪、卸下或改变该实例的插槽选择会取消，拿回同一实例不会恢复旧请求。只是把仍持握的同一实例移到另一武器槽不重启。到期时通过显式只读世界操作，再次校验真实容器、持握实例及其选择、存活 / 维度 / 旁观者资格。死亡或离开还会在容器投影清理时取消；到期校验覆盖清理尚未发生的边界。

同一时刻的 Buff 到期先结算，再按完成时有效容量、当前缺口和当前储备执行守恒 refill。至少实际装入一发才发出 `reload_finished`；储备不足但装入了部分弹药仍算完成。若中途已被其他效果补满或储备耗尽，则取消，不能凭空触发换弹词条。换弹状态清除和弹药余额写入先于后续规则 / 世界动作；后续世界结果未知时不重新补弹、退款或自动重放。

| 事实 | 内容 |
| --- | --- |
| `chorus:reload_started` | 接受后的持有者 / 武器来源，reason=manual |
| `chorus:reload_cancelled` | reason=equipment_changed、fire_accepted、host_rejected 或 no_ammunition_transferred；不触发完成 |
| `chorus:reload_finished` | reason=manual，实际 refill 的弹数测量；同次先发布 ammo_refilled / ammo_changed |

三类事实的 actor 为持有者，victim 为武器实例，source 带物品标签和 `chorus:manual_reload`。引用包含 weapon / item / reload（请求标识）/ reason，manual 标志为 true，duration 是接受的秒数，scheduled_duration 是向上取整后的秒数。完成事实另带 requested / applied / unfulfilled、前后弹数、有效 / 未修饰容量及有限储备差值；不把它伪装成原版实体目标。

完成事实还包含 `reload_completion_id`，用于识别本次合格装填。`reload` 仍标识整段请求，逐次装填的每一步共享该请求，但各有独立完成身份。手动完成身份由持有者、武器实例、请求令牌、该步开始时刻和步数确定；再次接受同令牌的后续换弹不会与已完成步骤混同。技能换弹按操作身份和武器实例区分，因此一次 EQUIPPED 操作中的多把武器也各有身份；显式 VERIFIED 策略下零转移的合格完成同样带此引用。开始、取消和普通补弹不产生完成身份。身份在所属会话及其检查点恢复范围内稳定，不承诺跨独立运行时全局唯一；去重时长、触发窗口和逐发资格由具体内容决定，不以请求令牌或根事件限制合法后续触发。

[weapons.json](../common/src/test/resources/effects/weapons.json) 与 kill_clip.json 链接的测试验证实际容器及手动换弹激活对应词条；该场景的武器击杀事实仍由测试宿主提供。下文单次开火已支持接受后中断换弹；当前实现整弹匣与逐次装填，尚无冲刺中断、排热、完整闪身 / Dragon's Shadow 内容、按键 / 动画 / HUD、跨运行时保存恢复；效果触发的合格换弹动作见下文。需要这些行为时扩展武器流程；不能让通用 refill 自动获得合格换弹资格。

#### 逐次装填

reload 默认整弹匣模式。可选 insert 开启逐次装填；外层 value / profile(s) 是首次插入的时长，repeat 单独声明后续插入的时长及有序 Profile 管线。例子的秒数为合成输入：

```json
"reload": {
  "value": {"type":"chorus:constant","value":0.4,"unit":"second"},
  "insert": {
    "rounds": {"type":"chorus:constant","value":1,"unit":"round"},
    "rounds_profile": "example:rounds_per_insertion",
    "repeat": {"value":{"type":"chorus:constant","value":0.2,"unit":"second"}}
  }
}
```

rounds_profile 可省略；存在时接收 rounds 的单位并输出 round，输出必须为正整数且不超过 int 上限。repeat 沿用 profile / profiles 互斥规则。每一步开始时从当前状态计算时长和弹数，保存输入与计算轨迹；该步到期前的 Buff 改变不改写已接受值。首次无弹匣缺口或无储备仍拒绝请求。

每次到期都重新确认实际物品、持握与玩家资格，再按当前容量 / 储备裁剪转移。实际装入才发布 ammo_refilled、ammo_changed、reload_finished；因此第一发装入即可触发配置在 reload_finished 的效果，不需要整段装填结束。事实带 incremental 布尔值、从 0 开始的 reload_step、planned_rounds 以及既有 applied 弹数；duration / scheduled_duration 是当前步骤的时长。需要仅在整段结束触发的内容监听 reload_ended，不能把两个事件混用。

完成事实的规则执行后，内部 NEXT 事件才计算下一步，已提交的 Buff / 弹药改变会影响下一步。满弹匣或储备空时移除计划，发布 reload_ended（reason 为 full / no_reserves）；否则发布 reload_continued 并创建有独立步号的定时器。NEXT 不等待未来 detached 动作。零转移不伪造完成，沿用 reload_cancelled；accepted fire、切枪 / 卸装 / 改装中断未来步骤，保留已提交弹药，拒绝的开火不取消。

状态以 WAITING / BETWEEN_INSERTS 区分尚未装入和已经装入、等待完成反应的边界。旧 DUE / NEXT 重提不会重复转移。后续计算失败或完成反应的世界结果未知时，已装入的弹药保留，运行时停止继续推导，不回滚、补发或自动重试。每步必须正时长，不添加有限步骤次数来限制合法持续装填。

这套协议已用实际玩家容器和普通 reload / fire 命令验证；按键、动画起止帧、不同武器的退出动画与原作逐发触发资格仍需内容侧校准。通用 refill_magazine 仍不产生 reload_finished。


#### 效果触发的合格换弹

`chorus:reload_weapons` 是独立动作，用于技能等效果明确授予换弹资格的场景：

```json
{"type":"chorus:reload_weapons","holder":"self","selection":"equipped",
 "completion":"transferred","reason":"example:class_ability_reload","origin":"event"}
```

holder 默认 self，origin 默认 bound。selection、completion、reason 必填：selection 为 drawn（当前持握）或 equipped（全部已装备的已声明武器）；completion 为 transferred（实际装入才完成）或 verified（物理资格通过即完成，允许零转移）。满弹匣 / 储备为空是否触发 perk 是内容政策，不能从“技能换弹”名称猜测。reason 为命名空间 ID。动作本身无耗时；需要施放到换弹的延迟时由技能参数和 after 声明。

执行时保存完整装备投影、原始原因 Origin 和 OperationId，通过只读世界操作验证玩家存活、维度、非旁观者及实际容器一致性。宿主拒绝或回执时装备已变化，不转移弹药、不发完成。所有匹配武器的容量与转移先针对同一提交前状态计算；任一失败不提交部分弹药。通过后一次写入全部余额，再发布事实，因此第一把武器的完成反应不会影响同一批第二把武器的已定转移。有限储备守恒，无限储备不创建有限账户，已有溢出弹数不裁掉。

若被选武器正在手动 / 逐次装填，成功验证的效果换弹会取消该计划（reload_cancelled 的 reason=instant_reload），再提交批量转移；即使零转移也取消。只选 drawn 不影响其他武器，拒绝 / 空匹配不取消。旧计划到期不能补发弹药。已接受技能成本不因换弹拒绝而退回；世界验证结果未知时尚未提交弹药，后续世界反应未知时保留已提交批量弹药，均不自动重试。

每把实际变化的武器发布 ammo_refilled / ammo_changed，每把符合 completion 的武器发布 reload_finished。完成事实 actor 为收取弹药的持有者、victim 为该武器实例，origin.weapon 为该武器，以便 Kill Clip 等按实例判断；origin.ability / source 保留引发效果的技能 / 来源，标签为该物品标签加 chorus:instant_reload。原始提供者完整 Origin 保存在 InstantReload.Completed.request.cause，DSL 引用另有 cause_owner / cause_source / cause_weapon / cause_ability。事实 manual=false、instant=true、incremental=false，duration / scheduled_duration=0 second、reload_step=0 count；reason 为配置值，reload 为该操作的稳定标识，planned_rounds 及弹药测量均按各武器记录。零时长描述此转移，不声称原作动画没有延迟。

结果可绑定：matched_weapons / reloaded_weapons / changed_weapons 为 count，requested / applied 为所有武器合计的 round；verified / rejected / stale_equipment / empty 表示结果分支，changed / reloaded 表示至少一把实际变化 / 完成。changed 不包含取消手动计划。通用 refill_magazine 仍不发 reload_finished，不能替代本动作。


### 显式随机抽样

`chorus:sample_random` 是动作，不能放进 Value、Profile、条件或查询表达式。每次实际执行只产生一份可绑定的抽样回执，后续读取和 capture_value / 攻击快照 / detached 延迟体复用该值，不重新抽样。规则条件不满足时不会消耗随机序列。

```json
{"action": {"type": "chorus:sample_random", "distribution": "uniform_real",
  "lower": {"type": "chorus:constant", "value": 0.1, "unit": "multiplier"},
  "upper": {"type": "chorus:constant", "value": 0.5, "unit": "multiplier"}}, "as": "roll"}
```

lower / upper 是同单位 Value；结果提供同单位的 value / lower / upper 字段。distribution 必填：uniform_real 将 53 位随机分数映射到 `[lower, upper)`；uniform_integer 在两个端点均包含的整数范围内抽样，端点须为 signed int 范围内的整数，使用拒绝采样消除取模偏差。两种模式均允许相等端点并仍消耗一次抽样。非有限宽度、颠倒范围、单位不匹配及非法整数在状态变更前拒绝。

随机源是 EffectState.random 中版本固定的 `chorus:splitmix64_v1`、64 位 seed 与原始取样游标 cursor。seed 运算按 64 位回绕；cursor 是非负 long，耗尽时失败而非回绕。整数拒绝采样可能推进多次游标。Sample 回执保存分布、前后随机状态、上下界和结果，其构造校验可从种子和起始游标复算；不依赖可变 Java Random 对象、时钟或暂停次数。算法采用 SplitMix64 的固定参数，参考 [OpenJDK SplittableRandom](https://github.com/openjdk/jdk/blob/master/src/java.base/share/classes/java/util/SplittableRandom.java)；实现及金标准序列固定在本项目中。

每次抽样提交游标后发布 `chorus:random_sampled`，包含类型化 Sample 回执与通用事件上下文。numbers 为 value / lower / upper，references 包含 algorithm / distribution、十六进制 seed、十进制 cursor_before / cursor_after；64 位标识不转成可能丢精度的 double。它可以用于服务端审计或规则响应，没有默认客户端广播。抽样事实的监听仍遵守正常事件队列。

同一维度运行时使用一个有序随机流；不同来源的实际抽样顺序会影响后续结果。EffectPrograms 安装生产运行时时由服务端生成新种子；纯核心 EffectState.empty() 固定 seed=0，测试 / 回放可显式提供状态。暂停、Buff / 资源更新、切枪、重新装备和 tick 推进都保留随机源。未知世界结果保留已提交游标与当前绑定，不自动重抽或退款。跨运行时保存、独立命名随机流、加权离散分布和坏运气保护尚未提供；坏运气计数也不能由通用 RNG 自动推断。

随机动作的首个内容用例是 [clown_cartridge.json](../common/src/test/resources/effects/clown_cartridge.json)：合格换弹后抽取普通 / 强化增幅，再按该次完成事实的 capacity 计算上取整后的 refill.ceiling。sample_random 与 refill 是不同动作，先提交抽样游标，再提交实际储备转移；后续世界结果未知不重做任一步。实际容器夹具与分布假设见 [Clown Cartridge 规则集](d2-ruleset.md#clown-cartridge-的随机溢出与验收边界)。

DSL arithmetic 的 add / mul 对有限操作数采用 BigDecimal.valueOf 的规范十进制运算，当前表达式节点最终仍返回 double；min / max 保持同单位比较。显式 round 再按声明的 floor / ceiling / half_up 取整，避免二进制乘法残差让 `100 × 1.1` 被 ceil 成 111；不会用 epsilon 把真正的非整数当成整数。Profile 阶段、曲线与已捕获测量的各自精度契约保持原有定义。

### 同单位比值

`chorus:ratio` 的 numerator / denominator 为同单位 Value，结果单位固定为 multiplier。例如把爆炸衰减区间内的距离换成 0～1：

```json
{"type":"chorus:ratio",
 "numerator":{"type":"chorus:event_number","name":"distance_past_plateau","unit":"meter"},
 "denominator":{"type":"chorus:event_number","name":"falloff_width","unit":"meter"}}
```

两个操作数在编译和求值时都检查单位；分母为正零或负零明确失败，不返回默认值。允许负比值，范围裁剪由调用方的 min / max 或条件决定。采用规范十进制 DECIMAL128 除法，节点输出仍为有限 double；无法表示的溢出失败，非终止小数按该精度舍入。快照分别处理两个操作数，来源读数冻结，victim / impact 读数保留到每个目标结算。JSON 和检查点注册了独立的 Ratio 类型，原有检查点记录字段不变。

[Chain Reaction](d2-ruleset.md#chain-reaction-的击杀爆炸与来源校准) 用 `(distance − full_radius) / (radius − full_radius)` 归一化，再查询显式提供的衰减 Profile；不会把一组固定米数曲线写死在通用引擎里。

### 服务端单次开火

weapons 中可选的 fire 为当前物品原型声明一次触发。缺省时拒绝开火；不会回退到原版近战或偷偷生成弹药。完整示例见 [weapon_fire.json](../common/src/test/resources/effects/weapon_fire.json)，与 kill_clip.json 链接；其中弹量、0.15 秒射击间隔、0.2 秒换弹与 10 点基础伤害都是机制测试参数。

| fire 字段 | 契约 |
| --- | --- |
| cost | 必填 Value，单位 round，求值必须为非负整数；0 明确表示免费，仍受间隔限制；不够时整次拒绝 |
| interval | 必填 Value；不使用 Profile 时必须是正 second |
| interval_profile | 可选已声明 Profile；interval 匹配其输入单位，输出必须是正 second；例如原型属性曲线转成射击间隔 |
| if / tags | 可选条件 / 标签；默认总是允许 / 空集合，与装备原型标签合并构成该次来源 |
| on_fire | 必填动作序列；支持 capture_damage、projectile、分支、迭代和 detached after。瞬时调用没有持久来源，禁止命名 timer 和 source 生命周期 after |

普通玩家的 `/chorus weapon fire` 不带参数。服务端验证玩家存活、非旁观、同维度、运行时健康及真实容器投影一致，生成请求标识并取当前持握实例。核心依次检查物品是否配置 fire、该实例的射击间隔、条件和足额弹药，再计算间隔。间隔按接受前状态求值并保存输入 / 输出 / Trace，向上取整到微秒；`now >= readyAt` 才可再次接受。无效单位、分数成本、非正间隔或时间溢出在提交前失败。

接受时将弹匣扣款与 `EffectState.shots[weapon]` 中的最近一次 Shot 一起提交，之后才运行世界动作。Shot 保存持有者、物品实例 / 插槽选择、请求标识、来源、接受时间、readyAt、间隔计算及实际扣弹回执。切枪、卸下、重新装备或在同一运行时转交物品均不会清除该实例的间隔；不同物品实例独立。当前没有 NBT / 跨运行时恢复。

只有接受的开火才清除持有者的换弹 Plan 与计时器，并发布 `reload_cancelled{reason=fire_accepted}`。冷却、条件失败和缺弹请求保留原换弹；请求恰逢换弹截止时，运行时先完成该边界的到期结算，再接受输入。随后按顺序发布实际 `ammo_spent / ammo_changed`（只有弹数变化时）和 `chorus:fire_accepted`，已有规则按事件队列运行。on_fire 在 fire_accepted 的专属规则中执行，再执行该事件的来源 / Buff 监听；动作读取当时状态，需要冻结的伤害应显式使用 capture_damage。

fire_accepted 的 actor 是持有者，victim 为空；source.owner 为接受时持有者，source.weapon 为实际物品实例，source.source 为 `shot/<请求标识>`。references 包含 weapon / item / shot / origin_source，其中 shot 保留请求标识，origin_source 等于 source.source。numbers 包含 cost、interval、scheduled_interval，以及单位均为 round 的 before_magazine / magazine / capacity / unmodified_capacity：分别是本次支付前后弹量、接受时求值的有效基础容量与容量 Profile 的未修饰输入。它们是不可变的接受快照，不会因后续反应补弹、换弹或容量变化而重算。发射后的投射物保留该来源、捕获的攻击和词法值；收枪或后来手持另一把武器不会改写其信用。

内容可用 `cost > 0 && before_magazine > 0 && magazine == 0` 判断本次接受是否消耗到空；零成本空弹匣开火不满足这一转换，拒绝开火不产生接受事实。接受时射空仍不证明该攻击的弹丸已经结算。需要等待时，显式记住 origin_source，再与 shot_progress / shot_resolved 的同名引用关联；不能在延迟回调中仅凭当前弹匣为零推断是哪次射击。

**接受、发射成功、命中、击杀与整枪结算是不同事实。** fire_accepted 不证明 projectile 已成功生成；生成是否成功看实际 Launch 回执，hit / kill 看 DamageReceipt。弹药支付也不自动生成 shot_fired、shot_resolved 或“射空弹匣”。若声明的世界动作被明确拒绝，已接受的弹药和间隔保持；若结果未知，运行时保留待确认操作并停止，不自动退款或重放。具体内容若需要已知失败补偿，应另声明并验证相应回执处理策略。

weapon_damage / weapon_kill 由伤害动作的 tags / kill_tags 明确声明；仅有武器来源不会自动获得武器击杀信用。双加载器已验证：实际容器命令 → 物理投射物击杀 → 手动换弹 → Kill Clip → 下一发实际 12.5 点伤害，且发射后收枪仍保留攻击快照。另验证取消武器击杀标签后，真实击杀不触发 Kill Clip。

Rampage 的可执行例子见 [rampage.json](../common/src/test/resources/effects/rampage.json) 与 [rampage_weapon.json](../common/src/test/resources/effects/rampage_weapon.json)：词条片段引用消费者的 test:weapon_damage / perks / weapon_perk，链接时检查存在性；普通与强化 Buff 使用独立静态 duration / decay_interval 并共享数值 bundle。仅覆盖 grant_buff.duration 不会改变定义的 decay_interval，因此两者不同的变体须分别声明。微秒计时、收枪保留、真实击杀和发射快照的具体政策见 [Rampage 规则集](d2-ruleset.md#rampage)。

该入口每次接受后执行一次动作体。下文已补充服务端 hitscan、数据定义精准区域及蓄力 / 持续充能输入；即时武器的长按连发、完整原型射击策略、未命中 / 弹匣耗尽的全部内容资格、射击手感和正式 HUD 仍需扩展。

### 整枪与弹丸结算

`chorus:begin_shot` 声明一组射击的预期弹丸数与有限攻击寿命，返回类型化 handle。普通 `projectile` 增加可选 `shot`，用 handle 和零起始整数索引关联一颗弹丸。`damage_snapshot.pellet` 显式指向该成员的当前 impact：只有匹配目标、owner / weapon / source / ability 的实际伤害回执才参与计数。普通投射物保持原有行为。

```json
[
  {"action":{"type":"chorus:begin_shot",
    "pellets":{"type":"chorus:constant","value":3,"unit":"count"},
    "lifetime":{"type":"chorus:constant","value":2,"unit":"second"}},"as":"group"},
  {"projectile":{"position":"muzzle","direction":"aim",
    "speed":{"type":"chorus:constant","value":20,"unit":"meter_per_second"},
    "gravity":{"type":"chorus:constant","value":0,"unit":"meter_per_second_squared"},
    "drag":{"type":"chorus:constant","value":1,"unit":"multiplier"},
    "lifetime":{"type":"chorus:constant","value":1,"unit":"second"}},
    "shot":{"binding":"group","pellet":{"type":"chorus:constant","value":0,"unit":"count"}},
    "as":"impact","do":[{"for_each":"impact","as":"victim","do":[
      {"type":"chorus:damage_snapshot","snapshot":"attack","target":{"binding":"victim"},"pellet":"impact"}
    ]}]}
]
```

这是片段：前文需要已捕获的 muzzle / aim / attack，并且还需要索引 1、2 的两个 projectile；完整可运行夹具是 [shot_weapon.json](../common/src/test/resources/effects/shot_weapon.json)。该夹具的三颗弹丸与数值是合成参数，不是 D2 霰弹枪原型。多个显式组可以属于同一次 fire_accepted，以表达未来连发控制的每个逻辑射击；触发身份 `origin.source=shot/<token>` 与整枪组身份 `references.shot=shot-group/...` 不同。

| shot_progress / shot_resolved 共有字段 | 含义 |
| --- | --- |
| pellets_total / pellets_hit / pellets_effective | 预期弹丸数 / 至少有一次合格 hit 回执的唯一弹丸数 / 至少有一次 HP、护盾或吸收损失的唯一弹丸数 |
| pellets_missed / pellets_rejected / pellets_unresolved | 已结束且没有 hit 的弹丸 / 世界明确拒绝生成的弹丸 / 未发射、仍飞行或丢失结束观察的弹丸 |
| targets_hit | 收到合格 hit 的目标数 |
| max_pellets_on_target / max_effective_pellets_on_target | 同一目标上的最大唯一命中 / 有效伤害弹丸数；两个最大值可能来自不同目标 |
| flags.complete / flags.all_hit | 所有预期槽位都已有终态 / 完整且每颗弹丸都曾 hit；all_hit 允许分散目标，不等于集中全中 |
| references.shot | 稳定的本次整枪组身份；成员伤害事实另外带 pellet 索引与 contact 序号 |
| references.origin_source | 该组 Handle 的 origin.source；武器开火派生组可与 fire_accepted 关联，不改写 references.shot 的组身份 |

计数值单位均为 COUNT。类型化 `ShotGroups.Summary` 额外保留两个每目标计数表；公共事件 victim 选择命中数最多的目标，同分按身份字符串排序，没有 hit 时为空。贯穿可计入多个目标；同一弹丸对同一目标的多次接触 / 多条伤害命令只计一次。免疫和格挡遵从现有 hit 契约，计入 hit 但不计入有效伤害；CANCELLED / FAILED 与纯几何接触不计入 hit。集中命中要检查 max_pellets_on_target，不能只检查 pellets_hit；需要最终完整结果时再要求 complete。

origin_source 只表达实际来源关系，不强制一枪一个组，也不保证来源一定是武器开火：技能或其他动作创建的组仍保留自己的来源。它不额外分配全局身份；自定义请求生产者须在关联存续期间使用不重复的 token，内容还应匹配 owner / weapon。Minecraft 开火入口生成 UUID。多个组可共享同一来源，某一组 complete 不代表该来源的所有组已结束；需要整次连发结算的内容必须显式组织组及计数。寿命截止的 complete=false 和全部打空的 complete=true 都保留关联，但含义不同；最后一颗打空不妨碍完整组结算。

`chorus:shot_progress` 在实际伤害回执增加任一目标的唯一 hit / 有效弹丸数时发布；重复命中未改变计数则不发布。另有 COUNT 字段 previous_pellets_hit / previous_pellets_effective / previous_max_pellets_on_target / previous_max_effective_pellets_on_target，references.pellet 指向本次成员。类型化 Progress 保留不可变的 before / after Summary。当前接触尚未关闭，complete 通常为 false；该事实不等待整枪结束，也不证明剩余弹丸已完成。

One-Two Punch 使用 `previous_max_pellets_on_target < 门槛 && max_pellets_on_target >= 门槛`：普通 12 / 强化 10，达到时立即挂 Buff，同一枪的后续弹丸不重复刷新。只等 shot_resolved 会错误推迟强化版触发。hit 与消费生命周期事实仍先入既有队列，progress 随后入队，不抢跑当前动作体或改变广度优先执行顺序。

局部状态先保留索引，再等待物理发射回执；终止接触的动作体与实际伤害回执完成后才封闭该弹丸。所有槽位结束时发布一次 summary 并取消截止计时器；明确发射拒绝仍是已知终态，不会自动退还已接受的弹药 / 间隔。重复接触、已结算后的回调均不重放动作体。未知世界结果保留已提交状态与待确认操作，停止运行，不推断为 miss / failure 或自动重试。

攻击寿命为半开区间 `[startedAt, dueAt)`，精确到期的延迟发射也不能重新打开组。超时发布 complete=false 与未结算数量，清理组并停止仍存活的成员；这是一条内容声明的攻击寿命，不是事件循环限制。索引可以在寿命内通过 detached after 延迟发射，但 `damage_snapshot.pellet` 只能在对应接触的同步动作体中使用：编译器不向 after 或嵌套 projectile 传播当前接触的记账资格。延迟爆炸仍可沿用几何与伤害快照，需作为独立伤害或新组建模。

目前提供显式成员 projectile 与 damage_snapshot 记账，One-Two Punch 已有独立内容与实际玩家输入 / 投射物 / 近战验收，仍为 partial，见 [规则集](d2-ruleset.md#one-two-punch雪上加霜)。下节 hitscan 也支持同一显式成员和实际伤害统计。原版命中自动归组、完整精准分类、即时武器自动连发、跨组 / 多分量共用一次“下一击”事务、活跃组 / 飞行实体的跨进程存档恢复仍需扩展。Rewind Rounds 已有独立数据定义及显式组数的结算策略，见 [命中计数与补弹](d2-ruleset.md#rewind-rounds-的命中计数与射空补弹)。

### 动态基础容量与数值快照

在 initialize_ammo 上添加 `"capacity_profile": "example:magazine"`，可选 `"holder": "self"` 指定收集修饰的实际武器持有者。Profile 必须已在程序中声明，输入 / 输出都是 round；原 capacity 参数作为未修饰输入固定保存。如下 Profile 先组合倍率，再统一向上取整：

```json
{"id": "example:magazine", "version": "example-v1", "input_unit": "round", "steps": [
  {"type": "chorus:apply", "id": "bonuses", "operation": "multiply",
    "group": {"name": "capacity", "reduction": "product"}},
  {"type": "chorus:round", "id": "integer", "rounding": "ceil"}
]}
```

来源或 Buff 的 modifier 引用这个 Profile / stage / group，使用常规 delta、分组与条件。Profile 和程序的 version 必须一致；修饰可用 `source_is: this_weapon` 或 Buff 的 `affects: instance_weapon` 保持武器隔离。上述乘积和最后上取整是示例选择，具体内容可以声明别的阶段顺序及 floor / nearest_even。动态最终值为 0、小数或超过 int 上限时明确失败，不静默裁剪。

容量查询的 actor 是账户 holder，victim / source.weapon / weapon 引用都是实际被查询的武器，source.owner 也是账户 holder，source.source 为该武器 ID，ability 留空；不借用补给发起者的身份或强化标签。查询带 `chorus:ammo_capacity_query` 标签、unmodified_capacity / magazine 测量及 infinite_reserves 标志。它使用已结算的当前领域状态，可读取当前层数、未修饰容量、已装弹数等。跨持有者补给依然从接收账户的 holder 收集修饰。

当前有效容量派生读取，不写回 AmmoState.capacity；Java 宿主用 `CompiledEffects.ammoCapacity(state, weapon)` 取得容量与完整 CalculationProfile.Result 轨迹。原始账户 read(capacity / missing) 对动态账户报错，要求显式取派生视图。动态容量只读重算不发 ammo_changed，不改变已有弹数或补齐弹匣。任何一次弹药动作先解析容量，再提交弹数；变更回执与事实保留本次采用的容量和输入。若本次写入又改变下一次容量条件，下一次查询才反映它。例如“弹数大于 1 时容量翻倍”，弹匣 1 / 容量 5 的一次默认 refill 只补到 5，后续新查询才得到容量 10。

观察结果本身是快照；`capture_value` 可把当时的有效容量留给 detached 延迟动作，而延迟体中直接读 ammo.capacity 会取执行时状态。Buff 到期、收枪策略、来源解绑按原有生命周期生效，不要求内容再发一个“恢复原容量”动作。数值 Profile 直接或间接依赖自己正在求的有效容量时明确报数值自引用错误；读取 unmodified_capacity / magazine 不受此限制，事件和效果连锁也不受该检查限制。

完整例子 [ammo_capacity.json](../common/src/test/resources/effects/ammo_capacity.json) 使用基础 5 发、200 ms 容量翻倍、400 ms 延迟等合成参数。纯核心与两端游戏测试验证 5 → 10 的容量变化再叠加 2 倍 refill ceiling 得到 20 发，Buff 到期后 capacity 恢复 5 而已装 20 发保留。它不是 Timelost Magazine 或 Fail-Deadly 的完整内容 / 数值验收。

## 目标查询和逐目标执行

以下步骤可放进 source 规则的 do 数组；查询半径、伤害和标签均为合成演示值。完整可执行程序见 [target_iteration.json](../common/src/test/resources/effects/target_iteration.json)，还展示了按每个目标实际伤害回血。

```json
[
  {"action": {
    "type": "chorus:select_targets", "center": "self", "relative_to": "self",
    "relation": "not_allied", "include_center": false,
    "radius": {"type": "chorus:constant", "value": 5, "unit": "chorus:meter"}
  }, "as": "nearby"},
  {"for_each": "nearby", "as": "enemy", "do": [
    {"type": "chorus:damage", "target": {"binding": "enemy"}, "damage_type": "example:area",
     "amount": {"type": "chorus:constant", "value": 2, "unit": "chorus:damage"}}
  ]}
]
```

- `center`、`relative_to` 默认 self；radius 必填，单位 meter，须为有限非负数。relation 默认 any，可选 allied / not_allied，按 relative_to 的原版队伍关系判定。include_center 默认 false；即使开启也不会纳入死亡实体或绕过队伍过滤。变量名 enemy 是作者命名，中立生物也可能是 not_allied。
- 默认按脚底球形距离查询本维度已加载的存活 LivingEntity，排除旁观者与已移除实体，包含半径边界。一 meter 对应一格；也可使用下文的 area、取样点与显式视线筛选，最近排序通过 order 明确选择。仍存在的死亡实体可作中心，已移除中心返回 missing，不回退其他位置。不加载区块；宿主实体解析器应能解析查询返回的 UUID。
- 结果的 `count` 字段单位 count，写作 `{"type":"chorus:result","binding":"nearby","field":"count"}`，可用于 compare 判断附近数量。布尔 `available` 用 result_flag 读取：成功但无目标为 true；缺失中心、关系参照或维度不匹配为 false，分别有 missing_center / missing_relative / wrong_dimension 标志。any 不要求世界中存在 relative_to。
- `for_each` 读取前面已绑定的目标集合，按查询冻结的顺序执行；do 可以包含动作、条件和嵌套循环。每个目标先执行完整序列，等待真实回执后才推进下一目标。嵌套循环可以读取外层目标，各循环的局部结果不会向外泄漏，也不能遮蔽已有名称。
- 目标参数既支持原有 self / victim / event_actor / source_owner / this_weapon 字符串，也支持循环的 `{"binding":"名称"}`；循环不改写原事件 victim。绑定必须是单个目标，不能把集合或伤害回执用作实体。
- 世界查询只做一次并固定列表；后续目标移动仍按原身份执行，消失则由对应动作返回 missing / failed 等结果。新进入范围的实体不补进旧列表。再次查询必须显式执行新的 select_targets；跨帧成员差分可用下节的 target_sets，独立空间场物体及自动进入 / 离开事件尚未实现。
- 循环外成本可供各目标共享返还额度，循环内每次支付则有独立额度；未绑定的返还也计入累计额度。成本引用仍限本规则 Frame，不能从循环外引用循环内的 cost。

这提供范围效果的通用执行基础；Volatile 与 Jolt 的部分数据定义已组合这些机制，真实技能接线和完整数值校准仍待完成。具体边界见 [实现记录](engine-implementation.md)。

### 保存成员与进入 / 离开差分

Buff 可声明 `"components":{"target_sets":["members"]}`，每个新 generation 初始化为空集合，刷新保留。它与普通字符串去重 `sets`、数值 `numbers`、引用 `references` 分别检查类型，所有组件名称不能重名。保存内容只有实体身份，按身份文本升序去重，不包含距离、坐标或实体对象。

以下是规则 do 数组片段，要求 example:field 已存在并声明 members。每次查询后提交成员集合，再分别处理离开和进入的身份；cue 名称和 3 米半径都是演示参数：

```json
[
  {"action": {
    "type": "chorus:select_targets", "center": "self",
    "radius": {"type": "chorus:constant", "value": 3, "unit": "meter"}
  }, "as": "nearby"},
  {"action": {
    "type": "chorus:sync_targets", "buff": "example:field",
    "component": "members", "targets": "nearby"
  }, "as": "changes"},
  {"action": {"type": "chorus:difference_targets", "binding": "changes", "part": "exited"}, "as": "exited"},
  {"for_each": "exited", "as": "member", "do": [
    {"type": "chorus:play_cue", "cue": "example:exit", "target": {"binding": "member"}}
  ]},
  {"action": {"type": "chorus:difference_targets", "binding": "changes", "part": "entered"}, "as": "entered"},
  {"for_each": "entered", "as": "member", "do": [
    {"type": "chorus:play_cue", "cue": "example:enter", "target": {"binding": "member"}}
  ]}
]
```

| 动作 | 输入与结果 |
| --- | --- |
| read_targets | buff、component、可选 target（默认 self）；返回已保存的身份集合，提供 count |
| sync_targets | 同上，并用 targets 指定此前绑定的查询集合或身份集合；提交新成员，返回类型化差分 |
| difference_targets | binding 指定差分，part 为 before / after / entered / exited；返回可供 for_each 的身份集合 |

差分提供 count 单位的 before_count / after_count / entered_count / exited_count，以及 observed / changed 布尔字段。entered = after − before，exited = before − after；before / after 在后续状态变化后仍保持原值。差分本身不是可迭代集合。

成功查询到空集合是有效观测，会移除所有旧成员。查询不可用时，sync_targets 返回 observed=false、changed=false，保留旧集合，进入和离开均为空。规则可检查原查询的 missing_center 等标志，明确选择保留、结束场或其他行为。传入 read_targets / difference_targets 的身份集合时 observed=true，表示输入集合可用；这次同步没有重新观测世界，不保证实体仍存在。

这些动作是纯状态操作，不产生隐式进入 / 离开事件，不自动施加或移除 Buff。身份集合的循环可用 member 作目标，但读取 member.distance 会在加载时报错；距离只能取自显式世界查询的目标结果。集合保存的是完整查询结果，包括原查询的关系、排除和数量限制；同步后采用身份顺序，不保留 nearest 排序。

结束规则可以用 `read_targets` 读取自己已结束的 Buff 最终快照，再逐成员清理。即使同键的新 generation 已出现，也不会误读新实例的集合；sync_targets 则只更新当前活动实例，不能修改结束快照。场和成员身上的效果需要使用匹配的实例键，例如各自 instanced_by:source，才能在一个场结束时保留其他来源。同一来源重复施放是否刷新原场，还是创建新的施放身份，需由内容 / 宿主明确选择。

完整 [membership_aura.json](../common/src/test/resources/effects/membership_aura.json) 使用 Buff 定时器每 50 ms 查询跟随持有者的 3 米范围：进入授予来源独立的恢复 Buff，离开移除；场持续 0.3 秒，到期、主动移除或中心不可用时清理最后成员。成员恢复使用同一通道的 2 HP/s，重叠场不叠加。这些均为合成参数，尚不是 Healing Rift / Well 的内容定义。

采样之间沿用上一次成员，先把旧恢复积分到下一次观测时间，再处理变化；不会回推实体真正跨越边界的时刻，采样间短暂进出也可能不被观察到。成员表示被选中的身份，不保证后续 apply_status 成功；拒绝后是否重试须由规则决定。提交成员后若世界动作失败，沿用引擎保留已提交状态并停止推导的协议，不自动回滚或补偿。固定位置可用下文的 positions 组件保存；独立场物体、完整技能装配、运行时快照与持续场的客户端同步仍待实现。

### 显式实体观测

`inspect_entity` 是只读世界动作，`target` 默认 victim，支持循环的单个目标绑定。它返回请求时的不可变观测，通过 `as` 保存；后续动作、世界变化和延迟恢复都不会刷新这个值。需要当前信息时再次显式查询。

```json
[
  {"action": {"type": "chorus:inspect_entity", "target": "victim"}, "as": "entity"},
  {"if": {"type": "chorus:all", "of": [
    {"type": "chorus:result_flag", "binding": "entity", "field": "available"},
    {"type": "chorus:result_flag", "binding": "entity", "field": "alive"},
    {"type": "chorus:result_flag", "binding": "entity", "field": "player"}
  ]}, "then": [
    {"type": "chorus:heal", "target": "victim",
     "amount": {"type": "chorus:constant", "value": 1, "unit": "damage"}}
  ]}
]
```

| 投影 | 类型 | 含义 |
| --- | --- | --- |
| available / missing | result_flag | 实体能否在当前维度解析，且尚未移除 |
| alive / player | result_flag | 原版 isAlive / 是否为 Player；仅 available 时可读 |
| health / max_health / absorption | result，damage | 原版当前生命、最大生命、Absorption；不含 Chorus Buff 护盾 |
| health_fraction | result，multiplier | health / max_health，保留观测比例，不隐式裁剪 |

缺失、已移除或跨维度实体返回 missing；仍存在的死亡实体可为 available 且 alive=false。缺失结果的 alive / player 和所有数值读取会报错，必须先用短路 all / 分支检查 available。未知字段、单位错误和将此结果当作目标 / 位置 / 集合使用均在加载时拒绝。回执须匹配原 EntityQuery，不能替换为其他实体的观测。

`player` 只是 Minecraft 实体类别，不决定活动的 PvE / PvP 模式，也不代表完整的 Destiny Guardian、敌人等级或勇士分类。[entity_observation.json](../common/src/test/resources/effects/entity_observation.json) 展示按已观测生命缺口回血；[jolt.json](../common/src/test/resources/effects/jolt.json) 将玩家观测与伤害回执组合，只有另一玩家实际损失 HP / 护盾 / Absorption 后才允许中心玩家承受链伤。观测本身不预判后续伤害是否成功。

### 观测实体分类标签

`inspect_entity` 同时复制原版单实体标签（`/tag`）和数据包 `entity_type` 标签，两个集合独立保存。条件只读取已绑定回执：

```json
{"type":"chorus:observed_entity_tag","binding":"entity","source":"entity","tag":"chorus_d2:elite_or_higher"}
```

source 为 entity（默认）或 type。entity 精确匹配实例上的字符串标签，type 匹配实体类型所属的注册表标签 id；不会隐式合并、从生命值猜等级或把不存在的标签回退成其他等级。它们与 event_tag、source_tag、has_source_tag 分属不同上下文，互不读取。内容可通过 any 明确组合两种来源；如果存在互斥分类，优先级须由内容定义。

例如 `data/chorus_d2/tags/entity_type/elite_or_higher.json` 可声明整类敌人，`/tag <实体> add chorus_d2:elite_or_higher` 可标记其中一个实例。引擎不内置 D2 等级枚举，也没有预设“牛就是精英”的正式映射；测试目录中的映射仅用于验收。原版保存单实体标签，数据包管理类型标签；这不等于已实现 Chorus 敌人定义、等级同步或迁移。

必须先检查观测 available：缺失观测的标签读取报错，已观测实体没有该标签才返回 false。死亡但尚未移除的实体仍能读取分类。世界中改标签、死亡、移除或数据包重载不会修改旧回执，再次 inspect_entity 才取得新值。标签条件支持动作 if、choose 和 after 中保留的绑定；不提供隐式世界查询，也不能直接用于没有动作绑定的 modifier。若数值需要冻结，可显式 capture_value；实体观测本身仍不能当成目标 / 位置引用。

[classified_burst.json](../common/src/test/resources/effects/classified_burst.json) 验证实际死亡时观察分类与位置，来源卸下、尸体移除和标签改变后，延迟查询仍选初始 4 / 8 米范围并造成实际伤害。它是 Incandescent 所需分类路径的通用测试，0.1 秒延迟和 1 点伤害是合成参数，尚未实现该 perk 的 Scorch / Ignition 内容。

### 固定位置与延迟范围

`capture_position` 显式读取目标当前脚底坐标，target 默认 self，也可用 victim 或循环目标绑定。返回不可变的维度与 x / y / z，结果绑定类型为 position，有 available / missing 标志。死亡但仍存在的实体可以捕获位置；已移除、无法解析或不在当前维度的实体返回 missing，不用零坐标代替。

下面是规则 do 数组片段。先捕获位置，再于 0.1 秒后查询该位置周围的目标；半径和延迟是演示参数：

```json
[
  {"action": {"type": "chorus:capture_position", "target": "victim"}, "as": "place"},
  {"if": {"type": "chorus:result_flag", "binding": "place", "field": "available"}, "then": [
    {"after": {"type": "chorus:constant", "value": 0.1, "unit": "second"}, "lifetime": "detached", "do": [
      {"action": {
        "type": "chorus:select_targets", "center": {"position": "place"},
        "radius": {"type": "chorus:constant", "value": 5, "unit": "meter"},
        "exclude": ["source_owner"], "order": "nearest"
      }, "as": "nearby"}
    ]}
  ]}
]
```

`center: {"position":"place"}` 与实体 `center: "victim"` / `{"binding":"target"}` 不同：前者始终使用捕获的坐标，后者查询时解析实体的当前位置。坐标不跟随原实体，原实体随后移动或消失不影响它；每次 select_targets 仍重新读取范围内的当前成员与距离。把 capture_position 放入 after 内则在延迟到期时捕获，而不是释放时。

固定位置没有对应的“中心实体”，include_center 对它不产生隐式排除；位于零距离的任何合格实体都可以入选，想排除原目标须明确写 exclude。关系仍通过 relative_to 的当前实体判断，坐标本身不保存阵营。nearest、limit、半径边界和距离曲线与实体中心相同，距离均从固定点到候选脚底计算。

若直接使用 missing 的位置绑定，查询返回 missing_center 和空列表；坐标维度不同返回 wrong_dimension 和空列表，不跨维度查询、不加载区块。position 不能作为伤害 target、for_each 集合或伤害快照，词法作用域与其他结果一致。实体位置可选择 LivingEntity 的 feet / body / eyes；伤害回执中的历史位置另见下文。方块 / 物体位置、通用坐标运算、独立场物体及跨重启恢复仍待实现。

完整 [fixed_position.json](../common/src/test/resources/effects/fixed_position.json) 将一次位置捕获、伤害快照和三次嵌套延迟组合；每次爆发重新选择目标。测试使用 5 米、10 点伤害、100 / 150 / 200 ms，都是合成参数，不是 Kinetic Tremors 的数值定义。

### 在 Buff 中保存固定位置

定义可声明 `"components":{"positions":["anchor"],"target_sets":["members"]}`。位置组件与其他组件共用名称唯一性检查，首次创建 generation 时为缺失位置，刷新保留；保存的是不可变的维度与坐标，没有实体引用。它使不同事件和定时回调能读取同一个固定场位置。

`write_position` 把已绑定的 position 精确复制到当前活动 Buff，`read_position` 返回该组件的 position 结果。两者都是纯状态动作，使用 buff、component、可选 target（默认 self）；write 另需 position 指定此前的结果绑定。以下片段要求 example:field 已存在且声明 anchor：

```json
[
  {"action":{"type":"chorus:capture_position","target":"self"},"as":"captured"},
  {"if":{"type":"chorus:result_flag","binding":"captured","field":"available"},"then":[
    {"type":"chorus:write_position","buff":"example:field","component":"anchor","position":"captured"}
  ]},
  {"action":{"type":"chorus:read_position","buff":"example:field","component":"anchor"},"as":"saved"},
  {"action":{
    "type":"chorus:select_targets","center":{"position":"saved"},
    "radius":{"type":"chorus:constant","value":5,"unit":"meter"}
  },"as":"nearby"}
]
```

read / write 均返回位置类型，可直接用于查询中心或再次复制，available / missing 只表示是否保存了坐标，不代表来源实体或区块仍存在。未初始化组件返回 missing；未声明组件或 Buff 不存在会报错。**write_position 会复制缺失值，从而显式清空已有坐标**；想在捕获失败时保留旧位置，需像示例一样检查 available。这与 sync_targets 对不可用查询的保留策略不同，两者不能混用。

read_position 在自身 ended 规则中读取旧 generation 的最终快照；同键新实例不会替代它。write_position 只写当前活动实例，不修改结束快照。读取结果在后续覆盖、移除、延迟或来源解绑后保持原值；重新读取组件才会取得新值。坐标维度也原样保存，交给世界查询时继续验证，不自动转换维度。

[healing_rift.json](../common/src/test/resources/effects/healing_rift.json) 是与 restoration.json 同版本链接的程序片段：宿主用 SourceChange.bind 为每次施放提供独立来源，own_source 只处理该来源的附加事件；先捕获脚底位置，成功后创建 15 秒 field 并写入 anchor，随后 buff_gained 规则开始每 50 ms 查询固定的 5 米范围。查询使用原版 allied 与 source_owner，成员变化授予 / 移除来源独立的 Rift presence，恢复速率复用 restoration.json 的定义。场结束清理最后成员，晚进入者不会多留 15 秒。

每次施放的 EffectSource.instance 和 Origin.source 都要唯一；ability 可为同一个技能标识，不能用当前武器 / 装备来源冒充独立施放。重复绑定相同来源不重施放、不延长，解绑施放来源不删除已创建的 field。当前 fixed anchor 本身允许施放实体消失，但 allied 查询仍需要当前维度中的 source_owner；Rift 片段在关系参照不可用时明确结束并清理，这是待校准策略。无此关系依赖的 any 查询已验证可在原实体移除后继续。

此片段尚未接入落地点 / 施放动画、按键与技能成本、来源离线后的阵营保留或表现物体；满血护盾已用上文的离散补充规则接入。脚底球形查询与 50 ms 采样是当前空间适配，不能当作原作地面场几何和精确跨界时序已校准。位置状态仍只保存在内存中。

[Duskfield](d2-ruleset.md#duskfield-暮域手雷与固定周期场) 另验证由实际技能投射物碰撞创建固定场：在同一动作体中 grant_buff、write_position 并写入周期 / 校准组件后，排队的 buff_gained 才启动该实例自己的 schedule。每跳 select_targets 重新取样成员，再以原归属执行 damage 和 invoke_bundle；没有将最初的目标列表固定到整个场寿命。场按来源身份分开，到期 / 明确移除撤销自有计时器，其他场与已授予的独立状态继续。该组合无需新增核心动作。

高频持续伤害还需宿主伤害类型配合。D2 的显式 `chorus_d2:ability_dot` 注册为绕过原版 hurt cooldown，其他护甲 / 无敌资格照常检查；只有声明该 damage_type 的动作使用它。通用引擎不因为伤害带有某个 DOT 标签就自动绕过原版规则。Duskfield 的原作时序和未完成项见规则集，合成飞行 / 首跳 / 衰减参数没有并入生产默认值。

### 排除、排序、数量和距离曲线

`select_targets` 新增以下可选字段：

| 字段 | 含义 |
| --- | --- |
| `exclude` | 目标引用数组，默认空；例如 `["source_owner", {"binding":"previous"}]`，外层循环绑定须已存在 |
| `order` | `identity`（默认 UUID 文本升序）或 `nearest`（距离升序，同距按 UUID 文本） |
| `limit` | count 单位的 Value，须为非负整数；省略表示全部，0 表示不选任何目标 |

过滤先于排序，排序先于数量截取；显式排除不受 include_center 开启影响。结果 count 是最终选中数量。查询集合的 for_each 元素还提供 `distance`（meter），它是查询时相对中心的距离快照，目标随后移动也不自动更新。

例如以下 Value 可用作 multiplier，再与基础 damage 相乘：

```json
{
  "type": "chorus:curve", "from": "meter", "to": "multiplier",
  "of": {"type": "chorus:result", "binding": "target", "field": "distance"},
  "curve": {
    "type": "chorus:table", "interpolation": "linear", "boundary": "clamp",
    "points": [
      {"input": 0, "output": 1},
      {"input": 3, "output": 1},
      {"input": 7, "output": 0}
    ]
  }
}
```

这里 3 米内保持全量，3–7 米线性下降至零。table 也可选 exact（只接受列出的点）或 floor（取前一档）；polynomial 用从常数项起的 coefficients、minimum / maximum 定义域。exponential 使用正数 base，计算 base^input，同样要求 minimum / maximum 与 boundary。指数曲线可表达连续弹跳衰减，避免有限表格在末项停止衰减；溢出拒绝，浮点下溢到 0 允许。cosine 按弧度计算 cos(input)，要求 minimum / maximum 与 boundary；度数或属性值需先显式换算输入。boundary = error 拒绝超出定义域，clamp 使用最近端点；所有结果仍须满足 damage、radius 等消费方约束，不自动把非法负伤害改成零。

完整 [radial_falloff.json](../common/src/test/resources/effects/radial_falloff.json) 展示从事件读取目标上限、排除施加者、以事件受害者为中心、按距离决定伤害。双加载器测试验证目标在查询后移动时仍使用捕获距离。演示的基础伤害和线性曲线是合成机制验证，不代表任何具体 Compendium perk 的全部数值已校准。

## 攻击修饰的取值时机

modifier 可声明 `"evaluate":"on_use"`（默认）或 `"evaluate":"on_hit"`。前者在宿主捕获攻击时绑定来源侧数值与条件；`victim` 的资源 / Buff 读数及条件仍留到实际命中时求值。后者在命中时读取当前贡献提供者的来源 / Buff。默认 provider:holder 仍从查询持有者（攻击缩放时为攻击者）收集；显式 provider:victim 才收集当前目标的对应声明。两者保留原本的 stage / group / stacking_key，在同一 Profile 中一起归约。

例如 [damage_snapshot.json](../common/src/test/resources/effects/damage_snapshot.json) 的临时增益写作：

```json
{
  "id": "live", "profile": "test:attack_damage", "stage": "empowering",
  "group": "empowering", "op": "multiply", "stacking_key": "test:live",
  "evaluate": "on_hit",
  "value": {"type": "chorus:constant", "value": 0.4, "unit": "delta"},
  "reference": "Synthetic snapshot test; not a calibrated Destiny effect",
  "confidence": "assumed"
}
```

`provider` 可选值为 `holder`（默认）和 `victim`。它选择拥有修饰的实体，不改变修饰作用域中的 self、组件或原施加者：目标冻结 Buff 仍读取该目标的冻结组件，伤害信用仍属于攻击来源。目标来源中的普通 holder 声明不会被带进攻击者查询；攻击者自己携带的 victim 声明也不会影响另一目标。两者是同一实体时，一条声明只访问一次。

victim 声明必须显式使用 evaluate:on_hit，误用 on_use 在编译时拒绝。capture_damage 会清空目标并完全跳过这类声明；命中时读取当时目标的当前状态，与已捕获的攻击者贡献共同经过 SUM / MAX 等原分组。目标在发射后被冻结、解冻或变更为另一实体时都按各自命中状态计算；缺失 victim 引用不回退查询持有者。来源、组件、affects 绑定和数值轨迹保持原语义；旧快照仍拒绝不相容的实时 Profile 布局。

例如 freeze.json 的 basic_or_glaive 使用 provider:victim，将目标冻结 +120% 放入 outgoing 的 melee/one_two_or_frozen；攻击者的 One-Two Punch 也进入同组，从而表达取高。没有把两侧所有 Buff 合并，也没有改写全局 defense_profile 的入口。独立 calculate / calculate_pipeline 同样依其显式查询中的 victim 选择提供者，始终为只读；ON_HIT 名称不使这些独立查询延期。默认原版来源不携带 D2 Profile / 分类，因此不会自动获得这些内容修饰。provider 本身不新增目标侧消费机制，consume_on_damage 的资格捕获仍遵循原有攻击者规则。

时机字段本身不会生成投射物或定时任务。JSON 可用下节的 `capture_damage`、`after` 和 `damage_snapshot` 完成捕获及延迟伤害。Java 宿主也可在释放时调用 `runtime.captureDamage(attack)`，保存返回的 `DamageSnapshot`，命中时把 `snapshot.command(targetUuid)` 提供给原生伤害来源适配或世界命令执行器。捕获要求 attack 有明确 scaling_profile，使用当前已追赶的状态；原始 target 不参与捕获。每次命中可指定不同目标，其余攻击元数据固定。未携带快照的普通 damage 仍按即时查询计算；目标防御 Profile 总是使用命中时当前状态。

快照包含旧 Profile、来源身份与部分求值后的表达式，不包含实体对象或可变装备引用。若新目录提供 on_hit 贡献，其阶段 / 分组结构必须与旧 Profile 相容；不相容会明确失败。计算轨迹逐贡献记录来源版本，不能把旧 Profile 的版本当成所有实时贡献的版本。快照不能跨 PvE / PvP 模式复用，命中时刻也不能早于捕获时刻。

当前 capture 上下文只有 incoming_damage、damage_type、伤害标签和来源身份；尚未装配的事件测量不会默认为零。自定义 Value / Condition 参与 on_use 时须实现 `snapshot` 部分绑定，返回不可变数据表达式，不能保留 Evaluation 或可变世界状态。on_release / on_tick / on_proc、其他原版投射物自动适配、完整反应规则继承、派生筛选与持久化尚未完成；这些字段不能当作已支持的 JSON 使用。

### 选择事件标签后捕获伤害

`chorus:capture_tagged_damage` 在查询攻击修饰前，将当前 EffectEvent 的 tags 与 inherit_event_tags 白名单求交集，再与 damage.tags 合并。返回与 capture_damage 相同的伤害快照结果，可以交给 damage_snapshot 或 write_damage_snapshot：

```json
{"action":{"type":"chorus:capture_tagged_damage",
  "inherit_event_tags":["example:arc_damage","example:solar_damage"],
  "damage":{"amount":{"type":"chorus:constant","value":10,"unit":"damage"},
    "damage_type":"minecraft:generic","scaling_profile":"example:outgoing","origin":"event",
    "tags":["example:explosion"],"kill_tags":["example:weapon_kill"]}},"as":"burst"}
```

damage 是 capture_damage 的字段对象，没有内层 type；内外层未知字段均拒绝，amount / Profile / 单位校验沿用 capture_damage。inherit_event_tags 必填，可以为空；集合合并去重，无匹配时只保留静态 tags。动作不会读取 bound source tags 或 event.source.tags，也不会自动继承 precision、kill_tags、伤害类别、Profile 或 proc 拒绝列表。归属继续由 damage.origin 选择，kill_tags / proc / 非致命 / 护盾专属缩放等继续由内层声明决定。

选择发生在捕获修饰前，所以元素相关 on_use 条件看到正确的攻击标签。标签与捕获贡献一起冻结，之后来源解绑、事件改变或每目标结算不会重新挑选标签。唯一元素、缺少分类和允许的派生信用属于内容条件；通用动作不硬编码元素目录或每次只能继承一个标签。检查点增加独立 CaptureTaggedDamage 记录，CaptureDamage 和 DamageSnapshot 的原字段形状保持不变。

Chain Reaction 与 Dragonfly 均以此继承单一元素，爆炸显式标为 weapon_damage / explosive_perk_damage，保留 weapon_kill。Dragonfly 不继承原精准击杀的 precision，后续爆炸击杀因此不凭空满足精准条件；没有添加全局连锁限制。见 [Dragonfly 内容规则](d2-ruleset.md#dragonfly-的精准击杀与爆炸信用)。

### 在 Buff 中保存伤害快照

Buff 可声明 `"components":{"damage_snapshots":["attack"]}`。每个组件初始化为缺失值，和 numbers / positions / target_sets 等组件的名字不能重复。快照由动作创建，不能在数据定义里预填一份运行时快照。下列步骤要求程序已有 example:status Buff 与 example:outgoing 伤害 Profile：

```json
[
  {"action":{"type":"chorus:capture_damage","amount":{"type":"chorus:constant","value":10,"unit":"damage"},"damage_type":"minecraft:generic","scaling_profile":"example:outgoing"},"as":"captured"},
  {"type":"chorus:write_damage_snapshot","buff":"example:status","target":"victim","component":"attack","snapshot":"captured"}
]
```

write_damage_snapshot 要求目标 Buff 已存在，把原快照完整写入组件；不重新读取来源数值、不改变归属、信用、proc、Profile 或贡献取样时机。它接受 capture_damage 或 read_damage_snapshot 的类型化结果，不能接受伤害回执、数值或位置。显式写入可以覆盖，是否只在首次施加时保存由内容规则判断，不由通用组件强制。

之后另一次 Buff 事件可读取同一组件：

```json
[
  {"action":{"type":"chorus:read_damage_snapshot","buff":"example:status","component":"attack"},"as":"stored"},
  {"if":{"type":"chorus:result_flag","binding":"stored","field":"available"},"then":[
    {"type":"chorus:damage_snapshot","snapshot":"stored","target":"self"}
  ]}
]
```

read / write 的 target 默认 self；返回 stored_damage_snapshot 结果，提供 available / missing，以及仅在 available 时可读的 base_damage（damage）。未初始化组件不是零伤害；直接使用缺失快照报错。把缺失的 read 结果写入另一组件表示显式清空。Buff 不存在、未声明组件、错类型和未绑定结果也不会静默回退。

刷新 Buff 保留组件，移除后同键的新 generation 初始化为空；自己的 ended 规则读取旧 generation 的最后状态。read 绑定是不可变值，之后覆盖 / 清空 / 移除组件不会改写已绑定的值，detached 延迟体可继续使用它。写入只针对当前存在的实例，不修改 ended 快照。

储存不会把命中期条件提前结算：来源侧 on_use 操作数和原 Profile 保持冻结，目标条件、当前防御及允许的 on_hit 贡献仍在真正命中时查询。每次 damage_snapshot 都是一次新的世界动作，有各自回执；组件存储不提供一次性消费权或重放失败命令的权限。活动模式、时间与跨版本兼容检查继续沿用伤害快照契约。当前仅保存在运行时 EffectState，跨重启 / 跨维度持久化尚未实现。

[stored_damage_snapshot.json](../common/src/test/resources/effects/stored_damage_snapshot.json) 与 damage_snapshot.json 链接，验证刷新、复制、清空、独立事件、结束后同键重建、来源卸下与未知世界结果。共享世界场景验证来源增益已过期后，Buff 中的攻击仍保留原来源并使用当前目标防御，最终实际扣血。

## 捕获结果并延迟执行

以下是规则的 `do` 数组片段，需要所在程序定义 `test:attack_damage` Profile，并提供有 victim 的事件。基础值和延迟是演示参数。完整可编译程序见 [delayed_snapshot.json](../common/src/test/resources/effects/delayed_snapshot.json)。

```json
[
  {
    "action": {
      "type": "chorus:capture_damage",
      "amount": {"type": "chorus:constant", "value": 10, "unit": "damage"},
      "damage_type": "minecraft:generic",
      "scaling_profile": "test:attack_damage"
    },
    "as": "shot"
  },
  {
    "after": {"type": "chorus:constant", "value": 0.1, "unit": "second"},
    "lifetime": "detached",
    "do": [
      {
        "action": {"type": "chorus:damage_snapshot", "snapshot": "shot", "target": "victim"},
        "as": "hit"
      },
      {
        "if": {"type": "chorus:result_flag", "binding": "hit", "field": "applied"},
        "then": [{
          "type": "chorus:heal", "target": "self",
          "amount": {
            "type": "chorus:scale",
            "of": {"type": "chorus:result", "binding": "hit", "field": "effective"},
            "from": "damage", "to": "damage", "factor": 0.5
          }
        }]
      }
    ]
  }
]
```

`capture_damage` 只产生类型化结果，不造成伤害。它接受 amount、damage_type、必填 scaling_profile，以及可选 tags、kill_tags、non_lethal、origin。`damage_snapshot.snapshot` 必须指向此前的攻击快照，target 默认 victim，也可引用循环目标；返回值与普通 damage 相同，effective 是实际损失。快照可读 base_damage，但不能当作目标或目标集合使用。

after 调度完成后，原序列立即继续。delay 必须为正、有限、单位 second 且精确到微秒；嵌套 after 相对进入自身时刻计时。每次调度独立，不覆盖此前任务。生命周期可选：

- `source`（默认）：静态来源解绑 / 替换、所属 Buff generation 结束时取消；Buff 暂停冻结剩余延迟。同刻先处理到期，不执行已结束来源的任务。
- `detached`：继续执行已经调度的动作，即使来源被卸下、Buff 到期或暂停；保留原归属和捕获结果。它不让消失的 Buff 在未来重新变成可读取的当前状态。

after 保存调度时可见的结果、来源及事件内容，在未来创建新动作帧。分支、循环和嵌套延迟遵循词法作用域，内部绑定不能逃逸或遮蔽外层名称。先查询再延迟会保留已选目标和距离；在延迟体内查询则读取未来的世界。中心可使用实体当前位置，也可通过 capture_position 保留固定世界坐标。

普通 Value 默认在动作执行时求值。需要保留释放时的某个数值时，在 after 前执行 `{ "action": { "type": "chorus:capture_value", "value": 数值表达式 }, "as": "saved" }`，之后通过 `{ "type": "chorus:result", "binding": "saved", "field": "value" }` 读取。其单位保持原样；例如 Buff 规则可先捕获 by_buff_tier，再于 Buff 结束后进行延迟治疗。攻击修饰的冻结使用 capture_damage，不必手工逐项捕获。

普通付款 / 退款结果不能跨 after 帧引用，编译器会拒绝；新的延迟帧可独立付款。需要延迟退款时，先通过 retain_cost 转交有限期凭据，再在未来使用 refund_retained_cost。捕获 paid 的数值不等于保留退款权。任务保留当前程序版本，尚无活动迁移、持久化、detached 单独取消句柄；物理飞行可用文末 projectile 步骤。

## 读取事件武器与 Buff 获得回执

Target `event_weapon` 读取触发事件 source.weapon；`this_weapon` 仍读取当前规则绑定来源的 weapon。例如全局 Arc 状态统计武器命中时，`{"type":"chorus:ammo","weapon":"event_weapon","field":"capacity"}` 才会读取正在计数的攻击武器。缺少事件武器或弹药账户时明确报错，内容应先检查武器伤害资格。条件 `event_source_tag` 读取事件 source.tags；它与事件本身的 event_tag、绑定来源的 source_tag 分开。上述来源输入在 on_use 数值快照中固定，命中期输入仍按现有 on_hit 规则求值。

Buff 生命周期事实现提供通用 EffectEvent 投影。numbers 的 `requested`、`credited`、`stored_delta`、`stacks_before`、`stacks_after` 均为 count；flags.applied 来自实际回执；references 包含 buff_definition、buff_generation 与 reason。actor / source 使用该 Buff 实例保留的 origin，victim 为持有者，不把它当成每次新增层的独立施加者记录。数值来自该次已提交的变化，后续层数改变或实例消失不会改写它。

每次成功 grant 都有 `chorus:buff_gained`，包含封顶但 credit_overflow=true 的获得；stack_changed / refreshed 等事实可以来自同一笔操作，回能规则应明确只监听 gained，避免重复。跨来源统一的获得收益可由持有者的静态系统规则监听 gained，并检查 buff_definition 与 victim=self。这样其他 perk 直接 grant_buff 时也能回复能量，且不会因旧实例随后被消费而丢失已经取得的收益。Bolt Charge 的 credited × 0.025 即使用此协议。

[rolling_storm.json](../common/src/test/resources/effects/rolling_storm.json) 演示独立生产者组合：同武器击杀时，使用 has_buff_tag 判断持有者当前 Amplified，以 choose 选择基础 1 / 2 层，再用 enhanced 与 has_buff 增加“强化且 Bolt Charge 未激活”时的一层。只执行一次 grant_buff，不自行 grant_resource；状态消费者统一负责收益。片段须与 bolt_charge.json 同版本链接，装备驱动见 rolling_storm_weapon.json。Amplified 标记与武器参数是测试输入，实际内容边界见 [Rolling Storm](d2-ruleset.md#rolling-storm-与-bolt-charge-联动)。

## 显式伤害批次

`begin_damage_batch` 创建有类型的逻辑批次句柄；`damage` 和 `damage_snapshot` 的可选 `batch` 字段引用它。句柄按动作帧、指令位置和循环执行次数生成，因此同一循环体为不同目标创建的批次也不同。默认归属为 `bound`，可用 `origin: "event"` 选择触发事件的所有者；批次与伤害的所有者必须相同。

```json
[
  {"action":{"type":"chorus:begin_damage_batch"},"as":"strike"},
  {"type":"chorus:damage","batch":"strike","damage_type":"minecraft:generic",
   "amount":{"type":"chorus:constant","value":2,"unit":"damage"}},
  {"type":"chorus:damage","batch":"strike","damage_type":"minecraft:generic",
   "amount":{"type":"chorus:constant","value":3,"unit":"damage"}}
]
```

上例是同一批的两个分量。每个已确认的伤害回执仍有独立 `references.damage_id`；由该回执产生的 hit / damage_taken / shield / death / kill 事实共用 `references.batch_id`。未声明 batch 的伤害以自己的 damage_id 生成单独批次；原版观察入口也遵循此默认值。Java 宿主明确知道分量关系时，可对各 DamageCommand 使用同一个 `withBatch(DamageBatch)`。批次引用是不可解析的内部身份，不应依赖其字符串格式或跨运行时重用。

规则可用现有 `update_component` 的 `once_set` 与 `event_reference: "batch_id"` 按批计数，改用 `damage_id` 则按实例计数。集合属于指定 Buff 实例，生命周期由该 Buff 管理；长期存活的累计集合由内容显式提供到期策略，不能把根事件去重当作循环限制。需要按有效伤害计数时，再检查 effective_damage 等实测字段；免疫 / 格挡可以有 hit 事实，取消 / 失败没有命中事实。

`capture_damage` 不接受 batch。可复用数值快照不会冻结“未来所有命中属于一批”；应在 `damage_snapshot` 上绑定批次。物理投射物可以在 impact body 内创建批次，再让直击 / 爆炸等多个分量引用；同一 shot 的多个接触是否共享批次须由内容决定。句柄可像其他不可变结果一样显式传入 after / projectile；这只表达内容声明的逻辑关系，不证明两个回执在现实时间同时发生。延迟脉冲若应分批，就在每次延迟体内创建新句柄。

root、shot、服务器 tick 和批次之间不做自动映射；派生伤害默认创建自己的单例批次，正常循环与事件传播保持原行为。该机制仅提供身份及按批记账，不提供最终 batch_resolved 事件或全批原子结算。多分量共用一次性 Buff 资格由下述独立的 damage_group 显式声明，不由 batch 自动推导。完整合成夹具见 [damage_batch.json](../common/src/test/resources/effects/damage_batch.json)，它不是完整 Bolt Charge。

### 单个集合凭据到期

条件 `chorus:set_contains` 按字面 entry 查询集合；`chorus:set_contains_event_reference` 从当前事件 references 的指定键取得身份后查询：

```json
{"type":"chorus:set_contains_event_reference","buff":"test:pending_fire",
 "component":"origins","event_reference":"origin_source","target":"self"}
```

两者只读当前来源所绑定的 Buff 实例，不在其他持有者、武器或来源实例中寻找匹配。target 默认 self；集合须在 Buff 的 components.sets 中声明，实例不存在或已到期返回 false。事件引用缺失 / 空白则明确报错，不以 false 隐藏错误输入；身份写入可复用 `update_component.once_set`，删除使用下述动作。快照时 self 等来源侧查询冻结为布尔值；victim 查询保留目标侧的实时集合，但立即捕获事件身份字符串，后续命中不能换用另一个事件引用。两种条件及捕获后的表达式可序列化，不保留执行闭包。这些集合仅服务内容声明的关联或去重，不限制合法事件循环。

`chorus:forget_set_entry` 从指定 Buff 的已声明集合删除当前事件引用指向的一项：

```json
{"after": {"type": "chorus:constant", "value": 3, "unit": "second"}, "lifetime": "source", "do": [
  {"type": "chorus:forget_set_entry", "buff": "chorus_d2:triple_tap_receipts", "component": "damage_ids", "event_reference": "damage_id"}
]}
```

`target` 默认为 self，并按该 Buff 定义及绑定来源计算实例键。返回 before / after 集合大小（count）和 applied 标志；凭据已删除或 Buff 已到期时 applied=false，不重新创建状态。未声明的集合在链接时拒绝；事件缺少非空身份则在执行时拒绝。其他集合、数值、引用、位置和快照保持原值。它只处理当前找到的实例；需要限制代际时，应使用 Buff 绑定的生命周期或来源卸载清理。

`after` 保留原命中事件，因此延迟删除的是该次 damage_id。Triple Tap / Fourth Time’s the Charm 只在 `update_component.once_set` 接受新身份后刷新凭据 Buff 并安排一次三秒清理：计数达到门槛后归零，去重凭据仍保留；持续射击时集合只包含最近三秒的身份，卸下词条取消来源任务并删除状态。三秒是这组内容的回执保留政策，超期重放不在保障范围，也不会禁止新伤害形成的合法效果循环。

## 保留攻击释放时的反应规则

来源规则可声明 `binding: "origin_bundle"`，默认值是 `current_owner_bundle`。默认规则在处理事件时使用仍绑定的来源；origin_bundle 只从该攻击保存的来源选择中执行。两者共用既有广度优先队列，同一来源的同一规则不会重复加入。

```json
{"id":"origin_kill","on":"chorus:kill","binding":"origin_bundle",
 "if":{"type":"chorus:all","of":[
   {"type":"chorus:source_is","source":"owner"},
   {"type":"chorus:source_is","source":"this_weapon"},
   {"type":"chorus:event_tag","tag":"chorus:weapon_kill"}
 ]},
 "do":[{"type":"chorus:grant_buff","buff":"test:kill_reward"}]}
```

该片段需要相应 Buff 定义；完整合成夹具见 [reaction_binding.json](../common/src/test/resources/effects/reaction_binding.json)，不代表任何 D2 perk 已校准。

`capture_damage` / `CompiledEffects.captureDamage` 同时保存数值快照与不可变 ReactionSnapshot：攻击所有者、包含 origin_bundle 规则的已绑定来源、各来源的身份 / 武器 / 技能 / 强化标签，以及完整定义目录。来源解绑或同一插槽改装不改写旧选择，飞行途中新增词条不会进入旧攻击；空选择也被保留。这里只捕获攻击所有者的静态来源，不捕获 Buff 的组件或活动层数。规则条件在命中时求值，只有来源身份和标签固定；检查当前生命、状态、在手或在线资格仍须由内容明确声明。

普通受管 damage 在发出请求前捕获这份选择，damage_snapshot 沿用释放时的选择。原版伤害观察入口在 describe 时捕获即时选择，宿主不必为普通原版命中自行预处理。外部宿主若自己发出 DamageFacts，应先调用 prepareReactions；单独创建一个未带反应快照的 EffectEvent 不会补出 origin_bundle 规则。身份选择不自动授予 weapon_kill 等信用，也不改变数值修饰的 on_use / on_hit 语义。

当前 origin_bundle 只允许 source 作用域，且事件限于 hit / damage_taken / shield_damaged / shield_broken / death_prevented / death / kill；Buff 作用域和其他事件在加载时拒绝。数值快照里的反应选择不可在命中时替换。受管命令在世界执行前校验目录，非空来源选择必须与当前完整程序相等，不能只凭同一个 version 字符串放行；多版本目录并存与迁移仍待实现。没有捕获规则的纯数值快照仍可使用原有跨目录数值路径。

反应产生的新 damage / capture_damage 默认重新捕获当前来源，不自动继承父反应选择；来源的派生继承和丢失继承条件尚未实现。独立的 proc 排除与继承见下一节。反应里的 after 仍遵守自身 SOURCE / DETACHED 生命周期，来源已卸下时继续执行需显式 detached。运行时停止或替换仍停止旧物理飞行，保存反应规则不等于恢复了飞行实体。未知世界结果保留待确认操作，不重放已提交的扣血或治疗。

## 显式排除后续触发

规则可声明 `proc_key`，伤害动作可声明独立的 `proc` 策略。默认不排除任何触发；规则没有 proc_key 时不受该筛选约束。键是内容约定的命名空间 ID，可以让同一效果的多个来源规则共用一个键；本程序没有接收者的键也允许存在，供后续内容组合使用。

```json
{"id":"discharge_probe","on":"chorus:hit","proc_key":"chorus_d2:bolt_discharge",
 "do":[{"type":"chorus:play_cue","cue":"test:discharge"}]}
```

上面只是接收探针，不是完整 Bolt Charge。Jolt 等明确不能触发放电的伤害可以声明：

```json
{"type":"chorus:damage","target":"victim","damage_type":"minecraft:generic",
 "amount":{"type":"chorus:constant","value":2,"unit":"damage"},
 "tags":["test:chain"],
 "proc":{"deny":["chorus_d2:bolt_discharge"]}}
```

2 点伤害为合成示例。deny 只拦截匹配 proc_key 的规则，不取消伤害，不删除 hit / kill 等事实，也不更改 weapon_kill 等信用或数值 Profile。当前来源、捕获的 origin_bundle 来源和活动 Buff 的规则都在求值条件之前检查此策略；没有对应键的普通监听照常执行。事件标签或规则本地 id 恰好与排除项同名，不等于声明了 proc_key。

`damage` 与 `capture_damage` 的 proc 支持 `inherit: "fresh"`（默认）或 `"event"`。fresh 只使用本动作 deny；event 将触发事件的排除集合与本动作 deny 取并集，不解除父排除。是否继承与 `origin: bound / event` 的伤害归属选择相互独立。需要清除旧排除时显式选择 fresh，再列出该新伤害自身的排除项。

capture_damage 在捕获时固定已解析的 ProcPolicy，after / projectile / damage_snapshot 沿用它；命中时不能重写。DamageFacts 将它带到命中、承伤、护盾、死亡保护与死亡 / 击杀事实，原版宿主可通过 DamageCommand.withProc 提供策略。emit / schedule 的事件上下文可保留策略；新的伤害动作仍遵循自身 inherit。Buff 自身长期保存父策略、状态变形时选择性继承等尚未接入。

这是效果定义的排除表，不是循环控制。没有深度上限、祖先规则黑名单或每 root 一次限制；[proc_policy.json](../common/src/test/resources/effects/proc_policy.json) 的测试允许同一规则在同一 root 反复伤害，直到世界确认目标已死亡。未知回执保留 pending，不补造事实或重放。完整 Jolt 联动见 [规则集](d2-ruleset.md#jolt-的归属与施加顺序)。

## 伤害回执时消费 Buff

下一击增益可以在 buff 上声明 `consume_on_damage`。`damage` / `damage_snapshot` 发出世界请求前，只读捕获当前符合条件的 Buff 键与 generation；对应回执完成时，先消费同 generation 的层数，再执行同一动作体的下一条指令。hit 等后续反应仍走既有广度优先队列，不为此更改全局事件顺序。

```json
{
  "definition":{"id":"example:next_melee","version":"v1","duration":3},
  "bundle":"example:next_melee",
  "consume_on_damage":{
    "when":"hit","stacks":1,
    "if":{"type":"chorus:event_tag","tag":"chorus:melee_damage"}
  }
}
```

`when=hit` 遵从 hit 契约，包含免疫 / 格挡，但不包含 CANCELLED / FAILED；`effective_damage` 要求 HP + Chorus 护盾 + Absorption 损失大于零。stacks 默认 1，必须为正整数。资格条件在请求前、该 Buff 的作用域中求值；只选择攻击所有者的活动实例，遵守 affects 和暂停状态。结果不满足条件时保留 Buff，未知结果保持 pending 并停止，不能推断为消费或自动重试。原 Buff 已结束并以新 generation 重新施加时，旧回执不能消费新实例。

声明该策略的 Buff，其数值 modifier 必须使用 `evaluate:"on_hit"`。查询与 capture_damage 不消费；实际发出的每条 damage_snapshot 重新捕获资格，以免一份快照永久复制已消费的增益。同一动作体的两个串行世界伤害会分别看到消费前与消费后的状态，示例见 [damage_consumption.json](../common/src/test/resources/effects/damage_consumption.json)。

安装 MinecraftEffectRuntime 后，普通原版 hurt 也自动捕获资格并按真实回执消费；默认 NativeSource 只提供原版伤害归属，不从手持物品猜测 D2 近战、技能或武器信用。宿主须明确提供正确标签与 Profile，才能匹配相应内容。独立纯引擎宿主仍须配对调用 prepareDamage / BuffConsumption.finish。

原版回调里的嵌套攻击采用进入顺序预留：外层尚未返回时，独立子攻击只能读取扣除待定消费额度后的攻击侧 Buff；三层、每击消费一层时，父攻击按三层计算，子攻击按两层计算。预留本身不改动 BuffStore、不发生命周期信号，也不隐藏接收方的防御盾层。取消 / 失败释放额度；effective_damage 的零损失同样不消费。父攻击后来取消，不追溯增强已经执行的子攻击；释放的额度供后续攻击使用。这是本引擎的宿主执行顺序策略，未声称原作对所有嵌套回调采用同一策略。

每个成功返回的原版调用先把确认的 Buff / 护盾 / 攻击组变动提交到边界内的临时状态，再供后续调用读取；纯反应等待最外层 hurt 返回。CombatCommit 在纯引擎继续前一次性核对并合入这些变动；DamageReceipt.consumptionFacts 标记已经处理的消费，避免受管 Action.complete 再扣一次。命中事实仍先于对应消费生命周期事实，预留不限制合法连锁。

异常会保留已知的原版回执、确认状态、生命周期事实及尚未返回的预留，停止派生而不重放。世界适配器在原版伤害返回后抛错时，纯操作仍 pending；Failure.committedCombat 可证明原版入口已经确认的消费，不能把它混同于正常完成了纯操作。并行宿主、跨运行时恢复与全组原子世界事务尚未实现。

## 一次攻击共享 Buff 资格

Buff 的 `consume_on_damage.sharing` 默认为 `damage`，逐条伤害消费；显式设为 `group` 后，绑定同一个攻击组的合格分量可共享该次资格。未绑定组的伤害仍逐条消费。`begin_damage_group` 只建立有限寿命的身份，不冻结 Buff，也不预扣层数。

```json
[
  {"action":{"type":"chorus:begin_damage_group","lifetime":{"type":"chorus:constant","value":1,"unit":"second"}},"as":"attack"},
  {"type":"chorus:damage","group":"attack","amount":{"type":"chorus:constant","value":5,"unit":"damage"},"damage_type":"minecraft:generic","scaling_profile":"example:melee","tags":["chorus:melee_damage"]},
  {"type":"chorus:damage","group":"attack","amount":{"type":"chorus:constant","value":5,"unit":"damage"},"damage_type":"minecraft:generic","scaling_profile":"example:melee","tags":["chorus:melee_damage"]},
  {"type":"chorus:end_damage_group","group":"attack"}
]
```

此例需要已有 example:melee Profile 和 sharing=group 的 Buff。第一段请求捕获当时符合条件的 Buff 实例，满足 when 的回执确认后消费，并把该实例的资格保存到组中。后续同组分量读取保留的来源、层数、tier 和组件，仍按各自标签、目标及当前其他状态求值。已消费的 Buff 不会被重新放回 BuffStore，也不会重新注册其事件监听器；无关攻击和普通状态查询看到正常的当前状态。

共享按 Buff key 记录：旧攻击组保留旧 generation，新施加的同 key Buff 不叠入该组，也不被后续成员继续消费。多层 Buff 仅在首个合格回执扣声明的 stacks；该组的后续成员仍使用消费前的层数。取消 / 失败不取得资格，effective_damage 策略下零损失也不取得；此时独立攻击仍可先消费 live Buff。未知回执保留 pending，之前已确认的消费和资格不回滚，不自动重放。

`damage_snapshot` 也接受 group，但 capture_damage 不保存组；同一数值快照可用于多个独立攻击。group 与 batch 可分别绑定，前者决定共享资格，后者决定内容的按批计数。每段仍有独立 damage_id、伤害 / 死亡事实和 proc 策略；事实的 references.attack_group 仅为权威归属标识，不自动授予派生动作成员身份，正常效果链照常执行。

组的 origin 默认为 bound，也可选 event；成员的伤害 owner 必须一致。lifetime 为正的 second，区间半开；end_damage_group 可提前关闭，截止时自动清理。关闭后再发出成员伤害会明确失败，因此内容应让有效期覆盖所有预期分量。句柄可随显式 after / projectile 绑定传递，已确认的资格独立于原 Buff 后续到期 / 收枪；完整攻击行为由内容决定，不能据此推断所有 D2 技能都应共用资格。

[damage_group.json](../common/src/test/resources/effects/damage_group.json) 及纯核心 / 双端世界测试覆盖直接与快照伤害、独立组、重施加、多层、目标动态条件、盾层 Profile、延迟、关闭 / 到期及未知结果。原版同组嵌套成员可使用同一待定资格，对独立攻击只预留一次成本；首个成功成员确认后，其他成员使用已保存的资格。归组必须由宿主显式传递同一 Handle，不从回调层级、root 或 batch 推断。该机制不合并世界动作，全组原子事务和跨运行时恢复尚未实现。

## 选择派生动作的来源

`damage`、`capture_damage` 和 `heal` 接受可选 `origin`：

| 值 | 归属 |
| --- | --- |
| `bound`（默认） | 当前规则绑定的静态来源，或 Buff 保存的施加来源 |
| `event` | 当前触发事件的完整 source，包括 owner、source、weapon、ability 和来源标签 |

例如甲施加的状态被乙的攻击触发时，状态规则可明确让连锁伤害归乙：

```json
{
  "type": "chorus:damage", "origin": "event", "target": {"binding": "neighbor"},
  "amount": {"type": "chorus:constant", "value": 4, "unit": "damage"},
  "damage_type": "minecraft:generic", "scaling_profile": "example:chain_damage",
  "tags": ["example:chain_damage"], "kill_tags": ["example:chain_kill"]
}
```

此片段要求已有 neighbor 循环绑定和 example:chain_damage Profile；4 点只是演示数值。完整可执行示例见 [action_origin.json](../common/src/test/resources/effects/action_origin.json)。

origin 只选择新世界动作的来源，不重新绑定规则。self 仍为原 Buff 持有者，source_owner、by_stacks、component、enhanced 等仍按原规则作用域解释；target 和范围查询的 relative_to 也独立指定。需要对触发事件的 actor 治疗或按其阵营查询，可显式使用 event_actor；event 的 source.owner 与 actor 并不保证相同，origin:event 始终读取 source，不用 actor 猜测来源。

数值 Profile 随新 DamageCommand 的 source.owner 收集合格来源；因此换归属可能改变出伤修饰。事件来源带着某把武器的身份，不会自动获得 weapon_damage / weapon_kill：新动作的 tags、kill_tags 和 scaling_profile 仍各自声明，不复制触发事件的伤害信用。真实原版攻击者还需世界适配器由 command.source 构建 DamageSource，内置管理运行时已经这样映射。

capture_damage 在捕获时选择 origin，并冻结该攻击者的 on_use 数值来源；之后 damage_snapshot 沿用快照归属，不接受 origin 重写。after / schedule 保留原触发事件，延迟体中的 origin:event 使用这份事件来源，后来的其他攻击不会替换它。只有明确的环境来源可以保留空 owner；没有可用 EffectEvent 时会报错，不回退到施加者。当前尚不支持任意实体重归属、逐字段混合来源或引用组件内的来源选择。

## 命中时提供距离等数值

攻击快照可以在来源卸下后继续使用当时的加伤，同时在每次命中时读取不同距离。在 for_each 的目标绑定 `target` 和此前捕获的 `shot` 作用域内：

```json
{
  "type": "chorus:damage_snapshot", "snapshot": "shot", "target": {"binding": "target"},
  "impact": {
    "distance": {"type": "chorus:result", "binding": "target", "field": "distance"}
  }
}
```

`impact` 是字段名到 Value 的映射，普通 damage 也接受。字段在发出命令时求值一次。distance 的含义来自这里的目标查询结果，并非引擎保留的自动测量字段；Java 宿主可用 `snapshot.command(target, new ImpactData(...))` 提供同类输入。

Profile 修饰通过下列表达式读取它：

```json
{"type": "chorus:impact_number", "name": "distance", "unit": "meter"}
```

将它作为 curve 输入，再把输出 delta 放进声明的 falloff 阶段，就能在“平加 → 加伤分组 → 衰减”的顺序中计算，而不必改写已冻结的基础伤害。impact_number 在 on_use 快照中保留为表达式；来源操作数仍冻结，来源之后消失不会删除该曲线。命中条件中的 impact_number 同样延后；on_hit 修饰和当前防御 / 盾层查询也能读取这份输入。完整可执行例见 [impact_snapshot.json](../common/src/test/resources/effects/impact_snapshot.json)，其中 3–7 米线性曲线和基础数值均为合成验收参数。

命中事实保留 impact，后续规则仍用 impact_number 读取；event_number 则读取实际事实的 effective_damage 等数值。两个通道互不覆盖。emit / schedule / after 保留原测量，后续需要新距离时须重新 select_targets 并显式映射。capture_damage 不接受 impact；它捕获的是使用时攻击，命中数据在 damage_snapshot 时提供。

字段必须非空，测量带明确单位，缺失或单位错误会使结算失败，不默认为零。现阶段尚无完整字段定义注册表或自动精准 / 碰撞测量；内容及宿主负责提供一致的字段。

## 来源标签与条件数值

`source_tag` 条件检查绑定来源的标签，包括静态 EffectSource.tags 和 Origin.tags；Buff 来源读取授予时保存的 Origin。它与读取本次事件的 event_tag 分开，不能用本次命中标签冒充装备的类别或强化状态。

`by_source_tag` 将来源标签映射到 Value，各分支必须同单位，运行时必须恰好匹配一个；无匹配和多匹配都明确失败，没有隐含默认档位。其他未列出的来源标签不影响匹配。

```json
{
  "type": "chorus:by_source_tag",
  "values": {
    "example:bow": {
      "type": "chorus:enhanced",
      "base": {"type": "chorus:constant", "value": 3, "unit": "count"},
      "enhanced": {"type": "chorus:constant", "value": 2, "unit": "count"}
    },
    "example:smg": {"type": "chorus:constant", "value": 14, "unit": "count"}
  }
}
```

`choose` 是返回数值的条件表达式，可用于动作参数或修饰值；两个分支均做加载校验，但运行时只求选中的分支。if 使用已有 Condition，then / else 使用已有 Value，支持互相嵌套。

```json
{
  "type": "chorus:choose",
  "if": {"type": "chorus:source_tag", "tag": "example:empowered"},
  "then": {"type": "chorus:constant", "value": 1.2, "unit": "multiplier"},
  "else": {"type": "chorus:constant", "value": 1, "unit": "multiplier"}
}
```

数值快照会固定来源标签选择。choose 的条件若已固定，只捕获选中分支；若仍依赖 victim / impact，则保留条件并冻结两侧的来源操作数，目标值到命中时求值。它不会从世界补读缺失字段；未选中的运行时分支不求值，加载时仍必须类型正确。

[kinetic_tremors.json](../common/src/test/resources/effects/kinetic_tremors.json) 用这些表达式配合计数组件、位置 / 攻击快照和 after 编码动能震颤的部分内容。测试宿主提供武器类别和 `chorus:target_rank/minor|elite|miniboss|boss|player` 标签、直击信用与实例身份；完整缩放、衰减、装备装配和待核对策略见 [实现记录](engine-implementation.md#kinetic-tremors-的当前内容边界)。

## 装备展示协议与最小配装页

Fabric / NeoForge 客户端默认按 K 打开独立配装页，可以在控制设置中改键。左边是规则集声明的装备槽，右边是主背包；选择两边后交换，或将装备取回第一个空背包槽。持握 / 收起只更改已实现的 drawn 状态；移槽先选源，再选目的槽。关闭页面停止订阅，刷新重新建立会话。页面不暂停游戏。

`EquipmentPayloads` 在 common 定义三种 v2 payload，加载器只负责注册与发送。三个消息 ID 一起版本化，旧客户端不会误读新字段；未声明 v2 通道时页面入口显示不可用：

- `equipment_visit_v2`：打开 / 关闭指定页面实例的订阅。
- `equipment_request_v2`：页面 id、服务端会话 token、展示序号及 swap / draw / stow / move / inspect / configure。configure 只携带完整插槽→选项 ID 映射；没有物品、物主、原型、数值参数、授权或效果定义输入。
- `equipment_view_v2`：页面 / 会话身份、玩家 / 维度、展示序号、处理结果和完整展示快照。包含装备 ItemStack 组件、drawn / revision、主背包、可用槽、运行状态、presentation / version，以及当前查看的一件装备的可选改装目录；不发送整个解释器状态或持有者授权集合。

服务端以真实连接玩家为所有者，只在服务器线程处理；核对会话、序号、实际物品 / 组件、规则运行时对象及状态。背包变化或同版本运行时替换也会令旧展示过期。每次处理过的请求都推进展示序号，包括拒绝和空操作，因此旧请求不能再次交换；页面从服务端返回值重建，不先移动本地物品。另一个容器打开或鼠标持有物品时拒绝操作。运行时缺失、定义不可用或效果失败仍展示物品，并沿用服务端已有的取回规则。

只给正在查看的玩家同步，tick 比较完整内容后有变化才发送；页面关闭、离开维度、玩家对象移除或服务器停止时清理订阅。刷新使用新页面 id，旧页面回包被忽略。网络集合与字符串有长度上限；它不是仓库、交易或多人共享容器协议。

选择已有装备后点击“改装”，inspect 请求只订阅该装备的目录，不改变物品、装备 revision 或效果。服务端从真实实例参数解析容量，返回插槽是否可编辑/必选、每个选项的效果标识/费用/是否解锁；效果标识仅用于名称和图标展示，不携带可执行 DSL。固定词条显示为只读，锁定选项显示费用及锁定提示；可选插槽有清空入口，多预算分别展示。单件目录最多 64 插槽、合计 512 选项、16 预算，本地键最多 128 字符；未知原型、损坏参数或超出展示上限时不提供目录，原物品仍可取回，核心配置容量不受这些 UI 上限约束。

客户端只在本地编辑完整选择草稿，可先暂时超额再重分配；费用按静态选择计算，未激活效果照样占用。未通过本件费用/资格/只读检查时禁用“应用”；客户端预览不能替代整个 Loadout 的服务端校验。configure 必须针对当前会话已查看的装备，并使用准确的展示序号；服务端复用 PlayerEquipment.configure 原子提交。返回/还原不修改物品，相同选择不提交。任何新的权威快照都会重置草稿，包含物品/授权变化和规则重载；拒绝、stale 或未知世界结果显示服务端实际状态，不自动重试。其他装备仍非法时，最终整套校验仍会拒绝，需要先取回或修复相应物品。

改装页沿用主题配色，窄窗口下插槽、选项和多预算分别分页；按钮叙述包含名称与费用。主题可增加 `effect_icons`，把效果 ID 映射到本地纹理资源（如 `chorus_d2:unflinching_solar` → `chorus_d2:textures/gui/perks/unflinching_aim.png`），缺失纹理保留文字。效果名称使用 `effect.<namespace>.<path>` 翻译键，缺失时显示当前选项名。资源包只控制展示；PNG 来自已有 Compendium 素材归档，七种抗抖选项复用原表整理的抗抖图标。

装备目录可添加可选字段：

```json
"equipment": {
  "presentation": "chorus_d2:equipment",
  "slots": [],
  "items": [],
  "limits": []
}
```

客户端将其解析为 `assets/chorus_d2/ui/equipment.json`。当前仅支持 `equipment_two_panel` 模板、ARGB 颜色和 slot_order；布局不能注入动作或修改装备合法性。槽位文字使用 `equipment.slot.<namespace>.<path>` 翻译键，缺失时显示整理过的路径。缺失 / 非法资源回退到通用主题。跨程序片段可以复用相同 presentation，冲突声明拒绝链接。

这是可操作的基础配装页。D2 三维人物预览、装备详情 / 属性比较、技能选择、Buff HUD、拖放 / 快捷移动、完整无障碍和手柄导航仍待实现；D2 配色和槽位翻译位于 `assets/chorus_d2/`，不是完整原作界面复刻。

## 技能选择与施放入口

完整程序的 `abilities` 声明当前支持的即时动作技能。每个定义指定 id、槽位、可选成本、施放条件、标签、施放数值参数、on_use 动作，以及可选的选择期 effects。它复用已有 DSL，可授予 Buff、查询目标、治疗 / 伤害及启动 detached 延迟动作；通用物理飞行已由文末 projectile 步骤接入；D2 专用投掷物、移动技能和持续引导等种类尚未实现。下面是结构完整的合成例子，数值不是命运 2 校准结果：

```json
{
  "version": "example-1",
  "resources": [{"id": "example:grenade", "capacity": 2, "initial": 2, "base_rate": 0.1, "thresholds": [1]}],
  "abilities": [{
    "id": "example:heal", "slot": "example:grenade",
    "cost": {"resource": "example:grenade", "amount": {"type": "chorus:constant", "value": 1, "unit": "charge_fraction"}},
    "tags": ["example:grenade"],
    "parameters": {"healing": {"value": {"type": "chorus:constant", "value": 4, "unit": "damage"}}},
    "on_use": [{"type": "chorus:heal", "amount": {"type": "chorus:event_number", "name": "param.healing", "unit": "damage"}}]
  }]
}
```

cost.amount 与每个 parameter.value 都是类型化 Value；各自可另给 profile，输入 / 输出单位必须保持不变。参数与成本在接受施放前统一查询当前持有者的来源 / Buff 修饰，固定为本次施放的数据。参数之间没有隐式求值依赖；param.<name> 是给后续动作读取的已解析测量。默认 `cost_from: definition` 时，省略 cost 表示无账户成本；amount = 0 表示使用已声明账户的免费施放，仍有 paid = 0 的成本回执。转换技能可以显式选择下述基础选择成本。

### 查询已选技能的标签

`chorus:selected_ability_tag` 查询指定持有者已提交的基础技能选择：

```json
{"type":"chorus:selected_ability_tag","slot":"chorus_d2:super","tag":"chorus_d2:solar","target":"self"}
```

`slot` 和 `tag` 必填且须为合法 ID；`target` 默认为 self，也可使用既有目标或当前作用域已绑定的目标。读取选中定义的 tags，不读取施放时的条件替换、事件信用、其它技能槽或来源标签，也不要求技能声明常驻 effects。合法但未选择的槽返回 false；无效 ID、未知字段、未绑定目标，以及损坏的已选定义不能静默视作匹配。求值需要完整的编译程序目录。

在 `on_use` 攻击快照中，攻击方选择冻结，victim 选择留到命中时查询；绑定局部目标不能带入攻击快照。独立延迟规则在执行时读取当前选择，仍保留原动作来源。程序与领域存储往返保留条件和基础选择；这不等于宿主技能 NBT 或完整物理世界重启已实现。`selected_ability.json` 的纯核心及共享世界场景验证选择隔离、替换施放、延迟和快照，Harmonic 是该接口的实际内容消费者。

### 查询已选技能的接收账户

`selected_ability_resource` 将指定资源 ID 与当前基础技能的接收账户比较；`selected_ability_event_resource` 从事件引用读取待比较 ID。后者的 reference 默认为 resource，适合资源速率 / 能量查询，不需要给每个技能写一份资源名称分支：

```json
{"type":"chorus:selected_ability_resource","slot":"chorus_d2:class","resource":"example:class_energy","target":"self"}
{"type":"chorus:selected_ability_event_resource","slot":"chorus_d2:class","reference":"resource","target":"self"}
```

两者先读取目标持有者已提交的基础选择，再按现有 `energyResource()` 约定优先读取 recharge_resource，其次 cost.resource。空槽、无接收账户、未知 / 缺失 / 格式错误的事件资源引用均为 false；静态声明的 slot / resource 必须为合法 ID，reference 必须为合法本地名称。不依据余额、施放替换或事件来源猜账户，也不追随 cost_slot / cost_from 的支付委托。共享同一接收账户的两个技能均能匹配。此条件只比较所选账户的资源定义 ID，不代替持有者授权或付款资格检查，不初始化、修改或补满账户。

资源速率查询按当前时间段重新计算；换槽后可让增益跟随新账户，已保留的旧账户是否继续基础恢复由原来的资源定义决定。`selected_ability_resource.json` 验证额外基础再生率：基础 0.1/s、属性加速 100%、额外基础加成 300% 时为 `0.1×2 + 0.1×3 = 0.5/s`，不能算成 `0.1×2×4`。这是通用组合夹具；实际 Winter’s Shroud 的 partial 参考内容现另见 `winters_shroud.json` / `class_regeneration.json`。使用 base_percent 时 percent_of 必须指向原基础阶段，不能误指经过属性缩放的阶段。

`invoke_bundle` 会将调用事实加入队列，不会插入当前动作栈立即执行。如果开始反应需要在技能位移前捕获目标，应在该反应中完成查询，再向共享施加流程传递已选择目标。凛冬之幕的两种子职业各自声明开始反应，复用同一 Slow / Freeze 定义；不能为了共享一段 pulse 声明而把空间取样推迟到技能动作之后。

攻击快照先把事件引用解析为固定资源 ID，再沿用目标求值时机：攻击方选择冻结，victim 选择保留到命中时读取，后续事件不会替换已捕获的资源 ID。独立 detached 延迟在执行时读取当前选择，但保留原事件引用。两个条件都有独立存储 ID；新增形状不改写旧条件或历史检查点。

### 选中技能的常驻效果

`abilities[].effects` 是按本地键索引的来源声明。基础技能选中后自动挂载，清除或换成另一基础技能时卸载；不需要测试事件创建一个永不结束的 Buff 才能启用回能 / 属性修饰。下面片段给已声明的技能增加来源，目标 Bundle 也须在同一程序或 imports 中定义：

```json
"effects": {
  "regeneration": {
    "bundle": "example:selected_regeneration",
    "tags": ["example:grenade_selection"],
    "parameters": {"power":{"value":1,"unit":"delta"}}
  }
}
```

`example:selected_regeneration` 是 SOURCE Bundle，声明 `"parameters":{"power":"delta"}`，可用 `chorus:source_parameter` 驱动修饰、规则或连续恢复。effects.parameters 是定义里的固定 Measure；技能顶层 parameters 是每次接受施放时计算的 Value，两者的求值时间和作用域不同，不隐式互通。效果标签与所属技能的标签合并；origin.owner 是选择者、origin.ability 是基础技能 id、origin.weapon 为空，origin.source 为这项常驻来源的身份。

选择变更先验证旧装配及来源投影、初始化缺失的资源池，然后原子提交新技能选择、资源与整组来源。队列顺序是新资源的 initialized 事实、全部旧 source_detached、全部新 source_attached、abilities_changed；这些规则执行时都能看到最终的完整选择和来源。相同选择重提不会重新初始化或刷新计时器。以 holder / slot / ability / effect 键构成的 `ability/` 身份由选择事务独占，bind / unbind / replaceSources 不允许直接修改；损坏或多余的初始投影会被拒绝。

普通的 `source_attached / source_detached` 和 `own_source` 可表达初始化 / 清理。命名计时器与 source 生命周期 after 在选择卸载时取消，detached 动作、已捕获的伤害修饰和 origin_bundle 反应继续保留原来源。清理使用旧参数，但全局状态已经是新选择；世界动作未知失败保留已提交选择和待确认回执，不重放 attach。

这组效果由基础 AbilityLoadout 决定：ability_overrides 临时解析出另一个施放定义，不挂载替代定义的 effects，也不卸载原选择。技能的施放 if 仅约束施放；常驻效果若有自己的生效条件，应写在 Bundle 的条件里。多个玩家和多个技能槽使用独立来源身份，数值是否叠加仍由 Profile 的 family / group 决定。

资源账户与选择期效果的生命周期分开：移除选择不会删除 / 补满账户，卸载的来源不再贡献恢复倍率，账户仍按剩余来源和资源基础恢复率推进。角色暂停 / 死亡 / 离线政策、技能选择跨维度与 NBT 持久化未由本字段补齐，仍由后续宿主装配定义。合成 `ability_effects.json` 验证这条通用链路；D2 的 Arcbolt / Threaded Spike 回能修饰已迁入选择期来源，参见下述 D2 装备属性输入。

### 施放时的条件替换与入口

效果包可声明条件替换，source 与 buff 作用域都支持：

```json
"ability_overrides": [{
  "id": "sprinting_variant", "slot": "example:grenade",
  "ability": "example:heal", "replace_with": "example:other_heal", "priority": 10,
  "if": {"type": "chorus:event_flag", "name": "sprinting"}
}]
```

replace_with 必须在链接后的程序中存在且属于同槽；可选 ability 匹配本级开始时的当前定义，不填写则匹配该槽。按优先级从低到高解析，每级先收集全部符合条件的候选：不同目标冲突则整次施放拒绝，同一目标允许多个来源共同声明；本级替换完成后再解析下一级。只读取施放者的来源与仍有效、未暂停且适用的 Buff。基础 AbilityLoadout 不被替换结果覆盖。

最终技能定义的 `cost_from` 决定支付来源：

| 值 | 成本声明 |
| --- | --- |
| `definition`（默认） | 使用最终技能自己的 `cost`；省略表示无账户成本，保持原有语义 |
| `base_selection` | 使用本次请求开始时基础选择的 `cost`，包括 resource、amount 和可选 profile |
| `previous_definition` | 使用最终转换前、上一实际不同技能定义的已声明 `cost`；没有前一步或前一步未声明成本则拒绝 |

例如给转换后的技能加入 `"cost_from":"base_selection"`，暮域被转换为冰炮台时消费暮域账户；直接选择冰炮台时，基础选择就是冰炮台，使用其自身声明的 cost。可以省略转换定义自身的 cost，但它被直接选中且无可继承成本时不能施放。`base_selection` 在多级替换中始终读取最初选择的声明；只有最终定义决定支付策略，各种委托均不递归解析供给成本的技能策略。

### 读取上一转换阶段的成本与身份

`previous_definition` 用于消耗已被其他来源替换的技能。例如 A（普通手雷）先变成 B（超能替代物），再变成 C（消费动作），C 可以读取 B 的已声明账户、金额与 Profile，而不支付 A 的费用。若只有 A → C 则读取 A。A → A 或 C → C 这种同身份覆盖不产生新的前一步；A → B → C → D 中 D 读取 C 的声明，不递归追随 C 的 cost_from / cost_slot。缺失前一步、前一步无 cost 均以 `NO_BASE_COST` 在参数查询前拒绝，不借用基础或最终定义的备用池；显式零金额仍保留免费支付回执。中间定义的条件、on_use 和常驻 effects 不会因此执行。

解析期间和接受事实的 `references.replaced_ability` 保存上一个实际不同定义的 ID；没有前一步时为存在的空字符串，覆盖输入同名引用。`base_ability` 始终是基础装配，`ability` 和动作来源始终是最终定义，接受事实的 `cost_ability` 则记录最终选择的费用声明身份。参数 / 最终条件可用 `{"type":"chorus:cast_ability_tag","reference":"replaced_ability","tag":"example:wisp"}` 读取该定义标签；reference 只允许 base_ability / replaced_ability，未知或空 ID 为 false。该查询读取事件引用的定义元数据，不把临时输入标签当作定义标签，不查询后来换上的技能，也不授予施放权限。可信宿主若自行构造普通事件，需要自行保证这些引用的来源；它不等同只接受规范 Started / Used 的 event_ability_tag。攻击快照会冻结此条件。

费用的数值表达式和 Profile 继续在最终动作上下文求值，保持最终技能信用；预检查和正式使用共享同一转换解析。退款只认已提交的付款回执，改选技能、移除替换源和领域检查点均不重定向原账户。CostFrom 的原双值存储枚举保留原 ID 并显式迁移，新三值枚举使用 cost_from_v2；旧 ID 下伪装的新枚举值仍被拒绝。

`base_selection` 不表示免费兜底。基础选择没有 cost 时返回 `NO_BASE_COST`，在行动门槛和技能条件之后、参数求值和支付之前拒绝；即使转换定义有自己的 cost，也不改扣另一个池。基础 cost.amount 为零则是有账户的免费支付，仍产生零支付回执。费用表达式和 Profile 使用最终施放的上下文：伤害 / 技能信用和标签属于转换后技能，references.base_ability 保留原选择；不会在算费用时偷偷切回基础技能信用。

需要从另一个输入入口消耗当前所选技能的账户时，可以声明 `cost_slot`：

```json
{
  "id": "example:air_action",
  "slot": "example:air_move",
  "cost_slot": "example:class",
  "tags": ["chorus:class_ability"],
  "if": {"type":"chorus:not", "of":{"type":"chorus:event_flag", "name":"on_ground"}},
  "on_use": []
}
```

此例仅说明支付声明，实际效果需由内容定义。`cost_slot` 必须是与自身 slot 不同的命名空间 ID；不能同时声明 cost 或非 definition 的 cost_from。它读取**同一持有者当前选中**的另一个槽位技能的原始 cost，不解析该槽的技能替换，也不追随另一条成本策略。最终调用技能的声明决定支付策略；目标槽为空或没有声明 cost 时返回 `NO_BASE_COST`，有账户且金额为零才是免费支付。这个规则只限定成本引用的含义，不限制效果事件的合法循环。

输入槽、实际技能定义、参数及攻击信用仍属于调用技能；支付只借用 resource / amount / profile，并在调用技能上下文中求值。接受事实的 `references.cost_slot` 和 `references.cost_ability` 记录实际读取的槽及成本声明技能，覆盖输入同名值；`ability_slot` 仍是本次输入槽。读取成本不会施放目标槽技能、执行它的 on_use 或产生第二组 Started / Used。若内容需要继承闪身收益，应显式组合相应效果，不能用第二次 use 模拟。

Ascension 参考定义按接受事实捕获的 cost_ability 调用共享 `chorus_d2:marksman_dodge_benefits` 或 `chorus_d2:gamblers_dodge_return` bundle。两者用类型化 delay 参数和 detached 延迟动作，普通闪身也调用同一份规则。调用不复制闪身的地面条件、水平位移或追踪规避窗口；后续换掉职业技能不改写已选收益分支，收益结算时仍按原规则读取当前装备武器 / 近战槽。Utility Kickstart 的职业技能资格改为 event_ability_tag，输入槽不再承担技能分类含义。

Marksman 共享收益先用 collect_pickups 查询当前施放者 15 米内的 `chorus_d2:special_ammo` / `chorus_d2:heavy_ammo`，再安排正 delay 的装备换弹。候选数不代表领取数；原物体奖励、私有资格与逐件派生反应仍走通用领取链路。该次领取在当前时间边界结算，后续计时边界才执行换弹，所以一微秒的最小正延迟也能消费新拾取的备弹。满备弹候选只跳过一次，延迟换弹不会重新发起收集。Ascension 的伤害中心是上升前捕获的位置，而此共享拾取查询使用上升后的当前位置；这是显式内容政策，完整原作动画时序仍需校准。

声明 cost、非 definition 的 cost_from 或 `cost_slot` 的 on_use 均有 `cast_cost` 绑定。它保存实际付款账户与实付额，refund_cost 和 retain_cost 沿用同一退款额度；延迟退款仍须显式 retain，不能直接把普通 cast_cost 跨帧捕获。改选任一槽、卸下替换来源、世界效果失败都不把这笔付款转到新账户。外部 grant_ability_energy 与自然回充仍由基础选择 / 资源定义路由，成本策略本身不重设 CES、恢复率、容量、余额或常驻 effects。`cost_slot` 不创建额外账户，也不把对 air_move 的回能自动转给 class；回能动作应明确指向实际接收槽。

存储 ID `chorus:effect/ability/ability_definition_v2` 增加 costSlot。原 ID 保留完整旧字段形状，仅用于读取并迁移为 costSlot 为空的当前定义；新存档只写 v2，历史检查点无需改写。

服务端 API：

```java
runtime.abilities(new AbilityChange(holder, before, new AbilityLoadout(Map.of("example:grenade", "example:heal"))));
AbilityUse.Receipt receipt = runtime.useAbility(player, "example:grenade");
```

选择入口是可信宿主 API；尚未提供子职业、解锁和装备约束的玩家选择校验。变更核对完整 before 及常驻来源投影，预检定义 / 槽位后整体提交选择和 effects。当前首次选择某槽时初始化该槽所有已声明候选技能会用到的资源池，已有账户只校验、不回满；清除选择也保留账户，账户继续按该运行时的资源时间轴推进；角色离线 / 暂停恢复策略尚未接入。槽内共用能量应引用同一个 resource id，不能为每个变体分别建账户后误称为同一冷却。资源基础声明仍属于固定目录，切换不会隐式重设它们；resizable 账户可通过 resize_resource 显式变更容量，有效恢复率及 CES 可通过 rate_profile / gain_scalar_profile 查询选择期来源。D2 手雷示例用零基础值、选择期基准来源和 Aspect 条件实现共用余额及空槽暂停，并用明确 SOURCE / 选择反应协调额外容量；固定回能和已付款退款仍可显式作用于保留账户。原作换装额外扣减、任意 Buff / 条件变化的自动容量投影尚未实现。

use 只接受真实玩家和槽位，校验维度、存活、非旁观及运行时健康；服务端采样 on_ground / sprinting / crouching。请求不带任意目标、施法者、技能定义或客户端运动断言。槽为空、条件不满足、替换冲突、所需基础成本缺失和能量不足返回明确结果，不发 ability_started / ability_used、不执行效果。成功时先提交实际资源扣除，再依次排入 resource_spent / resource_changed（仅有实际支付时）、ability_started、ability_used，on_use 在 ability_used 执行。

ability_started 与 ability_used 携带同一份已接受的定义、参数和成本回执。ability_started 的即时规则先于 on_use 动作体执行，适合移除“开始施放就结束”的状态；它不是取消或退款入口。资源已提交，规则若遇到未知世界结果仍遵守停机、不重放契约。它的后续派生事件按既有广度优先队列排序，并不保证先于 ability_used；需在动作体之前完成的清理应直接放在 started 的即时规则里，不能只再发一个清理事件或使用 after。

两者的 source.owner 是施放者，source.source 是独立 cast 身份，source.ability 是最终定义 id；标签来自定义和可信宿主输入。references 提供 ability / base_ability / ability_slot / cast，numbers 提供 paid 与 param.<name>，flags.free 表示未实际支付。on_use 持有本次解析结果，不因资源事件反应、切换选择或后续卸下来源而重新解析技能。

有声明成本或继承基础成本的 on_use 可通过隐式绑定 `cast_cost` 使用 refund_cost，遵循实际支付额和同一执行帧内累计认领上限；免费施放不能由退款制造能量。也可用 retain_cost 把剩余额度转交给有限期句柄，由 after / projectile 捕获；其他独立事件仍不能仅凭 paid 或 cast 字符串取得退款权。即时 on_use 动作体没有持久来源寿命；命名 timer / cancel_timer 与 source 生命周期 after 在该动作体内编译时拒绝，延迟动作必须明确 detached，或先建立拥有寿命的 Buff。已接受的世界操作失败保留扣费和待确认操作，不自动回滚或重试。

最小命令：

```mcfunction
chorus ability choose example:grenade example:heal
chorus ability status
chorus ability use example:grenade
chorus ability clear example:grenade
```

choose / clear 需要管理员权限；status / use 只操作发起玩家。运行时须先安装包含定义的程序。命令通过下节 PlayerAbilities 保存基础选择，并同时更新领域投影；status 显示活动选择、保存的配置 / revision 及 inactive 原因。手雷按键与输入网络见下节；完整子职业与技能原型、parent 继承、持续施放取消、完整技能进度与跨独立宿主资源迁移及原作参数装配继续待做。合成 abilities.json 不增加 Compendium 内容覆盖条目。

### 玩家基础技能选择的保存与恢复

`PlayerAbilities` 在原生玩家 NBT 的 `chorus:abilities` 中保存完整 AbilityLoadout 和 revision。它保存基础选择，施放时的条件替换不会改写玩家文件。可信内容宿主在校验职业、解锁及配装资格后调用：

```java
var selection = PlayerAbilities.get(player);
var loadout = new AbilityLoadout(Map.of("chorus_d2:grenade", "chorus_d2:firebolt"));
selection.configure(player, loadout, selection.revision());
```

`AbilityCodecs.LOADOUT` 的公开结构为 `{"slots":{"chorus_d2:grenade":"chorus_d2:firebolt"}}`；未知字段、非规范 ID、未知定义或不兼容槽位拒绝。一次请求携带完整选择，预检所有槽位；失效 revision 不产生部分提交。玩家内存配置、领域选择、必要账户与选择期来源先共同发布，再执行资源 / 来源反应。未知世界结果保留已提交的选择和实际写入，停止运行时，不重放附加。相同选择不消耗 revision、不重启定时器；显式空选择保留 revision，自动同步不能撤销它。实际落盘沿原版玩家保存和世界保存协议。

prepare / 规则输入自动同步已有玩家配置；未配置的玩家不会覆盖外部宿主通过 runtime.abilities 设置的选择。命令第一次编辑外部选择时接管完整选择；此后应使用 PlayerAbilities 修改，低层 API 继续供 NPC 或独立宿主使用。真实 ServerPlayer 替换保留选择，独立于装备掉落；同一维度已有的账户不会因改选、清空、重复同步或重生被重置。改选同时取消对应槽的未释放输入，即使同一 tick 切走再切回，也不能沿用旧 gesture。

缺失内容时整份选择保存为 inactive，不猜测替代技能，也不只激活一部分槽位；定义重新出现后可再次投影。存在但无法解码的 NBT 明确失败，不当作空选择。宿主归档 format 3 的 ability_owners 记录本维度核对玩家文件的责任：恢复前核对在线玩家，恢复后首次出现的离线玩家在时钟推进 / 施放前核对；错配停止运行时，不能用重绑覆盖存档状态。世界保存冻结期间也核对实际配置。

资源余额、容量、计时器及已接受工作仍由世界检查点保存。正常停服 / 重启加载玩家文件与相同世界检查点时，选择、余额和定时器继续，不重复初始化或补满。仅把玩家 NBT 放进全新的空运行时会按目录首次创建账户，不能冒充完整恢复。同一共享宿主的原生跨维度交接移动当前选择、核对责任、资源、Buff 和来源自有工作，退款保持原签发域路由；目标索引确认前不释放源责任。独立宿主仍沿用配置同步，各自账户没有自动迁移；已发射物理实体继续留在原世界。

原生验收覆盖实际玩家 NBT、真实重生、在线 / 延后登录核对、定时器与已支付余额、未知世界结果及跨维度改选。独立进程验收使用实际付费技能和原生玩家登录。职业默认目录、首次角色装配、玩家技能选择网络 / UI、跨独立宿主与物理实体迁移仍未提供。

### 按下、长按与松开

两加载器均注册可重绑定的「手雷技能」键，默认 V，发送 `chorus_d2:grenade` 槽位操作。`AbilityInputClient` 只传按下 / 松开 / 取消；持续时间由服务器运行时的逻辑时钟计算，客户端不能提供时长、施法者、最终技能、费用或目标。服务器未安装相应程序或尚未选择技能时，按键不创建默认技能。

```java
runtime.abilityInput(player, "chorus_d2:grenade", AbilityInput.Edge.PRESS, gesture);
// 后续服务器 tick 推进时钟；同一 gesture 配对松开。
AbilityInput.Receipt input = runtime.abilityInput(player, "chorus_d2:grenade", AbilityInput.Edge.RELEASE, gesture);
```

按下不支付或执行 on_use。每个玩家 / 槽位最多保留一个待松开的输入，重复按下返回 ALREADY_HELD，不重置起点；不同 gesture 的松开返回 STALE，不清掉当前输入。匹配的松开先消费记录，再进行完整替换、条件、参数和付款检查，故失败或重复松开不能再次尝试原输入。`RELEASED` 只表示已消费松开，实际施放结果在嵌套的 `AbilityUse.Receipt`；CANCEL 只清理，不施放。直接 use 命令也清理该槽待松开的输入，避免随后再施放一次。

| 查询上下文 | 标签与数值 |
| --- | --- |
| 按下及保持资格复核 | `chorus:ability_input_hold`；`input_hold_time: second` 是服务端已记录时长 |
| 匹配松开的最终施放 | `chorus:ability_input_release`；同一数值用于条件替换与施放动作 |
| 直接 use | 无上述标签；`input_hold_time = 0 second` |

保持资格先解析当时适用的替换，再检查最终技能的 action_gates；不求施放参数 / 成本、不支付，也不提前判定技能自身 if。松开重新按当前来源和 Buff 解析，按下不会冻结装备效果。按下 / 保持 / 取消目前不是规则事实；尚无按住期间的逐 tick 动作、达到阈值自动施放、引导动画或服务器回执 HUD。

基础选择切换（包括同一 tick 切走再切回）、行动限制、死亡、旁观、玩家实体 / 维度失效、断线、运行时关闭或故障会清理待松开的输入。保持相同选择不会取消。客户端打开界面或失去窗口焦点会发取消，并等待观察到按键松开后再接受新按下。清理只影响尚未施放的输入，已经生成的世界效果按自身生命周期继续。

`chorus:ability_input` 网络包包含正递增 sequence、原按下的 gesture、维度、槽位及 edge；PRESS 要求 gesture 等于 sequence。服务器按实际连接记录最新序号，在资格检查和世界操作之前消费序号；重复包不重试，过时维度不施放。服务端 tick 粒度及到包时点决定时长；尚未实现延迟补偿或客户端成功确认。未知世界结果保留已提交支付与副作用，停止运行时且不重放。

`bleak_watcher_conversion.json` 的 SOURCE Bundle 要求显式 `hold_time: second` 参数，仅在松开且正阈值达到时替换为冰炮台。缺少参数拒绝绑定，非正阈值不转换；没有该来源、短按或直接 use 均保留原选择。0.3 秒仅是测试校准，原表没有提供可确认的长按阈值。`bleak_watcher_aspect.json` includes 此来源，并在手雷槽存在能量账户时覆盖共用手雷 Profile 的基础恢复率和接收系数；三个手雷示例现支付同一账户。正式 Aspect 装配挂载与原作换装扣减仍待实现，详见 [冰炮台规则集](d2-ruleset.md#bleak-watcher-的独立炮台与五连发)。

## 技能、开火与手动换弹的行动限制

SOURCE / BUFF Bundle 可用 `action_gates` 声明禁止条件。`if` 为真表示拒绝，不填写时始终拒绝；同一 Bundle 内 `id` 必须唯一，未知字段、无效条件和未绑定结果在编译时拒绝。例如这个 BUFF Bundle 禁止新技能与开火，但不禁止手动换弹：

```json
{
  "id": "example:disabled_actions",
  "scope": "buff",
  "action_gates": [
    {"id": "abilities", "action": "ability_use"},
    {"id": "fire", "action": "weapon_fire"}
  ]
}
```

Buff 目录通过 `bundle` 字段关联上述 Bundle。`action` 支持 `ability_use / weapon_fire / weapon_reload / weapon_aim / ranged_attack / melee_attack / movement_input / jump / horizontal_motion / vertical_motion / projectile_tracking`。每条声明可选 `subject: actor | victim`，默认 actor；仅在 SOURCE / BUFF 的持有者等于查询相应身份时参与。目标侧限制不会交换事件 actor / victim，也不把第三方施加者当作持有者；条件中的 self 与参数仍属于声明持有者。BUFF 须有效、未暂停并适用于本次来源武器 / 技能。subject 所指身份为空时不匹配，不遍历其他人的声明来猜目标。各声明独立求值，任意一项拒绝就不能执行；没有可覆盖其他拒绝的 allow 优先级。例外应写入该条禁止条件，如“禁止技能，地面上的 Super 除外”；另一条禁止技能的效果仍可拒绝该 Super。

查询带 `chorus:action_gate_query` 标签，references 包含 `action` 和 `action_phase`（`start / continue / complete`），并保留对应操作的标签、字段与来源。技能在全部替换解析完成后查询，能读取最终定义标签及 `ability / base_ability / ability_slot / cast`，但此时尚未计算 `param.*`、支付能量或发布 accepted 事实。宿主技能输入提供当前原版 `on_ground / sprinting / crouching`；未观测字段不猜成 false。武器查询含 `weapon / item`；开火另有 `shot`，换弹另有 `reload / reload_step / incremental`。技能施放和原版 ranged_attack 输入带上述原版移动标志，不能假定武器查询也有。

`CompiledEffects.checkAction` 是只读查询，不发布查询事件或执行规则动作。`Decision` 保留操作、阶段、查询快照和所有命中的拒绝声明（Bundle、实例、声明 id、原来源、subject、实际 holder），按实例 / 声明排序；同一身份同时作为 actor / victim 不会重复计算同一声明。普通技能、开火和换弹 API 的 `RESTRICTED` 回执包含这个 Decision；拒绝不会支付成本、扣弹、建立射速间隔或覆盖已存在的换弹计划，也不发布 accepted 事实。基础装备 / 定义资格先检查，开火原有的射速检查也先执行。

手动换弹额外在宿主确认后的 `complete`、每次装填反应结算后的下一次 `continue` 复核限制。完成前拒绝不转移弹药；下一步拒绝保留刚完成的弹药转移、回血和其他反应。取消会清除计划及计时器，发布 `reload_cancelled`，`reason=action_restricted`；其 `ActionGate.Cancelled` payload 同时实现常规 EffectEvent.Carrier 并保留拒绝 Decision。授予限制 Buff 本身不会立即取消已接受计划；若它在下个复核边界之前过期，计划仍可继续。

技能 / 开火限制只拦截对应操作，手动换弹另复核上述边界。`reload_weapons` 等效果动作、已接受技能动作体、已飞出的投射物、DOT 和点燃连锁继续按各自内容执行；已飞出 Chorus 弹体的自动寻敌可另外用 `projectile_tracking` 限制，见下文，该限制不取消飞行或伤害。活动技能 Buff 可通过下节的批量结束动作终止，原版远程攻击接线见下节；原版近战及主动移动输入接线见下文；其他物品使用、完整 Suppression / Freeze / Suspend 资格以及客户端禁用提示尚未接入，不能把这组通用能力当作这些 D2 状态的完整实现。[action_gates.json](../common/src/test/resources/effects/action_gates.json) 是合成验收内容，不增加 Compendium 效果覆盖数。

## 原版生物的远程攻击资格

`ranged_attack` 是原版 Mob 的远程攻击入口，独立于玩家 Chorus 武器的 `weapon_fire`。可由 SOURCE 或目标持有的 BUFF 声明禁止条件；核心不识别压制或敌人等级。运行时未安装或当前程序没有这种门槛时沿用原版路径。

Minecraft 26.3 接线覆盖全部九个 `RangedAttackMob` 实现：骷髅系、溺尸、女巫、幻术师、掠夺者、猪灵、雪傀儡、羊驼和凋灵（含侧头及无目标坐标发射）；另覆盖烈焰人、恶魂、潜影贝、守卫者光束、旋风人 Shoot、监守者 SonicBoom 和末影龙扫射火球。判定在新弹体创建、发射音效、弩弹消耗或光束伤害之前进行。烈焰人近战和其他 AI 不被整体关闭。Goal / Brain 的持续检查会正常结束受限蓄力，保留原版停止时的姿态 / 冷却处理；拒绝的龙扫射转回盘旋。不会补发受限期间错过的攻击。

查询的 actor 是攻击 Mob，victim 是入口提供的目标；凋灵坐标发射没有实体目标，victim 为空。来源 owner 为攻击者，source 为原版攻击标识，weapon / ability 为空；不凭手持物猜 Chorus 信用。来源标签包含当前实体类型标签、实体自身标签和宿主明确提供的 `chorus:combatant`，不自动猜 D2 等级。事件带 `chorus:native_ranged_attack`，references 提供 `native_attack / entity_type`，flags 提供当前 `on_ground / sprinting / crouching`。每次尝试重新读取当前标签和已结算 Buff。这些原版查询使用 `start` 阶段；蓄力检查复核尚未发射攻击的资格，没有额外创建 Chorus 技能施放或 accepted 事实。

`MinecraftEffectRuntime.nativeActionReport()` 保留最近一次查询的不可变输入、逻辑时间、Decision 或查询失败原因；它只用于诊断，不发布新事件，也不无限积累实体引用。查询错误拒绝本次尝试并记录运行时失败。运行时失败后查询沿用最后已提交状态，其他没有命中限制的生物仍可射击；没有把查询错误伪装成有效拒绝声明。

这个入口不检查既有弹体的飞行或命中，不撤销已结算伤害，也不限制 DOT / 点燃反馈。其他模组自定义发射入口、法术召唤、完整移动失能及客户端禁用展示仍需各自接线；原版近战与主动移动输入见下文。[native_ranged.json](../common/src/test/resources/effects/native_ranged.json) 是合成机制验收，不增加 Compendium 效果覆盖数。

## 原版近战与接触攻击资格

`melee_attack` 独立于 `ability_use / weapon_fire / ranged_attack`，使用相同的 SOURCE / BUFF 禁止条件、持有者隔离及拒绝依据。它拦截 Minecraft 服务端普通玩家 `attack`、生物 `doHurtTarget` 及其原版覆盖方法、LivingEntity / Player 的 `stabAttack`、史莱姆 / 岩浆怪的接触伤害、末影龙逐目标的头部 / 翅膀接触攻击。原版守卫者在光束中借用 doHurtTarget 结算物理分量，此调用保留为 ranged_attack，不因禁止近战而丢失半束伤害。

玩家普通攻击在重置攻击冷却、扣武器耐久、横扫、击退或伤害前拒绝。生物覆盖方法在攻击动画、原版状态或其他附带行为前拒绝；例如蜜蜂不消耗毒刺，铁傀儡不启动攻击动画，监守者不因失败近战启动音波冷却。矛刺连同其非伤害击退 / 强制下坐骑分支受限；末影龙的逐目标接触在推力和伤害前检查。史莱姆的一般实体碰撞仍按原版进行，只有接触攻击被拒绝。

查询使用 start 阶段，事件带 `chorus:native_melee_attack`，references 包含 native_attack / entity_type；actor 和 victim 是实际实体 ID，包括非 LivingEntity 目标。来源保留实体 / 类型标签：原版玩家明确提供 `chorus:guardian`，Mob 提供 `chorus:combatant`，其他 LivingEntity 不猜角色。weapon / ability 留空，不把手持原版物品自动注册成 Chorus 装备。原版攻击实际成功后，仍沿原有 DamageCapture 路径提交事实，不额外制造一次命中或伤害。

门槛不取消先前接受的物品使用、冲刺 / 激流运动、投射物或已提交的世界动作。玩家客户端仍可能播放挥手，禁用提示和预测尚未接入；近战接触拒绝不等于已经取消全部使用动画或移动。爆炸、咆哮、荆棘 / 河豚反伤及其他模组自定义攻击需要自己的入口。近战门槛也不自动加入压制、Freeze 或 Suspend 内容，具体状态的资格和例外由内容声明。[native_melee.json](../common/src/test/resources/effects/native_melee.json) 为合成验收，不增加 Compendium 效果覆盖数。

## 原版主动移动输入与跳跃限制

`movement_input` 与 `jump` 是独立的 action_gates。前者禁止 moveRelative 接收的主动方向加速，后者禁止新的地面 / 液体跳跃；只设置其中一个不会隐式设置另一个。它们不改已有速度、重力或外部击退，也不撤销已接受的 apply_impulse。SOURCE / BUFF 的持有者、暂停、过期及多来源拒绝规则沿用 ActionGate，不设置 NoAI、不停整个 Brain、寻路或攻击流程。

服务端在状态提交、时间推进及原版输入读取边界归约两种资格。movement_input 查询使用 continue，jump 查询使用 start；标签分别为 chorus:native_movement_input / chorus:native_jump，actor 为实际持有者，victim 为空，references 包含 native_input=minecraft:movement_input 与 entity_type；当前实体 / 类型标签、guardian / combatant 角色及 on_ground / sprinting / crouching 与原版攻击入口相同。weapon / ability 为空。查询不发布事件，不改变效果链。

`MinecraftMovementProjection` 保存最近一批每持有者的完整 Decision / 失败证据，Chorus 的 S2C movement_input_v2 消息同步维度、实体 ID、UUID 和四位掩码；输入使用低两位，下面的轴约束使用高两位，存在轴约束时另带固定位置；只发送给支持此通道的区块观察者及玩家本人，开始追踪和玩家加载完成时另发当前快照。客户端核对维度 / UUID，实体卸载自然丢弃状态，不保留全局实体 ID 缓存。查询异常拒绝对应输入、保存 QUERY_FAILED 且不伪造 Decision，再停止运行时；已提交的限制保留到显式清理。关闭 / 卸载只清除这个运行时仍持有的掩码；跨维度的新运行时接管后，旧运行时不能清除新值。标记不写 NBT，不代表 Buff 持久化。

客户端在键盘 tick 后、自动跳跃后和读取上一帧边缘前过滤输入。movement_input 清除方向、冲刺及 shift 输入（包括飞行下降 / 水中下潜）；jump 清除跳跃输入（包括飞行上升 / 自动跳跃），并清除尚未释放的骑乘跳跃蓄力，避免把禁用误作释放按键。服务端同时过滤已持有及新到达的普通输入包。原版 moveRelative 在两侧过滤加速，地面 / 液体跳跃入口涵盖原版 Rabbit、Sniffer、Cube、MagmaCube、Mob、Bee 的重写，保留重力和碰撞流程。

这两个输入门槛不约束游泳朝向、鞘翅滑翔、特殊 AI 直接写速度或玩家位置包；这些路径的实际坐标 / 速度约束见下节。同步期间已在途的输入和惯性不会因输入门槛而追溯撤销。Freeze、Suspend 和 Slow 的内容资格及数值仍须另行声明。[native_movement.json](../common/src/test/resources/effects/native_movement.json) 是合成验收，未增加 Compendium 完成数。

## 水平与垂直运动约束

`horizontal_motion` 拒绝时固定 X / Z，`vertical_motion` 拒绝时固定 Y；同时拒绝则固定全部坐标。二者与 movement_input / jump、技能和攻击资格独立。可由 SOURCE 或受影响者身上的 BUFF 声明，任意有效拒绝继续保持该轴。新受限轴取当前原版位置作为锚点，其他已受限轴保留自己的锚点；重授予 / 加入第二来源不会重置位置。解除一个来源不能释放另一个来源仍禁止的轴。

查询使用 continue，事件标签分别为 chorus:native_horizontal_motion / chorus:native_vertical_motion，references 提供 native_motion=minecraft:motion_constraint；其余 actor、来源、姿态与原版行动门槛相同。位置锚点由宿主投影保存，不是纯规则解释器再模拟一次物理。当前锚点不写 NBT，不代表跨运行时恢复或控制状态持久化已经实现。

受限轴的已有速度在生效时清零，之后原版 setDeltaMovement、move 和 setPosRaw 都受约束，包含重力、AI 直接加速、碰撞推力和普通坐标写入。被拦截的冲量不会积累到解除时释放；未受限轴继续使用原版重力、速度与碰撞。固定高度时 maxUpStep 临时返回 0，防止先算出跨台阶的水平位移，再钳住高度造成穿墙。投影不设置 NoAI、不停其他规则队列或世界效果。

`apply_impulse` 在原版实际速度写入后重新读取 Change。所有请求分量都被约束时返回 UNCHANGED，不发送速度包、不延长冲量宽限期、不发布 applied 事实；只挡住部分轴时返回 APPLIED，回执和规则可读 delta 只包含实际接受的分量。输入限制本身仍不拦截外部冲量。

玩家位置包先保留原版非法数值检查，再以受限轴锚点替换该轴请求；自由轴仍走原版距离、速度、碰撞与状态验证。若请求越过受限轴，发送玩家实际被接受的位置作纠正，不直接传送到客户端提供的自由轴坐标。垂直约束期间仅清除浮空计数，保留其他移动校验；解除后恢复正常浮空检查。服务器可以因此约束未提供正确客户端输入的位移请求。

明确的宿主 teleportTo / teleport / 玩家连接 teleport 可以改变固定位置，完成后在新的位置继续约束；它们是受信的服务端迁移边界，普通 move / setPos 不享有此例外。相对传送先按原版发包，随后发锚点，客户端立即处理受限实体的授权传送，避免先套新锚点再重复加一次相对偏移。跨维度状态迁移和各种原版 / 模组瞬移技能的施放资格仍应由对应能力处理，坐标约束不等于已经禁止所有瞬移技能。

新施加轴约束时，宿主先让目标下坐骑，再固定当时位置；约束期间拒绝重新骑乘，解除后允许但不自动恢复旧骑乘关系。这是明确的 Chorus 宿主控制政策，不是 Compendium 的原作数值结论。受限实体本身作为坐骑、载具乘客的完整技能规则仍需内容装配。

该接口本身只固定当前高度；规则可组合下节的碰撞位移逐步抬升并更新锚点。Suspend 还需原作高度 / 速度校准、目标等级与有限水平速度规则，Freeze 还需目标等级、攻击 / 技能禁用、挣脱、碎冰和伤害规则。[native_motion.json](../common/src/test/resources/effects/native_motion.json) 为合成机制验收，不增加 Compendium 完成数。

## 碰撞感知位移与逐步抬升

`chorus:displace_entity` 执行一次服务端位移，必填 `direction` 方向绑定与 `distance`（meter，有限非负），默认 `target=self`、`origin=bound`，可附加 `tags`。方向可来自 capture_direction、direction_between 或新增的 world_direction。它按目标完整碰撞箱解析请求位移，取得裁剪后的目的地，再调用原版授权传送；已有轴约束会在新位置继续保持。它不移除控制 Buff，也不积累受限轴的速度。

`chorus:world_direction` 从必填 `position` 位置绑定继承维度，读取 x / y / z 三个 multiplier 表达式并归一化。位置缺失或三分量全零时返回缺失方向，不从当前施加者推测世界；(0, 1, 0) 因而明确表示该维度向上，与玩家视角无关。方向是不可变绑定，可以进入延迟动作；执行时目标已换维度则拒绝。下面是一次向上最多 0.25 米的合成示例，不是 Suspend 的校准参数：

```json
[
  {"action":{"type":"chorus:capture_position","target":"victim"},"as":"feet"},
  {"action":{"type":"chorus:world_direction","position":"feet",
    "x":{"type":"chorus:constant","value":0,"unit":"multiplier"},
    "y":{"type":"chorus:constant","value":1,"unit":"multiplier"},
    "z":{"type":"chorus:constant","value":0,"unit":"multiplier"}},"as":"up"},
  {"action":{"type":"chorus:displace_entity","target":"victim","direction":"up",
    "distance":{"type":"chorus:constant","value":0.25,"unit":"meter"}},"as":"moved"}
]
```

宿主使用原版 collideBoundingBox 的方块、实体和世界边界碰撞形状，禁用 step-up，不使用只检查中心射线的净空测试。查询前确认整个扫掠盒及一格边距内的 X/Z 区块均已加载，不主动加载区块。单次最多 64 米，带边距的离散查询包围盒最多 65,536 个格子；超出返回 query_too_large，不偷偷缩短请求。这是单次宿主查询成本边界，与事件链次数无关，多次定时位移和合法效果循环不受全局次数截断。

缺失 / 移除 / 非本维度实体、死亡、旁观、乘客、载有乘客、睡眠、noPhysics、缺失方向、方向维度不匹配、未加载扫掠、过大查询和越界分别有明确拒绝回执。越界包括目的地非有限、超出原版坐标边界或起始碰撞箱不在世界边界内；正常从边界内向外的移动被边界碰撞裁剪。乘客与载具拒绝是当前宿主政策：尚未共同检查整组乘客的碰撞箱。

回执只有实际位置变化才为 applied；完全被墙挡住或零距离请求为 unchanged，但仍有可观察的前后位置。数值字段 before_x/y/z、after_x/y/z、requested_x/y/z、resolved_x/y/z、delta_x/y/z 及 requested_distance、resolved_distance、delta_distance 都是 meter。requested 是归一化方向乘距离，resolved 是碰撞解析后的向量，delta 是世界执行后的实际坐标差。clipped 比较 requested 与 resolved，不把坐标浮点加减误差当碰撞；实际目的地还实现 PositionResult，可用于既有位置绑定消费者。所有数值都须先检查 observed / available；拒绝没有位置测量，不填零。各 outcome 有同名小写标志，另有 missing、changed、clipped。

只有 applied 发布 `chorus:entity_displaced`，带上述数值、clipped 标志、dimension 引用、原施加者 / 武器 / 技能信用、命令 tags 和 chorus:displacement。后继规则在实际世界回执之后执行。回执不匹配或执行结果未知时停止运行时，不回滚已移动的位置，不重试命令，也不伪造成功事实。

玩家使用原版位置同步与传送确认协议，轴锚点按已有协议随后同步；非玩家保留未受限的实体速度，玩家沿用原版 getKnownMovement（最近客户端移动包确认的速度）与相对速度协议，并保留视角。玩家的 getKnownMovement 与直接写入的 deltaMovement 并非同一值，后续原版移动、确认包和物理仍会更新速度。该操作不调用 Entity.move，不模拟一次完整原版自然移动的落地、摔落或沿途方块接触回调；不等于冲量，也不提供平滑预测轨迹。每 tick 传送在本地集成客户端已验收，高延迟下的观感、丢包与其他模组传送回调仍未专门验收。

[displacement.json](../common/src/test/resources/effects/displacement.json) 演示组合：目标 Buff 固定两轴，剩余高度组件从 1.05 米开始，每 50 ms 请求 min(0.25 米, 剩余高度)，只减实际 delta_y；发生裁剪、零移动、拒绝或到达高度即取消抬升计时器，但保留悬停直到 Buff 到期 / 被清除。Buff 自有计时器随生命周期退出，原施加者信用始终保留。5 秒时长、距离与步长均为合成验收值；该例不增加 Compendium 完成数，也不宣称完整 Suspend。

## 按标签批量结束 Buff

`chorus:remove_buffs_with_tag` 接受必填的 `tag` 和默认 self 的 `target`，按持有者选择带有该标签的全部 Buff 实例，包含其他施加者的实例、同定义的不同 source / weapon 实例及暂停中的状态。它不依赖当前绑定来源来构造某个实例键，也不删除别的持有者或不匹配标签的状态。未提供 tag、未知字段及不合法 target 在编译时拒绝；未知但格式合法的标签可以匹配零项。

```json
{
  "action": {
    "type": "chorus:remove_buffs_with_tag",
    "tag": "example:active_ability",
    "target": "victim"
  },
  "as": "ended_abilities"
}
```

结果提供 count 单位的 `instances / stacks` 及布尔 `changed`，Java 的 `BuffRemoval.Receipt` 另保留持有者、标签、移除原因来源和所有被移除实例的不可变快照。选择在动作开始时固定，按 Buff.Key 排序；先提交全部移除，再依次发布每个实例的 `buff_ended(reason=removed)`，最后发布一次 `buffs_removed`。后者的 actor / source 属于执行移除的来源、victim 属于被移除者，references.tag 和 numbers.instances / stacks 给出实际选择；各自的 ended 事实仍保留原 Buff 施加者。没有实际移除时不发布这些事实。

结束规则读取的实时状态已经没有这整批实例。规则随后授予的新实例不追加入原选择，不会被这次移除再次删除；该动作不设事件链次数上限。`withBuffs` 统一撤销这些实例拥有的命名计时器和 source 生命周期 after，detached 动作继续保留。护盾、属性和恢复等由 Buff 提供的贡献随实例消失；其他来源的贡献不受影响。世界清理动作返回未知结果时，整批移除和此前实际世界效果保持提交，不恢复状态或自动重放。

该动作可以表达驱散或结束以 Buff 生命周期建模的活动技能；它不自动发现任意 Java 宿主中运行的技能，不取消没有绑定到这些 Buff 的动作，也不代替具体 Super 的结束行为。[buff_removal.json](../common/src/test/resources/effects/buff_removal.json) 使用合成活动状态，验证整批结束、不同来源与持有者、暂停状态、真实清理回血及 detached 延迟回血。

## 捕获玩家移动输入方向

`{"action":{"type":"chorus:capture_input_direction","target":"self"},"as":"input"}` 读取服务端已经接收的玩家移动按键状态，并按查询时的 yaw 转成水平单位方向。前后、左右相反键分别抵消，斜向归一化；pitch、当前速度和外部击退不参与。它读取原版处理和 Chorus 移动输入限制之后的 `lastClientInput`，不接受调用者在效果事件中伪造一个输入方向，也不会修改按键或产生位移。

| 查询结果 | observed | available | neutral | missing |
| --- | --- | --- | --- | --- |
| 已观察且有方向 | true | true | false | false |
| 已观察但无有效方向键 | true | false | true | false |
| 目标缺失 / 死亡 / 未加载 / 宿主不支持 | false | false | false | true |

`available` 表示有可供动作消费的方向；`observed` 表示确实观察到了输入。中立输入不自动改成朝前，内容可显式在 `neutral` 分支另外读取 horizontal 朝向；未知输入不能被混作中立。当前 Minecraft 适配只支持服务端玩家；普通生物返回 UNSUPPORTED，不用其速度猜测输入。目标实体已从宿主索引移除时返回 MISSING_TARGET；仍可解析但位于未加载区块时返回 UNLOADED。

结果实现既有 DirectionResult，可传给 `apply_impulse`、`displace_entity`、圆锥或延迟动作。`after` 保留捕获值，之后转身、换键和卸下技能不会重新观察；检查点恢复保留方向和中立 / 未观察状态。动作核对回执目标，未知或错配回执按既有失败边界停机，保留已接受技能付款，不自动重读或重放。新增四个存储 ID，不改变旧 capture_direction、DirectionQuery.Mode 或其字段。

Acrobat’s Dodge 是该能力的内容需求之一，但 `input_direction.json` 仅为通用验收夹具，不等于已实现跳跃、完成时授予 Radiant 或 Ascension 的完整收益。五项 InputDirectionTest 和三项 InputDirectionGameTest 验证方向、输入限制、原生延迟冲量、未知边界及纯领域恢复；真实轨迹和技能完成时机仍须由内容另行表达。原表的 “upon landing” 与新版允许空中施放要一起核实，不能仅凭旧描述认定完成条件必定是接触地面。

## Acrobat’s Dodge 参考技能与共享收益

[acrobat_dodge.json](../common/src/test/resources/effects/acrobat_dodge.json) 装配已有引擎动作，不引入技能专用 Java 分支。职业槽声明独立 `acrobat_dodge_energy`：容量 / 初值一格、CES .5，所选技能来源把共享 class_regeneration 的基础速率替换为 PvE 1/56、PvP 1/148.4，并限定到该账户；stat 与额外基础回充继续使用共享阶段。未选中时该账户 base_rate 为零，已付余额保留。这是当前参考内容的换装政策，不是完整的 Destiny 换装惩罚模型。

原生施放需要 horizontal_speed / vertical_speed（meter_per_second）、neutral_heading_sign（multiplier）、completion_delay（second）四个明确校准输入。先捕获生命引用和实际移动输入；有输入沿输入方向，中立输入沿另行捕获的水平朝向乘 neutral_heading_sign。随后发出水平和竖直两次速度增量操作，只有每次回执为 observed 才安排后续阶段。它们是 additive impulse，不承诺原作位移曲线。缺失校准在技能接受前报错；世界操作未知时保留已付能量和已有速度，不重新执行。

完成阶段通过 detached after 保留生命引用和原施放，转身、换键和清槽不改写这些捕获值；解析时生命被替换、缺失或已死亡便结束。共享 `acrobat_dodge_benefits` 调用现有 Radiant / solar_effect_duration，对自己和十米内盟友逐个计算十秒基础时长与受益者自己的 Solace，再经 apply_status 授权。敌人范围为 6.5 米，按目标类型选择 40 / 20 峰值，再乘必需的 acrobat_dodge_falloff 与 acrobat_dodge_damage_scale 校准 Profile，最后进入 outgoing。查询使用完成时当前脚部位置、稳定身份顺序、无视线球形范围；这些空间 / 快照细节是显式内容政策。

Ascension 在有效位移回执分支发出 `chorus_d2:ascension_dodge_benefits`，payload 沿用原接受事实。Acrobat 所选来源检查该数据事件的 ability / cost_ability 引用后捕获生命，使用独立 acrobat_ascension_delay 安排同一份收益，保留 Ascension 归属。该扩展事件不是规范 ability_started / ability_used，不能用只接受规范事实的 event_ability_tag 来识别它。已观察但未改变位置可以触发，已知不可用的位移不会触发；普通职业模组仍只收到一次原始职业施放事实。

[校准夹具](../common/src/test/resources/effects/acrobat_dodge_test_calibration.json) 使用 6 / 4 米每秒增量、后退中立方向、.2 秒完成延迟、线性衰减及 .1 世界伤害换算。acrobat_ascension_damage 决定是否连同爆炸传递：默认合成值零只授 Radiant，核心测试另验证值一传爆炸。新版允许空中使用，但公开来源尚未确定精确完成帧或必须接触地形；时间续体是待校准的完成阶段政策。两种 Ascension 爆炸配置、动画 / 速度曲线、Class 属性曲线、正式装配与完整组合均未达到原作完整验收。

## 一次性物理冲量与派生方向

`chorus:apply_impulse` 在一次明确的世界操作中向目标当前速度加上冲量；单位为 meter_per_second。它接受已捕获的方向，不在执行或回执阶段重读施放者朝向，也不直接移动位置：

```json
[
  {"action":{"type":"chorus:capture_direction","target":"self"},"as":"heading"},
  {"action":{"type":"chorus:apply_impulse","target":"self","direction":"heading",
    "speed":{"type":"chorus:constant","value":12,"unit":"meter_per_second"},
    "axis_scale":{
      "x":{"type":"chorus:constant","value":1,"unit":"multiplier"},
      "y":{"type":"chorus:constant","value":0,"unit":"multiplier"},
      "z":{"type":"chorus:constant","value":1,"unit":"multiplier"}
    },"tags":["example:dash"]},"as":"motion"}
]
```

target 默认 self，origin 默认 bound（可选 event），tags 默认空。speed 必须非负有限数；axis_scale 可省略，默认三轴均 1，指定时须提供三个 multiplier 表达式，可以为负以反转对应轴。先对捕获方向归一化，再依次乘 speed 和世界坐标轴倍率，不再次归一化。因此 y=0 会保留目标原来的垂直速度，但俯仰会影响水平增量，直向上时投影为零。这不是“固定水平速度”或替换当前速度；需要其他方向政策时另行构造方向。

Minecraft 宿主确认目标存在且处于当前维度，拒绝死亡、旁观者、骑乘、睡眠和未加载位置；没有方向或方向维度不符也不执行。完整新速度和测量必须有效后才写入原版实体，米 / 秒按 20 tick/s 换算为 blocks/tick。玩家沿用 26.3 ApplyEntityImpulse 的直接 ClientboundSetEntityMotionPacket 协议，随后清除 syncVelocity 以免同次 tracker 再发；非玩家设置 syncVelocity。成功改变速度后调用 applyPostImpulseGraceTime(10)，沿用原版十 tick 的移动检查上下文宽限。零增量 / 浮点相加后未变化时不发包、不加宽限。移动继续服从原版地形碰撞、摩擦和重力；该动作不自动减免摔落伤害，也不自动套用击退抗性、Boss 免疫或特定技能移动状态。

结果可绑定 before_x/y/z、after_x/y/z、delta_x/y/z，单位均 meter_per_second，delta 为观测的前后差值。applied / unchanged 分别表示实际速度改变 / 已观察但不变；其余结果为 missing_target / dead / spectator / passenger / sleeping / missing_direction / wrong_dimension / unloaded。observed 表示存在速度测量，changed 等同 applied。拒绝分支读取数值字段会报错，内容必须先判断 observed，不能将“未观察”当作零速度。

只有 applied 发布 `chorus:impulse_applied`：actor 为原始来源 owner，victim 为目标，source 为本动作发出时固定的 Origin；标签包含来源标签、配置 tags 和 chorus:impulse，numbers 与回执的九个速度字段对应。完整命令及前后测量保存在 Impulse.Applied.receipt。它证明速度写入，不证明移动了多少距离、抵达目的地、完成闪身或命中了敌人。世界结果未知时维持既有待决操作，已发生的原版速度改变和已支付成本不会回滚或自动补发；回执必须对应原请求。

`chorus:direction_between` 是纯动作，from / to 引用先前的 POSITION 结果，计算从前者指向后者的单位方向：

```json
{"action":{"type":"chorus:direction_between","from":"caster_position","to":"target_position"},"as":"away"}
```

它不读取世界，也不伪装成方向查询回执。任一位置不可用、维度不同或两点重合时返回 missing，不选择任意替代方向；available 时可交给冲量、圆锥和投射物。位置和方向都能被 after 捕获，后续目标移动不改写已捕获值；冲量执行时仍检查目标及维度。反向交换 from / to 可表达朝中心拉动，配合已有目标列表可逐目标推开。

[impulse.json](../common/src/test/resources/effects/impulse.json) 是合成夹具，含已付款延迟冲量和两点推开示例；20 / 10 米每秒、延迟和治疗观察器不是 D2 数值。移动输入方向可用上节的 capture_input_direction 捕获；持续钩爪 / 摆荡的预测状态机、技能专用移动属性与输入语义、移动 / 落地事实、闪身 / 急切刀锋的正式触发与距离时间曲线仍需实现。

## 区域形状、方向快照与视线

`select_targets` 接受传统 radius（等同 sphere），或新的 area，必须二选一。area 的类型：

- `chorus:sphere`：radius 为球半径。
- `chorus:cylinder`：radius 为水平圆半径，height 为完整高度，沿世界 Y 轴，在中心上下各 height / 2。
- `chorus:cone`：length 为沿轴线的正长度，radius 为远端平面的半径，direction 引用前面 capture_direction 得到的结果。是有限实心锥，不是半径球内的视角扇区。

所有长度字段接受 meter 类型的 Value，可以引用已解析技能参数或已有结果；圆锥长度必须大于零，其余尺寸可为零。方向必须先显式捕获，不能从未声明的施放者朝向推断：

```json
[
  {"action": {"type": "chorus:capture_direction", "target": "self"}, "as": "aim"},
  {"action": {"type": "chorus:capture_position", "target": "self", "anchor": "eyes"}, "as": "origin"},
  {"action": {
    "type": "chorus:select_targets", "center": {"position": "origin"},
    "area": {"type": "chorus:cone", "length": {"type": "chorus:constant", "value": 16, "unit": "meter"},
      "radius": {"type": "chorus:constant", "value": 3.75, "unit": "meter"}, "direction": "aim"},
    "target_anchor": "body", "line_of_sight": true, "relation": "not_allied", "relative_to": "self",
    "exclude": ["self"], "order": "nearest", "limit": {"type": "chorus:constant", "value": 4, "unit": "count"}
  }, "as": "targets"}
]
```

此例展示结构，不是完整 Thunderclap 或其他技能定义。capture_direction 的可选 mode 默认为 look，读取服务器实体的完整 look vector；horizontal 则直接按服务器 yaw 捕获水平朝向，y=0，即使实体正向上 / 下看也有确定方向。两种模式均保存带维度的归一化方向，回执必须匹配原请求的目标和 mode。horizontal 表示朝向，不是客户端移动按键方向。capture_position 的 anchor 可选 feet（默认）/ body / eyes。方向与位置结果可由 after 捕获，原实体转身、移动、移除或来源解绑不改写这些值。缺少实体返回 missing，不补零向量；圆锥使用缺失方向时返回 missing_direction，方向和查询中心不在同维度时返回 wrong_dimension。延迟捕获可随领域检查点保存；不代表实体运动或物理世界的跨重启恢复。

`cross_direction` 与 `rotate_direction` 是对这些快照的纯动作，必须通过 `action` / `as` 绑定结果：

```json
{"action":{"type":"chorus:cross_direction","left":"up","right":"heading"},"as":"right"}
{"action":{"type":"chorus:rotate_direction","direction":"aim","axis":"fan_axis","angle":{"type":"chorus:constant","value":5,"unit":"degree"}},"as":"spark_direction"}
```

以上是两个独立步骤片段，输入必须此前绑定为 DIRECTION。叉积按 left × right 返回单位方向；旋转按右手定则，保留沿轴分量并归一化结果。缺失 / 错维度输入与平行叉积返回 missing；错误结果类型或角度单位在加载时拒绝。它们不查询实体，不把相机后来转向写回快照。可用 `right = up × heading`、`fan_axis = aim × right` 建立垂直瞄准时仍有效的扇面；完整例子见 `incinerator_snap.json`。两种动作各有新增存储身份，原有存储形状保持。

直接以实体为 center 的查询可以给 center_anchor；target_anchor 决定每个候选实体的取样点，均为 feet / body（碰撞箱中心高度）/ eyes。已捕获位置是精确坐标，不再叠加 center_anchor；这类查询若指定非默认 center_anchor 会在编译时报错。区域判断、距离和可选视线均使用同一对取样点，**不是整个碰撞箱与区域的相交测试**。球形旧请求仍保留脚底语义；非球形回执另保存相对坐标并验证它位于区域内，结果读取不再访问世界。

line_of_sight 默认 false，不会替已有 Jolt / Rift 等内容偷偷加遮挡规则。true 时从中心取样点向目标取样点发射方块碰撞射线：使用 COLLIDER 形状，半砖只挡实际占据的部分，玻璃会挡；流体和其他实体不挡。这是当前 Minecraft 宿主的明确规则，尚未校准为各命运 2 效果对每种障碍物的行为。只访问已有的已加载 LevelChunk；任一端点或路径经过未知地形时不可见，不触发区块加载。

所有关系 / 排除 / 形状 / 视线筛选完成后才排序和应用 limit，所以被墙挡住的最近目标不会挤掉更远的可见目标。查询返回的是一次观测：之后开门、转身或移动不会改写已有列表与距离；下一次查询才看到变化。视线与已选目标之间没有持续锁定或再次命中验证，内容如需这些检查必须另发查询。

Compendium 固定快照中，Arc D28、Solar D29 / D30、Void D30 给出扫描手雷的视线要求；Arc D58 和 Solar D40 需要锥形范围。它们证明这些通用能力是必需的；当前合成 spatial_query.json 验证机制，没有据此宣称这些技能已经完整实现或几何端点已经校准。

## 组合实例：电弧箭扫描与连锁

[arcbolt.json](../common/src/test/resources/effects/arcbolt.json) 是可编译的部分内容实例，展示了以下组合：

1. 宿主发出本次施放的落地事件，规则同时核对 owner 与 ability，并以 `origin: event` 捕获攻击快照。
2. `select_targets` 在落点 12 米内选择视线可见的最近目标；先完成过滤，再应用 `limit: 1`。
3. 在该单元素集合的 `for_each` 内安排 detached 的 1 秒延迟，保存首次目标身份。
4. 延迟到期后捕获目标当前身体点，执行 `damage_snapshot`；读取实际 `effective_with_absorption`，大于零才查询下一跳。
5. 下一跳用保存位置为中心、10 米范围、nearest / limit 1；`exclude` 显式列出施加者、落点实体及外层循环绑定的先前目标。四个嵌套阶段构成该基础技能的最多四目标规则。

排除绑定作用域按嵌套结构保留，下一次施放重新建立自己的绑定。这个上限由内容规定，不会限制引擎里的合法循环。存储每次伤害前的位置还能让致死并被移除的目标作为连锁起点。首段锁定、后续视线 / 跳跃间隔和伤害资格的具体选择见 [电弧箭数值与缺口](d2-ruleset.md#电弧箭手雷arcbolt-grenade)。目前 `test:arcbolt_impact` 是验收宿主输入，victim 是表示落点的独立实体；不能直接把它当成已经存在的生产投射物事件。

## 物理投射物与碰撞动作

`projectile.emitter` 可显式指定发射实体，默认 `source_owner`。它在发射时捕获为身份，与原始归属 owner 一起排除碰撞和追踪选敌；不替换原版 projectile owner、友敌参照或动作体的伤害信用。炮台可以使用 `emitter: "self"` 从自身内部发射，来源归属仍为施放者。发射实体后来被移除不会撤销已生成的弹体；从已捕获位置继续发射是否允许，由内容自己的存活条件控制。

投射物步骤还可用 `launch_as: "launch"` 保存立即生成回执，再以 `chorus:result_flag` 读取 `launched / missing_position / missing_direction / wrong_dimension / unloaded / rejected / expired_shot`，或读取 count（0 / 1）。它与 `as` 绑定的未来接触结果分开，适合“首次成功开火后移除减伤”等反应；请求发射不等于成功生成。未知世界结果不会进入成功或失败分支，也不会自动重试。launch_as 不得覆盖已有绑定或接触绑定，自己的接触动作体不能读取尚未返回的这份生成回执；它也不是 LivingEntity 目标集合。整枪成员投射物保留同样的回执语义和原有成员预留 / 拒绝结算。

`projectile` 是与 `after / for_each` 并列的控制步骤。先用 `capture_position`（通常 `anchor: eyes`）、`capture_direction` 和 `capture_damage` 保存 `muzzle / aim / shot`，再发射：

```json
{
  "projectile": {
    "position": "muzzle",
    "direction": "aim",
    "speed": { "type": "chorus:constant", "value": 20, "unit": "meter_per_second" },
    "gravity": { "type": "chorus:constant", "value": 0, "unit": "meter_per_second_squared" },
    "drag": { "type": "chorus:constant", "value": 1, "unit": "multiplier" },
    "lifetime": { "type": "chorus:constant", "value": 1, "unit": "second" }
  },
  "as": "impact",
  "do": [
    { "for_each": "impact", "as": "target", "do": [
      { "type": "chorus:damage_snapshot", "snapshot": "shot", "target": { "binding": "target" } }
    ] }
  ]
}
```

这些是合成参数。完整可编译程序见 [projectile.json](../common/src/test/resources/effects/projectile.json)，同时包含带成本的技能入口、落地范围动作和到期分支。

`as` 只在每次接触 / 终止动作体里引入绑定，不会向发射后的外层步骤泄漏。此前可复制的结果会保留，实际施放来源 / self 和原程序动作定义固定；来源卸下不会取消已发射物。未预先捕获的状态读数仍在该次接触时求值。`cast_cost` 等普通付款 / 退款结果不可复制到该帧；先 retain_cost 得到的有限期句柄可以共享账本，捕获 paid 数字不会获得退款权。

| 接触结果的用途 | 行为 |
| --- | --- |
| `for_each: impact` | ENTITY 为碰撞目标，ARRIVED / CAUGHT 为接收者的单元素身份集合，其他结果为空集合；没有猜测目标或距离字段 |
| 查询 `center: { "position": "impact" }` | 以真实接触点或终止位置发起后续范围查询 |
| `result_flag` | `entity / block / expired / unloaded / arrived / target_lost / caught` 恰有一个为 true；`terminal` 表示飞行结束，`bounced / pierced` 表示此次接触后继续反弹 / 穿透 |
| `result` | `count / sequence / bounces / entity_contacts / target_contacts`（count）、`age`（second）、`normal_x/y/z`（multiplier）；法线只有 BLOCK 为单位向量，其他为零 |

发射使用 `ProjectileFlight.Launch / Receipt` 世界协议，操作账本防止重复发射；回执必须对应原请求。缺失位置 / 方向、错误维度、未知区块和宿主拒绝不会产生可执行实体，也不会自动退款或重试。可选 `launch_as` 暴露独立的立即生成回执；内容可根据已确认拒绝显式处理退款，未知世界结果不进入该分支。

服务端每个 Minecraft tick（50 ms）推进一次：先加重力，再乘 drag，再移动。drag 是每 tick 的乘数，不是每秒速率。寿命向上量化到物理 tick，在最后一步先处理碰撞再处理到期；它不改变规则引擎计时器的微秒协议。速度 / 重力必须在 0–2000 的各自单位内，drag 在 0–1 内，寿命必须为正且能表示为有限整数微秒；超出宿主边界拒绝，不裁剪成另一个值。

命中用每 tick 的完整线段扫描，方块碰撞形状或首个不可模拟区域先限制线段末端，再检查可用路段内的实体。实体碰撞箱向外扩张 0.125 米，方块使用中心射线；只有存活且非旁观的 LivingEntity 可作为直击目标，原施加者始终排除。没有自动阵营排除、投射物互撞、盾牌反射、水下阻力或方块 `onProjectileHit` 副作用。流体不遮挡。进入未知区块或已加载但不执行实体 tick 的区域时，先结算边界前的实体 / 方块接触；仍继续飞行且抵达边界才以 unloaded 终止，停在该边界，不把它当成可反弹的墙。起点已经不可用时在当前位置结束；绝不加载新地形或追加区块票据。普通视线查询仍只要求地形已加载，飞行额外要求沿途实体模拟可用；范围 / 阵营及爆炸视线由动作体另行声明。

每次接触先提交计数，终止时才消费实体，再恢复独立动作体；后续失败会放弃余下飞行，已提交接触不重放。每个成功生成的飞行物由所属运行时登记。世界 tick 末尾检查登记项：实体所在区域失去 tick 资格时，以 unloaded 结束一次，不补跑运动或碰撞；已被外部移除的实体直接放弃。运行时关闭 / 故障立即移除全部所属飞行物，不等待实体再 tick，不执行其终止动作，也不交给替换程序执行。检查使用登记项快照，终止动作中新发射的飞行物留到下一次检查；终止动作的未知结果仍停止运行时并保留已提交世界变化。

当前实体 `noSave / noSummon`，不支持传送门、区块卸载续接或重启持久化。两端使用原版实体跟踪协议同步运动，重力 / drag 是显示用同步数据，完整动作体和快照仅在服务端；默认紫水晶碎片外观只是占位。原版箭、雪球等不会被自动转成该协议。显式伤害快照已携带来源层 origin_bundle 选择；Buff 规则、多版本并存和派生筛选继承仍待实现。


### 反弹、穿透与重复命中

`projectile.collision` 可选，省略时仍在第一个实体 / 方块接触后结束。以下配置允许一次墙面反弹、无限穿透实体，每个目标最多命中两次：

```json
"collision": {
  "block_bounces": { "type": "chorus:constant", "value": 1, "unit": "count" },
  "entity_pierces": "unlimited",
  "max_hits_per_target": { "type": "chorus:constant", "value": 2, "unit": "count" },
  "restitution": { "type": "chorus:constant", "value": 1, "unit": "multiplier" }
}
```

三个次数字段接受 count 单位的整数 Value 或字符串 `unlimited`，均在发射时求值。默认值依次为 0、0、1；前两个允许零，每目标上限必须为正。有限值不能超过 2147483647，省略上限只能用 `unlimited`，不使用负数约定。`entity_pierces: 1` 表示穿过第一个目标，在第二次实体接触后停止；物理接触即计数，即使后续伤害被取消。

可选 `total_continuations` 使用相同 count / `unlimited` 格式，默认为 `unlimited`。它允许内容为墙面反弹和实体穿透设置共享的继续飞行次数：值为 3 时，前三次接触后可继续，第四次接触执行动作体后结束；值为 0 时，第一次接触仍执行动作体，但不继续飞行或反射速度。独立的 `block_bounces` / `entity_pierces` 同时生效，任何一个用尽都结束；`max_hits_per_target` 仍决定同一实体是否可再次成为接触目标。未配置共享次数时保留原来的独立预算。所有次数在发射时固定，只约束该投射物的飞行，不限制引擎中的合法事件循环。

反弹按表面单位法线计算 `v' = (v - 2(v·n)n) × restitution`，恢复系数为 0–1，默认 1。每次接触使用该 tick 尚未走完的时间继续扫掠，支持同 tick 多次碰撞。物理射线使用碰撞形状各包围盒的闭线段交点，包含恰好在 tick 末端 / 起点面上的接触和盒间接缝；从表面外接近不因前向采样被误判为嵌入。真正嵌在方块中的弹体直接结束。每目标次数独立于总穿透次数；同一目标必须先离开扩张后的碰撞箱，再进入才可再次命中，连续重叠不会每 tick 重复伤害。达到目标上限后，该目标不再阻挡此弹体。

每次接触，包括非终止的反弹 / 穿透，都会执行一次 `do`，各次有独立操作身份和结果帧。`sequence` 从 1 开始；`bounces` 是已成功反弹的次数，`entity_contacts` 是总实体接触次数，`target_contacts` 是此目标累计次数（其他类型为 0）。这些计数均包含当前接触。`count` 为此次目标集合的大小（0 或 1）；下节 ARRIVED 也带接收者，但不增加实体命中次数。可用 `result_flag.terminal` 限制只执行最终爆炸，也可将计数作为 `damage_snapshot.impact` 输入，驱动快照保留的命中期衰减表达式。

[projectile_collisions.json](../common/src/test/resources/effects/projectile_collisions.json) 用合成数值验证直击 20、一次反弹后 10，以及来源卸下后的保留；不声称这是某个 D2 弹体的实际数值。墙面反射与沿轨迹穿透可结合下节的追踪及接触转向；指定目的地 / 返回阶段见下文；玩家接回输入见下文；盾牌反射仍待实现。


### 追踪与接触后转向

`projectile.tracking` 可选；与下节 destination 都省略时保持纯弹道运动。以下均为通用机制的合成参数，完整程序见 [projectile_tracking.json](../common/src/test/resources/effects/projectile_tracking.json)：

```json
"tracking": {
  "radius": { "type": "chorus:constant", "value": 8, "unit": "meter" },
  "turn_rate": { "type": "chorus:constant", "value": 180, "unit": "degree_per_second" },
  "acquisition_angle": { "type": "chorus:constant", "value": 90, "unit": "degree" },
  "target_anchor": "body",
  "relation": "not_allied",
  "line_of_sight": true,
  "redirect_on_contact": true
}
```

`radius / turn_rate` 必填，都是非负且有限的 Value，允许为零。`acquisition_angle` 是相对于当前飞行方向的扫描锥**半角**，范围 0–180 度，默认 180（全方向）。数值在发射时求值并保留；未给出原作速度、角度或半径时，不会从技能名称猜测。其他默认值依次为 body、not_allied、true、false。

服务端每 50 ms 先加重力、乘 drag，再进行一次持续转向；角度最多 `turn_rate / 20` 度，保持该时刻速度的大小，然后执行扫掠。转向沿当前方向与目标方向之间的最短圆弧；恰好相反时选确定性的正交方向，避免零轴 / NaN。速度为零不凭空产生运动；目标丢失时继续当前弹道。

初次选取半径、扫描锥、阵营和可选视线内最近的已加载存活非旁观 LivingEntity，同距按 UUID 排序。排除施加者、当前仍重叠的目标及已经达到该弹体命中上限的目标。成功锁定后保留实体身份，逐 tick 使用目标当前取样点；更近的新敌人不会抢走有效锁定。死亡、移除、离开半径、阵营改变、视线失效或命中上限会使锁定失效，再依当前扫描锥重新选择。扫描锥只用于选取，不用于保持锁定。

`relation` 沿用 `any / allied / not_allied` 和原版 `isAlliedTo`；相对施加者的关系要求施加者仍在此维度，any 不需要它存在。`line_of_sight: false` 仅允许选取墙后的目标，不会关闭物理碰撞。这些条件控制追踪目标，不自动使弹体穿过友军；实际碰撞目标仍使用前节规则。目标观察不加载区块，也不触发伤害。

`redirect_on_contact: true` 是额外的接触行为：仅在弹体尚未终止时，于此次碰撞动作体完成、位置离开接触面后重新选敌，立即朝所选目标取样点转向，并继续本 tick 余下路程。此次瞬时转向不受持续转向率限制；未选到敌人时保留当前方向（方块已先按法线反射）。可以将 turn_rate 设为 0，只保留碰撞后的离散转向。转向不凭空增加可命中次数：实体必须获准穿透后才能继续转向，方块也必须有剩余反弹预算。每次接触的伤害与计数保持独立，因此相邻目标可在同 tick 被依次击中。

上述 tracking 负责自动选敌；下节 destination 负责固定身份的返回。玩家接回输入见下文；目标类别优先级、D2 转向率与各技能参数校准仍待实现。共享墙面 / 实体继续次数现由 `collision.total_continuations` 显式声明，见前节；具体技能当前支持范围见 [规则集](d2-ruleset.md)。

### 目标侧追踪限制

SOURCE 或 BUFF Bundle 可以拒绝针对持有者的自动追踪：

```json
"action_gates": [
  { "id": "untrackable", "action": "projectile_tracking", "subject": "victim" }
]
```

Chorus `EffectProjectile` 在常规几何、关系、存活及视线检查通过后查询此门槛。获得目标及接触后重定向使用 `start`，保留已有目标使用 `continue`。不限定阶段的声明会同时阻止二者；若只拒绝 continue，同 tick 后续 start 仍可能再次选中该目标。拒绝会失去锁定并尝试其他合格候选；没有候选就保留当前弹道。目标不存在永久黑名单，Buff 到期 / 来源移除后可以重新获得锁定。`subject: actor` 则限制发射者对应弹体的追踪，多个 actor / victim 拒绝继续合并。

查询 actor 是发射时的 owner，victim 是本次候选；source 从投射物捕获的 continuation scope 读取，包含原来源标签，不依赖武器 / 技能仍被选择或来源仍绑定。物理发射实体另放在 `references.emitter`。`references.tracking_owner_kind` 是宿主在发射时按信用 owner 捕获的 `player / mob / other / unavailable` 分类，owner 消失后仍保留；它不按物理 emitter、当前模式或来源标签猜测。该字段与下述当前实体观察分开：发射时的 mob 后来可以变为 unavailable 的当前实体，但原始分类不变。事件包含 `chorus:projectile_tracking`，numbers 提供 `tracking_age: second`、`tracking_distance: meter`（当前弹体到候选锚点），flags 提供 `tracking_redirect`，references 提供弹体 UUID `projectile`，并由门槛添加 `action / action_phase`。不要假设该查询还带普通技能输入的 on_ground 标志；姿态可从实体观察读取。

宿主在当前逻辑时间观察 owner、emitter 与候选的实体资料及 feet / body / eyes 位置；缺失实体明确为 unavailable，逻辑身份不凭空变成玩家或战斗人员。纯核心允许 `projectile_tracking` 在 actor 为空、victim 明确时只查询目标侧限制，其他操作仍要求 actor。没有这种门槛的程序跳过查询和额外观察。`projectileTrackingReport()` 仅保存最近一次实际候选查询（不一定是最终选中者）的输入、阶段、Decision 或错误，不发布效果事件。无法计算条件是运行时错误，会停止运行时并移除其全部关联 Chorus 弹体，不能冒充正常拒绝并继续世界副作用。

该门槛不阻止实体碰撞或伤害；失去追踪的弹体仍可能沿弹道命中受保护目标。`destination` 的明确接收者、自动抵达与接回不使用自动选敌门槛。现已连接 Chorus tracking 与原版 ShulkerBullet（见下节）；其他 AI 的目标选择与 Guardian 辅助瞄准仍待接线。Gambler's Dodge 已用上述 owner 分类在独立校准的动画窗口限制 Combatant 弹体，见 [规则集](d2-ruleset.md#gamblers-dodge-的位移与多充能回能)。`target_gates.json` 的 0.2 秒 Buff 是通用合成验收，不代表 D2 闪身时长，也不提升 Compendium 内容覆盖。


### 原版潜影贝子弹追踪

`ShulkerBullet` 在服务器每次 tick 的原版转向前，针对已有的存活 LivingEntity 目标查询同一 `projectile_tracking` 门槛。首次允许使用 start，持续允许使用 continue，拒绝后再次尝试使用 start；每 tick 只查询一次。目标仍由原版选定，不增加最近敌人搜索。正常拒绝只让本次 tick 进入原版无目标分支，保留真实目标引用、现有速度与原版重力 / 碰撞 / 命中 / Levitation / 销毁；到期后可以继续原目标。目标确实消失时仍按原版清空引用。没有运行时或没有这类门槛时不执行规则查询。

查询额外带 `chorus:native_projectile_tracking` 标签和 `native_projectile` 实体类型。actor 及信用分类 / 来源标签从原版 owner 首次可解析时捕获；首次 setOwner 即捕获，移除 owner 后保留，相同身份不会用后来的标签覆盖旧快照，重新指定其他 owner 会重建信用。原版 owner 不足以证明物理 emitter，`emitter` 留空，weapon / ability 也不猜测；source 使用弹体 UUID。当前位置观察与已捕获分类分开。距离使用目标 body 中心，`tracking_redirect` 为 false；飞行年龄使用已模拟服务器 tick 数，首 tick 查询为零，卸载期间不补时。

原版 NBT 保存 `chorus:tracking_credit` 与 `chorus:tracking_age`；重载只接受与原版 Owner UUID 一致的信用，缺失 / 不匹配则重新解析，未知身份保持 unavailable。重载后的首次查询重新使用 start，年龄继续。整个 tick 中真实 Target 引用保持存在，避免保存或原版传送时因临时失锁丢失引用。此处只保存原版弹体的查询资料，尚不包含完整规则运行时 / Buff / 技能状态持久化。

未知条件结果停止运行时并在运动 / 命中前移除当前原版弹体；其他受管理的原版追踪弹在下一次实际资格查询发现运行时失败后停止。它们不属于 Chorus 飞行物注册表，不声称故障瞬间清理全世界所有原版弹体。原版伤害归因仍由原版 DamageSource 负责，这个追踪适配器不重写伤害来源。忠诚三叉戟的明确返回、末影之眼的目的地导航，以及其他模组追踪弹未因此自动接线。

`NativeTrackingGameTest` 的9项双端共享场景覆盖无运行时、两侧限制及阶段、重力和真实引用、owner 消失、存档 / 重指定 / 无效旧信用、实际伤害与飘浮、失败不重放，以及真实 Gambler 闪身的到期恢复和 Guardian 区别。Shulker 的原版转向与恢复策略属于 Minecraft 适配政策，不是 D2 原作弹道 / 重新锁定时序的校准证据。

### 指定目的地与返回阶段

`projectile.destination` 固定一个实体身份并逐 tick 追踪其当前位置；与自动选敌的 tracking 互斥。它允许 self，也接受 victim 或已经验证的 `{ "binding": "receiver" }` 目标。示例字段放在 projectile 内：

```json
"destination": {
  "target": "self",
  "turn_rate": { "type": "chorus:constant", "value": 3600, "unit": "degree_per_second" },
  "arrival_radius": { "type": "chorus:constant", "value": 0.3, "unit": "meter" },
  "target_anchor": "body",
  "collide_entities": false
}
```

target / turn_rate / arrival_radius 必填；数值有限非负，在发射时求值；anchor 默认为 body，也接受 feet / eyes。目标身份在发射时固定，后续选择变化、来源卸下或更近的其他实体不会替换它。目标须为此维度已加载、存活、非旁观 LivingEntity；缺失、死亡、移除或跨维度均在下次物理观察时终止为 target_lost，不改追其他实体，不加载区块。发射回执仍只确认实体生成，目标资格由飞行时观察。

需要锁定某一次生命时，先用 `capture_entity_anchor` 保存观察，在 destination 内加 `"reference":"selected_life"`。reference 是有类型 ENTITY_ANCHOR 结果的词法绑定，target 仍必填且必须指向同一个 UUID；普通位置、方向或字符串不能替代该引用。引用可以随扫描后的 detached 延迟、独立投射物与领域检查点保留，发射时不再捕获当前新生命。已知缺失的观察需要内容显式分支；读取不可用观察或 target / reference 不一致会在发射前停止，而不是降级为无保护目标。

声明 reference 后，原生飞行每次读取目的地时核对原维度与生命代次；相同 UUID / 数字实体 ID 的新生命得到 TARGET_LOST，不继续追踪、抵达或重选。主动接回也在实际输入边界检查同一引用，避免重生玩家在下一个投射物 tick 前领取旧飞行的收益。普通移动及保留相同生命的原生保存重载 / keep-all 玩家替换仍有效；回执未知继续保留世界写入且不重放。

省略 reference 保留原来的 UUID 身份语义，旧内容不自动获得新资格限制。目的地声明与已解析参数各新增 `_v2` 存储 ID，旧 ID 保留原字段并显式迁移为 reference 为空；旧存档没有观察过生命，迁移不会查询世界并补造它。程序、旧领域检查点及延迟发射均验证了迁移；这不代表正在飞行的原生实体已支持跨服务器重启。

每 tick 沿最短圆弧最多转 turn_rate / 20 度，保持重力和 drag 处理后的速度；碰撞反射后不会额外重置这个转向额度。目的地模式没有自动选敌的半径、锥角或阵营条件，也不额外执行目的地中心的视线检测；实际飞行路径仍做方块碰撞。

抵达区域是以接收者当前 anchor 为中心、arrival_radius 为半径的闭球。使用本次完整扫掠线段的第一个入球点，两个端点都在球外也能识别高速穿越；起点已在球内且未被碰撞阻挡时立即抵达，包括零速度。抵达点之前的方块 / 有效实体碰撞照常处理，抵达点之后的地形不再检查。collide_entities 默认为 true，控制其他生物是否沿用普通碰撞策略；false 明确允许穿过其他生物。接收者自身始终使用抵达球，不以其碰撞箱触发普通 ENTITY，原施加者也可以是接收者。

ARRIVED 携带接收者身份，`count=1`、`arrived=true`、`terminal=true`；它增加 sequence，但不增加 entity_contacts / bounces，target_contacts 为 0。TARGET_LOST 没有目标、count=0。两者都在执行 do 之前消费弹体；不会自动伤害、返还能量或视为玩家“接住”。内容应按 result_flag.arrived 显式执行相应动作；仅遍历目标集合并不代表发生了伤害命中。

[projectile_return.json](../common/src/test/resources/effects/projectile_return.json) 的可执行合成示例从普通玩家技能入口支付成本并保留退款凭据。去程实际命中后造成伤害，再以该 impact 位置启动另一枚 destination:self 弹体；抵达才返还成本、按 credited 治疗并关闭凭据。新阶段拥有独立的物理寿命 / 接触计数，原 impact、来源和有限期成本句柄仍由词法捕获保留。世界结果未知时保留已完成的伤害 / 退款 / 治疗，消耗飞行且不重放。

当前支持返回移动实体和到达后动作，接回窗口见下节；尚未提供原飞行实体内切换阶段、独立场物体或跨维度 / 重启恢复。示例 20 m/s、3600 度每秒、0.3 米、伤害和退款比例均为合成参数，不是 Threaded Spike 的已校准定义。


### 玩家主动接回

在 `projectile.destination` 内增加可选 `catch`，即可允许**发射时保存的接收者**主动接回；默认没有这个能力。普通返回施放者使用 `target: self`；显式指定另一玩家时，只有该玩家可以接住。catch 不自行改变技能选择或支付新技能成本。

```json
"catch": {
  "radius": { "type": "chorus:constant", "value": 2, "unit": "meter" },
  "opens_after": { "type": "chorus:constant", "value": 0.05, "unit": "second" },
  "closes_after": { "type": "chorus:constant", "value": 0.5, "unit": "second" },
  "line_of_sight": true
}
```

radius 有限非负；opens_after 默认 0，closes_after 必填且严格大于 opens_after，两者为有限的非负整微秒时间。数值在发射时求值。窗口以**这枚弹体的物理飞行年龄**为基准，左闭右开 `[opens_after, closes_after)`；原飞行终止或寿命耗尽后不能接回。它不是原施放的累计时长，新建返回弹体的年龄从 0 开始。

服务端接收输入时按接收者当前 destination.target_anchor 检查闭球距离，并在 line_of_sight（默认 true）开启时检查弹体到该 anchor 的方块碰撞形状；未知地形不算可见。不开启视线检查是内容的明确选择。客户端不提交施放者、弹体、位置、时间或返还比例。只查询本维度已加载、仍属于当前运行时的弹体，玩家必须存活且非旁观者。一次输入只消费一个合格弹体，按距离从近到远，等距按 UUID 排序；失效输入不会等待未来窗口，也不提供延迟补偿。

成功产生 `CAUGHT`：`caught=true`、`arrived=false`、`entity=false`、`terminal=true`，count 为 1，目标为接收者。它增加 sequence，保持碰撞 / 反弹计数，target_contacts 为 0；先消费实体再执行 do。自动抵达仍只产生 ARRIVED，两个结果分别配置动作。使用 `for_each` 访问接收者不代表造成了碰撞伤害。未知世界结果保留已提交退款与实际动作，停止派生且不重放。

两端提供默认 **G** 键（控制设置可改）和低权限自用命令 `/chorus ability catch`。网络请求携带连接内递增序号和当前维度；服务端在执行或拒绝前消费序号，重复或倒序请求不会接住下一枚弹体，断开连接后清理序号。菜单中不发送按键请求。当前是独立接回键；chorus-d2 仍需装配近战键复用、窗口提示与技能动画，尚无这些交互的成品界面。

[projectile_catch.json](../common/src/test/resources/effects/projectile_catch.json) 用合成值区分普通抵达返还实付成本的 25% 与主动接回返还 100%，并将实际 credited 用于治疗；不是 Threaded Spike 的能量表。原表若表示固定充能比例，应使用 grant_resource，并单独装配命中 / 击杀档位。


### 跨回调共享伤害统计

多次穿透 / 反弹、连锁、延迟伤害与返回收益可以显式共享一个有限期 `DamageTallies.Handle`。`begin_damage_tally` 创建空统计；`record_damage` 只接受类型为 DamageReceipt 的动作结果，不接收客户端给出的伤害 / 击杀数字。示例片段分别放在施放、各次伤害、最终返回的动作体内：

```json
{ "action": { "type": "chorus:begin_damage_tally", "duration": { "type": "chorus:constant", "value": 5, "unit": "second" } }, "as": "flight_tally" }
```

```json
[
  { "action": { "type": "chorus:damage_snapshot", "snapshot": "attack", "target": { "binding": "victim" } }, "as": "hit" },
  { "type": "chorus:record_damage", "tally": "flight_tally", "damage": "hit" }
]
```

```json
[
{ "action": { "type": "chorus:close_damage_tally", "tally": "flight_tally" }, "as": "totals" },
{ "if": { "type": "chorus:result_flag", "binding": "totals", "field": "available" }, "then": [
  { "type": "chorus:heal", "amount": { "type": "chorus:scale", "factor": 3, "from": "count", "to": "damage", "of": { "type": "chorus:result", "binding": "totals", "field": "kills" } } }
] }
]
```

`read_damage_tally` 使用相同 tally 参数，只读取当前汇总，不关闭。record / read / close 的结果具有以下字段：

| 字段 | 语义 |
| --- | --- |
| available | 统计在本次读取时仍存在且有效；关闭动作返回关闭前的最终快照 |
| changed | record 新记录了一个 damage_id，或 close 实际移除了统计；只读与重复记录为 false |
| attempts / hits / effective_hits | 所选唯一伤害回执数；除 CANCELLED / FAILED 外的回执数；实际 HP + Chorus 盾 + Absorption 损失大于 0 的回执数，均为 count |
| kills | 所选回执中不同 death_id 的数量，count；来自已确认的实际死亡 |
| health_loss / shield_loss / absorption_loss | 分别累计实际损失，damage；不计过量请求，不自动统一不同血池的承伤倍率 |
| effective / effective_with_absorption | HP + Chorus 盾损失，或再加 Absorption；与单次 DamageReceipt 投影一致 |

“所选”指内容显式执行 record_damage 的回执，不会自动搜集整棵事件树、原版攻击、其他来源伤害或同源 proc。每份回执按 damage_id 在**此统计内**去重；相同 ID 却有矛盾结果会拒绝，重复的同一 death_id 不重复记击杀。这不阻止合法连锁或不同伤害继续计数。IMMUNE / BLOCKED 依照现有 hit 事实语义计入 hits；具体技能若要求有效伤害，应使用 effective_hits，或在 record 前按回执条件筛选。它也不等同于不同敌人数、碰撞次数或整枪弹丸资格。

句柄可被多个飞行 / 延迟回调捕获，读取时访问当前共享统计；**读取结果本身是不可变快照**，再捕获它不会自动刷新。不同 begin 动作产生不同身份，即使施放者和技能相同也不混合。来源卸下或技能选择改变不清空它。显式 close 先删除统计，再让后续动作使用最终快照；到达 duration 截止点时由 EffectClock 先清理，即使弹体已外部移除、没有任何回调也会清理。关闭或到期后的访问返回 available=false、changed=false，数值投影为 0；内容必须检查 available，不能把缺失当作真实零命中。

统计不授予退款权限，不自动返还资源或触发效果。固定充能收益使用 grant_resource；实际成本返还仍需成本回执 / retain_cost。世界结果未知时，未取得回执的动作不会被推测记入统计；已关闭统计、已授予资源及已发生的世界动作保持提交，不重放。

可执行的 [tally_return.json](../common/src/test/resources/effects/tally_return.json) 让去程实体在三次实际接触后终止，仅在 terminal 分支新建回程。各次伤害先分别入账；回程抵达或接回时关闭汇总，用累计 hits 计算两种回能，用累计 kills 治疗。此例的三个目标、追踪参数、每击 10% / 20% 回能和每杀治疗 3 均为合成值。它证明跨阶段统计；另有 [threaded_spike.json](../common/src/test/resources/effects/threaded_spike.json) 按 Compendium 组装九目标、原表档位和 Woven Mail，必填校准参数与内容边界见 [规则集](d2-ruleset.md#threaded-spike-技能模板)。句柄不支持从任意事件 / Buff 按字符串查找。协调宿主内迁移及联合检查点编码支持如下；物理飞行实体的跨进程恢复仍需独立协议。


### 伤害统计的签发域路由

协调编译模式将 begin / record / read / close 四个动作编译成 `ResourceRoutes.Request`，从真实挂起指令与不可变绑定准备 `EffectHandleRequests` 原语。`begin_damage_tally` 用当前调用域、操作身份和该域时钟创建带 issuer 的句柄。读取也经过协调器，不在调用域预读同名账本；目标不存在签发域时明确失败，不扫描其他域。原语只修改 issuer 的统计，协调器共同发布该状态和调用域的确定回执。统计没有资源 recipient 或资源变化事实，也不凭持有者当前 lease 决定归属。

角色 A→B→C 时，来源 / Buff 自有 continuation 可以携带句柄，issuer 始终为 A。原记录的 startedAt / dueAt 保持，过期由 A 的逻辑时钟判断；continuation 自身延迟仍按迁移协议保留剩余时间。B 中的伤害实际在 B 执行，收到 DamageReceipt 后才记录到 A；后续在 C 读取、关闭及收益执行仍遵守各动作自己的归属，不把整个动作体搬回 A。相同回执重复记录沿用 damage_id 去重；同一活动 WorldOperation 重复调用返回已提交回执。提交后宿主回调异常保留账本变化和 pending，禁止成功检查点或自动重放。

旧未限定句柄在明确迁移边界由 sourceDomain 补齐。没有迁移的旧本地句柄可在明确调用域访问原账本；目标必须启用协调路由，本地 DSL 拒绝使用任何 issuer-qualified 句柄。账本 `matches` 比较全部原身份字段，并拒绝双方已限定但 issuer 不同；它不改变 Java record 的严格 equality。记录新回执保留账本 canonical handle，旧别名不能清除已知 issuer。历史 Summary 仍是不可变数值快照。

四种 live Handle 分别增加新的 handle_v2 存储身份，原四个 handle ID 对应精确旧布局迁移；新增六个 tally 请求 / 回执类型，原 730 项存储形状不变。联合检查点同时保存 A 的账本与 B 的 continuation；恢复不重新 begin、不刷新期限或重放已确认伤害。这项协议不代表 DamageGroups、ShotGroups、EntityAttachments 已能跨域继续，后三者的内部伤害与世界观察入口仍待接线。

### 伤害组的签发账本与显式参与者查询

协调模式的 `begin_damage_group` 通过 `EffectHandleRequests.GroupBegin` 在调用域创建带 issuer 的组；`end_damage_group` 通过 `GroupClose` 回签发域关闭并取消原到期计时器。账本变更与调用域的确定回执共同发布，重复活动请求返回已提交回执。关闭不依赖角色当前资源 lease，也不查找目标域的同名组。新增 GroupBegin / GroupClose / GroupStarted / GroupClosed 四种存储身份；原 740 项形状保持，总数为 744。

普通本地 DSL 拒绝带 issuer 的组；协调查询按 issuer 查账本和原域时钟。本批默认 `prepareDamage` 只接受调用域的组，在发出世界伤害前拒绝其他 issuer。`Pending.relocated` 继续拒绝直接捕获的 damage group，关闭账本的路由不代表组成员已经能随角色迁移执行。

`ResourceReadView.combatParticipants()` 是纯查询的显式入口。调用方须先独立确认受管参与者的角色、来源和 Buff 与其资源目录归属同域，并从资格捕获到结算固定参与者的生命和位置。只迁移资源账户不满足该前提。普通资源视图、DSL 动作和现有原生伤害保持本地 Buff / 来源语义；没有自动开启这一模式。

显式模式分别读取施加者、受击者和第三方条件目标的状态，以各自时钟判定 Buff 活性；未知持有者只按最初物理调用域解释，不扫描其他世界。嵌套属性、装备和已知武器所有者的弹药查询延续同一参与者映射。分组资格仍保留在 issuer，跨域来源和历史保留资格的数值贡献使用独立身份，避免各 BuffStore 的相同 generation 相撞。

`CombatDomains.view` 在不可变快照上计算嵌套攻击的消费预留，不发事实、不提交状态；`finish` 仅依据实际回执返回各域的候选写入、已结算回执和按域划分的 Buff 生命周期事实。已有原生观察保持；缺失的 Buff 观察在消费前按参与者时钟捕获，观察的事件时间属于物理调用域。候选写入必须由宿主共同发布，已标记结算的回执不会再次扣除或发事实。此工具要求与显式查询相同的参与者放置前提，目前未接入协调器的默认完成路径或原生伤害边界。

完整原生接入仍需独立且可核验的角色位置快照、跨运行时共享预留与联合发布；资源目录不能替代角色归属。伤害快照的历史捕获时钟与异钟世界命中也仍需独立协议。本节不改变合法反馈循环、不提供全局反应次数上限，也不增加 Compendium 完整覆盖声明。

后续位置协议需要分别记录角色 / 来源状态、Buff holder 状态和实际实体绑定，资源 lease 与 group issuer 保持独立。Buff 为空也须保留位置授权；仅资源交接不能移动角色位置，Buff-only 交接不能移动角色 / 来源位置。武器附着 Buff 的 holder 是武器实例，当前 carriedGear 的弹药 / 射击迁移不能视作这些 Buff 已迁移。纯查询与实际消费必须使用同一冻结的位置版本和生命身份，不能在回执时重新解释旧候选；这些要求尚未实现为原生协议。


## 共享 Strand 防御示例

[strand_defense.json](../common/src/test/resources/effects/strand_defense.json) 声明 Sever、Woven Mail 与 incoming Profile，outgoing 由 [combat_damage.json](../common/src/test/resources/effects/combat_damage.json) 提供；[slice.json](../common/src/test/resources/effects/slice.json) 是引用 Sever 的片段，必须与这两个模块及 continuity.json 同版本链接后编译，数据包可用 imports / fragment 组织，不能单独启动 slice。strand_inputs.json 仅提供合成触发和超能测试，未装配真实 D2 技能。

Sever 通过受影响持有者的 outgoing Profile 修饰输出。伤害命令须显式选择 chorus_d2:outgoing；原版 nativeSource 默认不选此 Profile，生产宿主仍需装配。默认定义时长为 10 秒。Slice 先用 calculate 查询施加者的 strand_debuff_duration，再把 10 / 5 秒基础加上已装备 Continuity 的 5 / 2.5 秒扩展，作为 apply_status.duration；不能把任意来源的未知时间自动替换成默认值。

Woven Mail 的 defense Profile 从受击者读取。守护者攻击分类使用 event_source_tag:chorus:guardian，精准 / 近战用 event_tag:chorus:precision / chorus:melee_damage，不能把原施加者的 source_tag 当成当前攻击者。移除规则监听 chorus:ability_started，并以 target_is(self, event_actor) 匹配受益者；source_is:owner 在 Buff 上匹配的是施加者，队友授予时会选错人。旧状态先移除，on_use 重新授予的同名状态可保留。数值基线、刷新策略及待校准项见 [D2 规则集](d2-ruleset.md#sever-与-woven-mail)。


### 共享计时器的 max_remaining 刷新

`refresh: "max_remaining"` 将共享截止时间设为 `max(旧截止时间, 当前时间 + 新授予时长)`。`grant_buff` 与 `refresh_buff` 都遵守此策略；后者不增加层数或制造获得事件。暂停时使用暂停逻辑时钟，恢复时再平移截止时间；永久效果不会被有限时长缩短，到期后重新授予从新时长开始。每层独立计时仍只接受 `none`。它与 `historic_max` 不同：旧 10 秒效果余 1 秒时，新的 2 秒只留下 2 秒，不恢复成 10 秒。共享 Woven Mail 已采用此策略；Sever 和 Restoration 原有策略不变。

## 动作序列中的数值 Profile 查询

### 在公式中读取实体属性

`chorus:attribute` 是只读 Value，查询指定实体的当前属性，输入和输出必须均为 stat_point。它用于把装备 / 碎片加值和属性限幅的结果交给回能、冷却或伤害曲线，而不是让每条曲线直接读取未经修饰的基础组件：

```json
{"type":"chorus:attribute","profile":"chorus_d2:melee_stat","target":"self",
 "input":{"type":"chorus:constant","value":0,"unit":"stat_point"}}
```

`target` 默认 self，也支持现有目标引用。input 在调用处求值；Profile 在被查询实体的独立上下文中计算：actor / victim / source.owner 均为该实体，source.source 为 Profile ID，武器 / 技能、标签、测量、引用及历史观察为空。属性修饰自己的绑定来源仍可读取。这样外层武器击杀等条件不会意外参与实体属性；其他单位或单位转换可用下面的profile_value，需要保留触发上下文的查询仍用calculate动作。

查询不写状态、不发事件、不执行世界请求，缺失 Profile / 错误单位 / 显式组件输入缺失明确失败。CompiledEffects.attribute 返回包含基础输入及贡献的完整 CalculationProfile.Result，表达式读取其输出；外层轨迹保存最终贡献数值，暂不自动嵌套属性的子轨迹。

on_use 的来源属性必须在捕获时可求值，结果保存为常量；victim 属性保留为表达式，其 input 的来源操作数先冻结，命中时使用当前目标及当前程序的属性定义。缺失或不兼容的新定义明确失败。属性依赖按实体 + Profile 区分，跨实体读取同一 Profile 合法；直接、相互以及通过动态弹匣容量返回自身的数值依赖会报错，不改写状态或截断效果事件链。完整例子见 [attribute_queries.json](../common/src/test/resources/effects/attribute_queries.json)，其中加值与治疗 / 伤害比例均为合成验收输入。

### D2 装备属性输入

`character_stats.json` 定义 Health / Grenade / Melee / Class / Super / Weapons 六个属性 Profile，每项合计来源贡献后限制在 0–200。`armor_stats.json` 的 SOURCE Bundle 要求六项 stat_point 参数，按实际装备来源累加，不先钳制或改写物品原始值。`armor_stat_inputs.json` 展示 helmet / arms / chest / legs / class_item 五槽与显式物品参数映射；其中 `test:armor_*` 和每项 0–200 的输入范围只是验收原型，不是原作合法掉落生成器，不校验六项总点数、金装特例或 archetype。

实际技能定义通过 `effects.energy_scaling.bundle` 选择 `chorus_d2:arcbolt_energy_scaling` 或 `chorus_d2:threaded_spike_energy_scaling`，两者查询零输入的最终 grenade_stat / melee_stat 再套用已有主动 / 被动曲线。裸装、首次选择和只有碎片时均无需先创建属性 Buff。卸载技能来源后该曲线不再贡献，资源账户仍按基础声明及剩余来源运行。Arcbolt、Duskfield 和 Bleak Watcher 的能量片段须链接 `grenade_energy.json`，并统一支付 `chorus_d2:grenade_energy`；各自来源提供基础率 / CES、includes 共用属性曲线，修饰以 resource 引用限定共用手雷账户。资源零基础值及 Aspect 的槽位条件使空槽暂停；Threaded Spike 仍保留原有独立资源基础值。内容迁移不能同时保留旧 CES 曲线。

原 `grenade_stat / melee_stat` Buff 现在只把 points 加进对应属性 Profile，可用于独立的角色固有输入；没有时贡献为零。宿主若继续填写这些组件，须只填未由装备来源表示的点数，不能把已汇总护甲值再填进去。查询 Profile 的 input 同样用 0，避免把组件既作为 input 又作为修饰加两次。Buff 存储值与实际 Gear 数值均不随最后的限幅改写。

5 项 ArmorStatInputsTest 与两端实际玩家护甲换装 / Firesprite 拾取 / Threaded Spike 回能验收覆盖这条链路。该阶段未实现 Health / Class / Super / Weapons 的全部玩法消费方，也没有正式护甲掉落、职业限制或完整属性 UI；Arcbolt 的合成选择不能当成完整手雷动作实现。来源与曲线冲突见 [核对记录](../data/d2-research/2026-10-11/armor-stat-inputs.json)。

### D2 增强属性伤害

[combat_damage.json](../common/src/test/resources/effects/combat_damage.json) 独立定义 `chorus_d2:outgoing`，使用它的 Strand 状态 / Threaded Spike / Arcbolt 组合须显式链接该模块。`ability_stat_damage.json` 依赖这个 Profile 与 `character_stats.json`，提供 `chorus_d2:ability_stat_damage` SOURCE；由宿主按角色绑定，不能只在选中近战或装备某件护甲时启用，因为未充能 / 偃月近战也可受益。重复来源经过同阶段 MAX 只贡献一次，不表示引擎已自动安装这个来源。

两个修饰分别要求 melee_damage / grenade_damage 且排除另一个标签，查询 self 的对应属性、输入 0 stat_point，扣去 100 后下限为 0，再换算成 delta。multiply 动作接收的是增量，例如 PvE 200 近战点数贡献 `0.3 delta`，阶段应用 `1 + 0.3`，不能传入 `1.3 multiplier`。阶段与 perk / outgoing_debuff 分开；普通路径同时有两种信用时均不生效，抓钩等混合公式要提供独立内容规则。

on_use 在 capture_damage 时冻结来源属性；没有 capture_damage 的直接伤害则每次查询。Arcbolt 延迟连锁冻结、Threaded Spike 接触时读取当前物理装备这两种策略已验，仍待原作取样时机校准。来源坐标、倍率、缺口与完整例子见 [D2 数值规则](d2-ruleset.md) 和 [核对记录](../data/d2-research/2026-10-11/enhanced-ability-damage.json)。这些模块仍是测试资源，没有改变发布 jar 的内容集。

### 显式计算动作

`chorus:calculate` 在当前已提交状态上执行一次只读查询，返回带类型的 `input` / `value` 字段。它不消费资源、不修改 Buff、不产生事件或世界请求。Java 的 `CalculationActions.Result` 保留被查询持有者、最终查询上下文与完整 `CalculationProfile.Result`，可检查各阶段、贡献来源、版本和置信度。

```json
{"action": {
  "type": "chorus:calculate",
  "profile": "chorus_d2:strand_debuff_duration",
  "target": "source_owner",
  "input": {"type": "chorus:constant", "value": 10, "unit": "second"},
  "tags": ["chorus_d2:sever"],
  "numbers": {"continuity_extension": {"type": "chorus:constant", "value": 5, "unit": "second"}}
}, "as": "duration"}
```

随后 `apply_status.duration` 可以使用 `{"type":"chorus:result","binding":"duration","field":"value"}`。完整 PvE / PvP 例子在 [slice.json](../common/src/test/resources/effects/slice.json)，需要同版本链接 strand_defense.json 和 [continuity.json](../common/src/test/resources/effects/continuity.json)。

| 字段 | 语义 |
| --- | --- |
| profile | 必填，当前程序中的 Profile id；输入与输出单位分别由该 Profile 校验/推导 |
| target | 查询谁身上的修饰，默认 self；可用 source_owner 或已有逐目标绑定。它不改变规则绑定来源 |
| input | 必填，查询的基础数值表达式，必须符合 Profile 的输入单位 |
| origin | 默认 bound，也可用 event；只选择查询 source，不改变 target、actor 或外层表达式读取的状态归属 |
| victim | 可选，覆盖查询上下文的 victim，可指向当前逐目标绑定；省略时保留触发上下文 victim。它与“读取谁的修饰”的 target 是两件事 |
| tags | 只用于这次查询的显式标签，默认空；不自动继承触发事件标签，以免命中/击杀标签误触发另一属性的修饰 |
| numbers | 查询测量表达式；在外层上下文求值，然后覆盖同名触发测量，其余测量保留。单位随值保留，不静默转换 |

actor、flags、references、impact 仍来自触发上下文；Buff 生命周期动作可使用已有 timerEvent 上下文回退。query 不修改原事件。`origin:event` 也不会使 `target:source_owner` 改指事件施加者：source_owner 和 input/numbers 仍按原规则绑定求值，若需查触发者应显式选择 event_actor。

编译拒绝未知 Profile、错误输入单位、未绑定目标/结果及未知字段。value 使用 Profile 的输出单位，因此经过单位转换的 Profile 可产生与 input 不同单位的结果；消费动作再次校验自己的单位/范围。查询不自动修正负值、缺失测量或未定义档位，条件实际读取了缺失/错误单位的测量就失败，不猜测数值。

结果是不可变快照，允许跨 `after` / projectile 捕获。先 calculate 再延迟会保留原结果；把 calculate 放进回调会重新读取届时的修饰。`evaluate:on_use/on_hit` 只控制伤害快照的采样方式，独立 calculate 按当前查询求值所有合格贡献，不自动延迟或构造伤害快照。需要影响状态时间时，应先明确原作究竟按发射、施加还是其他时机取样。

Continuity 的 fragment Bundle 只贡献该次查询提供的 extension，生产者只声明已知基础时间与对应扩展，不直接检查 fragment。采用 MAX 避免重复装配同一 fragment 叠加。该入口可表达 Sever 10+5 / 5+2.5，以及已接线的 Suspend 6+2 / 3+1 / 2+1；Suspend 的控制、Boss 例外和剩余缺口见 D2 规则文档。

Durance 复用同一机制：链接 [stasis_duration.json](../common/src/test/resources/effects/stasis_duration.json)、[durance.json](../common/src/test/resources/effects/durance.json) 与 character_stats.json。查询 `chorus_d2:stasis_duration`，以 `target:source_owner` 读取施加者，显式传入 `durance_extension: second`；`chorus_d2:slow` 和 `chorus_d2:durance_lingering` 为合格查询标签。Slow 来源新增必填非负 `slow_durance_extension`，无延长填 0，不使用隐式常量。不同生产者和接收者选择不同基础时间 / 延长；持续技能存在时间另查一次。查询不自动延长 Freeze，不修改已提交期限，也不实现炮台或持续场本体；完整内容约定和验收边界见 [Durance](d2-ruleset.md#whisper-of-durance分别计算状态与技能存在时间)。

### 有序 Profile 组合查询

`chorus:calculate_pipeline` 接受非空 profiles 列表，其余 target / input / origin / tags / numbers / victim 与 calculate 相同。先解析所有 Profile，检查输入与相邻段的单位，再按列表顺序执行。每段使用同一已提交状态、持有者和显式查询上下文；上一段输出成为下一段基础输入，不改写事件 numbers，也不自动加入中间值测量。

```json
{"action":{"type":"chorus:calculate_pipeline",
 "profiles":["example:reload_stat","example:rifle_reload_seconds","example:reload_animation"],
 "input":{"type":"chorus:constant","value":30,"unit":"stat_point"}},"as":"reload_query"}
```

结果 input / value 分别采用首段输入和末段输出单位，可像普通 calculate 结果一样跨延迟 / 投射物保留。Java 的 CalculationPipeline.Result.steps 按发生顺序保留每段完整 Result，包括 Profile / 版本、输入、贡献归约、置信度、命名因子与阶段轨迹。同名阶段或重复 Profile 不合并；明确列两次就执行两次。未知 Profile、空列表、单位断裂在编译时拒绝。

withBase / withoutFactors 只重算已保存的数学输入，不重新读取状态、表达式或调用原收集器。上游变化会逐段重新送入下游曲线 / 限幅；移除因子按命名匹配各段，使用重算而非除法，所以零倍率也能处理。已选贡献的置信度跨段汇总。这里是有限顺序查询；没有改变事件队列、合法效果循环或单个攻击快照的结算协议。

## 可受伤构造物

`chorus:spawn_construct` 是世界动作，先消费已捕获的 POSITION，再返回生成回执。种类用于内容身份和实体标签，不是新的注册实体类型。

```json
{
  "action": {
    "type": "chorus:spawn_construct",
    "position": "point",
    "kind": "example:turret",
    "health": { "type": "chorus:constant", "value": 150, "unit": "damage" },
    "width": { "type": "chorus:constant", "value": 0.5, "unit": "meter" },
    "height": { "type": "chorus:constant", "value": 0.75, "unit": "meter" },
    "lifetime": { "type": "chorus:constant", "value": 25, "unit": "second" },
    "origin": "bound",
    "tags": ["example:damageable"]
  },
  "as": "spawned"
}
```

示例尺寸仅是合成输入，不能作为 Bleak Watcher 的原作几何。生命值和尺寸要求有限且大于零，寿命要求正的整微秒；表达式在生成时求值一次。`origin` 默认 bound，也可选择 event，保留施放者 / 武器 / 技能信用。生成请求固定当前程序版本，宿主拒绝错配运行时。

回执 `count` 为 0 或 1，提供 `spawned / missing_position / wrong_dimension / unloaded / out_of_bounds / unsupported_parameters / obstructed / rejected` 标志。它是 TARGET_IDENTITIES 集合，可用 `for_each: "spawned"` 对成功生成的实体施加 Buff、捕获位置或继续查询；失败为空集合，不会创建虚假身份。回执必须对应原请求。世界结果未知时停止，不自动重试；已经生成的实体和此前支付不会回滚，依赖回执的行为不会继续执行。

Minecraft 使用 `chorus:effect_construct`，具有独立 UUID、真实生命值和同步的碰撞箱尺寸；始终带 `chorus:construct`、kind 和声明的 tags。现有 `inspect_entity / select_targets`、伤害管线与普通死亡事实适用，内容须显式区分构造物与其他目标类别。当前实现无 AI、自主移动、重力、经验和掉落，不能进传送门；铁粒渲染仅供占位。宿主在完整碰撞箱及一格查询边距内预检已加载区块，不加载未知地形；边界或实体 / 方块碰撞拒绝生成。单边尺寸最多 16 格，生命值必须可由原版 MAX_HEALTH 属性及正 float 表示，超限明确拒绝，不静默裁剪。这些限制只约束一次物理生成请求。

生命周期为 `[creation, expiry)`。到期即不再被存活查询选中，随后实体 tick 清理；施放者卸下来源、死亡或被移除不自动结束构造物。程序运行时关闭立即清理自己生成的构造物；运行时失败时实体失活并在下一次 tick 清理。实体不写存档。

行为 Buff 是内容声明，未由 spawn 隐式创建。推荐让它持有相同期限的自有定时器，在每次攻击前检查 self 的 available / alive；在 `chorus:death` 及 `chorus:construct_ended` 的 victim 等于 self 时移除该行为。这样只取消对应实例的未来行为，已脱离行为生命周期的投射物可以继续。直接删除或区块卸载也有结束事实，不必等下一次攻击轮询才移除行为。引擎不按 UUID 批量删除所有相关状态：其他实体上保留的施加来源、攻击信用与历史引用继续存在。

成功加入世界的构造物首次移除时发布 `chorus:construct_ended`。actor / source 保留生成时的原施放者与完整 Origin，victim 和 references.construct 是被移除的构造物；references.construct_kind 为种类，reason 为原版移除原因（如 discarded / unloaded_to_chunk）。flags 的 destroyed / expired / removed 互斥：实际生命已为零或 KILLED 原因属于 destroyed，其次已到期限属于 expired，其余为 removed；numbers.age 为移除时捕获的秒数。它不是伤害回执，不再产生一次 death / kill，也不隐式退款、爆炸或授予奖励。监听当前已绑定规则，保留信用不表示重新装配已卸下的来源。

事实的 observedEntities 记录该实体已不可用，同时保存最后 FEET / BODY / EYES 位置。`read_event_position` 可读取移除位置以声明后续范围效果；实时 capture_position 仍按当时世界查询，不因历史快照伪造一个存活实体。重复 remove / setRemoved 不重写原因、位置或发布第二份事实；生成拒绝和未加入世界的临时对象没有结束事实。

原版 onRemoval 回调只记录事实，不递归进入规则。世界动作内的移除附在该动作的成功回执上，父 Frame 完成后才运行派生反应；外部移除在下一次宿主事件 / prepare 边界处理，普通死亡事实仍先按伤害管线结算。未知世界结果停止且不重放结束反应。显式 checkpoint 会先结算待处理事实；已经固定的检查点遇到新移除事实则复核失败，不能保存一个尚未处理的结束事件。运行时关闭、替换和正常停服的固定保存阶段继续静默清理，不发送游戏奖励事件；保存时仍活动的短命实体在重启后的规则处置另待实现，不能由这一事实入口宣称已解决全部短命状态持久化。

内容仍须声明友敌参照、施放者失联策略、攻击规则和伤害归属。Minecraft 的目标查询与投射物追踪现按构造物的原始 owner 投影阵营；同 owner 可直接确认为 allied，其他关系须解析双方 owner 后读取当前原版队伍，解析失败为 unknown。该投影不递归遍历所有权链，不替换原版 AI 的队伍逻辑，也不隐式禁止伤害动作。

验证：`ConstructTest` 的身份、归属、独立 Buff / 定时器、结束原因 / 信用、失败和单位检查；共享 `ConstructGameTest` 的真实生命损失 / 死亡、独立实例、到期、未知结果及不加载区块；`ConstructLifecycleGameTest` 的直接移除、原版 setRemoved 卸载入口、自然到期、死亡不重复击杀、历史位置、父 Frame 顺序、未知结果与关闭静默；检查点边界另验证未消费的结束事实使归档失效。`ConstructClientGameTest` 的同步尺寸、生命值、占位渲染和关闭移除为此前图形验收，本次事实接线没有新增图形客户端验证。`BleakWatcherTest / BleakWatcherGameTest` 继续覆盖内容驱动的寻敌连发、首次成功发射前减伤和在途弹体脱离已销毁炮台的生命周期。技能仍需原作参数校准及正式子职业装配。

### 当前阵营观测

`chorus:inspect_relation` 是只读世界动作，`left` 默认 `source_owner`，`right` 默认 `victim`，支持普通目标与词法目标绑定：

```json
[
  {"action":{"type":"chorus:inspect_relation","left":"source_owner","right":"victim"},"as":"relation"},
  {"if":{"type":"chorus:result_flag","binding":"relation","field":"not_allied"},
   "then":[{"type":"chorus:play_cue","cue":"test:enemy_hit"}]}
]
```

回执提供 `available / missing / allied / not_allied`。缺失实体、跨维度或无法解析构造物的 owner 时可能返回 unknown，此时 `missing=true`，其余三个标志均为 false；不要用 `not allied` 的布尔反转代替 `not_allied`。相同构造物 owner 身份不需要 owner 当前仍在世界中。关系有方向，查询沿用原版 `left.isAlliedTo(right)`，不推断对称性；回执校验左右双方身份，不能以其他请求的结果恢复执行。

观测值不可变；需要命中时关系就应在命中 body 中再次查询。选择时的关系、追踪时的关系和命中时的关系是三个独立时点。`allied / not_allied` 目标筛选与追踪均排除 unknown 候选；物理弹体仍可能碰撞友方，是否伤害、继续或停止由内容及碰撞策略显式声明。Bleak Watcher 当前在实体命中时只对 `not_allied` 继续伤害与 Slow，友方碰撞仍消耗弹体。

## 物理拾取物的动作体

```json
{"pickup": {
  "position": "place", "kind": "example:energy_pickup", "recipient": "source_owner",
  "lifetime": {"type": "chorus:constant", "value": 25, "unit": "second"},
  "radius": {"type": "chorus:constant", "value": 0.5, "unit": "meter"}
}, "as": "contact", "do": [
  {"for_each": "contact", "as": "collector", "do": [
    {"type": "chorus:play_cue", "cue": "example:collected", "target": {"binding": "collector"}}
  ]}
]}
```

先用 capture_position 绑定 place。寿命为正秒数，radius 为非负米数；kind 是命名空间标识。可选 `attraction: {profile, radius, speed}` 分别指定米 → 米的收集者属性 Profile、基础米数及 meter_per_second 速度。Profile 必须在当前程序中存在；生成时不把生成者的吸附属性冻结给收集者。以上是合成语法示例，不是某个 D2 拾取物的完整定义。

`contact` 支持 collected / expired 标志、count（拾取为 1，过期为 0）、age 秒数，以及位置和目标集合引用。生命周期区间为 [生成, 过期)，到期时不能同时拾取。每个逻辑单位单独持有版本、来源、词法结果和收集者；动作体为 detached，普通成本回执不能跨帧，显式 retain_cost 句柄继续共享原账本限额。

步骤可另加 `collect_if`，类型为普通 Condition；例如 recipient 为 victim 的弹药砖可以使用：

```json
"collect_if": {
  "type": "chorus:compare",
  "left": {"type": "chorus:equipped_reserve_space", "holder": "victim", "tags": ["example:special_ammo"]},
  "op": "gt",
  "right": {"type": "chorus:constant", "value": 0, "unit": "round"}
}
```

这是 pickup / as / do / spawn_as 的同级字段，不放入 pickup 参数内。生成时只编译验证条件，接触时由 `CompiledEffects.canCollectPickup(state, spawn)` 读取最新已结算状态与时钟。来源、触发原因及生成前捕获的词法结果保持原值；self 仍是原规则持有者，victim 仍来自原事件，不会隐式改为收集者。需要判断 recipient 时应显式使用同一个目标表达式或捕获的身份。条件不能引用尚未发生的 contact、spawn_as 或不可跨帧的普通成本回执。

Minecraft 宿主在距离 / 视线与收集者存活检查之后、消费实体之前执行条件。false 时不消费、不发拾取事实、也不执行动作体；该单位仍可吸附、再次检查并正常到期。两个砖依次接触时，后一个读取前一个奖励结算后的储备。过期不受 collect_if 阻拦。查询是纯读取，不执行世界动作；其他宿主也必须在实际领取前调用这个接口，直接提交内部 contact 不会代替宿主完成资格检查。未知定义 / 错误版本或求值异常拒绝奖励；Minecraft 对异常单位丢弃并记录错误。

实现使用新增 ConditionalPickup 数据类型，旧 Pickup / Spawn / Parameters 的 v1 存储字段保持不变。[conditional_pickup.json](../common/src/test/resources/effects/conditional_pickup.json) 是合成示例：每把匹配有限武器补三发储备后再执行世界奖励。它验证领取协议，不定义 D2 弹药砖掉落量、分配倍率或 Overflow。真实玩家测试通过手动换弹产生储备缺口，原地同一个单位才可领取；其余场景验证晚换装、收枪、错误类别 / 持有者、两个砖竞争、无限储备、到期与未知奖励结果。

可选 `spawn_as: "created"` 把同步生成回执绑定到外层后续动作：`created.spawned` 为成功标志，`count` 为成功 1 / 失败 0；其余互斥标志为 missing_position、missing_recipient、wrong_dimension、unloaded、rejected。内容可据此只在确认生成后开始冷却。该回执不是目标集合，不能 foreach；不能与已有绑定或 contact 同名，也不能在自身的延迟动作体中读取尚未返回的生成回执。已知生成失败可以分支处理，未知世界结果仍停止推导，不视为已知失败后重试。

成功收集会排入一次 chorus:pickup：actor / victim 是收集者，source 是生成者，tags 含 kind，numbers 含 count=1 / age，references 含 pickup_id / pickup_kind / collector。拾取者词条使用 target_is(self,event_actor)，不能用 source_is(owner) 代替。事实按既有队列在当前动作体后执行；生成和过期不会产生拾取事实。世界执行结果未知时停止后续推导，不重放奖励。接口与宿主进度见 [实现记录](engine-implementation.md#拾取物的逻辑协议)。

Minecraft 宿主目前用 `chorus:effect_entity` 承载一个私有逻辑单位，recipient 必须是同维度存活实体的 UUID，生成失败不返回实体身份。接触距离按收集者脚底计算，并检查方块视线；spectator、死亡或暂时无法解析的收集者不能拾取，也不改选其他玩家。基础半径、速度和寿命在生成时固定，吸附 Profile 每 tick 按收集者现有来源 / Buff 求值，直线运动受已加载地形阻挡。6 m/s 等测试速度仅验证执行侧单位，不宣称已经复刻离子痕迹的路径。

寿命按运行时逻辑时钟计算，暂时不 tick 不会重置剩余时间；实体重新 tick 时先判断到期，再判断接触。短命实体 noSave / noSummon，运行时关闭、替换或失败后丢弃，不跨重启或维度迁移。拾取先消费实体再执行动作体；故障后不重新生成或重试奖励。当前外观为原版物品渲染器的萤石粉占位，逻辑上不可捡入背包；私有只约束拾取资格，按收集者过滤客户端可见性尚未实现。Firesprite 首批内容已通过显式校准 Profile 与 grant_ability_energy 路由当前手雷账户，见 [D2 规则集](d2-ruleset.md#firesprite-与-ember-of-tempering)。特殊 / 重型弹药砖和 Overflow 已有独立数据模板及物理链路，数量 / 分配需要外部校准，见 [满溢与弹药砖](d2-ruleset.md#overflow-与特殊--重型弹药砖)。视觉合并、队友副本自动分发、公共抢占及完整 D2 拾取内容仍待完成。


### 按范围主动收集拾取物

```json
{
  "action": {
    "type": "chorus:collect_pickups",
    "collector": "self",
    "radius": {"type": "chorus:constant", "value": 5, "unit": "meter"},
    "kinds": ["example:energy_pickup"],
    "line_of_sight": false
  },
  "as": "candidates"
}
```

该通用动作查询收集者脚底球形范围内、明确 kind 集合中的私有拾取物，不要求移动到原来的接触半径。示例 5 米只是测试值。kinds 必须非空；radius 是非负 meter 表达式，可以读取 Profile 或来源参数。line_of_sight 默认 false，内容可显式要求方块视线。候选按当前距离、再按实体 UUID 排序，只查询当前维度已加载的实体，不加载区块，不转移其他收集者的私有单位。

返回值 `count` 是候选数量，`found / missing_collector` 描述查询结果；found 可以同时 count=0，不能把候选数量当作已领取数量。查询不消费物品，当前动作体结束后进入排队的领取流程。每一件物品都重新检查实体/原 Spawn 身份、所属运行时、寿命、收集者存活、距离、可选视线和当前 `collect_if`。拒绝没有奖励或拾取事实，剩余候选继续；到期单位留给原有过期流程处理。候选列表固定，不自动加入本轮奖励新生成的单位。

每次确认领取先消费物理实体，再在同一个执行帧中运行该单位原来的 detached 奖励动作体，保留生成者来源和捕获值。首次领取和本批下一件使用 `afterReactions` 信封排队：所有当前普通事件及其进一步派生反应完成后，才领取下一件。因此下一件会看到 Armor Charge 的嵌套授予和许可更新，不能借用查询时的旧资格。普通反应仍按 FIFO 处理，延后事件之间也按 FIFO；不推进逻辑时间、不压制合法循环。如果普通反应持续循环，领取继续等待原有 Pump，而不是强制跳过反应。多个收集请求交错时，每一件的领取与基础奖励仍同帧完成，不能重复消费同一物体。未知世界结果停止后续执行，保留已经消费的实体及已提交资源，不重放奖励；不把整批拾取伪装为可回滚事务。

`PickupCollection` 的候选、批次、领取回执与 `AfterReactions` 信封支持类型化存储往返，已有 Pickup / Spawn / Contact 存储形状不变。会话检查点依然要求边界完全结算，不能在批次或未知世界动作中途保存为可恢复成功状态。这不代表短命 EffectObject 已支持物理世界重启恢复。当前能力面向受管私有拾取物；原版物品、公共抢占、Mote/Crest 活动物体、Shoot to Loot 的子弹命中和 Powerful Attraction 的完整内容装配仍需各自接线。

## 实际技能分类与 Powerful Attraction

`{"type":"chorus:event_ability_tag","tag":"chorus:class_ability"}` 读取 `chorus:ability_started` / `chorus:ability_used` 类型化事实中已接受、已解析技能的定义标签。它不读取输入附带标签或当前基础技能选择；非技能事实、事件类型不匹配、拒绝回执或 resolved ID 不一致均返回 false。事件上下文快照将其冻结为布尔值，后续换技能不改写旧施放的分类。需要查询当前基础选择时继续使用 `selected_ability_tag`。

参考 `powerful_attraction.json` 在接受的职业技能开始时查询范围球：一件 12.5 米，两件及以上 18.5 米；按持有者的当前来源求和限到两件。实际技能也可声明专用 `chorus_d2:activates_powerful_attraction` 标签表达替换技能资格。每个持有者 / cast 只发起一次查询，多插槽提供的多个来源不会重复触发。60 秒共享回执与逐项遗忘仅为有限去重政策，不是原作模组冷却；接受的免费职业技能目前允许触发，具体原作特例仍待校准。

本模组只收集当前受管的私有 `chorus_d2:orb_of_power`，不自行授予 Armor Charge 拾取许可，也不直接复制回能或吞噬奖励。查询在开始反应中取候选，领取等待普通反应及当前技能动作体结算，再复核当前位置和资格；位移中的精确原作触发时机尚未校准。Utility Kickstart 的现有充能消耗先结算，随后每颗球可以重新补充池；吞噬继续使用独立许可和原奖励。

`powerful_attraction_equipment.json` 用独立 `chorus_d2:class_item` 槽及三格可编辑模组演示实际装配，预算 10、Powerful Attraction 费用 2、Utility Kickstart 费用 3，采用现有持有者解锁授权。它是参考目录，不代表完整职业物品、子职业限制或解锁进度系统。Mote / Crest / Echoes 活动物体需要公共竞争、活动计分和真实生产者；当前定义未用占位 kind 冒充这些实现。来源与边界见 [Powerful Attraction 研究记录](../data/d2-research/2026-10-13/powerful-attraction.json)。

## 事实携带的 Buff 观察值

需要确认历史事件确实属于当前规则绑定的那一代 Buff 时，使用 `event_has_buff` 的 `match: instance`。它只允许在 BUFF 作用域中声明，同时比较逻辑实例键和 generation；刷新 / 加层保留代次，结束后同键重新授予会产生新代次。旧事件因此不会被新冻结等状态计入受伤阈值。对应的 `has_buff match: instance` 检查该代次现在是否仍存活；结束回调仍能匹配自己的历史观察，但不会把新代次当作旧状态复活。原有 bound / any 的含义保持不变；这是逐事件的状态资格条件，不是跨目标连锁的次数或深度限制。

`event_has_buff`（buff、target 默认 victim、minimum 默认 0（存在性）、match 默认 any）与 `event_has_buff_tag`（tag、target 默认 victim）读取事实的不可变观察值。它们不会重新查询当前 BuffStore。`event_buffs_available` 可先判断指定 target 是否确实被观察；未观察会报错，确认观察为空才表示没有 Buff，not 也不能把缺数据转成“不存在”的证据。match:bound 按当前绑定来源计算实例键；any 要求某一个实例独立达到 minimum，不跨来源相加。暂停实例保留存在性，已到期层不进入观察。

伤害适配器在**回执确认、该次 Buff 消费与派生事实执行之前**采样攻击者和受击者。此时物理伤害及护盾写入已经发生，因此它不是攻击进入前的完整状态快照。每份原版嵌套伤害分别采样；其后的 hit / damage_taken / shield / death / kill 共享该份观察，后来清理、授予、刷新或到期不会改写历史。纯核心 Action 在宿主尚未结算消费且没有提供观察时，从完成动作的状态补采样；已由宿主结算消费却没有观察的回执保持未知，不假造消费前值。

```json
{"type":"chorus:all","of":[
  {"type":"chorus:event_buffs_available","target":"victim"},
  {"type":"chorus:event_has_buff","buff":"example:marked","target":"victim","match":"any"}
]}
```

观察记录保留 holder、采样时刻、实例键 / generation、施加来源、定义 tags、层数、tier 与暂停标记；不复制组件、资源或完整世界。emit、原上下文的 after / 物理续体及派生 calculate / grant_energy 查询保留已有记录；改写查询 victim 不会自动获得该新目标的历史观察。新生成的资源 / 拾取等独立事实没有被自动赋予旧伤害观察。普通 has_buff / has_buff_tag 继续读当前状态。事件观察条件用于 on_use 数值快照时按已有事件值冻结，不推测将来命中的目标状态。


## 连续生命恢复的数值 Profile

`bundle.health_recovery[]` 可选 `profile`。未声明时沿用 rate 的直接值；声明后输入、输出必须匹配 basis 对应的速率单位：默认 absolute 为 damage_per_second，max_health 为 health_fraction_per_second。缺失引用或单位不符在链接时拒绝。先在恢复来源作用域求 rate，再用受益者当前的来源 / Buff 修饰计算最终速率，之后才参与 channel 的优先级与速率选择、区间积分；负数或非有限结果不能成为治疗额度。

查询的 actor / victim 均为受益者，source 保留恢复来源，tags 为声明的 tags，numbers.recovery_rate 为原始速率，references.recovery_channel 为通道。查询不继承历史施加事件，不伪造事件 Buff 观察。来源变化前先积分旧区间，下一段使用新修饰；Profile 不改写治疗归因，不存储被取消或溢出的治疗。该入口可把 D2 HP/s 与 Minecraft HP/s 的校准从效果定义中分离，也能表达当前受益者的恢复倍率。护盾回充使用自己的入口；离散 heal 不经过这个恢复 Profile，但同样支持下述最大生命比例。


### 按当前最大生命比例治疗与恢复

离散 `chorus:heal` 与 `bundle.health_recovery[]` 均接受 `basis: absolute | max_health`，默认 absolute 保留已有行为。两种 basis 使用不同单位，不能把 0.2 HP 与 20% 生命混用：

```json
{"type":"chorus:heal","target":"victim","basis":"max_health",
 "amount":{"type":"chorus:constant","value":0.2,"unit":"health_fraction"}}
```

```json
{"id":"regenerate","channel":"example:natural_regeneration","basis":"max_health",
 "rate":{"type":"chorus:constant","value":0.2,"unit":"health_fraction_per_second"}}
```

第一段请求目标当前最大生命的 20%；第二段声明每秒 20%，仍由既有时间轴按段积分。比例允许超过 1，实际生命上限、取消 / 改量和溢出继续交给原版治疗路径；负数、非有限数、乘算溢出与超出原版 float 范围的输入拒绝。它不修复 Chorus 护盾，也不把 Absorption 加入最大生命基数。

来源速率、条件与 Profile 在区间开始时确定，比例额度为 `rate × elapsedSeconds`。**最大生命在对应世界治疗操作执行时采样一次**，先将比例换算成 HP，再进入原版 heal；不是效果授予时的缓存，也不是区间内最大生命的连续积分。治疗钩子随后改变最大生命或治疗量，不会重算这次请求；下一次操作读取新上限。不同通道是顺序执行的独立操作，不承诺跨通道共享同一次世界观察。

同一 holder / channel 仍先按 priority 取高，再取最大恢复速率。最高优先级候选的 basis 相同时可在纯核心选择；同时包含固定 HP/s 和比例/s 时生成 `Recovery.Selection` 世界命令，保留候选、来源、标签和已积分区间，用一次最大生命观察把速率换成 HP/s 后选择。相同有效速率以稳定 id 决胜，只执行胜者的一次原版治疗。例：10 HP/s 与 20%/s，在 40 最大生命时前者胜出，在 100 最大生命时后者胜出。取消胜者不会改用次优来源，也不保存未生效额度。不同 target / channel 仍独立处理。

`HealingCommand` 保留原始比例和 basis；`HealingReceipt.maximumHealth` 保存可选观察。回执和事实中的 requested 始终是换算后的 HP，offered / effective / overheal 仍是原版实际写入的观察。HEALING 结果新增 `amount_resolved`、`observed_maximum` 标志与 `maximum_health` 数值；读取最大生命须先检查 observed_maximum，读取缺失目标的比例 requested 须先检查 amount_resolved。没有目标时比例请求的 HP 量未知，不伪造为零；已有绝对请求即使目标不可用，requested 仍然已知。未生效的治疗不产生恢复事实。

混合候选的 `SelectionReceipt` 保存原选择命令、胜者、最大生命和实际 HealingReceipt；校验来源、命令与观察必须一致。已完成操作的重放返回原结果，不因后续最大生命变化而重新选源；未知结果保留已发生的世界写入，不重新治疗。适配器须实现 Selection，不能擅自比较不同单位或挑第一项；Minecraft 宿主已提供该实现。

[fractional_healing.json](../common/src/test/resources/effects/fractional_healing.json) 是合成验收示例：当前最大生命变化、0.12 秒到期残段，以及实际 health_loss 后用定时器暂停 / 重启。仅盾损失不触发该示例的生命延迟；它尚未提供正式 D2 基础盾分类、完整自然恢复或勇士 / 治疗祸患行为。原表需求与数值矛盾已保存于[研究记录](../data/d2-research/2026-10-11/fractional-healing.json)。


## 事实携带的实体观察值

DamageReceipt / EffectEvent 可带 `EntityObservation(timeMicros, entities, positions)`。每个已观察身份映射为不可变的 EntityQuery.View 或显式 unavailable；未包含该身份则是 unknown。View 保存生命 / 最大生命 / Absorption、alive、原版 player、entityTags 与 typeTags；positions 另存已采样的身份 / 锚点位置，不包含 D2 敌人目录。死亡实体仍可有完整 View；unknown 与 unavailable 都不能直接读取 health、player 或标签。

```json
{"if":{"type":"chorus:event_entity_observed","target":"victim"},"then":[
  {"action":{"type":"chorus:read_event_entity","target":"victim"},"as":"at_hit"},
  {"if":{"type":"chorus:result_flag","binding":"at_hit","field":"available"},"then":[
    {"if":{"type":"chorus:observed_entity_tag","binding":"at_hit","source":"entity","tag":"chorus_d2:combatant_tier_2"},"then":[
      {"type":"chorus:play_cue","cue":"example:tier_two"}
    ]}
  ]}
]}
```

`event_entity_observed` 与 `read_event_entity` 的 target 默认 victim。前者对 unknown 返回 false，对已观察到 unavailable 返回 true；后者是纯动作，返回既有 ENTITY 类型结果，不发世界查询，unknown 会报错。后续继续使用 result / result_flag / observed_entity_tag，实体标签和注册表类型标签保持分开。当前的位置和状态仍用 capture_position / inspect_entity 明确查询；历史位置只读取 positions 中实际存在的记录，不由生命等字段推测。

原版适配器在 hurt 返回后、该次 Buff 消费及派生反应前采样；真实受击对象保留在伤害作用域，即使原版钩子已移除它也能提供字段。目标按 DamageCommand.target 保存；攻击者 UUID 能解析为当前维度 LivingEntity 时采样，确定不可用则记 empty；无法解析的逻辑攻击者别名保持 unknown，自伤只保存一份观察。任意 Java 宿主可通过 DamageReceipt.withObservedEntities 提供自己的观察，同份回执不可替换为矛盾值。纯核心不会从 EffectState 补造世界信息，旧适配器省略该字段仍为未知。

hit / damage_taken / shield / death / kill 共享回执观察；emit、原上下文的 after / 物理续体与派生 calculate / calculate_pipeline / grant_energy 保留历史。改写查询 victim 不会增加新目标记录，独立资源等新事实不会自动携带旧观察。event_entity_observed 在 on_use 数值快照中按原事实冻结。观察失败保留已提交伤害、报告失败并清理原版作用域，不重放伤害。


## 事实携带的实体位置

EntityObservation 的 `positions` 按 `PositionQuery(target, anchor)` 保存不可变的 Optional<WorldPosition>；它是采样记录的键，不表示读取历史时发世界命令。target 身份与 feet / body / eyes 锚点均精确匹配。位置与原版类别的可观察性独立：元数据存在不证明位置已采样，脚底记录不证明眼睛记录；旧的二参数 EntityObservation 构造器不提供任何位置证据。

```json
{"if":{"type":"chorus:event_position_observed","target":"victim","anchor":"feet"},"then":[
  {"action":{"type":"chorus:read_event_position","target":"victim","anchor":"feet"},"as":"at_hit"},
  {"action":{"type":"chorus:select_targets","center":{"position":"at_hit"},"radius":{"type":"chorus:constant","value":2,"unit":"meter"},"relation":"any"},"as":"near"}
]}
```

两种声明的 target 默认 victim、anchor 默认 feet。event_position_observed 对没有对应记录返回 false，对已观察到不可用返回 true。read_event_position 为纯动作，返回现有 POSITION 结果；未观察时报错，观察到不可用时 position 为空，可用 available / missing 检查。它不是 EntityQuery 结果，也不能直接作为实体 target。没有当前位置兜底，不默认原点。

原版伤害在 hurt 返回后的同一次实体观察中采样受击者与可解析攻击者的三个锚点，WorldPosition 保留实际实体所属维度。保留的受击对象即使被原版死亡钩子移除也可记录；后续死亡反应的移动、传送或删除不改变历史。UUID 攻击者确认不可用时三个锚点均记 empty，无法解析的逻辑别名仍是 unknown。此时机是伤害确认后、消费和派生反应前，不宣称为伤害进入前坐标或每个碰撞点。

位置与实体元数据共同随回执复制及 hit / death / kill、emit、after、派生查询传播。detached 动作可以在尸体和装备来源都不存在后用原位置发起新的范围查询，每次成员仍取当前世界。历史位置只决定坐标，不保证查询或生成成功：已有维度、区块加载、阵营等世界校验继续生效，不会跨维度生成或加载未知区块。没有位置的旧适配器须显式提供证据；新资源 / 拾取等独立事实不会自动借用旧位置。历史观察已纳入检查点 JSON；Minecraft 跨重启恢复仍待接入。

## 查询移动状态

`inspect_entity` 和 `read_event_entity` 的 ENTITY 回执新增 `movement_observed` 标记，以及 `on_ground / sprinting / crouching / swimming / fall_flying / passenger / sleeping` 七个布尔字段。新字段沿用 `result_flag`，无需将移动状态伪装成实体标签：

```json
[
  {"action":{"type":"chorus:inspect_entity","target":"self"},"as":"state"},
  {"if":{"type":"chorus:all","of":[
    {"type":"chorus:result_flag","binding":"state","field":"movement_observed"},
    {"type":"chorus:result_flag","binding":"state","field":"sprinting"}
  ]},"then":[{"type":"chorus:play_cue","cue":"example:sprinting"}]}
]
```

这里采样的是服务端当前原版实体标志，不证明键盘输入、实际位移、持续冲刺时间、滑铲或 D2 动作资格。`crouching` 对应原版姿态，不等同于按住潜行键；`on_ground` 也不等同于没有垂直速度。资格和持续时间由内容规则另外判断。

Minecraft 宿主在显式查询与伤害回执的实体采样中保存全部七项；`read_event_entity` 读取的仍是回执当时的冻结状态，后续停止冲刺、改变姿态、移除来源或实体不会改写该结果。旧适配器使用原有 View 构造器时，移动信息保持未观察；实体缺失或移动信息未提供时 `movement_observed` 返回 false，直接读取任何一个移动字段都会失败，不虚构“没有冲刺”。实体元数据可用与移动信息可用须分别判断。历史回执、emit、延迟动作和后续计算沿用原有观察传播规则；没有新增客户端输入包、移动事件或属性反馈查询。

## 水平速度上限

Bundle 可声明 `horizontal_speed_limits`，适用于 SOURCE 与 BUFF。每项必须有局部唯一 `id`、单位为 `meter_per_second` 的 `speed: Value`，可选 `if: Condition`。省略列表表示没有上限；零是禁止水平位移的有效上限，负数与非有限数非法。

```json
{
  "id": "example:hover",
  "parameters": {"speed": "meter_per_second"},
  "horizontal_speed_limits": [
    {"id": "hover", "speed": {"type": "chorus:source_parameter", "name": "speed"}}
  ]
}
```

`CompiledEffects.horizontalSpeedLimit(state, input)` 是纯查询：读取 actor 自身来源及作用于该接收者、未暂停且仍有效的 Buff，按实例与声明 id 排序，取所有匹配上限的最小值，同时保留各项 Bundle、实例、施加者与数值。空结果与零独立表示。上下文增加 `chorus:horizontal_speed_query` 标签；条件为假时不读取该项 speed。使用既有 Value / Condition 的类型与作用域规则，不提供虚构的历史动作回执。可读取已写入的 Buff 数值组件，也可用profile_value查询当前实体的数值Profile；若数值依赖原触发事件，应先由calculate捕获相应结果。

Minecraft 以每游戏 tick 20 分之一秒换算速度。宿主限制 X/Z 合成速度的长度，不分别限制两轴，因此斜向移动不会获得额外额度；Y 保留原语义，也可叠加 vertical_motion。move 先限制请求，再运行原版碰撞；实际坐标写入消耗该实体本 tick 的水平路程额度，普通 setPos / setPosRaw 与重复位置包共享额度。坐标写入前计账，回调内再次移动也不能刷新额度。碰撞未走出的路程不消耗额度；闲置 tick 不积攒额度，同 tick 更换或删除后重新添加来源也不重置已用路程。

玩家位置包先取得受限目的地，继续原版碰撞、纵向移动与其他校验，再纠正到实际接受位置；不把客户端目标坐标直接当作授权传送。明确的宿主传送与 displace_entity 可以绕过本次路程额度，随后继续限速，并且不会补充本 tick 额度。外部冲量和 AI 设置的速度也经过该上限；apply_impulse 回执读取真实接受的分量。生效时下坐骑，期间拒绝重新骑乘；这些是当前宿主政策，不代表已校准的 D2 移动曲线。

独立可选通道 `chorus:horizontal_speed_v1` 携带维度、实体 id / UUID 和可选上限，支持开始追踪、玩家加载、数值变化及清除同步。客户端只预测本地玩家的额度；远端实体直接消费原版权威追踪位置，避免对同一段运动重复限速。身份不匹配消息被忽略。客户端不运行第二套 Buff 规则。

运行时在规则状态提交、世界动作前与 prepare 边界更新投影。`nativeHorizontalSpeedReport()` 保留每个可查询 holder 的归约证据；查询失败为该 holder 投影零上限、记录错误并停止运行时，不构造正常归约结果。显式 close 只清理仍由自身拥有的投影，不清除后继运行时的值。不持久化临时速度状态。严格的逐服务端 tick 额度会影响网络批量到包与高延迟下的移动观感；当前验证仅涵盖本地集成客户端，网络容差与 D2 参数仍需单独校准。

## 原版属性投影

程序根的 `native_attributes` 将纯 Profile 的归约结果投影为当前运行时拥有的原版临时 AttributeModifier。旧程序省略它时没有投影。下面是可独立编译的合成加速示例；绑定 `example:fast` 来源后，当前原版移动速度乘以 1.5，解绑则移除该贡献：

```json
{
  "version": "example-1",
  "native_attributes": [{
    "id": "example:speed", "attribute": "minecraft:movement_speed",
    "operation": "add_multiplied_total", "profile": "example:speed",
    "input": {"value": 0, "unit": "delta"}, "output_unit": "delta"
  }],
  "profiles": [{
    "id": "example:speed", "version": "example-1", "input_unit": "delta",
    "steps": [{"type": "chorus:apply", "id": "contributions", "operation": "add",
      "group": {"name": "contributions", "reduction": "sum"}}]
  }],
  "bundles": [{
    "id": "example:fast",
    "modifiers": [{"id": "fast", "profile": "example:speed", "stage": "contributions",
      "group": "contributions", "op": "add", "stacking_key": "example:fast",
      "value": {"type": "chorus:constant", "value": 0.5, "unit": "delta"},
      "reference": "Synthetic native attribute example; not Destiny calibration", "confidence": "assumed"}]
  }]
}
```

`id / attribute / operation / profile / input / output_unit` 均必填。Profile 的输入、输出单位须与绑定一致，绑定 id 唯一，同一原版属性的同一 operation 只能绑定一次；同属性不同 operation 可以组合。同版本 imports 会合并绑定，重复或引用 / 单位错误在编译阶段拒绝。原版 attribute id 在安装运行时前按当前注册表验证；已注册但目标不支持的属性保留诊断，不添加假属性。

| operation | 输出含义 | 原版合成位置 |
| --- | --- | --- |
| add_value | 声明单位下的原版加值 | 加到原版 base；例如 max_health 的 10 点 |
| add_multiplied_base | delta，0.25 表示 +25% | 对加值处理后的基数应用基础倍率增量之和 |
| add_multiplied_total | delta，0.5 表示 ×1.5 | 作为一个总倍率因子，与其余总倍率因子相乘 |

Profile 必须仅输出 Chorus 贡献。输入是绑定中的固定 Measure，不读取原版当前总值再参与归约；同一效果的 SUM / MAX / 份数规则在 Chorus 内处理完后只写一次。原版最终值仍受其他来源、原版范围裁剪和基础值变化影响。两种倍率强制输出 `delta`；`add_value` 的类型单位不会自动换算物理尺度，特别是 movement_speed 的原版值不是米／秒。属性对应关系、负值和上限由内容作者与原版定义共同决定，不将原版范围当作 D2 校准数据。减少 max_health 不自动构造一次伤害 / 治疗，也不额外实现生命值裁剪规则。

查询上下文使用当前 holder 作为 actor / victim；origin 的 owner 是 holder，source 是绑定 id，weapon / ability 为空，带 `chorus:native_attribute_query` 标签及 `attribute / binding` 引用。来源和 Buff 修饰按接收者归约，实例参数沿用原有规则；没有武器伤害、历史动作结果或伪造的观测值。缺少所需输入会失败。`CompiledEffects.nativeAttributes` 返回含完整计算轨迹的纯快照。

默认 Minecraft 宿主只枚举状态内来源 / Buff 的 holder 以及装备 / 技能持有者，并用当前维度 UUID 查找 LivingEntity；逻辑别名不能当作实体，未登记的实体不会被全局扫描。要让固定非零输入作用于实体，也要先登记来源、Buff、装备或技能。查询在普通世界动作执行前及规则提交后刷新，tick 的 prepare 处理到期与资格变化；这不表示在已经执行中的原版伤害管线内插入新的反应。治疗等后续动作能读取新上限，过期 Buff、移除来源、实体消失或变为旁观者后只清理该运行时拥有的修饰；未变化的 modifier 不反复写脏。

`nativeAttributeReport()` 保存最后一次成功刷新中的每项计算、结果及可用的原版最终值。结果为 PROJECTED、ZERO、MISSING_TARGET、INELIGIBLE 或 UNSUPPORTED_ATTRIBUTE；不可观察的目标 / 属性没有数值，ZERO 则表示贡献为零且没有对应 modifier。报告不是持续实时采样，也不生成 attribute_changed 事实。客户端同步遵循该 Attribute 原有的 syncable 标志，不修改注册表同步策略。

所有候选属性先完成计算和身份冲突检查，再改动原版；预检失败保留旧投影，已经提交的规则状态仍保留，运行时报告失败并停止。写入或后续世界动作发生未知失败时也不自动回滚 / 重试。显式关闭运行时会清理已知且仍匹配的自身修饰；其他模组覆盖了自身 modifier id 时会报冲突，关闭时也不删除被外部改写的值。运行时不修改原版 base、不持久化临时 modifier，也不把这套投影当作完整 Slow / Freeze / Suspend、滑翔或跨重启效果恢复。

### 向前地面移动倍率

Minecraft 宿主注册 `chorus:forward_ground_speed`，为两端所有具有属性的 LivingEntity 添加默认值 1、范围 0–1024 的同步属性。它可通过同一 `native_attributes` 接口投影；例如 `attribute: "chorus:forward_ground_speed"`、`operation: "add_multiplied_total"`、输出单位 `delta` 的 Profile 得到 0.04 时，形成 ×1.04 的前移输入倍率。这个注册范围是宿主约束，不是 D2 速度上限。

它只作用于原版地面移动：本地输入向前分量大于零、在地面、非水/熔岩、非鞘翅/创造飞行、非乘坐、存活且非旁观者。向前斜走采用同一倍率，同时放大本次输入产生的两个水平分量，保持斜走方向；纯横移、后退和没有输入不受影响。归一化由原版先完成，然后再乘倍率，避免满幅输入被归一化掉加成。步行、疾跑、蹲行仍先采用原版各自的输入/速度处理。

此属性修改的是本次输入产生的水平加速度，不乘既有速度、击退/冲量或竖直分量，不绕过 movement_input 与轴运动限制。空中/液体控制保持原版路径。属性投影与同步复用原版 AttributeModifier 和属性包；服务端实际前移查询前推进相关运行时，处理到期，关闭时只清理自身贡献。没有新增自定义移动协议或检查点字段。

规则作者仍须定义叠加、绝对步行/疾跑速度、ADS 与持枪惩罚等数值 Profile。该接口不把 Minecraft 的摩擦、加速度、速度单位及空中手感视为已经校准的 D2 物理模型；全方向移速应继续使用 `minecraft:movement_speed`。可运行的合成验收见 `effects/directional_movement.json`。


## 投射物接触几何快照

`ProjectileFlight.Impact.geometry` 可保存实体接触时未膨胀的世界轴 AABB 与碰撞膨胀半径，原版宿主在运行接触反应之前采样。旧构造器仍可生成未观察几何的回执；墙面、到达、接回及到期事件没有实体接触几何。空几何与零坐标有不同含义。

投射物 / 散弹接触结果新增 `geometry_observed` 标志；数值 `relative_x / relative_y / relative_z` 为 multiplier，`bounds_width / bounds_height / bounds_depth / collision_inflation` 为 meter。读取不存在的几何数值会失败，内容必须先检查标志。归一化值按 `(接触点 − 当时包围盒最小坐标) / 当时尺寸` 计算，不裁剪到0–1；例如膨胀碰撞箱前表面的相对坐标可以为负。尺寸须为有限正数，膨胀半径为有限非负数，同次观察必须在同一维度。

这组接口提供事实，不定义全局头部或精准命中。Lightweight Knife 的数据模板把三轴数值交给目标侧外部 Profile；验收目标显式绑定区域，精准结果决定哪份攻击快照执行。宿主仍需提供正式目标区域 / 模型映射，轴对齐包围盒并不能替代朝向和动画骨骼。接触后目标移动不会改变已保存的坐标，原来的原子碰撞计数与未知世界结果不重放语义保持一致。


### 原版伤害来源分类与共享虚空护盾内容

默认 `MinecraftEffectRuntime.nativeSource` 现在从原版 DamageSource 的实际owner捕获entityTags与typeTags，并为Player添加`chorus:guardian`、为Mob添加`chorus:combatant`。owner不存在时不补造这些资格；source仍是直接伤害实体UUID或伤害类型，weapon / ability仍为空。托管伤害继续使用显式DamageCommand的来源，不用当前持枪覆盖信用。此标签只是宿主映射，不等于D2敌人等级、阵营、构造物控制者或历史发射信用的完整目录。

伤害结果的 `result_flag` 增加 `failed`，当且仅当回执Outcome为FAILED；它与applied/immune等已知结果和运行时未知故障分开。Minecraft世界动作在目标缺失/已移除/维度错误时可返回FAILED。未知执行不会伪造FAILED回执，仍停止等待并保留已提交世界效果。内容若要按已知物理接触触发、而非只按实际扣血，可显式组合这个标志和实体/投射物观察。

共享Void Overshield内容直接组合grant_buff与restore_shield及有上限的层声明，没有新增金装或技能专用Java分支；各来源调用时显式提供额度、时长及目标。原版分类支持仅在Combatant攻击的护盾预算中乘0.3，仍按FIFO传递未经该层重复减免的余量。具体取样、刷新、物理校准和生产边界见[D2规则集](d2-ruleset.md#共享-void-overshield-与-shield-throw)。

## 已结算会话的检查点与恢复

该接口提供完整不可变状态的检查点、format 1 JSON 编解码、单文件原子替换，以及 Minecraft 运行时的捕获 / 恢复入口。默认目录宿主已接入正常停服保存和下次启动恢复，见[自动停服保存与启动恢复](#自动停服保存与启动恢复)；运行中自动保存和崩溃恢复仍未接入。内容作者不需要给每个 Buff 单独选择要保存的字段。

```java
var session = new EffectSession(compiled, clock, 256, initialState, worldExecutor);
// 完成一个外部结算边界后捕获；调用不会触发任何世界动作。
EffectCheckpointCodec.write(path, session.checkpoint());
var saved = EffectCheckpointCodec.read(path);
// 宿主停止使用旧会话，并提供与检查点对应的世界及相同的外部时钟策略。
var resumed = EffectSession.restore(compiled, clock, 256, saved, worldExecutor);
```

`EffectSession.Checkpoint` 固定完整规则目录、resourceRouting 编译模式与 `TimelineEngine.Checkpoint<EffectState>`，后者携带 domain、timeMicros、nextEvent、nextFrame。恢复要求目录内容及编译模式相等，允许重新编译同一份目录和改变单次 Pump 预算；相同 version 下改数值也会拒绝。协调模式使用上文的 format 2，本地模式与旧文件继续使用 format 1。直接用自定义 TimelineEngine 构建的旧会话未证明数据目录身份，不能调用这个数据目录检查点接口；通用 TimelineEngine 自身的检查点仍由调用方负责规则身份。

所有领域字段一起保留，尤其包括已脱离来源的 after、其捕获的伤害 / 查询结果、暂停定时器余量、Buff generation、已领取部分退款的保留凭据和随机流游标。不会重新初始化来源、补满资源、清空弹药、重新取攻击快照或重新抽随机数。恢复时保持原逻辑时间，未来推进仍逐个结算原期限；没有隐式补算离线时长。

仅成功排空的边界可捕获。调用世界执行器期间、尚待回执、未处理事实 / Frame、未完成外部请求、失败或被放弃请求均拒绝。已完成回执不在检查点中保留：它们只用于原边界去重，恢复后所有 `< nextFrame` 的世界操作均已退休，晚到旧回执也不能恢复新动作。未知世界结果不能使用这个入口恢复或自动重试。

JSON 顶层固定 `format / program / state / time_micros / next_event / next_frame`。两个数据树使用显式存储类型 ID；节点的 `$` 是类型，`v` 是字段或集合内容。全部字段必填，包括空 Optional / 集合；缺失观察不会解码成零值，空储备账户保持无限、零储备账户仍为有限。长整数和带类型的数值使用十进制字符串，浮点保留原 IEEE 值及负零。Map / Set 编码排序，列表保持顺序。类型表显式登记当前可达内置 record / enum；自定义扩展、Java 比较器或函数代码未注册时编码失败，不能静默删除。解码只构造数据，不执行任何 DSL 动作；装配和恢复仍须经过 CompiledEffects / EffectSession 校验。

`write` 先完成全量编码，再在目标同目录写临时文件并 force，最后以 ATOMIC_MOVE 替换；不支持原子移动时直接报错，没有非原子覆盖的退路。编码失败保留旧文件，临时文件在完成或失败后清理。该保证只针对单文件可见性，不保证它与 Minecraft 实体 / 物品文件共同提交，也不声称目录元数据的断电持久性。旧文件保留不能作为自动恢复已经发生未知世界写入的理由。

存储 schema 有独立固定清单检查字段 / 泛型类型 / 枚举成员，且可达类型审计会发现新增内置类型漏注册。已有类型的结构变化需要显式版本迁移；只更新 v1 schema 清单不会让旧存档变得兼容。目前仅支持 format 1，未知版本拒绝。

### 存储节点的版本迁移

格式内部的节点类型可独立升级。RecordTreeCodec.Migration 显式登记只读旧类型、目标类型与纯数据转换；旧节点先完整校验，再升级，最后验证父记录或集合的元素类型。编码拒绝旧类型，迁移图不允许环或一个旧类型有多个出口，迁移后出现重复 Map 键 / Set 成员也拒绝。`schema()` 同时冻结旧、新 wire shape；`types()` 只列当前可写类型，`retiredTypes()` 列只读输入。Java 类名不成为可由存档控制的加载入口。

`chorus:effect/weapon/weapon_definition` 保留原 item / ammunition / reload / fire 四字段，`weapon_definition_v2` 保留带 handling 的五字段，分别通过 LegacyWeaponDefinitionV1 / V2 转为当前 `weapon_definition_v3`；缺失的 handling / aiming 只补空配置。handling 的 ready / stow 管线均须输出正秒数，PlayerEquipment.draw 已消费该配置，时序见[服务端举枪与收枪阶段](#服务端举枪与收枪阶段)。旧 `effect_state` / `effect_state_v2` 分别保留十五 / 十六字段，通过 LegacyEffectStateV1 / V2 转为当前 `effect_state_v3`，只补原先没有的 handling / aiming 表；已有计时器、资源、Buff、弹药和编号不重建。旧 `action_gate/kind` 保留原十个枚举成员，新 `kind_v2` 增加 WEAPON_AIM，旧值按同名纯数据迁移。

[旧检查点夹具](../common/src/test/resources/persistence/pre-handling-reload-v1.json)由修改前的真实会话生成，包含两把已装备武器、击杀窗口及正在进行的换弹。迁移测试验证 50 ms 保存后仍在原 200 ms 截止点补弹和触发对应 Kill Clip，恢复本身不发世界动作，也不重置事件 / 帧编号。此夹具保留原字节，不用新版编码器重生成。

### Minecraft 运行时的恢复入口

```java
// 在服务端线程、外部世界结算结束后调用。
MinecraftEffectRuntime.Checkpoint saved = runtime.checkpoint();
runtime.close();
// level 必须对应同一份世界状态；默认目录入口重新绑定世界动作和原版伤害观察器。
var resumed = EffectPrograms.restore(level, programId, saved);
```

宿主检查点另外保留维度 ID、世界 gameTime 和已登记的 PlayerEquipment 持有者 UUID 集合。与纯会话捕获不同，`runtime.checkpoint()` 先 prepare 到当前世界 tick，可能正常结算到期效果和装备变化，然后再捕获；不能从伤害来源描述、尚未提交的原版伤害、物品转移或世界动作执行中调用。原版伤害写集 / 事实尚未排空、运行时失败和未知世界结果也会拒绝保存。

恢复要求维度、世界 tick 和完整目录内容匹配，且该维度没有其他运行时。`EffectPrograms.restore` 从当前服务端目录按 ID 查找；自定义宿主可使用 `MinecraftEffectRuntime.restore`，显式提供同一外部时钟策略、世界执行器和伤害来源适配器。不会再次初始化来源或重新付费、回能，也不推进逻辑时间；后续逻辑时间为保存的逻辑时间加恢复后的世界 tick 差 × 50,000 微秒。

安装会重新建立伤害观察器和当前可用实体的原版属性 / 移动投影。临时 modifier 使用新运行时的身份，旧运行时须先关闭；其他系统的属性基础值和修饰不会被当作 Chorus 状态覆盖。未加载实体保留规则状态，在后续 prepare 中重新查询。

实体 NBT 载入时，`LivingAttributeLoadMixin` 在原版读取 UUID 和原生属性之后、`LivingEntity.setHealth` 裁剪已保存生命值之前重建 Chorus 属性。新实体尚未进入世界索引时直接使用该加载对象；只处理它的 holder，不刷新其他实体。存活资格读取即将写入的生命值，不依赖构造时或上一次的数据；保存的零生命保持为零。重复加载不叠加修饰，原版永久 modifier 继续参与最终上限。该入口只读取当前已提交的规则状态，不推进时钟或执行初始化 / 治疗；若 Buff 已经过期，不从旧实体 NBT 重新获得它的上限。

宿主必须先恢复对应维度的规则，再开始加载相关实体；晚于实体加载才安装运行时无法找回已经被原版裁掉的生命。Chorus 的 modifier 仍为 transient，不存进实体 NBT，也不单独备份第二份生命值。与普通投影相同，相关查询 / 身份冲突会明确失败并停止运行时，不用错误上限继续覆盖生命；失败处理不回滚已读取的原生字段。失败运行时中的相关实体加载也明确拒绝，无 Chorus 属性目录或未登记的其他实体仍按原版加载。自动生命周期已在 ServerLevel 构造结束、加载器 level-load 事件及正常初始实体加载之前安装对应运行时；独立进程验收使用实际玩家登录 / NBT 路径核对生命上限与当前生命。

已在线且受 PlayerEquipment 管理的玩家，在安装前逐一比较保存配装与实际物品容器投影；不一致直接拒绝，不移动物品或伪造一次卸装 / 装备反应。持有者集合跨恢复保留，空容器也能在后续 prepare 正常解除旧配装；未上线持有者以后再次出现时按正常装备刷新处理。通过 `equip` 接入的其他可信容器不自动变成 PlayerEquipment 的所有物，空原版容器不会抹掉它们的配装。

关闭运行时会立即移除其弹体、构造物和拾取物，不授予拾取奖励或编造到期 / 死亡事件；恢复不重新生成这些 noSave 实体。检查点仍完整保留领域状态和历史引用，依附短命实体的持续规则、聚合组和引用生命周期尚需协调处理，不能以删除所有相关 UUID 的方式破坏其他实体保留的来源信用。

### 多维度归档与宿主保存确认

`CheckpointArchive` 对本地会话写入 format 3 宿主归档，对协调组写入携带 resource_directory 和 participant_placement 的 format 5（旧 format 4 的位置目录显式迁移为空）；列表中的每个 Entry 保存 program 目录 ID、dimension、十进制字符串 game_time、完整会话 effects，以及 equipment_owners、actor_owners 和 ability_owners UUID 集合。旧 format 1 / 2 分别按原字段严格解码；旧 format 1 迁移为空 actor_owners，两种旧格式均迁移为空 ability_owners，不推测历史领域选择是否属于玩家配置。维度和持有者排序后编码，重复维度 / UUID、非规范身份、错误类型 / 字段 / 版本拒绝。空维度列表表示该次保存明确没有运行时；它与没有文件或无法读取的文件不同。本地原生会话的 effects 使用 `EffectCheckpointCodec` format 1；明确采用协调编译的命名目录会话使用 format 2，并与目录一起进入联合宿主保存路径。协调 / 本地混合归档保留每个会话原模式，受管资源不得在其他领域重复出现。

`EffectPrograms.checkpoint(level, id)` 捕获默认 Minecraft 目录宿主，`EffectPrograms.restore(level, entry)` 按当前目录恢复归档项。捕获会检查运行时确实由该默认入口创建、目录 ID 和完整内容相符；自定义 Java 世界执行器 / 来源适配器 / 时钟即使使用相同 DSL，也不能冒充默认宿主。自定义宿主仍可使用底层 runtime.checkpoint / restore，但持久化其宿主策略需要明确的适配协议。已重载为不同内容的目录不会被当作旧运行时身份，旧状态不能被隐式迁移。

`WorldCheckpointStore` 负责单文件保存协议，调用方为每个已锁定的世界维护一个实例并先创建父目录。实例内部串行处理保存回调；跨实例 / 进程互斥仍依赖世界锁。协议如下：

1. `begin()` 只接受没有文件的新世界或 COMMITTED 文件。返回恢复数据之前，先用新 run UUID 写成 RUNNING。无法解码、权限 / I/O 故障、RUNNING 和 PREPARED 文件都不回退为新空状态；异常退出留下的候选数据保留供显式恢复。
2. `session.prepare(archive, requiredParts)` 原子写入 PREPARED 候选，返回本次 Save 句柄。requiredParts 必须是非空的具名集合，由宿主按真实保存范围决定；字符串不会被解释为任意文件路径。写文件本身不确认任何世界保存。
3. 宿主确认某项实际保存 / flush 完成后调用 `save.confirm(part)`。重复确认幂等，未知项目拒绝。尚有未确认项目时不能 commit；实际保存故障调用 `save.failed(reason)`，保留候选、失败原因及已确认范围，不能用更多成功回调掩盖故障。
4. `save.commit()` 复验当前 run、Save 身份、候选内容和要求集合，全部确认且无失败才原子写成 COMMITTED。之后该 Session 不再允许继续保存或世界工作；这是静止边界的完成协议，不能一边持续改变世界一边保留可恢复标记。下次运行须重新 begin 并先使该标记失效。

新的 prepare 使旧 Save 句柄失效，旧异步回调不能确认 / 破坏新候选。若宿主过早确认后又收到同次保存失败，failed 会撤销该次 COMMITTED；已经进入下一 run 时拒绝旧回调，不写入新文件。修改过的候选不能被旧句柄提交。编码失败不覆盖原候选或替换它的句柄；磁盘错误继续向调用方传播。

实际写入复用 `AtomicJsonFile`：完整编码后写同目录临时文件、force 文件、原子替换，无非原子回退；不是跨文件事务，也未提供目录元数据断电持久性保证。Store 自身不观察 Minecraft 内部的保存结果，不能把手工 confirm 当作原版保存成功的证据。原版玩家 / level.dat 的部分写入方法会捕获 I/O 异常并只记录日志，实体区域写入也有异步故障上报；自动宿主必须在真正写入 / flush 完成并确认这些故障通道以后提供对应确认。

### 原版玩家与 level.dat 保存证据

`MinecraftSaveCapture.observe(expectedPaths)` 按调用方提供的实际文件路径建立一个线程内观察窗口。PlayerSaveCaptureMixin 包围 PlayerDataStorage.save，LevelSaveCaptureMixin 包围实际写入 level.dat 的私有 saveLevelData；SaveReplacementMixin 观察 Util.safeReplaceFile 内原本被丢弃的布尔替换结果。原版重试、备份 / 回滚与异常处理路径保持原样，观察器不重写文件或把保存失败变成游戏效果失败。

窗口内每次相关保存留下一个 Write：REPLACED、REPLACEMENT_FAILED、NO_REPLACEMENT、SERIALIZATION_FAILED 或 THREW；后两者携带已观察到的诊断摘要。写入临时文件前被原版吞掉的异常至少表现为 NO_REPLACEMENT，不能把 void 方法正常返回当作成功；返回 false 的替换失败会保留为 REPLACEMENT_FAILED，即使旧目标文件仍然存在。保存范围内的 ScopedCollector 序列化问题会留下 SERIALIZATION_FAILED，即使文件随后成功替换也不能清除。missing 另列根本没有调用保存的预期文件，不与已尝试但失败混淆。路径只做绝对化和 normalize，不根据存档输入扫描目录或猜测成功。

Scope 关闭后，报告才可能 successful：全部预期文件必须实际参与保存，且本窗口内相关尝试全部成功。同一文件后来成功不会抹掉前一次失败；需要显式开启下一次完整保存窗口。嵌套观察分别保留证据，错误关闭顺序、保存尚在执行时关闭以及跨线程使用均拒绝。其他玩家 / 世界文件的成功不能替代缺少的预期路径；其他线程的操作不计入本同步窗口。

```java
var capture = MinecraftSaveCapture.observe(expectedPlayerPaths);
try (capture) {
    server.getPlayerList().saveAll();
}
capture.report().requireSuccess();
pendingSave.confirm("players");
```

调用方必须先确定正确的玩家集合和对应路径，并在捕获失败时将本次保存标为 failed。level.dat 使用同一协议。该观察只证明原版同步文件替换的返回结果，不证明区域存储异步任务、全部实体 / 区块、硬件断电持久性或世界与规则状态共同提交。成功报告也不自动对 WorldCheckpointStore 作确认；完整生命周期协调仍需组合真实写入范围、静止边界及下面的异步故障 / flush 证据。

### 区域存储的异步写入与 flush 证据

每个原版 `IOWorker` 通过 `MinecraftRegionSaveAccess` 持有独立的 `RegionSaveJournal`。`SimpleRegionStorage.chunkScanner()` 可取得该 worker；区块、实体和 POI 底层共用这个协议。日志从 worker 建立时开始，记录 ENCODE / WRITE / DRAIN / FLUSH / CLOSE 的序号、尚未观察完成的任务、最后写入和成功 flush 的覆盖位置，以及该实例生命周期内的失败。不同存储实例即使 level / dimension / type 标签相同也不共享证据。

原版失败的 PendingStore 会从队列移走，后续 synchronize(true) 可以成功。因此失败必须保留，不能仅观察关闭前的一次 flush。Journal 的成功报告要求没有未完成任务、没有历史失败，且至少一次成功的 synchronize(true) 覆盖最后一次提交的写入。synchronize(false)、close 正常返回、写入已排队、文件存在都不能替代这个条件。删除区域条目也算写入；flush 开始以后新提交的写入使旧 flush 不足以确认当前状态。

提交顺序与原版入队顺序一致，异步完成使用独立状态锁，原版 close 等待 I/O 时不会阻塞完成记录。原始 CompletableFuture、异常与取消结果保留；观察器不重试或修复原版文件。合并到同一个 PendingStore 的多个写入分别等待原始结果，不承诺中间 NBT 版本全部落盘。完成的成功任务不永久保留。写入 supplier 抛错会提前留下失败，即使原版返回的 future 没有结束，也不能生成成功证明；关闭文件时被原版吞掉的 IOException 同样保留。禁用世界写入的原版调试配置不能产生有效写入证据。

```java
var journal = ((MinecraftRegionSaveAccess) storage.chunkScanner()).chorus$regionSaves();
storage.synchronize(true).join();
journal.completion().join().requireSuccess();
pendingSave.confirm("region");
```

completion 等待调用时已经提交的观察，或在已知失败时提前返回失败报告，不发起保存 / flush，也不取消仍在执行的原版任务。supplier 抛错且原版 future 永不结束时，失败报告仍能返回，并保留 pending；宿主不能把它当作 I/O 已停止。返回时会重新取当前报告，期间新增但未完成或未 flush 的工作仍会使报告失败。报告是不可变快照，后续写入或关闭失败不会修改旧对象；确认前必须等待所有相关工作并读取当前报告，宿主还必须保证世界不再变化。生命周期失败没有自动清除入口，之后一次成功不能证明原先失败的其他区块也已修复；显式恢复政策另行处理。调用方仍需枚举本次保存的全部存储实例，不能以一个 worker 成功替代整个世界。

上层 ChunkMap.save、EntityStorage.storeEntities 和 SectionStorage.writeChunk 已通过 ENCODE 操作接到各自真实 worker 的 Journal。编码范围中的异常、ScopedCollector 报告和 SectionStorage 的 DataResult 部分 / 缺失结果都保留为失败，不要求它们继续抛到服务器顶层。NeoForge 对 Entity.save 和 BlockEntity.saveWithFullMetadata 的吞错 / 丢弃行为保留，诊断在被捕获之前记录；ChunkMap 直接报告的保存异常也进入当前范围。异步区块编码抛错仍由 worker supplier 的已有观察记录。

`MinecraftRegionStorageAccess` 返回实体 / section 存储的准确 SimpleRegionStorage，供宿主枚举；ChunkMap 本身就是 SimpleRegionStorage。SaveSerializationCapture 只在当前调用线程已打开的编码范围内记录诊断，嵌套范围使用当前写入者，结束后清理；无关的读取 / 保存诊断不归入另一个存储实例。正常返回 false 的不保存实体不会仅因被跳过就被标为错误。原版日志、部分 NBT、dirty 清理和异常传播继续保持原样；证明失败不自动回滚文件或重新标记脏数据。

这些入口覆盖原版已调用的编码 / 保存路径，不证明所有需要保存的对象都被调用，也不覆盖在观察范围之外创建的 level.dat 内容或任意模组的自定义存储。下述服务器级入口另为其调用中的 level.dat 构建打开外层观察范围。SavedDataStorage 使用下面的独立观察协议，不能拿区域文件的报告替代。

### 世界附加数据的保存证据

原版 `SavedDataStorage` 通过 `MinecraftSavedDataSaveAccess` 提供每个实例独立的 `SavedDataSaveJournal`。scheduleSave 从编码开始即被观察，保留每一次返回 future 的完成 / 异常 / 取消，包括同步编码失败。tryWrite 中目录创建和实际文件写入的结果在原版 catch IOException 之前记录，成功文件数仅在写入方法正常返回后增加。Fabric 观察 NbtIo.writeCompressed；NeoForge 观察其替换的 IOUtilities.writeNbtCompressed 以及额外的 Files.createDirectories，保留该加载器临时文件 / force / move 行为。两端使用各自必需的写入注入点，不以可选注入跳过差异。关闭操作独立记录，不能因 close 返回就忽略此前异步任务。

这条原版路径有两个边界：收集时先清 dirty 标志，实际文件写入失败时 future 仍可成功；没有新 dirty 项时 scheduleSave 返回新的已完成 future，而不等待上一批 pendingWriteFuture。SavedDataSaveJournal 保留全部未完成批次与文件操作，后来的空保存不能覆盖旧任务或清除历史失败。编码中途失败前可能已有其他项被清掉 dirty，因而同步编码失败同样不能被下一次空保存洗掉。观察器不修改 dirty、原版 future 或缓存，不自动重试文件。

```java
var journal = ((MinecraftSavedDataSaveAccess) storage).chorus$savedDataSaves();
storage.scheduleSave();
journal.completion().join().requireSuccess();
pendingSave.confirm("saved_data");
```

成功报告要求该实例至少实际调用过一次 scheduleSave、所有观察任务完成且没有该实例生命周期内的失败；真正无脏数据的空批次可以成功。completion 等待调用时已经存在的任务，或在已知失败时提前返回当前失败报告，不取消仍在执行的 I/O；期间新增的未完成任务仍会显示在返回报告里。关闭后的旧批次仍可在原版继续写入，报告会保留 pending，不能在这时删除世界文件或提交检查点。DIRECTORY / WRITE / SCHEDULE / CLOSE 各有错误来源，实际目录 / 文件记录规范化绝对路径；已完成的成功文件不逐个永久保存在 Journal 中。

报告仍是不可变时点快照，不能检测保存调用之后新变 dirty 的对象；宿主须先冻结世界并调用正确存储实例的保存，再等待当前报告和所有关闭结果。它证明的是所观察原版编码与文件操作的返回结果，不提供 fsync / 目录断电持久性、跨文件事务或自动修复。服务器级实例枚举和正常停服协调见下文；运行中检查点、短命物体遗留状态、崩溃后的世界结果对账和显式迁移仍待接入。当前恢复不补算离线时长。维度与 tick 相等不证明世界状态相等，不能把旧检查点套在已经变化的世界上宣称回滚。

### 服务器级原版保存汇总

`MinecraftWorldSave.bindings(server)` 在服务端线程取得实际在线玩家与其 PlayerDataStorage、准确玩家文件路径、level.dat、各已加载维度的 ChunkMap / 实体 / POI 底层区域存储，以及全局和各维度的 SavedDataStorage。Bindings 保留这些实例的身份、维度和 gameTime，不从文件是否存在推测保存范围，也不将相同标签的不同 worker 合并。

`MinecraftWorldSave.save(server)` 调用原版 saveEverything(true, true, false)，随后对全部绑定区域执行 synchronize(true)，对全部 SavedData 执行 scheduleSave，并等待各 Journal 已观察的工作。补充步骤覆盖 POI 写入及原版空保存未等待的旧附加数据批次；其间的 level.dat 构建也处于序列化观察范围。结果分别提供同步文件、区域与附加数据报告，以及额外 issues；requireSuccess 仅在所有部分成功且 issues 为空时返回。

调用前拒绝非服务器线程、同一服务器的重入、没有维度、禁用保存、已经关闭或有历史失败的存储，不修改 noSave 或抹除旧错误。保存期间出现的 RuntimeException 进入失败报告，原版已发生的文件修改保留；有失败时仍可能存在尚未结束的工作。结束时重新取得 Bindings，维度 / 存储实例 / 在线玩家 / gameTime 任一变化都会使报告失败。后一次保存可以重新确定玩家范围，但区域和 SavedData 的历史失败仍需显式恢复，不能靠新一轮保存清除。

这仍是显式调用的原版游戏状态保存工具。它不冻结世界、不捕获规则检查点、不关闭存储，也不自动确认 / 提交 WorldCheckpointStore；宿主必须建立共同边界，防止保存期间或报告返回后继续变化，并处理后续关闭失败。同 tick / 相同实例的任意状态修改无法由 Bindings 相等检测。PlayerList 保存过程调用的统计 / 进度旁文件及任意模组自定义文件未纳入证据，报告不能作为服务器所有文件备份成功的证明。下述自动生命周期复用真实 Bindings 和各项观察协议，并在原版停服流程中等待最后关闭结果；没有把显式 save 的返回当作停服完成。

### 保存期间的规则边界

`MinecraftCheckpointBoundary.open(server)` 在服务器线程枚举全部维度和已安装运行时，先验证默认目录宿主身份，再结算所有维度到当前世界 tick，最后统一捕获多维度 CheckpointArchive。准备阶段允许已有运行时完成跨维度反应，但禁止嵌套打开保存边界或安装 / 恢复运行时；准备失败不回滚已发生的动作。全部结算完成以后，边界固定规则状态、运行时对象、目录、装备持有者与各维度时钟。

边界保持打开期间，正常运行时操作入口（包括计时推进、事件、技能、装备跟踪和关闭）拒绝执行；安装 / 恢复入口也拒绝。拒绝记录在当前边界里，调用方 catch 异常不能让它重新有效。`requireUnchanged()` 还核对当前维度 / 运行时身份、完整目录与规则状态、世界 tick 和在线玩家物理装备投影；观察到的变化即使后来被改回，也保留失败。它不为了迁就快照自动移动物品、解除来源或修复规则状态。

实体 NBT 加载是一个明确的例外：仍可按已固定的规则重建原版属性投影，让保存的生命在原版裁剪前获得正确上限。此入口不执行规则、推进时间或治疗，相关失败仍使运行时 / 保存边界无效。读取 immutable state / program / 报告也不会执行规则。`archive()` 返回快照前会复核边界；`saveNative()` 在原版保存汇总前后复核，并返回 MinecraftWorldSave.Report，调用方仍须检查 report.requireSuccess()。

`close()` 只释放自己拥有的边界，不执行排队操作、补发被拒绝输入、推进时钟或关闭运行时。关闭后的边界不能再次验证；旧句柄重复 close 不会释放新边界，其他线程也不能释放它。先结束边界，再显式执行运行时关闭或恢复正常处理。

这实现了保存期间的 Chorus 规则隔离，不会冻结 Minecraft 的任意世界字段。原版 / 任意模组直接修改实体、物品、方块等状态仍须由宿主停止；实体集合和任意字段不是靠规则快照相等就能证明静止。边界本身不确认或提交 WorldCheckpointStore，释放后继续运行也不能把旧报告当成可重启世界快照。自动停服宿主另行协调断线输入清理、延后运行时卸载、最后文件关闭与解锁时机，不能用简单的 stopServer 外层 try-with-resources 代替。

### 自动停服保存与启动恢复

`MinecraftCheckpointLifecycle` 已通过必需的服务端 Mixin 接入两个加载器。文件位于实际世界根目录的 `chorus-checkpoint.json`；不需要额外保存命令。适用范围是默认 `EffectPrograms` 目录宿主的正常停服和相同目录下次启动，自定义 Java 执行器仍需自行实现对应保存协议。

启动时，在原版持有世界锁、尚未 createLevels 的阶段调用 Store.begin。缺少文件代表首次使用；已有文件只接受 COMMITTED，并在返回恢复数据之前写成新 run 的 RUNNING。预检全部维度、目录是否存在、完整 EffectProgram 是否相等，以及归档时钟与实际 level.dat 是否一致。各 ServerLevel 构造结束时恢复该维度，早于加载器 level-load 事件和正常初始实体加载；loadLevel 完成时复核归档中的全部维度均已恢复。即使 version 相同，目录内容变化也拒绝启动，不默默丢弃来源、补满能量或回退空状态。

正常停服使用以下顺序：

1. 结算全部维度并打开规则边界，捕获实际存储 / 在线玩家范围，写出 PREPARED 候选，开始同步文件与序列化观察。
2. 执行原版保存和关闭。断线只退休不持久化的按键句柄；加载器卸载 / stopped 回调延后关闭 Chorus 运行时，让属性投影保留到最后写入。每个参与保存的区域 worker 在实际 close 前追加一次 synchronize(true)，覆盖最终区块 / 实体 / POI 写入。
3. 在区域、SavedData 和资源关闭之后、LevelStorageAccess 释放原版世界锁之前，检查全部同步文件、区域 flush / closed、SavedData pending / closed 和完整规则边界。分别确认 `native_files / region_stores / saved_data / rule_boundary`，全部成功才写 COMMITTED。
4. 原版关闭路径结束后释放规则边界，再清理运行时及其短命实体。已观察的写入、关闭或解锁失败使本次保存不可自动恢复；保留诊断并继续原版清理。晚到的同次失败仍可撤销提前产生的 COMMITTED。

RUNNING / PREPARED、未知格式和损坏数据会拒绝下次自动启动，原文件保留，不自动重放或删除检查点。运行中的原版 autosave 不把 Chorus 文件变成 COMMITTED，因此进程崩溃后的世界尚无自动恢复入口；不要通过手改 phase 或删除文件假装恢复成功。正常恢复保留原逻辑时间，不补算离线期间效果。

验收工具为 `python3 tools/checkpoint_restart.py`，可选 `--loader fabric|neoforge|both`、`--java-home <JDK25 路径>`；构建所需 `JDK21` 继续从环境传入。工具顺序启动八个独立 Gradle / GameTest 进程，每端分别执行正常保存、恢复、制造真实玩家备份替换失败、拒绝失败存档恢复。每次使用唯一测试目录，保留原生世界、日志和 `build/checkpoint-restart/<run>/report.json`，不覆盖普通 GameTest 世界。测试专用 Mixin 仅纠正 GameTest 启动器默认删除世界 / 新建 level.dat 的行为，恢复时读取真实文件；不进入发布 jar。

两端已验证两个维度自动恢复、实际玩家 PrepareSpawnTask / NBT 登录、29 / 30 生命、物理装备与零剩余技能能量，来源初始化和已支付技能不重放。故障场景检查 PREPARED 诊断、下次进程在测试运行前失败及检查点原始字节保留。该证据来自真实服务器进程，没有声称图形客户端验收。

剩余边界：统计 / 进度旁文件和任意模组自定义存储未纳入保存证明；原版多文件存档不是跨文件事务，也没有目录元数据断电保证。投射物 / 构造物 / 拾取物仍不重生，依附它们的持续规则和历史引用需要单独政策。崩溃后对账、运行中可恢复检查点、规则 / 格式迁移及任意模组的加载与停服兼容仍待完成。


## 以接受射击重置周期补弹

[Reconstruction 示例](../common/src/test/resources/effects/reconstruction.json) 使用来源拥有的命名 `schedule`，首次 delay 与重复 interval 分别按普通 / 强化选取6 / 5.5秒。`chorus:source_attached` 配合 own_source 启动；`chorus:fire_accepted` 配合 owner + this_weapon 以 `policy: replace` 重置同一计时器，持续射击不会不断留下旧任务。这里监听接受事实，不能用 ammo_spent、hit 或 trigger 输入代替：它们分别也可能代表其他扣款、晚到 / 多次命中或被拒绝的射击。

定时事件通过 own_timer 只交给本来源处理；执行时 observe_ammo 获取当前有效基础容量，再将其同时用于补弹量和溢出上限。收枪不卸下来源，卸装则取消该来源任务；已有弹药账户保留。原版 / 数据武器的宿主必须实际发布接受射击事实，只有定时器和弹药账户不等于完整枪械。该组合没有增加引擎专用词条类型；数值和待校准边界见 [D2 规则集](d2-ruleset.md#reconstruction-的停火计时与周期补弹)。

### Rewind Rounds 的关联候选

[Rewind Rounds 示例](../common/src/test/resources/effects/rewind_rounds.json) 组合 fire_accepted 的弹量 / 容量快照、set_contains_event_reference 和现有整数 refill。计数只接受本来源 owner / weapon 的有成本射击与合格伤害；待结算 Buff 保存射空 origin_source、预期组数和冻结的最低发射弹数。shot_resolved 用组 ID 去重并累计 complete 状态，最后一个预期组才可查询当前弹匣并尝试 refill。来源必须提供与实际发射结构一致的 groups 和足够的 settlement_seconds；引擎不从一个组的结束推导全次开火结束。

minimum_fired_fraction、ignore_seconds 和 Adaptive Burst 的 adaptive_fraction 是明确校准输入；普通 / 强化60 / 70%使用十进制乘法与向上取整。只有正数转移才清除计数并授予抑制 Buff，零转移不触发；reload_finished 与 source_detached 分别处理重置和清理。凭据窗口 receipt_seconds 独立于命中计数，忽略期也记住回执，使用 source-owned after 逐项清理。具体时间政策、来源证据和正式内容缺口见 [D2规则集](d2-ruleset.md#rewind-rounds-的命中计数与射空补弹)。

### 固定元素爆炸、独立奖励与非零衰减下限

[Firefly](d2-ruleset.md#firefly-的固定太阳爆炸与独立装填奖励) 复用 `capture_damage` 静态声明Solar，不从输入事件选择元素。装填奖励与需要历史位置的爆炸分别响应同一个确认精准击杀事实；爆炸信息不足时不丢弃可确定的装填奖励。装填Buff使用 `instanced_by: weapon`、`affects: instance_weapon`、`refresh: reset` 和 `on_stow: keep`；source_detached显式清理是内容政策。共享属性Profile接入武器reload的profiles序列，实际接受的换弹时长在Buff到期后保持。

[firefly.json](../common/src/test/resources/effects/firefly.json) 要求7个来源参数：pve_damage / pvp_damage为damage，full_radius / radius为meter，delay为second，pve_minimum_falloff / pvp_minimum_falloff为multiplier。外部 `chorus_d2:firefly_outgoing` 负责输出缩放、`chorus_d2:firefly_falloff` 负责归一化过渡曲线；两者没有隐式默认校准。`ratio`、`arithmetic` 与 `scale` 已能组合出 `m+(1-m)*f`，平台1、边界m、半径外无目标；m和f越界明确诊断。最小倍率属于具体效果 / 来源配置，不是所有范围伤害的引擎硬编码。测试资源中的线性曲线和世界单位映射均不代表原作校准完成。

### 超能结束反应与基础容量组合

[Timelost Magazine](d2-ruleset.md#timelost-magazine-的超能结束与基础弹匣) 的结束输入为内容事件 `chorus_d2:super_ended`，必须带 `chorus:super_ability` 并保留实际owner。生产者负责区分自然结束、移除和取消；不能将 `ability_started` 或任意Buff结束当作超能结束。测试生产者通过实际付款后创建的持续Buff自然到期发出该事实，直接移除不触发。

定义要求两个未知回能校准参数 `base_super_gain` / `enhanced_super_gain`（charge_fraction），以及外部 `chorus_d2:magazine_capacity` 的base_bonus阶段和共享reload_insert_rounds Profile。先授容量Buff再refill_magazine，可让真实补弹、后续Overflow、逐发装填查询同一派生容量；原始capacity仍为稳定Profile输入。修改容量与修改已装弹数是不同动作，到期不会自动删除超额弹药。Buff按武器隔离且收枪保留，来源卸下显式清理；这些组合全部复用现有DSL。参考 [timelost_magazine.json](../common/src/test/resources/effects/timelost_magazine.json)。

### 伤害驱动的基础容量与独立回执身份

[Fail-Deadly](d2-ruleset.md#fail-deadly-的伤害实例与基础弹匣) 将长期层数和近期回执身份分开存放。`update_component` 的 `once_set` / `event_reference` 先接受不同 `damage_id`，成功后才增加层数；`death_id` 使用另一个集合，武器击杀只清进度。附属 `after` 任务逐项删除身份，receipt Buff 的刷新 / 到期限制无事件时的保留期；计数 Buff 永久且收枪保留。去重针对同一事实重复交付，不是禁止效果循环。

`observe_ammo.unmodified_capacity` 在第一次命中时建立本周期的固定层数基准，避免修改后的容量形成自增长。`fail_deadly_magazine` 接收 COUNT（普通层数、火箭层数乘 0.5）并返回 ROUND；`fail_deadly_range` / `fail_deadly_aim_assist` 接收和返回 MULTIPLIER，`fail_deadly_insert` 接收 MULTIPLIER 返回整数 ROUND，四者都能读取 base_capacity / stack_limit / stacks。数据包必须显式提供这些 Profile，以及 `magazine_capacity` 的 base_add 阶段、共享 weapon_range / weapon_aim_assist 与 reload_insert_rounds；未提供的依赖不能被当作已校准默认值。

`receipt_seconds`（second）、`enhanced_range` / `enhanced_aim_assist`（stat_point）是词条参数；后两项在原表未知。实例 Buff 通过组件为容量增加发数、为两项属性增加点数，逐发装填仍由已接受步骤的共享查询处理。容量值独立于已装弹数，清层不删除已有溢出，也不会自动补弹。参考 [完整定义](../common/src/test/resources/effects/fail_deadly.json) 和 [合成曲线](../common/src/test/resources/effects/fail_deadly_test_calibration.json)；Range 已有下文的实际弹丸距离衰减参考消费者；辅瞄仍仅为数值查询，不能算成准星磁性或目标获取验收。

### 目标共享的承伤状态

[Weaken](d2-ruleset.md#共享-weaken-与可续时的强削弱) 复用 `inspect_entity`、`apply_status` 和带单位的组件写入，在状态授权成功后才更新目标共享的 strength。跨来源不重复建层；状态配置 MAX_REMAINING，组件显式取 MAX，两者分别控制时间与强度，不能假设选强度会自动选择同一来源的时长。固定时长、强度和保留来源的政策均属于内容定义。

目标 `defense_profile` 的 global_debuff 组以 MAX 归并承伤增幅，随后 resistance 组计算独立减伤。它与攻击端伤害快照分别求值，新的目标状态无需重建已发射攻击；来源归因仍是原攻击。共享 `movement_attributes` 的 status 组投影原版移动属性，到期和死亡清理沿用通用 Buff 生命周期。此例没有新增检查点类型或引擎 Java 分支。


### 用历史命中事实组合反应资格与范围状态

[Singularity Blade](d2-ruleset.md#singularity-blade-的命中资格与击杀扩散)使用现有 `event_buffs_available` / `event_has_buff_tag` 查询事实记录的攻击者 Buff；它们不等价于数值修饰的 `on_hit` 或读取处理到 kill 时的实时状态。同一伤害生成的 hit / kill 共享观察，因此前面的反应不会改变后面的历史资格。同步动作宿主未提供 Buff 观察时，伤害动作可在结算边界补采样；显式观察未包含该 holder 时仍是未知。直接输入的事实若没有观察，内容须明确处理，不能把未知当作空 Buff 或自动读当前状态。

击杀反应先检查历史位置是否可用，再用 `read_event_position` 绑定位置，随后 `select_targets` 查询当前目标；不重新读取死者坐标。`invoke_bundle` 的 `origin: event` 保留攻击信用，但被调 bundle 仍独立检查目标存活与状态授权。目标列表中的每一项可以有不同结果；范围状态不必产生伤害。时间 / 强度、范围锚点、阵营和视线策略由内容显式决定。

此组合没有新增 Java 动作或检查点类型。来源实例化 Buff 的两个身份集合分别记录 hit 与 kill，`once_set` 控制重复事实，`forget_set_entry` 与桶到期负责有界清理；有限去重窗口不限制合法后续命中与效果链。完整来源装配、实际行为与原作校准不能由这套通用接口的存在自动推定为完成。


### 持有者收益与历史击杀资格

[共享 Devour](d2-ruleset.md#共享-devour-的治疗续时与击杀手雷回能)在Buff作用域用`target_is(self,event_actor)`确定收益持有者，避免把最初赠予者当成击杀者。`event_has_buff`还要求该次死亡的历史观察已有状态，当前存在不能反推过去资格。重复身份集合放在共享目标状态中，赠予者不同不会重复发奖。

恢复、续时、回能是独立的语义结果：缺少死者tier只跳过未知的回能；治疗满血 / 已知拒绝也不能当作其他收益失败。相反，世界治疗结果未知会中止顺序动作，保留已提交的状态并等待宿主处理，不补发后续动作或重放治疗。`grant_ability_energy`在执行时解析当前技能槽、归一化收益与接收账户，不从旧Buff来源绑定某个已卸下技能。

源代码定义要求显式外部`health_projection`，以及调用者的时长 / 上限 / 回执期限；已有引擎接口足以组合此状态，没有新增Java动作或检查点类型。时间上限、换装时采样、各收益先后与回执有效期仍属于内容政策，须分别保留原作校准证据。


### 自动激活与已有共享状态的结算边界

[Feed the Void](d2-ruleset.md#feed-the-void-的自动激活与共享收益)把来源资格和共享Devour收益分开。资格必须检查`event_buffs_available`，再判断历史不存在；同时检查当前`has_buff`，避免同一事件前面的反应已授予状态后再次治疗。新状态不能让旧事实逆向取得持续击杀奖励；事件归因中的ability名称也不能代替明确的damage/kill信用。

多个来源可能在同一事件帧排队`invoke_bundle`，调用者不能只用当前Buff不存在来保证单次激活。此内容用独立目标共享的有限death_ids先接纳，再排队调用；回执不依附被清除的Devour，也不随单个来源卸下清空。`after lifetime=detached`保留清理参数，目标Buff本身max_remaining限制总寿命。该用途是回执幂等，不限制合法效果循环；期限和最终装配唯一性仍是宿主/内容的显式契约。


### 拾取许可与拾取奖励独立

[Starvation](d2-ruleset.md#starvationvoid-breach-与能量球收集)同时需要接触前许可与消费后奖励。内容用按来源的能力Buff表达`orb_pickup_permission`，`collect_if`实时检查收集者的Super账户或该许可；解绑撤销许可但不删除已经生成的物体。已存在/缺失账户先用`ability_energy_flag(available)`区分，再读取full状态；缺失不是空能量。

`chorus:pickup`来源保留生成者，actor/victim才是收集者；被动奖励使用`target_is(self,event_actor)`，不能用`source_is(owner)`把他人产生的Orb拒绝掉。真实物体在奖励前先消费，奖励动作中的已知满账户/无选择不会撤销拾取事实；未知世界动作则按既有顺序停止后续执行。Starvation通过独立有限pickup_id集合保证反应幂等，不能把它推广成外部重复contact可安全重放所有基础收益。


### 组合调用中的生成回执与冷却

[Harvest](d2-ruleset.md#echo-of-harvest-的削弱击杀与确认生成)示范“生成成功才冷却”的内容组合。调用者先保存带death_id的共享pending，再`invoke_bundle`一个include通用Orb模板的组合包。`pickup.spawn_as`返回世界确认结果；模板按结果`emit`成功/拒绝事件，事件保留完整调用载荷。组合包回调检查`own_source`与pending中的身份后，才提交冷却、释放预约并调用下一种拾取物。没有新增同步函数返回语法，也不把排队调用当作世界成功。

原来源卸下后，已接纳临时调用仍能收到自己的回执；其他实时来源不能代为消费该回调。已知拒绝可由内容继续尝试独立收益，未知结果则遵循运行时停止规则。pending期间到来的新击杀在本内容中被记录并跳过，不能在回执返回后补发成一次新的激活；具体排队政策不应默认为所有内容的通用规则。


### 状态施加事实与共享状态来源

`apply_status`收到ALLOWED并提交Buff后，在该次Buff生命周期事实之后排队`chorus:status_applied`。actor/source来自这一次世界授权请求，victim是接受者；共享Buff仍保留原先的origin与generation。例如A压制目标后，B再次成功压制，B获得本次施加信用，目标状态的最初来源仍是A。直接监听`buff_gained`的旧来源不能表达这项区别。

事实以`EffectEvent`携带Buff定义标签，numbers为`requested`、`stacks_before`、`stacks_after`（count），flags为`applied=true`和`new_instance`。references包含`status_id`、`buff_definition`、`buff_holder`、`buff_instance`、`buff_generation`，有伤害原因时另含`damage_id`。status_id由授权操作的frame/pc/invocation生成；同一Buff、同一damage_id的两次独立施加也有不同身份。检查点延续操作序号；它不承诺任意独立新会话之间的全局唯一性。

授权通过、状态提交即产生一次信用，即使满层或max_remaining使层数/截止点没有增加。DENIED/DEAD/MISSING、只读`check_status`、纯`grant_buff`、直接`refresh_buff`及移除均不发此事实。采用check_status后手动grant的复合内容也不会自动发布，迁移或显式内容事实须另行决定。事件不补造实体/位置/Buff历史观察，不将先前伤害快照冒充本次授权观测。

动作仍先提交状态，再执行排队反应；回调的未知世界结果不能撤销状态或重试本次施加。后续规则判断是否仍有该状态应查询当前has_buff，不能把已发生的施加事实当作永久存在保证。此接口供Domineering等“成功施加后获益”规则使用，接口验收本身不等于这些Fragment已经完成。


### Buff 授予事实与本次给予者

`grant_buff` 提交后排队 `chorus:buff_applied`；获准的 `apply_status` 也发布该事实，顺序为生命周期事实、既有 `status_applied`、`buff_applied`。两种动作各发布一次。actor/source 是本次动作来源，victim 是请求中的接收者；共享实例的最初来源与 generation 不因此改变。使用 `buff_gained`/`buff_refreshed` 读取到的仍是实例来源，不能据此奖励本次刷新者。

事件使用已有 `EffectEvent`，带定义标签；numbers 为 `requested`、`credited`、`stored_delta`、`stacks_before`、`stacks_after`（count），flags 为 `applied=true`、`new_instance`、`status_authorized`。最后一个标志仅在 `apply_status` 已获世界授权时为 true；纯授予为 false。references 为 `buff_application_id`、`buff_definition`、`buff_holder`、`buff_instance`、`buff_generation`。接收者是动作目标，`buff_holder` 是实际绑定键；绑定到武器的 Buff 不应把这两个字段混用。

`buff_application_id` 由当前 frame/pc/invocation 组成，同一会话的独立授予具有不同身份，检查点保留计数；不承诺独立新会话的全局唯一性。满层或相同截止点的已接受重施加仍有新事实。仅 `check_status`、计时器刷新、延长、消耗、移除、到期、恢复检查点，以及内部领域状态的直接构造不发布该事实。拒绝或未知授权也不发布。纯授予可创建数据状态，但不检查世界目标，因此它不能证明目标存活、关系正确、Cure 实际治疗或复活成功；这些资格仍由相应世界查询和回执表达。

先提交状态、后排队反应的契约不变。先前生命周期反应清除了 Buff，不能抹掉已经发生的授予；需要当前状态时应重新查询。未知下游世界结果保留已提交状态并停止，不能重放授予。新增事件使用已有存储类型，没有修改 `(状态, 事件) → (新状态, 动作列表)`，也没有增加全局防循环限制。

该接口补齐 Benevolence 等“给予盟友增益后获益”的来源表达；接口通过测试不等于相关 Fragment 已完整实现。Benevolence 的原表、现行数值与生产者例外已存入 [来源记录](../data/d2-research/2026-10-13/benevolence-source.json)，后续内容需要分别消费增益授予、实际治疗和复活事实。

## 抑制施加与 Domineering 组合

`suppression.json` 现在提供 `chorus_d2:suppression_application`。用 `invoke_bundle` 调用 `chorus_d2:apply_suppression`，明确传入 `combatant_duration` 与 `guardian_duration`（均为正 second），并保留真实动作 origin / victim。施加器观察目标是否存在、存活及是否玩家，然后执行 `apply_status`；授权成功后由引擎提交 `status_applied`，不是由内容包自行伪造。`chorus_d2:clear_suppression` 是显式清除入口，目标死亡也会清理活动状态。

Domineering内容链接 `suppression`、`character_stats`、`directional_movement_attributes` 与共享 `void_breach`，Fragment来源须给出正 `receipt_seconds`。实际抑制施加后，当前申请者四秒内最多一次获得十秒收益及持握武器补弹；击杀条件读取历史抑制并独立调用Breach。详细规则、源版本冲突和当前物理边界见[D2规则](d2-ruleset.md#echo-of-domineering-的抑制施加信用与持握补弹)。`domineering_inputs.json` 是合成武器与命中施加器的验收夹具，不是发布中的完整技能目录。

## 终结技的占用、确认与取消

`chorus:begin_finisher` 是数据包可调用的动作。它先请求服务器观察资格，再原子授予施放者与目标各一个占用 Buff，保存伤害请求和截止时间，发布 `chorus:finisher_started`。普通 `ability_started`、纯授予 Buff 或带 `finisher_kill` 标签的任意伤害都不代替这个过程。当前接口由可信效果来源调用；下文独立输入会向这些来源发出终结技请求，动画仍未接入。

```json
{
  "action": {
    "type": "chorus:begin_finisher",
    "target": "victim",
    "actor_buff": "example:finishing",
    "target_buff": "example:being_finished",
    "duration": {"type":"chorus:constant","value":0.3,"unit":"second"},
    "policy": {
      "maximum_health_fraction": 0.2,
      "range": 3,
      "eligible_tag": "example:finishable",
      "line_of_sight": true
    },
    "damage": {"type":"chorus:constant","value":1000,"unit":"damage"},
    "damage_type": "example:finisher",
    "tags": ["chorus:kinetic"]
  },
  "as": "attempt"
}
```

结果提供 `accepted` / `busy` / `rejected` 布尔字段。`origin` 默认为 `bound`，可显式选择 `event`；其 owner 必须等于当前规则 holder。可选 `damage_profile` 指定输出伤害 Profile。上述 0.3 秒、20%、3 米、1000 点和 Kinetic 都是示例输入，绝不是全体 D2 终结技的默认值。伤害类型也须由宿主映射到实际 DamageSource。

两个 Buff 都必须是 `attach: target`、`instanced_by: none`、`max_stacks: 1`、`duration: permanent`、`timer_mode: shared`、`on_stow: keep`，并带 `chorus:finisher_participant` 标签。省略字段时仍须符合相应默认值。角色可以共用一个定义，也可以使用不同定义；占用检查读取共同标签，因此不同终结技来源也不能争抢同一参与者。同一原子写入保存两端 generation；释放只移除本次占用，不能删除后来替换的 Buff。动作、移动限制以及期间护盾等内容应绑定这些占用 Buff；单独监听开始事件不足以证明稍后仍在终结技中。

Minecraft 宿主要求双方存在、存活、同维度，施放者不是旁观者、乘客或睡眠状态，目标不是玩家、自己或盟友，并且目标具有 `eligible_tag` 对应的**实体标签**。未知阵营也拒绝。接受时以原版当前 HP / 最大 HP 检查闭区间上限，不将 Absorption 或 Chorus 护盾并入分母；之后不因目标恢复血量而撤销已接受动作。距离使用双方脚底位置，视线使用施放者眼睛到目标身体；只观察已有区块。完整 D2 敌人目录、血条阈值、吸附位置、等级限制和技能例外由内容/宿主另行校准。

计划以现有 detached 单次计时器保存，每 50 ms 或最终截止点重新检查两端 generation 和世界资格。Buff 被移除、实体消失/死亡、距离或视线不再允许时，释放两端并发布 `chorus:finisher_cancelled`。50 ms 是本实现的观察间隔，不是原作动作时长或触发冷却。来源解绑不撤销已经接受的计划。显式取消可调用：

```json
{"type":"chorus:cancel_finisher","participant":"self","reason":"example:input_cancelled"}
```

取消读取该参与者正在等待的计划，立即释放对应两端与计时器，重复取消不补发事实；它不影响其他独立配对。内容可以据此接死亡、中断或用户取消事件，普通失联由 watchdog 最迟在下一次观察时发现。

截止点检查通过后，伤害进入现有 `prepareDamage → 世界执行 → DamageReceipt` 管线。输出数值的动态修饰在出手时计算，基础伤害、类型、标签、来源和 Profile 已在接受时捕获。引擎补充 `chorus:finisher_damage` / `chorus:finisher_kill`，不隐式赋予普通近战或武器信用，不改变其他连锁策略，也不自动赋予目标免伤。

只有实际回执包含确认死亡，才在普通 hit / damage_taken / death / kill 事实之后发布 `chorus:finisher`。该完成事实沿用 kill 的 `damage_id`、`death_id`、历史 Buff、实体和位置观察，并加入 `finisher_id`，不会重新查询已移除的尸体。两端占用在这些反应执行前已释放。已知未致死结果发布取消（`not_lethal` 或 `death_prevented`）；世界结果未知则停止会话，保留已提交状态，不猜测完成、不自动重放伤害。

开始/取消带 `finisher_id` 和接受时的 `duration`，取消另带 `reason`。目前实际击杀点就是计划结束点；动画中途击杀后继续收尾、第三人称表现、目标提示、目标保护政策和正式 D2 终结技内容仍待实现。纯会话检查点可保留计划、两端占用和时序；这不等于 Minecraft 实体/动画已经完成跨重启恢复。实现与验收见 [FinisherActions](../common/src/main/java/com/imdomestic/chorus/effect/data/FinisherActions.java)、[规则测试](../common/src/test/java/com/imdomestic/chorus/effect/FinisherTest.java)和[世界场景](../common/src/gametest/java/com/imdomestic/chorus/test/FinisherGameTest.java)。


### 独立终结技输入

Fabric / NeoForge均注册 `chorus:finisher_input` C2S包和可改绑的 `key.chorus.finisher`（默认H），同时提供无管理员权限的自助命令 `/chorus finisher`。客户端包只有正整数sequence和dimension；目标、玩家身份、坐标、资格、时长、元素、伤害等都不从客户端接收。打开界面、窗口失焦、死亡/旁观或服务器不支持该通道时，客户端不发请求，也不会在关闭界面后补发此前按键。

服务器先按连接消费单调递增sequence，再检查发送者与维度，因此重复、旧维度、拒绝和未知结果都不能重放。断线清理连接序号，停服清理对应服务器的连接；进行中的计划由既有世界资格检查处理失联。运行时必须健康、空闲，已经参与终结技的持有者直接返回BUSY且不扫描/排队。输入没有附加玩法冷却。

合法请求发出 `chorus:finisher_requested`：actor/victim都为实际发送者，origin仅带其owner，标签`chorus:finisher_input`，引用`request_id`由服务器生成，并观察on_ground/sprinting/crouching。该事实是意图，DISPATCHED只表示已派发，不代表存在可终结目标。没有绑定对应内容来源时，不会创建默认终结技、伤害或技能账户。

数据来源应先限定self==event_actor，再用服务器方向、目标查询、候选顺序和 `begin_finisher` 的实际资格检查选中目标。[finisher_input.json](../common/src/test/resources/effects/finisher_input.json)展示一个明确的合成策略：当前朝向圆锥长3块、末端半径2块、敌方/视线筛选、距离升序/身份并列排序；遍历全部候选，跳过无资格者，首次占用成功后不再尝试其他候选。因此最近但血量过高的目标不会挡住下一个合格目标。该策略不是已校准的D2准星/吸附算法。

终结技独立于 `AbilityUse`，不选择或支付普通技能槽，也不默认继承 `ability_use` 限制；终结技专属禁用条件仍由来源条件、世界资格及占用Buff定义。正式职业/终结技目录、敌人资格标签生产、HUD目标提示、视觉动画和所有原作控制/保护特例尚未完成。网络序号是连接输入状态，不是玩法存档；本阶段未改变效果检查点结构。

共享世界验收覆盖低权限命令、当前服务器朝向、候选筛选、实际击杀、重复/错误维度/忙碌请求、真实发送者资格、来源解绑和开始回调未知结果。Fabric客户端场景使用真实H键、注册包、服务器选敌和实际延迟击杀，并验证背包界面阻止输入以及关闭后不补发。NeoForge当前验证服务端链路和编解码，尚无同等真实客户端运行证据。

## Cessation 与直接易爆施加

[echo_of_cessation.json](../common/src/test/resources/effects/echo_of_cessation.json)组合已确认的终结技、历史位置查询、逐目标状态授权和共享拾取物。绑定Fragment时必须提供`burst_radius: meter`与`receipt_seconds: second`；4米和十二秒是验收输入，前者原表仍未知，后者仅为有限回执保留期。

确认终结技以`finisher_id`去重后读取victim历史位置，筛选相对于event_actor的非盟友，排除施放者与被终结目标。对每个候选调用共享[volatile.json](../common/src/test/resources/effects/volatile.json)中的直接状态生产者：

```json
{
  "type": "chorus:invoke_bundle",
  "bundle": "chorus_d2:volatile_status_application",
  "event": "chorus_d2:apply_volatile",
  "target": {"binding": "neighbor"},
  "origin": "event",
  "parameters": {
    "combatant_duration": {"type":"chorus:constant","value":10,"unit":"second"},
    "guardian_duration": {"type":"chorus:constant","value":6,"unit":"second"}
  }
}
```

被调用规则限定owner/own_source、正时长和没有已有Volatile/重施加冷却。`inspect_entity`确认目标存活和实际player分类，再以相应时长`apply_status`；成功后沿用通用`status_applied`事实。调用本身不表示状态已经写入，拒绝或未知结果也不会生成成功事实。当前生产者沿用保留首来源且不刷新已有Volatile的内容政策，不能据此推定所有原作施加来源都遵循相同刷新规则。

Fragment的击杀分支以独立`death_ids`去重，使用死亡时历史Volatile标签调用共享Void Breach，忽略原施加者身份。两个集合由已有Buff组件和detached清理计时器组成；有限保留防重复事实，不限制独立新击杀、技能循环或同根连锁。具体状态、裂口数值及当前校准缺口见[规则集](d2-ruleset.md#echo-of-cessation-的确认终结与历史易爆击杀)。

共享Volatile三个爆炸分支均先检查`event_position_observed`：已观察则使用`read_event_position`返回的位置，包括明确不可用的结果；仅对从未观察位置的旧宿主保留EntityCenter查询。不要把“历史位置不可用”与“未观察历史位置”合并，否则会把爆炸移动到反应执行时的新位置。Fragment自己的终结爆发要求历史位置，不使用这一旧宿主回退。

## Armor Charge 组合协议

[armor_charge.json](../common/src/test/resources/effects/armor_charge.json)展示可共享的离散层数池如何由Buff、来源贡献、延时生命周期与实际拾取事实组合。每个接收者安装恰好一个`chorus_d2:armor_charge_system`，必填`receipt_seconds: second`；模组来源来自[armor_charge_mods.json](../common/src/test/resources/effects/armor_charge_mods.json)，一份来源表示一个装备副本。上限/额外获得/计时参数由接收者来源汇总，不能把调用者的配置复制给接收者。

通用获得调用：

```json
{
  "type": "chorus:invoke_bundle",
  "bundle": "chorus_d2:armor_charge_application",
  "event": "chorus_d2:apply_armor_charge",
  "holder": {"binding":"recipient"},
  "target": {"binding":"recipient"},
  "origin": "event",
  "parameters": {
    "amount": {"type":"chorus:constant","value":1,"unit":"count"},
    "bonus_eligible": {"type":"chorus:constant","value":1,"unit":"count"}
  }
}
```

`amount`应为正整数；`bonus_eligible`由具体生产者明确给0或1。接收者没有控制器、没有获得资格、缺失或死亡时不授予。原始来源保留在池与层的origin中，池的目标绑定确保不同给予者共用同一接收者容量。直接调用不是已经确认的拾取事实，也不隐式添加永久去重。

控制器在来源变更及池的gained/stacks_changed/ended事实后调用同一个`armor_charge_reconcile`包。它汇总当前源贡献、更新派生配置、裁剪超出容量的层数、同步Orb许可，并保证被动衰减只有一个实例。普通获得保留既有计时实例，消费先移除旧实例再建立完整周期；自然到期只消费一层。删除计时器产生removed事实，不能再次走expired分支。一个buff_stacks_changed反应不等同于任意游戏的“花费奖励”，消费者仍应依据自己的实际消费结果决定发奖。

消费可以在同一规则中先检查`buff_count >= cost`，再使用`consume_buff`并继续收益动作。消费回执的`stored_delta`为负数，`credited`不是支付量；部分或全部不足的策略必须由消费内容明确选择。世界收益随后失败时保留已经支付的层数，未知结果沿用停止并不重放的执行协议；不要通过旧拾取事实补回成本。

六种Font由一个持有者控制Buff贡献20/40/50点，当前副本数在来源变更结算后更新。安装/撤销控制器会同时清理池、衰减和本系统许可，有限拾取身份按独立期限保留，避免控制器重绑重放旧球。普通模组变更、降低容量和变更周期的具体政策见[规则集](d2-ruleset.md#armor-charge-的共享层数衰减与-font)；正式装备限制、完整消费者和HUD仍需内容层接入。

### 终结技模组的付款与收益

链接 `armor_finisher_mods`、`armor_charge`、`void_overshield` 及其正常 Profile 依赖，为角色绑定一个 `chorus_d2:armor_charge_system`，并为每个实际模组副本绑定对应 bundle：`healthy_finisher`、`bulwark_finisher`、`explosive_finisher`、`one_two_finisher`、`utility_finisher` 或 `empowered_finish`（均为 `chorus_d2:`）。它们不要求额外参数；有限去重窗口读取控制器的 `receipt_seconds`。测试专用 `armor_finisher_inputs` 提供合成终结输入和代表性两充能近战账户，不是可发布的角色配装。

执行顺序是：确认终结事实 → 共享身份记录 → 当前模组模式归约 → 实体与收益需求观察 → 捕获应付层数 → `consume_buff` → 核对实际 `stored_delta` → 世界治疗、共享护盾或当前技能回能。至少三层的模组消费全部当前层数，并使用捕获的支付数量选表，不能在扣费后重新读空池推算收益。原子纯状态付款完成后，治疗被取消或其回执未知不会自动退款；后者停止运行时，禁止猜测结果后重发世界动作。

`armor_charge_finisher_modes` 每种模组用独立 MAX 组计数，相同模组多副本仍计 1。多个不同种类被注入时不选择一个赢家，不付款、不发奖，但终结本身正常完成。正式装配应先拒绝非法模组组合；当前运行时保护不能替代插槽、能量成本和完整冲突类别校验。

`armor_finisher_receipts` 是目标共享 Buff，记录包括“没钱”“已满”和模式冲突在内的已处理完成事实。来源移除不清掉它；同一身份在窗口内不能通过换装、重新获得资源或受伤再次获益。detach 延迟清理按身份执行，新 finisher_id 正常参与后续效果循环。来源移除也不收回已授予的共享护盾或资源。

实际充能来源和当前模组选择都在完成时读取，能量通过选中的基础槽回充目的账户，允许该账户的既有收益 Profile 修饰。满资源、多充能和 Bulwark 刷新政策及原作未定标边界见 [D2 规则集](d2-ruleset.md#消费-armor-charge-的终结技模组)。

### 只读观察当前终结阶段

```json
{
  "action": {
    "type": "chorus:observe_finisher",
    "actor": "self",
    "match_event": true
  },
  "as": "phase"
}
```

`observe_finisher` 查询 actor 当前拥有的有效终结计划；两个参与者占用 Buff 的 generation 必须仍有效，逻辑时间必须在接受与计划截止点之间。目标受害者的“被终结占用”不算自己正在执行终结。`actor` 默认 self；`match_event` 默认 false，设为 true 时还要求当前事件的 `finisher_id` 与计划相同，缺失或空身份返回 inactive。

结果提供 `active` 标志，以及以 second 为单位的 `remaining`、`elapsed` 和 `duration`。先判断 active，再读取数值；不存在计划时不伪造零时长。它不发世界请求、不修改状态、不产生事件，得到的回执也不会随后续时间或新计划变化。该回执只证明会话中的计划与占用，不能代替世界存活、距离或视线检查；这些仍由终结 watchdog 检查。查询的是当前可继续的阶段，完成/取消事件里的历史身份不等于仍在执行；到达截止点后即使世界结果未知，也不能用旧开始事件重新取得完整时长。

持久化新增 `FinisherActions.Observe` 与 `Finisher.Observation` 两个类型，不改变已有 Plan、EffectState 或字段结构。观察回执可随等待中的动作帧保存，当前有效计划仍使用原有两侧占用与 detached watchdog 协议。


### Proximity Ward 的阶段绑定

链接 `proximity_ward` 后为模组来源绑定 `chorus_d2:proximity_ward`，提供正数 `receipt_seconds`。它独立于 Armor Charge 控制器；生产绑定不应因为名字中含 Finisher 而给它计入消费模组互斥组。现有来源输入产生 `chorus:finisher_started` 后，规则通过 `observe_finisher(match_event=true)` 验证当前身份并读取 remaining，再创建同名目标共享护盾。

`proximity_ward_receipts` 的有效窗口取 receipt_seconds 与 remaining 的最大值，相同身份只能授予一次；盾中的 `finisher_ids` 只负责匹配终止事件。破盾清除盾实例但保留收据，因此开始事实重投不会重新给200 HP。新计划先替换遗留盾实例，再写自己的身份和容量。护盾的有限期限独立于来源生命周期；清理规则在 buff scope 中执行，解绑模组后仍能处理匹配取消和死亡。完整数值来源、换装与动画边界见[D2规则集](d2-ruleset.md#proximity-ward-的终结阶段护盾)。

护盾的有限期限按正常运行时的逻辑钟推进。若在截止点前出现未知结果，诊断状态冻结且不自动重放；Minecraft失败运行时已停止贡献Chorus护盾，后续原版伤害直接走无Chorus盾层的路径。不能通过在故障域中手工推进Buff时间冒充已知完成。

### Kickstart 的技能付款组合

链接`armor_charge`、`armor_charge_mods`及`kickstart`，每角色安装一份Armor Charge控制器，再为装备副本绑定`chorus_d2:grenade_kickstart`、`chorus_d2:melee_kickstart`或`chorus_d2:utility_kickstart`。每份来源各贡献一份获得资格和自己的副本Profile；Profile把贡献限制在0–3，合法槽位/能量预算仍应由正式装配验证，限幅不能替代合法性检查。三种内容分别匹配`chorus_d2:grenade`、`chorus_d2:melee`、`chorus_d2:class`。

`ability_started`来自已经接受并提交成本的服务器施放，包含`cast`和`ability_slot`引用、CHARGE单位的`paid`及布尔`free`。这些实际成本字段覆盖输入中同名值。对应规则读取事实而不读取其他技能的资源消费；普通`resource_spent`与原版命中均不能冒充接受。该反应在排队的技能主体前运行，所以主体之后发生世界失败不会撤销已完成的模组付款或回能。

单个规则按以下次序组合现有通用动作：

1. 用目标共享`kickstart_receipts.cast_ids`记录本次身份，刷新有限期限并安排detached清除；控制器的正数`receipt_seconds`提供保留时间。记录先于收益判断，重复副本不会再次进入付款。
2. 检查实际`paid >= 1`、非免费、无`chorus_d2:defer_kickstart`标签且Armor Charge非空。部分成本不会自动累计；特殊阶段必须有自己的后续结算。
3. `observe_ability_energy`读取对应槽位当前基础选择，要求available且非full。捕获Armor Charge层数，计算当前本模组副本，保存两者之和。
4. `consume_buff`消费全部捕获的层数，验证回执`stored_delta`等于负的应付数量。不能从已经清空的池反算档位，也不能把消费函数的限幅当作成本充足证明。
5. `grant_ability_energy(value_basis=base)`按运行时PvE/PvP表向该槽位返还能量，由当前接收者管线应用缩放一次。

现有合成输入以两充能容量、0.5接收者系数和可选×2收益验证组合；真实Shield Throw验证其既有0.9Profile。共享池归零后Font属性与衰减计时由Armor Charge控制器正常撤除，没有新增专用Java执行器或检查点类型。只保存纯会话身份不等于整个Minecraft世界已持久化。

延后标签当前是显式排除契约，不能声明已实现落地触发。未来Grapple如果必须等接触Tangle才确定免费使用，也需要先延后对应资格，不能追溯修改已发布的`paid/free`或自动撤回已经产生的其他效果。已经明确为免费的施放应以实际零成本接受；一般回能/返还则不自动成为免费使用。具体表格、官方多充能修复和未完成特例见[Kickstart规则](d2-ruleset.md#kickstart-的完整付费充能与回能表)。

### 公式内的通用单位查询

`chorus:profile_value`把一个实体的数值Profile输出作为Value使用；输入单位必须匹配被查询Profile的input_unit，输出单位由该Profile的最后一步推导，可改变单位。与仅允许stat_point→stat_point的`chorus:attribute`分开，旧属性表达式仍按原有契约验证。例如验收夹具将副本数汇总为count，再将count经独立曲线转换为second，用于真实延迟动作：

```json
{"type":"chorus:profile_value","profile":"test:duration","target":"self",
 "input":{"type":"chorus:profile_value","profile":"test:copies",
          "input":{"type":"chorus:constant","value":0,"unit":"count"}}}
```

`target`默认self；在调用处先解析目标和input，再进入该实体的独立上下文。actor/victim/source.owner均为查询实体，source.source为Profile ID，外层攻击/击杀的武器、技能、标签、测量、标志、引用及历史观察不继承。参与贡献的真实来源参数与来源归属仍可读取。需要原事件数据时，应显式作为input在调用处求值，或使用保留事件上下文的calculate动作；不能把未知字段当作零。

该查询不写状态、不发布事件、不调用世界。Java的`CompiledEffects.profileValue`返回CalculationProfile.Result及当前查询轨迹；Value只取output，外层轨迹不会自动嵌套子查询轨迹。缺少Profile、输入单位不符、输出不能满足消费者、未知目标绑定或不兼容的当前定义都会失败。现有归约、限幅、模式、来源选择和单位转换仍由Profile本身负责。

on_use捕获来源侧查询时，input与查询必须当场可求值，结果固定成常量。victim侧保留表达式，其input的来源侧操作数先冻结，impact_number可保持符号等待实际命中；每次命中读取该目标的当前Profile与来源。来源侧的查询如果需要尚不存在的impact输入，会明确失败，不推测数值。延迟动作中的普通表达式则到执行时重新读取；需要保留接受时结果应先capture_value。

数值依赖仍按实体+Profile身份追踪，与attribute及动态弹匣查询共享同一路径；允许跨实体相同Profile及独立重复查询，拒绝直接或相互自依赖的公式。这里仅检查一次数值求值是否存在未定义递归，不限制游戏效果事件的循环或新施放。持久化只新增Value.ProfileValue类型，NumericQuery及旧record字段不变；纯会话检查点保留表达式、已捕获结果和延迟时间。10项规则测试与真实延迟治疗/原版伤害场景验证此接口；尚不表示Weapon Surge完整内容或正式装备装配已实现。

### Weapon Surge 的计数查询与伤害阶段

链接`weapon_surge`和包含`weapon_surge`阶段的`empowering_damage`，以及已有Armor Charge基础定义/控制器。每个装备副本绑定`chorus_d2:<element>_weapon_surge`，element为kinetic、arc、solar、void、stasis、strand之一。来源贡献enabled/passive配置及同元素copies Profile，伤害Value按以下方式读取当前副本数，再通过带单位的表格转成delta：

```json
{"type":"chorus:profile_value","profile":"chorus_d2:solar_weapon_surge_copies",
 "input":{"type":"chorus:constant","value":0,"unit":"count"}}
```

Profile把副本总数限于0–3，当前source只有在目标共享Armor Charge存在、攻击带weapon_damage和匹配分量元素标签时贡献。完整档位值写入`chorus_d2:empowering_damage`的`weapon_surge`阶段/组，operation=multiply、reduction=MAX；外部同类金装/神器须贡献到同一阶段，而empowering和独立perk继续在其他阶段归约。同元素同档的重复来源不会再次相乘。限幅不能替代正式插槽和预算校验。

被动衰减仍只有Armor Charge控制器的一份计时，涌动不自行创建每元素倒计时、不按命中扣层；移除来源只撤回它的当前数值与被动贡献。独立高档Buff应保留自己的到期时间，不能通过删除/暂停低档来源实现覆盖。当前on_use捕获把profile_value副本查询及充能资格固定进攻击，后续来源/充能变化作用于新攻击；原作时序仍需逐武器校准。

验收武器的六种分量显式选择这个伤害Profile，使用真实容器、服务器开火和物理接触。已有Rampage定义仅映射数值目的地，测试断言其他内容保持一致；合成四档Buff不冒充任何具体金装。其他独立伤害Profile不会因为链接了此模块自动继承涌动，尤其Scorch/Ignition仍需显式适配及来源资格验收。正式数据包装配、三能量成本、特殊伤害例外和HUD未完成。[数值与来源](d2-ruleset.md#weapon-surge-的元素档位与独立计时)。


## 不同 Profile 共享数值阶段

`chorus:shared_apply`让目标 Profile 接收另一个 Profile 同名阶段的贡献。它保留完整的本地 `apply` 布局，链接时要求被引用阶段的操作、分组树、家族政策、百分比基准、命名因子及贡献单位一致。例如独立的持续伤害 Profile 可以共享武器增伤阶段，前后仍使用自己的伤害载荷与单位转换：

```json
{"type":"chorus:shared_apply","profile":"chorus_d2:empowering_damage",
 "apply":{"id":"weapon_surge","operation":"multiply",
          "group":{"name":"weapon_surge","reduction":"max"}}}
```

共享的是贡献入口，父 Profile 的转换、其他阶段和计算结果不会进入目标计算。目标阶段同时收集自己的贡献与导入贡献，在一个分组中执行 MAX / PRODUCT 等规则；不能先算父 Profile 的最终倍率再相乘，否则会破坏互斥分组。局部贡献也不会反向流入父 Profile。可继续引用另一个 shared_apply；相同真实 modifier 每次只访问一次，跨层共享不会复制副本。

查询保留原来的攻击、武器 / 技能、伤害标签、施加者与实际目标。原 modifier 的条件、provider、Buff 作用域、逐层身份及 on_use / on_hit 策略全部保留。和隔离攻击上下文的 profile_value 不同，这个接口适用于攻击阶段复用。共享数值阶段不会自动授予武器信用、扩大 perk 的资格或导入全部近战增伤；内容仍须声明准确的条件。

快照保存共享阶段的完整布局及已捕获贡献。卸装、来源 Buff 到期或目录替换后，on_use 仍用旧值；on_hit 从当前目录收集实际持有者 / 目标的贡献，目标 Profile 的布局若与快照不兼容则拒绝混算。只复用贡献单位：multiply 的 delta 可跨不同输入单位，add / replace 必须与所在阶段的实际单位一致。缺失阶段、布局不一致和循环阶段别名在链接时失败；此检查不限制效果事件循环或点燃连锁。

检查点仅新增 `CalculationStep.Shared` 类型，旧 record 字段不变。`shared_stages.json`、SharedStagesTest 与 SharedStagesGameTest 验证多层导入、分组、身份、取样、持久化、真实扣血和未知世界结果不重放。这个通用接口本身不代表 Solar 的全部增伤资格和取样时机已校准。


## Solar 对共享增伤阶段的接线

### 持有者系统来源与技能选择分离

加载 `solar.json` 只声明规则目录，不会自动绑定 `chorus_d2:solar_scaling`。宿主须在该持有者第一次产生 Solar 状态之前，以稳定身份安装一份常驻 SOURCE；它把逐目标 `solar_payload` 注入 `solar_outgoing`，随后才经过共享增伤阶段。这个基础来源不属于 Phoenix Dive、手雷、近战或武器的选择期效果，不能随任意一个槽清除。

```java
var configuration = PlayerActor.get(player);
configuration.configure(player, Optional.of(new ActorBinding("chorus_d2:solar_scaling")),
        configuration.revision());
```

此片段使用[持有者常驻配置](#持有者常驻配置)。原生玩家宿主改用[PlayerActor](#玩家常驻配置的保存与自动同步)保存及自动同步这个根包；首次选择角色默认根包仍由内容模块提供。正常领域检查点保留配置和受管来源，恢复后不重复创建；真实角色生命周期及正式玩家目录仍须由宿主协调。重复等价来源的 payload 贡献按同族 MAX，不相加。完整状态伤害快照会捕获当时有效的贡献；漏装后产生的零基数不能靠稍后补绑来追溯修正。

Phoenix Dive 定义已移除原来选择期 `effects.solar`，仅保留自己的回充来源。参考宿主在持有者层装配共享数值，Firebolt / Fusion / Solar Grenade / Snap 也不再依赖恰好选中了 Phoenix Dive。`SolarProducerAssemblyTest` 验证取消选择、领域恢复、重复系统来源以及混合手雷的首来源归因；同名世界测试在四个实际生产者取消选择后，用原生 Scorch 的正生命损失和确认死亡回执验收。只检查状态层数或出现一条伤害命令不足以证明管线接通。

### 共享进攻阶段

参考 `solar_test_calibration` 的 `chorus_d2:solar_outgoing` 现在依赖 `empowering_damage`，显式共享 weapon_stat / empowering / weapon_surge / perks，保留独立 payload、测试 bonus 和世界单位换算；所有显式组合该校准片段的目录必须同时提供公共 Profile。Incandescent 的 ProgramModule imports 已声明此依赖。单独的 `solar.json` 仍要求宿主提供完整的 Solar 数值校准。

四个 Solar damage_snapshot 出口都为实际受击者传入 radiant_champion；它来自已有 observed 观察，不从来源标签推测。共享修改不会更改初始来源的 weapon / grenade / melee / super 分支及 kill_tags，也不把独立 Incandescent 直接爆炸自动归入相同乘区。`solar_damage_perks` 是现有 Rampage 到公共 Profile 的验收适配，不是新建一套宽泛的“全部武器增伤”规则。参见 [规则依据及未完成范围](d2-ruleset.md#武器来源-solar-的共享增伤阶段)。

## Weapons 属性伤害的内容契约

`weapon_stat_damage` 提供常驻修饰器，目标为 `chorus_d2:empowering_damage / weapon_stat`，使用 `attribute(self, chorus_d2:weapons_stat, 0 stat_point)`；MAX 分组与统一 stacking_key 避免重复系统来源相乘。Solar 以完整相同的 Apply 布局共享该阶段，包含命名 factor `chorus_d2:weapons_stat`。添加模块本身不自动给所有伤害出口补齐上下文。

生产者必须明确 `chorus:weapon_damage` 信用，并在原攻击 Origin tags 保留恰好一种 `chorus_d2:primary_ammo`、`special_ammo`、`heavy_ammo`。资格通过 event_source_tag 读取攻击来源，不读取全局修饰器自身的 tags，也不从当前所持武器反查旧弹丸。装备来源投影和实际开火都需保留元数据；Incandescent 参考武器已接通这两条路径。只有 weapon 身份或混合 / 缺失弹药标签均不授予属性增伤。

每个真实伤害出口通过 `ImpactData` 传入 `weapons_stat_target: count`：0 未分类 / 冲突、1 普通 / 精英、2 Boss / Miniboss / Champion、3 实际玩家。分类由该次 inspect_entity 的 observed.player 与 entity/type tags 产生，玩家优先；两个 PvE 组必须互斥。该数字是 D2 内容约定，不是新增引擎枚举。四个 Solar damage_snapshot 出口与三种验收武器的物理接触均已接线；其他生产者必须显式提供，不能把缺少必需命中数据当作已知零值。

on_use 保留来源属性和弹药，impact 条件逐目标计算。重建带快照的 DamageCommand 时需保留 shield profile、reaction snapshot、proc policy 等全部捕获字段，不能用短构造器意外丢失它们。已存在的 DamageSnapshot.command(target, impact) 可以直接构造完整保存的攻击；本次没有改变执行器或检查点结构。数值、资料冲突与未完成项见 [Weapons 分段规则](d2-ruleset.md#weapons-属性的分段增伤与来源弹药)。

## 服务端举枪与收枪阶段

武器定义可选声明两段时间；以下 0.2 / 0.3 秒为合成示例，不是 D2 原型数据：

```json
"handling": {
  "stow": {"value": {"type": "chorus:constant", "value": 0.2, "unit": "second"}},
  "ready": {"value": {"type": "chorus:constant", "value": 0.3, "unit": "second"}}
}
```

每段可使用 `profile` 或有序 `profiles`，从 value 的单位开始，最终输出正秒数；例如将武器操控点数依次转换为秒数、乘动画系数、应用该原型的时间下限。配置内不允许显式零时长，未配置 handling 的武器则保持即时阶段。两把武器均未配置且没有待执行切换时，原来的即时 draw 行为不变。混用有、无配置的武器时，缺配置的阶段在同一逻辑边界完成，不人为增加一个 tick。

服务端从真实装备容器接受选择，消耗请求携带的装备 revision；客户端不提供时长、截止点、武器归属或阶段。请求接受立即取消该持有者的手动换弹，并在 handling 计划存在时拒绝 `weapon_fire` / `weapon_reload` 动作。收枪期间仍记录原武器为 drawn，但不能通过常规开火或手动换弹接口使用；收枪完成后，宿主先确认实体资格和完整物理装备投影，再清空物理持握，纯核心随回执提交同一装备变化和 `weapon_stowed`。其既有 Buff 收枪政策也在此时生效。

收枪事实处理后，READY 读取目标武器及当前修饰，接受自己的固定时长；它不会借用旧武器的操控来源。READY 期间 drawn 为空；完成时再提交真实持握和 `weapon_drawn`，随后可以开火。每段都冻结接受时的输入、计算轨迹与截止点；中途装卸修饰不改本段，下一段重新取样。逻辑时间按微秒向上取整，由服务器 tick 追赶各边界；这不是客户端动画帧计时。

同一目标的重复选择保留截止点。新目标取消旧计划，并从当前物理持握重新开始完整阶段；不累计旧动画的完成比例。收枪期间重新选择当前原武器会取消计划并立即恢复其可用性；空手举枪期间选择空手只取消，不再制造一次收枪。卸下、改装或移动计划涉及的目标 / 当前武器会取消；无关装备更新保留计划。死亡、离开维度等原有装备解除会清理计划，最终物理提交仍独立检查存活、旁观与同维度资格。旧 token 的 DUE / NEXT 不能恢复已取消的阶段。这些取消与取样规则是明确的 Chorus 政策，原作对应边界仍须校准。

新增 `weapon_handling_started` / `weapon_handling_finished` / `weapon_switch_finished` / `weapon_switch_cancelled` 事实，均以 `chorus:` 为前缀，保留阶段计划；DSL 可读 `weapon`、`item`、`switch`、`handling_phase`、`reason`、`duration` 和 `scheduled_duration`。`handling_phase` 为 stow 或 ready。开始与取消不是 `weapon_stowed` / `weapon_drawn` 的替代品；后两者只随真实持握变化发布。未知物理回执停止运行时且不重放，无法推断成功；若已提交持握后下游反应失败，物理状态和纯状态都保留完成结果。

EffectState.handling 以 holder 为键保存当前计划，与计时器及装备状态一起进入检查点。旧 effect_state 形状经显式迁移补空表；旧 record 字段不改写。正常停服的整体世界保存仍要求原有宿主保存证据，纯会话恢复不等同于崩溃恢复。ADS 阶段及其独立同步通道见下文；切枪动画 / 进度 HUD、专属切枪行动限制及全部异常打断政策仍有缺口。D2 两种参考切枪时间曲线的接线见下文。装备同步在接受请求和持握边界更新 revision / drawn。

## Weapons 属性对已接受换弹的时间贡献

组合 `character_stats`、实际属性来源、`weapon_stats` 与 `weapon_stat_reload`，再给角色绑定 `chorus_d2:weapon_stat_reload` 系统来源。武器已有的 `reload.profiles` 链保持“weapon_reload → 原型秒数曲线 → reload_animation”；角色属性贡献进入最后一个 Profile 的 `weapons_stat` MAX 阶段，factor 为 `chorus_d2:weapons_reload_duration`。它不贡献 weapon_reload 的点数，不修改弹匣或储备容量。

修饰器通过 `attribute(self, chorus_d2:weapons_stat, 0 stat_point)`查询当前角色来源，0–100 点每点转换为 −0.001 delta；重复系统提供者共用一个 stacking_key。角色已经由属性 Profile 汇总并限幅；此处再把用于时长的点数上限限定为 100。独立 animation 阶段继续 PRODUCT，两个命名因子可分别从保存的轨迹中剔除。

`WeaponReload.Plan`已经保存整条 CalculationPipeline.Result，无需新的定时器或状态字段。普通换弹在接受时求值一次；`insert.repeat.profiles`必须同样显式包含需要的时间管线，下一发只在前一发完成事实处理后求值，不能将最初的 Weapons 倍率缓存到整个重复装填循环。当前来源 / 护甲变化不修改正在等待的 Plan。没有经过这条时间管线的即时补弹或其他计时动作不会自动获得该倍率。

实际动作计时仍由宿主与现有换弹协议执行。操控查询、动画表现以及完整武器曲线不是该模块自动提供的能力；参见 [数值与验收边界](d2-ruleset.md#weapons-属性的换弹时长)。

## D2 操控点数与时间管线

参考内容显式组合 `weapon_stats`、`amplified`、`character_stats`、属性来源、`weapon_stat_handling` 和 `weapon_handling_curves`。其中 `handling_animation` 仍由 amplified 定义；没有激活 Buff 时该阶段自然为单位倍率。每角色绑定 `chorus_d2:weapon_stat_handling` 系统来源后，`weapons_handling_duration` 独立 MAX 阶段才读取角色属性。两个相同提供者不重复乘算该收益。

自动步枪的配置如下；基础 10 点只用于验收，正式原型必须提供正确的基础操控值：

```json
"handling": {
  "ready": {
    "value": {"type": "chorus:constant", "value": 10, "unit": "stat_point"},
    "profiles": ["chorus_d2:weapon_handling", "chorus_d2:auto_rifle_ready_time", "chorus_d2:handling_animation", "chorus_d2:weapons_handling_duration"]
  },
  "stow": {
    "value": {"type": "chorus:constant", "value": 10, "unit": "stat_point"},
    "profiles": ["chorus_d2:weapon_handling", "chorus_d2:auto_rifle_stow_time", "chorus_d2:handling_animation", "chorus_d2:weapons_handling_duration", "chorus_d2:auto_rifle_stow_floor"]
  }
}
```

霰弹枪改用 shotgun 对应三个 Profile。各 stow_floor 的下限来自自身曲线在 100 点的值；Clamp 上界为有限 double 最大值，不是另设的游戏时长上限。最终时长仍须满足引擎正数和计时范围校验。收枪下限是 D2 内容的可配置步骤，不写入通用 WeaponHandling；其他内容包可用自己的行为。

来源和事件同时保留该阶段武器的物品标签及 `chorus:weapon_ready / chorus:weapon_stow`，限定阶段的效果可用 event_source_tag，实例资格沿用 this_weapon。当前 Surplus 和 Amplified 均复用原定义；不把属性点数存入第二套 Buff，也不把整次切枪视为一个共同快照。保存的 CalculationPipeline.Result 包含点数、秒数曲线、两个命名倍率及收枪下限，恢复和诊断不查询已改变的来源。

`weapon_handling_test_calibration.json` 是把两个参考时间管线装到现有合成武器上的测试配置，不是独立可加载的规则目录；实际组合后的 WeaponDefinition 及完整目录进入严格 Codec 和检查点验收。曲线出处、下限组合推断和原作校准缺口见 [D2 操控规则](d2-ruleset.md#weaponsamplified-与-surplus-的实际操控时长)。

### 服务端开镜输入与阶段

武器原型可选 `aiming`，enter / exit 都是必填 Timing，支持单个 profile 或有序 profiles。下面的 0.2 / 0.1 秒为合成示例：

```json
"aiming": {
  "enter": {"value": {"type": "chorus:constant", "value": 0.2, "unit": "second"}},
  "exit": {"value": {"type": "chorus:constant", "value": 0.1, "unit": "second"}}
}
```

未配置时按下返回 NOT_CONFIGURED，空手返回 EMPTY_HANDS。与 handling 的正时长约束不同，开镜允许零秒并在同一逻辑边界完成；负数、非有限数、单位不匹配或计时溢出拒绝，不提交部分状态。输入为 `MinecraftEffectRuntime.aim(player, pressed)`，普通玩家命令 `/chorus weapon aim true|false` 只操作本人；武器、时长、token 和身份均由服务端确定。客户端另有可重绑定的按住瞄准键（默认 Z，可设为鼠标键）及服务端阶段回执；尚无 FOV、瞄准动画或 HUD。

| 状态 | held | ready | 待完成工作 |
| --- | --- | --- | --- |
| 无状态 | false | false | 无 |
| ENTERING | true | false | 到期确认当前玩家和持握武器，再进入 AIMED |
| AIMED | true | true | 保留按下起点及已完成期限，不留阶段计时器 |
| EXITING | false | false | 到期确认后清除状态 |

相同 pressed 值是无变化请求；反向输入取消旧阶段并重新取样完整新阶段，不按动画已完成比例折算。每条已接受边沿固定 input、秒数、Pipeline 轨迹和期限；期间来源移除、属性变化不重算，下一边沿再读当前值。事件来源包含武器物品标签与 `chorus:weapon_aim_enter / chorus:weapon_aim_exit`，可用 this_weapon 和 event_source_tag 限定贡献。

`chorus:weapon_aiming` 的 `target` 默认 self，`ready` 默认 false（查询 held），true 则要求 AIMED。该条件查询指定角色的状态；要限定当前贡献所属武器仍应组合 this_weapon / weapon_drawn 等现有条件。ON_USE 攻击快照捕获来源侧条件，目标侧条件仍在命中求值；离开开镜不改写已发射攻击。

| 事实 | 时机 |
| --- | --- |
| `chorus:weapon_aim_started` | 接受按下，状态已为 ENTERING |
| `chorus:weapon_aim_ready` | 到期且宿主、COMPLETE 门槛均通过，状态已为 AIMED |
| `chorus:weapon_aim_released` | 接受松开，状态已为 EXITING |
| `chorus:weapon_aim_ended` | 正常退出完成或强制取消，状态已清除 |

事实携带 weapon、item、aim token、aim_phase、reason，以及秒数 duration / elapsed 和 aim_held / aim_ready 标志。elapsed 从该事实对应阶段起点计，ready 保留按下起点；ended 的两个标志总为 false，即使所携带的历史 Plan 为 AIMED。取消不伪造 released 输入。计时事实只接受当前 Plan 和准确期限，旧 token / 已取消期限不能覆盖新输入。

`weapon_aim` 门槛检查按下 START 与进入完成 COMPLETE，松开不受该门槛阻止。纯规则核心和单次命令不隐含持续轮询；宿主需要立即取消时可调用显式 cancelAim。网络按住通道每个服务端 tick 额外查询 CONTINUE，拒绝时取消当前阶段但保留仍按住的意图，限制解除后接受完整的新阶段。Freeze 的既有全面角色输入禁止已增加该入口，Boss 例外沿用原规则。手动换弹或不同目标切枪被接受时立即清理；满弹匣等拒绝和同目标选择不清理。效果直接补弹不隐含取消。装备实例 / 配置 / 槽位 / drawn 变化取消，无关护甲更新保留；运行时还清理死亡、离开当前世界和旁观者的状态。

到期时通过 WeaponAim.Verify 查询实际玩家的同维度、存活、非旁观资格、当前 Plan 和物理容器。明确拒绝发布 ended；未知宿主结果停住会话，不重放。ready 状态提交之后的提示或反应失败保留已完成状态。运行时保存和恢复不重放阶段事实，恢复后的第一个 tick 即可核验已登记的在线容器。

[pre-aim-handling-v2.json](../common/src/test/resources/persistence/pre-aim-handling-v2.json) 是扩展类型前真实会话的原始检查点，包含旧枚举、两把武器、0.5 时长来源和正在执行的 STOW。迁移补空瞄准状态并保留 100 ms 的收枪 / 250 ms 的举枪期限；外层仍为 format 1，旧 wire shape 全部冻结。[来源与哈希](../common/src/test/resources/persistence/pre-aim-handling-v2.md)。下文的 Snapshot / Box Breathing 提供参考 ADS 曲线和连续瞄准消费者；正式内容装配、原作反转 / 取消窗口及物理世界整体重启仍待校准和验收。

### 客户端开镜意图与阶段回传

Fabric / NeoForge 注册独立的 `key.chorus.aim`，默认 Z。用户可重绑定到鼠标；当前不接管原版使用物品键，同一鼠标键还绑定原版操作时仍可能同时触发。完整 D2 操作映射与交互优先级留给客户端表现层。网络通道不需要装备界面保持打开，也不依赖客户端自行计算 perk 或开镜时长。

`chorus:weapon_aim_input` 只有 sequence、gesture、dimension 和 PRESS / HOLD / RELEASE / CANCEL。服务端从连接得到玩家，从实际装备得到武器和数值；每条连接先消费递增 sequence，再检查资格。PRESS 的 gesture 等于 sequence；其他边沿必须匹配当前按住身份。重复 PRESS 不替换身份、不重计时、不续租；旧 RELEASE / HOLD、重复包、其他维度的包都不能改变当前瞄准。

正常松开清除意图并接受 EXITING；GUI、失焦、死亡、旁观、玩家 / 世界变化、断线和超时强制清除。客户端直到再次松开再按下才重建取消后的操作，关闭 GUI 或重新获得焦点不会自动补按。每 10 个客户端 tick 发 HOLD，服务端超过或等于 40 tick 未获得有效 PRESS / HOLD 即超时；暂停服务器逻辑时不消耗租期。这是连接容错政策，不是 D2 时长或延迟补偿算法。

切枪 / 手动换弹立即结束旧 WeaponAim，但连接中的按住意图可以等待 BUSY 结束；完整动作完成后重新接受 ENTERING。Box Breathing 从这次新的 `weapon_aim_started` 起算全部等待时间。WEAPON_AIM / CONTINUE 拒绝也结束旧阶段；意图仍按住时继续尝试 START，直到资格恢复。空手且没有换枪阶段，或武器没有 aiming 定义，结束本次意图，后续 HOLD 不会把它复活。没有接受完成确认之前，客户端不能自行宣称 ready。

`chorus:weapon_aim_view` 回传单调 revision、已处理 sequence、gesture、本人 UUID / 维度、意图 held、状态、实际阶段、武器实例及服务端 startedAt / dueAt。held 表示连接仍请求瞄准，BUSY / RESTRICTED 时也可能为 true；**不能用它代替领域条件 `chorus:weapon_aiming`**。客户端提供只读 `WeaponAimClient.view()`，丢弃旧 revision 和其他身份 / 世界的投影；它不改变 FOV、攻击快照或规则状态。

意图与输入序号不进入检查点。运行时恢复可先得到已有领域 Plan，但网络管理器遇到运行时替换会撤销旧连接意图及其恢复后的 Plan；必须重新按下。正常停服在检查点固定前调用 quiesce，先完成网络开镜结束事实再存档；锁定后的断线没有操作可重放。单次命令与显式领域检查点的原期限恢复契约保持不变。未知世界结果消耗已处理序号、结束自动重试，并保留已提交的领域或世界结果。

### Box Breathing 的持续瞄准与下一发快照

`box_breathing` Bundle 挂载到武器 perk，强化来源带 `chorus:enhanced`。它从 `weapon_aim_started` 起算 1.5 / 1.4 秒，不等 `weapon_aim_ready`；开始和到期均要求自己的武器已持握、held 为真且弹匣大于零。到期产生按武器实例区分的永久 Buff，直到开火、松开、强制结束或来源卸下。永久不表示跨这些边界保留，只表示无需人为设置一个最长瞄准时限。

`fire_accepted` 的内置武器 on_fire 先运行，捕获本次伤害；对应 perk 规则随后移除 Buff，并在仍持握、held、有弹时重新接受完整等待。身体命中、打空和已接受但发射被明确拒绝的请求均消耗这次机会；射速 / 无弹 / 行动门槛导致的拒绝没有 fire_accepted，不重启等待。未知发射结果可能让流程停在消费规则之前，会话此时不可继续或自动重放；飞行期间的未知伤害结果不回滚已经提交的扣弹和消费。

扣弹先于 fire_accepted，所以最后一发在捕获攻击前弹匣已为零。实际触发成本的 `ammo_spent / ammo_changed` 事实现在额外带 **`chorus:weapon_fire_cost`**；这是该次事实的标签，来源、其他弹药操作和派生动作不继承。内容利用它把射击成本的清理交给 fire_accepted，避免丢失最后一发收益。其余弹药变化按事实 victim 与 this_weapon 匹配接收账户；降到零清理等待和 Buff，从零获得弹药且仍瞄准时重新计时。此连续有弹政策明确写入内容，尚未逐项在原作实测。

伤害 Buff 进入公共 `test:weapon_damage` 的 perks / weapon_perk 阶段，与 `chorus:precision` 命名因子分开。攻击必须是直接武器伤害，不含 explosive_perk_damage / glaive_melee；来源是否 held 和武器家族在发射时捕获，命中时必须提供 `ImpactData.weakspot: count`。1 表示真实目标弱点，0 表示身体或合成精确区域；缺失会报错，不根据 precision 标签猜测。弱点给 40%，`chorus_d2:glaive` 或同时有 sniper_rifle / heavy_ammo 标签的来源给 30%。原型必须提供准确家族和弹药标签。

`precision_delta` 是消费者的独立命中输入。弱点可以在其为零时仍取得 Box Breathing 收益；护盾可排除 `chorus:precision` 而保留该收益；合成精确区域可以有精确倍率，却传 weakspot=0。消费者的实际弹体接触读取目标相对几何区域后分别提供这两个输入。测试采用明确的 Minecraft 区域，未实现 Divinity 实体或全部 D2 目标模型。

另提供 `chorus_d2:aim_assist_falloff_distance` 的 1.1 倍距离贡献，只适用于已激活且仍 held 的对应武器。当前只验证数值 Profile，没有接入弹道吸附 / 准星。`box_breathing_weapon` 是完整合成消费目录，基础伤害 10、精确倍率 2、弹量、射速、ADS 过渡和几何均不是原作校准；规则数值与来源分歧见[规则集](d2-ruleset.md#box-breathing-的输入计时与弱点收益)。

### Snapshot Sights 与开镜时间管线

`snapshot_sights` 提供 `chorus_d2:ads_duration` 秒数 Profile 和普通 / 强化 Bundle；增强来源带 `chorus:enhanced`。Primary / Special 分别为 −0.5 / −0.2 DELTA。必须同时匹配来源持有者、本武器、`chorus:weapon_aim_enter` 及唯一的 primary_ammo / special_ammo / heavy_ammo 标签；缺失或多种弹药标签不会静默选择分支。增强 +5 Stability 单独贡献到 weapon_stability，不限于进入阶段。

`ads_duration` 的 ads 组为 PRODUCT；Snapshot 的 family 为 MAX，所以不同 ADS 家族可以相乘，同词条来自武器特性和内置特性时不会平方。稳定性 Profile 中同样为 `chorus_d2:snapshot_sights_stability` 配置 MAX，防止重复 +5。绑定到别的持有者或武器的来源不能借用资格。

重弹使用独立 `snapshot_sights_power`，要求来源参数 `ads_delta: delta`。`weapon_ads_weapon` 合成重弹原型要求装备参数 `snapshot_ads_delta`，边界为 −1 到 0，由 socket 映射给 Bundle；缺失参数在转移物理装备前拒绝。测试显式给 −0.2，另验证 −0.5 替换，两者均不代表重弹倍率已确认。正式内容应先获得校准证据再选择参数。

`weapon_ads_curves` 提供 17 个 stat_point → second Profile；消费者的 enter timing 依次使用 `weapon_handling`、对应 `<family>_ads_time`、`handling_animation` 和 `ads_duration`。Handling Profile 已限幅 0–100；曲线输入超界拒绝，避免跳过限幅。ADS 不使用 stow 最终下限，也不拼接角色 Weapons 的切枪时长 Profile。计时的完整 Result 随已接受 WeaponAimPlan 保存，后续 Buff / 护甲变化不会改写期限，新边沿读取当前来源。

`weapon_ads_weapon` 链接 Box Breathing 的实际弹体消费者，三种合成原型可选 Snapshot / Surplus。`weapon_ads_test_calibration` 仅保存三种测试 timing 映射，不是可直接加载的独立 Program。测试固定的退出 0.1 秒与基础 Handling 10、伤害 / 弹量 / 几何均非原作校准；更快达到 AIMED 不提前从输入开始的 Box Breathing 计时。该阶段无新 Java 执行器或检查点类型。曲线来源、特殊弹 ×0.75 / ×0.8 历史分歧及重弹疑问见[规则集](d2-ruleset.md#snapshot-sights-与-ads-时间曲线)。


### 已观测位置距离与实际武器衰减

`{"type":"chorus:position_distance","from":"muzzle","to":"impact"}` 返回 meter，from / to 是此前已绑定的位置结果名称。允许 POSITION、PROJECTILE_IMPACT、SHOT_IMPACT 和 PICKUP_CONTACT 的位置形状；目标集合或纯方向不接受。加载时验证绑定与形状，执行时验证两个观测实际存在并属于同一维度。缺失、跨维度或不能表示的无限距离明确失败；同一点为零，不以缺失替代零。

表达式只读不可变观测。发射时捕获的 eyes 位置和真实实体碰撞点可直接组合；不会追随后来移动或消失的持有者，也不改用目标中心。每次穿透碰撞单独求值，使用同一个原始 muzzle。它是三维直线距离，非飞行路径累计、跳弹各段之和或地图导航距离。需要其他定义时由内容显式提供相应观测。快照该表达式会固定已有结果；正常碰撞消费者则在 contact 续体中求值，再把数值传入 damage_snapshot 的 impact。

`weapon_range_weapon` 的实际开火先用 `calculate_pipeline` 将武器 Range 经 0–100 限幅、家族 start / end 曲线和开镜距离倍率生成两条不可变结果，并另存当前模式的最低伤害倍率。查询保留 actor / weapon 来源且 victim 明确为 event_weapon；这类武器查询不应借用清空来源上下文的角色 attribute / profile_value。结果随发射续体保存。输入副本的起止距离为 meter，最低倍率为 multiplier；角色位置、Range Buff 和瞄准状态之后改变都不重算。

碰撞续体对 `(distance − start) / (end − start)` 读取必需的 `chorus_d2:weapon_falloff_shape`，输出从完整伤害到最低伤害的损失进度；再按 `delta = −(1 − floor) × progress` 写入 `falloff_delta`。相同 ImpactData 还保留 distance / range_start / range_end / range_floor，数值贡献进入伤害 Profile 的 `chorus_d2:distance_falloff` 命名因子。具体 Profile 保留外部校准入口，提供者须保证进度和最低倍率在 0–1、end > start；当前两个参考曲线已在 Range 0–100 全域核对端点次序，非法单位 / 缺失 Profile 拒绝链接，除零拒绝执行。

`weapon_range_test_calibration` 显式选择 0–1 线性插值并在区间外保持端点；它是测试校准，没有把插值当作 Foundry 已独立测量的部分。消费者当前在 AIMED 为真时用原型 ADS 距离倍率，过渡中使用 hip 端点；此取样政策仍待原作校准。`weapon_range_curves` 保存两个普通家族的四条参考曲线，特殊原型必须另选正确声明，不能从默认家族推断金装例外。

存储只新增 `chorus:effect/data/value/position_distance`，字段 from / to 均为 String。旧 550 个 ID 和字段形状不变，两份历史检查点保持原始字节。既有位置、CalculationPipeline.Result 与 projectile continuation 承载数值和快照，无新实体持久化承诺。验收包括带位置 / 端点的发射对象编码和领域恢复，不代表跨完整物理世界重启恢复弹丸。[数值来源与缺口](d2-ruleset.md#range-属性与真实弹丸距离衰减)。


## 同次命中的直击与被动爆炸

`explosive_payload.json` 提供三个显式查询 Profile：`chorus_d2:payload_fraction`、`payload_radius`、`payload_blast_scale`，输入单位依次为 multiplier / meter / multiplier。消费者以 0 / 0 / 1 为输入，使用真实 event_weapon、BOUND 来源及 `chorus_d2:payload_query` 标签。匹配 owner 与 this_weapon 后查询到比例、半径和模式倍率；分量比例限制为 [0,0.99]，拒绝令补偿分母为零的输入。合法武器品质和家族由宿主声明，不能从武器显示名称猜测。

参考消费者在开火前捕获三个完整查询结果，再分别捕获直击和爆炸 DamageSnapshot。直击保留命名 precision 与 distance_falloff 因子；爆炸使用独立 Profile，不包含这两个因子，只接受外部 `chorus_d2:payload_radial_shape` 的径向结果。该必需 Profile 输入为距离/半径，输出契约为 [0,1] 的倍率；示例 provider 的线性曲线仅供验收，不可当作原作曲线。无 perk 的比例为零，仍按原直击结算，不发出零伤害爆炸命令或范围查询。

实体/方块接触各自开启显式 damage_group，直击、主目标爆炸、邻近目标爆炸加入同一组，随后关闭。消费策略声明 sharing=group 的一次性 Buff 因而能覆盖两个分量，下一次独立攻击重新读取资格。这个组不等于 damage_batch，也不会自动屏蔽同根事件或游戏预期循环。爆炸带 weapon_damage、explosive_perk_damage、passive_explosive_perk_damage、explosive_payload_damage 及明确元素标签；不继承 precision。上述标签仍需具体 proc 消费者主动声明，不能凭“爆炸”一词统一决定触发资格。

主目标从原 impact 读取身份，伤害后重新检查存活，再发一次满额爆炸；邻近列表排除主目标和持有者，查询 BODY 锚点、not_allied、nearest。直接击杀不会取消周围爆炸。攻击来源、两份快照和原碰撞点保持不变；未知物理回执遵循现有停机和不重放契约，不宣称两份宿主伤害能原子回滚。参考程序只覆盖 Kinetic 来源、指定合成武器与显式活动模式，不包含完整 D2 元素/武器/敌人目录。


## 平移已观测位置与延迟附着

新增普通动作 `{"type":"chorus:rebase_position","position":"impact","from":"anchor_start","to":"anchor_now"}`，返回可绑定的位置结果。它计算 position + (to − from)，不进行世界查询，不读取实体引用，也不处理旋转/缩放。三个输入必须是已绑定的位置形状，ProjectileFlight.Impact 也可提供位置。未绑定/错误形状、缺失位置、跨维度和非有限结果拒绝，不补零或猜测替代中心。需要允许缺失的内容必须先使用位置结果的 available/missing 条件显式分支。

Timed Payload 参考程序在命中时 capture_entity_anchor 保存目标 FEET 和生命引用，再以原碰撞点创建独立附着。detached after 续体通过 resolve_entity_attachment 取得平移或生命周期固定后的位置；它在内部采用同样的 rebase 计算。目标不可用分支明确 play_cue，并关闭附着和攻击组。生命周期策略见文末“独立实体附着实例”，旋转/缩放仍未实现。

`timed_payload.json` 提供 payload_delay 秒数查询，并复用前述 payload_fraction / payload_radius / payload_blast_scale Profile；链接时也需要这些 Profile 的提供者。参考组合通过 explosive_payload 片段提供公共查询定义，没有因此自动安装 Explosive Payload 词条。参数 payload_radius 必填；外部 payload_radial_shape 仍需提供。强化稳定性家族取高，独立于爆炸弹头强化装填家族。

存储仅新增 `chorus:effect/data/action/rebase_position`，字段为 position/from/to 三个字符串；原 551 个存储类型形状保持不变。结果复用 PositionResult.Stored，领域续体可编码并保持原截止时间和目标绑定。rebase_position 本身不保存 Minecraft 物理弹体；生命绑定由下一节的独立动作提供。


## 实体生命引用与受保护的快照伤害

三个通用动作组合出可保存的生命绑定，未改动旧 PositionQuery、DamageCommand 或 ProjectileFlight.Impact 的存储形状：

```json
[
  {"action":{"type":"chorus:capture_entity_anchor","target":{"binding":"victim"},"anchor":"feet"},"as":"initial"},
  {"if":{"type":"chorus:result_flag","binding":"initial","field":"available"},"then":[
    {"action":{"type":"chorus:resolve_entity_anchor","reference":"initial"},"as":"current"},
    {"if":{"type":"chorus:result_flag","binding":"current","field":"available"},"then":[
      {"type":"chorus:damage_entity_snapshot","reference":"initial","snapshot":"payload","target":{"binding":"victim"}}
    ]}
  ]}
]
```

示例要求外层已有 victim 目标和 payload 伤害快照；延迟、不可用分支和攻击组由内容显式添加。capture_entity_anchor 默认 target=victim、anchor=feet，可选 feet/body/eyes。其结果冻结引用、位置、anchor、alive；resolve_entity_anchor 只接受先前生命引用结果，沿用同一目标、生命、世界和 anchor。结果标志为 available/missing/replaced/wrong_dimension/alive；后三种失败不提供位置或生命观察。编译器拒绝把普通位置结果当生命引用，但生命观察可以供 rebase_position 与 position_distance 使用。引用不可用时继续 resolve 或 damage 明确报错，所以内容必须先分支。

damage_entity_snapshot 与 damage_snapshot 使用相同 snapshot/target/impact/pellet/batch/group 字段，另外要求 reference。目标必须匹配引用。它包装原 DamageCommand，宿主在实际提交前检查目标仍存在、同世界、同生命；不满足则返回无损失的 FAILED 回执。失败不产生伤害事实或 Buff 消费，正常与失败结果都经普通快照完成逻辑处理，包括攻击组、分量与弹丸进度。该操作只保护这一份写入，不保证观察、所有邻近伤害或整个攻击组原子执行。

Minecraft 26.3 使用 LivingEntity NBT 字符串 chorus:entity_life 保存随机 UUID。旧存档缺键时分配新值并在下次保存写入；已存在但类型错误或非法 UUID 的值拒绝加载，不静默换一条生命。原版玩家 restoreFrom(false) 是新生命，restoreFrom(true) 保留旧生命；普通实体保存/载入及原版跨维度 restoreFrom 保留生命。死亡但尚未移除的尸体仍可观测位置，保命后的实体不换生命。这不是所有模组自定义复活/复制机制的自动适配。

此阶段新增 EntityAnchor 的六个记录/枚举和 EntityAnchorActions 的三个动作存储 ID，总数从 552 增至 561，旧类型形状与历史检查点不变。生命引用和结果可随领域检查点恢复，但这不等于物理弹体已能跨世界重启。引用只在查询/写入时校验，不自动订阅移除事件或永久终止附着，同一生命回到原维度可再次使用旧引用。下一节提供通用移除事实及历史位置，内容仍需明确如何消费它们。


## 在 Buff 中保存实体生命观察

多阶段效果可声明 `components.entity_anchors`，把一次施放捕获的生命引用保存到后续独立事件。它与普通 positions、damage_snapshots 分开；数字、集合、引用、位置等组件之间不能重名。

```json
"components": {"entity_anchors": ["cast_life"]}
```

取得 `capture_entity_anchor` / `read_landing` 等有类型结果后，在已存在的 Buff 上写入，再于另一事件读取：

```json
{"type":"chorus:write_entity_anchor","buff":"example:pending","component":"cast_life","reference":"initial"}
```

后续事件中读取并比较（contact 来自 `read_landing`）：

```json
[
  {"action":{"type":"chorus:read_entity_anchor","buff":"example:pending","component":"cast_life"},"as":"initial"},
  {"if":{"type":"chorus:same_entity_life","left":"initial","right":"contact"},"then":[]}
]
```

读写的 target 默认 self，选择的是保存状态的 Buff 持有者；被引用实体由原始回执决定，不必等于持有者。写入和读取都是纯状态动作，完整保留原查询、结果、生命、世界、挂点、位置及当时 alive；已观察到的 missing / replaced / wrong_dimension 也原样保存。未写入时没有任何观察，读取会明确报错，不能用一个伪造的“世界目标不存在”结果替代。普通位置或伤害快照不能写入这种组件。

`same_entity_life` 要求两边均有观察且 target / life / dimension 全部相等；位置、feet/body/eyes 和当时 alive 不参与身份比较。两个不可用回执不会因为都为空就匹配。该条件无世界读取，参与快照时固定为当时比较结果。它本身不承诺当前仍存活：需要当前实体收益时，继续 `resolve_entity_anchor` 并检查 available / alive；可能跨世界写入边界的伤害继续使用受保护伤害动作。

组件遵循所属 Buff 的实例、刷新、移除与期限政策，其他组件更新不得丢失它。刷新保留旧组件，写入才替换观察；若新施放需要新的归属或实例，应由内容明确移除旧 Buff 再授予。保存并不自动开启完成监听、取消策略、重复事实记账或技能收益。示例验收组合将引用保留到真实落地，再消费 pending 状态并以 detached 续体解析原生命，历史范围中心仍取自落地事实。

存储新增 buff_schema_v2 / buff_components_v2 及读、写、比较三个独立类型，目录共 658 项；原有 653 项字段形状不变。精确旧六字段 schema / components 经迁移添加空声明 / 空观察集合，保留全部旧数字、集合、来源引用、成员、位置及伤害快照，旧类型 ID 下插入新字段仍拒绝。领域检查点覆盖保存前后及 detached 续体；这不等于物理世界整体恢复。

## 实体移除事实与历史挂点

服务器首次写入 LivingEntity 的原版 removalReason 标记时记录 chorus:entity_removed：先捕获同一生命和位置，字段写入成功后立即排队，后续 onRemoval 回调即使抛错或重新启用对象，也不抹去已经发生的转换。事实由 EntityLifecycle.Removed 承载，包含 target/life/dimension、kind、原版 reason、wasAlive 和全部 feet/body/eyes 历史位置。kind 为 destroyed/removed/unloaded/transferred；reason 保留 minecraft:killed、minecraft:discarded、minecraft:unloaded_to_chunk、minecraft:unloaded_with_player 或 minecraft:changed_dimension。destroyed 表示原生移除原因，不单独证明一次伤害击杀。

公共事件 victim 为该实体，actor 与 source.owner 为空，不补造攻击者。references 包含 entity_life/entity_dimension/removal_reason，flags.was_alive 表示开始移除前的存活状态；tags 包含 chorus:entity_removed 和 chorus:entity_removal_<kind>。同一事实的 EntityObservation 将实体明确记录为不可用，同时保留移除开始时的三个位置。可以用 read_event_position 读取位置，也可以保留生命身份：

```json
{"action":{"type":"chorus:read_entity_removal","anchor":"feet"},"as":"last_anchor"}
```

read_entity_removal 是纯本地动作，只接受保留了类型的实体移除事实，不查询当前实体。返回 EntityAnchor.Result，可用于 rebase_position、position_distance 或取回原生命引用。这里 available 只表示历史观察存在，alive 是移除前的 wasAlive，不能解释为实体现在还活着。普通 EffectEvent 即使手工拼出同名 references 也不符合该动作契约。detached 续体保留原始类型化 cause，检查点恢复和来源移除后仍可读取。emit 保留原始 payload，因此也保留移除事实的类型。普通 schedule 计时器保存公共 EffectEvent，不能假定其回调仍有原始类型；需要跨这种计时器使用生命挂点时，应先读取并显式保存结果。

原生回调只排队，不递归运行规则。世界动作内的移除事实随正常回执交给解释器，在父 frame 完成后反应；动作外的事实在 prepare/start/显式检查点前处理。反复调用已移除对象的 setRemoved 不重发或改写首次位置/原因；原生对象重新进入可用状态后发生的新移除是另一条转换。构造物仍有独立的 construct_ended 事实；通用事实在移除标记写入时记录，专属事实在其 onRemoval 中记录，队列保留这一顺序。两者不再生成 death/kill。未知世界结果保留已提交的移除证据并停止，未发出的奖励不重放；Failure.committedCombatFacts 这一既有字段现在也可包含已知原生生命周期事实。

正常关闭或停服保存后的清理不发布新的游戏奖励，关闭会丢弃尚未消费的运行时通知。没有安装对应世界运行时时，不建立回溯全体实体的移除日志。此机制只观察原生 setRemoved 边界；已死亡但仍在世界中的尸体尚未移除，仍使用现有伤害死亡事实。区块卸载原因接入的是原生 setRemoved 路径，专项区块保存/重新加载验收不能由直接调用入口替代。

该事实阶段新增三个存储 ID：entity_lifecycle/kind、entity_lifecycle/removed、entity_lifecycle_actions/read_removal；目录从 561 增至 564，旧类型形状不变。事实本身不会自动登记全部挂点；下文的显式附着实例会消费匹配生命的已提交事实，Timed Payload 已接入该实例。


## 确认死亡时的生命与历史位置

chorus:entity_life_died 的 EntityLifecycle.Died 记录来自现有伤害捕获中已经确认的原生死亡点；在 die 广播死亡表现成功后立即捕获，而不是等整个 hurt 返回或实体之后被移除。它保存 target/life/dimension、与 DamageReceipt 一致的 deathId，以及该时刻的实体观察和 feet/body/eyes 位置。之后的原生回调移动尸体、删除对象或用同 UUID 新对象替换它，不会改写这份事实。一个伤害调用内重复观察同一确认点只记录一次；新的独立死亡不按根事件或目标 UUID 全局抑制。

该生命周期事件不提供攻击者或新的击杀信用。原有 chorus:death / chorus:kill 仍由成功回执发布一次；references.death_id 将生命周期记录与回执关联。公共事件还包含 entity_life / entity_dimension、lethal=true 和 chorus:entity_life_died 标签。保命没有经过此确认点，因而没有该事实。移除记录与死亡记录各自保留真实时点，不能用后者替代实际卸载或移除通知。

```json
{"action":{"type":"chorus:read_entity_death","anchor":"feet"},"as":"death_anchor"}
```

read_entity_death 只接受类型化的 EntityLifecycle.Died，纯读取历史 EntityAnchor.Result。available 表示历史位置可用，alive 始终为 false；实体观察中的存在值是捕获时保留的原生对象视图，不保证当前实体索引仍能找到它。detached 续体保留 cause，来源卸除和领域检查点恢复后仍可读取；普通 schedule 的公共 EffectEvent 不保留类型，需先显式保存结果。

死亡回调只向原生事实队列写入。正常世界动作完成后与回执一起进入解释器，父 frame 完成后再运行规则；原生回调随后抛错时保留已经确认的生命/位置证据，但不继续执行奖励，也不自动重放。此入口继承 DamageCapture 的范围：只有受捕获的 hurt 调用中确认的死亡；不从 setHealth(0)、任意外部手工 die 调用或伪造的公共事件推断死亡。自定义同对象复活的生命旋转仍需独立适配。

该事实阶段新增 entity_lifecycle/died 和 entity_lifecycle_actions/read_death 两个存储 ID，总数从 564 增至 566，旧形状不变。两类事实现由下文的附着状态消费，公共通知仍遵守父 frame 完成后反应的顺序。

## 距离引信与显式校准

Explosive Head 参考消费者通过既有 `capture_position`、`position_distance`、`profile_value`、`capture_value`、`round` 和 `after` 组合距离引信，没有增加 Java 词条分支。`chorus_d2:explosive_head_delay` 必须由链接程序提供，输入 meter、输出 second；缺失或单位不符会在编译时拒绝。测试校准与效果定义分开，不能把测试曲线作为生产默认值。

开火保存的 muzzle 与接触 impact 供纯距离计算；校准查询在接触时执行，结果立即 capture，不随后续世界变化重算。无载荷的 `choose` 分支不执行查询。负结果走显式诊断；非负结果用十进制 arithmetic 乘一百万、向上 round、再乘百万分之一量化到微秒。零值直接执行爆炸动作体，正值才进入 detached after；不改变引擎 after 必须为正整数微秒的契约。攻击组与附着寿命设为引信加一秒，再在完成分支显式关闭，避免长引信先失去共享消费状态。

强化 +5 Accuracy 使用独立 `chorus_d2:weapon_accuracy`，stat_point 输入，perks 加值阶段按 accuracy 分组，Explosive Head 家族 MAX，最终限幅 0–100。它只提供属性查询；实际弓散布、拉弓与动画需要另行装配。距离定义、来源冲突和测试范围见 [D2 规则集](d2-ruleset.md#explosive-head-的分量与距离引信)。

## 真实落地事实与历史落点

`chorus:landed` 携带 `GroundContact.Landed`，记录生物实体通过原生向下移动从无支撑到有支撑的接触。actor / victim / owner 都是落地实体；`references` 包含 `landing_id`、`entity_life`、`entity_dimension`，`numbers.requested_y` / `moved_y` 为本次请求 / 实际竖直位移（米），`flags.ground_contact=true`。它没有武器或技能信用，不能单凭普通落地证明某次俯冲已经完成。

```json
{"action":{"type":"chorus:read_landing","anchor":"feet"},"as":"contact"}
```

`read_landing` 默认 feet，也可选 body / eyes；只读取真实 typed carrier，返回与 `capture_entity_anchor` 相同形状的历史生命引用和位置，不重新查世界。同名普通事件不能冒充该回执。后续范围查询可使用捕获的落点；需要操作当前实体时先 `resolve_entity_anchor` 并检查 available / alive，避免同 UUID 的新生命接收旧落地收益。detached 延迟和领域检查点可保留该事实及位置；具体技能负责关联待完成施放、同一生命、取消和超时。

Minecraft 宿主以碰撞盒当前无重叠、向下偏移 `1e-6` 方块后出现碰撞作为支撑观察；这个容差是宿主几何政策。要求移动开始前无支撑，结束后有支撑，允许请求刚好抵达地面而未被截短。`onGround` 标志与物理支撑分别保存：客户端声称着地、仅切换标志、瞬移至地面及站立后的重复向下移动都不会制造落地。乘客、旁观者、noPhysics 实体不参与，流体接触不自动等于实体碰撞支撑。

玩家包中的碰撞移动可能只是原版试算，只有原版接受最终位置后才发布；被驳回并回退的移动不产生事实。回调只排队，外部移动在运行时 prepare / tick 结算，规则世界动作内的移动在其回执边界合入，均不递归进入解释器。下游未知结果保留实际移动且不重放；新的真实离地再落地仍可再次触发，没有按根事件、链深或实体设置循环禁令。

当前通过普通碰撞、精确距离、玩家包接受 / 拒绝、历史位置、替换生命及未知结果的双端场景验收；这不是 Phoenix Dive 内容、客户端俯冲轨迹、流体落地或其他模组自定义移动的完整验收。存储目录新增两个 ID，旧形状不变。

## 独立实体附着实例

`begin_entity_attachment` 接受可用的生命挂点 `reference`、可用位置 `position`、可选正秒数 `lifetime` 和必填的五项 `policy`，返回独立 Handle。例如外层已经绑定 initial 和 impact 时：

```json
{"action":{"type":"chorus:begin_entity_attachment","reference":"initial","position":"impact","lifetime":{"type":"chorus:constant","value":1,"unit":"second"},"policy":{"death":"follow","destroyed":"fix","removed":"fix","unloaded":"end","transferred":"end"}},"as":"attachment"}
```

每次执行通过操作身份分配不同实例，保存原生命、参考锚点、接触偏移、开始/到期时间及策略；同一目标的多次攻击不会共用可变位置。未指定 lifetime 时保持到显式结束或生命周期结束；指定时在半开区间内有效，到期从状态删除。来源解绑不会隐式删除附着，内容应配套 detached 工作、明确结束或有限寿命。

死亡 policy 可选 follow（继续跟随同一生命的尸体）、fix（固定于确认死亡位置加原偏移）、end（永久结束）。destroyed/removed/unloaded/transferred 分别必填 wait/fix/end：wait 等待同一生命在原世界重新可用，fix 固定在对应原生移除位置，end 删除实例，之后同一生命回来也不恢复。FIXED 状态不再受后来的死亡/移除移动；它已脱离实体。WAITING 的历史位置只供保存，不自动视为当前可用位置。这里只做平移，没有骨骼旋转/缩放。

`{"action":{"type":"chorus:resolve_entity_attachment","attachment":"attachment"},"as":"point"}` 返回位置及 resolved/fixed/waiting/missing/replaced/wrong_dimension/closed/expired 标志。available 表示可用爆炸位置（resolved 或 fixed）；target_available/alive 只反映本次成功的当前实体观察。fixed 提供历史位置，但 target_available/alive 都为 false。需要生命引用时用 `attachment_entity(resolution="point")` 取得 EntityAnchor.Result，再检查其 available；不能把普通位置直接当作生命引用。`end_entity_attachment(attachment="attachment")` 幂等删除实例。

FOLLOWING/WAITING 解析发出携带当前 Entry/revision 的世界查询。查询期间若已确认的生命周期改变实例，完成阶段优先采用新状态，旧回执不能恢复已结束/固定/等待的实例；若新状态仍需查询，本次返回没有位置的 missing/waiting，内容可以显式安排下一次解析。遇到替换生命时删除实例并返回 replaced；无匹配历史事实的 missing/wrong_dimension 不臆造脱离点。一次解析不是对后续整个爆炸的锁，伤害仍使用独立写入校验。

已确认生命周期通过回执的 EntityAttachments.Commit 在依赖动作继续前更新状态；公共事实仍排队到父 frame 后反应。转发或重新 emit 同名公共通知不会再次应用这些状态写入，避免旧通知终止刚创建的新实例。动作外待处理的原生事实先按捕获时间分组结算（不回退已前进的逻辑时间），然后才推进到新的输入或引信截止时间；这些外部事实与新输入各有自己的 Start 边界。结算故障保留当前及尚未处理的已知事实，不自动重放奖励。没有加入根事件、链深度或循环次数上限。

附着阶段的 effect_state_v4 包含 attachments；旧 v3 对应精确旧记录并迁移为空附着集合，v1/v2 迁移继续保留。当前蓄力阶段采用下文的 effect_state_v5，继续保存所有附着。Handle、Entry、策略、查询、回执、Commit 与四个动作有独立存储 ID。领域检查点保存偏移、策略、阶段、期限和续体；宿主实体 NBT 重载保留同生命可供 WAIT 恢复。实际区块卸载/加载协调、同对象自定义复活、旋转/缩放和物理世界整体重启仍需独立实现及验收。

## 武器蓄力与配对松开

武器原型可选 `charging`；省略仍为原来的即时开火。启用时必须同时声明 fire。下面的 0.1 / 0.4 秒是合成示例，不是 D2 弓参数：

```json
"charging": {
  "trigger": "release",
  "minimum": {"value":{"type":"chorus:constant","value":0.1,"unit":"second"}},
  "full": {"value":{"type":"chorus:constant","value":0.4,"unit":"second"},"profile":"test:charge_time"}
}
```

minimum / full 使用与换弹相同的 Timing 结构，支持显式有序 profiles 管线。最终必须为秒，minimum 可为零，full 必须为正；向上量化至完整微秒后 minimum 不得大于 full。可选 `if` 只检查开始蓄力的资格，默认允许；它与发射时检查的 fire.if 分开，因此“仅满蓄力才可射出”不需要阻止开始蓄力。最低时间、满蓄力时间及计算轨迹都在接受每轮蓄力时固定；装备外的 Buff 变化只影响下一轮。

`trigger` 可选 `release`（默认）或 `full`，其他值拒绝加载。release 在满蓄力时转 READY，等待配对松开；full 在满蓄力边界自动尝试一次射击，提前松开即取消，即使已经超过 minimum 也不发出部分蓄力射击。full 仍要求合法 minimum/full，minimum 作为测量与显示数据，不提供提前发射资格。默认每次接受的按下最多消费一次射击事务；可选 `repeat=true` 仅允许与 full 同用，在射击恢复结束后尝试下一轮，详见下文。这不是对发射后派生效果或 Char 连锁的限制。

`WeaponCharge.Request(holder, token, edge, holderReference)` 的 edge 为 PRESS / RELEASE / CANCEL。token 由可信宿主为每次新手势生成唯一值，不是客户端指定的武器身份。PRESS 不扣弹或预留弹药；重复 PRESS 不重置现有状态。release 模式的匹配 RELEASE 先检查 minimum，再用当前 fire.if、成本、弹药和射击间隔执行原有射击事务；full 在固定满蓄力边界使用同一事务及当时的成本/条件。过早松开、显式取消或已知拒绝都结束本次蓄力；不匹配 token / 生命引用的松开返回 stale，保留当前有效手势。启用 charging 的武器直接走普通 fire 入口返回 charge_required。

按下使用 WEAPON_FIRE / START，持续资格及满蓄力边界使用 CONTINUE，实际射击使用 COMPLETE。Fire 受限回执保留实际 START / COMPLETE 阶段，不把完成拒绝伪装为开始拒绝；其他既有行动回执仍要求 START。满蓄力边界首先检查宿主及持续资格；release 转 READY，full 执行当前射击检查并在同一领域提交中清理计划/计时器和提交接受的扣弹。后者不留下可恢复的活动 READY 计划。ready、ended、原有开火事实按此顺序在提交后反应；ready 反应不能撤销已接受的付款，应使用 fire.if / COMPLETE gate 表达发射前限制。真实持有者死亡/失效、旁观、装备实例/参数或持握槽改变、已接受的切枪或手动换弹都会取消；被拒绝的满弹匣换弹不取消。瞬时补弹保留独立语义，不被当成手动换弹输入。

事实为 `weapon_charge_started / weapon_charge_ready / weapon_charge_ended`，数值提供 charge_elapsed、charge_minimum、charge_duration、charge_overdraw（秒）及 charge_fraction（0–1 multiplier），标志为 charged、charge_held、fully_charged，引用含 weapon、item、charge 和 charge_trigger（release/full），结束事实另有 reason。on_fire 同样收到本次发射的这些数值/标志，能在捕获伤害、速度或其他参数前求值；full 发射时 charge_held=true，release 发射时为 false。ended 的 charge_held 恒为 false，表示本轮蓄力已经消费，不推断玩家物理按钮已松开；重复模式此时可以已经进入恢复阶段。原有即时武器不会凭空拥有 charge 数值。开始资格/时间查询时 elapsed、fraction、overdraw 为零，尚未采样的 minimum / duration 不伪造为零。完美窗口由内容比较 fully_charged 和 overdraw 表达，没有核心写死的窗口或增伤。

领域状态与网络按键意图分离。宿主 API 和普通玩家自助命令 `chorus weapon charge press|release|cancel` 都使用同一领域协议；客户端按键包、保持租期、连接级清理和基础进度显示见下节。Minecraft API 总是绑定 holder 的实体 UUID、持久化生命和维度；原生复生即使复用 UUID 并转移相同装备，也不能继承旧蓄力。纯宿主可以显式不提供生命引用，但不能以无引用请求松开已绑定引用的计划；Minecraft 满蓄力验收要求匹配真实生命。取消当前物理输入不会删除已经发射的投射物或其延迟效果。

EffectState v5 包含 charging，精确旧 v4 迁移为空蓄力并保留全部附着；WeaponDefinition v4 包含可选 charging，旧 v3 保留原武器行为。Fire Outcome v2 增加 charge_required，旧枚举 ID 仍只接受原来的七个名称。单次 full 阶段新增 charging_v2、plan_v2 和 trigger；当前持续充能另使用 charging_v3、plan_v3 和 phase_v2，该阶段目录为 603 项，原 600 项逐键形状不变；当前 hitscan 新增六个独立类型后为 609 项。旧三字段 Charging 和十一字段 Plan 使用精确旧记录迁移为 release，不接受把新字段塞进旧 ID；外层 v5 状态/v4 武器不再升版。领域检查点保留触发模式、固定期限、轨迹及绑定生命；release 可恢复 CHARGING/READY，单次 full 仅有活动 CHARGING；重复 full 另可处于 RECOVERING。旧四字段 Charging / 十二字段 Plan 默认迁移为 repeat=false、cycle=0、无恢复凭据，旧 Phase ID 仍只接受 CHARGING/READY。未知世界结果仍停机，不重放已消费的计划、付款、弹体或伤害。完整物理世界重启、即时武器长按连发、最大持弓/强制松弦和完整原作蓄力武器行为仍有后续工作。

Minecraft 的 full 计划还需要本运行时内临时的输入许可：同一个玩家对象/连接、计划 token、当前生命和真实装备，以及输入生产者提供的有效保持判定。领域恢复不恢复这份许可；即使第一次 prepare 先推进到已过期的满蓄力计时器，也必须先拒绝自动发射，再清理计划。网络保持额外检查当前世界时钟下的租期，不用旧计时器的历史时间复活已经过期的输入；因此卡顿追赶时，当前已失去租期的计划不会补射。可信自助命令使用同生命/连接的运行时许可，仍受死亡、装备、断线和停服清理约束。纯核心宿主可以独立实现 WeaponCharge.Verify，其肯定回执代表明确授权，不能把存档存在直接当作物理长按。

内容可将 `charging.full.value` 声明为 `stat_point`，通过 `profiles` 依次执行属性到秒的曲线与秒到秒的 perk 修饰；最终输出仍必须为秒。例如参考消费者使用 `lightweight_bow_draw_time → weapon_draw_time`。最低松开时间保持单独声明，不能因为它也属于蓄力就隐式套用满弦系数。Tempo/Warm-Up 用普通 Buff 和 `grant_buff / extend_buff` 表达刷新与剩余时间上限，不需要在充能核心中识别 perk 名字；融合用 `weapon_charge_time`，避免误用只针对拉弓的 Tempo。[内容与校准边界](d2-ruleset.md#普通弓拉弓曲线与-tempo--warm-up)。

## 蓄力按键网络与进度显示

Fabric / NeoForge 注册 `key.chorus.charge`，默认 R，支持玩家在控制设置中改绑键盘或鼠标。它操作自定义武器容器中当前持握的 charging 武器；不自动替换原版攻击/使用按键。普通即时武器的现有 fire 入口保持原行为。按下开始，release 配对松开发射，full 保持至蓄满自动发射一次；GUI/失焦/死亡/旁观/玩家或维度改变发送 CANCEL 并清除显示。取消或单次自动发射结束后必须先松开旧按键，再重新按下；repeat=true 的完整循环由领域计时器推进，客户端持续发送同一次手势的 HOLD。

`weapon_charge_input` 只携带递增 sequence、按下时的 gesture、dimension 和 PRESS/HOLD/RELEASE/CANCEL。PRESS 的 sequence 必须等于 gesture，其他边沿沿用原 gesture；不接受客户端传入武器、玩家、时长、伤害或领域 token。服务器为每次新手势产生独立 token，从真实玩家生命/装备和服务器时钟接受蓄力。连接内旧 sequence 即使之前失败也视为重复；额外 PRESS 不替换仍有效的手势，错维度/错配松开不改写它。

客户端每 10 tick 发送一次 HOLD，服务器从最后接受的按下/保持起算 40 tick 租期；在到期边界先取消，再判断后到的 HOLD/RELEASE。HOLD 只延长仍存活的同一手势许可，不调用 PRESS；重复模式的轮次由领域计时器推进。领域计划被换弹、切枪、限制或资格变化取消后，持续物理按键不会自动重新进入；重新按下才可重试。松开在调用领域事务前先退役网络手势，已知拒绝和未知结果都不自动重发。

`weapon_charge_view` 包含确认 sequence、gesture、递增 revision、玩家 UUID/原生 entity ID、维度、held、status、OFF/CHARGING/READY/RECOVERING、weapon 和服务器微秒 startedAt/minimumAt/fullAt/observedAt。客户端只接受当前连接、当前玩家实体/维度、当前已发送序号和更新版本的投影；held 投影还必须匹配本地手势及按下时的玩家对象。原版复生会复用 UUID 和 entity ID，不能把 entity ID 单独当成生命身份。本地已经松开/取消的手势不接收迟到的 held 投影。显示由服务器 observedAt 算进度，不拿客户端帧时间推断 READY、完美窗口或发射资格。基础条在准星下方显示进度、最短松开标记与满蓄力文字；RECOVERING 显示已接受射击至 readyAt 的恢复进度和独立标签，不显示最短松开标记，遵守隐藏 HUD/GUI/失焦；原作 HUD 美术、动画和完美窗口提示仍属独立内容工作。

断线取消该连接的网络手势及受跟踪的命令蓄力；取消先按 token 撤销临时许可，再推进领域时间，过期清理不会变成自动发射。旧 token 不撤销更新计划的许可。运行时替换取消匹配的恢复计划，不继承旧物理手势，也不占用另一宿主创建的无关计划。正常停服在固定检查点及其他输入清理之前，先撤销全部运行时/持有者的蓄力许可，再取消领域计划和网络手势，避免清理第一个持有者时推进并发射另一个人的计划；故障运行时保留未知结果，不能为清理而重放动作。显式领域检查点仍可以保存和恢复蓄力，恢复后新物理输入需要独立配对；这不代表物理世界整体重启已验收。

自动完成用同 token/持有者的已接受 Shot 判断 FIRED；提前松开 full 返回 CANCELLED。已消费手势的 HOLD/RELEASE 为 stale，不另扣弹或发射。网络请求先结算当前仍有效的到期计划，再处理新的游戏输入，因此已到截止点的有效发射不会被随后到达的松开追溯取消；断线/停服使用上述先撤销许可的生命周期路径。未知发射反应返回 FAILED，保留付款和故障记录，不从持续按键或迟到包重试。

## 分时弹丸齐射的组合定义

[自适应融合参考消费者](../common/src/test/resources/effects/fusion_burst_weapon.json) 证明现有 `charging.trigger=full`、`begin_shot`、detached `after`、生命锚点、伤害快照和 projectile 可组合为分时齐射。接受一次射击先扣一次弹，再声明同一个七成员攻击组；首枚立即发射，其余六枚以相对于同次 on_fire 的绝对偏移排队。每枚使用唯一 pellet 索引，实际伤害回执才更新组进度。内容必须在 fire.tags 声明 `chorus:weapon_shot`，分组操作本身不替内容推断武器类别。

`after` 保持精确整数微秒契约；不能直接给它不可精确转换的 1/30 秒 double。参考 JSON 显式保存各个 n/30 的向上取整偏移：33334、66667、100000、133334、166667、200000 微秒。它们不是累加已舍入的单个间隔，也不修改通用引擎的量化规则。满蓄力时长、内部弹丸间隔和恢复间隔是独立内容输入，充能 perk 只通过配置的满蓄力 Profile 消费。

该定义在发射时捕获持有者生命引用，每枚到期时 resolve 同一生命，读取当前位置/方向并捕获当时的 on-use 伤害。以实例武器 .201 秒 Buff 授权 .2 秒内尚未发出的成员，on_stow=remove，并在来源卸下或生命检查失败时移除。已经发出的 projectile 不依赖这一门闩继续存在。固定 .65 秒发射间隔保证本定义没有重叠的已接受齐射；若内容允许同武器任意重叠，必须先定义独立齐射身份及取消政策，不能共享可被新齐射重授的同一门闩。

被跳过的成员直到组期限仍为未决；已知发射拒绝正常关闭该成员，二者都不会满足全部命中。分组期限和弹体寿命是不同输入。Controlled Burst 在 `previous_pellets_hit < pellets_total && pellets_hit == pellets_total` 的首次跨越触发，允许跨目标，不需要等待每枚穿透弹结束；另一条击杀规则独立刷新。单次成员重复接触不重复跨越，但另一项合格击杀仍可刷新。组回执是否把免疫/格挡计作命中、收枪后迟到事件是否有资格，由当前回执协议与对应 perk 门槛明确决定。

纯会话检查点固定完整目录，保留已排队的偏移、成员、归因和已付款状态；恢复依赖宿主提供匹配的世界与生命观察。不能把这种领域恢复当成活跃弹体跨进程恢复证明。参考内容不增加核心原语、存储类型或全局防循环条件；原作数值与剩余装配见 [D2 规则集](d2-ruleset.md#controlled-burst-与自适应融合参考消费者)。


## 持续充能与射击恢复

`charging.repeat` 默认为 false；设为 true 时必须同时使用 `trigger=full`，release+repeat 拒绝加载。一次可信 PRESS 保存一个手势 token，每轮依次经过 CHARGING → 自动射击 → RECOVERING → 下一轮 CHARGING。恢复期限直接使用本次已接受 WeaponFire.Shot.readyAt；它可以包含整段齐射和射后间隔，不从弹丸碰撞完成时间推测。fire.interval 和 full 时长均须为正，循环通过正常时间推进，不附加 root/代数上限。

每轮成功射击在同一提交中清理本轮满蓄力计时器、扣一次弹并保存 RECOVERING 计划及其下一截止点，然后依次发布 ready、ended、recovery_started 和原有开火事实。fire 的实际拒绝结束整个手势，不自动补弹或重复尝试。处于恢复的手势仍占用 charging 状态，所以瞬时收枪再重持、已接受的手动换弹、切枪、生命改变等现有取消路径可以立即退役它，不依赖下一次网络轮询；已经飞出的成员和独立延迟效果继续遵守各自寿命。

恢复截止点先通过同一 WeaponCharge.Verify 宿主核验及 CONTINUE 门槛，再执行新的 START / charging.if 检查，采样下一轮 minimum/full 与计算轨迹。已接受的本轮期限和射后间隔不会被中途 Buff 变化改写；下一轮读取当前 perk。恢复或下一次射击的已知拒绝结束整个手势，未知世界结果保留已付款和待诊断状态并停止，不重放。成本仍只在实际发射时检查，因此弹尽后的下一轮可能完成蓄力再被 NO_AMMUNITION 拒绝；当前协议不自动换弹，也不在重新获得弹药后自行恢复旧长按。

Plan 保存 repeat、从零开始的 cycle 和恢复阶段对应的已接受 Shot。输入匹配仍使用原 token；repeat 的每次射击使用 `charge-cycle/<token长度>:<token>/<cycle>` 独立 token，保证武器攻击归因不会把所有轮次合并成一发。每轮 started 的 reason 为 input 或 repeat，事件增加 `charge_cycle` count 和 `charge_repeating / charge_recovering` 标志。`weapon_charge_recovery_started / weapon_charge_recovery_ended` 分别标记恢复阶段；恢复取消不会重复发同一轮的 weapon_charge_ended。恢复事件的蓄力数值仍指向刚结束的一轮，阶段期限从 Plan.dueAt/恢复 Shot 读取。

网络只续租整个原手势；PRESS 不重置活动循环，HOLD 不创建下一轮。恢复期间 View.held=true、phase=RECOVERING，显示时间为 Shot.firedAt → Shot.readyAt，minimumAt=startedAt。自动模式的配对 RELEASE/CANCEL 在推进到期工作之前撤销输入许可并结束整个手势；自助命令及宿主 charge API 使用同样的前置取消边界。GUI、失焦、断线、停服和运行时替换同样退役；取消后即使物理按键仍按住，也需要先松开才可发起新手势。两种自动阶段都检查当前世界时钟的租期，追赶旧计时器不能恢复已过期的输入。

持久化保存领域循环阶段和已接受的恢复期限，但不保存物理长按许可。当前嵌套版本独立迁移，外层 EffectState v5 / WeaponDefinition v4 不变。Minecraft 恢复后的首次推进缺少许可时结束 CHARGING 或 RECOVERING，不补射；纯核心宿主若明确重新授权，则可按检查点中的逻辑时序继续。原作具体 cadence、自动换弹/连发选项、即时武器长按、强制松弦和完整物理世界重启仍是独立实现与校准范围。


## 服务端瞬时射线 hitscan

`hitscan` 是同步世界观察步骤，在同一动作序列内取得回执并执行 `do`，没有飞行实体或飞行时间。它可用于武器 `on_fire`、技能和普通规则；原有开火成本与间隔先提交，再观察射线。射线碰到实体并不自动造成伤害，规则继续使用 `damage_snapshot` 或其他动作处理回执。

```json
{
  "hitscan": {
    "position": "muzzle",
    "direction": "aim",
    "range": {"type":"chorus:constant","value":40,"unit":"meter"},
    "inflation": {"type":"chorus:constant","value":0,"unit":"meter"}
  },
  "as": "contact",
  "shot": {"binding":"shot","pellet":{"type":"chorus:constant","value":0,"unit":"count"}},
  "do": [{
    "for_each":"contact", "as":"victim",
    "do":[{"type":"chorus:damage_snapshot","snapshot":"damage","target":{"binding":"victim"},"pellet":"contact"}]
  }]
}
```

`muzzle` / `aim` 必须是此前捕获的位置 / 方向；`damage` 是攻击快照；可选 `shot` 来自 `begin_shot`。不属于整枪统计的射线省略外层 `shot` 和伤害动作的 `pellet`。`emitter` 默认 source_owner，与 owner 都排除出实体候选。射程为大于零、最多 256 米；碰撞箱扩张为 0–4 米，默认 0。这是每次世界查询的成本与几何边界，不是原作统一武器射程、辅助瞄准或事件连锁限制。动态值在发出查询前同样校验。

宿主沿闭线段读取已加载方块的 COLLIDER 形状，流体不遮挡；再取可用路段内最近的存活、非旁观 LivingEntity 碰撞箱交点。方块/未知边界与实体同距离时优先边界，两个实体同距离按 UUID 字符串排序。起点在目标内部时可在起点命中；射程末端恰好接触实体也算接触。阵营不自动过滤。目标 AABB 的可选扩张不扩张方块，不把接触点裁剪回原始碰撞箱。

射线不加载区块。未知地形前已观察到的实体/墙仍有效；否则回执停在首次未知边界。与飞行弹体不同，瞬时查询不要求沿途实体 tick 许可，因为没有需要继续模拟的飞行实体。可选直线穿透见下节；客户端历史回溯、自动散布、反弹、视觉曳光和原作武器校准仍待完成。

`as` 的绑定只在 `do` 内有效，可作为目标集合和位置中心。集合最多一个实体；位置/方向缺失或维度不符没有位置，不能以世界原点补齐。结果旗标为 `entity / block / range / unloaded / missing_position / missing_direction / wrong_dimension / expired_shot`，另有 `available`（存在几何回执）和 `geometry_observed`（有实体碰撞箱）。`available` 包含 `unloaded` 边界，不能当成命中成功。可读取 `count`、观察距离 `distance`、法线、相对 XYZ、原始宽高深与 collision_inflation；几何字段必须在相应可用分支读取。相对坐标可能落在 0–1 之外，供具体弱点策略决定资格。

显式成员在查询前预留，匹配回执后打开接触，`do` 结束后关闭；实际伤害回执才更新 hits/effective 与 shot_progress，全部成员完成后只发布一次 shot_resolved。缺失捕获是 rejected，范围耗尽/墙/未知边界是无实体命中；免疫/格挡仍按既有 hit 分类，取消/失败不计。重复成员在再次观察前失败。后续 after 等独立动作保留几何和来源，但不能重新使用已经关闭的成员来记账。未知世界结果保留已提交付款/命中，不重放射线或伤害。

独立数据样例见 [hitscan.json](../common/src/test/resources/effects/hitscan.json)。存储增加六个独立类型，目录 609 项，此前 603 项形状不变；未改写已有 Impact / ResultShape 枚举 ID。内部复用接触聚合适配器，外部将射程耗尽显式表示为 range，不混同飞行到期。


### 瞬时射线的直线穿透

在 hitscan 步骤外层添加可选 `"pierces": {"type":"chorus:constant","value":1,"unit":"count"}`，表示第一次实体接触后还能穿透一个**不同实体**。省略保持原单接触步骤；显式 0 具有同样的命中上限。值可以来自数值查询，必须为 0 至 Integer.MAX_VALUE 的整数 count；错误单位、负数或非整数在观察前拒绝。它是该条射线的内容参数，不限制整个事件链。方块和未知地形不穿透。

穿透路径在一次同步世界观察中按接触距离、同距离 UUID 顺序保存各目标及碰撞箱，排除 owner/emitter。达到 `1 + pierces` 个实体后，以最后实体为终点；实体数量不足时追加墙、射程耗尽或未知边界终点。一个实体只入选一次，没有因第一发伤害杀死目标而重新选择名单，也不会在前一目标引发动作后吸收新走进射线的实体。每个目标的实际存在/存活、护盾、防御与伤害回执仍在逐次写入时判断。**冻结几何、逐次提交伤害**是明确的 Chorus 瞬时射击政策，尚未逐项验证为原作所有武器的内部顺序。

`do` 对路径中的每次接触按顺序运行，最后的非实体终点也运行一次，且对应目标集合为空。`sequence` 从 1 递增，`entity_contacts` 是截至当前接触的不同实体数；`target_contacts` 对实体为 1，终点为 0。新增 `terminal` 与 `pierced` 旗标用于区分最后接触和继续穿透的实体接触；相对坐标、距离及其他旗标继续来自各自冻结回执。

显式 shot/pellet 在整条路径中只有一个成员：每次接触关闭后允许下次序号重新打开，最后接触才将成员关闭为完成。实际命中统计按“每目标唯一弹丸”累积；同一颗子弹穿过两个敌人意味着一个命中弹丸、两个目标，不是两颗弹丸。无效/免疫/取消等仍按原 DamageReceipt 分类；中途未知结果保留已经提交的损失和付款，后续已捕获接触不执行，查询和伤害都不重放。

路径回执、序号及延迟捕获可编码到领域检查点。独立 after 保留接触几何和序号，不保留已关闭的成员记账资格。存储另增五个类型，目录由 609 增至 614；原单接触步骤、Query、Result 及其所有旧字段/枚举保持精确形状，不以添加默认字段静默改写旧存储。

当前直线穿透可表达固定 Compendium `Weapon Perks!C20` 的“额外穿透一次”需求，射程/分层护盾与参考武器装配现见下节；正式目录和原作校准仍是完整词条验收条件。该能力不等于反弹、穿墙、穿透 Banner Shield 或反勇士护盾。


### 弹药 perk 的射程、穿透与护盾组合

[weapon_ballistics.json](../common/src/test/resources/effects/weapon_ballistics.json) 提供两个内容 Profile，不增加引擎原语。`chorus_d2:weapon_piercing_grant` 以零 count 为输入，在 `perks/piercing` 组对授予值取 MAX；消费者将结果与自身固有额外穿透数取 MAX，再传给 hitscan 外层 `pierces`。这是“最低授予量”的参考内容政策，不能直接把非零固有值作为此 Profile 的输入，否则 ADD 阶段会变成额外相加。固有穿透与具体 D2 弹药的叠加仍须单独校准。

`chorus_d2:weapon_shield_damage` 以 1 multiplier 为基准，在 `ammunition/ammunition` 阶段消费 delta 倍率。Armor-Piercing Rounds 只对本持有者、本武器、明确 `chorus:weapon_direct` 且不是爆炸 perk 的伤害提供 0.05 delta；只有当前处理的元素战员护盾或勇士屏障层合格。攻击建立时把该 Profile 传给 `capture_damage.shield_scaling_profile`，不把它放进普通生命伤害的 scaling_profile。捕获固定攻击方来源，而 layer_tag 延后到每层结算；倍率消耗该层的原始伤害预算，剩余预算继续进入后续盾层/原版防御。其它盾伤 perk 需要自己的已校准阶段/叠加规则，本文件没有替它们指定组合结果。

[参考武器](../common/src/test/resources/effects/armor_piercing_weapon.json) 的独立 magazine socket 选择 none/normal/enhanced。普通/强化 +5/+7 先进入已有 weapon_range，再算两个距离端点；每目标仍从最初捕获的枪口计算衰减。一个 begin_shot 成员跨整个穿透路径，各伤害动作显式使用 pellet；非实体终点先以 entity 旗标排除，避免读取不存在的目标相对几何。基础伤害/射速/弹量/形状校准仍为参考输入，发布包不包含这些测试定义。


### 瞬时射线的表面反弹与累计路程

hitscan 步骤外层可声明 `bounces`（非负整数 count），同时保留可选 `pierces`（默认零）、`hits_per_target`（正整数 count，默认 1）与布尔 `hit_origin_after_bounce`（默认 false）。这些字段属于步骤，不放入 hitscan 几何对象。省略 bounces 保持原单接触/直线穿透编码；显式零进入反弹路径类型但不发生反射。计数允许到 Integer.MAX_VALUE，查询仍共用初始最多 256 米的总路程，没有另加全局效果链次数限制。独立数据示例见 [ricochet_hitscan.json](../common/src/test/resources/effects/ricochet_hitscan.json)。

宿主读取实际方块 COLLIDER 形状，按命中面的单位法向量 n 反射单位方向 d：`d′ = d − 2(d·n)n`。每次从上一碰撞点继续，剩余范围减去本段实际路程；不把反弹当成一条重新获得完整射程的新射线。无需把起点沿法向量挪出墙外，闭区间碰撞算法会排除朝外离开的同一面。嵌入形状内部、预算耗尽或命中恰好位于射程终点时停止；未知区块保持 unloaded，不能当作墙反弹，也不会加载区块。流体仍不阻挡。

相邻方块接缝在大坐标下可能因端点舍入产生多个几乎同时的入射面。宿主只在按坐标 ULP 判断的多面歧义处检查已加载的相邻碰撞形状，保留外露面并排除实体内部接缝；法线改变不移动碰撞点，也不以统一 X/Y/Z 优先级替代墙的实际朝向。普通实体 AABB 接触仍沿用原算法，嵌入与向外离开同一面仍保留原终止/排除语义。

`distance` 对反弹接触表示从最初枪口走过的累计路程；`segment_distance` 是本直线段起点至当前接触的距离。两字段在无反弹射线上相等。`position_distance(muzzle, impact)` 仍是两点直线距离，不自动改变语义；需要弹道路程衰减的内容应读取接触的 distance。`bounces` 在非终止方块接触处已包含本次反弹，后续实体继承；`bounced` 只在实际继续的方块接触为真。实体几何和相对弱点坐标仍来自各个捕获碰撞箱。

pierces 限制整个路径中第一次之后的额外实体接触次数，不在每次反弹时重置。hits_per_target 决定同一实体在整条路径内允许接触几次，默认 1；显式设为 2 或更高时，反弹回来可以再次造成伤害，同时消耗额外接触预算。一条直线段仍只记录每个实体一次，重复回执不能假造重入；换段后才可再次入选。entity_contacts 累计所有实体接触，target_contacts 累计当前实体的接触；整枪汇总仍按不同弹丸/不同目标去重，两次伤害不伪造两个弹丸或两个目标。发射者 owner/emitter 在初始段始终排除；只有显式启用 hit_origin_after_bounce 且已经发生反弹时，才可作为后续目标，归因保持原真实身份。这是可配置宿主能力，不替内容决定自身伤害倍率或自杀/击杀 perk 的资格。

一次世界查询冻结所有线段、几何与顺序，然后逐接触执行动作体。前一方块动作移除墙体或前一伤害移动目标，不重画后面的反弹路径；后续真正写入仍检查目标当前资格。方块接触与后续实体共用一个整枪成员、递增序号，各次动作体关闭后再打开下一次，仅最后接触完成成员。未知世界结果保留既有付款/写入并停止，独立 after 保留路程与反弹数但不保留已结束的记账资格。

反弹步骤、Query、Prepared、Result、Contact 使用五个新增存储 ID，目录由 614 增至 619。原始单接触/穿透记录字段与枚举精确保持。结果校验各段连接、反射方向、剩余射程、单调路程、总计数、来源碰撞开关、每目标次数及每段唯一目标，不接受截短或错配路径。所有表面反射、次数、目标重入和自身碰撞政策与原作的对应范围见 [来源记录](../data/d2-research/2026-10-12/ricochet-hitscan.json)。

### Ricochet Rounds 的装备属性与反弹授予

[ricochet_rounds.json](../common/src/test/resources/effects/ricochet_rounds.json) 提供一个普通/强化共用 Bundle；强化由来源标签 chorus:enhanced 区分。普通 / 强化分别向 weapon_range 加 5 / 6，向 weapon_stability 加 10 / 11。来源必须同时匹配查询持有者和本武器实例。两个属性 Profile 为该 stacking_key 配置 MAX 家族，同词条多个来源只取最大加值，再与其它家族相加并限幅；这是显式内容叠加政策。

`chorus_d2:weapon_ricochet_grant` 接收 count，perks/ricochet 阶段以 MAX 合并授予值。Ricochet Bundle 要求 `ricochet_bounces: count` 参数，引用装备原型中同名的正整数参数；原表没有规定通用最大反弹次数，定义不隐藏默认次数。该参数与初始射程、穿透数、每目标次数分别控制不同预算。反弹能力的最终值为 max(固有值, perk 授予值)，不能让 perk 降低已有更高能力。

[参考消费者](../common/src/test/resources/effects/ricochet_rounds_weapon.json) 使用独立 magazine 槽的 none/normal/enhanced，读取反弹 Profile 后构造 hitscan.bounces，按 impact.distance 计算实际折线路程。另一可选 penetration 槽仅用于独立穿甲弹组合验收；它不是原作合法 roll 或 Seraph Rounds 的声明。Ricochet 自身不授予穿透、盾伤或额外生命伤害。稳定性当前可查询，但尚不驱动后坐力、flinch 或准确性。正式目录与 UI、原作次数和资格校准仍见 [内容边界](d2-ruleset.md#ricochet-rounds-的属性与独立装配)。

## 真实瞄准的角度冲量

`chorus:apply_aim_impulse` 使用两个有符号的 `degree` 表达式，向当前真实瞄准方向增加一次偏移。`target` 默认为 self，可指定 victim 或实体结果绑定；`origin` 默认为 bound，也可使用 event；tags 仅描述来源和用途。它不解释 recoil/flinch 的强度或资格，内容可复用 Profile、命中回执、开火动作和条件规则计算两轴。

```json
{
  "action": {
    "type": "chorus:apply_aim_impulse",
    "target": "self",
    "yaw": {"type":"chorus:constant","value":3,"unit":"degree"},
    "pitch": {"type":"chorus:profile_value","profile":"test:aim_pitch",
      "input":{"type":"chorus:constant","value":-12,"unit":"degree"}},
    "tags": ["test:kick"]
  },
  "as": "aim"
}
```

正 pitch 向下、负 pitch 向上，yaw 遵循 Minecraft 原生 YRot 正方向；不自行归一化 yaw。数值必须有限且可转换为有限 float；读取目标当前角度后，在写入前检查加法结果。原生俯仰限制为 −90° 至 90°，float 精度下无变化或已到极限的请求返回 unchanged，不发旋转包。

回执提供 degree 单位的 `before_yaw/pitch`、`after_yaw/pitch`、`delta_yaw/pitch`；delta 是宿主实际变化，不是请求角度。flags 为 applied、unchanged、missing_target、dead、spectator、sleeping、unloaded，加 observed/changed。未观察目标时读取角度会失败，不能把缺失当零。applied 才产生 `chorus:aim_impulse_applied`，事件含上述六个数值、原始来源和 `chorus:aim_impulse` 标签；动作体先续行，完成事实沿现有事件调度处理。

宿主调用 `forceSetRotation(yaw, true, pitch, true)`，玩家走原生旋转专用包；不改位置、速度，不传送、不取消骑乘。客户端在自己的当前角度上叠加请求并按原生限制裁剪，再通过原生旋转输入回传服务器。回执只证明同步服务器写入，**不是客户端已确认**；网络在途期间的鼠标移动可以使客户端最终角度或极限裁剪不同。当前协议不增加瞄准锁、预测、确认屏障或反作弊政策。非玩家会改变即时 look/head，但后续 AI 可再次转头，不能把这个动作当作 PvE stagger 或眩晕。

未知写入结果保留已经施加的旋转和支付，不发完成事实、不继续后续动作、不重试。延迟动作仍遵循 source/detached 生命周期；恢复的是数据续体，执行时取目标当时的角度，不恢复世界或客户端朝向。[合成验收定义](../common/src/test/resources/effects/aim_impulse.json) 用 3°/−12° 与 0.5 倍测试修饰，均非原作数值。原作恢复曲线、稳定性/武器原型映射、受伤至 flinch 的换算和视觉晃动需要另行装配。

### 受击抗抖的实际消费者与 No Distractions

`weapon_flinch.json` 定义 degree → degree 的 `chorus_d2:incoming_flinch` Profile：Stability、Health、护甲模组各为独立 MAX 阶段，perk 家族之间相乘，同一 No Distractions 家族去重。属性与曲线来自 [数值说明](d2-ruleset.md#no-distractions-与实际受击抗抖)，没有新增 Java 原语。

每件武器只安装一个 `chorus_d2:weapon_flinch_receiver`，传入基础 `stability: stat_point`；另外安装稳定性和 Health 数值 Bundle。消费者监听本持有者的 `damage_taken`，仅当前持握武器处理带 `chorus_d2:flinch_input` 标签的伤害。攻击必须显式提供 `impact.flinch_yaw/pitch: degree`，二者均可有符号；未声明输入不推测角度，声明却缺字段则按严格数据规则失败。正的护盾、Absorption 或生命损失可以产生事实，免疫/取消/零损失不产生受击角度。

受击时先用**防守武器的 bound 来源**查询 `weapon_stability`，让本武器点数与减益限幅生效；再将其作为 `stability` 数值传入两轴的 incoming_flinch 查询。最后 `apply_aim_impulse` 使用 **event 来源**保留攻击者信用。攻击者的武器属性、收起的枪和其他持有者的修饰不能混入。数值查询重用 Profile 与来源过滤，实际位移使用既有回执/未知结果协议；不能把同一枪的多个 receiver 当作数值家族去重。

`no_distractions.json` 从 `weapon_aim_started` 起计时。普通等待 1 秒，强化读取必需的 `enhanced_delay: second` 参数；参考装备约束为正且不超过 60 秒，验收显式选择 0.9 秒。原表仍有问号，Clarity 提供 0.9 秒交叉证据。等待中 `fire_accepted` 重置整段等待，激活后成功开火保持 Buff；被拒绝的射击不重置。它不要求弹匣有弹。释放/结束 ADS、收枪与来源卸下删除本武器计时和 Buff；已经 ADS 时新装来源从安装时重新等待。

计时从输入开始，Health 和已激活 perk 的修饰仅在 AIMED 阶段消费；长进入动画可能先达到计时门槛，再到完成 ADS 时获得抗抖。这是明确的 Chorus 取样政策。基础 Stability 影响当前持握武器的 hipfire/ADS 输入，不能将它等同于已经实现的原作后坐力或准确性。

[独立装备参考](../common/src/test/resources/effects/no_distractions_weapon.json) 包含两把枪和独立头部护甲：Health 从护甲参数进入现有角色属性 Profile，受伤时重新读取；头部替换不重启枪上的 perk。真实瞬时射线每发扣弹并产生显式 flinch 输入。1 点伤害、2°/−10°、20 米、基础 Stability 50、ADS/射速/弹量均为合成验收输入；完整原作基础角度、特殊原型、视觉恢复和生产装配仍待校准。

### Unflinching 的装备件数与护甲乘区

`unflinching.json` 声明六个 `chorus_d2:unflinching_<element>` Bundle，element 为 kinetic/arc/solar/void/stasis/strand。每个装备插槽贡献一个来源，其专属 `*_copies` Profile 按持有者求和并限制为 0–3；精确表将件数映射为 1/0.75/0.70/0.65。所有同元素来源都提供相同的合并档位，`incoming_flinch` 的独立 `armor` MAX 阶段只应用一次该倍率，再与其他阶段相乘；三件不是 `0.75³`。

模组只在完成 ADS、查询带 `chorus_d2:incoming_flinch_query` 且查询来源持有者匹配时生效。`event_source_tag` 读取防守武器的 `chorus_d2:<element>`，不使用模组自身标签或攻击的伤害属性。件数查询采用持有者局部 `profile_value`，无需继承武器身份；不同元素各有 Profile，不会将不匹配来源计入。内容须提供当前武器的单一元素标签；Osmosis/Permeability 等变色行为的实际来源更新需另行接入。

`unflinching_equipment.json` 提供独立 chest 栏与三个必选 mod 插槽，每槽可选 none、六种固定元素之一、harmonic 或对应的显式 *_artifact 优惠版本。空选择不贡献来源，缺失、未知、第四选项及错误装备栏由既有装备验证拒绝。护甲替换立即改变下一次受击的查询结果，未变更的武器 perk 来源继续计时/生效。非装备路径注入第四来源最多第三档；这不代表额外插槽可用。

`chorus_d2:unflinching_harmonic` 用 `selected_ability_tag` 查询当前持有者 `chorus_d2:super` 槽的五种元素标签，仅向所选超能的元素件数 Profile 贡献一件。空超能槽、他人的超能、其它槽的匹配技能和来源标签不能赋予资格；没有动能超能变体。固定属性与匹配 Harmonic 先合并件数再查档位，这是已明确标注、仍待原作混装实测的 Chorus 政策。改变超能选择影响下一次查询，不重启未变化的武器来源。`harmonic_selection.json` 提供不可施放的超能/手雷元数据夹具，不声明虚假的常驻效果，也不代表完整超能或棱镜目录。

该模组是被动数值来源，无 Armor Charge 成本或安静瞄准等待，不生成额外受击动作。真实伤害和来源信用仍沿用同一个 receiver 与角度回执，未知写入不会因多个模组而重放。参考胸甲的 energy 预算为 10，三个插槽的 none/固定属性/Harmonic 分别占 0/3/2；费用不随当前超能或收益是否生效变化。每个非 none 选项要求 `chorus_d2:unlock/unflinching`；七种 `<element>_artifact` 选项各占 1，额外要求 `chorus_d2:artifact/unflinching_<element>`，调用与普通版相同的抗抖 Bundle。none 不需要解锁，只有别的属性授权不能装上当前优惠选项。资格撤销使非法装配停用、保留物品，不自动改选或改价；完整护甲目录、账号/神器进度生产者与 UI 尚未接入。研究与原作边界见 [规则集](d2-ruleset.md#unflinching-aim-护甲抗抖)。

## Benevolence 与盟友增益组合

`ember_of_benevolence.json` 的来源 Bundle 为 `chorus_d2:ember_of_benevolence`。监听 `buff_applied` 的 Radiant / Restoration / Healing Rift presence，以及 `heal` 的 Cure / Healing Rift 标签。只接受本来源持有者作为给予者、不同于自身的接收者、双方当前存在且存活和已观察为 allied 的关系。`event_source_tag: chorus_d2:benevolence_excluded` 可排除特定生产者；不把任意普通治疗当成 Cure，不从治疗推断复活。

成功后授予共享 `benevolence_regeneration`，按当前模式重置为 7 / 4 秒；死亡事实移除收益。卸下碎片不撤销已经接受的有限收益；之后的新触发需要活动来源。`class_regeneration`、`grenade_regeneration`、`solar_melee_regeneration` 在属性阶段后使用 `base_percent` 加上三倍基准，`percent_of` 分别引用 baseline、base_rate、baseline。条件 `selected_ability_event_resource` 只修饰当前槽位的接收账户，Solar 近战通过 recharge_resource 匹配 progress，而非 uses；换槽不会同时回充所有保留账户。

同族贡献 MAX、不同额外基础族 SUM；-10 Grenade 使用独立的碎片族，避免重复来源多扣属性。授予与治疗回执分别用 `buff_application_id` / `heal_id` 去重，60 秒后清理集合项；两个真实 Cure 脉冲有不同 heal_id，允许分别刷新。这是已提交回执的记账，不限制因果链或合法循环。

`cure.json` 新增可调用的 `chorus_d2:cure_application`：`invoke_bundle` 使用事件 `chorus_d2:bestow_cure`、接收者 target 和必填 count 参数 tier（调用者须提供 1–3）。施加器检查接收者已有的共享 Cure 冷却，使用 `apply_status` 授权后开始两次原有治疗脉冲，保留实际给予者。已有自用 `chorus:cure_requested` / Icarus 入口不变，与盟友施加器共享同一定义和一秒冷却。Buff 授予本身不是 Cure 治疗完成，未知治疗回执不产生 Benevolence。

[12 项单测](../common/src/test/java/com/imdomestic/chorus/effect/BenevolenceTest.java)与[世界场景](../common/src/gametest/java/com/imdomestic/chorus/test/BenevolenceGameTest.java)验证公式、双模式、重复绑定、给予者刷新、拒绝/未知、Cure 冷却、过量治疗、Restoration / 裂隙、Acrobat 完成阶段和账户生命周期。合成输入位于 `benevolence_test_inputs.json`，不随发布 jar 打包。复活、Assembler 特例、全部来源排除和正式技能/碎片装配仍需继续实现，不能把通用标签或来源绑定当成这些生产者的原生验收。

### Heal Clip 的独立击杀窗口与治疗组合

链接 `heal_clip.json` 与共享 `cure.json`，并通过武器效果绑定提供三个正秒数参数：`window_seconds`、`enhanced_window_seconds`、`receipt_seconds`。前两者是尚未精确确认的普通 / 强化窗口；后者是事实身份记账的保留期，不能当作游戏冷却。示例装配见 [heal_clip_weapon.json](../common/src/test/resources/effects/heal_clip_weapon.json)，其中武器弹量、射速、换弹和弹道均为合成测试输入，不能直接当作正式 D2 原型。

定义要求确认击杀的 death_id 和合格换弹的 reload_completion_id，先按武器实例记账，再更新或消费窗口。普通补弹不触发；整段逐发装填共享 reload，但每次装入拥有独立完成身份。新的击杀可以在后续插入时再次触发，重复旧完成不得消费新窗口。窗口消费在 Cure 调用之前提交，不因共享冷却、授权拒绝或未知世界结果回退。

自身 tier 2 / 范围盟友 tier 1 均调用 `chorus_d2:cure_application`，通过 `chorus_d2:bestow_cure` 与显式 tier 参数复用授权和共享冷却。调用保留武器来源；其实际盟友治疗可继续驱动 Ember of Benevolence 与当前所选技能回充。协议不包含整条因果链次数上限。具体时序政策、来源和未完成项见 [D2 规则集](d2-ruleset.md#heal-clip-的武器击杀窗口与共享-cure)。

## Phoenix Dive 的 pending 与落地组合

参考 `phoenix_dive.json` 使用已有 Ability、Buff 组件和世界动作，没有新增技能专用 Java 分支或存储类型。输入参数为 `horizontal_speed` / `vertical_speed`（meter_per_second）和 `blast_radius`（meter）；`heated` 由接受施放时是否持有 Heat Rises 标签计算。职业资源引用 `class_regeneration` / `class_gain`，基准 79.6 秒、CES 0.8，沿用当前选择账户的恢复规则。测试校准单独放在 `phoenix_dive_test_calibration.json`，不能作为原作已测数值发布。

动作先 `capture_entity_anchor` 与 `capture_input_direction`，只在可用、存活与输入已观察时执行实际冲量。中立是已观察但无方向，与 missing / dead / unloaded / unsupported 分开；中立只添加世界向下冲量。运动确认后授予 pending，`write_entity_anchor` 保存 cast_life，并保存 heated / blast_radius 数字；热力升腾调用共享 Restoration 而非自建回血计时器。

Pending 的 `chorus:landed` 规则用 `read_entity_anchor`、`read_landing`、`same_entity_life` 关联两个独立事件。先捕获组件数字再 `remove_buff`，避免结束普通 active BuffScope 后继续读取活动组件。随后 `resolve_entity_anchor` 验证当前同生命存活，`invoke_bundle` 调用 `cure_application`，历史 contact 则作为 `select_targets` 的 position 中心。共享 Cure 的确认治疗天然可驱动 Benevolence。

热力升腾分支通过显式 `phoenix_dive_falloff` 和 `phoenix_dive_damage_scale` 构造伤害，使用 `chorus_d2:outgoing` 输出 Profile；职业、通用技能和日灼标签保留在原施放来源。伤害回执确认非致死应用、检查共享锁定并授权后调用 Scorch。首来源 Solar 快照为 class 选择 `class_damage` / `class_kill` 及通用技能标签，不把物理落地事件当成攻击所有者。

确认死亡 / 移除规则同样比较保存生命后取消 pending。卸源不取消已接受施放，pending 不靠任意超时结束；后续真实离地、支付和落地可独立再次完成。当前内容不承诺对可信宿主任意重放久远落地事实提供永久去重。边界、来源冲突与仍缺少的生产装配见 [D2 凤凰俯冲规则](d2-ruleset.md#phoenix-dive-的实际落地与-solar-组合)。

## Heat Rises 的消费与被动收益组合

`heat_rises.json` 提供 `chorus_d2:heat_rises_aspect` 来源、`heat_rises_consume` 转换技能和 `heat_rises` 活动 Buff。链接共享 Cure / Restoration、`solar_effect_duration`、武器 AE Profile 与当前近战回能管线；测试生产者独立位于 `heat_rises_test_inputs.json`。没有新增技能专用 Java 分支或存储类型。

Aspect 来源必须提供 `hold_time` 与 `receipt_seconds` 两个 SECOND 参数。前者是长按阈值，要求大于零；后者指定近期死亡回执集合的有限保存窗口，不能借此宣称永久重放保护。验收采用 .3 / 60 秒，不是原作参数。服务端 `AbilityInput` 观察 PRESS / RELEASE 并测量时长，只有匹配的 release 和足够 hold 才由 priority 100 的 grenade override 进入消费；短按仍使用原技能。

消费技能使用 `cost_from: previous_definition`，没有自己的备用费用或回充账户。`cast_ability_tag` 从 `replaced_ability` 的定义元数据读取 `healing_grenade` / `song_of_flame_wisp`，计算接受时的 `cure_tier` / `restoration_tier`；Touch of Flame 由已装配来源标签提供。普通手雷 → Wisp → consume 只支付 Wisp 已声明费用，保留基础手雷选择和其回充来源，不执行两个被替换定义的动作。Wisp 的真实费用 / 冷却和准入必须由其完整生产者定义，不能由此示例推断。

付款后捕获当前实体生命，只在可用且存活时授予状态并保存 `cast_life`。自身及历史消费脚部中心 9 米内的盟友分别调用共享 Cure；需要 Restoration 时，每个受益者独立查询 Solace 后授权施加。同一次消费的治疗继续使用本次 consume 来源，活动 Buff 保留既有首来源的策略不冒充新的给予者。单个接收者的已知拒绝不回滚其他收益，未知世界结果则停止执行、不重放已写入的生命值。

被动规则监听本持有者的确认 `chorus:kill`，通过 `read_event_entity` 读取冻结的 actor 移动 / victim 分类。共享有限寿命回执 Buff 以 `update_component` 的 `once_set` 保存 death_id，以返回的 `applied` 标志控制后续回能与 `extend_buff`；重复 Aspect 不重复领取同一笔收益。新击杀继续合法运行，回执桶到期不删除活动 Heat Rises，也没有全局因果链上限。

活动状态使用 15 秒 `reset`、30 秒 `extension_cap`，AE 的 passive / active 两族各自 MAX。保存生命与确认死亡 / 移除比较后取消状态；卸源后的保留政策、未连接的滑翔 / 雷达 / 完整 AE 行为和来源证据见 [Heat Rises 规则](d2-ruleset.md#heat-rises-的来源核对与待装配内容)。

## 确认消费可受伤构造物

`chorus:consume_construct` 用于治疗球等“移除一个物体后给予收益”的效果。先 `capture_entity_anchor` 或读取已有完整生命观察，再传入 `reference`；普通位置、UUID 字符串和未写入的绑定不能替代有类型生命引用。

```json
{"action":{"type":"chorus:consume_construct","reference":"orb_life"},"as":"claim"}
```

后续收益显式判断 `result_flag(claim, consumed)`。服务器在实际写入边界比较维度、实体生命和当前运行时归属，只消费仍存活的 `EffectConstruct`；成功后实体立即移除，第二次消费得到已知拒绝。其他结果分别为 `missing`、`wrong_dimension`、`replaced`、`not_construct`、`foreign_runtime`、`inactive`，不会删除普通生物或替换后的新生命，也不自动取消费用、给予掉落或恢复能量。

动作不会扣生命值或伪造 hit / death / kill / pickup。原生移除仍排队发布 `construct_ended(removed)` 及实体移除事实，在当前动作体完成后的边界处理；从构造物自己的 Buff 执行时，确认后的收益可完成，然后生命周期规则清理行为和计时器。已经捕获的目标 / 数值应在消费前保存，收益不能依赖移除后重新查询物体仍存在。实际移除后回执未知会停止运行，保留既有本地提交与物理移除，不重放消费或推测收益成功。

这只提供确认消费的通用原语。何时拾取、谁能拾取、范围与视线、同一时刻多人的分配，以及治疗球是否按每位玩家独立保留，都必须由内容明确决定。治疗手雷现已有下述参考生产者，但完整原作校准和正式装配仍未完成。研究见 [治疗手雷来源](../data/d2-research/2026-10-14/healing-grenade-source.json)。

## 对已捕获位置应用数值偏移

`chorus:offset_position` 是纯本地动作：输入一个位置绑定和三个 METER 表达式，返回同维度的 POSITION，不读取实体或世界，不默认使用原点。缺失位置仍返回 missing；结果可直接作为查询中心或生成位置，并沿用相应世界动作的已知拒绝。错误绑定类型、未绑定引用和非长度单位在编译时拒绝，非有限计算不能成为世界坐标。

```json
{"action":{"type":"chorus:offset_position","position":"impact",
 "x":{"type":"chorus:constant","value":0,"unit":"meter"},
 "y":{"type":"chorus:constant","value":0.01,"unit":"meter"},
 "z":{"type":"chorus:constant","value":0,"unit":"meter"}},"as":"placement"}
```

这里的 0.01 只是示例。它不自动旋转、归一化法线、寻找空位或证明位置可生成物体；实际 `spawn_construct` 仍检查加载范围、边界与完整实体碰撞。与 `rebase_position` 的三个已捕获点平移不同，偏移量由内容的有单位公式提供。

## 治疗手雷的投掷与确认拾取组合

`healing_grenade.json` 选择期挂载共享手雷回充，接受时支付一份，捕获眼部位置 / 朝向和 Touch of Flame 强度，再生成追踪盟友的真实投射物。实体或方块命中后按原接触位置查询盟友并调用共享 `cure_application`；飞行到期 / 卸载不伪造命中。球体使用独立构造物生命、7.5 秒 TTL 和显式 HP 投影。

放置公式为 `impact + normal × (half_extent + clearance) - (0, half_height, 0)`，把中心转换为原生实体脚部，并保留命中治疗原中心。宽 / 高 / clearance 全是 METER；乘法按“长度 × 无量纲法线”表达。球体 Buff 保存已确认生命，在计划扫描时解析该生命，再按当前关系查询 7.5 米内盟友。消费前保存 tier，只有 `consumed` 才移除行为并逐个计算 Solace 时长、施加共享 Restoration；不能在删除行为后再读取其 tier。

当前首个合格扫描消费一个共享球并奖励当时捕获的整批盟友，是待校准的明确内容政策。球体受伤死亡 / 到期 / 移除取消后续扫描，后来的盟友无法重复领取。关系参照不可用时不猜阵营；暂不领取并等待后续扫描或到期。原生消费不会产生虚构击杀；接收者拒绝、Cure 冷却和未知世界写入继续遵守各自回执边界。

参数来自单独的 `healing_grenade_test_calibration.json`：速度、重力、阻力、追踪、尺寸 / 偏移、生命投影及扫描相位均需显式提供。Touch 来源标签只供本分支采样，不能代表完整 Aspect 已实现。参考定义、实际热力升腾长按组合与原生碰撞测试均不等于原作多人领取政策或全套子职业 UI 已校准；完整范围见 [D2 规则集](d2-ruleset.md#healing-grenade-的来源与待装配机制)。

## Firebolt 的扫描与延迟追踪弹组合

`firebolt.json` 使用已有接口组合，不新增 Java 效果分支：技能参数保存 Touch / Ashes，`capture_damage` 保存战斗人员与玩家两份进攻快照；真实初始投射物的 ENTITY / BLOCK 才进入 `offset_position` 和 `select_targets`。扫描以偏移点为中心，按当前视线 / 敌我关系筛选，再按距离及身份截取 4 / 5 个目标，逐个保存 `capture_entity_anchor`。

每个目标拥有独立的一秒 `after` 续体。`resolve_entity_anchor` 成功且仍存活才计算当前方向并发射；destination.reference 继续使用最初保存的引用，而不是重新捕获同 UUID 的新生命。目标消失不从原扫描剩余候选或世界中补位，来源卸下不取消已接受的动作。二次弹体只在 ARRIVED 时读取当前目标类别，并通过 `damage_entity_snapshot` 在实际写入边界再次核对生命；BLOCK / TARGET_LOST / EXPIRED 不产生伤害或状态。

共享 Scorch 在已接受非致命伤害后独立授权，沿用同目标的点燃锁定期。技能来源同时声明 `chorus:grenade_damage`、`chorus:ability_damage` 和 `chorus:solar_damage`，使状态首来源的 DoT / 点燃继承原技能信用；只给直接 DamageCommand 标签不足以替代状态来源的声明。连续合法付费施放仍可叠满触发点燃，不添加全局循环限制。

`firebolt_energy.json` 复用共享手雷账户，选择期覆盖基准 83.8 秒与 CES 1；Heat Rises 消费实际定义只走消费动作。必填的物理参数与外部 `firebolt_outgoing` 校准 Profile 分别存放，测试 Profile 复用普通进攻阶段和合成生命换算，目标等级倍率仍待校准。当前定义保存在验收资源，正式目录、完整 Touch / 子职业装配、客户端表现及跨重启能力另需交付；具体数值和政策见 [D2 规则](d2-ruleset.md#firebolt-的扫描延迟追踪与来源冲突)。

## Fusion Grenade 的附着与两段爆炸组合

`fusion_grenade.json` 复用投射物追踪、生命引用、实体附着、伤害快照和 detached 续体。接受时保存 Touch / Ashes 参数及两种目标类别、两次爆炸的四份进攻快照；选择期来源提供共享手雷回充与晚绑定的范围衰减修饰。投射物对敌人弱追踪，ENTITY / BLOCK 接触才安排引信，EXPIRED / UNLOADED 不触发爆炸，碰撞本身不额外造成一份伤害。

实体接触保存 `capture_entity_anchor` 与经过显式偏移的接触点，再 `begin_entity_attachment`。引信结束用 `resolve_entity_attachment` 取原生命当前位置；首爆后若有 Touch，再等待 0.5 秒重新解析同一句柄。每段各自 `select_targets`，按当前 BODY / 视线 / 敌我关系选取受害者；每名受害者另捕获生命引用，`damage_entity_snapshot` 在实际写入边界复核。附着目标通过同一次范围查询受伤，不再额外补一次直接伤害。地形分支保存固定中心，仍在两次爆炸时分别查询世界。

附着采用 death=follow、destroyed/removed=fix、unloaded/transferred=end。已确认的移除固定最后观察位置，附近的新生命可以正常受到范围伤害，但不会继承旧附着；未经生命周期观察的 missing/replaced/wrong-dimension 没有合法中心，结束该链。完成或已知无法解析时关闭句柄。每次付费投掷有独立句柄和引信，领域检查点保留剩余延迟；卸装不取消已接受动作。这些是当前显式内容政策，需要原作验证。

首爆的 APPLIED 非致命回执且没有共享点燃锁定期才申请 Scorch；APPLIED 零生命损失仍申请是明确政策。第二爆没有 Scorch 动作，因此两个爆炸不是两份 40/60 层。状态已知拒绝不撤销伤害、不取消独立第二爆；未知世界结果停止会话并保留先前提交，不重放伤害或补发状态。来源声明手雷 / 技能 / Solar 标签，使共享 DoT 与点燃保留本次 Fusion 信用。

`fusion_grenade_energy.json` 覆盖基准 95.8 秒、CES 1；Heat Rises 读取并消费实际费用。`fusion_grenade_outgoing` 与 `fusion_grenade_falloff` 是必须提供的外部 Profile。后者输入“到中心距离 / 本次爆炸半径”，输出 multiplier，经 `radial_delta` 传入已捕获伤害的晚绑定阶段。测试只采用线性 1→0.25 衰减和 0.1 生命投影，不宣称原作曲线。物理参数、初次引信和接触偏移同样必须校准；详见 [D2 规则](d2-ruleset.md#fusion-grenade-的附着两次爆炸与数值缺口)。

## Solar Grenade 的固定场与独立熔岩球组合

`solar_grenade.json` 仅组合已有接口，没有新增 Java 效果特例。付费技能保存两种目标类别的初始 / 场 / 熔岩球六份进攻快照和 Touch / Ashes 参数。真实投射物的 ENTITY / BLOCK 接触才创建 `instanced_by: source` 的场 Buff，每次施放有独立来源、位置、参数、寿命及四份后续伤害快照。EXPIRED / UNLOADED 不凭空生成场，也不退还已接受的费用。

`buff_gained` 以 own_buff 限定实例，安排伤害、PvP Scorch 和可选熔岩球任务；所有定时规则还必须匹配 own_timer。仅检查事件类型会使同类多个场响应彼此事件，造成重复伤害或发射。正常到期以 own_buff 与 reason=expired 执行 PvE 结束施加，手动移除不执行；来源拥有的定时器随场结束，已发射弹体继续保留自己的动作续体。每跳重查当前目标，若伤害查询无法取得阵营参照则移除场，这是显式失联政策。

初始 / 场 / 熔岩球伤害均在逐目标捕获生命后通过 `damage_entity_snapshot` 写入，实时目标类别决定所用快照。只有初始伤害 APPLIED、非致命且正实际损失才申请 Scorch；周期 / 到期施加有独立状态授权。已知状态拒绝不撤销伤害或场，未知生成 / 伤害 / 状态结果停止会话且不重放。目标共享 `solar_grenade_tick_gate` 只限定场伤害，首个 APPLIED 回执后授予；不把它扩展为全局事件或点燃循环限制。

熔岩球发射前读取场位置、伤害快照、爆炸半径和接触偏移，保存到独立续体，再用 sample_random、cosine 和 world_direction 生成真实飞行方向。实体 / 地形接触重新查询附近敌人，使用不含 radial 阶段的伤害快照；领域恢复保留随机序列，场结束或卸装后仍在飞行的弹体不再读取已结束场组件。核心回归验证两场各发一弹、两个爆炸独立叠加；原生场景验证实际实体碰撞后两个不同距离目标同伤害。

`solar_grenade_energy.json` 提供 199.5 秒 / CES .5，Heat Rises 消费实际选择的费用而不执行投掷。`solar_grenade_test_calibration.json` 单独提供物理参数、场启动与寿命 Profile、到期 Scorch Profile、命中门槛、熔岩球分布 / 周期、初始衰减及世界投影。测试值不补写成原表事实；时间线冲突、重叠政策和生产接入缺口见 [D2 规则](d2-ruleset.md#solar-grenade-的持续场独立熔岩球与校准边界)。

## Sunbracers：消费准备态与限定接收账户

`sunbracers.json` 用现有 Buff / kill / ability_started / 数值贡献组合。持有者的合格充能近战击杀创建 5 秒 Ready；只有已接受并付款的 grenade_ability 才消费它并创建 4 秒活动状态。不能用输入按键或尚未确认的伤害触发。`selected_ability_tag` 检查基础选择为 Solar Grenade，`selected_ability_event_resource` 限定活动修饰作用于实际被查询的接收账户；不把替换后的 Heat Rises 技能 ID 当成原选择，也不让其他手雷共享账户时获得错误加速。

活动 Buff 自己拥有额外基础回能贡献；Ready 与去重状态仅拥有清理规则，避免三份状态各加一次倍率。声明 base_percent=200、percent_of=base_rate，沿用普通恢复、Benevolence 和切换账户的时间分段。死亡 ID 用显式 60 秒集合窗口去重，独立击杀仍可重新准备。最后一个带 sunbracers 标签的来源卸下清理该组状态，持有者死亡也清理；来源标签必须由可信装备装配提供。已知失败费用不消费 Ready；接受后未知发射结果保留已提交付款和状态，不重放。

Solar 参考定义新增普通 `solar_grenade_throw_speed` Profile，speed 参数通过其 profile 字段在接受阶段计算；校准只替换原始 value，不覆盖修饰管线。Fastball 为家族 MAX 的 delta，来自必填 `fastball_speed_delta`，没有固定在 Java 投射物代码中。外部 `solar_grenade_lifetime` 契约增加 exotic ADD 阶段及 Sunbracers 家族 MAX，再进入原相位校准。两项被动贡献要求 actual grenade_ability + solar_grenade 事件标签，武器生成场不能借用；卸装不修改已接受投掷保存的数值。具体来源冲突、刷新与物理校准政策见 [D2 规则](d2-ruleset.md#sunbracers-的准备窗口基础回能与投掷修饰)。

`field_time` 同样使用技能参数的 profile 字段，而不把带事件资格的修饰放进只读 `profile_value` 查询。后者有独立查询上下文，刻意不继承任意伤害 / 施放事件；前者使用本次接受的技能事件，因此可以验证技能标签并在付款前完成数值取样。来源隔离回归同时检查普通武器 Solar 事件仍为原始速度与未增强寿命。

### 投射物累计射程与近距引信

`projectile` 可选 `range` 米表达式和 `proximity` 声明，均在发射时解析并保存。省略时保持既有飞行行为。`range` 非负，累计每段实际飞行长度，覆盖重力、阻力、转向、反弹和穿透；不使用初速度乘时间，也不使用发射点到当前位置的直线距离。碰撞后的微小脱离位移和宿主外部传送不计入路程。零射程在首次物理 tick 的当前位置结束；寿命仍独立存在。

```json
"range": {"type": "chorus:constant", "value": 8, "unit": "meter"},
"proximity": {
  "radius": {"type": "chorus:constant", "value": 1.5, "unit": "meter"},
  "arm_distance": {"type": "chorus:constant", "value": 2, "unit": "meter"},
  "target_anchor": "body",
  "relation": "not_allied",
  "line_of_sight": true
}
```

这些示例值仅用于说明接口。`radius` 必须为正；`arm_distance` 必填且非负，表示累计路程达到多少米后才允许近距触发，不影响物理碰撞。目标 anchor 默认为 body、关系默认为 not_allied、视线默认为 true。引信球以目标本 tick 的 live anchor 为中心；对投射物的完整线段求首次进入点，不能只检查两端。若在线段中途启动且已在球内，就在启动点触发；启动前已经穿过的目标不能回溯触发。静止投射物只有已经启动且处于球内才触发，等待本身不增加飞行距离。

近距查询排除发射者、伤害归属者、死亡 / 移除 / 旁观实体及不在实体模拟区域的候选，使用当前阵营；相对阵营无法解析时不猜测。`any` 无需归属者存在。相同进入距离按 UUID 确定顺序。视线在本段首次符合距离条件的进入点检查；不是对两次世界 tick 之间的目标移动做连续追踪，也不沿遮挡边界搜索更晚的可见点。持续可见性及更多引信形状仍可另行扩展。

先用射程、到达和引信裁剪路径，再沿可用前缀处理实体、地形或未模拟区域；较早的物理接触仍优先。同点相等时延续实体接触先于地形的原有顺序，两者先于近距引信；无物理接触时近距先于到达，到达先于射程。反弹 / 穿透继续在剩余时间和路程内重新查询。若允许继续的接触恰好耗尽射程，则在该接触同一点、同一 tick 追加 range 终止，不再执行脱离位移或重新追踪。内容自己的接触额度继续适用，没有新增全局循环限制。

终止回执新增 `range` 与 `proximity` 标志。前者没有目标，后者的目标集合只含触发者；两者都是 terminal，不增加 entity_contacts / target_contacts，不提供碰撞箱几何，也不自动施加爆炸伤害。内容应在回执位置另做范围查询、伤害和状态施加。终止及计数在调用动作体前提交，未知动作结果不会复活投射物。

存储分别新增 ProjectileSpec、Parameters、End 的 v2 身份及两项 proximity 类型；旧身份保留原字段 / 枚举形状并显式迁移，旧投射物不会自动获得射程或引信。延迟发射的声明与新参数可随核心检查点恢复；原生飞行实体的跨进程重启仍是独立未完成项。来源与 Incinerator Snap 的剩余内容见 [D2 规则](d2-ruleset.md#incinerator-snap-的来源与物理火花组合)。

## Incinerator Snap 的扇形物理火花与近战信用

`incinerator_snap.json` 是参考数据定义，复用普通能力、方向动作、投射物、范围查询与共享 Solar 状态。一次接受消费共享 `solar_melee_uses`，选择期来源提供 `solar_melee_progress` 的 119.8 秒 / CES .9 恢复。缺少必填校准在付款前拒绝；成功接受后五次发射各有独立世界操作身份，未知发射停止剩余动作且不退回已经支付的费用。

接受时捕获 eyes、look、horizontal、两种目标的进攻快照和 Ashes 参数；从所在维度构造 up，以 up × horizontal 得到 right，再以 look × right 得到 fan_axis。五次 rotate_direction 使用 −2、−1、0、1、2 倍 fan_step，随后发射独立实体；不在每枚命中时重新读取施放者朝向。范围与引信参数也在发射时冻结。ENTITY / BLOCK / RANGE / PROXIMITY 在回执位置执行普通爆炸体，EXPIRED / UNLOADED 不爆炸；近距触发者不是唯一伤害接收者。

每次爆炸重新查询当前 BODY、视线和敌我关系，逐受害者捕获生命并用 damage_entity_snapshot 写入。`incinerator_snap_outgoing` 包含共享进攻阶段，`incinerator_snap_falloff` 根据距爆炸中心的米数返回倍率，通过 radial_delta 晚绑定。确认 APPLIED、非致命且 effective_with_absorption > 0 后才申请状态；拒绝状态不撤销先前伤害。来源同时声明 melee_ability、ability_damage、melee_damage、solar_damage，使初始来源的技能与击杀信用进入共享 Scorch / Ignition。

共享 `solar_scaling` 必须作为持有者常驻系统规则装配；参考宿主通过 ActorBinding 管理，单纯放入 Program 不会自动生效。它不随近战选择移除，因此卸下 Snap 后已发火花与衍生状态继续使用共享数值阶段。该依赖已在共享 Solar 技能宿主中统一执行，包含 Phoenix Dive、Firebolt、Fusion、Solar Grenade 与本技能；原生宿主通过 PlayerActor 保存和同步配置，完整职业默认目录与初始技能装配仍未提供。单元验收同时检查直接近战加成只提高直接爆炸、衍生灼烧 / 点燃继续沿共享 Solar 分类结算，不能把保留近战击杀信用当作重复应用直接增伤的理由。

`incinerator_snap_test_calibration.json` 单独提供速度、重力、阻力、寿命、射程、扇角、近距半径与启动距离、爆炸半径、接触偏移、Guardian 伤害及两个外部 Profile。测试中的平坦衰减、27 点 Guardian 基数与所有几何均为显式输入。五次实际爆炸到共享点燃、实际 Scorch 击杀到 Sunbracers Ready，再到付费 Solar Grenade 的组合有原生世界验收；完整原作校准、生产目录 / 职业限制与物理实体跨进程恢复仍未完成。来源及具体数值见 [D2 规则](d2-ruleset.md#incinerator-snap-的来源与物理火花组合)。
