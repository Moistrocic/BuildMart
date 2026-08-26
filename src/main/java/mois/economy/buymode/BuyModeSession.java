package mois.economy.buymode;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import mois.economy.Money;
import mois.economy.PriceLore;
import mois.economy.config.ItemValues;
import mois.economy.data.EconomyDb;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

/**
 * 便捷购买会话的追踪状态与结算。
 * <p>
 * 判定模型（服务端权威）：
 * <ul>
 * <li><b>消失</b>（背包槽位变空，或同物品数量减少）：物品进入暂存（untag 归一，不结算）——
 * 拿起/拆分拿起都只是暂存，不再「拿起即退款」；</li>
 * <li><b>出现</b>（槽位出现物品）：按「增量」吸收暂存（放回自己的物品 → 中性）；
 * 增量超出暂存的部分来自创造面板 → 购买（严格校验 + 扣款）；与暂存完全不同 → 购买；</li>
 * <li><b>丢弃</b>（-1 包）：完全匹配暂存（先拿起再丢）→ 卖出（按丢弃数量退款）；
 * 不匹配 → 挂起为 pendingDrop，由下一个槽位包用「槽位原内容」判定：
 * 槽位原内容匹配 = 背包 ctrl+q 直接丢 → 卖出；否则 = 面板 ctrl+q → 购买（生成实体）；</li>
 * <li><b>关闭物品栏 / 退出模式 / 掉线</b>：暂存剩余统一卖出（物品消失 = 卖出），pendingDrop 作废
 * （未付款的面板物品，无损失）。</li>
 * </ul>
 * 比较一律用 untag 归一后的数据：价格行是本模组自身数据，不参与任何匹配判定。
 */
public final class BuyModeSession {
	/** 暂存项：untag 归一后的副本，数量即剩余。 */
	private static final class PendingStack {
		final ItemStack stack;

		PendingStack(ItemStack stack) {
			this.stack = stack;
		}

		int remaining() {
			return stack.getCount();
		}

		void setRemaining(int n) {
			stack.setCount(n);
		}
	}

	private final List<PendingStack> pendingStacks = new ArrayList<>();
	private ItemStack pendingDrop = null;
	/** pendingDrop 挂起时的服务端 tick（用于超时结算：面板 ctrl+q 无槽位包跟随）。 */
	private long pendingDropTick = -1;
	/** 挂起的槽位出现（数字键/槽间交换的配对候选，见 {@link PendingSlot}）。 */
	private PendingSlot pendingSlot = null;

	/**
	 * 挂起的「槽位出现」：出现内容未能被暂存完全吸收时先挂起，由下一个槽位包
	 * 配对（对称交换）或超时/关闭界面结算。挂起期间服务端槽位**从未被修改**
	 * （仍为 {@code prev}），因此拒绝/作废时物品并未丢失。
	 *
	 * @param slotNum  目标槽位
	 * @param prev     挂起前的槽位内容（服务端权威）
	 * @param next     客户端请求的出现内容
	 * @param vanished 本包处理中已入暂存的「消失」物品（拒绝/作废时需撤销；
	 *                 同物品增量购买场景可为空）
	 * @param unbought 未能被暂存吸收的数量（结算购买时按此收费）
	 * @param tick     挂起时的服务端 tick（超时结算用）
	 */
	public record PendingSlot(int slotNum, ItemStack prev, ItemStack next,
			ItemStack vanished, int unbought, long tick) {
	}

	// ---------- 暂存 ----------

	/** 消失：物品进入暂存（不结算）；同物品合并数量。 */
	public void recordVanished(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		ItemStack normalized = normalize(stack);
		for (PendingStack p : pendingStacks) {
			if (sameItemAndComponents(p.stack, normalized)) {
				p.setRemaining(p.remaining() + normalized.getCount());
				return;
			}
		}
		pendingStacks.add(new PendingStack(normalized));
	}

	/**
	 * 按数量吸收暂存（出现放回 / 丢弃匹配），返回未能吸收的数量
	 * （&gt;0 = 该部分没有暂存来源，需按面板购买处理）。
	 */
	public int absorb(ItemStack stack, int count) {
		int left = count;
		Iterator<PendingStack> it = pendingStacks.iterator();
		while (it.hasNext() && left > 0) {
			PendingStack p = it.next();
			if (sameItemAndComponents(p.stack, stack)) {
				int take = Math.min(left, p.remaining());
				p.setRemaining(p.remaining() - take);
				left -= take;
				if (p.remaining() == 0) {
					it.remove();
				}
			}
		}
		return left;
	}

	/** 暂存快照（购买校验失败时回滚用）。 */
	public Object snapshot() {
		List<PendingStack> copy = new ArrayList<>();
		for (PendingStack p : pendingStacks) {
			copy.add(new PendingStack(p.stack.copy()));
		}
		return copy;
	}

	/** 恢复暂存快照。 */
	@SuppressWarnings("unchecked")
	public void restore(Object snap) {
		pendingStacks.clear();
		pendingStacks.addAll((List<PendingStack>) snap);
	}

