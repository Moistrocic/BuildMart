# Economy 模组架构说明（供新会话快速上手）

本目录是项目的**代码地图**：按包组织，逐文件说明职责、公开 API、行为细节与易踩的坑。
新会话接续开发时先读本 README，再按需查阅对应包文档，无需重读全部源码。

## 目录

- [README.md](README.md) — 项目总览、构建信息、全局约定（本文）
- [package-root.md](package-root.md) — 根包：`Economy`（入口）、`Money`（金额工具）、`PriceLore`（价格标签）
- [package-command.md](package-command.md) — `command` 包：全部指令（bal/pbal/pay/baltop/balhelp/eco/peco/balshop/fly）
- [package-config.md](package-config.md) — `config` 包：三个 JSON 配置 + 物品初始定价表
- [package-data.md](package-data.md) — `data` 包：SQLite 资金数据库
- [package-shop.md](package-shop.md) — `shop` 包：箱子商店（创建/出售/保护/持久化）
- [package-buymode.md](package-buymode.md) — `buymode` 包：/balshop buymode 便捷购买
- [package-fly.md](package-fly.md) — `fly` 包：/fly 付费飞行
- [package-mixin.md](package-mixin.md) — 全部 9 个 Mixin（注入点、原因、注意事项）
- [package-misc.md](package-misc.md) — util/AdminUtil、client 源集、资源文件（fabric.mod.json、economy.mixins.json）

## 项目总览

- **类型**：Fabric 模组（`net.fabricmc.fabric-loom`），服务端经济系统 + 少量客户端无关代码。
- **版本链**（`gradle.properties`）：Minecraft `26.3-snapshot-9`（mojmap 命名），Loader `0.19.3`，
  Fabric API `0.158.0+26.3`，Loom `1.17-SNAPSHOT`，Java 25（`it.options.release = 25`）。
  当前模组版本见 `version=`（最近一次为 `2.0`）。
- **依赖打包**：sqlite-jdbc 以 `include(...)` 打入 jar（排除其 slf4j-api，Minecraft 自带 slf4j）。
- **SourceSet**：`splitEnvironmentSourceSets()` —— `src/main` 两端共用（**所有服务端逻辑必须放这里**，
  保证单人游戏内置服务器也加载，见规则书 3.3）；`src/client` 仅客户端（目前只有空的 `EconomyClient`）。
- **入口**：`fabric.mod.json` → main `mois.economy.Economy`，client `mois.economy.client.EconomyClient`；
  mixin 配置 `economy.mixins.json`（9 个 mixin，`defaultRequire: 1`，包 `mois.economy.mixin`）。
- **启动标记**：`Economy Mod Loaded!`（Economy.onInitialize 末尾打印）。
  ⚠️ 规则书 RULES.md 4.2 里写的 `Hello Fabric world!` 已过时，以源码为准。

## 全局约定（改动代码前必读）

1. **金额一律用 `long` 整数“分”（cents）**，只在输入解析/输出展示时转十进制元字符串
   （`Money.parseCents*` / `Money.format`）。禁止浮点金额。
2. **纯净端兼容（规则书 3.1）**：客户端不装本模组也能进服。禁止自定义命令参数类型
   （会进同步注册表踢掉纯净端）；目标参数用 `StringArgumentType.word()` 手动解析
   （见 `EconomyTargets`）。价格提示用**真实 lore 组件**（纯净端可见）。
3. **价格标签（PriceLore）生命周期**：物品进入玩家背包（`Inventory.setItem`/`add` 混入）或打开容器时打标；
   丢出/死亡掉落立刻清除；关容器清除容器部分；下线清背包+容器+光标。标签显示“单价”（与数量无关），
   保证同种物品不同数量可正常堆叠。`tag()` 是幂等的（内容相同不重写组件）。
