package top.miragedge.dummy.dummy;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import top.miragedge.dummy.MiragEdgeDummy;
import top.miragedge.dummy.damage.DamageCalculator;
import top.miragedge.dummy.storage.DummyRecord;
import top.miragedge.dummy.storage.DummyStorage;
import top.miragedge.dummy.util.Messages;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 训练假人管理器：负责放置 / 识别 / 恢复 / 清理 / 物品构造。
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
    // 受击动画任务表：uuid -> 进行中的回弹任务（连续受击时先取消旧的）
    private final Map<UUID, BukkitTask> hitAnimations = new ConcurrentHashMap<>();

    public DummyManager(MiragEdgeDummy plugin, DummyStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    // ============ 物品 ============

    /**
     * 构造训练假人放置物品：材质走 config 的 item.material（非法回退 ARMOR_STAND），
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
            meta.setDisplayName(plugin.messages().getString("item.name", "&6训练假人"));
            meta.setLore(plugin.messages().getStringList("item.lore"));
            meta.getPersistentDataContainer().set(dummyKey(), PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * 判断物品是否为训练假人物品（PDC 标记）。
     */
    public boolean isDummyItem(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return false;
        }
        return stack.getItemMeta().getPersistentDataContainer().has(dummyKey(), PersistentDataType.BYTE);
    }

    // ============ 放置 ============

    /**
     * 玩家放置训练假人：扣 1 个物品、取视线落点上方 1 格、生成盔甲架、
     * 应用标记与静态配置、建 tracker、写存储。
     */
    public Dummy spawnDummy(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        // §4.3 步骤1：校验手中确为训练假人物品（防错：公方法被其他调用方直接调用时不消耗任意物品）
        if (hand == null || hand.getType() == Material.AIR || !isDummyItem(hand)) {
            return null;
        }

        // 先取视线落点（方案书 §4.3：getTargetBlockExact(5)），失败时不消耗物品。
        // 说明：该 API 在 paper-api 1.21.4 中位于 LivingEntity（Player 继承），已 javap 确认存在。
        Block target = player.getTargetBlockExact(5);
        if (target == null) {
            player.sendMessage(plugin.messages().fmt("messages.placement-failed"));
            return null;
        }

        // 扣 1 个放置物品（显式回写，兼容 getItemInMainHand 返回镜像副本的实现）
        int remaining = hand.getAmount() - 1;
        if (remaining <= 0) {
            player.getInventory().setItemInMainHand(null);
        } else {
            hand.setAmount(remaining);
            player.getInventory().setItemInMainHand(hand);
        }

        Location loc = target.getLocation().add(0, 1, 0);
        // 假人面向玩家：yaw + 180°，归一化到 [0, 360)
        loc.setYaw((Math.round(player.getLocation().getYaw() + 180f) % 360 + 360) % 360);
        loc.setPitch(0);

        UUID id = UUID.randomUUID();
        UUID owner = player.getUniqueId();

        boolean visible = plugin.getConfigManager().getBoolean("npc-name-visible", true);
        String rawName = plugin.getConfigManager().getString("npc-name", "&e训练假人");
        String displayName = (rawName == null || rawName.isEmpty()) ? "&e训练假人" : rawName;

        final Dummy dummy;
        ArmorStand stand = null;
        try {
            stand = (ArmorStand) player.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
            stand.getPersistentDataContainer().set(dummyKey(), PersistentDataType.BYTE, (byte) 1);
            stand.getPersistentDataContainer().set(ownerKey(), PersistentDataType.STRING, owner.toString());
            stand.getPersistentDataContainer().set(idKey(), PersistentDataType.STRING, id.toString());

            dummy = new Dummy(id, owner, stand);
            dummy.configureStatic();
            updateDisplayName(dummy);
            // 落盘移入 try：fromLocation 异常时实体不留在 tracker/世界里
            dummies.put(id, dummy);
            storage.saveRecord(DummyRecord.fromLocation(id, owner, loc, displayName));
        } catch (RuntimeException e) {
            // 生成/落盘失败：移除已生成的残留实体（防孤儿）+ 退还已扣物品
            if (stand != null && stand.isValid()) {
                stand.remove();
            }
            // 生成失败：退还已扣的 1 个物品，避免静默丢失；多余放不下则掉落
            plugin.getLogger().warning("放置训练假人失败: " + e.getMessage());
            ItemStack refund = createDummyItem(1);
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(refund);
            if (!leftover.isEmpty()) {
                dropRemainder(player.getLocation(), leftover);
            }
            player.sendMessage(plugin.messages().fmt("messages.placement-failed"));
            return null;
        }

        player.sendMessage(plugin.messages().fmt("messages.placed"));
        return dummy;
    }

    /**
     * 玩家通过物品收回训练假人：归还装备、移除实体与记录、返回物品（剩余掉落地面）。
     */
    public void pickupDummy(Player player, Dummy dummy) {
        // 取消进行中的受击动画任务，避免移除后任务常驻
        BukkitTask anim = hitAnimations.remove(dummy.getId());
        if (anim != null) {
            anim.cancel();
        }

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
     * 刷新假人头顶显示名：配置名 + 当前护甲点数（随穿/取装备实时更新）。
     * 护甲值取自 DamageCalculator 的同一张护甲表，与减伤展示一致。
     */
    public void updateDisplayName(Dummy dummy) {
        if (dummy == null || dummy.getStand() == null) {
            return;
        }
        boolean visible = plugin.getConfigManager().getBoolean("npc-name-visible", true);
        String rawName = plugin.getConfigManager().getString("npc-name", "&e训练假人");
        String base = (rawName == null || rawName.isEmpty()) ? "&e训练假人" : rawName;
        int armor = DamageCalculator.getTotalArmor(dummy.getEntity());
        double toughness = DamageCalculator.getTotalToughness(dummy.getEntity());
        StringBuilder sb = new StringBuilder(Messages.colorize(base));
        sb.append(" §7护甲: §a").append(armor);
        if (toughness > 0) {
            sb.append(" §7韧: §b").append(toughness > (int) toughness ? String.format(java.util.Locale.ROOT, "%.1f", toughness) : String.valueOf((int) toughness));
        }
        dummy.setCustomName(sb.toString(), visible);
    }

    /**
     * 受击击退动效：假人沿攻击方向反方向瞬移一小段后，分帧弹回原位。
     * 纯视觉位移（teleport），不破坏 F7「假人不被真实击退」；连续受击会重启动画。
     *
     * @param direction 攻击者指向假人的方向向量（水平分量会被归一化使用）
     */
    public void playHitEffect(Dummy dummy, Vector direction) {
        ArmorStand stand = dummy.getStand();
        if (stand == null || !stand.isValid()) {
            return;
        }
        // 取消旧动画，避免任务堆叠
        BukkitTask old = hitAnimations.remove(dummy.getId());
        if (old != null) {
            old.cancel();
        }

        Location origin = stand.getLocation().clone();
        // 后撤方向：取水平分量并归一化（方向为攻击者→假人，假人后撤为反方向）
        Vector away = direction.clone();
        away.setY(0);
        if (away.lengthSquared() < 1e-4) {
            away = origin.getDirection().clone();
            away.setY(0);
            if (away.lengthSquared() < 1e-4) {
                away = new Vector(0, 0, 1);
            }
        }
        away.normalize();
        double push = 0.35;
        Location hitBack = origin.clone().add(away.multiply(-push));

        // 受击音效
        origin.getWorld().playSound(origin, Sound.ENTITY_ARMOR_STAND_HIT, 0.6f, 0.9f);

        stand.teleport(hitBack);

        final int totalTicks = 5;
        final int[] tick = {0};
        final UUID dummyId = dummy.getId();
        final BukkitTask[] taskRef = new BukkitTask[1];
        taskRef[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            tick[0]++;
            ArmorStand s = dummy.getStand();
            if (s == null || !s.isValid() || !dummies.containsKey(dummyId)) {
                // 实体已失效：清理并自取消，防止空转
                hitAnimations.remove(dummyId);
                taskRef[0].cancel();
                return;
            }
            if (tick[0] >= totalTicks) {
                // 动画结束：精确归位并自取消任务
                s.teleport(origin);
                hitAnimations.remove(dummyId);
                taskRef[0].cancel();
            } else {
                // 线性插值从 hitBack 回到 origin
                double progress = 1.0 - (double) (totalTicks - tick[0]) / totalTicks;
                Location cur = origin.clone();
                cur.setX(origin.getX() + (hitBack.getX() - origin.getX()) * (1 - progress));
                cur.setY(origin.getY() + (hitBack.getY() - origin.getY()) * (1 - progress));
                cur.setZ(origin.getZ() + (hitBack.getZ() - origin.getZ()) * (1 - progress));
                s.teleport(cur);
            }
        }, 1L, 1L);
        hitAnimations.put(dummy.getId(), taskRef[0]);
    }

    /**
     * 关服等场景取消所有进行中的受击动画任务。
     */
    public void cancelAllHitAnimations() {
        for (BukkitTask task : hitAnimations.values()) {
            task.cancel();
        }
        hitAnimations.clear();
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
     * 判断实体是否为训练假人（PDC 标记）。
     */
    public boolean isDummyEntity(Entity entity) {
        return entity != null
                && entity.getPersistentDataContainer().has(dummyKey(), PersistentDataType.BYTE);
    }

    /**
     * 判断玩家是否训练假人主人：owner PDC 缺失时保守返回 false（避免「任何人可收回」，
     * F9 默认只有放置者本人可收回；正常流程放置时必写 owner）。
     */
    public boolean isOwner(Entity entity, Player player) {
        String ownerId = entity.getPersistentDataContainer().get(ownerKey(), PersistentDataType.STRING);
        if (ownerId == null) {
            plugin.getLogger().warning("训练假人实体缺少 owner PDC 标记: " + entity.getUniqueId() + "，按非主人处理");
            return false;
        }
        return ownerId.equalsIgnoreCase(player.getUniqueId().toString());
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

    public boolean removeDummy(UUID id) {
        // 取消进行中的受击动画任务
        BukkitTask anim = hitAnimations.remove(id);
        if (anim != null) {
            anim.cancel();
        }
        Dummy d = dummies.remove(id);
        if (d != null) {
            // 管理员删除时把假人身上的装备掉落在原地，避免物品静默丢失
            Location loc = d.getLocation();
            if (loc != null && loc.getWorld() != null) {
                for (Dummy.EquipmentSlot slot : Dummy.EquipmentSlot.values()) {
                    ItemStack item = d.getEquipment(slot);
                    if (item != null) {
                        loc.getWorld().dropItemNaturally(loc, item);
                    }
                }
            }
            d.remove();
        }
        // 内存记录中不存在（从未加载或已删除）视为失败
        boolean existed = storage.get(id) != null;
        storage.remove(id);
        return existed || d != null;
    }

    // ============ 持久化 / 恢复 / 清理 ============

    /**
     * 保存全部在场训练假人（含 yaw/pitch）。
     */
    public void saveAll() {
        for (Dummy d : dummies.values()) {
            if (d == null || !d.isValid()) {
                continue;
            }
            String displayName = d.getStand().getCustomName();
            if (displayName == null) {
                displayName = plugin.getConfigManager().getString("npc-name", "&e训练假人");
            }
            try {
                storage.saveRecord(DummyRecord.fromLocation(d.getId(), d.getOwner(), d.getLocation(), displayName));
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("无法保存训练假人 " + d.getId() + ": " + e.getMessage());
            }
        }
    }

    /**
     * 恢复全部已保存训练假人：世界未加载跳过，区块未加载用 getChunkAtAsync 异步生成（Leaf 禁止同步 getChunk）。
     */
    public void restoreAll() {
        for (DummyRecord rec : storage.all()) {
            try {
                World w = Bukkit.getWorld(rec.world());
                if (w == null) {
                    plugin.getLogger().fine("跳过恢复训练假人 " + rec.uuid() + "：世界未加载 " + rec.world());
                    continue;
                }
                Location loc = rec.toLocation();
                if (loc == null) {
                    plugin.getLogger().fine("跳过恢复训练假人 " + rec.uuid() + "：位置无效");
                    continue;
                }
                if (w.isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) {
                    spawnRestored(rec, loc);
                } else {
                    // Leaf 禁止主线程同步 getChunk：getChunkAtAsync(Location) 返回 CompletableFuture，
                    // 完成后在主线程执行（Paper 保证）；回调内做主线程防御，非主线程则转主线程再恢复。
                    w.getChunkAtAsync(loc).thenAccept(chunk -> {
                        if (chunk == null || !chunk.isLoaded()) {
                            return;
                        }
                        Runnable run = () -> {
                            try {
                                spawnRestored(rec, loc);
                            } catch (RuntimeException e) {
                                plugin.getLogger().warning("异步恢复训练假人 " + rec.uuid() + " 失败: " + e.getMessage());
                            }
                        };
                        if (Bukkit.isPrimaryThread()) {
                            run.run();
                        } else {
                            plugin.getServer().getScheduler().runTask(plugin, run);
                        }
                    });
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("恢复训练假人 " + rec.uuid() + " 失败: " + e.getMessage());
            }
        }
    }

    /**
     * 根据存储记录重新生成一个训练假人实体并登记。已存在则跳过（防重复）。
     */
    private Dummy spawnRestored(DummyRecord rec, Location loc) {
        if (dummies.containsKey(rec.uuid())) {
            return dummies.get(rec.uuid());
        }
        // 复用已存在的同 id PDC 实体（setPersistent(true) 的盔甲架会随区块持久化，
        // 重启后可能仍留在世界，直接再 spawn 会导致重复实体）。
        ArmorStand existing = findExistingById(rec.uuid());
        if (existing != null) {
            Dummy dummy = new Dummy(rec.uuid(), rec.owner(), existing);
            dummy.configureStatic();
            updateDisplayName(dummy);
            dummies.put(rec.uuid(), dummy);
            return dummy;
        }

        ArmorStand stand = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.getPersistentDataContainer().set(dummyKey(), PersistentDataType.BYTE, (byte) 1);
        stand.getPersistentDataContainer().set(ownerKey(), PersistentDataType.STRING, rec.owner().toString());
        stand.getPersistentDataContainer().set(idKey(), PersistentDataType.STRING, rec.uuid().toString());

        Dummy dummy = new Dummy(rec.uuid(), rec.owner(), stand);
        dummy.configureStatic();
        updateDisplayName(dummy);

        dummies.put(rec.uuid(), dummy);
        return dummy;
    }

    /**
     * 在世界中查找 id PDC 与给定 uuid 相同的已存在盔甲架（重启持久化残留时复用）。
     */
    private ArmorStand findExistingById(UUID uuid) {
        String idStr = uuid.toString();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof ArmorStand stand
                        && idStr.equals(entity.getPersistentDataContainer().get(idKey(), PersistentDataType.STRING))) {
                    return stand;
                }
            }
        }
        return null;
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
                    plugin.getLogger().warning("清理孤儿训练假人时发现缺少 id PDC 标记的实体 " + entity.getUniqueId() + "，已移除");
                    entity.remove();
                    continue;
                }
                try {
                    UUID uuid = UUID.fromString(idStr);
                    if (!dummies.containsKey(uuid) && storage.get(uuid) == null) {
                        entity.remove();
                        continue;
                    }
                    // 已跟踪的假人：若世界里出现的是另一台同 id 实体（如恢复重建导致的重复），移除残留
                    Dummy tracked = dummies.get(uuid);
                    if (tracked != null && tracked.getEntity() != null
                            && !tracked.getEntity().getUniqueId().equals(entity.getUniqueId())) {
                        plugin.getLogger().warning("发现重复训练假人实体 " + entity.getUniqueId() + "（id=" + uuid + "），已移除");
                        entity.remove();
                    }
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("清理孤儿训练假人时遇到非法 UUID '" + idStr + "': " + e.getMessage());
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
