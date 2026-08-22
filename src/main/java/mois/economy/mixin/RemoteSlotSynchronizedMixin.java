package mois.economy.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import mois.economy.PriceLore;
import net.minecraft.network.HashedPatchMap;
import net.minecraft.network.HashedStack;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * 26.3 的菜单同步校验：客户端点击包携带的是组件哈希（HashedStack），
 * 服务端用客户端哈希与自己实物栈比对，判断客户端视图是否一致。
 * <p>
 * 价格 lore 是线路注入的展示层数据：客户端栈带价格行、服务端栈不带，
 * 直接比对会永远不匹配，导致槽位/光标被反复强制重发、stateId 竞速，
 * 玩家移动物品（背包或容器）时出现幽灵物品。
 * <p>
 * 这里在比对前给候选栈注入价格行（与客户端所见一致），等效于忽略价格行差异，
 * 恢复原版同步语义；注入本身幂等，不会行堆积。
 */
@Mixin(targets = "net.minecraft.world.inventory.RemoteSlot$Synchronized")
public abstract class RemoteSlotSynchronizedMixin {
	@WrapOperation(method = "matches", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/network/HashedStack;matches(Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/network/HashedPatchMap$HashGenerator;)Z"))
	private boolean economy$matchesIgnoringPriceLore(HashedStack hashedStack, ItemStack candidate,
			HashedPatchMap.HashGenerator generator, Operation<Boolean> original) {
		return original.call(hashedStack, PriceLore.enabled ? PriceLore.inject(candidate) : candidate, generator);
	}
}
