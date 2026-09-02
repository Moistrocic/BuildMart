# 项目开发规则书

本文件是本仓库的开发规则书，适用于该项目后续的所有开发、修改与代码审阅工作。以下规则必须始终遵守。

## 1. 及时提交 commit，保证代码可回滚

- 每完成一个小的、可独立运行的改动后，必须及时提交 git commit，保持较小的提交粒度，便于定位问题和回滚。
- 提交信息应清楚描述本次改动的内容与原因。
- 默认不主动压缩（squash）提交。只有用户明确要求“压缩提交”时，才进行压缩。
- 收到压缩提交的要求后，按照用户给出的范围和需求，把与同一功能相关的多个 commit 合并成一个提交：
  - 以“功能”作为合并边界，同一功能的连续提交可以压缩为一个。
  - 与不同功能相关、或与本次压缩需求无关的提交不得擅自合并。
  - 用户没有明确分组时，先向用户确认压缩范围，再执行。
- 压缩前确认没有未提交的改动，压缩后确认历史正确、工作区干净。

## 2. Fabric API 优先

- 在实现任何功能（网络通信、注册、命令、事件、数据存储、渲染等）之前，先检查 Fabric Loader、Fabric API 以及 Minecraft 原版是否已经提供了相关 API。
- 如果已有合适的 API，必须优先使用，不要重复造轮子或自行实现等价功能。
- 只有在确认没有合适的现成 API 时，才允许自行创建 API 或相关代码；自行实现时应保持最小化、可复用，并在代码或提交信息中说明自建原因。
- 使用 API 前应确认其适用于当前项目的 Minecraft / Fabric Loader / Fabric API 版本。

## 3. 客户端兼容与单人游戏规则

### 3.1 未安装本模组的客户端必须能进入安装本模组的服务端

- 服务端功能不得强制要求客户端安装本模组：未安装本模组的原版（或其他）客户端必须能够正常加入、游玩安装了本模组的服务端。
- 禁止注册自定义命令参数类型（Fabric `ArgumentTypeRegistry`）：自定义参数类型会进入同步注册表，未安装 Fabric/本模组的纯净客户端会因注册表同步失败被服务端踢出（`This server requires Fabric API installed on your client!`）。需要特殊解析时使用原版参数类型（如 `StringArgumentType.word()/greedyString()`），在命令执行阶段手动解析，选择器语法复用原版 `EntitySelectorParser`。
- 网络协议、注册表、命令或服务端逻辑不能导致未安装本模组的客户端被踢出或无法交互。
- 自定义数据包必须设计为“可选”：发送前应判断对方能力；未安装本模组的客户端必须能够忽略或安全处理未知数据包。
- 客户端专用逻辑应与通用逻辑正确分离，且不能成为进入服务端的必要条件。

### 3.2 允许并支持客户端使用本模组

- “客户端无需安装本模组”不代表禁止客户端使用本模组；允许开发客户端侧功能（HUD、渲染、快捷键、客户端辅助等）。
- 客户端功能应是增强性的、可选的，缺少它们时服务端与核心玩法仍能正常工作。
- 需要根据逻辑端（如 `world.isClient`）而不是仅根据物理端区分客户端与服务端行为，保证单人游戏中的集成服务器逻辑正确。

### 3.3 单人游戏的内置服务器必须加载本模组且功能有效

- 单人游戏中，客户端启动的内置服务器（Integrated Server）必须会加载本模组，并且模组的服务端功能在单人游戏中同样有效。
- 通用逻辑与服务端逻辑必须放在 `src/main`（两端共用的 source set）中，保证独立服务端与单人游戏内置服务器都会加载；不要把服务端逻辑错误地放进仅客户端加载的 `src/client`。
- 修改后必须同时在“独立服务端 + 客户端”和“单人游戏”两种场景下验证模组加载与功能是否正常。

## 4. 启动验证规范

验证客户端/服务端能否正常启动时，禁止长时间阻塞等待启动输出；必须使用“后台启动 + 日志监视”方式，以日志标记为准判定成功或失败。

### 4.1 启动方式

- 用后台任务启动 `gradlew runClient`（或 `runServer`），立即返回，不阻塞当前会话。
- 同时启动一个后台日志监视器；监视器必须先于游戏记录日志基线时间戳（即监视器先启动，或至少在游戏写入日志前记录基线）。
- 监视器启动时同时记录 `run/crash-reports/` 目录的已有文件清单（或该目录的 LastWriteTime），用于后续识别“新增崩溃报告”。

### 4.2 判定规则

