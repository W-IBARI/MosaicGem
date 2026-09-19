package com.mosaicgem.plugin.hook.mm;

import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 取值工具：从主手物品上读出宝石的数值。
 *
 * 取值优先级（按"最准确"到"兜底"）：
 *   1) 外部值容器（**推荐**，MosaicGem「外部值」段声明的对外契约数据）：
 *        a) 宝石实例级：mosaicgem:gems[i].external（支持 gem= 过滤与 index 选择）
 *        b) 物品级：mosaicgem:external
 *           装备上 = 全部宝石按 config.yml 策略（first/sum）合并后的聚合值；
 *           宝石物品上 = 它自己的外部值
 *      常量与浮动值都走这里，不需要解析 lore 文本，也不受显示格式/语言文件影响。
 *   2) lore 契约标签：[[socket:值名=数值]] / [socket:...] / <socket:...>
 *      注意：契约写在宝石物品自己的 lore 上，镶嵌后不会复制到武器，所以武器上一般没有；
 *            保留它是为了兼容"直接读宝石物品"的场景。
 *   3) MosaicGem 的宝石随机值（原始 roll）：物品 PDC 里
 *        mosaicgem:gems = [ { mosaicgem:id=宝石名,
 *                             mosaicgem:values={ mosaicgem:count=N,
 *                                                mosaicgem:n0=变量名, mosaicgem:v0=数值,
 *                                                mosaicgem:n1=...,      mosaicgem:v1=..., } } ]
 *      这里存的是**原始 roll 值**，不受公式影响；
 *      手持宝石物品（工具）时则退回读物品级的 mosaicgem:values。
 *   4) 镶嵌信息里的「名称: 数值」行（武器 lore 上有），例如
 *        bakamc_attributes:test_roll: 0.7
 *        测试值: 0.70（+0.70）
 *      适合在 key 直接写 CE 属性 id / LoreChange 显示名时使用。
 *   5) PDC 文本扫描（最后兜底）
 */
public final class SocketReader {

    private static final String NS = "mosaicgem";

    private static final Pattern GEM_HEADER = Pattern.compile("^宝石\\s*\\d+\\s*[:：]\\s*(.+)$");
    private static final Pattern NUMBER = Pattern.compile("-?[0-9]+(?:\\.[0-9]+)?");

    /** 契约标签：三种写法都支持，也支持 socket:宝石名:值名=数值 */
    private static final Pattern SOCKET_TAG = Pattern.compile(
            "<socket:([^>]+)>|\\[\\[socket:([^\\]]+)\\]\\]|\\[socket:([^\\]]+)\\]");

    private SocketReader() {
    }

