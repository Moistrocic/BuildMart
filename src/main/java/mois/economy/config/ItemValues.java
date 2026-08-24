package mois.economy.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import mois.economy.Economy;
import mois.economy.Money;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.Fireworks;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.OminousBottleAmplifier;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 物品价值配置：config/economy/items.json，键为物品 ID（如 minecraft:dirt），
 * 值为十进制元字符串（如 "0.10"），解析为整数分存储；不在配置表中的物品默认
 * 不可交易（-1，即后续新增的物品默认不可购买/出售）。
 */
public final class ItemValues {
	/** 不可交易标记：配置价为 -1 的物品不能购买也不能卖出。 */
	public static final long UNTRADEABLE = -1L;

	/** 未配置物品的默认价：-1 = 不可交易（后续新增的物品默认不可购买/出售）。 */
	public static final long DEFAULT_CENTS = UNTRADEABLE;

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<String, Long> VALUES = new HashMap<>();
	/** 药水价格（分）：由酿造配方链推导（离线计算）。key = "形态-药水"，如 potion-swiftness / splash_potion-swiftness / lingering_potion-swiftness。 */
	private static final Map<String, Long> POTION_PRICES = buildPotionPrices();
	private static Map<String, Long> buildPotionPrices() {
		Map<String, Long> map = new HashMap<>(160);
		map.put("lingering_potion-awkward", 2325L);
		map.put("lingering_potion-fire_resistance", 2625L);
		map.put("lingering_potion-harming", 2415L);
		map.put("lingering_potion-healing", 2627L);
		map.put("lingering_potion-infested", 2500L);
		map.put("lingering_potion-invisibility", 2677L);
		map.put("lingering_potion-leaping", 2825L);
		map.put("lingering_potion-long_fire_resistance", 2725L);
		map.put("lingering_potion-long_invisibility", 2777L);
		map.put("lingering_potion-long_leaping", 2925L);
		map.put("lingering_potion-long_night_vision", 2717L);
		map.put("lingering_potion-long_poison", 2455L);
		map.put("lingering_potion-long_regeneration", 4425L);
		map.put("lingering_potion-long_slow_falling", 2925L);
		map.put("lingering_potion-long_slowness", 2505L);
		map.put("lingering_potion-long_strength", 2675L);
		map.put("lingering_potion-long_swiftness", 2445L);
		map.put("lingering_potion-long_turtle_master", 3425L);
		map.put("lingering_potion-long_water_breathing", 2485L);
		map.put("lingering_potion-long_weakness", 2285L);
		map.put("lingering_potion-luck", 2500L);
		map.put("lingering_potion-mundane", 2145L);
		map.put("lingering_potion-night_vision", 2617L);
		map.put("lingering_potion-oozing", 2500L);
		map.put("lingering_potion-poison", 2355L);
		map.put("lingering_potion-regeneration", 4325L);
		map.put("lingering_potion-slow_falling", 2825L);
		map.put("lingering_potion-slowness", 2405L);
		map.put("lingering_potion-strength", 2575L);
		map.put("lingering_potion-strong_harming", 2515L);
		map.put("lingering_potion-strong_healing", 2727L);
		map.put("lingering_potion-strong_leaping", 2925L);
		map.put("lingering_potion-strong_poison", 2455L);
		map.put("lingering_potion-strong_regeneration", 4425L);
		map.put("lingering_potion-strong_slowness", 2505L);
		map.put("lingering_potion-strong_strength", 2675L);
		map.put("lingering_potion-strong_swiftness", 2445L);
		map.put("lingering_potion-strong_turtle_master", 3425L);
		map.put("lingering_potion-swiftness", 2345L);
		map.put("lingering_potion-thick", 2225L);
		map.put("lingering_potion-turtle_master", 3325L);
		map.put("lingering_potion-water", 2125L);
		map.put("lingering_potion-water_breathing", 2385L);
		map.put("lingering_potion-weakness", 2185L);
		map.put("lingering_potion-weaving", 2500L);
		map.put("lingering_potion-wind_charged", 2500L);
		map.put("potion-awkward", 225L);
		map.put("potion-fire_resistance", 525L);
		map.put("potion-harming", 315L);
		map.put("potion-healing", 527L);
		map.put("potion-infested", 400L);
		map.put("potion-invisibility", 577L);
		map.put("potion-leaping", 725L);
		map.put("potion-long_fire_resistance", 625L);
		map.put("potion-long_invisibility", 677L);
		map.put("potion-long_leaping", 825L);
		map.put("potion-long_night_vision", 617L);
		map.put("potion-long_poison", 355L);
		map.put("potion-long_regeneration", 2325L);
		map.put("potion-long_slow_falling", 825L);
		map.put("potion-long_slowness", 405L);
		map.put("potion-long_strength", 575L);
		map.put("potion-long_swiftness", 345L);
		map.put("potion-long_turtle_master", 1325L);
		map.put("potion-long_water_breathing", 385L);
		map.put("potion-long_weakness", 185L);
		map.put("potion-luck", 400L);
		map.put("potion-mundane", 45L);
		map.put("potion-night_vision", 517L);
		map.put("potion-oozing", 400L);
		map.put("potion-poison", 255L);
		map.put("potion-regeneration", 2225L);
		map.put("potion-slow_falling", 725L);
		map.put("potion-slowness", 305L);
		map.put("potion-strength", 475L);
		map.put("potion-strong_harming", 415L);
		map.put("potion-strong_healing", 627L);
		map.put("potion-strong_leaping", 825L);
		map.put("potion-strong_poison", 355L);
		map.put("potion-strong_regeneration", 2325L);
		map.put("potion-strong_slowness", 405L);
		map.put("potion-strong_strength", 575L);
		map.put("potion-strong_swiftness", 345L);
		map.put("potion-strong_turtle_master", 1325L);
		map.put("potion-swiftness", 245L);
		map.put("potion-thick", 125L);
		map.put("potion-turtle_master", 1225L);
		map.put("potion-water", 25L);
		map.put("potion-water_breathing", 285L);
		map.put("potion-weakness", 85L);
		map.put("potion-weaving", 400L);
		map.put("potion-wind_charged", 400L);
		map.put("splash_potion-awkward", 325L);
		map.put("splash_potion-fire_resistance", 625L);
		map.put("splash_potion-harming", 415L);
		map.put("splash_potion-healing", 627L);
		map.put("splash_potion-infested", 500L);
		map.put("splash_potion-invisibility", 677L);
		map.put("splash_potion-leaping", 825L);
		map.put("splash_potion-long_fire_resistance", 725L);
		map.put("splash_potion-long_invisibility", 777L);
		map.put("splash_potion-long_leaping", 925L);
		map.put("splash_potion-long_night_vision", 717L);
		map.put("splash_potion-long_poison", 455L);
		map.put("splash_potion-long_regeneration", 2425L);
		map.put("splash_potion-long_slow_falling", 925L);
		map.put("splash_potion-long_slowness", 505L);
		map.put("splash_potion-long_strength", 675L);
		map.put("splash_potion-long_swiftness", 445L);
		map.put("splash_potion-long_turtle_master", 1425L);
		map.put("splash_potion-long_water_breathing", 485L);
		map.put("splash_potion-long_weakness", 285L);
		map.put("splash_potion-luck", 500L);
		map.put("splash_potion-mundane", 145L);
		map.put("splash_potion-night_vision", 617L);
		map.put("splash_potion-oozing", 500L);
		map.put("splash_potion-poison", 355L);
		map.put("splash_potion-regeneration", 2325L);
		map.put("splash_potion-slow_falling", 825L);
		map.put("splash_potion-slowness", 405L);
		map.put("splash_potion-strength", 575L);
		map.put("splash_potion-strong_harming", 515L);
		map.put("splash_potion-strong_healing", 727L);
		map.put("splash_potion-strong_leaping", 925L);
		map.put("splash_potion-strong_poison", 455L);
		map.put("splash_potion-strong_regeneration", 2425L);
		map.put("splash_potion-strong_slowness", 505L);
		map.put("splash_potion-strong_strength", 675L);
		map.put("splash_potion-strong_swiftness", 445L);
		map.put("splash_potion-strong_turtle_master", 1425L);
		map.put("splash_potion-swiftness", 345L);
		map.put("splash_potion-thick", 225L);
		map.put("splash_potion-turtle_master", 1325L);
		map.put("splash_potion-water", 125L);
		map.put("splash_potion-water_breathing", 385L);
		map.put("splash_potion-weakness", 185L);
		map.put("splash_potion-weaving", 500L);
		map.put("splash_potion-wind_charged", 500L);
		return map;
	}

