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
| JDK | **Azul Zulu JDK 21**（`F:\env\jdk\azul-21.0.11`） |
| Maven | 3.9.11（`F:\env\maven\3.9.11`，用 `mvn.cmd`） |
| Paper API | **1.21.4-R0.1-SNAPSHOT**（`io.papermc.paper:paper-api`，provided 作用域） |
| 编译命令 | `JAVA_HOME='F:\env\jdk\azul-21.0.11' F:\env\maven\3.9.11\bin\mvn.cmd compile` |
| Java 语言级别 | 21（pom 已配 `maven.compiler.release=21`） |

**为什么不能用 paper-api 26.x**：26.x 的 class 版本要求 JDK 25 编译，本机 JDK 21 读不了。1.21.4 编译产物在 Leaf 26.2（服务端版本）上可以正常运行——Paper 生态向后兼容。

**不要改 pom.xml 的 groupId/artifactId/version**（`top.miragedge:miragedgedummy:1.0.0`）。

## 2. 功能需求（验收即对照此清单）

玩家向功能（玩家不依赖命令，全部 GUI/物品交互）：

1. **[F1] 放置**：手持「训练假人」物品右键地面 → 扣 1 个物品，在视线落点上方 1 格生成盔甲架假人。
2. **[F2] 挨打显示伤害**：玩家近战/弓箭（含烟花火箭的弹射物）攻击假人 → ActionBar 显示 `伤害: 7.5 (3.7 ❤)`（伤害数值可配置小数位）。
3. **[F3] 护甲减伤**：假人穿上盔甲后，显示的伤害按原版公式扣除护甲 + 韧性 + 保护附魔。（这是本插件核心卖点。）
4. **[F4] 穿装备**：手持盔甲/武器右键假人 → 穿到对应槽位（头盔/胸甲/护腿/靴子/主手），消耗手中 1 个物品，换下的旧装备回到背包。
5. **[F5] 取下装备**：空手右键假人 → 取下最后穿戴的装备（或遍历取一件）回背包。
6. **[F6] 收回**：潜行右键假人 → 实体移除，训练假人物品回到背包。
7. **[F7] 防击退**：假人被打不后退、不燃烧、不受到真实伤害（伤害归零）。
8. **[F8] 持久化**：放置后重启服务器，训练假人原位恢复（含位置朝向）。
9. **[F9] 权限控制**：只有放置者本人能收回（可配置 `allow-non-owners-break`）。

管理员向（命令 `/dummy`，权限见 plugin.yml）：

10. **[C1] `/dummy give <玩家> <数量>`**：给玩家训练假人物品（`miragedgedummy.give`）。
11. **[C2] `/dummy reload`**：重载配置与消息（`miragedgedummy.reload`）。
12. **[C3] `/dummy list` / `remove <uuid>` / `info <uuid>`**：管理所有训练假人（`miragedgedummy.admin`）。

## 3. 配置与消息（骨架已写好默认值）

**config.yml**（`ConfigManager` 读取）：
- `item.material`：物品材质，默认 `ARMOR_STAND`
- `notifications.precision`：伤害小数位，默认 `1`
- `notifications.mode`：`actionbar`（推荐，基岩版兼容）或 `chat`
- `npc-name`：假人显示名，默认 `&e训练假人`
- `npc-name-visible`：是否显示名字悬浮标签
- `removal.require-sneak`：是否需要潜行才可收回
- `allow-non-owners-break`：非主人能否收回
- `debug`：调试模式

