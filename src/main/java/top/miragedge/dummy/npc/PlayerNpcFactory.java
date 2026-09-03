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

    private PlayerNpcFactory() {}

    /**
     * 在世界中生成一个玩家 NPC 假人（官方 API → NMS 反射）。
     *
     * @return Bukkit {@link Player} 实体；两层方案都失败时返回 null（调用方回退盔甲架）
     */
    public static Player spawn(World world, Location location, String skinName) {
        // ---- 1. 官方 API ----
        Player official = spawnOfficial(world, location);
        if (official != null) {
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
            // 皮肤纹理（best effort；失败不影响生成）
            if (skinName != null && !skinName.isBlank()) {
                try {
                    PlayerProfile pp = Bukkit.createProfile(skinName);
                    if (pp != null && pp.completeFromCache(false)
                            && pp.getProperties() != null && !pp.getProperties().isEmpty()) {
                        Object properties = gameProfileClass.getMethod("getProperties").invoke(gameProfile);
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
                return player;
            }
            if (bukkitEntity != null) {
                bukkitEntity.remove();
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

    /**
     * 应用玩家皮肤（缓存命中立即设置；未命中异步解析后回主线程设置）。
     * 任何异常都不影响 NPC 本体（保持默认皮肤）。
     */
    private static void applySkin(Player npc, String skinName) {
        if (skinName == null || skinName.isBlank()) {
            return;
        }
        try {
            PlayerProfile profile = Bukkit.createProfile(skinName);
            if (profile == null) {
                return;
            }
            if (profile.completeFromCache(false)) {
                npc.setPlayerProfile(profile);
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
