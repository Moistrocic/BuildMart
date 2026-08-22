package mois.economy.buymode;

import net.minecraft.server.level.ServerPlayer;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 便捷购买模式（会话级）：临时授予 instabuild，客户端（含纯净端）打开库存时
 * 会自动进入创造物品栏界面；拿取/放回的结算在创造槽位包处理中完成。
 * 关闭界面（doCloseContainer）或断开连接时退出并还原能力。
 */
public final class BuyModeManager {
	private static final Set<UUID> ACTIVE = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private static final Map<UUID, Boolean> PREV_INSTABUILD = new ConcurrentHashMap<>();

	private BuyModeManager() {
	}

	public static boolean isActive(ServerPlayer player) {
		return ACTIVE.contains(player.getUUID());
	}

	public static void enter(ServerPlayer player) {
		UUID uuid = player.getUUID();
		if (ACTIVE.add(uuid)) {
			PREV_INSTABUILD.put(uuid, player.getAbilities().instabuild);
		}
		player.getAbilities().instabuild = true;
		player.onUpdateAbilities();
	}

	public static void exit(ServerPlayer player) {
		UUID uuid = player.getUUID();
		if (ACTIVE.remove(uuid)) {
			Boolean prev = PREV_INSTABUILD.remove(uuid);
			player.getAbilities().instabuild = prev != null && prev;
			player.onUpdateAbilities();
		}
	}
}