    public static double read(Player player, String valueKey, String gemId, int index, double def) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return def;
        }

        Double v = readFromExternal(item, valueKey, gemId, index);
        if (v != null) {
            return v;
        }
        v = readFromLoreTags(item, valueKey, gemId, index);
        if (v != null) {
            return v;
        }
        v = readFromGemPdc(item, valueKey, gemId, index);
        if (v != null) {
            return v;
        }
        v = readFromLoreLines(item, valueKey, gemId, index);
        if (v != null) {
            return v;
        }
        v = readFromPdcScan(item, valueKey, index);
        if (v != null) {
            return v;
        }
        return def;
    }

    // ------------------------------------------------------------------ 数据源 1：外部值容器

    /**
     * 读外部值：先看宝石实例级（保留 gem= 过滤与 index 语义），再退回物品级。
     * 物品级在装备上是「全部宝石按策略合并后的聚合值」，在宝石物品上是它自己的外部值。
     */
    private static Double readFromExternal(ItemStack item, String valueKey, String gemId, int index) {
        List<Double> hits = new ArrayList<>();
        Object gemsRaw = pdcValue(item, "gems");
        if (gemsRaw instanceof Object[] rawArray) {
            for (Object rawGem : rawArray) {
                if (!(rawGem instanceof PersistentDataContainer gem)) {
                    continue;
                }
                String id = asString(rawValue(gem, new NamespacedKey(NS, "id")));
                if (gemId != null && !gemId.isEmpty() && id != null && !gemId.equalsIgnoreCase(id)) {
                    continue;
                }
                Double num = lookupInMap(rawValue(gem, new NamespacedKey(NS, "external")), valueKey);
                if (num != null) {
                    hits.add(num);
                }
            }
        }
        Double picked = pick(hits, index);
        if (picked != null) {
            return picked;
        }
        return lookupInMap(pdcValue(item, "external"), valueKey);
    }

    /** 读物品 PDC 上某个键的原始值 */
    private static Object pdcValue(ItemStack item, String key) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return rawValue(meta.getPersistentDataContainer(), new NamespacedKey(NS, key));
    }

    /** 在 MosaicGem 的 {count, n0=值名, v0=数值, ...} 结构里按值名取数 */
    private static Double lookupInMap(Object containerRaw, String valueKey) {
        if (!(containerRaw instanceof PersistentDataContainer container)) {
            return null;
        }
        int count = asInt(rawValue(container, new NamespacedKey(NS, "count")), 64);
        for (int i = 0; i < count; i++) {
            String name = asString(rawValue(container, new NamespacedKey(NS, "n" + i)));
            if (name == null || !name.equalsIgnoreCase(valueKey)) {
                continue;
            }
            Double num = asDouble(rawValue(container, new NamespacedKey(NS, "v" + i)));
            if (num != null) {
                return num;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 数据源 2：契约标签

    private static Double readFromLoreTags(ItemStack item, String valueKey, String gemId, int index) {
        List<Double> hits = new ArrayList<>();
        for (String line : loreLines(item)) {
            for (String tag : extractTags(line)) {
                int eq = tag.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String name = tag.substring(0, eq).trim();
                String gem = null;
                int colon = name.indexOf(':');
                if (colon > 0) {
                    gem = name.substring(0, colon).trim();
                    name = name.substring(colon + 1).trim();
                }
                if (!name.equalsIgnoreCase(valueKey)) {
                    continue;
                }
                if (gemId != null && !gemId.isEmpty() && gem != null && !gem.equalsIgnoreCase(gemId)) {
                    continue;
                }
                Double num = firstNumber(tag.substring(eq + 1));
                if (num != null) {
                    hits.add(num);
                }
            }
        }
        return pick(hits, index);
    }

    private static List<String> extractTags(String line) {
        List<String> tags = new ArrayList<>();
        Matcher m = SOCKET_TAG.matcher(line);
        while (m.find()) {
            for (int g = 1; g <= 3; g++) {
                String found = m.group(g);
                if (found != null && !found.isEmpty()) {
                    tags.add(found.trim());
                    break;
                }
            }
        }
        return tags;
    }

    // ------------------------------------------------------------------ 数据源 3：MosaicGem 宝石随机值（原始 roll）

    private static Double readFromGemPdc(ItemStack item, String valueKey, String gemId, int index) {
        Object gemsRaw = pdcValue(item, "gems");
        if (!(gemsRaw instanceof Object[] rawArray)) {
            // 手持宝石物品（工具）：退回读物品级的 mosaicgem:values
            return gemId == null || gemId.isEmpty() ? lookupInMap(pdcValue(item, "values"), valueKey) : null;
        }

        List<Double> hits = new ArrayList<>();
        for (Object rawGem : rawArray) {
            if (!(rawGem instanceof PersistentDataContainer gem)) {
                continue;
            }
            String id = asString(rawValue(gem, new NamespacedKey(NS, "id")));
            if (gemId != null && !gemId.isEmpty() && id != null && !gemId.equalsIgnoreCase(id)) {
                continue;
            }
            Double num = lookupInMap(rawValue(gem, new NamespacedKey(NS, "values")), valueKey);
            if (num != null) {
                hits.add(num);
            }
        }
        return pick(hits, index);
    }

    // ------------------------------------------------------------------ 数据源 4：镶嵌信息文本行

    private static Double readFromLoreLines(ItemStack item, String valueKey, String gemId, int index) {
        List<Double> hits = new ArrayList<>();
        String currentGem = null;
        for (String line : loreLines(item)) {
            Matcher header = GEM_HEADER.matcher(line);
            if (header.matches()) {
                currentGem = header.group(1).trim();
                continue;
            }
            int at = lastSeparator(line);
            if (at <= 0) {
                continue;
            }
            String name = line.substring(0, at).trim();
            if (!name.equalsIgnoreCase(valueKey)) {
                continue;
            }
            if (gemId != null && !gemId.isEmpty() && currentGem != null && !currentGem.equalsIgnoreCase(gemId)) {
                continue;
            }
            Double num = firstNumber(line.substring(at + 1));
            if (num != null) {
                hits.add(num);
            }
        }
        return pick(hits, index);
    }

    // ------------------------------------------------------------------ 数据源 5：PDC 文本扫描（兜底）

    private static Double readFromPdcScan(ItemStack item, String valueKey, int index) {
        String blob = stringifyAll(item);
        if (blob.isEmpty()) {
            return null;
        }
        Pattern p = Pattern.compile(Pattern.quote(valueKey) + "\\s*[=:]\\s*\"?(-?[0-9]+(?:\\.[0-9]+)?)");
        Matcher m = p.matcher(blob);
        List<Double> hits = new ArrayList<>();
        while (m.find()) {
            try {
                hits.add(Double.parseDouble(m.group(1)));
            } catch (NumberFormatException ignored) {
            }
        }
        return pick(hits, index);
    }

    // ------------------------------------------------------------------ 工具方法

    private static List<String> loreLines(ItemStack item) {
        List<String> out = new ArrayList<>();
        ItemMeta meta = item.getItemMeta();
        if (meta == null || !meta.hasLore() || meta.getLore() == null) {
            return out;
        }
        for (String raw : meta.getLore()) {
            if (raw == null) {
                continue;
            }
            String line = ChatColor.stripColor(raw).trim();
            if (!line.isEmpty()) {
                out.add(line);
            }
        }
        return out;
    }

    private static int lastSeparator(String line) {
        return Math.max(line.lastIndexOf(':'), line.lastIndexOf('：'));
    }

    private static Double firstNumber(String text) {
        Matcher m = NUMBER.matcher(text);
        if (m.find()) {
            try {
                return Double.parseDouble(m.group());
            } catch (NumberFormatException ignored) {
            }
        }
        return null;
    }

    private static String asString(Object v) {
        return v == null ? null : String.valueOf(v);
    }

    private static int asInt(Object v, int def) {
        if (v instanceof Number n) {
            return n.intValue();
        }
        try {
            return v == null ? def : Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static Double asDouble(Object v) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        return v == null ? null : firstNumber(String.valueOf(v));
    }

    /** 把物品 PDC 的所有键值递归展开成字符串（dump 与兜底扫描用） */
    public static String stringifyAll(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        return meta == null ? "" : stringifyContainer(meta.getPersistentDataContainer());
    }

    private static String stringifyContainer(PersistentDataContainer container) {
        StringBuilder sb = new StringBuilder("{");
        for (NamespacedKey key : container.getKeys()) {
            sb.append(key.getNamespace()).append(':').append(key.getKey())
                    .append('=').append(stringifyValue(rawValue(container, key))).append(',');
        }
        return sb.append('}').toString();
    }

    private static String stringifyValue(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof PersistentDataContainer container) {
            return stringifyContainer(container);
        }
        if (value instanceof Object[] array) {
            StringBuilder sb = new StringBuilder("[");
            for (Object o : array) {
                sb.append(stringifyValue(o)).append(',');
            }
            return sb.append(']').toString();
        }
        return String.valueOf(value);
    }

    /** 打印 MosaicGem 的 {count, n0/v0, ...} 值容器 */
    private static void printMap(Player player, PersistentDataContainer container) {
        int count = asInt(rawValue(container, new NamespacedKey(NS, "count")), 0);
        if (count == 0) {
            player.sendMessage("§7（空）");
            return;
        }
        for (int i = 0; i < count; i++) {
            String name = asString(rawValue(container, new NamespacedKey(NS, "n" + i)));
            String value = asString(rawValue(container, new NamespacedKey(NS, "v" + i)));
            player.sendMessage("§7" + name + " = §f" + value);
        }
    }

    /** 依次尝试各种类型把键读出来（常量名随版本变化，所以用反射） */
    private static Object rawValue(PersistentDataContainer pdc, NamespacedKey key) {
        Object v = tryGet(pdc, key, PersistentDataType.STRING);
        if (v != null) {
            return v;
        }
        String[] types = {"INTEGER", "LONG", "DOUBLE", "FLOAT", "BYTE", "SHORT", "BOOLEAN",
                "BYTE_ARRAY", "INT_ARRAY", "LONG_ARRAY", "STRING_ARRAY", "TAG_CONTAINER", "TAG_CONTAINER_ARRAY"};
        for (String type : types) {
            try {
                @SuppressWarnings("unchecked")
                PersistentDataType<Object, Object> dt =
                        (PersistentDataType<Object, Object>) PersistentDataType.class.getField(type).get(null);
                Object got = pdc.get(key, dt);
                if (got != null) {
                    return got;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static <T, Z> Object tryGet(PersistentDataContainer pdc, NamespacedKey key, PersistentDataType<T, Z> type) {
        try {
            return pdc.get(key, type);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Double pick(List<Double> hits, int index) {
        if (hits.isEmpty()) {
            return null;
        }
        int i = Math.max(0, Math.min(index, hits.size() - 1));
        return hits.get(i);
    }

    // ------------------------------------------------------------------ 调试 dump

    public static void dumpMainHand(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            player.sendMessage("§c主手没有物品");
            return;
        }
        player.sendMessage("§6===== MosaicGem 宝石数据 dump =====");
        player.sendMessage("§7物品: §f" + item.getType() + " §7x" + item.getAmount());

        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            player.sendMessage("§c该物品没有 ItemMeta");
            return;
        }

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        int n = 0;
        for (NamespacedKey key : pdc.getKeys()) {
            n++;
            String value = stringifyValue(rawValue(pdc, key));
            if (value.isEmpty()) {
                value = "§c<读不出来>";
            }
            if (value.length() > 500) {
                value = value.substring(0, 500) + "...";
            }
            player.sendMessage("§a" + key.getNamespace() + ":" + key.getKey() + "§7 = §f" + value);
        }
        player.sendMessage("§7PDC 键数量: §f" + n);

        // 外部值单独列出来，优先排查 MosaicGem「外部值」段是否写出
        Object itemExternal = rawValue(pdc, new NamespacedKey(NS, "external"));
        if (itemExternal instanceof PersistentDataContainer ext) {
            player.sendMessage("§6----- 外部值（物品级）-----");
            printMap(player, ext);
        }
        Object gemsRaw = rawValue(pdc, new NamespacedKey(NS, "gems"));
        if (gemsRaw instanceof Object[] rawGems) {
            int gi = 0;
            for (Object rawGem : rawGems) {
                if (!(rawGem instanceof PersistentDataContainer gem)) {
                    continue;
                }
                String gemId = asString(rawValue(gem, new NamespacedKey(NS, "id")));
                Object gemExternal = rawValue(gem, new NamespacedKey(NS, "external"));
                if (!(gemExternal instanceof PersistentDataContainer ext)) {
                    continue;
                }
                player.sendMessage("§6----- 外部值（宝石 " + (gi++) + "：" + gemId + "）-----");
                printMap(player, ext);
            }
        }

        if (meta.hasLore() && meta.getLore() != null) {
            player.sendMessage("§6----- lore -----");
            int i = 0;
            for (String line : meta.getLore()) {
                player.sendMessage("§7[" + (i++) + "] " + (line == null ? "" : line));
            }
        }
        player.sendMessage("§6==========================");
    }
}
