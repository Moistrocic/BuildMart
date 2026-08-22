package mois.economy.mixin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mois.economy.Money;
import mois.economy.buymode.BuyModeManager;
import mois.economy.config.ItemValues;
import mois.economy.data.EconomyDb;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 便捷购买模式的服务端结算，全部以服务端权威状态为准：
 * <p>
 * 1. 创造槽位包：拦截 slotNum &lt; 0 的原版“丢出”行为（26.3 创造界面的 ctrl+q /
 * 点击外部会发送该包并由原版直接生成实体），取消实体生成；槽位 1..45 做拿取扣款、
 * 放回退款、清空退款，余额不足时回滚槽位并强制全量同步，杜绝客户端幽灵物品；
 * 2. 普通点击包：26.3 创造界面从面板拿取物品实际走点击包（changedSlots）通道，
 * 在处理前后对比服务端库存计数做同样的扣款/退款，余额不足时恢复点击前状态并全量同步；
 * 3. 普通创造模式（非购买模式）下拿取物品后补发一次槽位同步，让价格 lore 立即生效。
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

		if (newStack.isEmpty()) {
			// 清空槽位（放回物品栏/垃圾桶）= 放回退款
			if (!prev.isEmpty()) {
				long refund = satMul(ItemValues.get(prev.getItem()), prev.getCount());
				creditQuietly(player, refund);
				sendRefund(player, prev, prev.getCount(), refund);
			}
			slot.setByPlayer(ItemStack.EMPTY);
			menu.setRemoteSlot(slotNum, ItemStack.EMPTY);
			return;
		}
		if (ItemStack.isSameItemSameComponents(prev, newStack) && prev.getCount() == newStack.getCount()) {
			return;
		}
		// 内容有变化：对“被移走的物品”和“新放入的物品”分别结算
		Item newItem = newStack.getItem();
		int beforeNew = countInInventory(player, newItem);
		boolean prevIsOther = !prev.isEmpty() && !ItemStack.isSameItemSameComponents(prev, newStack);
		int beforePrev = prevIsOther ? countInInventory(player, prev.getItem()) : 0;
		slot.setByPlayer(newStack);
		menu.setRemoteSlot(slotNum, newStack);
		int afterNew = countInInventory(player, newItem);

		long refundAmount = 0;
		int refundCount = 0;
		ItemStack refundStack = null;
		if (prevIsOther) {
			int lost = Math.min(beforePrev - countInInventory(player, prev.getItem()), prev.getCount());
			if (lost > 0) {
				refundCount = lost;
				refundAmount = satMul(ItemValues.get(prev.getItem()), lost);
				refundStack = prev;
			}
		}
		long buyAmount = 0;
		int buyCount = 0;
		if (afterNew > beforeNew) {
			buyCount = Math.min(afterNew - beforeNew, newStack.getCount());
			buyAmount = satMul(ItemValues.get(newItem), buyCount);
		} else if (afterNew < beforeNew && refundCount == 0) {
			// 同类物品数量减少（如丢弃后剩余状态包）：退款，保留客户端声明的剩余数量
			int given = Math.min(beforeNew - afterNew, prev.getCount());
			if (given > 0) {
				refundCount = given;
				refundAmount = satMul(ItemValues.get(newItem), given);
				refundStack = prev;
			}
		}

		if (buyAmount > 0 && balance(player) < buyAmount) {
			// 余额不足：回滚槽位 + 强制全量同步，本包不做任何资金变动
			slot.setByPlayer(prev);
			menu.broadcastFullState();
			sendInsufficient(player, newStack, buyCount, buyAmount);
			return;
		}
		if (refundAmount > 0) {
			creditQuietly(player, refundAmount);
			sendRefund(player, refundStack, refundCount, refundAmount);
		}
		if (buyAmount > 0) {
			deductQuietly(player, buyAmount);
			creditServerQuietly(buyAmount);
			sendBuy(player, newStack, buyCount, buyAmount);
		}
	}

	@Inject(method = "handleSetCreativeModeSlot", at = @At("RETURN"))
	private void economy$syncCreativeSlot(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
		ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
		if (player == null || !player.hasInfiniteMaterials()) {
			return;
		}
		short slotNum = packet.slotNum();
		if (slotNum < 1 || slotNum > 45) {
			return;
		}
		InventoryMenu menu = player.inventoryMenu;
		// 同步服务端的最终槽位状态（购买模式下被回滚时也能纠正客户端）。
		ItemStack current = menu.getSlot(slotNum).getItem();
		player.connection.send(new ClientboundContainerSetSlotPacket(
				menu.containerId, menu.incrementStateId(), slotNum, current));
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
		Map<Item, Integer> beforeCounts = countByItem(before);
		Map<Item, Integer> afterCounts = countByItem(menu.getItems());

		List<Change> bought = new ArrayList<>();
		List<Change> sold = new ArrayList<>();
		long netCharge = 0;
		Set<Item> keys = new HashSet<>(beforeCounts.keySet());
		keys.addAll(afterCounts.keySet());
		for (Item item : keys) {
			int diff = afterCounts.getOrDefault(item, 0) - beforeCounts.getOrDefault(item, 0);
			if (diff > 0) {
				long cost = satMul(ItemValues.get(item), diff);
				netCharge = satAdd(netCharge, cost);
				bought.add(new Change(item, diff, cost));
			} else if (diff < 0) {
				sold.add(new Change(item, -diff, satMul(ItemValues.get(item), -diff)));
			}
		}

		if (netCharge > 0 && balance(player) < netCharge) {
			// 余额不足：恢复点击前全部槽位 + 强制全量同步，杜绝幽灵物品；本包不做任何资金变动
			NonNullList<ItemStack> current = menu.getItems();
			for (int i = 0; i < before.size() && i < current.size(); i++) {
				menu.getSlot(i).setByPlayer(before.get(i).copy());
			}
			menu.broadcastFullState();
			sendInsufficientNet(player, netCharge);
			return;
		}
		for (Change change : sold) {
			creditQuietly(player, change.amount);
			sendRefund(player, new ItemStack(change.item), change.count, change.amount);
		}
		if (netCharge > 0) {
			deductQuietly(player, netCharge);
			creditServerQuietly(netCharge);
		}
		for (Change change : bought) {
			sendBuy(player, new ItemStack(change.item), change.count, change.amount);
		}
	}

	/** 一次点击包结算中单个物品种类的净变化。 */
	@Unique
	private record Change(Item item, int count, long amount) {
	}

	// ---------- 工具 ----------

	private static Map<Item, Integer> countByItem(NonNullList<ItemStack> items) {
		Map<Item, Integer> counts = new HashMap<>();
		for (ItemStack stack : items) {
			if (!stack.isEmpty()) {
				counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
			}
		}
		return counts;
	}

	private static int countInInventory(ServerPlayer player, Item item) {
		Inventory inventory = player.getInventory();
		int count = 0;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack stack = inventory.getItem(i);
			if (stack.is(item)) {
				count += stack.getCount();
			}
		}
		return count;
	}

	private static void sendBuy(ServerPlayer player, ItemStack stack, int count, long cost) {
		player.sendSystemMessage(Component.literal(
				"已购买 " + stack.getHoverName().getString() + " ×" + count
						+ "，花费 " + Money.format(cost) + " 元" + balanceSuffix(player))
				.withStyle(ChatFormatting.GOLD), false);
	}

	private static void sendRefund(ServerPlayer player, ItemStack stack, int count, long refund) {
		player.sendSystemMessage(Component.literal(
				"已放回 " + stack.getHoverName().getString() + " ×" + count
						+ "，获得 " + Money.format(refund) + " 元" + balanceSuffix(player))
				.withStyle(ChatFormatting.GREEN), false);
	}

	private static void sendInsufficient(ServerPlayer player, ItemStack stack, int count, long total) {
		player.sendSystemMessage(Component.literal(
				"你的资金不足：购买 " + stack.getHoverName().getString() + " ×" + count
						+ " 需要 " + Money.format(total) + " 元，当前资金 " + Money.format(balanceOrMax(player)) + " 元")
				.withStyle(ChatFormatting.RED), false);
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

	private static void creditServerQuietly(long amount) {
		if (amount <= 0) {
			return;
		}
		try {
			EconomyDb.credit(EconomyDb.SERVER_ACCOUNT_UUID, EconomyDb.SERVER_ACCOUNT_NAME, amount);
		} catch (EconomyDb.DatabaseException ignored) {
			// 结算失败静默。
		}
	}

	private static long satMul(long a, long b) {
		if (a == 0 || b == 0) {
			return 0;
		}
		if (a > Long.MAX_VALUE / b) {
			return Long.MAX_VALUE;
		}
		return a * b;
	}

	private static long satAdd(long a, long b) {
		if (a > Long.MAX_VALUE - b) {
			return Long.MAX_VALUE;
		}
		return a + b;
	}
}
