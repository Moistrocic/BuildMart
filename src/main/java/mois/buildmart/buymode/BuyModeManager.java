package mois.buildmart.buymode;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 便捷购买模式（会话级）：临时授予 instabuild，客户端（含纯净端）打开库存时
 * 会自动进入创造物品栏界面；拿取/放回的结算在创造槽位包处理中完成。
 * 关闭界面（doCloseContainer）或断开连接时退出并还原能力，
 * 同时把会话内尚未结清的暂存物品统一按卖出结算（见 {@link BuyModeSession}）。
 */
public final class BuyModeManager {
	private static final Set<UUID> ACTIVE = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private static final Map<UUID, Boolean> PREV_INSTABUILD = new ConcurrentHashMap<>();
	private static final Map<UUID, BuyModeSession> SESSIONS = new ConcurrentHashMap<>();

	private BuyModeManager() {
	}

	public static boolean isActive(ServerPlayer player) {
		return ACTIVE.contains(player.getUUID());
	}

	/** 当前便捷购买会话（未激活时返回 null）。 */
	public static BuyModeSession session(ServerPlayer player) {
		return SESSIONS.get(player.getUUID());
	}

	public static void enter(ServerPlayer player) {
		UUID uuid = player.getUUID();
		if (ACTIVE.add(uuid)) {
			PREV_INSTABUILD.put(uuid, player.getAbilities().instabuild);
		}
		SESSIONS.put(uuid, new BuyModeSession());
		player.getAbilities().instabuild = true;
		player.onUpdateAbilities();
	}

	/**
	 * 服务端 tick：结算挂起超过宽限期的 -1 包（面板 ctrl+q 购买）与槽位出现
	 * （数字键/槽间交换未配对 → 面板购买 or 拒绝），避免滞后一拍。
	 */
	public static void onServerTick(MinecraftServer server) {
		for (UUID uuid : ACTIVE) {
			ServerPlayer player = server.getPlayerList().getPlayer(uuid);
			if (player == null) {
				continue;
			}
			BuyModeSession session = SESSIONS.get(uuid);
			if (session != null) {
				BuyModeSettlement.settlePendingDrop(player, session);
				BuyModeSettlement.settlePendingSlot(player, session);
			}
		}
	}

	public static void exit(ServerPlayer player) {
		UUID uuid = player.getUUID();
		// 关闭物品栏/掉线/强制退出：暂存剩余统一卖出，pendingDrop 作废
		BuyModeSession session = SESSIONS.remove(uuid);
		if (session != null) {
			session.settleAndClear(player);
		}
		if (ACTIVE.remove(uuid)) {
			Boolean prev = PREV_INSTABUILD.remove(uuid);
			player.getAbilities().instabuild = prev != null && prev;
			player.onUpdateAbilities();
		}
	}
}
