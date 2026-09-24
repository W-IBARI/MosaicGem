package com.mosaicgem.plugin.util;

import com.mosaicgem.plugin.MosaicGemPlugin;
import com.mosaicgem.plugin.config.ConfigManager;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.logging.Level;

/**
 * MythicCrucible 软兼容桥（运行时反射，编译期不依赖）。
 *
 * <p>MythicCrucible 的物品技能系统通过 {@code Profile.runSkills(trigger, ...)} 统一触发
 * （SWING / USE / RIGHTCLICK 等），并会调用玩家 Profile 上注册的外部 {@code SkillHolder}。
 * 本桥为每个在线玩家注册一个外部 SkillHolder 代理：
 * <ul>
 *   <li>触发事件完全由 MythicCrucible 自己的监听器负责，插件不再写技能触发监听</li>
 *   <li>触发时按宝石配置的技能名（支持 {@code 技能名 @触发器} 格式）调用 MythicMobs 施放技能</li>
 *   <li>技能冷却、条件、目标选择仍由 MythicMobs 自身规则处理</li>
 * </ul>
 */
public final class MythicCrucibleBridge extends SoftDependencyBridge {

    private static final String PLUGIN_NAME = "MythicCrucible";
    private static final String SKILL_HOLDER_CLASS = "io.lumine.mythiccrucible.items.SkillHolder";

    private final ConfigManager configs;
    private final ItemFactory factory;
    private final MythicMobsBridge mythicMobs;
    private final MythicSkillExecutor skillExecutor;

    private Class<?> skillHolderClass;
    private Method getProfileManager;
    private Method getPlayerProfile;
    private Method registerExternalHolder;
    private Method unregisterExternalHolder;
    private Method adaptEntity;
    private Method triggerName;

    private final Map<Player, Object> holders = new LinkedHashMap<>();

    public MythicCrucibleBridge(MosaicGemPlugin plugin, ConfigManager configs, ItemFactory factory, MythicMobsBridge mythicMobs) {
        super(plugin);
        this.configs = configs;
        this.factory = factory;
        this.mythicMobs = mythicMobs;
        this.skillExecutor = new MythicSkillExecutor(configs, factory, mythicMobs);
    }

    @Override
    protected String pluginName() {
        return PLUGIN_NAME;
    }

    @Override
    protected void setup() throws Throwable {
        Plugin crucible = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        skillHolderClass = Class.forName(SKILL_HOLDER_CLASS);

        Class<?> mainClass = Class.forName("io.lumine.mythiccrucible.MythicCrucible");
        getProfileManager = mainClass.getMethod("getProfileManager");
        Object profileManager = getProfileManager.invoke(crucible);
        if (profileManager == null) {
            throw new IllegalStateException("MythicCrucible ProfileManager 为 null");
        }
        getPlayerProfile = profileManager.getClass().getMethod("getPlayerProfile", Player.class);

        Class<?> profileClass = Class.forName("io.lumine.mythiccrucible.profiles.Profile");
        registerExternalHolder = profileClass.getMethod("registerExternalHolder", skillHolderClass);
        unregisterExternalHolder = profileClass.getMethod("unregisterExternalHolder", skillHolderClass);

        Class<?> adapterClass = Class.forName("io.lumine.mythic.bukkit.BukkitAdapter");
        adaptEntity = adapterClass.getMethod("adapt", Class.forName("io.lumine.mythic.api.adapters.AbstractEntity"));

        Class<?> triggerClass = Class.forName("io.lumine.mythic.api.skills.SkillTrigger");
        try {
            triggerName = triggerClass.getMethod("name");
        } catch (NoSuchMethodException ignored) {
            triggerName = null;
        }
    }

    @Override
    protected void onAvailable() {
        plugin().getLogger().info("已桥接 MythicCrucible：mm 技能宝石改用 Crucible 物品技能触发（SWING/USE/RIGHTCLICK 等）");
    }

