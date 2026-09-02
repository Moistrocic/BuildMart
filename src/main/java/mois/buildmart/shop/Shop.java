package mois.buildmart.shop;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.UUID;

/**
 * 出售商店：绑定某个维度中的箱子位置。
 * 倒计时归零时清空箱子并把物品价值结算给收款人；箱子开启期间倒计时锁定，
 * 关闭瞬间倒计时立即归零以便快速出售。
 */
public final class Shop {
	private final ResourceKey<Level> dimension;
	private final BlockPos pos;
	private final UUID owner;
	private String ownerName;
	private UUID payee;
	private String payeeName;
	private int remainingTicks;
	private UUID displayUuid;
	private boolean wasOpen;

	public Shop(ResourceKey<Level> dimension, BlockPos pos, UUID owner, String ownerName,
			UUID payee, String payeeName, int remainingTicks) {
		this.dimension = dimension;
		this.pos = pos;
		this.owner = owner;
		this.ownerName = ownerName;
		this.payee = payee;
		this.payeeName = payeeName;
		this.remainingTicks = remainingTicks;
	}

	public ResourceKey<Level> dimension() {
		return dimension;
	}

	public BlockPos pos() {
		return pos;
	}

	public UUID owner() {
		return owner;
	}

	public String ownerName() {
		return ownerName;
	}

	public UUID payee() {
		return payee;
	}

	public String payeeName() {
		return payeeName;
	}

	public void setPayee(UUID payee, String payeeName) {
		this.payee = payee;
		this.payeeName = payeeName;
	}

	public int remainingTicks() {
		return remainingTicks;
	}

	public void setRemainingTicks(int remainingTicks) {
		this.remainingTicks = remainingTicks;
	}

	public UUID displayUuid() {
		return displayUuid;
	}

	public void setDisplayUuid(UUID displayUuid) {
		this.displayUuid = displayUuid;
	}

	public boolean wasOpen() {
		return wasOpen;
	}

	public void setWasOpen(boolean wasOpen) {
		this.wasOpen = wasOpen;
	}
}
