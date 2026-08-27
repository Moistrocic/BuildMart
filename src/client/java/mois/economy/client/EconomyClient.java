package mois.economy.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import mois.economy.network.FlyConfigSync;

public class EconomyClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 价格提示完全由服务端线路层 lore 提供（纯净端同样可见），客户端无需任何逻辑。
		// 飞行挖掘开关（fly.digNoSlow）由服务端 JOIN/热改时下发：更新共享状态，
		// PlayerMixin 两端统一读该值，客户端本地挖掘预测与服务端权威一致。
		ClientPlayNetworking.registerGlobalReceiver(FlyConfigSync.FlyConfigPayload.TYPE,
				(payload, context) -> FlyConfigSync.digNoSlow = payload.digNoSlow());
	}
}
