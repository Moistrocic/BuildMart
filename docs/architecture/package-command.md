# `mois.economy.command` 包 — 全部指令

注册中枢：`EconomyCommands.register(dispatcher, buildContext)`（由 `Economy.onInitialize` 调用），
内部再委托 `BalshopCommands.register`、`FlyCommands.register`、`TeleportCommands.register`
与 `HongbaoCommands.register`（传送指令见 package-teleport.md；红包指令见下）。

## `EconomyCommands.java` — 资金指令 + /balhelp

- `PAGE_SIZE = 10`；`HELP_LINES`（String[]，29 行）包含全部 /bal*、/shop*、/fly、传送、/suicide、/hongbao 帮助行。
- 注册的指令与执行方法：
  | 指令 | 方法 | 说明 |
  |---|---|---|
  | `/bal [玩家]` | `showBalance` | 查资金（目标用原版 `GameProfileArgument`，支持选择器） |
  | `/pbal` / `take 金额` / `save 金额` | `showServerAssets`/`takeFromServer`/`saveToServer` | 服务器公共资产（固定账户） |
  | `/pay 目标 金额` | `pay` | 转账；按 UUID 去重、跳过自己；离线目标直接入账 |
  | `/baltop [页码]` | `showTop` | 排行榜（`EconomyDb.topAccounts`） |
  | `/balhelp [页码]` | `showHelp` | 帮助分页 |
  | `/announcement 内容` / `clear` | `setAnnouncement`/`clearAnnouncement` | 进服红色公告（管理员 `LEVEL_ADMINS`） |
  | `/eco add\|remove\|set 目标 金额` | `ecoAdd`/`ecoRemove`/`ecoSet` | 管理员资金注入/回收；目标 = word 参数手动解析 |
  | `/peco add\|remove\|set 金额` | `pecoAdd`/`pecoRemove`/`pecoSet` | 服务器资产专用（管理员） |
- 工具方法（同类指令复用）：`requirePlayer`（PLAYER_ONLY）、`parseAmount`、
  `resolveUuid`（离线 UUID 回退）、`readBalance`、`countOrThrow`、`topOrThrow`、
  `transferOrThrow`、`transferManyOrThrow`、`text(content, color)`。
- 异常均用 `SimpleCommandExceptionType`（PLAYER_ONLY / DB_ERROR / PAYER_INSUFFICIENT /
  SERVER_INSUFFICIENT / PAY_NO_TARGET / AMOUNT_TOO_LARGE）。
- 转账通知：`notifyOnlineTargets` 给在线目标发绿字消息。

## `BalshopCommands.java` — 箱子商店与购买

- 常量 `MAX_BUY_COUNT = 17280`。
- `/shop create|remove` — 对准箱子（`targetedChest` 用 `player.pick(5.0, 1.0F, false)` 射线），
  仅主人/管理员可 remove（`requireOwnedShop` + `isAdmin`）。
- `/shop setpayee 玩家|setpayeeserver` — 设置收款人（`NameAndId.createOffline` 回退）。
- `/price 物品` — 查基础价（`ItemValues.get`），提示完整价值 = 基础价 + 附魔 + 容器内容物。
- `/buy 物品 数量` — `ItemArgument.item` 解析 → `ItemValues.price` 计价 →
  `EconomyDb.deduct` 只扣玩家资金（不入服务器资产）→ `inventory.add`，放不下掉落脚下。
- `/bm` — 切换 `BuyModeManager`（见 package-buymode.md）。
- 异常：NOT_CHEST / NOT_SHOP / ALREADY_SHOP / NOT_OWNER / PLAYER_ONLY / DB_ERROR / PAYER_INSUFFICIENT。
- 注册：`/shop` 为商店主命令（原 /balshop 已移除）；`/price`、`/buy`、`/bm` 为顶层简化入口，
  `/shop getprice|buy|buymode` 仍保留作为子命令。

## `HongbaoCommands.java` — 红包

- 注册：`/hongbao 总金额 数量 口令`（发红包）与 `/hongbao 口令`（领取），按参数个数区分。
- 发红包：`Money.parseCents` 解析金额 → 校验 总金额 ≥ 数量（每个至少 0.01 元）→
  `EconomyDb.deduct` 扣发红包者余额 → 存入内存表（口令 → 红包，同口令覆盖）→ 全员广播。
- 领取：命中口令且有余量 → 金额 = 随机 1 ~ (总金额 / 数量) × 2 分（最后一个红包领剩余全部，
  保证总额守恒；随机时给后续红包至少留 1 分）→ `EconomyDb.credit` 入账 → 全员广播
  「领到 X 元，红包剩余 N 个」。
- 内存存储：服务器停机时（`SERVER_STOPPING`）所有未领取红包作废并**返还剩余金额**给发红包人
  （`refundAll`）；同口令新红包覆盖旧红包时同样返还旧红包剩余。崩溃强杀场景无法返还。

## `FlyCommands.java` — 付费飞行

- 注册：`/fly`（`toggle`）与 `/fly warn`（`toggleWarn`）。
- `toggle`：
  - 已开启 → `FlyManager.disable` + 绿字“飞行模式已关闭，剩余资产 X 元”（余额读失败则省略）。
  - 未开启且创造/旁观 → 拒绝“创造/旁观模式自带飞行”。
  - 余额 < 每秒费用（`EconomyConfig.flyFeeCents()`）→ 拒绝开启并显示当前资金。
  - 否则 `FlyManager.enable` + `FlyManager.checkLowBalanceWarn`（余额 ≤ 1 分钟费用时立即提醒一次）
    + 绿字“飞行模式已开启，每秒扣费 X 元”。
- `toggleWarn`：切换 `FlyManager.toggleWarn`，提示“飞行余额不足提醒已开启/关闭”。
- 扣费本体不在命令里，在 `FlyManager.onServerTick`（命令只切换状态与提示）。

## `EconomyTargets.java` — /eco 目标解析（规则书 3.1 的纯净端兼容核心）

- `resolve(String input, CommandSourceStack source)`：
  - `@` 开头 → 原版 `EntitySelectorParser(reader, true).parse()`，解析后必须无剩余字符，
    再用 `selector.findPlayers(source)` 取在线玩家，按 UUID 去重（`LinkedHashMap` + `putIfAbsent`）。
  - 否则按玩家名 → `server.services().nameToIdCache().get(input)`，未知名抛
    `GameProfileArgument.ERROR_UNKNOWN_PLAYER`；无 UUID 时 `NameAndId.createOffline(name).id()`；
    同时查在线玩家对象（用于即时通知）。
- 返回 `List<ResolvedTarget>`；`ResolvedTarget(UUID uuid, String displayName, ServerPlayer onlinePlayer)`
  是 record，`onlinePlayer` 非空表示在线。
- `suggestions(source)` → 在线玩家名列表（/eco 补全）。
- 背景：禁止自定义 ArgumentType（会进同步注册表踢掉纯净端），故用原版 word 参数 + 手动解析。
