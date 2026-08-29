package mois.economy.spawner;

import mois.economy.data.EconomyDb;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;

/**
 * 刷怪笼玩法（休闲经济闭环）：
 * <ul>
 * <li>获取：趣味钓鱼战利品可配置产出刷怪笼（空笼物品，未定价不可交易防倒卖）；</li>
 * <li>绑定：放置空刷怪笼后手持刷怪蛋右键 → 绑定实体类型（消耗一个蛋）；</li>
 * <li>升级：{@code /spawner upgrade} 对准刷怪笼，纯金钱升级（扣款 + 流水
 *   SPAWNER_UPGRADE/SPAWNER），等级越高生成越快、数量越多、范围越大；</li>
 * <li>回收：玩家用镐破坏刷怪笼掉落带完整数据（类型+等级参数）的物品，
 *   放置即恢复，升级投入不白费；</li>
 * <li>产出：刷怪掉落物按物品定价表出售（商店/便捷购买）形成赚钱闭环。</li>
 * </ul>
 * 等级 = 由当前生成参数反推（参数完全由等级公式决定，幂等可推）。
 */
public final class SpawnerManager {
	/** 最高等级。 */
	public static final int MAX_LEVEL = 10;
	/** 基础生成参数（等级 1，与 26.3 原版默认一致）。 */
	private static final int BASE_MIN_DELAY = 600;
	private static final int BASE_MAX_DELAY = 800;
	private static final int BASE_COUNT = 4;
	private static final int BASE_NEARBY = 6;
	private static final int BASE_PLAYER_RANGE = 16;
	private static final int BASE_SPAWN_RANGE = 4;
	/** 每级间隔衰减系数（0.75^等级）。 */
	private static final double DELAY_FACTOR = 0.75D;
	/** 升级费用：1000 × 等级² 元（分）。 */
	private static final long FEE_BASE_CENTS = 100_000L;

	private SpawnerManager() {
	}

	// ---------- 等级参数 ----------

	public static int minDelay(int level) {
		return Math.max(20, (int) Math.round(BASE_MIN_DELAY * Math.pow(DELAY_FACTOR, level - 1)));
	}

	public static int maxDelay(int level) {
		return Math.max(40, (int) Math.round(BASE_MAX_DELAY * Math.pow(DELAY_FACTOR, level - 1)));
	}

	public static int spawnCount(int level) {
		return BASE_COUNT + (level - 1);
	}

	public static int maxNearby(int level) {
		return BASE_NEARBY + (level - 1) * 3;
	}

	public static int playerRange(int level) {
		return BASE_PLAYER_RANGE + (level - 1) * 2;
	}

	public static int spawnRange(int level) {
		return BASE_SPAWN_RANGE + (level - 1);
	}

	/** 升级费用（分）：等级 n → n+1 收 1000 × n² 元。 */
	public static long upgradeFeeCents(int level) {
		return FEE_BASE_CENTS * (long) level * level;
	}

	/** 由当前最小生成延迟反推等级（参数完全由等级公式决定，遍历找最接近者）。 */
	public static int inferLevel(int minSpawnDelay) {
		int best = 1;
		int bestDiff = Integer.MAX_VALUE;
		for (int level = 1; level <= MAX_LEVEL; level++) {
			int diff = Math.abs(minSpawnDelay - minDelay(level));
			if (diff < bestDiff) {
				bestDiff = diff;
				best = level;
			}
		}
		return best;
	}

	// ---------- 绑定（刷怪蛋右键，由 UseBlockCallback 调用） ----------

	/** 手持刷怪蛋右键空刷怪笼：绑定实体类型。返回是否处理成功（非 null = 失败提示）。 */
	public static String bindWithEgg(ServerPlayer player, SpawnerBlockEntity spawner, ItemStack eggStack) {
		SpawnerAccess access = (SpawnerAccess) spawner.getSpawner();
		if (access.economyHasPotentials()) {
			return "该刷怪笼已绑定实体类型，无法重新绑定";
		}
		EntityType<?> type = SpawnEggItem.getType(eggStack);
		if (type == null) {
			return "无法识别该刷怪蛋的实体类型";
		}
		spawner.setEntityId(type, player.getRandom());
		access.economyApplyLevel(1); // 绑定后按等级 1 参数生成
		spawner.setChanged();
		BlockPos pos = spawner.getBlockPos();
		player.level().sendBlockUpdated(pos, spawner.getBlockState(), spawner.getBlockState(), 3);
		eggStack.shrink(1);
		player.sendSystemMessage(Component.literal("已绑定刷怪笼类型：" + type.getDescription().getString())
				.withStyle(ChatFormatting.GREEN), false);
		return null;
	}

