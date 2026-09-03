# MiragEdgeDummy 训练假人 —— 开发文档

> 本文档是给「实现 agent」的完整开发说明书。**不要求任何额外上下文**——所有功能需求、交互细节、公式、坑位都在本文档里。按本文档 + 骨架代码完成全部 `TODO` 后，插件即达到可交付状态。
>
> 技术背景与反编译分析见 [TECHNICAL-REPORT.md](./TECHNICAL-REPORT.md)。

---

## 0. 一句话项目

**训练假人（MiragEdgeDummy）** 是一个训练假人插件：玩家放置一个盔甲架假人，假人穿上护甲后，玩家打它就能测出「面对这套护甲时我的真实伤害是多少」。纯 Paper API，**零外部依赖**（不需要 Citizens / 任何前置），兼容 Geyser 基岩版。

## 1. 构建环境（必须遵守）

| 项 | 值 |
|----|----|
| JDK | **OpenJDK 25**（`/usr/lib/jvm/java-25-openjdk-amd64`） |
| Maven | 3.9.x（离线 `mvn -o -B`） |
| Paper API | **26.2.build.119-stable**（`io.papermc.paper:paper-api`，provided 作用域） |
| 编译命令 | `JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 PATH=\${JAVA_HOME}/bin:${PATH} mvn -o -B clean compile` |
| Java 语言级别 | 25（pom 已配 `maven.compiler.release=25`） |

**版本要求**：服务端为 Paper/Leaf **26.2**（对应 MC 1.21.x），无需兼容低版本。26.2 的 paper-api 必须用 JDK 25 编译（class 版本要求）。离线构建时若 paper-api jar 无法解析，删除本地仓库对应版本的 `_remote.repositories` 文件即可。

**pom 坐标**：`top.miragedge:miragedgedummy:1.2`，finalName=`MiragEdgeDummy`（产物 `target/MiragEdgeDummy.jar`）。

## 2. 功能需求（验收即对照此清单）

玩家向功能（玩家不依赖命令，全部 GUI/物品交互）：

1. **[F1] 放置**：手持「训练假人」物品右键地面 → 扣 1 个物品，在视线落点上方 1 格生成假人实体（默认玩家 NPC）。
2. **[F2] 挨打显示真实受击伤害**：玩家近战/弓箭攻击假人 → ActionBar 显示 `伤害: 7.5 (3.7 ❤)`（伤害数值可配置小数位），并可选附带蓄力百分比、CPS、假人剩余生命（`notifications.show-cooldown/show-cps`）。**伤害来源 = 服务端真实 `EntityDamageEvent` 的最终伤害**（MONITOR 捕获）——天然包含武器附魔、攻击冷却、暴击、护甲减伤，以及**服务器上所有高级附魔插件（Aiyatsbus / EcoEnchants 等）对伤害的修改与命中效果**。
3. **[F3] 护甲减伤**：假人穿上盔甲后，显示的伤害按原版公式扣除护甲 + 韧性 + 保护附魔。（本插件核心卖点。玩家 NPC 为真玩家实体，护甲属性天然生效；显示值读真实 `Attribute.ARMOR`。）
4. **[F4] 穿装备**：手持盔甲/武器右键假人 → 穿到对应槽位（头盔/胸甲/护腿/靴子/主手），消耗手中 1 个物品，换下的旧装备回到背包。**PVP 大厅场景：仅管理员（`miragedgedummy.admin`）可编辑装备。**
5. **[F5] 取下装备**：空手右键假人 → 取下最后穿戴的装备（或遍历取一件）回背包。（仅管理员）
6. **[F6] 收回**：潜行右键假人 → 实体移除，训练假人物品回到背包。（仅管理员）
7. **[F7] 真实掉血 + 可被击杀**：玩家攻击让假人真实掉血（掉血量 = 显示伤害）；血量归零判定「击杀」→ 立即在原位置满血重生（装备/皮肤/追踪保留），击杀者收到击杀反馈。非玩家伤害（火/摔落/爆炸）归零。
8. **[F8] 持久化**：放置后重启服务器，训练假人原位恢复（含位置朝向与生命值）。
9. **[F9] 权限控制**：编辑（穿/取装备、收回）仅管理员；普通玩家只能攻击练手。

增强功能：

