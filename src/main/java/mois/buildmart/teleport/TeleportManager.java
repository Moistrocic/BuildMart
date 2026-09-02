package mois.buildmart.teleport;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import mois.buildmart.BuildMart;
import mois.buildmart.Money;
import mois.buildmart.config.EconomyConfig;
import mois.buildmart.data.EconomyDb;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.phys.Vec3;

/**
 * 传送系统核心：费用计算（固定/按距离/跨维度）、传送冷却、tpa 请求管理与执行。
 * 费用规则（EconomyConfig 的 TpFees）：
 * <ul>
 * <li>fixedFee 开启 → 固定收 fixedFeeAmount（一次）；</li>
 * <li>否则同维度 → perDistanceFee × 距离（向上取整）；</li>
 * <li>跨维度 → 额外收 crossDimensionFee（跨维度不计距离）。</li>
 * </ul>
 * 冷却按“被传送者”记录（tpa 中请求方被传送 / tpahere 中被请求人被传送）。
 * tpa/tpahere 的费用始终由“请求方”承担，被请求人不收费。
 */
public final class TeleportManager {
	/** tpa 待处理请求（键 = 被请求人，即接受者）。 */
	public record PendingRequest(long seq, UUID requesterUuid, String requesterName, boolean toTarget, long expireTick) {
	}

	/** 传送执行结果：ok=false 时 message 为可读失败原因；cost 为实际扣费（分，0 表示免费）。 */
	public record TpOutcome(boolean ok, String message, long cost) {
	}

	private static final AtomicLong SEQ = new AtomicLong();
	private static final Map<UUID, Long> HOME_COOLDOWN = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> TPA_COOLDOWN = new ConcurrentHashMap<>();
	private static final Map<UUID, Long> BACK_COOLDOWN = new ConcurrentHashMap<>();
	private static final Map<UUID, List<PendingRequest>> REQUESTS = new ConcurrentHashMap<>();

	private TeleportManager() {
	}

	public static Map<UUID, Long> homeCooldowns() {
		return HOME_COOLDOWN;
	}

	public static Map<UUID, Long> tpaCooldowns() {
		return TPA_COOLDOWN;
	}

	public static Map<UUID, Long> backCooldowns() {
		return BACK_COOLDOWN;
	}

	// ---------- tpa 请求 ----------

	/** 请求传送：requester 向 target 发出请求（toTarget=true 表示传送到 target 的位置）。 */
	public static void request(ServerPlayer requester, ServerPlayer target, boolean toTarget, MinecraftServer server) {
		long expire = server.getTickCount() + Math.max(20, EconomyConfig.tpaSettings().timeoutSeconds() * 20L);
		PendingRequest request = new PendingRequest(SEQ.incrementAndGet(), requester.getUUID(),
				requester.getGameProfile().name(), toTarget, expire);
		REQUESTS.computeIfAbsent(target.getUUID(), k -> new ArrayList<>())
				.removeIf(r -> r.requesterUuid().equals(requester.getUUID()));
		REQUESTS.computeIfAbsent(target.getUUID(), k -> new ArrayList<>()).add(request);
	}

	/** 接受者视角下最近（seq 最大）且未过期的请求；无则返回 null。 */
	public static PendingRequest latestRequest(UUID targetUuid, MinecraftServer server) {
		List<PendingRequest> list = REQUESTS.get(targetUuid);
		if (list == null || list.isEmpty()) {
			return null;
		}
		long now = server.getTickCount();
		return list.stream().filter(r -> r.expireTick() > now)
				.max(Comparator.comparingLong(PendingRequest::seq)).orElse(null);
	}

	/**
	 * 接受最近请求并执行传送。费用始终由请求方承担（“收费目标为自己”），
	 * 冷却记在被传送者身上：
	 * <ul>
	 * <li>/tpa（toTarget=true）：请求方被传送到被请求方位置；</li>
	 * <li>/tpahere（toTarget=false）：被请求方被传送到请求方位置。</li>
	 * </ul>
	 * 成功/失败通知：被请求方（执行 /tpaccept 者）与请求方（付费方）都会收到对应消息。
	 */
	public static TpOutcome accept(ServerPlayer accepter, MinecraftServer server) {
		PendingRequest request = latestRequest(accepter.getUUID(), server);
		if (request == null) {
			return new TpOutcome(false, "没有待接受的传送请求", 0);
		}
		ServerPlayer requester = server.getPlayerList().getPlayer(request.requesterUuid());
		if (requester == null) {
			return new TpOutcome(false, "请求方已不在线", 0);
		}
		boolean toTarget = request.toTarget();
		ServerPlayer mover = toTarget ? requester : accepter;
		ServerPlayer destination = toTarget ? accepter : requester; // 传送到谁的位置
		TpOutcome outcome = teleportAndCharge(mover, request.requesterUuid(), request.requesterName(),
				destination.level(), destination.position(), EconomyConfig.tpaSettings().fees(),
				TPA_COOLDOWN, server);
		if (outcome.ok()) {
			List<PendingRequest> list = REQUESTS.get(accepter.getUUID());
			if (list != null) {
				list.removeIf(r -> r.requesterUuid().equals(request.requesterUuid()));
			}
			String payerNotice = outcome.cost() > 0
					? "（费用 " + Money.format(outcome.cost()) + " 元由请求方 " + request.requesterName() + " 支付）"
					: "（本次免费）";
			accepter.sendSystemMessage(Component.literal("已接受传送请求" + payerNotice)
					.withStyle(ChatFormatting.GREEN), false);
			// 付费方（请求方）确认扣费；与 accepter 是同一人时不重复提示
			if (!requester.getUUID().equals(accepter.getUUID())) {
				requester.sendSystemMessage(Component.literal(outcome.cost() > 0
								? "传送完成，已扣除 " + Money.format(outcome.cost()) + " 元"
								: "传送完成（本次免费）")
						.withStyle(ChatFormatting.GREEN), false);
			}
			return outcome;
		}
		// 失败：付费方（请求方）收到原始措辞（“你的资金不足”对请求方是准确的）；
		// 被请求方（执行 /tpaccept 者）若非付费方，提示要区分“对方”，避免误以为自己的资金不足。
		if (requester.getUUID().equals(accepter.getUUID())) {
			return outcome;
		}
		requester.sendSystemMessage(Component.literal("传送失败：" + outcome.message())
				.withStyle(ChatFormatting.RED), false);
		String accepterMessage = outcome.message().startsWith("你的资金不足")
				? "对方的资金不足" + outcome.message().substring("你的资金不足".length())
				: outcome.message();
		return new TpOutcome(false, accepterMessage, 0);
	}

