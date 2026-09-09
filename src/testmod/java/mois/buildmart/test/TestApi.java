package mois.buildmart.test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionSet;

/**
 * 测试 API（仅存在于 {@code src/testmod}，不进发布 jar）：给 gametest 用例提供
 * 「构造无权限命令源 / 判断指令是否可解析」等观测与控制手段。
 * <p>
 * 设计约定（见测试方案）：断言优先读状态（权限判定/游戏规则/数据库），日志断言只作辅助。
 */
public final class TestApi {
	private TestApi() {
	}

	/**
	 * 构造一个**无任何权限**的命令源（等价于非管理员玩家在权限层面的解析视角）：
	 * 用于验证指令树的 requirement 判定（即客户端指令树 RESTRICTED 标志的服务端依据）。
	 */
	public static CommandSourceStack sourceWithoutPermissions(MinecraftServer server, ServerLevel level) {
		return server.createCommandSourceStack()
				.withLevel(level)
				.withPermission(PermissionSet.NO_PERMISSIONS);
	}

	/**
	 * 该指令对给定命令源是否可解析（requirement 通过且匹配到可执行节点）。
	 * brigadier 在 parseNodes 阶段用 {@code canUse(source)} 过滤节点，因此本方法
	 * 能真实反映"玩家输入该指令时服务端会不会走到执行层"。
	 */
	public static boolean canParse(CommandDispatcher<CommandSourceStack> dispatcher,
			String command, CommandSourceStack source) {
		ParseResults<CommandSourceStack> result = dispatcher.parse(command, source);
		return result.getContext().getCommand() != null;
	}
}