4. **26.3 物品同步协议**：客户端已知状态由 `RemoteSlot`（`remoteSlots`/`remoteCarried`）追踪；
   `setRemoteSlot`/`setRemoteSlotUnsafe` 只更新追踪结构，`broadcastChanges` 才按差异下发。
   因此**任何服务端主动改槽位后若想立即让客户端看到，必须显式补发**
   `ClientboundContainerSetSlotPacket(containerId, menu.incrementStateId(), slot, stack.copy())`。
5. **挖掘进度保护**：26.3 客户端 `MultiPlayerGameMode.sameDestroyTarget` 逐组件比较手持物品
   （`isSameItemSameComponents`），组件一变就重置挖掘进度。因此：`PriceLore.tag` 内容相同不得重写组件；
   `InventoryMixin.add` 不得重打**当前手持槽位**的标签（见 package-root 与 package-mixin）。
6. **会话级状态在内存**（`BuyModeManager`、`FlyManager`）：服务器重启即清空；飞行模式跨下线保留
   （不写存档，上线由 tick/onJoin 重新授予能力）。
7. **数据库**：SQLite 存 `world/economy.db`（WAL）；所有 `EconomyDb` 方法 `synchronized`，
   未 open 时 `requireOpen()` 抛运行时 `DatabaseException`；服务器资产 = 固定 UUID `new UUID(0,0)`。
8. **配置**：`config/economy/` 下 `config.json`（主配置）、`items.json`（物品价）、`enchantments.json`
   （附魔价），首次运行自动生成；商店数据 `world/economy-shops.json`。
9. **自检**：启动时 `PriceLore.selfCheck()`（仅开启时）与 `EconomyDb.runSelfTest()`（open 时）自动执行，
   改动相关逻辑后这两处自检若失败会直接报错，务必保证通过。
10. **指令注册中枢**：`EconomyCommands.register`（由 `Economy.onInitialize` 的
    `CommandRegistrationCallback` 调用），内部再委托 `BalshopCommands`、`FlyCommands`；
    新增指令要同步更新 `Economy.java` 的“命令注册完成”日志与 `/balhelp` 的 `HELP_LINES`。
11. **验证规范（规则书 4）**：启动验证用“后台启动 + 日志监视”，成功标记 = 模组初始化标记；
    run 开发服 `server.properties` 须 `online-mode=false`、`white-list=false`、`enforce-secure-profile=false`。

## 已知坑位速查

| 现象 | 原因 | 处理点 |
|---|---|---|
| 创造拿取/放回不显示价格 | 原版 `setRemoteSlot` 标记“客户端已知”后不再下发 | `ServerGamePacketListenerImplMixin` 显式补发槽位包 |
| 捡物品重置挖掘进度 | 重打标签改了手持物组件 → `sameDestroyTarget` 失败 | `PriceLore.tag` 幂等 + `InventoryMixin` 跳手持槽位 |
| 纯净端被踢 | 自定义命令参数类型进同步注册表 | 只用原版参数类型（规则书 3.1） |
| 管理员红名递归 | `createCommandSourceStack()` 会调 `getDisplayName()`（被 PlayerMixin 注入） | `AdminUtil` 用 `player.level().getServer()` |
| `/eco` 目标解析 | word 参数手动解析选择器 | `EconomyTargets.resolve` 复用 `EntitySelectorParser` |
| 商店物品误删 | `clearContent` 会删掉不可交易物品 | `ShopManager.sell` 逐格清除只清可交易 |
| 创造界面丢弃白嫖 | slotNum<0 的原版掉落包 | buymode 拦截取消该包 |

## 版本与提交历史要点

- 版本号在 `gradle.properties` 的 `version=`，历史上以独立 `chore:` 提交推进（1.0 → 1.1 → 1.2 → 1.3 → 2.0）。
- 功能提交惯例：`feat:`（新功能）、`fix:`（缺陷）、`chore:`（版本/杂项），中文描述（见 `git log`）。
- 规则书 `docs/spec/RULES.md` 约束提交粒度、Fabric API 优先、纯净端兼容与启动验证流程。
