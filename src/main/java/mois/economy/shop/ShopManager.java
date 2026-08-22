package mois.economy.shop;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import mois.economy.Economy;
import mois.economy.Money;
import mois.economy.config.ItemValues;
import mois.economy.data.EconomyDb;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 商店管理：内存索引 + JSON 持久化（世界目录 economy-shops.json）。
 * 每 20 tick（1 秒）处理一次：区块未加载时跳过（节省资源）；
 * 箱子开启期间锁定倒计时，关闭瞬间倒计时归零立即结算出售。
 */
public final class ShopManager {
	public static final int RESET_TICKS = 1200; // 60 秒

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Map<ShopKey, Shop> SHOPS = new HashMap<>();
	private static Path storagePath;

	private ShopManager() {
	}

	public record ShopKey(ResourceKey<Level> dimension, BlockPos pos) {
	}

	public static void init(Path worldDir) {
		storagePath = worldDir.resolve("economy-shops.json");
		SHOPS.clear();
		load();
	}

	public static Shop get(ResourceKey<Level> dimension, BlockPos pos) {
		return SHOPS.get(new ShopKey(dimension, pos));
	}

	public static Shop create(ServerLevel level, BlockPos pos, ServerPlayer owner) {
		Shop shop = new Shop(level.dimension(), pos, owner.getUUID(), owner.getGameProfile().name(),
				owner.getUUID(), owner.getGameProfile().name(), RESET_TICKS);
		SHOPS.put(new ShopKey(level.dimension(), pos), shop);
		spawnDisplay(level, shop);
		save();
		Economy.LOGGER.info("商店已创建：{} {}（所有人 {}）", dimensionString(level.dimension()), pos, owner.getGameProfile().name());
		return shop;
	}

	public static void remove(Shop shop, ServerLevel level) {
		SHOPS.remove(new ShopKey(shop.dimension(), shop.pos()));
		if (shop.displayUuid() != null) {
			Entity display = level.getEntity(shop.displayUuid());
			if (display != null) {
				display.discard();
			}
		}
		save();
		Economy.LOGGER.info("商店已移除：{} {}", dimensionString(shop.dimension()), shop.pos());
	}

	public static void setPayee(Shop shop, UUID payee, String payeeName) {
		shop.setPayee(payee, payeeName);
		save();
	}

	/** 服务器每 tick 调用；每秒处理一次商店。 */
	public static void onServerTick(MinecraftServer server) {
		if (SHOPS.isEmpty() || server.getTickCount() % 20 != 0) {
			return;
		}
		for (Shop shop : new ArrayList<>(SHOPS.values())) {
			ServerLevel level = server.getLevel(shop.dimension());
			if (level == null || !level.isPositionEntityTicking(shop.pos())) {
				continue; // 区块未加载：暂不处理，节省资源
			}
			boolean open = isChestOpen(level, shop.pos());
			if (open) {
				shop.setWasOpen(true);
				updateDisplay(level, shop);
				continue; // 开启期间锁定倒计时
			}
			if (shop.wasOpen()) {
				shop.setWasOpen(false);
				shop.setRemainingTicks(0); // 关闭瞬间倒计时归零，立即出售
			}
			shop.setRemainingTicks(shop.remainingTicks() - 20);
			if (shop.remainingTicks() <= 0) {
				sell(level, shop);
			}
			updateDisplay(level, shop);
		}
	}

	/** 出售：清空箱子（含双箱另一半），物品价值结算给收款人（不通知）。 */
	private static void sell(ServerLevel level, Shop shop) {
		long total = 0;
		List<Container> containers = chestContainers(level, shop.pos());
		for (Container container : containers) {
			for (int i = 0; i < container.getContainerSize(); i++) {
				ItemStack stack = container.getItem(i);
				if (stack.isEmpty()) {
					continue;
				}
				total = satAdd(total, satMul(ItemValues.get(stack.getItem()), stack.getCount()));
			}
			container.clearContent();
		}
		if (total > 0) {
			EconomyDb.credit(shop.payee(), shop.payeeName(), total);
		}
		shop.setRemainingTicks(RESET_TICKS);
		save();
		Economy.LOGGER.info("商店出售结算：{} {} → {}（{} 元）", dimensionString(shop.dimension()), shop.pos(),
				shop.payeeName(), Money.format(total));
	}

