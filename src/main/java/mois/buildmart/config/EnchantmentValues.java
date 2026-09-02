package mois.buildmart.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import mois.buildmart.Economy;
import net.minecraft.core.Holder;
import net.minecraft.world.item.enchantment.Enchantment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * 附魔价值配置：config/economy/enchantments.json，键为附魔 ID（如 minecraft:sharpness），
 * 值为该附魔“1 级”的十进制元价格，可覆盖默认值；每升 1 级价格翻倍
 * （1 级 2.00 → 2 级 4.00 → 3 级 8.00……与两本低级附魔书合成一本高级附魔书的
 * 价值守恒一致）。
 * <p>
 * 未配置的附魔按村民交易规则默认：1 级价格 = 7 绿宝石 + 1 本书；
 * 宝藏附魔（附魔台无法获得的）翻倍为 14 绿宝石 + 1 本书。
 * 绿宝石与书的价格跟随物品定价配置。
 */
public final class EnchantmentValues {
	/** 普通附魔 1 级价格所需的绿宝石数量（村民交易参考）。 */
	private static final int NORMAL_EMERALDS = 7;
	/** 宝藏附魔 1 级价格所需的绿宝石数量（村民交易参考，翻倍）。 */
	private static final int TREASURE_EMERALDS = 14;

	/** 宝藏附魔名单：附魔台无法获得、村民交易价格翻倍的附魔。 */
	private static final java.util.Set<String> TREASURE = java.util.Set.of(
			"minecraft:mending",
			"minecraft:frost_walker",
			"minecraft:binding_curse",
			"minecraft:vanishing_curse",
			"minecraft:swift_sneak",
			"minecraft:soul_speed",
			"minecraft:wind_burst",
			"minecraft:density",
			"minecraft:breach");

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Long> VALUES = new HashMap<>();

	private EnchantmentValues() {
	}

	/** 从 config 目录加载；文件不存在时生成空配置（全部按村民交易默认）。失败时回退为全默认。 */
	public static void load(Path configDir) {
		Path file = configDir.resolve("economy").resolve("enchantments.json");
		Map<String, Long> parsed = new HashMap<>();
		try {
			Files.createDirectories(file.getParent());
			if (!Files.exists(file)) {
				Files.writeString(file, GSON.toJson(new JsonObject()), StandardCharsets.UTF_8);
			}
			JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonObject();
			for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
				parsed.put(entry.getKey(), parseCents(entry.getValue().getAsString()));
			}
			synchronized (VALUES) {
				VALUES.clear();
				VALUES.putAll(parsed);
			}
			Economy.LOGGER.info("附魔价值配置已加载：{}（{} 项覆盖；未配置附魔按村民交易：普通 1 级 {} 绿宝石+1 书，宝藏翻倍；每升 1 级价格翻倍）",
					file, VALUES.size(), NORMAL_EMERALDS);
		} catch (Exception e) {
			Economy.LOGGER.warn("附魔价值配置加载失败，全部回退为村民交易默认", e);
			synchronized (VALUES) {
				VALUES.clear();
			}
		}
	}

	/** 附魔 1 级价格（分）：配置覆盖优先；否则按村民交易规则（普通 7 绿宝石+1 书，宝藏 14 绿宝石+1 书）。 */
	public static long get(Holder<Enchantment> holder) {
		String id = holder.unwrapKey().map(key -> key.identifier().toString()).orElse(null);
		if (id != null) {
			synchronized (VALUES) {
				Long override = VALUES.get(id);
				if (override != null) {
					return override;
				}
			}
		}
		long emeralds = id != null && TREASURE.contains(id) ? TREASURE_EMERALDS : NORMAL_EMERALDS;
		return satAdd(satMul(ItemValues.get(net.minecraft.world.item.Items.EMERALD), emeralds),
				ItemValues.get(net.minecraft.world.item.Items.BOOK));
	}

	private static long satAdd(long a, long b) {
		if (a > Long.MAX_VALUE - b) {
			return Long.MAX_VALUE;
		}
		return a + b;
	}

	private static long satMul(long a, long b) {
		if (a == 0 || b == 0) {
			return 0;
		}
		if (a > Long.MAX_VALUE / b) {
			return Long.MAX_VALUE;
		}
		return a * b;
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
