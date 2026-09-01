# `mois.economy.mixin` 包 — 全部 16 个 Mixin

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
5. `tick` @RETURN：`FlyManager.syncDigBoost`——每 tick 维护飞行挖掘加速属性
   （`BLOCK_BREAK_SPEED` ×5 瞬态修改器，见 package-fly.md；属性由原版机制自动同步客户端）。

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

本类最复杂，共 5 个注入点：

1. `handleSetCreativeModeSlot` @HEAD（cancellable）`economy$handleBuyMode` — **buymode 主结算**：
   - 非 buymode 直接放行（原版处理）。
   - `slotNum < 0`（创造界面丢弃包，原版会生成实体）→ `ci.cancel()` + `handleBuyModeDrop`：
     匹配会话暂存（先拿起再丢）→ **卖出**（按丢弃数量退款，不生成实体）；
     不匹配 → 挂起 `session.pendingDrop`，由下一个槽位包用「槽位原内容」判定
     （匹配 = 背包 ctrl+q 直接丢 → 卖出；不匹配 = 面板 ctrl+q → **购买** + 生成实体）；
     **挂起 ≥2 tick 仍无槽位包认领（面板 ctrl+q 无后续包）→ 服务端 tick 直接结算为购买**
     （`Economy.java` END_SERVER_TICK → `BuyModeManager.onServerTick` →
     `BuyModeSettlement.settlePendingDrop`，避免滞后一拍）；
     不可交易物品丢弃 → 作废。挂起的旧 pendingDrop 被新丢弃包触发时按面板购买结算。
   - `slotNum > 45` 放行（原版同样忽略）。
   - 接管后按**暂存模型 + 挂起配对**判定（详见 package-buymode.md 的 `BuyModeSession`）：
     - 槽位变空/同物品数量减少（拿起、拆分拿起）→ 物品入暂存，不结算；
     - 槽位出现物品：增量匹配暂存（同 item+组件，untag 归一比较）→ 中性放回；
     - **增量超出暂存（面板叠放）或与暂存完全不同 → 一律先挂起 `session.pendingSlot`**
       （槽位暂不修改，记录 prev/next/vanished/unbought），不再立即购买：
       - 下一个槽位包构成**对称交换**（本包原内容 = 挂起 next 且本包出现 = 挂起 prev，
         同 item+组件+数量，`isSwapPair`；锚点用 prev 而非 vanished——**空槽交换**时
         vanished 为空，prev 才是配对锚点）→ **数字键/槽间交换**（26.3 创造界面数字键 1-9
         对悬停物品执行 SWAP，客户端本地交换后经 `broadcastChanges` 把两个变化槽逐槽上报）：
         双槽中性放回，不扣款、不提示、无暂存残留；
       - 配对失败 / **挂起 ≥2 tick 无配对包**（服务端 tick → `BuyModeSettlement.settlePendingSlot`）：
         挂起内容通过 `isVanillaCreativeItem` 严格比对（比对前 `PriceLore.untag`，价格行是
         模组自身数据须绕过）→ **面板购买**（按未吸收增量扣款，原内容消失记录保留）；
         比对不过或余额不足 → **拒绝**：槽位保持原状（物品未丢失）、`removeVanished`
         撤销消失记录、补发权威内容 + 提示——杜绝「物品既在背包又被卖出退款」的白嫖
         与「残留被后续包吸收」的免费复制。
   - **严格比对索引**首次使用时构建：`CreativeModeTabs.tryRebuildTabContents` 用服务端
     注册表/特性重建创造面板内容（与客户端一致），收集全部标签页 displayItems 与
     searchTabDisplayItems；**另加兜底**——所有注册物品的纯净默认形态
     （`new ItemStack(item)`）也入索引，保证 26.3 独立服务端即使未构建标签页内容，
     未经修改的纯净物品也能通过比对（内容类物品如药水/旗帜的合法形态来自标签页内容）。
     比对用「相对物品默认组件的补丁相等」（`getComponentsPatch().equals`）而非
     `PatchedDataComponentMap` 整体——网络重建堆与本地构造堆的内部表示可能不同，
     语义内容（增删补丁）一致即可。“保存的快捷栏”（标签页/热键加载的客户端本地数据）
     中的改造物品（属性/超限附魔/自定义药水效果等）因此一律无法进入；
     卖出方向不检测（改造物品无法通过便捷购买获得，能持有的只有管理员）。
   - 成功写入后：`PriceLore.tag(newStack)` + `menu.setRemoteSlot(slotNum, newStack)` +
     **`player.connection.send(new ClientboundContainerSetSlotPacket(menu.containerId,
     menu.incrementStateId(), slotNum, newStack.copy()))`** ——原版 `setRemoteSlot` 会把槽位标记为
     “客户端已知”而不再下发，必须显式补发才能让客户端立即看到价格标签
     （统一走 `BuyModeSettlement.setSlotAndSync`）。
