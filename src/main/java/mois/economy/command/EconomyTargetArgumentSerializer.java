package mois.economy.command;

import com.google.gson.JsonObject;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.synchronization.ArgumentTypeInfo;
import net.minecraft.network.FriendlyByteBuf;

/**
 * {@link EconomyTargetArgumentType} 的序列化器：该参数类型无状态，
 * 网络与 JSON 序列化均为空操作，仅为让命令树（ClientboundCommandsPacket）
 * 能够携带 /eco 的 target 节点。必须通过 Fabric 的
 * ArgumentTypeRegistry.registerArgumentType 注册后命令树才能发给管理员。
 */
public final class EconomyTargetArgumentSerializer
		implements ArgumentTypeInfo<EconomyTargetArgumentType, EconomyTargetArgumentSerializer.Template> {

	public static final EconomyTargetArgumentSerializer INSTANCE = new EconomyTargetArgumentSerializer();

	private EconomyTargetArgumentSerializer() {
	}

	@Override
	public void serializeToNetwork(Template template, FriendlyByteBuf buf) {
		// 无状态，无需写入。
	}

	@Override
	public Template deserializeFromNetwork(FriendlyByteBuf buf) {
		return new Template();
	}

	@Override
	public void serializeToJson(Template template, JsonObject json) {
		// 无状态，无需写入。
	}

	@Override
	public Template unpack(EconomyTargetArgumentType argument) {
		return new Template();
	}

	public static final class Template implements ArgumentTypeInfo.Template<EconomyTargetArgumentType> {
		@Override
		public EconomyTargetArgumentType instantiate(CommandBuildContext context) {
			return EconomyTargetArgumentType.target();
		}

		@Override
		public ArgumentTypeInfo<EconomyTargetArgumentType, ?> type() {
			return INSTANCE;
		}
	}
}
