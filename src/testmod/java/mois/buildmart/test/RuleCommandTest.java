package mois.buildmart.test;

import mois.buildmart.config.EconomyConfig;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * 规则调整指令测试：/fixweather /fixtime /naturalmonsterspawn 与原版 /weather、/time 的
 * 权限放宽（rule.partialAdjust 开关 → 非管理员可见性）。
 */
public final class RuleCommandTest {
	private static final String EMPTY_STRUCTURE = "fabric-gametest-api-v1:empty";

	@GameTest(structure = EMPTY_STRUCTURE)
	public void ruleCommandsVisibilityFollowsConfig(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		TestPlayer player = TestPlayer.player(helper);

		admin.execute("/bm config rule.partialAdjust false");
		assertRuleCommandVisibility(player, false);

		admin.execute("/bm config rule.partialAdjust true");
		assertRuleCommandVisibility(player, true);
		admin.execute("/bm config rule.partialAdjust false");
		helper.succeed();
	}

	/** 权限矩阵：/fixweather /fixtime /naturalmonsterspawn /weather /time 的可见性（只解析不执行）。 */
	private static void assertRuleCommandVisibility(TestPlayer player, boolean expected) {
		player.checkVisible("/fixweather", expected)
				.checkVisible("/fixtime", expected)
				.checkVisible("/naturalmonsterspawn", expected)
				.checkVisible("/weather clear", expected)
				.expectState(() -> TestWorld.canUseNode(
						player.server().getCommands().getDispatcher(), "time", player.source()) == expected,
						"/time 节点可见性应为 " + expected);
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void fixweatherTogglesAdvanceWeather(GameTestHelper helper) {
		GameRules rules = helper.getLevel().getGameRules();
		// 归一到已知基线，避免受其它用例影响
		rules.set(GameRules.ADVANCE_WEATHER, true, helper.getLevel().getServer());
		TestPlayer admin = TestPlayer.admin(helper);

		admin.execute("/fixweather")
				.expectVisible(true)
				.expectMessage("天气已固定")
				.expectState(() -> !rules.get(GameRules.ADVANCE_WEATHER), "advance_weather 应为 false");
		admin.execute("/fixweather")
				.expectMessage("已恢复天气自然变化")
				.expectState(() -> rules.get(GameRules.ADVANCE_WEATHER), "advance_weather 应为 true");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void fixtimeTogglesAdvanceTime(GameTestHelper helper) {
		GameRules rules = helper.getLevel().getGameRules();
		rules.set(GameRules.ADVANCE_TIME, true, helper.getLevel().getServer());
		TestPlayer admin = TestPlayer.admin(helper);

		admin.execute("/fixtime")
				.expectVisible(true)
				.expectMessage("时间已固定")
				.expectState(() -> !rules.get(GameRules.ADVANCE_TIME), "advance_time 应为 false");
		admin.execute("/fixtime")
				.expectMessage("已恢复时间流动")
				.expectState(() -> rules.get(GameRules.ADVANCE_TIME), "advance_time 应为 true");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void naturalMonsterSpawnQueryAndSet(GameTestHelper helper) {
		GameRules rules = helper.getLevel().getGameRules();
		boolean initial = rules.get(GameRules.SPAWN_MONSTERS);
		TestPlayer admin = TestPlayer.admin(helper);

		admin.execute("/naturalmonsterspawn")
				.expectVisible(true)
				.expectMessage("自然怪物生成当前：");
		admin.execute("/naturalmonsterspawn false")
				.expectMessage("已关闭自然怪物生成")
				.expectState(() -> !rules.get(GameRules.SPAWN_MONSTERS), "spawn_monsters 应为 false");
		admin.execute("/naturalmonsterspawn true")
				.expectMessage("已开启自然怪物生成")
				.expectState(() -> rules.get(GameRules.SPAWN_MONSTERS), "spawn_monsters 应为 true");
		admin.execute("/naturalmonsterspawn maybe").expectMessage("需要 true 或 false");

		rules.set(GameRules.SPAWN_MONSTERS, initial, helper.getLevel().getServer());
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void vanillaWeatherWorksForPlayersWhenEnabled(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		TestPlayer player = TestPlayer.player(helper);

		// 关闭时非管理员不可见（只解析不执行）
		admin.execute("/bm config rule.partialAdjust false");
		player.checkVisible("/weather rain", false);

		// 开启时非管理员可用，且真的改变天气
		admin.execute("/bm config rule.partialAdjust true");
		player.execute("/weather rain")
				.expectVisible(true)
				.expectState(() -> helper.getLevel().getWeatherData().isRaining(), "rain 后应下雨");
		player.execute("/weather clear")
				.expectVisible(true)
				.expectState(() -> !helper.getLevel().getWeatherData().isRaining(), "clear 后应放晴");

		admin.execute("/bm config rule.partialAdjust false");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void vanillaTimeWorksForPlayersWhenEnabled(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		TestPlayer player = TestPlayer.player(helper);

		admin.execute("/bm config rule.partialAdjust false");
		player.checkVisible("/time set day", false);

		admin.execute("/bm config rule.partialAdjust true");
		player.execute("/time set day").expectVisible(true);
		long day = player.get(() -> helper.getLevel().getDefaultClockTime());
		player.execute("/time set night").expectVisible(true);
		long night = player.get(() -> helper.getLevel().getDefaultClockTime());
		player.expectState(() -> day != night, "/time set 应真正改变世界时间");

		admin.execute("/bm config rule.partialAdjust false");
		helper.succeed();
	}
}
