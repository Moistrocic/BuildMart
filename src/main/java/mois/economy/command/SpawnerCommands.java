package mois.economy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
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
 * /spawner —— 刷怪笼玩法（**仅限带特殊标签的刷怪笼**，原版刷怪笼不受影响）：
 * <ul>
 * <li>{@code /spawner info} — 查看准星对准的刷怪笼（类型/等级/生成参数/可调范围/升级费用）；</li>
 * <li>{@code /spawner upgrade} — 升级（纯金钱，等级效果与费用来自 spawner.json；
 *     /config spawner.upgrade 总开关）；</li>
 * <li>{@code /spawner set entity <类型>} — 随时更改刷怪笼刷的实体类型；</li>
 * <li>{@code /spawner set <参数> <值>} — 微调生成参数（minDelay/maxDelay/count/nearby/
 *     playerRange/spawnRange），值受当前等级允许范围约束；</li>
 * <li>{@code /spawner give} — 管理员获得带标签的刷怪笼物品。</li>
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
				.then(Commands.literal("info")
						.executes(SpawnerCommands::info))
				.then(Commands.literal("upgrade")
						.executes(SpawnerCommands::upgrade))
				.then(Commands.literal("set")
						.then(Commands.literal("entity")
								.then(Commands.argument("entity", StringArgumentType.word())
										.executes(SpawnerCommands::setEntity)))
						.then(Commands.argument("param", StringArgumentType.word())
								.suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
										java.util.List.of("minDelay", "maxDelay", "count", "nearby",
												"playerRange", "spawnRange"), builder))
								.then(Commands.argument("value", IntegerArgumentType.integer(1))
										.executes(SpawnerCommands::setParam))))
				.then(Commands.literal("give")
						.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
						.executes(SpawnerCommands::give)));
	}

	/** /spawner info —— 查看刷怪笼信息（仅带标签笼）。 */
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

	/** /spawner set entity <类型> —— 更改刷怪笼实体类型。 */
	private static int setEntity(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = requirePlayer(ctx.getSource());
		SpawnerBlockEntity spawner = SpawnerManager.targetedSpawner(player);
		if (spawner == null) {
			throw NOT_SPAWNER.create();
		}
		String error = SpawnerManager.setEntity(player, spawner, StringArgumentType.getString(ctx, "entity"));
		if (error != null) {
			ctx.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		return 1;
	}

	/** /spawner set <参数> <值> —— 微调生成参数（受等级范围约束）。 */
	private static int setParam(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = requirePlayer(ctx.getSource());
		SpawnerBlockEntity spawner = SpawnerManager.targetedSpawner(player);
		if (spawner == null) {
			throw NOT_SPAWNER.create();
		}
		String error = SpawnerManager.setParam(player, spawner,
				StringArgumentType.getString(ctx, "param"), IntegerArgumentType.getInteger(ctx, "value"));
		if (error != null) {
			ctx.getSource().sendFailure(Component.literal(error));
			return 0;
		}
		return 1;
	}

	/** /spawner give —— 管理员获得带标签刷怪笼。 */
	private static int give(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = requirePlayer(ctx.getSource());
		net.minecraft.world.item.ItemStack stack = SpawnerManager.createTaggedSpawnerStack();
		if (!player.getInventory().add(stack)) {
			player.spawnAtLocation(player.level(), stack);
		}
		ctx.getSource().sendSuccess(() -> Component.literal("已获得带标签的刷怪笼（放置后可用刷怪蛋绑定类型、/spawner 升级）")
				.withStyle(net.minecraft.ChatFormatting.GREEN), true);
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
