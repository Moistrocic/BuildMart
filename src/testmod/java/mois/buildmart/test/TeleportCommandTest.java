package mois.buildmart.test;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

/**
 * 传送与杂项指令测试：/sethome /home /delhome /listhome /tpa /tpahere /tpaccept /back，
 * 以及仅限玩家的指令在控制台下的报错。
 */
public final class TeleportCommandTest {
	private static final String EMPTY_STRUCTURE = "fabric-gametest-api-v1:empty";
	private static final String OFFLINE = "bm_offline_player";

	@GameTest(structure = EMPTY_STRUCTURE)
	public void homeLifecycle(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		// 开放家功能（默认上限 0）
		admin.execute("/config home.max 2").expectMessage("已设置为");

		TestPlayer player = TestPlayer.player(helper);
		player.execute("/sethome bmtest")
				.expectVisible(true)
				.expectMessage("已设置家");
		player.execute("/sethome bmtest2").expectMessage("已设置家");
		player.execute("/listhome")
				.expectMessage("我的家")
				.expectMessage("bmtest");
		player.execute("/home bmtest").expectMessage("已传送");
		player.execute("/delhome bmtest").expectMessage("已删除家");
		// 仍有 bmtest2，因此是"找不到该名字"而不是"还没有设置家"
		player.execute("/home bmtest").expectMessage("没有找到名为 bmtest 的家");
		player.execute("/delhome bmtest2").expectMessage("已删除家");

		admin.execute("/config home.max 0");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void teleportRequestErrorPaths(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		TestPlayer player = TestPlayer.player(helper);

		// 功能未开放时的提示
		player.execute("/tpa " + OFFLINE).expectMessage("传送请求功能未开放");
		player.execute("/tpahere " + OFFLINE).expectMessage("传送请求功能未开放");
		player.execute("/tpaccept").expectMessage("传送请求功能未开放");

		// 开放后：目标不在线
		admin.execute("/config tpa.enabled true").expectMessage("已设置为");
		player.execute("/tpa " + OFFLINE).expectMessage("该玩家不在线");
		player.execute("/tpahere " + OFFLINE).expectMessage("该玩家不在线");
		player.execute("/tpaccept").expectMessage("没有待接受的传送请求");

		admin.execute("/config tpa.enabled false");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void backErrorPathAndConsoleOnly(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		admin.execute("/config back.enabled true").expectMessage("已设置为");
		TestPlayer player = TestPlayer.player(helper);
		player.execute("/back").expectMessage("没有可返回的死亡点");
		admin.execute("/config back.enabled false");

		TestPlayer console = TestPlayer.console(helper);
		console.executeExpectFailure("/balop start", "该指令只能由玩家执行");
		console.executeExpectFailure("/home test", "该指令只能由玩家执行");
		console.executeExpectFailure("/bm", "该指令只能由玩家执行");
		console.executeExpectFailure("/fly", "该指令只能由玩家执行");
		console.executeExpectFailure("/spawner info", "该指令只能由玩家执行");
		helper.succeed();
	}
}
