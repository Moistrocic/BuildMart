# `mois.economy.data` 包 — SQLite 资金数据库

## `EconomyDb.java`

- 文件：`world/economy.db`（`EconomyDb.open(worldDir.resolve("economy.db"))`）；驱动 `org.sqlite.JDBC`
  随 jar 打包（嵌套 jar 环境不依赖 ServiceLoader，显式 `Class.forName` 加载）。
- 连接参数：`PRAGMA journal_mode=WAL`、`PRAGMA busy_timeout=5000`。
- **表结构**：
  - `economy_accounts(uuid TEXT PRIMARY KEY, name TEXT NOT NULL, balance INTEGER NOT NULL DEFAULT 0 CHECK (balance >= 0))`
  - `economy_settings(key TEXT PRIMARY KEY, value TEXT NOT NULL)`（目前仅存公告 `announcement`）。
  - `homes(uuid, name, world, x, y, z, created, PRIMARY KEY(uuid, name))` —— /sethome 的家。
  - `back_points(uuid PRIMARY KEY, world, x, y, z)` —— /back 的最近死亡点。
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
- **`DatabaseException extends RuntimeException`**：数据层不可恢复错误，命令层 catch 后向玩家返回可读提示。
- **`runSelfTest()`**（open 时自动）：随机账户验证写入/读取/转账/余额不足拦截/名字存储/扣款边界，
  用后清理（`deleteAccount`），失败抛 `IllegalStateException` 使 open 的 catch 里 `close()`。
- 事务模式：`transferMany`/`deductMany` 手动 `setAutoCommit(false)` + commit/rollback + finally 恢复。
