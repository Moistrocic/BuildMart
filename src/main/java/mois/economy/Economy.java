package mois.economy;

import mois.economy.command.EconomyCommands;
import mois.economy.data.EconomyDb;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;

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

		// 资金数据库存放在世界存档目录下（独立服务端与单人游戏内置服务器均生效）。
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			Path dbPath = server.getWorldPath(LevelResource.ROOT).resolve("economy.db");
			EconomyDb.open(dbPath);
		});
		ServerLifecycleEvents.SERVER_STOPPING.register(server -> EconomyDb.close());

		CommandRegistrationCallback.EVENT.register((dispatcher, buildContext, selection) -> {
			EconomyCommands.register(dispatcher);
			LOGGER.info("命令注册完成（bal/pbal/pay/baltop/balhelp）");
		});

		LOGGER.info("Hello Fabric world!");
	}

	public static Identifier id(String path) {
		return Identifier.fromNamespaceAndPath(MOD_ID, path);
	}
}