10. **[F10] 攻击冷却影响伤害**：真实事件天然含冷却因子；ActionBar 附带「蓄力: {percent}%」（近战）。横扫事件已跳过（否则其冷却恒为 4%~11% 覆盖主攻击显示）。
11. **[F11] 命中粒子**：血雾 `DUST` + 暴击 `CRIT`。
12. **[F12] 浮动伤害数字**：`TextDisplay` transformation 插值侧抛散开（丝滑）。
13. **[F13] 真实击退回弹**：读取服务端施加的**真实击退速度**（含击退附魔/疾跑的真实值）换算弹簧位移，朝锚点弹簧回位；无真实击退时保底冲量。
14. **[F14] CPS 显示**：ActionBar 显示最近 1 秒点击次数。
15. **[F15] 假人皮肤**：`npc-skin` = 玩家名（玩家 NPC 皮肤写进 GameProfile）。
16. **[F16] 假人生命值**：`/dummy give <玩家> <数量> [生命]` 指定每只假人最大生命值（物品 lore 显示；放置/收回/重启全程保留；头顶名称实时显示 生命: 当前/最大）。

管理员向（命令 `/dummy`，权限见 plugin.yml）：

17. **[C1] `/dummy give <玩家> <数量> [生命]`**：给玩家训练假人物品（`miragedgedummy.give`）。
18. **[C2] `/dummy reload`**：重载配置与消息（`miragedgedummy.reload`）。
19. **[C3] `/dummy list` / `remove <uuid>` / `info <uuid>`**：管理所有训练假人（`miragedgedummy.admin`）。

## 3. 配置与消息（骨架已写好默认值）

**config.yml**（`ConfigManager` 读取）：
- `item.material`：物品材质，默认 `ARMOR_STAND`
- `notifications.precision`：伤害小数位，默认 `1`
- `notifications.mode`：`actionbar`（推荐，基岩版兼容）或 `chat`
- `notifications.show-cps`：命中读数是否附带 CPS（默认 true，F14）
- `notifications.show-cooldown`：未蓄满力时是否附带蓄力百分比（默认 true，F10）
- `npc-name`：假人显示名，默认 `&e训练假人`
- `npc-name-visible`：是否显示名字悬浮标签
- `npc-skin`：假人皮肤玩家名（默认 `""` 空=不启用；玩家 NPC 用其皮肤，盔甲架方案占用头盔槽，F15）
- `dummy-entity-type`：假人底层实体类型（默认 `player`=真实玩家 NPC；`armor-stand`=传统盔甲架，F16）
- `removal.require-sneak`：是否需要潜行才可收回
- `allow-non-owners-break`：非主人能否收回
- `debug`：调试模式

**messages.yml**（`Messages` 工具读取）：
- 全部中文；**禁止 emoji**（基岩版 Geyser 渲染为 `?` 乱码）。唯一白名单：血量符号 `❤`（U+2764），基岩版字体原生支持
- 玩家向提示正文**灰色斜体** `§7§o`（服务器消息约定，与普通白色消息区分）
- 前缀 messages.global-prefix 自动拼到每条消息前（例外：伤害读数 messages.damage 与附加的 messages.cooldown / messages.cps 不加前缀——ActionBar 高频刷新保持短文案，与 §2 F2 示例一致）
- 占位符 `{damage}` `{hearts}` `{player}` `{amount}` `{id}` `{owner}` `{world}` `{item}` `{percent}` `{cps}` 运行时替换
- 新增键：`messages.cooldown`（`&7蓄力: &e{percent}&7%`）、`messages.cps`（`&7CPS: &a{cps}`）、`messages.skin-helmet-blocked`（启用皮肤后禁止装备头盔的提示）

实现要求：`ConfigManager` 首次启动生成 config.yml 与 messages.yml（`saveDefaultConfig()` + 手动 `saveResource("messages.yml", false)`），reload 时重读磁盘文件（服主改过就以磁盘为准）。

## 4. DummyManager / Dummy —— 训练假人生命周期

### 4.1 PDC 标记（识别核心，必须一致）

每个训练假人实体打 3 个 PersistentData 标记（NamespacedKey 用插件实例 `new NamespacedKey(plugin, key)`）：

| Key | 类型 | 内容 |
|-----|------|------|
| `dummy` | BYTE | `(byte)1`，标识这是训练假人 |
| `owner` | STRING | 放置者 UUID 字符串 |
| `id` | STRING | 训练假人自身 UUID 字符串（用于找回 tracker） |

