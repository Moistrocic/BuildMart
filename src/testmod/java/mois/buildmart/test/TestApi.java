package mois.buildmart.test;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import com.mojang.brigadier.tree.ArgumentCommandNode;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.RootCommandNode;
import io.netty.buffer.Unpooled;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.synchronization.SuggestionProviders;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundCommandsPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.PermissionSet;

import java.util.HashMap;
import java.util.Map;

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

	/**
	 * 复刻服务端下发的**客户端指令树**：{@code Commands.sendCommands(player)} 用
	 * {@code fillUsableCommands} 按 {@code child.canUse(player.createCommandSourceStack())}
	 * 逐层过滤，只把玩家能用的节点写进 {@code ClientboundCommandsPacket}。
	 * <p>
	 * 纯净客户端（未装本模组）只能用这棵树做本地解析，解析失败就**根本不发包**——
	 * 表现为客户端本地报错、服务端无日志。因此「服务端能解析」不等于「纯净客户端能用」，
	 * 依赖准星/参数的指令必须用本方法验证。
	 */
	public static RootCommandNode<CommandSourceStack> clientTree(CommandDispatcher<CommandSourceStack> dispatcher,
			CommandSourceStack source) {
		RootCommandNode<CommandSourceStack> root = new RootCommandNode<>();
		Map<CommandNode<CommandSourceStack>, CommandNode<CommandSourceStack>> copied = new HashMap<>();
		for (CommandNode<CommandSourceStack> child : dispatcher.getRoot().getChildren()) {
			if (child.canUse(source)) {
				root.addChild(copyNode(child, source, copied));
			}
		}
		return root;
	}

	/**
	 * 纯净客户端视角下该指令能否解析（用 {@link #clientTree} 重建的树）。
	 * 客户端重建节点时会丢弃服务端 requirement，只保留 RESTRICTED 标志对应的本地放行判定，
	 * 故这里把复制出的节点 requirement 统一置为放行。
	 */
	public static boolean canParseAsVanillaClient(CommandDispatcher<CommandSourceStack> dispatcher,
			String command, CommandSourceStack source) {
		CommandDispatcher<CommandSourceStack> client =
				new CommandDispatcher<>(clientTree(dispatcher, source));
		return canParse(client, command, source);
	}

	private static CommandNode<CommandSourceStack> copyNode(CommandNode<CommandSourceStack> node,
			CommandSourceStack source, Map<CommandNode<CommandSourceStack>, CommandNode<CommandSourceStack>> copied) {
		CommandNode<CommandSourceStack> existing = copied.get(node);
		if (existing != null) {
			return existing;
		}
		ArgumentBuilder<CommandSourceStack, ?> builder = node.createBuilder();
		builder.requires(ignored -> true);
		CommandNode<CommandSourceStack> copy = builder.build();
		copied.put(node, copy);
		for (CommandNode<CommandSourceStack> child : node.getChildren()) {
			if (child.canUse(source)) {
				copy.addChild(copyNode(child, source, copied));
			}
		}
		return copy;
	}

	/**
	 * 完整复刻「服务端发包 → 客户端收包重建指令树」的链路（含参数类型 ID 与补全 provider ID 的
	 * 序列化往返），最接近纯净客户端（未装本模组）的真实解析能力。
	 * <p>
	 * 与 {@link #canParseAsVanillaClient} 的差别：后者只做权限过滤；本方法还会经过
	 * {@code ClientboundCommandsPacket} 编解码与客户端 {@code NodeBuilder} 重建
	 * （RESTRICTED 节点由客户端本地放行、补全 provider 回退 {@code minecraft:ask_server}）。
	 */
	public static boolean canParseAsVanillaClientOverNetwork(MinecraftServer server,
			CommandDispatcher<CommandSourceStack> dispatcher, String command, CommandSourceStack source) {
		RootCommandNode<CommandSourceStack> filtered = clientTree(dispatcher, source);
		CommandSourceStack noPermission = Commands.createCompilationContext(PermissionSet.NO_PERMISSIONS);
		ClientboundCommandsPacket.NodeInspector<CommandSourceStack> inspector =
				new ClientboundCommandsPacket.NodeInspector<>() {
					@Override
					public Identifier suggestionId(ArgumentCommandNode<CommandSourceStack, ?> node) {
						SuggestionProvider<?> provider = node.getCustomSuggestions();
						return provider == null ? null : SuggestionProviders.getName(provider);
					}

					@Override
					public boolean isExecutable(CommandNode<CommandSourceStack> node) {
						return node.getCommand() != null;
					}

					@Override
					public boolean isRestricted(CommandNode<CommandSourceStack> node) {
						return !node.getRequirement().test(noPermission);
					}
				};

		ClientboundCommandsPacket packet = new ClientboundCommandsPacket(filtered, inspector);
		FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
		ClientboundCommandsPacket.STREAM_CODEC.encode(buffer, packet);
		ClientboundCommandsPacket decoded = ClientboundCommandsPacket.STREAM_CODEC.decode(buffer);

		CommandBuildContext context = CommandBuildContext.simple(
				server.registryAccess(), server.getWorldData().enabledFeatures());
		RootCommandNode<CommandSourceStack> root = decoded.getRoot(context,
				new ClientboundCommandsPacket.NodeBuilder<CommandSourceStack>() {
					@Override
					public ArgumentBuilder<CommandSourceStack, ?> createLiteral(String name) {
						return LiteralArgumentBuilder.literal(name);
					}

					@Override
					public ArgumentBuilder<CommandSourceStack, ?> createArgument(String name, ArgumentType<?> type,
							Identifier suggestionId) {
						RequiredArgumentBuilder<CommandSourceStack, ?> builder =
								RequiredArgumentBuilder.argument(name, type);
						if (suggestionId != null) {
							builder.suggests(SuggestionProviders.getProvider(suggestionId));
						}
						return builder;
					}

					@Override
					public ArgumentBuilder<CommandSourceStack, ?> configure(ArgumentBuilder<CommandSourceStack, ?> builder,
							boolean executable, boolean restricted) {
						if (executable) {
							builder.executes(ctx -> 0);
						}
						if (restricted) {
							// 客户端对 RESTRICTED 节点挂 client/commands/restricted 检查，
							// 其权限集恒包含该权限（ALLOW_RESTRICTED_COMMANDS），等价放行
							builder.requires(ignored -> true);
						}
						return builder;
					}
				});
		return canParse(new CommandDispatcher<>(root), command, source);
	}
}
