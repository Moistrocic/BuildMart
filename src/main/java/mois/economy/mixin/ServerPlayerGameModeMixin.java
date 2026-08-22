package mois.economy.mixin;

import mois.economy.shop.Shop;
import mois.economy.shop.ShopManager;
import mois.economy.util.AdminUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerPlayerGameMode;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 商店箱子保护：仅创建人或管理员可拆除（含双箱另一半）；
 * 创建人拆除成功时自动移除商店。
 */
@Mixin(ServerPlayerGameMode.class)
public abstract class ServerPlayerGameModeMixin {
	@Shadow
	@Final
	protected ServerPlayer player;

	@Shadow
	protected ServerLevel level;

	@Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
	private void economy$protectShop(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		Shop shop = ShopManager.getShopOrHalf(level, pos);
		if (shop == null) {
			return;
		}
		if (!AdminUtil.isAdmin(player) && !shop.owner().equals(player.getUUID())) {
			player.sendSystemMessage(
					Component.literal("只能由商店所有者拆除该箱子").withStyle(ChatFormatting.RED), false);
			cir.setReturnValue(false);
		}
	}

	@Inject(method = "destroyBlock", at = @At("RETURN"))
	private void economy$removeShopOnBreak(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		if (cir.getReturnValue()) {
			ShopManager.removeIfShop(level, pos);
		}
	}
}
