package top.miragedge.dummy.dummy;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;
import top.miragedge.dummy.MiragEdgeDummy;
import top.miragedge.dummy.damage.DamageCalculator;
import top.miragedge.dummy.npc.PlayerNpcFactory;
import top.miragedge.dummy.storage.DummyRecord;
import top.miragedge.dummy.storage.DummyStorage;
import top.miragedge.dummy.util.Messages;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 训练假人管理器：负责放置 / 识别 / 恢复 / 清理 / 物品构造 / 受击表现 / 物理模拟。
 *
 * <p>实现要点（见 docs/DEVELOPMENT.md §4）：</p>
 * <ul>
 *   <li>PDC 标记键：dummy / owner / id（{@link #dummyKey()} 等），另有 skin（皮肤头）、text（浮动伤害数字）</li>
 *   <li>放置：物品 PDC 标记 + 右键地面 → spawnDummy(玩家)</li>
 *   <li>恢复：data/ 记录 → 延时 + 异区块异步加载后重新生成</li>
 *   <li>孤儿清理：扫描世界，有 PDC 标记但不在 trackers 且无存储记录 → 移除</li>
 *   <li>受击表现：确定性缓动击退回弹（{@link #tickDummies()} 每 tick 驱动）+ 命中粒子 +
 *       TextDisplay 浮动伤害数字（transformation 插值丝滑动画）+ 受击音效</li>
 * </ul>
 */
public class DummyManager {

    /** 回弹最大安全时长（tick）：弹簧-阻尼收敛不了时强制归位。 */
    private static final int RECOIL_TICKS = 60;
    /** 弹簧刚度：位移越大，朝锚点的回拉加速度越大（离得越远拉力越大）。 */
    private static final double RECOIL_SPRING = 0.15;
    /** 阻尼系数：每 tick 速度衰减倍率（0~1，越小停得越快）。 */
    private static final double RECOIL_DAMPING = 0.82;
    /** 保底初始推出位移（格）。 */
    private static final double RECOIL_PUSH = 0.6;
    /** 真实击退速度 → 弹簧位移换算比例（原版击退速度约 0.4 格/tick → 位移约 1.6 格）。 */
    private static final double REAL_KNOCKBACK_SCALE = 4.0;

    private final MiragEdgeDummy plugin;
    private final DummyStorage storage;
    private final Map<UUID, Dummy> dummies = new ConcurrentHashMap<>();
    /** 回弹剩余安全 tick：uuid -> 剩余帧数（>0 表示处于回弹中） */
    private final Map<UUID, Integer> recoilTicks = new ConcurrentHashMap<>();
    /** 当前相对锚点的位移向量：uuid -> Vector（弹簧状态） */
    private final Map<UUID, Vector> recoilDisp = new ConcurrentHashMap<>();
    /** 当前速度向量：uuid -> Vector（弹簧状态） */
    private final Map<UUID, Vector> recoilVel = new ConcurrentHashMap<>();
    /** 在场浮动伤害数字实体 id 集合（用于关服清理） */
    private final Set<UUID> textDisplays = ConcurrentHashMap.newKeySet();
    /** 头顶名称刷新节流：uuid -> 上次刷新时间戳（避免高频命中每次都广播 metadata） */
    private final Map<UUID, Long> lastNameRefresh = new ConcurrentHashMap<>();

    public DummyManager(MiragEdgeDummy plugin, DummyStorage storage) {
        this.plugin = plugin;
        this.storage = storage;
    }

    // ============ 物品 ============

    /**
     * 构造训练假人放置物品：材质走 config 的 item.material（非法回退 ARMOR_STAND），
     * 名称/lore 从 messages.yml 实时读取，PDC 打 dummy=1 标记。
     *
     * <p>注：ItemMeta.setDisplayName/setLore(String) 在 26.2 已弃用（换 Adventure Component），
     * 本项目物品名/说明统一走 messages.yml 的 & 颜色码字符串体系，此处有意沿用并抑制告警。</p>
     */
    @SuppressWarnings("deprecation")
    public ItemStack createDummyItem(int amount) {
        return createDummyItem(amount, 0);
    }

    /**
     * 构造训练假人放置物品（可选生命值）：hp &gt; 0 时写入 PDC hp 键并附加生命值 lore 行。
     */
    @SuppressWarnings("deprecation")
    public ItemStack createDummyItem(int amount, int hp) {
        String matName = plugin.getConfigManager().getString("item.material", "ARMOR_STAND");
        Material mat = Material.getMaterial(matName);
        if (mat == null) {
            mat = Material.ARMOR_STAND;
        }
        ItemStack stack = new ItemStack(mat, amount);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(plugin.messages().getString("item.name", "&6训练假人"));
            java.util.List<String> lore = new java.util.ArrayList<>(plugin.messages().getStringList("item.lore"));
            if (hp > 0) {
                lore.add(plugin.messages().getString("item.lore-hp", "&7生命值: &a{hp}").replace("{hp}", String.valueOf(hp)));
                meta.getPersistentDataContainer().set(hpKey(), PersistentDataType.INTEGER, hp);
            }
            meta.setLore(lore);
            meta.getPersistentDataContainer().set(dummyKey(), PersistentDataType.BYTE, (byte) 1);
            stack.setItemMeta(meta);
        }
        return stack;
    }

    /**
     * 读取物品上携带的生命值（无则 0）。
     */
    public int getDummyItemHp(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) {
            return 0;
        }
        Integer hp = stack.getItemMeta().getPersistentDataContainer().get(hpKey(), PersistentDataType.INTEGER);
        return hp != null ? hp : 0;
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
     * 玩家放置训练假人：扣 1 个物品、取视线落点上方 1 格、生成假人实体、
     * 应用标记/静态配置/皮肤、按物品携带的生命值设置血量、建 tracker、写存储。
     */
    public Dummy spawnDummy(Player player) {
        ItemStack hand = player.getInventory().getItemInMainHand();
        // §4.3 步骤1：校验手中确为训练假人物品（防错：公方法被其他调用方直接调用时不消耗任意物品）
        if (hand == null || hand.getType() == Material.AIR || !isDummyItem(hand)) {
            return null;
        }

        // 先取视线落点（方案书 §4.3：getTargetBlockExact(5)），失败时不消耗物品。
        Block target = player.getTargetBlockExact(5);
        if (target == null) {
            player.sendMessage(plugin.messages().fmt("messages.placement-failed"));
            return null;
        }

        // 物品携带的生命值（/dummy give 的 [生命] 参数）；未指定用配置默认
        int hp = getDummyItemHp(hand);
        if (hp <= 0) {
            hp = plugin.getConfigManager().getInt("dummy-default-hp", 100);
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
        LivingEntity spawned = null;
        try {
            spawned = spawnDummyEntity(player.getWorld(), loc, id, owner);
            if (spawned == null) {
                throw new IllegalStateException("无法生成假人实体（玩家NPC与盔甲架均失败）");
            }
            spawned.getPersistentDataContainer().set(dummyKey(), PersistentDataType.BYTE, (byte) 1);
            spawned.getPersistentDataContainer().set(ownerKey(), PersistentDataType.STRING, owner.toString());
            spawned.getPersistentDataContainer().set(idKey(), PersistentDataType.STRING, id.toString());

            dummy = new Dummy(id, owner, spawned, hp);
            dummy.configureStatic();
            applySkinIfConfigured(spawned);
            updateDisplayName(dummy);
            // 落盘移入 try：fromLocation 异常时实体不留在 tracker/世界里
            dummies.put(id, dummy);
            storage.saveRecord(DummyRecord.fromLocation(id, owner, loc, displayName, hp));
        } catch (RuntimeException e) {
            // 生成/落盘失败：移除已生成的残留实体（防孤儿）+ 退还已扣物品（保留 hp）
            if (spawned != null && spawned.isValid()) {
                spawned.remove();
            }
            // 生成失败：退还已扣的 1 个物品，避免静默丢失；多余放不下则掉落
            plugin.getLogger().warning("放置训练假人失败: " + e.getMessage());
            ItemStack refund = createDummyItem(1, hp);
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
     * 按配置生成假人底层实体：{@code dummy-entity-type} 为 {@code player}（默认）时优先用
     * 假人玩家 NPC（真 Player 实体，有皮肤），失败/异常自动回退盔甲架；为 {@code armor-stand}
     * 时直接用盔甲架。返回 null 表示两种方案都失败。
     */
    private LivingEntity spawnDummyEntity(World world, Location loc, UUID id, UUID owner) {
        String type = plugin.getConfigManager().getString("dummy-entity-type", "player");
        if (type != null && type.equalsIgnoreCase("player")) {
            // 官方 NPC 玩家假人（Paper 26.2）：真 Player 实体（皮肤/身体/装备显示），不在玩家列表
            String skinName = plugin.getConfigManager().getString("npc-skin", "");
            if (skinName != null && skinName.isBlank()) {
                skinName = null;
            }
            Player npc = PlayerNpcFactory.spawn(world, loc, skinName);
            if (npc != null) {
                plugin.getLogger().fine("训练假人以官方 NPC 玩家实体生成");
                return npc;
            }
            plugin.getLogger().warning("官方 NPC 玩家假人生成失败，回退到盔甲架（详见上文 WARNING 原因）");
        }
        // 盔甲架回退
        return world.spawnEntity(loc, EntityType.ARMOR_STAND) instanceof ArmorStand stand ? stand : null;
    }

    /**
     * 玩家通过物品收回训练假人：归还装备（皮肤头除外）、移除实体与记录、返回物品（剩余掉落地面）。
     */
    public void pickupDummy(Player player, Dummy dummy) {
        recoilTicks.remove(dummy.getId());
        recoilDisp.remove(dummy.getId());
        recoilVel.remove(dummy.getId());

        Location dropLoc = dummy.getLocation();

        for (Dummy.EquipmentSlot slot : Dummy.EquipmentSlot.values()) {
            ItemStack item = dummy.getEquipment(slot);
            if (item == null) {
                continue;
            }
            // 皮肤头是假人本体（NPC 形象），不是玩家装备，收回时不归还
            if (isSkinHead(item)) {
                continue;
            }
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(item);
            dropRemainder(dropLoc, leftover);
        }

        dummy.remove();
        dummies.remove(dummy.getId());
        lastNameRefresh.remove(dummy.getId());
        storage.remove(dummy.getId());

        Map<Integer, ItemStack> rest = player.getInventory().addItem(createDummyItem(1, dummy.getMaxHp()));
        dropRemainder(dropLoc, rest);

        player.sendMessage(plugin.messages().fmt("messages.removed"));
    }

    /**
     * 刷新假人头顶显示名：配置名 + 当前护甲点数（随穿/取装备实时更新）。
     * 护甲值取自 DamageCalculator 的同一张护甲表，与减伤展示一致。
     */
    public void updateDisplayName(Dummy dummy) {
        if (dummy == null || dummy.getEntity() == null || dummy.getEntity().isDead()) {
            return;
        }
        boolean visible = plugin.getConfigManager().getBoolean("npc-name-visible", true);
        String rawName = plugin.getConfigManager().getString("npc-name", "&e训练假人");
        String base = (rawName == null || rawName.isEmpty()) ? "&e训练假人" : rawName;
        // 真实护甲值：玩家 NPC 的装备属性天然生效，直接读 Attribute.ARMOR 真实值；
        // 盔甲架兜底（属性不生效）才回退静态护甲表，保证显示值与实际减伤一致。
        double armorAttrValue = 0;
        double toughnessAttrValue = 0;
        if (dummy.getLiving() != null) {
            AttributeInstance armorAttr = dummy.getLiving().getAttribute(Attribute.ARMOR);
            if (armorAttr != null) {
                armorAttrValue = armorAttr.getValue();
            }
            AttributeInstance toughnessAttr = dummy.getLiving().getAttribute(Attribute.ARMOR_TOUGHNESS);
            if (toughnessAttr != null) {
                toughnessAttrValue = toughnessAttr.getValue();
            }
        }
        int armor = armorAttrValue > 0
                ? (int) Math.round(armorAttrValue)
                : DamageCalculator.getTotalArmor(dummy.getEntity());
        double toughness = toughnessAttrValue > 0
                ? toughnessAttrValue
                : DamageCalculator.getTotalToughness(dummy.getEntity());
        StringBuilder sb = new StringBuilder(Messages.colorize(base));
        sb.append(" §7护甲: §a").append(armor);
        if (toughness > 0) {
            sb.append(" §7韧: §b").append(toughness > (int) toughness
                    ? String.format(java.util.Locale.ROOT, "%.1f", toughness)
                    : String.valueOf((int) toughness));
        }
        // 生命值显示：当前/最大（随受击与击杀重生实时变化）
        double health = dummy.getHealth();
        int curHp = (int) Math.ceil(health);
        sb.append(" §7生命: §c").append(curHp).append("§7/§a").append(dummy.getMaxHp());
        // 节流：高频命中（多玩家打同一假人）时 100ms 内最多广播一次名称刷新，降低 metadata 包量
        long now = System.currentTimeMillis();
        Long last = lastNameRefresh.get(dummy.getId());
        if (last != null && now - last < 120L) {
            return;
        }
        lastNameRefresh.put(dummy.getId(), now);
        dummy.setCustomName(sb.toString(), visible);
    }

    /**
     * 击杀假人：原地立即重生（回满生命 + 回到出生锚点 + 清回弹状态 + 刷新头顶显示 + 重生反馈）。
     * 重生采用同一实体原地满血复活（不销毁实体），因此装备/皮肤/追踪全部保留。
     */
    public void killDummy(Dummy dummy) {
        if (dummy == null) {
            return;
        }
        recoilTicks.remove(dummy.getId());
        recoilDisp.remove(dummy.getId());
        recoilVel.remove(dummy.getId());
        dummy.respawn();
        updateDisplayName(dummy);
        // 重生反馈：音效 + 粒子
        LivingEntity entity = dummy.getLiving();
        if (entity != null && entity.isValid()) {
            World world = entity.getWorld();
            Location loc = entity.getLocation();
            world.playSound(loc, Sound.ENTITY_PLAYER_DEATH, 0.7f, 1.0f);
            world.spawnParticle(Particle.CLOUD, loc.clone().add(0, 1, 0), 12, 0.4, 0.6, 0.4, 0.02);
        }
    }

    // ============ 受击表现（真实物理击退 + 粒子 + 浮动伤害数字） ============

    /**
     * 假人受击：播放音效、命中粒子（含暴击）、浮动伤害数字，并触发击退回弹。
     *
     * <p>击退回弹为「确定性缓动 teleport」（先快速后撤、再平滑回位），不依赖服务端 velocity
     * （不同实体类型速度行为不可靠且曾多次反馈看不见），由 {@link #tickDummies()} 按缓动曲线驱动，
     * 保证每击都有明显且顺滑的击退。</p>
     *
     * @param dummy   被击中的假人
     * @param damage  显示用的伤害值（真实受击伤害，已含护甲减伤）
     * @param crit    是否为暴击（影响粒子与数字颜色）
     * @param direction 击退参考方向（假人被推离攻击者的水平方向）
     */
    public void onHit(Dummy dummy, double damage, boolean crit, Vector direction) {
        LivingEntity entity = dummy.getLiving();
        if (entity == null || !entity.isValid() || entity.isDead()) {
            return;
        }
        World world = entity.getWorld();
        Location base = entity.getLocation();

        // 1. 受击音效
        world.playSound(base, Sound.ENTITY_ARMOR_STAND_HIT, 0.6f, 0.9f);

        // 2. 命中粒子：克制血雾 + 暴击（去掉夸张的爱心粒子）
        Location center = base.clone().add(0, 0.6, 0);
        world.spawnParticle(Particle.DUST, center, 5, 0.3, 0.3, 0.3,
                new Particle.DustOptions(Color.fromRGB(180, 30, 30), 0.8f));
        if (crit) {
            world.spawnParticle(Particle.CRIT, center, 10, 0.35, 0.35, 0.35, 0.12);
        }

        // 3. 浮动伤害数字（TextDisplay，向攻击者左右两侧横向抛出，弧形散开）
        spawnDamageText(entity, damage, crit, direction);

        // 4. 触发击退回弹：弹簧-阻尼模型（离锚点越远，回拉拉力越大）。
        //    【真实击退值反馈】：初始位移先按方向给一个保底冲量；
        //    下一 tick 读取服务端施加的真实击退速度（含攻击力度/击退附魔/疾跑的真实值），
        //    按比例换算为弹簧位移覆盖——击退效果完全来自真实受击数据。
        Vector away = direction.clone();
        away.setY(0);
        if (away.lengthSquared() < 1e-6) {
            away = new Vector(0, 0, 1);
        }
        away.normalize();
        // 连击连续：新位移 = 当前位置相对锚点的位移 + 本次冲量（避免连续命中跳变，自然越打越远）
        Location anchorLoc = dummy.getAnchor();
        Vector currentDisp = (anchorLoc != null && anchorLoc.getWorld() != null)
                ? entity.getLocation().toVector().subtract(anchorLoc.toVector())
                : new Vector(0, 0, 0);
        Vector push = new Vector(away.getX() * RECOIL_PUSH, 0.25, away.getZ() * RECOIL_PUSH);
        recoilDisp.put(dummy.getId(), currentDisp.clone().add(push));
        recoilVel.put(dummy.getId(), new Vector(0, 0, 0));
        recoilTicks.put(dummy.getId(), RECOIL_TICKS);
        // 真实击退校准不在独立任务里做（与 tickDummies 清零速度的时序会竞争）；
        // 改在 tickDummies 回弹首帧内读取真实速度覆盖位移（见 tickDummies 注释）。
    }

    /**
     * 每 tick 循环（由 MiragEdgeDummy 主类定时调用）：
     * <ul>
     *   <li>击退回弹中：缓动曲线（前 30% ease-out 快速后撤，后 70% ease-in-out 平滑回位）
     *       按 phase 计算偏移并 teleport，期满精确归位；</li>
     *   <li>静止态：清零速度，漂移（方块推动等）则瞬时归位；同时持续防火。</li>
     * </ul>
     */
    public void tickDummies() {
        for (Dummy dummy : dummies.values()) {
            LivingEntity entity = dummy.getLiving();
            if (entity == null || !entity.isValid() || entity.isDead()) {
                // 实体失效：顺带清掉回弹状态与名称节流，避免陈旧条目累积
                recoilTicks.remove(dummy.getId());
                recoilDisp.remove(dummy.getId());
                recoilVel.remove(dummy.getId());
                lastNameRefresh.remove(dummy.getId());
                continue;
            }
            // 防火
            if (entity.getFireTicks() > 0) {
                entity.setFireTicks(0);
            }

            Location anchor = dummy.getAnchor();
            if (anchor == null || anchor.getWorld() == null || anchor.getWorld() != entity.getWorld()) {
                // 锚点异常（世界不符）：仅保持静止
                entity.setVelocity(new Vector(0, 0, 0));
                continue;
            }

            Integer left = recoilTicks.get(dummy.getId());
            if (left != null && left > 0) {
                boolean firstFrame = (left == RECOIL_TICKS);
                recoilTicks.put(dummy.getId(), left - 1);
                Vector disp = recoilDisp.get(dummy.getId());
                Vector vel = recoilVel.get(dummy.getId());
                if (disp != null && vel != null) {
                    // 【首帧】真实击退值校准：服务端在原版伤害结算后为假人施加了真实击退速度
                    // （含攻击力度/击退附魔/疾跑的真实值），首帧读到后按比例换算为弹簧位移覆盖
                    // 保底冲量——击退效果完全来自真实受击数据。
                    if (firstFrame) {
                        Vector realVel = entity.getVelocity();
                        if (realVel.lengthSquared() > 0.0004) {
                            Vector realDisp = realVel.clone().multiply(REAL_KNOCKBACK_SCALE);
                            if (realDisp.getY() < 0.15) {
                                realDisp.setY(Math.max(realDisp.getY(), 0.15));
                            }
                            if (realDisp.lengthSquared() > 2.5 * 2.5) {
                                realDisp.normalize().multiply(2.5);
                            }
                            disp = realDisp;
                            recoilDisp.put(dummy.getId(), disp);
                            vel = new Vector(0, 0, 0);
                            recoilVel.put(dummy.getId(), vel);
                        }
                    }
                    // 弹簧：加速度 a = -k * disp（位移越大，朝锚点的回拉拉力越大）；阻尼衰减
                    Vector accel = disp.clone().multiply(-RECOIL_SPRING);
                    vel.add(accel).multiply(RECOIL_DAMPING);
                    disp.add(vel);
                    // 收敛判断：位移与速度都极小 → 精确归位
                    boolean settled = disp.lengthSquared() < 1e-5 && vel.lengthSquared() < 1e-5;
                    if (settled || left <= 1) {
                        entity.teleport(anchor);
                        entity.setVelocity(new Vector(0, 0, 0));
                        recoilTicks.remove(dummy.getId());
                        recoilDisp.remove(dummy.getId());
                        recoilVel.remove(dummy.getId());
                    } else {
                        entity.teleport(anchor.toVector().add(disp)
                                .toLocation(entity.getWorld(), entity.getYaw(), entity.getPitch()));
                    }
                }
                // 回弹期间每 tick 清零速度：避免服务端 velocity 与弹簧 teleport 竞争抖动
                entity.setVelocity(new Vector(0, 0, 0));
            } else {
                // 静止保持：清零速度；有漂移（方块推动等）则瞬时归位
                entity.setVelocity(new Vector(0, 0, 0));
                if (entity.getLocation().distanceSquared(anchor) > 0.0001) {
                    entity.teleport(anchor);
                }
            }
        }
    }

    /**
     * 生成浮动伤害数字：TextDisplay 向攻击者左右两侧横向抛出（弧形散开），约 1.2 秒后自毁。
     *
     * <p><b>丝滑动画的关键</b>：用 {@code Display.setTransformation}（translation 分量）+ 客户端插值
     * （{@code setInterpolationDuration}）。TextDisplay 的实体位置固定，文本偏移放在 transformation
     * 的 translation 里；每到达一个关键帧更新一次 transformation，客户端在两次更新之间按帧率平滑插值，
     * 因此动画由客户端渲染驱动，丝滑不卡顿（不再用逐 tick teleport，那无法触发 Display 插值）。</p>
     *
     * @param direction 攻击者→假人的水平方向（用于计算左右侧抛射向量）
     */
    private void spawnDamageText(LivingEntity entity, double damage, boolean crit, Vector direction) {
        World world = entity.getWorld();
        int precision = plugin.getConfigManager().getInt("notifications.precision", 1);
        String text = String.format(java.util.Locale.ROOT, "%." + Math.max(0, precision) + "f", damage);

        // 实体位置固定（胸口高度），文本用 transformation.translation 做偏移
        Location loc = entity.getLocation().clone().add(0, 0.05, 0);

        TextDisplay display = (TextDisplay) world.spawnEntity(loc, EntityType.TEXT_DISPLAY);
        display.setBillboard(Display.Billboard.CENTER);
        display.setShadowed(true);
        display.setSeeThrough(false);
        display.setAlignment(TextDisplay.TextAlignment.CENTER);
        display.text(Component.text(text)
                .color(crit ? NamedTextColor.YELLOW : NamedTextColor.RED)
                .decoration(TextDecoration.BOLD, crit));
        display.setTextOpacity((byte) 255);
        display.setPersistent(false);
        display.getPersistentDataContainer().set(textKey(), PersistentDataType.BYTE, (byte) 1);
        textDisplays.add(display.getUniqueId());

        // 侧抛方向：垂直于击退方向（≈玩家视线左右两侧），随机选一侧
        Vector perp = direction.clone();
        perp.setY(0);
        if (perp.lengthSquared() < 1e-6) {
            perp = new Vector(0, 0, 1);
        }
        perp.normalize();
        double rad = Math.PI / 2;
        double px = perp.getX() * Math.cos(rad) - perp.getZ() * Math.sin(rad);
        double pz = perp.getX() * Math.sin(rad) + perp.getZ() * Math.cos(rad);
        Vector side = new Vector(px, 0, pz).normalize();
        if (Math.random() < 0.5) {
            side.multiply(-1); // 随机左或右
        }

        // ---- 动画参数：关键帧 + 客户端插值 ----
        // 注意：keyframes = life/interval - 1，使得【第 k 帧的进度 p=k/(keyframes)】，
        // 最后更新的帧就是 p=1.0 的终点（水平到位、回落胸口）；否则最后一帧永远不会被应用。
        final int life = 24;            // 总时长（tick）
        final int keyframeInterval = 4; // 每 4 tick 一个关键帧
        final int keyframes = life / keyframeInterval - 1; // = 5，帧号 0..5
        final double travel = 0.9;      // 水平飞行距离（格）
        final double arcHeight = 0.55;  // 弧顶抬升（格）
        final double baseY = 1.2;       // 胸口高度偏移

        // 预计算关键帧 translation（水平匀速前进 + 垂直 sin 弧）
        final org.joml.Vector3f[] frames = new org.joml.Vector3f[keyframes + 1];
        for (int k = 0; k <= keyframes; k++) {
            double p = (double) k / keyframes;
            frames[k] = new org.joml.Vector3f(
                    (float) (side.getX() * travel * p),
                    (float) (baseY + arcHeight * Math.sin(p * Math.PI)),
                    (float) (side.getZ() * travel * p));
        }

        // 初始帧 + 开启客户端插值（duration = 关键帧间隔，客户端在帧间平滑）
        display.setTransformation(new org.bukkit.util.Transformation(
                frames[0], new org.joml.AxisAngle4f(),
                new org.joml.Vector3f(1, 1, 1), new org.joml.AxisAngle4f()));
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(keyframeInterval);

        final int lifeFinal = life;
        final int kfInterval = keyframeInterval;
        final org.joml.Vector3f[] kf = frames;
        final UUID uid = display.getUniqueId();
        final int[] frame = {0};
        final int[] tick = {0};
        final BukkitTask[] taskRef = new BukkitTask[1];
        taskRef[0] = plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            TextDisplay d = display;
            if (d == null || !d.isValid()) {
                textDisplays.remove(uid);
                if (taskRef[0] != null) taskRef[0].cancel();
                return;
            }
            tick[0]++;
            if (tick[0] >= lifeFinal) {
                d.remove();
                textDisplays.remove(uid);
                if (taskRef[0] != null) taskRef[0].cancel();
                return;
            }
            // 到达关键帧：更新 transformation，客户端在 interval 内平滑插值
            int newFrame = tick[0] / kfInterval;
            if (newFrame != frame[0] && newFrame <= kf.length - 1) {
                frame[0] = newFrame;
                d.setTransformation(new org.bukkit.util.Transformation(
                        kf[newFrame], new org.joml.AxisAngle4f(),
                        new org.joml.Vector3f(1, 1, 1), new org.joml.AxisAngle4f()));
            }
            // 后半程淡出（前半程保持清晰，更自然）
            double p = (double) tick[0] / lifeFinal;
            double fade = 1.0 - Math.max(0, (p - 0.45) / 0.55);
            d.setTextOpacity((byte) Math.max(0, Math.min(255, Math.round(255 * fade))));
        }, 1L, 1L);
    }

    /**
     * 关服等场景取消所有击退回位状态并清理浮动伤害数字实体。
     * 方法名保持兼容（旧版为「取消动画任务」）。
     */
    public void cancelAllHitAnimations() {
        recoilTicks.clear();
        recoilDisp.clear();
        recoilVel.clear();
        removeAllTextDisplays();
    }

    /**
     * 移除所有仍存活的浮动伤害数字（TextDisplay，带 text PDC 标记）。
     */
    public void removeAllTextDisplays() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : new ArrayList<>(world.getEntities())) {
                if (entity instanceof TextDisplay
                        && entity.getPersistentDataContainer().has(textKey(), PersistentDataType.BYTE)) {
                    entity.remove();
                }
            }
        }
        textDisplays.clear();
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

    // ============ 皮肤（对接离线皮肤系统） ============

    /**
     * 若配置了 npc-skin 且底层实体是盔甲架，为其戴上皮肤头颅（玩家 NPC 的皮肤
     * 已在 {@link PlayerNpcFactory} 的 GameProfile 中处理，此处不再重复操作）。
     * 纹理未命中缓存时异步解析（可能联网），完成后回主线程应用；解析失败也照常应用（仅名字头颅）。
     */
    public void applySkinIfConfigured(LivingEntity entity) {
        // 玩家 NPC 的皮肤来自 GameProfile，无需额外处理
        if (entity instanceof Player) {
            return;
        }
        // 盔甲架：戴皮肤头颅
        if (!(entity instanceof ArmorStand stand)) {
            return;
        }
        String skinName = plugin.getConfigManager().getString("npc-skin", "");
        if (skinName == null || skinName.isBlank()) {
            return;
        }
        skinName = skinName.trim();
        // 用 Paper 原生 PlayerProfile（com.destroystokyo.paper.profile）：SkullMeta.setPlayerProfile 非弃用，
        // 且 complete() 会走 Mojang / 本地缓存 / 服务器离线皮肤系统（如 skinsrestorer 等挂接解析的插件）获取纹理。
        PlayerProfile profile = Bukkit.createProfile(skinName);
        if (profile.hasTextures()) {
            applySkin(stand, profile);
            return;
        }
        // 纹理未缓存：异步解析（可能联网），完成后回主线程应用。解析失败也照常应用（仅名字头颅）。
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                profile.complete(true, false);
            } catch (Throwable ignored) {
                // 解析失败不阻塞放置，交给 applySkin 应用未带纹理的 profile
            }
            // 注册回主线程前判 isEnabled：插件可能在解析期间被禁用（禁用后注册任务会抛异常）
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) {
                    applySkin(stand, profile);
                }
            });
        });
    }

    /**
     * 用解析出的配置给假人戴上皮肤头颅（带 skin PDC 标记，供归还/取下逻辑识别）。
     */
    private void applySkin(ArmorStand stand, PlayerProfile profile) {
        if (stand == null || !stand.isValid()) {
            return;
        }
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta == null || profile == null) {
            return;
        }
        try {
            meta.setPlayerProfile(profile);
        } catch (RuntimeException e) {
            plugin.getLogger().warning("应用假人皮肤失败: " + e.getMessage());
            return;
        }
        meta.getPersistentDataContainer().set(skinKey(), PersistentDataType.BYTE, (byte) 1);
        head.setItemMeta(meta);
        EntityEquipment eq = stand.getEquipment();
        if (eq != null) {
            eq.setHelmet(head);
        }
    }

    /**
     * 判断物品是否为假人皮肤头颅（带 skin PDC 标记）。
     */
    public boolean isSkinHead(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(skinKey(), PersistentDataType.BYTE);
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
        recoilTicks.remove(id);
        recoilDisp.remove(id);
        recoilVel.remove(id);
        Dummy d = dummies.remove(id);
        if (d != null) {
            // 管理员删除时把假人身上的装备掉落在原地，避免物品静默丢失（皮肤头除外）
            Location loc = d.getLocation();
            if (loc != null && loc.getWorld() != null) {
                for (Dummy.EquipmentSlot slot : Dummy.EquipmentSlot.values()) {
                    ItemStack item = d.getEquipment(slot);
                    if (item != null && !isSkinHead(item)) {
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
     *
     * <p>注：Nameable.getCustomName() 在 26.2 已弃用（换 Adventure Component），本项目显示名统一为
     * & 颜色码字符串体系，此处有意沿用并抑制告警。</p>
     */
    @SuppressWarnings("deprecation")
    public void saveAll() {
        for (Dummy d : dummies.values()) {
            try {
                if (d == null || !d.isValid()) {
                    continue;
                }
                // 持久化保存配置基础名（不含动态「护甲/生命」后缀——恢复时由 updateDisplayName 重建，
                // 避免把瞬时血量写进死数据）
                String displayName = plugin.getConfigManager().getString("npc-name", "&e训练假人");
                // onDisable 阶段必须同步写（禁用后 runTaskAsynchronously 会抛 IllegalPluginAccessException）
                storage.saveRecordSync(DummyRecord.fromLocation(d.getId(), d.getOwner(), d.getLocation(), displayName, d.getMaxHp()));
            } catch (RuntimeException e) {
                plugin.getLogger().warning("无法保存训练假人 " + (d != null ? d.getId() : "?") + ": " + e.getMessage());
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
                        // 回调可能晚于插件禁用执行：先判 enabled，避免禁用期注册任务/往关服世界补实体
                        if (!plugin.isEnabled()) {
                            return;
                        }
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
     * 区块加载时恢复该区块内的训练假人（玩家 NPC 不持久化，区块重载后需重建；
     * 幂等：已跟踪的会跳过）。
     */
    public void restoreChunk(World world, int chunkX, int chunkZ) {
        if (world == null) {
            return;
        }
        for (DummyRecord rec : storage.all()) {
            if (!world.getName().equals(rec.world())) {
                continue;
            }
            int cx = (int) Math.floor(rec.x() / 16.0);
            int cz = (int) Math.floor(rec.z() / 16.0);
            if (cx != chunkX || cz != chunkZ) {
                continue;
            }
            Dummy tracked = dummies.get(rec.uuid());
            if (tracked != null && tracked.isValid()) {
                continue; // 只有「有效」的在场实体才算已恢复（防止实体消失/区块卸载后无法重建）
            }
            Location loc = rec.toLocation();
            if (loc == null) {
                continue;
            }
            try {
                spawnRestored(rec, loc);
            } catch (RuntimeException e) {
                plugin.getLogger().warning("恢复训练假人 " + rec.uuid() + " 失败: " + e.getMessage());
            }
        }
    }

    /**
     * 根据存储记录重新生成一个训练假人实体并登记。已存在则跳过（防重复）。
     */
    private Dummy spawnRestored(DummyRecord rec, Location loc) {
        Dummy existing = dummies.get(rec.uuid());
        if (existing != null && existing.isValid()) {
            return existing;
        }
        // 记录存在但实体失效（区块卸载/被移除）：允许重建（findExistingById 复用残留实体，否则新生成）
        if (existing != null) {
            plugin.getLogger().fine("训练假人 " + rec.uuid() + " 实体已失效，重新恢复");
            recoilTicks.remove(rec.uuid());
            recoilDisp.remove(rec.uuid());
            recoilVel.remove(rec.uuid());
        }
        // 复用已存在的同 id PDC 实体（setPersistent(true) 的实体可能随区块持久化，
        // 重启后仍留在世界，直接再 spawn 会导致重复实体）。
        // 记录生命值：老记录无 hp 字段（0）时用配置默认
        int hp = rec.hp() > 0 ? rec.hp() : plugin.getConfigManager().getInt("dummy-default-hp", 100);

        LivingEntity existingEntity = findExistingById(rec.uuid());
        if (existingEntity != null) {
            Dummy dummy = new Dummy(rec.uuid(), rec.owner(), existingEntity, hp);
            dummy.configureStatic();
            applySkinIfConfigured(existingEntity);
            updateDisplayName(dummy);
            dummies.put(rec.uuid(), dummy);
            return dummy;
        }

        LivingEntity spawned = spawnDummyEntity(loc.getWorld(), loc, rec.uuid(), rec.owner());
        if (spawned == null) {
            plugin.getLogger().warning("恢复训练假人 " + rec.uuid() + " 失败：无法生成实体");
            return null;
        }
        spawned.getPersistentDataContainer().set(dummyKey(), PersistentDataType.BYTE, (byte) 1);
        spawned.getPersistentDataContainer().set(ownerKey(), PersistentDataType.STRING, rec.owner().toString());
        spawned.getPersistentDataContainer().set(idKey(), PersistentDataType.STRING, rec.uuid().toString());

        Dummy dummy = new Dummy(rec.uuid(), rec.owner(), spawned, hp);
        dummy.configureStatic();
        applySkinIfConfigured(spawned);
        updateDisplayName(dummy);

        dummies.put(rec.uuid(), dummy);
        return dummy;
    }

    /**
     * 在世界中查找 id PDC 与给定 uuid 相同的已存在盔甲架（重启持久化残留时复用）。
     */
    private LivingEntity findExistingById(UUID uuid) {
        String idStr = uuid.toString();
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof LivingEntity living
                        && idStr.equals(entity.getPersistentDataContainer().get(idKey(), PersistentDataType.STRING))) {
                    return living;
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

    public NamespacedKey skinKey() {
        return new NamespacedKey(plugin, "skin");
    }

    public NamespacedKey hpKey() {
        return new NamespacedKey(plugin, "hp");
    }

    public NamespacedKey textKey() {
        return new NamespacedKey(plugin, "damage-text");
    }
}
