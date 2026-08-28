package com.mosaicgem.plugin.config;

import com.mosaicgem.plugin.MosaicGemPlugin;
import com.mosaicgem.plugin.util.BuffTypeRegistry;
import com.mosaicgem.plugin.model.ToolType;
import com.mosaicgem.plugin.util.TargetMatcher;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.CommandSender;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 配置管理：主配置 + 多语言消息 + 宝石 / 打孔器 / 拆卸器。
 */
public class ConfigManager {

    private static final String DEFAULT_LANGUAGE = "zh_cn";
    private static final String LEGACY_MESSAGES_FILE = "messages.yml";

    private final MosaicGemPlugin plugin;

    private FileConfiguration config;
    private FileConfiguration messages;
    private FileConfiguration messageDefaults;
    private FileConfiguration permissions;
    private String language = DEFAULT_LANGUAGE;

    private final Map<String, GemDefinition> gems = new LinkedHashMap<>();
    private final Map<String, PuncherDefinition> punchers = new LinkedHashMap<>();
    private final Map<String, RemoverDefinition> removers = new LinkedHashMap<>();
    /** gemtype 标签全局数量限制（key: 标签名, value: 上限）。 */
    private Map<String, Integer> gemTypeLimits = new LinkedHashMap<>();
    /** 物品类型 -> 孔数上限（层级 2：覆盖全局 settings.max-holes）。 */
    private final Map<String, Integer> maxHolesByType = new LinkedHashMap<>();
    /** 物品 id（材质大写名）-> 孔数上限（层级 3：覆盖类型与全局）。 */
    private final Map<String, Integer> maxHolesById = new LinkedHashMap<>();

    public ConfigManager(MosaicGemPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 数值显示的小数位数（settings.value-decimal-places），用于表达式占位符结果
     * 与属性面板合并/加成的显示。缺省 2，范围 0~10。
     */
    public int valueDecimalPlaces() {
        return Math.max(0, Math.min(10,
                config.getInt("settings.value-decimal-places", 2)));
    }

    public void load() {
        config = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "config.yml"));
        permissions = YamlConfiguration.loadConfiguration(new File(plugin.getDataFolder(), "permissions.yml"));
        language = resolveLanguage(config.getString("settings.language", DEFAULT_LANGUAGE));
        messages = loadMessages(language);
        gems.clear();
        punchers.clear();
        removers.clear();

        loadItemFiles();

        // 解析 settings.gem-type-limit（gemtype 标签全局数量限制）
        gemTypeLimits = new LinkedHashMap<>();
        ConfigurationSection gemTypeLimitSection = config.getConfigurationSection("settings.gem-type-limit");
        if (gemTypeLimitSection != null) {
            for (String key : gemTypeLimitSection.getKeys(false)) {
                gemTypeLimits.put(key, Math.max(0, gemTypeLimitSection.getInt(key)));
            }
        }

        // 解析孔数上限分层：类型（层级 2）与物品 id（层级 3）
        maxHolesByType.clear();
        ConfigurationSection byTypeSection = config.getConfigurationSection("settings.max-holes-by-type");
        if (byTypeSection != null) {
            for (String key : byTypeSection.getKeys(false)) {
                int value = byTypeSection.getInt(key, -1);
                if (value >= 0) {
                    maxHolesByType.put(key.toUpperCase(Locale.ROOT), value);
                }
            }
        }
        maxHolesById.clear();
        ConfigurationSection byIdSection = config.getConfigurationSection("settings.max-holes-by-id");
        if (byIdSection != null) {
            for (String key : byIdSection.getKeys(false)) {
                int value = byIdSection.getInt(key, -1);
                if (value >= 0) {
                    maxHolesById.put(key.toUpperCase(Locale.ROOT), value);
                }
            }
        }

