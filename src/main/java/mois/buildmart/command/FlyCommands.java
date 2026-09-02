package mois.buildmart.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import mois.buildmart.Economy;
import mois.buildmart.Money;
import mois.buildmart.config.EconomyConfig;
import mois.buildmart.data.EconomyDb;
import mois.buildmart.fly.FlyManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;

/**
 * /fly 系列指令：付费飞行模式的开启/关闭与低余额提醒开关。
 * 扣费由 {@link FlyManager} 按服务端 tick 每秒结算，指令只负责切换状态与提示。
 */
public final class FlyCommands {
	private static final SimpleCommandExceptionType PLAYER_ONLY =
			new SimpleCommandExceptionType(Component.literal("该指令只能由玩家执行"));

	private FlyCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(Commands.literal("fly")
				.executes(FlyCommands::toggle)
				.then(Commands.literal("warn")
						.executes(FlyCommands::toggleWarn)));
	}

	// ---------- /fly ----------

	/** /fly —— 开启或关闭付费飞行模式。 */
	private static int toggle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		long fee = EconomyConfig.flyFeeCents();
		if (FlyManager.isEnabled(player)) {
			// 关闭：提示剩余资产
			FlyManager.disable(player);
			long balance = balanceOrMinusOne(player.getUUID());
			MutableComponent message = text("飞行模式已关闭", ChatFormatting.GREEN);
			if (balance >= 0) {
				message.append("，剩余资产 ").append(Money.format(balance)).append(" 元");
			}
			source.sendSuccess(() -> message, false);
			return 1;
		}
		if (player.isCreative() || player.isSpectator()) {
			source.sendFailure(text("创造/旁观模式自带飞行，无需开启飞行模式", ChatFormatting.RED));
			return 0;
		}
		long balance = balanceOrMinusOne(player.getUUID());
		if (balance < 0) {
			source.sendFailure(text("数据库错误，请稍后再试", ChatFormatting.RED));
			return 0;
		}
		if (balance < fee) {
			source.sendFailure(text("你的资金不足，无法开启飞行模式（每秒扣费 ", ChatFormatting.RED)
					.append(Money.format(fee)).append(" 元，当前资金 ")
					.append(Money.format(balance)).append(" 元）"));
			return 0;
		}
		// 开启：提示每秒扣费；余额仅能维持 1 分钟时按提醒开关提醒一次
		FlyManager.enable(player);
		FlyManager.checkLowBalanceWarn(player, balance, fee);
		source.sendSuccess(() -> text("飞行模式已开启，每秒扣费 ", ChatFormatting.GREEN)
				.append(Money.format(fee)).append(" 元"), false);
		return 1;
	}

	// ---------- /fly warn ----------

	/** /fly warn —— 开启或关闭“资金不足 1 分钟飞行”提醒。 */
	private static int toggleWarn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = requirePlayer(ctx.getSource());
		boolean enabled = FlyManager.toggleWarn(player.getUUID());
		ctx.getSource().sendSuccess(
				() -> text(enabled ? "飞行余额不足提醒已开启" : "飞行余额不足提醒已关闭", ChatFormatting.GREEN), false);
		return 1;
	}

	// ---------- 工具 ----------

	private static ServerPlayer requirePlayer(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			throw PLAYER_ONLY.create();
		}
		return player;
	}

	/** 读取余额；数据库异常返回 -1（提示中省略余额部分）。 */
	private static long balanceOrMinusOne(java.util.UUID uuid) {
		try {
			return EconomyDb.getBalance(uuid);
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("读取余额失败", e);
			return -1;
		}
	}

	private static MutableComponent text(String content, ChatFormatting color) {
		return Component.literal(content).withStyle(color);
	}
}
