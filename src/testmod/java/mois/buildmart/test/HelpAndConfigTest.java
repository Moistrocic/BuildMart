package mois.buildmart.test;

import mois.buildmart.config.EconomyConfig;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

import java.util.List;

/**
 * /bmhelp 全部分页 + /config 全部配置项的查询/设置测试。
 * <p>
 * 覆盖要求：
 * <ul>
 * <li>/bmhelp 与 /bmhelp 1..N 输出正确页眉；超出页码给出「页码超出范围，共 N 页」；</li>
 * <li>/config 每个配置项：查询（/config key）与设置（/config key value）都能工作；</li>
 * <li>错误路径：未知配置项、非法布尔值、必填字符串不能为空、非管理员不可见。</li>
 * </ul>
 */
public final class HelpAndConfigTest {
	private static final String EMPTY_STRUCTURE = "fabric-gametest-api-v1:empty";

	@GameTest(structure = EMPTY_STRUCTURE)
	public void bmhelpPagesAndOutOfRange(GameTestHelper helper) {
		TestPlayer player = TestPlayer.player(helper);

		// 页数从实际输出解析（HELP_LINES 变化时无需改测试）
		player.execute("/bmhelp").expectVisible(true).expectMessage("BuildMart 帮助 第 1/");
		int pages = player.messages().stream()
				.map(message -> java.util.regex.Pattern.compile("第 1/(\\d+) 页").matcher(message))
				.filter(java.util.regex.Matcher::find)
				.mapToInt(matcher -> Integer.parseInt(matcher.group(1)))
				.findFirst()
				.orElse(-1);
		if (pages <= 0) {
			helper.fail("无法从 /bmhelp 输出解析页数：" + player.messages());
			return;
		}

		for (int page = 1; page <= pages; page++) {
			player.execute("/bmhelp " + page)
					.expectVisible(true)
					.expectMessage("BuildMart 帮助 第 " + page + "/" + pages + " 页");
		}
		for (int page = pages + 1; page <= pages + 2; page++) {
			player.execute("/bmhelp " + page)
					.expectMessage("页码超出范围，共 " + pages + " 页");
		}
		helper.succeed();
	}

	/**
	 * 纯净客户端（未装本模组）视角的指令可见性：/config 只对权限等级 ≥3（ADMIN）出现在下发的
	 * 指令树里，且整条「发包→收包→客户端重建→解析」链路可用；普通玩家拿不到该节点。
	 * 别名 /bmconfig 同样可用（客户端侧 /config 撞名时用别名绕开 Fabric 的本地拦截）。
	 */
	@GameTest(structure = EMPTY_STRUCTURE)
	public void configVisibleInVanillaClientTree(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		TestPlayer player = TestPlayer.player(helper);
		var dispatcher = admin.server().getCommands().getDispatcher();
		String key = EconomyConfig.configKeys().iterator().next();

		admin.expectState(() -> TestApi.canParseAsVanillaClientOverNetwork(admin.server(), dispatcher,
				"config " + key, admin.source()), "管理员在纯净客户端视角应能解析 /config <key>");
		admin.expectState(() -> TestApi.canParseAsVanillaClientOverNetwork(admin.server(), dispatcher,
				"bmconfig " + key, admin.source()), "管理员在纯净客户端视角应能解析 /bmconfig <key>");
		player.expectState(() -> !TestApi.canParseAsVanillaClientOverNetwork(admin.server(), dispatcher,
				"config " + key, player.source()), "普通玩家不应拿到 /config 节点");
		helper.succeed();
	}

	/** 别名 /bmconfig 与 /config 功能等价：查询与设置都生效。 */
	@GameTest(structure = EMPTY_STRUCTURE)
	public void configAliasWorks(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		String boolKey = EconomyConfig.configKeys().stream()
				.filter(key -> "bool".equals(EconomyConfig.configType(key)))
				.findFirst()
				.orElse(null);
		if (boolKey == null) {
			helper.fail("没有可用于别名用例的布尔配置项");
			return;
		}
		String original = EconomyConfig.getValue(boolKey);

		admin.execute("/bmconfig " + boolKey)
				.expectVisible(true)
				.expectMessage(boolKey + " = ");
		admin.execute("/bmconfig " + boolKey + " false")
				.expectMessage(boolKey + " 已设置为 ")
				.expectState(() -> "false".equals(EconomyConfig.getValue(boolKey)), "别名应能写入配置");
		admin.execute("/bmconfig " + boolKey + " " + original)
				.expectMessage(boolKey + " 已设置为 ")
				.expectState(() -> original.equals(EconomyConfig.getValue(boolKey)), "别名应能恢复配置");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void configQueryAllKeys(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		for (String key : EconomyConfig.configKeys()) {
			admin.execute("/config " + key)
					.expectVisible(true)
					.expectMessage(key + " = ");
		}
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void configSetAllKeys(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		for (String key : EconomyConfig.configKeys()) {
			String original = EconomyConfig.getValue(key);
			String probe = probeValue(key, original);
			admin.execute("/config " + key + " " + probe)
					.expectMessage("已设置为");
			// 复位：直接改内存，避免命令转义差异影响其它用例
			EconomyConfig.apply(key, original);
		}
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void balopDomainAcceptsThreeForms(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		String original = EconomyConfig.balopDomain();

		admin.execute("/config balop.domain a.b.c")
				.expectMessage("已设置为")
				.expectState(() -> "a.b.c".equals(EconomyConfig.balopDomain()), "未加引号写法应生效");

		admin.execute("/config balop.domain \"a.b.c\"")
				.expectMessage("已设置为")
				.expectState(() -> "a.b.c".equals(EconomyConfig.balopDomain()), "带引号写法应剥离引号");

		admin.execute("/config balop.domain \"\"")
				.expectMessage("已设置为")
				.expectState(() -> EconomyConfig.balopDomain().isEmpty(), "空引号应清空域名");

		EconomyConfig.apply("balop.domain", original);
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void configErrorPaths(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);

		admin.execute("/config no.such.key").expectMessage("未知配置项");
		admin.execute("/config itemPricesInLore maybe").expectMessage("需要 true 或 false");
		admin.execute("/config balop.host \"\"").expectMessage("不能为空");

		// 非管理员：不可见且不生效
		boolean before = EconomyConfig.itemPricesInLore();
		TestPlayer.player(helper)
				.execute("/config itemPricesInLore " + (!before))
				.expectVisible(false)
				.expectState(() -> EconomyConfig.itemPricesInLore() == before, "非管理员执行 /config 不应生效");
		helper.succeed();
	}

	/** 按配置项类型给出一个合法的探测值。 */
	private static String probeValue(String key, String original) {
		String type = EconomyConfig.configType(key);
		if ("bool".equals(type)) {
			return "true".equals(original) ? "false" : "true";
		}
		if (original == null || original.isEmpty()) {
			// 字符串项且当前为空（如 balop.domain）：给个非空探测值
			return "probe.value";
		}
		return original;
	}

	/** 便于将来新增配置项时快速查看键清单（仅测试源集使用）。 */
	public static List<String> keys() {
		return EconomyConfig.configKeys();
	}
}
