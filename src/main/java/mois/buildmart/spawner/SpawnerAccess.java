package mois.buildmart.spawner;

import net.minecraft.world.entity.EntityType;

/**
 * 刷怪笼生成参数访问接口（由 {@code BaseSpawnerMixin} 实现）。
 * 原版 {@code BaseSpawner} 的生成参数均为私有字段且无公开 setter，
 * 通过 mixin 接口暴露给业务层（升级/绑定/展示/转化用）。
 */
public interface SpawnerAccess {
	int economyMinDelay();

	int economyMaxDelay();

	int economySpawnCount();

	int economyMaxNearby();

	int economyPlayerRange();

	int economySpawnRange();

	/** 是否已绑定实体类型（spawnPotentials 非空 = 原版地牢笼或已绑定）。 */
	boolean economyHasPotentials();

	/** 当前绑定/待生成的实体类型（null = 未绑定）。 */
	EntityType<?> economyEntityType();

	/** 当前生成倒计时（tick；直接转化/悬浮显示用）。 */
	int economySpawnDelay();
}
