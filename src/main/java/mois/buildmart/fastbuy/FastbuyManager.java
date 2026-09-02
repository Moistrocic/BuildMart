package mois.buildmart.fastbuy;

import mois.buildmart.config.EconomyConfig;
import mois.buildmart.command.BalshopCommands;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 快速投影购买（/fastbuy）服务端处理：
 * 注册 C2S payload {@link FastbuyRequestPayload}（客户端在投影拾取失败时发送）。
 * /fastbuy 关闭时直接忽略请求，不修改任何逻辑；开启时校验物品可交易后
 * 自动购买一组（该物品最大堆叠数量），复用 {@link BalshopCommands#purchase}。
 */
public final class FastbuyManager {
	private FastbuyManager() {
	}

	public static void register() {
		PayloadTypeRegistry.serverboundPlay().register(FastbuyRequestPayload.TYPE, FastbuyRequestPayload.CODEC);
		ServerPlayNetworking.registerGlobalReceiver(FastbuyRequestPayload.TYPE, FastbuyManager::handle);
	}

	private static void handle(FastbuyRequestPayload payload, ServerPlayNetworking.Context context) {
		// 网络线程收到请求，切到服务器主线程处理
		context.server().execute(() -> {
			ServerPlayer player = context.player();
			if (player == null || !EconomyConfig.fastbuy()) {
				return; // 关闭时忽略，不修改任何逻辑
			}
			Identifier id = Identifier.tryParse(payload.itemId());
			if (id == null) {
				return;
			}
			Item item = BuiltInRegistries.ITEM.getValue(id);
			if (item == null || item == Items.AIR) {
				return;
			}
			if (!mois.buildmart.config.ItemValues.isTradable(item)) {
				return;
			}
			ItemStack stack = new ItemStack(BuiltInRegistries.ITEM.wrapAsHolder(item),
					new ItemStack(item).getMaxStackSize());
			String error = BalshopCommands.purchase(player, stack);
			if (error != null) {
				player.sendSystemMessage(Component.literal("快速购买失败：" + error).withStyle(ChatFormatting.RED), false);
			} else {
				Component name = new ItemStack(item).getHoverName();
				player.sendSystemMessage(Component.literal("已自动购买 ").withStyle(ChatFormatting.GREEN)
						.append(name.copy().withStyle(ChatFormatting.GREEN))
						.append(Component.literal(" ×" + stack.getCount() + "，再次对准投影方块中键拾取")
								.withStyle(ChatFormatting.GREEN)), false);
			}
		});
	}
}
