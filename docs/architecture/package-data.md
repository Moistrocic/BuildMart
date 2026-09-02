# `mois.buildmart.data` 包 — SQLite 资金数据库

## `EconomyDb.java`

- 文件：`world/economy.db`（`EconomyDb.open(worldDir.resolve("economy.db"))`）；驱动 `org.sqlite.JDBC`
  随 jar 打包（嵌套 jar 环境不依赖 ServiceLoader，显式 `Class.forName` 加载）。
- 连接参数：`PRAGMA journal_mode=WAL`、`PRAGMA busy_timeout=5000`。
- **表结构**：
  - `economy_accounts(uuid TEXT PRIMARY KEY, name TEXT NOT NULL, balance INTEGER NOT NULL DEFAULT 0)` —
    **余额允许为负**（管理前端删除交易记录回滚造成；旧库的 `CHECK (balance >= 0)` 由
    `migrateSchema` 重建表移除）。
  - `economy_settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)`（目前仅存公告 `announcement`）。
  - `homes(uuid, name, world, x, y, z, created, PRIMARY KEY(uuid, name))` —— /sethome 的家。
  - `back_points(uuid PRIMARY KEY, world, x, y, z)` —— /back 的最近死亡点。
  - `economy_transactions(id INTEGER PRIMARY KEY AUTOINCREMENT, uuid, name, type, channel,
    item_id, item_name, item_data, count, price, balance, time)` —— **资金流水（一切资金变化）**：
    - **type**：`BUY`（购买，删除记录回滚退款 +price）/ `SELL`（出售，删除记录回滚扣回 -price）/
      `TRANSFER_IN`/`TRANSFER_OUT`（/pay 转账）/ `ADMIN_ADD`/`ADMIN_SUB`/`ADMIN_SET`
      （/eco 与管理面板）/ `FEE`（飞行/传送扣费）/ `REDPACKET_SEND`/`REDPACKET_CLAIM`/
      `REDPACKET_REFUND`（红包）；
    - **channel**：`SHOP`/`BM`/`BUY`（买卖）/ `PAY` / `ECO` / `BALOP` / `FLY` / `TP` / `REDPACKET`；
    - **price 语义**：BUY/SELL 为交易金额（正数）；其他类型为资金变化量（分，入账为正、扣款为负）；
    - item_id/item_name 为注册表 ID 与显示名（非买卖记录 item_id 为空串、item_name 为描述、
      count 为 0），**item_data 为物品完整组件数据（ItemStack.CODEC 编码的 JSON 字符串，
      含 NBT/附魔/自定义名；旧记录为 NULL，由 `migrateSchema` 补列）**；
    - balance 为变化后余额（分），time 为毫秒时间戳；索引 `idx_transactions_uuid_time (uuid, time DESC)`；
      `deleteAccount` 级联删除流水。
    - 记录点（**全部资金变化，强制约定见 AGENTS.md 第 6 节**）：`ShopManager.sell`、`BalshopCommands.buy`、
      `BuyModeSettlement.sendBuy/sendSell/sendRefund`、`BuyModeSession.settleAndClear`（买卖，
      带 item_data）；`EconomyCommands`（/pay 转账、/eco add/remove/set）、`BalopServer`
      （管理面板加钱/扣钱/设余额）、`HongbaoCommands`（发/领/过期返还）、`FlyManager`（飞行扣费）、
      `TeleportManager`（传送扣费）。记录失败一律静默，不影响资金结算。
- 家/死亡点 API：`setHome`/`getHome`/`getHomes`（按 created 倒序，第一项为最近设置）/`countHomes`，
  `setBackPoint`/`getBackPoint`/`clearBackPoint`；记录类型 `HomeEntry(name, world, x, y, z, created)`
  与 `BackPoint(world, x, y, z)`，world 为维度 ID 字符串（如 "minecraft:overworld"）。
- 服务器公共账户已移除（只有玩家账户）；历史遗留的全零 UUID 账户行在排行榜/账户数/总资产统计中排除
  （`LEGACY_SERVER_ACCOUNT_UUID`），`totalPlayerAssets()` 汇总所有玩家余额作为服务器总资产。
