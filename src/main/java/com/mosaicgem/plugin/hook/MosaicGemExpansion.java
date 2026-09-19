package com.mosaicgem.plugin.hook;

import com.mosaicgem.plugin.MosaicGemPlugin;
import com.mosaicgem.plugin.config.ConfigManager;
import com.mosaicgem.plugin.model.SocketData;
import com.mosaicgem.plugin.model.SocketedGem;
import com.mosaicgem.plugin.util.ItemFactory;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PlaceholderAPI 扩展：把装备上宝石的「外部值」暴露成占位符。
 *
 * <p>参数以 {@code :} 分隔（兼容 {@code ,} / {@code ;} / {@code .}），最多三段，也支持 PAPI 的
 * {@code {}} 包裹写法（{@code %mosaicgem_{head:roll}%}）：
 * <ul>
 *   <li>{@code %mosaicgem_<值名>%} —— 主手装备，首个命中</li>
 *   <li>{@code %mosaicgem_<槽位>:<值名>%} —— 指定槽位</li>
 *   <li>{@code %mosaicgem_<槽位>:<宝石>:<值名>%} —— 指定槽位 + 指定宝石</li>
 * </ul>
 *
 * <p>槽位：{@code mainhand}(默认) / {@code offhand} / {@code head} / {@code chest} / {@code legs} /
 * {@code feet} / {@code all}（遍历全部装备槽位并按 config.yml 的聚合策略合并）。
 * 宝石段：宝石内部名、显示名，或第 N 颗宝石的序号（从 1 开始）。
 * 取不到值时返回空串，不报错。
 *
 * <p>注意：本扩展直接读玩家背包，理论上应由主线程/玩家所在区域线程调用；
 * 若被异步任务调用导致读取异常，会静默返回空串而非抛异常。
 */
public final class MosaicGemExpansion extends PlaceholderExpansion {

    private static final Map<String, EquipmentSlot> SLOTS = Map.ofEntries(
            Map.entry("mainhand", EquipmentSlot.HAND),
            Map.entry("hand", EquipmentSlot.HAND),
            Map.entry("offhand", EquipmentSlot.OFF_HAND),
            Map.entry("off", EquipmentSlot.OFF_HAND),
            Map.entry("head", EquipmentSlot.HEAD),
            Map.entry("helmet", EquipmentSlot.HEAD),
            Map.entry("chest", EquipmentSlot.CHEST),
            Map.entry("chestplate", EquipmentSlot.CHEST),
            Map.entry("legs", EquipmentSlot.LEGS),
            Map.entry("leggings", EquipmentSlot.LEGS),
            Map.entry("feet", EquipmentSlot.FEET),
            Map.entry("boots", EquipmentSlot.FEET)
    );

    private final MosaicGemPlugin plugin;
    private final ConfigManager configs;
    private final ItemFactory factory;

    public MosaicGemExpansion(MosaicGemPlugin plugin, ConfigManager configs, ItemFactory factory) {
        this.plugin = plugin;
        this.configs = configs;
        this.factory = factory;
    }

    @Override
    public String getIdentifier() {
        return "mosaicgem";
    }

    @Override
    public String getAuthor() {
        return "BakaMC";
    }

