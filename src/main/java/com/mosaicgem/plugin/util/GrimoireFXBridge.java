package com.mosaicgem.plugin.util;

import com.mosaicgem.plugin.MosaicGemPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.LivingEntity;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * GrimoireFX 软依赖桥：**纯反射**调用其状态管理 API（不依赖 GFX jar 参与编译）。
 *
 * <p>调用链（GFX 0.1.0 已核实）：
 * <pre>
 * GrimoireFXPlugin#getManager()      -> StatusManager（public）
 * StatusManager#apply(LivingEntity, effectId, level, Long durationSeconds)
 * StatusManager#remove(LivingEntity, effectId)
 * StatusManager#hasStatus(UUID, effectId)
 * </pre>
 * 时长单位是<b>秒</b>；传 null / 负数时 GFX 会回退用「效果定义自身的时长」
 * （所以效果可以写成 {@code duration: -1} 表示"直到撤销"）。
 *
 * <p>依赖 plugin.yml 的 softdepend 保证 GrimoireFX 先于本插件启用；
 * 未安装时静默降级（gfx_effect 词条不生效，其余功能不受影响）。
 */
public final class GrimoireFXBridge extends SoftDependencyBridge {

    private Object manager;
    private Method applyMethod;
    private Method removeMethod;
    private Method hasStatusMethod;

    public GrimoireFXBridge(MosaicGemPlugin plugin) {
        super(plugin);
    }

    @Override
    protected String pluginName() {
        return "GrimoireFX";
    }

    @Override
    protected void setup() throws Throwable {
        Plugin gfx = Bukkit.getPluginManager().getPlugin(pluginName());
        if (gfx == null) {
            throw new IllegalStateException("GrimoireFX 未加载");
        }
        Object mgr = gfx.getClass().getMethod("getManager").invoke(gfx);
        if (mgr == null) {
            throw new IllegalStateException("GrimoireFX.getManager() 返回 null");
        }
        Class<?> managerClass = mgr.getClass();
        applyMethod = managerClass.getMethod("apply", LivingEntity.class, String.class, int.class, Long.class);
        removeMethod = managerClass.getMethod("remove", LivingEntity.class, String.class);
        hasStatusMethod = managerClass.getMethod("hasStatus", UUID.class, String.class);
        manager = mgr;
    }

    @Override
    protected void onAvailable() {
        plugin().getLogger().info("已接入 GrimoireFX（宝石可用 gfx_effect 词条调用其状态效果）");
    }

    /** 施加状态；{@code durationSeconds} 为秒，null / 负数 = 用效果定义自身的时长。 */
    public boolean apply(LivingEntity entity, String effectId, int level, Long durationSeconds) {
        if (!isAvailable() || entity == null || effectId == null) {
            return false;
        }
        try {
            applyMethod.invoke(manager, entity, effectId, Math.max(1, level), durationSeconds);
            return true;
        } catch (Throwable t) {
            plugin().getLogger().warning("GrimoireFX apply(" + effectId + ") 失败: " + t.getCause());
            return false;
        }
    }

    /** 撤销状态（GFX 侧只会撤它自己施放的、并还原玩家原有同类效果）。 */
    public boolean remove(LivingEntity entity, String effectId) {
        if (!isAvailable() || entity == null || effectId == null) {
            return false;
        }
        try {
            removeMethod.invoke(manager, entity, effectId);
            return true;
        } catch (Throwable t) {
            plugin().getLogger().warning("GrimoireFX remove(" + effectId + ") 失败: " + t.getCause());
            return false;
        }
    }

    /** 该玩家当前是否已有此状态（用于避免重复施放 / 收养已有状态）。 */
    public boolean hasStatus(UUID uuid, String effectId) {
        if (!isAvailable() || uuid == null || effectId == null) {
            return false;
        }
        try {
            Object result = hasStatusMethod.invoke(manager, uuid, effectId);
            return result instanceof Boolean b && b;
        } catch (Throwable t) {
            return false;
        }
    }
}