	/** 过期请求清理（服务端 tick 调用）。 */
	public static void onServerTick(MinecraftServer server) {
		if (server.getTickCount() % 20 != 0) {
			return;
		}
		long now = server.getTickCount();
		for (UUID key : new ArrayList<>(REQUESTS.keySet())) {
			List<PendingRequest> list = REQUESTS.get(key);
			if (list != null) {
				list.removeIf(r -> r.expireTick() <= now);
				if (list.isEmpty()) {
					REQUESTS.remove(key);
				}
			}
		}
	}

	// ---------- 死亡点 ----------

	/** 玩家死亡时记录死亡点（/back 配置关闭时不记录）。 */
	public static void recordDeath(ServerPlayer player) {
		if (!EconomyConfig.backSettings().enabled()) {
			return;
		}
		try {
			Vec3 pos = player.position();
			EconomyDb.setBackPoint(player.getUUID(),
					player.level().dimension().identifier().toString(),
					pos.x(), pos.y(), pos.z());
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("记录死亡点失败", e);
		}
	}

	// ---------- 执行 ----------

	/**
	 * 检查冷却 → 计算费用 → 扣款 → 传送。
	 *
	 * @param mover      被传送的玩家（冷却记在他身上）
	 * @param payerUuid  付费玩家（tpa 中为请求方）
	 * @param payerName  付费玩家名
	 * @param toLevel    目标维度
	 * @param toPos      目标坐标
	 * @param fees       费用/冷却配置
	 * @param cooldownMap 冷却记录表（按功能分开）
	 */
	public static TpOutcome teleportAndCharge(ServerPlayer mover, UUID payerUuid, String payerName,
			ServerLevel toLevel, Vec3 toPos, EconomyConfig.TpFees fees,
			Map<UUID, Long> cooldownMap, MinecraftServer server) {
		long now = server.getTickCount();
		long last = cooldownMap.getOrDefault(mover.getUUID(), 0L);
		int cooldownTicks = fees.cooldownSeconds() * 20;
		if (now - last < cooldownTicks) {
			long remain = (cooldownTicks - (now - last) + 19) / 20;
			return new TpOutcome(false, "传送冷却中，剩余 " + remain + " 秒", 0);
		}
		long cost = fee(fees, mover.level(), mover.position(), toLevel, toPos);
		if (cost > 0) {
			long balance;
			try {
				balance = EconomyDb.getBalance(payerUuid);
			} catch (EconomyDb.DatabaseException e) {
				BuildMart.LOGGER.error("传送扣费读取余额失败", e);
				return new TpOutcome(false, "数据库错误，请稍后再试", 0);
			}
			if (balance < cost) {
				return new TpOutcome(false, "你的资金不足（传送费用 " + Money.format(cost)
						+ " 元，当前资金 " + Money.format(balance) + " 元）", 0);
			}
			try {
				if (!EconomyDb.deduct(payerUuid, cost)) {
					return new TpOutcome(false, "你的资金不足（传送费用 " + Money.format(cost) + " 元）", 0);
				}
			} catch (EconomyDb.DatabaseException e) {
				BuildMart.LOGGER.error("传送扣费失败", e);
				return new TpOutcome(false, "数据库错误，请稍后再试", 0);
			}
			// 资金流水；记录失败静默
			try {
				EconomyDb.recordMoneyLog(payerUuid, EconomyDb.accountName(payerUuid),
						EconomyDb.TYPE_FEE, EconomyDb.CHANNEL_TP, "传送费用", -cost);
			} catch (EconomyDb.DatabaseException ignored) {
				// 记录失败静默。
			}
		}
		mover.teleportTo(toLevel, toPos.x(), toPos.y(), toPos.z(), Set.of(),
				mover.getYRot(), mover.getXRot(), false);
		cooldownMap.put(mover.getUUID(), now);
		return new TpOutcome(true, cost > 0 ? "已传送，费用 " + Money.format(cost) + " 元" : "已传送", cost);
	}

	// ---------- 费用 ----------

	/** 传送费用（分）：固定收费优先；否则同维度按距离，跨维度收跨维度费。 */
	private static long fee(EconomyConfig.TpFees fees, ServerLevel fromLevel, Vec3 fromPos,
			ServerLevel toLevel, Vec3 toPos) {
		if (fees.fixedFee()) {
			return fees.fixedFeeAmountCents();
		}
		if (fromLevel.dimension().equals(toLevel.dimension())) {
			return satMul((long) Math.ceil(fromPos.distanceTo(toPos)), fees.perDistanceFeeCents());
		}
		return fees.crossDimensionFeeCents(); // 跨维度：额外收跨维度费，不再计距离
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
