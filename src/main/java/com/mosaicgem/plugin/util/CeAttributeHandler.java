package com.mosaicgem.plugin.util;

import com.mosaicgem.plugin.config.AttributeLoreConfig;
import com.mosaicgem.plugin.config.GemDefinition;
import com.mosaicgem.plugin.model.SocketedGem;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ce_attribute：把宝石属性作为 CraftEngine 自定义属性的物品持久词条写入
 * （craftengine:attribute_modifiers），由 CE 26.8+ 属性系统读取生效。
 *
 * <ul>
 *   <li>属性行格式与 {@code vanilla_attribute} 一致：{@code 'bakamc:strength: ${value}'}
 *       （CE 属性 id : 数值，数值支持 ${随机值}）。</li>
 *   <li>词条 scope=weapon：只挂物品、只随攻击结算，不作用于实体面板。</li>
 *   <li>拆卸/变更时按剩余宝石重建；无 CraftEngine 时静默跳过，不影响其他 buffType。</li>
 * </ul>
 */
final class CeAttributeHandler implements BuffTypeHandler {

    @Override
    public String id() {
        return ItemFactory.BUFF_TYPE_CE;
    }

    @Override
    public List<String> valueLines(SocketedGem gem, ItemFactory factory) {
        GemDefinition definition = factory.configs().getGem(gem.id());
        if (definition == null) {
            return List.of();
        }
        AttributeLoreConfig loreConfig = factory.configs().attributeLore();
        List<String> result = new ArrayList<>();
        for (String line : definition.attributeLinesOf(ItemFactory.BUFF_TYPE_CE)) {
            ItemFactory.VanillaAttribute attribute = ItemFactory.parseVanillaAttribute(line);
            if (attribute != null) {
                String name = factory.configs().attributeName(attribute.id());
                String resolved = factory.resolve(attribute.value(), gem.values());
                AttributeLoreConfig.DisplayFormat display = loreConfig.display().get(name);
                if (display != null) {
                    try {
                        double value = Double.parseDouble(resolved.trim()) * display.factor();
                        resolved = String.format(Locale.ROOT, "%." + display.decimals() + "f", value)
                                + display.unit();
                    } catch (NumberFormatException ignored) {
                        // 非数值时保持原样
                    }
                }
                result.add(name + "：" + resolved);
            }
        }
        return result;
    }

    @Override
    public void rebuild(ItemStack item, List<SocketedGem> gems, ItemFactory factory) {
        CeAttributeBridge bridge = factory.ceAttributes();
        if (bridge == null) {
            return;
        }
        List<CeAttributeBridge.Spec> specs = collect(gems, factory);
        bridge.writeModifiers(item, specs);
    }

    private List<CeAttributeBridge.Spec> collect(List<SocketedGem> gems, ItemFactory factory) {
        List<CeAttributeBridge.Spec> result = new ArrayList<>();
        for (SocketedGem gem : gems) {
            GemDefinition definition = factory.configs().getGem(gem.id());
            if (definition == null) {
                continue;
            }
            for (String line : definition.attributeLinesOf(ItemFactory.BUFF_TYPE_CE)) {
                ItemFactory.VanillaAttribute parsed = ItemFactory.parseVanillaAttribute(line);
                if (parsed == null) {
                    continue;
                }
                String resolved = factory.resolve(parsed.value(), gem.values());
                double amount;
                try {
                    amount = Double.parseDouble(resolved.trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                result.add(new CeAttributeBridge.Spec(gem.instanceId(), parsed.id(), amount));
            }
        }
        return result;
    }
}