**放置物品**同样打 `dummy=1` 标记在 ItemMeta 的 PDC 上（用于 F1 识别手中物品）。

### 4.2 盔甲架静态配置（F7/F2 关键）

```java
stand.setGravity(false);          // 无重力（物理回弹由弹簧-阻尼驱动，Y 分量手工衰减）
stand.setBasePlate(false);        // 无基座板
stand.setArms(true);              // 有手臂（像人）
stand.setSmall(false);            // 正常大小
stand.setVisible(true);
stand.setInvulnerable(false);     // 关键：可受伤 → 服务端才产生真实 EntityDamageEvent（F2 前提）
stand.setRemoveWhenFarAway(false);
stand.setPersistent(true);        // 不因区块卸载消失
// 高血量兜底：伤害虽在 MONITOR 归零，仍设 1024 血防意外致死（/kill、插件直接 kill）
stand.setMaxHealth(1024)（经 Attribute.MAX_HEALTH.setBaseValue）+ setHealth(1024)
```

> **为什么不再无敌**：F2 要显示「真实受击伤害」（含服务器上高级附魔的伤害），唯一可靠来源是
> 服务端真实产生的伤害事件。假人无敌时事件不触发（或无效），只能人工估算（漏算高级附魔）。
> 改为可受伤后：每次攻击都产生真实事件 → 监听器在 MONITOR 读取最终伤害 → `setDamage(0)` 抵消
> （详见 §7.2）。注意：**只归零伤害、不 cancel 事件**（cancel 会让服务端跳过攻击冷却重置，
> 玩家永远满蓄力、伤害恒满，且丢失原版真实击退）。
>
> **实体类型（F16）**：`configureStatic()` 按实体类型区分——盔甲架额外设置无基座/有手臂/可见等外观，
> 玩家 NPC 不设外观（本身就是真人模型）。持久化策略：盔甲架 `setPersistent(true)`（随区块存档、重启复用）；
> 玩家 NPC `setPersistent(false)`（假玩家带连接字段不适合写世界存档，区块卸载即清除，由
> `ChunkLoadEvent` → `restoreChunk()` 从存储记录重建）。两者都 `setGravity(false)`（物理回弹由弹簧-阻尼驱动）。

### 4.3 spawnDummy（F1）

1. 校验手中是训练假人物品，`hand.setAmount(hand.getAmount() - 1)` 扣 1。
2. 落点：`player.getTargetBlockExact(5).getLocation().add(0, 1, 0)`；朝向取玩家 yaw + 180°（假人面向玩家），pitch=0。
3. `world.spawnEntity(loc, EntityType.ARMOR_STAND)` → 打 PDC 标记 → `configureStatic()` → 建 `Dummy` 入 `dummies` map。
4. 写 `DummyRecord` 到 storage（含 yaw/pitch）。

### 4.4 恢复 / 清理（F8）

- `restoreAll()`：遍历 `storage.all()`，世界未加载跳过；区块未加载用 `world.getChunkAtAsync(loc).thenAccept(...)` 异步生成（**不能同步 getChunk**，Leaf 会拦）。
- `cleanupOrphans()`：扫描 `Bukkit.getWorlds()` 的所有实体，有 `dummy=1` 标记但不在 `dummies` 且 storage 无记录 → 移除（防止重复生成/残留）。
- 主类里已排好时序：启动 100 tick 后 restore，再延迟 60 tick cleanup（等异步区块）。

### 4.5 saveAll（F8）

onDisable 时遍历 `dummies`，有效实体写 `DummyRecord`（位置四舍五入到合理精度，含 yaw/pitch）。

### 4.6 头顶护甲/生命值显示（真实值）

- 假人显示名 = `npc-name` + ` §7护甲: §aX`（+韧）+ `§7生命: §c当前§7/§a最大`。
- **真实值优先**：玩家 NPC 是真玩家实体，装备护甲属性天然生效——直接读 `Attribute.ARMOR` /
  `Attribute.ARMOR_TOUGHNESS` 真实值显示（与服务器实际减伤严格一致）；盔甲架兜底（属性不生效）
  才回退静态护甲表。
- 刷新时机：放置、恢复、穿/取装备、每次受击、击杀重生（生命值实时变化）。
- 名称可见性仍由 `npc-name-visible` 控制。

### 4.7 受击表现（F11/F12/F13：物理击退 + 粒子 + 浮动伤害数字）

