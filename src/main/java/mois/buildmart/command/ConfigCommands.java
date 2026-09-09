package mois.buildmart.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import mois.buildmart.config.EconomyConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 局内配置修改指令：`/bm config 配置项 [参数]`（管理员；`/bm` 单独执行仍是便捷购买开关）。
 * <p>
 * 为什么不做顶层 /config：指令能否被客户端执行取决于**客户端本地解析**（纯净客户端只能用
 * 服务端下发的、按权限过滤的指令树）。若客户端装了其他 mod 注册的**客户端侧** /config 指令，
 * Fabric 会先用客户端 dispatcher 执行该输入，未命中且抛出的 `dispatcherUnknownArgument`
 * 不在其忽略列表，于是**取消发送**并本地报错——指令根本到不了服务端（表现为「客户端报错、
 * 服务端无日志」）。挂在 `/bm` 下撞名概率极低，且该子节点限管理员，非管理员客户端看不到。
 * <p>
 * 配置项按 Tab 自动补全（与 /eco 目标补全同机制）；修改直接热重载内存配置
 * （所有消费方按次读取 getter，即时生效），并写回 config.json 持久化，无需重启。
 * 布尔型参数同样提供 true/false 补全。
 */
public final class ConfigCommands {
	private static final SimpleCommandExceptionType UNKNOWN_KEY =
			new SimpleCommandExceptionType(Component.literal("未知配置项，按 Tab 查看可修改项"));
	private static final SimpleCommandExceptionType SAVE_FAILED =
			new SimpleCommandExceptionType(Component.literal("配置写入失败，请查看服务端日志"));

	private ConfigCommands() {
	}

	/**
	 * `/bm config ...` 子节点（挂在 `/bm` 下；`/bm` 单独执行仍是便捷购买开关）。
	 * <p>
	 * 不做顶层 /config 的原因：客户端必须**本地解析成功**才会发包，而 Fabric 会先用**客户端侧**
	 * 指令 dispatcher 执行该输入；若客户端存在同名客户端指令（其他 mod 注册），未命中且抛出的
	 * `dispatcherUnknownArgument` 不在其忽略列表，于是取消发送并本地报错——指令根本到不了服务端
	 * （表现为「客户端报错、服务端无日志」）。`/bm` 是本模组的缩写根，撞名概率极低；且该子节点限
	 * 管理员，非管理员客户端看不到。
	 */
	public static LiteralArgumentBuilder<CommandSourceStack> node() {
		return Commands.literal("config")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.argument("key", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(EconomyConfig.configKeys(), builder))
						.executes(ConfigCommands::show)
						.then(Commands.argument("value", StringArgumentType.string())
								.suggests(ConfigCommands::suggestValue)
								.executes(ConfigCommands::set)));
	}

	// ---------- /bm config key ----------

	private static int show(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String key = StringArgumentType.getString(ctx, "key");
		String value = EconomyConfig.getValue(key);
		if (value == null) {
			throw UNKNOWN_KEY.create();
		}
		ctx.getSource().sendSuccess(() -> text(key + " = " + displayValue(value) + "（"
				+ EconomyConfig.configType(key) + "）", ChatFormatting.GREEN), false);
		return 1;
	}

	// ---------- /bm config key value ----------

	private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String key = StringArgumentType.getString(ctx, "key");
		String value = StringArgumentType.getString(ctx, "value");
		if (EconomyConfig.getValue(key) == null) {
			throw UNKNOWN_KEY.create();
		}
		// value 参数用 string（原生支持引号解析），三种写法等价：
		//   /bm config balop.domain a.b.c     → a.b.c
		//   /bm config balop.domain "a.b.c"   → a.b.c（引号被解析器剥除）
		//   /bm config balop.domain ""        → 空字符串（清空字符串配置项）
		// 注意不能用 word：其字符集不含引号，带引号输入会在解析期报
		// 「参数后应有空格分隔，但发现了紧邻的数据」，永远到不了执行层。
		String error = EconomyConfig.apply(key, value);
		if (error != null) {
			throw new SimpleCommandExceptionType(Component.literal(error)).create();
		}
		try {
			EconomyConfig.save(FabricLoader.getInstance().getConfigDir());
		} catch (IOException e) {
			throw SAVE_FAILED.create();
		}
		// rule.partialAdjust 决定规则调整指令（/weather /time /fixweather /fixtime /
		// naturalmonsterspawn）在客户端指令树里的受限标志（RESTRICTED，随进服下发）——
		// 改动后立即向全部在线玩家重发指令树，非管理员无需重进即可看到/收起这些指令
		// （服务端解析按次读配置本就即时生效；此处只刷新客户端的显示与本地校验）。
		if (key.equals("rule.partialAdjust") && ctx.getSource().getServer() != null) {
			for (net.minecraft.server.level.ServerPlayer player :
					ctx.getSource().getServer().getPlayerList().getPlayers()) {
				ctx.getSource().getServer().getCommands().sendCommands(player);
			}
		}
		String updated = EconomyConfig.getValue(key);
		ctx.getSource().sendSuccess(() -> text(key + " 已设置为 " + displayValue(updated), ChatFormatting.GREEN), false);
		return 1;
	}

	/** 空字符串显示为「（空）」，便于确认字符串配置项已清空。 */
	private static String displayValue(String value) {
		return value.isEmpty() ? "（空）" : value;
	}

	// ---------- 补全 ----------

	/** 参数值补全：布尔项给出 true/false。 */
	private static CompletableFuture<Suggestions> suggestValue(CommandContext<CommandSourceStack> ctx,
			SuggestionsBuilder builder) {
		String key = StringArgumentType.getString(ctx, "key");
		String type = EconomyConfig.configType(key);
		if ("bool".equals(type)) {
			List<String> options = new ArrayList<>(2);
			options.add("true");
			options.add("false");
			return SharedSuggestionProvider.suggest(options, builder);
		}
		// 数值项：提示当前值作为参考
		String current = EconomyConfig.getValue(key);
		if (current != null) {
			return SharedSuggestionProvider.suggest(List.of(current), builder);
		}
		return builder.buildFuture();
	}

	private static MutableComponent text(String content, ChatFormatting color) {
		return Component.literal(content).withStyle(color);
	}
}
