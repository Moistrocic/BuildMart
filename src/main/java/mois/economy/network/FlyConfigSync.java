package mois.economy.network;

import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 飞行挖掘速度开关（fly.digNoSlow）的服务端 → 客户端同步。
 * <p>
 * 客户端（含 mod 客户端）本地预测挖掘速度也需要与权威配置一致：
 * 服务端在玩家 JOIN 与 /config 热改时下发当前值，客户端 receiver 更新
 * {@link #digNoSlow}；PlayerMixin 两端统一读该值。
 * 纯净端没有本模组，收不到该 payload（发送前 {@link ServerPlayNetworking#canSend}
 * 检查），完全不受影响。
 */
public final class FlyConfigSync {
	/** 当前值：服务端 = 配置权威（SERVER_STARTED/热改时设置）；客户端 = 服务端下发。 */
	public static volatile boolean digNoSlow = true;

	/** 服务端引用（/config 热改时广播给全部在线玩家；SERVER_STOPPING 清空）。 */
	private static MinecraftServer server;

	private FlyConfigSync() {
	}

	/** S2C 载荷：digNoSlow（true=飞行挖掘不减速，false=原版生存飞行挖掘速度）。 */
	public record FlyConfigPayload(boolean digNoSlow) implements CustomPacketPayload {
		public static final Type<FlyConfigPayload> TYPE = new Type<>(
				Identifier.fromNamespaceAndPath("economy", "fly_dig_no_slow"));
		public static final StreamCodec<ByteBuf, FlyConfigPayload> CODEC = StreamCodec.composite(
				ByteBufCodecs.BOOL, FlyConfigPayload::digNoSlow, FlyConfigPayload::new);

		@Override
		public Type<? extends CustomPacketPayload> type() {
			return TYPE;
		}
	}

	/** 注册 S2C 载荷类型（两端 onInitialize 都会执行；不在同步注册表，纯净端无感知）。 */
	public static void register() {
		PayloadTypeRegistry.clientboundPlay().register(FlyConfigPayload.TYPE, FlyConfigPayload.CODEC);
	}

	/** 服务器启动：保存 server 引用并按配置设置当前值（后续热改时广播用）。 */
	public static void onServerStarted(MinecraftServer minecraftServer) {
		server = minecraftServer;
		digNoSlow = mois.economy.config.EconomyConfig.flyDigNoSlow();
	}

	/** 服务器停止：清空 server 引用。 */
	public static void onServerStopping() {
		server = null;
	}

	/** 玩家加入：下发当前值（客户端本地预测与服务端权威一致）。 */
	public static void sendTo(ServerPlayer player) {
		if (ServerPlayNetworking.canSend(player, FlyConfigPayload.TYPE)) {
			ServerPlayNetworking.send(player, new FlyConfigPayload(digNoSlow));
		}
	}

	/** /config 热改后广播给全部在线玩家。 */
	public static void broadcastToAll() {
		if (server != null) {
			for (ServerPlayer player : server.getPlayerList().getPlayers()) {
				sendTo(player);
			}
		}
	}
}
