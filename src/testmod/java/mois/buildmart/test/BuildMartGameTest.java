package mois.buildmart.test;

import com.mojang.brigadier.CommandDispatcher;
import mois.buildmart.config.EconomyConfig;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.gamerules.GameRules;

/**
 * BuildMart 服务端 gametest 用例（MVP）。
 * <p>
 * 覆盖两条最容易出问题、且纯单元测试覆盖不到的链路：
 * <ol>
 * <li>{@link #ruleCommandVisibilityFollowsConfig}：{@code rule.partialAdjust} 开关 →
 *     规则调整指令的权限判定（服务端侧依据 = 客户端指令树 RESTRICTED 标志）——
 *     对应曾经在生产环境出现过的"非管理员看不到/用不了这些指令"问题；</li>
 * <li>{@link #fixweatherTogglesAdvanceWeatherRule}：{@code /fixweather} 真正执行后
 *     {@code advance_weather} 游戏规则被翻转，再执行恢复。</li>
 * </ol>
 * 运行：{@code gradlew runGametest}（无头服务端，结果写入 build/gametest.xml）。
 */
public final class BuildMartGameTest {
	/** 规则调整指令中用于可见性验证的代表性指令。 */
	private static final String RULE_COMMAND = "fixweather";

	@GameTest(structure = "fabric-gametest-api-v1:empty")
	public void ruleCommandVisibilityFollowsConfig(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
		CommandSourceStack noPermissions = TestApi.sourceWithoutPermissions(server, helper.getLevel());

		// 1) 默认关闭：无权限源不应能解析规则调整指令
		boolean visibleWhenDisabled = TestApi.canParse(dispatcher, RULE_COMMAND, noPermissions);

		// 2) 开启后：无权限源应能解析（客户端指令树亦不再标记 RESTRICTED）
		String error = EconomyConfig.apply("rule.partialAdjust", "true");
		if (error != null) {
			helper.fail("设置 rule.partialAdjust=true 失败：" + error);
			return;
		}
		boolean visibleWhenEnabled = TestApi.canParse(dispatcher, RULE_COMMAND, noPermissions);

		// 3) 复位内存配置（apply 不落盘，避免污染开发配置）
		EconomyConfig.apply("rule.partialAdjust", "false");

		if (visibleWhenDisabled) {
			helper.fail("rule.partialAdjust=false 时，无权限源不应能解析 /" + RULE_COMMAND);
			return;
		}
		if (!visibleWhenEnabled) {
			helper.fail("rule.partialAdjust=true 时，无权限源应能解析 /" + RULE_COMMAND);
			return;
		}
		helper.succeed();
	}

	@GameTest(structure = "fabric-gametest-api-v1:empty")
	public void fixweatherTogglesAdvanceWeatherRule(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		GameRules rules = helper.getLevel().getGameRules();
		boolean initial = rules.get(GameRules.ADVANCE_WEATHER);

		// 控制台源（全权限）执行 /fixweather：应翻转 advance_weather
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "fixweather");
		boolean afterFirst = rules.get(GameRules.ADVANCE_WEATHER);

		// 再执行一次：应恢复原值（/fixweather 是开关语义）
		server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "fixweather");
		boolean afterSecond = rules.get(GameRules.ADVANCE_WEATHER);

		if (afterFirst == initial) {
			helper.fail("执行 /fixweather 后 advance_weather 应翻转，实际未变化（当前 " + initial + "）");
			return;
		}
		if (afterSecond != initial) {
			helper.fail("再次执行 /fixweather 应恢复原值 " + initial + "，实际为 " + afterSecond);
			return;
		}
		helper.succeed();
	}
}
