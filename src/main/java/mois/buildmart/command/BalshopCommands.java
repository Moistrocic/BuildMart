package mois.buildmart.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import mois.buildmart.BuildMart;
import mois.buildmart.Money;
import mois.buildmart.buymode.BuyModeManager;
import mois.buildmart.config.ItemValues;
import mois.buildmart.data.EconomyDb;
import mois.buildmart.shop.Shop;
import mois.buildmart.shop.ShopManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.commands.arguments.item.ItemInput;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * /balshop 系列指令：箱子商店的创建/移除/收款人设置，物品价格查询与购买。
 * 所有参数使用原版参数类型，保证纯净端兼容（规则书 3.1）。
 */
public final class BalshopCommands {
	private static final int MAX_BUY_COUNT = 17280;

	/** /buypack 单次盒数上限（防总价溢出；每盒 = 1 潜影盒 + 27 满堆物品）。 */
	private static final int MAX_BUY_PACK_BOXES = 1024;

	private static final SimpleCommandExceptionType NOT_CHEST =
			new SimpleCommandExceptionType(Component.literal("请对准一个箱子"));
	private static final SimpleCommandExceptionType NOT_SHOP =
			new SimpleCommandExceptionType(Component.literal("这里不是商店"));
	private static final SimpleCommandExceptionType ALREADY_SHOP =
			new SimpleCommandExceptionType(Component.literal("该箱子已经是商店"));
	private static final SimpleCommandExceptionType NOT_OWNER =
			new SimpleCommandExceptionType(Component.literal("只能操作自己的商店"));
	private static final SimpleCommandExceptionType PLAYER_ONLY =
			new SimpleCommandExceptionType(Component.literal("该指令只能由玩家执行"));
	private static final SimpleCommandExceptionType DB_ERROR =
			new SimpleCommandExceptionType(Component.literal("数据库错误，请稍后再试"));
	private static final SimpleCommandExceptionType PAYER_INSUFFICIENT =
			new SimpleCommandExceptionType(Component.literal("你的资金不足"));

