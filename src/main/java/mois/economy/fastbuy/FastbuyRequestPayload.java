package mois.economy.fastbuy;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 快速购买请求（客户端 → 服务端）：投影（litematica）拾取失败（背包无该物品）时
 * 由客户端发送，服务端在 /fastbuy 开启时自动购买一组。
 * 载荷：物品注册表 ID（字符串）。纯净端不会发送（未注册该包）。
 */
public record FastbuyRequestPayload(String itemId) implements CustomPacketPayload {
	public static final CustomPacketPayload.Type<FastbuyRequestPayload> TYPE =
			new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("economy", "fastbuy"));

	public static final StreamCodec<ByteBuf, FastbuyRequestPayload> CODEC =
			StreamCodec.composite(ByteBufCodecs.STRING_UTF8,
					FastbuyRequestPayload::itemId,
					FastbuyRequestPayload::new);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
