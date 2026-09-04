package top.miragedge.dummy.dummy;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;

import java.util.UUID;

/**
 * 训练假人实体封装：包一层 {@link LivingEntity}（盔甲架 或 假人玩家 NPC），
 * 持有 id / 主人 / 出生锚点。
 *
 * <p>零依赖实现（不需要 Citizens）：</p>
 * <ul>
 *   <li>{@code dummy-entity-type: player}（默认，用户要求）—— 生成真实玩家 NPC
 *       （{@link PlayerNpcFactory}，真 Player 实体、有皮肤/身体/装备显示），失败自动回退盔甲架；</li>
 *   <li>{@code dummy-entity-type: armor-stand} —— 传统盔甲架 + 皮肤头颅。</li>
 * </ul>
 *
 * <p>实现要点（见 docs/DEVELOPMENT.md §4）：</p>
 * <ul>
 *   <li>PDC 标记：dummy=1（标识）、owner=UUID字符串、id=UUID字符串（三个 NamespacedKey）</li>
 *   <li>关键：{@code setInvulnerable(false)} —— 只有可受伤，服务端才会为攻击产生真实
 *       {@code EntityDamageByEntityEvent}，从而能捕获包括高级附魔（Aiyatsbus/EcoEnchants 等）在内
 *       的完整真实伤害；伤害由监听器在 MONITOR 阶段统一 {@code setDamage(0)} 抵消，假人不会掉血。
 *       高血量（1024）作为兜底：任何未被抵消的伤害也不至于让假人瞬间死亡丢装备。</li>
 *   <li>装备槽位：盔甲 + 主手 + 副手</li>
 * </ul>
 */
public class Dummy {

    private final UUID id;
    private final UUID owner;
    /** 出生锚点：物理击退（弹簧回位）与击杀重生的参考点，构造时固定。 */
    private final Location anchor;
    /** 假人最大生命值（放置时由物品 PDC / 配置决定；击杀重生后回满该值）。 */
    private final int maxHp;
    /** 当前显示名（含护甲/生命数据），用于玩家 NPC 的 Team 包与进服补发 */
    private String displayName;
    private LivingEntity entity;

    public Dummy(UUID id, UUID owner, LivingEntity entity, int maxHp) {
        this.id = id;
        this.owner = owner;
        this.entity = entity;
        this.anchor = entity != null ? entity.getLocation().clone() : null;
        this.maxHp = maxHp > 0 ? maxHp : 100;
    }

