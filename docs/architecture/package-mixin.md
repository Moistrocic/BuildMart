# `mois.economy.mixin` 包 — 全部 9 个 Mixin

注册表：`src/main/resources/economy.mixins.json`（`required: true`，`compatibilityLevel: JAVA_21`，
`defaultRequire: 1`）。全部位于 `src/main`（两端加载，单人游戏内置服务器同样生效；
客户端环境无 `ServerPlayer` 实例时各注入点按条件恒假无害返回）。
目标版本：Minecraft 26.3-snapshot-9（mojmap 方法名）。

## `ServerPlayerMixin`（目标 `ServerPlayer`）

1. `getTabListDisplayName` @RETURN（cancellable）：管理员（`AdminUtil.isAdmin`）返回红色名字
   （未设置时回退 `getDisplayName`，后者已被 PlayerMixin 染红）。
2. `openMenu` @RETURN：打开容器后 `PriceLore.tagMenu(player.containerMenu)` 全界面打标。
3. `doCloseContainer` @HEAD：`untagMenu`（保留玩家背包部分）+ 光标 untag；若在便捷购买中，
   光标物品作废（`setCarried(EMPTY)`，避免白嫖）+ `BuyModeManager.exit`。
4. `die` @HEAD：记录最近死亡点（`TeleportManager.recordDeath`，back 配置关闭时不记录）。

## `PlayerMixin`（目标 `Player`）

- `getDisplayName` @RETURN（cancellable）：管理员红名（聊天框发送者名）。
- 注意：必须注入 `Player`（26.3 中 `Player` 重写了 `getDisplayName` 且不含 super 调用，
  注入 `Entity` 拦截不到玩家实例）。

## `LivingEntityMixin`（目标 `LivingEntity`）

- `drop` @HEAD：丢出/死亡掉落时 `PriceLore.untag(stack)`（物品离开背包不再显示价格）。
- 必须注入 `LivingEntity`（`drop` 声明于此，Mixin 无法跨继承层级解析到 `Player`）。

## `InventoryMixin`（目标 `Inventory`）— 物品进背包的统一打标漏斗

1. `setItem` @HEAD：给写入的 stack 打标（容器点击/快捷移动经 `Slot.setByPlayer → Inventory.setItem`）。
2. `add(ItemStack)` @HEAD：给进栈 stack 打标 + 遍历全部槽位打标——26.3 的 `Inventory.add`
   直接写 `items` 列表不经过 `setItem`，且合并判定在写入前进行，两边都有标签才能无缝堆叠；
   **跳过玩家当前手持槽位（`getSelectedSlot()`）**：26.3 客户端 `sameDestroyTarget` 逐组件
   比较手持物品，捡起物品时重打标签刷新手持工具的价格行（价格随耐久变化）会导致挖掘进度重置。
- ⚠️ 此跳过逻辑与 `PriceLore.tag` 的“内容相同不重写”是挖掘进度 bug 的一对配套修复，缺一不可。

## `CraftingMenuMixin`（目标 `CraftingMenu`）— 合成结果源头打标

- `slotChangedCraftingGrid` @RETURN（static 方法，回调须声明**全部参数**含 `RecipeHolder<CraftingRecipe>`）：
  给 `resultContainer.getItem(0)` 打标——背包 2×2 与工作台 3×3 的结果都在此处计算
  （仅服务端调用），结果槽内即带标签。
- 作用：普通点击取走 → 光标携带已打标堆，放置时 `safeInsert` 的 `isSameItemSameComponents`
  通过 → 与旧堆合并；shift 一键合成 → `quickMoveStack` 拷贝已打标堆 → `moveItemStackTo`
  合并判定通过。修复“4*木板分格堆放”问题。
- 标签生命周期与容器一致：结果槽不属于玩家背包，关界面被 `untagMenu` 清除。

## `AbstractContainerMenuMixin`（目标 `AbstractContainerMenu`）— 快捷移动合并前兜底打标

- `moveItemStackTo` @HEAD：给待移动的 stack 打标（幂等）。合并判定
  （`isSameItemSameComponents`）在写入前进行，未打标堆（切石机/锻造台等不经过
  CraftingMenuMixin 的结果、其它模组产物）会与已打标旧堆判为异种而分格，此处兜底。
- 客户端侧同样生效（单机内置服务器开启标签时），保证本地预测与服务端一致。

## `ServerGamePacketListenerImplMixin`（目标 `ServerGamePacketListenerImpl`）— buymode 结算 + 创造标签补发

本类最复杂，共 4 个注入点：

1. `handleSetCreativeModeSlot` @HEAD（cancellable）`economy$handleBuyMode` — **buymode 主结算**：
   - 非 buymode 直接放行（原版处理）。
   - `slotNum < 0`（创造界面丢弃包，原版会生成实体）→ `ci.cancel()` 取消实体生成。
   - `slotNum > 45` 放行（原版同样忽略）。
   - **组件白名单**：`isBuyableClean(newStack)` 校验（`DataComponentPatch.split()` 的 added
     键全部在 `BUYABLE_COMPONENTS` 白名单内，removed 为空，容器/收纳袋内容物递归）——
     拒绝“保存的快捷栏”（标签页/热键加载的客户端本地数据）等途径的改造 NBT 物品；
     26.3 中 `BASE_POTION`/`BANNER_BASE_COLOR` 已并入 `POTION_CONTENTS`/`BANNER_PATTERNS`。
   - 否则接管：不可交易物品（拿/放双方任一）→ 回滚 + `broadcastFullState` + 红字提示；
     按 `ItemValues.price` 的差值结算（delta>0 扣款 / <0 退款 / =0 只换槽位）；
     余额不足 → 回滚槽位 + `broadcastFullState`。
   - 成功写入后：`PriceLore.tag(newStack)` + `menu.setRemoteSlot(slotNum, newStack)` +
     **`player.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId,
     menu.incrementStateId(), slotNum, newStack.copy()))`** ——原版 `setRemoteSlot` 会把槽位标记为
     “客户端已知”而不再下发，必须显式补发才能让客户端立即看到价格标签。
