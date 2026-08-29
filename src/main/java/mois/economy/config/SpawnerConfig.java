package mois.economy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mois.economy.Economy;
import mois.economy.Money;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 刷怪笼分级配置：config/economy/spawner.json。
 * <p>
 * 每级定义各生成参数的**允许范围**（[下限, 上限]）与升级费用：
 * 升级后参数默认取范围下限，玩家可用 /spawner set 在范围内调整；
 * 升级费用为「升级到该等级」的费用（Lv 1 的费用即 Lv 0 → Lv 1，
 * 元字符串，与主配置风格一致）。**Lv 0 为初始态（原版生成机制），
 * 不在配置中，不可配置/不可微调**。
 * 文件缺失/解析失败时回退内置默认表。
 */
public final class SpawnerConfig {
	/** 单个等级的参数范围与费用。 */
	public record LevelParams(int level,
			int minDelayMin, int minDelayMax,
			int maxDelayMin, int maxDelayMax,
			int countMin, int countMax,
			int nearbyMin, int nearbyMax,
			int playerRangeMin, int playerRangeMax,
			int spawnRangeMin, int spawnRangeMax,
			long upgradeFeeCents) {
	}

	/** 内置默认表（Lv 0 = 原版生成机制；生成间隔 ×0.75/级，数量/范围随级增长；费用为升级到该级）。 */
	private static final String DEFAULT_JSON = """
			{
			  "levels": [
			    {"level": 1, "minDelay": [600, 600], "maxDelay": [800, 800], "count": [4, 4], "nearby": [6, 6], "playerRange": [16, 16], "spawnRange": [4, 4], "upgradeFee": "500.00"},
			    {"level": 2, "minDelay": [450, 600], "maxDelay": [600, 800], "count": [4, 6], "nearby": [6, 9], "playerRange": [16, 20], "spawnRange": [4, 6], "upgradeFee": "1000.00"},
			    {"level": 3, "minDelay": [337, 450], "maxDelay": [450, 600], "count": [5, 7], "nearby": [6, 12], "playerRange": [16, 22], "spawnRange": [4, 7], "upgradeFee": "4000.00"},
			    {"level": 4, "minDelay": [253, 337], "maxDelay": [337, 450], "count": [5, 8], "nearby": [6, 15], "playerRange": [16, 24], "spawnRange": [4, 8], "upgradeFee": "9000.00"},
			    {"level": 5, "minDelay": [190, 253], "maxDelay": [253, 337], "count": [6, 9], "nearby": [6, 18], "playerRange": [16, 26], "spawnRange": [4, 9], "upgradeFee": "16000.00"},
			    {"level": 6, "minDelay": [142, 190], "maxDelay": [190, 253], "count": [6, 10], "nearby": [6, 21], "playerRange": [16, 28], "spawnRange": [4, 10], "upgradeFee": "25000.00"},
			    {"level": 7, "minDelay": [107, 142], "maxDelay": [142, 190], "count": [7, 11], "nearby": [6, 24], "playerRange": [16, 30], "spawnRange": [4, 11], "upgradeFee": "36000.00"},
			    {"level": 8, "minDelay": [80, 107], "maxDelay": [107, 142], "count": [7, 12], "nearby": [6, 27], "playerRange": [16, 32], "spawnRange": [4, 12], "upgradeFee": "49000.00"},
			    {"level": 9, "minDelay": [60, 80], "maxDelay": [80, 107], "count": [8, 13], "nearby": [6, 30], "playerRange": [16, 34], "spawnRange": [4, 13], "upgradeFee": "64000.00"},
			    {"level": 10, "minDelay": [45, 60], "maxDelay": [60, 80], "count": [8, 14], "nearby": [6, 33], "playerRange": [16, 36], "spawnRange": [4, 14], "upgradeFee": "81000.00"}
			  ]
			}
			""";

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final List<LevelParams> LEVELS = new ArrayList<>();

	private SpawnerConfig() {
	}

	/** 最高等级（由配置推导，默认 10）。 */
	public static int maxLevel() {
		return LEVELS.isEmpty() ? 10 : LEVELS.get(LEVELS.size() - 1).level();
	}

