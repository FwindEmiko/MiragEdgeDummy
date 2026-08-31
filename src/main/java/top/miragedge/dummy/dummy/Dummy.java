package top.miragedge.dummy.dummy;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * 木人桩实体封装：包一层 {@link ArmorStand}，持有 id / 主人。
 *
 * <p>零依赖实现（不需要 Citizens）：假人 = 不可击退的静态盔甲架。</p>
 *
 * <p>实现要点（见 docs/DEVELOPMENT.md §4）：</p>
 * <ul>
 *   <li>PDC 标记：dummy=1（标识）、owner=UUID字符串、id=UUID字符串（三个 NamespacedKey）</li>
 *   <li>静态配置：无重力/无基座/有手臂/可见/无敌/不被卸载/持久</li>
 *   <li>装备槽位：盔甲 + 主手 + 副手</li>
 * </ul>
 */
public class Dummy {

    private final UUID id;
    private final UUID owner;
    private ArmorStand stand;

    public Dummy(UUID id, UUID owner, ArmorStand stand) {
        this.id = id;
        this.owner = owner;
        this.stand = stand;
    }

    public UUID getId() {
        return id;
    }

    public UUID getOwner() {
        return owner;
    }

    public ArmorStand getStand() {
        return stand;
    }

    public Entity getEntity() {
        return stand;
    }

    public Location getLocation() {
        return stand != null ? stand.getLocation() : null;
    }

    public boolean isValid() {
        return stand != null && stand.isValid() && !stand.isDead();
    }

    // ============ 实现 ============

    /**
     * 对盔甲架应用静态配置（重力/基座/手臂/可见/无敌/持久等）。
     */
    public void configureStatic() {
        stand.setGravity(false);
        stand.setBasePlate(false);
        stand.setArms(true);
        stand.setSmall(false);
        stand.setVisible(true);
        stand.setInvulnerable(true);
        stand.setRemoveWhenFarAway(false);
        stand.setPersistent(true);
    }

    /**
     * 设置装备。slot 参考 {@link EquipmentSlot}。
     */
    public void setEquipment(EquipmentSlot slot, ItemStack item) {
        EntityEquipment eq = stand.getEquipment();
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
    }

    /**
     * 读取装备。
     */
    public ItemStack getEquipment(EquipmentSlot slot) {
        EntityEquipment eq = stand.getEquipment();
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
     */
    public void setCustomName(String name, boolean visible) {
        stand.setCustomName(name);
        stand.setCustomNameVisible(visible);
    }

    public void remove() {
        if (stand != null) {
            stand.remove();
        }
    }

    /**
     * 装备槽位枚举（与 Bukkit EquipmentSlot 解耦，便于持久化）。
     */
    public enum EquipmentSlot {
        HELMET, CHESTPLATE, LEGGINGS, BOOTS, HAND, OFF_HAND
    }
}
