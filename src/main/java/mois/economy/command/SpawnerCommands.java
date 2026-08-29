package mois.economy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import mois.economy.spawner.SpawnerManager;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;

/**
 * /spawner —— 刷怪笼玩法（休闲经济闭环）：
 * <ul>
 * <li>{@code /spawner} — 查看准星对准的刷怪笼信息（类型/等级/生成参数/升级费用）；</li>
 * <li>{@code /spawner upgrade} — 升级刷怪笼（纯金钱，等级越高越贵，参数越强）。</li>
 * </ul>
 */
public final class SpawnerCommands {
	private static final SimpleCommandExceptionType PLAYER_ONLY =
			new SimpleCommandExceptionType(Component.literal("该指令只能由玩家执行"));
	private static final SimpleCommandExceptionType NOT_SPAWNER =
			new SimpleCommandExceptionType(Component.literal("请对准刷怪笼（5 格内）使用该指令"));

	private SpawnerCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(Commands.literal("spawner")
				.executes(SpawnerCommands::info)
				.then(Commands.literal("upgrade")
						.executes(SpawnerCommands::upgrade)));
	}

	/** /spawner —— 查看刷怪笼信息。 */
	private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = requirePlayer(ctx.getSource());
		SpawnerBlockEntity spawner = SpawnerManager.targetedSpawner(player);
		if (spawner == null) {
			throw NOT_SPAWNER.create();
		}
		ctx.getSource().sendSuccess(() -> SpawnerManager.describe(spawner), false);
		return 1;
	}

	/** /spawner upgrade —— 升级刷怪笼。 */
	private static int upgrade(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = requirePlayer(ctx.getSource());
		SpawnerBlockEntity spawner = SpawnerManager.targetedSpawner(player);
		if (spawner == null) {
			throw NOT_SPAWNER.create();
		}
		String error = SpawnerManager.upgrade(player, spawner);
		if (error != null) {
			ctx.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		return 1;
	}

	private static ServerPlayer requirePlayer(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			throw PLAYER_ONLY.create();
		}
		return player;
	}
}