	// ---------- pendingDrop（-1 延迟判定） ----------

	public boolean hasPendingDrop() {
		return pendingDrop != null;
	}

	public ItemStack pendingDrop() {
		return pendingDrop;
	}

	public void setPendingDrop(ItemStack stack, long serverTick) {
		pendingDrop = normalize(stack);
		pendingDropTick = serverTick;
	}

	/** pendingDrop 是否已超过宽限期（挂起 ≥2 tick 仍无槽位包认领 = 面板 ctrl+q，应结算为购买）。 */
	public boolean pendingDropExpired(long nowTick) {
		return pendingDrop != null && pendingDropTick >= 0 && nowTick - pendingDropTick >= 2;
	}

	public void clearPendingDrop() {
		pendingDrop = null;
		pendingDropTick = -1;
	}

	// ---------- pendingSlot（槽位出现挂起：交换配对 / 延迟结算） ----------

	public boolean hasPendingSlot() {
		return pendingSlot != null;
	}

	public PendingSlot pendingSlot() {
		return pendingSlot;
	}

	public void setPendingSlot(int slotNum, ItemStack prev, ItemStack next,
			ItemStack vanished, int unbought, long tick) {
		pendingSlot = new PendingSlot(slotNum, prev.copy(), next.copy(),
				vanished == null || vanished.isEmpty() ? ItemStack.EMPTY : vanished.copy(),
				unbought, tick);
	}

	public void clearPendingSlot() {
		pendingSlot = null;
	}

	/** pendingSlot 是否已超过宽限期（≥2 tick 仍无配对包认领 = 非交换，应结算购买/拒绝）。 */
	public boolean pendingSlotExpired(long nowTick) {
		return pendingSlot != null && pendingSlot.tick() >= 0 && nowTick - pendingSlot.tick() >= 2;
	}

	/**
	 * 撤销一次「消失」：从暂存扣减同物品数量。用于挂起被拒绝/作废时——
	 * 槽位从未被修改（物品未丢失），已入暂存的消失记录必须一并撤销，
	 * 否则关闭界面会把仍在背包里的物品再卖出退款一次。
	 */
	public void removeVanished(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		ItemStack normalized = normalize(stack);
		Iterator<PendingStack> it = pendingStacks.iterator();
		while (it.hasNext()) {
			PendingStack p = it.next();
			if (sameItemAndComponents(p.stack, normalized)) {
				int remove = Math.min(normalized.getCount(), p.remaining());
				p.setRemaining(p.remaining() - remove);
				if (p.remaining() == 0) {
					it.remove();
				}
				return;
			}
		}
	}

	// ---------- 结算 ----------

	/** 关闭物品栏 / 退出模式 / 掉线：暂存剩余统一卖出，pendingDrop 作废。 */
	public void settleAndClear(ServerPlayer player) {
		// 挂起中的槽位出现：服务端槽位从未被修改（物品未丢失）→ 撤销消失记录，不作卖出
		if (pendingSlot != null) {
			removeVanished(pendingSlot.vanished());
			pendingSlot = null;
		}
		long total = 0;
		List<String> sold = new ArrayList<>();
		for (PendingStack p : pendingStacks) {
			ItemStack stack = p.stack.copy();
			stack.setCount(p.remaining());
			long value = ItemValues.price(stack);
			if (value <= 0 || !ItemValues.isTradable(stack)) {
				continue; // 防御：不可交易物品不应出现在暂存中
			}
			total += value;
			sold.add(stack.getHoverName().getString() + " ×" + stack.getCount());
		}
		pendingStacks.clear();
		pendingDrop = null;
		if (total <= 0) {
			return;
		}
		try {
			EconomyDb.credit(player.getUUID(), player.getGameProfile().name(), total);
		} catch (EconomyDb.DatabaseException ignored) {
			// 结算失败静默。
		}
		// 掉线路径可能已无可用连接，发送失败静默（钱已入账）。
		try {
			player.sendSystemMessage(Component.literal(
					"已卖出 " + String.join("、", sold) + "，获得 " + Money.format(total) + " 元")
					.withStyle(ChatFormatting.GREEN), false);
		} catch (RuntimeException ignored) {
		}
	}

	// ---------- 工具 ----------

	/** 归一化：拷贝 + 剥除价格行（tag 不参与记录与匹配）。 */
	private static ItemStack normalize(ItemStack stack) {
		ItemStack copy = stack.copy();
		PriceLore.untag(copy);
		return copy;
	}

	/** 同物品同组件（比较相对默认的组件补丁，忽略数量；两侧先归一剥除价格行）。 */
	public static boolean sameItemAndComponents(ItemStack a, ItemStack b) {
		if (a == null || b == null) {
			return false;
		}
		if (a.isEmpty() || b.isEmpty()) {
			return a.isEmpty() && b.isEmpty();
		}
		if (a.getItem() != b.getItem()) {
			return false;
		}
		ItemStack na = a.copy();
		PriceLore.untag(na);
		ItemStack nb = b.copy();
		PriceLore.untag(nb);
		return na.getComponentsPatch().equals(nb.getComponentsPatch());
	}
}
