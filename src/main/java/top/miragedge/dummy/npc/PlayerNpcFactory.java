package top.miragedge.dummy.npc;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.CreatureSpawnEvent;
import top.miragedge.dummy.MiragEdgeDummy;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;

/**
 * 假人玩家实体工厂 —— 两层方案生成真实玩家 NPC：
 *
 * <ol>
 *   <li><b>官方 API</b>（Paper 26.2 起）：{@code world.spawn(loc, Player.class, ...)}
 *       —— 官方处理「不在玩家列表 / 不占 Tab / 不被存档 / 可被攻击」，最优先；</li>
 *   <li><b>NMS 反射</b>兜底（官方 API 不可用时，如某些 Leaf 构建）：按 1.21.4 Mojang 映射
 *       反射生成 {@code ServerPlayer} 并加入世界——真 Player 实体；</li>
 *   <li>两者都失败 → 返回 null，调用方回退盔甲架。</li>
 * </ol>
 *
 * <p>NMS 反射配方（参考 FancyNPCs implementation_26_2 与 Marallyzen FakePlayerEntity 开源实现）：</p>
 * <ol>
 *   <li>构造：{@code new ServerPlayer(MinecraftServer, ServerLevel, GameProfile, ClientInformation.createDefault())}
 *       —— 1.21.4 为 4 参构造，ClientInformation 位于 {@code net.minecraft.server.level} 包；</li>
 *   <li><b>伪造网络连接</b>：{@code Connection(PacketFlow.CLIENTBOUND)} +
 *       {@code CommonListenerCookie} + {@code ServerGamePacketListenerImpl}，写入 ServerPlayer 的
 *       {@code connection} 字段——否则实体每 tick 的 {@code tickClientLoadTimeout()} 空指针；</li>
 *   <li>皮肤：GameProfile textures 属性（{@code put(String, Property)} 签名）+ 皮肤层字节 127
 *       （DATA_PLAYER_MODE_CUSTOMISATION id=17，不设则皮肤无外层贴图）；</li>
 *   <li>{@code ServerLevel.addFreshEntity(entity)} 加入世界。</li>
 * </ol>
 *
 * <p><b>关键：皮肤解析与 NPC 生成解耦</b>——皮肤解析失败不影响 NPC 本体生成。</p>
 */
public final class PlayerNpcFactory {

    /** 皮肤配置缓存：skinName(lower) -> 完整 PlayerProfile（含 textures），避免每次放置重复回源 */
    private static final java.util.Map<String, PlayerProfile> SKIN_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    private PlayerNpcFactory() {}

