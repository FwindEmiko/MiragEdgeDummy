package top.miragedge.dummy.damage;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;

/**
 * 伤害计算器：模拟 Minecraft 原版护甲 + 附魔减伤公式。
 *
 * <p>公式来源：PlayerDummies v1.0.3 反编译参考（DamageCalculator.java），
 * 已按 1.21 API 规则整理（Enchantment 用 NamespacedKey 注册表查询，兼容 1.20.5+）。</p>
 *
 * <p>注意：假人本身是 {@code invulnerable} 盔甲架，事件伤害已被置 0，
 * 这里计算的是「玩家打在人形目标上会造成的真实伤害」，用于 ActionBar 显示。</p>
 *
 * <p>详细公式见 docs/TECHNICAL-REPORT.md §6 与 docs/DEVELOPMENT.md §6。</p>
 */
public final class DamageCalculator {

    private static Map<Material, Integer> ARMOR_DEFENSE = new HashMap<>();
    private static Map<Material, Double> ARMOR_TOUGHNESS = new HashMap<>();

    /**
     * 1.21 关键：Enchantment 一律走注册表 {@link Enchantment#getByKey} 查询，
     * 禁止使用已移除的旧静态常量（如 Enchantment.PROTECTION）。
     * 查询结果可能为 null（对应类型不存在/被卸载），此时视为该类附魔不可用并跳过。
     */
    private static final Enchantment PROTECTION = Enchantment.getByKey(NamespacedKey.minecraft("protection"));
    private static final Enchantment FIRE_PROTECTION = Enchantment.getByKey(NamespacedKey.minecraft("fire_protection"));
    private static final Enchantment FEATHER_FALLING = Enchantment.getByKey(NamespacedKey.minecraft("feather_falling"));
    private static final Enchantment PROJECTILE_PROTECTION = Enchantment.getByKey(NamespacedKey.minecraft("projectile_protection"));
    private static final Enchantment BLAST_PROTECTION = Enchantment.getByKey(NamespacedKey.minecraft("blast_protection"));
    private static final Enchantment SHARPNESS = Enchantment.getByKey(NamespacedKey.minecraft("sharpness"));

    private DamageCalculator() {
    }

    /**
     * 计算造成伤害（含护甲/附魔减伤）。
     *
     * @param entity     假人（LivingEntity，用于读取装备）
     * @param baseDamage 基础伤害（玩家攻击力 + 锋利 + 力量药水 + 重锤加成）
     * @param damageType 伤害类型（决定保护附魔类别）
     */
    public static double calculateDamage(Entity entity, double baseDamage, DamageCause damageType) {
        // 1. 非 LivingEntity 或没有装备槽位 → 原样返回
        if (!(entity instanceof LivingEntity)) {
            return baseDamage;
        }
        EntityEquipment equipment = ((LivingEntity) entity).getEquipment();
        if (equipment == null) {
            return baseDamage;
        }

        double damage = baseDamage;

        // 2. 读取 4 件盔甲：护甲值 totalArmor + 韧性 totalToughness（查不到算 0）
        int totalArmor = armorValue(equipment.getHelmet())
                + armorValue(equipment.getChestplate())
                + armorValue(equipment.getLeggings())
                + armorValue(equipment.getBoots());
        double totalToughness = toughnessValue(equipment.getHelmet())
                + toughnessValue(equipment.getChestplate())
                + toughnessValue(equipment.getLeggings())
                + toughnessValue(equipment.getBoots());

        // 3. 护甲减伤（原版公式）
        double defense = Math.min(20, Math.max(totalArmor / 5.0, totalArmor - damage / (2.0 + totalToughness / 4.0)));
        damage *= (1 - defense / 25.0);

        // 4. 保护附魔：四件盔甲按类型加权累加 totalProt，封顶 20 点，每点减 4%
        double totalProt = protectionLevel(equipment.getHelmet(), damageType)
                + protectionLevel(equipment.getChestplate(), damageType)
                + protectionLevel(equipment.getLeggings(), damageType)
                + protectionLevel(equipment.getBoots(), damageType);
        double capped = Math.min(20, totalProt);
        damage *= (1 - capped * 0.04);

        // 5. 不为负
        return Math.max(0, damage);
    }