**messages.yml**（`Messages` 工具读取）：
- 全部中文；**禁止 emoji**（基岩版 Geyser 渲染为 `?` 乱码）
- 玩家向提示正文**灰色斜体** `§7§o`（服务器消息约定，与普通白色消息区分）
- 前缀 messages.global-prefix 自动拼到每条消息前（例外：伤害读数 messages.damage 不加前缀——ActionBar 高频刷新保持短文案，与 §2 F2 示例一致）
- 占位符 `{damage}` `{hearts}` `{player}` `{amount}` `{id}` `{owner}` `{world}` `{item}` 运行时替换

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
stand.setGravity(false);          // 无重力
stand.setBasePlate(false);        // 无基座板
stand.setArms(true);              // 有手臂（像人）
stand.setSmall(false);            // 正常大小
stand.setVisible(true);
stand.setInvulnerable(true);      // 无敌 → 但注意见 §7.4 的伤害计算方式
stand.setRemoveWhenFarAway(false);
stand.setPersistent(true);        // 不因区块卸载消失
```

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

### 4.6 头顶护甲值显示（增强）

- 假人显示名 = 配置 `npc-name` + ` §7护甲: §a{护甲点数}`（护甲点数 = 四件盔甲 ARMOR_DEFENSE 之和，0 也显示）。
- 放置、恢复、穿装备、取下装备后都会调用 `DummyManager.updateDisplayName(dummy)` 刷新；
  护甲值与伤害减伤共用 `DamageCalculator.getTotalArmor` 同一张表，保证一致。
- 名称可见性仍由 `npc-name-visible` 控制。

### 4.7 受击击退动效（增强）

- 假人受击（`onDamage`）或空挥命中（`onSwing`）时：向攻击反方向瞬移 0.35 格，再用 5 tick 线性插值弹回原位，并播放
  `Sound.ENTITY_ARMOR_STAND_HIT` 音效。
- 纯视觉位移（teleport），不改变 F7「假人不被真实击退」语义；连续受击会取消旧动画重新开始；
  动画任务登记在 `DummyManager#hitAnimations`，onDisable 时 `cancelAllHitAnimations()` 统一取消。
- 血量符号 `❤`（U+2764）已确认基岩版字体原生支持、Geyser 可正常显示，属 §9.1 白名单。

## 5. DummyStorage / DummyRecord —— 持久化

- 单文件存储：`plugins/MiragEdgeDummy/data/<uuid>.yml`。
- 字段：uuid / owner / world / x / y / z / yaw / pitch / displayName。
- **写盘必须在异步线程**（`runTaskAsynchronously`），不要在 onDisable 之外的主线程同步写文件（主线程 IO 会卡服——这是本仓库 FE_PVP 项目踩过的坑）。
- 读入时：非法 UUID / 损坏 yml **静默跳过并打警告**，不中断启动。
- `world == null` 禁止保存（`DummyRecord.fromLocation` 已抛 IllegalArgumentException）。
- `shutdown()` 在 onDisable 调用：此时允许同步落盘（服务器在关服，无卡顿担忧），但建议仍异步并保证写入完成。

## 6. DamageCalculator —— 伤害公式（核心算法，全文照抄即可）

### 6.1 调用链

```
onDamage 事件
  → baseDamage（事件 BASE modifier；若为 0 则用玩家属性+武器+附魔+药水兜底）
  → DamageCalculator.calculateDamage(假人实体, baseDamage, 伤害类型)
  → Messages.fmtDamage(实际伤害, 实际伤害/2, precision)  → ActionBar/chat 发送
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

事件提供 `event.getDamage(DamageModifier.BASE)`（旧 API 用 try-catch 包住，抛 `NoSuchMethodError|IllegalArgumentException` 时降级 `event.getDamage()`）。若仍 ≤ 0.0001：

```
baseDamage = player.getAttribute(Attribute.ATTACK_DAMAGE).getValue()   // 1.21.4 已更名，旧名 GENERIC_ATTACK_DAMAGE 已移除
           + 锋利附魔 Sharpness × 1.25
           + 力量药水 Strength × (amplifier+1) × 3
           + 重锤坠落加成（可选，参照 mace 的坠击逻辑）