- **API（全部 `synchronized`，未 open 时 `requireOpen()` 抛 `DatabaseException`）**：
  | 方法 | 说明 |
  |---|---|
  | `isOpen()` / `getDbPath()` | 状态查询 |
  | `open(Path)` / `close()` | 生命周期；open 内 `initSchema` + `runSelfTest` |
  | `getBalance(UUID)` | 查余额；无账户返回 0 |
  | `ensureAccount(UUID, name)` | 建行或更新名字（`ON CONFLICT ... DO UPDATE`）；name 为 null 时存“未知玩家” |
  | `hasAccount(UUID)` | 账户是否已注册（payee 离线设置校验用） |
  | `credit(UUID, name, amount)` | 入账；账户不存在自动创建；amount 非负校验 |
  | `deduct(UUID, amount)` | 单笔扣款；余额不足返回 false 且不改 |
  | `deductMany(List<UUID>, amount)` | 批量扣款，事务原子（任一不足整体回滚） |
  | `setBalance(UUID, name, balance)` | 直接设余额（非负） |
  | `transfer(from, to, toName, amount)` | 单笔转账；转自己视为无操作；目标不存在自动建行 |
  | `transferMany(from, targets, names, amount)` | 批量转账；总额用 `Math.multiplyExact` 防溢出；事务原子 |
  | `accountCount()` | 账户总数（/baltop 分页） |
  | `topAccounts(limit, offset)` | 排行榜：`ORDER BY balance DESC, name ASC`，返回 `AccountEntry(uuid, name, balance)` |
  | `getAnnouncement()` | 读公告（连接未开时返回 null 而非抛错，供 JOIN 阶段使用） |
  | `setAnnouncement(String)` | 写公告；null/空 → 删除记录 |
  | `recordTransaction(uuid, name, type, channel, itemId, itemName, itemData, count, price)` | 写一条买卖流水（内部 ensureAccount + 附变化后余额；itemData 可空） |
  | `recordMoneyLog(uuid, name, type, channel, description, price)` | 写非买卖资金流水（转账/管理/扣费/红包等；item 字段用描述占位，price 为变化量可负） |
  | `recentTransactions(uuid, limit)` | 玩家最近流水（time DESC, id DESC），返回 `TransactionEntry(id, uuid, name, type, channel, itemId, itemName, itemData, count, price, balance, time)` |
  | `transactionCount(uuid)` | 玩家流水总数 |
  | `queryTransactions(uuid, types, channels, priceMin, priceMax, limit, offset)` | 分页查询流水（uuid 可空=全部、types/channels 多值 IN 匹配、priceMin/priceMax 金额区间含边界，均可不传），返回 `TransactionPage(total, list)` |
  | `listAccounts(query, sort, limit, offset)` | 分页查询账户（名字/UUID 模糊；sort 白名单 balance_desc/balance_asc/name_asc/name_desc/uuid_asc/uuid_desc），返回 `AccountPage(total, list)` |
  | `accountName(uuid)` | 查询账户名（不存在返回“未知玩家”） |
  | `setBalance(uuid, name, balance)` | 设置余额（**允许负数**） |
  | `adjustBalance(uuid, name, delta)` | 直接增减余额（delta 可负，允许余额为负），返回新余额（管理前端用） |
  | `deleteTransactionsWithRollback(ids)` | **删除流水并同步回滚资金**（原子事务）：删 BUY = 余额 +price（退款收回），删 SELL = 余额 -price（所得扣回）；**其他类型仅删记录、不回滚资金**；返回 `List<RollbackResult(id, uuid, name, type, price, newBalance)>`，不存在的 id 跳过 |
- **`DatabaseException extends RuntimeException`**：数据层不可恢复错误，命令层 catch 后向玩家返回可读提示。
- **`runSelfTest()`**（open 时自动）：随机账户验证写入/读取/转账/余额不足拦截/名字存储/扣款边界，
  用后清理（`deleteAccount`），失败抛 `IllegalStateException` 使 open 的 catch 里 `close()`。
- 事务模式：`transferMany`/`deductMany` 手动 `setAutoCommit(false)` + commit/rollback + finally 恢复。
