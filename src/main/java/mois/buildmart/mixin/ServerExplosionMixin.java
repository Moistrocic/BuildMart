package mois.buildmart.mixin;

import mois.buildmart.shop.ShopManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ServerExplosion;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/**
 * 商店箱子免疫一切爆炸：在 ServerExplosion 计算出的破坏位置列表中
 * 剔除商店箱子（含双箱另一半）。26.3 中爆炸破坏只消费这份列表，
 * 因此在列表层过滤即可覆盖 TNT/苦力怕/凋灵等所有实体来源爆炸
 * （不依赖 ExplosionDamageCalculator 子类的重写行为）。
 */
@Mixin(ServerExplosion.class)
public abstract class ServerExplosionMixin {
	@Shadow
	@Final
	private ServerLevel level;

	@Inject(method = "calculateExplodedPositions", at = @At("RETURN"))
	private void buildmart$removeShopPositions(CallbackInfoReturnable<List<BlockPos>> cir) {
		List<BlockPos> positions = cir.getReturnValue();
		positions.removeIf(pos -> ShopManager.isShopOrHalf(level, pos));
	}
}
