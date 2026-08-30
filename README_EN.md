# MosaicGem

[English](README_EN.md) | [中文](README.md)

<p align="center"><img src="MosaicGem_Icon.png" width="256" alt="MosaicGem"></p>

A Minecraft server gem socketing plugin. It supports **equipment punching (adding sockets), gem socketing, and gem removal**, with attribute support for **SX-Attribute (lore merging)**, **vanilla attributes (AttributeModifier)**, **enchantments (vanilla / CrazyEnchantments custom enchants)** and **MythicMobs skills/drops**. Built for **Folia 26.2 / Java Edition 26.2**.

> This plugin was developed using DeepSeek V4 Flash: the author designs the system framework and is responsible for feature testing and tuning, and does not participate in code-level design.

## Features

- **Equipment punching**: use a puncher to add sockets to equipment; success rate and two-dimensional socket limits are configurable
- **Gem socketing**: gems carry random values, and **one gem can carry multiple buff kinds at once (`buffs` entries, v1.2+)**; `sx_attribute` gems merge into the equipment attribute panel and are read by SX-Attribute; `vanilla_attribute` gems apply directly as vanilla attribute modifiers; `ce_attribute` gems write CraftEngine custom-attribute persistent item modifiers (CE 26.8+); `enchant` gems add/stack vanilla enchantments; `mythicmobs_skill` gems cast MythicMobs skills on the configured trigger (swing by default)
- **Gem removal**: removed gems are returned with their original random values, and the equipment attribute panel is restored automatically; each gem can set its own `remove-destroy-chance` (0-100%) — independent of the remover's success rate, rolled after a successful removal, and on a hit the gem is destroyed and not returned
- **Attribute panel merging**: an existing `攻击力：13.90` line plus a +20 gem becomes `攻击力：33.90（+20）`; attributes that only exist on the gem are appended as new lines
- **Three interaction methods**: anvil, crafting (2x2 inventory or workbench), and dragging a tool onto the target item — each can be toggled independently
- **Full player feedback**: every blocked or failed interaction notifies the player; messages are split into per-language files under `messages/`
- **Admin commands**: reload, give, debug, list, and self-test
- **Format preservation**: socketing/punching does not break existing lore colors, italics, bold, or other formatting

## Requirements

