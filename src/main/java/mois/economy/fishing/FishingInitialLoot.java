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
 * lore 显示信息：两行（标题 + 小故事），去掉默认斜体并按分组配色，贴合物品。
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
		loot.add(lootEntry(item("minecraft:cod", 1, lore2("小鱼，不值钱。", "aqua",
				"它在潮汐里数过一千遍沙子，还是没学会涨价。", "gray")), 0.10));
		loot.add(lootEntry(item("minecraft:salmon", 1, lore2("小鱼，不值钱。", "aqua",
				"逆流而上练就的肌肉，在水桶里毫无用武之地。", "gray")), 0.10));
		loot.add(lootEntry(item("minecraft:pufferfish", 1, lore2("小鱼，不值钱。", "aqua",
				"鼓起勇气的样子很可爱——虽然只能坚持一秒。", "gray")), 0.10));
		loot.add(lootEntry(item("minecraft:tropical_fish", 1, lore2("小鱼，不值钱。", "aqua",
				"彩虹色的鳞片，晒干后连猫都懒得闻。", "gray")), 0.10));
		// ---- 矿物共享 40%（11 种：10 × 0.03636 + 1 × 0.0364）----
		Map<String, Object> oreLore = lore2("水里生矿了？有点神秘。", "gold",
				"矿脉的叹息顺着暗流漂进渔网，沉甸甸的，像被河水腌过千百年的秘密。", "gray");
		loot.add(lootEntry(item("minecraft:diamond", 1, oreLore), 0.03636));
		loot.add(lootEntry(item("minecraft:emerald", 1, oreLore), 0.03636));
		loot.add(lootEntry(item("minecraft:iron_ingot", 1, oreLore), 0.03636));
		loot.add(lootEntry(item("minecraft:gold_ingot", 1, oreLore), 0.03636));
		loot.add(lootEntry(item("minecraft:copper_ingot", 1, oreLore), 0.03636));
		loot.add(lootEntry(item("minecraft:redstone", 1, oreLore), 0.03636));
		loot.add(lootEntry(item("minecraft:lapis_lazuli", 1, oreLore), 0.03636));
		loot.add(lootEntry(item("minecraft:quartz", 1, oreLore), 0.03636));
		loot.add(lootEntry(item("minecraft:coal", 1, oreLore), 0.03636));
		loot.add(lootEntry(item("minecraft:netherite_scrap", 1, oreLore), 0.03636));
		loot.add(lootEntry(item("minecraft:netherite_ingot", 1, oreLore), 0.0364));
		// ---- 稀有组共享 10%（5 种 × 2%，不详之瓶为五级）----
		Map<String, Object> rareLore = lore2("没见过的东西，仔细瞧瞧？", "light_purple",
				"它不属于这片水域，也不属于任何一张渔网该有的梦。", "gray");
		Map<String, Object> ominous = lore2("没见过的东西，仔细瞧瞧？", "light_purple",
				"瓶口封着远古的低语，开盖前最好先数好自己的心跳。", "gray");
		ominous.put("minecraft:ominous_bottle_amplifier", 4); // 五级不详之瓶（amplifier 0-4）
		loot.add(lootEntry(item("minecraft:ominous_bottle", 1, ominous), 0.02));
		loot.add(lootEntry(item("minecraft:echo_shard", 1, rareLore), 0.02));
		loot.add(lootEntry(item("minecraft:iron_nautilus_armor", 1, rareLore), 0.02));
		loot.add(lootEntry(item("minecraft:creeper_head", 1, rareLore), 0.02));
		loot.add(lootEntry(item("minecraft:ominous_trial_key", 1, rareLore), 0.02));
		// ---- 高级组共享 5%（凋零骷髅头/附魔金苹果/三叉戟：0.0167+0.0167+0.0166）----
		loot.add(lootEntry(item("minecraft:wither_skeleton_skull", 1, lore2("hello！", "red",
				"下界的凝视隔着三界传来，礼貌地打了个招呼。", "dark_gray")), 0.0167));
		loot.add(lootEntry(item("minecraft:enchanted_golden_apple", 1, lore2("hello！", "red",
				"被祝福过的苹果，咬一口连河神都要羡慕。", "dark_gray")), 0.0167));
		loot.add(lootEntry(item("minecraft:trident", 1, lore2("hello！", "red",
				"溺尸的失落之物，它记得每一场暴风雨。", "dark_gray")), 0.0166));
		// ---- 传说组共享 4%（4 种 × 1%）----
		loot.add(lootEntry(item("minecraft:elytra", 1, lore2("有点高级！", "dark_purple",
				"龙翼的余温未散，风在耳边说：飞吧。", "gray")), 0.01));
		loot.add(lootEntry(item("minecraft:dragon_head", 1, lore2("有点高级！", "dark_purple",
				"末地龙王的旧冠冕，鳞片还带着星辉。", "gray")), 0.01));
		loot.add(lootEntry(item("minecraft:heart_of_the_sea", 1, lore2("有点高级！", "dark_purple",
				"深海的心脏仍在跳动，为每一艘迷航的船亮灯。", "gray")), 0.01));
		loot.add(lootEntry(item("minecraft:conduit", 1, lore2("有点高级！", "dark_purple",
				"潮涌的核心，据说能给整片海讲一个安眠的故事。", "gray")), 0.01));
		// ---- 0.5%：下界合金斧 ----
		loot.add(lootEntry(item("minecraft:netherite_axe", 1, lore2("这是你掉的吗？", "dark_red",
				"一位旅人把它丢进岩浆……它却从河里游了上来。", "gray")), 0.005));
		// ---- 0.3%：下界之星 + 下界合金鹦鹉螺铠（各 0.15%）----
		loot.add(lootEntry(item("minecraft:nether_star", 1, lore2("金色传说！", "gold", true,
				"凋零的余烬凝成的星，落在水里也烧不灭。", "dark_gray")), 0.0015));
		loot.add(lootEntry(item("minecraft:netherite_nautilus_armor", 1, lore2("金色传说！", "gold", true,
				"以深渊为壳、以合金为骨的铠甲，河神亲自为它打捞。", "dark_gray")), 0.0015));
		// ---- 0.19%：龙蛋 ----
		loot.add(lootEntry(item("minecraft:dragon_egg", 1, lore2("谁家的蛋？", "dark_purple", true,
				"末地的风还缠在蛋壳上……母龙大概正在赶来的路上。", "gray")), 0.0019));
		// ---- 0.01%：刷怪笼 ----
		loot.add(lootEntry(item("minecraft:spawner", 1, lore2("不是哥们？", "dark_red", true,
				"牢笼里传来规律的咔哒声，像是什么东西在数你的心跳。", "dark_gray")), 0.0001));
		// ---- 补全项：剩余概率 = 空气（钓到空气）。当前各组合计正好 100%，
		// 因此该项概率为 0、不会触发；如需留概率给空气，需调低其它组概率 ----
		loot.add(lootEntry(item("minecraft:air"), "remaining"));
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

	/** lore 组件：两行（标题 + 故事），标题可加粗，全部关闭默认斜体。 */
	public static Map<String, Object> lore2(String title, String titleColor, boolean titleBold,
			String story, String storyColor) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("minecraft:lore", List.of(line(title, titleColor, titleBold), line(story, storyColor, false)));
		return map;
	}

	public static Map<String, Object> lore2(String title, String titleColor, String story, String storyColor) {
		return lore2(title, titleColor, false, story, storyColor);
	}

	/** 单行 lore（关闭斜体，可指定颜色/粗体）。 */
	public static Map<String, Object> line(String text, String color, boolean bold) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("text", text);
		map.put("italic", false);
		if (color != null) {
			map.put("color", color);
		}
		if (bold) {
			map.put("bold", true);
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
