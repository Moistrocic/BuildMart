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
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 红包指令：/hongbao 总金额 数量 口令（发红包，全员广播）。
 * <p>
 * 领取不走指令：玩家在聊天中说出口令（消息与口令完全一致）即自动领取
 * （聊天监听见 {@code ServerGamePacketListenerImplMixin.economy$hongbaoChat}）。
 * 领取金额在 1 分 ~ (总金额 / 数量) × 2 之间随机波动；最后一个红包领取剩余全部
 * （保证总额守恒）。每次领取后向全体玩家广播领取金额与剩余个数。
 * 红包存于内存：服务器停机或同口令覆盖时，未领取部分返还给发红包人。
 */
public final class HongbaoCommands {
	private static final SimpleCommandExceptionType PLAYER_ONLY =
			new SimpleCommandExceptionType(Component.literal("该指令只能由玩家执行"));
	private static final SimpleCommandExceptionType DB_ERROR =
			new SimpleCommandExceptionType(Component.literal("数据库错误，请稍后再试"));
	private static final SimpleCommandExceptionType PAYER_INSUFFICIENT =
			new SimpleCommandExceptionType(Component.literal("你的资金不足"));
	private static final SimpleCommandExceptionType AMOUNT_INVALID =
			new SimpleCommandExceptionType(Component.literal("红包总金额必须大于 0"));
	private static final SimpleCommandExceptionType AMOUNT_TOO_SMALL =
			new SimpleCommandExceptionType(Component.literal("总金额不足以分成该数量的红包（每个至少 0.01 元）"));

	/** 口令 -> 红包（内存存储，重启作废）。 */
	private static final Map<String, Hongbao> HONGBAOS = new ConcurrentHashMap<>();
	private static final Random RANDOM = new Random();

	private static final class Hongbao {
		final UUID ownerUuid;
		final String ownerName;
		final long totalCents;
		final int totalCount;
		long remainingCents;
		int remainingCount;

		Hongbao(UUID ownerUuid, String ownerName, long totalCents, int totalCount) {
			this.ownerUuid = ownerUuid;
			this.ownerName = ownerName;
			this.totalCents = totalCents;
			this.totalCount = totalCount;
			this.remainingCents = totalCents;
			this.remainingCount = totalCount;
		}
	}

	private HongbaoCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		// 发红包：/hongbao 总金额 数量 口令（领取通过聊天发言，见 economy$hongbaoChat）
		dispatcher.register(Commands.literal("hongbao")
				.then(Commands.argument("amount", StringArgumentType.word())
						.then(Commands.argument("count", IntegerArgumentType.integer(1))
								.then(Commands.argument("口令", StringArgumentType.word())
										.executes(HongbaoCommands::create)))));
	}

	// ---------- 发红包 ----------

	private static int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		long total = Money.parseCents(StringArgumentType.getString(ctx, "amount"));
		int count = IntegerArgumentType.getInteger(ctx, "count");
		String pass = StringArgumentType.getString(ctx, "口令");
		if (total <= 0) {
			throw AMOUNT_INVALID.create();
		}
		if (total < count) {
			throw AMOUNT_TOO_SMALL.create();
		}
		boolean ok;
		try {
			ok = EconomyDb.deduct(player.getUUID(), total);
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("hongbao 扣款数据库错误", e);
			throw DB_ERROR.create();
		}
		if (!ok) {
			throw PAYER_INSUFFICIENT.create();
		}
		// 同口令覆盖：旧红包失效，剩余金额返还给原发红包人
		Hongbao prev = HONGBAOS.put(pass, new Hongbao(player.getUUID(), player.getGameProfile().name(), total, count));
		broadcast(source.getServer(), Component.literal("[红包] " + player.getGameProfile().name()
				+ " 发出红包：共 " + Money.format(total) + " 元，共 " + count + " 个！"
				+ "输入 /hongbao " + pass + " 领取").withStyle(ChatFormatting.GOLD));
		if (prev != null) {
			refund(source.getServer(), prev, "被新的红包覆盖");
		}
		return 1;
	}

	// ---------- 领取（聊天发言触发） ----------

	/** 发言是否与某个红包口令完全一致（trim 后精确匹配）。 */
	public static boolean isPass(String message) {
		if (message == null) {
			return false;
		}
		String trimmed = message.trim();
		for (String pass : HONGBAOS.keySet()) {
			if (pass.equals(trimmed)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 按口令领取红包（聊天监听调用）。返回 null 表示领取成功；
	 * 否则返回错误提示（红包不存在/已领完/入账失败）。
	 */
	public static String claimByPass(ServerPlayer player, String pass, MinecraftServer server) {
		Hongbao hb = HONGBAOS.get(pass);
		if (hb == null) {
			return "红包不存在或已领完";
		}
		long amount;
		int remain;
		synchronized (hb) {
			if (hb.remainingCount <= 0) {
				HONGBAOS.remove(pass);
				return "该红包已被领完";
			}
			if (hb.remainingCount == 1) {
				// 最后一个红包：领取剩余全部，保证总额守恒
				amount = hb.remainingCents;
			} else {
				// 随机范围 1 ~ (总金额 / 数量) × 2，且至少给后面的红包留 1 分
				long upper = (hb.totalCents / hb.totalCount) * 2;
				long max = Math.min(upper, hb.remainingCents - 1);
				amount = 1 + RANDOM.nextLong(max);
			}
			hb.remainingCents -= amount;
			hb.remainingCount--;
			remain = hb.remainingCount;
			if (remain == 0) {
				HONGBAOS.remove(pass);
			}
		}
		try {
			EconomyDb.credit(player.getUUID(), player.getGameProfile().name(), amount);
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("hongbao 入账数据库错误", e);
			return "红包入账失败，请稍后再试";
		}
		String suffix = remain > 0 ? "，红包剩余 " + remain + " 个" : "，红包已领完";
		broadcast(server, Component.literal("[红包] " + player.getGameProfile().name()
				+ " 领到 " + Money.format(amount) + " 元" + suffix).withStyle(ChatFormatting.GOLD));
		return null;
	}

	// ---------- 工具 ----------

	/** 红包失效：剩余金额返还给发红包人（在线时通知），并记录日志。 */
	private static void refund(MinecraftServer server, Hongbao hb, String reason) {
		long remaining = hb.remainingCents;
		if (remaining <= 0) {
			return;
		}
		try {
			EconomyDb.credit(hb.ownerUuid, hb.ownerName, remaining);
		} catch (EconomyDb.DatabaseException e) {
			Economy.LOGGER.error("hongbao 返还数据库错误", e);
			return;
		}
		ServerPlayer owner = server.getPlayerList().getPlayer(hb.ownerUuid);
		if (owner != null) {
			owner.sendSystemMessage(Component.literal("[红包] 你的红包已失效（" + reason + "），剩余 "
					+ Money.format(remaining) + " 元已返还").withStyle(ChatFormatting.YELLOW), false);
		}
	}

	/** 服务器停机：所有未领取红包作废，剩余金额统一返还给发红包人。 */
	public static void refundAll(MinecraftServer server) {
		for (Hongbao hb : HONGBAOS.values()) {
			refund(server, hb, "服务器关闭");
		}
		HONGBAOS.clear();
	}

	private static void broadcast(MinecraftServer server, Component message) {
		server.getPlayerList().broadcastSystemMessage(message, false);
	}

	private static ServerPlayer requirePlayer(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			throw PLAYER_ONLY.create();
		}
		return player;
	}
}
