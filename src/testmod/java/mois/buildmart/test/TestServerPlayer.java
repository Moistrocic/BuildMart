package mois.buildmart.test;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;

/**
 * 测试用服务端玩家（仅测试源集）：原版 {@code GameTestHelper.makeMockServerPlayerInLevel()}
 * 返回的匿名类把 {@code gameMode()} 硬编码为 CREATIVE，导致 /fly、/suicide 等依赖生存判定的
 * 用例无法测试。这里改为可切换模式的子类（默认生存），其余行为与普通 ServerPlayer 一致。
 */
public final class TestServerPlayer extends ServerPlayer {
	private GameType testGameMode = GameType.SURVIVAL;

	public TestServerPlayer(MinecraftServer server, ServerLevel level, GameProfile profile,
			ClientInformation clientInformation) {
		super(server, level, profile, clientInformation);
	}

	@Override
	public GameType gameMode() {
		return testGameMode;
	}

	public void setTestGameMode(GameType gameType) {
		this.testGameMode = gameType;
	}
}
