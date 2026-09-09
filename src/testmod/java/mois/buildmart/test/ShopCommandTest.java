package mois.buildmart.test;

import mois.buildmart.config.ItemValues;
import mois.buildmart.shop.Shop;
import mois.buildmart.shop.ShopManager;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

/**
 * 箱子商店指令测试：/shop create|remove|setpayee|display|getprice|buy|buymode。
 * 通过"在玩家前方放箱子并让玩家瞄准"来命中依赖准星射线的子命令；
 * 并覆盖跨玩家场景（他人操作我的商店被拒、管理员越权、顾客放货给店主结算）。
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

	/** 跨玩家归属：非所有人不能改/删他人商店，管理员可越权（与 /spawner 规则一致）。 */
	@GameTest(structure = EMPTY_STRUCTURE)
	public void crossPlayerShopOwnership(GameTestHelper helper) {
		TestPlayer owner = TestPlayer.player(helper);
		TestPlayer other = TestPlayer.player(helper);
		TestPlayer admin = TestPlayer.admin(helper);
		EconomyDbAccess.ensureAccount(PAYEE);
		BlockPos chest = TestWorld.placeChest(helper, new BlockPos(2, 1, 2));
		owner.standAt(TestWorld.absolute(helper, new BlockPos(0, 1, 2))).aimAt(chest);
		owner.execute("/shop create").expectMessage("商店已创建");
		Shop shop = ShopManager.get(owner.level().dimension(), chest);
		owner.expectState(() -> shop != null && shop.owner().equals(owner.player().getUUID()),
				"商店所有人应为创建者");

		// 非所有人对准他人箱子：只读命令外的操作一律拒绝，且状态不变
		other.standAt(TestWorld.absolute(helper, new BlockPos(0, 1, 3))).aimAt(chest);
		other.execute("/shop create").expectMessage("该箱子已经是商店");
		other.execute("/shop remove").expectMessage("只能操作自己的商店");
		other.execute("/shop display false").expectMessage("只能操作自己的商店");
		other.execute("/shop setpayee " + PAYEE).expectMessage("只能操作自己的商店");
		other.expectState(() -> shop.display() && shop.payee().equals(owner.player().getUUID()),
				"他人不应改动商店 display/收款人");
		other.expectState(() -> ShopManager.get(other.level().dimension(), chest) != null,
				"他人不应移除商店");

		// 管理员：可越权改他人商店（isAdmin 放行）
		admin.standAt(TestWorld.absolute(helper, new BlockPos(0, 1, 3))).aimAt(chest);
		admin.execute("/shop display false").expectMessage("商店悬浮信息已关闭");
		admin.expectState(() -> !shop.display(), "管理员应能改他人商店 display");
		admin.execute("/shop remove").expectMessage("商店已移除");
		helper.succeed();
	}

	/**
	 * 跨玩家结算：顾客（非所有人）往他人商店箱子里放货，出售后钱进**店主的收款人账户**，
	 * 顾客本人不入账（商店模型 = 往箱子放货，由 ShopManager 周期出售）。
	 */
	@GameTest(structure = EMPTY_STRUCTURE)
	public void customerDepositPaysOwner(GameTestHelper helper) {
		TestPlayer owner = TestPlayer.player(helper).withBalance(0);
		TestPlayer customer = TestPlayer.player(helper).withBalance(0);
		BlockPos chest = TestWorld.placeChest(helper, new BlockPos(2, 1, 2));
		owner.standAt(TestWorld.absolute(helper, new BlockPos(0, 1, 2))).aimAt(chest);
		owner.execute("/shop create").expectMessage("商店已创建");
		Shop shop = ShopManager.get(owner.level().dimension(), chest);
		owner.expectState(() -> shop != null, "商店应已注册");

		long before = EconomyDbAccess.balance(owner.player());
		long price = ItemValues.price(new ItemStack(Items.STONE, 3));
		ChestBlockEntity container = (ChestBlockEntity) owner.level().getBlockEntity(chest);
		container.setItem(0, new ItemStack(Items.STONE, 3));
		shop.setRemainingTicks(0);

		// 每 tick 推进商店结算（onServerTick 内部按 tickCount%20 节流），结算完成后断言并清理
		helper.succeedWhen(() -> {
			ShopManager.onServerTick(owner.level().getServer());
			if (EconomyDbAccess.balance(owner.player()) != before + price) {
				helper.fail("顾客放货后店主（收款人）应收到 " + price + " 分");
			}
			if (!container.isEmpty()) {
				helper.fail("出售后箱子应被清空");
			}
			if (EconomyDbAccess.balance(customer.player()) != 0) {
				helper.fail("顾客不应因往他人商店放货而获得资金");
			}
			ShopManager.remove(shop, owner.level());
		});
	}
}
