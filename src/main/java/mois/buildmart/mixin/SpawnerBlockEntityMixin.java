package mois.buildmart.mixin;

import mois.buildmart.spawner.SpawnerStateAccess;
import net.minecraft.core.UUIDUtil;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 刷怪笼玩法状态持久化（{@link SpawnerStateAccess}）：
 * 自定义 NBT key 随 BlockEntity 存档（原版加载/保存时忽略未知 key，互不影响）；
 * 物品经 BLOCK_ENTITY_DATA 组件携带同样的 key，放置/挖回时自动恢复。
 */
@Mixin(SpawnerBlockEntity.class)
public abstract class SpawnerBlockEntityMixin implements SpawnerStateAccess {
	@Unique
	private boolean economyTagged;

	/** 升级等级：0 = 未升级（原版刷怪笼生成机制），1..maxLevel 为配置等级。 */
	@Unique
	private int economyLevel = 0;

	@Unique
	private int economyOverrideMinDelay = -1;

	@Unique
	private int economyOverrideMaxDelay = -1;

	@Unique
	private int economyOverrideCount = -1;

	@Unique
	private int economyOverrideNearby = -1;

	@Unique
	private int economyOverridePlayerRange = -1;

	@Unique
	private int economyOverrideSpawnRange = -1;

	@Unique
	private boolean economyAutoConvert;

	@Unique
	private int economyLooting;

	@Unique
	private boolean economyAutoSell;

	/** 悬浮信息显示（默认 true：自动出售开启时显示；关闭则完全不显示）。 */
	@Unique
	private boolean economyDisplay = true;

	/** 自动出售周期倒计时（-1 = 未初始化）。 */
	@Unique
	private int economySellTimer = -1;

	/** 漏斗功能（白名单物品放入相邻箱子）。 */
	@Unique
	private boolean economyHopper;

	/** 漏斗白名单：物品注册表 ID 列表。 */
	@Unique
	private List<String> economyHopperWhitelist = new ArrayList<>();

	/** 转化掉落物存储：物品完整数据 → 数量（同物品数据合并）。 */
	@Unique
	private List<ItemStack> economyDrops = new ArrayList<>();

	/** 已转化实体数（存储掉落物的来源计数；取出/出售时清零）。 */
	@Unique
	private int economyConverted;

	@Unique
	private UUID economyOwnerUuid;

	@Unique
	private String economyOwnerName;

	@Unique
	private UUID economyPayeeUuid;

	@Unique
	private String economyPayeeName;

	@Unique
	private UUID economyDisplayUuid;

	@Unique
	@Override
	public boolean economyTagged() {
		return economyTagged;
	}

	@Unique
	@Override
	public int economyLevel() {
		return economyLevel;
	}

	@Unique
	@Override
	public void economySetLevel(int level) {
		economyLevel = Math.max(0, level);
	}

	@Unique
	@Override
	public int economyOverrideMinDelay() {
		return economyOverrideMinDelay;
	}

	@Unique
	@Override
	public int economyOverrideMaxDelay() {
		return economyOverrideMaxDelay;
	}

	@Unique
	@Override
	public int economyOverrideCount() {
		return economyOverrideCount;
	}

	@Unique
	@Override
	public int economyOverrideNearby() {
		return economyOverrideNearby;
	}

	@Unique
	@Override
	public int economyOverridePlayerRange() {
		return economyOverridePlayerRange;
	}

	@Unique
	@Override
	public int economyOverrideSpawnRange() {
		return economyOverrideSpawnRange;
	}

	@Unique
	@Override
	public void economySetOverrideMinDelay(int v) {
		economyOverrideMinDelay = v;
	}

	@Unique
	@Override
	public void economySetOverrideMaxDelay(int v) {
		economyOverrideMaxDelay = v;
	}

	@Unique
	@Override
	public void economySetOverrideCount(int v) {
		economyOverrideCount = v;
	}

	@Unique
	@Override
	public void economySetOverrideNearby(int v) {
		economyOverrideNearby = v;
	}