	/** 指定等级的参数（越界返回最近等级）。 */
	public static LevelParams level(int level) {
		if (LEVELS.isEmpty()) {
			loadDefault();
		}
		if (level <= LEVELS.get(0).level()) {
			return LEVELS.get(0);
		}
		LevelParams last = LEVELS.get(LEVELS.size() - 1);
		if (level >= last.level()) {
			return last;
		}
		for (LevelParams p : LEVELS) {
			if (p.level() == level) {
				return p;
			}
		}
		return last;
	}

	public static void load(Path configDir) {
		Path file = configDir.resolve("economy").resolve("spawner.json");
		try {
			Files.createDirectories(file.getParent());
			if (!Files.exists(file)) {
				Files.writeString(file, DEFAULT_JSON, StandardCharsets.UTF_8);
			}
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonArray levels = root.getAsJsonArray("levels");
			List<LevelParams> parsed = new ArrayList<>();
			for (JsonElement el : levels) {
				JsonObject o = el.getAsJsonObject();
				int lv = o.get("level").getAsInt();
				parsed.add(new LevelParams(lv,
						range(o, "minDelay")[0], range(o, "minDelay")[1],
						range(o, "maxDelay")[0], range(o, "maxDelay")[1],
						range(o, "count")[0], range(o, "count")[1],
						range(o, "nearby")[0], range(o, "nearby")[1],
						range(o, "playerRange")[0], range(o, "playerRange")[1],
						range(o, "spawnRange")[0], range(o, "spawnRange")[1],
						parseCents(o.get("upgradeFee").getAsString())));
			}
			parsed.sort((a, b) -> Integer.compare(a.level(), b.level()));
			synchronized (LEVELS) {
				LEVELS.clear();
				LEVELS.addAll(parsed);
			}
			Economy.LOGGER.info("刷怪笼配置已加载：{}（{} 级）", file, LEVELS.size());
		} catch (Exception e) {
			Economy.LOGGER.warn("刷怪笼配置加载失败，使用内置默认表", e);
			loadDefault();
		}
	}

	private static void loadDefault() {
		try {
			JsonObject root = JsonParser.parseString(DEFAULT_JSON).getAsJsonObject();
			JsonArray levels = root.getAsJsonArray("levels");
			List<LevelParams> parsed = new ArrayList<>();
			for (JsonElement el : levels) {
				JsonObject o = el.getAsJsonObject();
				int lv = o.get("level").getAsInt();
				parsed.add(new LevelParams(lv,
						range(o, "minDelay")[0], range(o, "minDelay")[1],
						range(o, "maxDelay")[0], range(o, "maxDelay")[1],
						range(o, "count")[0], range(o, "count")[1],
						range(o, "nearby")[0], range(o, "nearby")[1],
						range(o, "playerRange")[0], range(o, "playerRange")[1],
						range(o, "spawnRange")[0], range(o, "spawnRange")[1],
						parseCents(o.get("upgradeFee").getAsString())));
			}
			parsed.sort((a, b) -> Integer.compare(a.level(), b.level()));
			synchronized (LEVELS) {
				LEVELS.clear();
				LEVELS.addAll(parsed);
			}
		} catch (RuntimeException ignored) {
			// 内置默认表不应失败
		}
	}

	private static int[] range(JsonObject o, String key) {
		JsonArray arr = o.getAsJsonArray(key);
		int min = arr.get(0).getAsInt();
		int max = arr.get(1).getAsInt();
		if (min > max) {
			int t = min;
			min = max;
			max = t;
		}
		return new int[]{min, max};
	}

	private static long parseCents(String input) {
		BigDecimal value = new BigDecimal(input.trim());
		if (value.signum() < 0) {
			throw new NumberFormatException("费用不能为负：" + input);
		}
		if (value.scale() > 2) {
			throw new NumberFormatException("费用最多两位小数：" + input);
		}
		return value.movePointRight(2).longValueExact();
	}

	/** 金额格式化（提示用）。 */
	public static String formatCents(long cents) {
		return Money.format(cents);
	}
}
