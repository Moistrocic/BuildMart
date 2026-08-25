package mois.economy.fishing;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * 趣味钓鱼初始战利品配置（类似 ItemInitialPrices 的初始化配置类）：
 * 首次启动时写入 config/economy/fishing.json，之后修改直接改配置文件。
 * <p>
 * 配置格式为 JSON 数组，每项为 {"item": {物品信息（ItemStack.CODEC 格式，
 * 可含 count/components）}, "chance": 概率}，全部概率之和应为 1。
 */
public final class FishingInitialLoot {
	/** 初始战利品：鳕鱼 50% + 绿宝石 50%。 */
	public static final String INITIAL_JSON = """
			[
			  {"item": {"id": "minecraft:cod"}, "chance": 0.5},
			  {"item": {"id": "minecraft:emerald"}, "chance": 0.5}
			]
			""";

	private FishingInitialLoot() {
	}

	/** 以 JsonArray 形式返回初始配置（供首次写入）。 */
	public static JsonArray initialJson() {
		return com.google.gson.JsonParser.parseString(INITIAL_JSON).getAsJsonArray();
	}

	/** 用于展示的初始配置对象（调试/日志用）。 */
	public static JsonObject example() {
		JsonObject root = new JsonObject();
		root.add("loot", initialJson());
		return root;
	}
}