	@Unique
	@Override
	public void economySetOverridePlayerRange(int v) {
		economyOverridePlayerRange = v;
	}

	@Unique
	@Override
	public void economySetOverrideSpawnRange(int v) {
		economyOverrideSpawnRange = v;
	}

	@Unique
	@Override
	public void economyClearOverrides() {
		economyOverrideMinDelay = -1;
		economyOverrideMaxDelay = -1;
		economyOverrideCount = -1;
		economyOverrideNearby = -1;
		economyOverridePlayerRange = -1;
		economyOverrideSpawnRange = -1;
	}

	// ---------- 直接转化 / 抢夺 / 自动出售 ----------

	@Unique
	@Override
	public boolean economyAutoConvert() {
		return economyAutoConvert;
	}

	@Unique
	@Override
	public void economySetAutoConvert(boolean v) {
		economyAutoConvert = v;
	}

	@Unique
	@Override
	public int economyLooting() {
		return economyLooting;
	}

	@Unique
	@Override
	public void economySetLooting(int v) {
		economyLooting = Math.max(0, Math.min(3, v));
	}

	@Unique
	@Override
	public boolean economyAutoSell() {
		return economyAutoSell;
	}

	@Unique
	@Override
	public void economySetAutoSell(boolean v) {
		economyAutoSell = v;
	}

	@Unique
	@Override
	public boolean economyDisplay() {
		return economyDisplay;
	}

	@Unique
	@Override
	public void economySetDisplay(boolean v) {
		economyDisplay = v;
	}

	@Unique
	@Override
	public int economySellTimer() {
		return economySellTimer;
	}

	@Unique
	@Override
	public void economySetSellTimer(int v) {
		economySellTimer = v;
	}

	// ---------- 漏斗 ----------

	@Unique
	@Override
	public boolean economyHopper() {
		return economyHopper;
	}

	@Unique
	@Override
	public void economySetHopper(boolean v) {
		economyHopper = v;
	}

	@Unique
	@Override
	public List<String> economyHopperWhitelist() {
		return List.copyOf(economyHopperWhitelist);
	}

	@Unique
	@Override
	public void economyHopperAdd(String itemId) {
		if (itemId != null && !itemId.isEmpty() && !economyHopperWhitelist.contains(itemId)) {
			economyHopperWhitelist.add(itemId);
		}
	}

	@Unique
	@Override
	public void economyHopperRemove(String itemId) {
		economyHopperWhitelist.remove(itemId);
	}

	// ---------- 转化掉落物存储（键值对：物品完整数据 → 数量） ----------

	@Unique
	@Override
	public List<ItemStack> economyDrops() {
		return List.copyOf(economyDrops);
	}

	@Unique
	@Override
	public void economyAddDrop(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		// 单条目上限 99：buildmart_drops 用 ItemStack.OPTIONAL_CODEC.listOf() 编码，
		// 其 count 字段范围 [1;99]，超限会序列化失败（保存刷屏/数据丢失）。
		// 同物品条目填满后自动另开条目，取出/出售时按条目累加不受影响。
		int remaining = stack.getCount();
		for (int i = 0; i < economyDrops.size() && remaining > 0; i++) {
			ItemStack existing = economyDrops.get(i);
			if (ItemStack.isSameItemSameComponents(existing, stack)) {
				int space = 99 - existing.getCount();
				if (space > 0) {
					int put = Math.min(space, remaining);
					economyDrops.set(i, existing.copyWithCount(existing.getCount() + put));
					remaining -= put;
				}
			}
		}
		while (remaining > 0) {
			int put = Math.min(99, remaining);
			economyDrops.add(stack.copyWithCount(put));
			remaining -= put;
		}
	}

	@Unique
	@Override
	public List<ItemStack> economyTakeDrops() {
		List<ItemStack> taken = List.copyOf(economyDrops);
		economyDrops.clear();
		return taken;
	}

	@Unique
	@Override
	public int economyConverted() {
		return economyConverted;
	}

	@Unique
	@Override
	public void economySetConverted(int v) {
		economyConverted = Math.max(0, v);
	}

