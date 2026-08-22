package mois.economy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mois.economy.PriceLore;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 物品进入玩家背包的统一漏斗：捡起掉落物、指令给予、容器点击、
 * 快捷移动等最终都会写入 Inventory.setItem。在此打上价格标签，
 * 保证物品“获得前”即带标签，与背包内已有物品无缝堆叠。
 * 客户端侧同样生效（单机内置服务器开启标签时），保证本地预测与服务端一致。
 */
@Mixin(Inventory.class)
public abstract class InventoryMixin {
	@Inject(method = "setItem", at = @At("HEAD"))
	private void economy$tagOnInventorySet(int slot, ItemStack stack, CallbackInfo ci) {
		PriceLore.tag(stack);
	}
}
