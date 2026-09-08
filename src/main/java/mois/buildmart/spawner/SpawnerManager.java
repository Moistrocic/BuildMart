package mois.buildmart.spawner;

import mois.buildmart.BuildMart;
import mois.buildmart.config.EconomyConfig;
import mois.buildmart.config.ItemValues;
import mois.buildmart.config.SpawnerConfig;
import mois.buildmart.data.EconomyDb;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
 * <li>直接转化：/spawner set autoconvert true——不生成生物，原本生成的生物按击杀
 *     掉落表转化为掉落物存储在方块中（挖掉时随刷怪笼物品保存防丢失，/spawner take
 *     取出）；抢夺 /spawner set looting 0-3 随等级解锁（8-10 级 0-3、5-7 级 0-2、
 *     2-4 级 0-1、1 级及以下仅 0），作用于转化掉落；</li>
 * <li>自动出售：/spawner set autosell true——转化掉落物直接按系统价格卖给收款人
 *     （/spawner set payee 设置，默认创建人），并显示悬浮信息（创建人/收款人/倒计时）；</li>
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

	/** 生成带标签的空刷怪笼物品（放置后为 Lv 0 空笼，原版生成机制）。
	 * 底版使用全新 {@link SpawnerBlockEntity} 的完整存档（含原版默认生成参数），
	 * 保证 give 物品与「放置→挖回」掉落的物品 NBT 完全一致，可互相堆叠。 */
	public static ItemStack createTaggedSpawnerStack(HolderLookup.Provider lookup) {
		BlockEntityType<?> spawnerType = spawnerType();
		if (spawnerType == null) {
			return ItemStack.EMPTY; // 防御：注册表异常时不给物品
		}
		SpawnerBlockEntity fresh = new SpawnerBlockEntity(BlockPos.ZERO, Blocks.SPAWNER.defaultBlockState());
		CompoundTag data = fresh.saveCustomOnly(lookup);
		data.putBoolean("buildmart_spawner", true);
		data.putInt("buildmart_level", 0);
		ItemStack stack = new ItemStack(Items.SPAWNER);
		stack.set(DataComponents.BLOCK_ENTITY_DATA,
				TypedEntityData.of(spawnerType, data));
		return stack;
	}

	// ---------- 绑定/换绑（刷怪蛋右键，仅带标签笼；可随时更换） ----------

	/**
	 * 手持刷怪蛋右键刷怪笼：绑定/更换实体类型（不限制更换）。返回 null = 成功，否则为失败提示。
	 * 模式规则：**创造模式（instabuild）使用物品不消耗**——不 shrink 刷怪蛋（与原版一致）；
	 * 生存模式消耗一个蛋。
	 */
	public static String bindWithEgg(ServerPlayer player, SpawnerBlockEntity spawner, ItemStack eggStack) {
		if (!state(spawner).economyTagged()) {
			return NOT_TAGGED;
		}
		EntityType<?> type = SpawnEggItem.getType(eggStack);
		if (type == null) {
			return "无法识别该刷怪蛋的实体类型";
		}
		boolean rebind = ((SpawnerAccess) spawner.getSpawner()).economyHasPotentials();
		spawner.setEntityId(type, player.getRandom());
		spawner.setChanged();
		syncToClient(player, spawner);
		if (!player.getAbilities().instabuild) {
			eggStack.shrink(1); // 生存模式消耗蛋；创造模式不消耗（原版规则）
		}
		player.sendSystemMessage(Component.literal(rebind
				? "已将刷怪笼类型更换为：" + type.getDescription().getString()
				: "已绑定刷怪笼类型：" + type.getDescription().getString())
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

	/** 参数微调钳制到指定等级允许范围（looting 按等级解锁上限钳制）。 */
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
		state.economySetLooting(Math.min(state.economyLooting(), lootingMaxForLevel(level)));
	}

	private static int clamp(int v, int min, int max) {
		if (v < 0) {
			return -1; // 未覆盖保持
		}
		return Math.max(min, Math.min(max, v));
	}

	// ---------- 参数微调（/spawner set） ----------

	/**
	 * 设置刷怪笼参数/配置。返回 null = 成功，否则为失败提示。
	 * 数值参数（minDelay/maxDelay/count/nearby/playerRange/spawnRange）受当前等级
	 * 允许范围约束；looting 受等级解锁约束（见 {@link #lootingMaxForLevel}）；
	 * autoconvert/autosell 仅接受 true/false；payee 为在线玩家名。
	 */
	public static String setParam(ServerPlayer player, SpawnerBlockEntity spawner, String param, String value) {
		SpawnerStateAccess s = state(spawner);
		if (!s.economyTagged()) {
			return NOT_TAGGED;
		}
		int level = s.economyLevel();
		switch (param) {
			case "autoconvert" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "autoconvert 需要 true 或 false";
				}
				s.economySetAutoConvert(b);
				spawner.setChanged();
				syncToClient(player, spawner);
				player.sendSystemMessage(Component.literal("已" + (b ? "开启" : "关闭")
						+ "直接转化（" + (b ? "生物将直接转化为掉落物存储" : "恢复正常生成生物") + "）")
						.withStyle(ChatFormatting.GREEN), false);
				return null;
			}
			case "looting" -> {
				Integer v = parseInt(value);
				if (v == null) {
					return "looting 需要 0~3 的整数";
				}
				int max = lootingMaxForLevel(level);
				if (v < 0 || v > max) {
					return "Lv " + level + " 的抢夺等级上限：" + max + "（8-10 级 0-3、5-7 级 0-2、2-4 级 0-1）";
				}
				s.economySetLooting(v);
				spawner.setChanged();
				syncToClient(player, spawner);
				player.sendSystemMessage(Component.literal("已设置抢夺 = " + v
						+ (v > 0 ? "（作用于直接转化的掉落）" : "（无抢夺效果）")).withStyle(ChatFormatting.GREEN), false);
				return null;
			}
			case "autosell" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "autosell 需要 true 或 false";
				}
				s.economySetAutoSell(b);
				if (!b) {
					s.economySetSellTimer(-1); // 关闭：出售周期复位，再开启重新 60 秒
				}
				spawner.setChanged();
				syncToClient(player, spawner);
				if (b) {
					player.sendSystemMessage(Component.literal("已开启自动出售（每 60 秒批量出售存储的转化掉落物，收款人："
							+ payeeDisplayName(s) + "；/spawner set payee 玩家 可改收款人）")
							.withStyle(ChatFormatting.GREEN), false);
				} else {
					player.sendSystemMessage(Component.literal("已关闭自动出售（转化掉落物继续存储在方块中）")
							.withStyle(ChatFormatting.GREEN), false);
				}
				return null;
			}
			case "display" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "display 需要 true 或 false";
				}
				s.economySetDisplay(b);
				if (!b) {
					removeDisplay((ServerLevel) player.level(), spawner); // 立即移除悬浮字
				}
				spawner.setChanged();
				syncToClient(player, spawner);
				if (b) {
					player.sendSystemMessage(Component.literal("已开启悬浮信息（自动出售开启时显示创建人/收款人/倒计时）")
							.withStyle(ChatFormatting.GREEN), false);
				} else {
					player.sendSystemMessage(Component.literal("已关闭悬浮信息（自动出售照常工作，不再显示悬浮字）")
							.withStyle(ChatFormatting.GREEN), false);
				}
				return null;
			}
			case "hopper" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "hopper 需要 true 或 false";
				}
				s.economySetHopper(b);
				spawner.setChanged();
				if (b) {
					// 开启时立即迁移已积累的白名单物品
					migrateWhitelistedToChest((ServerLevel) player.level(), spawner, s);
				}
				syncToClient(player, spawner);
				if (b) {
					player.sendSystemMessage(Component.literal("已开启漏斗（白名单物品自动放入同高度相邻箱子；"
							+ "当前白名单 " + s.economyHopperWhitelist().size() + " 种，/spawner hopper add 物品 添加）")
							.withStyle(ChatFormatting.GREEN), false);
				} else {
					player.sendSystemMessage(Component.literal("已关闭漏斗（转化掉落物全部存回刷怪笼）")
							.withStyle(ChatFormatting.GREEN), false);
				}
				return null;
			}
			case "payee" -> {
				if (value.isEmpty()) {
					return "payee 需要玩家名";
				}
				// 在线玩家优先；不在线玩家按离线 UUID 解析，但必须已注册资金账户
				ServerPlayer online = player.level().getServer().getPlayerList().getPlayerByName(value);
				UUID payeeUuid;
				String payeeName;
				if (online != null) {
					payeeUuid = online.getUUID();
					payeeName = online.getGameProfile().name();
				} else {
					net.minecraft.server.players.NameAndId profile = net.minecraft.server.players.NameAndId
							.createOffline(value);
					payeeUuid = profile.id();
					payeeName = profile.name();
				}
				try {
					if (!EconomyDb.hasAccount(payeeUuid)) {
						return "该玩家尚未创建资金账户（需先上线或产生资金记录）";
					}
				} catch (EconomyDb.DatabaseException e) {
					return "数据库错误，请稍后再试";
				}
				s.economySetPayee(payeeUuid, payeeName);
				spawner.setChanged();
				syncToClient(player, spawner);
				player.sendSystemMessage(Component.literal("已设置收款人 = " + payeeName)
						.withStyle(ChatFormatting.GREEN), false);
				return null;
			}
			default -> {
			}
		}
		// 数值生成参数（受等级范围约束；Lv 0 不可微调）
		if (level <= 0) {
			return "Lv 0 为原版生成机制，无法微调参数，请先 /spawner upgrade";
		}
		Integer valueInt = parseInt(value);
		if (valueInt == null) {
			return "参数 " + param + " 需要整数（可用 minDelay/maxDelay/count/nearby/playerRange/spawnRange/autoconvert/looting/autosell/hopper/display/payee）";
		}
		SpawnerConfig.LevelParams p = SpawnerConfig.level(level);
		int min;
		int max;
		switch (param) {
			case "minDelay" -> {
				min = p.minDelayMin();
				max = p.minDelayMax();
				s.economySetOverrideMinDelay(valueInt);
			}
			case "maxDelay" -> {
				min = p.maxDelayMin();
				max = p.maxDelayMax();
				s.economySetOverrideMaxDelay(valueInt);
			}
			case "count" -> {
				min = p.countMin();
				max = p.countMax();
				s.economySetOverrideCount(valueInt);
			}
			case "nearby" -> {
				min = p.nearbyMin();
				max = p.nearbyMax();
				s.economySetOverrideNearby(valueInt);
			}
			case "playerRange" -> {
				min = p.playerRangeMin();
				max = p.playerRangeMax();
				s.economySetOverridePlayerRange(valueInt);
			}
			case "spawnRange" -> {
				min = p.spawnRangeMin();
				max = p.spawnRangeMax();
				s.economySetOverrideSpawnRange(valueInt);
			}
			default -> {
				return "未知参数：" + param + "（可用 minDelay/maxDelay/count/nearby/playerRange/spawnRange/autoconvert/looting/autosell/hopper/display/payee）";
			}
		}
		if (valueInt < min || valueInt > max) {
			// 回滚本次修改
			setParam(s, param, -1);
			return "Lv " + level + " 的 " + param + " 允许范围：" + min + " ~ " + max;
		}
		// maxDelay 不能小于 minDelay（防御）
		if (param.equals("maxDelay") && s.economyOverrideMinDelay() >= 0
				&& valueInt < s.economyOverrideMinDelay()) {
			setParam(s, param, -1);
			return "maxDelay 不能小于 minDelay（当前 " + s.economyOverrideMinDelay() + "）";
		}
		if (param.equals("minDelay") && s.economyOverrideMaxDelay() >= 0
				&& valueInt > s.economyOverrideMaxDelay()) {
			setParam(s, param, -1);
			return "minDelay 不能大于 maxDelay（当前 " + s.economyOverrideMaxDelay() + "）";
		}
		spawner.setChanged();
		syncToClient(player, spawner);
		player.sendSystemMessage(Component.literal("已设置 " + param + " = " + valueInt
				+ "（Lv " + level + " 范围 " + min + " ~ " + max + "）").withStyle(ChatFormatting.GREEN), false);
		// count 超过生效 nearby 时提醒（原版机制：附近实体达到 nearby 上限后整个生成周期暂停）
		if (param.equals("count")) {
			int effNearby = s.economyOverrideNearby() >= 0 ? s.economyOverrideNearby() : p.nearbyMin();
			if (valueInt > effNearby) {
				player.sendSystemMessage(Component.literal("提示：附近实体上限 nearby = " + effNearby
						+ "，达到后生成暂停；若希望每次 " + valueInt + " 只同时在场，请 /spawner set nearby " + valueInt)
						.withStyle(ChatFormatting.YELLOW), false);
			}
		}
		return null;
	}

	// ---------- 直接转化 / 抢夺 / 自动出售 ----------

	/** 抢夺等级按刷怪笼等级解锁：8-10 级 0-3、5-7 级 0-2、2-4 级 0-1、1 级及以下仅 0。 */
	public static int lootingMaxForLevel(int level) {
		if (level >= 8) {
			return 3;
		}
		if (level >= 5) {
			return 2;
		}
		if (level >= 2) {
			return 1;
		}
		return 0;
	}

	/**
	 * 直接转化：把原本将生成的生物按击杀掉落表转化为掉落物，统一合并存储到方块
	 * （economy_drops）。自动出售开启时由周期任务批量出售（见 {@link #sellStoredDrops}）。
	 * 返回 false = 本次未转化（未绑定实体等，保持原版倒计时）。
	 */
	public static boolean convertToDrops(ServerLevel level, SpawnerBlockEntity spawner, SpawnerStateAccess state) {
		SpawnerAccess access = (SpawnerAccess) spawner.getSpawner();
		EntityType<?> type = access.economyEntityType();
		if (type == null) {
			return false; // 未绑定实体：不转化（原版也不生成）
		}
		// 假实体承载击杀上下文（THIS_ENTITY）；26.3 的 looting 由附魔效果组件
		// （enchanted_count_increase 等）读取 ATTACKING_ENTITY 的附魔等级——
		// 模拟：创建同类假实体作攻击者，主手挂 Looting N 的剑
		Entity fake = type.create(level, EntitySpawnReason.MOB_SUMMONED);
		if (fake == null) {
			return false;
		}
		int looting = state.economyLooting();
		if (looting > 0 && fake instanceof LivingEntity living) {
			ItemStack sword = new ItemStack(Items.DIAMOND_SWORD);
			ItemEnchantments.Mutable ench = new ItemEnchantments.Mutable(ItemEnchantments.EMPTY);
			ench.set(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
					.getOrThrow(Enchantments.LOOTING), looting);
			sword.set(DataComponents.ENCHANTMENTS, ench.toImmutable());
			living.setItemSlot(EquipmentSlot.MAINHAND, sword);
		}
		Optional<ResourceKey<net.minecraft.world.level.storage.loot.LootTable>> lootKey = type.getDefaultLootTable();
		if (lootKey.isEmpty()) {
			return true; // 无掉落表：转化成功但无掉落
		}
		LootParams.Builder builder = new LootParams.Builder(level)
				.withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(spawner.getBlockPos()))
				.withParameter(LootContextParams.DAMAGE_SOURCE, level.damageSources().genericKill())
				.withParameter(LootContextParams.THIS_ENTITY, fake)
				.withParameter(LootContextParams.ATTACKING_ENTITY, fake);
		// 强制判定为「玩家击杀」：killed_by_player 条件只检查 LAST_DAMAGE_PLAYER 是否存在，
		// 特殊掉落物（凋灵骷髅头颅等）依赖它——优先用创建人，其次用笼子附近最近的玩家
		net.minecraft.world.entity.player.Player killer = killerFor(level, spawner, state);
		if (killer != null) {
			builder.withParameter(LootContextParams.LAST_DAMAGE_PLAYER, killer);
		}
		LootParams params = builder.create(LootContextParamSets.ENTITY);
		net.minecraft.world.level.storage.loot.LootTable table = level.getServer().reloadableRegistries()
				.getLootTable(lootKey.get());
		// 一次生成周期原本生成 spawnCount 只生物 → 逐只独立计算掉落（更真实），实体数累加
		int entities = Math.max(1, access.economySpawnCount());
		for (int i = 0; i < entities; i++) {
			for (ItemStack drop : table.getRandomItems(params)) {
				if (drop.isEmpty()) {
					continue;
				}
				// 漏斗：白名单物品放入同 y 水平相邻的箱子（放不下的回退存储）
				if (state.economyHopper() && hopperIsWhitelisted(drop, state)) {
					hopperInsert(level, spawner, drop, state);
				} else {
					state.economyAddDrop(drop);
				}
			}
		}
		state.economySetConverted(state.economyConverted() + entities);
		spawner.setChanged();
		return true;
	}

	// ---------- 漏斗（白名单物品自动放入相邻箱子） ----------

	/** 白名单匹配（按注册表 ID）。 */
	private static boolean hopperIsWhitelisted(ItemStack drop, SpawnerStateAccess state) {
		String id = BuiltInRegistries.ITEM.getKey(drop.getItem()).toString();
		return state.economyHopperWhitelist().contains(id);
	}

	/**
	 * 把掉落物放入同 y 水平相邻的箱子（优先堆叠已有同种，再找空槽；支持多个相邻箱子）；
	 * 箱子不存在或放不下时剩余部分回退到刷怪笼存储。
	 */
	private static void hopperInsert(ServerLevel level, SpawnerBlockEntity spawner, ItemStack stack,
			SpawnerStateAccess state) {
		int remaining = stack.getCount();
		BlockPos pos = spawner.getBlockPos();
		for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.Plane.HORIZONTAL) {
			if (remaining <= 0) {
				break;
			}
			BlockPos neighbor = pos.relative(dir); // 同 y 且水平相邻
			if (!(level.getBlockEntity(neighbor) instanceof net.minecraft.world.Container container)) {
				continue;
			}
			remaining = insertIntoContainer(container, stack, remaining);
		}
		if (remaining > 0) {
			state.economyAddDrop(stack.copyWithCount(remaining));
		}
	}

	/** 往容器放入 count 个（优先堆叠到已有同种槽，再找空槽）；返回剩余数量。 */
	private static int insertIntoContainer(net.minecraft.world.Container container, ItemStack template, int count) {
		int remaining = count;
		for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
			ItemStack existing = container.getItem(i);
			if (existing.isEmpty()) {
				continue;
			}
			if (ItemStack.isSameItemSameComponents(existing, template)) {
				int space = existing.getMaxStackSize() - existing.getCount();
				if (space > 0) {
					int put = Math.min(space, remaining);
					existing.grow(put);
					container.setItem(i, existing);
					remaining -= put;
				}
			}
		}
		for (int i = 0; i < container.getContainerSize() && remaining > 0; i++) {
			if (container.getItem(i).isEmpty()) {
				int put = Math.min(template.getMaxStackSize(), remaining);
				container.setItem(i, template.copyWithCount(put));
				remaining -= put;
			}
		}
		return remaining;
	}

	/**
	 * 把存储中符合白名单的掉落物迁移到相邻箱子（放不下的回退存储）。
	 * 覆盖「开启漏斗/添加白名单之前已积累的掉落物」场景——在开启漏斗、
	 * 添加/移除白名单时各触发一次；转化时白名单物品直接进箱子（不走存储）。
	 */
	public static void migrateWhitelistedToChest(ServerLevel level, SpawnerBlockEntity spawner,
			SpawnerStateAccess state) {
		if (!state.economyHopper()) {
			return;
		}
		List<ItemStack> all = state.economyTakeDrops();
		if (all.isEmpty()) {
			return;
		}
		for (ItemStack drop : all) {
			if (drop.isEmpty()) {
				continue;
			}
			if (hopperIsWhitelisted(drop, state)) {
				hopperInsert(level, spawner, drop, state); // 放不下自动回退存储
			} else {
				state.economyAddDrop(drop);
			}
		}
		spawner.setChanged();
	}

	/** 转化击杀者：优先创建人（在线），否则笼子激活范围内的最近玩家；都没有返回 null。 */
	private static net.minecraft.world.entity.player.Player killerFor(ServerLevel level,
			SpawnerBlockEntity spawner, SpawnerStateAccess state) {
		UUID ownerUuid = state.economyOwnerUuid();
		if (ownerUuid != null) {
			net.minecraft.world.entity.player.Player owner = level.getServer().getPlayerList().getPlayer(ownerUuid);
			if (owner != null) {
				return owner;
			}
		}
		BlockPos pos = spawner.getBlockPos();
		return level.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
				((SpawnerAccess) spawner.getSpawner()).economyPlayerRange(), e -> true);
	}

	/** 自动出售周期（tick）：60 秒一轮批量出售。 */
	public static final int SELL_PERIOD_TICKS = 1200;

	/**
	 * 批量出售存储的转化掉落物（自动出售周期到点调用）：
	 * 可交易物品按系统价格卖给收款人（默认创建人），不可交易物品留下继续存储；
	 * 结算日志输出受 /config shop.sellLog 控制。倒计时结束后**所有**存储的掉落物
	 * 都会被出售（含开启自动出售前积累的）。
	 */
	public static void sellStoredDrops(ServerLevel level, SpawnerBlockEntity spawner, SpawnerStateAccess state) {
		List<ItemStack> stored = state.economyTakeDrops();
		if (stored.isEmpty()) {
			return;
		}
		state.economySetConverted(0); // 存储清空：实体计数归零
		UUID payeeUuid = state.economyPayeeUuid() != null ? state.economyPayeeUuid() : state.economyOwnerUuid();
		String payeeName = state.economyPayeeName() != null ? state.economyPayeeName() : state.economyOwnerName();
		long total = 0;
		int count = 0;
		for (ItemStack drop : stored) {
			if (drop.isEmpty()) {
				continue;
			}
			long value = ItemValues.price(drop);
			if (value < 0) {
				state.economyAddDrop(drop); // 不可交易：留下继续存储
				continue;
			}
			total += value;
			count += drop.getCount();
		}
		if (count > 0 && payeeUuid != null) {
			try {
				EconomyDb.credit(payeeUuid, payeeName, total);
				EconomyDb.recordMoneyLog(payeeUuid, payeeName, EconomyDb.TYPE_SELL, EconomyDb.CHANNEL_SPAWNER,
						"刷怪笼自动出售 " + count + " 件（" + spawner.getBlockPos().toShortString() + "）", total);
				if (mois.buildmart.config.EconomyConfig.shopSellLog()) {
					BuildMart.LOGGER.info("刷怪笼自动出售结算：{} {} → {}（{} 元）",
							level.dimension().identifier(), spawner.getBlockPos().toShortString(),
							payeeName, mois.buildmart.Money.format(total));
				}
			} catch (EconomyDb.DatabaseException e) {
				BuildMart.LOGGER.warn("刷怪笼自动出售入账失败", e);
				// 入账失败：掉落物不丢失，转存方块
				for (ItemStack drop : stored) {
					if (!drop.isEmpty() && ItemValues.price(drop) >= 0) {
						state.economyAddDrop(drop);
					}
				}
			}
		} else if (count > 0) {
			// 无收款人（早期笼子无创建人记录）：全部存储
			for (ItemStack drop : stored) {
				if (!drop.isEmpty() && ItemValues.price(drop) >= 0) {
					state.economyAddDrop(drop);
				}
			}
		}
		spawner.setChanged();
	}

	// ---------- 悬浮（自动出售开启时：创建人/收款人/倒计时） ----------

	/** 每 tick 调用；自动出售开启且悬浮显示开启时创建/更新悬浮实体（金色，与 shop 一致），
	 *  悬浮显示关闭或自动出售关闭时移除残留。 */
	public static void tickDisplay(ServerLevel level, SpawnerBlockEntity spawner, SpawnerStateAccess state,
			int sellTimer) {
		if (!state.economyAutoSell() || !state.economyDisplay()) {
			removeDisplay(level, state);
			return;
		}
		if (level.getServer().getTickCount() % 20 != 0) {
			return; // 每秒刷新一次文本
		}
		Display.TextDisplay display = findDisplay(level, state);
		if (display == null) {
			display = new Display.TextDisplay(net.minecraft.world.entity.EntityTypes.TEXT_DISPLAY, level);
			BlockPos pos = spawner.getBlockPos();
			display.setPos(pos.getX() + 0.5, pos.getY() + 1.6, pos.getZ() + 0.5);
			display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
			level.addFreshEntity(display);
			state.economySetDisplayUuid(display.getUUID());
			spawner.setChanged();
		}
		display.setText(buildDisplayText(state, sellTimer));
	}

	private static Display.TextDisplay findDisplay(ServerLevel level, SpawnerStateAccess state) {
		UUID uuid = state.economyDisplayUuid();
		if (uuid != null && level.getEntity(uuid) instanceof Display.TextDisplay display) {
			return display;
		}
		return null;
	}

	/** 移除悬浮实体（自动出售关闭/挖掉时）。 */
	public static void removeDisplay(ServerLevel level, SpawnerBlockEntity spawner) {
		removeDisplay(level, state(spawner));
	}

	private static void removeDisplay(ServerLevel level, SpawnerStateAccess state) {
		UUID uuid = state.economyDisplayUuid();
		if (uuid != null) {
			if (level.getEntity(uuid) != null) {
				level.getEntity(uuid).discard();
			}
			state.economySetDisplayUuid(null);
		}
	}

	private static Component buildDisplayText(SpawnerStateAccess state, int sellTimer) {
		String owner = state.economyOwnerName() != null ? state.economyOwnerName() : "未知";
		String payee = payeeDisplayName(state);
		int seconds = Math.max(0, (int) Math.ceil(sellTimer / 20.0));
		// 与 shop 悬浮一致：整段金色
		return Component.literal("创建人：" + owner
				+ "\n收款人：" + payee
				+ "\n倒计时：" + seconds + " 秒").withStyle(ChatFormatting.GOLD);
	}

	private static String payeeDisplayName(SpawnerStateAccess state) {
		if (state.economyPayeeName() != null) {
			return state.economyPayeeName();
		}
		return state.economyOwnerName() != null ? state.economyOwnerName() : "未知";
	}

	// ---------- 漏斗白名单（/spawner hopper add|remove|list） ----------

	/** 添加白名单物品。返回 null = 成功，否则为失败提示。 */
	public static String hopperAdd(ServerPlayer player, SpawnerBlockEntity spawner, net.minecraft.world.item.Item item) {
		SpawnerStateAccess s = state(spawner);
		if (!s.economyTagged()) {
			return NOT_TAGGED;
		}
		String id = BuiltInRegistries.ITEM.getKey(item).toString();
		if (s.economyHopperWhitelist().contains(id)) {
			player.sendSystemMessage(Component.literal("白名单已有：" + id).withStyle(ChatFormatting.YELLOW), false);
			return null;
		}
		s.economyHopperAdd(id);
		spawner.setChanged();
		// 立即迁移：之前积累的该物品也进箱子
		migrateWhitelistedToChest((ServerLevel) player.level(), spawner, s);
		syncToClient(player, spawner);
		player.sendSystemMessage(Component.literal("已添加漏斗白名单：" + id
				+ "（当前 " + s.economyHopperWhitelist().size() + " 种）").withStyle(ChatFormatting.GREEN), false);
		return null;
	}

	/** 移除白名单物品。返回 null = 成功，否则为失败提示。 */
	public static String hopperRemove(ServerPlayer player, SpawnerBlockEntity spawner, net.minecraft.world.item.Item item) {
		SpawnerStateAccess s = state(spawner);
		if (!s.economyTagged()) {
			return NOT_TAGGED;
		}
		String id = BuiltInRegistries.ITEM.getKey(item).toString();
		if (!s.economyHopperWhitelist().contains(id)) {
			player.sendSystemMessage(Component.literal("白名单中没有：" + id).withStyle(ChatFormatting.YELLOW), false);
			return null;
		}
		s.economyHopperRemove(id);
		spawner.setChanged();
		syncToClient(player, spawner);
		player.sendSystemMessage(Component.literal("已移除漏斗白名单：" + id
				+ "（剩余 " + s.economyHopperWhitelist().size() + " 种）").withStyle(ChatFormatting.GREEN), false);
		return null;
	}

	/** 查看白名单（样式与 info 一致：金色标题 + 逐行中文名（英文 ID），不显示数量）。 */
	public static void hopperList(ServerPlayer player, SpawnerBlockEntity spawner) {
		SpawnerStateAccess s = state(spawner);
		player.sendSystemMessage(whitelistDisplay(s.economyHopperWhitelist()), false);
	}

	/** 白名单列表组件：金色标题 + 逐行中文名（英文 ID），与存储掉落物列表样式一致（不显示数量）。 */
	public static MutableComponent whitelistDisplay(List<String> whitelist) {
		MutableComponent msg = Component.literal("漏斗白名单列表（" + whitelist.size() + " 种）：")
				.withStyle(ChatFormatting.GOLD);
		if (whitelist.isEmpty()) {
			msg.append(Component.literal("\n（空，/spawner hopper add 物品 添加）").withStyle(ChatFormatting.DARK_GRAY));
		} else {
			for (String id : whitelist) {
				MutableComponent line = Component.literal("\n").withStyle(ChatFormatting.AQUA);
				net.minecraft.world.item.Item item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(id));
				if (item != null) {
					// translatable 组件由客户端翻译显示中文
					line.append(new ItemStack(item).getHoverName().copy().withStyle(ChatFormatting.AQUA));
				} else {
					line.append(Component.literal(id).withStyle(ChatFormatting.AQUA));
				}
				line.append(Component.literal("（" + id + "）").withStyle(ChatFormatting.AQUA));
				msg.append(line);
			}
		}
		return msg;
	}

	// ---------- 取出存储掉落物（/spawner take） ----------
	/** 取出全部存储掉落物（背包优先，放不下的掉落在脚下）。返回 null = 成功。 */
	public static String takeDrops(ServerPlayer player, SpawnerBlockEntity spawner) {
		SpawnerStateAccess s = state(spawner);
		if (!s.economyTagged()) {
			return NOT_TAGGED;
		}
		List<ItemStack> drops = s.economyTakeDrops();
		if (drops.isEmpty()) {
			player.sendSystemMessage(Component.literal("刷怪笼中没有存储的转化掉落物")
					.withStyle(ChatFormatting.YELLOW), false);
			return null;
		}
		s.economySetConverted(0); // 存储清空：实体计数归零
		int totalCount = 0;
		for (ItemStack drop : drops) {
			totalCount += drop.getCount();
			giveOrDrop(player, drop);
		}
		spawner.setChanged();
		syncToClient(player, spawner);
		player.sendSystemMessage(Component.literal("已取出 " + drops.size() + " 种共 " + totalCount
				+ " 个转化掉落物（背包放不下的已掉落在脚下）").withStyle(ChatFormatting.GREEN), false);
		return null;
	}

	/** 按单堆上限分块放入背包，放不下的溢出部分掉落到玩家脚下。 */
	private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
		int remaining = stack.getCount();
		int max = stack.getMaxStackSize();
		while (remaining > 0) {
			int chunk = Math.min(remaining, max);
			ItemStack part = stack.copy();
			part.setCount(chunk);
			if (!player.getInventory().add(part)) {
				ItemStack drop = stack.copy();
				drop.setCount(remaining);
				player.spawnAtLocation(player.level(), drop);
				return;
			}
			remaining -= chunk;
		}
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

	private static Boolean parseBool(String value) {
		if (value.equalsIgnoreCase("true")) {
			return Boolean.TRUE;
		}
		if (value.equalsIgnoreCase("false")) {
			return Boolean.FALSE;
		}
		return null;
	}

	private static Integer parseInt(String value) {
		try {
			return Integer.valueOf(value);
		} catch (NumberFormatException e) {
			return null;
		}
	}

	// ---------- 信息（/spawner info，仅带标签笼） ----------

	/**
	 * 刷怪笼信息文本（格式与颜色规范）：
	 * 冒号前——前 3 行（刷怪笼/绑定生物/所有者）白色；可调整区间行（生成间隔/数量/
	 * 附近上限/激活区间/范围）蓝色；可设置的激活属性（直接转化/自动出售）按状态
	 * （开启蓝/关闭灰）；存储掉落物标题金色。冒号后——Lv 值当前&lt;上限红色、等于
	 * 绿色，斜杠与上限绿色；其余自由搭配。
	 */
	public static Component describe(SpawnerBlockEntity spawner) {
		SpawnerStateAccess state = state(spawner);
		if (!state.economyTagged()) {
			return Component.literal(NOT_TAGGED).withStyle(ChatFormatting.RED);
		}
		SpawnerAccess access = (SpawnerAccess) spawner.getSpawner();
		int level = state.economyLevel();
		int maxLevel = SpawnerConfig.maxLevel();
		MutableComponent info = Component.empty();

		// 刷怪笼：Lv 当前/满级（Lv 值按规则着色）
		info.append(Component.literal("刷怪笼：").withStyle(ChatFormatting.WHITE));
		info.append(lvText(level, maxLevel));
		if (!EconomyConfig.spawnerUpgrade()) {
			info.append(Component.literal("　[升级功能已关闭，效果暂按原版生成，数据保留]")
					.withStyle(ChatFormatting.RED));
		}

		// 绑定生物：中文名（原版英文 ID）——getDescription 是 translatable 组件，
		// 直接 append 由客户端翻译显示中文
		info.append(Component.literal("\n绑定生物：").withStyle(ChatFormatting.WHITE));
		EntityType<?> type = access.economyEntityType();
		if (type != null) {
			MutableComponent boundName = Component.empty();
			boundName.append(type.getDescription().copy().withStyle(ChatFormatting.AQUA));
			boundName.append(Component.literal("（" + BuiltInRegistries.ENTITY_TYPE.getKey(type) + "）")
					.withStyle(ChatFormatting.GRAY));
			info.append(boundName);
		} else {
			info.append(Component.literal("未绑定（手持刷怪蛋右键绑定）").withStyle(ChatFormatting.YELLOW));
		}

		// 所有者
		info.append(Component.literal("\n所有者：").withStyle(ChatFormatting.WHITE));
		info.append(Component.literal(state.economyOwnerName() != null ? state.economyOwnerName() : "未知")
				.withStyle(ChatFormatting.AQUA));

		if (level > 0) {
			SpawnerConfig.LevelParams p = SpawnerConfig.level(level);
			// 生成间隔：当前 min~max tick（最小/最大间隔区间）
			info.append(Component.literal("\n生成间隔：").withStyle(ChatFormatting.BLUE));
			info.append(Component.literal(access.economyMinDelay() + "~" + access.economyMaxDelay() + " tick")
					.withStyle(ChatFormatting.GRAY));
			info.append(Component.literal("（最小间隔区间：" + p.minDelayMin() + "~" + p.minDelayMax()
					+ "，最大间隔区间：" + p.maxDelayMin() + "~" + p.maxDelayMax() + "）")
					.withStyle(ChatFormatting.DARK_GRAY));
			// 生成数量
			info.append(Component.literal("\n生成数量：").withStyle(ChatFormatting.BLUE));
			info.append(Component.literal(access.economySpawnCount() + " 只").withStyle(ChatFormatting.GRAY));
			info.append(Component.literal("（限制区间：" + p.countMin() + "~" + p.countMax() + "）")
					.withStyle(ChatFormatting.DARK_GRAY));
			// 附近上限
			info.append(Component.literal("\n附近上限：").withStyle(ChatFormatting.BLUE));
			info.append(Component.literal(access.economyMaxNearby() + " 只").withStyle(ChatFormatting.GRAY));
			info.append(Component.literal("（限制区间：" + p.nearbyMin() + "~" + p.nearbyMax() + "）")
					.withStyle(ChatFormatting.DARK_GRAY));
			// 激活区间
			info.append(Component.literal("\n激活区间：").withStyle(ChatFormatting.BLUE));
			info.append(Component.literal(String.valueOf(access.economyPlayerRange())).withStyle(ChatFormatting.GRAY));
			info.append(Component.literal("（限制区间：" + p.playerRangeMin() + "~" + p.playerRangeMax() + "）")
					.withStyle(ChatFormatting.DARK_GRAY));
			// 范围
			info.append(Component.literal("\n范围：").withStyle(ChatFormatting.BLUE));
			info.append(Component.literal(String.valueOf(access.economySpawnRange())).withStyle(ChatFormatting.GRAY));
			info.append(Component.literal("（限制区间：" + p.spawnRangeMin() + "~" + p.spawnRangeMax() + "）")
					.withStyle(ChatFormatting.DARK_GRAY));
		}

		// 直接转化：开/关（可设置激活属性：开启蓝/关闭灰）
		info.append(Component.literal("\n直接转化：")
				.withStyle(state.economyAutoConvert() ? ChatFormatting.BLUE : ChatFormatting.GRAY));
		info.append(Component.literal(state.economyAutoConvert() ? "开" : "关")
				.withStyle(state.economyAutoConvert() ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));

		// 抢夺：Lv 当前/上限（Lv 值按规则着色）
		info.append(Component.literal("\n抢夺：").withStyle(ChatFormatting.BLUE));
		info.append(lvText(state.economyLooting(), lootingMaxForLevel(level)));

		// 自动出售：开/关（可设置激活属性：开启蓝/关闭灰）
		info.append(Component.literal("\n自动出售：")
				.withStyle(state.economyAutoSell() ? ChatFormatting.BLUE : ChatFormatting.GRAY));
		info.append(Component.literal(state.economyAutoSell() ? "开（收款人：" + payeeDisplayName(state) + "）" : "关")
				.withStyle(state.economyAutoSell() ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));

		// 悬浮信息：开/关（display 开关；仅在自动出售开启时实际显示）
		info.append(Component.literal("\n悬浮信息：")
				.withStyle(state.economyDisplay() ? ChatFormatting.BLUE : ChatFormatting.GRAY));
		info.append(Component.literal(state.economyDisplay()
				? "开（自动出售开启时显示创建人/收款人/倒计时）" : "关")
				.withStyle(state.economyDisplay() ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));

		// 漏斗：开/关（可设置激活属性：开启蓝/关闭灰）
		info.append(Component.literal("\n漏斗：")
				.withStyle(state.economyHopper() ? ChatFormatting.BLUE : ChatFormatting.GRAY));
		info.append(Component.literal(state.economyHopper() ? "开" : "关")
				.withStyle(state.economyHopper() ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));

		// 漏斗白名单列表（样式与存储掉落物列表一致：金色标题 + 中文名（英文 ID）逐行；仅不显示数量）
		info.append(Component.literal("\n").append(whitelistDisplay(state.economyHopperWhitelist())));

		// 存储掉落物列表（实体数，种类，掉落物总数）：金色
		// 同物品可能拆成多条（单条目上限 99）——显示时合并为一行、种类按唯一物品计
		List<ItemStack> drops = state.economyDrops();
		java.util.LinkedHashMap<net.minecraft.world.item.Item, ItemStack> merged = new java.util.LinkedHashMap<>();
		int totalCount = 0;
		for (ItemStack drop : drops) {
			totalCount += drop.getCount();
			merged.merge(drop.getItem(), drop,
					(a, b) -> a.copyWithCount(a.getCount() + b.getCount()));
		}
		info.append(Component.literal("\n存储掉落物列表（实体数：" + state.economyConverted()
				+ "，种类：" + merged.size() + "，掉落物总数：" + totalCount + "）：")
				.withStyle(ChatFormatting.GOLD));
		if (merged.isEmpty()) {
			info.append(Component.literal("\n（空）").withStyle(ChatFormatting.DARK_GRAY));
		} else {
			int shown = 0;
			for (ItemStack drop : merged.values()) {
				if (shown >= 9) {
					info.append(Component.literal("\n…等 " + merged.size() + " 种（/spawner take 取出全部）")
							.withStyle(ChatFormatting.DARK_GRAY));
					break;
				}
				String id = BuiltInRegistries.ITEM.getKey(drop.getItem()).toString();
				// 中文名在前（translatable 组件由客户端翻译），英文 ID 在括号里
				MutableComponent dropLine = Component.literal("\n").withStyle(ChatFormatting.AQUA);
				dropLine.append(drop.getHoverName().copy().withStyle(ChatFormatting.AQUA));
				dropLine.append(Component.literal("（" + id + "）：").withStyle(ChatFormatting.AQUA));
				info.append(dropLine);
				info.append(Component.literal(String.valueOf(drop.getCount())).withStyle(ChatFormatting.GRAY));
				shown++;
			}
		}
		return info;
	}

	/** Lv 值文本：当前 &lt; 上限红色、等于绿色；斜杠与上限绿色。 */
	private static MutableComponent lvText(int current, int max) {
		MutableComponent c = Component.literal("Lv ").withStyle(ChatFormatting.GREEN);
		c.append(Component.literal(String.valueOf(current))
				.withStyle(current < max ? ChatFormatting.RED : ChatFormatting.GREEN));
		c.append(Component.literal("/").withStyle(ChatFormatting.GREEN));
		c.append(Component.literal(String.valueOf(max)).withStyle(ChatFormatting.GREEN));
		return c;
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