    /**
     * 获取玩家基础攻击力（含手持武器属性 + 锋利 + 力量 + 重锤坠落加成）。
     * 仅在事件提供的 base damage 不可用（为 0）时兜底调用。
     */
    public static double getPlayerBaseDamage(org.bukkit.entity.Player player) {
        // javap 已核验：paper-api 1.21.4-R0.1-SNAPSHOT 的 Attribute 枚举已改用注册表名
        //（ATTACK_DAMAGE / ARMOR / ARMOR_TOUGHNESS 等），GENERIC_ATTACK_DAMAGE 旧常量
        // 在该版本已不存在，故此处必须使用 Attribute.ATTACK_DAMAGE。
        // 若将来回退到旧版 API（仍保留 GENERIC_* 常量）可改回 Attribute.GENERIC_ATTACK_DAMAGE。
        double base = 1.0;
        AttributeInstance attack = player.getAttribute(Attribute.ATTACK_DAMAGE);
        if (attack != null) {
            base = attack.getValue();
        }

        // 主手锋利：每级 +1.25
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand != null && SHARPNESS != null) {
            base += hand.getEnchantmentLevel(SHARPNESS) * 1.25;
        }

        // 力量药水：每级 +3
        PotionEffect strength = player.getPotionEffect(PotionEffectType.STRENGTH);
        if (strength != null) {
            base += (strength.getAmplifier() + 1) * 3.0;
        }

        // 重锤坠落加成（近似实现，非精确原版 smash 公式，可选功能）：
        // 手持重锤且处于坠落（getFallDistance > 0）时按 5/格 增加，封顶 50。
        if (hand != null && hand.getType() == Material.MACE && player.getFallDistance() > 0) {
            base += Math.min(50.0, player.getFallDistance() * 5.0);
        }

