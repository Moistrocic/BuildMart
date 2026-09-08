# `mois.buildmart.command` 包 — 全部指令

注册中枢：`EconomyCommands.register(dispatcher, buildContext)`（由 `BuildMart.onInitialize` 调用），
内部再委托 `BalshopCommands.register`、`FlyCommands.register`、`TeleportCommands.register`、
`HongbaoCommands.register`、`RuleCommands.register`（规则调整指令，见下；传送指令见 package-teleport.md；红包指令见下）。

## `EconomyCommands.java` — 资金指令 + /bmhelp

- `PAGE_SIZE = 10`；`HELP_LINES`（String[]，30 行）包含全部 /bal*、/shop*、/fly、传送、/suicide、/hongbao、/spawner、/balop、规则调整帮助行。
- 注册的指令与执行方法：
  | 指令 | 方法 | 说明 |
  |---|---|---|
  | `/bal [玩家]` | `showBalance` | 查资金（目标用原版 `GameProfileArgument`，支持选择器） |
  | `/pay 目标 金额` | `pay` | 转账；按 UUID 去重、跳过自己；离线目标直接入账 |
  | `/baltop [页码]` | `showTop` | 排行榜（`EconomyDb.topAccounts`），首页顶部显示服务器总资产（`totalPlayerAssets`） |
  | `/bmhelp [页码]` | `showHelp` | 帮助分页 |
  | `/announcement 内容` / `clear` | `setAnnouncement`/`clearAnnouncement` | 进服红色公告（管理员 `LEVEL_ADMINS`） |
  | `/eco add\|remove\|set 目标 金额` | `ecoAdd`/`ecoRemove`/`ecoSet` | 管理员资金注入/回收；目标 = word 参数手动解析；每次操作写资金流水（ADMIN_ADD/ADMIN_SUB/ADMIN_SET，channel=ECO，`logQuietly` 静默） |
  | `/balop start\|stop` | `balopStart`/`balopStop` | 启动/关闭数据库管理前端（管理员；监听地址/端口见 config 的 balop 段，默认 localhost:8899；**start 返回的链接展示主机 = balop.domain（非空纯文本替换）否则 balop.host**；详见 package-balop.md） |
- 工具方法（同类指令复用）：`requirePlayer`（PLAYER_ONLY）、`parseAmount`、
  `resolveUuid`（离线 UUID 回退）、`readBalance`、`countOrThrow`、`topOrThrow`、
  `totalAssetsOrThrow`、`transferOrThrow`、`transferManyOrThrow`、`text(content, color)`。
- 异常均用 `SimpleCommandExceptionType`（PLAYER_ONLY / DB_ERROR / PAYER_INSUFFICIENT /
  PAY_NO_TARGET / AMOUNT_TOO_LARGE）。
- 转账通知：`notifyOnlineTargets` 给在线目标发绿字消息。

## `BalshopCommands.java` — 箱子商店与购买

- 常量 `MAX_BUY_COUNT = 17280`。
- `/shop create|remove` — 对准箱子（`targetedChest` 用 `player.pick(5.0, 1.0F, false)` 射线），
  仅主人/管理员可 remove（`requireOwnedShop` + `isAdmin`）。
- `/shop setpayee 玩家` — 设置收款人（`NameAndId.createOffline` 回退；服务器账户收款已移除）。
- `/shop display true|false` — 商店悬浮信息显示开关（仅主人/管理员；状态持久化于
  economy-shops.json 的 display 字段，默认 true；详见 package-shop.md）。
- `/price 物品` — 查基础价（`ItemValues.get`），提示完整价值 = 基础价 + 附魔 + 容器内容物。
- `/buy 物品 数量` — `ItemArgument.item` 解析 → `ItemValues.price` 计价 →
  `EconomyDb.deduct` 只扣玩家资金（不入服务器资产）→ `giveOrDrop` 发放：
  **绕过原版 `ItemInput.createItemStack` 的 overstacked 校验**（26.2/26.3 对
  count > 单堆上限抛「只可以堆叠到 N」），手动按同参数构造堆后**按单堆上限分块
  放入背包（可跨槽堆叠），背包放不下的溢出部分掉落到玩家脚下**。
- `/bm` — 切换 `BuyModeManager`（见 package-buymode.md）。
- `/fastbuy` — 快速投影购买开关（状态在 `EconomyConfig.fastbuy`，config.json 持久化）。
  关闭时不处理任何请求；开启时服务端收到客户端 `FastbuyRequestPayload`（C2S 包，
  litematica 拾取失败时由可选客户端模块发送）→ 校验可交易 → 自动购买一组
  （该物品最大堆叠，复用 `BalshopCommands.purchase`）。详见 package-misc.md 客户端说明。
- `/buypack 物品 盒数` — 购买整盒物品：每盒 = 1 个潜影盒（`Items.SHULKER_BOX` +
  `DataComponents.CONTAINER` 27 格）+ 27 × 堆叠上限 个该物品（带参数组件）；
  每盒价值 = `ItemValues.price(空盒)` + 27 × `ItemValues.price(满堆)`；
  盒上限 `MAX_BUY_PACK_BOXES = 1024`；发放复用 `giveOrDrop`（逐盒放入，溢出掉落脚下）。
