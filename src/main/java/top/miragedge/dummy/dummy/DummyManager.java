package top.miragedge.dummy.dummy;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import top.miragedge.dummy.MiragEdgeDummy;
import top.miragedge.dummy.storage.DummyRecord;
import top.miragedge.dummy.storage.DummyStorage;
import top.miragedge.dummy.util.Messages;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 木人桩管理器：负责放置 / 识别 / 恢复 / 清理 / 物品构造。
 *
 * <p>与 PlayerDummies 的 DummyManager 职责相同，但去掉 Citizens 分支，纯盔甲架。</p>
 *
 * <p>实现要点（见 docs/DEVELOPMENT.md §4）：</p>
 * <ul>
 *   <li>PDC 标记键：dummy / owner / id（{@link #dummyKey()} 等）</li>
 *   <li>放置：物品 PDC 标记 + 右键地面 → spawnDummy(玩家)</li>
 *   <li>恢复：data/ 记录 → 延时 + 异区块异步加载后重新生成</li>
 *   <li>孤儿清理：扫描世界，有 PDC 标记但不在 trackers 且无存储记录 → 移除</li>
 * </ul>
 */
public class DummyManager {

    private final MiragEdgeDummy plugin;
    private final DummyStorage storage;
    private final Map<UUID, Dummy> dummies = new ConcurrentHashMap<>();

    public DummyManager(MiragEdgeDummy plugin, DummyStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    // ============ 物品 ============

    /**
     * 构造木人桩放置物品：材质走 config 的 item.material（非法回退 ARMOR_STAND），
     * 名称/lore 从 messages.yml 实时读取，PDC 打 dummy=1 标记。
     */
    public ItemStack createDummyItem(int amount) {
        String matName = plugin.getConfigManager().getString("item.material", "ARMOR_STAND");
        Material mat = Material.getMaterial(matName);
        if (mat == null) {
            mat = Material.ARMOR_STAND;
        }
        ItemStack stack = new ItemStack(mat, amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.messages().getString("item.name", "木人桩"));
            meta.setLore(plugin.messages().getStringList("item.lore"));
            meta.getPersistentDataContainer().set(dummyKey(), PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * 判断物品是否为木人桩物品（PDC 标记）。
     */
    public boolean isDummyItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer().has(dummyKey(), PersistentDataType.BYTE);
    }

    // ============ 放置 ============

    /**
     * 玩家放置木人桩：扣 1 个物品、取视线落点上方 1 格、生成盔甲架、
     * 应用标记与静态配置、建 tracker、写存储。
     */
    public Dummy spawnDummy(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() == Material.AIR) {
            return null;
        }

        // 先取视线落点，失败时不消耗物品。
        // 注意：paper-api 1.21.4 已移除 getTargetBlockExact(int)，
        // 改用 LivingEntity#getTargetBlock(int)：准星指向、忽略空气，超距返回 null。
        Block target = player.getTargetBlock(5);
        if (target == null) {
            player.sendMessage(plugin.messages().fmt("messages.placement-failed"));
            return null;
        }

        // 扣 1 个放置物品
        int remaining = hand.getAmount() - 1;
        if (remaining <= 0) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(remaining);
        }

        Location loc = target.getLocation().add(0, 1, 0);
        // 假人面向玩家：yaw + 180°，归一化到 [0, 360)
        loc.setYaw((Math.round(player.getLocation().getYaw() + 180f) % 360 + 360) % 360);
        loc.setPitch(0);

        UUID id = UUID.randomUUID();
        UUID owner = player.getUniqueId();

        ArmorStand stand = (ArmorStand) player.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.getPersistentDataContainer().set(dummyKey(), PersistentDataType.BYTE, (byte) 1);
        stand.getPersistentDataContainer().set(ownerKey(), PersistentDataType.STRING, owner.toString());
        stand.getPersistentDataContainer().set(idKey(), PersistentDataType.STRING, id.toString());

        Dummy dummy = new Dummy(id, owner, stand);
        dummy.configureStatic();

        boolean visible = plugin.getConfigManager().getBoolean("npc-name-visible", true);
        String rawName = plugin.getConfigManager().getString("npc-name", "&e木人桩");
        String displayName = (rawName == null || rawName.isEmpty()) ? "&e木人桩" : rawName;
        dummy.setCustomName(Messages.colorize(displayName), visible);

        dummies.put(id, dummy);
        storage.saveRecord(DummyRecord.fromLocation(id, owner, loc, displayName));

        player.sendMessage(plugin.messages().fmt("messages.placed"));
        return dummy;
    }

    /**
     * 玩家通过物品收回木人桩：归还装备、移除实体与记录、返回物品（剩余掉落地面）。
     */
    public void pickupDummy(Player player, Dummy dummy) {
        Location dropLoc = dummy.getLocation();

        for (Dummy.EquipmentSlot slot : Dummy.EquipmentSlot.values()) {
            ItemStack item = dummy.getEquipment(slot);
            if (item == null) {
                continue;
            }
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            dropRemainder(dropLoc, leftover);
        }

        dummy.remove();
        dummies.remove(dummy.getId());
        storage.remove(dummy.getId());

        Map<Integer, ItemStack> rest = player.getInventory().addItem(createDummyItem(1));
        dropRemainder(dropLoc, rest);

        player.sendMessage(plugin.messages().fmt("messages.removed"));
    }

    /**
     * 背包放不下的物品在假人原位置自然掉落（归还装备是刻意保留的友好行为）。
     */
    private void dropRemainder(Location dropLoc, Map<Integer, ItemStack> leftover) {
        if (leftover == null || leftover.isEmpty() || dropLoc == null || dropLoc.getWorld() == null) {
            return;
        }
        for (ItemStack drop : leftover.values()) {
            dropLoc.getWorld().dropItemNaturally(dropLoc, drop);
        }
    }

    // ============ 识别 ============

    /**
     * 判断实体是否为木人桩（PDC 标记）。
     */
    public boolean isDummyEntity(Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(dummyKey(), PersistentDataType.BYTE);
    }

    /**
     * 判断玩家是否木人桩主人（无 owner 或 UUID 匹配）。
     */
    public boolean isOwner(Entity entity, Player player) {
        String ownerId = entity.getPersistentDataContainer().get(ownerKey(), PersistentDataType.STRING);
        return ownerId == null || ownerId.equalsIgnoreCase(player.getUniqueId().toString());
    }

    public Dummy getDummy(UUID id) {
        return dummies.get(id);
    }

    public Dummy getDummyByEntity(Entity entity) {
        if (!isDummyEntity(entity)) {
            return null;
        }
        String idStr = entity.getPersistentDataContainer().get(idKey(), PersistentDataType.STRING);
        if (idStr == null) {
            return null;
        }
        try {
            return dummies.get(UUID.fromString(idStr));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public Map<UUID, Dummy> getAllDummies() {
        return dummies;
    }

    public void removeDummy(UUID id) {
        Dummy d = dummies.remove(id);
        if (d != null) {
            d.remove();
        }
        storage.remove(id);
    }

    // ============ 持久化 / 恢复 / 清理 ============

    /**
     * 保存全部在场木人桩（含 yaw/pitch）。
     */
    public void saveAll() {
        for (Dummy d : dummies.values()) {
            if (d == null || !d.isValid()) {
                continue;
            }
            String displayName = d.getStand().getCustomName();
            if (displayName == null) {
                displayName = plugin.getConfigManager().getString("npc-name", "&e木人桩");
            }
            try {
                storage.saveRecord(DummyRecord.fromLocation(d.getId(), d.getOwner(), d.getLocation(), displayName));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("无法保存木人桩 " + d.getId() + ": " + e.getMessage());
            }
        }
    }

    /**
     * 恢复全部已保存木人桩：世界未加载跳过，区块未加载用 getChunkAtAsync 异步生成（Leaf 禁止同步 getChunk）。
     */
    public void restoreAll() {
        for (DummyRecord rec : storage.all()) {
            try {
                World w = Bukkit.getWorld(rec.world());
                if (w == null) {
                    plugin.getLogger().fine("跳过恢复木人桩 " + rec.uuid() + "：世界未加载 " + rec.world());
                    continue;
                }
                Location loc = rec.toLocation();
                if (loc == null) {
                    plugin.getLogger().fine("跳过恢复木人桩 " + rec.uuid() + "：位置无效");
                    continue;
                }
                if (w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                    spawnRestored(rec, loc);
                } else {
                    // Leaf 禁止主线程同步 getChunk：getChunkAtAsync(Location) 返回 CompletableFuture，
                    // 完成后在主线程执行（Paper 保证），这里异步生成后恢复木人桩。
                    w.getChunkAtAsync(loc).thenAccept(chunk -> {
                        if (chunk != null && chunk.isLoaded()) {
                            try {
                                spawnRestored(rec, loc);
                            } catch (RuntimeException e) {
                                plugin.getLogger().warning("异步恢复木人桩 " + rec.uuid() + " 失败: " + e.getMessage());
                            }
                        }
                    });
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("恢复木人桩 " + rec.uuid() + " 失败: " + e.getMessage());
            }
        }
    }

    /**
     * 根据存储记录重新生成一个木人桩实体并登记。已存在则跳过（防重复）。
     */
    private Dummy spawnRestored(DummyRecord rec, Location loc) {
        if (dummies.containsKey(rec.uuid())) {
            return dummies.get(rec.uuid());
        }
        ArmorStand stand = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.getPersistentDataContainer().set(dummyKey(), PersistentDataType.BYTE, (byte) 1);
        stand.getPersistentDataContainer().set(ownerKey(), PersistentDataType.STRING, rec.owner().toString());
        stand.getPersistentDataContainer().set(idKey(), PersistentDataType.STRING, rec.uuid().toString());

        Dummy dummy = new Dummy(rec.uuid(), rec.owner(), stand);
        dummy.configureStatic();

        boolean visible = plugin.getConfigManager().getBoolean("npc-name-visible", true);
        dummy.setCustomName(Messages.colorize(rec.displayName()), visible);

        dummies.put(rec.uuid(), dummy);
        return dummy;
    }

    /**
     * 清理孤儿实体：有 PDC 标记但无 tracker 且无存储记录 → 移除。
     */
    public void cleanupOrphans() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                if (!isDummyEntity(entity)) {
                    continue;
                }
                String idStr = entity.getPersistentDataContainer().get(idKey(), PersistentDataType.STRING);
                if (idStr == null) {
                    plugin.getLogger().warning("清理孤儿木人桩时发现缺少 id PDC 标记的实体 " + entity.getUniqueId() + "，已移除");
                    entity.remove();
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(idStr);
                    if (!dummies.containsKey(uuid) && storage.get(uuid) == null) {
                        entity.remove();
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("清理孤儿木人桩时遇到非法 UUID '" + idStr + "': " + e.getMessage());
                }
            }
        }
    }

    // ============ NamespacedKey ============

    public NamespacedKey dummyKey() {
        return new NamespacedKey(plugin, "dummy");
    }

    public NamespacedKey ownerKey() {
        return new NamespacedKey(plugin, "owner");
    }

    public NamespacedKey idKey() {
        return new NamespacedKey(plugin, "id");
    }
}
