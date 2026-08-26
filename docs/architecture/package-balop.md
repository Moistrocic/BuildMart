# `mois.economy.balop` 包 — 数据库管理前端（/balop，多会话验证）

## `BalopServer.java` — 本机 HTTP 管理服务器（多会话）

- 生命周期由指令控制：`/balop start|stop`（仅管理员，**需玩家身份**，注册于 `EconomyCommands`）；
  监听地址/端口来自 `config/economy/config.json` 的 `balop` 段（默认 `localhost:8899`，
  仅本机可访问；**改绑局域网/公网地址时任何能访问该端口的人都能改资金，请自担风险**），
  也可用 `/config balop.host` / `/config balop.port` 热修改（改后需 `/balop stop` + start 生效）。
  `/balop start` 的提示中地址为**可点击聊天链接**（`ClickEvent.OpenUrl`，点击弹确认后打开浏览器）。
- **会话模型**：
  - 每个管理员执行 `/balop start` 获得**独立会话**（32 hex 随机 token，`SecureRandom`）；
    访问地址 `http://host:port/?token=xxx`；页面与所有 API 请求必须携带 token
    （`X-Balop-Token` 请求头或 URL query），无效/过期返回 401（页面为提示页）；
  - 会话 **5 分钟无任何请求自动关闭**（daemon 扫描线程每 30 秒清理，不阻止 JVM 退出）；
    前端每 6 秒自动轮询会持续刷新 lastAccess，正常使用不会过期；
  - `/balop stop` **只关闭执行者自己的全部会话**，不影响其他管理员的会话；
    全部会话清空后 HTTP 服务器一并停止；
  - **服务器关闭（SERVER_STOPPING）时 `shutdownAll()` 关闭全部会话与 HTTP 服务**
    （即使 Windows 下 JVM 残留也不允许端口继续服务）；
  - 未启动服务器时第一个 start 按当前配置创建 HTTP 服务器，后续 start 复用
    （端口以已运行实例为准）。
- **资金操作全部写流水**（channel=BALOP）：加钱 ADMIN_ADD、扣钱 ADMIN_SUB、设余额 ADMIN_SET
  （price = 余额变化量；`recordAdminLog` 静默），与 `/eco` 同属管理员操作可追溯。
- 实现：JDK `com.sun.net.httpserver.HttpServer` + 独立 slf4j Logger（无 Minecraft 依赖，
  可独立单元测试——`.dsh-tmp/SessionTest` 验证过多会话隔离/401/stop 只关自己/超时清理）；
  所有数据库操作走 `EconomyDb`（synchronized，WAL + busy_timeout，与服务器主线程并发安全）。
- 实现：JDK 自带 `com.sun.net.httpserver.HttpServer`（无新增依赖）+ 独立 slf4j Logger
  （daemon 线程池）；所有数据库操作走 `EconomyDb`（synchronized，WAL + busy_timeout，
  与服务器主线程并发安全）。

## REST API

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/` | 管理页面（单页 HTML，内嵌 JS/CSS，深色风格；响应 `Cache-Control: no-store` 防浏览器缓存旧页） |
| GET | `/api/players?q=&sort=&page=&size=` | 玩家列表（名字/UUID 模糊查询；sort=balance_desc\|balance_asc\|name_asc\|name_desc\|uuid_asc\|uuid_desc，默认余额倒序） |
| GET | `/api/players/{uuid}` | 玩家详情（名字 + 余额） |
| POST | `/api/players/{uuid}/credit` | 加钱 `{amountCents}`（分） |
| POST | `/api/players/{uuid}/deduct` | 扣钱（**允许扣成负数**，与玩家侧 deduct 的余额检查不同） |
| POST | `/api/players/{uuid}/balance` | 设置余额（可负） |
| GET | `/api/players/{uuid}/transactions?type=&channel=&amountMinCents=&amountMaxCents=&page=&size=` | 该玩家交易流水（类型/渠道多值逗号分隔，金额区间分） |
| GET | `/api/transactions?uuid=&type=&channel=&amountMinCents=&amountMaxCents=&page=&size=` | 交易流水（uuid 可空 = 全部玩家） |
| DELETE | `/api/transactions/{id}` | 删除单条并**同步回滚资金** |
| POST | `/api/transactions/delete` | 批量删除 `{ids:[...]}` 并同步回滚资金 |

## 生命周期

- `/balop start|stop` 手动控制（多会话，见上）；**服务器关闭（SERVER_STOPPING）时自动
  `BalopServer.shutdownAll()`**——关闭全部会话与 HTTP 服务，即使 Windows 下 JVM 残留
  也不允许端口继续服务（面板能打开但数据库已关闭的假象）

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
