package mois.buildmart.test;

import mois.buildmart.balop.BalopServer;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * /balop 数据库管理前端测试：启动会话（含链接提示）、重复启动拒绝、停止关闭。
 */
public final class BalopTest {
	private static final String EMPTY_STRUCTURE = "fabric-gametest-api-v1:empty";

	@GameTest(structure = EMPTY_STRUCTURE)
	public void startStopLifecycle(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);

		admin.execute("/balop start")
				.expectVisible(true)
				.expectMessage("数据库管理前端已启动")
				.expectState(BalopServer::isRunning, "HTTP 服务应在运行");

		admin.execute("/balop start").expectMessage("你已有开启中的管理前端会话");

		admin.execute("/balop stop")
				.expectMessage("已关闭 1 个管理前端会话")
				.expectState(() -> !BalopServer.isRunning(), "停止后 HTTP 服务应关闭");

		admin.execute("/balop stop").expectMessage("你没有开启中的管理前端会话");
		helper.succeed();
	}
}
