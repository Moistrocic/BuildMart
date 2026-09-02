package mois.buildmart.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import mois.buildmart.config.EconomyConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /fastbuy —— 快速投影购买模式开关（服务端权威，默认关闭）。
 * 开启后：装了本模组客户端的玩家在投影（litematica）中键拾取失败
 * （背包无对应物品）时，客户端发送请求，服务端自动购买一组该物品。
 * 关闭时不处理任何请求、不修改任何逻辑。
 */
public final class FastbuyCommands {
	private static final SimpleCommandExceptionType PLAYER_ONLY =
			new SimpleCommandExceptionType(Component.literal("该指令只能由玩家执行"));

	private FastbuyCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		dispatcher.register(Commands.literal("fastbuy")
				.executes(FastbuyCommands::toggle));
	}

	private static int toggle(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			throw PLAYER_ONLY.create();
		}
		boolean now = !EconomyConfig.fastbuy();
		EconomyConfig.setFastbuy(now);
		source.sendSuccess(() -> Component.literal(now
				? "快速投影购买已开启：投影中键拾取无对应物品时将自动购买一组（需客户端安装本模组 + litematica）"
				: "快速投影购买已关闭（不再处理自动购买请求）")
				.withStyle(now ? ChatFormatting.GREEN : ChatFormatting.GRAY), false);
		return 1;
	}
}
