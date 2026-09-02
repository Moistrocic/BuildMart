package mois.buildmart.fishing;

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
 * lore 显示信息：每个物品一段贴合它的小故事，按分组配色、关闭默认斜体；
 * 矿物 11 种各自独立的故事。
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
		loot.add(lootEntry(item("minecraft:cod", 1, lore1("它在潮汐里数过一千遍沙子，还是没学会涨价。", "aqua")), 0.10));
		loot.add(lootEntry(item("minecraft:salmon", 1, lore1("逆流而上练就的肌肉，在水桶里毫无用武之地。", "aqua")), 0.10));
		loot.add(lootEntry(item("minecraft:pufferfish", 1, lore1("鼓起勇气的样子很可爱——虽然只能坚持一秒。", "aqua")), 0.10));
		loot.add(lootEntry(item("minecraft:tropical_fish", 1, lore1("彩虹色的鳞片，晒干后连猫都懒得闻。", "aqua")), 0.10));
		// ---- 矿物共享 40%（11 种细分，10 × 0.03636 + 1 × 0.0364）----
		loot.add(lootEntry(item("minecraft:diamond", 1, lore1("河水冲不淡它的锋芒，却冲掉了它的身价。", "gold")), 0.03636));
		loot.add(lootEntry(item("minecraft:emerald", 1, lore1("矿工找了一辈子的绿，它却自己躺进了渔网。", "gold")), 0.03636));
		loot.add(lootEntry(item("minecraft:iron_ingot", 1, lore1("铁匠打了一辈子铁，也没想到铁会自己游过来。", "gold")), 0.03636));
		loot.add(lootEntry(item("minecraft:gold_ingot", 1, lore1("河底的金子，比岸上的更懂得沉默。", "gold")), 0.03636));
		loot.add(lootEntry(item("minecraft:copper_ingot", 1, lore1("氧化得恰到好处，像一枚被河水盘过的铜钱。", "gold")), 0.03636));
		loot.add(lootEntry(item("minecraft:redstone", 1, lore1("河床下藏着一条会发光的脉络，被渔网捞了上来。", "gold")), 0.03636));
		loot.add(lootEntry(item("minecraft:lapis_lazuli", 1, lore1("蓝得像把一整片夜空塞进了矿里。", "gold")), 0.03636));
		loot.add(lootEntry(item("minecraft:quartz", 1, lore1("下界的骨头，被河水洗得发白。", "gold")), 0.03636));
		loot.add(lootEntry(item("minecraft:coal", 1, lore1("一块被水泡软的太阳，还能烧很久。", "gold")), 0.03636));
		loot.add(lootEntry(item("minecraft:netherite_scrap", 1, lore1("从岩浆里逃出来的碎片，怕水，但不怕你。", "gold")), 0.03636));
		loot.add(lootEntry(item("minecraft:netherite_ingot", 1, lore1("连河水都化不开的合金，沉得像河底长了根。", "gold")), 0.0364));
		// ---- 稀有组共享 10%（5 种 × 2%，不详之瓶为五级）----
		Map<String, Object> ominous = lore1("瓶口封着远古的低语，开盖前最好先数好自己的心跳。", "light_purple");
		ominous.put("minecraft:ominous_bottle_amplifier", 4); // 五级不详之瓶（amplifier 0-4）
		loot.add(lootEntry(item("minecraft:ominous_bottle", 1, ominous), 0.02));
		loot.add(lootEntry(item("minecraft:echo_shard", 1, lore1("古城废墟的回声，被河水冲成了碎片。", "light_purple")), 0.02));
		loot.add(lootEntry(item("minecraft:iron_nautilus_armor", 1, lore1("铁与海螺的混血，穿它的人大概听不见海哭。", "light_purple")), 0.02));
		loot.add(lootEntry(item("minecraft:creeper_head", 1, lore1("它盯着你，你也盯着它——先炸的算输。", "light_purple")), 0.02));
		loot.add(lootEntry(item("minecraft:ominous_trial_key", 1, lore1("试炼的回响还挂在钥匙上，每一齿都像在倒数。", "light_purple")), 0.02));
		// ---- 高级组共享 5%（0.0167+0.0167+0.0166）----
		loot.add(lootEntry(item("minecraft:wither_skeleton_skull", 1, lore1("下界的凝视隔着三界传来，礼貌地打了个招呼。", "red")), 0.0167));
		loot.add(lootEntry(item("minecraft:enchanted_golden_apple", 1, lore1("被祝福过的苹果，咬一口连河神都要羡慕。", "red")), 0.0167));
		loot.add(lootEntry(item("minecraft:trident", 1, lore1("溺尸的失落之物，它记得每一场暴风雨。", "red")), 0.0166));
		// ---- 传说组共享 4%（4 种 × 1%）----
		loot.add(lootEntry(item("minecraft:elytra", 1, lore1("龙翼的余温未散，风在耳边说：飞吧。", "dark_purple")), 0.01));
		loot.add(lootEntry(item("minecraft:dragon_head", 1, lore1("末地龙王的旧冠冕，鳞片还带着星辉。", "dark_purple")), 0.01));
		loot.add(lootEntry(item("minecraft:heart_of_the_sea", 1, lore1("深海的心脏仍在跳动，为每一艘迷航的船亮灯。", "dark_purple")), 0.01));
		loot.add(lootEntry(item("minecraft:conduit", 1, lore1("潮涌的核心，据说能给整片海讲一个安眠的故事。", "dark_purple")), 0.01));
		// ---- 0.5%：下界合金斧 ----
		loot.add(lootEntry(item("minecraft:netherite_axe", 1, lore1("一位旅人把它丢进岩浆……它却从河里游了上来。", "dark_red")), 0.005));
		// ---- 0.3%：下界之星 + 下界合金鹦鹉螺铠（各 0.15%，金色粗体）----
		loot.add(lootEntry(item("minecraft:nether_star", 1, lore1("凋零的余烬凝成的星，落在水里也烧不灭。", "gold", true)), 0.0015));
		loot.add(lootEntry(item("minecraft:netherite_nautilus_armor", 1, lore1("以深渊为壳、以合金为骨的铠甲，河神亲自为它打捞。", "gold", true)), 0.0015));
		// ---- 0.19%：龙蛋（粗体）----
		loot.add(lootEntry(item("minecraft:dragon_egg", 1, lore1("末地的风还缠在蛋壳上……母龙大概正在赶来的路上。", "dark_purple", true)), 0.0019));
		// ---- 0.01%：刷怪笼（粗体）----
		loot.add(lootEntry(item("minecraft:spawner", 1, lore1("牢笼里传来规律的咔哒声，像是什么东西在数你的心跳。", "dark_red", true)), 0.0001));
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

	/** lore 组件：单行故事（关闭默认斜体，按分组配色，可加粗）。 */
	public static Map<String, Object> lore1(String text, String color, boolean bold) {
		Map<String, Object> map = new LinkedHashMap<>();
		map.put("minecraft:lore", List.of(line(text, color, bold)));
		return map;
	}

	public static Map<String, Object> lore1(String text, String color) {
		return lore1(text, color, false);
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