    /**
     * 解析皮肤配置（含纹理）：
     * <ol>
     *   <li>内存缓存命中直接返回；</li>
     *   <li>本地配置缓存（completeFromCache(true, false)，true=必须带纹理）；</li>
     *   <li>在线回源（complete(true, true)：textures + 在线模式，走 Mojang 会话服务器，
     *       服务器本身离线模式下此调用仍会访问 Mojang API 拉纹理）。</li>
     * </ol>
     * 主线程同步调用（放置假人是低频管理员操作；首次约 1~3s，之后走缓存即时返回）。
     */
    private static PlayerProfile resolveSkinProfile(String skinName) {
        if (skinName == null || skinName.isBlank()) {
            return null;
        }
        String key = skinName.toLowerCase(java.util.Locale.ROOT);
        PlayerProfile cached = SKIN_CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        PlayerProfile pp = Bukkit.createProfile(skinName);
        if (pp == null) {
            return null;
        }
        // 1) 本地缓存（textures=true）
        try {
            if (pp.completeFromCache(true, false) && pp.hasTextures()) {
                SKIN_CACHE.put(key, pp);
                return pp;
            }
        } catch (Exception ignored) {
        }
        // 2) 在线回源（Mojang API）
        try {
            if (pp.complete(true, true) && pp.hasTextures()) {
                SKIN_CACHE.put(key, pp);
                return pp;
            }
        } catch (Exception ignored) {
        }
        // 3) 离线兜底
        try {
            if (pp.complete(true, false) && pp.hasTextures()) {
                SKIN_CACHE.put(key, pp);
                return pp;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * 在世界中生成一个玩家 NPC 假人（官方 API → NMS 反射）。
     *
     * @return Bukkit {@link Player} 实体；两层方案都失败时返回 null（调用方回退盔甲架）
     */
    public static Player spawn(World world, Location location, String skinName) {
        // ---- 1. 官方 API ----
        Player official = spawnOfficial(world, location);
        if (official != null) {
            broadcastSpawnPackets(official);
            applySkin(official, skinName);
            return official;
        }
        // ---- 2. NMS 反射兜底 ----
        Player reflected = spawnReflected(world, location, skinName);
        if (reflected != null) {
            return reflected;
        }
        return null;
    }

    /**
     * 官方 API：EntityType.PLAYER 26.2 起可生成（生成的是 NPC 假人，非在线玩家）。
     */
    private static Player spawnOfficial(World world, Location location) {
        // isSpawnable() 在某些构建上返回 false 但 spawn 仍可用，因此不做短路判断，直接尝试
        try {
            Player npc = world.spawn(location, Player.class, CreatureSpawnEvent.SpawnReason.CUSTOM, false, null);
            if (npc != null) {
                return npc;
            }
        } catch (Exception e) {
            MiragEdgeDummy.getInstance().getLogger()
                    .log(Level.INFO, "官方玩家 NPC 生成不可用（" + e.getMessage() + "），改用 NMS 反射方案");
        }
        return null;
    }

    /**
     * NMS 反射兜底：按 1.21.4 Mojang 映射生成 ServerPlayer 并加入世界（不在玩家列表、不被存档）。
     */
    private static Player spawnReflected(World world, Location location, String skinName) {
        String stage = "1.handle";
        try {
            // ---- 1. NMS handle 与声明类型（Class.getConstructor 要求精确声明类型，
            //      运行时子类 DedicatedServer 不匹配构造函数声明的 MinecraftServer） ----
            Class<?> minecraftServerClass = Class.forName("net.minecraft.server.MinecraftServer");
            Object nmsServer;
            try {
                // 首选静态 MinecraftServer.getServer()
                nmsServer = minecraftServerClass.getMethod("getServer").invoke(null);
            } catch (Exception noStatic) {
                // 兜底：CraftServer.getServer()
                nmsServer = Bukkit.getServer().getClass().getMethod("getServer").invoke(Bukkit.getServer());
            }
            Object nmsWorld = world.getClass().getMethod("getHandle").invoke(world);
            Class<?> serverLevelClass = Class.forName("net.minecraft.server.level.ServerLevel");
            Class<?> serverPlayerClass = Class.forName("net.minecraft.server.level.ServerPlayer");
            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");

            stage = "2.profile";
            // ---- 2. GameProfile：随机 UUID（不占真实玩家身份）+ 皮肤纹理 ----
            String displayName = (skinName != null && !skinName.isBlank()) ? skinName.trim() : "Dummy";
            Object gameProfile = gameProfileClass.getConstructor(UUID.class, String.class)
                    .newInstance(UUID.randomUUID(), displayName);
            // 皮肤纹理（best effort；失败不影响生成）。
            // 注意：completeFromCache(false) 的 false = 不加载纹理（旧 bug 根因！），必须传 true。
            if (skinName != null && !skinName.isBlank()) {
                try {
                    PlayerProfile pp = resolveSkinProfile(skinName);
                    if (pp != null && pp.hasTextures() && pp.getProperties() != null) {
                        Object properties = invokeProfileAccessor(gameProfile, "properties", "getProperties");
                        Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
                        Constructor<?> propCtor = propertyClass.getConstructor(
                                String.class, String.class, String.class);
                        Method putMethod;
                        try {
                            putMethod = properties.getClass().getMethod("put", String.class, propertyClass);
                        } catch (NoSuchMethodException noPut) {
                            putMethod = properties.getClass().getMethod("put", Object.class, Object.class);
                        }
                        for (ProfileProperty prop : pp.getProperties()) {
                            Object property = propCtor.newInstance(
                                    prop.getName(), prop.getValue(), prop.getSignature());
                            putMethod.invoke(properties, "textures", property);
                        }
                    }
                } catch (Exception ignored) {
                    // 皮肤失败不影响生成
                }
            }

            stage = "3.clientinfo";
            // ---- 3. ClientInformation.createDefault()（双包名兜底） ----
            Class<?> ciClass = null;
            Object clientInformation = null;
            for (String ciPackage : new String[]{
                    "net.minecraft.server.level.ClientInformation",
                    "net.minecraft.server.network.ClientInformation"}) {
                try {
                    ciClass = Class.forName(ciPackage);
                    clientInformation = ciClass.getMethod("createDefault").invoke(null);
                    break;
                } catch (Exception ignored) {
                    ciClass = null;
                    clientInformation = null;
                }
            }

            stage = "4.construct";
            // ---- 4. ServerPlayer 构造：4 参（1.21.4+）→ 3 参（旧版） ----
            Object serverPlayer;
            if (ciClass != null && clientInformation != null) {
                Constructor<?> spCtor = serverPlayerClass.getConstructor(
                        minecraftServerClass, serverLevelClass, gameProfileClass, ciClass);
                serverPlayer = spCtor.newInstance(nmsServer, nmsWorld, gameProfile, clientInformation);
            } else {
                Constructor<?> spCtor = serverPlayerClass.getConstructor(
                        minecraftServerClass, serverLevelClass, gameProfileClass);
                serverPlayer = spCtor.newInstance(nmsServer, nmsWorld, gameProfile);
            }

            // ---- 4b. 防御性：构造后再把 gameProfile 写回字段（FancyNPCs 同款做法，
            //       个别版本构造函数内部不会把传入 profile 存入字段，导致皮肤不生效） ----
            try {
                java.lang.reflect.Field gpField = serverPlayerClass.getField("gameProfile");
                gpField.set(serverPlayer, gameProfile);
            } catch (Exception ignored) {
                // 字段不存在/不可写则依赖构造函数本身
            }

            stage = "5.fakeconn";
            // ---- 5. 伪造网络连接（防 ServerPlayer.tick 的 connection.tickClientLoadTimeout 空指针） ----
            //      仅 1.20.5+（有 ClientInformation）的 4 参构造路径需要；旧版 3 参路径跳过。
            if (ciClass != null && clientInformation != null) {
                Class<?> packetFlowClass = Class.forName("net.minecraft.network.protocol.PacketFlow");
                Object clientbound = packetFlowClass.getField("CLIENTBOUND").get(null);
                Class<?> connectionClass = Class.forName("net.minecraft.network.Connection");
                Object fakeConnection = connectionClass.getConstructor(packetFlowClass).newInstance(clientbound);
                // 用 netty EmbeddedChannel 填充私有 channel 字段（实体移除/断开时会调用 channel.close，
                // 空 channel 会 NPE——Marallyzen 的 FakeNetworkManagerImpl 同款处理）
                try {
                    java.lang.reflect.Field channelField = connectionClass.getDeclaredField("channel");
                    channelField.setAccessible(true);
                    Object channel = Class.forName("io.netty.channel.embedded.EmbeddedChannel")
                            .getConstructor().newInstance();
                    channelField.set(fakeConnection, channel);
                } catch (Exception ignored) {
                    // channel 注入失败不阻塞（移除实体时可能有风险，但生成仍继续）
                }
                // Paper 1.21.4 的 CommonListenerCookie 是 6 参 record（Paper patch 加了 clientBrand+channels），
                // 4 参构造不存在——用官方静态工厂 createInitial(GameProfile, boolean) 创建
                Class<?> cookieClass = Class.forName("net.minecraft.server.network.CommonListenerCookie");
                Object cookie;
                try {
                    cookie = cookieClass.getMethod("createInitial", gameProfileClass, boolean.class)
                            .invoke(null, gameProfile, false);
                } catch (NoSuchMethodException noFactory) {
                    // 兜底：直接 4 参构造（旧版本或未打 patch 的服务端）
                    cookie = cookieClass.getConstructor(gameProfileClass, int.class, ciClass, boolean.class)
                            .newInstance(gameProfile, 0, clientInformation, false);
                }
                Class<?> listenerClass = Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl");
                Object listener = listenerClass.getConstructor(
                        minecraftServerClass, connectionClass, serverPlayerClass, cookieClass)
                        .newInstance(nmsServer, fakeConnection, serverPlayer, cookie);
                serverPlayerClass.getField("connection").set(serverPlayer, listener);
            }

            stage = "6.skinlayers";
            // ---- 6. 皮肤层字节 127（DATA_PLAYER_MODE_CUSTOMISATION id=17，全部皮肤层可见） ----
            try {
                Object entityData = serverPlayerClass.getMethod("getEntityData").invoke(serverPlayer);
                Class<?> accessorClass = Class.forName("net.minecraft.network.sync.EntityDataAccessor");
                Class<?> serializerClass = Class.forName("net.minecraft.network.sync.EntityDataSerializer");
                Object byteSerializer = Class.forName("net.minecraft.network.sync.EntityDataSerializers")
                        .getField("BYTE").get(null);
                Object accessor = accessorClass.getConstructor(int.class, serializerClass)
                        .newInstance(17, byteSerializer);
                entityData.getClass().getMethod("set", accessorClass, Object.class)
                        .invoke(entityData, accessor, (byte) 127);
            } catch (Exception ignored) {
                // 皮肤层字节失败不影响生成（只是少外层贴图）
            }

            stage = "7.addworld";
            // ---- 7. 位置 + 加入世界 ----
            serverPlayerClass.getMethod("setPos", double.class, double.class, double.class)
                    .invoke(serverPlayer, location.getX(), location.getY(), location.getZ());
            serverPlayerClass.getMethod("setYRot", float.class).invoke(serverPlayer, location.getYaw());
            serverPlayerClass.getMethod("setXRot", float.class).invoke(serverPlayer, location.getPitch());
            Class<?> entityClass = Class.forName("net.minecraft.world.entity.Entity");
            boolean added = false;
            serverLevelClass.getMethod("addFreshEntity", entityClass).invoke(nmsWorld, serverPlayer);
            added = true;

            stage = "8.bukkit";
            // ---- 8. 取 Bukkit Player ----
            Entity bukkitEntity = (Entity) serverPlayerClass.getMethod("getBukkitEntity").invoke(serverPlayer);
            if (bukkitEntity instanceof Player player) {
                // 9. 手动广播完整生成包（信息包 + 生成包 + 元数据）——不依赖实体追踪器
                stage = "9.spawnpackets";
                broadcastSpawnPackets(player);
                return player;
            }
            if (bukkitEntity != null) {
                removeEntity(bukkitEntity);
            } else if (added) {
                serverPlayerClass.getMethod("discard").invoke(serverPlayer);
            }
            return null;
        } catch (Exception e) {
            MiragEdgeDummy.getInstance().getLogger()
                    .log(Level.WARNING, "NMS 反射生成玩家 NPC 失败（步骤 " + stage + "），将回退盔甲架: " + e.getMessage());
            return null;
        }
    }

    // ============ 通用工具 ============

    /**
     * authlib 7/9 的 GameProfile 是 record（访问器 id()/name()/properties()），
     * 旧版（authlib 3.x）是 getId()/getProperties()。按顺序尝试多个方法名，找到谁用谁。
     */
    private static Object invokeProfileAccessor(Object profile, String... names) throws Exception {
        Exception last = null;
        for (String name : names) {
            try {
                return profile.getClass().getMethod(name).invoke(profile);
            } catch (NoSuchMethodException e) {
                last = e;
            }
        }
        throw new NoSuchMethodException("GameProfile 缺少访问器: " + String.join("/", names) + " -> " + last);
    }

    /**
     * 安全移除假人实体：Player 类型（玩家 NPC）走 NMS discard()——Bukkit 的
     * CraftPlayer.remove() 会抛 UnsupportedOperationException（要求 kickPlayer）；
     * 其他实体走普通 remove()。
     */
    public static void removeEntity(Entity entity) {
        if (entity == null) {
            return;
        }
        if (entity instanceof Player player) {
            try {
                Object handle = player.getClass().getMethod("getHandle").invoke(player);
                handle.getClass().getMethod("discard").invoke(handle);
                return;
            } catch (Exception ignored) {
                // discard 失败则尝试普通 remove（可能再抛，交给调用方容错）
            }
        }
        try {
            entity.remove();
        } catch (UnsupportedOperationException ignored) {
            // 玩家实体兜底失败：不再重复抛（调用方已保证后续状态一致）
        }
    }

    // ============ 手动渲染包广播（FancyNpcs 26.2 同款，不依赖实体追踪器） ============

    /**
     * 向所有在线玩家发送假人的完整生成包序列：玩家信息包（皮肤档案）+
     * ClientboundAddEntityPacket（生成）+ ClientboundSetEntityDataPacket（元数据：皮肤层/自定义名）。
     * 即使服务端实体追踪器不广播（Leaf 26.2 + Moonrise 下假玩家可能不被追踪），
     * 客户端也能完整渲染假人——参考 FancyNpcs implementation_26_2 的 per-viewer 发包方案。
     */
    public static void broadcastSpawnPackets(Player npc) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            sendSpawnPacketsTo(npc, viewer);
        }
    }

    /**
     * 向单个客户端发送假人完整生成包序列。
     */
    public static void sendSpawnPacketsTo(Player npc, Player viewer) {
        try {
            Object info = buildProfileInfoPacket(npc);
            if (info != null) {
                sendPacketTo(viewer, info);
            }
            // 摘除 Tab（26.2 下 listed=false 的 ADD_PLAYER 仍可能短暂入列，显式 updateListed(false)）
            Object unlist = buildUnlistPacket(npc);
            if (unlist != null) {
                sendPacketTo(viewer, unlist);
            }
            Object spawn = buildAddEntityPacket(npc);
            if (spawn != null) {
                sendPacketTo(viewer, spawn);
            }
            Object meta = buildMetadataPacket(npc);
            if (meta != null) {
                sendPacketTo(viewer, meta);
            }
        } catch (Exception e) {
            MiragEdgeDummy.getInstance().getLogger()
                    .log(Level.WARNING, "广播假人生成包失败（客户端可能看不到假人）: " + e.getMessage());
        }
    }

    /**
     * 皮肤/元数据刷新（皮肤解析完成后调用）：只重发信息包与元数据，不重新生成实体。
     */
    public static void broadcastSkinRefresh(Player npc) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            try {
                Object info = buildProfileInfoPacket(npc);
                if (info != null) {
                    sendPacketTo(viewer, info);
                }
                Object meta = buildMetadataPacket(npc);
                if (meta != null) {
                    sendPacketTo(viewer, meta);
                }
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 名字标签更新（玩家实体的头顶名字来自 GameProfile 名而非 metadata 自定义名）：
     * 重建 GameProfile（保留 UUID 与皮肤纹理、名字换成显示名）→ 写回 gameProfile/listName 字段
     * → 广播 UPDATE_DISPLAY_NAME 信息包 + 元数据。头部显示即假人护甲/生命数据。
     */
    public static void broadcastNameUpdate(Player npc, String legacyName) {
        try {
            Object handle = npc.getClass().getMethod("getHandle").invoke(npc);
            // listName（头顶/列表显示名）：Adventure → NMS Component
            try {
                net.kyori.adventure.text.Component adv =
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacySection().deserialize(legacyName);
                Class<?> paperAdventure = Class.forName("io.papermc.paper.adventure.PaperAdventure");
                Object nmsComponent = paperAdventure
                        .getMethod("asVanilla", net.kyori.adventure.text.Component.class)
                        .invoke(null, adv);
                java.lang.reflect.Field listNameField = handle.getClass().getField("listName");
                listNameField.set(handle, nmsComponent);
            } catch (Exception ignored) {
            }
            // 广播：Team 包（头顶名） + UPDATE_DISPLAY_NAME 信息包 + 摘除 Tab + 元数据
            broadcastTeamPacket(npc, legacyName);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                try {
                    Object info = buildProfileInfoPacket(npc, legacyName);
                    if (info != null) {
                        sendPacketTo(viewer, info);
                    }
                    Object unlist = buildUnlistPacket(npc);
                    if (unlist != null) {
                        sendPacketTo(viewer, unlist);
                    }
                    Object meta = buildMetadataPacket(npc);
                    if (meta != null) {
                        sendPacketTo(viewer, meta);
                    }
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            MiragEdgeDummy.getInstance().getLogger()
                    .log(Level.WARNING, "更新假人名字标签失败: " + e.getMessage());
        }
    }

    /**
     * 构造并发送 Team 包（FancyNpcs 同款头顶名字方案）：team prefix = 显示名，
     * nameTagVisibility=ALWAYS、collision=NEVER、成员=profile 名。
     * 玩家实体头顶标签来自 Team，而非 metadata 自定义名。
     */
    public static void broadcastTeamPacket(Player npc, String legacyName) {
        Object packet = buildTeamPacket(npc, legacyName);
        if (packet == null) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            try {
                sendPacketTo(viewer, packet);
            } catch (Exception ignored) {
            }
        }
    }

    /** 向单个客户端发送 Team 包（玩家进服补发头顶名字） */
    public static void sendTeamPacketTo(Player npc, Player viewer, String legacyName) {
        Object packet = buildTeamPacket(npc, legacyName);
        if (packet == null) {
            return;
        }
        try {
            sendPacketTo(viewer, packet);
        } catch (Exception ignored) {
        }
    }

    /**
     * 构造 ClientboundSetPlayerTeamPacket（FancyNpcs 同款头顶名字方案）：
     * team prefix = 显示名，nameTagVisibility=ALWAYS、collision=NEVER、成员=profile 名。
     */
    private static Object buildTeamPacket(Player npc, String legacyName) {
        try {
            Object handle = npc.getClass().getMethod("getHandle").invoke(npc);
            Class<?> componentClass = Class.forName("net.minecraft.network.chat.Component");
            Class<?> scoreboardClass = Class.forName("net.minecraft.world.scores.Scoreboard");
            Class<?> teamClass = Class.forName("net.minecraft.world.scores.PlayerTeam");
            Class<?> visibilityClass = Class.forName("net.minecraft.world.scores.Team$Visibility");
            Class<?> collisionClass = Class.forName("net.minecraft.world.scores.Team$CollisionRule");

            Object scoreboard = scoreboardClass.getConstructor().newInstance();
            String teamName = "dummy-" + npc.getUniqueId().toString().substring(0, 8);
            Object team = teamClass.getConstructor(scoreboardClass, String.class)
                    .newInstance(scoreboard, teamName);

            // 成员：profile 名（客户端按名字匹配实体）
            Object profile = handle.getClass().getField("gameProfile").get(handle);
            String profileName = profile == null ? npc.getName() : (String) invokeProfileAccessor(profile, "name", "getName");
            java.util.Collection<String> players = (java.util.Collection<String>) teamClass.getMethod("getPlayers").invoke(team);
            players.clear();
            if (profileName != null) {
                players.add(profileName);
            }

            // prefix = 显示名（护甲/生命数据）
            net.kyori.adventure.text.Component adv =
                    net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                            .legacySection().deserialize(legacyName);
            Class<?> paperAdventure = Class.forName("io.papermc.paper.adventure.PaperAdventure");
            Object prefix = paperAdventure
                    .getMethod("asVanilla", net.kyori.adventure.text.Component.class)
                    .invoke(null, adv);
            teamClass.getMethod("setPlayerPrefix", componentClass).invoke(team, prefix);

            // 显示规则
            Object always = null;
            for (Object c : (Object[]) visibilityClass.getMethod("values").invoke(null)) {
                if ("ALWAYS".equals(c.toString())) { always = c; break; }
            }
            if (always != null) {
                teamClass.getMethod("setNameTagVisibility", visibilityClass).invoke(team, always);
            }
            Object never = null;
            for (Object c : (Object[]) collisionClass.getMethod("values").invoke(null)) {
                if ("NEVER".equals(c.toString())) { never = c; break; }
            }
            if (never != null) {
                teamClass.getMethod("setCollisionRule", collisionClass).invoke(team, never);
            }

            // 发包：createAddOrModifyPacket(team, false)
            Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket");
            return packetClass.getMethod("createAddOrModifyPacket", teamClass, boolean.class)
                    .invoke(null, team, false);
        } catch (Exception e) {
            MiragEdgeDummy.getInstance().getLogger()
                    .log(Level.WARNING, "构建假人 Team 包失败（头顶名字可能不显示）: " + e.getMessage());
            return null;
        }
    }

    /**
     * 构造 updateListed(uuid, false) 信息包：强制把假人从 Tab 玩家列表摘除（26.2 静态工厂）。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object buildUnlistPacket(Player npc) throws Exception {
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
        try {
            return packetClass.getMethod("updateListed", UUID.class, boolean.class)
                    .invoke(null, npc.getUniqueId(), false);
        } catch (NoSuchMethodException noFactory) {
            // 旧版本兜底：EnumSet(UPDATE_LISTED) + Entry(UUID, false)
            Class<?> actionClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");
            Class<?> entryClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry");
            Object updateListed = null;
            for (Object constant : (Object[]) actionClass.getMethod("values").invoke(null)) {
                if ("UPDATE_LISTED".equals(constant.toString())) {
                    updateListed = constant;
                    break;
                }
            }
            if (updateListed == null) {
                return null;
            }
            Constructor<?> entryCtor = null;
            for (Constructor<?> c : entryClass.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 2 && p[0] == UUID.class && p[1] == boolean.class) {
                    entryCtor = c;
                    break;
                }
            }
            if (entryCtor == null) {
                return null;
            }
            Object entry = entryCtor.newInstance(npc.getUniqueId(), false);
            Constructor<?> packetCtor = null;
            for (Constructor<?> c : packetClass.getConstructors()) {
                Class<?>[] p = c.getParameterTypes();
                if (p.length == 2 && p[0] == EnumSet.class && p[1] == List.class) {
                    packetCtor = c;
                    break;
                }
            }
            if (packetCtor == null) {
                return null;
            }
            return packetCtor.newInstance(EnumSet.of((Enum) updateListed), List.of(entry));
        }
    }

    /**
     * 手动广播假人瞬移包（物理回弹期间实体 teleport 不被追踪器同步时的兜底）。
     */
    public static void broadcastTeleport(Player npc) {
        try {
            Location loc = npc.getLocation();
            Class<?> vecClass = Class.forName("net.minecraft.world.phys.Vec3");
            Object pos = vecClass.getConstructor(double.class, double.class, double.class)
                    .newInstance(loc.getX(), loc.getY(), loc.getZ());
            Object delta = vecClass.getField("ZERO").get(null);
            Class<?> pmrClass = Class.forName("net.minecraft.world.entity.PositionMoveRotation");
            Object pmr = pmrClass.getConstructor(vecClass, vecClass, float.class, float.class)
                    .newInstance(pos, delta, loc.getYaw(), loc.getPitch());
            Class<?> tpClass = Class.forName("net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket");
            Object packet = tpClass.getConstructor(int.class, pmrClass, java.util.Set.class, boolean.class)
                    .newInstance(npc.getEntityId(), pmr, java.util.Set.of(), false);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                try {
                    sendPacketTo(viewer, packet);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 手动广播假人装备包（穿/取装备后调用）。
     */
    public static void broadcastEquipment(Player npc) {
        try {
            Class<?> craftItemStack = Class.forName("org.bukkit.craftbukkit.inventory.CraftItemStack");
            java.lang.reflect.Method asNMSCopy = craftItemStack.getMethod("asNMSCopy", org.bukkit.inventory.ItemStack.class);
            // 26.2 把 CraftEquipmentSlot 从 inventory 子包移到了 craftbukkit 根包（javap 核验）
            Class<?> craftEquipSlot;
            try {
                craftEquipSlot = Class.forName("org.bukkit.craftbukkit.CraftEquipmentSlot");
            } catch (ClassNotFoundException notRoot) {
                craftEquipSlot = Class.forName("org.bukkit.craftbukkit.inventory.CraftEquipmentSlot");
            }
            java.lang.reflect.Method getNMS = craftEquipSlot.getMethod("getNMS", org.bukkit.inventory.EquipmentSlot.class);
            Class<?> pairClass = Class.forName("com.mojang.datafixers.util.Pair");
            java.lang.reflect.Method pairOf = pairClass.getMethod("of", Object.class, Object.class);

            org.bukkit.inventory.EntityEquipment eq = npc.getEquipment();
            java.util.List<Object> pairs = new java.util.ArrayList<>();
            if (eq != null) {
                for (org.bukkit.inventory.EquipmentSlot slot : org.bukkit.inventory.EquipmentSlot.values()) {
                    org.bukkit.inventory.ItemStack item = eq.getItem(slot);
                    if (item == null) {
                        item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.AIR);
                    }
                    Object nmsSlot = getNMS.invoke(null, slot);
                    Object nmsItem = asNMSCopy.invoke(null, item);
                    pairs.add(pairOf.invoke(null, nmsSlot, nmsItem));
                }
            }
            Class<?> eqPacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket");
            Object packet = eqPacketClass.getConstructor(int.class, java.util.List.class)
                    .newInstance(npc.getEntityId(), pairs);
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                try {
                    sendPacketTo(viewer, packet);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 手动广播移除实体包（收回/管理员删除假人后调用，兜底追踪器不广播的场景）。
     */
    public static void broadcastRemovePackets(Player npc) {
        try {
            Class<?> rmClass = Class.forName("net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket");
            Object packet = rmClass.getConstructor(int[].class)
                    .newInstance(new Object[]{new int[]{npc.getEntityId()}});
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                try {
                    sendPacketTo(viewer, packet);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
        broadcastProfileRemove(npc);
    }

    /**
     * 构造 ClientboundAddEntityPacket（11 参，实测 leaf-26.2-63 与 1.21.5 一致）。
     */
    private static Object buildAddEntityPacket(Player npc) throws Exception {
        Object handle = npc.getClass().getMethod("getHandle").invoke(npc);
        Class<?> addPacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundAddEntityPacket");
        Class<?> entityTypeClass = Class.forName("net.minecraft.world.entity.EntityType");
        Class<?> vecClass = Class.forName("net.minecraft.world.phys.Vec3");
        Object zero = vecClass.getField("ZERO").get(null);
        Object type = handle.getClass().getMethod("getType").invoke(handle);
        Location loc = npc.getLocation();
        java.lang.reflect.Constructor<?> ctor = addPacketClass.getConstructor(
                int.class, UUID.class, double.class, double.class, double.class,
                float.class, float.class, entityTypeClass, int.class, vecClass, double.class);
        return ctor.newInstance(npc.getEntityId(), npc.getUniqueId(),
                loc.getX(), loc.getY(), loc.getZ(),
                loc.getPitch(), loc.getYaw(), type, 0, zero, (double) loc.getYaw());
    }

    /**
     * 构造 ClientboundSetEntityDataPacket：取实体 SynchedEntityData 的 itemsById 全量打包。
     */
    private static Object buildMetadataPacket(Player npc) throws Exception {
        Object handle = npc.getClass().getMethod("getHandle").invoke(npc);
        Object entityData = handle.getClass().getMethod("getEntityData").invoke(handle);
        java.lang.reflect.Field itemsById = entityData.getClass().getDeclaredField("itemsById");
        itemsById.setAccessible(true);
        Object[] items = (Object[]) itemsById.get(entityData);
        java.util.List<Object> values = new java.util.ArrayList<>();
        if (items != null) {
            for (Object item : items) {
                if (item == null) {
                    continue;
                }
                try {
                    values.add(item.getClass().getMethod("value").invoke(item));
                } catch (Exception ignored) {
                }
            }
        }
        Class<?> metaPacketClass = Class.forName("net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket");
        return metaPacketClass.getConstructor(int.class, java.util.List.class)
                .newInstance(npc.getEntityId(), values);
    }

    // ============ 玩家信息包广播（1.20.5+ 客户端渲染玩家实体必需） ============

    /**
     * 向所有在线客户端广播假人的玩家信息（ADD_PLAYER, listed=false）。
     * 1.20.5+ 起 AddPlayerPacket 不再携带 GameProfile，客户端只渲染「玩家信息表」中存在的玩家实体；
     * 不广播则假人服务端存在但客户端完全看不见。listed=false 保证不占用 Tab 玩家列表。
     */
    public static void broadcastProfileInfo(Player npc) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            sendProfileInfoTo(npc, viewer);
        }
    }

    /**
     * 向单个客户端发送假人的玩家信息更新包（加入世界时补发）。
     */
    public static void sendProfileInfoTo(Player npc, Player viewer) {
        try {
            Object packet = buildProfileInfoPacket(npc);
            if (packet == null) {
                return;
            }
            sendPacketTo(viewer, packet);
        } catch (Exception e) {
            MiragEdgeDummy.getInstance().getLogger()
                    .log(Level.WARNING, "广播假人玩家信息失败（客户端可能看不到假人）: " + e.getMessage());
        }
    }

    /**
     * 移除假人后向所有在线客户端广播玩家信息移除包，清理客户端侧残留信息。
     */
    public static void broadcastProfileRemove(Player npc) {
        try {
            Class<?> removeClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket");
            Constructor<?> removeCtor = null;
            for (Constructor<?> c : removeClass.getConstructors()) {
                if (c.getParameterTypes().length == 1) {
                    removeCtor = c;
                    break;
                }
            }
            if (removeCtor == null) {
                return;
            }
            Object packet = removeCtor.newInstance(List.of(npc.getUniqueId()));
            for (Player viewer : Bukkit.getOnlinePlayers()) {
                try {
                    sendPacketTo(viewer, packet);
                } catch (Exception ignored) {
                }
            }
        } catch (Exception e) {
            MiragEdgeDummy.getInstance().getLogger()
                    .log(Level.WARNING, "广播假人玩家信息移除失败: " + e.getMessage());
        }
    }

    /**
     * 构造 ClientboundPlayerInfoUpdatePacket(ADD_PLAYER, listed=false, GameProfile)：
     * <ul>
     *   <li>Entry 记录签名 1.21.4：(UUID, GameProfile, boolean listed, int latency,
     *       GameType, @Nullable Component displayName, @Nullable RemoteChatSession.Data chatSession)；
     *       Paper 补丁将 displayName 换为 Adventure Component——displayName/chatSession 传 null，
     *       构造器按 7 参 + 前 5 个类型精确匹配自动适应两套签名；</li>
     *   <li>延迟 0 / GameType.SURVIVAL 无实际影响（假人不在 Tab）。</li>
     * </ul>
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object buildProfileInfoPacket(Player npc) throws Exception {
        return buildProfileInfoPacket(npc, null);
    }

    /**
     * 构造信息包；displayName 非 null 时附带 UPDATE_DISPLAY_NAME 动作并写入 Entry.displayName
     * （玩家实体头顶名字来自 GameProfile/listName 而非 metadata 自定义名）。
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Object buildProfileInfoPacket(Player npc, String displayNameLegacy) throws Exception {
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket");
        Class<?> actionClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action");
        Class<?> entryClass = Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry");
        Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
        Class<?> gameTypeClass = Class.forName("net.minecraft.world.level.GameType");

        Object handle = npc.getClass().getMethod("getHandle").invoke(npc);
        Object profile = handle.getClass().getField("gameProfile").get(handle);
        if (profile == null) {
            return null;
        }
        UUID uuid = (UUID) invokeProfileAccessor(profile, "id", "getId");

        // displayName 组件（旧版签名为 Adventure Component，26.2 为 chat Component——
        // PaperAdventure.asVanilla 在运行时返回正确类型，构造器按类型填充即可）
        Object displayNameComponent = null;
        if (displayNameLegacy != null) {
            try {
                net.kyori.adventure.text.Component adv =
                        net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
                                .legacySection().deserialize(displayNameLegacy);
                Class<?> paperAdventure = Class.forName("io.papermc.paper.adventure.PaperAdventure");
                displayNameComponent = paperAdventure
                        .getMethod("asVanilla", net.kyori.adventure.text.Component.class)
                        .invoke(null, adv);
            } catch (Exception ignored) {
            }
        }

        Object survival = null;
        for (Object constant : (Object[]) gameTypeClass.getMethod("values").invoke(null)) {
            if ("SURVIVAL".equals(constant.toString())) {
                survival = constant;
                break;
            }
        }
        if (survival == null) {
            // 26.x 用 DEFAULT_MODE 静态字段表示默认模式（生存）
            try {
                survival = gameTypeClass.getField("DEFAULT_MODE").get(null);
            } catch (Exception ignored) {
            }
        }
        if (survival == null) {
            Object[] all = (Object[]) gameTypeClass.getMethod("values").invoke(null);
            survival = all.length > 0 ? all[0] : null;
        }

        // Entry 构造器扫描：前 5 个参数 (UUID, GameProfile, boolean listed, int latency, GameType)
        // 精确匹配；剩余参数按【声明类型】填默认值（兼容 1.21.x 与 26.x 多代签名）：
        //   7 参（1.21.0/1.21.1）: displayName(null), chatSession(null)
        //   8 参（1.21.2~1.21.11）: displayName(null), chatSession(null), int listOrder(0)
        //   9 参（26.x，实测 leaf-26.2-63）: displayName(null), boolean showHat(true), int listOrder(0), chatSession(null)
        Constructor<?> entryCtor = null;
        Class<?>[] entryParamTypes = null;
        for (Constructor<?> c : entryClass.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if ((p.length == 7 || p.length == 8 || p.length == 9)
                    && p[0] == UUID.class
                    && p[1] == gameProfileClass
                    && p[2] == boolean.class
                    && p[3] == int.class
                    && p[4] == gameTypeClass) {
                entryCtor = c;
                entryParamTypes = p;
                break;
            }
        }
        if (entryCtor == null) {
            throw new IllegalStateException("找不到 ClientboundPlayerInfoUpdatePacket$Entry 构造器");
        }
        Object[] entryArgs = new Object[entryParamTypes.length];
        entryArgs[0] = uuid;
        entryArgs[1] = profile;
        entryArgs[2] = false;   // listed=false：不占用 Tab 玩家列表
        entryArgs[3] = 0;       // latency
        entryArgs[4] = survival;
        for (int i = 5; i < entryArgs.length; i++) {
            Class<?> t = entryParamTypes[i];
            if (i == 5 && displayNameComponent != null) {
                entryArgs[i] = displayNameComponent;   // displayName（头顶/列表显示名）
            } else if (t == boolean.class) {
                entryArgs[i] = true;    // showHat（26.x）：显示皮肤帽子层
            } else if (t == int.class) {
                entryArgs[i] = 0;       // listOrder
            } else {
                entryArgs[i] = null;    // displayName / chatSession（引用类型）
            }
        }
        Object entry = entryCtor.newInstance(entryArgs);

        Object addPlayer = null;
        Object updateDisplayName = null;
        for (Object constant : (Object[]) actionClass.getMethod("values").invoke(null)) {
            if ("ADD_PLAYER".equals(constant.toString())) {
                addPlayer = constant;
            } else if ("UPDATE_DISPLAY_NAME".equals(constant.toString())) {
                updateDisplayName = constant;
            }
        }
        if (addPlayer == null) {
            throw new IllegalStateException("找不到 Action.ADD_PLAYER");
        }
        EnumSet actions;
        if (displayNameComponent != null && updateDisplayName != null) {
            actions = EnumSet.of((Enum) addPlayer, (Enum) updateDisplayName);
        } else {
            actions = EnumSet.of((Enum) addPlayer);
        }

        Constructor<?> packetCtor = null;
        for (Constructor<?> c : packetClass.getConstructors()) {
            Class<?>[] p = c.getParameterTypes();
            if (p.length == 2 && p[0] == EnumSet.class && p[1] == List.class) {
                packetCtor = c;
                break;
            }
        }
        if (packetCtor == null) {
            throw new IllegalStateException("找不到 ClientboundPlayerInfoUpdatePacket 构造器");
        }
        return packetCtor.newInstance(actions, List.of(entry));
    }

    /**
     * 向单个客户端发送 NMS 包：viewer.getHandle().connection.send(packet)。
     */
    private static void sendPacketTo(Player viewer, Object packet) throws Exception {
        Object handle = viewer.getClass().getMethod("getHandle").invoke(viewer);
        Object connection = handle.getClass().getField("connection").get(handle);
        if (connection == null) {
            return;
        }
        Class<?> packetInterface = Class.forName("net.minecraft.network.protocol.Packet");
        Method send = null;
        Class<?> klass = connection.getClass();
        while (klass != null && send == null) {
            try {
                send = klass.getDeclaredMethod("send", packetInterface);
            } catch (NoSuchMethodException ignored) {
                klass = klass.getSuperclass();
            }
        }
        if (send == null) {
            throw new IllegalStateException("找不到 connection.send(Packet)");
        }
        send.invoke(connection, packet);
    }

    /**
     * 应用玩家皮肤（缓存命中立即设置；未命中异步解析后回主线程设置）。
     * 任何异常都不影响 NPC 本体（保持默认皮肤）。
     */
    private static void applySkin(Player npc, String skinName) {
        if (skinName == null || skinName.isBlank()) {
            return;
        }
        try {
            // 主线程同步解析（含缓存；首查在线回源 1~3s）；NMS 反射路径已在生成前解析，
            // 此处对官方 API 路径补齐皮肤并刷新客户端。
            PlayerProfile profile = resolveSkinProfile(skinName);
            if (profile != null && profile.hasTextures()) {
                npc.setPlayerProfile(profile);
                broadcastSkinRefresh(npc);
                return;
            }
            final MiragEdgeDummy plugin = MiragEdgeDummy.getInstance();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    profile.complete(true, false);
                } catch (Throwable ignored) {
                }
                // 注册前判 isEnabled：插件可能在解析期间被禁用（禁用后注册任务会抛异常）
                if (!plugin.isEnabled()) {
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (plugin.isEnabled() && npc.isValid()) {
                        try {
                            npc.setPlayerProfile(profile);
                            // 皮肤解析完成后重播信息包：客户端刷新假人皮肤
                            broadcastSkinRefresh(npc);
                        } catch (Exception ignored) {
                        }
                    }
                });
            });
        } catch (Exception e) {
            MiragEdgeDummy.getInstance().getLogger()
                    .log(Level.WARNING, "假人皮肤解析失败（NPC 保持默认皮肤，不影响生成）: " + e.getMessage());
        }
    }
}
