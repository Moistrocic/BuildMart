package mois.economy.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端物品价值缓存：由服务端 PriceListPayload 填充。
 * 未收到服务端数据时 {@link #get(ItemStack)} 返回 -1，提示层据此不显示价格，
 * 保证卸载模组/服务端无本模组时不会残留价格提示。
 */
public final class PriceCache {
	private static volatile Map<String, Long> prices;

	private PriceCache() {
	}

	public static void load(String json) {
		Map<String, Long> parsed = new HashMap<>();
		try {
			JsonObject root = JsonParser.parseString(json).getAsJsonObject();
			for (Map.Entry<String, com.google.gson.JsonElement> entry : root.entrySet()) {
				parsed.put(entry.getKey(), parseCents(entry.getValue().getAsString()));
			}
		} catch (Exception e) {
			return; // 数据异常时保持原状态
		}
		prices = parsed;
	}

	/** 返回物品价值（分）；未收到服务器数据返回 -1。 */
	public static long get(ItemStack stack) {
		Map<String, Long> current = prices;
		if (current == null || stack.isEmpty()) {
			return -1;
		}
		Item item = stack.getItem();
		Identifier id = BuiltInRegistries.ITEM.getKey(item);
		return current.getOrDefault(id.toString(), 100L);
	}

	private static long parseCents(String input) {
		BigDecimal value = new BigDecimal(input.trim());
		if (value.signum() < 0 || value.scale() > 2) {
			throw new NumberFormatException("非法价格：" + input);
		}
		return value.movePointRight(2).longValueExact();
	}
}
