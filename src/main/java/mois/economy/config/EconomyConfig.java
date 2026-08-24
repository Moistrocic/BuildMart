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
 * <ul>
 * <li>itemPricesInLore：物品价格以金色 lore 形式随物品数据下发给客户端（默认开启）；</li>
 * <li>flyFeePerSecond：付费飞行模式每秒扣费（十进制元字符串，默认 500.00 元/秒）；</li>
 * <li>home：家的配置（最大数量、传送冷却、传送消耗）；</li>
 * <li>tpa：传送请求配置（开关、冷却、消耗、请求超时）；</li>
 * <li>back：死亡点传送配置（开关、冷却、消耗）。</li>
 * </ul>
 * 传送消耗规则：fixedFee 开启则固定收 fixedFeeAmount；否则同维度按
 * perDistanceFee × 距离（向上取整）收费，跨维度额外收 crossDimensionFee。
 */
public final class EconomyConfig {
	public static final boolean DEFAULT_ITEM_PRICES_IN_LORE = true;
	/** 默认飞行扣费：500.00 元/秒（50000 分/秒）。 */
	public static final long DEFAULT_FLY_FEE_CENTS = 50000L;

	// ---------- 传送默认值 ----------
	/** 默认家数量上限：0 = 未开放。 */
	public static final int DEFAULT_HOME_MAX = 0;
	/** 默认传送冷却：0 秒。 */
	public static final int DEFAULT_TP_COOLDOWN_SECONDS = 0;
	/** 默认不启用固定收费。 */
	public static final boolean DEFAULT_FIXED_FEE = false;
	/** 默认固定传送费：500.00 元。 */
	public static final long DEFAULT_FIXED_FEE_AMOUNT_CENTS = 50000L;
	/** 默认按距离收费单价：1 距离 1 元。 */
	public static final long DEFAULT_PER_DISTANCE_FEE_CENTS = 100L;
	/** 默认跨维度额外费用：1000.00 元。 */
	public static final long DEFAULT_CROSS_DIMENSION_FEE_CENTS = 100000L;
	/** 默认传送请求超时：60 秒。 */
	public static final int DEFAULT_TPA_TIMEOUT_SECONDS = 60;

	/** 传送费用/冷却公共配置。 */
	public record TpFees(int cooldownSeconds, boolean fixedFee, long fixedFeeAmountCents,
			long perDistanceFeeCents, long crossDimensionFeeCents) {
	}

	/** /home 配置。 */
	public record HomeSettings(int max, TpFees fees) {
	}

	/** /tpa /tpahere /tpaccept 配置。 */
	public record TpaSettings(boolean enabled, TpFees fees, int timeoutSeconds) {
	}

	/** /back 配置。 */
	public record BackSettings(boolean enabled, TpFees fees) {
	}

	private static final TpFees DEFAULT_FEES = new TpFees(DEFAULT_TP_COOLDOWN_SECONDS, DEFAULT_FIXED_FEE,
			DEFAULT_FIXED_FEE_AMOUNT_CENTS, DEFAULT_PER_DISTANCE_FEE_CENTS, DEFAULT_CROSS_DIMENSION_FEE_CENTS);

	private static boolean itemPricesInLore = DEFAULT_ITEM_PRICES_IN_LORE;
	private static long flyFeeCents = DEFAULT_FLY_FEE_CENTS;
	private static HomeSettings homeSettings = new HomeSettings(DEFAULT_HOME_MAX, DEFAULT_FEES);
	private static TpaSettings tpaSettings = new TpaSettings(false, DEFAULT_FEES, DEFAULT_TPA_TIMEOUT_SECONDS);
	private static BackSettings backSettings = new BackSettings(false, DEFAULT_FEES);

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
			homeSettings = readHome(root.getAsJsonObject("home"));
			tpaSettings = readTpa(root.getAsJsonObject("tpa"));
			backSettings = readBack(root.getAsJsonObject("back"));
			Economy.LOGGER.info("主配置已加载：{}（itemPricesInLore={}，flyFeePerSecond={} 元/秒，"
							+ "home.max={}，tpa.enabled={}，back.enabled={}）",
					file, itemPricesInLore, Money.format(flyFeeCents),
					homeSettings.max(), tpaSettings.enabled(), backSettings.enabled());
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

	public static HomeSettings homeSettings() {
		return homeSettings;
	}

	public static TpaSettings tpaSettings() {
		return tpaSettings;
	}

	public static BackSettings backSettings() {
		return backSettings;
	}

