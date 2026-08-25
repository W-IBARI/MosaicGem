package com.mosaicgem.plugin.util;

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
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemAttributeModifiers;
import io.papermc.paper.persistence.PersistentDataContainerView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataAdapterContext;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 物品工厂：生成工具物品、读写物品组件（custom data）中的镶嵌数据。
 */
public class ItemFactory {


    /** 宝石加成方式：SX 属性（写入 lore，由 SX-Attribute 读取） */
    public static final String BUFF_TYPE_SX = "sx_attribute";

    /** 宝石加成方式：原版属性（直接附加到物品属性修饰符） */
    public static final String BUFF_TYPE_VANILLA = "vanilla_attribute";

    /** 宝石加成方式：原版附魔（附加/叠加到物品的原版附魔） */
    public static final String BUFF_TYPE_ENCHANT = "enchant";

    /** 宝石加成方式：MythicMobs 技能（镶嵌后按 MythicMobs 规则发动技能） */
    public static final String BUFF_TYPE_MM_SKILL = "mythicmobs_skill";

    /** CrazyEnchantments 附魔 id 前缀（配置使用 ce:Wither 格式） */
    public static final String CRAZY_ENCHANT_PREFIX = "ce:";

    /** CrazyEnchantments 附魔 id 前缀的别名，与 ce: 等价 */
    public static final String CRAZY_ENCHANT_PREFIX_ALT = "crazy:";

    /** 属性合并行标记（数值后）：SX 解析 §X 之前的内容，§X 为无效代码客户端静默忽略 */
    public static final String LORE_MARKER = "\u00A7X";

    /** 镶嵌信息行标记（放在行首）：SX 整行忽略，玩家不可见，用于按标记移除 */
    public static final String SOCKET_MARKER = "\u00A7X";

    private final MosaicGemPlugin plugin;
    private final ConfigManager configs;
    private final CrazyEnchantBridge crazyEnchantBridge;

    private final NamespacedKey keyItem;
    private final NamespacedKey keyId;
    private final NamespacedKey keyValues;
    private final NamespacedKey keyHoles;
    private final NamespacedKey keyGems;
    private final NamespacedKey keySocketLines;
    private final NamespacedKey keyBaseLines;
    private final NamespacedKey keyVanillaNatives;
    private final NamespacedKey keyEnchantNatives;
    private final NamespacedKey keyUuid;
    private final NamespacedKey keyCount;
    private final NamespacedKey keySources;

    public ItemFactory(MosaicGemPlugin plugin, ConfigManager configs) {
        this.plugin = plugin;
        this.configs = configs;
        this.crazyEnchantBridge = new CrazyEnchantBridge(plugin);
        this.keyItem = key("item");
        this.keyId = key("id");
        this.keyValues = key("values");
        this.keyHoles = key("holes");
        this.keyGems = key("gems");
        this.keySocketLines = key("socketLines");
        this.keyBaseLines = key("baseLines");
        this.keyVanillaNatives = key("vanillaNatives");
        this.keyEnchantNatives = key("enchantNatives");
        this.keyUuid = key("uuid");
        this.keyCount = key("count");
        this.keySources = key("sources");
    }

    ConfigManager configs() {
        return configs;
    }

    CrazyEnchantBridge crazyEnchants() {
        return crazyEnchantBridge;
    }

    // ------------------------------------------------------------------
    // 物品生成
    // ------------------------------------------------------------------

    public ItemStack buildGem(GemDefinition definition, Map<String, String> values) {
        return build(definition, ToolType.GEM, values);
    }

    public ItemStack buildPuncher(PuncherDefinition definition) {
        return build(definition, ToolType.PUNCHER, null);
    }

    public ItemStack buildRemover(RemoverDefinition definition) {
        return build(definition, ToolType.REMOVER, null);
    }

    private ItemStack build(ItemDefinition definition, ToolType type, Map<String, String> values) {
        ItemStack item = new ItemStack(definition.getMaterial());
        item.editMeta(meta -> {
            if (definition.isEnchant()) {
                meta.setEnchantmentGlintOverride(true);
            }
            if (definition.getCustomModelData() != null) {
                meta.setCustomModelData(definition.getCustomModelData());
            }
            meta.displayName(toComponent(resolve(definition.getName(), values)));
            if (!definition.getLore().isEmpty()) {
                meta.lore(definition.getLore().stream()
                        .map(line -> toComponent(resolve(line, values)))
                        .toList());
            }
        });
        markTool(item, type, definition.getId(), values);
        return item;
    }

    // ------------------------------------------------------------------
    // 工具标记与识别
    // ------------------------------------------------------------------

    public void markTool(ItemStack item, ToolType type, String id, Map<String, String> values) {
        item.editPersistentDataContainer(pdc -> {
            pdc.set(keyItem, PersistentDataType.STRING, type.name().toLowerCase(Locale.ROOT));
            pdc.set(keyId, PersistentDataType.STRING, id);
            if (values != null && !values.isEmpty()) {
                pdc.set(keyValues, PersistentDataType.TAG_CONTAINER, writeMap(pdc.getAdapterContext(), values));
            }
        });
    }

