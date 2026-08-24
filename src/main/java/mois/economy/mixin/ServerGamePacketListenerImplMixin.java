package mois.economy.mixin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mois.economy.Money;
import mois.economy.PriceLore;
import mois.economy.buymode.BuyModeManager;
import mois.economy.config.ItemValues;
import mois.economy.data.EconomyDb;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 便捷购买模式的服务端结算，全部以服务端权威状态为准：
 * <p>
 * 1. 创造槽位包：拦截 slotNum &lt; 0 的原版“丢出”行为（26.3 创造界面的 ctrl+q /
 * 点击外部会发送该包并由原版直接生成实体），取消实体生成；槽位 1..45 按
 * “完整价值差（基础价+附魔+容器内容物）”做拿取扣款、放回退款、清空退款，
 * 余额不足时回滚槽位并强制全量同步，杜绝客户端幽灵物品；
 * 2. 普通点击包：26.3 创造界面从面板拿取物品实际走点击包（changedSlots）通道，
 * 在处理前后对比服务端各槽位完整价值做同样的扣款/退款，余额不足时恢复点击前
 * 状态并全量同步。
 * <p>
 * 两条路径结算后都会把变化槽位的权威内容（含价格标签）强制下发给客户端：
 * 原版用 setRemoteSlot 把槽位标记为“客户端已知”后不会再发同步包，客户端本地
 * 持有的堆没有价格标签，若不补发，标签要等之后的操作触发同步才可见。
 * 非便捷购买的普通创造模式同样受影响，由 handleSetCreativeModeSlot 的 RETURN
 * 注入在保留原版处理的前提下补发打标后的槽位内容。
 * <p>
 * 购买花费只从玩家账户扣除、不入服务器资产（服务器资产仅来自商店收款与玩家主动存入）。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	// ---------- 创造槽位包 ----------

	@Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
	private void economy$handleBuyMode(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
		ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
		if (player == null || !BuyModeManager.isActive(player)) {
			return;
		}
		short slotNum = packet.slotNum();
		if (slotNum < 0) {
			// 26.3 创造界面丢弃物品（ctrl+q 等）会发送 slotNum<0 的包并由原版生成真实实体；
			// 便捷购买下取消：不生成实体。对应物品的退款由随后的槽位包结算。
			ci.cancel();
			return;
		}
		if (slotNum > 45) {
			return; // 超出玩家库存菜单范围的槽位，原版同样会忽略
		}
		ci.cancel();
		ItemStack newStack = packet.itemStack();
		InventoryMenu menu = player.inventoryMenu;
		Slot slot = menu.getSlot(slotNum);
		ItemStack prev = slot.getItem();
		// 不可交易物品（基岩/屏障/命令方块等）：禁止拿取与放回
		if (!ItemValues.isTradable(newStack) || !ItemValues.isTradable(prev)) {
			slot.setByPlayer(prev);
			menu.broadcastFullState();
			sendUntradeable(player);
			return;
		}
		// 以服务端槽位状态为准计算完整价值差（基础价+附魔+容器内容物）：
		// 价值增加=购买，价值减少=放回退款，等价变化只更新槽位不动资金。
		long delta = ItemValues.price(newStack) - ItemValues.price(prev);
		// 严格校验（购买方向，优先于余额检查）：原版创造物品栏只存在未经任何修改的
		// 初始状态物品，购买的物品必须与其完全一致（比较 item+组件，忽略数量），
		// 任何差异都驳回——改造物品无论余额多少都不可购买，先给明确提示。
		// 这样“保存的快捷栏”（客户端本地数据，标签页/热键加载）里的改造物品
		// （属性/超限附魔/自定义药水效果等）一律无法进入便捷购买；
		// 卖出/放回方向不做检测（改造物品无法通过本模式获得，能持有的只有管理员）。
		if (delta > 0 && !isVanillaCreativeItem(newStack, player.level().getServer())) {
			slot.setByPlayer(prev);
			menu.broadcastFullState();
			sendModified(player);
			return;
		}
		if (delta > 0 && balance(player) < delta) {
			// 余额不足：回滚槽位 + 强制全量同步，本包不做任何资金变动
			slot.setByPlayer(prev);
			menu.broadcastFullState();
			sendInsufficient(player, prev, newStack, delta);
			return;
		}
		slot.setByPlayer(newStack);
		// 显式打标：覆盖合成格等不经过 Inventory.setItem 的容器槽位（幂等）
		PriceLore.tag(newStack);
		// 原版 handleSetCreativeModeSlot 用 setRemoteSlot 把该槽位标记为“客户端已知”后不会
		// 再下发同步包，客户端本地持有的堆没有价格标签。这里标记远端状态后强制下发一次
		// 打标后的权威堆（同步机制与服务端 ContainerSynchronizer.sendSlotChange 一致），
		// 保证拿取/放回的瞬间客户端即可看到价值标签。
		menu.setRemoteSlot(slotNum, newStack);
		player.connection.send(new ClientboundContainerSetSlotPacket(
				menu.containerId, menu.incrementStateId(), slotNum, newStack.copy()));
		if (delta > 0) {
			// 购买：只扣玩家资金，不入服务器资产
			deductQuietly(player, delta);
			sendBuy(player, prev, newStack, delta);
		} else if (delta < 0) {
			creditQuietly(player, -delta);
			sendRefund(player, prev, newStack, -delta);
		}
	}

	// ---------- 普通创造模式（非便捷购买）的标签即时同步 ----------

	/**
	 * 原版 handleSetCreativeModeSlot 用 setRemoteSlot 把槽位标记为“客户端已知”后不会再
	 * 下发同步包（其后的 broadcastChanges 无差异），客户端本地持有的堆没有价格标签。
	 * 此处在其处理完成后把打标后的权威堆补发一次，普通创造模式拿取/放回瞬间即可看到标签。
	 * 便捷购买模式的包已被上方的 HEAD 注入接管（取消原版并自行同步），这里互斥跳过。
	 */
	@Inject(method = "handleSetCreativeModeSlot", at = @At("RETURN"))
	private void economy$syncCreativeSlotTag(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
		ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
		if (player == null || !player.hasInfiniteMaterials() || BuyModeManager.isActive(player)) {
			return;
		}
		if (!PriceLore.enabled) {
			return; // 标签功能关闭时无需补发
		}
		short slotNum = packet.slotNum();
		if (slotNum < 1 || slotNum > 45) {
			return; // 丢弃包（slotNum<0）与超范围槽位原版不写槽位
		}
		ItemStack requested = packet.itemStack();
		if (requested.isEmpty() || requested.getCount() > requested.getMaxStackSize()) {
			return; // 原版忽略此类包，槽位未变化
		}
		InventoryMenu menu = player.inventoryMenu;
		ItemStack stack = menu.getSlot(slotNum).getItem();
		if (stack.isEmpty()) {
			return;
		}
		// 显式打标：覆盖合成格等不经过 Inventory.setItem 的容器槽位（幂等）
		PriceLore.tag(stack);
		player.connection.send(new ClientboundContainerSetSlotPacket(
				menu.containerId, menu.incrementStateId(), slotNum, stack.copy()));
	}

	// ---------- 普通点击包（26.3 创造界面拿取物品的实际通道） ----------

	@Unique
	private NonNullList<ItemStack> economy$beforeItems;
	@Unique
	private boolean economy$settleClick;

	@Inject(method = "handleContainerClick", at = @At("HEAD"), cancellable = true)
	private void economy$buyModeClickHead(ServerboundContainerClickPacket packet, CallbackInfo ci) {
		ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
		if (player == null || !BuyModeManager.isActive(player)) {
			return;
		}
		InventoryMenu menu = player.inventoryMenu;
		if (packet.containerId() != menu.containerId) {
			return;
		}
		// 点击容器外部且光标有物品：原版会掉落实体。物品在拿起时已退款，这里直接作废。
		if (packet.slotNum() == -999 && !menu.getCarried().isEmpty()) {
			menu.setCarried(ItemStack.EMPTY);
			ci.cancel();
			return;
		}
		NonNullList<ItemStack> items = menu.getItems();
		NonNullList<ItemStack> snapshot = NonNullList.withSize(items.size(), ItemStack.EMPTY);
		for (int i = 0; i < items.size(); i++) {
			snapshot.set(i, items.get(i).copy());
		}
		economy$beforeItems = snapshot;
		economy$settleClick = true;
	}

	@Inject(method = "handleContainerClick", at = @At("RETURN"))
	private void economy$buyModeClickSettle(ServerboundContainerClickPacket packet, CallbackInfo ci) {
		if (!economy$settleClick) {
			return;
		}
		economy$settleClick = false;
		NonNullList<ItemStack> before = economy$beforeItems;
		economy$beforeItems = null;
		ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
		if (player == null || before == null) {
			return;
		}
		InventoryMenu menu = player.inventoryMenu;
		NonNullList<ItemStack> current = menu.getItems();
		// 不可交易物品（基岩/屏障/命令方块等）：禁止拿取与放回，整体回滚
		for (int i = 0; i < before.size() && i < current.size(); i++) {
			if (!ItemValues.isTradable(before.get(i)) || !ItemValues.isTradable(current.get(i))) {
				for (int j = 0; j < before.size() && j < current.size(); j++) {
					menu.getSlot(j).setByPlayer(before.get(j).copy());
				}
				menu.broadcastFullState();
				sendUntradeable(player);
				return;
			}
		}
		// 按槽位完整价值（基础价+附魔+容器内容物）的净变化结算
		List<SlotDelta> changes = new ArrayList<>();
		long netDelta = 0;
		for (int i = 0; i < before.size() && i < current.size(); i++) {
			long delta = ItemValues.price(current.get(i)) - ItemValues.price(before.get(i));
			if (delta != 0) {
				changes.add(new SlotDelta(i, before.get(i), current.get(i), delta));
				netDelta = satAdd(netDelta, delta);
			}
		}

		if (netDelta > 0 && balance(player) < netDelta) {
			// 余额不足：恢复点击前全部槽位 + 强制全量同步，杜绝幽灵物品；本包不做任何资金变动
			for (int i = 0; i < before.size() && i < current.size(); i++) {
				menu.getSlot(i).setByPlayer(before.get(i).copy());
			}
			menu.broadcastFullState();
			sendInsufficientNet(player, netDelta);
			return;
		}
		if (netDelta > 0) {
			// 购买：只扣玩家资金，不入服务器资产
			deductQuietly(player, netDelta);
			if (changes.size() == 1) {
				SlotDelta change = changes.get(0);
				sendBuy(player, change.before, change.after, change.delta);
			} else {
				sendBuyNet(player, netDelta);
			}
		} else if (netDelta < 0) {
			long refund = -netDelta;
			creditQuietly(player, refund);
			if (changes.size() == 1) {
				SlotDelta change = changes.get(0);
				sendRefund(player, change.before, change.after, refund);
			} else {
				sendRefundNet(player, refund);
			}
		}
		// 结算后把变化槽位的权威内容（含价格标签）下发，保证便捷购买界面内标签立即刷新：
		// 结算发生在原版 broadcastChanges 之后，若客户端预测状态与服务端打标结果不一致，
		// 原版同步可能漏发，这里逐槽位补发一次（幂等）。
		for (SlotDelta change : changes) {
			ItemStack stack = menu.getSlot(change.slot).getItem();
			PriceLore.tag(stack);
			player.connection.send(new ClientboundContainerSetSlotPacket(
					menu.containerId, menu.incrementStateId(), change.slot, stack.copy()));
		}
	}

	/** 一次点击包结算中单个槽位的价值变化。 */
	@Unique
	private record SlotDelta(int slot, ItemStack before, ItemStack after, long delta) {
	}

	// ---------- 工具 ----------

	/** 原版创造物品栏内容索引：物品 → 该物品在创造面板中的全部展示堆（构建一次后缓存）。 */
	@Unique
	private static final Map<Item, List<ItemStack>> CREATIVE_ITEMS = new HashMap<>();
	@Unique
	private static boolean creativeItemsBuilt = false;

	/** 购买的物品是否与原版创造物品栏中的某个展示堆完全一致（比较 item+组件，忽略数量）。 */
	@Unique
	private static boolean isVanillaCreativeItem(ItemStack stack, MinecraftServer server) {
		if (stack.isEmpty()) {
			return true;
		}
		buildCreativeItemsIfNeeded(server);
		List<ItemStack> candidates = CREATIVE_ITEMS.get(stack.getItem());
		if (candidates == null) {
			return false;
		}
		for (ItemStack creative : candidates) {
			if (ItemStack.isSameItemSameComponents(stack, creative)) {
				return true;
			}
		}
		return false;
	}

	/** 首次使用时构建创造物品栏内容（与客户端展示用同一套注册表/特性构建，内容一致）。 */
	@Unique
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
	}

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

	private static void sendBuy(ServerPlayer player, ItemStack before, ItemStack after, long cost) {
		player.sendSystemMessage(Component.literal(
				"已购买 " + gainedName(before, after)
						+ "，花费 " + Money.format(cost) + " 元" + balanceSuffix(player))
				.withStyle(ChatFormatting.GOLD), false);
	}

	private static void sendRefund(ServerPlayer player, ItemStack before, ItemStack after, long refund) {
		player.sendSystemMessage(Component.literal(
				"已放回 " + lostName(before, after)
						+ "，获得 " + Money.format(refund) + " 元" + balanceSuffix(player))
				.withStyle(ChatFormatting.GREEN), false);
	}

	private static void sendBuyNet(ServerPlayer player, long cost) {
		player.sendSystemMessage(Component.literal(
				"已购买物品，花费 " + Money.format(cost) + " 元" + balanceSuffix(player))
				.withStyle(ChatFormatting.GOLD), false);
	}

	private static void sendRefundNet(ServerPlayer player, long refund) {
		player.sendSystemMessage(Component.literal(
				"已放回物品，获得 " + Money.format(refund) + " 元" + balanceSuffix(player))
				.withStyle(ChatFormatting.GREEN), false);
	}

	private static void sendInsufficient(ServerPlayer player, ItemStack before, ItemStack after, long total) {
		player.sendSystemMessage(Component.literal(
				"你的资金不足：购买 " + gainedName(before, after)
						+ " 需要 " + Money.format(total) + " 元，当前资金 " + Money.format(balanceOrMax(player)) + " 元")
				.withStyle(ChatFormatting.RED), false);
	}

	private static void sendUntradeable(ServerPlayer player) {
		player.sendSystemMessage(Component.literal(
				"该物品不可购买或出售").withStyle(ChatFormatting.RED), false);
	}

	private static void sendModified(ServerPlayer player) {
		player.sendSystemMessage(Component.literal(
				"该物品与原版创造物品栏不一致，无法购买（仅允许未经修改的物品）").withStyle(ChatFormatting.RED), false);
	}

	private static void sendInsufficientNet(ServerPlayer player, long total) {
		player.sendSystemMessage(Component.literal(
				"你的资金不足：本次操作需要 " + Money.format(total) + " 元，当前资金 "
						+ Money.format(balanceOrMax(player)) + " 元，已回滚")
				.withStyle(ChatFormatting.RED), false);
	}

	private static String balanceSuffix(ServerPlayer player) {
		long balance = balanceOrMinusOne(player);
		return balance >= 0 ? "，剩余资金 " + Money.format(balance) + " 元" : "";
	}

	/** 读取余额用于资金充足性判断；数据库异常时按充足处理（扣款时再报错）。 */
	private static long balance(ServerPlayer player) {
		try {
			return EconomyDb.getBalance(player.getUUID());
		} catch (EconomyDb.DatabaseException e) {
			return Long.MAX_VALUE;
		}
	}

	private static long balanceOrMax(ServerPlayer player) {
		long balance = balanceOrMinusOne(player);
		return balance >= 0 ? balance : 0;
	}

	private static long balanceOrMinusOne(ServerPlayer player) {
		try {
			return EconomyDb.getBalance(player.getUUID());
		} catch (EconomyDb.DatabaseException e) {
			return -1;
		}
	}

	private static void deductQuietly(ServerPlayer player, long amount) {
		try {
			EconomyDb.deduct(player.getUUID(), amount);
		} catch (EconomyDb.DatabaseException ignored) {
			// 结算失败静默，避免刷屏。
		}
	}

	private static void creditQuietly(ServerPlayer player, long amount) {
		if (amount <= 0) {
			return;
		}
		try {
			EconomyDb.credit(player.getUUID(), player.getGameProfile().name(), amount);
		} catch (EconomyDb.DatabaseException ignored) {
			// 结算失败静默。
		}
	}

	private static long satAdd(long a, long b) {
		if (a > Long.MAX_VALUE - b) {
			return Long.MAX_VALUE;
		}
		return a + b;
	}
}
