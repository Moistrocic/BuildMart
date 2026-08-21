package mois.economy.mixin;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 管理员名称红色显示：聊天发送者名（getDisplayName）与玩家列表/Tab（getTabListDisplayName）
 * 统一染红。客户端聊天头像是从玩家列表条目取名的，因此染红 Tab 名单即可同时覆盖聊天框。
 * <p>
 * 管理员判定：单人游戏所有者，或权限等级为“管理员”（ADMINS，原 /op 3 级）及以上。
 */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {
	@Inject(method = "getDisplayName", at = @At("RETURN"), cancellable = true)
	private void economy$redDisplayName(CallbackInfoReturnable<Component> cir) {
		if (!((Object) this instanceof ServerPlayer player) || !economy$isAdmin(player)) {
			return;
		}
		cir.setReturnValue(cir.getReturnValue().copy().withStyle(ChatFormatting.RED));
	}

	@Inject(method = "getTabListDisplayName", at = @At("RETURN"), cancellable = true)
	private void economy$redTabListName(CallbackInfoReturnable<Component> cir) {
		ServerPlayer player = (ServerPlayer) (Object) this;
		if (!economy$isAdmin(player)) {
			return;
		}
		Component current = cir.getReturnValue();
		Component base = current != null ? current : player.getDisplayName();
		cir.setReturnValue(base.copy().withStyle(ChatFormatting.RED));
	}

	@Unique
	private static boolean economy$isAdmin(ServerPlayer player) {
		MinecraftServer server = player.createCommandSourceStack().getServer();
		if (server == null) {
			return false;
		}
		NameAndId id = new NameAndId(player.getGameProfile());
		if (server.isSingleplayerOwner(id)) {
			return true;
		}
		LevelBasedPermissionSet permissions = server.getProfilePermissions(id);
		return permissions.level().isEqualOrHigherThan(PermissionLevel.ADMINS);
	}
}
