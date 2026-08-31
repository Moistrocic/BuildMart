package mois.economy.buymode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mois.economy.Economy;
import mois.economy.ItemCodec;
import mois.economy.Money;
import mois.economy.PriceLore;
import mois.economy.config.ItemValues;
import mois.economy.data.EconomyDb;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;

/**
 * 便捷购买的公共结算工具（自 ServerGamePacketListenerImplMixin 提取）：
 * 创造物品栏索引与严格比对、面板购买链（含 -1 挂起超时结算）、买卖提示与资金操作。
 * 独立于 mixin——mixin 类不允许非 private 方法（会被合并进 target 导致校验失败），
 * 槽位包处理（mixin）与服务端 tick（BuyModeManager）都经由本类。
 */
public final class BuyModeSettlement {
	/** 原版创造物品栏内容索引：物品 → 该物品在创造面板中的全部展示堆（构建一次后缓存）。 */
	private static final Map<Item, List<ItemStack>> CREATIVE_ITEMS = new HashMap<>();
	private static boolean creativeItemsBuilt = false;

	private BuyModeSettlement() {
	}

	// ---------- 创造物品栏索引与严格比对 ----------

	/**
	 * 购买的物品是否与“原版创造物品栏”中的某个展示堆完全一致（比较相对物品默认组件的
	 * 补丁，忽略数量）。比对索引 = 创造标签页内容 ∪ 全注册物品的纯净默认形态
	 * （new ItemStack(item)）：后者作为兜底，保证即使服务端标签页内容未构建
	 * （26.3 独立服务端可能不构建），任何未经修改的纯净物品也能通过比对；内容类物品
	 * （药水/旗帜等）的合法形态来自标签页内容。
	 */
	public static boolean isVanillaCreativeItem(ItemStack stack, MinecraftServer server) {
		if (stack.isEmpty()) {
			return true;
		}
		buildCreativeItemsIfNeeded(server);
		List<ItemStack> candidates = CREATIVE_ITEMS.get(stack.getItem());
		if (candidates == null) {
			return false;
		}
		for (ItemStack creative : candidates) {
			// 比“相对物品默认组件的补丁”而非 PatchedDataComponentMap 整体：26.3 从
			// 网络包重建的堆（Item.STREAM_CODEC → new ItemStack(holder, count, patch)）
			// 与本地构造的堆在内部补丁/原型表示上可能不同，但语义内容（相对默认的
			// 增删）一致；纯净堆补丁为空、真改造物品补丁必然非空，语义等价且更稳健。
			if (stack.getComponentsPatch().equals(creative.getComponentsPatch())) {
				return true;
			}
		}
		return false;
	}

	/** 首次使用时构建创造物品栏内容（与客户端展示用同一套注册表/特性构建，内容一致）。 */
	private static synchronized void buildCreativeItemsIfNeeded(MinecraftServer server) {
		if (creativeItemsBuilt) {
			return;
		}
		creativeItemsBuilt = true;
		CreativeModeTabs.tryRebuildTabContents(server.getWorldData().enabledFeatures(), false, server.registryAccess());
		for (CreativeModeTab tab : BuiltInRegistries.CREATIVE_MODE_TAB) {
			for (ItemStack stack : tab.getDisplayItems()) {
				CREATIVE_ITEMS.computeIfAbsent(stack.getItem(), k -> new ArrayList<>()).add(stack);
			}
			for (ItemStack stack : tab.getSearchTabDisplayItems()) {
				CREATIVE_ITEMS.computeIfAbsent(stack.getItem(), k -> new ArrayList<>()).add(stack);
			}
		}
		// 兜底：所有注册物品的纯净默认形态（new ItemStack(item)），保证未构建标签页
		// 内容时纯净物品也能通过比对。
		for (Item item : BuiltInRegistries.ITEM) {
			CREATIVE_ITEMS.computeIfAbsent(item, k -> new ArrayList<>()).add(new ItemStack(item));
		}
	}

	// ---------- 面板购买链 ----------

	/**
	 * 结算挂起超过宽限期的 -1 包为面板购买（由服务端 tick 调用）：
	 * 面板 ctrl+q 只发 -1 包、无槽位包跟随，若一直等下一个包会滞后一拍；
	 * 挂起 ≥2 tick 仍无槽位包认领即按面板购买立即结算。
	 */
	public static void settlePendingDrop(ServerPlayer player, BuyModeSession session) {
		if (session == null || !session.pendingDropExpired(player.level().getServer().getTickCount())) {
			return;
		}
		ItemStack drop = session.pendingDrop();
		session.clearPendingDrop();
		if (drop != null && !drop.isEmpty()) {
			buyDrop(player, drop);
		}
	}