    public Dummy(UUID id, UUID owner, LivingEntity entity) {
        this(id, owner, entity, 100);
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwner() {
        return owner;
    }

    public LivingEntity getLiving() {
        return entity;
    }

    /**
     * 兼容旧调用：若底层是盔甲架则返回，否则返回 null。
     */
    public ArmorStand getStand() {
        return entity instanceof ArmorStand stand ? stand : null;
    }

    public Entity getEntity() {
        return entity;
    }

    public boolean isPlayerNpc() {
        return entity instanceof Player;
    }

    public Location getLocation() {
        return entity != null ? entity.getLocation() : null;
    }

    public Location getAnchor() {
        return anchor;
    }

    public int getMaxHp() {
        return maxHp;
    }

    /**
     * 有效生命上限：min(配置 maxHp, 实体实际属性上限)。
     * 26.2 下第三方插件/服务端可能钳制玩家生命上限（实测 100 → 80），显示与回血都以实际值为准。
     */
    public double getEffectiveMaxHp() {
        return Math.max(1.0, Math.min(maxHp, readMaxHealth()));
    }

    /**
     * 实体当前生命上限（走属性 API，避开弃用的 Damageable#getMaxHealth()）。
     */
    private double readMaxHealth() {
        if (entity == null) {
            return maxHp;
        }
        try {
            AttributeInstance attr = entity.getAttribute(Attribute.MAX_HEALTH);
            if (attr != null) {
                double value = attr.getValue();
                if (value > 0) {
                    return value;
                }
            }
        } catch (Exception ignored) {
        }
        return maxHp;
    }

    /**
     * 当前生命值（0 = 已被击杀）。
     */
    public double getHealth() {
        return entity != null ? entity.getHealth() : 0;
    }

    public boolean isValid() {
        return entity != null && entity.isValid() && !entity.isDead();
    }

    /**
     * 击杀后原地重生：回满生命、回到出生锚点、熄灭火焰、清零速度。
     */
    public void respawn() {
        if (entity == null || !entity.isValid()) {
            return;
        }
        // 回满到配置 maxHp。26.2 下玩家实体的有效生命上限可能被第三方插件/属性修饰钳制
        // （实测出现「Health value (100.0) must be between 0 and 80.0」异常，导致击杀后卡 1 血
        // 无法重生）——先清掉外来修饰，再钳制到有效上限，setHealth 永远不抛异常。
        AttributeInstance attr = entity.getAttribute(Attribute.MAX_HEALTH);
        if (attr != null) {
            try {
                attr.getModifiers().forEach(m -> attr.removeModifier(m));
            } catch (Exception ignored) {
            }
            try {
                attr.setBaseValue(maxHp);
            } catch (Exception ignored) {
            }
        }
        try {
            entity.setHealth(maxHp);
        } catch (Exception e) {
            // 有效上限被服务端钳制：退而求其次回满到有效上限
            try {
                entity.setHealth(readMaxHealth());
            } catch (Exception ignored) {
            }
        }
        entity.setFireTicks(0);
        entity.setVelocity(new Vector(0, 0, 0));
        if (anchor != null && anchor.getWorld() != null && anchor.getWorld() == entity.getWorld()) {
            entity.teleport(anchor);
        }
    }

    // ============ 实现 ============

    /**
     * 对底层实体应用静态配置（重力/可受伤/高血量/持久等），按实体类型区分。
     */
    public void configureStatic() {
        if (entity == null) {
            return;
        }
        // 盔甲架独有外观配置
        if (entity instanceof ArmorStand stand) {
            stand.setGravity(false);
            stand.setBasePlate(false);
            stand.setArms(true);
            stand.setSmall(false);
            stand.setVisible(true);
        }
        // 统一：无重力（物理回弹由弹簧-阻尼驱动）、可受伤（真实伤害事件）、不被卸载
        entity.setGravity(false);
        // 允许真实受击（见类注释）：不设无敌，才能捕获含高级附魔在内的完整真实伤害。
        entity.setInvulnerable(false);
        entity.setRemoveWhenFarAway(false);
        // 持久化策略：
        //  盔甲架 → 持久（随区块正常存档，重启后由 findExistingById 复用）；
        //  玩家 NPC → 不持久（假玩家带连接字段不适合写世界存档；区块卸载即清除，
        //            由区块加载事件 restoreChunk 从存储记录重建）。
        entity.setPersistent(entity instanceof ArmorStand);
        // 假人生命值：由放置物品 / 配置决定（/dummy give 的 [生命] 参数），可被击杀。
        // setMaxHealth(double) 在 26.2 已弃用（since 1.20.6），改用属性 API 设置基础值。
        AttributeInstance maxHealthAttr = entity.getAttribute(Attribute.MAX_HEALTH);
        if (maxHealthAttr != null) {
            try {
                maxHealthAttr.getModifiers().forEach(m -> maxHealthAttr.removeModifier(m));
            } catch (Exception ignored) {
            }
            try {
                maxHealthAttr.setBaseValue(maxHp);
            } catch (Exception ignored) {
            }
        }
        try {
            entity.setHealth(maxHp);
        } catch (Exception e) {
            try {
                entity.setHealth(readMaxHealth());
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 设置装备。slot 参考 {@link EquipmentSlot}。
     */
    public void setEquipment(EquipmentSlot slot, ItemStack item) {
        EntityEquipment eq = entity.getEquipment();
        if (eq == null) {
            return;
        }
        ItemStack value = (item == null || item.getType() == Material.AIR) ? null : item;
        switch (slot) {
            case HELMET:
                eq.setHelmet(value);
                break;
            case CHESTPLATE:
                eq.setChestplate(value);
                break;
            case LEGGINGS:
                eq.setLeggings(value);
                break;
            case BOOTS:
                eq.setBoots(value);
                break;
            case HAND:
                eq.setItemInMainHand(value);
                break;
            case OFF_HAND:
                eq.setItemInOffHand(value);
                break;
            default:
                break;
        }
        // 玩家 NPC：手动广播装备包（不依赖实体追踪器，FancyNpcs 同款）
        if (entity instanceof Player npc) {
            try {
                top.miragedge.dummy.npc.PlayerNpcFactory.broadcastEquipment(npc);
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 读取装备。
     */
    public ItemStack getEquipment(EquipmentSlot slot) {
        EntityEquipment eq = entity.getEquipment();
        if (eq == null) {
            return null;
        }
        ItemStack item;
        switch (slot) {
            case HELMET:
                item = eq.getHelmet();
                break;
            case CHESTPLATE:
                item = eq.getChestplate();
                break;
            case LEGGINGS:
                item = eq.getLeggings();
                break;
            case BOOTS:
                item = eq.getBoots();
                break;
            case HAND:
                item = eq.getItemInMainHand();
                break;
            case OFF_HAND:
                item = eq.getItemInOffHand();
                break;
            default:
                item = null;
                break;
        }
        return (item == null || item.getType() == Material.AIR) ? null : item;
    }

    /**
     * 清空假人所有装备。
     */
    public void clearEquipment() {
        for (EquipmentSlot slot : EquipmentSlot.values()) {
            setEquipment(slot, null);
        }
    }

    /**
     * 设置自定义名（默认可见）。
     */
    public void setCustomName(String name) {
        setCustomName(name, true);
    }

    /**
     * 设置自定义名，并指定名称是否可见。
     * Nameable.setCustomName(String) 在 26.2 已弃用（换 Adventure Component），
     * 本项目显示名统一走 & 颜色码字符串体系（与 messages.yml 一致），此处有意沿用并抑制告警。
     */
    @SuppressWarnings("deprecation")
    public void setCustomName(String name, boolean visible) {
        this.displayName = name;
        entity.setCustomName(name);
        entity.setCustomNameVisible(visible);
    }

    /** 当前显示名（玩家 NPC 的 Team 包需要按名字重建） */
    public String getDisplayName() {
        return displayName;
    }

    public void remove() {
        if (entity != null) {
            // 玩家 NPC 走 NMS discard（CraftPlayer.remove() 会抛 UnsupportedOperationException）
            top.miragedge.dummy.npc.PlayerNpcFactory.removeEntity(entity);
        }
    }

    /**
     * 装备槽位枚举（与 Bukkit EquipmentSlot 解耦，便于持久化）。
     */
    public enum EquipmentSlot {
        HELMET, CHESTPLATE, LEGGINGS, BOOTS, HAND, OFF_HAND
    }
}
