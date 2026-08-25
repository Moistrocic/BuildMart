package mois.economy.mixin;

import java.util.ArrayList;
import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mois.economy.PriceLore;
import mois.economy.buymode.BuyModeManager;
import mois.economy.buymode.BuyModeSession;
import mois.economy.buymode.BuyModeSettlement;
import mois.economy.command.HongbaoCommands;
import mois.economy.config.ItemValues;
import net.minecraft.ChatFormatting;
import net.minecraft.core.NonNullList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundChatPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * 便捷购买模式的服务端结算，全部以服务端权威状态为准，判定模型见
 * {@link mois.economy.buymode.BuyModeSession}：
 * <p>
 * 1. 创造槽位包：背包物品消失（拿起/拆分/顶出）→ 暂存不结算；出现且匹配暂存
 * → 中性放回；出现不匹配暂存（来自创造面板）→ 购买（严格比对原版创造物品栏
 * + 余额检查 + 扣款）；slotNum &lt; 0 的丢弃包 → 匹配暂存=卖出（物品消失），
 * 不匹配则挂起由下一槽位包区分「背包 ctrl+q 直接丢（卖出）」与
 * 「面板 ctrl+q（购买并生成实体）」；余额不足/比对失败回滚槽位并全量同步；
 * 2. 普通点击包（26.3 创造界面实际不发点击包，保留兜底）：按槽位价值净变化
 * 结算，购买方向同样执行严格比对。
 * <p>
 * 结算后把变化槽位的权威内容（含价格标签）强制下发给客户端：原版用 setRemoteSlot
 * 把槽位标记为“客户端已知”后不会再发同步包，客户端本地持有的堆没有价格标签，
 * 若不补发，标签要等之后的操作触发同步才可见。非便捷购买的普通创造模式同样
 * 受影响，由 handleSetCreativeModeSlot 的 RETURN 注入在保留原版处理的前提下
 * 补发打标后的槽位内容。
 * <p>
 * 关闭物品栏/退出模式/掉线时，会话内尚未结清的暂存物品统一按卖出结算
 * （见 {@link mois.economy.buymode.BuyModeManager#exit(net.minecraft.server.level.ServerPlayer)}）。
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
			// 26.3 创造界面丢弃物品（ctrl+q 等）会发送 slotNum<0 的包并由原版生成真实实体。
			// 便捷购买下接管判定：匹配暂存=卖出（先拿起再丢）；否则挂起由下一槽位包
			// 用槽位原内容区分「背包 ctrl+q 直接丢（卖出）」与「面板 ctrl+q（购买）」。
			ci.cancel();
			BuyModeSession session = BuyModeManager.session(player);
			if (session != null) {
				handleBuyModeDrop(player, session, packet.itemStack());
			}
			return;
		}
		if (slotNum > 45) {
			return; // 超出玩家库存菜单范围的槽位，原版同样会忽略
		}
		ci.cancel();
		BuyModeSession session = BuyModeManager.session(player);
		if (session == null) {
			return;
		}
		ItemStack newStack = packet.itemStack();
		InventoryMenu menu = player.inventoryMenu;
		Slot slot = menu.getSlot(slotNum);
		ItemStack prev = slot.getItem();
		// 不可交易物品（基岩/屏障/命令方块等）：禁止拿取与放回
		if (!ItemValues.isTradable(newStack) || !ItemValues.isTradable(prev)) {
			slot.setByPlayer(prev);
			menu.broadcastFullState();
			BuyModeSettlement.sendUntradeable(player);
			return;
		}
		// ---- 1) 结算挂起的 -1 包（用槽位原内容判定来源）----
		if (session.hasPendingDrop()) {
			ItemStack drop = session.pendingDrop();
			session.clearPendingDrop();
			if (!drop.isEmpty() && BuyModeSession.sameItemAndComponents(prev, drop)
					&& prev.getCount() >= drop.getCount()) {
				// 背包 ctrl+q 直接丢：卖出丢出部分；槽位直接设为剩余（不再走出现/消失判定）
				long refund = ItemValues.price(drop);
				BuyModeSettlement.creditQuietly(player, refund);
				setSlotAndSync(player, menu, slotNum, slot, newStack);
				if (refund > 0) {
					BuyModeSettlement.sendSell(player, drop, refund);
				}
				return;
			}
			// 面板 ctrl+q：购买（严格校验 + 余额 + 扣款 + 生成实体）
			BuyModeSettlement.buyDrop(player, drop);
		}
		// ---- 2) 出现/消失判定（暂存模型）----
		if (newStack.isEmpty()) {
			// 消失：拿起/清空，物品入暂存（不结算，关闭界面时统一卖出）
			if (!prev.isEmpty()) {
				session.recordVanished(prev);
			}
			setSlotAndSync(player, menu, slotNum, slot, newStack);
			return;
		}
		boolean sameAsPrev = !prev.isEmpty() && BuyModeSession.sameItemAndComponents(prev, newStack);
		if (sameAsPrev && newStack.getCount() < prev.getCount()) {
			// 部分消失（拆分拿起等）：消失部分入暂存，剩余留槽
			session.recordVanished(newStack.copyWithCount(prev.getCount() - newStack.getCount()));
			setSlotAndSync(player, menu, slotNum, slot, newStack);
			return;
		}
		if (!sameAsPrev && !prev.isEmpty()) {
			// 原槽内容被顶出（去客户端光标）→ 入暂存
			session.recordVanished(prev);
		}
		// 先吸收暂存（放回自己的物品 → 中性）；未能吸收的增量 = 面板来源 → 购买
		int deltaCount = sameAsPrev ? newStack.getCount() - prev.getCount() : newStack.getCount();
		int unbought = session.absorb(newStack, deltaCount);
		if (unbought == 0) {
			// 全部来自暂存（放回自己的物品）：中性
			setSlotAndSync(player, menu, slotNum, slot, newStack);
			return;
		}
		// 未能吸收的部分来自创造面板：购买（严格校验 + 余额 + 按增量扣款）
		long cost = ItemValues.price(newStack)
				- ItemValues.price(newStack.copyWithCount(newStack.getCount() - unbought));
		Object snapshot = session.snapshot();
		if (!BuyModeSettlement.approveBuy(player, newStack, cost)) {
			session.restore(snapshot);
			rollbackSlot(menu, slot, prev);
			return;
		}
		setSlotAndSync(player, menu, slotNum, slot, newStack);
		BuyModeSettlement.sendBuy(player, prev, newStack, cost);
	}

	/**
	 * -1 丢弃包判定：先匹配暂存（拿起后丢弃 = 卖出，按丢弃数量退款）；
	 * 不匹配则挂起 pendingDrop，由下一个槽位包用「槽位原内容」区分
	 * 背包 ctrl+q 直接丢（卖出）与面板 ctrl+q（购买）。
	 */
	@Unique
	private static void handleBuyModeDrop(ServerPlayer player, BuyModeSession session, ItemStack dropped) {
		if (dropped.isEmpty()) {
			return;
		}
		if (!ItemValues.isTradable(dropped)) {
			BuyModeSettlement.sendUntradeable(player); // 不可交易物品丢弃：作废（不退款、不生成实体）
			return;
		}
		// 已有挂起 -1：先结算旧的（无槽位包跟随 = 面板丢 = 购买）
		if (session.hasPendingDrop()) {
			ItemStack old = session.pendingDrop();
			session.clearPendingDrop();
			BuyModeSettlement.buyDrop(player, old);
		}
		// 完全匹配暂存才吸收并卖出；否则原样挂起
		Object snapshot = session.snapshot();
		int unbought = session.absorb(dropped, dropped.getCount());
		if (unbought == 0) {
			long refund = ItemValues.price(dropped);
			BuyModeSettlement.creditQuietly(player, refund);
			if (refund > 0) {
				BuyModeSettlement.sendSell(player, dropped, refund);
			}
			return;
		}
		session.restore(snapshot);
		session.setPendingDrop(dropped, player.level().getServer().getTickCount());
	}

	/** 设置槽位（服务端权威 + 打标 + 强制下发），购买/放回/拿起共用。 */
	@Unique
	private static void setSlotAndSync(ServerPlayer player, InventoryMenu menu, int slotNum, Slot slot, ItemStack stack) {
		slot.setByPlayer(stack);
		// 显式打标：覆盖合成格等不经过 Inventory.setItem 的容器槽位（幂等）
		PriceLore.tag(stack);
		// 原版 handleSetCreativeModeSlot 用 setRemoteSlot 把该槽位标记为“客户端已知”后不会
		// 再下发同步包，客户端本地持有的堆没有价格标签。这里标记远端状态后强制下发一次
		// 打标后的权威堆（同步机制与服务端 ContainerSynchronizer.sendSlotChange 一致），
		// 保证拿取/放回的瞬间客户端即可看到价值标签。
		menu.setRemoteSlot(slotNum, stack);
		player.connection.send(new ClientboundContainerSetSlotPacket(
				menu.containerId, menu.incrementStateId(), slotNum, stack.copy()));
	}

	/** 回滚槽位并全量同步（购买被拒/余额不足）。 */
	@Unique
	private static void rollbackSlot(InventoryMenu menu, Slot slot, ItemStack prev) {
		slot.setByPlayer(prev);
		menu.broadcastFullState();
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
				BuyModeSettlement.sendUntradeable(player);
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
				netDelta = BuyModeSettlement.satAdd(netDelta, delta);
			}
		}

		if (netDelta > 0) {
			// 防御：点击包路径（26.3 创造界面实际不发点击包，保留兜底）同样执行
			// 购买严格比对——任何价值增加的槽位内容必须与原版创造物品栏一致
			for (SlotDelta change : changes) {
				if (change.delta > 0) {
					ItemStack probe = change.after.copy();
					PriceLore.untag(probe);
					if (!BuyModeSettlement.isVanillaCreativeItem(probe, player.level().getServer())) {
						for (int i = 0; i < before.size() && i < current.size(); i++) {
							menu.getSlot(i).setByPlayer(before.get(i).copy());
						}
						menu.broadcastFullState();
						BuyModeSettlement.sendModified(player);
						return;
					}
				}
			}
		}
		if (netDelta > 0 && BuyModeSettlement.balance(player) < netDelta) {
			// 余额不足：恢复点击前全部槽位 + 强制全量同步，杜绝幽灵物品；本包不做任何资金变动
			for (int i = 0; i < before.size() && i < current.size(); i++) {
				menu.getSlot(i).setByPlayer(before.get(i).copy());
			}
			menu.broadcastFullState();
			BuyModeSettlement.sendInsufficientNet(player, netDelta);
			return;
		}
		if (netDelta > 0) {
			// 购买：只扣玩家资金，不入服务器资产
			BuyModeSettlement.deductQuietly(player, netDelta);
			if (changes.size() == 1) {
				SlotDelta change = changes.get(0);
				BuyModeSettlement.sendBuy(player, change.before, change.after, change.delta);
			} else {
				BuyModeSettlement.sendBuyNet(player, netDelta);
			}
		} else if (netDelta < 0) {
			long refund = -netDelta;
			BuyModeSettlement.creditQuietly(player, refund);
			if (changes.size() == 1) {
				SlotDelta change = changes.get(0);
				BuyModeSettlement.sendRefund(player, change.before, change.after, refund);
			} else {
				BuyModeSettlement.sendRefundNet(player, refund);
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

	// ---------- 红包聊天领取 ----------

	/**
	 * 聊天发言与红包口令完全一致（trim 后精确匹配）时自动领取红包：
	 * 发言照常进入公屏（不取消原版处理），领取结果私聊反馈，领取成功由
	 * HongbaoCommands 全服广播；非口令发言完全不受影响。
	 */
	@Inject(method = "handleChat", at = @At("HEAD"), cancellable = true)
	private void economy$hongbaoChat(ServerboundChatPacket packet, CallbackInfo ci) {
		ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
		if (player == null) {
			return;
		}
		String message = packet.message();
		if (!HongbaoCommands.isPass(message)) {
			return; // 非口令发言：原版正常处理
		}
		// 口令发言：照常进入公屏，同时触发领取
		String error = HongbaoCommands.claimByPass(player, message.trim(), player.level().getServer());
		if (error != null) {
			player.sendSystemMessage(Component.literal(error).withStyle(ChatFormatting.RED), false);
		}
	}

}