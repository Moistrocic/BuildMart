package mois.buildmart.test;

import mois.buildmart.buymode.BuyModeManager;
import mois.buildmart.config.EconomyConfig;
import mois.buildmart.data.EconomyDb;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Items;

import java.util.UUID;

/**
 * 经济类指令测试：/bal /baltop /eco add|remove|set /pay /price /buy /buypack
 * /bm /fastbuy /fly /fly warn /hongbao /announcement /suicide。
 * <p>
 * 断言口径：明显的可观测结果——指令提示文本、账户余额变化、背包物品、开关状态。
 */
public final class EconomyCommandTest {
	private static final String EMPTY_STRUCTURE = "fabric-gametest-api-v1:empty";

	@GameTest(structure = EMPTY_STRUCTURE)
	public void balanceAndTop(GameTestHelper helper) {
		TestPlayer player = TestPlayer.player(helper).withBalance(12345); // 123.45 元
		player.execute("/bal")
				.expectVisible(true)
				.expectMessage("你的资金：")
				.expectMessage("123.45");
		player.execute("/baltop").expectMessage("资金排行榜");
		player.execute("/baltop 1").expectMessage("资金排行榜 第 1 页");
		player.execute("/baltop 999").expectMessage("页码超出范围，共 ");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void ecoAddRemoveSet(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		String targetName = "bm_eco_target";
		UUID target = EconomyDbAccess.offlineUuid(targetName);
		long before = EconomyDbAccess.balance(target);

		admin.execute("/eco add " + targetName + " 10.00").expectMessage("增加");
		admin.expectState(() -> EconomyDbAccess.balance(target) == before + 1000L,
				"eco add 后余额应增加 1000 分");

		admin.execute("/eco remove " + targetName + " 4.00").expectMessage("扣除");
		admin.expectState(() -> EconomyDbAccess.balance(target) == before + 600L,
				"eco remove 后余额应为 +600 分");

		admin.execute("/eco set " + targetName + " 1.00").expectMessage("资金设置为");
		admin.expectState(() -> EconomyDbAccess.balance(target) == 100L, "eco set 后余额应为 100 分");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void payTransfersMoney(GameTestHelper helper) {
		TestPlayer player = TestPlayer.player(helper).withBalance(10000); // 100.00 元
		String targetName = "bm_pay_target";
		UUID target = EconomyDbAccess.offlineUuid(targetName);
		long targetBefore = EconomyDbAccess.balance(target);

		player.execute("/pay " + targetName + " 3.00").expectMessage("转账");
		player.expectState(() -> EconomyDbAccess.balance(target) == targetBefore + 300L,
				"收款方应增加 300 分");
		player.expectState(() -> EconomyDbAccess.balance(player.player()) == 9700L,
				"付款方余额应减少 300 分");

		player.execute("/pay " + targetName + " 99999.00").expectMessage("资金不足");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void priceBuyAndBuypack(GameTestHelper helper) {
		TestPlayer player = TestPlayer.player(helper).withBalance(1_000_000); // 10000 元

		player.execute("/price minecraft:stone")
				.expectVisible(true)
				.expectMessage("的基础价格：");

		long before = EconomyDbAccess.balance(player.player());
		player.execute("/buy minecraft:stone 1").expectMessage("已购买");
		player.expectState(() -> player.hasItem(Items.STONE), "购买后背包应有石头");
		player.expectState(() -> EconomyDbAccess.balance(player.player()) < before,
				"购买后余额应减少");

		player.execute("/buypack minecraft:stone 1").expectMessage("已购买");
		player.expectState(() -> player.hasItem(Items.SHULKER_BOX), "购买整盒后背包应有潜影盒");

		player.execute("/price minecraft:bedrock").expectMessage("不可购买或出售");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void buyModeAndFastbuyToggles(GameTestHelper helper) {
		TestPlayer player = TestPlayer.player(helper);

		player.execute("/bm")
				.expectVisible(true)
				.expectMessage("便捷购买已开启")
				.expectState(() -> BuyModeManager.isActive(player.player()), "bm 开启后应为激活状态");
		player.execute("/bm")
				.expectMessage("已退出便捷购买")
				.expectState(() -> !BuyModeManager.isActive(player.player()), "bm 再次执行应退出");

		player.execute("/fastbuy").expectMessage("快速投影购买已开启");
		player.expectState(EconomyConfig::fastbuy, "fastbuy 开关应为 true");
		player.execute("/fastbuy").expectMessage("快速投影购买已关闭");
		player.expectState(() -> !EconomyConfig.fastbuy(), "fastbuy 开关应为 false");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void flyTogglesWithBalance(GameTestHelper helper) {
		TestPlayer player = TestPlayer.player(helper).withBalance(1_000_000);

		player.execute("/fly")
				.expectVisible(true)
				.expectMessage("飞行模式已开启");
		player.execute("/fly").expectMessage("飞行模式已关闭");

		player.execute("/fly warn").expectMessage("飞行余额不足提醒已关闭");
		player.execute("/fly warn").expectMessage("飞行余额不足提醒已开启");

		TestPlayer poor = TestPlayer.player(helper);
		poor.execute("/fly").expectMessage("你的资金不足");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void hongbaoAndAnnouncement(GameTestHelper helper) {
		TestPlayer admin = TestPlayer.admin(helper);
		TestPlayer player = TestPlayer.player(helper).withBalance(1000); // 10.00 元

		// 红包：发出后余额减少
		long before = EconomyDbAccess.balance(player.player());
		player.execute("/hongbao 1.00 1 bmtest").expectMessage("红包");
		player.expectState(() -> EconomyDbAccess.balance(player.player()) == before - 100L,
				"发红包后余额应减少 100 分");
		player.execute("/hongbao 1.00 200 bmtest2").expectMessage("总金额不足以分成");

		// 公告：设置 / 清除
		admin.execute("/announcement 测试公告")
				.expectMessage("公告已设置")
				.expectState(() -> "测试公告".equals(EconomyDb.getAnnouncement()), "公告应写入数据库");
		admin.execute("/announcement clear")
				.expectMessage("公告已清除")
				.expectState(() -> {
					String value = EconomyDb.getAnnouncement();
					return value == null || value.isEmpty();
				}, "公告应被清除");
		helper.succeed();
	}

	@GameTest(structure = EMPTY_STRUCTURE)
	public void suicidePlayerAndConsole(GameTestHelper helper) {
		TestPlayer console = TestPlayer.console(helper);
		console.executeExpectFailure("/suicide", "该指令只能由玩家执行");

		TestPlayer player = TestPlayer.player(helper);
		player.execute("/suicide")
				.expectVisible(true)
				.expectState(() -> player.player().isDeadOrDying() || player.player().getHealth() <= 0.0F,
						"/suicide 后玩家应死亡");
		helper.succeed();
	}
}
