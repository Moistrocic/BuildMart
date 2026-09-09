package mois.buildmart.test;

import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家消息捕获器（仅测试源集）：由 {@code ServerPlayerMessageSpyMixin} 调用。
 * <p>
 * 测试执行期间 {@link #begin(UUID)} 开始记录该玩家收到的所有系统消息（含指令反馈与
 * 模组直接发送的提示），{@link #end(UUID)} 结束并取回；未在记录中的消息照常发送。
 * 捕获时**取消原发送**，因此测试用玩家无需真实网络连接（不会因 connection 为空而 NPE）。
 */
public final class MessageSpy {
	private static final Map<UUID, List<String>> CAPTURED = new ConcurrentHashMap<>();

	private MessageSpy() {
	}

	/** 开始记录该玩家的消息。 */
	public static void begin(UUID playerId) {
		CAPTURED.put(playerId, Collections.synchronizedList(new ArrayList<>()));
	}

	/** 结束记录并返回期间捕获的消息（无记录返回空列表）。 */
	public static List<String> end(UUID playerId) {
		List<String> captured = CAPTURED.remove(playerId);
		return captured == null ? List.of() : List.copyOf(captured);
	}

	/**
	 * 尝试捕获一条消息：返回 true 表示已被捕获（调用方应取消原发送）。
	 */
	public static boolean capture(ServerPlayer player, String message) {
		List<String> captured = CAPTURED.get(player.getUUID());
		if (captured == null) {
			return false;
		}
		captured.add(message);
		return true;
	}
}
