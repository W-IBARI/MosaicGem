# MosaicGem

[English](README_EN.md) | [中文](README.md)

<p align="center"><img src="MosaicGem_Icon.png" width="256" alt="MosaicGem"></p>

Minecraft 服务器宝石镶嵌插件，支持**装备打孔、宝石镶嵌、宝石拆卸**，属性支持 **SX-Attribute（lore 合并）**、**原版属性（AttributeModifier）**、**附魔（原版附魔 / CrazyEnchantments 自定义附魔）** 与 **MythicMobs 技能/掉落**，适配 **Folia 26.2 / Java Edition 26.2**。

> 本插件使用 DeepSeek V4 Flash 开发：作者仅设计系统框架并负责功能测试与调优，不参与代码细节设计。

## 功能特性

- **装备打孔**：使用打孔器为装备添加孔位，成功率与双维度孔数上限可配置
- **宝石镶嵌**：宝石携带随机数值，`sx_attribute` 宝石合并进装备属性面板由 SX-Attribute 读取生效；`vanilla_attribute` 宝石直接附加到装备的原版属性修饰符；`enchant` 宝石直接附加/叠加到装备的附魔；`mythicmobs_skill` 宝石按配置的触发器（默认挥动）发动 MythicMobs 技能
- **宝石拆卸**：拆卸后宝石按原随机数值返还，装备属性面板自动还原；每颗宝石可独立配置 `remove-destroy-chance` 拆卸损毁概率（0-100%），与拆卸器成功率无关，拆卸成功后再独立判定，命中则宝石损毁不返还
- **属性面板合并**：物品原有 `攻击力：13.90` + 宝石 +20 → `攻击力：33.90（+20）`，宝石独有的属性自动新增行
- **三种交互方式**：铁砧合成、工作台/随身合成、拖拽工具到目标物品，均可独立开关
- **完整反馈**：所有被拦截或失败的操作都会提示玩家，文案按语言拆分到独立的 `messages/` 目录语言文件中配置
- **管理指令**：重载配置、发放物品、调试查看、列表、自检
- **格式保留**：镶嵌/打孔不会破坏物品原有 lore 的颜色、斜体、加粗等格式

## 环境要求

