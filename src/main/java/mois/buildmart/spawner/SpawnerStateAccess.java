package mois.buildmart.spawner;

import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.UUID;

/**
 * 刷怪笼玩法状态访问接口（由 {@code SpawnerBlockEntityMixin} 实现）。
 * 数据全部存 BlockEntity NBT（随世界存档）：
 * <ul>
 * <li>economy_spawner：是否为本模组生成的刷怪笼（标签，钓鱼/管理员 give 获得；
 *     原版刷怪笼无此标记——不受升级/掉落/信息等任何改动影响）；</li>
 * <li>economy_level：升级等级（独立于生成参数——开关关闭时参数回退 Lv 1，
 *     但升级数据保留，再次开启自动恢复）；</li>
 * <li>economy_override_*：玩家 /spawner set 的参数微调（-1 = 未覆盖，
 *     受当前等级允许范围约束，升级时钳制到新等级范围）；</li>
 * <li>economy_auto_convert：直接转化（true 时不生成生物，原本生成的生物直接
 *     转化为掉落物并存储在方块中）；</li>
 * <li>economy_looting：抢夺等级 0-3（解锁随刷怪笼等级：8-10 级 0-3、5-7 级 0-2、
 *     2-4 级 0-1、1 级及以下仅 0；仅作用于直接转化）；</li>
 * <li>economy_auto_sell：自动出售（开启时转化掉落物直接按系统价格卖给收款人，
 *     并显示悬浮信息：创建人/收款人/倒计时）；economy_display：悬浮信息显示
 *     （默认 true，false = 自动出售开启也不显示悬浮字）；</li>
 * <li>economy_drops：转化掉落物存储（键值对：物品完整数据 → 数量，随方块存档，
 *     挖掉时随刷怪笼物品保存防止丢失）；</li>
 * <li>economy_owner_* / economy_payee_*：创建人（放置时记录）与收款人
 *     （/spawner set payee 设置，默认 = 创建人）；</li>
 * <li>economy_display_uuid：自动出售悬浮实体 UUID（重建用）。</li>
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

	// ---------- 直接转化 / 抢夺 / 自动出售 ----------

	boolean economyAutoConvert();

	void economySetAutoConvert(boolean v);

	/** 抢夺等级 0-3（解锁受刷怪笼等级约束，见 SpawnerManager.lootingMaxForLevel）。 */
	int economyLooting();

	void economySetLooting(int v);

	boolean economyAutoSell();

	void economySetAutoSell(boolean v);

	/** 悬浮信息显示（bool，默认 true）：关闭时即使自动出售开启也不显示悬浮字。 */
	boolean economyDisplay();

	void economySetDisplay(boolean v);

	/** 自动出售周期倒计时（tick；-1 = 未初始化，开启后 60 秒一轮批量出售）。 */
	int economySellTimer();

	void economySetSellTimer(int v);

	// ---------- 漏斗（白名单物品自动放入相邻箱子） ----------

	/** 漏斗功能（bool）：开启时转化掉落物中白名单物品直接放入同 y 水平相邻的箱子。 */
	boolean economyHopper();

	void economySetHopper(boolean v);

	/** 白名单物品 ID 列表（如 minecraft:coal；匹配按注册表 ID）。 */
	List<String> economyHopperWhitelist();

	void economyHopperAdd(String itemId);

	void economyHopperRemove(String itemId);

	// ---------- 转化掉落物存储（键值对：物品完整数据 → 数量） ----------

	/** 当前存储的掉落物（不可修改列表）。 */
	List<ItemStack> economyDrops();

	/** 合并存储一个掉落物（同物品数据数量相加）。 */
	void economyAddDrop(ItemStack stack);

	/** 取出全部存储掉落物并清空。 */
	List<ItemStack> economyTakeDrops();

	/** 已转化的实体数（对应存储中的掉落物来源；取出/出售时清零）。 */
	int economyConverted();

	void economySetConverted(int v);

	// ---------- 创建人 / 收款人（自动出售） ----------

	UUID economyOwnerUuid();

	String economyOwnerName();

	void economySetOwner(UUID uuid, String name);

	/** 收款人 UUID（null = 未设置，收入归创建人）。 */
	UUID economyPayeeUuid();

	String economyPayeeName();

	void economySetPayee(UUID uuid, String name);

	// ---------- 悬浮实体（自动出售开启时） ----------

	UUID economyDisplayUuid();

	void economySetDisplayUuid(UUID uuid);
}
