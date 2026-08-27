package mois.economy.mixin;

import java.util.OptionalInt;

import mois.economy.PriceLore;
import mois.economy.buymode.BuyModeManager;
import mois.economy.teleport.TeleportManager;
import mois.economy.util.AdminUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 管理员在玩家列表（Tab）中红色显示。26.3 中 getTabListDisplayName 是返回 null 的空实现，
 * 但 PLAYER_INFO 包构建（ClientboundPlayerInfoUpdatePacket.Entry）仍会调用它作为 Tab 显示名，
 * 因此在这里为管理员返回红色名字即可；未设置时回退到 getDisplayName（已由 PlayerMixin 染红）。
 * <p>
 * 另：打开容器时给容器界面打价格标签；关闭容器时清除容器标签并退出便捷购买模式。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	@Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
	private void economy$redTabListName(CallbackInfoReturnable<Component> cir) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		if (!AdminUtil.isAdmin(player)) {
			return;
		}
		Component current = cir.getReturnValue();
		Component base = current != null ? current : player.getDisplayName();
		cir.setReturnValue(base.copy().withStyle(ChatFormatting.RED));
	}

	@Inject(method = "openMenu", at = @At("RETURN"))
	private void economy$tagOpenedMenu(MenuProvider menuProvider, CallbackInfoReturnable<OptionalInt> cir) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		if (cir.getReturnValue().isPresent() && player.containerMenu != null) {
			// 玩家打开容器：界面内所有物品（含玩家背包部分）显示价格
			PriceLore.tagMenu(player.containerMenu);
		}
	}

	@Inject(method = "doCloseContainer", at = @At("HEAD"))
	private void economy$exitBuyModeAndUntag(CallbackInfo ci) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		// 关闭容器：清除容器物品的价格标签（玩家背包部分保留）；
		// 光标物品先清标签：若原版把它放回背包会由 Inventory.setItem 重新打标，
		// 若掉落实体则保持无标签。
		PriceLore.untagMenu(player.containerMenu, player.getInventory());
		PriceLore.untag(player.containerMenu.getCarried());
		if (BuyModeManager.isActive(player)) {
			// 光标上的物品已计入会话暂存（拿起不结算），关闭界面时由
			// BuyModeManager.exit 统一按卖出结算；这里清空服务端光标，
			// 避免原版把它放回背包或掉落实体造成重复资产。
			player.inventoryMenu.setCarried(ItemStack.EMPTY);
		}
		BuyModeManager.exit(player);
	}

	@Inject(method = "die", at = @At("HEAD"))
	private void economy$recordBackPoint(DamageSource damageSource, CallbackInfo ci) {
		// 记录最近死亡点（/back 使用；配置关闭时不记录）
		TeleportManager.recordDeath((ServerPlayer) (Object) this);
	}

	@Inject(method = "tick", at = @At("RETURN"))
	private void economy$syncFlyDigBoost(CallbackInfo ci) {
		// 每 tick 维护飞行挖掘加速属性（BLOCK_BREAK_SPEED ×5，见 FlyManager.syncDigBoost）；
		// 属性变化由原版机制自动同步客户端，纯净端本地预测/裂纹/破坏速率天然一致。
		mois.economy.fly.FlyManager.syncDigBoost((ServerPlayer) (Object) this);
	}
}
