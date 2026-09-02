package mois.buildmart.command;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.selector.EntitySelector;
import net.minecraft.commands.arguments.selector.EntitySelectorParser;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /eco 系列指令的资金目标解析。
 * <p>
 * 出于规则书 3.1 的纯净端兼容要求，目标使用原版 StringArgumentType.word() 承载、
 * 在此处手动解析：禁止注册自定义 ArgumentType（会进入同步注册表导致纯净端被踢）。
 * 支持玩家名（含离线玩家）与不带选项的原版选择器（@a/@p/@r/@s）；
 * 选择器解析复用原版 {@link EntitySelectorParser}。服务器资产操作使用独立的 /peco 指令。
 */
public final class EconomyTargets {
	private static final SimpleCommandExceptionType INVALID_SELECTOR =
			new SimpleCommandExceptionType(Component.literal("无效的目标"));

	private EconomyTargets() {
	}

	/** 解析目标字符串（word 参数）为目标列表，按 UUID 去重。 */
	public static List<ResolvedTarget> resolve(String input, CommandSourceStack source) throws CommandSyntaxException {
		Map<UUID, ResolvedTarget> result = new LinkedHashMap<>();
		if (input.startsWith("@")) {
			StringReader reader = new StringReader(input);
			EntitySelector selector = new EntitySelectorParser(reader, true).parse();
			if (reader.canRead()) {
				throw INVALID_SELECTOR.create();
			}
			for (ServerPlayer player : selector.findPlayers(source)) {
				result.putIfAbsent(player.getUUID(),
						new ResolvedTarget(player.getUUID(), player.getGameProfile().name(), player));
			}
		} else {
			NameAndId nameAndId = source.getServer().services().nameToIdCache().get(input)
					.orElseThrow(() -> GameProfileArgument.ERROR_UNKNOWN_PLAYER.create());
			UUID uuid = nameAndId.id() != null ? nameAndId.id() : NameAndId.createOffline(nameAndId.name()).id();
			ServerPlayer online = source.getServer().getPlayerList().getPlayer(uuid);
			result.put(uuid, new ResolvedTarget(uuid, nameAndId.name(), online));
		}
		return new ArrayList<>(result.values());
	}

	/** 补全建议：在线玩家名。 */
	public static List<String> suggestions(CommandSourceStack source) {
		return new ArrayList<>(source.getOnlinePlayerNames());
	}

	/** 解析后的单个资金目标。onlinePlayer 非空表示目标当前在线，可发送即时通知。 */
	public record ResolvedTarget(UUID uuid, String displayName, ServerPlayer onlinePlayer) {
	}
}
