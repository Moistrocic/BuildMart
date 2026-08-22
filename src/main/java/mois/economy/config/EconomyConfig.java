package mois.economy.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mois.economy.Economy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 主配置：config/economy/config.json。
 * itemPricesInLore：物品价格以金色 lore 形式随物品数据下发给客户端（默认开启）。
 * 开启时纯净客户端也能看到价格提示，且客户端无需安装本模组；关闭时仅安装模组的客户端
 * 通过价格同步包显示提示。
 */
public final class EconomyConfig {
	public static final boolean DEFAULT_ITEM_PRICES_IN_LORE = true;

	private static boolean itemPricesInLore = DEFAULT_ITEM_PRICES_IN_LORE;

	private EconomyConfig() {
	}

	public static void load(Path configDir) {
		Path file = configDir.resolve("economy").resolve("config.json");
		try {
			Files.createDirectories(file.getParent());
			if (!Files.exists(file)) {
				writeDefault(file);
			}
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			if (root.has("itemPricesInLore")) {
				itemPricesInLore = root.get("itemPricesInLore").getAsBoolean();
			}
			Economy.LOGGER.info("主配置已加载：{}（itemPricesInLore={}）", file, itemPricesInLore);
		} catch (IOException e) {
			Economy.LOGGER.error("主配置加载失败，使用默认值", e);
		}
	}

	public static boolean itemPricesInLore() {
		return itemPricesInLore;
	}

	private static void writeDefault(Path file) throws IOException {
		JsonObject root = new JsonObject();
		root.addProperty("itemPricesInLore", DEFAULT_ITEM_PRICES_IN_LORE);
		Files.writeString(file, "{\n  \"itemPricesInLore\": true\n}\n", StandardCharsets.UTF_8);
	}
}
