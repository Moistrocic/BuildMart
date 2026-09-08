package mois.buildmart.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.tree.CommandNode;
import mois.buildmart.BuildMart;
import mois.buildmart.config.EconomyConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gamerules.GameRules;

import java.lang.reflect.Field;
import java.util.function.Predicate;

/**
 * 部分规则调整指令（受 /config rule.partialAdjust 控制，配置为 true 时**普通玩家**也可用；
 * 权限等级 2（LEVEL_GAMEMASTERS）及以上的管理员始终可用）：
 * <ul>
 * <li>原版 /weather、/time —— 原版指令本身（保持 26.3 原版语义/参数），注册后把根节点
 *     的权限要求放宽为上述动态判定（改动在内存中的需求谓词上，指令树其余部分原样保留）；
 *     部分规则调整关闭时与原本一致（等级 2 才可用），开启后玩家可直接使用；</li>
 * <li>{@code /fixweather} —— 固定天气：等价于把 gamerule {@code advance_weather} 设为
 *     false（天气不再自然变化，睡醒也不再重置天气），再次输入恢复；</li>
 * <li>{@code /fixtime} —— 固定时间：等价于把 gamerule {@code advance_time} 设为 false
 *     （世界时钟全部停止，昼夜不再流动），再次输入恢复；</li>
 * <li>{@code /naturalmonsterspawn [true|false]} —— 控制自然怪物生成（不含刷怪笼），
 *     等价于 gamerule {@code spawn_monsters}（/gamerule 需要管理员，本指令按上述
 *     动态权限判定放行普通玩家）；不填参数 = 查询当前状态。</li>
 * </ul>
 * 注意：规则改动走 {@code GameRules.set}（与服务端 {@code server.onGameRuleChanged}
 * 联动：spawn_monsters → 刷新刷怪标记，advance_time → 全量重发时钟同步），
 * 与 /gamerule 完全同源；规则值随世界存档持久化。
 */
public final class RuleCommands {
	private static final SimpleCommandExceptionType INVALID_BOOL =
			new SimpleCommandExceptionType(Component.literal("需要 true 或 false"));

	/** 等级 2 管理员判定（与 /weather /time 原版权限一致）。 */
	private static final Predicate<CommandSourceStack> GAMEMASTER =
			Commands.hasPermission(Commands.LEVEL_GAMEMASTERS);

	private RuleCommands() {
	}