	// ---------- 创建人 / 收款人 ----------

	@Unique
	@Override
	public UUID economyOwnerUuid() {
		return economyOwnerUuid;
	}

	@Unique
	@Override
	public String economyOwnerName() {
		return economyOwnerName;
	}

	@Unique
	@Override
	public void economySetOwner(UUID uuid, String name) {
		economyOwnerUuid = uuid;
		economyOwnerName = name;
	}

	@Unique
	@Override
	public UUID economyPayeeUuid() {
		return economyPayeeUuid;
	}

	@Unique
	@Override
	public String economyPayeeName() {
		return economyPayeeName;
	}

	@Unique
	@Override
	public void economySetPayee(UUID uuid, String name) {
		economyPayeeUuid = uuid;
		economyPayeeName = name;
	}

	// ---------- 悬浮实体 ----------

	@Unique
	@Override
	public UUID economyDisplayUuid() {
		return economyDisplayUuid;
	}

	@Unique
	@Override
	public void economySetDisplayUuid(UUID uuid) {
		economyDisplayUuid = uuid;
	}

	@Inject(method = "loadAdditional", at = @At("RETURN"))
	private void buildmart$loadState(ValueInput input, CallbackInfo ci) {
		// 旧版（Economy 时代）NBT 键兼容：新键 buildmart_* 优先；但放置路径
		// （loadInto）会先保存 BE 当前状态（含新键默认值）再 merge 物品 tag，
		// 旧键物品放置后两套键并存——因此旧键「真值」必须覆盖新键默认值：
		// 布尔用或（任一 true 即带标签），数值用取大（新键默认 0/-1 < 旧键真值），
		// 列表在新键为空时回退旧键。加载后字段已就位，下次 saveAdditional 只写
		// 新键 → 存档自然迁移为 buildmart_*。
		economyTagged = input.getBooleanOr("buildmart_spawner", false)
				|| input.getBooleanOr("economy_spawner", false);
		economyLevel = Math.max(0, Math.max(input.getIntOr("buildmart_level", 0),
				input.getIntOr("economy_level", 0)));
		economyOverrideMinDelay = Math.max(input.getIntOr("buildmart_override_min_delay", -1),
				input.getIntOr("economy_override_min_delay", -1));
		economyOverrideMaxDelay = Math.max(input.getIntOr("buildmart_override_max_delay", -1),
				input.getIntOr("economy_override_max_delay", -1));
		economyOverrideCount = Math.max(input.getIntOr("buildmart_override_count", -1),
				input.getIntOr("economy_override_count", -1));
		economyOverrideNearby = Math.max(input.getIntOr("buildmart_override_nearby", -1),
				input.getIntOr("economy_override_nearby", -1));
		economyOverridePlayerRange = Math.max(input.getIntOr("buildmart_override_player_range", -1),
				input.getIntOr("economy_override_player_range", -1));
		economyOverrideSpawnRange = Math.max(input.getIntOr("buildmart_override_spawn_range", -1),
				input.getIntOr("economy_override_spawn_range", -1));
		economyAutoConvert = input.getBooleanOr("buildmart_auto_convert", false)
				|| input.getBooleanOr("economy_auto_convert", false);
		economyLooting = Math.max(0, Math.min(3, Math.max(input.getIntOr("buildmart_looting", 0),
				input.getIntOr("economy_looting", 0))));
		economyAutoSell = input.getBooleanOr("buildmart_auto_sell", false)
				|| input.getBooleanOr("economy_auto_sell", false);
		// 悬浮显示默认 true：旧存档/旧版笼无此键时行为不变
		economyDisplay = input.getBooleanOr("buildmart_display", true);
		economySellTimer = Math.max(input.getIntOr("buildmart_sell_timer", -1),
				input.getIntOr("economy_sell_timer", -1));
		economyHopper = input.getBooleanOr("buildmart_hopper", false)
				|| input.getBooleanOr("economy_hopper", false);
		List<String> bmWhitelist = input.read("buildmart_hopper_whitelist",
				net.minecraft.util.ExtraCodecs.NON_EMPTY_STRING.listOf()).orElse(List.of());
		economyHopperWhitelist = new ArrayList<>(bmWhitelist.isEmpty()
				? input.read("economy_hopper_whitelist",
						net.minecraft.util.ExtraCodecs.NON_EMPTY_STRING.listOf()).orElse(List.of())
				: bmWhitelist);
		List<ItemStack> bmDrops = input.read("buildmart_drops", ItemStack.OPTIONAL_CODEC.listOf())
				.orElse(List.of());
		economyDrops = new ArrayList<>(bmDrops.isEmpty()
				? input.read("economy_drops", ItemStack.OPTIONAL_CODEC.listOf()).orElse(List.of())
				: bmDrops);
		economyConverted = Math.max(input.getIntOr("buildmart_converted", 0),
				input.getIntOr("economy_converted", 0));
		economyOwnerUuid = input.read("buildmart_owner_uuid", UUIDUtil.CODEC)
				.orElse(input.read("economy_owner_uuid", UUIDUtil.CODEC).orElse(null));
		economyOwnerName = input.read("buildmart_owner_name", ExtraCodecs.NON_EMPTY_STRING)
				.orElse(input.read("economy_owner_name", ExtraCodecs.NON_EMPTY_STRING).orElse(null));
		economyPayeeUuid = input.read("buildmart_payee_uuid", UUIDUtil.CODEC)
				.orElse(input.read("economy_payee_uuid", UUIDUtil.CODEC).orElse(null));
		economyPayeeName = input.read("buildmart_payee_name", ExtraCodecs.NON_EMPTY_STRING)
				.orElse(input.read("economy_payee_name", ExtraCodecs.NON_EMPTY_STRING).orElse(null));
		economyDisplayUuid = input.read("buildmart_display_uuid", UUIDUtil.CODEC)
				.orElse(input.read("economy_display_uuid", UUIDUtil.CODEC).orElse(null));
	}

