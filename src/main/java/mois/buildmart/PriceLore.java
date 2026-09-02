package mois.buildmart;

import java.util.List;

import mois.buildmart.config.ItemValues;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.TextColor;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.BundleContents;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.item.component.ItemLore;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;

/**
 * 价格标签：以真实 lore 组件的形式打在被玩家持有的物品上（进背包/打开容器时），
 * 显示“单价”——与数量无关，同种物品不同数量的标签完全一致，可以正常堆叠，
 * 且在物品进入背包“之前”就打上，捡起/指令获取时能与背包内物品无缝合并。
 * <p>
 * 生命周期（价格只需玩家可见，因此严格控制存留范围）：
 * <ul>
 * <li>进入玩家背包（捡起/指令/容器点击等，统一经 Inventory.setItem）→ 打标签；</li>
 * <li>玩家打开的容器界面 → 打开时全部打标签；</li>
 * <li>丢出/死亡掉落（drop）→ 立刻清除；</li>
 * <li>关闭容器 → 容器内物品立刻清除（玩家背包部分除外）；</li>
 * <li>玩家下线 → 背包与当前容器全部清除，存档不留残留。</li>
 * </ul>
 */
public final class PriceLore {
	private static final String MARKER = "单价：";

	/** 由配置控制的功能总开关（EconomyConfig.itemPricesInLore）。 */
	public static volatile boolean enabled = false;

	private PriceLore() {
	}

	/** 给物品打上价格标签（幂等；不递归进容器内容物——内容物在容器被打开时另行打标）。 */
	public static void tag(ItemStack stack) {
		if (!enabled || stack == null || stack.isEmpty()) {
			return;
		}
		Component line = priceLine(stack);
		ItemLore lore = stack.getOrDefault(DataComponents.LORE, ItemLore.EMPTY);
		List<Component> lines = lore.lines();
		// 已带完全相同价格行时直接返回，不重写组件：重写会生成新的组件对象，
		// 26.3 客户端 sameDestroyTarget 逐组件比较手持物品，捡起物品引发的
		// 无意义重写会触发槽位同步并把玩家正在进行的挖掘进度重置为 0。
		if (!lines.isEmpty() && lines.get(lines.size() - 1).equals(line)) {
			return;
		}
		List<Component> kept = lines.stream().filter(existing -> !isPriceLine(existing)).toList();
		ItemLore base = kept.isEmpty() ? ItemLore.EMPTY : new ItemLore(kept);
		stack.set(DataComponents.LORE, base.withLineAdded(line));
	}

