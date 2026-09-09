package mois.buildmart.test;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;

import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 测试用执行者（高复用 DSL）：模拟「玩家 / 管理员玩家 / 控制台」执行指令，并校验
 * 显而易见的可观测结果——收到的消息、指令可见性、服务端状态（配置/游戏规则/库存等）。
 * <p>
 * 典型用法：
 * <pre>{@code
 * TestPlayer.player(helper)
 *     .execute("/bmhelp")
 *     .expectMessage("BuildMart 帮助")   // 服务端发给该玩家的消息（客户端将显示的内容）
 *     .expectVisible(true);              // 指令对该执行者可见/可解析
 * TestPlayer.admin(helper)
 *     .execute("/config rule.partialAdjust true")
 *     .expectMessage("已设置为")
 *     .expectState(EconomyConfig::partialAdjust, "配置应被修改为 true");
 * }</pre>
 * <b>客户端侧说明</b>：服务端 gametest 中没有真实客户端，因此「客户端行为」以两个代理
 * 指标校验：① 服务端发给该玩家的消息文本（客户端会显示的内容）；② 指令树可见性
 * （{@link #expectVisible(boolean)}，即客户端 RESTRICTED 判定的服务端依据）。
 * 真正需要客户端 UI 的行为（如 /fly 后双击空格飞行）不在此覆盖，交由真人测试。
 */
public final class TestPlayer {
	private final GameTestHelper helper;
	private final MinecraftServer server;
	private final ServerLevel level;
	/** null = 控制台。 */
	private final ServerPlayer player;
	/** true = 管理员权限（玩家身份 + 全权限集）。 */
	private final boolean elevated;
	private List<String> messages = List.of();
	private String lastCommand = "";

	private TestPlayer(GameTestHelper helper, ServerPlayer player, boolean elevated) {
		this.helper = helper;
		this.server = helper.getLevel().getServer();
		this.level = helper.getLevel();
		this.player = player;
		this.elevated = elevated;
	}

	/** 非管理员玩家（无任何权限，等价于普通生存玩家）。 */
	public static TestPlayer player(GameTestHelper helper) {
		return new TestPlayer(helper, createPlayer(helper), false);
	}

	/** 管理员玩家（玩家身份 + 全权限集，等价于 op 4 玩家）。 */
	public static TestPlayer admin(GameTestHelper helper) {
		return new TestPlayer(helper, createPlayer(helper), true);
	}

	/** 控制台执行者（全权限、无玩家身份）。 */
	public static TestPlayer console(GameTestHelper helper) {
		return new TestPlayer(helper, null, true);
	}

	private static ServerPlayer createPlayer(GameTestHelper helper) {
		MinecraftServer server = helper.getLevel().getServer();
		GameProfile profile = new GameProfile(UUID.randomUUID(), "buildmart_test");
		return new ServerPlayer(server, helper.getLevel(), profile, ClientInformation.createDefault());
	}

	/** 该执行者的命令源（管理员为全权限，控制台为服务端控制台源）。 */
	public CommandSourceStack source() {
		CommandSourceStack source = player != null
				? player.createCommandSourceStack()
				: server.createCommandSourceStack();
		return elevated && player != null ? source.withPermission(PermissionSet.ALL_PERMISSIONS) : source;
	}

	// ---------- 执行 ----------

	/** 以该执行者身份执行一条指令（可带或不带前导 '/'），并捕获其收到的消息。 */
	public TestPlayer execute(String command) {
		this.lastCommand = command.startsWith("/") ? command.substring(1) : command;
		if (player != null) {
			MessageSpy.begin(player.getUUID());
		}
		try {
			server.getCommands().performPrefixedCommand(source(), command);
		} finally {
			if (player != null) {
				messages = MessageSpy.end(player.getUUID());
			}
		}
		return this;
	}

	// ---------- 校验 ----------

	/** 断言执行期间该执行者收到过包含指定文本的消息（客户端将显示的内容）。 */
	public TestPlayer expectMessage(String text) {
		boolean found = messages.stream().anyMatch(message -> message.contains(text));
		if (!found) {
			helper.fail("执行 /" + lastCommand + " 后应收到包含「" + text + "」的消息，实际收到：" + messages);
		}
		return this;
	}

	/** 断言执行期间该执行者没有收到包含指定文本的消息。 */
	public TestPlayer expectNoMessage(String text) {
		boolean found = messages.stream().anyMatch(message -> message.contains(text));
		if (found) {
			helper.fail("执行 /" + lastCommand + " 后不应收到包含「" + text + "」的消息，实际收到：" + messages);
		}
		return this;
	}

	/**
	 * 断言该执行者能否解析上一条执行的指令（客户端指令树可见性的服务端依据）：
	 * 无权限执行受限指令时为 false，有权限或指令对所有人开放时为 true。
	 */
	public TestPlayer expectVisible(boolean expected) {
		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
		boolean visible = TestApi.canParse(dispatcher, lastCommand, source());
		if (visible != expected) {
			helper.fail("指令 /" + lastCommand + " 对该执行者的可见性应为 " + expected + "，实际为 " + visible);
		}
		return this;
	}

	/** 断言服务端状态满足条件（配置项/游戏规则/库存/数据库等任意可读状态）。 */
	public TestPlayer expectState(BooleanSupplier condition, String description) {
		if (!condition.getAsBoolean()) {
			helper.fail("服务端状态断言失败：" + description);
		}
		return this;
	}

	/** 读取服务端状态（供自定义断言使用）。 */
	public <T> T get(Supplier<T> supplier) {
		return supplier.get();
	}

	/** 该执行者的玩家对象（控制台返回 null），用于库存等玩家级断言。 */
	public ServerPlayer player() {
		return player;
	}

	/** 该执行者所属的服务端测试关卡。 */
	public ServerLevel level() {
		return level;
	}

	/** 该执行者收到的消息快照。 */
	public List<String> messages() {
		return messages;
	}
}
