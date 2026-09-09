package mois.buildmart.mixin;

import mois.buildmart.config.EconomyConfig;
import mois.buildmart.fishing.FishingManager;
import mois.buildmart.spawner.SpawnerManager;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 趣味钓鱼：/bm config funFishing 开启时，鱼钩收回（retrieve 的战利品路径，
 * 即未钩住实体且已上钩）直接用 FishingManager 按概率选取的配置战利品替换原版战利品表，
 * 并沿用原版的生成方式（鱼钩位置抛向玩家 + 经验球）与 FISHING_ROD_HOOKED 成就触发。
 * 关闭时放行原版处理。配置未加载（概率和非法等）时同样回退原版。
 */
@Mixin(FishingHook.class)
public abstract class FishingHookMixin {
	@Shadow
	protected Entity hookedIn;
	@Shadow
	protected int nibble;

	@Inject(method = "retrieve", at = @At("HEAD"), cancellable = true)
	private void buildmart$customFishingLoot(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
		FishingHook hook = (FishingHook) (Object) this;
		if (!EconomyConfig.funFishing()) {
			return; // 关闭：原版战利品
		}
		if (hook.level().isClientSide()) {
			return; // 客户端原版处理（返回 0，与服务端一致）
		}
		if (hookedIn != null || nibble <= 0) {
			return; // 钩住实体/尚未上钩：原版路径
		}
		ItemStack loot = FishingManager.roll(hook.level().getRandom());
		if (loot == null) {
			return; // 配置未就绪：回退原版
		}
		ServerLevel level = (ServerLevel) hook.level();
		// 钓鱼产出的刷怪笼自动打上模组标签：与 /spawner give 底版一致的完整
		// BLOCK_ENTITY_DATA（原版默认参数 + economy_spawner + economy_level），
		// 放置后即可绑定/升级/挖取，且与 give/挖回物品 NBT 一致可互相堆叠；
		// fishing.json 里自带 BLOCK_ENTITY_DATA 的自定义物品不覆盖。
		if (loot.is(Items.SPAWNER) && !loot.has(DataComponents.BLOCK_ENTITY_DATA)) {
			ItemStack tagged = SpawnerManager.createTaggedSpawnerStack(level.registryAccess());
			loot.set(DataComponents.BLOCK_ENTITY_DATA, tagged.get(DataComponents.BLOCK_ENTITY_DATA));
		}
		cir.cancel();
		Player player = hook.getPlayerOwner();
		if (!loot.isEmpty()) {
			// 命中战利品：成就触发 + 生成（沿用原版方式：鱼钩位置、朝玩家方向的速度（原版双重 sqrt 公式）、经验球）
			if (player instanceof ServerPlayer serverPlayer) {
				CriteriaTriggers.FISHING_ROD_HOOKED.trigger(serverPlayer, rod, hook, List.of(loot));
			}
			ItemEntity itemEntity = new ItemEntity(level, hook.getX(), hook.getY(), hook.getZ(), loot);
			double dx = player.getX() - hook.getX();
			double dy = player.getY() - hook.getY();
			double dz = player.getZ() - hook.getZ();
			itemEntity.setDeltaMovement(dx * 0.1, dy * 0.1 + Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.08, dz * 0.1);
			level.addFreshEntity(itemEntity);
			level.addFreshEntity(new ExperienceOrb(level, player.getX(), player.getY() + 0.5, player.getZ(),
					level.getRandom().nextInt(6) + 1));
			// 鱼标签物品计 FISH_CAUGHT 统计（与原版一致）
			if (loot.is(ItemTags.FISHES)) {
				player.awardStat(Stats.FISH_CAUGHT, 1);
			}
		}
		// 原版战利品路径的收尾（cancel 后必须补做，否则鱼钩不销毁会重复收竿）：
		// 销毁鱼钩；返回值 1（鱼上钩），落地时 2。未命中战利品时同样正常收竿。
		int result = hook.onGround() ? 2 : 1;
		hook.discard();
		cir.setReturnValue(result);
	}
}
