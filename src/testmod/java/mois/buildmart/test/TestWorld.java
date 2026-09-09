package mois.buildmart.test;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.TypedEntityData;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;

/**
 * 测试用世界辅助（仅测试源集）：在 gametest 结构内（相对坐标）放置箱子/带标签刷怪笼等，
 * 让依赖准星射线（/shop、/spawner）的指令可以命中。
 * <p>
 * 注意必须用 {@code helper.setBlock(相对坐标)} 在结构内布置，并用 {@link #absolute} 取绝对坐标；
 * 直接写到结构外的绝对坐标会落在普通地形（实心方块）里，导致准星射线先命中地形。
 */
public final class TestWorld {
	private TestWorld() {
	}

	/** 结构内相对坐标 → 绝对坐标。 */
	public static BlockPos absolute(GameTestHelper helper, BlockPos relative) {
		return helper.absolutePos(relative);
	}

	/** 在结构内放置箱子，返回绝对坐标。 */
	public static BlockPos placeChest(GameTestHelper helper, BlockPos relative) {
		helper.setBlock(relative, Blocks.CHEST);
		return helper.absolutePos(relative);
	}

	/** 在结构内放置**带标签**刷怪笼（本模组玩法笼），返回方块实体。 */
	public static SpawnerBlockEntity placeTaggedSpawner(GameTestHelper helper, BlockPos relative) {
		helper.setBlock(relative, Blocks.SPAWNER);
		BlockPos absolute = helper.absolutePos(relative);
		ServerLevel level = helper.getLevel();
		if (!(level.getBlockEntity(absolute) instanceof SpawnerBlockEntity spawner)) {
			throw new IllegalStateException("刷怪笼方块实体未创建：" + absolute);
		}
		ItemStack tagged = mois.buildmart.spawner.SpawnerManager
				.createTaggedSpawnerStack(level.registryAccess());
		TypedEntityData<?> data = tagged.get(DataComponents.BLOCK_ENTITY_DATA);
		if (data == null) {
			throw new IllegalStateException("带标签刷怪笼物品缺少 BLOCK_ENTITY_DATA");
		}
		data.loadInto(spawner, level.registryAccess());
		spawner.setChanged();
		return spawner;
	}

	/** 指令节点对给定命令源是否可用（客户端指令树 RESTRICTED 判定的直接依据）。 */
	public static boolean canUseNode(CommandDispatcher<CommandSourceStack> dispatcher,
			String node, CommandSourceStack source) {
		var child = dispatcher.getRoot().getChild(node);
		return child != null && child.canUse(source);
	}
}
