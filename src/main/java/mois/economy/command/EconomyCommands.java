package mois.economy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import mois.economy.Economy;
import mois.economy.Money;
import mois.economy.data.EconomyDb;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 资金相关指令注册与执行。金额解析用 {@link Money}（整数分），
 * 玩家目标参数用原版 {@link GameProfileArgument}：支持玩家名与 @a/@p 等选择器，
 * 选择器行为完全遵循原版规则；离线玩家按档案缓存解析（无 UUID 时用离线 UUID）。
 */
public final class EconomyCommands {
	private static final int PAGE_SIZE = 10;

	private static final SimpleCommandExceptionType PLAYER_ONLY =
			new SimpleCommandExceptionType(Component.literal("该指令只能由玩家执行"));
	private static final SimpleCommandExceptionType DB_ERROR =
			new SimpleCommandExceptionType(Component.literal("数据库错误，请稍后再试"));
	private static final SimpleCommandExceptionType PAYER_INSUFFICIENT =
			new SimpleCommandExceptionType(Component.literal("你的资金不足"));
	private static final SimpleCommandExceptionType SERVER_INSUFFICIENT =
			new SimpleCommandExceptionType(Component.literal("服务器资产不足"));
	private static final SimpleCommandExceptionType PAY_NO_TARGET =
			new SimpleCommandExceptionType(Component.literal("没有可转账的玩家"));
	private static final SimpleCommandExceptionType AMOUNT_TOO_LARGE =
			new SimpleCommandExceptionType(Component.literal("金额过大"));

	private static final String[] HELP_LINES = {
			"/balhelp 页码 -  查看资金帮助",
			"/bal - 查看自己的资金",
			"/bal 玩家 - 查看自己的资金",
			"/pbal - 查看服务器公共资金",
			"/pbal take 金额 - 取出服务器公共资金",
			"/pbal save 金额 - 存入服务器公共资金",
			"/pay 玩家 金额 - 向玩家支付",
			"/baltop 页码 -  查看资金排行榜",
			"/balshop create - 创建出售商店",
			"/balshop remove - 移除出售商店",
			"/balshop setpayee 玩家 - 设置收款人",
			"/balshop setpayeeserver - 设置服务器账户为收款人",
			"/balshop getprice 物品 - 查看物品价格",
			"/balshop buy 物品 数量 - 购买物品"
	};

