package mois.economy.mixin;

import mois.economy.util.AdminUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 管理员在聊天框中红色显示：服务端构建聊天包（ChatType.Bound）时调用
 * Player.getDisplayName() 作为发送者名，因此染红该方法即可让管理员的名字
 * 在所有玩家的聊天框里显示为红色。
 * <p>
 * 注意：getDisplayName 在 26.3 中由 Player 重写（不含 super 调用），
 * 若注入到 Entity 上将无法拦截玩家实例，故必须针对 Player 注入。
 * 客户端环境没有 ServerPlayer 实例，过滤条件恒为假，无副作用。
 */
@Mixin(Player.class)
public abstract class PlayerMixin {
	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void economy$redDisplayName(CallbackInfoReturnable<Component> cir) {
		if (!((Object) this instanceof ServerPlayer player) || !AdminUtil.isAdmin(player)) {
			return;
		}
		Component current = cir.getReturnValue();
		if (current == null) {
			return;
		}
		cir.setReturnValue(current.copy().withStyle(ChatFormatting.RED));
	}
}
