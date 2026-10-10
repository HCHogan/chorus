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
Loadout next = new Loadout(Map.of("test:weapon_a",
    new Loadout.Gear(itemInstanceId, "test:rifle", Map.of("perk", "enhanced"))),
    Optional.of("test:weapon_a"));
Loadout before = runtime.state().engine().domain().equipment().getOrDefault(holder, Loadout.EMPTY);
runtime.equip(new EquipmentChange(holder, before, next));
```

`EquipmentCodecs.LOADOUT` 提供对应的 slots / drawn JSON 读写。`Gear` 保存 instance / definition / choices / parameters 元数据，没有 ItemStack、数量或耐久；`equip` 不证明玩家拥有物品，不能直接作为客户端请求处理器。玩家物品应通过下面的 PlayerEquipment 容器接口转移，物品组件与效果元数据分开保存。

事务先追赶逻辑时间、验证 before 装配和其全部来源，再一次提交新来源、装配及现有武器 Buff 的 stow / draw 迁移。事实随后依次入队：weapon_stowed / weapon_drawn 及其 Buff 生命周期事实、按来源身份排序的全部 source_detached、全部 source_attached、equipment_changed。`equipment_changed` 的 Fact 携带完整 Receipt 和 before / after；事件数字 equipped_count 为 COUNT。清理动作读取旧来源标签，但看到的全局装备与来源已是新装配。世界动作随后失败时保留已提交状态和待确认操作，不自动重放或伪造回滚。

`on_stow` 是切枪时对已有 Buff 的迁移策略；它不阻止收枪后新授予 Buff。需要这项限制的规则显式检查 `weapon_drawn`。该条件按绑定来源的 owner + weapon 查询装配；无装备记录或无武器归属返回 false，攻击 on_use 快照固定捕获时结果。

非装备宿主可用 `runtime.replaceSources(SourceBatch)` 批量更新其他来源。每项带 before / after，先核对全部旧值再整体提交；即使某项不变也参与旧值验证，任何不一致均拒绝。相等重绑无事实且不重置定时器，替换取消旧来源绑定的定时器；独立 Buff 和 detached 延迟动作按其自身生命周期保留，需要清理的状态由旧来源自己的 detached 规则明确处理。`bind / unbind / replaceSources` 均拒绝直接修改 equipment/ 来源。

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

已安装运行时在 prepare、完整事件边界结束和服务器 tick 同步物理容器投影：死亡或离开该维度会解除旧来源，存活玩家按当前固定目录重新装配。死亡发生在装备反应的世界动作中时，先保留该边界事实，随后同步已掉落的容器，不递归进入解释器。跨维度 / 离线的活动 Buff 和技能状态迁移仍未实现；玩家 NBT 往返、死亡 / respawn 和异常后所有权已有双加载器测试，完整服务器重启与真实多客户端 UI 流程尚未验收。

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

`joltJson` / `voltshotJson` 是调用方已经读取的 JSON。链接会连接各片段的 Buff、bundle、Profile、资源与装备声明，再执行全部引用、作用域和单位检查；不会复制或改写版本，也不会按片段顺序覆盖同名定义。所有片段及内部带版本的定义须使用同一个 ruleset version；各目录中的重复 id 均拒绝，即使内容相同。装备槽位、物品原型和 limit 各自检查重复，装备效果可引用其他片段的 source bundle。最多一个片段声明全局 defense_profile，它可引用其他片段定义的 Profile。空目录、混合版本、重复防御选择及缺失依赖均报错。

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

| 条件 | 含义 |
| --- | --- |
| has_buff 的 match:bound | 默认行为，按当前来源和 Buff 的实例键匹配 |
| has_buff 的 match:any | 此目标任意来源中，至少一个该定义的实例单独达到 minimum；不跨实例相加 |
| has_buff_tag | 此目标至少一个现存 Buff 的定义含 tag；不限施加来源 |
| has_source_tag | 此目标当前绑定的至少一个 EffectSource 的 tags 或 origin.tags 含 tag；不读取动作保留的旧来源或触发事件标签 |
| has_shield | 此目标至少一层活动、未暂停的 Chorus 护盾余量大于零；可用 tag 筛选层的定义标签 |

它们均接受 target（默认 self），只读取已提交的引擎状态。has_buff / has_buff_tag 保留原有 Buff 存在性语义，暂停实例仍可被查询；has_shield 判断实际参与防御的正容量层，不把空层或原版 Absorption 算进去。Buff / shield 标签取自定义；has_source_tag 则明确查询持有者当前来源，用于子职业等装备资格，不把 Strand 攻击标签当成 Strand 子职业。用于 on_use 修饰时，来源侧判断会冻结，victim 判断留到命中时求值；普通动作分支直接查询当时状态。

Rift 内容将这两个概念分开：presence 带 `chorus:counts_as_overshield` 供交互资格使用，即使尚未生成容量；共享 rift_overshield 才保存能挡伤害的实际池。每位成员的多个 Rift 共用一个池和补充计时器：每 50 ms 观测存活且满血、没有带 `chorus_d2:void_overshield` 标签的正容量层后，补充 0.015，封顶 1.5（原表 3 HP/s、15 HP，测试缩放 0.1）。离开一个场保留其他场；最后一个场清理时移除该池，重新进入从零开始。正常观测失败、死亡、受伤或 Void 盾阻止生成时不累计补偿。

这是一份明确的离散脉冲实现。刚在该 tick 恢复满血时会补完整一次脉冲，不计算此前不足 50 ms 的满血时长；首次脉冲、离场是否残留护盾、空层的 FIFO 年龄及多来源共享池保留首次归属仍需原作校准。下文的连续回充只读取引擎状态，没有自动替代这个世界满血观测策略。可运行示例见 healing_rift.json；shield_restoration.json 中的 Void 标签测试层只有合成的 1 点容量，不是完整 Void Overshield 定义。

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

[under_over.json](../common/src/test/resources/effects/under_over.json) 与 [shield_scaling.json](../common/src/test/resources/effects/shield_scaling.json) 可链接为测试程序，分别提供词条与合成盾 / 输入。元素盾、勇士屏障、战员和 Guardian overshield 的标签由内容明确声明，宿主还须提供武器身份、直击 / 爆炸词条和 Guardian / bodyshot 标签。精准因子抑制另见下节；生命层专属 Profile、穿透和自动盾分类尚未提供；测试的 MAX 分组和跨层预算仍需按具体原作组合校准。

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

- 一份充能固定为 1；capacity = 2 不会把 `charge_fraction = 0.1` 翻倍。当前只实现共享能量的顺序回充。capacity / initial 必填，base_rate 默认 0，允许负速率表示持续消耗。capacity 必须为正，initial 和 thresholds 必须在容量内；阈值不允许重复。
- `rate_profile` 可引用本程序中的 Profile，其输入、输出均须为 `charge_fraction_per_second`。base_rate 是输入，输出是本段积分速率，静态来源与 Buff 的 modifiers 按同一数值管线归约。速率查询携带 resource 引用、resource_value / capacity 测量和 `chorus:resource_rate_query` 标签；归属为账户持有者，不把多来源回能任意算给一把武器。完整示例见 [resource_regeneration.json](../common/src/test/resources/effects/resource_regeneration.json)。
- 独立的 `gain_profile` 用于 `grant_energy`，输入、输出均须为 `charge_fraction`；资源只声明 rate_profile 不会自动获得一次性收益的缩放。已声明的两个 Profile 都在编译时验证引用和单位。
- 0、capacity 以及 thresholds 是精确逻辑时间边界；需要监听的中间整格必须声明。`resource_crossed` 默认匹配 self 的账户，支持 up / down；达到后停留在阈值上不会重复触发。一次性入账或消费跨越阈值同样生效。初始化只发 initialized，初值不冒充恢复过程。
- 初始化返回 value / created；消费返回 paid / after / succeeded，并保留实际成本回执。余额不足时不部分扣款；零成本可以成功但 paid = 0。payment 是规则内唯一的本地标识，完整回执身份另含事件、规则实例及动作 OperationId；同一指令在循环中每次执行也是独立付款，不在不同激活或迭代间复用。
- `chorus:resource_changed` 只在值变化时发布；`resource_granted` 另带请求 / 入账测量，`resource_spent` 在实际扣费后发布。统一测量为 before / after / delta / capacity，单位 charge_fraction；引用为 resource / reason，布尔值为 changed。恢复、普通入账、完整充能、消费、返还的 reason 分别为 regeneration / grant / full_charge / spend / refund。通用规则通常监听 changed，避免同时监听专项事实而重复发放同一收益。
- 定义只描述账户，不自动创建所有玩家的资源。DSL 读取、入账或消费未初始化账户会报错；缺失定义、错误 Profile 单位、未声明的反应阈值在加载时拒绝。
- 已声明资源由程序接管速率；Java 宿主速率接口只为程序外账户保留。账户解绑后仍按定义恢复；若玩法要求停用时停止，应通过来源修饰和 base_rate = 0 等内容规则表达。持久化、动态容量、parallel / linked、多份充能分配策略和持久成本账本尚未实现。

### 归一化后授予能量

`chorus:grant_energy` 使用 resource、target（默认 self）、amount（charge_fraction）和必填的 value_basis。base 直接将 amount 送入资源的 gain_profile；reference 先除以 reference_factors 中所有正倍率的乘积；fixed 直接入账，不执行 Profile。base / fixed 禁止 reference_factors，reference 要求非空，fixed 还禁止查询 tags / numbers。base / reference 缺少 gain_profile 在编译时拒绝，非有限 / 非正参考因子在求值时拒绝；均不会先写账户再报错。

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

绑定结果可读 requested / normalized / scaled / credited / overflow / after，单位均为 charge_fraction；Java 结果另外保留参考因子与完整 Profile 轨迹。满容量仍发布 resource_granted，只有余额变化才发布 resource_changed。参考属性与版本放在内容出处中，reference_factors 只表达确实包含的倍率，不是自动跨版本转换。旧 grant_resource 仍用于内容已经完成缩放的 requested / scaled；固定能量比例也可显式使用 grant_energy 的 fixed，不依赖实付成本。完整合成触发例见 [spike_energy_inputs.json](../common/src/test/resources/effects/spike_energy_inputs.json)，不代表某个回能 perk 已完成装配。

### 按当前技能槽授予能量

`chorus:grant_ability_energy` 接受 slot（命名空间 ID）以及与 grant_energy 相同的 target / amount / value_basis / reference_factors / tags / numbers。它在执行时读取目标的基础技能选择，通过该技能 cost.resource 找到接收账户，再复用账户 gain_profile 和完整收益轨迹。它不重新解析施放时的临时 ability_overrides；外部回能属于已选基础技能，不能因为临时免费替换而流向另一个池。未来内容需要别的路由时应显式扩展政策。

未选择该槽时返回 no_selection，已选技能未声明 cost 时返回 no_resource，两者均不求值 amount、不写状态、不发资源事实。声明 cost 且 amount 为零仍有资源账户，不等同于未声明 cost。缺失已选定义、未初始化账户、无 gain_profile 的普通收益等属于配置错误，求值失败，不能伪装成不符合资格。

绑定结果提供 granted / no_selection / no_resource 布尔值；仅 granted 时允许读取 requested / normalized / scaled / credited / overflow / after，缺失收益读取数值会报错，不能用零掩盖缺失。granted 表示完成收益结算，满账户也可能 credited = 0。Java 结果保留 holder / slot / ability 和嵌套收益轨迹。slot 只校验 ID 格式，候选账户及收益 Profile 在实际选择后检查；显式 resource 版本继续提供固定引用的加载期检查。

例如 [pugilist.json](../common/src/test/resources/effects/pugilist.json) 的击杀分支使用如下动作，数值依据和换算边界见 [Pugilist](d2-ruleset.md#pugilist)：

```json
{"type":"chorus:grant_ability_energy","slot":"chorus_d2:melee","value_basis":"base",
 "amount":{"type":"chorus:constant","value":0.04,"unit":"charge_fraction"}}