假人受击（真实伤害事件路径，见 §7.2）时 `DummyManager.onHit(dummy, 伤害, 是否暴击, 击退方向)` 依次做四件事：

1. **受击音效**：`Sound.ENTITY_ARMOR_STAND_HIT`。
2. **命中粒子（克制）**：红色血雾 `Particle.DUST`（`DustOptions` 深红，少量）+ 暴击 `Particle.CRIT`——**去掉夸张的爱心粒子**（DAMAGE_INDICATOR）。
3. **浮动伤害数字（transformation 插值，丝滑动画）**：在假人位置生成 `EntityType.TEXT_DISPLAY`（billboard=CENTER、shadowed、红色，暴击黄色加粗）；
   **不垂直上天**——按攻击者视线的左右两侧随机一侧横向抛出（垂直于击退方向旋转 90°）；
   **关键帧插值**：预计算 6 个关键帧位置（水平匀速+垂直 sin 弧），每 4 tick 更新一次 `setTransformation`，客户端通过 `setInterpolationDuration(4)` 在帧间平滑插值（由渲染驱动、丝滑不卡顿），24 tick 后半程淡出自毁。实体带 PDC 标记 `damage-text`，onDisable 由 `removeAllTextDisplays()` 统一清理。
4. **击退回弹（弹簧-阻尼 + 真实击退值）**：记入 `recoilTicks/recoilDisp/recoilVel`，由 `tickDummies()` 每 tick 驱动：
   - 弹簧模型：`a = -k*disp`（离锚点越远回拉拉力越大）、`vel=(vel+a)*damp`、`disp+=vel`，每 tick `teleport(anchor+disp)`；
   - **真实击退值反馈**：回弹首帧读取服务端为假人施加的**真实击退速度**（含攻击力度/击退附魔/疾跑的真实值），
     按 `REAL_KNOCKBACK_SCALE(4.0)` 换算为位移覆盖保底冲量（上限 2.5 格）——击退幅度完全来自真实受击数据；
   - 收敛（|disp|²<1e-5 且 |vel|²<1e-5）或 60 tick 强制 `teleport(anchor)` 精确归位；
   - 静止态：清零速度，漂移则瞬时归位；每 tick 清 `fireTicks`。

> 设计要点：位移由弹簧积分驱动（平滑、自然过冲），幅度由服务端真实击退速度决定（真实值反馈），
> 保底冲量仅在服务端未施加击退时兜底，保证每击明显且最终精确归位。

### 4.8 假人皮肤（F15，npc-skin）

- `npc-skin` 配置非空时启用皮肤，按实体类型处理：
  - **玩家 NPC（F16，默认）**：`PlayerNpcFactory` 用官方 `Player#setPlayerProfile(PlayerProfile)` 应用皮肤，
    假人直接呈现真实玩家皮肤/身体/装备，无需戴头颅；
  - **盔甲架（回退/手动配置）**：给假人戴上对应玩家皮肤的 `PLAYER_HEAD`（头盔槽）。
- 实现：`Bukkit.createProfile(玩家名)`（Paper 原生 `com.destroystokyo.paper.profile.PlayerProfile`）
  → 已有纹理直接用；玩家 NPC 在生成时同步 `complete(true,false)` 解析后写入 GameProfile；
  盔甲架方案异步解析后 `SkullMeta.setPlayerProfile`（非弃用）应用。头颅带 PDC 标记 `skin`。
- 盔甲架方案的皮肤头是假人本体：`pickupDummy` / 管理员 `removeDummy` 不归还/不掉落皮肤头；
  `takeOffEquipment` 跳过头盔槽；`equipItem` 在皮肤启用时拒绝装备头盔类物品（提示
  `messages.skin-helmet-blocked`）。

### 4.9 假人玩家 NPC（F16，双层方案）

- `dummy-entity-type: player` 时用 `PlayerNpcFactory` 两层方案（每层失败都打 WARNING 便于排查）：
  **1. 官方 API**（Paper 26.2 起）：`world.spawn(loc, Player.class, SpawnReason.CUSTOM, ...)` → 得 Bukkit `Player`，Paper 官方处理「不在玩家列表/不占 Tab/不被存档/可被攻击」；先查 `EntityType.PLAYER.isSpawnable()`，不支持直接走下一层。
  **2. NMS 反射兜底**：官方不可用时（如某些 Leaf 构建）反射生成 `ServerPlayer` 加入世界（同样不在玩家列表、随机 UUID 不被存档）。
  **3. 都失败 → 回退盔甲架**。
