package mois.buildmart.client;

import mois.buildmart.BuildMart;
import mois.buildmart.fastbuy.FastbuyRequestPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Supplier;

/**
 * 快速投影购买客户端模块（可选增强，见 README）：
 * 通过反射接入 litematica 的 {@code SchematicPickBlockEventHandler}（第三方
 * listener 机制），在「投影中键拾取前」（onSchematicPickBlockPrePick）检查
 * 生存玩家背包是否有对应物品——没有则发送 {@link FastbuyRequestPayload}
 * 请求服务端自动购买一组（服务端 /fastbuy 开启时生效）。
 * <p>
 * litematica 未安装时静默跳过（ClassNotFound）；服务端未注册该 payload 时
 * 不发送（canSend 检查）；不修改 litematica 拾取流程本身（返回默认结果）。
 */
public final class FastbuyClient {
	private static boolean registered = false;

	private FastbuyClient() {
	}

	public static void init() {
		if (registered) {
			return;
		}
		try {
			Class<?> handlerClass = Class.forName(
					"fi.dy.masa.litematica.schematic.pickblock.SchematicPickBlockEventHandler");
			Class<?> listenerIface = Class.forName(
					"fi.dy.masa.litematica.interfaces.ISchematicPickBlockEventListener");
			Class<?> resultEnum = Class.forName(
					"fi.dy.masa.litematica.schematic.pickblock.SchematicPickBlockEventResult");
			Object handler = handlerClass.getMethod("getInstance").invoke(null);
			// 反射取 SUCCESS 枚举（不干扰 litematica 流程的返回值）
			Object success = Enum.valueOf(resultEnum.asSubclass(Enum.class), "SUCCESS");
			Object proxy = Proxy.newProxyInstance(listenerIface.getClassLoader(),
					new Class<?>[]{listenerIface}, (proxyObj, method, args) -> {
						switch (method.getName()) {
							case "getName" -> {
								return (Supplier<String>) () -> "buildmart:fastbuy";
							}
							case "onSchematicPickBlockPrePick" -> {
								// args: (Level schematicWorld, BlockPos pos, BlockState state, ItemStack stack)
								if (args != null && args.length == 4 && args[3] instanceof ItemStack stack) {
									onPickBlock(stack);
								}
								return success;
							}
							case "onSchematicPickBlockStart", "onSchematicPickBlockPreGather" -> {
								return success;
							}
							default -> {
								return null; // void / 其他回调
							}
						}
					});
			Method register = handlerClass.getMethod("registerSchematicPickBlockEventListener", listenerIface);
			register.invoke(handler, proxy);
			registered = true;
			BuildMart.LOGGER.info("已接入 litematica 拾取事件（快速投影购买客户端模块）");
		} catch (ClassNotFoundException e) {
			// litematica 未安装：静默跳过（可选模块）
		} catch (Exception e) {
			BuildMart.LOGGER.warn("接入 litematica 拾取事件失败（快速购买不可用）", e);
		}
	}

	/** 拾取前检查：生存玩家且背包无该物品 → 请求服务端自动购买一组。 */
	private static void onPickBlock(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Player player = mc.player;
		if (player == null || player.isCreative()) {
			return; // 仅生存模式
		}
		Item item = stack.getItem();
		if (playerHasItem(player, item)) {
			return; // 背包有：正常拾取逻辑（litematica 自行切栏/换手）
		}
		if (!ClientPlayNetworking.canSend(FastbuyRequestPayload.TYPE)) {
			return; // 服务端未注册该 payload（未装本模组服务端）：不发
		}
		ClientPlayNetworking.send(new FastbuyRequestPayload(
				BuiltInRegistries.ITEM.getKey(item).toString()));
	}

	/** 背包（含快捷栏）是否已有该物品。 */
	private static boolean playerHasItem(Player player, Item item) {
		var inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			ItemStack s = inventory.getItem(i);
			if (!s.isEmpty() && s.is(item)) {
				return true;
			}
		}
		return false;
	}
}