- Server: Folia 26.x (26.1+, Java Edition 26.1+)
- Java: JDK 25+
- Optional dependencies: [SX-Attribute-Folia (26.2 optimized build)](https://github.com/W-IBARI/SX-Attribute-Folia-fixed) (required for `sx_attribute` gems, plus its dependency [SX-Item](https://github.com/Saukiya/SX-Item)); [CrazyEnchantments](https://github.com/Crazy-Crew/CrazyEnchantments/) (required for `ce:` custom enchants); [MythicMobs](https://git.mythiccraft.io/mythiccraft/MythicMobs) (required for `mythicmobs_skill` gems and gem drops); [MythicCrucible](https://git.mythiccraft.io/mythiccraft/mythiccrucible) (optional, enables multiple skill triggers for `mythicmobs_skill` gems); [CraftEngine](https://github.com/Xiao-MoMi/craft-engine) 26.8+ (required for `ce_attribute` gems, writes into CE custom attributes)

> The original SX-Attribute repository does not support 26.2 yet, so MosaicGem recommends the [26.2 optimized build](https://github.com/W-IBARI/SX-Attribute-Folia-fixed) provided by W-IBARI/SX-Attribute-Folia-fixed.
>
> MythicMobs is also a soft dependency: without it, `mythicmobs_skill` gems and custom gem drops are unavailable; everything else keeps working.

## Installation

1. Build the plugin (see "Building" below) or use a released jar
2. Put `MosaicGem-*.jar` into the server's `plugins` folder
3. For `sx_attribute` gems, also install `SX-Item` and `SX-Attribute` (26.2 optimized build)
4. Start the server. The plugin generates `config.yml`, `messages/zh_cn.yml`, `messages/en_us.yml`, `items/gems.yml`, `items/punchers.yml`, and `items/removers.yml` automatically
5. Edit the configuration as needed and run `/mosaicgem reload`

## Gameplay

### Punching

- Interact with a puncher and the target equipment (anvil / crafting / drag)
- Success: equipment sockets +1, one puncher is consumed
- Failure: one puncher is consumed; the equipment and socketed gems are unaffected
- `rate` is a percentage (0-100)
- Socket limits are resolved in **three layers** per item, from highest priority down (any hit blocks the operation with a message):
  - **Item id limit** (overrides type & global): `settings.max-holes-by-id` keyed by the **uppercase material name** (e.g. `IRON_SWORD`, `SHIELD`); takes effect on match
  - **Item type limit** (overrides global): `settings.max-holes-by-type` keyed by **item type** (same semantics as puncher `targetType`, e.g. `SWORD`/`HELMET`/`CROSSBOW`, case-insensitive); used when the id layer misses
  - **Global limit** (fallback): total sockets from all sources cannot exceed `settings.max-holes`; a value of `0` means the type/item cannot be punched
  - **Source limit**: one puncher type cannot contribute more sockets than its `holesnum`

#### Configuration example (`settings` section)

```yaml
settings:
  max-holes: 6            # ① Global (fallback)
  max-holes-by-type:      # ② Item type (overrides ①)
    SWORD: 8
    HELMET: 4
  max-holes-by-id:        # ③ Item id (uppercase material, overrides ①②)
    IRON_SWORD: 10
    SHIELD: 1
```

Resolution order: `③ item id` → `② item type` → `① global`; unconfigured keys fall back to the global value.

### Socketing

- The target must already have sockets, and the socketed count must be below the socket limit
- Gems roll random values at creation time from `random` and keep them for that instance; each gem in a bulk give is independent
- The same gem can be socketed multiple times according to `repetitions` (unlimited if omitted)
- Gems can set `gemtype` type tags (a string list, multiple allowed); combined with `settings.gem-type-limit` in `config.yml`, it limits how many gems of the same `gemtype` tag can be socketed on one item (e.g. `攻击: 2` = at most 2 gems tagged "攻击" per item). Tags without a configured limit, or gems without `gemtype`, are not limited
- `buffs` (v1.2+): a **list of buff entries** — one gem can carry multiple buff kinds at once; each entry = `type` (buff kind) + `attribute` (lines for that kind, same format as before). Legacy `buffType + attribute` is auto-converted to a single entry (backward compatible; `buffs` wins when both are present). `type` supports `sx_attribute` (lore read by SX-Attribute), `vanilla_attribute` (vanilla attribute modifiers), `ce_attribute` (CraftEngine custom-attribute persistent item modifiers, CE 26.8+), `enchant` (add/stack enchantments) and `mythicmobs_skill` (cast MythicMobs skills on the configured trigger, swing by default); other types are blocked with a message
- `sx_attribute` merging rules:
  - The item's existing attribute line and all gems of the same attribute are summed and shown as `total（+bonus）`, e.g. `攻击力：33.90（+20）`
  - Attributes that only exist on gems are appended as new lines
  - Decimal places: a single gem strips trailing zeros (`+20`); multiple gems use the largest decimal count among them (`+40.00`)
  - The bonus text is isolated by the `§X` marker so SX-Attribute only reads the pure total and never counts `（+20）`
- `vanilla_attribute` merging rules:
  - Multiple gems of the same attribute merge into one AttributeModifier; the tooltip shows a single total (e.g. two gems totaling +11 → `装备时：攻击力 +11`)
  - The item's existing ADD_NUMBER modifiers of the same attribute are merged into the total and shown as an increase over the original value
  - Merged native modifiers are stored in the item data and restored automatically when gems are removed
- `ce_attribute` merging rules (CraftEngine 26.8+):
  - Attribute lines use `CE attribute id: value` (e.g. `bakamc:strength: ${random_value}`); values support `random` rolls
  - Each gem attribute is written as one persistent CE item modifier (`craftengine:attribute_modifiers`), attached to the item only (scope fixed `weapon`: melee reads main hand, arrows read the weapon at firing time)
  - Same-attribute gems sum via add_value and merge with the weapon's own words inside CE's attribute pipeline; on removal, modifiers are rebuilt from the remaining gems
  - Requires CraftEngine 26.8+; without it the socketing still works but the attributes won't take effect
- `enchant` stacking rules:
  - If the target already has the enchant, the final level = original level + gem total (e.g. `锋利 III` + +2 → `锋利 V`); otherwise the enchant is created at the gem level
  - Multiple gems of the same enchant are summed and written once
  - Native enchant levels are stored in the item data and restored when all gems are removed
  - Vanilla enchant ids use the `minecraft:` prefix (e.g. `minecraft:sharpness`); with CrazyEnchantments installed, `ce:` ids (e.g. `ce:Wither`) can also be socketed
- `mythicmobs_skill` rules:
  - Each attribute line declares a MythicMobs skill name using the MythicCrucible item-skill format: `技能名 @触发器` (e.g. `TestSkill @onSwing`); the default trigger is `@onSwing`
  - With [MythicCrucible](https://git.mythiccraft.io/mythiccraft/mythiccrucible) installed, triggers are fully handled by Crucible's item-skill system (`SWING` / `USE` / `RIGHTCLICK` etc.); the plugin only casts the skill from the gem config. Cooldowns, conditions, and target selection are handled by MythicMobs
  - Without MythicCrucible, it falls back to the built-in melee-attack trigger (only `@onSwing` / `@onAttack` / `@onHit` work)
  - The socket info shows the skill name (e.g. `TestSkill`) without the trigger suffix

### MythicCrucible item skills (`mythicmobs_skill`)

[MythicCrucible](https://git.mythiccraft.io/mythiccraft/mythiccrucible) is MythicMobs' item extension; its item-skill format is `- skill:技能名 @触发器`. MosaicGem's `mythicmobs_skill` gems use a similar format:

```yaml
MM技能测试宝石:
  buffType: 'mythicmobs_skill'
  attribute:
    - 'TestSkill @onSwing'   # Casts MythicMobs skill TestSkill on swing
    - 'Heal @onUse'          # Casts Heal on right-click use (requires MythicCrucible)
```

- Common trigger names (case-insensitive, `on` prefix optional): `onSwing` / `SWING`, `onUse` / `USE`, `onRightClick` / `RIGHTCLICK`, `onShoot` / `SHOOT`, `onJump` / `JUMP`, etc.
- Without MythicCrucible, only `onSwing` (plus `onAttack` / `onHit`) works; other triggers are ignored

### MythicMobs gem drops

With MythicMobs installed, use the custom drop type `mosaicgem` in a mob's `Drops` to drop plugin gems:

```yaml
Drops:
- mosaicgem:附魔测试宝石 1 0.5
- mosaicgem{id=SA测试宝石} 1 0.2
```

- The argument after `mosaicgem` is the gem's internal id (the same id used by `/mosaicgem give`); `{id=宝石名}` or `{gem=宝石名}` also works
- Drop amount and chance follow MythicMobs' normal drop syntax (amount and chance columns)
- Dropped gems are rolled according to the gem config (if the gem has random values)

### Removal

- Interact with a remover on equipment that has socketed gems; success removes the last socketed gem
- The gem is returned with its original random values (inventory first, dropped on the ground if the inventory is full)
- The attribute panel is restored: `sx_attribute` lines return to their original values and gem-only lines are removed; `vanilla_attribute` modifiers are rebuilt from the remaining gems and merged natives are restored; `ce_attribute` modifiers are rebuilt from the remaining gems (CE persistent item modifiers); `enchant` levels are rebuilt and native levels restored
- Failure consumes one remover; the equipment and remaining gems are unaffected
- **Gem destroy check**: each gem can set `remove-destroy-chance` (0-100, percent, default 0). This check is **independent of the remover's success rate** — it is rolled only after the remover succeeds; on a hit the gem is destroyed (lost, not returned) while the equipment is restored normally and the player sees the `remove-destroyed` message; on a miss the gem is returned normally

### Tool consumption and stacking

- One action consumes one tool; the rest stays on the cursor/in the slot for continued use
- Clicking another tool while holding a tool on the cursor is left to vanilla (merge/swap) and is not intercepted
- Creative and survival modes behave identically

### Interaction methods

| Method | Action |
| --- | --- |
| Anvil | Put equipment on one side and the tool on the other (either order), preview in the result slot, click the result to finish |
| Crafting | Place 1 tool + 1 target equipment in the 2x2 inventory or workbench grid, click the result |
| Drag | Left-click the tool to pick it up, then click the target equipment in the inventory |

All three methods can be toggled in `config.yml` under `settings.interactions`.

## Commands & Permissions

| Command | Description | Default permission node (editable in permissions.yml) |
| --- | --- | --- |
| `/mosaicgem reload` | Reload all config files (missing yml files are re-created) | `mosaicgem.reload` (default OP) |
| `/mosaicgem give <id> [amount] [player]` | Give gems/punchers/removers (type is auto-detected) | `mosaicgem.give` (default OP) |
| `/mosaicgem debug [player]` | Inspect sockets, gems, attribute lines, etc. | `mosaicgem.debug` (default OP) |
| `/mosaicgem list <gem\|puncher\|remover>` | List configured items | `mosaicgem.list` (default OP) |
| `/mosaicgem selftest` | Environment-free self-test: config parsing, item generation, data read/write, attribute merging | `mosaicgem.debug` (default OP) |
| `/mosaicgem lore` | Toggle the gem detail lines of the held socketed item (per-gem value rows in the socket info; main entries stay; state persists on the item) | `mosaicgem.lore` (default true, all players) |

Aliases: `/mg`, `/mgem`.

## Configuration Files

Config files live in `plugins/MosaicGem/` and are generated on first startup; edit them and run `/mosaicgem reload` to hot-reload.

### config.yml

```yaml
settings:
  language: zh_cn          # message language: zh_cn / en_us
  max-holes: 6             # global socket hard limit (sum of all sources)
  gem-type-limit:          # global per-gemtype limit (key=tag name, value=max; 0/omitted = unlimited)
    攻击: 2                # at most 2 gems tagged "攻击" per item
  interactions:
    anvil: true            # anvil crafting
    crafting: true         # workbench / 2x2 crafting
    drag: true             # drag tool onto target item
```

### permissions.yml: command permissions

`permissions.yml` in the plugin root defines the permission nodes required by each command (no more hardcoding):

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

- Each command accepts multiple nodes; any one of them grants access
- `default-level` is the fallback when the player has no granted node:
  - `op`: OP or above only (**default**; every command defaults to OP)
  - `true`: all players
  - `false`: no default; must be granted by a permission plugin
  - `not-op`: non-OP players
- `permissions: []` (empty list) means no specific node is required; only `default-level` applies
- Commands not listed in the file fall back to the built-in defaults (`op`)
- Permission plugins such as LuckPerms work out of the box: assign the configured nodes to players/groups (e.g. `/lp user <player> permission set mosaicgem.reload true`); Bukkit's standard permission system hands the check to LuckPerms

#### socket-lore: socket info on equipment (shared by all buffTypes)

Updated automatically after punching/socketing/removing:

```yaml
socket-lore:
  enabled: true
  lines:
    - ''
    - '&r&f[ &6Socket Info &f]'
    - '&r&7Sockets: &f{holes}&7/&f{max_holes}'
  gem-lines:
    - '&r&7Gem {index}: &f{gem}'
    - '&r&7  {value_lines}'
  empty-line: '&r&7No gems'
```

Placeholders:

| Placeholder | Description |
| --- | --- |
| `{holes}` | Number of occupied sockets (gems socketed) |
| `{max_holes}` | Available sockets on the item (holes punched) |
| `{gem_count}` | Number of socketed gems |
| `{index}` | Gem index (starting from 1) |
| `{gem}` | Gem display name |
| `{id}` | Gem internal id |
| `{values}` | Gem value description (single line, e.g. `攻击力：20.00、防御力：20.00`) |
| `{value_lines}` | Gem value description (one attribute per line) |

#### attribute-lore: attribute panel merging (independent of gem buffType)

Merges gem attributes into the equipment lore: the attribute name comes from each gem's own `LoreChange` mapping (identifier → name), and the value is taken from that attribute line; merged by the "total + bonus" rules (same attributes summed, total + bonus shown). Applies to ALL buffTypes (`sx_attribute` / `vanilla_attribute` / `enchant` / ...).

```yaml
attribute-lore:
  enabled: true
  new-line: '&r&f{name}：&e{value}'  # template for new lines added for gem-only attributes
  bonus-format: '&r（+{bonus}）'     # bonus display format
```

Placeholders: `{name}` attribute name, `{value}` total value, `{bonus}` gem bonus.

> Templates use `&r` (regular text) by default. Colors can use `&`, `§x` hex codes, or `<#RRGGBB>`; `<#RRGGBB>` is parsed into a real color, e.g. `&r<#FFAA00>（<#1EFF5C>+{bonus}<#FFAA00>）`.

The `display` sub-section configures per-attribute display formats (keyed by the `LoreChange` attribute name) for both the panel merge and socket info:

```yaml
attribute-lore:
  enabled: true
  new-line: '&r&f{name}：&e{value}'
  bonus-format: '&r（+{bonus}）'
  display:                      # per-attribute display format (optional)
    暴击率:                      # key = attribute name from LoreChange
      factor: 100               # scale (e.g. ratio→percent; default 1)
      unit: '%'                 # suffix (default empty)
      decimals: 2               # display decimals (0~10; default 2, 0 = integer)
    暴击伤害:
      factor: 100
      unit: '%'
      decimals: 2
```

> Merge rule: total = original line value + gem value × `factor`; the bonus is scaled the same way (e.g. `暴击率：19%（+19%）`).
> Attributes without a `display` entry use `settings.value-decimal-places`.

#### Numeric placeholder expressions (1.1.1+, all `${...}` placeholders)

Expressions are supported everywhere `${...}` placeholders are used (gem lore, `attribute` value lines, socket info...):

| Form | Description | Example (crit=0.1543) |
| --- | --- | --- |
| `${key}` | raw random value (original behavior) | `${crit}` → `0.1543` |
| `${expression}` | evaluated, displayed with `settings.value-decimal-places` decimals | `${crit*100}` → `15.43` |
| `${expression,N}` | evaluated, forced N decimals | `${crit*100,0}` → `15` |

Supported: `+ - * / % ^`, parentheses, and functions `ROUND(x[,n]) / FLOOR / CEIL / ABS / MIN / MAX / SQRT / LOG`; variables are the gem `random` keys. Failed expressions keep the raw placeholder text.

`settings.value-decimal-places` (0~10, default 2) is the default display precision for expression results.

### Language message files

Player messages are split per language: `settings.language` in `config.yml` selects the file under `messages/` (e.g. `zh_cn` → `messages/zh_cn.yml`). `{xxx}` are placeholders. Built-in languages:

- `messages/zh_cn.yml`: Simplified Chinese
- `messages/en_us.yml`: English

Language values are case-insensitive and treat `-` and `_` as equal (e.g. `en-US` and `en_us`). If the selected file is missing, the plugin falls back to Chinese; under Chinese, an old `messages.yml` is still preferred so custom text is preserved.

The language files also contain two name mapping sections:

- `attribute-names`: maps attribute ids (e.g. `minecraft:attack_damage`, `bakamc:strength`) to display names (e.g. `攻击力`, `力量`) for `vanilla_attribute` and `ce_attribute` gems
- `enchant-names`: maps enchant ids (e.g. `minecraft:sharpness` → `锋利`, `ce:Wither` → `凋灵`) for `enchant` gems; if missing, vanilla enchants fall back to the raw id and CrazyEnchantments enchants fall back to their CustomName

| Message key | Scenario |
| --- | --- |
| `punch-max-global` | Punching: global socket limit reached |
| `punch-max-source` | Punching: puncher source limit reached |
| `punch-fail` | Punching: success roll failed (tool consumed) |
| `socket-no-hole` | Socketing: item has no sockets |
| `socket-full` | Socketing: sockets are full |
| `socket-repeat-limit` | Socketing: repeat-socket limit reached for this gem |
| `socket-gemtype-limit` | Socketing: global per-gemtype limit reached (settings.gem-type-limit) |
| `socket-bufftype-unsupported` | Socketing: buff entry type not supported (with `{type}` placeholder) |
| `lore-detail-on` / `lore-detail-off` | Toggle result messages for `/mosaicgem lore` |
| `lore-no-item` / `lore-no-gems` | Failure messages for `/mosaicgem lore` (empty hand / item has no gems) |
| `remove-empty` | Removal: no socketed gems |
| `remove-fail` | Removal: success roll failed (tool consumed) |
| `remove-destroyed` | Removal: succeeded but the gem's destroy check hit (gem lost, not returned) |
| `target-invalid` | Target type/material does not match the tool's limits |
| `interaction-disabled` | This interaction method is disabled |
| `tool-config-missing` | Tool is not in the config |
| `invalid-combination` | Tool+tool or invalid combination |
| `inventory-full` | Returned gem dropped on the ground because the inventory was full |

There are also success/command/help/debug/selftest messages (`punch-success`, `socket-success`, `remove-success`, `reload-success`, `give-*`, `player-not-found`, `no-permission`, `help-*`, `debug-*`, `selftest-*`, etc.); see the comments inside the files for the full key list.

### items/ directory (item configs)

Every `.yml` under `plugins/MosaicGem/items/` (including subdirectories) is treated as a potential item config file. The item type is declared by top-level sections; multiple sections can coexist in one file, and definitions can be split across any number of files:

```yaml
gems:
  # gem definitions…
punchers:
  # puncher definitions…
removers:
  # remover definitions…
```

- Type sections: `gems:` (gems), `punchers:` (punchers), `removers:` (removers)
- Legacy flat format (`gems.yml` / `punchers.yml` / `removers.yml` with item ids directly at the top level) is still supported
- If the same id appears in multiple files, the later-loaded definition overrides the earlier one (a warning is logged at startup/reload)

### items/gems.yml

On first startup, `items/gems.yml` is generated (using a `gems:` section) according to the installed soft dependencies, so unused sample gems are not shown:

- Always generated: `原版测试宝石` (`vanilla_attribute`), `附魔测试宝石` (`enchant`)
- With SX-Attribute installed: also `SA测试宝石` (`sx_attribute`)
- With MythicMobs installed: also `MM技能测试宝石` (`mythicmobs_skill`)
- With CraftEngine installed: also `CE测试宝石` (`ce_attribute`)

**Generation rule**: if **any** `.yml` file already exists under `items/` (including subdirectories) — valid or not — no default item config files (`gems.yml` / `punchers.yml` / `removers.yml`) are generated at all, so custom config is never overwritten. After installing a new soft dependency, delete those default files and run `/mosaicgem reload` to regenerate them. The full example below shows the file when every soft dependency is installed:

```yaml
gems:
  SA测试宝石:
    material: PAPER              # item material
    isEnchant: true              # enchant glint
    name: "&cSA测试宝石"          # display name (supports & color codes)
    lore:                        # item lore
      - '&a▪ 伤害增加：${random_value}'
    custom-model-data: 0         # custom model data
    targetType:                  # allowed equipment types; empty = all
      - SWORD
    targetMaterial:              # allowed equipment ids; empty = all (AND with targetType)
      - IRON_SWORD
    repetitions: 5               # max times this gem can be socketed; empty = unlimited
    gemtype:                     # type tags (multiple allowed); limit count via settings.gem-type-limit
      - 攻击
    remove-destroy-chance: 0     # removal destroy chance 0-100% (default 0 = never; independent of remover success rate)
    random:                      # random values rolled at creation; referenced in lore/attribute
      random_value: '10.00~20.00'
    buffs:                       # v1.2+ entry list; legacy buffType + attribute still works
      - type: sx_attribute       # SX attribute: written to lore, read by SX-Attribute
        attribute:               # leading digit is the identifier, matched against LoreChange keys
          - '1: 攻击力: ${random_value}'
          - '2: 防御力: ${random_value}'
    LoreChange:                  # identifier -> attribute name; attributes without mapping do nothing
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
    buffs:
      - type: vanilla_attribute  # vanilla attribute: applied to AttributeModifier, lore untouched
        attribute:                # vanilla_attribute only: "vanilla attribute id：value"
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
    buffs:
      - type: enchant                # enchant: added/stacked onto the item
        attribute:                    # enchant only: "enchant id：level"
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
    buffs:
      - type: mythicmobs_skill       # MythicMobs skill: triggered by Crucible item skills
        attribute:                    # mythicmobs_skill only: one MythicMobs skill per line
          - 'TestSkill @onSwing'

  # Composite example: one gem carrying CE attribute + enchant + vanilla attribute + MM skill
  复合测试宝石:
    material: NETHER_STAR
    isEnchant: true
    name: "&d复合测试宝石"
    targetType:
      - SWORD
    repetitions: 1
    random:
      attack: '2.50~5.00'
    remove-destroy-chance: 0
    buffs:
      - type: ce_attribute
        attribute:
          - '1: bakamc:strength: ${attack}'
      - type: enchant
        attribute:
          - 'minecraft:sharpness: 3'
      - type: vanilla_attribute
        attribute:
          - 'minecraft:attack_damage: ${attack}'
      - type: mythicmobs_skill
        attribute:
          - 'TestSkill @onSwing'
    LoreChange:
      - 1: 力量
```

- `random` supports multiple random numbers in `min~max` format; decimals follow the configured precision and the value is fixed to the gem instance
- `gemtype`: gem type tags (a string list, multiple allowed, e.g. `['攻击', '火属性']`). Combined with `settings.gem-type-limit` in `config.yml`, limits how many gems of the same tag can be socketed on one item; empty list (omitted) means the gem does not participate in type counting
- `remove-destroy-chance`: probability (0-100, percent; default 0 = never destroyed; out-of-range values are clamped) that the gem is destroyed (lost) on removal. **Independent of the remover's success rate**: rolled only after the remover succeeds; on a hit the gem is not returned
- `buffs[].attribute`: attribute lines of that entry (same format as the legacy `attribute`). The leading digit is an **identifier** (e.g. `1:`, `2:`); the attribute name comes from the gem's own `LoreChange` section (identifier → name); attributes without a mapping do nothing; the value is taken from the numeric value of the corresponding attribute line
- `LoreChange`: the attribute-panel-merge mapping, independent of buffType — the `attribute-lore` section reads each gem's mapping and merges the corresponding attribute value into the equipment lore by the "total + bonus" rules (same attributes summed, total + bonus shown); applies to ALL buffTypes
- `sx_attribute`: written to the equipment lore and read by SX-Attribute
- `vanilla_attribute`: applied directly as vanilla attribute modifiers (same attributes merge into one modifier; the item's native same-attribute modifiers are merged into the total); lore is **not** modified
- `ce_attribute`: written as CraftEngine persistent item modifiers (`craftengine:attribute_modifiers`), format `CE attribute id: value` (e.g. `bakamc:strength: ${random_value}`); scope fixed to `weapon` (item-only, attack-time settlement); same-attribute gems sum via add_value and merge with the weapon's own words; requires CraftEngine 26.8+
- `enchant`: adds/stacks enchantments (existing → original level + gem level; missing → created; multiple gems summed; native levels stored and restored on removal)
- `mythicmobs_skill`: attribute lines are MythicMobs skill names (MythicCrucible format `技能名 @触发器` supported); with MythicCrucible installed the Crucible item-skill system triggers them, otherwise melee-attack fallback is used; socket info shows the skill name
- Vanilla attribute display names come from `attribute-names` (also used for `ce_attribute` ids like `bakamc:strength`); enchant display names from `enchant-names` (raw id fallback, or CrazyEnchantments CustomName for `ce:` enchants)
- CrazyEnchantments custom enchants: `ce:附魔名: 等级` (e.g. `ce:Wither: 2`), requires CrazyEnchantments; vanilla enchants: `minecraft:sharpness: 等级`, bare ids (e.g. `sharpness`) get the `minecraft:` prefix automatically
- `targetMaterial` and `targetType` are ANDed when both are set
- Supported equipment types: `SWORD`, `SPEAR`, `TRIDENT`, `AXE`, `HOE`, `SHOVEL`, `PICKAXE`, `BOW`, `CROSSBOW`, `MACE`, `SHIELD`, `HELMET`, `CHESTPLATE`, `LEGGINGS`, `BOOTS`, `ELYTRA`

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
    rate: 10                     # success rate (0-100, percentage)
    holesnum: 2                  # max sockets this puncher type can add to one item
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
    rate: 10                     # success rate (0-100, percentage)
```

## Data Storage & Attribute Calculation

- Tool items (gems/punchers/removers) are identified by type and internal id through PersistentDataContainer
- Gem instances store their fixed random values in item data
- Equipment records: total sockets, per-source socket counts, socketed gem list (instance UUID, random values), and original attribute lines before merging
- Vanilla attributes: native modifiers merged into gem modifiers are stored in the item PDC and restored when gems are removed
- Enchantments: native enchant levels (vanilla + CrazyEnchantments) are stored in the item PDC and restored on removal
- Merged attribute lines and socket info use the `§X` marker:
  - In merged lines, SX-Attribute reads the value before `§X`; the `（+bonus）` after it does not affect calculation
  - Socket info lines start with `§X` and are ignored by SX-Attribute; they are display-only
- Existing lore text such as `§r`, `§x` hex colors, italics, and bold is written back literally and never lost

## Building

```powershell
.\gradlew.bat build
```

Artifact: `build/libs/MosaicGem-1.0.2.jar`

Released versions (with jars) are available on [GitHub Releases](https://github.com/W-IBARI/MosaicGem/releases).

Development environment: JDK 25, Gradle 9.6.1 (project wrapper included), `dev.folia:folia-api:26.1.2.build.8-stable` (compile against the lowest 26.x stable to support all 26.x).

## FAQ

**Q: Attributes are not working?**

Make sure `SX-Item` and `SX-Attribute` are installed and enabled, the gem uses a buff entry with `type: sx_attribute` (`buffs` format), and the merged line exists in the equipment attribute panel; for `vanilla_attribute` gems, check that the corresponding vanilla attribute is present in the item's tooltip.

**Q: `ce:` enchant gems are not working?**

Make sure [CrazyEnchantments](https://github.com/Crazy-Crew/CrazyEnchantments/) is installed and enabled, and the name after `ce:` matches the enchant name in CrazyEnchantments exactly (e.g. `ce:Wither`). MosaicGem calls its API through a runtime bridge; no extra dependency is needed.

**Q: MythicMobs mobs are not dropping plugin gems?**

Make sure MythicMobs is installed and loads before MosaicGem (declared as a soft dependency); use `mosaicgem:<gem internal id>` as a drop in the mob or drop table, then run `/mm reload` (or restart) so the drops are re-parsed. MosaicGem triggers one drop/mob reload at startup to register its custom drop.

**Q: `mythicmobs_skill` gems are not casting?**

Make sure the gem uses a buff entry with `type: mythicmobs_skill`, the skill name in `attribute` matches a skill configured in MythicMobs exactly, and the item is socketed; with MythicCrucible installed, triggers follow the `@<trigger>` field (default `@onSwing`); without it, only melee attacks trigger (`@onSwing` / `@onAttack` / `@onHit`). Cooldowns, conditions, and target selection are handled by MythicMobs.

**Q: `ce_attribute` gems don't work?**

Make sure CraftEngine 26.8+ is installed and enabled (the startup log should show "CraftEngine 属性桥接已启用"), the gem uses a buff entry with `type: ce_attribute`, and the CE attribute id in `attribute` (e.g. `bakamc:strength`) is defined in CraftEngine's `attributes` configuration. Socketed gems persist their words with the item; the CE attribute pipeline picks them up when the item is worn (scope=weapon: shown in socket info and lore, not in the entity panel).

**Q: How do I debug configuration issues?**

Run `/mosaicgem selftest` for config parsing, item generation, data read/write and attribute merging; run `/mosaicgem debug` to inspect the item data of what you are holding.

## License

This plugin is released under the [GNU LGPL v3](https://www.gnu.org/licenses/lgpl-3.0.html) license (full text in the repository's [LICENSE](LICENSE) file).

Anyone may use, modify, and distribute this plugin under the LGPL-3.0 terms; modified versions distributed externally must keep this license notice and be licensed under the same license.
