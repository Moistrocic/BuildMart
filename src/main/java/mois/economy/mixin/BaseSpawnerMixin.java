package mois.economy.mixin;

import mois.economy.spawner.SpawnerAccess;
import mois.economy.spawner.SpawnerManager;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.SpawnData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 暴露 {@link BaseSpawner} 的生成参数（均为私有字段且无公开 setter）：
 * 通过 {@link SpawnerAccess} 接口供刷怪笼玩法（升级/绑定/展示）访问。
 * 原版生成逻辑不受影响（字段含义不变）。
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

	@Shadow
	private int spawnDelay;

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

	@Unique
	@Override
	public void economyApplyLevel(int level) {
		minSpawnDelay = SpawnerManager.minDelay(level);
		maxSpawnDelay = SpawnerManager.maxDelay(level);
		spawnCount = SpawnerManager.spawnCount(level);
		maxNearbyEntities = SpawnerManager.maxNearby(level);
		requiredPlayerRange = SpawnerManager.playerRange(level);
		spawnRange = SpawnerManager.spawnRange(level);
		// 重置当前倒计时（防止升级后仍按旧延迟等待）
		spawnDelay = minSpawnDelay;
	}
}
