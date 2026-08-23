package mois.economy.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import mois.economy.PriceLore;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.ResultContainer;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;

/**
 * 合成结果在“产出时”打上价格标签（背包 2×2 与工作台 3×3 均经
 * CraftingMenu.slotChangedCraftingGrid 计算结果，且只在服务端被调用）。
 * 结果槽内即带标签后：
 * <ul>
 * <li>普通点击取走 → 光标携带的堆已打标，放置时 safeInsert 的
 * isSameItemSameComponents 判定通过，与背包旧堆无缝合并；</li>
 * <li>shift 一键合成 → quickMoveStack 拷贝的堆已打标，moveItemStackTo 的
 * 合并判定通过，不会出现多个 4*木板分开堆叠。</li>
 * </ul>
 * 标签生命周期与容器一致：结果槽不属于玩家背包，关闭界面时被 untagMenu 清除。
 */
@Mixin(CraftingMenu.class)
public abstract class CraftingMenuMixin {
	@Inject(method = "slotChangedCraftingGrid", at = @At("RETURN"))
	private static void economy$tagCraftResult(AbstractContainerMenu menu, ServerLevel level, Player player,
			CraftingContainer craftingContainer, ResultContainer resultContainer, RecipeHolder<CraftingRecipe> recipe,
			CallbackInfo ci) {
		PriceLore.tag(resultContainer.getItem(0));
	}
}