- 皮肤：`Player#setPlayerProfile(PlayerProfile)`：缓存命中立即设置，未命中异步解析后回主线程——**皮肤解析与 NPC 生成解耦**（皮肤失败不影响 NPC 本体）。
- 玩家 NPC 不持久化（`setPersistent(false)`），区块卸载即清除，由 `ChunkLoadEvent`→`restoreChunk()` 重建。
## 5. DummyStorage / DummyRecord —— 持久化

- 单文件存储：`plugins/MiragEdgeDummy/data/<uuid>.yml`。
- 字段：uuid / owner / world / x / y / z / yaw / pitch / displayName / hp（生命值）。
- **写盘必须在异步线程**（`runTaskAsynchronously`），不要在 onDisable 之外的主线程同步写文件（主线程 IO 会卡服——这是本仓库 FE_PVP 项目踩过的坑）。
- 读入时：非法 UUID / 损坏 yml **静默跳过并打警告**，不中断启动。
- `world == null` 禁止保存（`DummyRecord.fromLocation` 已抛 IllegalArgumentException）。
- `shutdown()` 在 onDisable 调用：此时允许同步落盘（服务器在关服，无卡顿担忧），但建议仍异步并保证写入完成。

## 6. DamageCalculator —— 伤害公式（核心算法，全文照抄即可）

### 6.1 调用链（真实受击伤害）

```
onAnyDamage（MONITOR）收到真实 EntityDamageByEntityEvent
  → finalDamage = event.getFinalDamage()            // 真实最终伤害（含全部插件/护甲/保护/冷却/暴击）
  → 三分支计算 displayed（见 §7.3）：
       服务端已结算护甲(ARMOR≠0)  → displayed = finalDamage
       只结算了保护(MAGIC≠0)     → displayed = applyArmorOnly(假人, finalDamage)
       都未结算                  → displayed = calculateDamage(假人, finalDamage, 类型)
  → 兜底：finalDamage≈0（创造模式）→ getPlayerBaseDamage(玩家)（含冷却因子）× 护甲公式
  → Messages.fmtDamage(displayed, displayed/2, precision)
  → 按 mode 发 ActionBar（附加蓄力%/CPS）或 chat
```

### 6.2 护甲减伤（原版公式）

```
totalArmor   = 四件盔甲护甲值之和
totalTough   = 四件盔甲韧性之和

defensePoints = min(20, max(totalArmor/5, totalArmor - damage / (2 + totalTough/4)))
damage *= (1 - defensePoints / 25)
```

护甲值表（骨架里已给全）：皮 1/3/2/1，锁链 2/5/4/1，铁 2/6/5/2，金 2/5/3/1，钻 3/8/6/3 + 韧性 2.0，下界合金 3/8/6/3 + 韧性 3.0，海龟壳头盔 2。

### 6.3 保护附魔减伤（原版公式）

```
totalProt = 四件盔甲的保护等级之和（按伤害类型加权）:
  保护 protection           ×1
  火焰保护 fire_protection   ×2  （仅 FIRE / FIRE_TICK / LAVA / HOT_FLOOR）
  摔落保护 feather_falling   ×3  （仅 FALL）
  弹射物保护 projectile      ×2  （仅 PROJECTILE）
  爆炸保护 blast_protection  ×2  （仅 BLOCK_EXPLOSION / ENTITY_EXPLOSION）

capped = min(20, totalProt)
damage *= (1 - capped * 0.04)
```

### 6.4 baseDamage 兜底计算（事件拿不到伤害时）

主路径用真实事件的 `getFinalDamage()`；仅当 `finalDamage ≤ 0.0001` 且为近战（创造模式等）时兜底：

```
baseDamage = player.getAttribute(Attribute.ATTACK_DAMAGE).getValue()   // 1.21.4 已更名，旧名 GENERIC_ATTACK_DAMAGE 已移除
           + 锋利附魔 Sharpness × 1.25
           + 力量药水 Strength × (amplifier+1) × 3
           + 重锤坠落加成（可选，参照 mace 的坠击逻辑）
           × player.getAttackCooldown()   // 0.0~1.0 攻击冷却因子（F10）
```

- **攻击冷却**：主路径的真实事件 BASE 已由服务端按冷却结算，故显示天然随冷却变化（F10）；
  兜底路径必须手动乘 `getAttackCooldown()`，否则「冷却没满」与「满蓄力」显示一样。
