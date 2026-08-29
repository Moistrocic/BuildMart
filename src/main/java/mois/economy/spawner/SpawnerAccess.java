package mois.economy.spawner;

/**
 * 刷怪笼生成参数访问接口（由 {@code BaseSpawnerMixin} 实现）。
 * 原版 {@code BaseSpawner} 的生成参数均为私有字段且无公开 setter，
 * 通过 mixin 接口暴露给业务层（升级/绑定/展示用）。
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

	/** 按目标等级应用全部生成参数（升级/绑定后重置用）。 */
	void economyApplyLevel(int level);
}
