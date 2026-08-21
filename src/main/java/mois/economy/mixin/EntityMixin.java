package mois.economy.mixin;

import mois.economy.util.AdminUtil;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 管理员在聊天框中红色显示：服务端构建聊天包（ChatType.Bound）时调用
 * ServerPlayer.getDisplayName() 作为发送者名，因此染红该方法即可让
 * 管理员的名字在所有玩家的聊天框里显示为红色。
 * <p>
 * 方法声明在 Entity 上，故对 Entity 注入并过滤 ServerPlayer 实例；
 * 客户端环境没有 ServerPlayer 实例，过滤条件恒为假，无副作用。
 */
@Mixin(Entity.class)
public abstract class EntityMixin {
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
