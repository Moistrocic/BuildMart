package mois.economy;

import mois.economy.config.ItemValues;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * 线路层价格 lore：物品通过网络序列化发给客户端时临时附上一行金色价格，
 * 客户端（含纯净端）的物品提示会原生渲染该行；物品从客户端发回服务端时剥除，
 * 因此物品数据不会持久残留，卸载模组后零污染。
 * <p>
 * enabled 仅由服务端在 SERVER_STARTED 时按配置开启；客户端进程永远为 false，
 * 保证注入只发生在服务端发出方向上。
 */
public final class PriceLore {
	private static final String MARKER = "价值：";

	public static volatile boolean enabled = false;

	private PriceLore() {
	}

	/** 包装物品流编解码器：编码前注入价格行，解码后剥除价格行。未开启时原样返回。 */
	public static StreamCodec<RegistryFriendlyByteBuf, ItemStack> wrap(
			StreamCodec<RegistryFriendlyByteBuf, ItemStack> original) {
		return StreamCodec.of(
				(buf, stack) -> original.encode(buf, enabled && stack != null && !stack.isEmpty()
						? inject(stack) : stack),
				buf -> {
					ItemStack stack = original.decode(buf);
					return enabled && stack != null && !stack.isEmpty() ? strip(stack) : stack;
				});
	}

	static ItemStack inject(ItemStack stack) {
		ItemStack copy = stack.copy();
		ItemLore lore = copy.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
		copy.set(DataComponents.LORE, lore.withLineAdded(priceLine(stack)));
		return copy;
	}

	static ItemStack strip(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null || lore.lines().isEmpty()) {
			return stack;
		}
		List<Component> kept = lore.lines().stream().filter(line -> !isPriceLine(line)).toList();
		if (kept.size() == lore.lines().size()) {
			return stack;
		}
		if (kept.isEmpty()) {
			stack.remove(DataComponents.LORE);
		} else {
			stack.set(DataComponents.LORE, new ItemLore(kept));
		}
		return stack;
	}

	private static Component priceLine(ItemStack stack) {
		return Component.literal(MARKER)
				.append(Money.format(ItemValues.get(stack.getItem())))
				.append(" 元")
				.withStyle(ChatFormatting.GOLD);
	}

	private static boolean isPriceLine(Component line) {
		return line.getString().startsWith(MARKER)
				&& TextColor.fromLegacyFormat(ChatFormatting.GOLD).equals(line.getStyle().getColor());
	}

	/** 注入/剥除逻辑自检（服务器启动时调用），含真实网络 codec 往返。 */
	public static void selfCheck(net.minecraft.core.RegistryAccess registryAccess) {
		ItemStack injected = inject(new ItemStack(Items.DIRT));
		ItemLore lore = injected.get(DataComponents.LORE);
		if (lore == null || lore.lines().stream().noneMatch(PriceLore::isPriceLine)) {
			throw new IllegalStateException("价格 lore 注入失败");
		}
		ItemStack custom = new ItemStack(Items.STONE);
		custom.set(DataComponents.LORE, ItemLore.EMPTY.withLineAdded(Component.literal("自定义")));
		ItemStack stripped = strip(inject(custom));
		ItemLore after = stripped.get(DataComponents.LORE);
		if (after == null || after.lines().size() != 1 || !after.lines().get(0).getString().equals("自定义")) {
			throw new IllegalStateException("价格 lore 剥除失败");
		}
		// 真实线路往返：模拟客户端把带价格行的物品发回，解码后应无残留。
		RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), registryAccess);
		ItemStack.OPTIONAL_STREAM_CODEC.encode(buf, injected);
		ItemStack decoded = ItemStack.OPTIONAL_STREAM_CODEC.decode(buf);
		ItemLore decodedLore = decoded.get(DataComponents.LORE);
		if (decodedLore != null && !decodedLore.lines().isEmpty()) {
			throw new IllegalStateException("线路价格 lore 未剥除");
		}
		Economy.LOGGER.info("价格lore自检通过");
	}
}
