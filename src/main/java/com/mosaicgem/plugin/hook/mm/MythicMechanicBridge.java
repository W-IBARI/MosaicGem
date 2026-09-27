package com.mosaicgem.plugin.hook.mm;

import com.mosaicgem.plugin.MosaicGemPlugin;
import com.mosaicgem.plugin.util.CeAttributeBridge;
import io.lumine.mythic.api.adapters.AbstractEntity;
import io.lumine.mythic.api.adapters.AbstractLocation;
import io.lumine.mythic.api.config.MythicLineConfig;
import io.lumine.mythic.api.skills.INoTargetSkill;
import io.lumine.mythic.api.skills.ITargetedEntitySkill;
import io.lumine.mythic.api.skills.ITargetedLocationSkill;
import io.lumine.mythic.api.skills.SkillMetadata;
import io.lumine.mythic.api.skills.SkillResult;
import io.lumine.mythic.api.skills.placeholders.PlaceholderDouble;
import io.lumine.mythic.api.skills.placeholders.PlaceholderInt;
import io.lumine.mythic.bukkit.MythicBukkit;
import io.lumine.mythic.bukkit.events.MythicMechanicLoadEvent;
import io.lumine.mythic.core.skills.SkillExecutor;
import io.lumine.mythic.core.skills.SkillMechanic;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Monster;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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

    /** 渴血宝石用的机制名：在 MM 技能里开一个吸血窗口，数值结算由插件在伤害事件里完成 */
    public static final String BLOODTHIRST_NAME = "bloodthirst";

    /** 蔑视宝石用的机制名：读施法者主手武器的 CE 主属性（力量/魔力合并值，取较大者）写进技能变量 */
    public static final String WEAPON_ATTR_NAME = "weapexattr";

    /** 蔑视宝石的斩杀机制：扫描周围 → 血量低于斩杀线 → 立刻死亡（全在插件侧做） */
    public static final String CONTEMPT_NAME = "contempt";

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
        plugin.getLogger().info("已注册 MythicMobs mechanic [" + MECHANIC_NAME + ", " + BLOODTHIRST_NAME
                + ", " + WEAPON_ATTR_NAME + ", " + CONTEMPT_NAME + "]");
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
            plugin.getLogger().info("已让 MythicMobs 重新解析技能文件，" + MECHANIC_NAME + " / " + BLOODTHIRST_NAME
                    + " / " + WEAPON_ATTR_NAME + " 现在应当生效");
        } catch (Throwable t) {
            plugin.getLogger().warning("触发 MythicMobs 技能重解析失败，请手动执行 /mm reload: " + t);
        }
    }

    /** MM 解析技能文件时派发：名字匹配就注册对应机制的实例 */
    @EventHandler
    public void onMechanicLoad(MythicMechanicLoadEvent event) {
        if (event.getMechanicName() == null) {
            return;
        }
        String name = event.getMechanicName().trim();
        try {
            if (MECHANIC_NAME.equalsIgnoreCase(name)) {
                event.register(new SocketValueMechanic(event.getContainer().getManager(), event.getConfig(), this));
            } else if (BLOODTHIRST_NAME.equalsIgnoreCase(name)) {
                event.register(new BloodthirstMechanic(event.getContainer().getManager(), event.getConfig(), this));
            } else if (WEAPON_ATTR_NAME.equalsIgnoreCase(name)) {
                event.register(new WeaponAttrMechanic(event.getContainer().getManager(), event.getConfig(), this));
            } else if (CONTEMPT_NAME.equalsIgnoreCase(name)) {
                event.register(new ContemptMechanic(event.getContainer().getManager(), event.getConfig(), this));
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("注册 " + name + " 实例失败: " + t);
        }
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

    /** 解析 {@code attrs=} 参数（逗号分隔的 CE 属性 id 列表）。 */
    private static List<String> parseAttributeIds(String raw) {
        List<String> parsed = new ArrayList<>();
        for (String part : raw.split(",")) {
            if (!part.isBlank()) {
                parsed.add(part.trim());
            }
        }
        return parsed;
    }

    /**
     * 读施法者武器的主属性：主手优先（读不到再试副手），在给定属性列表里取较大者。
     * 走 CE 的"物品定义 + 物品持久词条"合并口径（与本服 lifesteal.js / CE 伤害公式一致）。
     *
     * @return 读不到时返回 0
     */
    private double readWeaponMainAttribute(Player player, List<String> attributeIds) {
        CeAttributeBridge ce = plugin.ceAttributes();
        if (ce == null || !ce.isAvailable()) {
            return 0D;
        }
        double best = Double.NaN;
        for (ItemStack item : new ItemStack[]{
                player.getInventory().getItemInMainHand(),
                player.getInventory().getItemInOffHand()}) {
            for (String attributeId : attributeIds) {
                double value = ce.readWeaponAttribute(item, attributeId);
                if (!Double.isNaN(value) && (Double.isNaN(best) || value > best)) {
                    best = value;
                }
            }
            if (!Double.isNaN(best)) {
                break;
            }
        }
        return Double.isNaN(best) ? 0D : best;
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
                Player player = bridge.resolvePlayer(data, target);
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
    }

    /**
     * bloodthirst 机制本体（渴血宝石）：只负责"开一个吸血窗口"，不做任何数值结算。
     *
     * <p>用法：{@code bloodthirst{pct=18;dur=10;cap=6;over=30}} —— 百分比吸血率 / 窗口秒数 /
     * 单次回复上限 / 溢出转吸收的持续秒数。实战数值由 {@code BloodthirstListener} 在伤害
     * 事件（MONITOR）里按 CE 结算后的最终伤害计算，见 BloodthirstService。
     *
     * <p>无论成败都返回 {@link SkillResult#SUCCESS}，避免中断技能链。
     */
    private static final class BloodthirstMechanic extends SkillMechanic
            implements ITargetedEntitySkill, INoTargetSkill {

        private final MythicMechanicBridge bridge;
        // ⚠ 必须用 Placeholder* 取值：getDouble()/getInteger() 在【技能文件解析时】就把值定死了，
        //   那时 skill.var.pct 还不存在（施放时才由 socketvalue 写入）→ 只会拿到默认值（踩过）
        private final PlaceholderDouble pct;
        private final PlaceholderInt duration;
        private final PlaceholderDouble cap;
        private final PlaceholderInt absorbSeconds;

        BloodthirstMechanic(SkillExecutor executor, MythicLineConfig config, MythicMechanicBridge bridge) {
            super(executor, BLOODTHIRST_NAME, config);
            this.bridge = bridge;
            this.pct = config.getPlaceholderDouble(new String[]{"pct", "percent", "p"}, 15D);
            this.duration = config.getPlaceholderInteger(new String[]{"dur", "duration", "d"}, 10);
            this.cap = config.getPlaceholderDouble(new String[]{"cap", "max", "c"}, 6D);
            this.absorbSeconds = config.getPlaceholderInteger(new String[]{"over", "absorb", "a"}, 30);
        }

        @Override
        public SkillResult castAtEntity(SkillMetadata data, AbstractEntity target) {
            return apply(data, target);
        }

        @Override
        public SkillResult cast(SkillMetadata data) {
            return apply(data, null);
        }

        private SkillResult apply(SkillMetadata data, AbstractEntity target) {
            try {
                Player player = bridge.resolvePlayer(data, target);
                if (player == null) {
                    bridge.logOnce("bt-no-player", BLOODTHIRST_NAME + ": 未能确定玩家（目标不是玩家，且施法者也不是玩家），已跳过");
                    return SkillResult.SUCCESS;
                }
                AbstractEntity meta = target != null ? target
                        : (data.getCaster() != null ? data.getCaster().getEntity() : null);
                double pctValue = pct.get(data, meta);
                int durationValue = duration.get(data, meta);
                double capValue = cap.get(data, meta);
                int absorbValue = absorbSeconds.get(data, meta);
                bridge.plugin.bloodthirst().open(player, pctValue, durationValue, capValue, absorbValue);
                bridge.plugin.getLogger().info(BLOODTHIRST_NAME + ": 已开启吸血窗口（" + player.getName()
                        + "，吸血率 " + pctValue + "%，" + durationValue + " 秒，单次上限 " + capValue
                        + "，溢出吸收 " + absorbValue + " 秒）");
            } catch (Throwable t) {
                bridge.logOnce("bt-error", BLOODTHIRST_NAME + " 开启窗口失败: " + t);
            }
            return SkillResult.SUCCESS;
        }
    }

    /**
     * weapexattr 机制（蔑视宝石用）：读施法者【主手武器】的 CE 自定义属性**合并值**
     * （物品定义 + 物品上的持久词条，口径与本服 lifesteal.js / CE 伤害公式一致），
     * 在多个属性里取最大值，写进技能变量供 MM 做算术。
     *
     * <p>用法：{@code weapexattr{var=atk}} → 默认取 力量 与 魔力 中较大的那个；
     * 属性表可用 {@code attrs=bakamc_attributes:strength,bakamc_attributes:magic_power} 自定义。
     *
     * <p>无论成败都返回 {@link SkillResult#SUCCESS}，避免中断技能链。
     */
    private static final class WeaponAttrMechanic extends SkillMechanic
            implements ITargetedEntitySkill, INoTargetSkill {

        private final MythicMechanicBridge bridge;
        private final String varName;
        private final List<String> attributeIds;
        private final AtomicBoolean firstRun = new AtomicBoolean(false);

        WeaponAttrMechanic(SkillExecutor executor, MythicLineConfig config, MythicMechanicBridge bridge) {
            super(executor, WEAPON_ATTR_NAME, config);
            this.bridge = bridge;
            this.varName = config.getString(new String[]{"var", "v"}, "weaponattr");
            String attrs = config.getString(new String[]{"attrs", "attr", "a"},
                    "bakamc_attributes:strength,bakamc_attributes:magic_power");
            List<String> parsed = new ArrayList<>();
            for (String part : attrs.split(",")) {
                if (!part.isBlank()) {
                    parsed.add(part.trim());
                }
            }
            this.attributeIds = parsed;
        }

        @Override
        public SkillResult castAtEntity(SkillMetadata data, AbstractEntity target) {
            return apply(data, target);
        }

        @Override
        public SkillResult cast(SkillMetadata data) {
            return apply(data, null);
        }

        private SkillResult apply(SkillMetadata data, AbstractEntity target) {
            try {
                Player player = bridge.resolvePlayer(data, target);
                if (player == null) {
                    bridge.logOnce("wea-no-player", WEAPON_ATTR_NAME + ": 未能确定玩家（目标不是玩家，且施法者也不是玩家），已跳过");
                    return SkillResult.SUCCESS;
                }
                CeAttributeBridge ce = bridge.plugin.ceAttributes();
                if (ce == null || !ce.isAvailable()) {
                    bridge.logOnce("wea-no-ce", WEAPON_ATTR_NAME + ": CraftEngine 属性桥接不可用，无法读取武器主属性");
                    return SkillResult.SUCCESS;
                }
                double resolved = bridge.readWeaponMainAttribute(player, attributeIds);
                data.getVariables().putDouble(varName, resolved);
                if (firstRun.compareAndSet(false, true)) {
                    bridge.plugin.getLogger().info(WEAPON_ATTR_NAME + " 首次执行：" + player.getName()
                            + " 主手武器主属性（力量/魔力取较大者）= " + resolved + "（写入 skill.var." + varName + "）");
                }
            } catch (Throwable t) {
                bridge.logOnce("wea-error", WEAPON_ATTR_NAME + " 读取武器主属性失败: " + t);
            }
            return SkillResult.SUCCESS;
        }
    }

    /**
     * contempt 机制（蔑视宝石的"斩杀"本体）：把"扫周围 → 血量低于斩杀线 → 立刻死亡"整套放在插件里，
     * MM 只负责按窗口周期调用它。
     *
     * <p><b>为什么不放 MythicMobs（2026-09-28 实测）</b>：MM 的 {@code ?health{a=…}} 条件**不支持动态值**
     * —— 写成 {@code a=<<caster.var.thr>>} 或 {@code a=0-<caster.var.thr>} 都会被静默丢弃、退化成恒真
     * （判别实验：阈值 10 对 20 血的怪仍命中）；而 {@code damage{…}} 会被本服 melee_block.js 压成 1 点。
     * 所以"斩杀线随宝石 roll 变化"只能在插件里算。
     *
     * <p>用法：{@code contempt{n=<caster.var.mie_n>;cap=250;radius=4;debug=true}}
     * <ul>
     *   <li>{@code n}：斩杀线百分比（武器主属性 × n%）</li>
     *   <li>{@code cap}：斩杀线硬上限（默认 250）</li>
     *   <li>{@code radius}：扫描半径（默认 4 格，按真实球面距离判定）</li>
     *   <li>{@code attrs}：参与取值的 CE 属性，默认 力量/魔力 取较大者</li>
     *   <li>{@code debug}：true 时每次扫描打一行日志（排查用）</li>
     * </ul>
     * 只对"敌对怪"生效：原版 {@link Monster} 或 MythicMobs 怪；排除玩家、已驯服宠物、NPC、盔甲架、
     * 无敌实体与死亡实体。只杀**严格低于**斩杀线的（等于斩杀线不动）。
     */
    private static final class ContemptMechanic extends SkillMechanic
            implements ITargetedEntitySkill, INoTargetSkill {

        private final MythicMechanicBridge bridge;
        private final PlaceholderDouble pct;
        private final PlaceholderDouble cap;
        private final PlaceholderDouble radius;
        private final List<String> attributeIds;
        private final boolean debug;

        ContemptMechanic(SkillExecutor executor, MythicLineConfig config, MythicMechanicBridge bridge) {
            super(executor, CONTEMPT_NAME, config);
            this.bridge = bridge;
            this.pct = config.getPlaceholderDouble(new String[]{"n", "pct", "percent"}, 100D);
            this.cap = config.getPlaceholderDouble(new String[]{"cap", "max"}, 250D);
            this.radius = config.getPlaceholderDouble(new String[]{"radius", "r"}, 4D);
            this.attributeIds = parseAttributeIds(config.getString(new String[]{"attrs", "attr"},
                    "bakamc_attributes:strength,bakamc_attributes:magic_power"));
            this.debug = config.getBoolean(new String[]{"debug", "dbg"}, false);
        }

        @Override
        public SkillResult castAtEntity(SkillMetadata data, AbstractEntity target) {
            return apply(data, target);
        }

        @Override
        public SkillResult cast(SkillMetadata data) {
            return apply(data, null);
        }

        private SkillResult apply(SkillMetadata data, AbstractEntity target) {
            try {
                Player player = bridge.resolvePlayer(data, target);
                if (player == null) {
                    bridge.logOnce("ct-no-player", CONTEMPT_NAME + ": 未能确定玩家（目标不是玩家，且施法者也不是玩家），已跳过");
                    return SkillResult.SUCCESS;
                }
                double attribute = bridge.readWeaponMainAttribute(player, attributeIds);
                double percent = pct.get(data, target);
                double limit = Math.min(attribute * percent / 100D, cap.get(data, target));
                if (limit <= 0D) {
                    if (debug) {
                        bridge.plugin.getLogger().info(CONTEMPT_NAME + "：" + player.getName()
                                + " 主属性=" + attribute + " → 斩杀线=" + limit + "，本次不斩杀");
                    }
                    return SkillResult.SUCCESS;
                }

                double range = Math.max(0.5D, radius.get(data, target));
                Location origin = player.getLocation();
                int candidates = 0;
                int killed = 0;
                for (Entity entity : player.getNearbyEntities(range, range, range)) {
                    if (!(entity instanceof LivingEntity living) || entity instanceof Player) {
                        continue;
                    }
                    if (living.isDead() || living.getHealth() <= 0D || living.isInvulnerable()) {
                        continue;
                    }
                    if (entity.getLocation().distanceSquared(origin) > range * range) {
                        continue;
                    }
                    if (!isExecuteTarget(living)) {
                        continue;
                    }
                    candidates++;
                    if (living.getHealth() >= limit) {
                        continue;   // ⚠ 只杀严格低于斩杀线的
                    }
                    living.setHealth(0D);
                    killed++;
                    feedback(living);
                }
                if (debug || killed > 0) {
                    bridge.plugin.getLogger().info(CONTEMPT_NAME + "：" + player.getName()
                            + " 主属性=" + attribute + " n=" + percent + " 斩杀线=" + limit
                            + " 候选怪=" + candidates + " 击杀=" + killed);
                }
            } catch (Throwable t) {
                bridge.logOnce("ct-error", CONTEMPT_NAME + " 执行失败: " + t);
            }
            return SkillResult.SUCCESS;
        }

        /** 敌对怪过滤：原版 Monster 或 MythicMobs 怪；排除宠物/NPC/盔甲架。 */
        private static boolean isExecuteTarget(LivingEntity entity) {
            if (entity instanceof ArmorStand) {
                return false;
            }
            if (entity instanceof Tameable tameable && tameable.isTamed()) {
                return false;
            }
            if (entity.hasMetadata("NPC")) {
                return false;
            }
            if (entity instanceof Monster) {
                return true;
            }
            return MythicBukkit.inst().getMobManager().isActiveMob(entity.getUniqueId());
        }

        /** 击杀反馈（少量灵魂粒子 + 一声闷响）。 */
        private static void feedback(LivingEntity entity) {
            try {
                Location at = entity.getLocation().add(0D, 0.8D, 0D);
                entity.getWorld().spawnParticle(Particle.SOUL, at, 8, 0.3D, 0.4D, 0.3D, 0.01D);
                entity.getWorld().playSound(at, Sound.ENTITY_WITHER_HURT, 0.4F, 1.7F);
            } catch (Throwable ignored) {
            }
        }
    }
}
