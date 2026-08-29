package mois.economy.spawner;

import mois.economy.config.EconomyConfig;
import mois.economy.config.SpawnerConfig;
import mois.economy.data.EconomyDb;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * 刷怪笼玩法（休闲经济闭环）——**仅限带特殊标签（本模组生成）的刷怪笼**：
 * <ul>
 * <li>获取：趣味钓鱼战利品可配置产出刷怪笼（带标签物品）；管理员 /spawner give 也可获得；
 *     刷怪笼定价 -1（不可交易）防倒卖；</li>
 * <li>绑定：放置后手持刷怪蛋右键绑定实体类型（消耗一个蛋）；之后 /spawner set entity 随时改刷的怪；</li>
 * <li>升级：/spawner upgrade（纯金钱，费用/效果来自 spawner.json 分级配置；
 *     /config spawner.upgrade 总开关，关闭时禁止升级且已升级效果按 Lv 1 生成，
 *     升级数据保留，再次开启自动恢复）；</li>
 * <li>参数微调：/spawner set 各参数，值受当前等级允许范围约束；</li>
 * <li>回收：玩家用镐破坏带标签刷怪笼掉落带完整数据物品（重新放置恢复）；
 *     原版刷怪笼不受任何影响（不可升级/不可挖取/不可绑定）。</li>
 * </ul>
 */
public final class SpawnerManager {
	private SpawnerManager() {
	}

	/** 不可操作的提示（非本模组刷怪笼）。 */
	public static final String NOT_TAGGED = "该刷怪笼不是本模组生成的刷怪笼，无法操作";

	// ---------- 状态辅助 ----------

	private static SpawnerStateAccess state(SpawnerBlockEntity spawner) {
		return (SpawnerStateAccess) spawner;
	}

	/** 刷怪笼方块实体类型（注册 ID 为 minecraft:mob_spawner；spawner 兜底）。 */
	private static BlockEntityType<?> spawnerType() {
		BlockEntityType<?> t = BuiltInRegistries.BLOCK_ENTITY_TYPE
				.getValue(Identifier.fromNamespaceAndPath("minecraft", "mob_spawner"));
		if (t == null) {
			t = BuiltInRegistries.BLOCK_ENTITY_TYPE
					.getValue(Identifier.fromNamespaceAndPath("minecraft", "spawner"));
		}
		return t;
	}

	// ---------- 管理员 give（带标签刷怪笼） ----------

	/** 生成带标签的空刷怪笼物品（放置后为 Lv 0 空笼，原版生成机制）。 */
	public static ItemStack createTaggedSpawnerStack() {
		CompoundTag data = new CompoundTag();
		data.putBoolean("economy_spawner", true);
		data.putInt("economy_level", 0);
		BlockEntityType<?> spawnerType = spawnerType();
		if (spawnerType == null) {
			return ItemStack.EMPTY; // 防御：注册表异常时不给物品
		}
		ItemStack stack = new ItemStack(Items.SPAWNER);
		stack.set(DataComponents.BLOCK_ENTITY_DATA,
				TypedEntityData.of(spawnerType, data));
		return stack;
	}

	// ---------- 绑定（刷怪蛋右键，仅带标签笼） ----------

	/** 手持刷怪蛋右键空刷怪笼：绑定实体类型。返回 null = 成功，否则为失败提示。 */
	public static String bindWithEgg(ServerPlayer player, SpawnerBlockEntity spawner, ItemStack eggStack) {
		if (!state(spawner).economyTagged()) {
			return NOT_TAGGED;
		}
		SpawnerAccess access = (SpawnerAccess) spawner.getSpawner();
		if (access.economyHasPotentials()) {
			return "该刷怪笼已绑定实体类型（可用 /spawner set entity 更改）";
		}
		EntityType<?> type = SpawnEggItem.getType(eggStack);
		if (type == null) {
			return "无法识别该刷怪蛋的实体类型";
		}
		spawner.setEntityId(type, player.getRandom());
		spawner.setChanged();
		syncToClient(player, spawner);
		eggStack.shrink(1);
		player.sendSystemMessage(Component.literal("已绑定刷怪笼类型：" + type.getDescription().getString())
				.withStyle(ChatFormatting.GREEN), false);
		return null;
	}

	// ---------- 升级（/spawner upgrade） ----------

