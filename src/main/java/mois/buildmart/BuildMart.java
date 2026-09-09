package mois.buildmart;

import mois.buildmart.buymode.BuyModeManager;
import mois.buildmart.command.EconomyCommands;
import mois.buildmart.config.EconomyConfig;
import mois.buildmart.config.EnchantmentValues;
import mois.buildmart.config.ItemValues;
import mois.buildmart.data.EconomyDb;
import mois.buildmart.fly.FlyManager;
import mois.buildmart.shop.ShopManager;
import mois.buildmart.teleport.TeleportManager;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;

/**
 * BuildMart（原名 Economy）模组主入口。
 * 定位：为不想玩生电（红石自动化）但喜欢建筑的玩家打造的生存整合——
 * 经济市场、箱子商店、便捷购买、刷怪笼生产（直接转化/自动出售/漏斗）、
 * 付费飞行、传送、红包、趣味钓鱼、数据库管理前端等，让玩家用「钱」替代
 * 「红石」解决资源与自动化问题；快速投影购买（/fastbuy）是建筑党增强。
 * <p>
 * 数据兼容说明（改名自 economy，保留旧资源名避免破坏存量数据）：
 * 资金数据库文件仍为 world/economy.db、配置目录仍为 config/economy/、
 * 数据库表名仍为 economy_accounts / economy_transactions。
 */
public class BuildMart implements ModInitializer {
	public static final String MOD_ID = "buildmart";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			// 物品价值配置 + 附魔价值配置 + 主配置 + 刷怪笼分级配置 + 资金数据库 + 商店数据。
			ItemValues.load(FabricLoader.getInstance().getConfigDir());
			EnchantmentValues.load(FabricLoader.getInstance().getConfigDir());
			EconomyConfig.load(FabricLoader.getInstance().getConfigDir());
			mois.buildmart.config.SpawnerConfig.load(FabricLoader.getInstance().getConfigDir());
			PriceLore.enabled = EconomyConfig.itemPricesInLore();
			if (PriceLore.enabled) {
				PriceLore.selfCheck();
			}
			Path worldDir = server.getWorldPath(LevelResource.ROOT);
			// 数据库文件名保留 economy.db（改名兼容，避免旧资金数据丢失）
			EconomyDb.open(worldDir.resolve("economy.db"));
			ShopManager.init(worldDir);
			// 趣味钓鱼战利品配置（需要已就绪的 RegistryAccess 解析物品）
			mois.buildmart.fishing.FishingManager.load(
					FabricLoader.getInstance().getConfigDir(), server.registryAccess());
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			ShopManager.save();
			// 未领取的红包作废并返还剩余金额（需在数据库关闭前执行）
			mois.buildmart.command.HongbaoCommands.refundAll(server);
			// 数据库管理前端随服务器一起关闭：关闭全部会话与 HTTP 服务，即使进程残留
			// （Windows 下 JVM 未完全退出）也不允许端口继续服务
			mois.buildmart.balop.BalopServer.shutdownAll();
			EconomyDb.close();
		});
		ServerTickEvents.END_SERVER_TICK.register(ShopManager::onServerTick);
		ServerTickEvents.END_SERVER_TICK.register(FlyManager::onServerTick);
		ServerTickEvents.END_SERVER_TICK.register(TeleportManager::onServerTick);
		ServerTickEvents.END_SERVER_TICK.register(BuyModeManager::onServerTick);

		// 快速投影购买：注册 C2S payload（/fastbuy 关闭时 handler 直接忽略）
		mois.buildmart.fastbuy.FastbuyManager.register();

		// 玩家进入服务器时：同步名字到数据库（首次进服自动建行），并发送红色公告。
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			try {
				// 同步玩家名字到数据库：首次进入自动建行，并修复历史上被覆盖为“未知玩家”的名字。
				EconomyDb.ensureAccount(handler.getPlayer().getUUID(),
						handler.getPlayer().getGameProfile().name());
				String announcement = EconomyDb.getAnnouncement();
				if (announcement != null && !announcement.isEmpty()) {
					handler.getPlayer().sendSystemMessage(
							Component.literal(announcement).withStyle(ChatFormatting.RED), false);
				}
			} catch (EconomyDb.DatabaseException e) {
				LOGGER.warn("读取公告失败", e);
			}
			// 保留的飞行模式恢复飞行能力
			FlyManager.onJoin(handler.getPlayer());
		});
		// 刷怪笼玩法：手持刷怪蛋右键**带标签**的刷怪笼绑定实体类型（服务端事件，纯净端兼容）；
		// 原版刷怪笼一律 PASS——不干预原版右键行为（原版对刷怪笼使用刷怪蛋照常生效）
		net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
			if (world.isClientSide() || !(player instanceof net.minecraft.server.level.ServerPlayer serverPlayer)) {
				return net.minecraft.world.InteractionResult.PASS;
			}
			if (!(world.getBlockEntity(hitResult.getBlockPos())
					instanceof net.minecraft.world.level.block.entity.SpawnerBlockEntity spawner)) {
				return net.minecraft.world.InteractionResult.PASS;
			}
			if (!((mois.buildmart.spawner.SpawnerStateAccess) spawner).economyTagged()) {
				return net.minecraft.world.InteractionResult.PASS; // 原版刷怪笼：不干预
			}
			net.minecraft.world.item.ItemStack held = player.getItemInHand(hand);
			if (!(held.getItem() instanceof net.minecraft.world.item.SpawnEggItem)) {
				return net.minecraft.world.InteractionResult.PASS;
			}
			String error = mois.buildmart.spawner.SpawnerManager.bindWithEgg(serverPlayer, spawner, held);
			if (error != null) {
				serverPlayer.sendSystemMessage(net.minecraft.network.chat.Component.literal(error)
						.withStyle(net.minecraft.ChatFormatting.RED), false);
				return net.minecraft.world.InteractionResult.FAIL;
			}
			return net.minecraft.world.InteractionResult.SUCCESS;
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			// 下线：清除背包与打开容器的价格标签，退出便捷购买模式，停止飞行扣费（保留飞行模式）
			if (handler.getPlayer() != null) {
				PriceLore.untagPlayerAndMenu(handler.getPlayer());
				FlyManager.onDisconnect(handler.getPlayer());
			}
			BuyModeManager.exit(handler.getPlayer());
		});

		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
			EconomyCommands.register(dispatcher, buildContext);
			LOGGER.info("命令注册完成（bal/pay/baltop/bmhelp/announcement/eco/shop/price/buy/bm（含 bm config）/fastbuy/fly/home/sethome/delhome/listhome/tpa/tpahere/tpaccept/back/suicide/hongbao/spawner/weather/time 权限放宽/fixweather/fixtime/naturalmonsterspawn）");
		});

		LOGGER.info("BuildMart Loaded!");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
