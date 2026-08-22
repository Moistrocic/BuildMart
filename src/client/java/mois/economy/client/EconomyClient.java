package mois.economy.client;

import mois.economy.Money;
import mois.economy.net.PriceListPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

public class EconomyClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 注册价格同步包：收到后更新本地价值缓存。
		PayloadTypeRegistry.clientboundPlay().register(PriceListPayload.TYPE, PriceListPayload.CODEC);
		ClientPlayNetworking.registerGlobalReceiver(PriceListPayload.TYPE, (payload, context) ->
				context.client().execute(() -> PriceCache.load(payload.json())));

		// 物品提示追加金色价值行；未收到服务器价格数据时 PriceCache.get 返回 -1，不显示。
		ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipFlag, lines) -> {
			long cents = PriceCache.get(stack);
			if (cents < 0) {
				return;
			}
			lines.add(Component.literal("价值：")
					.append(Money.format(cents)).append(" 元")
					.withStyle(ChatFormatting.GOLD));
		});
	}
}
