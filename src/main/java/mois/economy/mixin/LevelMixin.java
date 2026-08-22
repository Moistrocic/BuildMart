package mois.economy.mixin;

import mois.economy.shop.ShopManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 兜底保护：任何无实体的外部破坏（末影龙等不走爆炸路径的途径）无法拆除商店箱子。
 * 玩家破坏不经过此方法（ServerPlayerGameMode 自行处理），实体引起的破坏不受影响。
 */
@Mixin(Level.class)
public abstract class LevelMixin {
	@Inject(method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
			at = @At("HEAD"), cancellable = true)
	private void economy$protectShop(BlockPos pos, boolean drop, Entity entity, int flags,
			CallbackInfoReturnable<Boolean> cir) {
		if (entity != null || !((Object) this instanceof ServerLevel serverLevel)) {
			return;
		}
		if (ShopManager.isShopOrHalf(serverLevel, pos)) {
			cir.setReturnValue(false);
		}
	}
}
