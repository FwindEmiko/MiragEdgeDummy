# MiragEdgeDummy 木人桩 —— 技术报告

> 本报告记录：需求来源、反编译分析结论、方案选型决策、架构设计、风险与规避。
> 面向后续维护者与审查者，回答「为什么这样做」。

---

## 1. 背景：为什么会有这个插件

服务器在启用训练假人插件 `[训练假人]BattleTraining v1.7` 时崩溃：

```
java.lang.ArrayIndexOutOfBoundsException: Index 3 out of bounds for length 3
  at me.RaulH22.BattleTraining.a.Main.getServerVersion(Main.java:58)
```

**根因**：BattleTraining 是 1.8~1.16 时代的插件，`getServerVersion()` 按固定格式切割 `Bukkit.getVersion()` 字符串（例如 `git-Leaf-26.2-88 (MC: 1.21.x)`），Leaf 26.2 的版本串段数与其预期不符 → 数组越界。这是「老插件 × 新分支服务端」的典型版本解析不兼容，配置无法修复，必须改源码。

进一步核查作者仓库 [RaulH22/BattleTraining-SpigotPlugin](https://github.com/RaulH22/BattleTraining-SpigotPlugin)：**作者已将源码与 JAR 全部删除**（git 历史仅剩一条 "Delete BattleTraining v1.5.1.jar"，仓库只剩 README）。修复该插件的路径彻底断绝。

## 2. 替代品调研

| 候选 | 开源 | 依赖 | 结论 |
|------|------|------|------|
| **PlayerDummies v1.0.3** | 代码公开但**无 LICENSE 文件**（非标准开源） | Citizens（免费但重） | 功能最接近，但依赖重、无许可证、早期版本（作者自标 ⚠️）、0 star |
| **MythicDummies** | 开源（GPL） | 需要整套 MythicMobs | 为一个假人引 RPG 全家桶，对生电服太重；下载要 mythiccraft.io 账号 |
| **Training-Dummy 数据包** | 开源 | 零依赖 | 功能极简（只有伤害数字），无穿甲/无持久化 |
| **BattleTraining** | 曾开源 | — | 作者删库，不可用 |

## 3. PlayerDummies 反编译分析（参考实现来源）

下载官方 JAR（Modrinth v1.0.3，89KB，JDK21 编译）并用 Vineflower 反编译，得到 22 个源文件。反编译产物保留在 `F:\FCelestial\playerdummies-analysis\decomp\`。

### 3.1 架构发现

```
com.petarmc.playerDummies/
├── PlayerDummies.java        # 主类
├── Config.java               # 静态配置 + 消息（NotificationManager 抽象了 ActionBar/chat）
├── command/DummyCommand      # /dummies 命令
├── damage/DamageCalculator   # ★护甲+附魔减伤公式（自研，非调用原版）
├── dummy/DummyManager        # ★假人生命周期：放置/恢复/孤儿清理/重复清理
├── dummy/DummyTracker        # ★伤害记录 + ActionBar 显示
├── listener/DummyListener    # ★全部交互（668 行，含大量 debug 噪音）
├── npc/
│   ├── NPCWrapper            # 抽象接口（装备/皮肤/静态配置）
│   ├── ArmorStandNPCWrapper  # ★盔甲架实现（纯 Bukkit API，零反射）
│   ├── CitizensNPCWrapper    # Citizens 实现（713 行，全反射调用）
│   ├── NPCManager            # AUTO/CITIZENS/ARMORSTAND 模式切换
│   └── NPCMode
├── storage/DummyStorage      # data/<uuid>.yml 持久化
├── stats/DamageStats         # 最近伤害（PAPI 用）
└── expansion/ + petarlib/    # PAPI 扩展 + bStats 统计
```

**关键结论**：
1. **Citizens 是纯可选的**——`npc-use-npcs: false` 或未装 Citizens 时 AUTO 模式自动降级到盔甲架，盔甲架实现完全基于标准 Bukkit API，零反射、零依赖。
2. **伤害显示是 ActionBar**——基岩版（Geyser）兼容。
3. **伤害公式是作者自研的原版模拟**——不是让假人真的受伤（假人 invulnerable），而是把「攻击力 + 锋利 + 力量药水」作为基础伤害，套护甲/韧性/保护附魔公式算出「打在穿甲目标上应有的伤害」。
4. **持久化是 YAML 文件 + PDC 三重标记**（dummy/owner/id），带启动恢复 + 孤儿清理 + 重复清理，工程完整。
5. **皮肤功能调 api.mojang.com**——本服务器（中国网络）不可达，必超时，需砍掉。

### 3.2 反编译代码质量问题（选择从零写的技术理由）

| 问题 | 说明 |
|------|------|
| 变量名退化 | CFR 恢复后大量 `var1/var2`，可读性差 |
| 逻辑重复 | 两个移除处理器 `onRemove` 与 `onRemoveSneakLowest` 职责重叠 |
| 依赖包袱 | Citizens 713 行全反射（作者为避免硬依赖），bStats 统计，Mojang 皮肤 API |
| debug 噪音 | 668 行 listener 里大量 debug 分支与 `sendMessage` 调试输出 |
| 许可不明 | 无 LICENSE 文件，反编译改造后出售有法律风险 |

## 4. 方案决策：反编译改造 vs 从零自研

| 维度 | 反编译改造 | 从零自研（选定） |
|------|-----------|------------------|
| 工作量 | 恢复 + 清理 40-50%（删 Citizens/皮肤/bStats/debug） | 核心 7 类 ~2000 行 |
| 版权 | ⚠️ 无 LICENSE，自用灰区，出售踩雷 | ✅ 完全干净 |
| 环境适配 | 要吃掉 Mojang API 不可达、bStats 等副作用 | 从一开始就不带 |
| 质量 | 变量名混乱、逻辑重叠，需同步重构 | 全新 1.21 API、零反射、可维护 |
| 定制 | 英文消息要全改中文 | 天然中文 + 服务器消息约定 |

**决策：从零自研**。理由：(1) 版权完全干净，符合未来出售插件的要求；(2) 服务器环境（GitHub/Mojang API 不可达）让原插件的皮肤/bStats 功能成为废代码；(3) 反编译恢复后的清理工作量接近从零写一半，且代码更烂；(4) 可完全按服务器约定定制（全中文、灰色斜体、无 emoji、物品交互优先）。

## 5. 架构设计

### 5.1 模块划分

```
top.miragedge.dummy
├── MiragEdgeDummy.java        # 主类：装配各模块，调度恢复/防击退
├── config/ConfigManager.java  # config.yml + messages.yml 加载/重载
├── util/Messages.java         # 中文消息 + & 颜色 + {占位符} 替换 + ActionBar 格式化
├── dummy/Dummy.java           # 盔甲架实体封装（PDC 标记/静态配置/装备槽）
├── dummy/DummyManager.java    # 生命周期：物品/放置/识别/恢复/清理/保存
├── damage/DamageCalculator.java  # 原版护甲+附魔减伤公式（纯函数）
├── storage/DummyRecord.java   # 持久化记录（record）
├── storage/DummyStorage.java  # data/<uuid>.yml 存储（异步写盘）
├── listener/DummyListener.java  # 全部交互（放置/伤害/装备/取下/收回/空挥）
└── command/DummyCommand.java  # /dummy 管理命令
```

### 5.2 数据流

```
[玩家右键木人桩物品] → PlayerInteractEvent → DummyManager.spawnDummy
      → 生成 ArmorStand + PDC(dummy/owner/id) → Dummy 入 map → DummyStorage 写盘

[玩家攻击假人] → EntityDamageByEntityEvent
      → DamageCalculator.calculateDamage(装备, baseDamage, 伤害类型)
      → Messages.fmtDamage → player.sendActionBar

[右键假人] → PlayerInteractAtEntityEvent
      → 空手=取下装备 / 手持+潜行=收回 / 手持=穿装备

[重启] → restoreAll (100tick) → 异区块 getChunkAtAsync → cleanupOrphans (60tick 后)
```

### 5.3 与 PlayerDummies 的差异（刻意精简）

| 项 | PlayerDummies | MiragEdgeDummy |
|----|---------------|----------------|
| NPC 提供者 | Citizens + ArmorStand 双实现 | 纯 ArmorStand（删掉整个 npc/ 抽象层） |
| 皮肤 | Mojang API 异步拉取 | 移除（环境不可达） |
| bStats | 内置 | 移除 |
| WorldGuard | 预留（暂时禁用） | 不实现 |
| PlaceholderAPI | 扩展 %armorstanddummies_last_damage% | 暂不实现（可后续加，softdepend 已留） |
| 消息 | 英文 | 全中文 + 灰色斜体约定 |
| 模块 | DummyTracker/DamageStats/PetarLib | 合并简化，伤害显示收进 Dummy 或直接监听器内联 |

## 6. 伤害公式来源与正确性

公式完整抄自原版 Minecraft 护甲机制（与 PlayerDummies 反编译实现一致）：

```
护甲减伤:  defense = min(20, max(armor/5, armor - damage/(2 + toughness/4)))
           damage *= (1 - defense/25)
保护附魔:  每级保护 -4%，上限 20 级等效（即最多减 80%）
           类型加权: 保护×1 / 火焰·弹射·爆炸×2 / 摔落×3
```

护甲值与韧性查表（皮/锁/铁/金/钻/下界合金/海龟壳），骨架已给全。此公式与服务器其他伤害相关插件无冲突——本插件**不改任何实体真实伤害**，仅在事件里计算一个展示数值，假人本体伤害已置 0。

**验证方式（实服）**：空手打无甲假人应显示约 1-2（拳击基础 1 + 可能的属性值）；穿全套下界合金 + 保护 IV 后，钻石剑（7 基础）应显著降至 ~1-2。数值与单机打真实目标的心数一致即可认为公式正确。

## 7. 风险与规避

| 风险 | 影响 | 规避 |
|------|------|------|
| 1.21 附魔 API 变更 | 编译失败/运行报错 | 全部用 `Enchantment.getByKey(NamespacedKey.minecraft(...))`，禁旧常量 |
| 副手/双事件触发 | 交互重复或失效 | At 变体处理业务 + 非 At 变体取消 + 500ms 防抖 map |
| 区块异步加载 | Leaf AsyncCatcher 拦同步 getChunk | restoreAll 用 `getChunkAtAsync().thenAccept` |
| 主线程写盘 | 卡服（FE_PVP 已踩坑） | storage 写盘全异步，onDisable 才允许同步 |
| 基岩版显示 | ActionBar 乱码/emoji 变 ? | 中文无 emoji + sendActionBar（Geyser 支持） |
| 假人不触发伤害事件 | 空挥无反馈 | onSwing 射线兜底 + 250ms 防抖 |
| 盔甲架 invulnerable 判定 | 部分版本不触发事件 | onSwing 兜底 + onAnyDamage 总闸归零 |
| 打包/环境 | 本机 JDK 读不了 26.x paper-api | 固定 paper-api 1.21.4 + JDK21 |

## 8. 已知未做（有意为之）

1. **PAPI 扩展**（`%miragedgedummy_last_damage%`）：`softdepend: [PlaceholderAPI]` 已留，后续需要再加。
2. **WorldGuard 区域限制**：放置/收回是否受领地 flag 约束——不实现，保持简单。
3. **多个假人伤害统计排行/DPS 计时**：BattleTraining 有「训练模式计时」，本期不做，避免范围膨胀。
4. **假人装备持久化**：本期只持久化位置，**不持久化假人身上穿的装备**（重启后假人恢复但装备清空）。如需，下一版本在 DummyRecord 增加装备字段（序列化 ItemStack）。

## 9. 参考资源

- PlayerDummies 反编译源码：`F:\FCelestial\playerdummies-analysis\decomp\`（含完整的 ArmorStandNPCWrapper / DamageCalculator / DummyListener 参考）
- PlayerDummies Spigot 页面：https://www.spigotmc.org/resources/132852
- Paper API：https://jd.papermc.io/paper/1.21.4/
- Leaf（服务端）：Velocity 代理 + Leaf 26.2 后端子服，`bungee-plugin-message-channel = true`
