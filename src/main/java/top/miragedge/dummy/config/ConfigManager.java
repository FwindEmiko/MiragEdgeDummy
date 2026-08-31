package top.miragedge.dummy.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import top.miragedge.dummy.MiragEdgeDummy;
import top.miragedge.dummy.util.Messages;

import java.io.File;

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
     * 首次加载：saveDefaultConfig() 生成 config.yml（若不存在），
     * 读取 messages.yml（不存在则 saveResource 复制默认），构建 Messages 实例。
     */
    public void load() {
        plugin.saveDefaultConfig();
        File messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        this.config = plugin.getConfig();
        this.messages = new Messages(YamlConfiguration.loadConfiguration(messagesFile));
    }

    /**
     * 重新加载配置与消息：reloadConfig() 重读 config.yml，再 load() 重读 messages.yml。
     */
    public void reload() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
        load();
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
