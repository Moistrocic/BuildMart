package mois.buildmart.test;

import mois.buildmart.shop.Shop;
import mois.buildmart.shop.ShopManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 箱子商店指令测试：/shop create|remove|setpayee|display|getprice|buy|buymode。
 * 通过"在玩家前方放箱子并让玩家瞄准"来命中依赖准星射线的子命令。
 */
public final class ShopCommandTest {
	private static final String EMPTY_STRUCTURE = "fabric-gametest-api-v1:empty";
	private static final String PAYEE = "bm_shop_payee";

	@GameTest(structure = EMPTY_STRUCTURE)
	public void shopLifecycleAndDisplay(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper).withBalance(1_000_000);
		BlockPos chest = TestWorld.placeChest(helper, new BlockPos(2, 1, 2));
		admin.standAt(TestWorld.absolute(helper, new BlockPos(0, 1, 2))).aimAt(chest);
		EconomyDbAccess.ensureAccount(PAYEE);

		admin.execute("/shop create")
				.expectVisible(true)
				.expectMessage("商店已创建");
		Shop shop = ShopManager.get(admin.level().dimension(), chest);
		admin.expectState(() -> shop != null, "商店应已注册");

		admin.execute("/shop display false")
				.expectMessage("商店悬浮信息已关闭")
				.expectState(() -> shop != null && !shop.display(), "商店 display 应为 false");
		admin.execute("/shop display true")
				.expectMessage("商店悬浮信息已开启")
				.expectState(() -> shop != null && shop.display(), "商店 display 应为 true");

		admin.execute("/shop setpayee " + PAYEE)
				.expectMessage("收款人已设置为")
				.expectState(() -> shop != null && PAYEE.equals(shop.payeeName()), "收款人应被更新");

		admin.execute("/shop getprice minecraft:stone").expectMessage("的基础价格：");
		admin.execute("/shop buy minecraft:stone 1").expectMessage("已购买");
		admin.expectState(() -> admin.hasItem(Items.STONE),
				"通过商店购买后背包应有石头");

		admin.execute("/shop buymode").expectMessage("便捷购买已开启");
		admin.execute("/shop buymode").expectMessage("已退出便捷购买");

		admin.execute("/shop remove")
				.expectMessage("商店已移除")
				.expectState(() -> ShopManager.get(admin.level().dimension(), chest) == null,
						"商店应已移除");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void shopRequiresTargetedChest(GameTestHelper helper) {
		TestPlayer player = TestPlayer.player(helper);
		// 没有对准箱子（准星指向空气/远处）
		player.execute("/shop create").expectMessage("请对准一个箱子");
		player.execute("/shop remove").expectMessage("请对准一个箱子");
		player.execute("/shop display true").expectMessage("请对准一个箱子");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void shopDisplayRejectsInvalidValue(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		BlockPos chest = TestWorld.placeChest(helper, new BlockPos(2, 1, 2));
		admin.standAt(TestWorld.absolute(helper, new BlockPos(0, 1, 2))).aimAt(chest);
		admin.execute("/shop create").expectMessage("商店已创建");
		admin.execute("/shop display maybe").expectMessage("display 需要 true 或 false");
		admin.execute("/shop remove").expectMessage("商店已移除");
		helper.succeed();
	}
}