- 监视器启动前记录 `run/logs/latest.log` 的 LastWriteTime 作为基线；只有日志 mtime 超过基线后才检查其内容，防止把上一次运行的旧日志误判为本次成功。
- 每 5 秒轮询一次。
- 成功判定：日志中出现本模组的初始化标记（当前为 `BuildMart Loaded!`；后续模组应维护自己的唯一初始化标记）。
- 失败判定：出现致命标记（如 `Mixin apply for mod buildmart failed`、`Failed to start the minecraft server`）；普通 `ERROR` 行可能来自良性事件（见 4.4），不能单独作为失败依据。
- 崩溃判定（最高优先级，一经命中立即停止轮询并汇报，禁止继续等到超时）：
  - 日志出现崩溃签名：`Game crashed!`、`Crash report saved`、`StackOverflowError`、`Unexpected error`（配合堆栈）等；
  - 或 `run/crash-reports/` 目录出现基线之后的新增崩溃报告文件（对照启动前记录的文件清单，旧报告不算）。
  - 命中后读取崩溃报告文件与日志尾部定位原因，向用户汇报后再结束监视。
- 超时判定：超过 5 分钟仍未出现任何标记，视为启动失败，需检查启动任务输出与日志排查原因。

### 4.3 确认与清理

- 判定成功后，通过 `Get-Process -Name java,javaw` 检查窗口标题（`Minecraft*`）确认窗口已创建。
- 验证完成后立即终止启动任务（job_kill），并确认无残留 java 进程。

### 4.4 正常现象，不算启动失败

- 开发环境（离线账号）下 Realms 认证失败（401、`SignedJWT: FabricMC`）及其堆栈属正常现象。
- 独立服务端首次运行缺少 `server.properties` 会记一条 `Failed to load properties from file: server.properties` 的 ERROR，随后自动生成文件并正常继续启动，不算失败。
- Loom 的 `Class path entries reference missing files: build\resources\client` 警告在客户端资源为空时属无害告警。
- 成功判定以模组初始化标记为准，而不是“日志停止输出”或“窗口停留在主菜单”。

### 4.5 开发环境服务端配置

- 独立服务端的 `server.properties` 必须关闭正版验证：`online-mode=false`。
- 同时必须关闭白名单与安全档案强制校验：`white-list=false`、`enforce-secure-profile=false`（26.3 的 `white-list` 默认值为 true；离线开发端没有正版会话与安全档案，任一项开启都会导致开发端无法进入服务器）。
- 验证用临时服务器（如 `run-verify` 独立运行目录）同样需要先写入上述三项，再启动服务器与客户端连接验证。
- 管理员权限验证需要预先准备 `world/ops.json`（26.3 格式：`{"uuid","name","level","bypassesPlayerLimit"}`，`level` 为整数 3=ADMINS）。

## 6. 资金变化必须记录流水（后续开发强制约定）

- **任何改变玩家资金的行为都必须写入 `economy_transactions` 流水**（用户明确要求，永久有效）：
  - 买卖（BUY/SELL）用 `EconomyDb.recordTransaction`（带物品完整组件数据 item_data）；
  - 转账/管理操作/系统扣费/红包等用 `EconomyDb.recordMoneyLog`（描述 + 金额变化量，
    入账为正、扣款为负）；
  - type/channel 枚举见 `EconomyDb` 常量与 `docs/architecture/package-data.md`；
    新增资金行为时先检查是否已有对应 type/channel，没有则补充常量并同步文档。
- 记录失败必须静默（try-catch `DatabaseException`），**绝不能影响资金操作主流程**。
- 删除记录的回滚资金仅适用于 BUY/SELL（见 `deleteTransactionsWithRollback`）。

## 7. 分支隔离（用户明确要求，永久有效）

- **在一个分支开发时，不要动其他分支**：
  - 不在当前分支上自动 cherry-pick / merge / 同步其他分支的提交；
  - 不擅自切换分支提交本分支的改动；
  - 需要把改动同步到其他分支（如 main ↔ 26.2）时，**先向用户确认**，得到指示后再操作；
  - 切换分支前确认当前分支工作区干净（未提交的改动会带过去或需要 stash，先询问用户）。

## 8. 代码地图（新会话接续开发前必读）

- 本仓库的架构文档位于 `docs/architecture/`：**`README.md` 是代码地图总览**——按包组织，包含构建信息、全局约定（金额单位、纯净端兼容、价格标签生命周期、26.3 同步协议等）与“已知坑位速查”表。新会话接续开发前**必须先读 `docs/architecture/README.md`**，再按需查阅对应 `package-*.md`。
- 包文档清单：
  - `package-root.md` — 根包：Economy 入口、Money 金额工具、PriceLore 价格标签
  - `package-command.md` — 全部指令（bal/pay/baltop/eco/shop/price/buy/bm/fly/传送/suicide/hongbao/config）
  - `package-config.md` — 配置与定价（config.json / items.json / enchantments.json + 初始定价表）
  - `package-data.md` — SQLite 资金数据库
  - `package-shop.md` — 箱子商店
  - `package-buymode.md` — 便捷购买（暂存模型判定）
  - `package-fly.md` — 付费飞行
  - `package-fishing.md` — 趣味钓鱼（自定义战利品 + 概率/补全项 + lore 故事）
  - `package-teleport.md` — 传送系统
  - `package-mixin.md` — 全部 12 个 Mixin（注入点、原因、注意事项）
  - `package-misc.md` — util/AdminUtil、client 源集、资源文件
- 修改代码或文档后，相关 `package-*.md` 必须同步更新，保持与代码一致。