	/**
	 * 面板 ctrl+q：购买（严格校验 + 余额 + 扣款），成功时生成丢出实体。
	 * 严格校验失败或余额不足：不生成实体、不扣款（物品在客户端已销毁，无损失）。
	 */
	public static boolean buyDrop(ServerPlayer player, ItemStack drop) {
		long cost = ItemValues.price(drop);
		if (!approveBuy(player, drop, cost)) {
			return false;
		}
		player.drop(drop.copy(), false, true);
		sendBuy(player, ItemStack.EMPTY, drop, cost);
		return true;
	}

	/**
	 * 购买校验 + 扣款：严格比对原版创造物品栏（剥除价格行后），再查余额。
	 * 任一失败返回 false（提示已发），由调用方回滚。
	 */
	public static boolean approveBuy(ServerPlayer player, ItemStack stack, long cost) {
		ItemStack probe = stack.copy();
		PriceLore.untag(probe); // 价格行是本模组自身数据，比对应绕过
		if (!isVanillaCreativeItem(probe, player.level().getServer())) {
			List<ItemStack> candidates = CREATIVE_ITEMS.getOrDefault(probe.getItem(), List.of());
			Economy.LOGGER.warn("buymode 拒绝购买：{} 尝试 {} ×{}（cost={}），剥除价格行后候选数={}",
					player.getGameProfile().name(), probe, probe.getCount(), cost, candidates.size());
			sendModified(player);
			return false;
		}
		if (balance(player) < cost) {
			sendInsufficient(player, ItemStack.EMPTY, stack, cost);
			return false;
		}
		deductQuietly(player, cost);
		return true;
	}

	// ---------- 槽位出现挂起（数字键/槽间交换） ----------

	/**
	 * 结算挂起超过宽限期的槽位出现（由服务端 tick 调用）：无配对包认领 = 非交换，
	 * 按「面板购买 or 拒绝」结算，与 {@link #settlePendingDrop} 同一套超时机制。
	 */
	public static void settlePendingSlot(ServerPlayer player, BuyModeSession session) {
		if (session == null || !session.pendingSlotExpired(player.level().getServer().getTickCount())) {
			return;
		}
		BuyModeSession.PendingSlot pending = session.pendingSlot();
		if (pending != null) {
			settlePendingSlot(player, session, player.inventoryMenu, pending);
		}
	}

	/**
	 * 结算挂起的槽位出现（配对失败/超时）：
	 * <ul>
	 * <li>挂起内容与原版创造物品栏一致 → 按未吸收增量购买（原内容被覆盖，其消失记录
	 * 保留，关闭界面时按卖出退款）；余额不足 → 槽位保持原状（物品未丢失），
	 * 撤销消失记录并补发权威内容；</li>
	 * <li>非面板物品（改造物品/槽间交换未配对）→ 拒绝：槽位保持原状，撤销消失记录
	 * （物品未丢失，杜绝「既在背包又被卖出退款」的白嫖），补发权威内容并提示。</li>
	 * </ul>
	 */
	public static void settlePendingSlot(ServerPlayer player, BuyModeSession session,
			InventoryMenu menu, BuyModeSession.PendingSlot pending) {
		session.clearPendingSlot();
		ItemStack next = pending.next();
		if (next.isEmpty() || pending.unbought() <= 0) {
			// 防御：无内容则直接作废（物品未丢失）
			session.removeVanished(pending.vanished());
			return;
		}
		ItemStack probe = next.copy();
		PriceLore.untag(probe); // 价格行是本模组自身数据，比对应绕过
		if (isVanillaCreativeItem(probe, player.level().getServer())) {
			long cost = ItemValues.price(next)
					- ItemValues.price(next.copyWithCount(next.getCount() - pending.unbought()));
			if (!approveBuy(player, next, cost)) {
				// 余额不足：槽位保持原状，撤销消失记录，补发权威内容
				session.removeVanished(pending.vanished());
				setSlotAndSync(player, menu, pending.slotNum(),
						menu.getSlot(pending.slotNum()), pending.prev());
				return;
			}
			setSlotAndSync(player, menu, pending.slotNum(),
					menu.getSlot(pending.slotNum()), next);
			sendBuy(player, pending.prev(), next, cost);
			return;
		}
		// 非面板物品：拒绝（槽位从未被修改，物品未丢失）
		session.removeVanished(pending.vanished());
		setSlotAndSync(player, menu, pending.slotNum(),
				menu.getSlot(pending.slotNum()), pending.prev());
		sendModified(player);
	}