	/** 升级刷怪笼（Lv 0 → 1 起，费用/效果来自 spawner.json 分级配置）。返回 null = 成功，否则为失败提示。 */
	public static String upgrade(ServerPlayer player, SpawnerBlockEntity spawner) {
		if (!state(spawner).economyTagged()) {
			return NOT_TAGGED;
		}
		if (!EconomyConfig.spawnerUpgrade()) {
			return "升级功能已关闭（/config spawner.upgrade 可重新开启；已升级效果暂按原版生成，数据保留）";
		}
		SpawnerAccess access = (SpawnerAccess) spawner.getSpawner();
		if (!access.economyHasPotentials()) {
			return "该刷怪笼尚未绑定实体类型，请先手持刷怪蛋右键绑定";
		}
		int level = state(spawner).economyLevel();
		if (level >= SpawnerConfig.maxLevel()) {
			return "该刷怪笼已满级（Lv " + SpawnerConfig.maxLevel() + "）";
		}
		long fee = SpawnerConfig.level(level + 1).upgradeFeeCents();
		long balance;
		try {
			balance = EconomyDb.getBalance(player.getUUID());
		} catch (EconomyDb.DatabaseException e) {
			return "数据库错误，请稍后再试";
		}
		if (balance < fee) {
			return "你的资金不足：升级到 Lv " + (level + 1) + " 需要 "
					+ SpawnerConfig.formatCents(fee) + " 元（当前 " + SpawnerConfig.formatCents(balance) + " 元）";
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
		// 升级：等级 +1，参数微调钳制到新等级范围（保留玩家微调但不越界）
		state(spawner).economySetLevel(level + 1);
		clampOverrides(state(spawner), level + 1);
		spawner.setChanged();
		syncToClient(player, spawner);
		player.sendSystemMessage(Component.literal("刷怪笼已升级到 Lv " + (level + 1)
				+ "（花费 " + SpawnerConfig.formatCents(fee) + " 元）").withStyle(ChatFormatting.GREEN), false);
		return null;
	}

	/** 参数微调钳制到指定等级允许范围。 */
	private static void clampOverrides(SpawnerStateAccess state, int level) {
		SpawnerConfig.LevelParams p = SpawnerConfig.level(level);
		state.economySetOverrideMinDelay(clamp(state.economyOverrideMinDelay(), p.minDelayMin(), p.minDelayMax()));
		state.economySetOverrideMaxDelay(clamp(state.economyOverrideMaxDelay(), p.maxDelayMin(), p.maxDelayMax()));
		state.economySetOverrideCount(clamp(state.economyOverrideCount(), p.countMin(), p.countMax()));
		state.economySetOverrideNearby(clamp(state.economyOverrideNearby(), p.nearbyMin(), p.nearbyMax()));
		state.economySetOverridePlayerRange(
				clamp(state.economyOverridePlayerRange(), p.playerRangeMin(), p.playerRangeMax()));
		state.economySetOverrideSpawnRange(
				clamp(state.economyOverrideSpawnRange(), p.spawnRangeMin(), p.spawnRangeMax()));
	}

	private static int clamp(int v, int min, int max) {
		if (v < 0) {
			return -1; // 未覆盖保持
		}
		return Math.max(min, Math.min(max, v));
	}

	// ---------- 参数微调（/spawner set） ----------

	/** 设置生成参数微调（值受当前等级允许范围约束；Lv 0 不可微调）。返回 null = 成功，否则为失败提示。 */
	public static String setParam(ServerPlayer player, SpawnerBlockEntity spawner, String param, int value) {
		if (!state(spawner).economyTagged()) {
			return NOT_TAGGED;
		}
		int level = state(spawner).economyLevel();
		if (level <= 0) {
			return "Lv 0 为原版生成机制，无法微调参数，请先 /spawner upgrade";
		}
		SpawnerConfig.LevelParams p = SpawnerConfig.level(level);
		SpawnerStateAccess s = state(spawner);
		int min;
		int max;
		switch (param) {
			case "minDelay" -> {
				min = p.minDelayMin();
				max = p.minDelayMax();
				s.economySetOverrideMinDelay(value);
			}
			case "maxDelay" -> {
				min = p.maxDelayMin();
				max = p.maxDelayMax();
				s.economySetOverrideMaxDelay(value);
			}
			case "count" -> {
				min = p.countMin();
				max = p.countMax();
				s.economySetOverrideCount(value);
			}
			case "nearby" -> {
				min = p.nearbyMin();
				max = p.nearbyMax();
				s.economySetOverrideNearby(value);
			}
			case "playerRange" -> {
				min = p.playerRangeMin();
				max = p.playerRangeMax();
				s.economySetOverridePlayerRange(value);
			}
			case "spawnRange" -> {
				min = p.spawnRangeMin();
				max = p.spawnRangeMax();
				s.economySetOverrideSpawnRange(value);
			}
			default -> {
				return "未知参数：" + param + "（可用 minDelay/maxDelay/count/nearby/playerRange/spawnRange）";
			}
		}
		if (value < min || value > max) {
			// 回滚本次修改
			setParam(s, param, -1);
			return "Lv " + level + " 的 " + param + " 允许范围：" + min + " ~ " + max;
		}
		// maxDelay 不能小于 minDelay（防御）
		if (param.equals("maxDelay") && s.economyOverrideMinDelay() >= 0
				&& value < s.economyOverrideMinDelay()) {
			setParam(s, param, -1);
			return "maxDelay 不能小于 minDelay（当前 " + s.economyOverrideMinDelay() + "）";
		}
		if (param.equals("minDelay") && s.economyOverrideMaxDelay() >= 0
				&& value > s.economyOverrideMaxDelay()) {
			setParam(s, param, -1);
			return "minDelay 不能大于 maxDelay（当前 " + s.economyOverrideMaxDelay() + "）";
		}
		spawner.setChanged();
		syncToClient(player, spawner);
		player.sendSystemMessage(Component.literal("已设置 " + param + " = " + value
				+ "（Lv " + level + " 范围 " + min + " ~ " + max + "）").withStyle(ChatFormatting.GREEN), false);
		return null;
	}

	/** 按参数名回滚微调（越界时清理本次写入）。 */
	private static void setParam(SpawnerStateAccess s, String param, int unused) {
		switch (param) {
			case "minDelay" -> s.economySetOverrideMinDelay(-1);
			case "maxDelay" -> s.economySetOverrideMaxDelay(-1);
			case "count" -> s.economySetOverrideCount(-1);
			case "nearby" -> s.economySetOverrideNearby(-1);
			case "playerRange" -> s.economySetOverridePlayerRange(-1);
			case "spawnRange" -> s.economySetOverrideSpawnRange(-1);
			default -> {
			}
		}
	}

	// ---------- 信息（/spawner info，仅带标签笼） ----------

	/** 刷怪笼信息文本。 */
	public static Component describe(SpawnerBlockEntity spawner) {
		SpawnerStateAccess state = state(spawner);
		if (!state.economyTagged()) {
			return Component.literal(NOT_TAGGED).withStyle(ChatFormatting.RED);
		}
		SpawnerAccess access = (SpawnerAccess) spawner.getSpawner();
		int level = state.economyLevel();
		int effLevel = EconomyConfig.spawnerUpgrade() ? level : 0;
		Component bound = access.economyHasPotentials()
				? Component.literal("已绑定").withStyle(ChatFormatting.GREEN)
				: Component.literal("未绑定（手持刷怪蛋右键绑定）").withStyle(ChatFormatting.YELLOW);
		Component levelText = level <= 0
				? Component.literal("Lv 0（原版生成机制，未升级）").withStyle(ChatFormatting.GRAY)
				: Component.literal("Lv " + level + "/" + SpawnerConfig.maxLevel()).withStyle(ChatFormatting.AQUA);
		Component upgradeState = EconomyConfig.spawnerUpgrade()
				? Component.empty()
				: Component.literal("　[升级功能已关闭，效果暂按原版生成，数据保留]").withStyle(ChatFormatting.RED);
		net.minecraft.network.chat.MutableComponent info = Component.literal("刷怪笼：")
				.append(bound)
				.append(Component.literal("　")).append(levelText).append(upgradeState)
				.append(Component.literal("\n当前生效：生成间隔 " + access.economyMinDelay() + "~"
						+ access.economyMaxDelay() + " tick　每次 " + access.economySpawnCount()
						+ " 只　附近上限 " + access.economyMaxNearby() + "　激活距离 "
						+ access.economyPlayerRange() + "　范围 " + access.economySpawnRange() + "\n")
						.withStyle(ChatFormatting.GRAY));
		if (level > 0) {
			SpawnerConfig.LevelParams p = SpawnerConfig.level(level);
			info.append(Component.literal("Lv " + level + " 参数范围：minDelay " + p.minDelayMin() + "~"
					+ p.minDelayMax() + "　maxDelay " + p.maxDelayMin() + "~" + p.maxDelayMax()
					+ "　count " + p.countMin() + "~" + p.countMax() + "　nearby " + p.nearbyMin()
					+ "~" + p.nearbyMax() + "　playerRange " + p.playerRangeMin() + "~"
					+ p.playerRangeMax() + "　spawnRange " + p.spawnRangeMin() + "~" + p.spawnRangeMax() + "\n")
					.withStyle(ChatFormatting.DARK_GRAY));
		}
		if (level < SpawnerConfig.maxLevel()) {
			info.append(Component.literal("下次升级（Lv " + (level + 1) + "）："
					+ SpawnerConfig.formatCents(SpawnerConfig.level(level + 1).upgradeFeeCents())
					+ " 元，/spawner upgrade 升级").withStyle(ChatFormatting.GOLD));
		} else {
			info.append(Component.literal("已满级").withStyle(ChatFormatting.GOLD));
		}
		return info;
	}

	// ---------- 目标与同步 ----------

	/** 目标刷怪笼（准星射线，5 格内）；不是刷怪笼返回 null。 */
	public static SpawnerBlockEntity targetedSpawner(ServerPlayer player) {
		HitResult hit = player.pick(5.0D, 1.0F, false);
		if (!(hit instanceof BlockHitResult blockHit)) {
			return null;
		}
		if (player.level().getBlockEntity(blockHit.getBlockPos()) instanceof SpawnerBlockEntity spawner) {
			return spawner;
		}
		return null;
	}

	/** 客户端同步（方块实体数据更新）。 */
	private static void syncToClient(ServerPlayer player, SpawnerBlockEntity spawner) {
		BlockPos pos = spawner.getBlockPos();
		player.level().sendBlockUpdated(pos, spawner.getBlockState(), spawner.getBlockState(), 3);
	}
}