- 服务端：Folia 26.x（26.1 及以上，Java Edition 26.1+）
- Java：JDK 25+
- 可选依赖：[SX-Attribute-Folia（26.2 优化构建版）](https://github.com/W-IBARI/SX-Attribute-Folia-fixed)（`sx_attribute` 宝石属性生效需要，同时需要其前置 [SX-Item](https://github.com/Saukiya/SX-Item)）；[CrazyEnchantments](https://github.com/Crazy-Crew/CrazyEnchantments/)（`ce:` 前缀的自定义附魔宝石需要）；[MythicMobs](https://git.mythiccraft.io/mythiccraft/MythicMobs)（`mythicmobs_skill` 宝石与怪物掉落宝石需要）；[MythicCrucible](https://git.mythiccraft.io/mythiccraft/mythiccrucible)（`mythicmobs_skill` 宝石的多触发器支持需要，可选）

> 说明：原版 SX-Attribute 仓库尚未适配 26.2，因此 MosaicGem 推荐使用 [W-IBARI/SX-Attribute-Folia-fixed](https://github.com/W-IBARI/SX-Attribute-Folia-fixed) 提供的 26.2 优化构建版。
>
> MythicMobs 同样为软依赖：未安装时 `mythicmobs_skill` 宝石与自定义掉落不可用，其余功能不受影响。

## 安装

1. 构建插件（见下文「构建」），或使用已发布的 jar
2. 将 `MosaicGem-*.jar` 放入服务端 `plugins` 目录
3. 如需 `sx_attribute` 宝石属性生效，同时放入 `SX-Item` 与 `SX-Attribute`（26.2 优化构建版）的 jar
4. 启动服务端，插件会自动生成 `config.yml`、`messages/zh_cn.yml`、`messages/en_us.yml`、`items/gems.yml`、`items/punchers.yml`、`items/removers.yml`
5. 按需修改配置后执行 `/mosaicgem reload`

## 玩法机制

### 打孔

- 使用打孔器与目标装备交互（铁砧 / 工作台 / 拖拽）
- 打孔成功：装备孔数 +1，打孔器消耗 1 个
- 打孔失败：打孔器消耗 1 个，装备及其已镶嵌宝石不受影响
- `rate` 为百分比（0-100）
- 孔数上限为双维度，任一不满足都会被拦截并提示：
  - **全局上限**：装备所有来源的孔数之和不得超过 `settings.max-holes`
  - **来源上限**：同一类打孔器给一件装备贡献的孔数不得超过该打孔器的 `holesnum`

### 镶嵌

- 目标装备必须已有孔位，且已镶嵌数量未达到孔数上限
- 宝石生成时，按照配置的值生成，同时支持随机数配置：按 `random` 配置随机取值并固定到该宝石实例；批量发放时每颗宝石随机值独立
- 同一种宝石可按 `repetitions` 限制重复镶嵌次数（不填为无上限）
- 宝石可配置 `gemtype` 类型标签（字符串列表，可多个），配合 `config.yml` 的 `settings.gem-type-limit` 全局限制：一件装备上同一 `gemtype` 标签的宝石数量不得超过上限（如 `攻击: 2` 表示带"攻击"标签的宝石一件装备最多 2 颗）；标签未配置上限或 `gemtype` 缺省时不限制
- `buffType` 支持 `sx_attribute`（写入 lore 由 SX-Attribute 读取）、`vanilla_attribute`（附加原版属性修饰符）、`enchant`（附加/叠加附魔）与 `mythicmobs_skill`（按配置的触发器发动 MythicMobs 技能，默认挥动），其他类型会拦截镶嵌并提示
- `sx_attribute` 属性面板合并规则：
  - 物品原有属性行与所有宝石同类数值求和，显示为 `总值（+加成）`，如 `攻击力：33.90（+20）`
  - 宝石独有的属性自动追加新属性行
  - 加成数值的小数位：单颗宝石生效时去掉多余小数零（`+20`）；多颗宝石生效时取小数位最多的宝石的位数（`+40.00`）
  - 加成文字通过 `§X` 标记与数值隔离，SX-Attribute 只读取纯总值，不会把 `（+20）` 算进属性
- `vanilla_attribute` 原版属性合并规则：
  - 同属性的多颗宝石会合并为一个 AttributeModifier，tooltip 只显示一行总值（如两颗合计 +11 → `装备时：攻击力 +11`）
  - 物品原有的同属性 ADD_NUMBER 修饰符会一并合并进总值，显示在“原值上增加”
  - 被合并的原生修饰符会存入物品数据，宝石取下后自动还原
- `enchant` 附魔合并规则：
  - 目标装备已有该附魔时，最终等级 = 原等级 + 宝石合计（如原 `锋利 III` + 宝石 +2 → `锋利 V`）；没有则按宝石等级新建附魔
  - 多颗附魔宝石的同类附魔等级求和后一次性写入
  - 原生附魔等级会存入物品数据，宝石全部取下后自动还原为原始等级
  - 原版附魔 id 使用 `minecraft:` 前缀（如 `minecraft:sharpness`）；已安装 CrazyEnchantments 时还可使用 `ce:` 前缀（如 `ce:Wither`）镶嵌其自定义附魔
- `mythicmobs_skill` 技能宝石规则：
  - 配置条中的每一行声明一个 MythicMobs 技能名，格式参考 MythicCrucible 物品技能：`技能名 @触发器`（如 `TestSkill @onSwing`），不写触发器默认 `@onSwing`
  - 安装 [MythicCrucible](https://git.mythiccraft.io/mythiccraft/mythiccrucible) 时，触发完全交给 Crucible 的物品技能系统（`SWING` / `USE` / `RIGHTCLICK` 等触发器），插件只负责按宝石配置施放技能；冷却、条件、目标选择等由 MythicMobs 处理
  - 未安装 MythicCrucible 时回退到内置的近战攻击触发（仅 `@onSwing` / `@onAttack` / `@onHit` 类触发器生效）
  - 镶嵌信息中宝石的属性行直接显示技能名（如 `TestSkill`），不显示触发器后缀

### MythicCrucible 物品技能（mythicmobs_skill）

[MythicCrucible](https://git.mythiccraft.io/mythiccraft/mythiccrucible) 是 MythicMobs 的物品扩展，其物品技能格式为 `- skill:技能名 @触发器`。MosaicGem 的 `mythicmobs_skill` 宝石沿用类似格式：

```yaml
MM技能测试宝石:
  buffType: 'mythicmobs_skill'
  attribute:
    - 'TestSkill @onSwing'   # 挥动时施放 MythicMobs 技能：TestSkill
    - 'Heal @onUse'          # 右键使用时施放 Heal（需要 MythicCrucible）
```

- 常见触发器名（大小写不敏感，`on` 前缀可省略）：`onSwing` / `SWING`、`onUse` / `USE`、`onRightClick` / `RIGHTCLICK`、`onShoot` / `SHOOT`、`onJump` / `JUMP` 等
- 未安装 MythicCrucible 时仅 `onSwing`（及 `onAttack` / `onHit`）生效，其余触发器会被忽略

### MythicMobs 怪物掉落宝石

安装 MythicMobs 后，可在怪物的 `Drops` 中使用自定义掉落类型 `mosaicgem` 掉落插件宝石：

```yaml
Drops:
- mosaicgem:附魔测试宝石 1 0.5
- mosaicgem{id=SA测试宝石} 1 0.2
```

- `mosaicgem` 后的参数为宝石内部名（与 `/mosaicgem give` 使用的 id 一致），也可用 `{id=宝石名}` 或 `{gem=宝石名}` 指定
- 掉落数量与概率沿用 MythicMobs 的掉落语法（数量、概率列）
- 掉落的宝石会按宝石配置随机生成数值（如宝石本身配置了随机生成数值的部分）

### 拆卸

- 拆卸器与已镶嵌宝石的装备交互，成功后移除最后一个镶嵌的宝石
- 宝石按原始随机数值原样返还（优先进背包，背包满则掉落脚边）
- 装备属性面板自动还原：`sx_attribute` 原有属性行恢复原始数值、宝石新增行整行移除；`vanilla_attribute` 修饰符按剩余宝石重建，被合并的原生修饰符自动还原；`enchant` 附魔按剩余宝石重建，原生附魔等级自动还原
- 失败时拆卸器消耗 1 个，装备与剩余宝石不受影响
- **宝石损毁判定**：每颗宝石可配置 `remove-destroy-chance`（0-100，百分比，缺省 0）。该判定**与拆卸器成功率无关**——拆卸器判定成功后才独立进行；命中时宝石损毁（消失、不返还），装备正常还原，玩家收到 `remove-destroyed` 提示；未命中则正常返还

### 工具消耗与堆叠

- 一次行为只消耗 1 个工具，剩余工具继续留在光标/原槽位，可连续操作

### 交互方式

| 方式 | 操作 |
| --- | --- |
| 铁砧 | 左侧放装备、右侧放工具（或反过来），结果槽预览，点击结果完成操作 |
| 合成 | 在 2x2 随身合成或工作台中放入「1 个工具 + 1 个目标装备」，点击结果 |
| 拖拽 | 鼠标左键拾取工具吸附到光标，再点击背包中的目标装备 |

三种方式均可在 `config.yml` 的 `settings.interactions` 中独立开关。

## 指令与权限

| 指令 | 说明 | 默认权限节点（可在 permissions.yml 修改） |
| --- | --- | --- |
| `/mosaicgem reload` | 重载全部配置文件（缺失的 yml 自动补齐） | `mosaicgem.reload`（默认 OP） |
| `/mosaicgem give <id> [数量] [玩家]` | 给予宝石/打孔器/拆卸器（自动匹配类型，不区分） | `mosaicgem.give`（默认 OP） |
| `/mosaicgem debug [玩家]` | 查看物品的孔数、宝石、属性行等调试信息 | `mosaicgem.debug`（默认 OP） |
| `/mosaicgem list <gem\|puncher\|remover>` | 列出已配置的物品 | `mosaicgem.list`（默认 OP） |
| `/mosaicgem selftest` | 无玩家环境自检：配置解析、物品生成、数据读写、属性合并 | `mosaicgem.debug`（默认 OP） |

指令别名：`/mg`、`/mgem`。

## 配置文件

配置文件位于 `plugins/MosaicGem/`，首次启动自动生成，修改后执行 `/mosaicgem reload` 热重载。

### config.yml

```yaml
settings:
  language: zh_cn          # 消息语言：zh_cn（简体中文）/ en_us（English）
  max-holes: 6            # 全局孔数硬上限（所有来源孔数之和）
  gem-type-limit:         # gemtype 标签全局数量限制（key=标签名, value=上限；0/缺省=不限制）
    攻击: 2               # 带"攻击"标签的宝石一件装备最多 2 颗
  interactions:
    anvil: true           # 铁砧合成
    crafting: true        # 工作台/随身合成
    drag: true            # 拖拽工具到目标物品
```

### permissions.yml：指令权限

根目录的 `permissions.yml` 用于自定义每个指令所需的权限节点，不再硬编码：

```yaml
commands:
  reload:
    default-level: op
    permissions:
      - mosaicgem.reload
  give:
    default-level: op
    permissions:
      - mosaicgem.give
  debug:
    default-level: op
    permissions:
      - mosaicgem.debug
  list:
    default-level: op
    permissions:
      - mosaicgem.list
  selftest:
    default-level: op
    permissions:
      - mosaicgem.debug
```

- 每个指令可配置多个节点，玩家满足任意一个即可使用
- `default-level` 为该指令的默认权限级（玩家未获得任何权限节点时的判定），可选值：
  - `op`：仅 OP 或以上（**默认**，全部指令默认均为 OP）
  - `true`：所有玩家
  - `false`：无默认权限，只能通过权限插件授予
  - `not-op`：非 OP 玩家
- `permissions: []`（空列表）表示不需要任何特定权限节点，仅按 `default-level` 判定
- 未在文件中配置的指令回退到内置默认节点与默认权限级（`op`）
- 使用 LuckPerms 等权限插件时无需任何额外适配：直接给玩家/用户组分配这里配置的节点即可覆盖默认权限级（如 `/lp user <玩家> permission set mosaicgem.reload true`），Bukkit 的标准权限系统会自动交给 LuckPerms 判定

#### socket-lore：装备上的镶嵌信息（所有 buffType 共用）

打孔/镶嵌/拆卸成功后自动更新，展示孔位与宝石：

```yaml
socket-lore:
  enabled: true
  lines:
    - ''
    - '&r&f[ &6镶嵌信息 &f]'
    - '&r&7孔位: &f{holes}&7/&f{max_holes}'
  gem-lines:
    - '&r&7宝石{index}: &f{gem}'
    - '&r&7  {value_lines}'
  empty-line: '&r&7暂无宝石'
```

占位符：

| 占位符 | 说明 |
| --- | --- |
| `{holes}` | 已经镶嵌的孔洞数（孔内宝石数） |
| `{max_holes}` | 物品可用的孔洞数（已打孔数） |
| `{gem_count}` | 已镶嵌宝石数量 |
| `{index}` | 宝石序号（从 1 开始） |
| `{gem}` | 宝石显示名 |
| `{id}` | 宝石内部名 |
| `{values}` | 宝石数值描述（单行合并，如 `攻击力：20.00、防御力：20.00`） |
| `{value_lines}` | 宝石数值描述（每个属性单独一行） |

#### attribute-lore：属性面板合并（与宝石 buff 类型无关）

把宝石的属性合并进装备 lore 属性行：属性名由各宝石配置自己的 `LoreChange` 映射提供（标识符 → 属性名），数值取该属性行中的数值，按「值 + 加成」规则合并（同属性求和、显示总值与加成），与 `buffType` 无关（`sx_attribute` / `vanilla_attribute` / `enchant` 等均适用）。

```yaml
attribute-lore:
  enabled: true
  new-line: '&r&f{name}：&e{value}'  # 宝石独有属性的新增行模版
  bonus-format: '&r（+{bonus}）'     # 加成标注格式
```

占位符：`{name}` 属性名、`{value}` 属性总值、`{bonus}` 宝石加成值。

> 默认模板均带 `&r`（正体）。如需自定义颜色，可直接在模板中加入颜色代码（`&`、`§x` 十六进制或 `<#RRGGBB>`），`<#RRGGBB>` 会被解析为真实颜色，例如 `&r<#FFAA00>（<#1EFF5C>+{bonus}<#FFAA00>）`。

### 多语言消息文件

所有玩家消息按语言拆分：`config.yml` 的 `settings.language` 决定加载 `messages/` 目录下的哪个文件（如 `zh_cn` 对应 `messages/zh_cn.yml`），`{xxx}` 为占位符。内置语言：

- `messages/zh_cn.yml`：简体中文
- `messages/en_us.yml`：English（英文）

语言值不区分大小写，`-` 与 `_` 等价（如 `en-US` 与 `en_us` 均可）。若指定语言的文件不存在，会自动回退到中文文件；旧版 `messages.yml` 在中文语言下仍会被优先读取，用于保留已自定义的文案。

语言文件里还有两段名称映射表：

- `attribute-names`：把原版属性 id（如 `minecraft:attack_damage`）映射成玩家可见的显示名（如 `攻击力`），`vanilla_attribute` 宝石的镶嵌信息 lore 使用
- `enchant-names`：把附魔 id（如 `minecraft:sharpness` → `锋利`、`ce:Wither` → `凋灵`）映射成玩家可见的显示名，`enchant` 宝石的镶嵌信息 lore 使用；未配置时原版附魔回退显示原始 id，CrazyEnchantments 附魔回退显示其 CustomName

| 消息键 | 场景 |
| --- | --- |
| `punch-max-global` | 打孔：全局孔数已满 |
| `punch-max-source` | 打孔：打孔器来源上限已满 |
| `punch-fail` | 打孔：成功率失败（工具消耗） |
| `socket-no-hole` | 镶嵌：装备没有孔位 |
| `socket-full` | 镶嵌：孔位已满 |
| `socket-repeat-limit` | 镶嵌：达到重复镶嵌次数上限 |
| `socket-gemtype-limit` | 镶嵌：同 gemtype 标签宝石达到全局上限（settings.gem-type-limit） |
| `socket-bufftype-unsupported` | 镶嵌：buffType 不受支持 |
| `remove-empty` | 拆卸：没有已镶嵌宝石 |
| `remove-fail` | 拆卸：成功率失败（工具消耗） |
| `remove-destroyed` | 拆卸：拆卸成功但宝石损毁判定命中（宝石消失，不返还） |
| `target-invalid` | 目标类型/材质不符合限制 |
| `interaction-disabled` | 该交互方式被禁用 |
| `tool-config-missing` | 工具在配置中不存在 |
| `invalid-combination` | 工具+工具或无效组合 |
| `inventory-full` | 返还宝石时背包已满 |

另有成功/指令/帮助/调试/自检类消息：`punch-success`、`socket-success`、`remove-success`、`reload-success`、`give-*`、`player-not-found`、`no-permission`、`help-*`、`debug-*`、`selftest-*` 等，完整键值见文件内注释。

### items/ 目录（物品配置）

`plugins/MosaicGem/items/` 下的任意 `.yml`（含子目录）都会被尝试识别为物品配置。文件顶层使用类型段声明物品类型，多个类型段可共存于同一文件，也可分散在任意多个文件中：

```yaml
gems:
  # 宝石配置……
punchers:
  # 打孔器配置……
removers:
  # 拆卸器配置……
```

- 类型段：`gems:`（宝石）、`punchers:`（打孔器）、`removers:`（拆卸器）
- 旧版扁平格式（`gems.yml` / `punchers.yml` / `removers.yml` 顶层直接写物品 id）仍然兼容
- 同一 id 在多个文件中重复时，后加载的覆盖先前的（启动/重载日志会提示）

### items/gems.yml

首次启动时 `items/gems.yml` 会按已安装的软依赖自动生成（使用 `gems:` 段），避免展示用不到的示例宝石：

- 始终生成：`原版测试宝石`（`vanilla_attribute`）、`附魔测试宝石`（`enchant`）
- 已安装 SX-Attribute：额外生成 `SA测试宝石`（`sx_attribute`）
- 已安装 MythicMobs：额外生成 `MM技能测试宝石`（`mythicmobs_skill`）

**生成规则**：只要 `items/` 目录（含子目录）下已存在**任何** `.yml` 文件（无论内容是否合法），都不再生成任何默认物品配置文件（`gems.yml` / `punchers.yml` / `removers.yml`），避免覆盖自定义配置；之后新安装软依赖时，删除这些默认文件并执行 `/mosaicgem reload` 即可重新生成。以下是所有软依赖齐全时的生成示例：

```yaml
gems:
  SA测试宝石:
    material: PAPER
    isEnchant: true
    name: "&cSA测试宝石"
    lore:
      - '&a▪ 伤害增加：${random_value}'
    custom-model-data: 0
    targetType:
      - SWORD
    targetMaterial:
      - IRON_SWORD
    repetitions: 5
    gemtype:                  # 类型标签（可多个），配合 settings.gem-type-limit 限制数量
      - 攻击
    remove-destroy-chance: 0    # 拆卸损毁概率 0-100%（缺省 0 = 永不损毁；与拆卸器成功率无关）
    random:
      random_value: '10.00~20.00'
    buffType: 'sx_attribute'
    attribute:
      - '1: 攻击力: ${random_value}'   # 行首数字为标识符，与 LoreChange 中的键对应
      - '2: 防御力: ${random_value}'
    LoreChange:                        # 标识符 → 属性名映射；无映射的属性不动作
      - 1: 攻击力
      - 2: 防御力

  原版测试宝石:
    material: PAPER
    isEnchant: true
    name: "&b原版测试宝石"
    lore:
      - '&a▪ 攻击伤害：${random_value}'
    custom-model-data: 0
    targetType:
      - SWORD
    targetMaterial:
      - IRON_SWORD
    repetitions: 5
    random:
      random_value: '5.00~10.00'
    buffType: 'vanilla_attribute'
    attribute:
      - 'minecraft:attack_damage: ${random_value}'
      - 'minecraft:attack_speed: 2'

  附魔测试宝石:
    material: PAPER
    isEnchant: true
    name: "&d附魔测试宝石"
    lore:
      - '&a▪ 锋利等级：${random_value}'
    custom-model-data: 0
    targetType:
      - SWORD
    targetMaterial:
      - IRON_SWORD
    repetitions: 5
    random:
      random_value: '1~5'
    buffType: 'enchant'
    attribute:
      - 'minecraft:sharpness: ${random_value}'
      - 'minecraft:unbreaking: 1'

  MM技能测试宝石:
    material: PAPER
    isEnchant: true
    name: "&eMM技能测试宝石"
    lore:
      - '&a▪ 技能: TestSkill'
    custom-model-data: 0
    targetType:
      - SWORD
    targetMaterial:
      - IRON_SWORD
    repetitions: 5
    buffType: 'mythicmobs_skill'
    attribute:
      - 'TestSkill @onSwing'
```

- `random` 支持多个随机数，格式 `最小值~最大值`，小数位数按配置自动保留；生成后数值固定到该宝石实例
- `gemtype`：宝石类型标签（字符串列表，可多个，如 `['攻击', '火属性']`）。配合 `config.yml` 的 `settings.gem-type-limit` 限制一件装备上同一标签宝石的数量上限；不填则为空列表，不参与类型计数
- `remove-destroy-chance`：拆卸时宝石损毁（消失）概率，0~100 对应 0%~100%（缺省 0 = 永不损毁；越界自动钳制）。**与拆卸器成功率无关**：拆卸器判定成功后仍需独立过此判定，命中则宝石不返还
- `attribute`：注入的属性行。行首数字为**标识符**（如 `1:`、`2:`），属性由宝石自己的 `LoreChange` 段声明映射（标识符 → 属性名），无映射的属性「不动作」；数值取对应属性行中的数值
- `LoreChange`：属性面板合并且与 buff 类型无关——`attribute-lore` 段读取各宝石该映射，把对应属性的数值按「值 + 加成」规则合并进装备 lore（同属性求和、显示总值与加成）；`sx_attribute` / `vanilla_attribute` / `enchant` 等均适用
- `sx_attribute`：属性行写进装备 lore，由 SX-Attribute 读取
- `vanilla_attribute`：属性行直接附加为原版属性修饰符（同属性多宝石合并为一个修饰符，物品原生同属性修饰符合并进总值），**不会**修改/覆盖装备 lore
- `enchant`：属性行直接附加/叠加到装备附魔（已存在则原等级 + 宝石等级，不存在则新建；多宝石同类附魔求和；原生附魔等级存物品数据，取下自动还原）
- `mythicmobs_skill`：属性行是 MythicMobs 技能名（支持 `技能名 @触发器` 的 MythicCrucible 格式）；安装 MythicCrucible 时由其物品技能系统触发，否则回退到近战攻击触发；镶嵌信息直接显示技能名
- 原版属性 id 的显示名在语言文件 `attribute-names` 段配置；附魔 id 的显示名在 `enchant-names` 段配置，未配置时显示原始 id（CrazyEnchantments 附魔回退显示其 CustomName）
- CrazyEnchantments 自定义附魔格式：`ce:附魔名: 等级`（如 `ce:Wither: 2`），需要服务器已安装 CrazyEnchantments；原版附魔格式：`minecraft:sharpness: 等级`，裸 id（如 `sharpness`）会自动补 `minecraft:` 前缀
- `targetMaterial` 与 `targetType` 同时配置时需**同时满足**才可操作
- 支持的装备类型：`SWORD`、`SPEAR`、`TRIDENT`、`AXE`、`HOE`、`SHOVEL`、`PICKAXE`、`BOW`、`CROSSBOW`、`MACE`、`SHIELD`、`HELMET`、`CHESTPLATE`、`LEGGINGS`、`BOOTS`、`ELYTRA`

### items/punchers.yml

```yaml
punchers:
  测试打孔器:
    material: PAPER
    isEnchant: true
    name: "&c测试打孔器"
    lore:
      - '我是lore描述'
    custom-model-data: 0
    targetType:
      - SWORD
    targetMaterial:
      - IRON_SWORD
    rate: 10                     # 成功率（0-100，百分比）
    holesnum: 2                  # 该类打孔器给同一物品的孔数上限
```

### items/removers.yml

```yaml
removers:
  测试拆卸器:
    material: PAPER
    isEnchant: true
    name: "&c测试拆卸器"
    lore:
      - '我是lore描述'
    custom-model-data: 0
    targetType:
      - SWORD
    targetMaterial:
      - IRON_SWORD
    rate: 10                     # 成功率（0-100，百分比）
```

## 数据存储与属性计算

- 工具物品（宝石/打孔器/拆卸器）通过物品的 custom data 组件（PersistentDataContainer）标记类型与内部名
- 宝石实例生成时固定随机数值并写入组件数据
- 装备记录：总孔数、各来源打孔器的孔数、已镶嵌宝石列表（实例 UUID、随机数值）、属性合并前的原始属性行
- 原版属性：被合并进宝石修饰符的物品原生修饰符会存入物品 PDC，宝石取下后用于还原
- 附魔：物品的原生附魔等级（原版 + CrazyEnchantments）会存入物品 PDC，宝石取下后按原生等级还原
- 属性合并行与镶嵌信息行使用 `§X` 标记：
  - 合并行中 `§X` 之前的数值由 SX-Attribute 读取，之后的 `（+加成）` 不影响计算
  - 镶嵌信息行以 `§X` 开头，SX-Attribute 整行忽略，仅作展示
- 物品原有 lore 中的 `§r`、`§x` 十六进制色、斜体/加粗等按字面文本原样写回，不会丢失

## 构建

```powershell
.\gradlew.bat build
```

构建产物：`build/libs/MosaicGem-1.0.2.jar`

已发布版本可从 [GitHub Releases](https://github.com/W-IBARI/MosaicGem/releases) 直接下载 jar（含历史版本）。

开发环境：JDK 25、Gradle 9.6.1（项目自带 Wrapper）、`dev.folia:folia-api:26.1.2.build.8-stable`（针对 26.x 最低 stable 编译以兼容全部 26.x）。

## 常见问题

**Q：属性不生效？**

确认 `SX-Item` 与 `SX-Attribute` 已安装并启用，宝石的 `buffType` 为 `sx_attribute`，且装备属性面板中存在合并后的属性行；`vanilla_attribute` 宝石则检查物品属性面板中是否存在对应原版属性。

**Q：`ce:` 附魔宝石不生效？**

确认服务器已安装并启用 [CrazyEnchantments](https://github.com/Crazy-Crew/CrazyEnchantments/)，且 `ce:` 后面的附魔名与 CrazyEnchantments 配置中的附魔名完全一致（如 `ce:Wither`）；MosaicGem 通过运行时桥接调用其 API，无需额外依赖。

**Q：MythicMobs 怪物不掉插件宝石？**

确认 MythicMobs 已安装并先于 MosaicGem 加载（插件声明了软依赖）；在怪物或掉落表中使用 `mosaicgem:宝石内部名` 作为掉落，并执行 `/mm reload`（或重启服务器）让掉落重新解析。MosaicGem 启动时会自动触发一次掉落/怪物配置重载以便注册自定义掉落。

**Q：`mythicmobs_skill` 宝石技能不触发？**

确认宝石的 `buffType` 为 `mythicmobs_skill`、`attribute` 中的技能名与 MythicMobs 中配置的技能完全一致，且该装备已镶嵌；安装 MythicCrucible 时由它的物品技能系统按 `@触发器` 触发（默认 `@onSwing`），未安装时仅近战攻击触发（`@onSwing` / `@onAttack` / `@onHit`）；技能冷却、条件、目标选择等由 MythicMobs 自行处理。

**Q：如何排查配置问题？**

执行 `/mosaicgem selftest` 自检配置解析、物品生成、数据读写与属性合并；执行 `/mosaicgem debug` 查看手持物品的组件数据。

## 开源许可

本插件基于 [GNU LGPL v3](https://www.gnu.org/licenses/lgpl-3.0.html) 开源许可发布（完整条款见仓库内 [LICENSE](LICENSE) 文件）。

允许任何人在遵守 LGPL-3.0 条款的前提下使用、修改、分发本插件；修改后对外分发时需保留本许可声明，并以相同许可开源。
