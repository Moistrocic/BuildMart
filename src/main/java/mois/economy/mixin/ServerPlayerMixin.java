package mois.economy.mixin;

import mois.economy.util.AdminUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 管理员在玩家列表（Tab）中红色显示。Tab 名单由 getTabListDisplayName 决定，
 * 未单独设置名单时回退到 getDisplayName（已由 EntityMixin 染红）。
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
}