        int warnings = 0;
        for (GemDefinition gem : gems.values()) {
            if (!BuffTypeRegistry.get().isKnown(gem.getBuffType())) {
                plugin.getLogger().warning("宝石 [" + gem.getId() + "] 的 buffType 不受支持: " + gem.getBuffType()
                        + "（当前仅支持 " + BuffTypeRegistry.get().supportedTypes() + "，属性将不会注入）");
                warnings++;
            }
        }
        if (warnings > 0) {
            plugin.getLogger().warning("共 " + warnings + " 个宝石使用了不受支持的 buffType");
        }
        plugin.getLogger().info("当前消息语言: " + language);
    }

    /**
     * 规范化 settings.language 的值，防止拼接出非法文件路径。
     */
    private String resolveLanguage(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_LANGUAGE;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        if (!normalized.matches("[a-z0-9_]+")) {
            plugin.getLogger().warning("settings.language 的值无效: " + raw + "，回退到默认语言 " + DEFAULT_LANGUAGE);
            return DEFAULT_LANGUAGE;
        }
        return normalized;
    }

    /**
     * 加载消息文件：优先读取 messages_<语言>.yml；
     * 中文语言下若存在旧版 messages.yml 则作为迁移来源；
     * 缺失/未定义的消息键回退到内置中文默认值。
     */
    private FileConfiguration loadMessages(String language) {
        File legacy = new File(plugin.getDataFolder(), LEGACY_MESSAGES_FILE);
        FileConfiguration loaded = null;

        if (DEFAULT_LANGUAGE.equals(language) && legacy.exists()) {
            FileConfiguration legacyConfig = YamlConfiguration.loadConfiguration(legacy);
            if (legacyConfig.contains("messages")) {
                loaded = legacyConfig;
                plugin.getLogger().info("检测到旧版 " + LEGACY_MESSAGES_FILE + "，已作为中文语言文件加载");
            }
        }

        if (loaded == null) {
            File selected = new File(plugin.getDataFolder(), "messages" + File.separator + language + ".yml");
            if (selected.exists()) {
                loaded = YamlConfiguration.loadConfiguration(selected);
            } else {
                plugin.getLogger().warning("消息文件缺失: " + selected.getName() + "，回退到 messages/" + DEFAULT_LANGUAGE + ".yml");
                File fallback = new File(plugin.getDataFolder(), "messages" + File.separator + DEFAULT_LANGUAGE + ".yml");
                if (fallback.exists()) {
                    loaded = YamlConfiguration.loadConfiguration(fallback);
                } else if (legacy.exists()) {
                    loaded = YamlConfiguration.loadConfiguration(legacy);
                } else {
                    plugin.getLogger().warning("未找到任何消息文件，使用内置默认文案");
                    loaded = new YamlConfiguration();
                }
            }
        }

        messageDefaults = bundledMessages("messages/" + DEFAULT_LANGUAGE + ".yml");
        if (messageDefaults != null) {
            loaded.setDefaults(messageDefaults);
        }
        return loaded;
    }

    /**
     * 读取插件内置（jar 内）的默认消息文件。
     */
    private FileConfiguration bundledMessages(String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) {
                plugin.getLogger().warning("内置消息文件不存在: " + name);
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().warning("读取内置消息文件失败: " + name + " - " + e.getMessage());
            return null;
        }
    }

    /**
     * 扫描 items 目录下所有 .yml（含子目录），逐个尝试识别物品配置。
     * 支持新版分区格式（文件顶层出现 gems: / punchers: / removers: 段），
     * 兼容旧版扁平格式（gems.yml / punchers.yml / removers.yml 顶层直接是物品 id）。
     */
    private void loadItemFiles() {
        File itemsDir = new File(plugin.getDataFolder(), "items");
        List<File> files = new ArrayList<>();
        collectYmlFiles(itemsDir, files);
        files.sort(Comparator.comparing(File::getPath));
        if (files.isEmpty()) {
            plugin.getLogger().warning("未找到任何物品配置文件（items 目录为空）");
            return;
        }
        for (File file : files) {
            loadItemFile(file);
        }
        plugin.getLogger().info("已加载 gems: " + gems.size()
                + " 个、punchers: " + punchers.size()
                + " 个、removers: " + removers.size() + " 个");
    }

    private void collectYmlFiles(File dir, List<File> out) {
        File[] children = dir.listFiles();
        if (children == null) {
            return;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                collectYmlFiles(child, out);
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".yml")) {
                out.add(child);
            }
        }
    }

    private void loadItemFile(File file) {
        FileConfiguration cfg = YamlConfiguration.loadConfiguration(file);
        boolean recognized = false;
        recognized |= loadTypeSection(cfg, "gems", GemDefinition::new, file, gems);
        recognized |= loadTypeSection(cfg, "punchers", PuncherDefinition::new, file, punchers);
        recognized |= loadTypeSection(cfg, "removers", RemoverDefinition::new, file, removers);
        if (recognized) {
            return;
        }
        // 兼容旧版扁平格式：按文件名推断物品类型
        String name = file.getName().toLowerCase(Locale.ROOT);
        switch (name) {
            case "gems.yml" -> loadEntries(cfg, GemDefinition::new, file, "gems", gems);
            case "punchers.yml" -> loadEntries(cfg, PuncherDefinition::new, file, "punchers", punchers);
            case "removers.yml" -> loadEntries(cfg, RemoverDefinition::new, file, "removers", removers);
            default -> plugin.getLogger().warning("跳过无法识别类型的物品配置: " + file.getPath()
                    + "（请在文件顶层使用 gems: / punchers: / removers: 段声明类型）");
        }
    }

    private <T extends ItemDefinition> boolean loadTypeSection(
            FileConfiguration cfg,
            String type,
            DefinitionFactory<T> factory,
            File file,
            Map<String, T> target
    ) {
        ConfigurationSection section = cfg.getConfigurationSection(type);
        if (section == null) {
            return false;
        }
        loadEntries(section, factory, file, type, target);
        return true;
    }

    private <T extends ItemDefinition> void loadEntries(
            ConfigurationSection section,
            DefinitionFactory<T> factory,
            File file,
            String type,
            Map<String, T> target
    ) {
        int count = 0;
        for (String key : section.getKeys(false)) {
            ConfigurationSection entry = section.getConfigurationSection(key);
            if (entry == null) {
                continue;
            }
            T definition = factory.create(key, entry);
            if (!definition.isValid()) {
                plugin.getLogger().warning("跳过无效配置: " + file.getName() + " -> " + type + "." + key + "（material 无效）");
                continue;
            }
            if (target.containsKey(key)) {
                plugin.getLogger().warning("物品配置重复，后加载的覆盖先前的: " + key + "（" + file.getName() + "）");
            }
            target.put(key, definition);
            count++;
        }
        if (count > 0) {
            plugin.getLogger().info("已从 " + file.getName() + " 加载 " + type + ": " + count + " 个");
        }
    }

    private interface DefinitionFactory<T extends ItemDefinition> {
        T create(String id, ConfigurationSection section);
    }

    public ItemDefinition find(ToolType type, String id) {
        if (type == null || id == null) {
            return null;
        }
        return switch (type) {
            case GEM -> gems.get(id);
            case PUNCHER -> punchers.get(id);
            case REMOVER -> removers.get(id);
        };
    }

    public GemDefinition getGem(String id) {
        return gems.get(id);
    }

    public Map<String, GemDefinition> getGems() {
        return gems;
    }

    public Map<String, PuncherDefinition> getPunchers() {
        return punchers;
    }

    public Map<String, RemoverDefinition> getRemovers() {
        return removers;
    }

    /**
     * 单个物品可用的孔数上限（全局兜底值，settings.max-holes）。
     */
    public int maxHoles() {
        return Math.max(0, config.getInt("settings.max-holes", 6));
    }

    /**
     * 物品可用孔数上限（三层解析，对具体物品从高优先级向下匹配）：
     *   1. 物品 id（材质大写名，settings.max-holes-by-id）——层级 3，覆盖 2/1
     *   2. 物品类型（settings.max-holes-by-type，与打孔器 targetType 同语义）——层级 2，覆盖 1
     *   3. 全局（settings.max-holes）——层级 1
     */
    public int maxHolesFor(org.bukkit.inventory.ItemStack target) {
        if (target != null && target.getType() != null) {
            String idName = target.getType().name().toUpperCase(Locale.ROOT);
            Integer byId = maxHolesById.get(idName);
            if (byId != null) {
                return byId;
            }
            for (Map.Entry<String, Integer> entry : maxHolesByType.entrySet()) {
                if (TargetMatcher.matchesType(target.getType(), entry.getKey())) {
                    return entry.getValue();
                }
            }
        }
        return maxHoles();
    }

    /**
     * 物品类型 -> 孔数上限（层级 2；覆盖全局）。
     */
    public Map<String, Integer> maxHolesByType() {
        return maxHolesByType;
    }

    /**
     * 物品 id（材质大写名）-> 孔数上限（层级 3；覆盖类型与全局）。
     */
    public Map<String, Integer> maxHolesById() {
        return maxHolesById;
    }

    /**
     * 获取 gemtype 标签全局数量限制（key: 标签名, value: 上限值；负数/缺省按 0 处理 = 不限制）。
     */
    public Map<String, Integer> gemTypeLimits() {
        return gemTypeLimits;
    }

    public boolean isInteractionEnabled(String name) {
        return config.getBoolean("settings.interactions." + name, true);
    }

    /**
     * 判断发送者是否有权执行指定指令。
     * 先判断权限节点（由 LuckPerms 等权限插件管理），再按 default-level 判定默认权限级。
     */
    public boolean hasCommandPermission(CommandSender sender, String command) {
        if (sender == null) {
            return true;
        }
        List<String> nodes = commandPermissionNodes(command);
        for (String node : nodes) {
            if (node != null && !node.isBlank() && sender.hasPermission(node.trim())) {
                return true;
            }
        }
        return defaultLevelAllows(sender, commandDefaultLevel(command));
    }

    /**
     * 获取指令要求的权限节点列表；未在 permissions.yml 中配置时回退到内置默认节点。
     */
    public List<String> commandPermissionNodes(String command) {
        if (command == null) {
            return List.of();
        }
        String path = "commands." + command + ".permissions";
        if (permissions.contains(path)) {
            return permissions.getStringList(path);
        }
        return defaultCommandNodes(command);
    }

    private List<String> defaultCommandNodes(String command) {
        return switch (command.toLowerCase(Locale.ROOT)) {
            case "reload" -> List.of("mosaicgem.reload");
            case "give" -> List.of("mosaicgem.give");
            case "debug", "selftest" -> List.of("mosaicgem.debug");
            case "list" -> List.of("mosaicgem.list");
            default -> List.of();
        };
    }

    /**
     * 获取指令的默认权限级；未在 permissions.yml 中配置时默认 op。
     * 可选值：op / true / false / not-op。
     */
    public String commandDefaultLevel(String command) {
        if (command == null) {
            return "op";
        }
        String level = permissions.getString("commands." + command + ".default-level", "op");
        return level == null || level.isBlank() ? "op" : level.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * 没有任何权限节点被授予时，按默认权限级判定：
     * op      - 仅 OP 或以上
     * true    - 所有玩家
     * false   - 仅权限插件授予者（无默认权限）
     * not-op  - 非 OP 玩家
     * 其他未知值一律按 op 处理。
     */
    private boolean defaultLevelAllows(CommandSender sender, String level) {
        return switch (level) {
            case "true" -> true;
            case "false" -> false;
            case "not-op" -> !sender.isOp();
            default -> sender.isOp();
        };
    }

    public String language() {
        return language;
    }

    public String prefix() {
        return messages.getString("messages.prefix", "&8[&6MosaicGem&8] ");
    }

    public String message(String key) {
        return messages.getString("messages." + key, key);
    }

    /**
     * 获取原版属性 id 的显示名（来自语言文件 attribute-names 段）。
     */
    public String attributeName(String id) {
        if (id == null) {
            return id;
        }
        Object name = nameFrom(messages, "attribute-names", id);
        if (name == null && messageDefaults != null) {
            // 旧版语言文件可能缺少整个段：回退到内置默认语言文件，避免直接显示内部名
            name = nameFrom(messageDefaults, "attribute-names", id);
        }
        return name != null ? name.toString() : id;
    }

    /**
     * 获取附魔 id 的显示名（来自语言文件 enchant-names 段）。
     */
    public String enchantName(String id) {
        if (id == null) {
            return id;
        }
        Object name = nameFrom(messages, "enchant-names", id);
        if (name == null && messageDefaults != null) {
            // 旧版语言文件可能缺少整个段：回退到内置默认语言文件，避免直接显示附魔内部名
            name = nameFrom(messageDefaults, "enchant-names", id);
        }
        return name != null ? name.toString() : id;
    }

    private Object nameFrom(FileConfiguration source, String section, String id) {
        ConfigurationSection configurationSection = source.getConfigurationSection(section);
        if (configurationSection == null) {
            return null;
        }
        return configurationSection.getValues(false).get(id);
    }

    public SocketLoreTemplate socketLore() {
        return SocketLoreTemplate.from(config.getConfigurationSection("socket-lore"));
    }

    public AttributeLoreConfig attributeLore() {
        return AttributeLoreConfig.from(config.getConfigurationSection("attribute-lore"));
    }
}
