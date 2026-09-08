package mois.buildmart.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import mois.buildmart.BuildMart;
import mois.buildmart.Money;
import mois.buildmart.data.EconomyDb;
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
	private static final SimpleCommandExceptionType PAY_NO_TARGET =
			new SimpleCommandExceptionType(Component.literal("没有可转账的玩家"));
	private static final SimpleCommandExceptionType AMOUNT_TOO_LARGE =
			new SimpleCommandExceptionType(Component.literal("金额过大"));

	private static final String[] HELP_LINES = {
			"/bmhelp 页码 -  查看BuildMart帮助",
			"/bal - 查看自己的资金",
			"/bal 玩家 - 查看自己的资金",
			"/pay 玩家 金额 - 向玩家支付",
			"/baltop 页码 -  查看资金排行榜",
			"/shop create - 创建出售商店",
			"/shop remove - 移除出售商店",
			"/shop setpayee 玩家 - 设置收款人",
			"/price 物品 - 查看物品价格",
			"/buy 物品 数量 - 购买物品",
			"/buypack 物品 盒数 - 购买一盒物品",
			"/bm - 进入快捷购买模式",
			"/fastbuy - 快速投影购买开关（投影中键无物品时自动购买一组；需客户端安装本模组 + litematica）",
			"/fly - 开启或关闭飞行模式",
			"/fly warn - 开启或关闭飞行提醒",
			"/home 名称 - 传送回家",
			"/sethome 名称 - 设置家",
			"/delhome 名称 - 删除家",
			"/listhome 页码 - 查看家列表",
			"/tpa 玩家 - 请求传送到玩家位置",
			"/tpahere 玩家 - 请求玩家传送到自己位置",
			"/tpaccept - 接受最近的传送请求",
			"/back - 回到最近死亡点",
			"/suicide - 自杀",
			"/hongbao 总金额 数量 口令 - 发红包（聊天说出口令即可领取）",
			"/spawner info - 查看刷怪笼信息",
			"/spawner upgrade - 升级刷怪笼",
			"/spawner set 参数 值 - 设置刷怪笼参数/配置",
			"/spawner hopper add 物品 - 漏斗白名单添加物品（白名单物品自动放入相邻箱子）",
			"/spawner hopper remove 物品 - 漏斗白名单移除物品",
			"/spawner hopper list 物品 - 查看漏斗白名单",
			"/spawner take - 取出刷怪笼存储的转化掉落物",
			"/balop start - 启动数据库管理前端（仅管理员）",
			"/balop stop - 关闭数据库管理前端（仅管理员）",
			"/fixweather - 固定天气（不再自然变化；再次输入恢复）",
			"/fixtime - 固定时间（昼夜不再流动；再次输入恢复）",
			"/naturalmonsterspawn true/false - 控制自然怪物生成（不含刷怪笼；不填参数查询当前状态）"
	};

	private EconomyCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		BalshopCommands.register(dispatcher, buildContext);
		FlyCommands.register(dispatcher, buildContext);
		TeleportCommands.register(dispatcher, buildContext);
		HongbaoCommands.register(dispatcher, buildContext);
		ConfigCommands.register(dispatcher, buildContext);
		SpawnerCommands.register(dispatcher, buildContext);
		FastbuyCommands.register(dispatcher, buildContext);
		RuleCommands.register(dispatcher, buildContext);

		dispatcher.register(Commands.literal("suicide")
				.executes(EconomyCommands::suicide));

		dispatcher.register(Commands.literal("bal")
				.executes(ctx -> showBalance(ctx, null))
				.then(Commands.argument("player", GameProfileArgument.gameProfile())
						.executes(ctx -> showBalance(ctx, GameProfileArgument.getGameProfiles(ctx, "player")))));

		dispatcher.register(Commands.literal("pay")
				.then(Commands.argument("targets", GameProfileArgument.gameProfile())
						.then(Commands.argument("amount", StringArgumentType.word())
								.executes(EconomyCommands::pay))));

		dispatcher.register(Commands.literal("baltop")
				.executes(ctx -> showTop(ctx.getSource(), 1))
				.then(Commands.argument("page", IntegerArgumentType.integer(1))
						.executes(ctx -> showTop(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))));

		dispatcher.register(Commands.literal("bmhelp")
				.executes(ctx -> showHelp(ctx.getSource(), 1))
				.then(Commands.argument("page", IntegerArgumentType.integer(1))
						.executes(ctx -> showHelp(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "page")))));

		dispatcher.register(Commands.literal("announcement")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.argument("content", StringArgumentType.greedyString())
						.executes(EconomyCommands::setAnnouncement))
				.then(Commands.literal("clear")
						.executes(EconomyCommands::clearAnnouncement)));

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

		// /balop start|stop —— 数据库管理前端（仅管理员）
		dispatcher.register(Commands.literal("balop")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.literal("start")
						.executes(EconomyCommands::balopStart))
				.then(Commands.literal("stop")
						.executes(EconomyCommands::balopStop)));

	}

	// ---------- /balop ----------

	/**
	 * /balop start —— 开启管理会话（仅管理员，需玩家身份）：每个管理员获得独立
	 * 会话（随机 token），访问地址为可点击链接；会话 5 分钟无请求自动关闭。
	 */
	private static int balopStart(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = requirePlayer(ctx.getSource());
		// 展示主机优先域名（balop.domain）→ 公网 IP（balop.publicIp）→ 本机 IPv4/localhost
		// （仅影响返回链接，监听地址仍是 balop.host）
		String result = mois.buildmart.balop.BalopServer.start(player.getUUID(),
				player.getGameProfile().name(),
				mois.buildmart.config.EconomyConfig.balopHost(),
				mois.buildmart.config.EconomyConfig.balopPort(),
				mois.buildmart.config.EconomyConfig.balopDisplayHost());
		// 成功返回 http:// 开头的访问地址；其余一律为错误提示（直接展示给玩家）
		if (result == null || !result.startsWith("http://")) {
			ctx.getSource().sendFailure(Component.literal(result == null ? "未知错误" : result));
			return 0;
		}
		String url = result;
		ctx.getSource().sendSuccess(() -> Component.literal("数据库管理前端已启动：")
				.withStyle(ChatFormatting.GREEN)
				.append(Component.literal(url)
						.withStyle(style -> style.withColor(ChatFormatting.AQUA)
								.withClickEvent(new net.minecraft.network.chat.ClickEvent.OpenUrl(
										java.net.URI.create(url)))
								.withHoverEvent(new net.minecraft.network.chat.HoverEvent.ShowText(
										Component.literal("点击打开管理面板"))))), true);
		return 1;
	}

	/**
	 * /balop stop —— 关闭自己（执行者）的全部管理会话；不影响其他管理员的会话。
	 */
	private static int balopStop(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = requirePlayer(ctx.getSource());
		int closed = mois.buildmart.balop.BalopServer.stop(player.getUUID());
		if (closed <= 0) {
			ctx.getSource().sendFailure(Component.literal("你没有开启中的管理前端会话"));
			return 0;
		}
		ctx.getSource().sendSuccess(() -> Component.literal(
				"已关闭 " + closed + " 个管理前端会话").withStyle(ChatFormatting.GREEN), true);
		return 1;
	}

	// ---------- /suicide ----------

	/** /suicide —— 直接自杀（致命伤害；死亡点由 /back 自动记录）。 */
	private static int suicide(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerPlayer player = requirePlayer(ctx.getSource());
		player.hurtServer(player.level(), player.damageSources().genericKill(), Float.MAX_VALUE);
		return 1;
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
		// 资金流水：转出方一条（总额）、每个目标一条（单份金额）
		try {
			long total = Math.multiplyExact(amount, targets.size());
			EconomyDb.recordMoneyLog(sender.getUUID(), sender.getGameProfile().name(),
					EconomyDb.TYPE_TRANSFER_OUT, EconomyDb.CHANNEL_PAY,
					"转账给 " + String.join("、", names), -total);
			for (int i = 0; i < uuids.size(); i++) {
				EconomyDb.recordMoneyLog(uuids.get(i), names.get(i),
						EconomyDb.TYPE_TRANSFER_IN, EconomyDb.CHANNEL_PAY,
						"收到 " + sender.getGameProfile().name() + " 的转账", amount);
			}
		} catch (RuntimeException ignored) {
			// 记录失败静默，不影响转账结果。
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
		if (page == 1) {
			// 首页顶部显示服务器总资产（所有玩家余额之和）
			message.append(text("服务器总资产：", ChatFormatting.GOLD)
					.append(Money.format(totalAssetsOrThrow())).append(" 元\n"));
		}
		int rank = (page - 1) * PAGE_SIZE + 1;
		for (EconomyDb.AccountEntry entry : entries) {
			message.append(String.valueOf(rank++)).append(". ")
					.append(entry.name()).append(" - ")
					.append(Money.format(entry.balance())).append(" 元\n");
		}
		source.sendSuccess(() -> message, false);
		return 1;
	}

	// ---------- /bmhelp ----------

	private static int showHelp(CommandSourceStack source, int page) throws CommandSyntaxException {
		int pages = Math.max(1, (HELP_LINES.length + PAGE_SIZE - 1) / PAGE_SIZE);
		if (page > pages) {
			throw new SimpleCommandExceptionType(Component.literal("页码超出范围，共 " + pages + " 页")).create();
		}
		MutableComponent message = text("=== BuildMart 帮助 第 " + page + "/" + pages + " 页 ===", ChatFormatting.GOLD)
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
				logQuietly(target.uuid(), target.displayName(),
						EconomyDb.TYPE_ADMIN_ADD, EconomyDb.CHANNEL_ECO, "管理员加钱", amount);
			}
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("eco add 数据库错误", e);
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
			BuildMart.LOGGER.error("eco remove 数据库错误", e);
			throw DB_ERROR.create();
		}
		if (!ok) {
			throw DB_ERROR.create();
		}
		for (EconomyTargets.ResolvedTarget target : targets) {
			logQuietly(target.uuid(), target.displayName(),
					EconomyDb.TYPE_ADMIN_SUB, EconomyDb.CHANNEL_ECO, "管理员扣钱", -amount);
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
				long oldBalance = EconomyDb.getBalance(target.uuid());
				EconomyDb.setBalance(target.uuid(), target.displayName(), amount);
				logQuietly(target.uuid(), target.displayName(),
						EconomyDb.TYPE_ADMIN_SET, EconomyDb.CHANNEL_ECO, "管理员设置余额", amount - oldBalance);
			}
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("eco set 数据库错误", e);
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

	// ---------- /announcement ----------

	private static int setAnnouncement(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String content = StringArgumentType.getString(ctx, "content");
		try {
			EconomyDb.setAnnouncement(content);
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("announcement 数据库错误", e);
			throw DB_ERROR.create();
		}
		ctx.getSource().sendSuccess(() -> text("公告已设置", ChatFormatting.GREEN), false);
		return 1;
	}

	private static int clearAnnouncement(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		try {
			EconomyDb.setAnnouncement(null);
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("announcement 数据库错误", e);
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
			BuildMart.LOGGER.error("读取余额失败", e);
			throw DB_ERROR.create();
		}
	}

	private static int countOrThrow() throws CommandSyntaxException {
		try {
			return EconomyDb.accountCount();
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("查询账户总数失败", e);
			throw DB_ERROR.create();
		}
	}

	private static List<EconomyDb.AccountEntry> topOrThrow(int page) throws CommandSyntaxException {
		try {
			return EconomyDb.topAccounts(PAGE_SIZE, (page - 1) * PAGE_SIZE);
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("查询排行榜失败", e);
			throw DB_ERROR.create();
		}
	}

	private static long totalAssetsOrThrow() throws CommandSyntaxException {
		try {
			return EconomyDb.totalPlayerAssets();
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("查询总资产失败", e);
			throw DB_ERROR.create();
		}
	}

	private static boolean transferOrThrow(UUID from, UUID to, String toName, long amount, String op)
			throws CommandSyntaxException {
		try {
			return EconomyDb.transfer(from, to, toName, amount);
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error(op + " 数据库错误", e);
			throw DB_ERROR.create();
		}
	}

	private static boolean transferManyOrThrow(UUID from, List<UUID> targets, List<String> names, long amount)
			throws CommandSyntaxException {
		try {
			return EconomyDb.transferMany(from, targets, names, amount);
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("pay 数据库错误", e);
			throw DB_ERROR.create();
		}
	}

	private static MutableComponent text(String content, ChatFormatting color) {
		return Component.literal(content).withStyle(color);
	}

	/** 记录资金流水；失败静默（记录不应影响资金操作结果）。 */
	private static void logQuietly(UUID uuid, String name, String type, String channel,
			String description, long price) {
		try {
			EconomyDb.recordMoneyLog(uuid, name, type, channel, description, price);
		} catch (EconomyDb.DatabaseException ignored) {
			// 记录失败静默。
		}
	}
}
