package mois.buildmart.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mois.buildmart.BuildMart;
import mois.buildmart.Money;
import mois.buildmart.PriceLore;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主配置：config/economy/config.json。
 * <ul>
 * <li>itemPricesInLore：物品价格以金色 lore 形式随物品数据下发给客户端（默认开启）；</li>
 * <li>fly：付费飞行配置段（feePerSecond 每秒扣费、digNoSlow 飞行挖掘不减速开关）；</li>
 * <li>shop：商店配置段（sellLog 出售结算日志开关）；</li>
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
	/** 默认趣味钓鱼开关：关闭（使用原版钓鱼战利品）。 */
	public static final boolean DEFAULT_FUN_FISHING = false;
	/** 默认飞行挖掘不减速：开启（true=不减速，飞行中挖掘与地面一致；false=原版生存飞行挖掘速度）。 */
	public static final boolean DEFAULT_FLY_DIG_NO_SLOW = true;
	/** 默认商店出售结算日志：关闭（每店每 60 秒一条，过多影响后台观感）。 */
	public static final boolean DEFAULT_SHOP_SELL_LOG = false;
	/** 默认刷怪笼升级功能：开启（关闭时禁止升级，已升级效果按 Lv 1 生成，数据保留）。 */
	public static final boolean DEFAULT_SPAWNER_UPGRADE = true;
	/** 默认快速投影购买（/fastbuy）：关闭（关闭时不处理任何 fastbuy 请求，不修改任何逻辑）。 */
	public static final boolean DEFAULT_FASTBUY = false;
	/** 默认数据库管理前端（/balop）监听地址：本机回环，避免暴露到网络。 */
	public static final String DEFAULT_BALOP_HOST = "localhost";
	/** 默认数据库管理前端（/balop）端口。 */
	public static final int DEFAULT_BALOP_PORT = 8899;
	/** 默认部分规则调整权限：关闭（开启后玩家可使用 /weather /time /fixweather /fixtime /naturalmonsterspawn）。 */
	public static final boolean DEFAULT_PARTIAL_ADJUST = false;
	/** 默认 /balop start 展示域名：空 = 未设置（链接直接使用 balop.host + balop.port）。 */
	public static final String DEFAULT_BALOP_DOMAIN = "";

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
	private static boolean funFishing = DEFAULT_FUN_FISHING;
	private static boolean flyDigNoSlow = DEFAULT_FLY_DIG_NO_SLOW;
	private static boolean shopSellLog = DEFAULT_SHOP_SELL_LOG;
	private static boolean spawnerUpgrade = DEFAULT_SPAWNER_UPGRADE;
	private static boolean fastbuy = DEFAULT_FASTBUY;
	private static String balopHost = DEFAULT_BALOP_HOST;
	private static int balopPort = DEFAULT_BALOP_PORT;
	private static boolean partialAdjust = DEFAULT_PARTIAL_ADJUST;
	private static String balopDomain = DEFAULT_BALOP_DOMAIN;
	private static HomeSettings homeSettings = new HomeSettings(DEFAULT_HOME_MAX, DEFAULT_FEES);
	private static TpaSettings tpaSettings = new TpaSettings(false, DEFAULT_FEES, DEFAULT_TPA_TIMEOUT_SECONDS);
	private static BackSettings backSettings = new BackSettings(false, DEFAULT_FEES);

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** 配置项描述：类型（bool/int/money）与说明（用于 /bm config 补全与校验）。 */
	private record Entry(String type, String description) {
	}

	/** /bm config 可修改的全部配置项（key -> 类型/说明）。 */
	private static final Map<String, Entry> ENTRIES = buildEntries();

	private static Map<String, Entry> buildEntries() {
		Map<String, Entry> map = new HashMap<>(24);
		map.put("itemPricesInLore", new Entry("bool", "物品价格 lore 显示开关"));
		map.put("fly.feePerSecond", new Entry("money", "付费飞行每秒扣费（元）"));
		map.put("funFishing", new Entry("bool", "趣味钓鱼开关（关闭=原版钓鱼战利品）"));
		map.put("fly.digNoSlow", new Entry("bool", "飞行挖掘不减速开关（true=与地面一致，false=原版生存飞行挖掘速度）"));
		map.put("shop.sellLog", new Entry("bool", "商店出售结算日志开关（默认关闭，开启后每店每 60 秒一条）"));
		map.put("spawner.upgrade", new Entry("bool", "刷怪笼升级功能开关（关闭时禁止升级，已升级效果按 Lv 1 生成，数据保留）"));
		map.put("shop.fastbuy", new Entry("bool", "快速投影购买开关（投影中键拾取无物品时自动购买一组；需客户端安装本模组）"));
		map.put("balop.host", new Entry("string", "数据库管理前端监听地址（/balop 重启后生效）"));
		map.put("balop.port", new Entry("int", "数据库管理前端端口 1-65535（/balop 重启后生效）"));
		map.put("balop.domain", new Entry("string", "/balop start 链接展示域名（非空时纯文本替换 balop.host 展示；留空用 balop.host+balop.port）"));
		map.put("rule.partialAdjust", new Entry("bool", "部分规则调整权限（true=允许玩家使用 /weather /time /fixweather /fixtime /naturalmonsterspawn）"));
		map.put("home.max", new Entry("int", "家数量上限（0 = 未开放）"));
		map.put("home.cooldownSeconds", new Entry("int", "回家冷却（秒）"));
		map.put("home.fixedFee", new Entry("bool", "回家固定收费开关"));
		map.put("home.fixedFeeAmount", new Entry("money", "回家固定收费金额（元）"));
		map.put("home.perDistanceFee", new Entry("money", "回家按距离单价（元/距离）"));
		map.put("home.crossDimensionFee", new Entry("money", "回家跨维度附加费（元）"));
		map.put("tpa.enabled", new Entry("bool", "tpa 请求开关"));
		map.put("tpa.cooldownSeconds", new Entry("int", "tpa 冷却（秒）"));
		map.put("tpa.fixedFee", new Entry("bool", "tpa 固定收费开关"));
		map.put("tpa.fixedFeeAmount", new Entry("money", "tpa 固定收费金额（元）"));
		map.put("tpa.perDistanceFee", new Entry("money", "tpa 按距离单价（元/距离）"));
		map.put("tpa.crossDimensionFee", new Entry("money", "tpa 跨维度附加费（元）"));
		map.put("tpa.timeoutSeconds", new Entry("int", "tpa 请求超时（秒）"));
		map.put("back.enabled", new Entry("bool", "/back 开关"));
		map.put("back.cooldownSeconds", new Entry("int", "/back 冷却（秒）"));
		map.put("back.fixedFee", new Entry("bool", "/back 固定收费开关"));
		map.put("back.fixedFeeAmount", new Entry("money", "/back 固定收费金额（元）"));
		map.put("back.perDistanceFee", new Entry("money", "/back 按距离单价（元/距离）"));
		map.put("back.crossDimensionFee", new Entry("money", "/back 跨维度附加费（元）"));
		return map;
	}

	// ---------- /bm config 热重载支持 ----------

	/** 全部配置项 key（补全用）。 */
	public static List<String> configKeys() {
		return new ArrayList<>(ENTRIES.keySet());
	}

	/** 配置项类型（bool/int/money），未知 key 返回 null（值补全用）。 */
	public static String configType(String key) {
		Entry entry = ENTRIES.get(key);
		return entry != null ? entry.type() : null;
	}

	/** 当前值（字符串形式），未知 key 返回 null。 */
	public static String getValue(String key) {
		switch (key) {
			case "itemPricesInLore" -> {
				return Boolean.toString(itemPricesInLore);
			}
			case "fly.feePerSecond" -> {
				return Money.format(flyFeeCents);
			}
			case "funFishing" -> {
				return Boolean.toString(funFishing);
			}
			case "fly.digNoSlow" -> {
				return Boolean.toString(flyDigNoSlow);
			}
			case "shop.sellLog" -> {
				return Boolean.toString(shopSellLog);
			}
			case "spawner.upgrade" -> {
				return Boolean.toString(spawnerUpgrade);
			}
			case "shop.fastbuy" -> {
				return Boolean.toString(fastbuy);
			}
			case "balop.host" -> {
				return balopHost;
			}
			case "balop.port" -> {
				return String.valueOf(balopPort);
			}
			case "balop.domain" -> {
				return balopDomain;
			}
			case "rule.partialAdjust" -> {
				return Boolean.toString(partialAdjust);
			}
			case "home.max" -> {
				return String.valueOf(homeSettings.max());
			}
			case "home.cooldownSeconds" -> {
				return String.valueOf(homeSettings.fees().cooldownSeconds());
			}
			case "home.fixedFee" -> {
				return Boolean.toString(homeSettings.fees().fixedFee());
			}
			case "home.fixedFeeAmount" -> {
				return Money.format(homeSettings.fees().fixedFeeAmountCents());
			}
			case "home.perDistanceFee" -> {
				return Money.format(homeSettings.fees().perDistanceFeeCents());
			}
			case "home.crossDimensionFee" -> {
				return Money.format(homeSettings.fees().crossDimensionFeeCents());
			}
			case "tpa.enabled" -> {
				return Boolean.toString(tpaSettings.enabled());
			}
			case "tpa.cooldownSeconds" -> {
				return String.valueOf(tpaSettings.fees().cooldownSeconds());
			}
			case "tpa.fixedFee" -> {
				return Boolean.toString(tpaSettings.fees().fixedFee());
			}
			case "tpa.fixedFeeAmount" -> {
				return Money.format(tpaSettings.fees().fixedFeeAmountCents());
			}
			case "tpa.perDistanceFee" -> {
				return Money.format(tpaSettings.fees().perDistanceFeeCents());
			}
			case "tpa.crossDimensionFee" -> {
				return Money.format(tpaSettings.fees().crossDimensionFeeCents());
			}
			case "tpa.timeoutSeconds" -> {
				return String.valueOf(tpaSettings.timeoutSeconds());
			}
			case "back.enabled" -> {
				return Boolean.toString(backSettings.enabled());
			}
			case "back.cooldownSeconds" -> {
				return String.valueOf(backSettings.fees().cooldownSeconds());
			}
			case "back.fixedFee" -> {
				return Boolean.toString(backSettings.fees().fixedFee());
			}
			case "back.fixedFeeAmount" -> {
				return Money.format(backSettings.fees().fixedFeeAmountCents());
			}
			case "back.perDistanceFee" -> {
				return Money.format(backSettings.fees().perDistanceFeeCents());
			}
			case "back.crossDimensionFee" -> {
				return Money.format(backSettings.fees().crossDimensionFeeCents());
			}
			default -> {
				return null;
			}
		}
	}

	/**
	 * 热重载：按 key 修改内存配置（即时生效，需调用方保存到文件）。
	 * 返回 null 表示成功；否则返回错误提示。
	 */
	public static String apply(String key, String value) {
		switch (key) {
			case "itemPricesInLore" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "itemPricesInLore 需要 true 或 false";
				}
				itemPricesInLore = b;
				PriceLore.enabled = b;
				return null;
			}
			case "fly.feePerSecond" -> {
				Long cents = parseMoney(value);
				if (cents == null) {
					return "fly.feePerSecond 需要非负金额（如 500.00）";
				}
				flyFeeCents = cents;
				return null;
			}
			case "funFishing" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "funFishing 需要 true 或 false";
				}
				funFishing = b;
				return null;
			}
			case "fly.digNoSlow" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "fly.digNoSlow 需要 true 或 false";
				}
				flyDigNoSlow = b;
				// 服务端权威值；下一 tick FlyManager.syncDigBoost 按新值增删属性修改器，
				// 属性变化由原版机制自动同步客户端（无需自定义网络包）
				return null;
			}
			case "shop.sellLog" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "shop.sellLog 需要 true 或 false";
				}
				shopSellLog = b;
				return null;
			}
			case "spawner.upgrade" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "spawner.upgrade 需要 true 或 false";
				}
				spawnerUpgrade = b;
				// 下一 tick 刷怪笼按新状态计算生效参数（关闭=按 Lv 1，数据保留）
				return null;
			}
			case "shop.fastbuy" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "shop.fastbuy 需要 true 或 false";
				}
				fastbuy = b;
				// 关闭时不处理任何 fastbuy 请求（不修改任何逻辑）
				return null;
			}
			case "balop.host" -> {
				if (value == null || value.trim().isEmpty()) {
					return "balop.host 不能为空";
				}
				balopHost = value.trim();
				return null;
			}
			case "balop.port" -> {
				Integer v = parseNonNegativeInt(value);
				if (v == null || v < 1 || v > 65535) {
					return "balop.port 需要 1-65535 的整数";
				}
				balopPort = v;
				return null;
			}
			case "balop.domain" -> {
				if (value == null) {
					return "balop.domain 不能为 null";
				}
				balopDomain = value.trim();
				return null;
			}
			case "rule.partialAdjust" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "rule.partialAdjust 需要 true 或 false";
				}
				partialAdjust = b;
				// 权限按次读取即时生效：/weather /time 与规则调整指令下次执行即按新值判定
				return null;
			}
			case "home.max" -> {
				Integer v = parseNonNegativeInt(value);
				if (v == null) {
					return "home.max 需要非负整数";
				}
				homeSettings = new HomeSettings(v, homeSettings.fees());
				return null;
			}
			case "home.cooldownSeconds" -> {
				Integer v = parseNonNegativeInt(value);
				if (v == null) {
					return "home.cooldownSeconds 需要非负整数";
				}
				homeSettings = new HomeSettings(homeSettings.max(), withCooldown(homeSettings.fees(), v));
				return null;
			}
			case "home.fixedFee" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "home.fixedFee 需要 true 或 false";
				}
				homeSettings = new HomeSettings(homeSettings.max(), withFixed(homeSettings.fees(), b));
				return null;
			}
			case "home.fixedFeeAmount" -> {
				Long cents = parseMoney(value);
				if (cents == null) {
					return "home.fixedFeeAmount 需要非负金额";
				}
				homeSettings = new HomeSettings(homeSettings.max(), withFixedAmount(homeSettings.fees(), cents));
				return null;
			}
			case "home.perDistanceFee" -> {
				Long cents = parseMoney(value);
				if (cents == null) {
					return "home.perDistanceFee 需要非负金额";
				}
				homeSettings = new HomeSettings(homeSettings.max(), withPerDistance(homeSettings.fees(), cents));
				return null;
			}
			case "home.crossDimensionFee" -> {
				Long cents = parseMoney(value);
				if (cents == null) {
					return "home.crossDimensionFee 需要非负金额";
				}
				homeSettings = new HomeSettings(homeSettings.max(), withCross(homeSettings.fees(), cents));
				return null;
			}
			case "tpa.enabled" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "tpa.enabled 需要 true 或 false";
				}
				tpaSettings = new TpaSettings(b, tpaSettings.fees(), tpaSettings.timeoutSeconds());
				return null;
			}
			case "tpa.cooldownSeconds" -> {
				Integer v = parseNonNegativeInt(value);
				if (v == null) {
					return "tpa.cooldownSeconds 需要非负整数";
				}
				tpaSettings = new TpaSettings(tpaSettings.enabled(), withCooldown(tpaSettings.fees(), v),
						tpaSettings.timeoutSeconds());
				return null;
			}
			case "tpa.fixedFee" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "tpa.fixedFee 需要 true 或 false";
				}
				tpaSettings = new TpaSettings(tpaSettings.enabled(), withFixed(tpaSettings.fees(), b),
						tpaSettings.timeoutSeconds());
				return null;
			}
			case "tpa.fixedFeeAmount" -> {
				Long cents = parseMoney(value);
				if (cents == null) {
					return "tpa.fixedFeeAmount 需要非负金额";
				}
				tpaSettings = new TpaSettings(tpaSettings.enabled(), withFixedAmount(tpaSettings.fees(), cents),
						tpaSettings.timeoutSeconds());
				return null;
			}
			case "tpa.perDistanceFee" -> {
				Long cents = parseMoney(value);
				if (cents == null) {
					return "tpa.perDistanceFee 需要非负金额";
				}
				tpaSettings = new TpaSettings(tpaSettings.enabled(), withPerDistance(tpaSettings.fees(), cents),
						tpaSettings.timeoutSeconds());
				return null;
			}
			case "tpa.crossDimensionFee" -> {
				Long cents = parseMoney(value);
				if (cents == null) {
					return "tpa.crossDimensionFee 需要非负金额";
				}
				tpaSettings = new TpaSettings(tpaSettings.enabled(), withCross(tpaSettings.fees(), cents),
						tpaSettings.timeoutSeconds());
				return null;
			}
			case "tpa.timeoutSeconds" -> {
				Integer v = parseNonNegativeInt(value);
				if (v == null) {
					return "tpa.timeoutSeconds 需要非负整数";
				}
				tpaSettings = new TpaSettings(tpaSettings.enabled(), tpaSettings.fees(), v);
				return null;
			}
			case "back.enabled" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "back.enabled 需要 true 或 false";
				}
				backSettings = new BackSettings(b, backSettings.fees());
				return null;
			}
			case "back.cooldownSeconds" -> {
				Integer v = parseNonNegativeInt(value);
				if (v == null) {
					return "back.cooldownSeconds 需要非负整数";
				}
				backSettings = new BackSettings(backSettings.enabled(), withCooldown(backSettings.fees(), v));
				return null;
			}
			case "back.fixedFee" -> {
				Boolean b = parseBool(value);
				if (b == null) {
					return "back.fixedFee 需要 true 或 false";
				}
				backSettings = new BackSettings(backSettings.enabled(), withFixed(backSettings.fees(), b));
				return null;
			}
			case "back.fixedFeeAmount" -> {
				Long cents = parseMoney(value);
				if (cents == null) {
					return "back.fixedFeeAmount 需要非负金额";
				}
				backSettings = new BackSettings(backSettings.enabled(), withFixedAmount(backSettings.fees(), cents));
				return null;
			}
			case "back.perDistanceFee" -> {
				Long cents = parseMoney(value);
				if (cents == null) {
					return "back.perDistanceFee 需要非负金额";
				}
				backSettings = new BackSettings(backSettings.enabled(), withPerDistance(backSettings.fees(), cents));
				return null;
			}
			case "back.crossDimensionFee" -> {
				Long cents = parseMoney(value);
				if (cents == null) {
					return "back.crossDimensionFee 需要非负金额";
				}
				backSettings = new BackSettings(backSettings.enabled(), withCross(backSettings.fees(), cents));
				return null;
			}
			default -> {
				return "未知配置项：" + key;
			}
		}
	}

	/** 把当前内存配置写回 config.json（/bm config 修改后持久化）。 */
	public static void save(Path configDir) throws IOException {
		Path file = configDir.resolve("economy").resolve("config.json");
		Files.createDirectories(file.getParent());
		JsonObject root = new JsonObject();
		root.addProperty("itemPricesInLore", itemPricesInLore);
		root.addProperty("funFishing", funFishing);
		JsonObject fly = new JsonObject();
		fly.addProperty("feePerSecond", Money.format(flyFeeCents));
		fly.addProperty("digNoSlow", flyDigNoSlow);
		root.add("fly", fly);
		JsonObject shop = new JsonObject();
		shop.addProperty("sellLog", shopSellLog);
		shop.addProperty("fastbuy", fastbuy);
		root.add("shop", shop);
		JsonObject spawner = new JsonObject();
		spawner.addProperty("upgrade", spawnerUpgrade);
		root.add("spawner", spawner);
		JsonObject rule = new JsonObject();
		rule.addProperty("partialAdjust", partialAdjust);
		root.add("rule", rule);
		JsonObject balop = new JsonObject();
		balop.addProperty("host", balopHost);
		balop.addProperty("port", balopPort);
		balop.addProperty("domain", balopDomain);
		root.add("balop", balop);
		root.add("home", sectionJson(homeSettings.max(), null, homeSettings.fees()));
		root.add("tpa", sectionJson(-1, tpaSettings, tpaSettings.fees()));
		root.add("back", sectionJson(-1, backSettings, backSettings.fees()));
		Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
	}

	private static JsonObject sectionJson(int homeMax, Object extra, TpFees fees) {
		JsonObject section = new JsonObject();
		if (homeMax >= 0) {
			section.addProperty("max", homeMax);
		}
		if (extra instanceof TpaSettings tpa) {
			section.addProperty("enabled", tpa.enabled());
			section.addProperty("timeoutSeconds", tpa.timeoutSeconds());
		} else if (extra instanceof BackSettings back) {
			section.addProperty("enabled", back.enabled());
		}
		section.addProperty("cooldownSeconds", fees.cooldownSeconds());
		section.addProperty("fixedFee", fees.fixedFee());
		section.addProperty("fixedFeeAmount", Money.format(fees.fixedFeeAmountCents()));
		section.addProperty("perDistanceFee", Money.format(fees.perDistanceFeeCents()));
		section.addProperty("crossDimensionFee", Money.format(fees.crossDimensionFeeCents()));
		return section;
	}

	private static TpFees withCooldown(TpFees fees, int v) {
		return new TpFees(v, fees.fixedFee(), fees.fixedFeeAmountCents(), fees.perDistanceFeeCents(),
				fees.crossDimensionFeeCents());
	}

	private static TpFees withFixed(TpFees fees, boolean v) {
		return new TpFees(fees.cooldownSeconds(), v, fees.fixedFeeAmountCents(), fees.perDistanceFeeCents(),
				fees.crossDimensionFeeCents());
	}

	private static TpFees withFixedAmount(TpFees fees, long v) {
		return new TpFees(fees.cooldownSeconds(), fees.fixedFee(), v, fees.perDistanceFeeCents(),
				fees.crossDimensionFeeCents());
	}

	private static TpFees withPerDistance(TpFees fees, long v) {
		return new TpFees(fees.cooldownSeconds(), fees.fixedFee(), fees.fixedFeeAmountCents(), v,
				fees.crossDimensionFeeCents());
	}

	private static TpFees withCross(TpFees fees, long v) {
		return new TpFees(fees.cooldownSeconds(), fees.fixedFee(), fees.fixedFeeAmountCents(),
				fees.perDistanceFeeCents(), v);
	}

	private static Boolean parseBool(String input) {
		if ("true".equalsIgnoreCase(input)) {
			return Boolean.TRUE;
		}
		if ("false".equalsIgnoreCase(input)) {
			return Boolean.FALSE;
		}
		return null;
	}

	private static Integer parseNonNegativeInt(String input) {
		try {
			int v = Integer.parseInt(input.trim());
			return v >= 0 ? v : null;
		} catch (NumberFormatException e) {
			return null;
		}
	}

	private static Long parseMoney(String input) {
		try {
			return parseCents(input);
		} catch (RuntimeException e) {
			return null;
		}
	}

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
			// fly 段（旧版顶层 flyFeePerSecond/flyDigSpeedRestore 兼容读取）
			JsonObject flySection = root.getAsJsonObject("fly");
			if (flySection != null) {
				try {
					if (flySection.has("feePerSecond")) {
						flyFeeCents = parseCents(flySection.get("feePerSecond").getAsString());
					}
				} catch (RuntimeException e) {
					BuildMart.LOGGER.warn("fly.feePerSecond 配置无效，使用默认 {} 元/秒", Money.format(DEFAULT_FLY_FEE_CENTS), e);
					flyFeeCents = DEFAULT_FLY_FEE_CENTS;
				}
				if (flySection.has("digNoSlow")) {
					flyDigNoSlow = flySection.get("digNoSlow").getAsBoolean();
				}
			} else if (root.has("flyFeePerSecond")) {
				// 旧版顶层 key 兼容
				try {
					flyFeeCents = parseCents(root.get("flyFeePerSecond").getAsString());
				} catch (RuntimeException e) {
					BuildMart.LOGGER.warn("flyFeePerSecond 配置无效，使用默认 {} 元/秒", Money.format(DEFAULT_FLY_FEE_CENTS), e);
					flyFeeCents = DEFAULT_FLY_FEE_CENTS;
				}
			}
			if (root.has("flyDigSpeedRestore")) {
				// 旧版顶层 key 兼容
				flyDigNoSlow = root.get("flyDigSpeedRestore").getAsBoolean();
			}
			if (root.has("funFishing")) {
				funFishing = root.get("funFishing").getAsBoolean();
			}
			// rule 段（旧版顶层 partialRuleAdjust 兼容读取：旧键真值优先）
			JsonObject ruleSection = root.getAsJsonObject("rule");
			if (ruleSection != null && ruleSection.has("partialAdjust")) {
				partialAdjust = ruleSection.get("partialAdjust").getAsBoolean();
			} else if (root.has("partialRuleAdjust")) {
				partialAdjust = root.get("partialRuleAdjust").getAsBoolean();
			}
			// shop 段（旧版顶层 shopSellLog / fastbuy 兼容读取：新段优先）
			JsonObject shopSection = root.getAsJsonObject("shop");
			if (shopSection != null) {
				if (shopSection.has("sellLog")) {
					shopSellLog = shopSection.get("sellLog").getAsBoolean();
				}
				if (shopSection.has("fastbuy")) {
					fastbuy = shopSection.get("fastbuy").getAsBoolean();
				}
			} else if (root.has("shopSellLog")) {
				shopSellLog = root.get("shopSellLog").getAsBoolean();
			}
			if (root.has("fastbuy")) {
				// 旧版顶层 fastbuy 兼容（shop 段缺失时兜底）
				fastbuy = root.get("fastbuy").getAsBoolean();
			}
			if (root.has("balop")) {
				JsonObject balop = root.getAsJsonObject("balop");
				try {
					balopHost = balop.has("host") ? balop.get("host").getAsString() : DEFAULT_BALOP_HOST;
					balopPort = balop.has("port") ? balop.get("port").getAsInt() : DEFAULT_BALOP_PORT;
					balopDomain = balop.has("domain") ? balop.get("domain").getAsString() : DEFAULT_BALOP_DOMAIN;
					if (balopPort < 1 || balopPort > 65535) {
						throw new IllegalArgumentException("port 超出范围");
					}
				} catch (RuntimeException e) {
					BuildMart.LOGGER.warn("balop 配置无效，使用默认 {}:{}", DEFAULT_BALOP_HOST, DEFAULT_BALOP_PORT, e);
					balopHost = DEFAULT_BALOP_HOST;
					balopPort = DEFAULT_BALOP_PORT;
				}
			}
			// spawner 段
			JsonObject spawnerSection = root.getAsJsonObject("spawner");
			if (spawnerSection != null && spawnerSection.has("upgrade")) {
				spawnerUpgrade = spawnerSection.get("upgrade").getAsBoolean();
			}
			homeSettings = readHome(root.getAsJsonObject("home"));
			tpaSettings = readTpa(root.getAsJsonObject("tpa"));
			backSettings = readBack(root.getAsJsonObject("back"));
			BuildMart.LOGGER.info("主配置已加载：{}（itemPricesInLore={}，flyFeePerSecond={} 元/秒，"
							+ "home.max={}，tpa.enabled={}，back.enabled={}）",
					file, itemPricesInLore, Money.format(flyFeeCents),
					homeSettings.max(), tpaSettings.enabled(), backSettings.enabled());
		} catch (IOException e) {
			BuildMart.LOGGER.warn("主配置加载失败，使用默认值", e);
		}
	}

	public static boolean itemPricesInLore() {
		return itemPricesInLore;
	}

	/** 付费飞行每秒扣费（分）。 */
	public static long flyFeeCents() {
		return flyFeeCents;
	}

	/** 趣味钓鱼开关（开启时用自定义钓鱼战利品，关闭时用原版）。 */
	public static boolean funFishing() {
		return funFishing;
	}

	/** 飞行挖掘不减速开关（true=飞行中挖掘与地面一致，false=原版生存飞行挖掘速度）。 */
	public static boolean flyDigNoSlow() {
		return flyDigNoSlow;
	}

	/** 商店出售结算日志开关（默认关闭：每店每 60 秒一条过于刷屏）。 */
	public static boolean shopSellLog() {
		return shopSellLog;
	}

	/** 刷怪笼升级功能开关（关闭时禁止升级，已升级效果按 Lv 1 生成，数据保留）。 */
	public static boolean spawnerUpgrade() {
		return spawnerUpgrade;
	}

	/** 快速投影购买开关（默认关闭；关闭时不处理任何 fastbuy 请求，不修改任何逻辑）。 */
	public static boolean fastbuy() {
		return fastbuy;
	}

	/** 切换快速投影购买（/fastbuy 指令用）。 */
	public static void setFastbuy(boolean v) {
		fastbuy = v;
	}

	/** 数据库管理前端监听地址（/balop start 时读取）。 */
	public static String balopHost() {
		return balopHost;
	}

	/** 数据库管理前端端口（/balop start 时读取）。 */
	public static int balopPort() {
		return balopPort;
	}

	/** 部分规则调整权限（rule.partialAdjust：true=玩家可用 /weather /time /fixweather /fixtime /naturalmonsterspawn）。 */
	public static boolean partialAdjust() {
		return partialAdjust;
	}

	/** /balop start 链接展示域名（空 = 未设置，链接用 balop.host + balop.port；域名整体替换，不带端口）。 */
	public static String balopDomain() {
		return balopDomain;
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
			BuildMart.LOGGER.warn("home 配置无效，使用默认值", e);
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
			BuildMart.LOGGER.warn("tpa 配置无效，使用默认值", e);
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
			BuildMart.LOGGER.warn("back 配置无效，使用默认值", e);
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
				  "funFishing": false,
				  "rule": {"partialAdjust": false},
				  "fly": {"feePerSecond": "500.00", "digNoSlow": true},
				  "shop": {"sellLog": false, "fastbuy": false},
				  "spawner": {"upgrade": true},
				  "balop": {"host": "localhost", "port": 8899, "domain": ""},
				  "home": {"max": 0, "cooldownSeconds": 0, "fixedFee": false, "fixedFeeAmount": "500.00", "perDistanceFee": "1.00", "crossDimensionFee": "1000.00"},
				  "tpa": {"enabled": false, "cooldownSeconds": 0, "fixedFee": false, "fixedFeeAmount": "500.00", "perDistanceFee": "1.00", "crossDimensionFee": "1000.00", "timeoutSeconds": 60},
				  "back": {"enabled": false, "cooldownSeconds": 0, "fixedFee": false, "fixedFeeAmount": "500.00", "perDistanceFee": "1.00", "crossDimensionFee": "1000.00"}
				}
				""", StandardCharsets.UTF_8);
	}
}
