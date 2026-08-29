package mois.economy.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 修复：非 OP 玩家（生存模式）放置模组刷怪笼时标签丢失。
 * <p>
 * 原版 {@code BlockItem.updateCustomBlockEntityTag} 对 {@code onlyOpCanSetNbt}
 * 的方块实体类型（MOB_SPAWNER 在列）要求 {@code canUseGameMasterBlocks()}
 * （创造 + 管理权限）才加载物品上的 BLOCK_ENTITY_DATA；
 * 普通生存玩家放置时直接跳过 {@code loadInto}，economy_spawner 标签随之丢失，
 * 放置后变成原版空笼（无法操作、破坏不掉落）。
 * <p>
 * 本 mixin 仅对携带 {@code economy_spawner} 标签的刷怪笼物品放行该检查——
 * 模组刷怪笼的参数由 {@link mois.economy.mixin.BaseSpawnerMixin} 每 tick 覆盖
 * （伪造的生成参数不生效），实体绑定本就是开放玩法，放行不引入额外能力；
 * 原版刷怪笼物品不受影响，仍按原版规则执行。
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {
	@Redirect(method = "updateCustomBlockEntityTag", at = @At(value = "INVOKE",
			target = "Lnet/minecraft/world/entity/player/Player;canUseGameMasterBlocks()Z"))
	private static boolean economy$allowTaggedSpawnerPlacement(Player player, Level level,
			Player placingPlayer, BlockPos pos, ItemStack stack) {
		TypedEntityData<?> data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
		if (data != null && data.contains("economy_spawner")) {
			return true; // 模组刷怪笼：非 OP 也可放置，BLOCK_ENTITY_DATA 正常加载
		}
		return player.canUseGameMasterBlocks();
	}
}
