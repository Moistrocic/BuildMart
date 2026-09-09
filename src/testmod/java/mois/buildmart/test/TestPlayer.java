package mois.buildmart.test;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.Item;

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
 *     .execute("/bm config rule.partialAdjust true")
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
		if (player != null) {
			// 统一按生存玩家：创造/旁观自带飞行，会影响 /fly、/suicide 等判定
			forceSurvival(player);
		}
	}

	/** 把测试玩家置为生存模式并清掉创造相关能力（保持 connection 可用）。 */
	private static void forceSurvival(ServerPlayer player) {
		if (player instanceof TestServerPlayer testPlayer) {
			testPlayer.setTestGameMode(net.minecraft.world.level.GameType.SURVIVAL);
		}
		player.getAbilities().instabuild = false;
		player.getAbilities().invulnerable = false;
		player.getAbilities().flying = false;
		player.onUpdateAbilities();
	}

	/** 非管理员玩家（测试自建 ServerPlayer，生存模式、无任何权限）。 */
	public static TestPlayer player(GameTestHelper helper) {
		return new TestPlayer(helper, createPlayer(helper, false), false);
	}

	/** 管理员玩家（真实 op：PlayerList.op 生效，因此 player.createCommandSourceStack() 也带权限）。 */
	public static TestPlayer admin(GameTestHelper helper) {
		return new TestPlayer(helper, createPlayer(helper, true), true);
	}

	/** 控制台执行者（全权限、无玩家身份）。 */
	public static TestPlayer console(GameTestHelper helper) {
		return new TestPlayer(helper, null, true);
	}

	/**
	 * 创建并接入一个测试玩家：复刻原版 mock 玩家的装配（Connection + EmbeddedChannel +
	 * PlayerList.placeNewPlayer），因此 {@code connection.send} 可用（/bm、/fly 会触发能力同步），
	 * 但使用 {@link TestServerPlayer} 以便按用例切换游戏模式（默认生存）。
	 * <p>
	 * {@code elevated=true} 时走 {@code PlayerList.op}，让 {@code player.permissions()} 本身带权限——
	 * 主代码里以 {@code player.createCommandSourceStack()} 判定管理员的路径（如刷怪笼归属校验）
	 * 在测试中同样能越权，而不是只在命令源上临时加权限。
	 */
	private static ServerPlayer createPlayer(GameTestHelper helper, boolean elevated) {
		MinecraftServer server = helper.getLevel().getServer();
		// 名字必须全局唯一且不跨运行复用：PlayerList 的权限查询按名字匹配，
		// 与 run-gametest/world/ops.json 里上一次运行的残留条目同名会让普通玩家也拿到
		// 管理员权限（归属校验类用例随运行次数抖动），故用随机后缀。
		GameProfile profile = new GameProfile(UUID.randomUUID(),
				"bm_" + Long.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextLong(0x100000000L)));
		net.minecraft.server.network.CommonListenerCookie cookie =
				net.minecraft.server.network.CommonListenerCookie.createInitial(profile, false);
		TestServerPlayer player = new TestServerPlayer(server, helper.getLevel(), profile,
				cookie.clientInformation());
		net.minecraft.network.Connection connection =
				new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
		new io.netty.channel.embedded.EmbeddedChannel(connection);
		server.getPlayerList().placeNewPlayer(connection, player, cookie);
		net.minecraft.server.players.NameAndId nameAndId =
				new net.minecraft.server.players.NameAndId(profile.id(), profile.name());
		if (elevated) {
			// GameTestServer.operatorUserPermissions() 恒为 ALL（等级 0），默认 op 在测试里拿不到权限；
			// 必须显式指定权限集，玩家对象本身才带管理员权限（主代码里按 player 判定管理员的路径需要）。
			server.getPlayerList().op(nameAndId,
					java.util.Optional.of(net.minecraft.server.permissions.LevelBasedPermissionSet.OWNER),
					java.util.Optional.empty());
		} else {
			// 非管理员玩家必须确认不在 op 列表里（含上一次运行/同名条目残留），
			// 否则普通玩家也会带权限，归属校验类用例形同虚设。
			server.getPlayerList().deop(nameAndId);
			if (net.minecraft.commands.Commands.hasPermission(net.minecraft.commands.Commands.LEVEL_MODERATORS)
					.test(player.createCommandSourceStack())) {
				helper.fail("非管理员测试玩家不应拥有任何权限等级：" + profile.name());
			}
		}
		// 原版对“客户端尚未加载完成”的玩家免疫一切伤害（ServerPlayer.isInvulnerableTo 检查
		// connection.hasClientLoaded），假连接不会自然 tick 掉加载超时，这里手动推进，
		// 否则 /suicide 等伤害类指令在测试环境里永远无效。
		for (int i = 0; i < 200 && !player.connection.hasClientLoaded(); i++) {
			player.connection.tickClientLoadTimeout();
		}
		return player;
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
		if (messages.stream().noneMatch(message -> message.contains(text))) {
			helper.fail("执行 /" + lastCommand + " 后应收到包含「" + text + "」的消息，实际收到：" + messages);
		}
		return this;
	}

	/** 断言执行期间该执行者没有收到包含指定文本的消息。 */
	public TestPlayer expectNoMessage(String text) {
		if (messages.stream().anyMatch(message -> message.contains(text))) {
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

	/**
	 * 只做可见性判定（不执行指令，无副作用）：用于权限矩阵这类批量检查。
	 */
	public TestPlayer checkVisible(String command, boolean expected) {
		String normalized = command.startsWith("/") ? command.substring(1) : command;
		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
		boolean visible = TestApi.canParse(dispatcher, normalized, source());
		if (visible != expected) {
			helper.fail("指令 /" + normalized + " 对该执行者的可见性应为 " + expected + "，实际为 " + visible);
		}
		return this;
	}

	/**
	 * 执行并断言抛出包含指定文本的命令异常（用于控制台等"消息不可捕获"的执行者，
	 * 或需要确认失败原因的场景）。
	 */
	public TestPlayer executeExpectFailure(String command, String expectedText) {
		String normalized = command.startsWith("/") ? command.substring(1) : command;
		CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
		try {
			dispatcher.execute(dispatcher.parse(normalized, source()));
		} catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
			if (e.getMessage() == null || !e.getMessage().contains(expectedText)) {
				helper.fail("执行 /" + normalized + " 的失败信息应包含「" + expectedText + "」，实际：" + e.getMessage());
			}
			return this;
		}
		helper.fail("执行 /" + normalized + " 本应失败（期望失败信息包含「" + expectedText + "」），但成功了");
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

	// ---------- 玩家上下文辅助 ----------

	/**
	 * 让玩家站到指定**绝对**方块位置（结构内的安全位置，通常由
	 * {@code TestWorld.absolute(helper, 相对坐标)} 得到）。
	 */
	public TestPlayer standAt(BlockPos absoluteFeet) {
		player.setPos(absoluteFeet.getX() + 0.5D, absoluteFeet.getY(), absoluteFeet.getZ() + 0.5D);
		player.setDeltaMovement(Vec3.ZERO);
		return this;
	}

	/** 让玩家看向指定**绝对**位置的方块中心（用原版 lookAt 计算朝向）。 */
	public TestPlayer aimAt(BlockPos absoluteTarget) {
		player.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,
				Vec3.atCenterOf(absoluteTarget));
		return this;
	}

	/** 玩家正前方 distance 格处的绝对坐标（用于放置被瞄准的方块）。 */
	public BlockPos blockAhead(int distance) {
		return player.blockPosition().relative(player.getDirection(), distance);
	}

	/** 背包里是否有该物品（按物品比较，忽略价格 lore 等组件差异）。 */
	public boolean hasItem(Item item) {
		return hasItem(item, 1);
	}

	/** 背包里该物品的总数量是否不少于 amount（按物品比较，忽略组件差异）。 */
	public boolean hasItem(Item item, int amount) {
		if (player == null) {
			return false;
		}
		var inventory = player.getInventory();
		int total = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			var stack = inventory.getItem(i);
			if (stack.is(item)) {
				total += stack.getCount();
			}
		}
		return total >= amount;
	}

	/** 给该玩家入账（测试前置准备，直接走数据库，不依赖指令）。 */
	public TestPlayer withBalance(long cents) {
		EconomyDbAccess.credit(player, cents);
		return this;
	}

	/** 该执行者的玩家对象（控制台返回 null），用于库存等玩家级断言。 */
	public ServerPlayer player() {
		return player;
	}

	/** 该执行者的玩家名（控制台返回 null）。 */
	public String name() {
		return player != null ? player.getGameProfile().name() : null;
	}

	/** 该执行者所属的服务端测试关卡。 */
	public ServerLevel level() {
		return level;
	}

	/** 该执行者的服务端。 */
	public MinecraftServer server() {
		return server;
	}

	/** 该执行者收到的消息快照。 */
	public List<String> messages() {
		return messages;
	}
}
