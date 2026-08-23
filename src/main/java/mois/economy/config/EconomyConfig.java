package mois.economy.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mois.economy.Economy;
import mois.economy.Money;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 主配置：config/economy/config.json。
 * itemPricesInLore：物品价格以金色 lore 形式随物品数据下发给客户端（默认开启）。
 * 开启时纯净客户端也能看到价格提示，且客户端无需安装本模组；关闭时仅安装模组的客户端
 * 通过价格同步包显示提示。
 * flyFeePerSecond：付费飞行模式每秒扣费（十进制元字符串，如 "0.10"，默认 0.10 元/秒）。
 */
public final class EconomyConfig {
	public static final boolean DEFAULT_ITEM_PRICES_IN_LORE = true;
	/** 默认飞行扣费：0.10 元/秒（10 分/秒）。 */
	public static final long DEFAULT_FLY_FEE_CENTS = 10L;

	private static boolean itemPricesInLore = DEFAULT_ITEM_PRICES_IN_LORE;
	private static long flyFeeCents = DEFAULT_FLY_FEE_CENTS;

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
			if (root.has("flyFeePerSecond")) {
				try {
					flyFeeCents = parseCents(root.get("flyFeePerSecond").getAsString());
				} catch (RuntimeException e) {
					Economy.LOGGER.error("flyFeePerSecond 配置无效，使用默认 {} 元/秒", Money.format(DEFAULT_FLY_FEE_CENTS), e);
					flyFeeCents = DEFAULT_FLY_FEE_CENTS;
				}
			}
			Economy.LOGGER.info("主配置已加载：{}（itemPricesInLore={}，flyFeePerSecond={} 元/秒）",
					file, itemPricesInLore, Money.format(flyFeeCents));
		} catch (IOException e) {
			Economy.LOGGER.error("主配置加载失败，使用默认值", e);
		}
	}

	public static boolean itemPricesInLore() {
		return itemPricesInLore;
	}

	/** 付费飞行每秒扣费（分）。 */
	public static long flyFeeCents() {
		return flyFeeCents;
	}

	/** 解析非负十进制元字符串为分（最多两位小数），用于配置项。 */
	private static long parseCents(String input) {
		BigDecimal value = new BigDecimal(input.trim());
		if (value.signum() < 0) {
			throw new NumberFormatException("飞行费用不能为负");
		}
		if (value.scale() > 2) {
			throw new NumberFormatException("飞行费用最多两位小数");
		}
		return value.movePointRight(2).longValueExact();
	}

	private static void writeDefault(Path file) throws IOException {
		JsonObject root = new JsonObject();
		root.addProperty("itemPricesInLore", DEFAULT_ITEM_PRICES_IN_LORE);
		root.addProperty("flyFeePerSecond", "0.10");
		Files.writeString(file, "{\n  \"itemPricesInLore\": true,\n  \"flyFeePerSecond\": \"0.10\"\n}\n", StandardCharsets.UTF_8);
	}
}
