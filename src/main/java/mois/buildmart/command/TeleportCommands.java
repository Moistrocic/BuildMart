package mois.buildmart.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import mois.buildmart.BuildMart;
import mois.buildmart.config.EconomyConfig;
import mois.buildmart.data.EconomyDb;
import mois.buildmart.teleport.TeleportManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.UUID;

/**
 * 传送指令：/home /sethome /tpa /tpahere /tpaccept /back。
 * 费用/冷却/开关配置见 {@link EconomyConfig} 的 home/tpa/back 段；
 * 结算与执行见 {@link TeleportManager}。
 */
public final class TeleportCommands {
	private static final int MAX_HOME_NAME_LENGTH = 16;

	private static final SimpleCommandExceptionType PLAYER_ONLY =
			new SimpleCommandExceptionType(Component.literal("该指令只能由玩家执行"));
	private static final SimpleCommandExceptionType DB_ERROR =
			new SimpleCommandExceptionType(Component.literal("数据库错误，请稍后再试"));

	private TeleportCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(Commands.literal("home")
				.executes(ctx -> home(ctx, null))
				.then(Commands.argument("name", StringArgumentType.word())
						.executes(ctx -> home(ctx, StringArgumentType.getString(ctx, "name")))));

		dispatcher.register(Commands.literal("sethome")
				.then(Commands.argument("name", StringArgumentType.word())
						.executes(TeleportCommands::setHome)));

		dispatcher.register(Commands.literal("delhome")
				.then(Commands.argument("name", StringArgumentType.word())
						.executes(TeleportCommands::delHome)));

		dispatcher.register(Commands.literal("listhome")
				.executes(ctx -> listHome(ctx, 1))
				.then(Commands.argument("page", IntegerArgumentType.integer(1))
						.executes(ctx -> listHome(ctx, IntegerArgumentType.getInteger(ctx, "page")))));

