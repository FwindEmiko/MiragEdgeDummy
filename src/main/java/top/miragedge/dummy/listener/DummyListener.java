package top.miragedge.dummy.listener;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.util.Vector;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import top.miragedge.dummy.MiragEdgeDummy;
import top.miragedge.dummy.damage.DamageCalculator;
import top.miragedge.dummy.dummy.Dummy;
import top.miragedge.dummy.dummy.DummyManager;
import top.miragedge.dummy.util.Messages;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 训练假人全部交互逻辑。
 *
 * <p>事件矩阵（详见 docs/DEVELOPMENT.md §7）：</p>
 * <ul>
 *   <li>{@link #onPlace} —— 手持训练假人物品右键地面 → 放置</li>
 *   <li>{@link #onAnyDamage} —— 任何伤害对假人归零（MONITOR：捕获含高级附魔的真实受击伤害并显示，
 *       然后 setDamage(0) 抵消；不 cancel 以保留玩家攻击冷却重置与真实击退）</li>
 *   <li>{@link #onInteract} —— 右键假人：空手=取下装备 / 手持=穿装备 / 潜行=收回</li>
 *   <li>{@link #onSwing} —— 记录点击时间戳（CPS 统计），不产生伤害显示（伤害一律以真实事件为准）</li>
 *   <li>{@link #onDeath} —— 死亡兜底：任何途径都不允许假人死亡</li>
 * </ul>
 */
public class DummyListener implements Listener {

    private final MiragEdgeDummy plugin;
    private final DummyManager dummyManager;

    // 防抖：空手取下装备与收回之间的 500ms 间隔（防止误触发）
    private final Map<String, Long> lastPickup = new ConcurrentHashMap<>();
    // CPS：每玩家最近 1 秒内的攻击（挥臂）时间戳队列
    private final Map<UUID, ArrayDeque<Long>> cpsClicks = new ConcurrentHashMap<>();
    // CPS 去重：同一服务器 tick 内的 伤害事件+挥臂事件 视为同一次点击（只计一次）
    private final Map<UUID, Integer> lastCpsTick = new ConcurrentHashMap<>();
    // 右键交互去重：At 与非 At 事件同 tick 双触发时只处理一次
    private final Map<String, Integer> lastInteractTick = new ConcurrentHashMap<>();
    // 上次清理防抖表的时间戳（防止 Map 无限增长）
    private long lastPrune = 0L;

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

    // ============ 伤害（真实受击伤害的权威来源） ============

    /**
     * 任何伤害对假人归零；玩家/投射物攻击时捕获真实受击伤害并显示。
     *
     * <p>设计要点（对应「攻击伤害应使用真实受击伤害 + 兼容高级附魔」的需求）：</p>
     * <ol>
     *   <li>假人现已不是无敌实体，服务端会为每次攻击产生真实
     *       {@code EntityDamageByEntityEvent}——只要假人在场上，包括 Aiyatsbus / EcoEnchants 等
     *       高级附魔在内的所有插件都会照常结算伤害并触发各自命中效果；</li>
     *   <li>在 MONITOR（最后一档优先级）读取伤害：此时所有插件对伤害的修改均已应用，
     *       读到的就是「真实受击伤害」（含武器附魔、攻击冷却、暴击、护甲减伤等全部成分）；</li>
     *   <li>读取后用 {@code setDamage(0)} 抵消——只抵消血量结算，刻意<b>不</b> cancel：
     *       cancel 会导致服务端跳过攻击冷却重置（玩家永远满蓄力，伤害恒满，违背「冷却影响伤害」），
     *       且丧失原版真实击退（物理回弹的前提）；</li>
     *   <li>解除无敌帧（noDamageTicks），支持高 CPS 连点每次都产生事件。</li>
     * </ol>
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onAnyDamage(EntityDamageEvent event) {
        // 已被低优先级插件取消的攻击不处理（避免假回弹/错误显示）
        if (event.isCancelled()) {
            return;
        }
        Entity entity = event.getEntity();
        if (!dummyManager.isDummyEntity(entity)) {
            return;
        }
        Dummy dummy = dummyManager.getDummyByEntity(entity);
        if (dummy == null) {
            return;
        }

        // 玩家 / 投射物攻击：捕获真实伤害 → 效果 → 显示
        // try/catch：表现路径（粒子/数字生成等）异常不得影响下方 setDamage(0)（假人不能掉血）
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            try {
                handlePlayerAttack(byEntity, dummy);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("处理假人受击表现异常: " + e.getMessage());
            }
        }

        // 统一抵消伤害（见类注释：不 cancel，保留冷却重置与真实击退）
        event.setDamage(0);
        entity.setFireTicks(0);

        // 解除无敌帧（noDamageTicks）：假人掉血恒为 0，但仍会被「受伤」逻辑打上 0.5s 无敌，
        // 不清除会让连点（高 CPS）只触发第一次事件。
        // 1) 同步清一次（若服务端本次未设置无敌帧则直接生效）；
        // 2) 下一 tick 再清一次（覆盖服务端在事件结束后设置无敌帧的场景）。
        // 注：Paper 调度器在每 tick 实体处理前执行 runTask，故下一 tick 的清除先于该 tick 的攻击，
        //     人类连点（≤20 CPS）每次都能触发真实事件；这是尽力而为的近似，极端 CPS 不保证。
        final Entity fe = entity;
        if (fe instanceof LivingEntity living) {
            living.setNoDamageTicks(0);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (fe.isValid() && fe instanceof LivingEntity living2) {
                living2.setNoDamageTicks(0);
            }
        });
    }

    /**
     * 处理玩家（或玩家投射物）对假人的真实攻击：计算并显示真实受击伤害、触发受击表现。
     *
     * <p>注：EntityDamageEvent.DamageModifier 在 26.2 已整体弃用（since 1.20.4），但官方未提供
     * 读取「护甲/保护减伤分量」的非弃用替代 API（org.bukkit.damage.DamageSource 不含分量），
     * 真实受击伤害捕获必须读取 ARMOR/MAGIC 修饰符，故在此方法及辅助方法上抑制告警。</p>
     */
    @SuppressWarnings("deprecation")
    private void handlePlayerAttack(EntityDamageByEntityEvent event, Dummy dummy) {
        Entity damager = event.getDamager();
        Player player = null;
        if (damager instanceof Player p) {
            player = p;
        } else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            player = p;
        }
        if (player == null) {
            return;
        }

        // ---- 1. 真实受击伤害 ----
        // 基准用 MONITOR 时刻的「最终伤害」：它已包含服务端结算的护甲/韧性/保护附魔减伤，
        // 以及全部插件（含 Aiyatsbus / EcoEnchants 等高级附魔）对伤害的所有修改
        // （无论其用 setDamage(BASE)、setDamage(CUSTOM) 还是 getDamage()*k 形式）。
        double finalDamage = event.getFinalDamage();
        double armorMod = safeModifier(event, EntityDamageEvent.DamageModifier.ARMOR);
        double magicMod = safeModifier(event, EntityDamageEvent.DamageModifier.MAGIC);

        double displayed;
        if (Math.abs(armorMod) > 0.001) {
            // 服务端已按假人护甲（属性）结算 → 最终伤害即完整真实受击伤害
            displayed = finalDamage;
        } else if (Math.abs(magicMod) > 0.001) {
            // 服务端结算了保护附魔但未结算护甲（盔甲架装备属性不生效的常见情形）：
            // 最终伤害已含保护减伤，只需再补护甲减伤，避免保护被重复计算。
            displayed = DamageCalculator.applyArmorOnly(dummy.getEntity(), finalDamage);
        } else {
            // 服务端护甲与保护都未结算 → 套用完整护甲+保护公式
            displayed = DamageCalculator.calculateDamage(dummy.getEntity(), finalDamage, event.getCause());
        }
        // 兜底：事件最终伤害不可用（例如创造模式零伤害）→ 手动估算（含攻击冷却因子）
        if (finalDamage <= 0.0001
                && (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                    || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK)) {
            double manual = DamageCalculator.getPlayerBaseDamage(player);
            displayed = DamageCalculator.calculateDamage(dummy.getEntity(), manual, event.getCause());
        }

        // ---- 2. 受击表现（音效 / 粒子 / 浮动伤害数字 / 物理击退） ----
        boolean crit = isCrit(player, event);
        dummyManager.onHit(dummy, displayed, crit, recoilDirection(damager, dummy, player));

        // ---- 3. 伤害读数（ActionBar/chat 由配置决定） ----
        // 近战命中即一次点击：先记录本次点击（tick 去重，挥臂事件随后不会重复计），
        // 再读取 CPS，避免「伤害事件先于挥臂事件」导致读数恒少 1。
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            recordCps(player);
        }
        // 蓄力% 仅在近战时有意义（弓箭蓄力是另一套机制，不要误显示近战冷却）
        sendDamage(player, displayed, event.getCause());
    }

    /**
     * 读取指定伤害修饰符值；修饰符缺失/异常一律按 0 处理。
     */
    @SuppressWarnings("deprecation")
    private double safeModifier(EntityDamageByEntityEvent event, EntityDamageEvent.DamageModifier modifier) {
        try {
            return event.getDamage(modifier);
        } catch (RuntimeException e) {
            return 0.0;
        }
    }

    /**
     * 物理击退参考方向（假人被推离攻击者的水平方向）：
     * 投射物取其飞行方向；近战取 攻击者→假人 水平向量；退化用玩家视线方向。
     * 服务端原版击退优先（真实），此方向仅用于服务端未施加击退时的兜底冲量（见 DummyManager.onHit）。
     */
    private Vector recoilDirection(Entity damager, Dummy dummy, Player player) {
        if (damager instanceof Projectile projectile) {
            Vector v = projectile.getVelocity();
            v.setY(0);
            if (v.lengthSquared() > 1e-4) {
                return v.normalize();
            }
        }
        Location dl = dummy.getLocation();
        Vector dir = (dl != null ? dl.toVector() : player.getLocation().toVector())
                .subtract(damager.getLocation().toVector());
        dir.setY(0);
        if (dir.lengthSquared() < 1e-4) {
            dir = player.getLocation().getDirection();
            dir.setY(0);
            if (dir.lengthSquared() < 1e-4) {
                dir = new Vector(0, 0, -1);
            }
        }
        return dir.normalize();
    }

    /**
     * 原版暴击条件近似：近战攻击 + 未着地 + 有下落距离 + 不在水中。
     */
    private boolean isCrit(Player player, EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            return false;
        }
        // 原版暴击近似：有下落距离（= 攻击时处于空中）+ 不在水中。
        // 刻意不用已弃用的 Player.isOnGround()（since 1.16.1），fallDistance>0 已隐含未着地。
        return player.getFallDistance() > 0.0 && !player.isInWater();
    }

    // ============ 交互（装备/取下/收回） ============

    /**
     * 右键假人（穿透事件，含位置信息）。
     * 空手 → 取下最后装备的槽位/遍历取回装备；
     * 手持（潜行）→ 收回；手持（不潜行）→ 装备到对应槽位。
     * 玩家 NPC 实体可能不触发 At 变体，故业务逻辑抽到 {@link #handleInteract}，
     * 由 At 与非 At 两个事件共用（同 tick 去重）。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractAtEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!dummyManager.isDummyEntity(clicked)) {
            return;
        }
        // 阻止原版盔甲架交互
        event.setCancelled(true);
        handleInteract(event.getPlayer(), clicked);
    }

    /**
     * 假人主人显示名：离线玩家名，查不到则用 UUID。
     */
    private String ownerName(Dummy dummy) {
        String ownerStr = dummy.getOwner().toString();
        String name = Bukkit.getOfflinePlayer(dummy.getOwner()).getName();
        return name != null ? name : ownerStr;
    }

    /**
     * 兼容旧版交互事件（非 At 变体）：对盔甲架，At 变体已处理 → 只取消；
     * 对可能不触发 At 变体的玩家 NPC 实体，走同一套业务逻辑（tick 去重防双触发）。
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractLegacy(PlayerInteractEntityEvent event) {
        Entity clicked = event.getRightClicked();
        if (!dummyManager.isDummyEntity(clicked)) {
            return;
        }
        event.setCancelled(true);
        if (dummyManager.getDummyByEntity(clicked) == null) {
            return;
        }
        handleInteract(event.getPlayer(), clicked);
    }

    /**
     * 右键假人业务逻辑（At 与非 At 事件共用；同 tick 去重防止双触发）。
     */
    private void handleInteract(Player player, Entity clicked) {
        // 同 tick 去重：一次右键若同时触发 At 与非 At 事件，只处理一次
        String dedupKey = player.getUniqueId() + ":" + clicked.getUniqueId();
        int tick = Bukkit.getCurrentTick();
        Integer lastTick = lastInteractTick.get(dedupKey);
        if (lastTick != null && lastTick.intValue() == tick) {
            return;
        }
        lastInteractTick.put(dedupKey, tick);

        Dummy dummy = dummyManager.getDummyByEntity(clicked);
        if (dummy == null) {
            return;
        }

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

        // 交互越权防护：取下/穿上/收回均受 allow-non-owners-break 控制，
        // 非主人不能扒甲/换装/收回（F9 语义统一；默认仅放置者本人可操作装备）
        boolean allowNonOwners = plugin.getConfigManager().getBoolean("allow-non-owners-break", false);
        if (!allowNonOwners && !dummyManager.isOwner(clicked, player)) {
            player.sendMessage(plugin.messages().fmt("messages.not-owner",
                    "owner", ownerName(dummy)));
            return;
        }

        // 分支顺序（UX 优化）：训练假人物品（持手）优先判定收回 → 空手取下 → 穿装备
        if (dummyManager.isDummyItem(active)) {
            boolean requireSneak = plugin.getConfigManager().getBoolean("removal.require-sneak", true);
            if (requireSneak && !player.isSneaking()) {
                // 需要潜行才可收回：提示
                player.sendMessage(plugin.messages().fmt("messages.sneak-to-remove"));
            } else {
                removeDummy(player, clicked, dummy);
            }
            return;
        }

        if (active == null || active.getType() == Material.AIR) {
            takeOffEquipment(player, clicked, dummy);
            return;
        }

        equipItem(player, clicked, dummy, active, main, off, fromMainHand);
    }

    // ============ 世界加载（补恢复） ============

    /**
     * 世界加载时重试恢复该世界的训练假人（启动 100 tick 时世界可能尚未加载，
     * restoreAll 幂等：已恢复/已有实体的记录会被跳过，不会重复生成）。
     */
    @EventHandler
    public void onWorldLoad(org.bukkit.event.world.WorldLoadEvent event) {
        dummyManager.restoreAll();
    }

    /**
     * 区块加载时恢复该区块内的训练假人（玩家 NPC 不持久化，区块重载后需重建；
     * restoreChunk 幂等：已跟踪的会跳过）。
     */
    @EventHandler
    public void onChunkLoad(org.bukkit.event.world.ChunkLoadEvent event) {
        dummyManager.restoreChunk(event.getChunk().getWorld(),
                event.getChunk().getX(), event.getChunk().getZ());
    }

    // ============ CPS（每秒攻击次数）统计 ============

    /**
     * 挥臂动画 → 记录一次点击（CPS 数据源）。
     * 伤害一律以真实 EntityDamageEvent 为准（onAnyDamage），这里不再做任何伤害估算，
     * 避免「空挥近似伤害」与高级附魔真实伤害不一致。
     */
    @EventHandler
    public void onSwing(PlayerAnimationEvent event) {
        if (event.getAnimationType() != PlayerAnimationType.ARM_SWING) {
            return;
        }
        recordCps(event.getPlayer());
    }

    /**
     * 记录一次点击时间戳，并裁剪掉 1 秒前的旧记录（队列即 1s 滑动窗口）。
     * 同一服务器 tick 内的多次事件（如一次近战命中的 伤害事件 + 挥臂事件）去重为同一次点击。
     */
    private void recordCps(Player player) {
        int tick = Bukkit.getCurrentTick();
        Integer prevTick = lastCpsTick.get(player.getUniqueId());
        if (prevTick != null && prevTick.intValue() == tick) {
            return;
        }
        lastCpsTick.put(player.getUniqueId(), tick);

        long now = System.currentTimeMillis();
        ArrayDeque<Long> queue = cpsClicks.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        queue.addLast(now);
        long cutoff = now - 1000L;
        while (!queue.isEmpty() && queue.peekFirst() < cutoff) {
            queue.pollFirst();
        }
        // 额外上限：即使高频连点也防止队列异常膨胀
        while (queue.size() > 120) {
            queue.pollFirst();
        }
    }

    /**
     * 当前 CPS = 最近 1 秒内的点击次数。
     */
    private int getCps(Player player) {
        ArrayDeque<Long> queue = cpsClicks.get(player.getUniqueId());
        if (queue == null || queue.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        long cutoff = now - 1000L;
        while (!queue.isEmpty() && queue.peekFirst() < cutoff) {
            queue.pollFirst();
        }
        return queue.size();
    }

    // ============ 死亡兜底 ============

    /**
     * 训练假人不应死亡（伤害已在 MONITOR 抵消）。此处兜底任何绕过伤害事件的致死途径
     * （/kill、插件直接 kill 等）：取消死亡事件并回满血量。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (!dummyManager.isDummyEntity(entity)) {
            return;
        }
        event.setCancelled(true);
        plugin.getLogger().warning("训练假人 " + entity.getUniqueId() + " 触发死亡事件，已取消并回满血量");
        if (entity instanceof LivingEntity living) {
            // getMaxHealth() 已弃用（since 1.21.x），改读属性值
            double max = 1024;
            AttributeInstance attr = living.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) {
                max = attr.getValue();
            }
            living.setHealth(max);
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
        // 500ms 防抖：同一 玩家+假人 只处理一次（校验通过后才占用冷却）
        String key = player.getUniqueId() + ":" + clicked.getUniqueId();
        long now = System.currentTimeMillis();
        String dk = "remove:" + key;
        Long prev = lastPickup.get(dk);
        if (prev != null && (now - prev) < 500) {
            return;
        }
        lastPickup.put(dk, now);

        // 关键决策：removed 消息的唯一来源。
        // 经核对 DummyManager.pickupDummy() 内部已发送 messages.removed，
        // 为避免重复提示，listener 这里不再补发（保持单一来源）。
        dummyManager.pickupDummy(player, dummy);
    }

    /**
     * 空手取下装备：优先取 PDC 记录的 last_equipped 槽，否则遍历取第一个有装备的槽。
     * 皮肤头（npc-skin 启用的头盔槽）不可取下。
     */
    private void takeOffEquipment(Player player, Entity clicked, Dummy dummy) {
        // 500ms 防抖：同一 玩家+假人 只处理一次（与收回路径隔离，避免置信冲突）
        String key = player.getUniqueId() + ":" + clicked.getUniqueId();
        long now = System.currentTimeMillis();
        String dk = "take:" + key;
        Long prev = lastPickup.get(dk);
        if (prev != null && (now - prev) < 500) {
            return;
        }
        lastPickup.put(dk, now);

        // 皮肤头是否占用头盔槽：按实体当前头盔 PDC 状态判断（而非读配置——reload 改配置后也应正确）
        boolean helmetIsSkin = dummyManager.isSkinHead(dummy.getEquipment(Dummy.EquipmentSlot.HELMET));

        String last = clicked.getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "last_equipped"), PersistentDataType.STRING);

        Dummy.EquipmentSlot slot = null;
        if (last != null && !last.isEmpty()) {
            try {
                slot = Dummy.EquipmentSlot.valueOf(last);
            } catch (IllegalArgumentException ignored) {
                slot = null;
            }
            // 皮肤头占用头盔槽：不可取下，视为无记录
            if (slot == Dummy.EquipmentSlot.HELMET && helmetIsSkin) {
                slot = null;
            }
            // last_equipped 指向的槽已空（如外部改动过假人装备）：清掉过期记录并回退遍历
            if (slot != null && dummy.getEquipment(slot) == null) {
                clicked.getPersistentDataContainer().remove(new NamespacedKey(plugin, "last_equipped"));
                slot = null;
            }
        }
        if (slot == null) {
            for (Dummy.EquipmentSlot s : Dummy.EquipmentSlot.values()) {
                if (s == Dummy.EquipmentSlot.HELMET && helmetIsSkin) {
                    continue;
                }
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

        // 加入背包，放不下的自然掉落在假人位置（并提示背包满）
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
        if (!leftover.isEmpty()) {
            for (ItemStack left : leftover.values()) {
                clicked.getWorld().dropItemNaturally(clicked.getLocation(), left);
            }
            player.sendMessage(plugin.messages().fmt("messages.inventory-full"));
        }
        dummy.setEquipment(slot, null);

        // last_equipped 记录已被消费（无论取的是记录槽还是 fallback 槽），一律清除避免残留
        if (last != null) {
            clicked.getPersistentDataContainer().remove(new NamespacedKey(plugin, "last_equipped"));
        }
        // 刷新头顶护甲值显示
        dummyManager.updateDisplayName(dummy);
        player.sendMessage(plugin.messages().fmt("messages.retrieved", "item", displayName(item)));
    }

    /**
     * 穿装备：装备 1 个到对应槽位，扣减手中 1 个，旧装备放回背包/掉落。
     * 启用皮肤时头盔槽被皮肤占用，禁止覆盖。
     */
    private void equipItem(Player player, Entity clicked, Dummy dummy, ItemStack active,
                           ItemStack main, ItemStack off, boolean fromMainHand) {
        Dummy.EquipmentSlot slot = slotFor(active);
        // 皮肤占用头盔槽：不允许覆盖皮肤（按实体当前头盔 PDC 状态判断，清晰提示而非静默换皮肤）
        if (slot == Dummy.EquipmentSlot.HELMET
                && dummyManager.isSkinHead(dummy.getEquipment(Dummy.EquipmentSlot.HELMET))) {
            player.sendMessage(plugin.messages().fmt("messages.skin-helmet-blocked"));
            return;
        }
        ItemStack copy = active.clone();
        copy.setAmount(1);

        ItemStack old = dummy.getEquipment(slot);
        dummy.setEquipment(slot, copy);

        // 扣减 1 个来源物品（显式写回，兼容 getItemInMainHand 返回镜像副本的 API 实现）
        if (fromMainHand) {
            main.setAmount(main.getAmount() - 1);
            if (main.getAmount() <= 0) {
                player.getInventory().setItemInMainHand(null);
            } else {
                player.getInventory().setItemInMainHand(main);
            }
        } else {
            off.setAmount(off.getAmount() - 1);
            if (off.getAmount() <= 0) {
                player.getInventory().setItemInOffHand(null);
            } else {
                player.getInventory().setItemInOffHand(off);
            }
        }

        // 旧装备放回背包，剩余掉落（并提示背包满）
        if (old != null) {
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(old);
            if (!leftover.isEmpty()) {
                for (ItemStack left : leftover.values()) {
                    clicked.getWorld().dropItemNaturally(clicked.getLocation(), left);
                }
                player.sendMessage(plugin.messages().fmt("messages.inventory-full"));
            }
        }

        clicked.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "last_equipped"), PersistentDataType.STRING, slot.name());
        // 刷新头顶护甲值显示
        dummyManager.updateDisplayName(dummy);
        player.sendMessage(plugin.messages().fmt("messages.equipped", "item", displayName(copy)));
    }

    /**
     * 供主类定时器调用的清理入口。
     */
    public void pruneMaps() {
        pruneMaps(System.currentTimeMillis());
    }

    /**
     * 定期清理防抖与 CPS Map 中超过 60 秒的过期条目，防止长驻内存无限增长。
     */
    private void pruneMaps(long now) {
        if (now - lastPrune < 60_000L) {
            return;
        }
        lastPrune = now;
        long cutoff = now - 60_000L;
        lastPickup.entrySet().removeIf(e -> e.getValue() < cutoff);
        cpsClicks.entrySet().removeIf(e -> e.getValue().isEmpty() || e.getValue().peekLast() < cutoff);
        // lastInteractTick 值是最新 tick（单调递增），用「过期 tick」清理：当前 tick 差值过大即移除
        int nowTick = Bukkit.getCurrentTick();
        lastInteractTick.entrySet().removeIf(e -> nowTick - e.getValue() > 100);
    }

    /**
     * 按配置的 notifications.mode 发送伤害显示（chat / 其余走 ActionBar），
     * ActionBar 附带攻击蓄力百分比（未满时）与 CPS（由 show-cooldown / show-cps 控制）。
     */
    private void sendDamage(Player player, double damage, EntityDamageEvent.DamageCause cause) {
        Messages m = plugin.messages();
        int precision = plugin.getConfigManager().getInt("notifications.precision", 1);
        String text = m.fmtDamage(damage, damage / 2.0, precision);
        String mode = plugin.getConfigManager().getString("notifications.mode", "actionbar");
        boolean showCps = plugin.getConfigManager().getBoolean("notifications.show-cps", true);
        boolean showCooldown = plugin.getConfigManager().getBoolean("notifications.show-cooldown", true);
        // 蓄力% 仅近战有意义（弓箭蓄力是另一套机制，不显示近战冷却避免误导）
        boolean isMelee = cause == EntityDamageEvent.DamageCause.ENTITY_ATTACK
                || cause == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK;
        String cooldownText = "";
        if (showCooldown && isMelee) {
            // getAttackCooldown() = 蓄力进度（0=刚攻击，1=满充能），即本次近战伤害的倍数。
            // 在 MONITOR 读取时仍是「本次攻击」的蓄力值（服务端在事件后才重置冷却），故与伤害一致。
            float cooldown = player.getAttackCooldown();
            int percent = Math.round(cooldown * 100.0f);
            cooldownText = "  " + m.raw("messages.cooldown").replace("{percent}", String.valueOf(percent));
        }
        String cpsText = showCps
                ? "  " + m.raw("messages.cps").replace("{cps}", String.valueOf(getCps(player)))
                : "";

        if (mode.equalsIgnoreCase("chat")) {
            player.sendMessage(text + cooldownText + cpsText);
            return;
        }

        // ActionBar（默认，基岩版兼容）：伤害 + 蓄力%（近战）+ CPS
        // sendActionBar(String) 已弃用（换 Adventure Component）；text 为 § 颜色码字符串
        player.sendActionBar(LegacyComponentSerializer.legacySection().deserialize(text + cooldownText + cpsText));
    }

    /**
     * 物品显示名：优先自定义显示名，否则用本地化名（跟随服务端语言/资源包，中文服显示中文）。
     * 26.2 的 ItemStack.displayName()（Adventure Component）已自动覆盖上述两种语义，
     * 此处序列化为 legacy 字符串与消息体系保持一致。
     */
    private String displayName(ItemStack item) {
        if (item == null) {
            return "";
        }
        try {
            return LegacyComponentSerializer.legacySection().serialize(item.displayName());
        } catch (Throwable ignored) {
            return item.getType().name();
        }
    }

    // ============ 工具方法 ============

    /**
     * 根据手中物品推断装备槽位：
     * HELMET←头盔类 / CHESTPLATE←胸甲类 / LEGGINGS←护腿类 / BOOTS←靴子类 / 其余→HAND。
     */
    private Dummy.EquipmentSlot slotFor(ItemStack item) {
        Material m = item.getType();
        String name = m.name();
        // 头盔类：_HELMET 后缀、海龟壳，以及原版可戴头部的南瓜头/骷髅头/头颅类
        if (name.endsWith("_HELMET") || m == Material.TURTLE_HELMET
                || m == Material.CARVED_PUMPKIN || name.endsWith("_HEAD") || name.endsWith("_SKULL")) {
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
