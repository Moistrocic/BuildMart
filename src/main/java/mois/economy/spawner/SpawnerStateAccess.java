package mois.economy.spawner;

/**
 * 刷怪笼玩法状态访问接口（由 {@code SpawnerBlockEntityMixin} 实现）。
 * 数据全部存 BlockEntity NBT（随世界存档）：
 * <ul>
 * <li>economy_spawner：是否为本模组生成的刷怪笼（标签，钓鱼/管理员 give 获得；
 *     原版刷怪笼无此标记——不受升级/掉落/信息等任何改动影响）；</li>
 * <li>economy_level：升级等级（独立于生成参数——开关关闭时参数回退 Lv 1，
 *     但升级数据保留，再次开启自动恢复）；</li>
 * <li>economy_override_*：玩家 /spawner set 的参数微调（-1 = 未覆盖，
 *     受当前等级允许范围约束，升级时钳制到新等级范围）。</li>
 * </ul>
 */
public interface SpawnerStateAccess {
	boolean economyTagged();

	int economyLevel();

	void economySetLevel(int level);

	int economyOverrideMinDelay();

	int economyOverrideMaxDelay();

	int economyOverrideCount();

	int economyOverrideNearby();

	int economyOverridePlayerRange();

	int economyOverrideSpawnRange();

	void economySetOverrideMinDelay(int v);

	void economySetOverrideMaxDelay(int v);

	void economySetOverrideCount(int v);

	void economySetOverrideNearby(int v);

	void economySetOverridePlayerRange(int v);

	void economySetOverrideSpawnRange(int v);

	/** 清空全部参数微调（升级后回到新等级默认）。 */
	void economyClearOverrides();
}
