# `mois.economy.shop` 包 — 箱子商店

## `Shop.java` — 商店实体（内存对象）

字段（可变，getter/setter）：`dimension`（`ResourceKey<Level>`）、`pos`（`BlockPos`）、
`owner`（UUID）+ `ownerName`、`payee`（UUID）+ `payeeName`、`remainingTicks`（倒计时）、
`displayUuid`（悬浮字实体 UUID）、`wasOpen`（上一秒是否开启，用于“关闭瞬间立即出售”）。

## `ShopManager.java` — 商店管理（内存索引 + JSON 持久化）

- `RESET_TICKS = 1200`（60 秒刷新周期）。
- 数据结构：`SHOPS: Map<ShopKey, Shop>`；`ShopKey(dimension, pos)` 是 record；
  持久化文件 `world/economy-shops.json`（`init(Path worldDir)` 加载，`storagePath` 保存路径）。
- API：
  | 方法 | 说明 |
  |---|---|
  | `init(Path)` | 清空索引并从 JSON 加载（`load()`，失败清空并记日志） |
  | `get(dimension, pos)` | 精确查找 |
  | `create(ServerLevel, pos, owner)` | 建店（初始收款人=所有人，倒计时 RESET_TICKS）+ 生成悬浮字 + save |
  | `remove(shop, level)` | 删店（含丢弃悬浮字实体 `display.discard()`）+ save |
  | `setPayee(shop, uuid, name)` | 改收款人 + save |
  | `onServerTick(MinecraftServer)` | 见下 |
  | `isShopOrHalf(level, pos)` / `getShopOrHalf(level, pos)` | pos 自身或双箱另一半是否为商店 |
  | `removeIfShop(level, pos)` | pos 恰为商店所在箱时移除并返回 true（破坏保护用） |
  | `save()` | 全量写 JSON（`GsonBuilder.setPrettyPrinting`） |
- **每秒 tick 逻辑**（`getTickCount() % 20 == 0`，SHOPS 为空直接返回）：
  1. 维度未加载或区块未实体 tick（`isPositionEntityTicking`）→ 跳过（省资源）；
  2. 箱子本体已不是 `ChestBlock` → 自愈移除；
  3. 开启中（`isChestOpen`，本箱+另一半的 `ChestBlockEntity.getOpenCount`）→ 标记 `wasOpen`、锁定倒计时；
  4. 上一秒开启本秒关闭 → 倒计时归零立即出售；
  5. 否则倒计时 -20，≤0 时 `sell`；最后刷新悬浮字。
- **`sell(level, shop)`**：取本箱 + 水平相邻同类型箱的 `Container` 列表，逐格清空**可交易**物品
  （不可交易物品留下——不能整箱 `clearContent`，会误删留下的物品），累计
  `ItemValues.price` 入账收款人（`EconomyDb.credit`，不通知），倒计时重置、save、记日志；
  **每格出售写一条交易流水**（`EconomyDb.recordTransaction`，type=SELL、channel=SHOP、
  收款人 payee，记录失败静默不影响出售）。
- **悬浮字**：`Display.TextDisplay`（`EntityTypes.TEXT_DISPLAY`），位于箱子上方 1.6 格，
  内容“所有人/收款人/刷新：N 秒”，金色；`updateDisplay` 在实体丢失时重建（`spawnDisplay`）。
- **持久化格式**：`{"shops":[{dimension, x, y, z, owner, ownerName, payee, payeeName,
  remainingSeconds, displayUuid?}]}`；dimension 用 `ResourceKey.codec(Registries.DIMENSION)`
  + `JsonOps` 编解码（`encodeDimension`/`decodeDimension`）。
- **破坏保护**（被 mixin 调用）：
  - `ServerPlayerGameModeMixin`：玩家拆除前校验主人/管理员；
  - `ExplosionDamageCalculatorMixin`：爆炸破坏判定返回 false；
  - `ServerExplosionMixin`：`calculateExplodedPositions` 结果中剔除商店位置（26.3 爆炸只消费该列表）；
  - `LevelMixin`：无实体外部破坏（如末影龙）拦截 `Level.destroyBlock`。
- 工具：`satAdd` / `satMul`（与 ItemValues 等处的实现重复，按饱和运算语义各自维护）。
