package mois.economy;

import mois.economy.buymode.BuyModeManager;
import mois.economy.command.EconomyCommands;
import mois.economy.config.EconomyConfig;
import mois.economy.config.EnchantmentValues;
import mois.economy.config.ItemValues;
import mois.economy.data.EconomyDb;
import mois.economy.fly.FlyManager;
import mois.economy.shop.ShopManager;
import mois.economy.teleport.TeleportManager;
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

public class Economy implements ModInitializer {
	public static final String MOD_ID = "economy";

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
			// 物品价值配置 + 附魔价值配置 + 主配置 + 资金数据库 + 商店数据。
			ItemValues.load(FabricLoader.getInstance().getConfigDir());
			EnchantmentValues.load(FabricLoader.getInstance().getConfigDir());
			EconomyConfig.load(FabricLoader.getInstance().getConfigDir());
			PriceLore.enabled = EconomyConfig.itemPricesInLore();
			if (PriceLore.enabled) {
				PriceLore.selfCheck();
			}
			Path worldDir = server.getWorldPath(LevelResource.ROOT);
			EconomyDb.open(worldDir.resolve("economy.db"));
			ShopManager.init(worldDir);
			// 趣味钓鱼战利品配置（需要已就绪的 RegistryAccess 解析物品）
			mois.economy.fishing.FishingManager.load(
					FabricLoader.getInstance().getConfigDir(), server.registryAccess());
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			ShopManager.save();
			// 未领取的红包作废并返还剩余金额（需在数据库关闭前执行）
			mois.economy.command.HongbaoCommands.refundAll(server);
			// 数据库管理前端随服务器一起关闭：关闭全部会话与 HTTP 服务，即使进程残留
			// （Windows 下 JVM 未完全退出）也不允许端口继续服务
			mois.economy.balop.BalopServer.shutdownAll();
			EconomyDb.close();
		});
		ServerTickEvents.END_SERVER_TICK.register(ShopManager::onServerTick);
		ServerTickEvents.END_SERVER_TICK.register(FlyManager::onServerTick);
		ServerTickEvents.END_SERVER_TICK.register(TeleportManager::onServerTick);
		ServerTickEvents.END_SERVER_TICK.register(BuyModeManager::onServerTick);

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
				LOGGER.error("读取公告失败", e);
			}
			// 保留的飞行模式恢复飞行能力
			FlyManager.onJoin(handler.getPlayer());
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
			LOGGER.info("命令注册完成（bal/pay/baltop/balhelp/announcement/eco/shop/price/buy/bm/fly/home/sethome/delhome/listhome/tpa/tpahere/tpaccept/back/suicide/hongbao/config）");
		});

		LOGGER.info("Economy Mod Loaded!");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
