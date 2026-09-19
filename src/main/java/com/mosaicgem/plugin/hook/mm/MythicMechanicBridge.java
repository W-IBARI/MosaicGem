package com.mosaicgem.plugin.hook.mm;

import com.mosaicgem.plugin.MosaicGemPlugin;
import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.adapters.AbstractLocation;
import io.lumine.mythic.api.config.MythicLineConfig;
import io.lumine.mythic.api.skills.INoTargetSkill;
import io.lumine.mythic.api.skills.ITargetedEntitySkill;
import io.lumine.mythic.api.skills.ITargetedLocationSkill;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.SkillResult;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent;
import io.lumine.mythic.core.skills.SkillExecutor;
import io.lumine.mythic.core.skills.SkillMechanic;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 把装备上宝石的数值接进 MythicMobs，提供 {@code socketvalue} mechanic。
 *
 * <p>机制名：{@code socketvalue{var=变量名;key=值名;gem=宝石名;index=第几个;default=默认值}}
 *
 * <p><b>为什么必须继承 {@link SkillMechanic}（而不是用动态代理实现 ISkillMechanic 接口）：</b>
 * MM 解析到未知机制时会用 {@code CustomMechanic} 包装注册进来的 {@code ISkillMechanic}，
 * 而该包装路径派发时用的是变量作用域被隔离的 metadata 副本 —— 往里写的
 * {@code skill.var.*} 传不到后续机制，技能里读到的永远是 UNDEFINED。
 * 只有作为 {@code SkillMechanic} 子类注册（MM 自己的 {@code VariableSetMechanic}、
 * MythicCrucible 的机制都是这么做的）才能与技能链共享变量。此结论来自实测 + 反编译核对。
 *
 * <p><b>与 MythicMobs 的耦合边界（重要）：</b>
 * 本文件是本插件里<b>唯一</b>引用 MythicMobs 类型的类，编译时需要 MM jar 在 classpath 上。
 * 为了让插件本身仍可独立运行/独立构建：
 * <ul>
 *   <li>运行期：{@code MosaicGemPlugin} 通过 {@code Class.forName} 反射加载本类，并用
 *       {@code try/catch} 包住 —— MM 未安装或版本不符时，只是本 mechanic 不可用，
 *       镶嵌/拆卸/lore/外部值/PlaceholderAPI 全部照常</li>
 *   <li>构建期：{@code build.ps1} 检测不到 MM jar 时会跳过编译本文件，插件依然能构建出来</li>
 * </ul>
 */
public final class MythicMechanicBridge implements Listener {

    /** 注册给 MM 的机制名 */
    public static final String MECHANIC_NAME = "socketvalue";

    private final MosaicGemPlugin plugin;
    private final Set<String> reported = new HashSet<>();
    private final AtomicBoolean firstDispatch = new AtomicBoolean(false);
    private final AtomicBoolean firstSuccess = new AtomicBoolean(false);

    private MythicMechanicBridge(MosaicGemPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 由 {@code MosaicGemPlugin} 反射调用（这样主插件代码不需要引用 MM 类型即可编译）。
     *
     * @param plugin 本插件实例
     */
    public static void setup(MosaicGemPlugin plugin) {
        MythicMechanicBridge bridge = new MythicMechanicBridge(plugin);
        Bukkit.getPluginManager().registerEvents(bridge, plugin);
        plugin.getLogger().info("已注册 MythicMobs mechanic [" + MECHANIC_NAME + "]");
        // MythicMobs 作为软依赖会先于本插件启用、技能文件在它启用时就解析完了，那时还不认识
        // socketvalue；所以注册成功后必须让它重新解析一次技能文件，否则技能里只是个未知机制。
        try {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, task -> bridge.reloadSkills(), 20L);
        } catch (Throwable t) {
            bridge.reloadSkills();
        }
    }

    /** 只重解析技能文件（不碰怪物与掉落表，避免把掉落类型重复注册一遍） */
    private void reloadSkills() {
        try {
            MythicBukkit.inst().getSkillManager().loadSkills();
            plugin.getLogger().info("已让 MythicMobs 重新解析技能文件，" + MECHANIC_NAME + " 现在应当生效");
        } catch (Throwable t) {
            plugin.getLogger().warning("触发 MythicMobs 技能重解析失败，请手动执行 /mm reload: " + t);
        }
    }

