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
 * Map/List，键为组件 ID（如 "minecraft:lore"、"minecraft:ominous_bottle_amplifier"）。
 * <p>
 * 概率规则：普通项为 (0, 1] 的数字，总和 ≤ 1（不能大于 1）；可含至多一个补全项
 * （"chance": "remaining"）自动补足剩余概率。
 * <p>
 * 分组说明（概率合计正好 100%）：鱼类 40% / 矿物 40% / 稀有组 10% / 高级组 5% /
 * 传说组 4% / 下界合金斧 0.5% / 金色传说组 0.3% / 龙蛋 0.19% / 刷怪笼 0.01%。
 * 每组物品都带一条 lore 显示信息（玩家悬停物品可见）。
 */
public final class FishingInitialLoot {
	// 注意：GSON 必须在 INITIAL_JSON 之前初始化（静态字段按声明顺序初始化，
	// buildInitialJson 依赖 GSON）
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** 初始战利品（分组见类注释）。 */
	public static final String INITIAL_JSON = buildInitialJson();

	private FishingInitialLoot() {
	}

	/** 用 Map/List 构建初始配置（Gson 序列化；物品支持 count 与任意嵌套 components）。 */
	private static String buildInitialJson() {
		List<Map<String, Object>> loot = new ArrayList<>();
		// ---- 鱼类共享 40%（4 种 × 10%）----
		for (String id : List.of("minecraft:cod", "minecraft:salmon", "minecraft:pufferfish", "minecraft:tropical_fish")) {
			loot.add(lootEntry(item(id, 1, lore("小鱼，不值钱。")), 0.10));
		}
		// ---- 矿物共享 40%（11 种平均：10 × 0.03636 + 1 × 0.0364 = 40%）----
		List<String> ores = List.of("minecraft:diamond", "minecraft:emerald", "minecraft:iron_ingot",
				"minecraft:gold_ingot", "minecraft:copper_ingot", "minecraft:redstone", "minecraft:lapis_lazuli",
				"minecraft:quartz", "minecraft:coal", "minecraft:netherite_scrap", "minecraft:netherite_ingot");
		for (int i = 0; i < ores.size(); i++) {
			double chance = (i == ores.size() - 1) ? 0.0364 : 0.03636;
			loot.add(lootEntry(item(ores.get(i), 1, lore("水里生矿了？有点神秘。")), chance));
		}
		// ---- 稀有组共享 10%（5 种 × 2%，不详之瓶为五级）----
		for (String id : List.of("minecraft:ominous_bottle", "minecraft:echo_shard", "minecraft:iron_nautilus_armor",
				"minecraft:creeper_head", "minecraft:ominous_trial_key")) {
			Map<String, Object> components = lore("没见过的东西，仔细瞧瞧？");
			if (id.equals("minecraft:ominous_bottle")) {
				components.put("minecraft:ominous_bottle_amplifier", 4); // 五级不详之瓶（amplifier 0-4）
			}
			loot.add(lootEntry(item(id, 1, components), 0.02));
		}
		// ---- 高级组共享 5%（凋零骷髅头/附魔金苹果/三叉戟：0.0167+0.0167+0.0166）----
		String[] epic = {"minecraft:wither_skeleton_skull", "minecraft:enchanted_golden_apple", "minecraft:trident"};
		double[] epicChance = {0.0167, 0.0167, 0.0166};
		for (int i = 0; i < epic.length; i++) {
			loot.add(lootEntry(item(epic[i], 1, lore("hello！")), epicChance[i]));
		}
		// ---- 传说组共享 4%（4 种 × 1%）----
		for (String id : List.of("minecraft:elytra", "minecraft:dragon_head", "minecraft:heart_of_the_sea",
				"minecraft:conduit")) {
			loot.add(lootEntry(item(id, 1, lore("有点高级！")), 0.01));
		}
		// ---- 0.5%：下界合金斧 ----
		loot.add(lootEntry(item("minecraft:netherite_axe", 1, lore("这是你掉的吗？")), 0.005));
		// ---- 0.3%：下界之星 + 下界合金鹦鹉螺铠（各 0.15%）----
		loot.add(lootEntry(item("minecraft:nether_star", 1, lore("金色传说！")), 0.0015));
		loot.add(lootEntry(item("minecraft:netherite_nautilus_armor", 1, lore("金色传说！")), 0.0015));
		// ---- 0.19%：龙蛋 ----
		loot.add(lootEntry(item("minecraft:dragon_egg", 1, lore("谁家的蛋？")), 0.0019));
		// ---- 0.01%：刷怪笼 ----
		loot.add(lootEntry(item("minecraft:spawner", 1, lore("不是哥们？")), 0.0001));
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
	 * 组件格式，键如 "minecraft:lore"、"minecraft:ominous_bottle_amplifier"）。
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

	/** lore 组件：玩家悬停物品时显示的自定义信息行。 */
	public static Map<String, Object> lore(String line) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("minecraft:lore", List.of(Map.of("text", line)));
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
