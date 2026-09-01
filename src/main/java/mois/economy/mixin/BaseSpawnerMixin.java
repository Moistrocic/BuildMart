package mois.economy.mixin;

import mois.economy.config.EconomyConfig;
import mois.economy.config.SpawnerConfig;
import mois.economy.spawner.SpawnerAccess;
import mois.economy.spawner.SpawnerManager;
import mois.economy.spawner.SpawnerStateAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.SpawnData;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 刷怪笼玩法：
 * <ul>
 * <li>暴露 {@link BaseSpawner} 生成参数（私有字段）供展示（{@link SpawnerAccess}）；</li>
 * <li>serverTick HEAD：仅对**带标签**的刷怪笼每 tick 按「等级 + 参数微调 + 开关」计算
 *   并写入生成参数——原版刷怪笼完全不受影响；{@code spawner.upgrade} 关闭时按
 *   Lv 1 生成（升级数据保留，再次开启自动恢复）；升级/set 的修改 1 tick 内生效；</li>
 * <li>直接转化：autoConvert 开启时，本 tick 原版将生成（spawnDelay ≤ 0）则改为
 *   计算掉落物（存方块或自动出售），并重置倒计时让原版逻辑走递减分支（不生成生物）；</li>
 * <li>自动出售悬浮：autoSell 开启时每 tick 同步悬浮实体（创建人/收款人/倒计时）。</li>
 * </ul>
 */
@Mixin(BaseSpawner.class)
public abstract class BaseSpawnerMixin implements SpawnerAccess {
	@Shadow
	private int minSpawnDelay;

	@Shadow
	private int maxSpawnDelay;

	@Shadow
	private int spawnCount;

	@Shadow
	private int maxNearbyEntities;

	@Shadow
	private int requiredPlayerRange;

	@Shadow
	private int spawnRange;

	@Shadow
	private int spawnDelay;

	@Shadow
	private WeightedList<SpawnData> spawnPotentials;

	@Shadow
	private SpawnData nextSpawnData;

	@Unique
	@Override
	public int economyMinDelay() {
		return minSpawnDelay;
	}

	@Unique
	@Override
	public int economyMaxDelay() {
		return maxSpawnDelay;
	}

	@Unique
	@Override
	public int economySpawnCount() {
		return spawnCount;
	}

	@Unique
	@Override
	public int economyMaxNearby() {
		return maxNearbyEntities;
	}

	@Unique
	@Override
	public int economyPlayerRange() {
		return requiredPlayerRange;
	}

	@Unique
	@Override
	public int economySpawnRange() {
		return spawnRange;
	}

	@Unique
	@Override
	public boolean economyHasPotentials() {
		// 26.3 setEntityId 只写 nextSpawnData 的 entity id、不填 spawnPotentials——
		// 绑定判定须两者兼顾：spawnPotentials 非空（原版地牢笼）或 nextSpawnData 有实体 id（蛋绑定）
		if (spawnPotentials != null && !spawnPotentials.isEmpty()) {
			return true;
		}
		if (nextSpawnData == null) {
			return false;
		}
		return !nextSpawnData.getEntityToSpawn().getStringOr("id", "").isEmpty();
	}

	@Unique
	@Override
	public EntityType<?> economyEntityType() {
		if (nextSpawnData != null) {
			String id = nextSpawnData.getEntityToSpawn().getStringOr("id", "");
			if (!id.isEmpty()) {
				Identifier key = Identifier.tryParse(id);
				if (key != null) {
					EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(key);
					if (type != null) {
						return type;
					}
				}
			}
		}
		if (spawnPotentials != null && !spawnPotentials.isEmpty()) {
			// 原版地牢笼：取第一个候选
			for (net.minecraft.util.random.Weighted<SpawnData> weighted : spawnPotentials.unwrap()) {
				String id = weighted.value().getEntityToSpawn().getStringOr("id", "");
				if (!id.isEmpty()) {
					Identifier key = Identifier.tryParse(id);
					if (key != null) {
						EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getValue(key);
						if (type != null) {
							return type;
						}
					}
				}
			}
		}
		return null;
	}

	@Unique
	@Override
	public int economySpawnDelay() {
		return spawnDelay;
	}

	@Inject(method = "serverTick", at = @At("HEAD"))
	private void economy$applyComputedParams(ServerLevel level, BlockPos pos, CallbackInfo ci) {
		if (!(level.getBlockEntity(pos) instanceof SpawnerBlockEntity spawner)) {
			return;
		}
		SpawnerStateAccess state = (SpawnerStateAccess) spawner;
		if (!state.economyTagged()) {
			return; // 原版刷怪笼：不干预
		}
		// 生效等级：升级开关关闭或未升级（Lv 0）时按原版生成机制（不写参数）
		int effLevel = EconomyConfig.spawnerUpgrade() ? state.economyLevel() : 0;
		if (effLevel > 0) {
			SpawnerConfig.LevelParams p = SpawnerConfig.level(effLevel);
			minSpawnDelay = state.economyOverrideMinDelay() >= 0 ? state.economyOverrideMinDelay() : p.minDelayMin();
			maxSpawnDelay = state.economyOverrideMaxDelay() >= 0 ? state.economyOverrideMaxDelay() : p.maxDelayMin();
			spawnCount = state.economyOverrideCount() >= 0 ? state.economyOverrideCount() : p.countMin();
			maxNearbyEntities = state.economyOverrideNearby() >= 0 ? state.economyOverrideNearby() : p.nearbyMin();
			requiredPlayerRange = state.economyOverridePlayerRange() >= 0
					? state.economyOverridePlayerRange() : p.playerRangeMin();
			spawnRange = state.economyOverrideSpawnRange() >= 0 ? state.economyOverrideSpawnRange() : p.spawnRangeMin();
		}
		// 直接转化：本 tick 原版将生成 → 改为掉落物（统一存入方块），并重置倒计时
		if (state.economyAutoConvert() && this.spawnDelay <= 0) {
			if (SpawnerManager.convertToDrops(level, spawner, state)) {
				this.spawnDelay = minSpawnDelay + level.getRandom().nextInt(maxSpawnDelay - minSpawnDelay + 1);
			}
		}
		// 自动出售：周期（60 秒）批量出售存储的掉落物 + 悬浮（金色，倒计时=出售周期剩余）
		if (state.economyAutoSell()) {
			int timer = state.economySellTimer();
			if (timer < 0) {
				timer = SpawnerManager.SELL_PERIOD_TICKS;
			} else {
				timer--;
				if (timer <= 0) {
					SpawnerManager.sellStoredDrops(level, spawner, state);
					timer = SpawnerManager.SELL_PERIOD_TICKS;
				}
			}
			state.economySetSellTimer(timer);
			SpawnerManager.tickDisplay(level, spawner, state, timer);
		} else {
			SpawnerManager.tickDisplay(level, spawner, state, -1);
		}
	}
}