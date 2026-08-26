# `mois.economy.balop` 包 — 数据库管理前端（/balop）

## `BalopServer.java` — 本机 HTTP 管理服务器

- 生命周期由指令控制：`/balop start|stop`（仅管理员，注册于 `EconomyCommands`）；
  监听地址/端口来自 `config/economy/config.json` 的 `balop` 段（默认 `localhost:8899`，
  仅本机可访问；**改绑局域网/公网地址时任何能访问该端口的人都能改资金，无鉴权，请自担风险**）。
- 实现：JDK 自带 `com.sun.net.httpserver.HttpServer`（无新增依赖）+ 独立 slf4j Logger
  （daemon 线程池）；所有数据库操作走 `EconomyDb`（synchronized，WAL + busy_timeout，
  与服务器主线程并发安全）。

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 管理页面（单页 HTML，内嵌 JS/CSS，深色风格） |
| GET | `/api/players?q=&page=&size=` | 玩家列表（名字/UUID 模糊查询，余额倒序分页） |
| GET | `/api/players/{uuid}` | 玩家详情（名字 + 余额） |
| POST | `/api/players/{uuid}/credit` | 加钱 `{amountCents}`（分） |
| POST | `/api/players/{uuid}/deduct` | 扣钱（**允许扣成负数**，与玩家侧 deduct 的余额检查不同） |
| POST | `/api/players/{uuid}/balance` | 设置余额（可负） |
| GET | `/api/players/{uuid}/transactions?type=&channel=&page=&size=` | 该玩家交易流水 |
| GET | `/api/transactions?uuid=&type=&channel=&page=&size=` | 交易流水（uuid 可空 = 全部玩家） |
| DELETE | `/api/transactions/{id}` | 删除单条并**同步回滚资金** |
| POST | `/api/transactions/delete` | 批量删除 `{ids:[...]}` 并同步回滚资金 |

## 交易记录删除回滚（EconomyDb.deleteTransactionsWithRollback）

- 删除一条 **BUY** 记录 = 撤销该笔购买 → **退款收回**（余额 +price）；
  删除一条 **SELL** 记录 = 撤销该笔卖出 → **卖出所得扣回**（余额 -price）。
- 读记录 + 删行 + 余额调整在**同一事务**（原子）；不存在的 id 跳过。
- **回滚后余额允许为负**：economy_accounts 已移除 `CHECK (balance >= 0)`
  （`migrateSchema` 对旧库重建表），`setBalance`/`adjustBalance` 均放行负数；
  玩家侧 `deduct`/`transferMany` 仍保留余额检查（正常游玩不允许透支）。

## 前端页面

- 玩家列表（搜索、分页、负余额红色徽标）→ 点击进入详情：
  - 资金操作：加钱 / 扣钱 / 设为余额（金额输入为元，可小数，前端精确转分）；
  - 交易流水：类型（BUY/SELL）与渠道（SHOP/BM）筛选、分页、每行可展开
    「物品数据 (NBT/组件)」查看 item_data 原始 JSON；
  - 删除：单条删除、勾选批量删除，均二次确认并提示回滚金额与回滚后余额。
