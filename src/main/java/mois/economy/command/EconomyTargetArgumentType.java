package mois.economy.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import mois.economy.data.EconomyDb;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * /eco 系列指令的资金目标参数：支持玩家名（含离线玩家）、@a/@p/@r/@s 等原版选择器，
 * 以及特殊目标 "@server"（服务器资产账户）。
 * 解析与解析规则复用原版 {@link EntitySelectorParser} 与服务的 nameToIdCache，遵循原版行为。
 */
public final class EconomyTargetArgumentType implements ArgumentType<Object> {
	public static final String SERVER_TARGET = "@server";

	private EconomyTargetArgumentType() {
	}

	public static EconomyTargetArgumentType target() {
		return new EconomyTargetArgumentType();
	}

	@Override
	public Object parse(StringReader reader) throws CommandSyntaxException {
		if (reader.canRead() && reader.peek() == '@') {
			String remaining = reader.getRemaining();
			if (remaining.equals(SERVER_TARGET) || remaining.startsWith(SERVER_TARGET + ' ')) {
				reader.setCursor(reader.getCursor() + SERVER_TARGET.length());
				return ServerTarget.INSTANCE;
			}
			return new SelectorTarget(new EntitySelectorParser(reader, true).parse());
		}
		return new NameTarget(reader.readUnquotedString());
	}

	@Override
	public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
		if (!(context.getSource() instanceof SharedSuggestionProvider provider)) {
			return Suggestions.empty();
		}
		List<String> options = new ArrayList<>(provider.getOnlinePlayerNames());
		options.add(SERVER_TARGET);
		return SharedSuggestionProvider.suggest(options, builder);
	}

	/** 解析命令上下文中名为 "target" 的参数为目标列表（按 UUID 去重）。 */
	public static List<ResolvedTarget> resolve(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		Object parsed = ctx.getArgument("target", Object.class);
		CommandSourceStack source = ctx.getSource();
		Map<UUID, ResolvedTarget> result = new LinkedHashMap<>();
		if (parsed instanceof ServerTarget) {
			result.put(EconomyDb.SERVER_ACCOUNT_UUID,
					new ResolvedTarget(EconomyDb.SERVER_ACCOUNT_UUID, EconomyDb.SERVER_ACCOUNT_NAME, null));
		} else if (parsed instanceof SelectorTarget selector) {
			for (ServerPlayer player : selector.selector().findPlayers(source)) {
				result.putIfAbsent(player.getUUID(),
						new ResolvedTarget(player.getUUID(), player.getGameProfile().name(), player));
			}
		} else if (parsed instanceof NameTarget nameTarget) {
			NameAndId nameAndId = source.getServer().services().nameToIdCache().get(nameTarget.name())
					.orElseThrow(() -> GameProfileArgument.ERROR_UNKNOWN_PLAYER.create());
			UUID uuid = nameAndId.id() != null ? nameAndId.id() : NameAndId.createOffline(nameAndId.name()).id();
			ServerPlayer online = source.getServer().getPlayerList().getPlayer(uuid);
			result.put(uuid, new ResolvedTarget(uuid, nameAndId.name(), online));
		}
		return new ArrayList<>(result.values());
	}

	/** 解析后的单个资金目标。onlinePlayer 非空表示目标当前在线，可发送即时通知。 */
	public record ResolvedTarget(UUID uuid, String displayName, ServerPlayer onlinePlayer) {
	}

	private record SelectorTarget(EntitySelector selector) {
	}

	private record NameTarget(String name) {
	}

	private enum ServerTarget {
		INSTANCE
	}
}
