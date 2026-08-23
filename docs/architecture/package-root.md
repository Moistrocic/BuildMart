# 根包 `mois.economy`

## `Economy.java` — 模组入口与全局生命周期

`implements ModInitializer`；`MOD_ID = "economy"`；`LOGGER = LoggerFactory.getLogger("economy")`。

- `onInitialize()` 注册三类回调：
  - `ServerLifecycleEvents.SERVER_STARTED`：
    `ItemValues.load` → `EnchantmentValues.load` → `EconomyConfig.load`（均读 `FabricLoader.getConfigDir()`），
    `PriceLore.enabled = EconomyConfig.itemPricesInLore()`，开启时 `PriceLore.selfCheck()`；
    `EconomyDb.open(worldDir/economy.db)`；`ShopManager.init(worldDir)`。
  - `ServerLifecycleEvents.SERVER_STOPPING`：`ShopManager.save()` + `EconomyDb.close()`。
  - `ServerTickEvents.END_SERVER_TICK`：`ShopManager::onServerTick`、`FlyManager::onServerTick`。
  - `ServerPlayConnectionEvents.JOIN`：`EconomyDb.ensureAccount`（同步玩家名，首次自动建行）、
    发送红色公告（`EconomyDb.getAnnouncement`）、`FlyManager.onJoin`（恢复飞行）。
  - `ServerPlayConnectionEvents.DISCONNECT`：`PriceLore.untagPlayerAndMenu`、`FlyManager.onDisconnect`
    （收回能力但不退出模式）、`BuyModeManager.exit`。
  - `CommandRegistrationCallback`：`EconomyCommands.register`，日志“命令注册完成
    （bal/pbal/pay/baltop/balhelp/announcement/eco/peco/balshop/fly）”。
- 末尾打印启动标记 `Economy Mod Loaded!`。
- `id(String path)` → `Identifier.fromNamespaceAndPath("economy", path)`（当前未被使用，预留）。

## `Money.java` — 金额工具（分 ↔ 元字符串）

- 金额在系统内一律 `long` 分（cents）。
- `parseCents(String)` / `parseCentsAllowZero(String)` → 分；非法输入抛
  `CommandSyntaxException`（INVALID_AMOUNT / NOT_POSITIVE / NOT_NEGATIVE / TOO_MANY_DECIMALS / TOO_LARGE）。
- `format(long cents)` → `"X.XX"`（两位小数，整数运算，负号处理）。
- 被 `EconomyCommands`、`BalshopCommands`、`FlyCommands`、`FlyManager`、`ShopManager` 等广泛使用。

## `PriceLore.java` — 价格标签（真实 lore 组件）

给玩家持有的物品打“单价”标签；纯服务端实现，纯净客户端可见。

- `public static volatile boolean enabled` — 由 `EconomyConfig.itemPricesInLore()` 决定。
- `MARKER = "单价："`；`isPriceLine(Component)` 同时识别金色“单价：”行与红色“不可交易”行。
- **`tag(ItemStack)`** — 幂等打标：若最后一行已是完全相同的价格行则**直接返回、不重写组件**
  （重写会生成新组件对象，导致 26.3 客户端 `sameDestroyTarget` 检测到手持物变化而重置挖掘进度）。
  不递归进容器内容物（内容物在容器打开时另行打标）。
- **`untag(ItemStack)`** — 递归清除价格行（深入 `CONTAINER` 与 `BUNDLE_CONTENTS`）。
- `tagMenu(AbstractContainerMenu)` — 给界面所有槽位打标（含玩家背包部分）。
- `untagMenu(menu, playerInventory)` — 关容器时清除非玩家背包槽位。
- `untagInventory(ServerPlayer)` — 清背包（含盔甲/副手/容器内容物）与光标。
- `untagPlayerAndMenu(ServerPlayer)` — 下线用：背包 + 当前容器 + 光标。
- `priceLine(ItemStack)` — 由 `ItemValues.unitPrice` 生成：
  `UNTRADEABLE` → 红色“不可交易”；否则金色“单价：X 元”（不带斜体）。
- `selfCheck()` — 启动自检：打标/幂等/保留自定义 lore/清除。
- **价格随物品状态变化**：耐久物品的价格按剩余耐久折算（`ItemValues.pricePerItem`），
  因此工具掉耐久后重新打标会得到新价格行——这是挖掘进度 bug 的根源之一，改动需谨慎。
