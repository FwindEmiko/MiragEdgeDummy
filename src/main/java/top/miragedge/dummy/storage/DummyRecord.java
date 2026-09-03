package top.miragedge.dummy.storage;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.UUID;

/**
 * 训练假人持久化记录（不可变 record）。
 *
 * <p>对应 data/&lt;uuid&gt;.yml 单文件存储。</p>
 *
 * <p>实现要点（见 docs/DEVELOPMENT.md §5）：</p>
 * <ul>
 *   <li>保存：uuid/owner/world/x/y/z/yaw/pitch/displayName</li>
 *   （注：装备持久化按技术报告 §8.4 有意不做，如需请后续版本在 DummyRecord 增加装备字段）</li>
 *   <li>world 为 null 时禁止保存（避免写死占位世界名，参考 FE_PVP 教训）</li>
 *   <li>位置需四舍五入到合理精度，避免重启后抖动</li>
 * </ul>
 */
public record DummyRecord(
        UUID uuid,
        UUID owner,
        String world,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        String displayName,
        int hp
) {

    /**
     * 从坐标构造：world 为 null 时抛出 {@link IllegalArgumentException}（由调用方捕获跳过）。
     */
    public static DummyRecord fromLocation(UUID uuid, UUID owner, Location loc, String displayName, int hp) {
        World w = loc.getWorld();
        if (w == null) {
            throw new IllegalArgumentException("World cannot be null");
        }
        return new DummyRecord(uuid, owner, w.getName(), loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch(), displayName, hp);
    }

    /**
     * 还原为 Location：world 未加载返回 null（由调用方跳过）。
     */
    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        if (w == null) {
            return null;
        }
        return new Location(w, x, y, z, yaw, pitch);
    }
}