- 购买核心：`BalshopCommands.purchase(player, stack)`（校验/扣款/流水/发放）——
  `/buy` 与 fastbuy 共用。
- 异常：NOT_CHEST / NOT_SHOP / ALREADY_SHOP / NOT_OWNER / PLAYER_ONLY / DB_ERROR / PAYER_INSUFFICIENT。
- 注册：`/shop` 为商店主命令（原 /balshop 已移除）；`/price`、`/buy`、`/buypack`、`/bm` 为顶层简化入口，
  `/shop getprice|buy|buymode` 仍保留作为子命令。

## `HongbaoCommands.java` — 红包

- 注册：`/hongbao 总金额 数量 口令`（发红包，管理员不限，任何玩家可发）。
- 发红包：`Money.parseCents` 解析金额 → 校验 总金额 ≥ 数量（每个至少 0.01 元）→
  `EconomyDb.deduct` 扣发红包者余额 → 存入内存表（口令 → 红包，同口令覆盖）→ 全员广播；
  资金流水 REDPACKET_SEND（channel=REDPACKET，`logQuietly` 静默）。
- **领取走聊天**：`ServerGamePacketListenerImplMixin.buildmart$hongbaoChat` 拦截
  `handleChat`——发言与某口令完全一致（trim 精确匹配）即自动领取，**口令发言照常进入公屏**；
  领取金额 = 随机 1 ~ (总金额 / 数量) × 2 分（最后一个红包领剩余全部，保证总额守恒；
  随机时给后续红包至少留 1 分）→ `EconomyDb.credit` 入账 → 全员广播
  「领到 X 元，红包剩余 N 个」。无 `/hongbao 口令` 领取指令；资金流水 REDPACKET_CLAIM。
- 内存存储：服务器停机时（`SERVER_STOPPING`）所有未领取红包作废并**返还剩余金额**给发红包人
  （`refundAll`）；同口令新红包覆盖旧红包时同样返还旧红包剩余。崩溃强杀场景无法返还；
  返还写资金流水 REDPACKET_REFUND。

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

## `ConfigCommands.java` — 局内配置修改（/config）

- 注册：`/config 配置项 [参数]`（管理员 `LEVEL_ADMINS`，不在 /bmhelp 帮助列表）。
- 配置项 key 按 Tab 自动补全（`EconomyConfig.configKeys()`，30 项：itemPricesInLore / partialRuleAdjust /
  flyFeePerSecond / home.* / tpa.* / back.*）；布尔项参数值补全 true/false，数值项补全当前值。
- 执行：`EconomyConfig.apply(key, value)` 热重载内存配置（所有消费方按次读取 getter，即时生效；
  itemPricesInLore 会同步 `PriceLore.enabled`）→ `EconomyConfig.save(configDir)` 写回 config.json
  持久化，无需重启。`/config key`（无参数）查询当前值。

## `RuleCommands.java` — 部分规则调整指令（/config partialRuleAdjust）

- 权限：`requires(RuleCommands::allowed)`——`EconomyConfig.partialRuleAdjust() ||`
  权限等级 2（`LEVEL_GAMEMASTERS`）管理员。判定按次读取配置，/config 热改**即时生效**
  （含收回）；规则改动全部走 `GameRules.set`（与服务端 `onGameRuleChanged` 联动、随世界
  存档持久化），与 /gamerule 同源。
- **客户端可见性**：客户端把每个指令节点标记 RESTRICTED（服务端在进服下发指令树时用
  无权限源测试 requirement），非管理员隐藏受限指令并显示“未知或不完整的命令”。
  因此 partialRuleAdjust=true 时这些指令对非管理员同样可见/可执行（false 时恢复隐藏）；
  **/config 切换后 `ConfigCommands` 会向全部在线玩家即时重发指令树**，无需重进服。
- **原版 /weather、/time 权限放宽**：原版指令（26.3 语义与参数原样保留，含新的时间
  时钟/时间标记体系）注册后，把根节点 requirement 反射写为上述动态判定（Brigadier
  requirement 为 final、无公开替换 API；失败仅记 warn 并保持原版权限）——
  partialRuleAdjust=true 时普通玩家可直接使用，关闭时与原本一致（等级 2 才可用）。
- `/fixweather` — 固定/恢复天气：advance_weather 规则取反（false = 天气不再自然变化、
  睡醒不重置天气；再次输入恢复）。
- `/fixtime` — 固定/恢复时间：advance_time 规则取反（false = 世界时钟全部停止、
  昼夜不再流动；再次输入恢复）。
- `/naturalmonsterspawn [true|false]` — 控制自然怪物生成（**不含刷怪笼**）：spawn_monsters
  规则；不填参数 = 查询当前状态；参数值补全 true/false。
- 新指令已同步 /bmhelp 帮助行（3 行）与 `BuildMart.java` 命令注册日志。

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
