package mois.economy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mois.economy.PriceLore;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 物品进入玩家背包的统一漏斗：捡起掉落物、指令给予走 Inventory.add
 * （26.3 中它直接写 items 列表、不经过 setItem），容器点击/快捷移动走
 * Slot.setByPlayer → container.setItem。两条路径都在写入前打上价格标签，
 * 保证物品“获得前”即带标签，与背包内已有物品无缝堆叠。
 * 客户端侧同样生效（单机内置服务器开启标签时），保证本地预测与服务端一致。
 */
@Mixin(Inventory.class)
public abstract class InventoryMixin {
	@Inject(method = "setItem", at = @At("HEAD"))
	private void economy$tagOnInventorySet(int slot, ItemStack stack, CallbackInfo ci) {
		PriceLore.tag(stack);
	}

	@Inject(method = "add", at = @At("HEAD"))
	private void economy$tagOnInventoryAdd(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		// 捡起/指令给予走 add(ItemStack)，内部以 slot=-1 自选目标槽位：
		// 合并判定在写入前进行，进栈与所有候选槽位都打标才能无缝合并
		PriceLore.tag(stack);
		Inventory inventory = (Inventory) (Object) this;
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			// 跳过当前手持槽位：26.3 客户端 sameDestroyTarget 逐组件比较手持物品，
			// 捡起物品时重打标签会刷新手持工具的价格行（价格随剩余耐久变化），触发
			// 槽位同步并把正在进行的挖掘进度重置为 0；手持物品的价格行留待它
			// 离开手持槽位时再刷新（届时价格计算已用最新耐久）。
			if (i == inventory.getSelectedSlot()) {
				continue;
			}
			PriceLore.tag(inventory.getItem(i));
		}
	}
}
