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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.ItemEnchantments;

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

	/** 容器（潜影盒/收纳袋）内容物递归计价的最大嵌套深度，防止异常数据导致过深递归。 */
	private static final int MAX_CONTAINER_DEPTH = 8;

	/**
	 * 物品完整价值（分）=（基础价 + 附魔总价 + 容器内容物价值）× 数量。
	 * 附魔按“每级价格 × 等级”累计（含附魔书存储附魔）；容器内容物递归计价，
	 * 每件外层物品都携带相同的内容物，因此内容物按件计入。空物品为 0。
	 * <p>
	 * 耐久：有耐久的物品其“基础价”按剩余耐久比例折算（如 100/200 耐久 = 基础价一半），
	 * 附魔与容器内容物不受影响。
	 * <p>
	 * 附魔书：自身基础价记 0，价值完全来自存储附魔——铁砧把附魔书合并到武器/附魔书
	 * 上时，合并结果的价值恰好等于两件物品价值之和，不发生“少 1 元”。
	 */
	public static long price(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		return satMul(pricePerItem(stack, MAX_CONTAINER_DEPTH), stack.getCount());
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
			total = satAdd(total, satMul(EnchantmentValues.get(entry.getKey()), entry.getIntValue()));
		}
		return total;
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