2. `handleSetCreativeModeSlot` @RETURN `economy$syncCreativeSlotTag` — **普通创造模式补发**：
   非 buymode 且 `hasInfiniteMaterials()` 的玩家，原版处理完成后把打标后的权威槽位补发一次
   （守卫：slotNum 1..45、非空、数量合法、`PriceLore.enabled`）。与上一条互斥。
3. `handleContainerClick` @HEAD（cancellable）`economy$buyModeClickHead` — buymode 点击包快照：
   - 记录 `economy$beforeItems`（全部槽位拷贝）+ `economy$settleClick` 标记；
   - `slotNum == -999` 且光标有物品（点击外部丢弃）→ 清空光标 + `ci.cancel()`
     （26.3 创造界面实际不发点击包，此路径为防御保留；光标物品由暂存模型结算）。
4. `handleContainerClick` @RETURN `economy$buyModeClickSettle` — buymode 点击包结算（防御）：
   - 对比 before/current：不可交易整体回滚；按各槽位 `ItemValues.price` 净变化结算
     （`SlotDelta(int slot, before, after, delta)` record 记录变化槽位）；
     **购买方向（netDelta>0）同样执行严格比对**（任一价值增加槽位不匹配 → 整体回滚）；
     余额不足回滚 + 全量同步；
   - 结算完成后对每个变化槽位 `PriceLore.tag` + 补发 `ClientboundContainerSetSlotPacket`
     （结算发生在原版 `broadcastChanges` 之后，补发保证界面内标签立即刷新）。
5. `handleChat` @HEAD（cancellable）`economy$hongbaoChat` — **红包聊天领取**：
   发言与某红包口令完全一致（trim 精确匹配）→ `HongbaoCommands.claimByPass` 自动领取，
   **发言照常进入公屏**（不取消原版处理）；领取失败私聊红字；非口令发言完全不受影响。
- **结算工具已提取到 `BuyModeSettlement`**（mixin 不允许非 private 方法，会被合并进 target
  导致启动崩溃）：创造索引与严格比对（`isVanillaCreativeItem`）、面板购买链（`buyDrop`/
  `approveBuy`/`settlePendingDrop`/`settlePendingSlot`）、槽位设置与同步（`setSlotAndSync`）、
  买卖提示（`sendBuy/sendSell/sendRefund/sendModified` 等）、
  资金操作（`balance*`/`deductQuietly`/`creditQuietly`/`satAdd`）都在该独立类，mixin 只保留
  private 方法并通过 `BuyModeSettlement.xxx` 调用。
- 设计原则：购买花费直接从玩家账户扣除、不进入任何账户；一切以服务端权威槽位状态为准。

## `ServerPlayerGameModeMixin`（目标 `ServerPlayerGameMode`）— 商店拆除保护 + buymode 禁挖

- `destroyBlock` @HEAD（cancellable）：
  - **buymode 激活时直接取消**（`BuyModeManager.isActive`）——instabuild 会让客户端走创造
    式秒破且无掉落物，关闭 buymode 后恢复正常生存挖掘；
  - 否则 `ShopManager.getShopOrHalf` 命中且非主人/管理员 → 红字提示 + false（阻止拆除）。
- `destroyBlock` @RETURN：拆除成功（`cir.getReturnValue()`）→ `ShopManager.removeIfShop` 自动删店。
  （飞行挖掘加速已改由属性方案实现——`FlyManager.syncDigBoost` 挂 `BLOCK_BREAK_SPEED` ×5
  瞬态修改器，原版属性同步自动下发客户端，无需速度注入/补发包，详见 package-fly.md。）

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

## `FishingHookMixin`（目标 `FishingHook`）— 趣味钓鱼战利品替换

- `retrieve(ItemStack)` @HEAD（cancellable）`economy$customFishingLoot`：`funFishing` 开关开启 +
  服务端 + 战利品路径（`hookedIn == null && nibble > 0`，@Shadow 字段）→
  `FishingManager.roll` 按概率取战利品替换原版战利品表。
- 命中：触发 `FISHING_ROD_HOOKED` 成就 + 生成 `ItemEntity`（原版双重 sqrt 速度公式）+ 经验球 +
  鱼标签物品计 `FISH_CAUGHT`；未命中（EMPTY）无掉落。
- ⚠️ **cancel 后必须补做原版收尾**：`hook.discard()`（否则鱼钩不销毁可重复收竿刷战利品）、
  返回值 `onGround() ? 2 : 1`。详见 package-fishing.md。

## `BaseSpawnerMixin`（目标 `BaseSpawner`）— 刷怪笼生成参数重算 + 转化/自动出售

