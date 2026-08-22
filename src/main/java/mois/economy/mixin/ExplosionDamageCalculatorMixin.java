package mois.economy.mixin;

import mois.economy.shop.ShopManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.ExplosionDamageCalculator;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 商店箱子免疫爆炸（TNT/苦力怕/凋灵等所有走 Explosion 的破坏）：
 * 在爆炸破坏判定阶段直接返回 false。
 */
@Mixin(ExplosionDamageCalculator.class)
public abstract class ExplosionDamageCalculatorMixin {
	@Inject(method = "shouldBlockExplode", at = @At("HEAD"), cancellable = true)
	private void economy$protectShopFromExplosion(Explosion explosion, BlockGetter blockGetter, BlockPos pos,
			BlockState state, float power, CallbackInfoReturnable<Boolean> cir) {
		if (blockGetter instanceof ServerLevel serverLevel && ShopManager.isShopOrHalf(serverLevel, pos)) {
			cir.setReturnValue(false);
		}
	}
}
