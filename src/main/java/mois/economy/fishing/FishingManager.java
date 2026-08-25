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
 * {"item": 物品信息（ItemStack.CODEC 格式，可含 count/components）, "chance": 概率}，
 * 概率之和应为 1）。首次启动由 {@link FishingInitialLoot} 生成默认配置。
 * <p>
 * 开关见 {@link mois.economy.config.EconomyConfig#funFishing()}（/config 可热重载）：
 * 关闭时钓鱼走原版战利品表，开启时由 FishingHookMixin 替换为按概率随机选取的配置战利品。
 * 配置概率和非法或加载失败时视为未配置，回退原版。
 */
public final class FishingManager {
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
				for (JsonElement element : array) {
					JsonObject obj = element.getAsJsonObject();
					ItemStack stack = ItemStack.CODEC.parse(ops, obj.get("item")).getOrThrow();
					double chance = obj.get("chance").getAsDouble();
					if (chance <= 0) {
						throw new IllegalArgumentException("概率必须大于 0：" + chance);
					}
					ENTRIES.add(new Entry(stack, chance));
					sum += chance;
				}
				if (Math.abs(sum - 1.0) > 0.001) {
					Economy.LOGGER.error("趣味钓鱼配置概率之和为 {}，应为 1（共 {} 项），本次回退原版钓鱼", sum, ENTRIES.size());
					ENTRIES.clear();
					return;
				}
				loaded = true;
				Economy.LOGGER.info("趣味钓鱼战利品已加载：{}（{} 项，概率和 {}）", file, ENTRIES.size(), sum);
			} catch (IOException | RuntimeException e) {
				Economy.LOGGER.error("趣味钓鱼配置加载失败，回退原版钓鱼", e);
				ENTRIES.clear();
			}
		}
	}

	/** 按概率随机选取一个战利品（副本）；未配置/加载失败返回 null（调用方回退原版）。 */
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
			return ENTRIES.get(ENTRIES.size() - 1).stack().copy();
		}
	}
}
