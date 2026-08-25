package mois.economy.fishing;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 趣味钓鱼初始战利品配置（类似 ItemInitialPrices 的初始化配置类）：
 * 首次启动时写入 config/economy/fishing.json，之后修改直接改配置文件。
 * <p>
 * 配置格式为 JSON 数组，每项 {"item": 物品信息, "chance": 概率}。物品信息用
 * ItemStack.CODEC 格式（id/count/components），components 值可为任意嵌套
 * Map/List，键为组件 ID（如 "minecraft:enchantments"、"minecraft:attribute_modifiers"），
 * 因此可以给战利品附加附魔、属性修改、自定义数量等。
 * <p>
 * 概率规则：普通项为 (0, 1] 的数字，总和 ≤ 1（不能大于 1）；可含至多一个补全项
 * （"chance": "remaining"）自动补足剩余概率。
 * <p>
 * 带组件物品的构建示例：
 * <pre>
 * Map&lt;String, Object&gt; components = new LinkedHashMap&lt;&gt;();
 * components.put("minecraft:enchantments", Map.of("levels", Map.of("minecraft:sharpness", 5)));
 * loot.add(lootEntry(item("minecraft:diamond_sword", 1, components), 0.1));
 * </pre>
 */
public final class FishingInitialLoot {
	// 注意：GSON 必须在 INITIAL_JSON 之前初始化（静态字段按声明顺序初始化，
	// buildInitialJson 依赖 GSON）
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** 初始战利品：鳕鱼 50% + 补全项（绿宝石）补足剩余 50%。 */
	public static final String INITIAL_JSON = buildInitialJson();

	private FishingInitialLoot() {
	}

	/** 用 Map/List 构建初始配置（Gson 序列化；物品支持 count 与任意嵌套 components）。 */
	private static String buildInitialJson() {
		List<Map<String, Object>> loot = new ArrayList<>();
		// 简单物品（数量默认 1）
		loot.add(lootEntry(item("minecraft:cod"), 0.5));
		// 补全项：剩余概率归绿宝石
		loot.add(lootEntry(item("minecraft:emerald"), "remaining"));
		return GSON.toJson(loot);
	}

	/** 简单物品：仅 id（数量默认 1）。 */
	public static Map<String, Object> item(String id) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("id", id);
		return map;
	}

	/**
	 * 带数量/组件的物品。components 值可为任意嵌套 Map/List（ItemStack.CODEC
	 * 组件格式，键如 "minecraft:enchantments"、"minecraft:attribute_modifiers"）。
	 */
	public static Map<String, Object> item(String id, Integer count, Map<String, Object> components) {
		Map<String, Object> map = item(id);
		if (count != null) {
			map.put("count", count);
		}
		if (components != null && !components.isEmpty()) {
			map.put("components", components);
		}
		return map;
	}

	/** 单个战利品项。chance 为 (0,1] 数字或 "remaining"（补全项，至多一个）。 */
	public static Map<String, Object> lootEntry(Map<String, Object> item, Object chance) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("item", item);
		map.put("chance", chance);
		return map;
	}
}
