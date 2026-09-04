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
import top.miragedge.dummy.npc.PlayerNpcFactory;
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
    // 挥臂瞬间的蓄力值（0~1）：26.2 攻击管线下 MONITOR 时刻读冷却可能已被重置，
    // 挥臂事件先于攻击处理，此值最接近「本次攻击的实际蓄力」。
    private final Map<UUID, Float> swingCooldown = new ConcurrentHashMap<>();
    // 伤害事件 LOWEST 时刻的蓄力值（冷却在本次攻击结束才重置，LOWEST 必为真实蓄力值）
    private final Map<UUID, Float> preAttackCooldown = new ConcurrentHashMap<>();
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
            // PVP 大厅场景：放置也是管理员操作（普通玩家只能攻击练手）
            if (!event.getPlayer().hasPermission("miragedgedummy.admin")) {
                event.getPlayer().sendMessage(plugin.messages().fmt("messages.no-edit-permission"));
                return;
            }
            dummyManager.spawnDummy(event.getPlayer());
        }
    }

    // ============ 伤害（真实受击伤害的权威来源） ============

    /**
     * 任何伤害的处理总闸（MONITOR）：玩家/玩家投射物攻击让假人<b>真实掉血</b>，
     * 掉血达到当前生命值上限时判定「击杀」→ 原地重生；其它伤害源（火/摔落/爆炸等）归零。
     *
     * <p>设计要点（对应「攻击伤害应使用真实受击伤害 + 兼容高级附魔 + 可被击杀」的需求）：</p>
     * <ol>
     *   <li>假人不是无敌实体，服务端为每次攻击产生真实
     *       {@code EntityDamageByEntityEvent}——包括 Aiyatsbus / EcoEnchants 等
     *       高级附魔在内的所有插件都会照常结算伤害并触发各自命中效果；</li>
     *   <li>在 MONITOR（最后一档优先级）读取伤害：此时所有插件对伤害的修改均已应用，
     *       读到的就是「真实受击伤害」（含武器附魔、攻击冷却、暴击、护甲减伤等全部成分）；</li>
     *   <li>玩家攻击：{@code event.setDamage(显示伤害)} 让假人真实掉血（掉血量与显示一致）；
     *       若本次伤害 ≥ 当前生命 → {@code setDamage(0)} + 击杀重生（不 cancel，保留冷却重置）；</li>
     *   <li>横扫（SWEEP_ATTACK）跳过：它跟随主攻击事件之后触发、且冷却已被服务端重置，
     *       读出的蓄力恒为 4%~11%（覆盖主攻击的正确显示——「满蓄力显示 4%」的根因）；</li>
     *   <li>解除无敌帧（noDamageTicks），支持高 CPS 连点每次都产生事件。</li>
     * </ol>
     */
    /**
     * LOWEST：在攻击冷却重置前捕获蓄力值（本击结束服务端才重置冷却，此值=本次攻击真实蓄力）。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCaptureCooldown(EntityDamageEvent event) {
        try {
            if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
                return;
            }
            if (!dummyManager.isDummyEntity(event.getEntity())) {
                return;
            }
            if (event instanceof EntityDamageByEntityEvent byEvent && byEvent.getDamager() instanceof Player attacker) {
                preAttackCooldown.put(attacker.getUniqueId(), attacker.getAttackCooldown());
            }
        } catch (Exception ignored) {
        }
    }

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

        // 玩家 / 投射物攻击：捕获真实伤害 → 效果 → 显示（含击杀判定与反馈）
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            try {
                handlePlayerAttack(byEntity, dummy);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("处理假人受击表现异常: " + e.getMessage());
            }
        } else {
            // 非玩家攻击（火/摔落/爆炸等）：归零（假人只被玩家击杀）
            event.setDamage(0);
        }
        entity.setFireTicks(0);

        // 解除无敌帧（noDamageTicks）：掉血后会被打上 0.5s 无敌，不清除会让连点只触发第一次。
        // 1) 同步清一次（若服务端本次未设置无敌帧则直接生效）；
        // 2) 下一 tick 再清一次（覆盖服务端在事件结束后设置无敌帧的场景）。
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
    private double handlePlayerAttack(EntityDamageByEntityEvent event, Dummy dummy) {
        // 横扫事件跳过：它跟随主攻击之后触发，此时攻击冷却已被服务端重置，
        // 读出的蓄力%恒为 4%~11% 并覆盖主攻击的正确显示（「满蓄力显示 4%」的根因），
        // 且其伤害也会与主攻击重复扣血。主攻击（ENTITY_ATTACK）已完整处理本击。
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            event.setDamage(0);
            return -1;
        }
        // 击杀后重生等待期间：忽略攻击（延迟结束后才满血复活）
        if (dummyManager.isRespawning(dummy.getId())) {
            event.setDamage(0);
            return -1;
        }
        Entity damager = event.getDamager();
        Player player = null;
        if (damager instanceof Player p) {
            player = p;
        } else if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player p) {
            player = p;
        }
        if (player == null) {
            event.setDamage(0);
            return -1;
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
        if (finalDamage <= 0.0001 && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            double manual = DamageCalculator.getPlayerBaseDamage(player);
            displayed = DamageCalculator.calculateDamage(dummy.getEntity(), manual, event.getCause());
        }

        // ---- 2. 受击表现（音效 / 粒子 / 浮动伤害数字 / 物理击退） ----
        boolean crit = isCrit(player, event);
        dummyManager.onHit(dummy, displayed, crit, recoilDirection(damager, dummy, player));

        // ---- 3. 真实掉血 + 击杀判定（击杀进入延迟重生，无消息提醒） ----
        LivingEntity living = (LivingEntity) dummy.getEntity();
        double afterHp = living.getHealth();
        if (displayed >= living.getHealth()) {
            // 击杀：抵消致死伤害，进入延迟重生（不 cancel，保留攻击冷却重置）
            event.setDamage(0);
            dummyManager.killDummy(dummy);
            afterHp = 0;
        } else {
            // 正常掉血：与显示数值一致（真实值反馈）
            event.setDamage(displayed);
            afterHp = living.getHealth() - displayed;
        }

        // ---- 4. 伤害读数（ActionBar/chat 由配置决定） ----
        // 近战命中即一次点击：先记录本次点击（tick 去重，挥臂事件随后不会重复计），
        // 再读取 CPS，避免「伤害事件先于挥臂事件」导致读数恒少 1。
        if (event.getCause() == EntityDamageEvent.DamageCause.ENTITY_ATTACK) {
            recordCps(player);
        }
        // 蓄力% 仅在近战时有意义（弓箭蓄力是另一套机制，不要误显示近战冷却）
        sendDamage(player, displayed, event.getCause(), afterHp, (int) dummy.getEffectiveMaxHp());

        // ---- 5. 普通命中刷新头顶生命值（击杀已由 killDummy 延迟重生时刷新） ----
        if (displayed < living.getHealth()) {
            dummyManager.updateDisplayName(dummy);
        }
        return displayed;
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

        // 编辑权限（PVP 竞技场大厅场景）：穿/取装备、收回假人 一律仅管理员可操作，
        // 普通玩家只能攻击练手，不能改动假人配置。权限节点 miragedgedummy.admin（默认 op）。
        if (!player.hasPermission("miragedgedummy.admin")) {
            player.sendMessage(plugin.messages().fmt("messages.no-edit-permission"));
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

    // ============ 玩家加入（补发玩家 NPC 信息包） ============

    /**
     * 1.20.5+ 客户端只渲染「玩家信息表」中存在的玩家实体：新加入的玩家需要为
     * 所有已在场的玩家 NPC 补发 ClientboundPlayerInfoUpdatePacket（实体追踪器会在其
     * 进入视野时自动发 AddPlayerPacket，但信息包必须由插件自己广播）。
     */
    @EventHandler
    public void onPlayerJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        for (Dummy dummy : dummyManager.getAllDummies()) {
            if (dummy.isPlayerNpc() && dummy.isValid() && dummy.getLiving() instanceof Player npc) {
                PlayerNpcFactory.sendSpawnPacketsTo(npc, event.getPlayer());
                String name = dummy.getDisplayName();
                if (name != null) {
                    PlayerNpcFactory.sendTeamPacketTo(npc, event.getPlayer(), name);
                }
            }
        }
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
        // 记录挥臂瞬间蓄力（26.2 下 MONITOR 时刻冷却可能已重置，这里更接近真实攻击蓄力）
        try {
            swingCooldown.put(event.getPlayer().getUniqueId(), event.getPlayer().getAttackCooldown());
        } catch (Exception ignored) {
        }
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
     * 训练假人正常击杀路径已在 MONITOR 拦截（不产生死亡事件）。此处兜底任何绕过伤害事件的致死途径
     * （/kill、插件直接 kill 等）：取消死亡事件并原地重生（回满血 + 回到出生锚点）。
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(EntityDeathEvent event) {
        Entity entity = event.getEntity();
        if (!dummyManager.isDummyEntity(entity)) {
            return;
        }
        event.setCancelled(true);
        Dummy dummy = dummyManager.getDummyByEntity(entity);
        if (dummy != null) {
            plugin.getLogger().info("训练假人 " + entity.getUniqueId() + " 触发死亡事件，已取消并原地重生");
            dummyManager.killDummy(dummy);
        }
    }

    // ============ 私有工具 ============

    /**
     * 收回假人：校验主人（受 allow-non-owners-break 控制）→ pickupDummy → 提示。
     */
    private void removeDummy(Player player, Entity clicked, Dummy dummy) {
        // 权限校验已在 handleInteract 完成（管理员门禁）；此处只做防抖与收回。
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
        swingCooldown.entrySet().removeIf(e -> !Bukkit.getOfflinePlayer(e.getKey()).isOnline());
        preAttackCooldown.entrySet().removeIf(e -> !Bukkit.getOfflinePlayer(e.getKey()).isOnline());
        // lastInteractTick 值是最新 tick（单调递增），用「过期 tick」清理：当前 tick 差值过大即移除
        int nowTick = Bukkit.getCurrentTick();
        lastInteractTick.entrySet().removeIf(e -> nowTick - e.getValue() > 100);
    }

    /**
     * 按配置的 notifications.mode 发送伤害显示（chat / 其余走 ActionBar），
     * ActionBar 附带攻击蓄力百分比（未满时）与 CPS（由 show-cooldown / show-cps 控制）。
     */
    private void sendDamage(Player player, double damage, EntityDamageEvent.DamageCause cause,
                             double afterHp, int maxHp) {
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
            // 蓄力进度（0=刚攻击，1=满充能）。读取顺序：
            // 1) LOWEST 捕获（冷却在本击结束才重置，必为真实值）；
            // 2) 挥臂缓存；3) 实时兜底。
            Float pre = preAttackCooldown.get(player.getUniqueId());
            Float cached = swingCooldown.get(player.getUniqueId());
            float cooldown = pre != null ? pre : (cached != null ? cached : player.getAttackCooldown());
            int percent = Math.round(cooldown * 100.0f);
            cooldownText = "  " + m.raw("messages.cooldown").replace("{percent}", String.valueOf(percent));
        }
        String cpsText = showCps
                ? "  " + m.raw("messages.cps").replace("{cps}", String.valueOf(getCps(player)))
                : "";
        // 假人剩余生命（真实值反馈：掉血后实时显示当前/最大）
        int curHp = (int) Math.max(0, Math.ceil(afterHp));
        String hpText = "  " + m.raw("messages.hp")
                .replace("{current}", String.valueOf(curHp))
                .replace("{max}", String.valueOf(maxHp));

        if (mode.equalsIgnoreCase("chat")) {
            player.sendMessage(text + cooldownText + cpsText + hpText);
            return;
        }

        // ActionBar（默认，基岩版兼容）：伤害 + 蓄力%（近战）+ CPS + 假人生命
        // sendActionBar(String) 已弃用（换 Adventure Component）；text 为 § 颜色码字符串
        player.sendActionBar(LegacyComponentSerializer.legacySection()
                .deserialize(text + cooldownText + cpsText + hpText));
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
