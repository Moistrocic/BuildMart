package mois.economy.mixin;

import mois.economy.buymode.BuyModeManager;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
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

	@Shadow
	private boolean isDestroyingBlock;

	@Shadow
	private BlockPos destroyPos;

	@Shadow
	private int destroyProgressStart;

	@Shadow
	private boolean hasDelayedDestroy;

	@Shadow
	private int gameTicks;

	@Shadow
	public abstract boolean destroyBlock(BlockPos pos);

	@Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
	private void economy$protectShop(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		// 便捷购买模式授予了 instabuild，客户端会以创造方式破坏（秒破且无掉落物）：
		// 购买模式下禁止破坏方块，关闭 buymode 后恢复正常生存挖掘。
		if (BuyModeManager.isActive(player)) {
			player.sendSystemMessage(
					Component.literal("便捷购买模式下无法破坏方块").withStyle(ChatFormatting.RED), false);
			cir.setReturnValue(false);
			return;
		}
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

	/**
	 * 纯净客户端（无 mod）飞行挖掘加速：26.3 服务端对普通破坏（isDestroyingBlock）
	 * 只广播裂纹进度、**不判定破坏**——破坏时刻由客户端本地进度满后发送的
	 * DESTROY_BLOCK 包决定（hasDelayedDestroy 分支才会由服务端判定）。
	 * 纯净端本地 getDestroySpeed 未恢复（慢 5 倍）→ 实际挖掘变慢（裂纹显示快是假象）。
	 * 修复：飞行挖掘加速（fly.digNoSlow）生效、服务端权威进度已满时，由服务端
	 * 直接 destroyBlock（下一 tick 原版 isAir 分支自动复位 isDestroyingBlock；
	 * destroyBlock 幂等，且商店保护/buymode 禁挖的既有拦截一并生效）。
	 */
	@Inject(method = "tick", at = @At("RETURN"))
	private void economy$earlyDestroyForVanillaClient(CallbackInfo ci) {
		if (!mois.economy.network.FlyConfigSync.digNoSlow
				|| !player.getAbilities().flying || player.onGround()
				|| !isDestroyingBlock || hasDelayedDestroy) {
			return;
		}
		float progress = level.getBlockState(destroyPos)
				.getDestroyProgress(player, level, destroyPos) * (gameTicks - destroyProgressStart + 1);
		if (progress >= 1.0F) {
			destroyBlock(destroyPos);
		}
	}
}