	@Inject(method = "saveAdditional", at = @At("RETURN"))
	private void buildmart$saveState(ValueOutput output, CallbackInfo ci) {
		output.putBoolean("buildmart_spawner", economyTagged);
		output.putInt("buildmart_level", economyLevel);
		output.putInt("buildmart_override_min_delay", economyOverrideMinDelay);
		output.putInt("buildmart_override_max_delay", economyOverrideMaxDelay);
		output.putInt("buildmart_override_count", economyOverrideCount);
		output.putInt("buildmart_override_nearby", economyOverrideNearby);
		output.putInt("buildmart_override_player_range", economyOverridePlayerRange);
		output.putInt("buildmart_override_spawn_range", economyOverrideSpawnRange);
		output.putBoolean("buildmart_auto_convert", economyAutoConvert);
		output.putInt("buildmart_looting", economyLooting);
		output.putBoolean("buildmart_auto_sell", economyAutoSell);
		output.putBoolean("buildmart_display", economyDisplay);
		output.putInt("buildmart_sell_timer", economySellTimer);
		output.putBoolean("buildmart_hopper", economyHopper);
		output.store("buildmart_hopper_whitelist", net.minecraft.util.ExtraCodecs.NON_EMPTY_STRING.listOf(),
				economyHopperWhitelist);
		output.store("buildmart_drops", ItemStack.OPTIONAL_CODEC.listOf(), economyDrops);
		output.putInt("buildmart_converted", economyConverted);
		output.storeNullable("buildmart_owner_uuid", UUIDUtil.CODEC, economyOwnerUuid);
		output.storeNullable("buildmart_owner_name", ExtraCodecs.NON_EMPTY_STRING, economyOwnerName);
		output.storeNullable("buildmart_payee_uuid", UUIDUtil.CODEC, economyPayeeUuid);
		output.storeNullable("buildmart_payee_name", ExtraCodecs.NON_EMPTY_STRING, economyPayeeName);
		output.storeNullable("buildmart_display_uuid", UUIDUtil.CODEC, economyDisplayUuid);
	}
}
