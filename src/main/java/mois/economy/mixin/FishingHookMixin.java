package mois.economy.mixin;

import mois.economy.config.EconomyConfig;
import mois.economy.fishing.FishingManager;
import net.minecraft.advancements.triggers.CriteriaTriggers;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.FishingHook;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 趣味钓鱼：/config funFishing 开启时，鱼钩收回（retrieve 的战利品路径，
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
	private void economy$customFishingLoot(ItemStack rod, CallbackInfoReturnable<Integer> cir) {
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
		cir.cancel();
		ServerLevel level = (ServerLevel) hook.level();
		Player player = hook.getPlayerOwner();
		// 与 FISHING_ROD_HOOKED 成就触发保持一致（原版在生成战利品前触发）
		if (player instanceof ServerPlayer serverPlayer) {
			CriteriaTriggers.FISHING_ROD_HOOKED.trigger(serverPlayer, rod, hook, List.of(loot));
		}
		// 沿用原版生成方式：鱼钩位置、朝玩家方向的速度（原版双重 sqrt 公式）、经验球
		ItemEntity itemEntity = new ItemEntity(level, hook.getX(), hook.getY(), hook.getZ(), loot);
		double dx = player.getX() - hook.getX();
		double dy = player.getY() - hook.getY();
		double dz = player.getZ() - hook.getZ();
		itemEntity.setDeltaMovement(dx * 0.1, dy * 0.1 + Math.sqrt(Math.sqrt(dx * dx + dy * dy + dz * dz)) * 0.08, dz * 0.1);
		level.addFreshEntity(itemEntity);
		level.addFreshEntity(new ExperienceOrb(level, player.getX(), player.getY() + 0.5, player.getZ(),
				level.getRandom().nextInt(6) + 1));
		cir.setReturnValue(0);
	}
}