	/** 目标箱子及其双箱另一半（同类型水平相邻箱子）的容器列表。 */
	private static List<Container> chestContainers(ServerLevel level, BlockPos pos) {
		List<Container> result = new ArrayList<>();
		BlockEntity be = level.getBlockEntity(pos);
		if (be instanceof Container container) {
			result.add(container);
		}
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos neighbor = pos.relative(dir);
			if (level.getBlockState(neighbor).is(level.getBlockState(pos).getBlock())) {
				BlockEntity neighborBe = level.getBlockEntity(neighbor);
				if (neighborBe instanceof Container neighborContainer) {
					result.add(neighborContainer);
				}
			}
		}
		return result;
	}

	/** 箱子是否处于开启状态：本箱 + 双箱另一半的开启人数（多人同时开启计入）。 */
	private static boolean isChestOpen(ServerLevel level, BlockPos pos) {
		if (ChestBlockEntity.getOpenCount(level, pos) > 0) {
			return true;
		}
		for (Direction dir : Direction.Plane.HORIZONTAL) {
			BlockPos neighbor = pos.relative(dir);
			if (level.getBlockState(neighbor).is(level.getBlockState(pos).getBlock())
					&& ChestBlockEntity.getOpenCount(level, neighbor) > 0) {
				return true;
			}
		}
		return false;
	}

	/** 更新箱子上方的悬浮字（所有人/收款人/刷新倒计时）；实体丢失时重建。 */
	private static void updateDisplay(ServerLevel level, Shop shop) {
		Display.TextDisplay display = null;
		if (shop.displayUuid() != null) {
			Entity entity = level.getEntity(shop.displayUuid());
			if (entity instanceof Display.TextDisplay textDisplay) {
				display = textDisplay;
			}
		}
		if (display == null) {
			spawnDisplay(level, shop);
			return;
		}
		display.setText(buildText(shop));
	}

	private static void spawnDisplay(ServerLevel level, Shop shop) {
		Display.TextDisplay display = new Display.TextDisplay(EntityTypes.TEXT_DISPLAY, level);
		display.setPos(shop.pos().getX() + 0.5, shop.pos().getY() + 1.6, shop.pos().getZ() + 0.5);
		display.setBillboardConstraints(Display.BillboardConstraints.CENTER);
		display.setText(buildText(shop));
		level.addFreshEntity(display);
		shop.setDisplayUuid(display.getUUID());
	}

	private static Component buildText(Shop shop) {
		int seconds = Math.max(0, shop.remainingTicks() / 20);
		return Component.literal("所有人：" + shop.ownerName()
				+ "\n收款人：" + shop.payeeName()
				+ "\n刷新：" + seconds + " 秒").withStyle(ChatFormatting.GOLD);
	}

	// ---------- 持久化 ----------

	private static void load() {
		if (!Files.exists(storagePath)) {
			return;
		}
		try {
			JsonArray array = JsonParser.parseString(Files.readString(storagePath, StandardCharsets.UTF_8))
					.getAsJsonObject().getAsJsonArray("shops");
			for (com.google.gson.JsonElement element : array) {
				JsonObject obj = element.getAsJsonObject();
				ResourceKey<Level> dimension = decodeDimension(obj.get("dimension"));
				BlockPos pos = new BlockPos(obj.get("x").getAsInt(), obj.get("y").getAsInt(), obj.get("z").getAsInt());
				Shop shop = new Shop(dimension, pos,
						UUID.fromString(obj.get("owner").getAsString()),
						obj.get("ownerName").getAsString(),
						UUID.fromString(obj.get("payee").getAsString()),
						obj.get("payeeName").getAsString(),
						Math.min(RESET_TICKS, Math.max(0, obj.get("remainingSeconds").getAsInt() * 20)));
				if (obj.has("displayUuid")) {
					shop.setDisplayUuid(UUID.fromString(obj.get("displayUuid").getAsString()));
				}
				SHOPS.put(new ShopKey(dimension, pos), shop);
			}
			Economy.LOGGER.info("商店已加载：{} 个", SHOPS.size());
		} catch (Exception e) {
			Economy.LOGGER.error("商店数据加载失败，跳过", e);
			SHOPS.clear();
		}
	}

	public static void save() {
		if (storagePath == null) {
			return;
		}
		JsonArray array = new JsonArray();
		for (Shop shop : SHOPS.values()) {
			JsonObject obj = new JsonObject();
			obj.add("dimension", encodeDimension(shop.dimension()));
			obj.addProperty("x", shop.pos().getX());
			obj.addProperty("y", shop.pos().getY());
			obj.addProperty("z", shop.pos().getZ());
			obj.addProperty("owner", shop.owner().toString());
			obj.addProperty("ownerName", shop.ownerName());
			obj.addProperty("payee", shop.payee().toString());
			obj.addProperty("payeeName", shop.payeeName());
			obj.addProperty("remainingSeconds", Math.max(0, shop.remainingTicks() / 20));
			if (shop.displayUuid() != null) {
				obj.addProperty("displayUuid", shop.displayUuid().toString());
			}
			array.add(obj);
		}
		JsonObject root = new JsonObject();
		root.add("shops", array);
		try {
			Files.createDirectories(storagePath.getParent());
			Files.writeString(storagePath, GSON.toJson(root), StandardCharsets.UTF_8);
		} catch (IOException e) {
			Economy.LOGGER.error("商店数据保存失败", e);
		}
	}

	// ---------- 工具 ----------

	private static JsonElement encodeDimension(ResourceKey<Level> key) {
		return ResourceKey.codec(Registries.DIMENSION).encodeStart(JsonOps.INSTANCE, key).getOrThrow();
	}

	private static ResourceKey<Level> decodeDimension(JsonElement element) {
		return ResourceKey.codec(Registries.DIMENSION).parse(JsonOps.INSTANCE, element).getOrThrow();
	}

	private static String dimensionString(ResourceKey<Level> key) {
		return encodeDimension(key).getAsString();
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
}
