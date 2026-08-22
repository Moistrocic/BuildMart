package mois.economy.client;

import net.fabricmc.api.ClientModInitializer;

public class EconomyClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 价格提示完全由服务端线路层 lore 提供（纯净端同样可见），客户端无需任何逻辑。
	}
}