	/**
	 * 清除价格标签（递归清除容器内容物中的标签）。返回是否实际修改了任何组件。
	 * 26.2 无 ItemContainerContents/BundleContents 的 Mutable API，采用
	 * 「读出全部内容 → 修改 → 重建组件」的方式，仅在实际有改动时写回。
	 */
	public static boolean untag(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return false;
		}
		boolean changed = false;
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore != null) {
			List<Component> kept = lore.lines().stream().filter(line -> !isPriceLine(line)).toList();
			if (kept.size() != lore.lines().size()) {
				if (kept.isEmpty()) {
					stack.remove(DataComponents.LORE);
				} else {
					stack.set(DataComponents.LORE, new ItemLore(kept));
				}
				changed = true;
			}
		}
		ItemContainerContents container = stack.get(DataComponents.CONTAINER);
		if (container != null) {
			List<ItemStack> items = container.allItemsCopyStream().toList();
			boolean innerChanged = false;
			for (int i = 0; i < items.size(); i++) {
				ItemStack inner = items.get(i);
				if (!inner.isEmpty() && untag(inner)) {
					items.set(i, inner);
					innerChanged = true;
				}
			}
			if (innerChanged) {
				stack.set(DataComponents.CONTAINER, ItemContainerContents.fromItems(items));
				changed = true;
			}
		}
		BundleContents bundle = stack.get(DataComponents.BUNDLE_CONTENTS);
		if (bundle != null) {
			List<ItemStackTemplate> rebuilt = new java.util.ArrayList<>();
			boolean innerChanged = false;
			for (ItemStackTemplate tpl : bundle.items()) {
				ItemStack inner = tpl.create();
				if (!inner.isEmpty() && untag(inner)) {
					rebuilt.add(ItemStackTemplate.fromStack(inner));
					innerChanged = true;
				} else {
					rebuilt.add(tpl);
				}
			}
			if (innerChanged) {
				stack.set(DataComponents.BUNDLE_CONTENTS, new BundleContents(rebuilt));
				changed = true;
			}
		}
		return changed;
	}

	/** 打开容器：给界面所有槽位打标签（含玩家背包部分，幂等）。 */
	public static void tagMenu(AbstractContainerMenu menu) {
		if (!enabled || menu == null) {
			return;
		}
		for (Slot slot : menu.slots) {
			// 加工类机器（熔炉/烟熏炉/高炉/酿造台）的输出槽会被配方判定逐组件比对
			// （AbstractFurnaceBlockEntity.canBurn 的 isSameItemSameComponents），打标会
			// 改变组件导致烧制/酿造中断；合成网格的结果槽由 CraftingMenuMixin 在产出时打标。
			if (slot.container instanceof AbstractFurnaceBlockEntity
					|| slot.container instanceof BrewingStandBlockEntity) {
				continue;
			}
			tag(slot.getItem());
		}
	}

	/** 关闭容器：清除界面中非玩家背包槽位的标签（玩家背包里的物品保留）。 */
	public static void untagMenu(AbstractContainerMenu menu, Inventory playerInventory) {
		if (menu == null) {
			return;
		}
		for (Slot slot : menu.slots) {
			if (slot.container == playerInventory) {
				continue;
			}
			untag(slot.getItem());
		}
	}

	/** 玩家下线：清除背包（含盔甲/副手/容器内容物）与光标上的标签。 */
	public static void untagInventory(ServerPlayer player) {
		Inventory inventory = player.getInventory();
		for (int i = 0; i < inventory.getContainerSize(); i++) {
			untag(inventory.getItem(i));
		}
		if (player.containerMenu != null) {
			untag(player.containerMenu.getCarried());
		}
	}

	/** 玩家下线：清除背包 + 当前打开的容器 + 光标。 */
	public static void untagPlayerAndMenu(ServerPlayer player) {
		untagInventory(player);
		if (player.containerMenu != null) {
			untagMenu(player.containerMenu, player.getInventory());
		}
	}

	private static Component priceLine(ItemStack stack) {
		long unitPrice = ItemValues.unitPrice(stack);
		if (unitPrice == ItemValues.UNTRADEABLE) {
			// 不可交易物品显示红色标记而非价格
			return Component.literal("不可交易")
					.withStyle(ChatFormatting.RED)
					.withStyle(style -> style.withItalic(false));
		}
		// 显示“单价”而非整组总价：与数量无关，同种物品不同数量的标签完全一致，
		// 否则游戏会因组件不同拒绝堆叠（3 个与 5 个绿宝石无法合并）
		return Component.literal(MARKER)
				.append(Money.format(unitPrice))
				.append(" 元")
				.withStyle(ChatFormatting.GOLD)
				.withStyle(style -> style.withItalic(false));
	}

	private static boolean isPriceLine(Component line) {
		if (line.getString().startsWith(MARKER)
				&& TextColor.fromLegacyFormat(ChatFormatting.GOLD).equals(line.getStyle().getColor())) {
			return true;
		}
		// “不可交易”标记同样属于本模组的标签，清除时一并移除
		return "不可交易".equals(line.getString())
				&& TextColor.fromLegacyFormat(ChatFormatting.RED).equals(line.getStyle().getColor());
	}

	/** 打标/清标逻辑自检（服务器启动时调用）。 */
	public static void selfCheck() {
		if (!enabled) {
			throw new IllegalStateException("价格标签未启用，无法自检");
		}
		ItemStack stack = new ItemStack(Items.STONE, 3);
		tag(stack);
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null || lore.lines().size() != 1 || !isPriceLine(lore.lines().get(0))) {
			throw new IllegalStateException("价格标签打标失败");
		}
		tag(stack); // 幂等性
		if (stack.get(DataComponents.LORE).lines().size() != 1) {
			throw new IllegalStateException("价格标签应幂等");
		}
		ItemStack custom = new ItemStack(Items.DIRT);
		custom.set(DataComponents.LORE, ItemLore.EMPTY.withLineAdded(Component.literal("自定义")));
		tag(custom);
		if (custom.get(DataComponents.LORE).lines().size() != 2) {
			throw new IllegalStateException("打标应保留自定义 lore");
		}
		untag(custom);
		ItemLore after = custom.get(DataComponents.LORE);
		if (after == null || after.lines().size() != 1 || !after.lines().get(0).getString().equals("自定义")) {
			throw new IllegalStateException("价格标签清除失败");
		}
		BuildMart.LOGGER.info("价格lore自检通过");
	}
}
