package top.miragedge.dummy.listener;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import top.miragedge.dummy.MiragEdgeDummy;
import top.miragedge.dummy.damage.DamageCalculator;
import top.miragedge.dummy.dummy.Dummy;
import top.miragedge.dummy.dummy.DummyManager;
import top.miragedge.dummy.util.Messages;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 木人桩全部交互逻辑。
 *
 * <p>事件矩阵（详见 docs/DEVELOPMENT.md §7）：</p>
 * <ul>
 *   <li>{@link #onPlace} —— 手持木人桩物品右键地面 → 放置</li>
 *   <li>{@link #onDamage} —— 攻击假人 → 伤害归零 + 计算真实伤害 ActionBar 显示</li>
 *   <li>{@link #onInteract} —— 右键假人：空手=取下装备 / 手持=穿装备 / 潜行=收回</li>
 *   <li>{@link #onSwing} —— 空挥补伤害显示（解决假人无敌不掉血导致事件缺失问题）</li>
 *   <li>{@link #onAnyDamage} —— 任何伤害源对假人归零（防火/防击退）</li>
 * </ul>
 */
public class DummyListener implements Listener {

    private final MiragEdgeDummy plugin;
    private final DummyManager dummyManager;

    // 防抖：空手取下装备与收回之间的 500ms 间隔（防止误触发）
    private final Map<String, Long> lastPickup = new ConcurrentHashMap<>();
    // 空挥记录：玩家+假人组合 250ms 防抖
    private final Map<String, Long> lastSwing = new ConcurrentHashMap<>();
    // 最后攻击时间：空挥触发伤害显示时用于区分真实动画事件
    private final Map<UUID, Long> lastAttackTime = new ConcurrentHashMap<>();

    public DummyListener(MiragEdgeDummy plugin, DummyManager dummyManager) {
        this.plugin = plugin;
        this.dummyManager = dummyManager;
        // 注意：不缓存 Messages，每次交互都通过 plugin.messages() 取，
        // 否则 /dummy reload 后消息不会刷新。
    }

    // ============ 放置 ============

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlace(PlayerInteractEvent event) {
        // 仅主手 + 右键方块
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        if (item != null && dummyManager.isDummyItem(item)) {
            event.setCancelled(true);
            dummyManager.spawnDummy(event.getPlayer());
        }
    }

    // ============ 伤害 ============

    /**
     * 任何伤害对假人归零（防火、防击退）。
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnyDamage(EntityDamageEvent event) {
        Entity entity = event.getEntity();
        if (!dummyManager.isDummyEntity(entity)) {
            return;
        }
        event.setDamage(0);
        entity.setVelocity(new Vector(0, 0, 0));
        entity.setFireTicks(0);
    }

    /**
     * 玩家攻击假人：计算真实伤害并显示（ActionBar/chat 由配置决定）。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        Entity entity = event.getEntity();
        if (!dummyManager.isDummyEntity(entity)) {
            return;
        }
        Dummy dummy = dummyManager.getDummyByEntity(entity);
        if (dummy == null) {
            return;
        }

        // 识别攻击者：直接玩家，或投射物的发射者为玩家
        Entity damager = event.getDamager();
        Player player = null;
        if (damager instanceof Player) {
            player = (Player) damager;
        } else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            player = shooter;
        }
        if (player == null) {
            return;
        }

        // baseDamage：优先 BASE 修饰符（新版 API），失败降级为 getDamage()
        double baseDamage;
        try {
            baseDamage = event.getDamage(EntityDamageEvent.DamageModifier.BASE);
        } catch (NoSuchMethodError | IllegalArgumentException e) {
            baseDamage = event.getDamage();
        }
        if (baseDamage <= 0.0001) {
            baseDamage = DamageCalculator.getPlayerBaseDamage(player);
        }

        double finalDamage = DamageCalculator.calculateDamage(entity, baseDamage, event.getCause());
        sendDamage(player, finalDamage);
        lastAttackTime.put(player.getUniqueId(), System.currentTimeMillis());
    }

    // ============ 交互（装备/取下/收回） ============

    /**
     * 右键假人（穿透事件，含位置信息）。
     * 空手 → 取下最后装备的槽位/遍历取回装备；
     * 手持（潜行）→ 收回；手持（不潜行）→ 装备到对应槽位。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractAtEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!dummyManager.isDummyEntity(clicked)) {
            return;
        }
        // 阻止原版盔甲架交互
        event.setCancelled(true);
        Dummy dummy = dummyManager.getDummyByEntity(clicked);
        if (dummy == null) {
            return;
        }

        Player player = event.getPlayer();

        // 决定 activeItem（副手语义：主手非空用主手，否则副手；都空则为 null）
        ItemStack main = player.getInventory().getItemInMainHand();
        ItemStack off = player.getInventory().getItemInOffHand();
        ItemStack active = null;
        boolean fromMainHand = false;
        if (main != null && main.getType() != Material.AIR) {
            active = main;
            fromMainHand = true;
        } else if (off != null && off.getType() != Material.AIR) {
            active = off;
        }

        // 防抖：同一 玩家+假人 500ms 内只处理一次；处理前先记录
        String key = player.getUniqueId() + ":" + clicked.getUniqueId();
        long now = System.currentTimeMillis();
        Long prevPickup = lastPickup.get(key);
        if (prevPickup != null && (now - prevPickup) < 500) {
            return;
        }
        lastPickup.put(key, now);

        // 分支顺序（关键）：潜行收回 → 空手取下 → 木人桩物品 → 穿装备
        if (player.isSneaking()) {
            removeDummy(player, clicked, dummy);
            return;
        }

        if (active == null || active.getType() == Material.AIR) {
            takeOffEquipment(player, clicked, dummy);
            return;
        }

        if (dummyManager.isDummyItem(active)) {
            boolean requireSneak = plugin.getConfigManager().getBoolean("removal.require-sneak", true);
            if (requireSneak) {
                player.sendMessage(plugin.messages().fmt("messages.sneak-to-remove"));
            } else {
                // 不允许手持木人桩物品穿上：直接走收回逻辑
                removeDummy(player, clicked, dummy);
            }
            return;
        }

        equipItem(player, clicked, dummy, active, main, off, fromMainHand);
    }

    /**
     * 兼容旧版交互事件拦截（非 At 变体），防止重复触发原版交互。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractLegacy(PlayerInteractEntityEvent event) {
        if (dummyManager.isDummyEntity(event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    // ============ 空挥补伤害 ============

    /**
     * 玩家空挥：当瞄准假人且 250ms 防抖通过时，手动计算伤害显示。
     * 覆盖「假人无敌（invulnerable）导致实体伤害事件部分缺失」的边界。
     */
    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        Player player = event.getPlayer();
        if (player.getGameMode() == GameMode.CREATIVE) {
            return;
        }

        long now = System.currentTimeMillis();
        // 真实攻击事件 100ms 内，避免空挥重复计算
        Long lastHit = lastAttackTime.get(player.getUniqueId());
        if (lastHit != null && (now - lastHit) < 100) {
            return;
        }

        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection();
        for (Entity e : player.getWorld().getNearbyEntities(eye, 5, 5, 5)) {
            if (!(e instanceof ArmorStand) || !dummyManager.isDummyEntity(e)) {
                continue;
            }
            Vector to = e.getLocation().add(0, 1, 0).toVector().subtract(eye.toVector());
            double dist = to.length();
            if (dist > 5) {
                continue;
            }
            double dot = to.normalize().dot(dir);
            if (dot < 0.8) {
                continue;
            }

            // 玩家+假人组合 250ms 防抖
            String swingKey = player.getUniqueId() + ":" + e.getUniqueId();
            Long lastSwingTime = lastSwing.get(swingKey);
            if (lastSwingTime != null && (now - lastSwingTime) < 250) {
                continue;
            }
            lastSwing.put(swingKey, now);

            double base = DamageCalculator.getPlayerBaseDamage(player);
            double finalDmg = DamageCalculator.calculateDamage(e, base, EntityDamageEvent.DamageCause.ENTITY_ATTACK);
            sendDamage(player, finalDmg);
            lastAttackTime.put(player.getUniqueId(), now);
            break;
        }
    }

    // ============ 私有工具 ============

    /**
     * 收回假人：校验主人（受 allow-non-owners-break 控制）→ pickupDummy → 提示。
     */
    private void removeDummy(Player player, Entity clicked, Dummy dummy) {
        boolean allowNonOwnersBreak = plugin.getConfigManager().getBoolean("allow-non-owners-break", false);
        if (!allowNonOwnersBreak && !dummyManager.isOwner(clicked, player)) {
            player.sendMessage(plugin.messages().fmt("messages.not-owner"));
            return;
        }
        // 关键决策：removed 消息的唯一来源。
        // 经核对 DummyManager.pickupDummy() 内部已发送 messages.removed，
        // 为避免重复提示，listener 这里不再补发（保持单一来源）。
        // 若集成者改为由 listener 负责 removed，需同步删掉 DummyManager 内的那行。
        dummyManager.pickupDummy(player, dummy);
    }

    /**
     * 空手取下装备：优先取 PDC 记录的 last_equipped 槽，否则遍历取第一个有装备的槽。
     */
    private void takeOffEquipment(Player player, Entity clicked, Dummy dummy) {
        String last = clicked.getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "last_equipped"), PersistentDataType.STRING);

        Dummy.EquipmentSlot slot = null;
        if (last != null && !last.isEmpty()) {
            try {
                slot = Dummy.EquipmentSlot.valueOf(last);
            } catch (IllegalArgumentException ignored) {
                slot = null;
            }
        }
        if (slot == null) {
            for (Dummy.EquipmentSlot s : Dummy.EquipmentSlot.values()) {
                if (dummy.getEquipment(s) != null) {
                    slot = s;
                    break;
                }
            }
        }
        if (slot == null) {
            player.sendMessage(plugin.messages().fmt("messages.nothing-to-take"));
            return;
        }

        ItemStack item = dummy.getEquipment(slot);
        if (item == null) {
            player.sendMessage(plugin.messages().fmt("messages.nothing-to-take"));
            return;
        }

        // 加入背包，放不下的自然掉落在假人位置
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        for (ItemStack left : leftover.values()) {
            clicked.getWorld().dropItemNaturally(clicked.getLocation(), left);
        }
        dummy.setEquipment(slot, null);

        // 若取下的正是最近装备的槽，清掉记录
        if (last != null && last.equals(slot.name())) {
            clicked.getPersistentDataContainer().remove(new NamespacedKey(plugin, "last_equipped"));
        }
        player.sendMessage(plugin.messages().fmt("messages.retrieved", "item", displayName(item)));
    }

    /**
     * 穿装备：装备 1 个到对应槽位，扣减手中 1 个，旧装备放回背包/掉落。
     */
    private void equipItem(Player player, Entity clicked, Dummy dummy, ItemStack active,
                           ItemStack main, ItemStack off, boolean fromMainHand) {
        Dummy.EquipmentSlot slot = slotFor(active);
        ItemStack copy = active.clone();
        copy.setAmount(1);

        ItemStack old = dummy.getEquipment(slot);
        dummy.setEquipment(slot, copy);

        // 扣减 1 个来源物品
        if (fromMainHand) {
            main.setAmount(main.getAmount() - 1);
            if (main.getAmount() <= 0) {
                player.getInventory().setItemInMainHand(null);
            }
        } else {
            off.setAmount(off.getAmount() - 1);
            if (off.getAmount() <= 0) {
                player.getInventory().setItemInOffHand(null);
            }
        }

        // 旧装备放回背包，剩余掉落
        if (old != null) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(old);
            for (ItemStack left : leftover.values()) {
                clicked.getWorld().dropItemNaturally(clicked.getLocation(), left);
            }
        }

        clicked.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "last_equipped"), PersistentDataType.STRING, slot.name());
        player.sendMessage(plugin.messages().fmt("messages.equipped", "item", displayName(copy)));
    }

    /**
     * 按配置的 notifications.mode 发送伤害显示（chat / 其余走 ActionBar）。
     */
    private void sendDamage(Player player, double finalDamage) {
        Messages m = plugin.messages();
        int precision = plugin.getConfigManager().getInt("notifications.precision", 1);
        String text = m.fmtDamage(finalDamage, finalDamage / 2.0, precision);
        String mode = plugin.getConfigManager().getString("notifications.mode", "actionbar");
        if (mode.equalsIgnoreCase("chat")) {
            player.sendMessage(text);
        } else {
            player.sendActionBar(text);
        }
    }

    /**
     * 物品显示名：有自定义名用显示名，否则用物品类型名。
     */
    private String displayName(ItemStack item) {
        if (item != null && item.getItemMeta() != null && item.getItemMeta().hasDisplayName()) {
            return item.getItemMeta().getDisplayName();
        }
        return item != null ? item.getType().name() : "";
    }

    // ============ 工具方法 ============

    /**
     * 根据手中物品推断装备槽位：
     * HELMET←头盔类 / CHESTPLATE←胸甲类 / LEGGINGS←护腿类 / BOOTS←靴子类 / 其余→HAND。
     */
    private Dummy.EquipmentSlot slotFor(ItemStack item) {
        Material m = item.getType();
        String name = m.name();
        if (name.endsWith("_HELMET") || m == Material.TURTLE_HELMET) {
            return Dummy.EquipmentSlot.HELMET;
        }
        if (name.endsWith("_CHESTPLATE")) {
            return Dummy.EquipmentSlot.CHESTPLATE;
        }
        if (name.endsWith("_LEGGINGS")) {
            return Dummy.EquipmentSlot.LEGGINGS;
        }
        if (name.endsWith("_BOOTS")) {
            return Dummy.EquipmentSlot.BOOTS;
        }
        return Dummy.EquipmentSlot.HAND;
    }
}
