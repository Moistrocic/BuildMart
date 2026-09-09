package mois.buildmart.test.mixin;

import mois.buildmart.test.MessageSpy;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 测试专用 mixin（仅 buildmart-testmod 加载）：拦截 {@link ServerPlayer} 的系统消息发送，
 * 交给 {@link MessageSpy} 记录并取消原发送——测试用玩家因此不需要真实连接。
 * 未处于捕获状态时完全不干预（直接放行）。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMessageSpyMixin {
	@Inject(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;Z)V",
			at = @At("HEAD"), cancellable = true)
	private void buildmart$spySystemMessage(Component message, boolean bypassHiddenChat, CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (MessageSpy.capture(self, message.getString())) {
			ci.cancel();
		}
	}

	@Inject(method = "sendSystemMessage(Lnet/minecraft/network/chat/Component;)V",
			at = @At("HEAD"), cancellable = true)
	private void buildmart$spySystemMessage(Component message, CallbackInfo ci) {
		ServerPlayer self = (ServerPlayer) (Object) this;
		if (MessageSpy.capture(self, message.getString())) {
			ci.cancel();
		}
	}
}
