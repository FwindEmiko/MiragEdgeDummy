package top.miragedge.dummy;

import org.bukkit.plugin.java.JavaPlugin;
import top.miragedge.dummy.command.DummyCommand;
import top.miragedge.dummy.config.ConfigManager;
import top.miragedge.dummy.dummy.DummyManager;
import top.miragedge.dummy.listener.DummyListener;
import top.miragedge.dummy.storage.DummyStorage;
import top.miragedge.dummy.util.Messages;

/**
 * 木人桩 MiragEdgeDummy 主类。
 *
 * <p>训练假人插件：放置盔甲架假人，测试玩家对护甲/附魔的伤害输出。
 * 零外部依赖（不需要 Citizens），纯 Paper API，兼容 Geyser 基岩版。</p>
 *
 * <p>模块划分：</p>
 * <ul>
 *   <li>{@link ConfigManager} —— 配置与消息加载</li>
 *   <li>{@link DummyManager} —— 木人桩生命周期管理（生成/恢复/清理）</li>
 *   <li>{@link DummyStorage} —— 持久化（data/&lt;uuid&gt;.yml）</li>
 *   <li>{@link DummyListener} —— 全部交互逻辑（放置/伤害/装备/拾取）</li>
 *   <li>{@link DummyCommand} —— /dummy 管理命令</li>
 * </ul>
 */
public final class MiragEdgeDummy extends JavaPlugin {

    private static MiragEdgeDummy instance;

    private ConfigManager configManager;
    private DummyStorage storage;
    private DummyManager dummyManager;
    private DummyListener dummyListener;
    private DummyCommand dummyCommand;

    @Override
    public void onEnable() {
        instance = this;

        // 1. 配置与消息
        this.configManager = new ConfigManager(this);
        this.configManager.load();

        // 2. 持久化 + 管理器
        this.storage = new DummyStorage(this);
        this.dummyManager = new DummyManager(this, this.storage);

        // 3. 监听器 + 命令
        this.dummyListener = new DummyListener(this, this.dummyManager);
        this.getServer().getPluginManager().registerEvents(this.dummyListener, this);

        this.dummyCommand = new DummyCommand(this, this.dummyManager);
        var cmd = this.getCommand("dummy");
        if (cmd != null) {
            cmd.setExecutor(this.dummyCommand);
            cmd.setTabCompleter(this.dummyCommand);
        }

        // 4. 重启后恢复已保存的木人桩（延迟 100 tick，等世界区块加载）
        this.getServer().getScheduler().runTaskLater(this, () -> {
            this.dummyManager.restoreAll();
            // 延迟清理孤儿实体（防止重复生成）
            this.getServer().getScheduler().runTaskLater(this, this.dummyManager::cleanupOrphans, 60L);
        }, 100L);

        // 5. 每 tick 保持木人桩静止（防击退/熄灭）
        this.getServer().getScheduler().runTaskTimer(this, () -> this.dummyManager.getAllDummies().values().forEach(dummy -> {
            if (dummy.getEntity() != null && !dummy.getEntity().isDead()) {
                dummy.getEntity().setVelocity(new org.bukkit.util.Vector(0, 0, 0));
                dummy.getEntity().setFireTicks(0);
            }
        }), 1L, 1L);

        getLogger().info("木人桩 MiragEdgeDummy v" + getPluginMeta().getVersion() + " 已启用");
    }

    @Override
    public void onDisable() {
        if (this.dummyManager != null) {
            this.dummyManager.saveAll();
        }
        if (this.storage != null) {
            this.storage.shutdown();
        }
    }

    public static MiragEdgeDummy getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public DummyStorage getStorage() {
        return storage;
    }

    public DummyManager getDummyManager() {
        return dummyManager;
    }

    /**
     * 便捷入口：取配置管理器中的消息（供监听器/命令使用）。
     */
    public Messages messages() {
        return configManager.getMessages();
    }
}