	private ItemValues() {
	}

	/** 从 config 目录加载；文件不存在时按初始定价表（{@link ItemInitialPrices}）+ 注册表全量生成。失败时回退为全默认不可交易。 */
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
			// 合并缺失：初始定价表新增的物品（26.3 数据驱动注册表）补入配置并写回，
			// 旧配置不会因为缺少新物品条目而全部落到默认不可交易
			boolean changed = false;
			for (Map.Entry<String, String> entry : ItemInitialPrices.INITIAL.entrySet()) {
				if (!parsed.containsKey(entry.getKey())) {
					parsed.put(entry.getKey(), parseCents(entry.getValue()));
					changed = true;
				}
			}
			// 迁移：本版本修正的初始价强制覆盖旧配置值（用户已确认的新价格）
			for (String id : MIGRATED_IDS) {
				String init = ItemInitialPrices.INITIAL.get(id);
				if (init != null && parsed.containsKey(id)) {
					long initCents = parseCents(init);
					if (parsed.get(id) != initCents) {
						parsed.put(id, initCents);
						changed = true;
					}
				}
			}
			if (changed) {
				writeJson(file, parsed);
				Economy.LOGGER.info("物品价值配置已补充/迁移新条目，已写回 {}", file);
			}
			synchronized (VALUES) {
				VALUES.clear();
				VALUES.putAll(parsed);
			}
			Economy.LOGGER.info("物品价值配置已加载：{}（{} 项，未配置物品默认不可交易）", file, VALUES.size());
		} catch (Exception e) {
			Economy.LOGGER.error("物品价值配置加载失败，全部回退为默认不可交易", e);
			synchronized (VALUES) {
				VALUES.clear();
			}
		}
	}

	/** 本版本强制迁移的初始价（旧配置值被覆盖，一次性）：鸡蛋变体 1.00 -> 0.20；风弹 1.00 -> 10.00。 */
	private static final List<String> MIGRATED_IDS = List.of(
			"minecraft:blue_egg", "minecraft:brown_egg", "minecraft:wind_charge");

	/** 把价格表写回配置 JSON（与 writeDefaults 同一格式）。 */
	private static void writeJson(Path file, Map<String, Long> values) throws IOException {
		JsonObject root = new JsonObject();
		for (String id : values.keySet().stream().sorted().toList()) {
			root.addProperty(id, Money.format(values.get(id)));
		}
		Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
	}

	public static long get(net.minecraft.world.item.Item item) {
		return get(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item));
	}

	public static long get(net.minecraft.resources.Identifier id) {
		synchronized (VALUES) {
			return VALUES.getOrDefault(id.toString(), DEFAULT_CENTS);
		}
	}

	/** 容器（潜影盒/收纳袋）内容物递归计价的最大嵌套深度，防止异常数据导致过深递归。 */
	private static final int MAX_CONTAINER_DEPTH = 8;

	/**
	 * 物品完整价值（分）=（基础价 + 附魔总价 + 容器内容物价值）× 数量。
	 * 附魔按“1 级价格 × 2^(等级-1)”累计（含附魔书存储附魔，与两本低级附魔书
	 * 合成一本高级附魔书的价值守恒一致）；容器内容物递归计价，
	 * 每件外层物品都携带相同的内容物，因此内容物按件计入。空物品为 0。
	 * <p>
	 * 耐久：有耐久的物品其“基础价”按剩余耐久比例折算（如 100/200 耐久 = 基础价一半），
	 * 附魔与容器内容物不受影响。
	 * <p>
	 * 附魔书：自身基础价记 0，价值完全来自存储附魔——铁砧把附魔书合并到武器/附魔书
	 * 上时，合并结果的价值恰好等于两件物品价值之和。
	 * <p>
	 * 不可交易：基础价或任意容器内容物为 -1 时整体返回 {@link #UNTRADEABLE}。
	 */
	public static long price(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		if (!isTradable(stack)) {
			return UNTRADEABLE;
		}
		return satMul(pricePerItem(stack, MAX_CONTAINER_DEPTH), stack.getCount());
	}

	/** 物品（含容器内容物）是否可交易：基础价与内容物均不得为 -1。 */
	public static boolean isTradable(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return true;
		}
		if (!isTradable(stack.getItem())) {
			return false;
		}
		ItemContainerContents container = stack.get(DataComponents.CONTAINER);
		if (container != null) {
			for (ItemStack inner : container.nonEmptyItemCopyStream().toList()) {
				if (!isTradable(inner)) {
					return false;
				}
			}
		}
		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
		if (bundle != null) {
			for (ItemStack inner : bundle.itemCopies().toList()) {
				if (!isTradable(inner)) {
					return false;
				}
			}
		}
		return true;
	}

	/**
	 * 单个物品的完整价值（不含数量倍乘，含附魔与容器内容物）；
	 * 用于展示“单价”——与数量无关，保证同种物品不同数量时
	 * lore 完全一致，可以正常堆叠。不可交易返回 {@link #UNTRADEABLE}。
	 */
	public static long unitPrice(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		if (!isTradable(stack)) {
			return UNTRADEABLE;
		}
		return pricePerItem(stack, MAX_CONTAINER_DEPTH);
	}

	/** 物品类型是否可交易（配置价不是 -1）。 */
	public static boolean isTradable(net.minecraft.world.item.Item item) {
		return get(item) >= 0;
	}

	/** 单件物品的完整价值（不含本层数量），容器内容物递归。 */
	private static long pricePerItem(ItemStack stack, int depth) {
		long total;
		if (stack.is(Items.ENCHANTED_BOOK)) {
			total = 0;
		} else {
			total = get(stack.getItem());
			// 基础价按剩余耐久比例折算：100/200 耐久 → 基础价的一半
			if (stack.isDamageableItem() && stack.getMaxDamage() > 0) {
				int maxDamage = stack.getMaxDamage();
				int damage = Math.min(Math.max(stack.getDamageValue(), 0), maxDamage);
				total = satMul(total, maxDamage - damage) / maxDamage;
			}
		}
		// 药水/喷溅/滞留/药水箭：按酿造配方链定价（标准药水组件覆盖基础价）；
		// 自定义效果（无标准药水）或表外药水回退到物品基础价
		PotionContents potionContents = stack.get(DataComponents.POTION_CONTENTS);
		if (potionContents != null && potionContents.potion().isPresent()) {
			Long base = POTION_PRICES.get("potion-"
					+ potionContents.potion().get().getRegisteredName().replace("minecraft:", ""));
			if (base != null) {
				if (stack.is(Items.SPLASH_POTION)) {
					// 喷溅 = 药水 + 火药
					total = base + 100;
				} else if (stack.is(Items.LINGERING_POTION)) {
					// 滞留 = 喷溅 + 龙息（酿造链已含火药与龙息）
					total = base + 2100;
				} else if (stack.is(Items.TIPPED_ARROW)) {
					// 药水箭：8 箭 + 1 滞留药水 -> 8 支（向上取整）
					total = get(Items.ARROW) + (base + 2100 + 7) / 8;
				} else {
					total = base;
				}
			}
		}
		// 烟花：按飞行等级定价（纸 + 火药 × 等级），三等级价格不同
		if (stack.is(Items.FIREWORK_ROCKET)) {
			Fireworks fireworks = stack.get(DataComponents.FIREWORKS);
			if (fireworks != null) {
				int flight = Math.max(1, Math.min(3, fireworks.flightDuration()));
				total = 20 + 100L * flight;
			}
		}
		// 不详之瓶：10 元 × 等级（amplifier 0 = I 级）
		if (stack.is(Items.OMINOUS_BOTTLE)) {
			OminousBottleAmplifier amplifier = stack.get(DataComponents.OMINOUS_BOTTLE_AMPLIFIER);
			if (amplifier != null) {
				total = 1000L * (Math.max(0, amplifier.value()) + 1);
			}
		}
		ItemEnchantments ench = stack.get(DataComponents.ENCHANTMENTS);
		if (ench != null) {
			total = addEnchantments(total, ench);
		}
		ItemEnchantments stored = stack.get(DataComponents.STORED_ENCHANTMENTS);
		if (stored != null) {
			total = addEnchantments(total, stored);
		}
		if (depth > 0) {
			ItemContainerContents container = stack.get(DataComponents.CONTAINER);
			if (container != null) {
				for (ItemStack inner : container.nonEmptyItemCopyStream().toList()) {
					total = satAdd(total, satMul(pricePerItem(inner, depth - 1), inner.getCount()));
				}
			}
			BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
			if (bundle != null) {
				for (ItemStack inner : bundle.itemCopies().toList()) {
					total = satAdd(total, satMul(pricePerItem(inner, depth - 1), inner.getCount()));
				}
			}
		}
		return total;
	}

	private static long addEnchantments(long total, ItemEnchantments enchantments) {
		for (Object2IntMap.Entry<Holder<Enchantment>> entry : enchantments.entrySet()) {
			// 1 级价格为基础，每升 1 级翻倍（原版由两本低级附魔书合成一本高级）
			long levelOne = EnchantmentValues.get(entry.getKey());
			int level = Math.max(1, entry.getIntValue());
			total = satAdd(total, satPow2Mul(levelOne, level - 1));
		}
		return total;
	}

	/** base × 2^exp（饱和运算）。 */
	private static long satPow2Mul(long base, int exp) {
		if (base <= 0 || exp <= 0) {
			return base;
		}
		long value = base;
		for (int i = 0; i < exp && i < 62; i++) {
			if (value > Long.MAX_VALUE / 2) {
				return Long.MAX_VALUE;
			}
			value *= 2;
		}
		return value;
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

	/** 首次生成配置：写入全部已注册物品，表内物品用初始定价（{@link ItemInitialPrices}），表外物品默认不可交易（-1.00）。 */
	private static void writeDefaults(Path file) throws IOException {
		JsonObject root = new JsonObject();
		List<String> ids = new ArrayList<>();
		for (Identifier id : BuiltInRegistries.ITEM.keySet()) {
			ids.add(id.toString());
		}
		ids.sort(String::compareTo);
		for (String id : ids) {
			root.addProperty(id, ItemInitialPrices.INITIAL.getOrDefault(id, Money.format(DEFAULT_CENTS)));
		}
		Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
	}

	/** 解析非负十进制元字符串为分（最多两位小数）；"-1"/"-1.00" 返回 {@link #UNTRADEABLE}，其它非法值抛异常。 */
	private static long parseCents(String input) {
		BigDecimal value = new BigDecimal(input.trim());
		if (value.compareTo(BigDecimal.ONE.negate()) == 0) {
			return UNTRADEABLE;
		}
		if (value.signum() < 0) {
			throw new NumberFormatException("价格不能为负：" + input);
		}
		if (value.scale() > 2) {
			throw new NumberFormatException("价格最多两位小数：" + input);
		}
		return value.movePointRight(2).longValueExact();
	}
}