```

发射后换技能时，以击杀收益执行时的选择为准。选择变化不会把旧池能量转移到新池，也不会自动重配共享池的容量 / CES；这延续现有技能资源目录的边界。无选中技能时的跳过、基础技能归属和当前时点路由都是显式宿主政策，不能据此宣称已校准全部原作换装行为。完整合成候选池见 [ability_energy_targets.json](../common/src/test/resources/effects/ability_energy_targets.json)。

[demolitionist.json](../common/src/test/resources/effects/demolitionist.json) 进一步组合两个独立分支：weapon_kill 使用 grant_ability_energy 指向 grenade 槽；已接受的 ability_started 用 grenade_ability、weapon_drawn 和无冷却条件筛选，再执行 refill_magazine。只有结果 applied > 0 才授予 3 秒冷却，不从请求量推测实际补弹。底层不发布 reload_finished，冷却也不限制另一条击杀回能规则。技能原型、数据基准及待校准政策见 [Demolitionist](d2-ruleset.md#demolitionist)。

### 观察当前技能槽能量

`chorus:observe_ability_energy` 接受 slot 和 target（默认 self），按与 grant_ability_energy 相同的基础选择解析账户，绑定不可变的观察结果。不会初始化账户、扣费、运行 gain_profile、发布事实或改变状态。后续回能不改写已有观察；多个技能的分配规则应先观察全部槽，再按这些结果计算各份收益。

结果 flags 为 available / no_selection / no_resource / full；数值字段 value / capacity / missing 的单位是 charge_fraction，full_charges 为 count（总能量向下取整，一份充能恒为 1）。缺失选择或 cost 时分别设置 no_selection / no_resource，available 与 full 均为 false；任何数值读取都会失败，需先用 available 守卫。已有 cost 的账户缺失、容量不匹配或已选定义损坏仍是错误。读取不要求 gain_profile，也不把 cost.amount=0 当成无账户。

```json
{"action":{"type":"chorus:observe_ability_energy","slot":"chorus_d2:melee"},"as":"melee_before"}
```

Java 结果保留 holder / slot / ability 与含资源键和时点的 ResourceState。它是观察证据，不是冻结路由的支付凭证；之后的 grant_ability_energy 仍解析执行时选择。跨延迟或世界调用的分配需要另外明确路由政策。当前依然只支持顺序充能账户，不能把 full_charges 当作并行充能槽位模型。

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

完整合成示例 [retained_cost.json](../common/src/test/resources/effects/retained_cost.json) 使用真实技能支付、物理命中和独立延迟回调共享额度，再按实际 credited 治疗。普通 paid 数字或 payment 字符串不具备此权利。当前只支持同一运行时内通过词法捕获传递；向 Buff 引用组件保存、独立后续事件按施放查找、跨维度迁移和跨重启恢复仍未实现。固定充能比例的收益继续使用 grant_resource，不能因为发生在返回阶段便自动改为按实际成本退款。

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
| `spend_ammo` | amount 必填，pool 为 magazine（默认）或 reserves；全部足额才扣除，失败 applied = 0。零请求可 complete，但不会产生实际弹药或开火事实 |
| `refill_magazine` | amount / ceiling 均可省略；实际量受请求、弹匣空间和储备限制。从有限储备扣除同量；无限储备无需保存一个虚构的大数 |
| `grant_ammo` | amount 必填，pool 默认 magazine，ceiling 可省略。给弹匣不会扣储备；给有限储备最多到储备容量；给无限储备 applied = 0。弹匣默认 ceiling 为基础容量，已有溢出不被删除 |
| `ammo` Value | weapon 默认 this_weapon，field 为 magazine / capacity / unmodified_capacity / missing / reserves / reserve_capacity；单位 round。capacity 为当前有效基础容量，unmodified_capacity 为固定输入，missing = max(0, capacity − magazine)。不存在账户或读取无限储备的有限数值均报错 |
| `round` Value | input 为任意同单位数值，mode 必填 floor / ceiling / half_up。保留单位；half_up 在恰好半数时远离零。它不自动将 charge_fraction 或 count 换成 round |

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

fire_accepted 的 actor 是持有者，victim 为空；source.owner 为接受时持有者，source.weapon 为实际物品实例，source.source 为 `shot/<请求标识>`。references 包含 weapon / item / shot，numbers 包含 cost、interval 和 scheduled_interval。发射后的投射物保留该来源、捕获的攻击和词法值；收枪或后来手持另一把武器不会改写其信用。

**接受、发射成功、命中、击杀与整枪结算是不同事实。** fire_accepted 不证明 projectile 已成功生成；生成是否成功看实际 Launch 回执，hit / kill 看 DamageReceipt。弹药支付也不自动生成 shot_fired、shot_resolved 或“射空弹匣”。若声明的世界动作被明确拒绝，已接受的弹药和间隔保持；若结果未知，运行时保留待确认操作并停止，不自动退款或重放。具体内容若需要已知失败补偿，应另声明并验证相应回执处理策略。

weapon_damage / weapon_kill 由伤害动作的 tags / kill_tags 明确声明；仅有武器来源不会自动获得武器击杀信用。双加载器已验证：实际容器命令 → 物理投射物击杀 → 手动换弹 → Kill Clip → 下一发实际 12.5 点伤害，且发射后收枪仍保留攻击快照。另验证取消武器击杀标签后，真实击杀不触发 Kill Clip。

Rampage 的可执行例子见 [rampage.json](../common/src/test/resources/effects/rampage.json) 与 [rampage_weapon.json](../common/src/test/resources/effects/rampage_weapon.json)：词条片段引用消费者的 test:weapon_damage / perks / weapon_perk，链接时检查存在性；普通与强化 Buff 使用独立静态 duration / decay_interval 并共享数值 bundle。仅覆盖 grant_buff.duration 不会改变定义的 decay_interval，因此两者不同的变体须分别声明。微秒计时、收枪保留、真实击杀和发射快照的具体政策见 [Rampage 规则集](d2-ruleset.md#rampage)。

当前是一触发一次动作体的服务端入口。客户端按键 / 长按、hitscan、精准区域、自动 burst 控制、蓄力 / 射击模式、未命中 / 弹匣耗尽的内容资格、射击手感和 HUD 仍需扩展，不能据此把相关 Compendium 词条标为已覆盖。

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

计数值单位均为 COUNT。类型化 `ShotGroups.Summary` 额外保留两个每目标计数表；公共事件 victim 选择命中数最多的目标，同分按身份字符串排序，没有 hit 时为空。贯穿可计入多个目标；同一弹丸对同一目标的多次接触 / 多条伤害命令只计一次。免疫和格挡遵从现有 hit 契约，计入 hit 但不计入有效伤害；CANCELLED / FAILED 与纯几何接触不计入 hit。集中命中要检查 max_pellets_on_target，不能只检查 pellets_hit；需要最终完整结果时再要求 complete。

`chorus:shot_progress` 在实际伤害回执增加任一目标的唯一 hit / 有效弹丸数时发布；重复命中未改变计数则不发布。另有 COUNT 字段 previous_pellets_hit / previous_pellets_effective / previous_max_pellets_on_target / previous_max_effective_pellets_on_target，references.pellet 指向本次成员。类型化 Progress 保留不可变的 before / after Summary。当前接触尚未关闭，complete 通常为 false；该事实不等待整枪结束，也不证明剩余弹丸已完成。

One-Two Punch 使用 `previous_max_pellets_on_target < 门槛 && max_pellets_on_target >= 门槛`：普通 12 / 强化 10，达到时立即挂 Buff，同一枪的后续弹丸不重复刷新。只等 shot_resolved 会错误推迟强化版触发。hit 与消费生命周期事实仍先入既有队列，progress 随后入队，不抢跑当前动作体或改变广度优先执行顺序。

局部状态先保留索引，再等待物理发射回执；终止接触的动作体与实际伤害回执完成后才封闭该弹丸。所有槽位结束时发布一次 summary 并取消截止计时器；明确发射拒绝仍是已知终态，不会自动退还已接受的弹药 / 间隔。重复接触、已结算后的回调均不重放动作体。未知世界结果保留已提交状态与待确认操作，停止运行，不推断为 miss / failure 或自动重试。

攻击寿命为半开区间 `[startedAt, dueAt)`，精确到期的延迟发射也不能重新打开组。超时发布 complete=false 与未结算数量，清理组并停止仍存活的成员；这是一条内容声明的攻击寿命，不是事件循环限制。索引可以在寿命内通过 detached after 延迟发射，但 `damage_snapshot.pellet` 只能在对应接触的同步动作体中使用：编译器不向 after 或嵌套 projectile 传播当前接触的记账资格。延迟爆炸仍可沿用几何与伤害快照，需作为独立伤害或新组建模。

目前提供显式成员 projectile 与 damage_snapshot 记账，One-Two Punch 已有独立内容与实际玩家输入 / 投射物 / 近战验收，仍为 partial，见 [规则集](d2-ruleset.md#one-two-punch雪上加霜)。原版命中自动归组、hitscan、精准分类、自动连发、跨组 / 多分量共用一次“下一击”事务、Rewind Rounds 内容和活跃组 / 飞行实体的存档恢复尚未实现。

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

modifier 可声明 `"evaluate":"on_use"`（默认）或 `"evaluate":"on_hit"`。前者在宿主捕获攻击时绑定来源侧数值与条件；`victim` 的资源 / Buff 读数及条件仍留到实际命中时求值。后者在命中时从当前攻击者的来源 / Buff 中收集。两者保留原本的 stage / group / stacking_key，在同一 Profile 中一起归约。

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

时机字段本身不会生成投射物或定时任务。JSON 可用下节的 `capture_damage`、`after` 和 `damage_snapshot` 完成捕获及延迟伤害。Java 宿主也可在释放时调用 `runtime.captureDamage(attack)`，保存返回的 `DamageSnapshot`，命中时把 `snapshot.command(targetUuid)` 提供给原生伤害来源适配或世界命令执行器。捕获要求 attack 有明确 scaling_profile，使用当前已追赶的状态；原始 target 不参与捕获。每次命中可指定不同目标，其余攻击元数据固定。未携带快照的普通 damage 仍按即时查询计算；目标防御 Profile 总是使用命中时当前状态。

快照包含旧 Profile、来源身份与部分求值后的表达式，不包含实体对象或可变装备引用。若新目录提供 on_hit 贡献，其阶段 / 分组结构必须与旧 Profile 相容；不相容会明确失败。计算轨迹逐贡献记录来源版本，不能把旧 Profile 的版本当成所有实时贡献的版本。快照不能跨 PvE / PvP 模式复用，命中时刻也不能早于捕获时刻。

当前 capture 上下文只有 incoming_damage、damage_type、伤害标签和来源身份；尚未装配的事件测量不会默认为零。自定义 Value / Condition 参与 on_use 时须实现 `snapshot` 部分绑定，返回不可变数据表达式，不能保留 Evaluation 或可变世界状态。on_release / on_tick / on_proc、其他原版投射物自动适配、完整反应规则继承、派生筛选与持久化尚未完成；这些字段不能当作已支持的 JSON 使用。

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

规则可用现有 `update_component` 的 `once_set` 与 `event_reference: "batch_id"` 按批计数，改用 `damage_id` 则按实例计数。集合属于指定 Buff 实例，生命周期由该 Buff 管理；长期存活的累计集合仍需内容提供重建 / 到期策略。需要按有效伤害计数时，再检查 effective_damage 等实测字段；免疫 / 格挡可以有 hit 事实，取消 / 失败没有命中事实。

`capture_damage` 不接受 batch。可复用数值快照不会冻结“未来所有命中属于一批”；应在 `damage_snapshot` 上绑定批次。物理投射物可以在 impact body 内创建批次，再让直击 / 爆炸等多个分量引用；同一 shot 的多个接触是否共享批次须由内容决定。句柄可像其他不可变结果一样显式传入 after / projectile；这只表达内容声明的逻辑关系，不证明两个回执在现实时间同时发生。延迟脉冲若应分批，就在每次延迟体内创建新句柄。

root、shot、服务器 tick 和批次之间不做自动映射；派生伤害默认创建自己的单例批次，正常循环与事件传播保持原行为。该机制仅提供身份及按批记账，不提供最终 batch_resolved 事件或全批原子结算。多分量共用一次性 Buff 资格由下述独立的 damage_group 显式声明，不由 batch 自动推导。完整合成夹具见 [damage_batch.json](../common/src/test/resources/effects/damage_batch.json)，它不是完整 Bolt Charge。

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

`EquipmentPayloads` 在 common 定义三种 payload，加载器只负责注册与发送：

- `equipment_visit`：打开 / 关闭指定页面实例的订阅。
- `equipment_request`：页面 id、服务端会话 token、展示序号及 swap / draw / stow / move 的槽位或背包索引。没有可提交的物品、物主、词条或效果定义。
- `equipment_view`：页面 / 会话身份、玩家 / 维度、展示序号、处理结果和完整展示快照。包含装备 ItemStack 组件、drawn / revision、主背包、可用槽、运行状态及 presentation / version；不发送整个解释器状态。

服务端以真实连接玩家为所有者，只在服务器线程处理；核对会话、序号、实际物品 / 组件、规则运行时对象及状态。背包变化或同版本运行时替换也会令旧展示过期。每次处理过的请求都推进展示序号，包括拒绝和空操作，因此旧请求不能再次交换；页面从服务端返回值重建，不先移动本地物品。另一个容器打开或鼠标持有物品时拒绝操作。运行时缺失、定义不可用或效果失败仍展示物品，并沿用服务端已有的取回规则。

只给正在查看的玩家同步，tick 比较完整内容后有变化才发送；页面关闭、离开维度、玩家对象移除或服务器停止时清理订阅。刷新使用新页面 id，旧页面回包被忽略。网络集合与字符串有长度上限；它不是仓库、交易或多人共享容器协议。

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

cost.amount 与每个 parameter.value 都是类型化 Value；各自可另给 profile，输入 / 输出单位必须保持不变。参数与成本在接受施放前统一查询当前持有者的来源 / Buff 修饰，固定为本次施放的数据。参数之间没有隐式求值依赖；param.<name> 是给后续动作读取的已解析测量。省略 cost 表示无账户成本；amount = 0 表示使用已声明账户的免费施放，仍有 paid = 0 的成本回执。

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

服务端 API：

```java
runtime.abilities(new AbilityChange(holder, before, new AbilityLoadout(Map.of("example:grenade", "example:heal"))));
AbilityUse.Receipt receipt = runtime.useAbility(player, "example:grenade");
```

选择入口是可信宿主 API；尚未提供子职业、解锁和装备约束的玩家选择校验。变更核对完整 before 及常驻来源投影，预检定义 / 槽位后整体提交选择和 effects。当前首次选择某槽时初始化该槽所有已声明候选技能会用到的资源池，已有账户只校验、不回满；清除选择也保留账户，账户继续按该运行时的资源时间轴推进；角色离线 / 暂停恢复策略尚未接入。槽内共用能量应引用同一个 resource id，不能为每个变体分别建账户后误称为同一冷却。此版资源容量与恢复定义仍属于程序固定目录，切换技能不会自动重设 CES、容量或恢复基准。

use 只接受真实玩家和槽位，校验维度、存活、非旁观及运行时健康；服务端采样 on_ground / sprinting / crouching。请求不带任意目标、施法者、技能定义或客户端运动断言。槽为空、条件不满足、替换冲突和能量不足返回明确结果，不发 ability_started / ability_used、不执行效果。成功时先提交实际资源扣除，再依次排入 resource_spent / resource_changed（仅有实际支付时）、ability_started、ability_used，on_use 在 ability_used 执行。

ability_started 与 ability_used 携带同一份已接受的定义、参数和成本回执。ability_started 的即时规则先于 on_use 动作体执行，适合移除“开始施放就结束”的状态；它不是取消或退款入口。资源已提交，规则若遇到未知世界结果仍遵守停机、不重放契约。它的后续派生事件按既有广度优先队列排序，并不保证先于 ability_used；需在动作体之前完成的清理应直接放在 started 的即时规则里，不能只再发一个清理事件或使用 after。

两者的 source.owner 是施放者，source.source 是独立 cast 身份，source.ability 是最终定义 id；标签来自定义和可信宿主输入。references 提供 ability / base_ability / ability_slot / cast，numbers 提供 paid 与 param.<name>，flags.free 表示未实际支付。on_use 持有本次解析结果，不因资源事件反应、切换选择或后续卸下来源而重新解析技能。

有 cost 的 on_use 可通过隐式绑定 `cast_cost` 使用 refund_cost，遵循实际支付额和同一执行帧内累计认领上限；免费施放不能由退款制造能量。也可用 retain_cost 把剩余额度转交给有限期句柄，由 after / projectile 捕获；其他独立事件仍不能仅凭 paid 或 cast 字符串取得退款权。即时 on_use 动作体没有持久来源寿命；命名 timer / cancel_timer 与 source 生命周期 after 在该动作体内编译时拒绝，延迟动作必须明确 detached，或先建立拥有寿命的 Buff。已接受的世界操作失败保留扣费和待确认操作，不自动回滚或重试。

最小命令：

```mcfunction
chorus ability choose example:grenade example:heal
chorus ability status
chorus ability use example:grenade
chorus ability clear example:grenade
```

choose / clear 需要管理员权限；status / use 只操作发起玩家。运行时须先安装包含定义的程序。技能选择和能量仍只属于当前维度运行时，不保存到玩家 NBT，不自动跨维度 / 离线迁移；重装运行时不等于已完成角色技能恢复。按键 / 网络展示、完整子职业与技能原型、parent 继承、持续施放取消、目标 / 方向快照、独立技能状态持久化及原作参数装配继续待做。合成 abilities.json 不增加 Compendium 内容覆盖条目。

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

Buff 目录通过 `bundle` 字段关联上述 Bundle。`action` 支持 `ability_use / weapon_fire / weapon_reload / ranged_attack`。查询只读取操作人的 SOURCE 和操作人身上有效、未暂停且适用于当前武器 / 技能的 BUFF。Debuff 可以由其他人施加，判断对象仍是持有者，拒绝依据保留原施加者。各声明独立求值，任意一项拒绝就不能执行；没有可覆盖其他拒绝的 allow 优先级。例外应写入该条禁止条件，如“禁止技能，地面上的 Super 除外”；另一条禁止技能的效果仍可拒绝该 Super。

查询带 `chorus:action_gate_query` 标签，references 包含 `action` 和 `action_phase`（`start / continue / complete`），并保留对应操作的标签、字段与来源。技能在全部替换解析完成后查询，能读取最终定义标签及 `ability / base_ability / ability_slot / cast`，但此时尚未计算 `param.*`、支付能量或发布 accepted 事实。宿主技能输入提供当前原版 `on_ground / sprinting / crouching`；未观测字段不猜成 false。武器查询含 `weapon / item`；开火另有 `shot`，换弹另有 `reload / reload_step / incremental`。技能施放和原版 ranged_attack 输入带上述原版移动标志，不能假定武器查询也有。

`CompiledEffects.checkAction` 是只读查询，不发布查询事件或执行规则动作。`Decision` 保留操作、阶段、查询快照和所有命中的拒绝声明（Bundle、实例、声明 id、原来源），按实例 / 声明排序。普通技能、开火和换弹 API 的 `RESTRICTED` 回执包含这个 Decision；拒绝不会支付成本、扣弹、建立射速间隔或覆盖已存在的换弹计划，也不发布 accepted 事实。基础装备 / 定义资格先检查，开火原有的射速检查也先执行。

手动换弹额外在宿主确认后的 `complete`、每次装填反应结算后的下一次 `continue` 复核限制。完成前拒绝不转移弹药；下一步拒绝保留刚完成的弹药转移、回血和其他反应。取消会清除计划及计时器，发布 `reload_cancelled`，`reason=action_restricted`；其 `ActionGate.Cancelled` payload 同时实现常规 EffectEvent.Carrier 并保留拒绝 Decision。授予限制 Buff 本身不会立即取消已接受计划；若它在下个复核边界之前过期，计划仍可继续。

该机制只拦截这些新操作及手动换弹边界。`reload_weapons` 等效果动作、已接受技能动作体、已飞出的投射物、DOT 和点燃连锁继续按各自内容执行。活动技能 Buff 可通过下节的批量结束动作终止，原版远程攻击接线见下节；原版左键 / 物品使用、AI 移动、完整 Suppression / Freeze / Suspend 资格以及客户端禁用提示尚未接入，不能把这组通用能力当作这些 D2 状态的完整实现。[action_gates.json](../common/src/test/resources/effects/action_gates.json) 是合成验收内容，不增加 Compendium 效果覆盖数。

## 原版生物的远程攻击资格

`ranged_attack` 是原版 Mob 的远程攻击入口，独立于玩家 Chorus 武器的 `weapon_fire`。可由 SOURCE 或目标持有的 BUFF 声明禁止条件；核心不识别压制或敌人等级。运行时未安装或当前程序没有这种门槛时沿用原版路径。

Minecraft 26.3 接线覆盖全部九个 `RangedAttackMob` 实现：骷髅系、溺尸、女巫、幻术师、掠夺者、猪灵、雪傀儡、羊驼和凋灵（含侧头及无目标坐标发射）；另覆盖烈焰人、恶魂、潜影贝、守卫者光束、旋风人 Shoot、监守者 SonicBoom 和末影龙扫射火球。判定在新弹体创建、发射音效、弩弹消耗或光束伤害之前进行。烈焰人近战和其他 AI 不被整体关闭。Goal / Brain 的持续检查会正常结束受限蓄力，保留原版停止时的姿态 / 冷却处理；拒绝的龙扫射转回盘旋。不会补发受限期间错过的攻击。

查询的 actor 是攻击 Mob，victim 是入口提供的目标；凋灵坐标发射没有实体目标，victim 为空。来源 owner 为攻击者，source 为原版攻击标识，weapon / ability 为空；不凭手持物猜 Chorus 信用。来源标签包含当前实体类型标签、实体自身标签和宿主明确提供的 `chorus:combatant`，不自动猜 D2 等级。事件带 `chorus:native_ranged_attack`，references 提供 `native_attack / entity_type`，flags 提供当前 `on_ground / sprinting / crouching`。每次尝试重新读取当前标签和已结算 Buff。这些原版查询使用 `start` 阶段；蓄力检查复核尚未发射攻击的资格，没有额外创建 Chorus 技能施放或 accepted 事实。

`MinecraftEffectRuntime.nativeActionReport()` 保留最近一次查询的不可变输入、逻辑时间、Decision 或查询失败原因；它只用于诊断，不发布新事件，也不无限积累实体引用。查询错误拒绝本次尝试并记录运行时失败。运行时失败后查询沿用最后已提交状态，其他没有命中限制的生物仍可射击；没有把查询错误伪装成有效拒绝声明。

这个入口不检查既有弹体的飞行或命中，不撤销已结算伤害，也不限制 DOT / 点燃反馈。其他模组自定义发射入口、原版近战 / 法术召唤、移动失能、玩家左键及客户端禁用展示仍需各自接线。[native_ranged.json](../common/src/test/resources/effects/native_ranged.json) 是合成机制验收，不增加 Compendium 效果覆盖数。

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

[impulse.json](../common/src/test/resources/effects/impulse.json) 是合成夹具，含已付款延迟冲量和两点推开示例；20 / 10 米每秒、延迟和治疗观察器不是 D2 数值。持续钩爪 / 摆荡的预测状态机、移动属性、按键与输入方向、移动 / 落地事实、闪身 / 急切刀锋的正式触发与距离时间曲线仍需实现。

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

此例展示结构，不是完整 Thunderclap 或其他技能定义。capture_direction 读取服务器实体的 look vector，保存带维度的归一化方向；capture_position 的 anchor 可选 feet（默认）/ body / eyes。方向与位置结果可由 after 捕获，原实体转身、移动、移除或来源解绑不改写这些值。缺少实体返回 missing，不补零向量；圆锥使用缺失方向时返回 missing_direction，方向和查询中心不在同维度时返回 wrong_dimension。它还没有可写入 Buff 的方向组件或持久化 Codec。

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

发射使用 `ProjectileFlight.Launch / Receipt` 世界协议，操作账本防止重复发射；回执必须对应原请求。缺失位置 / 方向、错误维度、未知区块和宿主拒绝不会产生可执行实体，也不会自动退款或重试。当前步骤不在外层暴露命名的发射回执；需要按发射失败退款的内容仍需扩展结果接口。

服务端每个 Minecraft tick（50 ms）推进一次：先加重力，再乘 drag，再移动。drag 是每 tick 的乘数，不是每秒速率。寿命向上量化到物理 tick，在最后一步先处理碰撞再处理到期；它不改变规则引擎计时器的微秒协议。速度 / 重力必须在 0–2000 的各自单位内，drag 在 0–1 内，寿命必须为正且能表示为有限整数微秒；超出宿主边界拒绝，不裁剪成另一个值。

命中用每 tick 的完整线段扫描，方块碰撞形状先限制线段末端，再检查实体。实体碰撞箱向外扩张 0.125 米，方块使用中心射线；只有存活且非旁观的 LivingEntity 可作为直击目标，原施加者始终排除。没有自动阵营排除、投射物互撞、盾牌反射、水下阻力或方块 `onProjectileHit` 副作用。流体不遮挡。未知端点 / 途中区块终止为 unloaded，停在上个已知位置，绝不加载新地形；范围 / 阵营及爆炸视线由动作体另行声明。

每次接触先提交计数，终止时才消费实体，再恢复独立动作体；后续失败会放弃余下飞行，已提交接触不重放。运行时停止 / 替换 / 故障后，旧飞行物会移除，不交给新程序执行。当前实体 `noSave / noSummon`，不支持传送门、区块卸载续接或重启持久化。两端使用原版实体跟踪协议同步运动，重力 / drag 是显示用同步数据，完整动作体和快照仅在服务端；默认紫水晶碎片外观只是占位。原版箭、雪球等不会被自动转成该协议。显式伤害快照已携带来源层 origin_bundle 选择；Buff 规则、多版本并存和派生筛选继承仍待实现。


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

三个次数字段接受 count 单位的整数 Value 或字符串 `unlimited`，均在发射时求值。默认值依次为 0、0、1；前两个允许零，每目标上限必须为正。有限值不能超过 2147483647，省略上限只能用 `unlimited`，不使用负数约定。`entity_pierces: 1` 表示穿过第一个目标，在第二次实体接触后停止；物理接触即计数，即使后续伤害被取消。墙面反弹与实体穿透预算独立，不限制引擎中的合法事件循环。

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

上述 tracking 负责自动选敌；下节 destination 负责固定身份的返回。玩家接回输入见下文；目标类别优先级、D2 转向率校准与统一的墙面 / 实体弹跳总预算仍待实现。三种近战技能的数值与状态装配仍是 [规则集需求](d2-ruleset.md) 中的未完成项。


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

可执行的 [tally_return.json](../common/src/test/resources/effects/tally_return.json) 让去程实体在三次实际接触后终止，仅在 terminal 分支新建回程。各次伤害先分别入账；回程抵达或接回时关闭汇总，用累计 hits 计算两种回能，用累计 kills 治疗。此例的三个目标、追踪参数、每击 10% / 20% 回能和每杀治疗 3 均为合成值。它证明跨阶段统计；另有 [threaded_spike.json](../common/src/test/resources/effects/threaded_spike.json) 按 Compendium 组装九目标、原表档位和 Woven Mail，必填校准参数与内容边界见 [规则集](d2-ruleset.md#threaded-spike-技能模板)。句柄尚不支持从任意事件 / Buff 按字符串查找、跨运行时迁移或重启持久化。


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

`target` 默认 self，也支持现有目标引用。input 在调用处求值；Profile 在被查询实体的独立上下文中计算：actor / victim / source.owner 均为该实体，source.source 为 Profile ID，武器 / 技能、标签、测量、引用及历史观察为空。属性修饰自己的绑定来源仍可读取。这样外层武器击杀等条件不会意外参与实体属性；需要触发上下文或任意单位转换的查询继续使用下面的 calculate 动作。

查询不写状态、不发事件、不执行世界请求，缺失 Profile / 错误单位 / 显式组件输入缺失明确失败。CompiledEffects.attribute 返回包含基础输入及贡献的完整 CalculationProfile.Result，表达式读取其输出；外层轨迹保存最终贡献数值，暂不自动嵌套属性的子轨迹。

on_use 的来源属性必须在捕获时可求值，结果保存为常量；victim 属性保留为表达式，其 input 的来源操作数先冻结，命中时使用当前目标及当前程序的属性定义。缺失或不兼容的新定义明确失败。属性依赖按实体 + Profile 区分，跨实体读取同一 Profile 合法；直接、相互以及通过动态弹匣容量返回自身的数值依赖会报错，不改写状态或截断效果事件链。完整例子见 [attribute_queries.json](../common/src/test/resources/effects/attribute_queries.json)，其中加值与治疗 / 伤害比例均为合成验收输入。

### D2 装备属性输入

`character_stats.json` 定义 Health / Grenade / Melee / Class / Super / Weapons 六个属性 Profile，每项合计来源贡献后限制在 0–200。`armor_stats.json` 的 SOURCE Bundle 要求六项 stat_point 参数，按实际装备来源累加，不先钳制或改写物品原始值。`armor_stat_inputs.json` 展示 helmet / arms / chest / legs / class_item 五槽与显式物品参数映射；其中 `test:armor_*` 和每项 0–200 的输入范围只是验收原型，不是原作合法掉落生成器，不校验六项总点数、金装特例或 archetype。

实际技能定义通过 `effects.energy_scaling.bundle` 选择 `chorus_d2:arcbolt_energy_scaling` 或 `chorus_d2:threaded_spike_energy_scaling`，两者查询零输入的最终 grenade_stat / melee_stat 再套用已有主动 / 被动曲线。裸装、首次选择和只有碎片时均无需先创建属性 Buff。卸载技能来源后该曲线不再贡献，资源账户仍按固定定义的基础率 / CES 及剩余来源运行。

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

Continuity 的 fragment Bundle 只贡献该次查询提供的 extension，生产者只声明已知基础时间与对应扩展，不直接检查 fragment。采用 MAX 避免重复装配同一 fragment 叠加。该入口可表达 Sever 10+5 / 5+2.5，也能表达 Suspend 6+2 / 3+1 / 2+1；后者目前只有数值查询验证，不代表 Suspend 的位移、控制、Boss 和勇士机制已经实现。

### 有序 Profile 组合查询

`chorus:calculate_pipeline` 接受非空 profiles 列表，其余 target / input / origin / tags / numbers / victim 与 calculate 相同。先解析所有 Profile，检查输入与相邻段的单位，再按列表顺序执行。每段使用同一已提交状态、持有者和显式查询上下文；上一段输出成为下一段基础输入，不改写事件 numbers，也不自动加入中间值测量。

```json
{"action":{"type":"chorus:calculate_pipeline",
 "profiles":["example:reload_stat","example:rifle_reload_seconds","example:reload_animation"],
 "input":{"type":"chorus:constant","value":30,"unit":"stat_point"}},"as":"reload_query"}