- **1.21 注意**：`Enchantment` 用 `Enchantment.getByKey(NamespacedKey.minecraft("sharpness"))` 获取，
  **不要直接引用 `Enchantment.DAMAGE_ALL` 之类的旧常量**（1.20.5+ 附魔注册表化后旧常量已废弃/移除）。
  PotionEffect 同理用 `PotionEffectType.STRENGTH`。
- 兜底公式只能覆盖原版锋利/力量/重锤，**无法覆盖服务器高级附魔**——这正是主路径改用真实事件的原因。

## 7. DummyListener —— 事件矩阵（最复杂模块，坑最多）

### 7.1 放置 `onPlace(PlayerInteractEvent)`（F1）

- 仅 `event.getHand() == EquipmentSlot.HAND` 且 `Action.RIGHT_CLICK_BLOCK`。
- `isDummyItem(手中物品)` → `event.setCancelled(true)`。
- **PVP 大厅：放置也要求 `miragedgedummy.admin`**（普通玩家只能攻击练手，不能放置/编辑/收回），
  无权限时提示 `messages.no-edit-permission`。

### 7.2 伤害总闸 `onAnyDamage(EntityDamageEvent)`（F7/F2，优先级 MONITOR）

`isDummyEntity(entity)` 后统一处理（对**所有**伤害源）：

1. `EntityDamageByEntityEvent` → `handlePlayerAttack(...)`（§7.3）捕获真实伤害、让假人真实掉血（掉血量=显示值）、
   伤害 ≥ 当前生命时判「击杀」→ `setDamage(0)` + `killDummy` 原地满血重生（装备/皮肤保留）；
2. 非玩家伤害（火/摔落/爆炸/虚空）→ `setDamage(0)`（假人只被玩家击杀）；
3. `entity.setFireTicks(0)`；
4. 同步 + 下一 tick `living.setNoDamageTicks(0)` 解除无敌帧，支持高 CPS 连点每次都触发事件。

> **为什么不 cancel**：cancel 会让 `Player#attack` 的 `target.hurt()` 返回 false →
> `resetAttackStrengthTicker()` 被跳过 → 玩家攻击冷却永远不重置 → 伤害恒满（违背 F10），
> 且原版击退也不施加（破坏 F13 真实击退）。归零致死伤害 + 保留事件是两者的平衡点。

### 7.3 玩家攻击 `handlePlayerAttack(EntityDamageByEntityEvent, Dummy)`（F2/F3，MONITOR 内）

在 §7.2 的 MONITOR 内调用（此时所有插件对伤害的修改均已应用）：

1. 找攻击者：`damager instanceof Player` 或 `damager instanceof Projectile proj && proj.getShooter() instanceof Player`；非玩家攻击直接返回（仍被 §7.2 归零）。
2. 取 `finalDamage = event.getFinalDamage()`（真实最终伤害，含高级附魔的 CUSTOM 修饰符、冷却、暴击、护甲/保护减伤）。
3. **三分支**（读 ARMOR / MAGIC 修饰符判断服务端是否已结算）：
   - `|ARMOR| > 0.001`：服务端已按假人护甲结算 → `displayed = finalDamage`；
   - 否则 `|MAGIC| > 0.001`：只结算了保护附魔 → `displayed = DamageCalculator.applyArmorOnly(假人, finalDamage)`（只补护甲，避免保护重复）；
   - 否则：`displayed = DamageCalculator.calculateDamage(假人, finalDamage, 伤害类型)`（完整护甲+保护公式）。
4. **横扫跳过**：`ENTITY_SWEEP_ATTACK` 直接 `setDamage(0)` 返回——它跟随主攻击之后触发、冷却已被重置，
   蓄力恒读 4%~11% 覆盖主攻击正确显示（「满蓄力显示 4%」根因），且与主攻击重复扣血。
5. 兜底：`finalDamage ≤ 0.0001` 且近战（创造模式零伤害）→ `getPlayerBaseDamage(玩家)`（含冷却因子）× 护甲公式。
6. 判断暴击 → `dummyManager.onHit(dummy, displayed, crit, recoilDirection(...))`（粒子/浮动数字/弹簧击退，§4.7）。
7. **真实掉血/击杀判定**：`displayed ≥ living.getHealth()` → `setDamage(0)` + `killDummy`（原地满血重生）+ 击杀者提示；
   否则 `setDamage(displayed)`（掉血量与显示一致——真实值反馈）。