		dispatcher.register(Commands.literal("tpa")
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.executes(ctx -> request(ctx, true))));

		dispatcher.register(Commands.literal("tpahere")
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.executes(ctx -> request(ctx, false))));

		dispatcher.register(Commands.literal("tpaccept")
				.executes(TeleportCommands::accept));

		dispatcher.register(Commands.literal("back")
				.executes(TeleportCommands::back));
	}

	// ---------- /home ----------

	/** /home [名称] —— 传送回家；不带名称时回到最近设置的家。 */
	private static int home(CommandContext<CommandSourceStack> ctx, String name) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		List<EconomyDb.HomeEntry> homes;
		try {
			homes = EconomyDb.getHomes(player.getUUID());
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("查询家列表失败", e);
			throw DB_ERROR.create();
		}
		if (homes.isEmpty()) {
			source.sendFailure(text("你还没有设置家，使用 /sethome <名称> 设置", ChatFormatting.RED));
			return 0;
		}
		EconomyDb.HomeEntry home;
		if (name == null) {
			home = homes.get(0); // getHomes 按设置时间倒序，第一项为最近设置的家
		} else {
			home = homes.stream().filter(h -> h.name().equalsIgnoreCase(name)).findFirst().orElse(null);
			if (home == null) {
				source.sendFailure(text("没有找到名为 " + name + " 的家", ChatFormatting.RED));
				return 0;
			}
		}
		ServerLevel level = source.getServer().getLevel(parseDimension(home.world()));
		if (level == null) {
			source.sendFailure(text("家的维度不可用", ChatFormatting.RED));
			return 0;
		}
		TeleportManager.TpOutcome outcome = TeleportManager.teleportAndCharge(player,
				player.getUUID(), player.getGameProfile().name(), level,
				new Vec3(home.x(), home.y(), home.z()), EconomyConfig.homeSettings().fees(),
				TeleportManager.homeCooldowns(), source.getServer());
		send(source, outcome);
		return outcome.ok() ? 1 : 0;
	}

	// ---------- /sethome ----------

	/** /sethome 名称 —— 设置/覆盖指定名称的家（数量受配置上限限制）。 */
	private static int setHome(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		String name = StringArgumentType.getString(ctx, "name");
		if (name.length() > MAX_HOME_NAME_LENGTH) {
			source.sendFailure(text("家的名称过长（最多 " + MAX_HOME_NAME_LENGTH + " 个字符）", ChatFormatting.RED));
			return 0;
		}
		EconomyConfig.HomeSettings settings = EconomyConfig.homeSettings();
		if (settings.max() <= 0) {
			source.sendFailure(text("服务器未开放设置家", ChatFormatting.RED));
			return 0;
		}
		UUID uuid = player.getUUID();
		try {
			if (EconomyDb.getHome(uuid, name) == null && EconomyDb.countHomes(uuid) >= settings.max()) {
				source.sendFailure(text("家的数量已达上限（" + settings.max() + "）", ChatFormatting.RED));
				return 0;
			}
			Vec3 pos = player.position();
			EconomyDb.setHome(uuid, name, player.level().dimension().identifier().toString(),
					pos.x(), pos.y(), pos.z());
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("设置家失败", e);
			throw DB_ERROR.create();
		}
		source.sendSuccess(() -> text("已设置家 ", ChatFormatting.GREEN).append(name), false);
		return 1;
	}

	// ---------- /delhome ----------

	/** /delhome 名称 —— 删除指定名称的家。 */
	private static int delHome(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		String name = StringArgumentType.getString(ctx, "name");
		boolean removed;
		try {
			removed = EconomyDb.removeHome(player.getUUID(), name);
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("删除家失败", e);
			throw DB_ERROR.create();
		}
		if (!removed) {
			source.sendFailure(text("没有找到名为 " + name + " 的家", ChatFormatting.RED));
			return 0;
		}
		source.sendSuccess(() -> text("已删除家 ", ChatFormatting.GREEN).append(name), false);
		return 1;
	}

	// ---------- /listhome ----------

	/** /listhome [页码] —— 查看已有的家，每页 10 行。 */
	private static int listHome(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		List<EconomyDb.HomeEntry> homes;
		try {
			homes = EconomyDb.getHomes(player.getUUID());
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("查询家列表失败", e);
			throw DB_ERROR.create();
		}
		int pageSize = 10;
		int pages = Math.max(1, (homes.size() + pageSize - 1) / pageSize);
		if (page > pages) {
			source.sendFailure(text("页码超出范围，共 " + pages + " 页", ChatFormatting.RED));
			return 0;
		}
		MutableComponent message = text("=== 我的家 第 " + page + "/" + pages + " 页 ===", ChatFormatting.GOLD)
				.append("\n");
		int start = (page - 1) * pageSize;
		int end = Math.min(start + pageSize, homes.size());
		for (int i = start; i < end; i++) {
			EconomyDb.HomeEntry home = homes.get(i);
			message.append(String.valueOf(i + 1)).append(". ")
					.append(home.name()).append(" - ")
					.append(home.world())
					.append(" (").append(String.valueOf((int) home.x())).append(", ")
					.append(String.valueOf((int) home.y())).append(", ")
					.append(String.valueOf((int) home.z())).append(")\n");
		}
		source.sendSuccess(() -> message, false);
		return 1;
	}

	// ---------- /tpa /tpahere /tpaccept ----------

	/** /tpa 玩家（toTarget=true 传送到对方位置）/ /tpahere 玩家（请求对方传送到自己位置）。 */
	private static int request(CommandContext<CommandSourceStack> ctx, boolean toTarget) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer requester = requirePlayer(source);
		if (!EconomyConfig.tpaSettings().enabled()) {
			source.sendFailure(text("传送请求功能未开放", ChatFormatting.RED));
			return 0;
		}
		NameAndId profile = GameProfileArgument.getGameProfiles(ctx, "player").iterator().next();
		UUID uuid = profile.id() != null ? profile.id() : NameAndId.createOffline(profile.name()).id();
		ServerPlayer target = source.getServer().getPlayerList().getPlayer(uuid);
		if (target == null) {
			source.sendFailure(text("该玩家不在线", ChatFormatting.RED));
			return 0;
		}
		if (target.getUUID().equals(requester.getUUID())) {
			source.sendFailure(text("不能向自己发送传送请求", ChatFormatting.RED));
			return 0;
		}
		TeleportManager.request(requester, target, toTarget, source.getServer());
		if (toTarget) {
			source.sendSuccess(() -> text("已向 ", ChatFormatting.GREEN).append(playerName(target))
					.append(" 发送传送到其位置的请求"), false);
			target.sendSystemMessage(text("", ChatFormatting.GOLD)
					.append(playerName(requester)).append(" 请求传送到你的位置，输入 /tpaccept 接受"), false);
		} else {
			source.sendSuccess(() -> text("已向 ", ChatFormatting.GREEN).append(playerName(target))
					.append(" 发送传送到你位置的请求"), false);
			target.sendSystemMessage(text("", ChatFormatting.GOLD)
					.append(playerName(requester)).append(" 请求你传送到其位置，输入 /tpaccept 接受"), false);
		}
		return 1;
	}

	/** /tpaccept —— 接受最近的传送请求（费用由请求方承担；成功/失败通知由 TeleportManager 分发）。 */
	private static int accept(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		if (!EconomyConfig.tpaSettings().enabled()) {
			source.sendFailure(text("传送请求功能未开放", ChatFormatting.RED));
			return 0;
		}
		TeleportManager.TpOutcome outcome = TeleportManager.accept(player, source.getServer());
		if (!outcome.ok()) {
			// 无请求/请求方离线等本地失败：直接提示；传送失败已由 TeleportManager 通知付费方
			source.sendFailure(text(outcome.message(), ChatFormatting.RED));
			return 0;
		}
		return 1;
	}

	// ---------- /back ----------

	/** /back —— 回到最近死亡点（使用成功后立即清除，再次使用视为无死亡点）。 */
	private static int back(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		if (!EconomyConfig.backSettings().enabled()) {
			source.sendFailure(text("死亡点传送功能未开放", ChatFormatting.RED));
			return 0;
		}
		EconomyDb.BackPoint point;
		try {
			point = EconomyDb.getBackPoint(player.getUUID());
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("查询死亡点失败", e);
			throw DB_ERROR.create();
		}
		if (point == null) {
			source.sendFailure(text("没有可返回的死亡点", ChatFormatting.RED));
			return 0;
		}
		ServerLevel level = source.getServer().getLevel(parseDimension(point.world()));
		if (level == null) {
			source.sendFailure(text("死亡点维度不可用", ChatFormatting.RED));
			return 0;
		}
		TeleportManager.TpOutcome outcome = TeleportManager.teleportAndCharge(player,
				player.getUUID(), player.getGameProfile().name(), level,
				new Vec3(point.x(), point.y(), point.z()), EconomyConfig.backSettings().fees(),
				TeleportManager.backCooldowns(), source.getServer());
		if (outcome.ok()) {
			try {
				EconomyDb.clearBackPoint(player.getUUID());
			} catch (EconomyDb.DatabaseException e) {
				BuildMart.LOGGER.error("清除死亡点失败", e);
			}
		}
		send(source, outcome);
		return outcome.ok() ? 1 : 0;
	}

	// ---------- 工具 ----------

	private static ServerPlayer requirePlayer(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			throw PLAYER_ONLY.create();
		}
		return player;
	}

	/** 玩家显示名组件（26.3 中 getDisplayName 可能返回 null，回退到档案名）。 */
	private static Component playerName(ServerPlayer player) {
		Component name = player.getDisplayName();
		return name != null ? name : Component.literal(player.getGameProfile().name());
	}

	/** 维度 ID 字符串（如 "minecraft:overworld"）→ 维度键。 */
	private static ResourceKey<Level> parseDimension(String world) {
		return ResourceKey.create(Registries.DIMENSION, Identifier.parse(world));
	}

	private static void send(CommandSourceStack source, TeleportManager.TpOutcome outcome) {
		if (outcome.ok()) {
			source.sendSuccess(() -> text(outcome.message(), ChatFormatting.GREEN), false);
		} else {
			source.sendFailure(text(outcome.message(), ChatFormatting.RED));
		}
	}

	private static MutableComponent text(String content, ChatFormatting color) {
		return Component.literal(content).withStyle(color);
	}
}