- `serverTick` @HEAD `economy$applyComputedParams`：方块实体为带标签刷怪笼时按
  「等级/微调/开关」计算并写入生成参数（`effLevel <= 0` 不写 = 原版机制）。
- **直接转化**：`autoConvert` 且 `spawnDelay <= 0`（本 tick 原版将生成）→
  `SpawnerManager.convertToDrops` 逐只（`spawnCount` 只/周期）计算击杀掉落存入方块，
  并重置倒计时让原版逻辑走递减分支（不生成生物）。
- **自动出售**：`autoSell` 时维护 60 秒出售周期（`economy_sell_timer`），到点批量
  `sellStoredDrops`；每 tick 同步金色悬浮（创建人/收款人/倒计时），关闭时移除残留。
- 详见 package-spawner.md。

## `SpawnerBlockMixin`（目标 `Block`）— 带标签刷怪笼可回收

- `playerDestroy` @HEAD `economy$dropSpawnerItem`：非创造 + `instanceof SpawnerBlock` +
  带标签（`SpawnerStateAccess.economyTagged`）→ 掉落带完整数据物品
  （`saveCustomOnly` + `normalizeForStacking` 归一化：`Delay` 重置为 20、
  移除默认 `SpawnData`，与 `/spawner give` 底版 NBT 完全一致 → 可互相堆叠；
  存储的转化掉落物随方块物品保存防丢失）；同时移除自动出售悬浮实体。
- ⚠️ `playerDestroy` 声明于 `Block`，mixin 不搜索父类方法，须注入 `Block` 并在运行时
  `instanceof SpawnerBlock` 判断。

## `SpawnerBlockEntityMixin`（目标 `SpawnerBlockEntity`）— 刷怪笼玩法状态持久化

- `loadAdditional` / `saveAdditional` @RETURN：读写 `economy_spawner` / `economy_level` /
  `economy_override_*` / 直接转化三配置 / `economy_drops`（`ItemStack.OPTIONAL_CODEC.listOf()`）
  / `economy_converted` / 创建人收款人 / 出售周期 / 悬浮 UUID，实现 `SpawnerStateAccess`。
- 原版加载/保存忽略未知 key，与模组 key 互不影响。

## `BlockItemMixin`（目标 `BlockItem`）— 非 OP 玩家可放置带标签刷怪笼

- `updateCustomBlockEntityTag` 内 `Player.canUseGameMasterBlocks()` 调用点 @Redirect
  `economy$allowTaggedSpawnerPlacement`：物品 `BLOCK_ENTITY_DATA` 携带 `economy_spawner`
  标签时返回 true（放行原版 `onlyOpCanSetNbt` 检查），否则原逻辑。
- 背景：`MOB_SPAWNER` 属于 `OP_ONLY_CUSTOM_DATA`（命令方块/告示牌/刷怪笼等），非 OP
  生存玩家放置时原版直接跳过 `loadInto` → 标签/等级/绑定全部丢失（变原版空笼）。
- 放置时**首次记录创建人**（自动出售悬浮与收款人默认值用）。
- 安全性：模组笼生成参数每 tick 被 `BaseSpawnerMixin` 覆盖，伪造 NBT 不生效；
  原版刷怪笼物品不受影响（无标签 → 仍走原版权限规则）。

## 引用关系一览

- 商店保护：`ServerPlayerGameModeMixin` / `ExplosionDamageCalculatorMixin` /
  `ServerExplosionMixin` / `LevelMixin` → 全部调 `ShopManager`。
- 价格标签：`InventoryMixin`（进背包）、`CraftingMenuMixin`（合成结果源头）、
  `AbstractContainerMenuMixin`（快捷移动合并前兜底）、`ServerPlayerMixin`（开关容器）、
  `LivingEntityMixin`（掉落清除）、`ServerGamePacketListenerImplMixin`（创造/buymode 补发）。
- buymode：`ServerGamePacketListenerImplMixin` + `ServerPlayerMixin.doCloseContainer` +
  `Economy.java` DISCONNECT → `BuyModeManager`；结算工具在 `BuyModeSettlement`。
- 红包：`ServerGamePacketListenerImplMixin.economy$hongbaoChat`（聊天领取）→ `HongbaoCommands`。
- 钓鱼：`FishingHookMixin` → `FishingManager` / `EconomyConfig.funFishing`。
- 刷怪笼：`BaseSpawnerMixin`（参数重算）+ `SpawnerBlockMixin`（掉落回收）+
  `SpawnerBlockEntityMixin`（状态持久化）+ `BlockItemMixin`（非 OP 放置放行）→ `SpawnerManager`。
- 红名：`ServerPlayerMixin.getTabListDisplayName` + `PlayerMixin.getDisplayName` → `AdminUtil`。