	// ---------- 传送配置解析 ----------

	private static HomeSettings readHome(JsonObject section) {
		if (section == null) {
			return new HomeSettings(DEFAULT_HOME_MAX, DEFAULT_FEES);
		}
		try {
			return new HomeSettings(
					section.has("max") ? section.get("max").getAsInt() : DEFAULT_HOME_MAX,
					readFees(section));
		} catch (RuntimeException e) {
			Economy.LOGGER.error("home 配置无效，使用默认值", e);
			return new HomeSettings(DEFAULT_HOME_MAX, DEFAULT_FEES);
		}
	}

	private static TpaSettings readTpa(JsonObject section) {
		if (section == null) {
			return new TpaSettings(false, DEFAULT_FEES, DEFAULT_TPA_TIMEOUT_SECONDS);
		}
		try {
			return new TpaSettings(
					section.has("enabled") && section.get("enabled").getAsBoolean(),
					readFees(section),
					section.has("timeoutSeconds") ? section.get("timeoutSeconds").getAsInt()
							: DEFAULT_TPA_TIMEOUT_SECONDS);
		} catch (RuntimeException e) {
			Economy.LOGGER.error("tpa 配置无效，使用默认值", e);
			return new TpaSettings(false, DEFAULT_FEES, DEFAULT_TPA_TIMEOUT_SECONDS);
		}
	}

	private static BackSettings readBack(JsonObject section) {
		if (section == null) {
			return new BackSettings(false, DEFAULT_FEES);
		}
		try {
			return new BackSettings(
					section.has("enabled") && section.get("enabled").getAsBoolean(),
					readFees(section));
		} catch (RuntimeException e) {
			Economy.LOGGER.error("back 配置无效，使用默认值", e);
			return new BackSettings(false, DEFAULT_FEES);
		}
	}

	/** 读取费用段（cooldownSeconds/fixedFee/fixedFeeAmount/perDistanceFee/crossDimensionFee）。 */
	private static TpFees readFees(JsonObject section) {
		int cooldown = section.has("cooldownSeconds") ? section.get("cooldownSeconds").getAsInt()
				: DEFAULT_TP_COOLDOWN_SECONDS;
		boolean fixed = section.has("fixedFee") && section.get("fixedFee").getAsBoolean();
		long fixedAmount = section.has("fixedFeeAmount")
				? parseCents(section.get("fixedFeeAmount").getAsString()) : DEFAULT_FIXED_FEE_AMOUNT_CENTS;
		long perDistance = section.has("perDistanceFee")
				? parseCents(section.get("perDistanceFee").getAsString()) : DEFAULT_PER_DISTANCE_FEE_CENTS;
		long cross = section.has("crossDimensionFee")
				? parseCents(section.get("crossDimensionFee").getAsString()) : DEFAULT_CROSS_DIMENSION_FEE_CENTS;
		return new TpFees(cooldown, fixed, fixedAmount, perDistance, cross);
	}

	/** 解析非负十进制元字符串为分（最多两位小数），用于配置项。 */
	private static long parseCents(String input) {
		BigDecimal value = new BigDecimal(input.trim());
		if (value.signum() < 0) {
			throw new NumberFormatException("金额不能为负");
		}
		if (value.scale() > 2) {
			throw new NumberFormatException("金额最多两位小数");
		}
		return value.movePointRight(2).longValueExact();
	}

	private static void writeDefault(Path file) throws IOException {
		JsonObject root = new JsonObject();
		root.addProperty("itemPricesInLore", DEFAULT_ITEM_PRICES_IN_LORE);
		root.addProperty("flyFeePerSecond", "500.00");
		Files.writeString(file, """
				{
				  "itemPricesInLore": true,
				  "flyFeePerSecond": "500.00",
				  "home": {"max": 0, "cooldownSeconds": 0, "fixedFee": false, "fixedFeeAmount": "500.00", "perDistanceFee": "1.00", "crossDimensionFee": "1000.00"},
				  "tpa": {"enabled": false, "cooldownSeconds": 0, "fixedFee": false, "fixedFeeAmount": "500.00", "perDistanceFee": "1.00", "crossDimensionFee": "1000.00", "timeoutSeconds": 60},
				  "back": {"enabled": false, "cooldownSeconds": 0, "fixedFee": false, "fixedFeeAmount": "500.00", "perDistanceFee": "1.00", "crossDimensionFee": "1000.00"}
				}
				""", StandardCharsets.UTF_8);
	}
}
