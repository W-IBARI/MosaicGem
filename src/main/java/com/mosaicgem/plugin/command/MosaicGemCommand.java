package com.mosaicgem.plugin.command;

import com.mosaicgem.plugin.MosaicGemPlugin;
import com.mosaicgem.plugin.config.ConfigManager;
import com.mosaicgem.plugin.config.GemDefinition;
import com.mosaicgem.plugin.config.ItemDefinition;
import com.mosaicgem.plugin.config.PuncherDefinition;
import com.mosaicgem.plugin.config.RemoverDefinition;
import com.mosaicgem.plugin.config.SocketLoreTemplate;
import com.mosaicgem.plugin.model.SocketData;
import com.mosaicgem.plugin.model.SocketedGem;
import com.mosaicgem.plugin.model.ToolType;
import com.mosaicgem.plugin.service.AttributeLoreService;
import com.mosaicgem.plugin.util.ItemFactory;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.persistence.PersistentDataContainerView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * /mosaicgem 指令：reload / give / debug / list
 */
public class MosaicGemCommand implements CommandExecutor, TabCompleter {

    private final MosaicGemPlugin plugin;
    private final ConfigManager configs;
    private final ItemFactory factory;

    public MosaicGemCommand(MosaicGemPlugin plugin, ConfigManager configs, ItemFactory factory) {
        this.plugin = plugin;
        this.configs = configs;
        this.factory = factory;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> reload(sender);
            case "give" -> give(sender, args);
            case "debug" -> debug(sender, args);
            case "list" -> list(sender, args);
            case "selftest" -> selftest(sender);
            default -> {
                sendHelp(sender);
                yield true;
            }
        };
    }

    // ------------------------------------------------------------------
    // 子指令
    // ------------------------------------------------------------------

    private boolean reload(CommandSender sender) {
        if (!hasPermission(sender, "reload")) {
            return true;
        }
        plugin.reloadConfigs();
        send(sender, configs.message("reload-success"));
        return true;
    }

    private boolean give(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "give")) {
            return true;
        }
        if (args.length < 2) {
            send(sender, configs.message("give-usage"));
            return true;
        }
        String id = args[1];

        // 在所有类型（宝石/打孔器/拆卸器）中查找同名物品
        List<GiveMatch> matches = new ArrayList<>();
        for (ToolType type : ToolType.values()) {
            ItemDefinition definition = configs.find(type, id);
            if (definition != null) {
                matches.add(new GiveMatch(type, definition));
            }
        }
        if (matches.isEmpty()) {
            send(sender, configs.message("give-not-found-all").replace("{id}", id));
            return true;
        }
        if (matches.size() > 1) {
            send(sender, configs.message("give-ambiguous").replace("{types}",
                    matches.stream().map(m -> m.type().name().toLowerCase(Locale.ROOT))
                            .collect(Collectors.joining(", "))));
            return true;
        }
        GiveMatch match = matches.get(0);
        ToolType type = match.type();
        ItemDefinition definition = match.definition();

        int amount = args.length > 2 ? parseAmount(args[2]) : 1;
        Player target = null;
        if (args.length > 3) {
            target = Bukkit.getPlayerExact(args[3]);
        } else if (sender instanceof Player player) {
            target = player;
        }
        if (target == null) {
            send(sender, configs.message("player-not-found").replace("{player}", args.length > 3 ? args[3] : "?"));
            return true;
        }

        // 宝石逐个生成随机值，即使一口气给多个也各不相同；打孔器/拆卸器无随机值，可保持整组发放
        List<ItemStack> items = new ArrayList<>();
        if (type == ToolType.GEM) {
            for (int i = 0; i < amount; i++) {
                items.add(factory.buildGem((GemDefinition) definition, factory.rollRandom((GemDefinition) definition)));
            }
        } else {
            ItemStack tool = switch (type) {
                case PUNCHER -> factory.buildPuncher((PuncherDefinition) definition);
                case REMOVER -> factory.buildRemover((RemoverDefinition) definition);
                default -> null;
            };
            tool.setAmount(Math.max(1, amount));
            items.add(tool);
        }

        Map<Integer, ItemStack> leftover = target.getInventory().addItem(items.toArray(new ItemStack[0]));
        for (ItemStack rest : leftover.values()) {
            target.getWorld().dropItem(target.getLocation(), rest);
        }
        send(sender, configs.message("give-success")
                .replace("{player}", target.getName())
                .replace("{amount}", String.valueOf(amount))
                .replace("{id}", id));
        return true;
    }

    private boolean debug(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "debug")) {
            return true;
        }
        Player target = null;
        if (args.length >= 2) {
            target = Bukkit.getPlayerExact(args[1]);
        } else if (sender instanceof Player player) {
            target = player;
        }
        if (target == null) {
            send(sender, configs.message("debug-usage"));
            return true;
        }
        ItemStack item = target.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            send(sender, configs.message("debug-no-item").replace("{player}", target.getName()));
            return true;
        }

        List<String> lines = new ArrayList<>();
        lines.add(configs.message("debug-title"));
        lines.add(configs.message("debug-holder").replace("{player}", target.getName()));
        lines.add(configs.message("debug-item").replace("{item}", item.getType().name()));
        lines.add(configs.message("debug-amount").replace("{amount}", String.valueOf(item.getAmount())));
        if (item.hasItemMeta()) {
            var meta = item.getItemMeta();
            if (meta.hasDisplayName()) {
                lines.add(configs.message("debug-display-name").replace("{name}", meta.getDisplayName()));
            }
            if (meta.hasCustomModelData()) {
                lines.add(configs.message("debug-custom-model-data").replace("{value}", String.valueOf(meta.getCustomModelData())));
            }
            if (meta.hasEnchantmentGlintOverride()) {
                lines.add(configs.message("debug-enchant-glint").replace("{value}", String.valueOf(meta.getEnchantmentGlintOverride())));
            }
            if (meta.hasLore()) {
                lines.add(configs.message("debug-lore"));
                for (String lore : meta.getLore()) {
                    lines.add("  &7" + lore);
                }
            }
        }

        ToolType toolType = factory.getToolType(item);
        if (toolType != null) {
            lines.add(configs.message("debug-plugin-item")
                    .replace("{type}", toolType.name().toLowerCase(Locale.ROOT))
                    .replace("{id}", factory.getToolId(item)));
            Map<String, String> values = factory.readValues(item);
            if (!values.isEmpty()) {
                lines.add(configs.message("debug-random-values"));
                values.forEach((name, value) -> lines.add("  &7" + name + " = &e" + value));
            }
        }

        SocketData socketData = factory.readSocketData(item);
        lines.add(configs.message("debug-holes")
                .replace("{holes}", String.valueOf(socketData.holes()))
                .replace("{gems}", String.valueOf(socketData.gems().size())));
        if (!socketData.gems().isEmpty()) {
            lines.add(configs.message("debug-socketed-gems"));
            for (SocketedGem gem : socketData.gems()) {
                lines.add("  &7- &e" + gem.id() + " &7(" + gem.instanceId().substring(0, 8) + ")");
                gem.values().forEach((name, value) -> lines.add("      &7" + name + " = &e" + value));
                for (String line : gem.lines()) {
                    lines.add("      " + configs.message("debug-injected-line").replace("{line}", line));
                }
            }
        }

        PersistentDataContainerView pdc = item.getPersistentDataContainer();
        lines.add(configs.message("debug-component-keys")
                .replace("{keys}", pdc.getKeys().stream().map(key -> key.getKey()).toList().toString()));
        lines.add(configs.message("debug-end"));
        for (String line : lines) {
            sender.sendMessage(ItemFactory.text(line));
        }
        return true;
    }

    private boolean list(CommandSender sender, String[] args) {
        if (!hasPermission(sender, "list")) {
            return true;
        }
        if (args.length < 2) {
            send(sender, configs.message("list-usage"));
            return true;
        }
        ToolType type = ToolType.fromString(args[1]);
        if (type == null) {
            send(sender, configs.message("list-usage"));
            return true;
        }
        List<String> ids = switch (type) {
            case GEM -> new ArrayList<>(configs.getGems().keySet());
            case PUNCHER -> new ArrayList<>(configs.getPunchers().keySet());
            case REMOVER -> new ArrayList<>(configs.getRemovers().keySet());
        };
        send(sender, configs.message("list-result")
                .replace("{type}", type.name().toLowerCase(Locale.ROOT))
                .replace("{count}", String.valueOf(ids.size()))
                .replace("{ids}", String.join(", ", ids)));
        return true;
    }

    /**
     * 自检：不依赖玩家环境，验证配置解析、物品生成与组件读写。
     */
    private boolean selftest(CommandSender sender) {
        if (!hasPermission(sender, "selftest")) {
            return true;
        }
        List<String> lines = new ArrayList<>();
        int ok = 0;
        int fail = 0;

        for (GemDefinition definition : configs.getGems().values()) {
            try {
                Map<String, String> values = factory.rollRandom(definition);
                ItemStack item = factory.buildGem(definition, values);
                if (factory.getToolType(item) != ToolType.GEM) {
                    throw new IllegalStateException("宝石标记缺失");
                }
                if (!factory.readValues(item).equals(values)) {
                    throw new IllegalStateException("随机数读写不一致");
                }
                ok++;
            } catch (Exception e) {
                fail++;
                lines.add(configs.message("selftest-gem-fail")
                        .replace("{id}", definition.getId())
                        .replace("{error}", e.getMessage()));
            }
        }
        for (PuncherDefinition definition : configs.getPunchers().values()) {
            try {
                ItemStack item = factory.buildPuncher(definition);
                if (factory.getToolType(item) != ToolType.PUNCHER) {
                    throw new IllegalStateException("打孔器标记缺失");
                }
                ok++;
            } catch (Exception e) {
                fail++;
                lines.add(configs.message("selftest-puncher-fail")
                        .replace("{id}", definition.getId())
                        .replace("{error}", e.getMessage()));
            }
        }
        for (RemoverDefinition definition : configs.getRemovers().values()) {
            try {
                ItemStack item = factory.buildRemover(definition);
                if (factory.getToolType(item) != ToolType.REMOVER) {
                    throw new IllegalStateException("拆卸器标记缺失");
                }
                ok++;
            } catch (Exception e) {
                fail++;
                lines.add(configs.message("selftest-remover-fail")
                        .replace("{id}", definition.getId())
                        .replace("{error}", e.getMessage()));
            }
        }

        try {
            ItemStack sword = new ItemStack(Material.IRON_SWORD);
            Map<String, String> values = new LinkedHashMap<>();
            values.put("random_value", "15.23");
            SocketedGem gem = new SocketedGem("测试宝石", "test-uuid", values, List.of("攻击力：15.23"));
            Map<String, Integer> sources = new LinkedHashMap<>();
            sources.put("测试打孔器", 1);
            factory.writeSocketData(sword, 1, sources, List.of(gem));
            SocketData data = factory.readSocketData(sword);
            if (data.holes() != 1) {
                throw new IllegalStateException("孔数读写不一致: " + data.holes());
            }
            if (!data.holeSources().equals(sources)) {
                throw new IllegalStateException("来源孔数读写不一致");
            }
            if (data.gems().size() != 1 || !data.gems().get(0).id().equals("测试宝石")) {
                throw new IllegalStateException("宝石数据读写不一致");
            }
            SocketLoreTemplate template = configs.socketLore();
            factory.applySocketLore(sword, data, template);
            if (template.enabled() && factory.readSocketLines(sword).isEmpty()) {
                throw new IllegalStateException("镶嵌信息 lore 未写入");
            }
            ok++;
        } catch (Exception e) {
            fail++;
            lines.add(configs.message("selftest-socket-data-fail").replace("{error}", e.getMessage()));
        }

        try {
            ItemStack sword = new ItemStack(Material.IRON_SWORD);
            // 模拟其他插件以组件字面文本写入 lore（§r 是字面字符，由客户端解释）
            sword.editMeta(meta -> meta.lore(List.of(
                    Component.text("\u00A7r<#AAAAAA>主手装备"),
                    Component.text("\u00A7r<#FFAA00>攻击力：<#FF5555>13.90"),
                    Component.text("\u00A7r<#FFAA00>攻击速度：<#FFFF55>1.6"),
                    Component.text("\u00A7o斜体说明"),
                    Component.text("\u00A7l加粗说明")
            )));
            Map<String, String> gemValues = new LinkedHashMap<>();
            gemValues.put("random_value", "20.00");
            SocketedGem gem = new SocketedGem("SA测试宝石", "test-uuid-merge", gemValues, List.of());
            Map<String, Integer> sources = new LinkedHashMap<>();
            sources.put("测试打孔器", 1);
            factory.writeSocketData(sword, 1, sources, List.of(gem));

            AttributeLoreService attributeLoreService = new AttributeLoreService(configs, factory);
            attributeLoreService.update(sword, List.of(gem));
            factory.applySocketLore(sword, new SocketData(1, sources, List.of(gem)), configs.socketLore());
            Component mergedComponent = sword.lore().stream()
                    .filter(line -> ItemFactory.stripLoreText(
                            LegacyComponentSerializer.legacySection().serialize(line)).contains("（+20"))
                    .findFirst()
                    .orElse(null);
            if (mergedComponent == null || !hasItalicFalse(mergedComponent)) {
                String serialized = mergedComponent == null
                        ? "null"
                        : LegacyComponentSerializer.legacySection().serialize(mergedComponent);
                throw new IllegalStateException("合并行未显式关闭斜体: " + serialized);
            }
            java.util.function.Function<String, String> escape = s -> s.replace("\u00A7", "\\u00A7").replace("\u200B", "\\u200B");
            List<String> resultLore = sword.getItemMeta().getLore();
            String mergedLine = resultLore.get(1);
            boolean hasMarker = mergedLine.contains(AttributeLoreService.MARKER);
            boolean hasSectionX = mergedLine.contains("\u00A7X");
            boolean hasZw = mergedLine.contains("\u200B");
            // 1.0.1 起文本经 MiniMessage 解析，§r 已转为真实样式，这里改为检查组件是否为正体
            boolean resetMainHand = hasItalicFalse(sword.lore().get(0));
            boolean resetAttribute = hasItalicFalse(mergedComponent);
            boolean resetAttackSpeed = hasItalicFalse(sword.lore().get(2));
            boolean italicOther = resultLore.get(3).contains("\u00A7o");
            boolean boldOther = resultLore.get(4).contains("\u00A7l");
            if (!mergedLine.contains("33.90") || !ItemFactory.stripLoreText(mergedLine).contains("（+20") || !hasMarker
                    || !resetMainHand || !resetAttribute || !resetAttackSpeed || !italicOther || !boldOther) {
                throw new IllegalStateException("属性合并失败: marker=" + hasMarker + " sectionX=" + hasSectionX
                        + " zw=" + hasZw + " resetMainHand=" + resetMainHand + " resetAttribute=" + resetAttribute
                        + " resetAttackSpeed=" + resetAttackSpeed + " italicOther=" + italicOther
                        + " boldOther=" + boldOther + " lore=" + resultLore.stream().map(escape).toList());
            }
            // 再次更新（模拟拆卸重算）应还原后重新合并，结果稳定
            attributeLoreService.update(sword, List.of(gem));
            factory.applySocketLore(sword, new SocketData(1, sources, List.of(gem)), configs.socketLore());
            String mergedLine2 = sword.getItemMeta().getLore().get(1);
            if (!mergedLine2.contains("33.90") || !ItemFactory.stripLoreText(mergedLine2).contains("（+20")) {
                throw new IllegalStateException("属性重复合并异常: " + escape.apply(mergedLine2));
            }

            // 第二颗宝石：应更新原属性行而不是新增一行，括号取小数位最多的宝石位数
            Map<String, String> gemValues2 = new LinkedHashMap<>();
            gemValues2.put("random_value", "20.00");
            SocketedGem gem2 = new SocketedGem("SA测试宝石", "test-uuid-merge-2", gemValues2, List.of());
            attributeLoreService.update(sword, List.of(gem, gem2));
            factory.applySocketLore(sword, new SocketData(1, sources, List.of(gem, gem2)), configs.socketLore());
            List<String> loreAfterSecond = sword.getItemMeta().getLore();
            long attackLines = loreAfterSecond.stream()
                    .filter(line -> line.contains(AttributeLoreService.MARKER)
                            && !line.startsWith(AttributeLoreService.MARKER)
                            && ItemFactory.stripLoreText(line).startsWith("攻击力"))
                    .count();
            long holeLines = loreAfterSecond.stream().filter(line -> line.contains("孔位")).count();
            long gemLines = loreAfterSecond.stream()
                    .filter(line -> ItemFactory.stripLoreText(line).startsWith("宝石"))
                    .count();
            String secondLine = loreAfterSecond.get(1);
            if (attackLines != 1 || holeLines != 1 || gemLines != 2
                    || !secondLine.contains("53.90") || !ItemFactory.stripLoreText(secondLine).contains("（+40.00")) {
                throw new IllegalStateException("第二颗宝石合并异常: attackLines=" + attackLines
                        + " holeLines=" + holeLines + " gemLines=" + gemLines
                        + " lore=" + loreAfterSecond.stream().map(escape).toList());
            }

            // 取下一颗宝石：应还原为只剩一颗宝石的合并结果
            attributeLoreService.update(sword, List.of(gem));
            factory.applySocketLore(sword, new SocketData(1, sources, List.of(gem)), configs.socketLore());
            String afterRemove = sword.getItemMeta().getLore().get(1);
            long gemLinesAfterRemove = sword.getItemMeta().getLore().stream()
                    .filter(line -> ItemFactory.stripLoreText(line).startsWith("宝石"))
                    .count();
            if (!afterRemove.contains("33.90") || !ItemFactory.stripLoreText(afterRemove).contains("（+20")
                    || gemLinesAfterRemove != 1) {
                throw new IllegalStateException("取下宝石后合并异常: gemLines=" + gemLinesAfterRemove
                        + " lore=" + sword.getItemMeta().getLore().stream().map(escape).toList());
            }
            ok++;
        } catch (Exception e) {
            fail++;
            lines.add(configs.message("selftest-attribute-merge-fail").replace("{error}", e.getMessage()));
        }

        try {
            // 原版属性：同属性宝石应合并为一个修饰符，并与物品原生修饰符合并；取下后还原
            GemDefinition vanillaDefinition = configs.getGem("原版测试宝石");
            if (vanillaDefinition == null) {
                throw new IllegalStateException("缺少原版测试宝石配置");
            }
            ItemStack vanillaSword = new ItemStack(Material.IRON_SWORD);
            vanillaSword.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS,
                    ItemAttributeModifiers.itemAttributes()
                            .addModifier(Attribute.ATTACK_DAMAGE,
                                    new AttributeModifier(new NamespacedKey("mosaicgemtest", "native_damage"),
                                            7.0, AttributeModifier.Operation.ADD_NUMBER))
                            .build());
            Map<String, String> vanillaValues1 = new LinkedHashMap<>();
            vanillaValues1.put("random_value", "5.00");
            Map<String, String> vanillaValues2 = new LinkedHashMap<>();
            vanillaValues2.put("random_value", "6.00");
            SocketedGem vanillaGem1 = new SocketedGem(vanillaDefinition.getId(), "v-uuid-1", vanillaValues1, List.of());
            SocketedGem vanillaGem2 = new SocketedGem(vanillaDefinition.getId(), "v-uuid-2", vanillaValues2, List.of());

            factory.rebuildVanillaAttributes(vanillaSword, List.of(vanillaGem1, vanillaGem2));
            ItemAttributeModifiers mods = vanillaSword.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            long attackCount = mods.modifiers().stream()
                    .filter(entry -> entry.attribute() == Attribute.ATTACK_DAMAGE)
                    .count();
            double attackTotal = mods.modifiers().stream()
                    .filter(entry -> entry.attribute() == Attribute.ATTACK_DAMAGE)
                    .mapToDouble(entry -> entry.modifier().getAmount())
                    .sum();
            if (attackCount != 1 || Math.abs(attackTotal - 18.0) > 0.001) {
                throw new IllegalStateException("原版宝石未合并: count=" + attackCount + " total=" + attackTotal);
            }

            // 全部取下：应还原原生修饰符（+7），且不残留插件修饰符
            factory.rebuildVanillaAttributes(vanillaSword, List.of());
            mods = vanillaSword.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
            attackCount = mods.modifiers().stream()
                    .filter(entry -> entry.attribute() == Attribute.ATTACK_DAMAGE)
                    .count();
            attackTotal = mods.modifiers().stream()
                    .filter(entry -> entry.attribute() == Attribute.ATTACK_DAMAGE)
                    .mapToDouble(entry -> entry.modifier().getAmount())
                    .sum();
            if (attackCount != 1 || Math.abs(attackTotal - 7.0) > 0.001) {
                throw new IllegalStateException("原版宝石取下后未还原: count=" + attackCount + " total=" + attackTotal);
            }
            ok++;
        } catch (Exception e) {
            fail++;
            lines.add(configs.message("selftest-attribute-merge-fail").replace("{error}", "原版: " + e.getMessage()));
        }

        try {
            // 附魔：已有附魔叠加、无附魔新建、多颗宝石合计、取下后还原原始等级
            GemDefinition enchantDefinition = configs.getGem("附魔测试宝石");
            if (enchantDefinition == null) {
                throw new IllegalStateException("缺少附魔测试宝石配置");
            }
            Enchantment sharpness = Enchantment.getByKey(NamespacedKey.minecraft("sharpness"));
            Enchantment unbreaking = Enchantment.getByKey(NamespacedKey.minecraft("unbreaking"));
            if (sharpness == null || unbreaking == null) {
                throw new IllegalStateException("服务器未注册 sharpness/unbreaking 附魔");
            }
            ItemStack enchantSword = new ItemStack(Material.IRON_SWORD);
            enchantSword.addUnsafeEnchantment(sharpness, 2);

            Map<String, String> enchantValues1 = new LinkedHashMap<>();
            enchantValues1.put("random_value", "3");
            Map<String, String> enchantValues2 = new LinkedHashMap<>();
            enchantValues2.put("random_value", "4");
            SocketedGem enchantGem1 = new SocketedGem(enchantDefinition.getId(), "e-uuid-1", enchantValues1, List.of());
            SocketedGem enchantGem2 = new SocketedGem(enchantDefinition.getId(), "e-uuid-2", enchantValues2, List.of());

            factory.rebuildEnchantments(enchantSword, List.of(enchantGem1, enchantGem2));
            if (enchantSword.getEnchantmentLevel(sharpness) != 9) {
                throw new IllegalStateException("原附魔未正确叠加: sharpness=" + enchantSword.getEnchantmentLevel(sharpness));
            }
            if (enchantSword.getEnchantmentLevel(unbreaking) != 2) {
                throw new IllegalStateException("无原附魔时未按宝石等级新建: unbreaking=" + enchantSword.getEnchantmentLevel(unbreaking));
            }

            factory.rebuildEnchantments(enchantSword, List.of());
            if (enchantSword.getEnchantmentLevel(sharpness) != 2) {
                throw new IllegalStateException("取下附魔宝石后未还原: sharpness=" + enchantSword.getEnchantmentLevel(sharpness));
            }
            if (enchantSword.containsEnchantment(unbreaking)) {
                throw new IllegalStateException("取下附魔宝石后新增附魔未移除");
            }
            // 镶嵌信息应显示映射名（如“锋利”），而不是附魔内部名（minecraft:sharpness）
            String mappedName = configs.enchantName("minecraft:sharpness");
            if ("minecraft:sharpness".equals(mappedName)) {
                throw new IllegalStateException("enchant-names 映射未生效: " + mappedName);
            }
            List<String> displayLines = factory.resolveGemValueList(
                    new SocketedGem(enchantDefinition.getId(), "e-uuid-display", enchantValues1, List.of()));
            if (displayLines.isEmpty() || ItemFactory.stripLoreText(displayLines.get(0)).contains("minecraft:")) {
                throw new IllegalStateException("镶嵌信息显示了附魔内部名: " + displayLines);
            }
            ok++;
        } catch (Exception e) {
            fail++;
            lines.add(configs.message("selftest-attribute-merge-fail").replace("{error}", "附魔: " + e.getMessage()));
        }

        try {
            // MythicMobs 技能宝石：镶嵌信息应显示配置的 MM 技能名
            GemDefinition mmDefinition = configs.getGem("MM技能测试宝石");
            if (mmDefinition == null) {
                throw new IllegalStateException("缺少 MM 技能测试宝石配置");
            }
            List<String> skillLines = factory.resolveGemValueList(
                    new SocketedGem(mmDefinition.getId(), "mm-uuid-display", Map.of(), List.of()));
            if (!skillLines.contains("TestSkill")) {
                throw new IllegalStateException("MM 技能宝石信息未显示技能名: " + skillLines);
            }
            if (skillLines.stream().anyMatch(line -> line.contains("@"))) {
                throw new IllegalStateException("MM 技能宝石信息未去掉触发器后缀: " + skillLines);
            }
            ok++;
        } catch (Exception e) {
            fail++;
            lines.add(configs.message("selftest-attribute-merge-fail").replace("{error}", "MM技能: " + e.getMessage()));
        }

        try {
            // 打孔：镶嵌信息 lore 应原地更新，不产生重复的孔位行
            ItemStack punchSword = new ItemStack(Material.IRON_SWORD);
            punchSword.editMeta(meta -> meta.setLore(List.of("&7基础描述")));
            Map<String, Integer> sources = new LinkedHashMap<>();
            sources.put("测试打孔器", 1);
            factory.writeSocketData(punchSword, 1, sources, List.of());
            factory.applySocketLore(punchSword, new SocketData(1, sources, List.of()), configs.socketLore());
            sources.put("测试打孔器", 2);
            factory.writeSocketData(punchSword, 2, sources, List.of());
            factory.applySocketLore(punchSword, new SocketData(2, sources, List.of()), configs.socketLore());
            long holeLines = punchSword.getItemMeta().getLore().stream()
                    .filter(line -> line.contains("孔位"))
                    .count();
            if (holeLines != 1) {
                throw new IllegalStateException("打孔后孔位行重复: " + punchSword.getItemMeta().getLore());
            }
            Component holeComponent = punchSword.lore().stream()
                    .filter(line -> LegacyComponentSerializer.legacySection().serialize(line).contains("孔位"))
                    .findFirst()
                    .orElse(null);
            if (holeComponent == null || !hasItalicFalse(holeComponent)) {
                throw new IllegalStateException("镶嵌信息行未显式关闭斜体");
            }
            ok++;
        } catch (Exception e) {
            fail++;
            lines.add(configs.message("selftest-punch-lore-fail").replace("{error}", e.getMessage()));
        }

        try {
            // 配置模板：<#RRGGBB> 十六进制颜色应转为真实颜色，而不是作为字面文本写入
            Component colored = ItemFactory.toComponent("&r<#FFAA00>（<#1EFF5C>+20<#FFAA00>）");
            String plain = PlainTextComponentSerializer.plainText().serialize(colored);
            if (plain.contains("<#") || !plain.contains("（+20）")) {
                throw new IllegalStateException("十六进制颜色被当成字面文本: " + plain);
            }
            if (!hasColor(colored, TextColor.fromHexString("#FFAA00"))
                    || !hasColor(colored, TextColor.fromHexString("#1EFF5C"))) {
                throw new IllegalStateException("十六进制颜色未转换为真实颜色");
            }
            ok++;
        } catch (Exception e) {
            fail++;
            lines.add(configs.message("selftest-attribute-merge-fail").replace("{error}", "颜色模板: " + e.getMessage()));
        }

        try {
            // 完整往返：带 §X 标记与 <#RRGGBB> 的合并行写入物品后，颜色必须保留为 §x 十六进制码
            ItemStack colorRoundTrip = new ItemStack(Material.IRON_SWORD);
            factory.setLore(colorRoundTrip, List.of(
                    "攻击力：33.90\u00A7X&r<#FFAA00>（<#1EFF5C>+20<#FFAA00>）"
            ));
            String legacy = colorRoundTrip.getItemMeta().getLore().get(0);
            if (legacy.contains("<#") || !legacy.contains("\u00A7x")) {
                throw new IllegalStateException("合并行颜色在物品序列化后丢失: " + legacy);
            }
            ok++;
        } catch (Exception e) {
            fail++;
            lines.add(configs.message("selftest-attribute-merge-fail").replace("{error}", "颜色往返: " + e.getMessage()));
        }

        try {
            // 真实配置往返：若策划在 bonus-format 中配置了 <#RRGGBB>，合并后的物品 lore 必须保留为 §x 颜色码
            String bonusFormat = configs.attributeLore().bonusFormat();
            if (bonusFormat.contains("<#")) {
                ItemStack mergedSword = new ItemStack(Material.IRON_SWORD);
                mergedSword.editMeta(meta -> meta.lore(List.of(Component.text("攻击力：13.90"))));
                Map<String, String> bonusValues = new LinkedHashMap<>();
                bonusValues.put("random_value", "20.00");
                SocketedGem bonusGem = new SocketedGem("SA测试宝石", "hex-bonus-uuid", bonusValues, List.of());
                AttributeLoreService loreService = new AttributeLoreService(configs, factory);
                loreService.update(mergedSword, List.of(bonusGem));
                String mergedLegacy = mergedSword.getItemMeta().getLore().stream()
                        .filter(line -> line.contains("攻击力"))
                        .findFirst()
                        .orElse("");
                if (mergedLegacy.contains("<#") || !mergedLegacy.contains("\u00A7x")) {
                    throw new IllegalStateException("bonus-format 颜色在合并后丢失: " + mergedLegacy);
                }
            }
            ok++;
        } catch (Exception e) {
            fail++;
            lines.add(configs.message("selftest-attribute-merge-fail").replace("{error}", "配置颜色: " + e.getMessage()));
        }

        try {
            // 指令权限：应从 permissions.yml 读取，未配置时回退到内置默认节点/默认权限级
            if (!configs.commandPermissionNodes("reload").contains("mosaicgem.reload")
                    || !configs.commandPermissionNodes("give").contains("mosaicgem.give")
                    || !configs.commandPermissionNodes("debug").contains("mosaicgem.debug")
                    || !configs.commandPermissionNodes("selftest").contains("mosaicgem.debug")
                    || !"op".equals(configs.commandDefaultLevel("reload"))
                    || !"op".equals(configs.commandDefaultLevel("give"))
                    || !"op".equals(configs.commandDefaultLevel("debug"))
                    || !"op".equals(configs.commandDefaultLevel("list"))
                    || !"op".equals(configs.commandDefaultLevel("selftest"))
                    || !"op".equals(configs.commandDefaultLevel("unknown"))) {
                throw new IllegalStateException("指令权限节点读取异常: " + configs.commandPermissionNodes("reload"));
            }
            ok++;
        } catch (Exception e) {
            fail++;
            lines.add(configs.message("selftest-attribute-merge-fail").replace("{error}", "权限配置: " + e.getMessage()));
        }

        sender.sendMessage(ItemFactory.text(configs.message("selftest-title")));
        sender.sendMessage(ItemFactory.text(configs.message("selftest-pass-fail")
                .replace("{ok}", String.valueOf(ok))
                .replace("{fail}", String.valueOf(fail))));
        if (fail > 0) {
            for (String line : lines) {
                sender.sendMessage(ItemFactory.text(line));
            }
        }
        return true;
    }

    private static boolean hasItalicFalse(Component component) {
        if (component.style().decoration(TextDecoration.ITALIC) == TextDecoration.State.FALSE) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasItalicFalse(child)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasColor(Component component, TextColor color) {
        if (color.equals(component.color())) {
            return true;
        }
        for (Component child : component.children()) {
            if (hasColor(child, color)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // Tab 补全
    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<>();
        if (args.length == 1) {
            result.addAll(List.of("reload", "give", "debug", "list", "selftest"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("give")) {
            result.addAll(configs.getGems().keySet());
            result.addAll(configs.getPunchers().keySet());
            result.addAll(configs.getRemovers().keySet());
        } else if (args.length == 4 && args[0].equalsIgnoreCase("give")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                result.add(player.getName());
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("list")) {
            result.addAll(List.of("gem", "puncher", "remover"));
        } else if (args.length == 2 && args[0].equalsIgnoreCase("debug")) {
            for (Player player : Bukkit.getOnlinePlayers()) {
                result.add(player.getName());
            }
        }
        return result.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))).toList();
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    private boolean hasPermission(CommandSender sender, String command) {
        if (configs.hasCommandPermission(sender, command)) {
            return true;
        }
        send(sender, configs.message("no-permission"));
        return false;
    }

    private int parseAmount(String text) {
        try {
            return Math.max(1, Integer.parseInt(text));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void sendHelp(CommandSender sender) {
        send(sender, configs.message("help-title") + "\n"
                + configs.message("help-reload") + "\n"
                + configs.message("help-give") + "\n"
                + configs.message("help-debug") + "\n"
                + configs.message("help-list") + "\n"
                + configs.message("help-selftest"));
    }

    private void send(CommandSender sender, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        sender.sendMessage(ItemFactory.text(configs.prefix() + message));
    }

    private record GiveMatch(ToolType type, ItemDefinition definition) {
    }
}
