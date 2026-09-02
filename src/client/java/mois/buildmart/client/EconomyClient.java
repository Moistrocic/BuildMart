package mois.buildmart.client;

import net.fabricmc.api.ClientModInitializer;

public class EconomyClient implements ClientModInitializer {
	@Override
	public void onInitializeClient() {
		// 客户端增强（可选）：快速投影购买——反射接入 litematica 拾取事件；
		// litematica 未安装时静默跳过。核心经济逻辑全部在服务端（src/main）。
		FastbuyClient.init();
	}
}