    @Override
    public String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer offlinePlayer, String params) {
        if (offlinePlayer == null || params == null || params.isBlank()) {
            return "";
        }
        Player player = offlinePlayer.getPlayer();
        if (player == null || !player.isOnline()) {
            return "";
        }
        String[] parts = split(params);
        String slotName = parts.length >= 2 ? parts[0] : "mainhand";
        String gemName = parts.length >= 3 ? parts[1] : "";
        String key = switch (parts.length) {
            case 1 -> parts[0];
            case 2 -> parts[1];
            default -> parts[2];
        };
        if (key.isBlank()) {
            return "";
        }
        try {
            if ("all".equalsIgnoreCase(slotName)) {
                return lookupAll(player, gemName, key);
            }
            EquipmentSlot slot = SLOTS.get(slotName.toLowerCase(Locale.ROOT));
            if (slot == null) {
                return "";
            }
            return lookup(player.getInventory().getItem(slot), gemName, key);
        } catch (Throwable t) {
            // 跨线程读取背包等异常：静默返回空串，避免影响调用方的占位符解析
            plugin.getLogger().warning("占位符取值失败 (%mosaicgem_" + params + "%): " + t);
            return "";
        }
    }

    /**
     * 单个物品取值：装备走「实时按策略合并」，宝石物品走物品级外部值容器。
     * 实时合并的好处：改 external-aggregate.yml 后 /mg reload 即可生效，无需重新镶嵌。
     */
    private String lookup(ItemStack item, String gemName, String key) {
        if (item == null || item.getType().isAir()) {
            return "";
        }
        SocketData data = factory.readSocketData(item);
        if (!data.gems().isEmpty()) {
            if (gemName == null || gemName.isBlank()) {
                return factory.mergeExternalValues(data.gems()).getOrDefault(key, "");
            }
            SocketedGem gem = findGem(data, gemName);
            return gem == null ? "" : gem.external().getOrDefault(key, "");
        }
        // 宝石物品（工具）等没有镶嵌数据，直接读物品级外部值
        return factory.readExternal(item).getOrDefault(key, "");
    }

    /**
     * 遍历全部装备槽位：把匹配的宝石收集起来，在取值层按策略合并后取值（实时，不读烘焙值）。
     */
    private String lookupAll(Player player, String gemName, String key) {
        List<SocketedGem> gems = new ArrayList<>();
        for (EquipmentSlot slot : new EquipmentSlot[]{
                EquipmentSlot.HAND, EquipmentSlot.OFF_HAND, EquipmentSlot.HEAD,
                EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET}) {
            ItemStack item = player.getInventory().getItem(slot);
            if (item == null || item.getType().isAir()) {
                continue;
            }
            SocketData data = factory.readSocketData(item);
            for (SocketedGem gem : data.gems()) {
                if (gemName == null || gemName.isBlank() || matches(gem, gemName)) {
                    gems.add(gem);
                }
            }
        }
        return factory.mergeExternalValues(gems).getOrDefault(key, "");
    }

    /**
     * 按「序号（1 基）→ 内部名 → 显示名」顺序定位宝石。
     */
    private SocketedGem findGem(SocketData data, String gemName) {
        List<SocketedGem> gems = data.gems();
        if (gems.isEmpty()) {
            return null;
        }
        if (gemName.matches("\\d+")) {
            int index = Integer.parseInt(gemName) - 1;
            return index >= 0 && index < gems.size() ? gems.get(index) : null;
        }
        for (SocketedGem gem : gems) {
            if (matches(gem, gemName)) {
                return gem;
            }
        }
        return null;
    }

    private boolean matches(SocketedGem gem, String gemName) {
        if (gem.id().equalsIgnoreCase(gemName)) {
            return true;
        }
        var definition = configs.getGem(gem.id());
        if (definition == null) {
            return false;
        }
        String display = stripTags(definition.getName());
        return display.equalsIgnoreCase(gemName) || display.equalsIgnoreCase(stripTags(gemName));
    }

    /**
     * 去掉显示名中的颜色/格式标签（§x 与 MiniMessage 标签），便于用显示名定位宝石。
     */
    private static String stripTags(String text) {
        if (text == null) {
            return "";
        }
        return ItemFactory.stripLoreText(text.replaceAll("<[^>]+>", "")).trim();
    }

    /**
     * 拆分参数：去掉 {@code {}} 包裹，按 {@code : , ; .} 切分并过滤空段。
     */
    private static String[] split(String params) {
        String text = params.trim();
        if (text.startsWith("{") && text.endsWith("}") && text.length() > 2) {
            text = text.substring(1, text.length() - 1);
        }
        List<String> parts = new ArrayList<>();
        for (String part : text.split("[:;,.]")) {
            if (!part.isBlank()) {
                parts.add(part.trim());
            }
        }
        return parts.toArray(new String[0]);
    }

    /** 供调试：读取装备级聚合值（未使用，保留给后续 API 统一入口） */
    public Map<String, String> externalOf(ItemStack item) {
        return new LinkedHashMap<>(factory.readExternal(item));
    }
}