```

结果 input / value 分别采用首段输入和末段输出单位，可像普通 calculate 结果一样跨延迟 / 投射物保留。Java 的 CalculationPipeline.Result.steps 按发生顺序保留每段完整 Result，包括 Profile / 版本、输入、贡献归约、置信度、命名因子与阶段轨迹。同名阶段或重复 Profile 不合并；明确列两次就执行两次。未知 Profile、空列表、单位断裂在编译时拒绝。

withBase / withoutFactors 只重算已保存的数学输入，不重新读取状态、表达式或调用原收集器。上游变化会逐段重新送入下游曲线 / 限幅；移除因子按命名匹配各段，使用重算而非除法，所以零倍率也能处理。已选贡献的置信度跨段汇总。这里是有限顺序查询；没有改变事件队列、合法效果循环或单个攻击快照的结算协议。

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

可选 `spawn_as: "created"` 把同步生成回执绑定到外层后续动作：`created.spawned` 为成功标志，`count` 为成功 1 / 失败 0；其余互斥标志为 missing_position、missing_recipient、wrong_dimension、unloaded、rejected。内容可据此只在确认生成后开始冷却。该回执不是目标集合，不能 foreach；不能与已有绑定或 contact 同名，也不能在自身的延迟动作体中读取尚未返回的生成回执。已知生成失败可以分支处理，未知世界结果仍停止推导，不视为已知失败后重试。

成功收集会排入一次 chorus:pickup：actor / victim 是收集者，source 是生成者，tags 含 kind，numbers 含 count=1 / age，references 含 pickup_id / pickup_kind / collector。拾取者词条使用 target_is(self,event_actor)，不能用 source_is(owner) 代替。事实按既有队列在当前动作体后执行；生成和过期不会产生拾取事实。世界执行结果未知时停止后续推导，不重放奖励。接口与宿主进度见 [实现记录](engine-implementation.md#拾取物的逻辑协议)。

Minecraft 宿主目前用 `chorus:effect_entity` 承载一个私有逻辑单位，recipient 必须是同维度存活实体的 UUID，生成失败不返回实体身份。接触距离按收集者脚底计算，并检查方块视线；spectator、死亡或暂时无法解析的收集者不能拾取，也不改选其他玩家。基础半径、速度和寿命在生成时固定，吸附 Profile 每 tick 按收集者现有来源 / Buff 求值，直线运动受已加载地形阻挡。6 m/s 等测试速度仅验证执行侧单位，不宣称已经复刻离子痕迹的路径。

寿命按运行时逻辑时钟计算，暂时不 tick 不会重置剩余时间；实体重新 tick 时先判断到期，再判断接触。短命实体 noSave / noSummon，运行时关闭、替换或失败后丢弃，不跨重启或维度迁移。拾取先消费实体再执行动作体；故障后不重新生成或重试奖励。当前外观为原版物品渲染器的萤石粉占位，逻辑上不可捡入背包；私有只约束拾取资格，按收集者过滤客户端可见性尚未实现。Firesprite 首批内容已通过显式校准 Profile 与 grant_ability_energy 路由当前手雷账户，见 [D2 规则集](d2-ruleset.md#firesprite-与-ember-of-tempering)。暂未实现视觉合并、队友副本自动分发、公共抢占、弹药砖和其他 D2 拾取物的完整内容。


## 事实携带的 Buff 观察值

`event_has_buff`（buff、target 默认 victim、minimum 默认 1、match 默认 any）与 `event_has_buff_tag`（tag、target 默认 victim）读取事实的不可变观察值。它们不会重新查询当前 BuffStore。`event_buffs_available` 可先判断指定 target 是否确实被观察；未观察会报错，确认观察为空才表示没有 Buff，not 也不能把缺数据转成“不存在”的证据。match:bound 按当前绑定来源计算实例键；any 要求某一个实例独立达到 minimum，不跨来源相加。暂停实例保留存在性，已到期层不进入观察。

伤害适配器在**回执确认、该次 Buff 消费与派生事实执行之前**采样攻击者和受击者。此时物理伤害及护盾写入已经发生，因此它不是攻击进入前的完整状态快照。每份原版嵌套伤害分别采样；其后的 hit / damage_taken / shield / death / kill 共享该份观察，后来清理、授予、刷新或到期不会改写历史。纯核心 Action 在宿主尚未结算消费且没有提供观察时，从完成动作的状态补采样；已由宿主结算消费却没有观察的回执保持未知，不假造消费前值。

```json
{"type":"chorus:all","of":[
  {"type":"chorus:event_buffs_available","target":"victim"},
  {"type":"chorus:event_has_buff","buff":"example:marked","target":"victim","match":"any"}
]}
```

观察记录保留 holder、采样时刻、实例键 / generation、施加来源、定义 tags、层数、tier 与暂停标记；不复制组件、资源或完整世界。emit、原上下文的 after / 物理续体及派生 calculate / grant_energy 查询保留已有记录；改写查询 victim 不会自动获得该新目标的历史观察。新生成的资源 / 拾取等独立事实没有被自动赋予旧伤害观察。普通 has_buff / has_buff_tag 继续读当前状态。事件观察条件用于 on_use 数值快照时按已有事件值冻结，不推测将来命中的目标状态。


## 连续生命恢复的数值 Profile

`bundle.health_recovery[]` 可选 `profile`。未声明时沿用 rate 的直接值；声明后必须引用输入、输出均为 damage_per_second 的 Profile，缺失或单位不符在链接时拒绝。先在恢复来源作用域求 rate，再用受益者当前的来源 / Buff 修饰计算最终速率，之后才参与 channel 的优先级与速率选择、区间积分；负数或非有限结果不能成为治疗额度。

查询的 actor / victim 均为受益者，source 保留恢复来源，tags 为声明的 tags，numbers.recovery_rate 为原始速率，references.recovery_channel 为通道。查询不继承历史施加事件，不伪造事件 Buff 观察。来源变化前先积分旧区间，下一段使用新修饰；Profile 不改写治疗归因，不存储被取消或溢出的治疗。该入口可把 D2 HP/s 与 Minecraft HP/s 的校准从效果定义中分离，也能表达当前受益者的恢复倍率。护盾回充与离散 heal 仍使用各自既有入口。


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

位置与实体元数据共同随回执复制及 hit / death / kill、emit、after、派生查询传播。detached 动作可以在尸体和装备来源都不存在后用原位置发起新的范围查询，每次成员仍取当前世界。历史位置只决定坐标，不保证查询或生成成功：已有维度、区块加载、阵营等世界校验继续生效，不会跨维度生成或加载未知区块。没有位置的旧适配器须显式提供证据；新资源 / 拾取等独立事实不会自动借用旧位置。跨重启序列化仍待实现。

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
