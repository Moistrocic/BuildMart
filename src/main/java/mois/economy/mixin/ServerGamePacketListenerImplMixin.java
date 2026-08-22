package mois.economy.mixin;

import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 创造模式拿取物品时，原版通过 setRemoteSlot 把该槽位标记为“客户端已知”，
 * 不会再下发同步包——客户端本地持有的堆没有价格 lore。此处处理后强制下发一次
 * 该槽位（下发方向会注入价格行），保证拿取瞬间即可看到价格。
 */
@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {
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
		ItemStack stack = packet.itemStack();
		if (stack.isEmpty()) {
			return;
		}
		InventoryMenu menu = player.inventoryMenu;
		player.connection.send(new ClientboundContainerSetSlotPacket(
				menu.containerId, menu.incrementStateId(), slotNum, stack));
	}
}
