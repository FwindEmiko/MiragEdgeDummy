# MiragEdgeDummy 项目记忆

## 架构
- 训练假人插件：Paper/Leaf 26.2（MC 1.21.4），纯 Bukkit API，零 Citizens 依赖，兼容 Geyser 基岩版。
- 模块：MiragEdgeDummy（主类）/ config.ConfigManager / dummy.Dummy+DummyManager / damage.DamageCalculator /
  listener.DummyListener / storage.DummyStorage+DummyRecord / command.DummyCommand / npc.PlayerNpcFactory。
- 构建：JDK 25 + Maven 离线（mvn -o），paper-api 26.2.build.119-stable。产物 target/[M][训练假人]MiragEdgeDummy.jar。

## 约定/规范
- 全部消息中文，禁 emoji（唯一白名单 ❤ U+2764）；玩家向正文 &7&o 灰斜体；damage/cooldown/cps 不加全局前缀。
- 名称统一「训练假人」（不用「木人桩」）。
- 部署协作：本地修改+构建，用户自行部署测试；不执行游戏内命令。

## 关键决策
1. 真实伤害捕获：假人 setInvulnerable(false)（可受伤），让服务端产生真实 EntityDamageByEntityEvent，
   MONITOR 读取 event.getFinalDamage()（含 Aiyatsbus/EcoEnchants 等高级附魔插件对伤害的全部修改）。
   玩家攻击：setDamage(显示伤害) 真实掉血（掉血量=显示值）；伤害≥当前生命→setDamage(0)+原地满血重生（不 cancel，保留冷却重置）。
   非玩家伤害（火/摔落/爆炸）归零；横扫(SWEEP_ATTACK)跳过（其冷却已重置恒 4%~11%，会覆盖主攻击正确显示）。
2. 官方 NPC 优先（world.spawn(loc, Player.class, ...)，EntityType.PLAYER.isSpawnable）→ NMS 反射兜底 → 盔甲架回退。
3. 皮肤解析与 NPC 生成解耦：皮肤失败绝不影响 NPC 本体。
4. 回弹：弹簧-阻尼（a=-k*disp 离锚点越远拉力越大，vel=(vel+a)*damp，disp+=vel，每 tick teleport(anchor+disp)）。
   **真实击退值反馈**：下一 tick 读服务端施加的真实击退速度 × REAL_KNOCKBACK_SCALE(4.0) 换算位移覆盖（上限 2.5 格），
   无真实击退时用保底冲量。收敛(|disp|²<1e-5 且 |vel|²<1e-5)或 60tick 强制归位。
5. 浮动伤害数字：TextDisplay 固定实体位置 + setTransformation(translation) + setInterpolationDuration(4) 客户端插值；
   关键帧数=life/interval-1（保证最后一帧被应用，否则截断）。
6. 假人生命值：物品 PDC hp 键（/dummy give [生命] 参数）+ DummyRecord.hp 持久化 + Dummy.maxHp；
   configureStatic 用 maxHp 设 Attribute.MAX_HEALTH；击杀原地重生=同一实体 setHealth(max)+teleport(anchor)（装备/皮肤保留）。
7. PVP 大厅权限：编辑（穿/取装备、收回）仅 miragedgedummy.admin；普通玩家只能攻击。头顶名称显示 护甲/韧/生命（真实 Attribute 值）。

## 模块/组件
- npc.PlayerNpcFactory：NMS 反射假玩家（1.21.4 配方，参考 FancyNPCs implementation_26_2 / Marallyzen FakePlayerEntity）。
- DummyManager：recoilTicks/recoilDisp/recoilVel 三 map（弹簧状态）；textDisplays 集合（PDC damage-text 标记）。
- DummyListener：lastPickup/lastCpsTick/lastInteractTick 防抖；onAnyDamage(MONITOR) 统一伤害闸门。
- ConfigManager.upgradeYaml：旧配置文件自动补缺键（版本标记 config-auto-upgraded，同版本只补一次，不覆盖已有键）。
- DummyStorage：saveRecord 异步 / saveRecordSync 同步（onDisable 用，禁用后注册任务会抛 IllegalPluginAccessException）。

## 踩坑记录
1. Class.getConstructor 要求【精确声明类型】：ServerPlayer 构造声明 (MinecraftServer, ServerLevel, GameProfile, ClientInformation)，
   用 nmsServer.getClass()（DedicatedServer 运行时子类）查找必失败（NoSuchMethodException）。必须 Class.forName 声明类型。
2. Paper 1.21.4 的 CommonListenerCookie 是 6 参 record（Paper patch 加 clientBrand+channels），4 参构造不存在，
   用静态工厂 CommonListenerCookie.createInitial(GameProfile, boolean)。
3. ServerPlayer 必须有伪造 connection（Connection(PacketFlow.CLIENTBOUND)+EmbeddedChannel 填 channel 字段+
   ServerGamePacketListenerImpl），否则每 tick connection.tickClientLoadTimeout() 空指针。
4. ClientInformation 在 net.minecraft.server.level 包（不是 .server.network）；createDefault() 静态工厂。
5. 皮肤层字节 DATA_PLAYER_MODE_CUSTOMISATION id=17 设 127，否则皮肤无外层贴图。
6. GameProfile 纹理写入要用 put(String, Property) 签名（PropertyMap 自有方法），put(Object,Object) 写不进。
7. TextDisplay.setText(String) 是旧 API；26.2 有 text(Component) 用后者；setRemoveWhenFarAway 是 Mob 方法 TextDisplay 没有。
8. Player.isOnGround() 弃用（since 1.16.1）；setMaxHealth 弃用用 Attribute.MAX_HEALTH.setBaseValue；sendActionBar(String) 弃用
   用 LegacyComponentSerializer.legacySection().deserialize + sendActionBar(Component)。
9. EntityType.PLAYER.isSpawnable() 在 Leaf 26.2-63-alpha 返回 false（官方 NPC 在该构建不可用），日志会显示「改用 NMS 反射方案」。
10. 蓄力% 只对近战显示（弓箭命中时显示近战冷却会误导）；getAttackCooldown()=蓄力进度 0..1（0=刚攻击）。
11. CPS 显示依赖 messages.cps 键——旧 messages.yml 缺键时 raw() 返回空串（看起来「没显示」），靠配置自动升级补全。
12. 子代理审查模式：后台 subagent 易因上下文不足失败，用聚焦小 prompt（只读指定文件+指定问题）成功率更高。

## 进行中的工作
- 当前等待用户实测：NMS 反射假玩家（步骤诊断日志：失败会显示「步骤 X.xxx」）+ 生命值/击杀重生/管理员权限门禁。
- 官方 NPC API 在用户 Leaf 26.2-63-alpha 不可用（isSpawnable=false），NMS 反射是唯一途径，失败回退盔甲架。
- 审查子代理报告 7 项已处理：真实击退捕获移入 tickDummies 首帧（原 runTask 与清零竞争=死代码）；
  名称刷新 120ms 节流；放置/编辑/收回统一 miragedgedummy.admin 门禁；respawn 回满配置 maxHp 并重置属性 base；
  saveAll 存配置基础名（动态 HP 后缀不进存储）；MONITOR 同级插件顺序约束属可接受（文档说明）。
