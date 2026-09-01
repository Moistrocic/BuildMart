package mois.economy.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.Level;
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
 * 26.2 签名：playerDestroy(Level, Player, BlockPos, BlockState, BlockEntity, ItemStack)。
 */
@Mixin(Block.class)
public abstract class SpawnerBlockMixin {
	@Inject(method = "playerDestroy", at = @At("HEAD"))
	private void economy$dropSpawnerItem(Level level, Player player, BlockPos pos,
			BlockState state, BlockEntity blockEntity, ItemStack tool, CallbackInfo ci) {
		if (!(level instanceof ServerLevel serverLevel) || !(player instanceof ServerPlayer serverPlayer)) {
			return;
		}
		if (serverPlayer.isCreative()) {
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
		// 挖掉时移除自动出售悬浮实体
		mois.economy.spawner.SpawnerManager.removeDisplay(level, spawner);
		// 注册 ID 为 minecraft:mob_spawner（spawner 兜底）；注册表异常时不掉落
		BlockEntityType<?> spawnerType = BuiltInRegistries.BLOCK_ENTITY_TYPE
				.getValue(Identifier.fromNamespaceAndPath("minecraft", "mob_spawner"));
		if (spawnerType == null) {
			spawnerType = BuiltInRegistries.BLOCK_ENTITY_TYPE
					.getValue(Identifier.fromNamespaceAndPath("minecraft", "spawner"));
		}
		if (spawnerType == null) {
			return;
		}
		CompoundTag tag = spawner.saveCustomOnly(serverLevel.registryAccess());
		normalizeForStacking(tag);
		ItemStack stack = new ItemStack(Items.SPAWNER);
		stack.set(DataComponents.BLOCK_ENTITY_DATA,
				TypedEntityData.of(spawnerType, tag));
		SpawnerBlock.popResource(serverLevel, pos, stack);
	}

	/**
	 * 归一化掉落物品的 BLOCK_ENTITY_DATA，使其与 /spawner give 底版
	 * （全新 {@link SpawnerBlockEntity} 的完整存档）完全一致，可互相堆叠：
	 * <ol>
	 * <li>{@code Delay}（生成倒计时）是放置后的运行时变量，每次挖回都不同
	 *     （挖回物品之间也不堆叠）→ 重置为 give 底版的初始值 20；</li>
	 * <li>未绑定实体的默认 {@code SpawnData}（entity 为空 = 猪）在放置加载后被
	 *     原版写出，give 底版没有该键 → 移除；绑定过实体（SpawnData.entity 含
	 *     id/数据）则保留，放置后绑定不丢失。</li>
	 * </ol>
	 */
	private static void normalizeForStacking(CompoundTag tag) {
		tag.putShort("Delay", (short) 20);
		// 26.3：getCompound 返回 Optional
		var spawnData = tag.getCompound("SpawnData");
		if (spawnData.isPresent()) {
			var entity = spawnData.get().getCompound("entity");
			if (entity.isEmpty() || entity.get().isEmpty()) {
				tag.remove("SpawnData");
			}
		}
	}
}