8. `sendDamage(player, displayed, cause, 剩余生命, 最大生命)`：ActionBar 显示 伤害 + 蓄力%（近战）+ CPS + 假人生命。

> 注：DamageModifier 枚举在 26.2 已弃用但无替代 API（org.bukkit.damage.DamageSource 不含分量），
> 仅在读取处 `@SuppressWarnings("deprecation")` 并注释说明。

### 7.4 交互 `onInteract(PlayerInteractAtEntityEvent)`（F4/F5/F6，优先级 LOWEST）

**这是三个功能的交汇点，分支顺序关键**：

```
右键假人:
  ├─ 交互越权防护：非主人（!allow-non-owners-break）不得 取下/穿上/收回，提示 not-owner{owner}
  ├─ 手持训练假人物品 且（潜行 或 removal.require-sneak=false）→ 收回（F6）
  │    实体 remove + 移除 tracker/storage + 归还装备 + 发回物品。
  │    需潜行而未潜行时提示 sneak-to-remove。
  ├─ 手持物品为空（AIR）→ 取下装备（F5）
  │    取「最后装备槽位」的装备回背包（槽位记在实体 PDC "last_equipped"，见 §7.5）；
  │    无记录则遍历 6 个槽位取第一件。
  └─ 手持其他物品 → 穿装备（F4）
        按物品类型映射槽位（slotFor）→ 穿上；
        旧装备回背包；手中物品 -1。
```

**必须处理的边界（PlayerDummies 踩过的坑，全部要在实现中覆盖）**：
- **收回 vs 取下（UX 优化）**：收回仅当**手持训练假人物品**时才可能触发（配合潜行确认，避免玩家蹲下交互时误收整个假人）；**空手一律走取下**（无论是否潜行），不会误收。原实现「潜行空手=收回」易误触，已改为本规则。
- **owner 校验**：取下/穿上/收回三条路径统一校验主人（受 `allow-non-owners-break` 控制，默认 false），杜绝陌生玩家扒甲偷装。
- **副手语义**：玩家副手拿物品、主手空 → 右键应穿副手物品；主手有物时副手交互应忽略（否则双触发）。
- 取下装备的槽位记录：穿装备时把槽位名（如 `HELMET`）写入实体 PDC `last_equipped`，取下时优先读它——这样「刚穿的护甲」能精准取下，而不是每次取到护腿。
- 双事件问题：`PlayerInteractAtEntityEvent` 与 `PlayerInteractEntityEvent` 会都触发。**在 At 变体里处理业务，在非 At 变体里直接取消**（`onInteractLegacy`），避免重复执行。
- 防抖：收回/取下用 `(playerUuid:entityUuid)` 为 key 的 500ms 冷却 map（路径标识区分，收回/取下各自独立），防止同一个右键触发两次。

### 7.5 last_equipped PDC

穿装备时：`entity.getPersistentDataContainer().set("last_equipped", STRING, slot.name())`。
取下后或清空时：`remove("last_equipped")`。

### 7.6 `onSwing(PlayerAnimationEvent)`（F14：CPS 统计，不再估算伤害）

- 假人已改为可受伤（§4.2），每次点击都会产生真实伤害事件 → 伤害显示**一律以真实事件为准**（§7.3），
  旧版「空挥射线 + 人工估算伤害」已移除（估算漏算高级附魔，与真实伤害不一致）。
- `onSwing` 仅保留一个职责：`ARM_SWING` 动画 → `recordCps(player)`，把点击时间戳写入每玩家
  1 秒滑动窗口队列（`cpsClicks`），供 ActionBar 显示 CPS（最近 1 秒点击次数）。
- 队列在 `pruneMaps`（每 60 秒）清理过期玩家，主线程操作无并发问题。

## 8. DummyCommand —— 命令

- `give <player> <amount>`：`Integer.parseInt` 校验为正数；找不到玩家报错；成功给物品（玩家自己也收到一条）。
- `reload`：`ConfigManager.reload()`。
- `list`：遍历 storage.all() 打印（owner 解析为玩家名）。
- `remove <uuid>` / `info <uuid>`：UUID 解析失败 / 不存在都要给明确中文提示。
- 每条消息走 `Messages`（中文），权限校验用 plugin.yml 里的节点。
- `onTabComplete`：`give` 补全玩家名 + `"1"`；`remove/info` 补全已存 uuid；一级参数按权限过滤。