	private EconomyCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		BalshopCommands.register(dispatcher, buildContext);
		dispatcher.register(Commands.literal("bal")
				.executes(ctx -> showBalance(ctx, null))
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.executes(ctx -> showBalance(ctx, GameProfileArgument.getGameProfiles(ctx, "player")))));

		dispatcher.register(Commands.literal("pbal")
				.executes(ctx -> showServerAssets(ctx.getSource()))
				.then(Commands.literal("take")
						.then(Commands.argument("amount", StringArgumentType.word())
								.executes(EconomyCommands::takeFromServer)))
				.then(Commands.literal("save")
						.then(Commands.argument("amount", StringArgumentType.word())
								.executes(EconomyCommands::saveToServer))));

		dispatcher.register(Commands.literal("pay")
				.then(Commands.argument("targets", GameProfileArgument.gameProfile())
						.then(Commands.argument("amount", StringArgumentType.word())
								.executes(EconomyCommands::pay))));

		dispatcher.register(Commands.literal("baltop")
				.executes(ctx -> showTop(ctx.getSource(), 1))
				.then(Commands.argument("page", IntegerArgumentType.integer(1))
						.executes(ctx -> showTop(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))));

		dispatcher.register(Commands.literal("balhelp")
				.executes(ctx -> showHelp(ctx.getSource(), 1))
				.then(Commands.argument("page", IntegerArgumentType.integer(1))
						.executes(ctx -> showHelp(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))));

		dispatcher.register(Commands.literal("announcement")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.argument("content", StringArgumentType.greedyString())
						.executes(EconomyCommands::setAnnouncement))
				.then(Commands.literal("clear")
						.executes(EconomyCommands::clearAnnouncement)));

		// 管理员资金指令：给系统注入/回收资金，目标支持玩家名、选择器与 @server（服务器资产账户）。
		// 目标用原版 word 参数承载并手动解析（见 EconomyTargets），保证纯净端兼容。
		dispatcher.register(Commands.literal("eco")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.literal("add")
						.then(Commands.argument("target", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
										EconomyTargets.suggestions(ctx.getSource()), builder))
								.then(Commands.argument("amount", StringArgumentType.word())
										.executes(EconomyCommands::ecoAdd))))
				.then(Commands.literal("remove")
						.then(Commands.argument("target", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
										EconomyTargets.suggestions(ctx.getSource()), builder))
								.then(Commands.argument("amount", StringArgumentType.word())
										.executes(EconomyCommands::ecoRemove))))
				.then(Commands.literal("set")
						.then(Commands.argument("target", StringArgumentType.word())
								.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
										EconomyTargets.suggestions(ctx.getSource()), builder))
								.then(Commands.argument("amount", StringArgumentType.word())
										.executes(EconomyCommands::ecoSet)))));

		// 服务器资产专用管理员指令：/peco set|add|remove 金额。
		dispatcher.register(Commands.literal("peco")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.literal("add")
						.then(Commands.argument("amount", StringArgumentType.word())
								.executes(EconomyCommands::pecoAdd)))
				.then(Commands.literal("remove")
						.then(Commands.argument("amount", StringArgumentType.word())
								.executes(EconomyCommands::pecoRemove)))
				.then(Commands.literal("set")
						.then(Commands.argument("amount", StringArgumentType.word())
								.executes(EconomyCommands::pecoSet))));
	}

	// ---------- /bal ----------

	private static int showBalance(CommandContext<CommandSourceStack> ctx, Collection<NameAndId> targets)
			throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		if (targets == null) {
			ServerPlayer player = requirePlayer(source);
			long balance = readBalance(player.getUUID());
			source.sendSuccess(() -> text("你的资金：", ChatFormatting.GREEN)
					.append(Money.format(balance)).append(" 元"), false);
			return 1;
		}
		for (NameAndId profile : targets) {
			UUID uuid = resolveUuid(profile);
			long balance = readBalance(uuid);
			source.sendSuccess(() -> text(profile.name() + " 的资金：", ChatFormatting.GREEN)
					.append(Money.format(balance)).append(" 元"), false);
		}
		return targets.size();
	}

	// ---------- /pbal ----------

	private static int showServerAssets(CommandSourceStack source) throws CommandSyntaxException {
		long balance = readBalance(EconomyDb.SERVER_ACCOUNT_UUID);
		source.sendSuccess(() -> text(EconomyDb.SERVER_ACCOUNT_NAME + "：", ChatFormatting.GOLD)
				.append(Money.format(balance)).append(" 元"), false);
		return 1;
	}

	/** /pbal take 金额 —— 从服务器资产取出给玩家。 */
	private static int takeFromServer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		long amount = parseAmount(ctx);
		boolean ok = transferOrThrow(EconomyDb.SERVER_ACCOUNT_UUID, player.getUUID(),
				player.getGameProfile().name(), amount, "pbal take");
		if (!ok) {
			throw SERVER_INSUFFICIENT.create();
		}
		long balance = readBalance(player.getUUID());
		source.sendSuccess(() -> text("你从服务器资产取出了 ", ChatFormatting.GREEN)
				.append(Money.format(amount)).append(" 元，当前资金：")
				.append(Money.format(balance)).append(" 元"), false);
		return 1;
	}

	/** /pbal save 金额 —— 把玩家资金存入服务器资产。 */
	private static int saveToServer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		long amount = parseAmount(ctx);
		boolean ok = transferOrThrow(player.getUUID(), EconomyDb.SERVER_ACCOUNT_UUID,
				EconomyDb.SERVER_ACCOUNT_NAME, amount, "pbal save");
		if (!ok) {
			throw PAYER_INSUFFICIENT.create();
		}
		long balance = readBalance(player.getUUID());
		source.sendSuccess(() -> text("你向服务器资产存入了 ", ChatFormatting.GREEN)
				.append(Money.format(amount)).append(" 元，当前资金：")
				.append(Money.format(balance)).append(" 元"), false);
		return 1;
	}

	// ---------- /pay ----------

	private static int pay(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer sender = requirePlayer(source);
		long amount = parseAmount(ctx);
		Collection<NameAndId> profiles = GameProfileArgument.getGameProfiles(ctx, "targets");

		// 按 UUID 去重，并跳过自己（@a 包含自己时不计入转账对象）。
		Map<UUID, String> targets = new LinkedHashMap<>();
		for (NameAndId profile : profiles) {
			UUID uuid = resolveUuid(profile);
			if (uuid.equals(sender.getUUID())) {
				continue;
			}
			targets.putIfAbsent(uuid, profile.name());
		}
		if (targets.isEmpty()) {
			throw PAY_NO_TARGET.create();
		}
		if (amount > Long.MAX_VALUE / targets.size()) {
			throw AMOUNT_TOO_LARGE.create();
		}

		List<UUID> uuids = new ArrayList<>(targets.keySet());
		List<String> names = new ArrayList<>(targets.values());
		boolean ok = transferManyOrThrow(sender.getUUID(), uuids, names, amount);
		if (!ok) {
			throw PAYER_INSUFFICIENT.create();
		}

		MutableComponent summary;
		if (targets.size() == 1) {
			summary = text("已向 ", ChatFormatting.GREEN)
					.append(names.get(0)).append(" 转账 ").append(Money.format(amount)).append(" 元");
		} else {
			summary = text("已向 " + targets.size() + " 位玩家各转账 ", ChatFormatting.GREEN)
					.append(Money.format(amount)).append(" 元");
		}
		source.sendSuccess(() -> summary, false);

		// 通知在线目标（离线目标直接入账，下次上线可见）。
		for (UUID uuid : uuids) {
			ServerPlayer target = source.getServer().getPlayerList().getPlayer(uuid);
			if (target != null) {
				target.sendSystemMessage(text("你收到来自 ", ChatFormatting.GREEN)
						.append(sender.getDisplayName()).append(" 的 ")
						.append(Money.format(amount)).append(" 元"), false);
			}
		}
		return 1;
	}

	// ---------- /baltop ----------

	private static int showTop(CommandSourceStack source, int page) throws CommandSyntaxException {
		int total = countOrThrow();
		int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
		if (page > pages) {
			throw new SimpleCommandExceptionType(Component.literal("页码超出范围，共 " + pages + " 页")).create();
		}
		List<EconomyDb.AccountEntry> entries = topOrThrow(page);
		MutableComponent message = text("=== 资金排行榜 第 " + page + " 页 ===", ChatFormatting.GOLD).append("\n");
		int rank = (page - 1) * PAGE_SIZE + 1;
		for (EconomyDb.AccountEntry entry : entries) {
			message.append(String.valueOf(rank++)).append(". ")
					.append(entry.name()).append(" - ")
					.append(Money.format(entry.balance())).append(" 元\n");
		}
		source.sendSuccess(() -> message, false);
		return 1;
	}

	// ---------- /balhelp ----------

	private static int showHelp(CommandSourceStack source, int page) throws CommandSyntaxException {
		int pages = Math.max(1, (HELP_LINES.length + PAGE_SIZE - 1) / PAGE_SIZE);
		if (page > pages) {
			throw new SimpleCommandExceptionType(Component.literal("页码超出范围，共 " + pages + " 页")).create();
		}
		MutableComponent message = text("=== 资金帮助 第 " + page + "/" + pages + " 页 ===", ChatFormatting.GOLD)
				.append("\n");
		int start = (page - 1) * PAGE_SIZE;
		int end = Math.min(start + PAGE_SIZE, HELP_LINES.length);
		for (int i = start; i < end; i++) {
			message.append(HELP_LINES[i]).append("\n");
		}
		source.sendSuccess(() -> message, false);
		return 1;
	}

	// ---------- /eco ----------

	/** /eco add 目标 金额 —— 为目标增加资金。 */
	private static int ecoAdd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		long amount = parseAmount(ctx);
		List<EconomyTargets.ResolvedTarget> targets = EconomyTargets.resolve(StringArgumentType.getString(ctx, "target"), ctx.getSource());
		if (amount > Long.MAX_VALUE / Math.max(1, targets.size())) {
			throw AMOUNT_TOO_LARGE.create();
		}
		try {
			for (EconomyTargets.ResolvedTarget target : targets) {
				EconomyDb.credit(target.uuid(), target.displayName(), amount);
			}
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("eco add 数据库错误", e);
			throw DB_ERROR.create();
		}
		MutableComponent summary;
		if (targets.size() == 1) {
			summary = text("已向 ", ChatFormatting.GREEN)
					.append(targets.get(0).displayName()).append(" 增加 ")
					.append(Money.format(amount)).append(" 元，当前资金：")
					.append(Money.format(readBalance(targets.get(0).uuid()))).append(" 元");
		} else {
			summary = text("已向 " + targets.size() + " 个目标各增加 ", ChatFormatting.GREEN)
					.append(Money.format(amount)).append(" 元");
		}
		source.sendSuccess(() -> summary, false);
		notifyOnlineTargets(targets, amount, "你收到来自管理员的 ", " 元");
		return 1;
	}

	/** /eco remove 目标 金额 —— 从目标扣除资金（余额不足时整体拒绝）。 */
	private static int ecoRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		long amount = parseAmount(ctx);
		List<EconomyTargets.ResolvedTarget> targets = EconomyTargets.resolve(StringArgumentType.getString(ctx, "target"), ctx.getSource());
		// 预检每个目标余额，给出具体提示。
		for (EconomyTargets.ResolvedTarget target : targets) {
			if (readBalance(target.uuid()) < amount) {
				throw new SimpleCommandExceptionType(Component.literal(
						target.displayName() + " 的余额不足，当前资金：" + Money.format(readBalance(target.uuid())) + " 元"))
						.create();
			}
		}
		List<UUID> uuids = targets.stream().map(EconomyTargets.ResolvedTarget::uuid).toList();
		boolean ok;
		try {
			ok = EconomyDb.deductMany(uuids, amount);
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("eco remove 数据库错误", e);
			throw DB_ERROR.create();
		}
		if (!ok) {
			throw DB_ERROR.create();
		}
		MutableComponent summary;
		if (targets.size() == 1) {
			summary = text("已从 ", ChatFormatting.GREEN)
					.append(targets.get(0).displayName()).append(" 扣除 ")
					.append(Money.format(amount)).append(" 元，当前资金：")
					.append(Money.format(readBalance(targets.get(0).uuid()))).append(" 元");
		} else {
			summary = text("已从 " + targets.size() + " 个目标各扣除 ", ChatFormatting.GREEN)
					.append(Money.format(amount)).append(" 元");
		}
		source.sendSuccess(() -> summary, false);
		notifyOnlineTargets(targets, amount, "你的资金被管理员扣除了 ", " 元");
		return 1;
	}

	/** /eco set 目标 金额 —— 把目标资金设置为指定值（允许 0）。 */
	private static int ecoSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		long amount = Money.parseCentsAllowZero(StringArgumentType.getString(ctx, "amount"));
		List<EconomyTargets.ResolvedTarget> targets = EconomyTargets.resolve(StringArgumentType.getString(ctx, "target"), ctx.getSource());
		try {
			for (EconomyTargets.ResolvedTarget target : targets) {
				EconomyDb.setBalance(target.uuid(), target.displayName(), amount);
			}
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("eco set 数据库错误", e);
			throw DB_ERROR.create();
		}
		MutableComponent summary;
		if (targets.size() == 1) {
			summary = text("已将 ", ChatFormatting.GREEN)
					.append(targets.get(0).displayName()).append(" 的资金设置为 ")
					.append(Money.format(amount)).append(" 元");
		} else {
			summary = text("已将 " + targets.size() + " 个目标的资金设置为 ", ChatFormatting.GREEN)
					.append(Money.format(amount)).append(" 元");
		}
		source.sendSuccess(() -> summary, false);
		notifyOnlineTargets(targets, amount, "你的资金被管理员设置为 ", " 元");
		return 1;
	}

	private static void notifyOnlineTargets(List<EconomyTargets.ResolvedTarget> targets,
			long amount, String prefix, String suffix) {
		for (EconomyTargets.ResolvedTarget target : targets) {
			if (target.onlinePlayer() != null) {
				target.onlinePlayer().sendSystemMessage(text(prefix, ChatFormatting.GREEN)
						.append(Money.format(amount)).append(suffix), false);
			}
		}
	}

	// ---------- /peco ----------

	/** /peco add 金额 —— 给服务器资产增加资金。 */
	private static int pecoAdd(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		long amount = parseAmount(ctx);
		try {
			EconomyDb.credit(EconomyDb.SERVER_ACCOUNT_UUID, EconomyDb.SERVER_ACCOUNT_NAME, amount);
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("peco add 数据库错误", e);
			throw DB_ERROR.create();
		}
		long balance = readBalance(EconomyDb.SERVER_ACCOUNT_UUID);
		source.sendSuccess(() -> text("已向服务器资产增加 ", ChatFormatting.GREEN)
				.append(Money.format(amount)).append(" 元，当前：")
				.append(Money.format(balance)).append(" 元"), false);
		return 1;
	}

	/** /peco remove 金额 —— 从服务器资产扣除资金。 */
	private static int pecoRemove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		long amount = parseAmount(ctx);
		long balance = readBalance(EconomyDb.SERVER_ACCOUNT_UUID);
		if (balance < amount) {
			throw new SimpleCommandExceptionType(Component.literal(
					"服务器资产不足，当前资金：" + Money.format(balance) + " 元")).create();
		}
		try {
			if (!EconomyDb.deductMany(List.of(EconomyDb.SERVER_ACCOUNT_UUID), amount)) {
				throw DB_ERROR.create();
			}
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("peco remove 数据库错误", e);
			throw DB_ERROR.create();
		}
		long balanceAfter = readBalance(EconomyDb.SERVER_ACCOUNT_UUID);
		source.sendSuccess(() -> text("已从服务器资产扣除 ", ChatFormatting.GREEN)
				.append(Money.format(amount)).append(" 元，当前：")
				.append(Money.format(balanceAfter)).append(" 元"), false);
		return 1;
	}

	/** /peco set 金额 —— 把服务器资产设置为指定值（允许 0）。 */
	private static int pecoSet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		long amount = Money.parseCentsAllowZero(StringArgumentType.getString(ctx, "amount"));
		try {
			EconomyDb.setBalance(EconomyDb.SERVER_ACCOUNT_UUID, EconomyDb.SERVER_ACCOUNT_NAME, amount);
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("peco set 数据库错误", e);
			throw DB_ERROR.create();
		}
		source.sendSuccess(() -> text("已将服务器资产设置为 ", ChatFormatting.GREEN)
				.append(Money.format(amount)).append(" 元"), false);
		return 1;
	}

	// ---------- /announcement ----------

	private static int setAnnouncement(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String content = StringArgumentType.getString(ctx, "content");
		try {
			EconomyDb.setAnnouncement(content);
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("announcement 数据库错误", e);
			throw DB_ERROR.create();
		}
		ctx.getSource().sendSuccess(() -> text("公告已设置", ChatFormatting.GREEN), false);
		return 1;
	}

	private static int clearAnnouncement(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		try {
			EconomyDb.setAnnouncement(null);
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("announcement 数据库错误", e);
			throw DB_ERROR.create();
		}
		ctx.getSource().sendSuccess(() -> text("公告已清除", ChatFormatting.GREEN), false);
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

	private static long parseAmount(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		return Money.parseCents(StringArgumentType.getString(ctx, "amount"));
	}

	/** 档案可能没有 UUID（仅缓存的离线名字），此时按原版规则生成离线 UUID。 */
	private static UUID resolveUuid(NameAndId profile) {
		return profile.id() != null ? profile.id() : NameAndId.createOffline(profile.name()).id();
	}

	private static long readBalance(UUID uuid) throws CommandSyntaxException {
		try {
			return EconomyDb.getBalance(uuid);
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("读取余额失败", e);
			throw DB_ERROR.create();
		}
	}

	private static int countOrThrow() throws CommandSyntaxException {
		try {
			return EconomyDb.accountCount();
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("查询账户总数失败", e);
			throw DB_ERROR.create();
		}
	}

	private static List<EconomyDb.AccountEntry> topOrThrow(int page) throws CommandSyntaxException {
		try {
			return EconomyDb.topAccounts(PAGE_SIZE, (page - 1) * PAGE_SIZE);
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("查询排行榜失败", e);
			throw DB_ERROR.create();
		}
	}

	private static boolean transferOrThrow(UUID from, UUID to, String toName, long amount, String op)
			throws CommandSyntaxException {
		try {
			return EconomyDb.transfer(from, to, toName, amount);
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error(op + " 数据库错误", e);
			throw DB_ERROR.create();
		}
	}

	private static boolean transferManyOrThrow(UUID from, List<UUID> targets, List<String> names, long amount)
			throws CommandSyntaxException {
		try {
			return EconomyDb.transferMany(from, targets, names, amount);
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("pay 数据库错误", e);
			throw DB_ERROR.create();
		}
	}

	private static MutableComponent text(String content, ChatFormatting color) {
		return Component.literal(content).withStyle(color);
	}
}
