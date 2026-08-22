package mois.economy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mois.economy.Economy;
import mois.economy.Money;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 物品价值配置：config/economy/items.json，键为物品 ID（如 minecraft:dirt），
 * 值为十进制元字符串（如 "0.10"），解析为整数分存储；未配置的物品默认 1.00 元。
 */
public final class ItemValues {
	public static final long DEFAULT_CENTS = 100L; // 1.00 元

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Long> VALUES = new HashMap<>();

	private ItemValues() {
	}

	/** 从 config 目录加载；文件不存在时生成示例默认配置。失败时回退为全默认 1.00 元。 */
	public static void load(Path configDir) {
		Path file = configDir.resolve("economy").resolve("items.json");
		Map<String, Long> parsed = new HashMap<>();
		try {
			Files.createDirectories(file.getParent());
			if (!Files.exists(file)) {
				writeDefaults(file);
			}
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
				parsed.put(entry.getKey(), parseCents(entry.getValue().getAsString()));
			}
			synchronized (VALUES) {
				VALUES.clear();
				VALUES.putAll(parsed);
			}
			Economy.LOGGER.info("物品价值配置已加载：{}（{} 项，未配置物品默认 {} 元）",
					file, VALUES.size(), Money.format(DEFAULT_CENTS));
		} catch (Exception e) {
			Economy.LOGGER.error("物品价值配置加载失败，全部回退为默认 1.00 元", e);
			synchronized (VALUES) {
				VALUES.clear();
			}
		}
	}

	public static long get(net.minecraft.world.item.Item item) {
		return get(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
	}

	public static long get(net.minecraft.resources.Identifier id) {
		synchronized (VALUES) {
			return VALUES.getOrDefault(id.toString(), DEFAULT_CENTS);
		}
	}

	/** 把当前配置序列化为 JSON 字符串（用于同步给客户端）。 */
	public static String toJson() {
		JsonObject root = new JsonObject();
		synchronized (VALUES) {
			for (Map.Entry<String, Long> entry : VALUES.entrySet()) {
				root.addProperty(entry.getKey(), Money.format(entry.getValue()));
			}
		}
		return GSON.toJson(root);
	}

	private static void writeDefaults(Path file) throws IOException {
		JsonObject root = new JsonObject();
		// 示例默认价值（正式默认值后续统一讨论），未列出的物品一律 1.00 元。
		root.addProperty("minecraft:dirt", "0.10");
		root.addProperty("minecraft:stone", "0.20");
		root.addProperty("minecraft:cobblestone", "0.20");
		root.addProperty("minecraft:oak_log", "0.30");
		Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
	}

	/** 解析非负十进制元字符串为分（最多两位小数），非法值抛异常。 */
	private static long parseCents(String input) {
		BigDecimal value = new BigDecimal(input.trim());
		if (value.signum() < 0) {
			throw new NumberFormatException("价格不能为负：" + input);
		}
		if (value.scale() > 2) {
			throw new NumberFormatException("价格最多两位小数：" + input);
		}
		return value.movePointRight(2).longValueExact();
	}
}