	// ---------- 升级（/spawner upgrade） ----------

	/** 升级刷怪笼（纯金钱）。返回 null = 成功，否则为失败提示。 */
	public static String upgrade(ServerPlayer player, SpawnerBlockEntity spawner) {
		SpawnerAccess access = (SpawnerAccess) spawner.getSpawner();
		int level = inferLevel(access.economyMinDelay());
		if (!access.economyHasPotentials()) {
			return "该刷怪笼尚未绑定实体类型，请先手持刷怪蛋右键绑定";
		}
		if (level >= MAX_LEVEL) {
			return "该刷怪笼已满级（Lv " + MAX_LEVEL + "）";
		}
		long fee = upgradeFeeCents(level);
		long balance;
		try {
			balance = EconomyDb.getBalance(player.getUUID());
		} catch (EconomyDb.DatabaseException e) {
			return "数据库错误，请稍后再试";
		}
		if (balance < fee) {
			return "你的资金不足：升级到 Lv " + (level + 1) + " 需要 " + mois.economy.Money.format(fee)
					+ " 元（当前 " + mois.economy.Money.format(balance) + " 元）";
		}
		try {
			if (!EconomyDb.deduct(player.getUUID(), fee)) {
				return "你的资金不足";
			}
		} catch (EconomyDb.DatabaseException e) {
			return "数据库错误，请稍后再试";
		}
		// 资金流水（升级扣款）
		try {
			EconomyDb.recordMoneyLog(player.getUUID(), player.getGameProfile().name(),
					EconomyDb.TYPE_SPAWNER_UPGRADE, EconomyDb.CHANNEL_SPAWNER,
					"刷怪笼升级（Lv " + level + " → Lv " + (level + 1) + "）", -fee);
		} catch (EconomyDb.DatabaseException ignored) {
			// 记录失败静默。
		}
		access.economyApplyLevel(level + 1);
		spawner.setChanged();
		BlockPos pos = spawner.getBlockPos();
		player.level().sendBlockUpdated(pos, spawner.getBlockState(), spawner.getBlockState(), 3);
		player.sendSystemMessage(Component.literal("刷怪笼已升级到 Lv " + (level + 1)
				+ "（花费 " + mois.economy.Money.format(fee) + " 元）").withStyle(ChatFormatting.GREEN), false);
		return null;
	}

	// ---------- 信息 ----------

	/** 刷怪笼信息文本（/spawner）。 */
	public static net.minecraft.network.chat.MutableComponent describe(SpawnerBlockEntity spawner) {
		SpawnerAccess access = (SpawnerAccess) spawner.getSpawner();
		int level = inferLevel(access.economyMinDelay());
		Component bound = access.economyHasPotentials()
				? Component.literal("已绑定").withStyle(ChatFormatting.GREEN)
				: Component.literal("未绑定（手持刷怪蛋右键绑定）").withStyle(ChatFormatting.YELLOW);
		net.minecraft.network.chat.MutableComponent info = Component.literal("刷怪笼：")
				.append(bound)
				.append(Component.literal("　Lv " + level + "/" + MAX_LEVEL + "\n").withStyle(ChatFormatting.AQUA))
				.append(Component.literal("生成间隔 " + access.economyMinDelay() + "~" + access.economyMaxDelay()
						+ " tick　每次 " + access.economySpawnCount() + " 只　附近上限 "
						+ access.economyMaxNearby() + "　激活距离 " + access.economyPlayerRange()
						+ "　范围 " + access.economySpawnRange() + "\n").withStyle(ChatFormatting.GRAY));
		if (level < MAX_LEVEL) {
			info.append(Component.literal("下次升级（Lv " + (level + 1) + "）："
					+ mois.economy.Money.format(upgradeFeeCents(level)) + " 元，/spawner upgrade 升级")
					.withStyle(ChatFormatting.GOLD));
		} else {
			info.append(Component.literal("已满级").withStyle(ChatFormatting.GOLD));
		}
		return info;
	}

	/** 目标刷怪笼（准星射线，5 格内）；不是刷怪笼返回 null。 */
	public static SpawnerBlockEntity targetedSpawner(ServerPlayer player) {
		net.minecraft.world.phys.HitResult hit = player.pick(5.0D, 1.0F, false);
		if (!(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit)) {
			return null;
		}
		if (player.level().getBlockEntity(blockHit.getBlockPos()) instanceof SpawnerBlockEntity spawner) {
			return spawner;
		}
		return null;
	}

	/** 刷怪笼方块所在维度（用于提示）。 */
	public static String dimensionString(ServerLevel level) {
		return level.dimension().identifier().toString();
	}
}