## 9. 消息与玩家体验规范（服务器约定，必须遵守）

1. **全中文**，游戏内消息**禁止 emoji**（Geyser 渲染乱码）。例外：血量符号 `❤`（U+2764）经基岩版字体原生支持、Geyser 验证可正常显示，属于允许的白名单符号。
2. 玩家向提示正文用 **灰色斜体 §7§o**；例外：命令成功反馈可用 &a（如 reload-success/remove-success）、错误反馈可用 &c（如 invalid-usage），便于管理员区分状态。
3. 不输出 md 代码块、不搞花哨装饰。
4. 玩家向功能必须**物品/GUI 交互**，命令仅供管理员（玩家「太笨了让用指令是天方夜谭」——服务器既定规则）。
5. 物品 lore 用大白话说明用法（骨架 messages.yml 已写示例）。

## 10. 性能与线程

- 写盘异步（§5）。
- 每 tick 物理循环 `tickDummies()`（主类已写）遍历 `dummies.values()` 且实体有效才处理，开销极小。
- 每命中：生成 1 个 TextDisplay + 1 个 20-tick 自取消任务；任务在实体失效/期满时自清理，无泄漏；
  高 CPS 连点时同刻在场的浮动数字数量有限（寿命 1 秒），可接受。
- `profile.complete(true,false)` 皮肤解析可能联网，**必须在异步线程**执行（已用 `runTaskAsynchronously`），
  完成后回主线程 `applySkin`。
- **所有 Bukkit API 调用在主线程**；异步线程只做 IO（读文件、写文件、皮肤解析）。

## 11. 交付验收清单（实现 agent 自查后打勾）

- [x] `mvn compile` 通过（JDK25 + paper-api 26.2.build.119-stable 离线），**零 error、零 deprecation 告警**。
- [x] 打包 `mvn package` 产出 `target/MiragEdgeDummy.jar`（finalName=MiragEdgeDummy）。
- [x] 阅读代码自查：无未完成的 TODO/FIXME。
- [x] 逻辑自查：放置→扣物品→生成假人（**玩家 NPC 优先，失败回退盔甲架**）；**攻击→真实受击伤害 ActionBar（含高级附魔）**；穿甲→显示减伤后的伤害（护甲/韧性/保护）；空手取下→回背包（头顶护甲值实时刷新）；潜行+手持假人物品→收回；重启→恢复原位；**区块重载→restoreChunk 重建玩家 NPC**。
- [x] 新功能自查：未蓄满力伤害变低（F10）；**命中粒子克制无爱心**（F11）；**伤害数字向玩家左右两侧侧抛散开**（F12）；**物理回弹保证可见**（F13，兜底冲量）；ActionBar 显示 CPS（F14）；**npc-skin 皮肤 + 真实玩家 NPC 实体**（F15/F16）。
- [x] 边界自查：副手交互不双触发；**At/非At 交互事件 tick 去重（兼容玩家NPC）**；空手一律取下；非主人不能操作装备（默认配置）；假人不死/不燃（MONITOR 归零 + noDamageTicks 连点 + EntityDeathEvent 兜底）；高级附魔插件命中特效不被取消（不 cancel 事件）；皮肤头在收回/删除/取下时被正确跳过；玩家 NPC 不持久化（避免假玩家存档污染）+ 区块加载重建。
- [x] 消息自查：全部中文、仅有白名单 ❤、前缀正确、占位符替换正确（damage/cooldown/cps 例外不加前缀）。
- [x] 对照 §2 功能清单 1-18 逐条确认。

## 12. 部署说明（给汐汐酱）

- JAR：`target/MiragEdgeDummy.jar`（Maven 打包，finalName=MiragEdgeDummy），放到服务器 `plugins/` 目录。
- 版本：Paper/Leaf 26.2（`api-version: '1.21'` 可继续在 1.21+ 上加载），JDK 25 编译。
- 无需任何前置插件。首启生成 config.yml / messages.yml。
- `/dummy give <玩家> 1` 发物品；玩家右键地面放置。
- 可选：`dummy-entity-type: player`（默认，Paper 26.2 官方 NPC 玩家实体，失败自动回退盔甲架）/ `armor-stand`；
  `npc-skin` 启用皮肤；伤害显示附加 CPS/蓄力由 `notifications.show-cps/show-cooldown` 控制。
- 需要重启服务端生效（本项目不处理热加载）。