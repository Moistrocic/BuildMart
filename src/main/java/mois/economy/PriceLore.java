package mois.economy;

import mois.economy.config.ItemValues;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;

/**
 * 线路层价格 lore：物品通过网络序列化发给客户端时临时附上一行金色价格（正体），
 * 客户端（含纯净端）的物品提示会原生渲染该行；物品从客户端发回服务端时剥除，
 * 因此物品数据不会持久残留，卸载模组后零污染。
 * <p>
 * 方向判定：独立服务端的所有编解码都应用；单人游戏内置服务器与服务端线程共享
 * JVM 与 codec，因此按“服务端线程”区分方向（服务端线程上的编码=下发注入、
 * 解码=回传剥除；客户端线程一律不动）。纯客户端进程 serverRef 为 null，恒不生效，
 * 只会原样渲染服务端注入的 lore。
 */
public final class PriceLore {
	private static final String MARKER = "价值：";

	/** 由配置控制的功能总开关（EconomyConfig.itemPricesInLore）。 */
	public static volatile boolean enabled = false;

	private static volatile MinecraftServer serverRef;
	private static volatile boolean dedicated;

	private PriceLore() {
	}

	public static void configure(MinecraftServer server) {
		serverRef = server;
		dedicated = server.isDedicatedServer();
	}

	/** 当前编解码是否应注入/剥除：仅服务端侧（独立服任意线程，单机限服务端线程）。 */
	private static boolean shouldApply() {
		if (!enabled || serverRef == null) {
			return false;
		}
		return dedicated || serverRef.isSameThread();
	}

	/** 包装物品流编解码器：下发方向注入价格行，回传方向剥除价格行。 */
	public static StreamCodec<RegistryFriendlyByteBuf, ItemStack> wrap(
			StreamCodec<RegistryFriendlyByteBuf, ItemStack> original) {
		return StreamCodec.of(
				(buf, stack) -> original.encode(buf, shouldApply() && stack != null && !stack.isEmpty()
						? inject(stack) : stack),
				buf -> {
					ItemStack stack = original.decode(buf);
					return shouldApply() && stack != null && !stack.isEmpty() ? strip(stack) : stack;
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
				.withStyle(ChatFormatting.GOLD)
				.withStyle(style -> style.withItalic(false));
	}

	private static boolean isPriceLine(Component line) {
		return line.getString().startsWith(MARKER)
				&& TextColor.fromLegacyFormat(ChatFormatting.GOLD).equals(line.getStyle().getColor());
	}

	/** 注入/剥除逻辑自检（服务器启动时调用），含真实网络 codec 往返。 */
	public static void selfCheck(net.minecraft.core.RegistryAccess registryAccess) {
		if (!shouldApply()) {
			throw new IllegalStateException("价格 lore 未启用，无法自检");
		}
		ItemStack injected = inject(new ItemStack(Items.DIRT));
		ItemLore lore = injected.get(DataComponents.LORE);
		if (lore == null || lore.lines().stream().noneMatch(PriceLore::isPriceLine)) {
			throw new IllegalStateException("价格 lore 注入失败");
		}
		Component line = lore.lines().stream().filter(PriceLore::isPriceLine).findFirst().orElseThrow();
		if (Boolean.TRUE.equals(line.getStyle().isItalic())) {
			throw new IllegalStateException("价格 lore 应为正体");
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