2. `handleSetCreativeModeSlot` @RETURN `economy$syncCreativeSlotTag` — **普通创造模式补发**：
   非 buymode 且 `hasInfiniteMaterials()` 的玩家，原版处理完成后把打标后的权威槽位补发一次
   （守卫：slotNum 1..45、非空、数量合法、`PriceLore.enabled`）。与上一条互斥。
3. `handleContainerClick` @HEAD（cancellable）`economy$buyModeClickHead` — buymode 点击包快照：
   - 记录 `economy$beforeItems`（全部槽位拷贝）+ `economy$settleClick` 标记；
   - `slotNum == -999` 且光标有物品（点击外部丢弃）→ 清空光标 + `ci.cancel()`（物品拿起时已退款）。
4. `handleContainerClick` @RETURN `economy$buyModeClickSettle` — buymode 点击包结算：
   - 对比 before/current：不可交易整体回滚；按各槽位 `ItemValues.price` 净变化结算
     （`SlotDelta(int slot, before, after, delta)` record 记录变化槽位）；余额不足回滚 + 全量同步；
   - 结算完成后对每个变化槽位 `PriceLore.tag` + 补发 `ClientboundContainerSetSlotPacket`
     （结算发生在原版 `broadcastChanges` 之后，补发保证界面内标签立即刷新）。
- 辅助：`gainedName`/`lostName`（物品名×数量展示）、`sendBuy/sendRefund/sendBuyNet/sendRefundNet/
  sendInsufficient/sendUntradeable/sendInsufficientNet/sendModified`（聊天提示）、
  `balance/balanceOrMax/balanceOrMinusOne`（DB 异常兜底）、`deductQuietly/creditQuietly`（静默失败）、
  `isBuyableClean`（组件白名单，见上）、`satAdd`。
- 设计原则：购买只扣玩家资金、不入服务器资产；一切以服务端权威槽位状态为准。

## `ServerPlayerGameModeMixin`（目标 `ServerPlayerGameMode`）— 商店拆除保护 + buymode 禁挖

- `destroyBlock` @HEAD（cancellable）：
  - **buymode 激活时直接取消**（`BuyModeManager.isActive`）——instabuild 会让客户端走创造
    式秒破且无掉落物，关闭 buymode 后恢复正常生存挖掘；
  - 否则 `ShopManager.getShopOrHalf` 命中且非主人/管理员 → 红字提示 + false（阻止拆除）。
- `destroyBlock` @RETURN：拆除成功（`cir.getReturnValue()`）→ `ShopManager.removeIfShop` 自动删店。

## `ExplosionDamageCalculatorMixin`（目标 `ExplosionDamageCalculator`）

- `shouldBlockExplode` @HEAD（cancellable）：`blockGetter instanceof ServerLevel` 且
  `ShopManager.isShopOrHalf` → 返回 false（商店箱子免疫 TNT/苦力怕等爆炸破坏判定）。

## `ServerExplosionMixin`（目标 `ServerExplosion`）

- `calculateExplodedPositions` @RETURN：从返回的破坏位置列表 `removeIf` 商店位置。
- 背景：26.3 实体来源爆炸走子类计算器绕过 `ExplosionDamageCalculator` 注入点，
  且爆炸破坏只消费这份位置列表，在列表层过滤即可覆盖所有实体来源爆炸。

## `LevelMixin`（目标 `Level`）

- `destroyBlock(BlockPos, boolean, Entity, int)` @HEAD（cancellable）：兜底保护——无实体的
  外部破坏（如末影龙等不走爆炸路径的途径）命中商店箱子时返回 false。
  玩家破坏不经过此方法（ServerPlayerGameMode 自行处理）；实体引起的破坏不受影响。

## 引用关系一览

- 商店保护：`ServerPlayerGameModeMixin` / `ExplosionDamageCalculatorMixin` /
  `ServerExplosionMixin` / `LevelMixin` → 全部调 `ShopManager`。
- 价格标签：`InventoryMixin`（进背包）、`CraftingMenuMixin`（合成结果源头）、
  `AbstractContainerMenuMixin`（快捷移动合并前兜底）、`ServerPlayerMixin`（开关容器）、
  `LivingEntityMixin`（掉落清除）、`ServerGamePacketListenerImplMixin`（创造/buymode 补发）。
- buymode：`ServerGamePacketListenerImplMixin` + `ServerPlayerMixin.doCloseContainer` +
  `Economy.java` DISCONNECT → `BuyModeManager`。
- 红名：`ServerPlayerMixin.getTabListDisplayName` + `PlayerMixin.getDisplayName` → `AdminUtil`。
