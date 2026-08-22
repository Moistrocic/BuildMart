package mois.economy.net;

import mois.economy.Economy;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * 服务端 → 客户端：物品价值表（JSON 字符串，与 config/economy/items.json 同构）。
 * 客户端收到后用于物品提示中的金色价格；未收到时不显示价格（纯净端不受影响）。
 */
public record PriceListPayload(String json) implements CustomPacketPayload {
	/** 26.3 的 createType(String) 只会把字符串当 path 塞进 minecraft 命名空间，故直接构造。 */
	public static final Type<PriceListPayload> TYPE = new Type<>(Economy.id("prices"));

	public static final StreamCodec<FriendlyByteBuf, PriceListPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.STRING_UTF8, PriceListPayload::json, PriceListPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
