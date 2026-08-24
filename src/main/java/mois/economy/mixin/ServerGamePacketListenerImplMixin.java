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

import mois.economy.Economy;
import mois.economy.Money;
import mois.economy.PriceLore;
import mois.economy.buymode.BuyModeManager;
import mois.economy.buymode.BuyModeSession;
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
import net.minecraft.util.Prediction;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
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
			sendUntradeable(player);
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
				creditQuietly(player, refund);
				setSlotAndSync(player, menu, slotNum, slot, newStack);
				if (refund > 0) {
					sendSell(player, drop, refund);
				}
				return;
			}
			// 面板 ctrl+q：购买（严格校验 + 余额 + 扣款 + 生成实体）
			tryBuyDrop(player, drop);
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
		if (!approveBuy(player, newStack, cost)) {
			session.restore(snapshot);
			rollbackSlot(menu, slot, prev);
			return;
		}
		setSlotAndSync(player, menu, slotNum, slot, newStack);
		sendBuy(player, prev, newStack, cost);
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
			sendUntradeable(player); // 不可交易物品丢弃：作废（不退款、不生成实体）
			return;
		}
		// 已有挂起 -1：先结算旧的（无槽位包跟随 = 面板丢 = 购买）
		if (session.hasPendingDrop()) {
			ItemStack old = session.pendingDrop();
			session.clearPendingDrop();
			tryBuyDrop(player, old);
		}
		// 完全匹配暂存才吸收并卖出；否则原样挂起
		Object snapshot = session.snapshot();
		int unbought = session.absorb(dropped, dropped.getCount());
		if (unbought == 0) {
			long refund = ItemValues.price(dropped);
			creditQuietly(player, refund);
			if (refund > 0) {
				sendSell(player, dropped, refund);
			}
			return;
		}
		session.restore(snapshot);
		session.setPendingDrop(dropped);
	}

	/**
	 * 面板 ctrl+q：购买（严格校验 + 余额 + 扣款），成功时生成丢出实体。
	 * 严格校验失败或余额不足：不生成实体、不扣款（物品在客户端已销毁，无损失）。
	 */
	@Unique
	private static boolean tryBuyDrop(ServerPlayer player, ItemStack drop) {
		long cost = ItemValues.price(drop);
		if (!approveBuy(player, drop, cost)) {
			return false;
		}
		player.drop(drop.copy(), true, Prediction.PREDICTED);
		sendBuy(player, ItemStack.EMPTY, drop, cost);
		return true;
	}

	/**
	 * 购买校验 + 扣款：严格比对原版创造物品栏（剥除价格行后），再查余额。
	 * 任一失败返回 false（提示已发），由调用方回滚。
	 */
	@Unique
	private static boolean approveBuy(ServerPlayer player, ItemStack stack, long cost) {
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

		if (netDelta > 0) {
			// 防御：点击包路径（26.3 创造界面实际不发点击包，保留兜底）同样执行
			// 购买严格比对——任何价值增加的槽位内容必须与原版创造物品栏一致
			for (SlotDelta change : changes) {
				if (change.delta > 0) {
					ItemStack probe = change.after.copy();
					PriceLore.untag(probe);
					if (!isVanillaCreativeItem(probe, player.level().getServer())) {
						for (int i = 0; i < before.size() && i < current.size(); i++) {
							menu.getSlot(i).setByPlayer(before.get(i).copy());
						}
						menu.broadcastFullState();
						sendModified(player);
						return;
					}
				}
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

	/**
	 * 购买的物品是否与“原版创造物品栏”中的某个展示堆完全一致（比较相对物品默认组件的
	 * 补丁，忽略数量）。比对索引 = 创造标签页内容 ∪ 全注册物品的纯净默认形态
	 * （new ItemStack(item)）：后者作为兜底，保证即使服务端标签页内容未构建
	 * （26.3 独立服务端可能不构建），任何未经修改的纯净物品也能通过比对；内容类物品
	 * （药水/旗帜等）的合法形态来自标签页内容。
	 */
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
		// 兜底：所有注册物品的纯净默认形态（new ItemStack(item)），保证未构建标签页
		// 内容时纯净物品也能通过比对。
		for (Item item : BuiltInRegistries.ITEM) {
			CREATIVE_ITEMS.computeIfAbsent(item, k -> new ArrayList<>()).add(new ItemStack(item));
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

	/** 卖出提示：物品从背包消失（丢弃/关闭界面统一结算），按价值退款。 */
	private static void sendSell(ServerPlayer player, ItemStack stack, long refund) {
		player.sendSystemMessage(Component.literal(
				"已卖出 " + stack.getHoverName().getString() + " ×" + stack.getCount()
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
