package mois.economy.mixin;

import mois.economy.config.EconomyConfig;
import mois.economy.config.SpawnerConfig;
import mois.economy.spawner.SpawnerAccess;
import mois.economy.spawner.SpawnerStateAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.random.WeightedList;
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
 *   Lv 1 生成（升级数据保留，再次开启自动恢复）；升级/set 的修改 1 tick 内生效。</li>
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
	private WeightedList<SpawnData> spawnPotentials;

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
		return spawnPotentials != null && !spawnPotentials.isEmpty();
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
		// 生效等级：升级开关关闭时按 Lv 1（升级数据保留，再次开启自动恢复）
		int effLevel = EconomyConfig.spawnerUpgrade() ? state.economyLevel() : 1;
		SpawnerConfig.LevelParams p = SpawnerConfig.level(effLevel);
		minSpawnDelay = state.economyOverrideMinDelay() >= 0 ? state.economyOverrideMinDelay() : p.minDelayMin();
		maxSpawnDelay = state.economyOverrideMaxDelay() >= 0 ? state.economyOverrideMaxDelay() : p.maxDelayMin();
		spawnCount = state.economyOverrideCount() >= 0 ? state.economyOverrideCount() : p.countMin();
		maxNearbyEntities = state.economyOverrideNearby() >= 0 ? state.economyOverrideNearby() : p.nearbyMin();
		requiredPlayerRange = state.economyOverridePlayerRange() >= 0
				? state.economyOverridePlayerRange() : p.playerRangeMin();
		spawnRange = state.economyOverrideSpawnRange() >= 0 ? state.economyOverrideSpawnRange() : p.spawnRangeMin();
	}
}
