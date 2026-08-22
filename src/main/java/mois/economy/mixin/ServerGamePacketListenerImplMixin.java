package mois.economy.mixin;

import mois.economy.Money;
import mois.economy.buymode.BuyModeManager;
import mois.economy.config.ItemValues;
import mois.economy.data.EconomyDb;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 创造模式拿取处理：
 * 1. 普通创造：拿取后原版不回发同步（客户端本地堆无价格），强制下发一次槽位，
 *    使价格 lore 立即显示；
 * 2. 便捷购买模式：接管创造槽位包做资金结算——从面板拿取扣款、
 *    放入（库存减少）退款并回收、清空槽位退款；余额不足时回滚。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
	@Inject(method = "handleSetCreativeModeSlot", at = @At("HEAD"), cancellable = true)
	private void economy$handleBuyMode(ServerboundSetCreativeModeSlotPacket packet, CallbackInfo ci) {
		ServerPlayer player = ((ServerGamePacketListenerImpl) (Object) this).player;
		if (player == null || !BuyModeManager.isActive(player)) {
			return;
		}
		short slotNum = packet.slotNum();
		if (slotNum < 1 || slotNum > 45) {
			return; // 丢弃等其它操作走原版逻辑
		}
		ci.cancel();
		ItemStack newStack = packet.itemStack();
		InventoryMenu menu = player.inventoryMenu;
		Slot slot = menu.getSlot(slotNum);
		ItemStack prev = slot.getItem();

		if (newStack.isEmpty()) {
			// 清空槽位 = 放回退款
			if (!prev.isEmpty()) {
				long refund = satMul(ItemValues.get(prev.getItem()), prev.getCount());
				creditQuietly(player, refund);
				player.sendSystemMessage(Component.literal("已放回 ×" + prev.getCount()
						+ "，退款 " + Money.format(refund) + " 元").withStyle(ChatFormatting.GREEN), true);
			}
			slot.setByPlayer(ItemStack.EMPTY);
			menu.setRemoteSlot(slotNum, ItemStack.EMPTY);
		} else if (!ItemStack.isSameItemSameComponents(prev, newStack) || prev.getCount() != newStack.getCount()) {
			Item item = newStack.getItem();
			int before = countInInventory(player, item);
			slot.setByPlayer(newStack);
			menu.setRemoteSlot(slotNum, newStack);
			int after = countInInventory(player, item);
			long unit = ItemValues.get(item);
			if (after > before) {
				// 从面板拿取 → 扣款（余额不足回滚）
				int gained = Math.min(after - before, newStack.getCount());
				long total = satMul(unit, gained);
				if (balance(player) < total) {
					slot.setByPlayer(prev);
					menu.setRemoteSlot(slotNum, prev);
					player.sendSystemMessage(
							Component.literal("你的资金不足").withStyle(ChatFormatting.RED), false);
				} else {
					deductQuietly(player, total);
					EconomyDb.credit(EconomyDb.SERVER_ACCOUNT_UUID, EconomyDb.SERVER_ACCOUNT_NAME, total);
					player.sendSystemMessage(Component.literal("已购买 ×" + gained
							+ "，花费 " + Money.format(total) + " 元").withStyle(ChatFormatting.GOLD), true);
				}
			} else if (after < before) {
				// 放入自己的物品 → 退款并回收
				int given = Math.min(before - after, newStack.getCount());
				long refund = satMul(unit, given);
				creditQuietly(player, refund);
				slot.setByPlayer(ItemStack.EMPTY);
				menu.setRemoteSlot(slotNum, ItemStack.EMPTY);
				player.sendSystemMessage(Component.literal("已放回 ×" + given
						+ "，退款 " + Money.format(refund) + " 元").withStyle(ChatFormatting.GREEN), true);
			}
		}
		menu.broadcastChanges();
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

	private static long balance(ServerPlayer player) {
		try {
			return EconomyDb.getBalance(player.getUUID());
		} catch (EconomyDb.DatabaseException e) {
			return Long.MAX_VALUE; // 读取失败按充足处理，扣款时再报错
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

	private static long satMul(long a, long b) {
		if (a == 0 || b == 0) {
			return 0;
		}
		if (a > Long.MAX_VALUE / b) {
			return Long.MAX_VALUE;
		}
		return a * b;
	}
}