	/**
	 * 部分规则调整权限：配置开启（/config rule.partialAdjust true）或等级 2 及以上管理员。
	 * 动态读取配置，因此 /config 热改即时生效（含收回权限）。
	 */
	private static boolean allowed(CommandSourceStack source) {
		return EconomyConfig.partialAdjust() || GAMEMASTER.test(source);
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		// 原版 /weather /time：放宽根节点权限（子树/执行逻辑原样保留，语义与参数不变）
		relaxVanillaPermission(dispatcher, "weather");
		relaxVanillaPermission(dispatcher, "time");

		dispatcher.register(Commands.literal("fixweather")
				.requires(RuleCommands::allowed)
				.executes(RuleCommands::fixWeather));

		dispatcher.register(Commands.literal("fixtime")
				.requires(RuleCommands::allowed)
				.executes(RuleCommands::fixTime));

		dispatcher.register(Commands.literal("naturalmonsterspawn")
				.requires(RuleCommands::allowed)
				.executes(RuleCommands::queryMonsterSpawn)
				.then(Commands.argument("value", StringArgumentType.word())
						.suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
								new String[]{"true", "false"}, builder))
						.executes(RuleCommands::setMonsterSpawn)));
	}

	// ---------- 原版指令权限放宽 ----------

	/**
	 * 把原版指令根节点的权限要求替换为动态判定（{@link #allowed}）。
	 * Brigadier 节点的 requirement 为 final 字段、无公开替换 API（重复注册同名词条只会
	 * 合并子节点、保留旧 requirement），故用反射写回该字段；失败仅记日志并保持原版权限
	 * （指令树不受影响，不会导致启动失败）。注册时机晚于原版指令注册（Fabric 回调在
	 * {@code Commands} 构造器尾部触发），因此此时根节点已存在。
	 */
	private static void relaxVanillaPermission(CommandDispatcher<CommandSourceStack> dispatcher, String name) {
		CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(name);
		if (node == null) {
			BuildMart.LOGGER.info("/{} 指令未找到（当前环境未注册），跳过权限放宽", name);
			return;
		}
		try {
			Field requirement = CommandNode.class.getDeclaredField("requirement");
			requirement.setAccessible(true);
			requirement.set(node, (Predicate<CommandSourceStack>) RuleCommands::allowed);
			BuildMart.LOGGER.info("/{} 指令权限已挂载部分规则调整开关（/config rule.partialAdjust）", name);
		} catch (ReflectiveOperationException e) {
			BuildMart.LOGGER.warn("/{} 指令权限放宽失败，保持原版权限（等级 2）", name, e);
		}
	}

	// ---------- /fixweather ----------

	/** /fixweather —— 固定/恢复天气（advance_weather 规则开关，随世界存档）。 */
	private static int fixWeather(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerLevel level = ctx.getSource().getLevel();
		boolean advancing = level.getGameRules().get(GameRules.ADVANCE_WEATHER);
		level.getGameRules().set(GameRules.ADVANCE_WEATHER, !advancing, ctx.getSource().getServer());
		if (advancing) {
			ctx.getSource().sendSuccess(() -> Component.literal(
					"天气已固定：当前天气保持不再自然变化（gamerule advance_weather=false），再次输入 /fixweather 恢复")
					.withStyle(ChatFormatting.GREEN), true);
		} else {
			ctx.getSource().sendSuccess(() -> Component.literal(
					"已恢复天气自然变化（gamerule advance_weather=true）")
					.withStyle(ChatFormatting.GREEN), true);
		}
		return 1;
	}

	// ---------- /fixtime ----------

	/** /fixtime —— 固定/恢复时间流动（advance_time 规则开关，随世界存档）。 */
	private static int fixTime(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerLevel level = ctx.getSource().getLevel();
		boolean advancing = level.getGameRules().get(GameRules.ADVANCE_TIME);
		level.getGameRules().set(GameRules.ADVANCE_TIME, !advancing, ctx.getSource().getServer());
		if (advancing) {
			ctx.getSource().sendSuccess(() -> Component.literal(
					"时间已固定：昼夜不再流动（gamerule advance_time=false），再次输入 /fixtime 恢复")
					.withStyle(ChatFormatting.GREEN), true);
		} else {
			ctx.getSource().sendSuccess(() -> Component.literal(
					"已恢复时间流动（gamerule advance_time=true）")
					.withStyle(ChatFormatting.GREEN), true);
		}
		return 1;
	}

	// ---------- /naturalmonsterspawn ----------

	/** /naturalmonsterspawn（无参数）—— 查询当前自然怪物生成状态。 */
	private static int queryMonsterSpawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ServerLevel level = ctx.getSource().getLevel();
		boolean enabled = level.getGameRules().get(GameRules.SPAWN_MONSTERS);
		ctx.getSource().sendSuccess(() -> Component.literal(
				"自然怪物生成当前：" + (enabled ? "开启" : "关闭") + "（不含刷怪笼，"
						+ (enabled ? "spawn_monsters=true" : "spawn_monsters=false")
						+ "；/naturalmonsterspawn true|false 可切换）")
				.withStyle(ChatFormatting.GREEN), false);
		return 1;
	}

	/** /naturalmonsterspawn true|false —— 控制自然怪物生成（不含刷怪笼，spawn_monsters 规则）。 */
	private static int setMonsterSpawn(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		String raw = StringArgumentType.getString(ctx, "value");
		if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false")) {
			throw INVALID_BOOL.create();
		}
		boolean enabled = Boolean.parseBoolean(raw);
		ServerLevel level = ctx.getSource().getLevel();
		level.getGameRules().set(GameRules.SPAWN_MONSTERS, enabled, ctx.getSource().getServer());
		if (enabled) {
			ctx.getSource().sendSuccess(() -> Component.literal(
					"已开启自然怪物生成（spawn_monsters=true；刷怪笼不受本设置影响）")
					.withStyle(ChatFormatting.GREEN), true);
		} else {
			ctx.getSource().sendSuccess(() -> Component.literal(
					"已关闭自然怪物生成（spawn_monsters=false；刷怪笼不受本设置影响）")
					.withStyle(ChatFormatting.GREEN), true);
		}
		return 1;
	}
}
