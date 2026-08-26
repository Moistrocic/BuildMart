package mois.economy;

import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import net.minecraft.core.RegistryAccess;
import net.minecraft.world.item.ItemStack;

/**
 * 物品堆 ↔ JSON 字符串编解码（交易流水 item_data 用）。
 * 编码使用 ItemStack.CODEC（完整组件数据，含 NBT/附魔/自定义名等），
 * 与趣味钓鱼配置（FishingManager）同一套编解码。
 */
public final class ItemCodec {
	private ItemCodec() {
	}

	/**
	 * 把物品堆编码为 JSON 字符串（完整组件数据，含 NBT）；空堆或编码失败返回 null
	 * （记录仍写入，仅缺少完整物品数据）。
	 */
	public static String encode(ItemStack stack, RegistryAccess registryAccess) {
		if (stack == null || stack.isEmpty()) {
			return null;
		}
		try {
			DynamicOps<com.google.gson.JsonElement> ops =
					registryAccess.createSerializationContext(JsonOps.INSTANCE);
			return ItemStack.CODEC.encodeStart(ops, stack).getOrThrow().toString();
		} catch (RuntimeException e) {
			return null;
		}
	}
}