    public ToolType getToolType(ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return null;
        }
        String value = item.getPersistentDataContainer().get(keyItem, PersistentDataType.STRING);
        return ToolType.fromString(value);
    }

    public String getToolId(ItemStack item) {
        if (item == null) {
            return null;
        }
        return item.getPersistentDataContainer().get(keyId, PersistentDataType.STRING);
    }

    public Map<String, String> readValues(ItemStack item) {
        if (item == null) {
            return Map.of();
        }
        PersistentDataContainer container = item.getPersistentDataContainer().get(keyValues, PersistentDataType.TAG_CONTAINER);
        return container == null ? Map.of() : readMap(container);
    }

    // ------------------------------------------------------------------
    // 镶嵌数据读写（孔数 / 宝石列表）
    // ------------------------------------------------------------------

    public SocketData readSocketData(ItemStack item) {
        if (item == null) {
            return new SocketData(0, Map.of(), List.of());
        }
        PersistentDataContainerView pdc = item.getPersistentDataContainer();
        int holes = pdc.getOrDefault(keyHoles, PersistentDataType.INTEGER, 0);
        Map<String, Integer> holeSources = readSources(pdc.get(keySources, PersistentDataType.TAG_CONTAINER));
        if (holeSources.isEmpty() && holes > 0) {
            // 兼容旧数据：只有总孔数、没有来源记录时，统一归入 legacy 来源
            holeSources = new LinkedHashMap<>(Map.of("legacy", holes));
        }
        List<SocketedGem> gems = new ArrayList<>();
        PersistentDataContainer[] array = pdc.get(keyGems, PersistentDataType.TAG_CONTAINER_ARRAY);
        if (array != null) {
            for (PersistentDataContainer gemContainer : array) {
                String id = gemContainer.get(keyId, PersistentDataType.STRING);
                String instanceId = gemContainer.get(keyUuid, PersistentDataType.STRING);
                if (id == null || instanceId == null) {
                    continue;
                }
                gems.add(new SocketedGem(id, instanceId, readMap(gemContainer.get(keyValues, PersistentDataType.TAG_CONTAINER)), readList(gemContainer)));
            }
        }
        return new SocketData(holes, holeSources, gems);
    }

    public void writeSocketData(ItemStack item, int holes, Map<String, Integer> holeSources, List<SocketedGem> gems) {
        item.editPersistentDataContainer(pdc -> {
            if (holes <= 0 && holeSources.isEmpty() && gems.isEmpty()) {
                pdc.remove(keyHoles);
                pdc.remove(keyGems);
                pdc.remove(keySources);
                return;
            }
            pdc.set(keyHoles, PersistentDataType.INTEGER, Math.max(0, holes));
            if (holeSources.isEmpty()) {
                pdc.remove(keySources);
            } else {
                pdc.set(keySources, PersistentDataType.TAG_CONTAINER, writeSources(pdc.getAdapterContext(), holeSources));
            }
            if (gems.isEmpty()) {
                pdc.remove(keyGems);
                return;
            }
            PersistentDataContainer[] array = new PersistentDataContainer[gems.size()];
            for (int i = 0; i < gems.size(); i++) {
                SocketedGem gem = gems.get(i);
                PersistentDataContainer gemContainer = pdc.getAdapterContext().newPersistentDataContainer();
                gemContainer.set(keyId, PersistentDataType.STRING, gem.id());
                gemContainer.set(keyUuid, PersistentDataType.STRING, gem.instanceId());
                if (!gem.values().isEmpty()) {
                    gemContainer.set(keyValues, PersistentDataType.TAG_CONTAINER, writeMap(pdc.getAdapterContext(), gem.values()));
                }
                if (!gem.lines().isEmpty()) {
                    writeList(gemContainer, gem.lines());
                }
                array[i] = gemContainer;
            }
            pdc.set(keyGems, PersistentDataType.TAG_CONTAINER_ARRAY, array);
        });
    }

    private PersistentDataContainer writeSources(PersistentDataAdapterContext context, Map<String, Integer> holeSources) {
        PersistentDataContainer container = context.newPersistentDataContainer();
        int index = 0;
        for (Map.Entry<String, Integer> entry : holeSources.entrySet()) {
            container.set(key("s" + index), PersistentDataType.STRING, entry.getKey());
            container.set(key("c" + index), PersistentDataType.INTEGER, entry.getValue());
            index++;
        }
        container.set(keyCount, PersistentDataType.INTEGER, index);
        return container;
    }

    private Map<String, Integer> readSources(PersistentDataContainer container) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (container == null) {
            return result;
        }
        int count = container.getOrDefault(keyCount, PersistentDataType.INTEGER, 0);
        for (int i = 0; i < count; i++) {
            String source = container.get(key("s" + i), PersistentDataType.STRING);
            Integer value = container.get(key("c" + i), PersistentDataType.INTEGER);
            if (source != null && value != null && value > 0) {
                result.put(source, value);
            }
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Lore 操作
    // ------------------------------------------------------------------

    public void removeLoreLines(ItemStack item, List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return;
        }
        if (item.getItemMeta() == null || !item.getItemMeta().hasLore()) {
            return;
        }
        List<String> lore = new ArrayList<>(item.getItemMeta().getLore());
        for (String line : lines) {
            lore.remove(line);
        }
        setLore(item, lore);
    }

    /**
     * 写入 lore：普通行按传统 § 代码解析；包含合并标记的行，标记部分作为字面文本写入，
     * 避免 Paper 的传统 setLore 把 §X 吞掉。
     */
    public void setLore(ItemStack item, List<String> lore) {
        item.editMeta(meta -> {
            if (lore == null || lore.isEmpty()) {
                meta.lore(null);
                return;
            }
            List<Component> components = new ArrayList<>();
            for (String line : lore) {
                components.add(toComponent(line));
            }
            meta.lore(components);
        });
    }

            public static Component toComponent(String line) {
        String colored = colorize(line);
        int markerIndex = colored.indexOf("\u00A7X");
        Component result;
        if (markerIndex < 0) {
            result = text(colored);
        } else {
            // 标记前后的文本都走完整解析（支持 &/§ 与 MiniMessage 渐变），标记本身保持字面文本
            Component prefix = text(colored.substring(0, markerIndex));
            Component suffix = text(colored.substring(markerIndex + 2));
            result = prefix.append(Component.text(colored.substring(markerIndex, markerIndex + 2))).append(suffix);
            result = applyDefaultItalic(result);
        }
        return result;
    }

    /** MiniMessage 解析器（支持 <#RRGGBB>、<gradient:...> 等语法） */
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    /**
     * 插件统一的文本解析入口：支持传统 &/§ 颜色代码、<#RRGGBB> 十六进制颜色，
     * 以及 MiniMessage 语法（<gradient:...>、<italic>、<underlined>、<strikethrough> 等）。
     * 未显式指定斜体的部分默认强制正体。
     */
    public static Component text(String text) {
        if (text == null || text.isEmpty()) {
            return Component.empty();
        }
        try {
            return applyDefaultItalic(MINI_MESSAGE.deserialize(legacyToMiniMessage(text)));
        } catch (Exception e) {
            return applyDefaultItalic(Component.text(text));
        }
    }

    /**
     * 递归地把“未显式设置斜体”的节点补成 false，显式 <italic>/&o 保持 true。
     */
    private static Component applyDefaultItalic(Component component) {
        Component result = component;
        if (component.style().decoration(TextDecoration.ITALIC) == TextDecoration.State.NOT_SET) {
            result = result.decoration(TextDecoration.ITALIC, false);
        }
        List<Component> children = result.children();
        if (!children.isEmpty()) {
            List<Component> mapped = new ArrayList<>();
            for (Component child : children) {
                mapped.add(applyDefaultItalic(child));
            }
            result = result.children(mapped);
        }
        return result;
    }

    /**
     * 把传统颜色代码转换为 MiniMessage 标签：
     * &0-&f、&k&l&m&n&o&r、&x 十六进制（&x&F&F&A&A&0&0）、<#RRGGBB>。
     */
    private static String legacyToMiniMessage(String text) {
        String converted = text.replaceAll("<#([0-9a-fA-F]{6})>", "<color:#$1>");
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = converted.length();
        while (i < len) {
            char c = converted.charAt(i);
            if (c == '&' || c == '\u00A7') {
                if (i + 1 >= len) {
                    sb.append(c);
                    i++;
                    continue;
                }
                char code = Character.toLowerCase(converted.charAt(i + 1));
                switch (code) {
                    case '0' -> sb.append("<black>");
                    case '1' -> sb.append("<dark_blue>");
                    case '2' -> sb.append("<dark_green>");
                    case '3' -> sb.append("<dark_aqua>");
                    case '4' -> sb.append("<dark_red>");
                    case '5' -> sb.append("<dark_purple>");
                    case '6' -> sb.append("<gold>");
                    case '7' -> sb.append("<gray>");
                    case '8' -> sb.append("<dark_gray>");
                    case '9' -> sb.append("<blue>");
                    case 'a' -> sb.append("<green>");
                    case 'b' -> sb.append("<aqua>");
                    case 'c' -> sb.append("<red>");
                    case 'd' -> sb.append("<light_purple>");
                    case 'e' -> sb.append("<yellow>");
                    case 'f' -> sb.append("<white>");
                    case 'k' -> sb.append("<obfuscated>");
                    case 'l' -> sb.append("<bold>");
                    case 'm' -> sb.append("<strikethrough>");
                    case 'n' -> sb.append("<underlined>");
                    case 'o' -> sb.append("<italic>");
                    case 'r' -> sb.append("<reset>");
                    case 'x' -> {
                        int j = i + 2;
                        StringBuilder hex = new StringBuilder("#");
                        int read = 0;
                        while (j < len && read < 6) {
                            char h = converted.charAt(j);
                            if (h == '&' || h == '\u00A7') {
                                j++;
                                continue;
                            }
                            if (isHexDigit(h)) {
                                hex.append(h);
                                read++;
                            }
                            j++;
                        }
                        if (read == 6) {
                            sb.append("<color:").append(hex).append(">");
                            i = j;
                            continue;
                        }
                        sb.append("<reset>");
                    }
                    default -> sb.append(c);
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        return escapeInvalidTags(shortTagAliases(sb.toString()));
    }

    /** MiniMessage 短标签别名统一为全名（兼容 <i>/<u>/<st>/<b>/<obf>） */
    private static String shortTagAliases(String text) {
        String s = text;
        s = s.replace("<i>", "<italic>").replace("</i>", "</italic>");
        s = s.replace("<u>", "<underlined>").replace("</u>", "</underlined>");
        s = s.replace("<st>", "<strikethrough>").replace("</st>", "</strikethrough>");
        s = s.replace("<b>", "<bold>").replace("</b>", "</bold>");
        s = s.replace("<obf>", "<obfuscated>").replace("</obf>", "</obfuscated>");
        return s;
    }

    /** 把不是合法 MiniMessage 标签的 <...> 转义为字面文本（避免 <id> 这类用法被误解析） */
    private static String escapeInvalidTags(String text) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '<') {
                int end = text.indexOf('>', i + 1);
                if (end > i) {
                    String body = text.substring(i + 1, end);
                    if (isValidTag(body)) {
                        sb.append('<').append(body).append('>');
                        i = end + 1;
                        continue;
                    }
                }
                sb.append("\\<");
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static boolean isValidTag(String body) {
        if (body.isEmpty()) {
            return false;
        }
        if (body.charAt(0) == '#') {
            return body.length() == 7 && body.substring(1).matches("[0-9a-fA-F]{6}");
        }
        if (body.charAt(0) == '/') {
            return body.length() > 1 && body.substring(1).matches("[a-zA-Z][a-zA-Z0-9_]*");
        }
        int colon = body.indexOf(':');
        String name = colon > 0 ? body.substring(0, colon) : body;
        if (!name.matches("[a-zA-Z][a-zA-Z0-9_]*")) {
            return false;
        }
        return KNOWN_TAGS.contains(name);
    }

    private static final Set<String> KNOWN_TAGS = Set.of(
            "color", "gradient", "rainbow", "transition", "hover", "click", "keybind", "insertion",
            "newline", "selector", "score", "nbt", "translatable", "font", "reset",
            "bold", "italic", "underlined", "strikethrough", "obfuscated",
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "dark_gray", "blue", "green", "aqua", "red", "light_purple", "yellow", "white"
    );

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }



    // ------------------------------------------------------------------
    // 镶嵌信息 lore（孔位/宝石提示）
    // ------------------------------------------------------------------

    /**
     * 读取上次写入的镶嵌信息行。
     */
    public List<String> readSocketLines(ItemStack item) {
        if (item == null) {
            return List.of();
        }
        PersistentDataContainer container = item.getPersistentDataContainer().get(keySocketLines, PersistentDataType.TAG_CONTAINER);
        return readList(container);
    }

    /**
     * 更新装备上的镶嵌信息 lore：先移除旧信息，再按模版写入新信息。
     * 直接基于组件操作，避免把已有 lore 转成 legacy 字符串再重写，
     * 从而保留属性合并行的真实颜色/格式结构。
     */
    public void applySocketLore(ItemStack item, SocketData data, SocketLoreTemplate template) {
        List<Component> lore = item.lore() == null ? new ArrayList<>() : new ArrayList<>(item.lore());
        // 移除旧的镶嵌信息：新格式按行首标记移除
        lore.removeIf(line -> LegacyComponentSerializer.legacySection().serialize(line).startsWith(SOCKET_MARKER));
        // 旧格式按 PDC 记录的原行移除（迁移）
        List<String> oldLines = readSocketLines(item);
        if (!oldLines.isEmpty()) {
            lore.removeIf(line -> oldLines.contains(LegacyComponentSerializer.legacySection().serialize(line)));
        }

        List<String> newLines = new ArrayList<>();
        if (template.enabled()) {
            // {holes} = 已经镶嵌的孔洞数（宝石数），{max_holes} = 物品可用的孔洞数（已打孔数）
            String holes = String.valueOf(data.gems().size());
            String max = String.valueOf(data.holes());
            String gemCount = String.valueOf(data.gems().size());
            for (String line : template.lines()) {
                newLines.add(socketLine(colorize(line
                        .replace("{holes}", holes)
                        .replace("{max_holes}", max)
                        .replace("{gem_count}", gemCount))));
            }
            int index = 1;
            for (SocketedGem gem : data.gems()) {
                String gemName = resolveGemName(gem.id());
                String gemValues = resolveGemValues(gem);
                for (String line : template.gemLines()) {
                    String base = line
                            .replace("{index}", String.valueOf(index))
                            .replace("{gem}", gemName)
                            .replace("{id}", gem.id())
                            .replace("{values}", gemValues);
                    if (base.contains("{value_lines}")) {
                        List<String> valueLines = resolveGemValueList(gem);
                        if (valueLines.isEmpty()) {
                            continue;
                        }
                        for (String value : valueLines) {
                            newLines.add(socketLine(colorize(base.replace("{value_lines}", value))));
                        }
                    } else {
                        newLines.add(socketLine(colorize(base)));
                    }
                }
                index++;
            }
            if (data.gems().isEmpty() && template.emptyLine() != null && !template.emptyLine().isEmpty()) {
                newLines.add(socketLine(colorize(template.emptyLine()
                        .replace("{holes}", holes)
                        .replace("{max_holes}", max))));
            }
        }

        for (String line : newLines) {
            lore.add(toComponent(line));
        }
        item.editMeta(meta -> {
            if (lore.isEmpty()) {
                meta.lore(null);
            } else {
                meta.lore(lore);
            }
        });
        writeSocketLines(item, newLines);
    }

    /**
     * 生成以镶嵌信息标记开头的 lore 行。
     * 注意：行内容不能恰好等于 §X，否则 SX-Attribute 的 split("§X")[0] 会得到空数组并崩溃；
     * 空行统一写成 §X + 普通空格（玩家视角仍是无字空行，且不会被序列化器丢弃）。
     */
    private static String socketLine(String text) {
        return SOCKET_MARKER + (text == null || text.isEmpty() ? " " : text);
    }

    private String resolveGemName(String id) {
        GemDefinition definition = configs.getGem(id);
        return definition == null ? id : colorize(definition.getName());
    }

    private void writeSocketLines(ItemStack item, List<String> lines) {
        item.editPersistentDataContainer(pdc -> {
            if (lines == null || lines.isEmpty()) {
                pdc.remove(keySocketLines);
                return;
            }
            PersistentDataContainer container = pdc.getAdapterContext().newPersistentDataContainer();
            writeList(container, lines);
            pdc.set(keySocketLines, PersistentDataType.TAG_CONTAINER, container);
        });
    }

    /**
     * 读取属性合并前的原始属性行（属性名 -> 原始 lore 行）。
     */
    public Map<String, String> readBaseLines(ItemStack item) {
        if (item == null) {
            return Map.of();
        }
        PersistentDataContainer container = item.getPersistentDataContainer().get(keyBaseLines, PersistentDataType.TAG_CONTAINER);
        return readMap(container);
    }

    /**
     * 保存属性合并前的原始属性行，用于拆卸/重算时还原。
     */
    public void writeBaseLines(ItemStack item, Map<String, String> baseLines) {
        item.editPersistentDataContainer(pdc -> {
            if (baseLines == null || baseLines.isEmpty()) {
                pdc.remove(keyBaseLines);
                return;
            }
            pdc.set(keyBaseLines, PersistentDataType.TAG_CONTAINER, writeMap(pdc.getAdapterContext(), baseLines));
        });
    }

    /**
     * 生成宝石的数值描述（用于镶嵌信息 lore 的 {values} 占位符）。
     */
    public String resolveGemValues(SocketedGem gem) {
        if (gem == null) {
            return "";
        }
        GemDefinition definition = configs.getGem(gem.id());
        if (definition == null) {
            return gem.id();
        }
        return BuffTypeRegistry.get().values(gem, this);
    }

    /**
     * 生成宝石的数值行列表（每条属性一行），用于 {value_lines} 占位符。
     */
    public List<String> resolveGemValueList(SocketedGem gem) {
        if (gem == null || configs.getGem(gem.id()) == null) {
            return List.of();
        }
        return BuffTypeRegistry.get().valueLines(gem, this);
    }

    // ------------------------------------------------------------------
    // 原版属性（vanilla_attribute）
    // ------------------------------------------------------------------

    /**
     * 重建物品的原版属性修饰符：
     * - 移除本插件此前添加的全部修饰符
     * - 汇总所有已镶嵌原版宝石的属性值（同属性求和）
     * - 把物品原有的同属性 ADD_NUMBER 修饰符合并进去（原样存入 PDC，宝石取下后可还原），
     *   保证 tooltip 只显示一行总值
     */
    public void rebuildVanillaAttributes(ItemStack item, List<SocketedGem> gems) {
        if (item == null || item.getType().isAir()) {
            return;
        }

        // 1. 汇总所有原版宝石按属性 id 的总加成
        Map<String, Double> totals = new LinkedHashMap<>();
        for (SocketedGem gem : gems) {
            GemDefinition definition = configs.getGem(gem.id());
            if (definition == null || !BUFF_TYPE_VANILLA.equalsIgnoreCase(definition.getBuffType())) {
                continue;
            }
            for (String line : definition.getAttribute()) {
                VanillaAttribute parsed = parseVanillaAttribute(line);
                if (parsed == null) {
                    continue;
                }
                String resolved = resolve(parsed.value(), gem.values());
                double amount;
                try {
                    amount = Double.parseDouble(resolved.trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                totals.merge(parsed.id(), amount, Double::sum);
            }
        }

        // 2. 读取当前修饰符：本插件的全部移除；同属性 ADD_NUMBER 原生修饰符进入“可合并池”
        ItemAttributeModifiers current = item.getData(DataComponentTypes.ATTRIBUTE_MODIFIERS);
        List<ItemAttributeModifiers.Entry> keep = new ArrayList<>();
        List<NativeEntry> availableNatives = new ArrayList<>(readVanillaNatives(item));
        if (current != null) {
            for (ItemAttributeModifiers.Entry entry : current.modifiers()) {
                NamespacedKey key = entry.modifier().getKey();
                if (key != null && "mosaicgem".equals(key.getNamespace())) {
                    continue;
                }
                String attributeId = entry.attribute().getKey().toString();
                if (entry.modifier().getOperation() == AttributeModifier.Operation.ADD_NUMBER
                        && totals.containsKey(attributeId)) {
                    availableNatives.add(toNativeEntry(entry));
                    continue;
                }
                keep.add(entry);
            }
        }

        // 3. 按属性消费可合并的原生修饰符，生成合并后的单个修饰符
        Map<String, List<NativeEntry>> nativesByAttribute = new LinkedHashMap<>();
        for (NativeEntry nativeEntry : availableNatives) {
            nativesByAttribute.computeIfAbsent(nativeEntry.attributeId(), k -> new ArrayList<>()).add(nativeEntry);
        }
        List<NativeEntry> storedNatives = new ArrayList<>();
        List<NativeEntry> restoreNatives = new ArrayList<>();
        Map<String, CombinedEntry> combined = new LinkedHashMap<>();
        for (String attributeId : totals.keySet()) {
            List<NativeEntry> natives = nativesByAttribute.remove(attributeId);
            double nativeSum = 0;
            org.bukkit.inventory.EquipmentSlotGroup group = null;
            io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay display = null;
            if (natives != null) {
                for (NativeEntry nativeEntry : natives) {
                    nativeSum += nativeEntry.amount();
                    if (group == null) {
                        group = org.bukkit.inventory.EquipmentSlotGroup.getByName(nativeEntry.group());
                    }
                    if (display == null) {
                        display = "hidden".equals(nativeEntry.display())
                                ? io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay.hidden()
                                : io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay.reset();
                    }
                }
                storedNatives.addAll(natives);
            }
            double amount = totals.get(attributeId) + nativeSum;
            if (amount != 0) {
                combined.put(attributeId, new CombinedEntry(attributeId, amount, group, display));
            }
        }
        for (List<NativeEntry> natives : nativesByAttribute.values()) {
            restoreNatives.addAll(natives);
        }

        // 4. 重建组件：保留项 + 还原的原生修饰符 + 每属性一个合并修饰符
        ItemAttributeModifiers.Builder builder = ItemAttributeModifiers.itemAttributes();
        for (ItemAttributeModifiers.Entry entry : keep) {
            builder.addModifier(entry.attribute(), entry.modifier(), entry.getGroup(), entry.display());
        }
        for (NativeEntry nativeEntry : restoreNatives) {
            addNativeEntry(builder, nativeEntry);
        }
        for (CombinedEntry entry : combined.values()) {
            Attribute attribute;
            try {
                attribute = Registry.ATTRIBUTE.get(NamespacedKey.fromString(entry.attributeId()));
            } catch (IllegalArgumentException e) {
                continue;
            }
            if (attribute == null) {
                continue;
            }
            NamespacedKey modifierKey = new NamespacedKey(plugin, "attr_" + sanitizeKey(entry.attributeId()));
            AttributeModifier modifier = new AttributeModifier(modifierKey, entry.amount(), AttributeModifier.Operation.ADD_NUMBER);
            org.bukkit.inventory.EquipmentSlotGroup group = entry.group();
            io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay display = entry.display();
            if (group != null && display != null) {
                builder.addModifier(attribute, modifier, group, display);
            } else {
                builder.addModifier(attribute, modifier);
            }
        }
        item.setData(DataComponentTypes.ATTRIBUTE_MODIFIERS, builder.build());
        writeVanillaNatives(item, storedNatives);
    }

    private static String sanitizeKey(String attributeId) {
        int index = attributeId.indexOf(':');
        String key = index >= 0 ? attributeId.substring(index + 1) : attributeId;
        return key.replaceAll("[^a-z0-9/._-]", "_");
    }

    private static NativeEntry toNativeEntry(ItemAttributeModifiers.Entry entry) {
        NamespacedKey key = entry.modifier().getKey();
        String keyString = key == null ? "minecraft:unknown" : key.getNamespace() + ":" + key.getKey();
        String group = entry.getGroup() == null ? "ANY" : entry.getGroup().toString();
        return new NativeEntry(
                entry.attribute().getKey().toString(),
                keyString,
                entry.modifier().getAmount(),
                group,
                displayCode(entry.display())
        );
    }

    private static String displayCode(io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay display) {
        if (display != null && display.equals(io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay.hidden())) {
            return "hidden";
        }
        return "reset";
    }

    private static void addNativeEntry(ItemAttributeModifiers.Builder builder, NativeEntry nativeEntry) {
        Attribute attribute;
        try {
            attribute = Registry.ATTRIBUTE.get(NamespacedKey.fromString(nativeEntry.attributeId()));
        } catch (IllegalArgumentException e) {
            return;
        }
        if (attribute == null) {
            return;
        }
        NamespacedKey key;
        try {
            key = NamespacedKey.fromString(nativeEntry.key());
        } catch (IllegalArgumentException e) {
            return;
        }
        AttributeModifier modifier = new AttributeModifier(key, nativeEntry.amount(), AttributeModifier.Operation.ADD_NUMBER);
        org.bukkit.inventory.EquipmentSlotGroup group = org.bukkit.inventory.EquipmentSlotGroup.getByName(nativeEntry.group());
        io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay display = "hidden".equals(nativeEntry.display())
                ? io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay.hidden()
                : io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay.reset();
        if (group != null) {
            builder.addModifier(attribute, modifier, group, display);
        } else {
            builder.addModifier(attribute, modifier);
        }
    }

    private List<NativeEntry> readVanillaNatives(ItemStack item) {
        PersistentDataContainer container = item.getPersistentDataContainer().get(keyVanillaNatives, PersistentDataType.TAG_CONTAINER);
        List<NativeEntry> result = new ArrayList<>();
        if (container == null) {
            return result;
        }
        int count = container.getOrDefault(keyCount, PersistentDataType.INTEGER, 0);
        for (int i = 0; i < count; i++) {
            String attributeId = container.get(key("a" + i), PersistentDataType.STRING);
            String key = container.get(key("k" + i), PersistentDataType.STRING);
            Double amount = container.get(key("v" + i), PersistentDataType.DOUBLE);
            String group = container.get(key("g" + i), PersistentDataType.STRING);
            String display = container.get(key("d" + i), PersistentDataType.STRING);
            if (attributeId != null && key != null && amount != null && group != null && display != null) {
                result.add(new NativeEntry(attributeId, key, amount, group, display));
            }
        }
        return result;
    }

    private void writeVanillaNatives(ItemStack item, List<NativeEntry> entries) {
        item.editPersistentDataContainer(pdc -> {
            if (entries == null || entries.isEmpty()) {
                pdc.remove(keyVanillaNatives);
                return;
            }
            PersistentDataContainer container = pdc.getAdapterContext().newPersistentDataContainer();
            int index = 0;
            for (NativeEntry entry : entries) {
                container.set(key("a" + index), PersistentDataType.STRING, entry.attributeId());
                container.set(key("k" + index), PersistentDataType.STRING, entry.key());
                container.set(key("v" + index), PersistentDataType.DOUBLE, entry.amount());
                container.set(key("g" + index), PersistentDataType.STRING, entry.group());
                container.set(key("d" + index), PersistentDataType.STRING, entry.display());
                index++;
            }
            container.set(keyCount, PersistentDataType.INTEGER, index);
            pdc.set(keyVanillaNatives, PersistentDataType.TAG_CONTAINER, container);
        });
    }

    // ------------------------------------------------------------------
    // 附魔（enchant）
    // ------------------------------------------------------------------

    /**
     * 重建物品的附魔：
     * - 首次镶嵌时把物品现有的全部附魔等级（原版 + CrazyEnchantments）记入 PDC
     * - 汇总所有已镶嵌附魔宝石的等级（同附魔求和）
     * - 最终等级 = 原等级 + 宝石合计，已存在则叠加，不存在则新建
     * - 宝石全部取下后还原为原始等级
     */
    public void rebuildEnchantments(ItemStack item, List<SocketedGem> gems) {
        if (item == null || item.getType().isAir()) {
            return;
        }

        // 1. 汇总附魔宝石按附魔 id 的总等级
        Map<String, Integer> totals = new LinkedHashMap<>();
        for (SocketedGem gem : gems) {
            GemDefinition definition = configs.getGem(gem.id());
            if (definition == null || !BUFF_TYPE_ENCHANT.equalsIgnoreCase(definition.getBuffType())) {
                continue;
            }
            for (String line : definition.getAttribute()) {
                VanillaAttribute parsed = parseVanillaAttribute(line);
                if (parsed == null) {
                    continue;
                }
                String resolved = resolve(parsed.value(), gem.values());
                try {
                    int level = (int) Math.round(Double.parseDouble(resolved.trim()));
                    if (level == 0) {
                        continue;
                    }
                    totals.merge(normalizeEnchantId(parsed.id()), level, Integer::sum);
                } catch (NumberFormatException ignored) {
                    plugin.getLogger().warning("附魔宝石 [" + gem.id() + "] 的附魔等级不是有效数字: " + resolved);
                }
            }
        }

        // 2. 原等级：优先取 PDC 中的原生记录；没有记录的（首次/外部新加）取当前物品等级
        Map<String, Integer> storedNatives = new LinkedHashMap<>(readEnchantNatives(item));
        Map<String, Integer> current = readAllEnchantments(item);
        Set<String> managed = new LinkedHashSet<>(storedNatives.keySet());
        managed.addAll(totals.keySet());

        Map<String, Integer> natives = new LinkedHashMap<>();
        for (String id : managed) {
            Integer stored = storedNatives.get(id);
            natives.put(id, stored != null ? stored : current.getOrDefault(id, 0));
        }

        // 3. 移除受管附魔后按“原等级 + 宝石等级”重写
        for (String id : natives.keySet()) {
            removeManagedEnchantment(item, id);
        }
        for (Map.Entry<String, Integer> entry : natives.entrySet()) {
            int level = entry.getValue() + totals.getOrDefault(entry.getKey(), 0);
            if (level > 0) {
                applyManagedEnchantment(item, entry.getKey(), level);
            }
        }

        // 4. 记录原生等级；全部附魔宝石取下后清空记录（下次镶嵌重新快照）
        if (totals.isEmpty()) {
            writeEnchantNatives(item, Map.of());
        } else {
            writeEnchantNatives(item, natives);
        }
    }

    /**
     * 读取物品上的全部附魔（原版 + CrazyEnchantments），返回规范化 id -> 等级。
     */
    private Map<String, Integer> readAllEnchantments(ItemStack item) {
        Map<String, Integer> result = new LinkedHashMap<>();
        for (Map.Entry<Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            result.put("minecraft:" + entry.getKey().getKey().getKey(), entry.getValue());
        }
        for (Map.Entry<String, Integer> entry : crazyEnchantBridge.getEnchantments(item).entrySet()) {
            result.put(CRAZY_ENCHANT_PREFIX + entry.getKey(), entry.getValue());
        }
        return result;
    }

    private void removeManagedEnchantment(ItemStack item, String id) {
        if (isCrazyEnchantId(id)) {
            String name = crazyNameOf(id);
            if (crazyEnchantBridge.getEnchantments(item).containsKey(name)) {
                crazyEnchantBridge.removeEnchantments(item, List.of(name));
            }
            return;
        }
        Enchantment enchantment = vanillaEnchantment(id);
        if (enchantment != null) {
            item.removeEnchantment(enchantment);
        }
    }

    private void applyManagedEnchantment(ItemStack item, String id, int level) {
        if (isCrazyEnchantId(id)) {
            if (!crazyEnchantBridge.isAvailable()) {
                plugin.getLogger().warning("服务器未安装/启用 CrazyEnchantments，无法镶嵌自定义附魔: " + id);
                return;
            }
            crazyEnchantBridge.setEnchantment(item, crazyNameOf(id), level);
            return;
        }
        Enchantment enchantment = vanillaEnchantment(id);
        if (enchantment == null) {
            plugin.getLogger().warning("未知的附魔 id，已跳过: " + id);
            return;
        }
        try {
            item.addUnsafeEnchantment(enchantment, level);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("无法给物品附加附魔 " + id + " Lv." + level + "（" + e.getMessage() + "）");
        }
    }

    private Map<String, Integer> readEnchantNatives(ItemStack item) {
        PersistentDataContainer container = item.getPersistentDataContainer().get(keyEnchantNatives, PersistentDataType.TAG_CONTAINER);
        Map<String, Integer> result = new LinkedHashMap<>();
        if (container == null) {
            return result;
        }
        int count = container.getOrDefault(keyCount, PersistentDataType.INTEGER, 0);
        for (int i = 0; i < count; i++) {
            String id = container.get(key("e" + i), PersistentDataType.STRING);
            Integer level = container.get(key("l" + i), PersistentDataType.INTEGER);
            if (id != null && level != null) {
                result.put(id, level);
            }
        }
        return result;
    }

    private void writeEnchantNatives(ItemStack item, Map<String, Integer> entries) {
        item.editPersistentDataContainer(pdc -> {
            if (entries == null || entries.isEmpty()) {
                pdc.remove(keyEnchantNatives);
                return;
            }
            PersistentDataContainer container = pdc.getAdapterContext().newPersistentDataContainer();
            int index = 0;
            for (Map.Entry<String, Integer> entry : entries.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                container.set(key("e" + index), PersistentDataType.STRING, entry.getKey());
                container.set(key("l" + index), PersistentDataType.INTEGER, entry.getValue());
                index++;
            }
            container.set(keyCount, PersistentDataType.INTEGER, index);
            pdc.set(keyEnchantNatives, PersistentDataType.TAG_CONTAINER, container);
        });
    }

    /**
     * 规范化附魔 id：裸 id 补 minecraft: 前缀；crazy: 别名统一为 ce:。
     */
    static String normalizeEnchantId(String id) {
        String trimmed = id == null ? "" : id.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith(CRAZY_ENCHANT_PREFIX_ALT)) {
            return CRAZY_ENCHANT_PREFIX + trimmed.substring(CRAZY_ENCHANT_PREFIX_ALT.length());
        }
        if (lower.startsWith(CRAZY_ENCHANT_PREFIX)) {
            return trimmed;
        }
        return trimmed.contains(":")
                ? trimmed.toLowerCase(Locale.ROOT)
                : "minecraft:" + trimmed.toLowerCase(Locale.ROOT);
    }

    static boolean isCrazyEnchantId(String id) {
        return id != null && (id.toLowerCase(Locale.ROOT).startsWith(CRAZY_ENCHANT_PREFIX)
                || id.toLowerCase(Locale.ROOT).startsWith(CRAZY_ENCHANT_PREFIX_ALT));
    }

    static String crazyNameOf(String id) {
        int index = id.indexOf(':');
        return index >= 0 && index + 1 < id.length() ? id.substring(index + 1) : id;
    }

    private static Enchantment vanillaEnchantment(String id) {
        if (id == null) {
            return null;
        }
        String key = id.toLowerCase(Locale.ROOT);
        if (key.startsWith("minecraft:")) {
            key = key.substring("minecraft:".length());
        }
        return Enchantment.getByKey(NamespacedKey.minecraft(key));
    }

    private record NativeEntry(String attributeId, String key, double amount, String group, String display) {
    }

    private record CombinedEntry(
            String attributeId,
            double amount,
            org.bukkit.inventory.EquipmentSlotGroup group,
            io.papermc.paper.datacomponent.item.attribute.AttributeModifierDisplay display
    ) {
    }

    static VanillaAttribute parseVanillaAttribute(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        // 属性行行首若带数字标识符（如 '1: minecraft:attack_damage: xxx'），先剥离再解析
        line = stripLoreIdentifier(line);
        int index = Math.max(line.lastIndexOf(':'), line.lastIndexOf('：'));
        if (index <= 0) {
            return null;
        }
        String id = line.substring(0, index).trim();
        String value = line.substring(index + 1).trim();
        if (id.isEmpty() || value.isEmpty()) {
            return null;
        }
        return new VanillaAttribute(id, value);
    }

    record VanillaAttribute(String id, String value) {
    }

    /**
     * 去掉 lore 文本中的颜色代码（§x）与 <#XXXXXX> 标记，返回纯文本。
     */
    public static String stripLoreText(String line) {
        if (line == null) {
            return "";
        }
        String stripped = line.replaceAll("<#[0-9a-fA-F]{6}>", "");
        stripped = stripped.replaceAll("\u00A7.", "");
        stripped = stripped.replace("\u200B", "").replace("\u200C", "");
        return stripped.trim();
    }

    /**
     * 返回属性行首标识符（第一个「：」或「:」之前的部分并 trim），无标识符返回 null。
     * 标识符仅用于 {@code LoreChange} 配置映射，不参与展示。
     */
    public static String loreIdentifier(String line) {
        int index = loreSeparatorIndex(line);
        return index <= 0 ? null : line.substring(0, index).trim();
    }

    /**
     * 去掉属性行首标识符前缀（用于展示），仅当首段为纯数字标识符（如 1、2）时剥离；
     * 非数字前缀（如 minecraft:attack_damage 的命名空间）不剥离，避免误伤含冒号的 id。
     */
    public static String stripLoreIdentifier(String line) {
        int index = loreSeparatorIndex(line);
        if (index <= 0) {
            return line;
        }
        String prefix = line.substring(0, index).trim();
        if (prefix.isEmpty() || !prefix.matches("\\d+")) {
            return line;
        }
        return line.substring(index + 1).trim();
    }

    private static int loreSeparatorIndex(String line) {
        if (line == null) {
            return -1;
        }
        int wide = line.indexOf('：');
        int ascii = line.indexOf(':');
        if (wide < 0) {
            return ascii;
        }
        if (ascii < 0) {
            return wide;
        }
        return Math.min(wide, ascii);
    }

    // ------------------------------------------------------------------
    // 随机数
    // ------------------------------------------------------------------

    public Map<String, String> rollRandom(GemDefinition definition) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : definition.getRandom().entrySet()) {
            result.put(entry.getKey(), rollValue(entry.getValue()));
        }
        return result;
    }

    private String rollValue(String range) {
        String trimmed = range.trim();
        String[] parts = trimmed.split("[~\\-]", 2);
        if (parts.length != 2) {
            return trimmed;
        }
        double min;
        double max;
        try {
            min = Double.parseDouble(parts[0].trim());
            max = Double.parseDouble(parts[1].trim());
        } catch (NumberFormatException e) {
            return trimmed;
        }
        if (max < min) {
            double temp = min;
            min = max;
            max = temp;
        }
        int decimals = Math.max(decimalsOf(parts[0]), decimalsOf(parts[1]));
        double value = min + (max - min) * ThreadLocalRandom.current().nextDouble();
        return String.format(Locale.ROOT, "%." + decimals + "f", value);
    }

    private int decimalsOf(String text) {
        int index = text.indexOf('.');
        return index < 0 ? 0 : text.length() - index - 1;
    }

    // ------------------------------------------------------------------
    // 文本与 PDC 工具
    // ------------------------------------------------------------------

    public String resolve(String text, Map<String, String> values) {
        if (text == null) {
            return null;
        }
        String result = text;
        if (values != null) {
            for (Map.Entry<String, String> entry : values.entrySet()) {
                result = result.replace("${" + entry.getKey() + "}", entry.getValue());
            }
        }
        return result;
    }

    public static String colorize(String text) {
        return text == null ? null : text.replace('&', '\u00A7');
    }

    public static String newInstanceId() {
        return UUID.randomUUID().toString();
    }

    private NamespacedKey key(String name) {
        return new NamespacedKey(plugin, name);
    }

    private PersistentDataContainer writeMap(PersistentDataAdapterContext context, Map<String, String> values) {
        PersistentDataContainer container = context.newPersistentDataContainer();
        int index = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            container.set(key("n" + index), PersistentDataType.STRING, entry.getKey());
            container.set(key("v" + index), PersistentDataType.STRING, entry.getValue());
            index++;
        }
        container.set(keyCount, PersistentDataType.INTEGER, index);
        return container;
    }

    private Map<String, String> readMap(PersistentDataContainer container) {
        Map<String, String> result = new LinkedHashMap<>();
        if (container == null) {
            return result;
        }
        int count = container.getOrDefault(keyCount, PersistentDataType.INTEGER, 0);
        for (int i = 0; i < count; i++) {
            String name = container.get(key("n" + i), PersistentDataType.STRING);
            String value = container.get(key("v" + i), PersistentDataType.STRING);
            if (name != null && value != null) {
                result.put(name, value);
            }
        }
        return result;
    }

    private void writeList(PersistentDataContainer container, List<String> lines) {
        int index = 0;
        for (String line : lines) {
            container.set(key("l" + index), PersistentDataType.STRING, line);
            index++;
        }
        container.set(keyCount, PersistentDataType.INTEGER, index);
    }

    private List<String> readList(PersistentDataContainer container) {
        List<String> result = new ArrayList<>();
        if (container == null) {
            return result;
        }
        int count = container.getOrDefault(keyCount, PersistentDataType.INTEGER, 0);
        for (int i = 0; i < count; i++) {
            String line = container.get(key("l" + i), PersistentDataType.STRING);
            if (line != null) {
                result.add(line);
            }
        }
        return result;
    }
}
