package top.miragedge.dummy.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import top.miragedge.dummy.MiragEdgeDummy;
import top.miragedge.dummy.util.Messages;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 配置与消息加载器。
 *
 * <p>负责加载 config.yml 与 messages.yml，并提供类型安全的消息访问。
 * 使用双缓冲避免每次读取都解析配置。</p>
 *
 * <p>实现要点（见 docs/DEVELOPMENT.md §3）：</p>
 * <ul>
 *   <li>config.yml 在插件数据目录不存在时由 saveDefaultConfig() 生成</li>
 *   <li>messages.yml 首次启动 saveResource 复制到数据目录，之后以磁盘文件为准（可被服主汉化/修改）</li>
 *   <li><b>配置自动升级</b>：升级插件后，旧配置文件缺失的新键（如 messages.cps/cooldown、
 *       npc-skin、dummy-entity-type、notifications.show-cps 等）会自动从内置默认模板补全
 *       并写回磁盘——已存在的键绝不覆盖（尊重服主修改）。</li>
 *   <li>reload 时全部重新加载（/dummy reload）</li>
 * </ul>
 */
public class ConfigManager {

    private final MiragEdgeDummy plugin;
    private FileConfiguration config;
    private Messages messages;

    public ConfigManager(MiragEdgeDummy plugin) {
        this.plugin = plugin;
    }

    /**
     * 首次/每次加载：生成缺失文件 → 自动升级旧配置补全缺失键 → 构建 Messages 实例。
     */
    public void load() {
        plugin.saveDefaultConfig();
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        // 配置自动升级：把 jar 内置默认模板中「磁盘缺失的叶子键」补全并写回
        upgradeYaml(new File(plugin.getDataFolder(), "config.yml"), "config.yml");
        upgradeYaml(messagesFile, "messages.yml");

        // 重新从磁盘读取（升级可能写回了新键）
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        this.messages = new Messages(YamlConfiguration.loadConfiguration(messagesFile));
    }

    /**
     * 重新加载配置与消息：reloadConfig() 重读 config.yml，再 load() 重读 messages.yml。
     */
    public void reload() {
        load();
    }

    /**
     * 自动升级 YAML 配置文件：把内置资源模板中磁盘缺失的「叶子键」补全并保存。
     * 已有键绝不覆盖（尊重服主汉化/修改）。仅在存在缺失键时重写一次文件（注释会丢失，可接受）。
     */
    /** 配置自动升级标记键：写入配置顶层，值为插件版本——同一版本只补全一次，尊重服主后续删除/修改。 */
    private static final String UPGRADE_MARKER = "config-auto-upgraded";

    private void upgradeYaml(File diskFile, String resourceName) {
        try {
            YamlConfiguration disk = YamlConfiguration.loadConfiguration(diskFile);
            // 该版本已升级过就不再补（避免每次 reload 重补被删的键）；插件升级换版本号后会再补一次
            String markerValue = plugin.getPluginMeta().getVersion();
            if (markerValue.equals(disk.getString(UPGRADE_MARKER))) {
                return;
            }
            // try-with-resources 关闭内置模板流
            try (java.io.InputStream in = plugin.getResource(resourceName)) {
                if (in == null) {
                    return;
                }
                YamlConfiguration defaults = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8));
                boolean changed = false;
                for (String key : defaults.getKeys(true)) {
                    // 用 isSet 判缺失（contains 对 null 值返回 false 会误覆盖用户显式置空的键）
                    if (!disk.isSet(key) && !defaults.isConfigurationSection(key)) {
                        disk.set(key, defaults.get(key));
                        changed = true;
                    }
                }
                disk.set(UPGRADE_MARKER, markerValue);
                disk.save(diskFile);
                if (changed) {
                    plugin.getLogger().info(resourceName + " 已自动补全缺失键（新增配置项，请查看文件确认）");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning(resourceName + " 自动升级失败: " + e.getMessage());
        }
    }

    /**
     * 读整型配置（带默认值）。已由 load() 填充 this.config。
     */
    public int getInt(String path, int def) {
        return (this.config != null ? this.config : plugin.getConfig()).getInt(path, def);
    }

    public boolean getBoolean(String path, boolean def) {
        return (this.config != null ? this.config : plugin.getConfig()).getBoolean(path, def);
    }

    public String getString(String path, String def) {
        return (this.config != null ? this.config : plugin.getConfig()).getString(path, def);
    }

    public Messages getMessages() {
        return messages;
    }
}