	// ---------- 槽位设置与同步 ----------

	/**
	 * 设置槽位（服务端权威 + 打标 + 强制下发），购买/放回/拿起共用。
	 * 26.3 同步协议：原版 handleSetCreativeModeSlot 用 setRemoteSlot 把槽位标记为
	 * “客户端已知”后不会下发同步包，客户端本地持有的堆没有价格标签；此处标记远端
	 * 状态后强制下发一次打标后的权威堆（同步机制与服务端 ContainerSynchronizer
	 * 一致），保证拿取/放回/回滚的瞬间客户端即可看到权威内容（含标签）。
	 */
	public static void setSlotAndSync(ServerPlayer player, InventoryMenu menu,
			int slotNum, Slot slot, ItemStack stack) {
		slot.setByPlayer(stack);
		// 显式打标：覆盖合成格等不经过 Inventory.setItem 的容器槽位（幂等）
		PriceLore.tag(stack);
		menu.setRemoteSlot(slotNum, stack);
		player.connection.send(new ClientboundContainerSetSlotPacket(
				menu.containerId, menu.incrementStateId(), slotNum, stack.copy()));
	}

	// ---------- 提示 ----------

	/** 从 before 变为 after 时“新增”部分的展示信息（物品名与数量）。 */
	private static String gainedName(ItemStack before, ItemStack after) {
		if (!after.isEmpty() && ItemStack.isSameItemSameComponents(before, after)) {
			return after.getHoverName().getString() + " ×" + Math.max(1, after.getCount() - before.getCount());
		}
		return after.getHoverName().getString() + " ×" + Math.max(1, after.getCount());
	}

	/** 从 before 变为 after 时“移走”部分的展示信息（物品名与数量）。 */
	private static String lostName(ItemStack before, ItemStack after) {
		if (!before.isEmpty() && ItemStack.isSameItemSameComponents(before, after)) {
			return before.getHoverName().getString() + " ×" + Math.max(1, before.getCount() - after.getCount());
		}
		return before.getHoverName().getString() + " ×" + Math.max(1, before.getCount());
	}

	/** 从 before 变为 after 时“新增”的数量（交易流水用，与扣款增量一致）。 */
	private static int gainedCount(ItemStack before, ItemStack after) {
		if (!after.isEmpty() && ItemStack.isSameItemSameComponents(before, after)) {
			return Math.max(1, after.getCount() - before.getCount());
		}
		return Math.max(1, after.getCount());
	}

	/** 从 before 变为 after 时“移走”的数量（交易流水用，与退款减量一致）。 */
	private static int lostCount(ItemStack before, ItemStack after) {
		if (!before.isEmpty() && ItemStack.isSameItemSameComponents(before, after)) {
			return Math.max(1, before.getCount() - after.getCount());
		}
		return Math.max(1, before.getCount());
	}

	/**
	 * 记录一笔 bm 买卖流水（channel=BM，含物品完整组件数据 item_data）；
	 * 失败静默（记录失败不应影响资金结算，与 {@link #creditQuietly} 同一原则）。
	 */
	public static void recordTrade(ServerPlayer player, String type, ItemStack stack, int count, long price) {
		if (stack == null || stack.isEmpty() || count <= 0) {
			return;
		}
		try {
			EconomyDb.recordTransaction(player.getUUID(), player.getGameProfile().name(), type,
					EconomyDb.CHANNEL_BM,
					BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
					stack.getHoverName().getString(),
					ItemCodec.encode(stack, player.level().registryAccess()),
					count, price);
		} catch (EconomyDb.DatabaseException ignored) {
			// 记录失败静默。
		}
	}

	public static void sendBuy(ServerPlayer player, ItemStack before, ItemStack after, long cost) {
		player.sendSystemMessage(Component.literal(
				"已购买 " + gainedName(before, after)
						+ "，花费 " + Money.format(cost) + " 元" + balanceSuffix(player))
				.withStyle(ChatFormatting.GOLD), false);
		recordTrade(player, EconomyDb.TYPE_BUY, after, gainedCount(before, after), cost);
	}

