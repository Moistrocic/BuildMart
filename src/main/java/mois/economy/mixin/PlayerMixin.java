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

	/**
	 * 飞行中挖掘速度恢复为地面速度：26.3 原版对不在地面的玩家在
	 * getDestroySpeed 末尾执行 {@code f / 5.0F}（空中挖掘惩罚，速度降至 1/5）。
	 * /fly 付费飞行开启后玩家悬浮空中，挖掘会变得极慢；此处对「正在飞行且
	 * 未落地」的玩家撤销该惩罚（×5 还原），使飞行挖掘速度与地面一致。
	 * 注入 Player 是通用类（服务端权威判定 + 客户端本地预测同步生效）；
	 * 纯净客户端未装模组时服务端仍按恢复后的速度破坏（客户端进度显示略慢，
	 * 方块在服务端进度满时破坏，可正常游玩）。
	 */
	@Inject(method = "getDestroySpeed", at = @At("RETURN"), cancellable = true)
	private void economy$restoreDigSpeedWhileFlying(CallbackInfoReturnable<Float> cir) {
		// /config flyDigSpeedRestore 开关：关闭时保留原版空中挖掘惩罚
		if (!mois.economy.config.EconomyConfig.flyDigSpeedRestore()) {
			return;
		}
		Player player = (Player) (Object) this;
		if (!player.onGround() && player.getAbilities().flying) {
			cir.setReturnValue(cir.getReturnValue() * 5.0F);
		}
	}
}