    /**
     * 为玩家注册外部 SkillHolder（加入服务器时调用）。
     */
    public synchronized void registerPlayer(Player player) {
        if (!isAvailable() || player == null || holders.containsKey(player)) {
            return;
        }
        try {
            Object profile = getProfile(player);
            if (profile == null) {
                return;
            }
            Object holder = createHolderProxy(player);
            registerExternalHolder.invoke(profile, holder);
            holders.put(player, holder);
        } catch (ReflectiveOperationException e) {
            plugin().getLogger().log(Level.WARNING, "注册 MythicCrucible 技能持有者失败: " + player.getName(), e);
        }
    }

    /**
     * 移除玩家的外部 SkillHolder（退出服务器时调用）。
     */
    public synchronized void unregisterPlayer(Player player) {
        if (!isAvailable() || player == null) {
            return;
        }
        Object holder = holders.remove(player);
        if (holder == null) {
            return;
        }
        try {
            Object profile = getProfile(player);
            if (profile != null) {
                unregisterExternalHolder.invoke(profile, holder);
            }
        } catch (ReflectiveOperationException e) {
            plugin().getLogger().log(Level.WARNING, "移除 MythicCrucible 技能持有者失败: " + player.getName(), e);
        }
    }

    private Object getProfile(Player player) throws ReflectiveOperationException {
        Object profileManager = getProfileManager.invoke(Bukkit.getPluginManager().getPlugin(PLUGIN_NAME));
        return profileManager == null ? null : getPlayerProfile.invoke(profileManager, player);
    }

    /**
     * 创建实现 MythicCrucible SkillHolder 接口的动态代理。
     */
    private Object createHolderProxy(Player player) {
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            switch (name) {
                case "hasTimerSkills" -> {
                    return false;
                }
                case "runTimerSkills" -> {
                    return null;
                }
                case "getSkills" -> {
                    return new LinkedList<>();
                }
                case "runSkills" -> {
                    if (args == null || args.length < 2) {
                        return null;
                    }
                    if (args.length >= 4 && args[1] != null) {
                        // runSkills(caster, trigger, origin, triggerEntity[, consumer])
                        execute(player, args[1], args.length >= 4 ? args[3] : null);
                        // 故意返回 false：Crucible 在返回 true 时会 event.setCancelled(true)，
                        // 会把原版右键行为一起吃掉（盾牌举不起来、方块交互失效）。
                        // 技能已经在上面施放完了，这里只需要"不认领"这次交互。
                        return false;
                    }
                    // runSkills(parentSkill, metadata)：父技能链由 Crucible/MythicMobs 内部处理，这里不重复施放
                    return null;
                }
                case "toString" -> {
                    return "MosaicGemSkillHolder(" + player.getName() + ")";
                }
                case "hashCode" -> {
                    return System.identityHashCode(proxy);
                }
                case "equals" -> {
                    return proxy == args[0];
                }
                default -> {
                    return null;
                }
            }
        };
        return Proxy.newProxyInstance(skillHolderClass.getClassLoader(), new Class<?>[]{skillHolderClass}, handler);
    }

    /**
     * Crucible 触发某个技能触发器时执行：检查主手装备上的 mm 技能宝石并施放匹配的技能。
     */
    private boolean execute(Player player, Object trigger, Object triggerEntity) {
        String currentTrigger = triggerName(trigger);
        if (currentTrigger == null) {
            return false;
        }
        Entity target = toBukkitEntity(triggerEntity);
        if (target == null) {
            target = player;
        }
        return skillExecutor.cast(player, currentTrigger, target);
    }

    private String triggerName(Object trigger) {
        if (trigger == null) {
            return null;
        }
        try {
            if (triggerName != null) {
                Object name = triggerName.invoke(trigger);
                if (name != null) {
                    return MythicSkillLine.normalizeTrigger(String.valueOf(name));
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // fall through
        }
        return MythicSkillLine.normalizeTrigger(String.valueOf(trigger));
    }

    private Entity toBukkitEntity(Object abstractEntity) {
        if (abstractEntity == null || adaptEntity == null) {
            return null;
        }
        try {
            Object entity = adaptEntity.invoke(null, abstractEntity);
            return entity instanceof Entity bukkitEntity ? bukkitEntity : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

}
