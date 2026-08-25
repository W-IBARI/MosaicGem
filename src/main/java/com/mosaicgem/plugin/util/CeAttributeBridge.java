package com.mosaicgem.plugin.util;

import com.mosaicgem.plugin.MosaicGemPlugin;
import org.bukkit.inventory.ItemStack;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * CraftEngine 软依赖桥接（纯反射，无编译期依赖）。
 *
 * <p>接入点：CraftEngine 26.8+ 自定义属性的「物品持久词条」机制：
 * <ul>
 *   <li>{@code ItemAttributeModifierStore.read/write}：读写物品 custom data
 *       {@code craftengine:attribute_modifiers}（NBT 序列化后随物品 PDC 持久化）；</li>
 *   <li>CE 内置 persistent provider 在物品属性结算时自动合并这些词条，持有者穿戴后
 *       即进入 {@code bakamc:*} 等自定义属性的运算管线。</li>
 * </ul>
 * 宝石词条统一按 {@code scope: weapon} 写入：只随攻击结算（近战读主手、箭矢读发射时
 * 武器），不作用于实体面板。
 */
public final class CeAttributeBridge extends SoftDependencyBridge {

    /**
     * 一条待写入的宝石属性词条。
     *
     * @param instanceId 宝石实例 id（稳定唯一，用于幂等更新/移除）
     * @param attributeId CE 自定义属性 id（如 {@code bakamc:strength}）
     * @param amount 属性值（add_value）
     */
    public record Spec(String instanceId, String attributeId, double amount) {
    }

    private static final String NAMESPACE = "mosaicgem";

    private Method ceInstance;        // CraftEngine.instance()
    private Method ceItemManager;     // CraftEngine.itemManager()
    private Method ceWrap;            // ItemManager.wrap(ItemStack) -> Item
    private Method storeRead;         // ItemAttributeModifierStore.read(Item) -> List
    private Method storeWrite;        // ItemAttributeModifierStore.write(Item, List)
    private Method modifierId;        // ItemAttributeModifier.id() -> Key
    private Method keyNamespace;      // Key.namespace()
    private Method keyOf;             // Key.of(String)
    private Method scopeById;         // AttributeModifierScope.byId(String)
    private Constructor<?> modifierCtor; // ItemAttributeModifier(Key,Key,double,Key,Scope,Slot)
    private Object mainhandSlot;      // EquipmentSlotGroup.MAINHAND

    CeAttributeBridge(MosaicGemPlugin plugin) {
        super(plugin);
    }

    @Override
    protected String pluginName() {
        return "CraftEngine";
    }

    @Override
    protected void setup() throws Throwable {
        ClassLoader loader = plugin().getServer().getPluginManager()
                .getPlugin(pluginName()).getClass().getClassLoader();
        Class<?> craftEngine = loader.loadClass("net.momirealms.craftengine.core.plugin.CraftEngine");
        Class<?> itemManager = loader.loadClass("net.momirealms.craftengine.core.item.ItemManager");
        Class<?> item = loader.loadClass("net.momirealms.craftengine.core.item.Item");
        Class<?> store = loader.loadClass("net.momirealms.craftengine.core.attribute.modifier.ItemAttributeModifierStore");
        Class<?> modifier = loader.loadClass("net.momirealms.craftengine.core.attribute.modifier.ItemAttributeModifier");
        Class<?> key = loader.loadClass("net.momirealms.craftengine.core.util.Key");
        Class<?> scope = loader.loadClass("net.momirealms.craftengine.core.attribute.modifier.AttributeModifierScope");
        Class<?> slot = loader.loadClass("net.momirealms.craftengine.core.attribute.equipment.EquipmentSlotGroup");

        ceInstance = craftEngine.getMethod("instance");
        ceItemManager = craftEngine.getMethod("itemManager");
        ceWrap = itemManager.getMethod("wrap", Object.class);
        storeRead = store.getMethod("read", item);
        storeWrite = store.getMethod("write", item, List.class);
        modifierId = modifier.getMethod("id");
        keyNamespace = key.getMethod("namespace");
        keyOf = key.getMethod("of", String.class);
        scopeById = scope.getMethod("byId", String.class);
        modifierCtor = modifier.getConstructor(key, key, double.class, key, scope, slot);
        mainhandSlot = slot.getField("MAINHAND").get(null);
    }

    @Override
    protected void onAvailable() {
        plugin().getLogger().info("CraftEngine 属性桥接已启用（ce_attribute 宝石可写入 CE 持久词条）");
    }

    /**
     * 把宝石词条写入 CE 物品持久数据：先移除本插件旧词条，再写入当前全部词条。
     * CraftEngine 缺失或版本过旧时返回 false（调用方静默跳过）。
     *
     * @param item 目标物品（CE 物品或普通物品均可；词条随物品 PDC 持久化）
     * @param specs 当前镶嵌宝石的词条列表
     */
    public boolean writeModifiers(ItemStack item, List<Spec> specs) {
        if (!isAvailable()) {
            return false;
        }
        try {
            Object engine = ceInstance.invoke(null);
            Object manager = ceItemManager.invoke(engine);
            Object ceItem = ceWrap.invoke(manager, item);

            // 读取现有词条，剔除本插件历史词条（id 命名空间为 mosaicgem）
            List<?> existing = (List<?>) storeRead.invoke(null, ceItem);
            List<Object> merged = new ArrayList<>();
            if (existing != null) {
                for (Object m : existing) {
                    if (m == null) {
                        continue;
                    }
                    Object id = modifierId.invoke(m);
                    if (id != null && NAMESPACE.equals(keyNamespace.invoke(id))) {
                        continue;
                    }
                    merged.add(m);
                }
            }

            // 追加当前词条：id = mosaicgem:gem_<instanceId>/<attribute>，scope=weapon，slot=mainhand
            Object operation = keyOf.invoke(null, "minecraft:add_value");
            Object weaponScope = scopeById.invoke(null, "weapon");
            for (Spec spec : specs) {
                Object attributeKey = keyOf.invoke(null, spec.attributeId());
                Object idKey = keyOf.invoke(null, NAMESPACE + ":gem_" + spec.instanceId() + "/" + spec.attributeId());
                Object modifier = modifierCtor.newInstance(attributeKey, idKey, spec.amount(), operation, weaponScope, mainhandSlot);
                merged.add(modifier);
            }

            storeWrite.invoke(null, ceItem, merged);
            return true;
        } catch (Throwable e) {
            plugin().getLogger().warning("写入 CraftEngine 属性词条失败: " + e);
            return false;
        }
    }
}
