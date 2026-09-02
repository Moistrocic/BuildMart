package mois.buildmart.util;

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
		// 注意：不能使用 createCommandSourceStack() 取服务器——它会调用 getDisplayName()，
		// 与 PlayerMixin 的注入形成无限递归。level().getServer() 无此依赖。
		MinecraftServer server = player.level().getServer();
		if (server == null) {
			return false;
		}
		NameAndId id = new NameAndId(player.getGameProfile());
		return server.isSingleplayerOwner(id)
				|| server.getProfilePermissions(id).level().isEqualOrHigherThan(PermissionLevel.ADMINS);
	}
}
