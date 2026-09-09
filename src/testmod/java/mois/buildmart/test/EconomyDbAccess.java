package mois.buildmart.test;

import mois.buildmart.data.EconomyDb;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 测试用的数据库/账户辅助（仅测试源集）：前置准备与断言都直接走 {@link EconomyDb}，
 * 不依赖指令链路（避免"用被测功能准备被测功能"）。
 */
public final class EconomyDbAccess {
	private EconomyDbAccess() {
	}

	/** 给玩家入账（自动建行）。 */
	public static void credit(ServerPlayer player, long cents) {
		EconomyDb.credit(player.getUUID(), player.getGameProfile().name(), cents);
	}

	public static long balance(ServerPlayer player) {
		return EconomyDb.getBalance(player.getUUID());
	}

	public static long balance(UUID uuid) {
		return EconomyDb.getBalance(uuid);
	}

	/** 离线玩家 UUID（与模组内 NameAndId.createOffline 解析一致）。 */
	public static UUID offlineUuid(String name) {
		return net.minecraft.server.players.NameAndId.createOffline(name).id();
	}

	/** 确保离线玩家已注册资金账户（/shop setpayee、/spawner set payee 的前置校验需要）。 */
	public static void ensureAccount(String name) {
		EconomyDb.ensureAccount(offlineUuid(name), name);
	}

	public static void creditOffline(String name, long cents) {
		EconomyDb.credit(offlineUuid(name), name, cents);
	}
}