```

**1.21 注意**：`Enchantment` 用 `Enchantment.getByKey(NamespacedKey.minecraft("sharpness"))` 获取，**不要直接引用 `Enchantment.DAMAGE_ALL` 之类的旧常量**（1.20.5+ 附魔注册表化后旧常量已废弃/移除）。PotionEffect 同理用 `PotionEffectType.STRENGTH`。

## 7. DummyListener —— 事件矩阵（最复杂模块，坑最多）

### 7.1 放置 `onPlace(PlayerInteractEvent)`（F1）

- 仅 `event.getHand() == EquipmentSlot.HAND` 且 `Action.RIGHT_CLICK_BLOCK`。
- `isDummyItem(手中物品)` → `event.setCancelled(true)` → `spawnDummy(player)`。

### 7.2 伤害归零 `onAnyDamage(EntityDamageEvent)`（F7，优先级 MONITOR）

- `isDummyEntity(entity)` → `event.setDamage(0)` + `setVelocity(0,0,0)` + `setFireTicks(0)`。
- 这是总闸：任何伤害源（火焰、爆炸、摔落……）都被归零，假人不死不被击退。

### 7.3 玩家攻击 `onDamage(EntityDamageByEntityEvent)`（F2/F3，优先级 HIGHEST）

1. `isDummyEntity(entity)` 且能取到 tracker。
2. 找攻击者：`damager instanceof Player` 或 `damager instanceof Projectile proj && proj.getShooter() instanceof Player`。
3. 取 baseDamage（见 §6.4）。
4. `DamageCalculator.calculateDamage(...)` → 格式化 → 按 `notifications.mode` 发 ActionBar（用 `player.sendActionBar(String)`，**基岩版也显示**）或 chat。
5. `lastAttackTime.put(playerUuid, now)` 供空挥用。

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

### 7.6 空挥补伤害 `onSwing(PlayerAnimationEvent)`

**为什么需要**：盔甲架 `invulnerable` 时部分版本不触发 `EntityDamageByEntityEvent`（或触发但不带真实伤害），导致空手/某些攻击打假人没反馈。参考原实现用射线检测兜底（注意：纯弹射物攻击如弓箭依赖伤害事件路径，建议实服验证 Geyser/基岩端表现）：

1. 非创造模式玩家。
2. `lastAttackTime` 距今 ≥100ms（避免和真实攻击事件重复）。
3. 眼睛朝向射线：`player.getEyeLocation().getDirection()`，遍历 5 格内假人，方向点积 `dot ≥ 0.8` 且距离 ≤5。
4. 通过后（player:entity 250ms 防抖）→ 用玩家属性+武器计算 baseDamage → 走同一套显示逻辑。

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
- 每 tick 的防击退循环（主类已写）开销极小，但要保证遍历的是 `dummies.values()` 且实体有效。
- 空挥射线检测只在玩家挥臂事件触发，量小。
- **所有 Bukkit API 调用在主线程**；异步线程只做 IO（读文件、写文件）。

## 11. 交付验收清单（实现 agent 自查后打勾）

- [x] `mvn compile` 通过（JDK21 + paper-api 1.21.4 离线），零 error（warning 为文档指定的旧式 API 用法，可容忍）。
- [x] 打包 `mvn package` 产出 `target/MiragEdgeDummy.jar`。
- [x] 阅读代码自查：无未完成的 TODO/FIXME。
- [x] 逻辑自查：放置→扣物品→生成假人；攻击→ActionBar 伤害；穿甲→显示减伤后的伤害；空手取下→回背包（头顶护甲值实时刷新）；潜行+手持假人物品→收回；重启→恢复原位。
- [x] 边界自查：副手交互不双触发；空手一律取下（仅手持假人物品才可收回，杜绝潜行误触）；非主人不能 取下/穿上/收回（默认配置）；假人不被火烧/击退（受击动画为纯视觉 teleport，不破坏真实位置）。
- [x] 消息自查：全部中文、仅有白名单 ❤、前缀正确、占位符替换正确（damage 例外不加前缀）。
- [x] 对照 §2 功能清单 1-12 逐条确认（另含头顶护甲显示 / 受击动效 / kind emoji 三项增强）。

## 12. 部署说明（给汐汐酱）

- JAR 命名规范：`[M][假人]MiragEdgeDummy-1.0.0.jar`，放到 `M:\MainServer\plugins\`。
- 无需任何前置插件。首启生成 config.yml / messages.yml。
- `/dummy give <玩家> 1` 发物品；玩家右键地面放置。
- 需要重启服务端生效（本项目不处理热加载）。