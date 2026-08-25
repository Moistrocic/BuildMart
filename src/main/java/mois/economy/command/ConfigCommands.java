package mois.economy.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import mois.economy.config.EconomyConfig;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
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
 * 局内配置修改指令：/config 配置项 [参数]（管理员）。
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

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(Commands.literal("config")
				.requires(Commands.hasPermission(Commands.LEVEL_ADMINS))
				.then(Commands.argument("key", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(EconomyConfig.configKeys(), builder))
						.executes(ConfigCommands::show)
						.then(Commands.argument("value", StringArgumentType.word())
								.suggests(ConfigCommands::suggestValue)
								.executes(ConfigCommands::set))));
	}

	// ---------- /config key ----------

	private static int show(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String key = StringArgumentType.getString(ctx, "key");
		String value = EconomyConfig.getValue(key);
		if (value == null) {
			throw UNKNOWN_KEY.create();
		}
		ctx.getSource().sendSuccess(() -> text(key + " = " + value + "（"
				+ EconomyConfig.configType(key) + "）", ChatFormatting.GREEN), false);
		return 1;
	}

	// ---------- /config key value ----------

	private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String key = StringArgumentType.getString(ctx, "key");
		String value = StringArgumentType.getString(ctx, "value");
		if (EconomyConfig.getValue(key) == null) {
			throw UNKNOWN_KEY.create();
		}
		String error = EconomyConfig.apply(key, value);
		if (error != null) {
			throw new SimpleCommandExceptionType(Component.literal(error)).create();
		}
		try {
			EconomyConfig.save(FabricLoader.getInstance().getConfigDir());
		} catch (IOException e) {
			throw SAVE_FAILED.create();
		}
		String updated = EconomyConfig.getValue(key);
		ctx.getSource().sendSuccess(() -> text(key + " 已设置为 " + updated, ChatFormatting.GREEN), false);
		return 1;
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
