package mois.economy.fly;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import mois.economy.Economy;
import mois.economy.Money;
import mois.economy.config.EconomyConfig;
import mois.economy.data.EconomyDb;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 付费飞行模式（会话级）：
 * <ul>
 * <li>开启期间每 20 tick（1 秒）按 {@link EconomyConfig#flyFeeCents()} 扣费一次；</li>
 * <li>余额不足 1 秒费用时自动关闭飞行并提醒；</li>
 * <li>余额仅能维持 1 分钟（fee×60）时提醒一次，余额回升到阈值以上后可再次提醒，
 * 玩家可用 /fly warn 关闭该提醒；</li>
 * <li>玩家下线停止扣费但保留模式，重新上线后自动恢复飞行能力。</li>
 * </ul>
 * 状态保存在内存中（随服务器重启清空）；飞行能力不写入玩家存档，每次上线由 tick 重新授予。
 */
public final class FlyManager {
	private static final Set<UUID> ENABLED = Collections.newSetFromMap(new ConcurrentHashMap<>());
	/** 关闭了“资金不足 1 分钟提醒”的玩家。 */
	private static final Set<UUID> WARN_OFF = Collections.newSetFromMap(new ConcurrentHashMap<>());
	/** 本次低余额事件已提醒过的玩家（余额回升到阈值以上后解除，可再次提醒）。 */
	private static final Set<UUID> LOW_WARNED = Collections.newSetFromMap(new ConcurrentHashMap<>());

	private FlyManager() {
	}

	public static boolean isEnabled(ServerPlayer player) {
		return ENABLED.contains(player.getUUID());
	}

	public static boolean isWarnEnabled(UUID uuid) {
		return !WARN_OFF.contains(uuid);
	}

	/** 切换“资金不足 1 分钟提醒”，返回切换后的开启状态。 */
	public static boolean toggleWarn(UUID uuid) {
		if (WARN_OFF.remove(uuid)) {
			return true;
		}
		WARN_OFF.add(uuid);
		return false;
	}

	/** 开启飞行模式并授予飞行能力（立即悬空）。 */
	public static void enable(ServerPlayer player) {
		if (ENABLED.add(player.getUUID())) {
			player.getAbilities().mayfly = true;
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
		}
	}

	/** 关闭飞行模式并收回飞行能力。 */
	public static void disable(ServerPlayer player) {
		if (ENABLED.remove(player.getUUID())) {
			revokeAbilities(player);
		}
	}

	/** 玩家上线：保留的模式恢复飞行能力（防止出生在空中时坠落）。 */
	public static void onJoin(ServerPlayer player) {
		if (ENABLED.contains(player.getUUID())) {
			player.getAbilities().mayfly = true;
			player.getAbilities().flying = true;
			player.onUpdateAbilities();
		}
	}

	/** 玩家下线：停止扣费但保留模式；收回飞行能力，避免被写入玩家存档。 */
	public static void onDisconnect(ServerPlayer player) {
		revokeAbilities(player);
	}

	/** 每秒结算一次：确保飞行能力 → 余额检查 → 低余额提醒 → 扣费。 */
	public static void onServerTick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}
		long fee = EconomyConfig.flyFeeCents();
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			UUID uuid = player.getUUID();
			if (!ENABLED.contains(uuid)) {
				continue;
			}
			// 只保证“可以飞”（mayfly），不强制悬空：玩家双击空格下降时不会被每秒拉起。
			if (!player.getAbilities().mayfly) {
				player.getAbilities().mayfly = true;
				player.onUpdateAbilities();
			}
			if (fee <= 0) {
				continue; // 免费飞行：不扣费、不检查余额
			}
			long balance;
			try {
				balance = EconomyDb.getBalance(uuid);
			} catch (EconomyDb.DatabaseException e) {
				Economy.LOGGER.error("飞行扣费读取余额失败", e);
				continue; // 本轮跳过，不误关飞行
			}
			if (balance < fee) {
				disable(player);
				player.sendSystemMessage(Component.literal(
						"你的资金不足（每秒扣费 " + Money.format(fee) + " 元），飞行模式已自动关闭，剩余资产 "
								+ Money.format(balance) + " 元")
						.withStyle(ChatFormatting.RED), false);
				continue;
			}
			checkLowBalanceWarn(player, balance, fee);
			try {
				EconomyDb.deduct(uuid, fee);
			} catch (EconomyDb.DatabaseException e) {
				Economy.LOGGER.error("飞行扣费失败", e);
			}
			// 资金流水（每秒扣费一条）；记录失败静默
			try {
				EconomyDb.recordMoneyLog(uuid, player.getGameProfile().name(),
						EconomyDb.TYPE_FEE, EconomyDb.CHANNEL_FLY, "飞行扣费", -fee);
			} catch (EconomyDb.DatabaseException ignored) {
				// 记录失败静默。
			}
		}
	}

	/** 余额仅能维持 1 分钟（fee×60）时提醒一次；余额回升到阈值以上后解除标记，可再次提醒。 */
	public static void checkLowBalanceWarn(ServerPlayer player, long balance, long fee) {
		if (fee <= 0 || balance > satMul(fee, 60)) {
			LOW_WARNED.remove(player.getUUID());
			return;
		}
		if (isWarnEnabled(player.getUUID()) && LOW_WARNED.add(player.getUUID())) {
			player.sendSystemMessage(Component.literal(
					"你的资金仅能维持不足 1 分钟的飞行（每秒扣费 " + Money.format(fee) + " 元，"
							+ "1 分钟约 " + Money.format(satMul(fee, 60)) + " 元），请及时充值")
					.withStyle(ChatFormatting.YELLOW), false);
		}
	}

	private static void revokeAbilities(ServerPlayer player) {
		if (player.isCreative() || player.isSpectator()) {
			return; // 创造/旁观的能力由游戏模式管理，不应收回
		}
		if (player.getAbilities().mayfly) {
			player.getAbilities().flying = false;
			player.getAbilities().mayfly = false;
			player.onUpdateAbilities();
		}
	}

	private static long satMul(long a, long b) {
		if (a <= 0 || b <= 0) {
			return 0;
		}
		if (a > Long.MAX_VALUE / b) {
			return Long.MAX_VALUE;
		}
		return a * b;
	}
}
