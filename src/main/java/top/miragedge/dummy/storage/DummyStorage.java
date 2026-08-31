package top.miragedge.dummy.storage;

import org.bukkit.configuration.file.YamlConfiguration;
import top.miragedge.dummy.MiragEdgeDummy;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 木人桩持久化：data/&lt;uuid&gt;.yml 单文件存储。
 *
 * <p>实现要点（见 docs/DEVELOPMENT.md §5）：</p>
 * <ul>
 *   <li>构造时 loadAll() 读入内存 map，save 时写单个文件（不整表重写）</li>
 *   <li>shutdown() 时确保落盘（onDisable 调用）</li>
 *   <li>损坏的 yml / 非法 UUID 静默跳过并打警告，不中断启动</li>
 *   <li>所有写盘操作放在异步线程（勿阻塞主线程）——参考 FE_PVP 主线程 IO 教训</li>
 * </ul>
 */
public class DummyStorage {

    private final MiragEdgeDummy plugin;
    private final File dataDir;
    private final Map<UUID, DummyRecord> records = new ConcurrentHashMap<>();

    public DummyStorage(MiragEdgeDummy plugin) {
        this.plugin = plugin;
        this.dataDir = new File(plugin.getDataFolder(), "data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            plugin.getLogger().warning("无法创建木人桩数据目录: " + dataDir.getAbsolutePath());
        }
        loadAll();
    }

    /**
     * 保存记录：内存立即登记 + 异步落盘单文件。
     */
    public void saveRecord(DummyRecord record) {
        records.put(record.uuid(), record);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> writeFile(record));
    }

    /**
     * 删除记录：内存移除 + 异步删除对应文件。
     */
    public void remove(UUID uuid) {
        records.remove(uuid);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            File f = new File(dataDir, uuid + ".yml");
            if (f.exists() && !f.delete()) {
                plugin.getLogger().warning("无法删除木人桩文件: " + f.getAbsolutePath());
            }
        });
    }

    public DummyRecord get(UUID uuid) {
        return records.get(uuid);
    }

    public Collection<DummyRecord> all() {
        return records.values();
    }

    /**
     * 关闭前落盘：遍历内存记录同步写入（插件停用阶段允许同步 IO）。
     */
    public void shutdown() {
        for (DummyRecord record : records.values()) {
            try {
                writeFile(record);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("关闭时保存木人桩 " + record.uuid() + " 失败: " + e.getMessage());
            }
        }
    }

    /**
     * 启动时加载全部 data/*.yml。任何异常只打警告，不中断启动。
     */
    private void loadAll() {
        File[] files = dataDir.listFiles((dir, name) -> name.endsWith(".yml"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try {
                String name = file.getName();
                UUID uuid;
                try {
                    uuid = UUID.fromString(name.substring(0, name.length() - 4));
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("跳过非 UUID 文件名 " + name);
                    continue;
                }

                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

                String world = cfg.getString("world");
                if (world == null || world.isEmpty()) {
                    plugin.getLogger().warning("跳过木人桩 " + uuid + "：缺少 world");
                    continue;
                }

                String ownerStr = cfg.getString("owner");
                UUID owner;
                try {
                    owner = UUID.fromString(ownerStr);
                } catch (Exception e) {
                    plugin.getLogger().warning("跳过木人桩 " + uuid + "：非法 owner '" + ownerStr + "'");
                    continue;
                }

                double x = cfg.getDouble("x");
                double y = cfg.getDouble("y");
                double z = cfg.getDouble("z");
                float yaw = (float) cfg.getDouble("yaw", 0.0D);
                float pitch = (float) cfg.getDouble("pitch", 0.0D);
                String displayName = cfg.getString("display-name", null);

                records.put(uuid, new DummyRecord(uuid, owner, world, x, y, z, yaw, pitch, displayName));
            } catch (Exception e) {
                plugin.getLogger().warning("加载木人桩文件 " + file.getName() + " 失败: " + e.getMessage());
            }
        }
    }

    /**
     * 写单文件。坐标四舍五入到两位小数，避免重启后位置抖动。
     */
    private void writeFile(DummyRecord record) {
        try {
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.set("uuid", record.uuid().toString());
            cfg.set("owner", record.owner().toString());
            cfg.set("world", record.world());
            cfg.set("x", round(record.x()));
            cfg.set("y", round(record.y()));
            cfg.set("z", round(record.z()));
            cfg.set("yaw", round(record.yaw()));
            cfg.set("pitch", round(record.pitch()));
            if (record.displayName() != null) {
                cfg.set("display-name", record.displayName());
            }
            File f = new File(dataDir, record.uuid() + ".yml");
            cfg.save(f);
        } catch (IOException e) {
            plugin.getLogger().warning("写入木人桩 " + record.uuid() + " 失败: " + e.getMessage());
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0D) / 100.0D;
    }
}
