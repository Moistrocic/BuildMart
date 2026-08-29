package mois.economy.mixin;

import mois.economy.spawner.SpawnerStateAccess;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

	@Inject(method = "loadAdditional", at = @At("RETURN"))
	private void economy$loadState(ValueInput input, CallbackInfo ci) {
		economyTagged = input.getBooleanOr("economy_spawner", false);
		economyLevel = Math.max(0, input.getIntOr("economy_level", 0));
		economyOverrideMinDelay = input.getIntOr("economy_override_min_delay", -1);
		economyOverrideMaxDelay = input.getIntOr("economy_override_max_delay", -1);
		economyOverrideCount = input.getIntOr("economy_override_count", -1);
		economyOverrideNearby = input.getIntOr("economy_override_nearby", -1);
		economyOverridePlayerRange = input.getIntOr("economy_override_player_range", -1);
		economyOverrideSpawnRange = input.getIntOr("economy_override_spawn_range", -1);
	}

	@Inject(method = "saveAdditional", at = @At("RETURN"))
	private void economy$saveState(ValueOutput output, CallbackInfo ci) {
		output.putBoolean("economy_spawner", economyTagged);
		output.putInt("economy_level", economyLevel);
		output.putInt("economy_override_min_delay", economyOverrideMinDelay);
		output.putInt("economy_override_max_delay", economyOverrideMaxDelay);
		output.putInt("economy_override_count", economyOverrideCount);
		output.putInt("economy_override_nearby", economyOverrideNearby);
		output.putInt("economy_override_player_range", economyOverridePlayerRange);
		output.putInt("economy_override_spawn_range", economyOverrideSpawnRange);
	}
}
