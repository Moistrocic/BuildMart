package mois.economy.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SpawnerBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 带标签刷怪笼可回收：玩家用镐破坏**本模组生成的刷怪笼**（economy_spawner 标记）时
 * 掉落带完整数据（实体类型 + 等级 + 参数微调）的刷怪笼物品，重新放置即恢复——
 * 升级投入不白费。**原版刷怪笼不受影响**（不掉落）；创造模式不掉落。
 * 注入 {@code Block.playerDestroy}（playerDestroy 声明于 Block，mixin 不搜索父类
 * 方法），运行时判断目标是否为刷怪笼。
 */
@Mixin(Block.class)
public abstract class SpawnerBlockMixin {
	@Inject(method = "playerDestroy", at = @At("HEAD"))
	private void economy$dropSpawnerItem(ServerLevel level, ServerPlayer player, BlockPos pos,
			BlockState state, BlockEntity blockEntity, ItemStack tool, CallbackInfo ci) {
		if (player.isCreative()) {
			return; // 创造模式不掉落
		}
		if (!((Object) this instanceof SpawnerBlock)) {
			return;
		}
		if (!(blockEntity instanceof SpawnerBlockEntity spawner)) {
			return;
		}
		if (!((mois.economy.spawner.SpawnerStateAccess) spawner).economyTagged()) {
			return; // 原版刷怪笼：不掉落
		}
		BlockEntityType<?> spawnerType = BuiltInRegistries.BLOCK_ENTITY_TYPE
				.getValue(Identifier.fromNamespaceAndPath("minecraft", "spawner"));
		CompoundTag tag = spawner.saveCustomOnly(level.registryAccess());
		ItemStack stack = new ItemStack(Items.SPAWNER);
		stack.set(DataComponents.BLOCK_ENTITY_DATA,
				TypedEntityData.of(spawnerType, tag));
		SpawnerBlock.popResource(level, pos, stack);
	}
}
