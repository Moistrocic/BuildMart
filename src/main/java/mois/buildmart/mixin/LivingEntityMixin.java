package mois.buildmart.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import mois.buildmart.PriceLore;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;

/**
 * 物品丢出（含死亡掉落）时立刻清除价格标签——物品脱离玩家背包后不再显示价格。
 * drop 方法声明于 LivingEntity，必须在此注入（Mixin 无法跨继承层级解析）。
 * 26.2 签名：drop(ItemStack, boolean, boolean)（无 Prediction）。
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "drop", at = @At("HEAD"))
	private void buildmart$untagOnDrop(ItemStack stack, boolean b1, boolean b2,
			CallbackInfoReturnable<ItemEntity> cir) {
		PriceLore.untag(stack);
	}
}
