package mois.buildmart.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mois.buildmart.PriceLore;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;

/**
 * 快捷移动（shift 点击）合并前的兜底打标：moveItemStackTo 在合并判定
 * （isSameItemSameComponents）之前运行，待移动的堆若未打标（如切石机/锻造台
 * 等不经过 CraftingMenuMixin 的结果，或其它模组产物）会与背包内已打标旧堆
 * 判为异种物品而分格堆放。此处先把待移动堆打上标签（幂等），保证合并判定一致。
 * 客户端侧同样生效（单机内置服务器开启标签时），保证本地预测与服务端一致。
 */
@Mixin(AbstractContainerMenu.class)
public abstract class AbstractContainerMenuMixin {
	@Inject(method = "moveItemStackTo", at = @At("HEAD"))
	private void buildmart$tagBeforeMove(ItemStack stack, int startIndex, int endIndex, boolean reverseDirection,
			CallbackInfoReturnable<Boolean> cir) {
		PriceLore.tag(stack);
	}
}
