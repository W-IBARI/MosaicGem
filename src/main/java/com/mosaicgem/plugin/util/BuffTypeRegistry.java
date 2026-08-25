package com.mosaicgem.plugin.util;

import com.mosaicgem.plugin.config.GemDefinition;
import com.mosaicgem.plugin.model.SocketedGem;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * buffType 注册表：所有支持的 buffType 都在这里登记，
 * 校验、镶嵌允许、镶嵌信息展示、生效重建统一走本注册表分发。
 */
public final class BuffTypeRegistry {

    private static final BuffTypeRegistry INSTANCE = new BuffTypeRegistry();

    private final Map<String, BuffTypeHandler> handlers = new LinkedHashMap<>();

    private BuffTypeRegistry() {
        register(new SxAttributeHandler());
        register(new VanillaAttributeHandler());
        register(new CeAttributeHandler());
        register(new EnchantHandler());
        register(new MythicMobSkillHandler());
    }

    public static BuffTypeRegistry get() {
        return INSTANCE;
    }

    private void register(BuffTypeHandler handler) {
        handlers.put(handler.id().toLowerCase(Locale.ROOT), handler);
    }

    public BuffTypeHandler get(String type) {
        return type == null ? null : handlers.get(type.toLowerCase(Locale.ROOT));
    }

    public boolean isKnown(String type) {
        return get(type) != null;
    }

    public Collection<BuffTypeHandler> handlers() {
        return handlers.values();
    }

    public String supportedTypes() {
        return handlers.keySet().stream().collect(Collectors.joining(" / "));
    }

    public boolean usesLoreLines(String type) {
        BuffTypeHandler handler = get(type);
        return handler != null && handler.usesLoreLines();
    }

    /**
     * 镶嵌信息展示：按宝石定义分发给对应处理器；未知类型回退到纯文本行。
     */
    public List<String> valueLines(SocketedGem gem, ItemFactory factory) {
        if (gem == null) {
            return List.of();
        }
        GemDefinition definition = factory.configs().getGem(gem.id());
        if (definition == null) {
            return List.of();
        }
        BuffTypeHandler handler = get(definition.getBuffType());
        if (handler != null) {
            return handler.valueLines(gem, factory);
        }
        List<String> result = new ArrayList<>();
        for (String line : definition.getAttribute()) {
            String text = ItemFactory.stripLoreText(factory.resolve(line, gem.values()));
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return result;
    }

    public String values(SocketedGem gem, ItemFactory factory) {
        return String.join("、", valueLines(gem, factory));
    }

    /**
     * 镶嵌/拆卸后调用所有处理器的重建逻辑（无生效数据的处理器自动跳过）。
     */
    public void rebuildAll(ItemStack item, List<SocketedGem> gems, ItemFactory factory) {
        for (BuffTypeHandler handler : handlers.values()) {
            handler.rebuild(item, gems, factory);
        }
    }
}
