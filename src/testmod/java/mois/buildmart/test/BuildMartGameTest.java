package mois.buildmart.test;

import mois.buildmart.config.EconomyConfig;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * BuildMart 基础功能 gametest：覆盖「执行者输入指令 → 消息/可见性/服务端状态正确」这类
 * 显而易见、且最容易因版本迁移或新功能而失效的行为（复杂交互交由真人测试）。
 * <p>
 * 运行：{@code gradlew runGametest}（无头服务端，报告写入 build/gametest.xml）。
 */
public final class BuildMartGameTest {
	@GameTest(structure = "fabric-gametest-api-v1:empty")
	public void bmhelpWorksForNormalPlayer(GameTestHelper helper) {
		TestPlayer.player(helper)
				.execute("/bmhelp")
				.expectVisible(true)
				.expectMessage("BuildMart 帮助");
		helper.succeed();
	}

	@GameTest(structure = "fabric-gametest-api-v1:empty")
	public void configDeniedForNormalPlayer(GameTestHelper helper) {
		TestPlayer.player(helper)
				.execute("/config rule.partialAdjust true")
				.expectVisible(false)
				.expectState(() -> !EconomyConfig.partialAdjust(),
						"非管理员执行 /config 不应修改配置");
		helper.succeed();
	}

	@GameTest(structure = "fabric-gametest-api-v1:empty")
	public void configWorksForAdminPlayer(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		admin.execute("/config rule.partialAdjust true")
				.expectVisible(true)
				.expectMessage("已设置为")
				.expectState(EconomyConfig::partialAdjust, "管理员执行后 rule.partialAdjust 应为 true");
		admin.execute("/config rule.partialAdjust false")
				.expectState(() -> !EconomyConfig.partialAdjust(), "复位后 rule.partialAdjust 应为 false");
		helper.succeed();
	}

	@GameTest(structure = "fabric-gametest-api-v1:empty")
	public void ruleCommandVisibilityFollowsConfig(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		TestPlayer normal = TestPlayer.player(helper);

		// 关闭时：非管理员不可见
		admin.execute("/config rule.partialAdjust false");
		normal.execute("/fixweather").expectVisible(false);

		// 开启时：非管理员可见（客户端指令树不再标记 RESTRICTED）
		admin.execute("/config rule.partialAdjust true");
		normal.execute("/fixweather").expectVisible(true).expectMessage("天气已固定");

		// 复位配置与游戏规则
		admin.execute("/config rule.partialAdjust false");
		admin.execute("/fixweather");
		helper.succeed();
	}

	@GameTest(structure = "fabric-gametest-api-v1:empty")
	public void fixweatherTogglesAdvanceWeatherRule(GameTestHelper helper) {
		GameRules rules = helper.getLevel().getGameRules();
		boolean initial = rules.get(GameRules.ADVANCE_WEATHER);

		TestPlayer.admin(helper)
				.execute("/fixweather")
				.expectMessage("天气已固定")
				.expectState(() -> rules.get(GameRules.ADVANCE_WEATHER) != initial,
						"执行 /fixweather 后 advance_weather 应翻转");

		TestPlayer.admin(helper)
				.execute("/fixweather")
				.expectMessage("已恢复天气自然变化")
				.expectState(() -> rules.get(GameRules.ADVANCE_WEATHER) == initial,
						"再次执行 /fixweather 后 advance_weather 应恢复原值");

		helper.succeed();
	}
}
