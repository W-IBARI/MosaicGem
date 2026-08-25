package com.mosaicgem.plugin;

import com.mosaicgem.plugin.command.MosaicGemCommand;
import com.mosaicgem.plugin.config.ConfigManager;
import com.mosaicgem.plugin.listener.AnvilInteractionListener;
import com.mosaicgem.plugin.listener.CraftingInteractionListener;
import com.mosaicgem.plugin.listener.DragInteractionListener;
import com.mosaicgem.plugin.listener.MythicCrucibleListener;
import com.mosaicgem.plugin.listener.MythicSkillListener;
import com.mosaicgem.plugin.service.GemService;
import com.mosaicgem.plugin.util.ItemFactory;
import com.mosaicgem.plugin.util.MythicCrucibleBridge;
import com.mosaicgem.plugin.util.MythicMobsBridge;
import com.mosaicgem.plugin.util.MythicSkillExecutor;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

public final class MosaicGemPlugin extends JavaPlugin {

    private static final List<String> MESSAGE_FILES = List.of(
            "messages/zh_cn.yml",
            "messages/en_us.yml"
    );

    private static MosaicGemPlugin instance;

    private ConfigManager configManager;
    private ItemFactory itemFactory;
    private GemService gemService;
    private MythicMobsBridge mythicMobsBridge;
    private MythicCrucibleBridge mythicCrucibleBridge;
    private MosaicGemCommand command;

    public static MosaicGemPlugin instance() {
        return instance;
    }

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        saveMessageFiles();
        saveItemFiles();
        saveResourceIfAbsent("permissions.yml");

        configManager = new ConfigManager(this);
        configManager.load();

        itemFactory = new ItemFactory(this, configManager);
        gemService = new GemService(this, configManager, itemFactory);
        Bukkit.getPluginManager().registerEvents(new AnvilInteractionListener(configManager, itemFactory, gemService), this);
        Bukkit.getPluginManager().registerEvents(new CraftingInteractionListener(configManager, itemFactory, gemService), this);
        Bukkit.getPluginManager().registerEvents(new DragInteractionListener(configManager, itemFactory, gemService), this);

        mythicMobsBridge = new MythicMobsBridge(this, configManager, itemFactory);
        // MythicMobs 桥必须先初始化：注册自定义掉落并重载掉落/怪物配置（无论是否安装 Crucible）
        boolean mythicMobsAvailable = mythicMobsBridge.isAvailable();
        mythicCrucibleBridge = new MythicCrucibleBridge(this, configManager, itemFactory, mythicMobsBridge);
        if (mythicCrucibleBridge.isAvailable()) {
            // 使用 MythicCrucible 的物品技能触发管线（SWING/USE/RIGHTCLICK 等），不再自建触发监听
            Bukkit.getPluginManager().registerEvents(new MythicCrucibleListener(mythicCrucibleBridge), this);
            for (org.bukkit.entity.Player player : Bukkit.getOnlinePlayers()) {
                mythicCrucibleBridge.registerPlayer(player);
            }
        } else if (mythicMobsAvailable) {
            // 未安装 MythicCrucible 时回退到内置攻击触发
            MythicSkillExecutor skillExecutor = new MythicSkillExecutor(configManager, itemFactory, mythicMobsBridge);
            Bukkit.getPluginManager().registerEvents(new MythicSkillListener(skillExecutor), this);
        }

        command = new MosaicGemCommand(this, configManager, itemFactory);
        PluginCommand pluginCommand = getCommand("mosaicgem");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }

        getLogger().info("MosaicGem 已启用 (Folia " + Bukkit.getBukkitVersion() + ")，当前语言: " + configManager.language());
    }

    @Override
    public void onDisable() {
        getLogger().info("MosaicGem 已禁用");
    }

    public void reloadConfigs() {
        // 热重载时先补齐缺失的默认配置文件，再重新加载
        saveDefaultConfig();
        saveMessageFiles();
        saveItemFiles();
        saveResourceIfAbsent("permissions.yml");
        reloadConfig();
        configManager.load();
        getLogger().info("配置已重载，当前语言: " + configManager.language());
    }

    private void saveMessageFiles() {
        for (String name : MESSAGE_FILES) {
            saveResourceIfAbsent(name);
        }
    }

    private void saveItemFiles() {
        if (hasAnyItemConfig()) {
            getLogger().info("items 目录下已存在 .yml 物品配置，跳过默认文件生成");
            return;
        }
        generateGemsFileIfAbsent();
        saveResourceIfAbsent("items/punchers.yml");
        saveResourceIfAbsent("items/removers.yml");
    }

    /**
     * items 目录（含子目录）下是否存在任何 .yml 文件。
     * 只要存在，无论内容是否合法，都不再生成任何默认物品配置文件。
     */
    private boolean hasAnyItemConfig() {
        return containsYmlFile(new File(getDataFolder(), "items"));
    }

    private boolean containsYmlFile(File dir) {
        File[] children = dir.listFiles();
        if (children == null) {
            return false;
        }
        for (File child : children) {
            if (child.isDirectory()) {
                if (containsYmlFile(child)) {
                    return true;
                }
            } else if (child.getName().toLowerCase(Locale.ROOT).endsWith(".yml")) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按已安装的软依赖生成默认宝石配置：
     * 原版属性 / 附魔示例宝石始终生成；SX-Attribute 宝石需要 SX-Attribute，
     * MythicMobs 技能宝石需要 MythicMobs。文件已存在时不覆盖。
     */
    private void generateGemsFileIfAbsent() {
        File target = new File(getDataFolder(), "items" + File.separator + "gems.yml");
        if (target.exists()) {
            return;
        }
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }

        StringBuilder content = new StringBuilder();
        appendResource(content, "items/gem-blocks/header.yml");
        appendResource(content, "items/gem-blocks/vanilla.yml");
        appendResource(content, "items/gem-blocks/enchant.yml");
        if (Bukkit.getPluginManager().getPlugin("SX-Attribute") != null) {
            appendResource(content, "items/gem-blocks/sx.yml");
        }
        if (Bukkit.getPluginManager().getPlugin("MythicMobs") != null) {
            appendResource(content, "items/gem-blocks/mm.yml");
        }
        if (Bukkit.getPluginManager().getPlugin("CraftEngine") != null) {
            appendResource(content, "items/gem-blocks/ce.yml");
        }

        try {
            Files.write(target.toPath(), content.toString().getBytes(StandardCharsets.UTF_8));
            getLogger().info("已生成 items/gems.yml（按已安装软依赖筛选示例宝石）");
        } catch (IOException e) {
            getLogger().warning("生成 items/gems.yml 失败: " + e.getMessage());
        }
    }

    private void appendResource(StringBuilder builder, String name) {
        try (InputStream in = getResource(name)) {
            if (in == null) {
                getLogger().warning("内置资源缺失: " + name);
                return;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    builder.append(line).append('\n');
                }
            }
            if (builder.length() > 0 && builder.charAt(builder.length() - 1) != '\n') {
                builder.append('\n');
            }
        } catch (IOException e) {
            getLogger().warning("读取内置资源失败: " + name + " - " + e.getMessage());
        }
    }

    private void saveResourceIfAbsent(String name) {
        File target = new File(getDataFolder(), name);
        if (!target.exists()) {
            File parent = target.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            saveResource(name, false);
        }
    }
}
