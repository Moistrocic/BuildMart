package mois.economy.fishing;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import mois.economy.Economy;
import net.minecraft.core.RegistryAccess;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 趣味钓鱼战利品：config/economy/fishing.json（JSON 数组，每项
 * {"item": 物品信息（ItemStack.CODEC 格式，可含 count/components）, "chance": 概率}）。
 * <p>
 * 概率规则：普通项概率为 (0, 1] 的数字，全部普通项之和 ≤ 1（不能大于 1）；
 * 可含至多一个补全项（"chance": "remaining"），其概率 = 1 - 普通项之和，
 * 用于把剩余概率补全给某个物品。无补全项且概率和 < 1 时，剩余概率 = 钓不到东西。
 * <p>
 * 开关见 {@link mois.economy.config.EconomyConfig#funFishing()}（/config 可热重载）：
 * 关闭时钓鱼走原版战利品表，开启时由 FishingHookMixin 替换为按概率随机选取的配置战利品。
 * 配置非法（概率和 > 1、概率 ≤ 0、多个补全项等）或加载失败时视为未配置，回退原版。
 */
public final class FishingManager {
	/** 补全项标记：概率 = 1 - 普通项概率之和。 */
	private static final String FILL_MARKER = "remaining";

	private record Entry(ItemStack stack, double chance) {
	}

	/** 配置解析结果缓存（服务器生命周期内不变；改配置需重启）。 */
	private static final List<Entry> ENTRIES = new ArrayList<>();
	private static boolean loaded = false;

	private FishingManager() {
	}

	/** 加载配置（SERVER_STARTED 时调用，需要已就绪的 RegistryAccess 解析物品）。 */
	public static void load(Path configDir, RegistryAccess registryAccess) {
		synchronized (ENTRIES) {
			ENTRIES.clear();
			loaded = false;
			Path file = configDir.resolve("economy").resolve("fishing.json");
			try {
				Files.createDirectories(file.getParent());
				if (!Files.exists(file)) {
					Files.writeString(file, FishingInitialLoot.INITIAL_JSON, StandardCharsets.UTF_8);
					Economy.LOGGER.info("趣味钓鱼配置不存在，已生成默认配置 {}", file);
				}
				JsonArray array = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8)).getAsJsonArray();
				com.mojang.serialization.DynamicOps<JsonElement> ops = registryAccess.createSerializationContext(JsonOps.INSTANCE);
				double sum = 0;
				ItemStack fillStack = null;
				for (JsonElement element : array) {
					JsonObject obj = element.getAsJsonObject();
					ItemStack stack = ItemStack.CODEC.parse(ops, obj.get("item")).getOrThrow();
					JsonElement chanceEl = obj.get("chance");
					if (chanceEl.isJsonPrimitive() && chanceEl.getAsJsonPrimitive().isString()) {
						// 补全项：chance 为 "remaining"
						if (!FILL_MARKER.equals(chanceEl.getAsString())) {
							throw new IllegalArgumentException("无法识别的概率值：" + chanceEl.getAsString());
						}
						if (fillStack != null) {
							throw new IllegalArgumentException("补全项（chance: \"remaining\"）只能有一个");
						}
						fillStack = stack;
						continue;
					}
					double chance = chanceEl.getAsDouble();
					if (chance <= 0 || chance > 1) {
						throw new IllegalArgumentException("概率必须在 (0, 1] 之间：" + chance);
					}
					ENTRIES.add(new Entry(stack, chance));
					sum += chance;
				}
				if (sum > 1.0) {
					throw new IllegalArgumentException("普通项概率之和为 " + sum + "，不能大于 1");
				}
				if (fillStack != null) {
					// 补全项补足剩余概率；普通项之和已为 1 时补全项概率为 0（忽略）
					double fillChance = 1.0 - sum;
					if (fillChance > 0) {
						ENTRIES.add(new Entry(fillStack, fillChance));
						sum = 1.0;
					}
				}
				loaded = true;
				Economy.LOGGER.info("趣味钓鱼战利品已加载：{}（{} 项，总概率 {}）", file, ENTRIES.size(), sum);
			} catch (IOException | RuntimeException e) {
				Economy.LOGGER.error("趣味钓鱼配置加载失败，回退原版钓鱼", e);
				ENTRIES.clear();
			}
		}
	}

	/**
	 * 按概率随机选取一个战利品（副本）。
	 * 返回 null = 配置未加载/非法（调用方回退原版）；
	 * 返回 ItemStack.EMPTY = 本次未命中任何战利品（正常收竿，无掉落）。
	 */
	public static ItemStack roll(RandomSource random) {
		synchronized (ENTRIES) {
			if (!loaded || ENTRIES.isEmpty()) {
				return null;
			}
			double r = random.nextDouble();
			double acc = 0;
			for (Entry entry : ENTRIES) {
				acc += entry.chance();
				if (r < acc) {
					return entry.stack().copy();
				}
			}
			return ItemStack.EMPTY;
		}
	}
}