	private BalshopCommands() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
		// 简化命令：/shop 为商店主命令，/price /buy /bm 为顶层简化入口
		registerShop(dispatcher, buildContext, "shop");
		dispatcher.register(Commands.literal("price")
				.then(Commands.argument("item", ItemArgument.item(buildContext))
						.executes(BalshopCommands::getPrice)));
		dispatcher.register(Commands.literal("buy")
				.then(Commands.argument("item", ItemArgument.item(buildContext))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_BUY_COUNT))
								.executes(BalshopCommands::buy))));
		dispatcher.register(Commands.literal("buypack")
				.then(Commands.argument("item", ItemArgument.item(buildContext))
						.then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_BUY_PACK_BOXES))
								.executes(BalshopCommands::buyPack))));
		dispatcher.register(Commands.literal("bm")
				.executes(BalshopCommands::buyMode));
	}

	private static void registerShop(CommandDispatcher<CommandSourceStack> dispatcher,
			CommandBuildContext buildContext, String name) {
		dispatcher.register(Commands.literal(name)
				.then(Commands.literal("create")
						.executes(BalshopCommands::create))
				.then(Commands.literal("remove")
						.executes(BalshopCommands::remove))
				.then(Commands.literal("setpayee")
						.then(Commands.argument("player", GameProfileArgument.gameProfile())
								.executes(BalshopCommands::setPayee)))
				.then(Commands.literal("getprice")
						.then(Commands.argument("item", ItemArgument.item(buildContext))
								.executes(BalshopCommands::getPrice)))
				.then(Commands.literal("buy")
						.then(Commands.argument("item", ItemArgument.item(buildContext))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_BUY_COUNT))
										.executes(BalshopCommands::buy))))
				.then(Commands.literal("buymode")
						.executes(BalshopCommands::buyMode)));
	}

	// ---------- 商店管理 ----------

	private static int create(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		ChestBlockEntity chest = targetedChest(source);
		ServerLevel level = player.level();
		if (ShopManager.get(level.dimension(), chest.getBlockPos()) != null) {
			throw ALREADY_SHOP.create();
		}
		ShopManager.create(level, chest.getBlockPos(), player);
		source.sendSuccess(() -> text("商店已创建", ChatFormatting.GREEN), false);
		return 1;
	}

	private static int remove(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		ChestBlockEntity chest = targetedChest(source);
		Shop shop = ShopManager.get(player.level().dimension(), chest.getBlockPos());
		if (shop == null) {
			throw NOT_SHOP.create();
		}
		if (!isAdmin(source) && !shop.owner().equals(player.getUUID())) {
			throw NOT_OWNER.create();
		}
		ShopManager.remove(shop, player.level());
		source.sendSuccess(() -> text("商店已移除", ChatFormatting.GREEN), false);
		return 1;
	}

	private static int setPayee(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		ChestBlockEntity chest = targetedChest(source);
		Shop shop = requireOwnedShop(source, player, chest);
		NameAndId profile = GameProfileArgument.getGameProfiles(ctx, "player").iterator().next();
		UUID uuid = profile.id() != null ? profile.id() : NameAndId.createOffline(profile.name()).id();
		// 收款人必须已注册资金账户（离线解析的玩家同样校验）
		try {
			if (!EconomyDb.hasAccount(uuid)) {
				throw new SimpleCommandExceptionType(
						Component.literal("该玩家尚未创建资金账户（需先上线或产生资金记录）")).create();
			}
		} catch (EconomyDb.DatabaseException e) {
			throw DB_ERROR.create();
		}
		ShopManager.setPayee(shop, uuid, profile.name());
		source.sendSuccess(() -> text("收款人已设置为 ", ChatFormatting.GREEN)
				.append(profile.name()), false);
		return 1;
	}


	// ---------- 价格与购买 ----------

	private static int getPrice(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		ItemInput input = ItemArgument.getItem(ctx, "item");
		long cents = ItemValues.get(input.item().value());
		String id = BuiltInRegistries.ITEM.getKey(input.item().value()).toString();
		if (cents == ItemValues.UNTRADEABLE) {
			ctx.getSource().sendSuccess(() -> text(id, ChatFormatting.RED)
					.append(" 不可购买或出售"), false);
			return 1;
		}
		ctx.getSource().sendSuccess(() -> text(id, ChatFormatting.GREEN)
				.append(" 的基础价格：").append(Money.format(cents))
				.append(" 元（实际结算按完整价值 = 基础价 + 附魔 + 容器内容物）"), false);
		return 1;
	}

	/**
	 * 执行一次购买：校验可交易 → 按完整价值扣款（只扣玩家资金，不入服务器资产）→
	 * BUY 流水（含物品完整组件数据，记录失败静默）→ 发放（拆分入包，溢出掉落脚下）。
	 * 返回 null = 成功，否则为失败提示（未扣款）。
	 */
	public static String purchase(ServerPlayer player, ItemStack stack) {
		if (!ItemValues.isTradable(stack.getItem())) {
			return "该物品不可购买或出售";
		}
		long total = ItemValues.price(stack);
		long balance;
		try {
			balance = EconomyDb.getBalance(player.getUUID());
		} catch (EconomyDb.DatabaseException e) {
			return "数据库错误，请稍后再试";
		}
		if (balance < total) {
			return "你的资金不足（需要 " + Money.format(total) + " 元，当前 "
					+ Money.format(balance) + " 元）";
		}
		try {
			if (!EconomyDb.deduct(player.getUUID(), total)) {
				return "你的资金不足";
			}
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("purchase 数据库错误", e);
			return "数据库错误，请稍后再试";
		}
		try {
			EconomyDb.recordTransaction(player.getUUID(), player.getGameProfile().name(),
					EconomyDb.TYPE_BUY, EconomyDb.CHANNEL_BUY,
					BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
					stack.getHoverName().getString(),
					mois.buildmart.ItemCodec.encode(stack, player.level().registryAccess()),
					stack.getCount(), total);
		} catch (EconomyDb.DatabaseException ignored) {
			// 记录失败静默。
		}
		giveOrDrop(player, stack);
		return null;
	}

	private static int buy(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		ItemInput input = ItemArgument.getItem(ctx, "item");
		int count = IntegerArgumentType.getInteger(ctx, "count");
		// 绕过原版 ItemInput.createItemStack 的 overstacked 校验（购买数量可超过单堆上限）：
		// 手动按同参数构造堆；发放时拆分放入背包，溢出部分掉落脚下。
		ItemStack stack = new ItemStack(input.item(), count, input.components());
		String error = purchase(player, stack);
		if (error != null) {
			throw new SimpleCommandExceptionType(Component.literal(error)).create();
		}
		String id = BuiltInRegistries.ITEM.getKey(input.item().value()).toString();
		long balanceAfter = readBalance(player.getUUID());
		source.sendSuccess(() -> text("已购买 ", ChatFormatting.GREEN)
				.append(String.valueOf(count)).append(" 个 ").append(id)
				.append("，花费 ").append(Money.format(ItemValues.price(stack))).append(" 元，当前资金：")
				.append(Money.format(balanceAfter)).append(" 元"), false);
		return 1;
	}

	/**
	 * /buypack 物品 盒数 —— 购买整盒物品：每盒 = 1 个潜影盒 + 27 格 × 堆叠上限 个该物品。
	 * 每盒价值 = 空潜影盒价值 + 27 × 满堆价值（ItemValues.price，含附魔/组件）。
	 */
	private static int buyPack(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		ItemInput input = ItemArgument.getItem(ctx, "item");
		int boxes = IntegerArgumentType.getInteger(ctx, "count");
		Item item = input.item().value();
		if (!ItemValues.isTradable(item)) {
			throw new SimpleCommandExceptionType(Component.literal("该物品不可购买或出售")).create();
		}
		int maxStack = new ItemStack(input.item()).getMaxStackSize();
		// 每盒价值 = 空潜影盒 + 27 个满堆
		long perBox = ItemValues.price(new ItemStack(Items.SHULKER_BOX))
				+ 27L * ItemValues.price(new ItemStack(input.item(), maxStack, input.components()));
		long total = perBox * boxes;

		long balance = readBalance(player.getUUID());
		if (balance < total) {
			throw PAYER_INSUFFICIENT.create();
		}
		try {
			if (!EconomyDb.deduct(player.getUUID(), total)) {
				throw PAYER_INSUFFICIENT.create();
			}
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("balshop buypack 数据库错误", e);
			throw DB_ERROR.create();
		}
		// 构建盒子：27 格 × 满堆（带组件）
		List<ItemStack> contents = new ArrayList<>(27);
		for (int i = 0; i < 27; i++) {
			contents.add(new ItemStack(input.item(), maxStack, input.components()));
		}
		ItemStack box = new ItemStack(Items.SHULKER_BOX);
		box.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(contents));
		// 资金流水：BUY 交易记录（含盒内容物完整组件数据）；记录失败静默
		try {
			EconomyDb.recordTransaction(player.getUUID(), player.getGameProfile().name(),
					EconomyDb.TYPE_BUY, EconomyDb.CHANNEL_BUY,
					BuiltInRegistries.ITEM.getKey(box.getItem()).toString(),
					box.getHoverName().getString(),
					mois.buildmart.ItemCodec.encode(box, player.level().registryAccess()),
					boxes, total);
		} catch (EconomyDb.DatabaseException ignored) {
			// 记录失败静默。
		}
		// 发放：逐盒放入（潜影盒堆叠上限 1），放不下的溢出掉落脚下
		giveOrDrop(player, box.copyWithCount(boxes));
		String id = BuiltInRegistries.ITEM.getKey(item).toString();
		long balanceAfter = readBalance(player.getUUID());
		source.sendSuccess(() -> text("已购买 ", ChatFormatting.GREEN)
				.append(String.valueOf(boxes)).append(" 盒 ").append(id)
				.append("（每盒 1 潜影盒 + 27×").append(String.valueOf(maxStack)).append(" 个），花费 ")
				.append(Money.format(total)).append(" 元，当前资金：")
				.append(Money.format(balanceAfter)).append(" 元"), false);
		return 1;
	}

	// ---------- 便捷购买 ----------

	/** /balshop buymode —— 切换便捷购买：临时授予 instabuild，客户端打开库存时自动进入创造物品栏。 */
	private static int buyMode(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
		CommandSourceStack source = ctx.getSource();
		ServerPlayer player = requirePlayer(source);
		if (BuyModeManager.isActive(player)) {
			BuyModeManager.exit(player);
			source.sendSuccess(() -> text("已退出便捷购买", ChatFormatting.GREEN), false);
		} else {
			BuyModeManager.enter(player);
			source.sendSuccess(() -> text("便捷购买已开启：按 E 打开背包即可进入购买界面，关闭界面自动退出", ChatFormatting.GREEN), false);
		}
		return 1;
	}

	// ---------- 工具 ----------

	/**
	 * 购买物品发放：背包可容纳的部分全部放入（自动拆分到多个堆叠槽），
	 * 放不下的溢出部分直接掉落到玩家脚下。26.3/26.2 的 Inventory.add 对
	 * 单堆可拆分放置（addResource 逐槽），但 add 失败时整块未放入——
	 * 故按单堆上限分块调用，失败即剩余全部掉落。
	 */
	private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
		int remaining = stack.getCount();
		Inventory inventory = player.getInventory();
		int max = stack.getMaxStackSize();
		while (remaining > 0) {
			int chunk = Math.min(remaining, max);
			ItemStack part = stack.copy();
			part.setCount(chunk);
			if (!inventory.add(part)) {
				// 背包已满：剩余部分掉落到玩家脚下
				ItemStack drop = stack.copy();
				drop.setCount(remaining);
				player.spawnAtLocation(player.level(), drop);
				return;
			}
			remaining -= chunk;
		}
	}

	private static ServerPlayer requirePlayer(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = source.getPlayer();
		if (player == null) {
			throw PLAYER_ONLY.create();
		}
		return player;
	}

	private static boolean isAdmin(CommandSourceStack source) {
		Predicate<CommandSourceStack> admin = Commands.hasPermission(Commands.LEVEL_ADMINS);
		return admin.test(source);
	}

	private static ChestBlockEntity targetedChest(CommandSourceStack source) throws CommandSyntaxException {
		ServerPlayer player = requirePlayer(source);
		HitResult hit = player.pick(5.0, 1.0F, false);
		if (!(hit instanceof BlockHitResult blockHit)) {
			throw NOT_CHEST.create();
		}
		BlockPos pos = blockHit.getBlockPos();
		if (!(player.level().getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
			throw NOT_CHEST.create();
		}
		return chest;
	}

	private static Shop requireOwnedShop(CommandSourceStack source, ServerPlayer player, ChestBlockEntity chest)
			throws CommandSyntaxException {
		Shop shop = ShopManager.get(player.level().dimension(), chest.getBlockPos());
		if (shop == null) {
			throw NOT_SHOP.create();
		}
		if (!isAdmin(source) && !shop.owner().equals(player.getUUID())) {
			throw NOT_OWNER.create();
		}
		return shop;
	}

	private static long readBalance(UUID uuid) throws CommandSyntaxException {
		try {
			return EconomyDb.getBalance(uuid);
		} catch (EconomyDb.DatabaseException e) {
			BuildMart.LOGGER.error("读取余额失败", e);
			throw DB_ERROR.create();
		}
	}

	private static MutableComponent text(String content, ChatFormatting color) {
		return Component.literal(content).withStyle(color);
	}
}
