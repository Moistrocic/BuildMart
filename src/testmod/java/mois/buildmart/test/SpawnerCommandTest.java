package mois.buildmart.test;

import mois.buildmart.spawner.SpawnerStateAccess;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;

/**
 * 刷怪笼指令测试：/spawner give|info|set|hopper add|list|remove|take|upgrade。
 * 需要玩家瞄准带标签刷怪笼的子命令通过"前方放置带标签刷怪笼"来命中。
 */
public final class SpawnerCommandTest {
	private static final String EMPTY_STRUCTURE = "fabric-gametest-api-v1:empty";

	@GameTest(structure = EMPTY_STRUCTURE)
	public void spawnerGiveAndInfo(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);

		admin.execute("/spawner give")
				.expectVisible(true)
				.expectMessage("已获得带标签的刷怪笼");
		admin.expectState(() -> {
			for (int i = 0; i < admin.player().getInventory().getContainerSize(); i++) {
				ItemStack stack = admin.player().getInventory().getItem(i);
				if (stack.is(Items.SPAWNER) && stack.get(DataComponents.BLOCK_ENTITY_DATA) != null) {
					return true;
				}
			}
			return false;
		}, "背包中应有带 BLOCK_ENTITY_DATA 的刷怪笼");

		// 未对准刷怪笼
		admin.execute("/spawner info").expectMessage("请对准刷怪笼");
		admin.execute("/spawner upgrade").expectMessage("请对准刷怪笼");
		admin.execute("/spawner take").expectMessage("请对准刷怪笼");
		admin.execute("/spawner set display false").expectMessage("请对准刷怪笼");
		admin.execute("/spawner hopper add minecraft:stone").expectMessage("请对准刷怪笼");
		admin.execute("/spawner hopper list").expectMessage("请对准刷怪笼");

		// 对准带标签刷怪笼：info 显示信息
		SpawnerBlockEntity spawner = TestWorld.placeTaggedSpawner(helper, new BlockPos(2, 1, 2));
		admin.standAt(TestWorld.absolute(helper, new BlockPos(0, 1, 2))).aimAt(spawner.getBlockPos());
		admin.execute("/spawner info")
				.expectVisible(true)
				.expectMessage("刷怪笼：")
				.expectMessage("所有者：");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void spawnerSetAndHopper(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		SpawnerBlockEntity spawner = TestWorld.placeTaggedSpawner(helper, new BlockPos(2, 1, 2));
		admin.standAt(TestWorld.absolute(helper, new BlockPos(0, 1, 2))).aimAt(spawner.getBlockPos());
		SpawnerStateAccess state = (SpawnerStateAccess) spawner;

		admin.execute("/spawner set display false")
				.expectMessage("已关闭悬浮信息")
				.expectState(() -> !state.economyDisplay(), "display 应为 false");
		admin.execute("/spawner set display true")
				.expectMessage("已开启悬浮信息")
				.expectState(state::economyDisplay, "display 应为 true");

		admin.execute("/spawner set autoconvert true")
				.expectMessage("已开启直接转化")
				.expectState(state::economyAutoConvert, "autoconvert 应为 true");
		admin.execute("/spawner set autoconvert false")
				.expectMessage("已关闭直接转化");

		admin.execute("/spawner hopper add minecraft:stone")
				.expectMessage("已添加漏斗白名单")
				.expectState(() -> state.economyHopperWhitelist().contains("minecraft:stone"),
						"白名单应包含石头");
		admin.execute("/spawner hopper list").expectMessage("漏斗白名单列表");
		admin.execute("/spawner hopper remove minecraft:stone")
				.expectMessage("已移除漏斗白名单")
				.expectState(() -> !state.economyHopperWhitelist().contains("minecraft:stone"),
						"白名单应移除石头");

		admin.execute("/spawner take").expectMessage("没有存储的转化掉落物");
		admin.execute("/spawner upgrade").expectMessage("尚未绑定实体类型");
		helper.succeed();
	}
}
