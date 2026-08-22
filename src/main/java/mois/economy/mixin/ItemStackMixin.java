package mois.economy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mois.economy.PriceLore;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 包装 ItemStack 的网络流编解码器（静态初始化时包装 createOptionalStreamCodec 的返回值）：
 * 服务端发出方向注入价格 lore，回传方向剥除。OPTIONAL_LIST_STREAM_CODEC 与
 * STREAM_CODEC 均派生自 OPTIONAL_STREAM_CODEC，因此一次包装覆盖全部路径。
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@WrapOperation(method = "<clinit>", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/item/ItemStack;createOptionalStreamCodec(Lnet/minecraft/network/codec/StreamCodec;)Lnet/minecraft/network/codec/StreamCodec;"))
	private static StreamCodec<RegistryFriendlyByteBuf, ItemStack> economy$wrapPriceLore(
			StreamCodec<RegistryFriendlyByteBuf, DataComponentPatch> patchCodec,
			Operation<StreamCodec<RegistryFriendlyByteBuf, ItemStack>> original) {
		return PriceLore.wrap(original.call(patchCodec));
	}
}
