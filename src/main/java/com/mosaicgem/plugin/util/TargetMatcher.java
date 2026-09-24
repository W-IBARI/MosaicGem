package com.mosaicgem.plugin.util;

import org.bukkit.Material;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.EquipmentSlotGroup;

import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 装备类型匹配（SWORD / SPEAR / TRIDENT / AXE / HOE / SHOVEL / PICKAXE / BOW / CROSSBOW / MACE / SHIELD /
 * HELMET / CHESTPLATE / LEGGINGS / BOOTS / ELYTRA）。
 */
public final class TargetMatcher {

    private TargetMatcher() {
    }

    public static boolean matchesType(Material material, String type) {
        if (material == null || type == null) {
            return false;
        }
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "SWORD" -> material.name().endsWith("_SWORD");
            case "SPEAR" -> material.name().endsWith("_SPEAR");
            case "TRIDENT" -> material == Material.TRIDENT;
            case "AXE" -> material.name().endsWith("_AXE");
            case "HOE" -> material.name().endsWith("_HOE");
            case "SHOVEL" -> material.name().endsWith("_SHOVEL");
            case "PICKAXE" -> material.name().endsWith("_PICKAXE");
            case "BOW" -> material == Material.BOW;
            case "CROSSBOW" -> material == Material.CROSSBOW;
            case "MACE" -> material == Material.MACE;
            case "SHIELD" -> material == Material.SHIELD;
            case "HELMET" -> material.name().endsWith("_HELMET");
            case "CHESTPLATE" -> material.name().endsWith("_CHESTPLATE");
            case "LEGGINGS" -> material.name().endsWith("_LEGGINGS");
            case "BOOTS" -> material.name().endsWith("_BOOTS");
            case "ELYTRA" -> material == Material.ELYTRA;
            default -> false;
        };
    }

    /**
     * 宝石声明的可镶嵌类型 → 生效槽位集合。
     * <p>空集表示「不限制」：未声明 targetType、或声明了无法识别的类型时沿用旧行为（任意槽位）。
     * <p>返回值不可修改，可直接用于圈定 ItemStack 所在的槽位。
     */
    public static Set<EquipmentSlot> slotsOf(Collection<String> types) {
        if (types == null || types.isEmpty()) {
            return Set.of();
        }
        Set<EquipmentSlot> slots = EnumSet.noneOf(EquipmentSlot.class);
        for (String type : types) {
            Set<EquipmentSlot> mapped = slotsOfType(type);
            if (mapped == null) {
                // 出现无法识别的类型：整颗宝石按「不限制」处理，避免误判后效果静默失效
                return Set.of();
            }
            slots.addAll(mapped);
        }
        return Collections.unmodifiableSet(slots);
    }

    /**
     * 把 {@link #slotsOf(Collection)} 的结果收敛成物品属性修饰符需要的槽位组；
     * 空集返回 {@code null}（调用方维持不加限制的旧行为）。
     */
    public static EquipmentSlotGroup slotGroupOf(Collection<String> types) {
        Set<EquipmentSlot> slots = slotsOf(types);
        if (slots.isEmpty()) {
            return null;
        }
        if (slots.size() == 1) {
            return switch (slots.iterator().next()) {
                case HEAD -> EquipmentSlotGroup.HEAD;
                case CHEST -> EquipmentSlotGroup.CHEST;
                case LEGS -> EquipmentSlotGroup.LEGS;
                case FEET -> EquipmentSlotGroup.FEET;
                case HAND -> EquipmentSlotGroup.MAINHAND;
                case OFF_HAND -> EquipmentSlotGroup.OFFHAND;
                default -> null;
            };
        }
        if (slots.equals(EnumSet.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND))) {
            return EquipmentSlotGroup.HAND;
        }
        // 多个不同槽位（如 头 + 手）没有对应的槽位组，维持不加限制
        return null;
    }

    /** 单个类型的槽位映射；无法识别返回 {@code null}。 */
    private static Set<EquipmentSlot> slotsOfType(String type) {
        if (type == null) {
            return null;
        }
        return switch (type.toUpperCase(Locale.ROOT)) {
            case "HELMET" -> Set.of(EquipmentSlot.HEAD);
            case "CHESTPLATE", "ELYTRA" -> Set.of(EquipmentSlot.CHEST);
            case "LEGGINGS" -> Set.of(EquipmentSlot.LEGS);
            case "BOOTS" -> Set.of(EquipmentSlot.FEET);
            case "SHIELD" -> Set.of(EquipmentSlot.HAND, EquipmentSlot.OFF_HAND);
            case "SWORD", "SPEAR", "TRIDENT", "AXE", "HOE", "SHOVEL", "PICKAXE",
                 "BOW", "CROSSBOW", "MACE" -> Set.of(EquipmentSlot.HAND);
            default -> null;
        };
    }
}
