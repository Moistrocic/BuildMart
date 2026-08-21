package mois.economy.util;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;

/**
 * 管理员判定：单人游戏所有者，或权限等级为“管理员”（ADMINS，原 /op 3 级）及以上。
 */
public final class AdminUtil {
	private AdminUtil() {
	}

	public static boolean isAdmin(ServerPlayer player) {
		MinecraftServer server = player.createCommandSourceStack().getServer();
		if (server == null) {
			return false;
		}
		NameAndId id = new NameAndId(player.getGameProfile());
		return server.isSingleplayerOwner(id)
				|| server.getProfilePermissions(id).level().isEqualOrHigherThan(PermissionLevel.ADMINS);
	}
}
