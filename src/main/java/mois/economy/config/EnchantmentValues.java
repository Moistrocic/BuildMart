package mois.economy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mois.economy.Economy;
import mois.economy.Money;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 附魔价值配置：config/economy/enchantments.json，键为附魔 ID（如 minecraft:sharpness），
 * 值为该附魔“每一级”的十进制元价格（如 "2.00"），解析为整数分存储。
 * 未配置的附魔默认每级 1.00 元。物品完整价值 = 基础价 + Σ(附魔每级价格 × 等级)。
 */
public final class EnchantmentValues {
	public static final long DEFAULT_PER_LEVEL_CENTS = 100L; // 1.00 元/级

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Long> VALUES = new HashMap<>();

	private EnchantmentValues() {
	}

	/** 从 config 目录加载；文件不存在时生成示例默认配置。失败时回退为全默认每级 1.00 元。 */
	public static void load(Path configDir) {
		Path file = configDir.resolve("economy").resolve("enchantments.json");
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
			Economy.LOGGER.info("附魔价值配置已加载：{}（{} 项，未配置附魔默认每级 {} 元）",
					file, VALUES.size(), Money.format(DEFAULT_PER_LEVEL_CENTS));
		} catch (Exception e) {
			Economy.LOGGER.error("附魔价值配置加载失败，全部回退为默认每级 1.00 元", e);
			synchronized (VALUES) {
				VALUES.clear();
			}
		}
	}

	/** 附魔每级价格（分）；未配置或无法解析 ID 时用默认价。 */
	public static long get(Holder<Enchantment> holder) {
		String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
		if (id == null) {
			return DEFAULT_PER_LEVEL_CENTS;
		}
		synchronized (VALUES) {
			return VALUES.getOrDefault(id, DEFAULT_PER_LEVEL_CENTS);
		}
	}

	private static void writeDefaults(Path file) throws IOException {
		JsonObject root = new JsonObject();
		root.addProperty("minecraft:sharpness", "2.00");
		root.addProperty("minecraft:smite", "2.00");
		root.addProperty("minecraft:bane_of_arthropods", "2.00");
		root.addProperty("minecraft:protection", "2.00");
		root.addProperty("minecraft:efficiency", "1.50");
		root.addProperty("minecraft:fortune", "3.00");
		root.addProperty("minecraft:silk_touch", "5.00");
		root.addProperty("minecraft:unbreaking", "1.00");
		root.addProperty("minecraft:mending", "5.00");
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