	public static void sendRefund(ServerPlayer player, ItemStack before, ItemStack after, long refund) {
		player.sendSystemMessage(Component.literal(
				"已放回 " + lostName(before, after)
						+ "，获得 " + Money.format(refund) + " 元" + balanceSuffix(player))
				.withStyle(ChatFormatting.GREEN), false);
		recordTrade(player, EconomyDb.TYPE_SELL, before, lostCount(before, after), refund);
	}

	/** 卖出提示：物品从背包消失（丢弃/关闭界面统一结算），按价值退款。 */
	public static void sendSell(ServerPlayer player, ItemStack stack, long refund) {
		player.sendSystemMessage(Component.literal(
				"已卖出 " + stack.getHoverName().getString() + " ×" + stack.getCount()
						+ "，获得 " + Money.format(refund) + " 元" + balanceSuffix(player))
				.withStyle(ChatFormatting.GREEN), false);
		recordTrade(player, EconomyDb.TYPE_SELL, stack, stack.getCount(), refund);
	}

	public static void sendBuyNet(ServerPlayer player, long cost) {
		player.sendSystemMessage(Component.literal(
				"已购买物品，花费 " + Money.format(cost) + " 元" + balanceSuffix(player))
				.withStyle(ChatFormatting.GOLD), false);
	}

	public static void sendRefundNet(ServerPlayer player, long refund) {
		player.sendSystemMessage(Component.literal(
				"已放回物品，获得 " + Money.format(refund) + " 元" + balanceSuffix(player))
				.withStyle(ChatFormatting.GREEN), false);
	}

	public static void sendInsufficient(ServerPlayer player, ItemStack before, ItemStack after, long total) {
		player.sendSystemMessage(Component.literal(
				"你的资金不足：购买 " + gainedName(before, after)
						+ " 需要 " + Money.format(total) + " 元，当前资金 " + Money.format(balanceOrMax(player)) + " 元")
				.withStyle(ChatFormatting.RED), false);
	}

	public static void sendUntradeable(ServerPlayer player) {
		player.sendSystemMessage(Component.literal(
				"该物品不可购买或出售").withStyle(ChatFormatting.RED), false);
	}

	public static void sendModified(ServerPlayer player) {
		player.sendSystemMessage(Component.literal(
				"该物品与原版创造物品栏不一致，无法购买（仅允许未经修改的物品）").withStyle(ChatFormatting.RED), false);
	}

	public static void sendInsufficientNet(ServerPlayer player, long total) {
		player.sendSystemMessage(Component.literal(
				"你的资金不足：本次操作需要 " + Money.format(total) + " 元，当前资金 "
						+ Money.format(balanceOrMax(player)) + " 元，已回滚")
				.withStyle(ChatFormatting.RED), false);
	}

	private static String balanceSuffix(ServerPlayer player) {
		long balance = balanceOrMinusOne(player);
		return balance >= 0 ? "，剩余资金 " + Money.format(balance) + " 元" : "";
	}

	// ---------- 资金 ----------

	/** 读取余额用于资金充足性判断；数据库异常时按充足处理（扣款时再报错）。 */
	public static long balance(ServerPlayer player) {
		try {
			return EconomyDb.getBalance(player.getUUID());
		} catch (EconomyDb.DatabaseException e) {
			return Long.MAX_VALUE;
		}
	}

	public static long balanceOrMax(ServerPlayer player) {
		long balance = balanceOrMinusOne(player);
		return balance >= 0 ? balance : 0;
	}

	public static long balanceOrMinusOne(ServerPlayer player) {
		try {
			return EconomyDb.getBalance(player.getUUID());
		} catch (EconomyDb.DatabaseException e) {
			return -1;
		}
	}

	public static void deductQuietly(ServerPlayer player, long amount) {
		try {
			EconomyDb.deduct(player.getUUID(), amount);
		} catch (EconomyDb.DatabaseException ignored) {
			// 结算失败静默，避免刷屏。
		}
	}

	public static void creditQuietly(ServerPlayer player, long amount) {
		if (amount <= 0) {
			return;
		}
		try {
			EconomyDb.credit(player.getUUID(), player.getGameProfile().name(), amount);
		} catch (EconomyDb.DatabaseException ignored) {
			// 结算失败静默。
		}
	}

	public static long satAdd(long a, long b) {
		if (a > Long.MAX_VALUE - b) {
			return Long.MAX_VALUE;
		}
		return a + b;
	}
}