    /** MM 解析技能文件时派发：名字匹配就注册一个 socketvalue 实例 */
    @EventHandler
    public void onMechanicLoad(MythicMechanicLoadEvent event) {
        if (event.getMechanicName() == null || !MECHANIC_NAME.equalsIgnoreCase(event.getMechanicName().trim())) {
            return;
        }
        try {
            event.register(new SocketValueMechanic(event.getContainer().getManager(), event.getConfig(), this));
        } catch (Throwable t) {
            plugin.getLogger().warning("注册 " + MECHANIC_NAME + " 实例失败: " + t);
        }
    }

    private void logOnce(String reason, String message) {
        if (reported.add(reason)) {
            plugin.getLogger().warning(message);
        }
    }

    /**
     * socketvalue 机制本体：读施法者（或目标玩家）主手的宝石数值，写进技能变量。
     * 无论成败都返回 {@link SkillResult#SUCCESS}，避免中断技能链。
     */
    private static final class SocketValueMechanic extends SkillMechanic
            implements ITargetedEntitySkill, ITargetedLocationSkill, INoTargetSkill {

        private final MythicMechanicBridge bridge;
        private final String varName;
        private final String valueKey;
        private final String gemId;
        private final int index;
        private final double defaultValue;

        SocketValueMechanic(SkillExecutor executor, MythicLineConfig config, MythicMechanicBridge bridge) {
            super(executor, MECHANIC_NAME, config);
            this.bridge = bridge;
            this.varName = config.getString(new String[]{"var", "v"}, "gemvalue");
            this.valueKey = config.getString(new String[]{"key", "k"}, "roll");
            this.gemId = config.getString(new String[]{"gem", "g"}, "");
            this.index = config.getInteger(new String[]{"index", "i"}, 0);
            this.defaultValue = config.getDouble(new String[]{"default", "d"}, 0D);
        }

        @Override
        public SkillResult castAtEntity(SkillMetadata data, AbstractEntity target) {
            return apply(data, target);
        }

        @Override
        public SkillResult castAtLocation(SkillMetadata data, AbstractLocation target) {
            return apply(data, null);
        }

        @Override
        public SkillResult cast(SkillMetadata data) {
            return apply(data, null);
        }

        private SkillResult apply(SkillMetadata data, AbstractEntity target) {
            try {
                Player player = resolvePlayer(data, target);
                if (player == null) {
                    bridge.logOnce("no-player", "socketvalue: 未能确定玩家（目标不是玩家，且施法者也不是玩家），已跳过");
                    return SkillResult.SUCCESS;
                }
                double value = SocketReader.read(player, valueKey, gemId, index, defaultValue);
                if (bridge.firstDispatch.compareAndSet(false, true)) {
                    bridge.plugin.getLogger().info("socketvalue: 首次执行（取值对象 " + player.getName()
                            + "，key=" + valueKey + "）");
                }
                // SkillMechanic 路径与技能链共享 metadata，因此这里写进去的变量后续机制读得到
                data.getVariables().putDouble(varName, value);
                if (bridge.firstSuccess.compareAndSet(false, true)) {
                    bridge.plugin.getLogger().info("socketvalue 首次执行成功：key=" + valueKey + " -> " + value
                            + "（写入 skill.var." + varName + "）");
                }
            } catch (Throwable t) {
                bridge.logOnce("cast-error", "socketvalue 取值失败: " + t);
            }
            return SkillResult.SUCCESS;
        }

        /** 目标若是玩家就用目标，否则回退施法者（两者都必须是玩家） */
        private Player resolvePlayer(SkillMetadata data, AbstractEntity target) {
            Player fromTarget = asPlayer(target);
            if (fromTarget != null) {
                return fromTarget;
            }
            if (data.getCaster() == null) {
                return null;
            }
            return asPlayer(data.getCaster().getEntity());
        }

        private Player asPlayer(AbstractEntity entity) {
            if (entity == null || !entity.isPlayer()) {
                return null;
            }
            Entity bukkit = entity.getBukkitEntity();
            return bukkit instanceof Player player ? player : null;
        }
    }
}