        return base;
    }

    /**
     * 获取实体当前穿着的四件盔甲的总护甲点数（供假人头顶显示，与减伤公式共用同一张表）。
     */
    public static int getTotalArmor(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return 0;
        }
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return 0;
        }
        return armorValue(equipment.getHelmet())
                + armorValue(equipment.getChestplate())
                + armorValue(equipment.getLeggings())
                + armorValue(equipment.getBoots());
    }

    /**
     * 获取实体当前穿着的四件盔甲的总韧性（供头顶显示与减伤参考）。
     */
    public static double getTotalToughness(Entity entity) {
        if (!(entity instanceof LivingEntity living)) {
            return 0;
        }
        EntityEquipment equipment = living.getEquipment();
        if (equipment == null) {
            return 0;
        }
        return toughnessValue(equipment.getHelmet())
                + toughnessValue(equipment.getChestplate())
                + toughnessValue(equipment.getLeggings())
                + toughnessValue(equipment.getBoots());
    }

    /**
     * 单件盔甲护甲值；null/AIR 或无表项一律 0。
     */
    private static int armorValue(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return 0;
        }
        return ARMOR_DEFENSE.getOrDefault(item.getType(), 0);
    }

    /**
     * 单件盔甲韧性值；null/AIR 或无表项一律 0。
     */
    private static double toughnessValue(ItemStack item) {
        if (item == null || item.getType() == Material.AIR) {
            return 0.0;
        }
        return ARMOR_TOUGHNESS.getOrDefault(item.getType(), 0.0);
    }

    /**
     * 单件盔甲按伤害类型加权累加的保护附魔点数。
     * protection 对全部伤害类型计；火焰类 / 弹射 / 爆炸类权重 2；摔落权重 3。
     */
    private static double protectionLevel(ItemStack item, DamageCause damageType) {
        if (item == null || item.getType() == Material.AIR) {
            return 0.0;
        }
        double prot = 0.0;
        for (Map.Entry<Enchantment, Integer> entry : item.getEnchantments().entrySet()) {
            Enchantment ench = entry.getKey();
            int level = entry.getValue();
            if (PROTECTION != null && PROTECTION.equals(ench)) {
                prot += 1.0 * level;
            } else if (FIRE_PROTECTION != null && FIRE_PROTECTION.equals(ench) && isFireDamage(damageType)) {
                prot += 2.0 * level;
            } else if (FEATHER_FALLING != null && FEATHER_FALLING.equals(ench) && damageType == DamageCause.FALL) {
                prot += 3.0 * level;
            } else if (PROJECTILE_PROTECTION != null && PROJECTILE_PROTECTION.equals(ench)
                    && damageType == DamageCause.PROJECTILE) {
                prot += 2.0 * level;
            } else if (BLAST_PROTECTION != null && BLAST_PROTECTION.equals(ench)
                    && (damageType == DamageCause.BLOCK_EXPLOSION || damageType == DamageCause.ENTITY_EXPLOSION)) {
                prot += 2.0 * level;
            }
        }
        return prot;
    }

    private static boolean isFireDamage(DamageCause damageType) {
        return damageType == DamageCause.FIRE
                || damageType == DamageCause.FIRE_TICK
                || damageType == DamageCause.LAVA
                || damageType == DamageCause.HOT_FLOOR;
    }

    // ============ 静态数据（已完整，无需改动） ============

    static {
        ARMOR_DEFENSE.put(Material.LEATHER_HELMET, 1);
        ARMOR_DEFENSE.put(Material.LEATHER_CHESTPLATE, 3);
        ARMOR_DEFENSE.put(Material.LEATHER_LEGGINGS, 2);
        ARMOR_DEFENSE.put(Material.LEATHER_BOOTS, 1);
        ARMOR_DEFENSE.put(Material.CHAINMAIL_HELMET, 2);
        ARMOR_DEFENSE.put(Material.CHAINMAIL_CHESTPLATE, 5);
        ARMOR_DEFENSE.put(Material.CHAINMAIL_LEGGINGS, 4);
        ARMOR_DEFENSE.put(Material.CHAINMAIL_BOOTS, 1);
        ARMOR_DEFENSE.put(Material.IRON_HELMET, 2);
        ARMOR_DEFENSE.put(Material.IRON_CHESTPLATE, 6);
        ARMOR_DEFENSE.put(Material.IRON_LEGGINGS, 5);
        ARMOR_DEFENSE.put(Material.IRON_BOOTS, 2);
        ARMOR_DEFENSE.put(Material.GOLDEN_HELMET, 2);
        ARMOR_DEFENSE.put(Material.GOLDEN_CHESTPLATE, 5);
        ARMOR_DEFENSE.put(Material.GOLDEN_LEGGINGS, 3);
        ARMOR_DEFENSE.put(Material.GOLDEN_BOOTS, 1);
        ARMOR_DEFENSE.put(Material.DIAMOND_HELMET, 3);
        ARMOR_DEFENSE.put(Material.DIAMOND_CHESTPLATE, 8);
        ARMOR_DEFENSE.put(Material.DIAMOND_LEGGINGS, 6);
        ARMOR_DEFENSE.put(Material.DIAMOND_BOOTS, 3);
        ARMOR_DEFENSE.put(Material.NETHERITE_HELMET, 3);
        ARMOR_DEFENSE.put(Material.NETHERITE_CHESTPLATE, 8);
        ARMOR_DEFENSE.put(Material.NETHERITE_LEGGINGS, 6);
        ARMOR_DEFENSE.put(Material.NETHERITE_BOOTS, 3);
        ARMOR_DEFENSE.put(Material.TURTLE_HELMET, 2);

        ARMOR_TOUGHNESS.put(Material.DIAMOND_HELMET, 2.0);
        ARMOR_TOUGHNESS.put(Material.DIAMOND_CHESTPLATE, 2.0);
        ARMOR_TOUGHNESS.put(Material.DIAMOND_LEGGINGS, 2.0);
        ARMOR_TOUGHNESS.put(Material.DIAMOND_BOOTS, 2.0);
        ARMOR_TOUGHNESS.put(Material.NETHERITE_HELMET, 3.0);
        ARMOR_TOUGHNESS.put(Material.NETHERITE_CHESTPLATE, 3.0);
        ARMOR_TOUGHNESS.put(Material.NETHERITE_LEGGINGS, 3.0);
        ARMOR_TOUGHNESS.put(Material.NETHERITE_BOOTS, 3.0);

        // 静态表初始化完成后转为不可变，防止被外部修改
        ARMOR_DEFENSE = java.util.Collections.unmodifiableMap(ARMOR_DEFENSE);
        ARMOR_TOUGHNESS = java.util.Collections.unmodifiableMap(ARMOR_TOUGHNESS);
    }
}
