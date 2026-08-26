# Economy（经济模组）

基于 Fabric 的**服务端经济系统**模组（Minecraft `26.3-snapshot-9`，mojmap），提供资金账户、物品定价、箱子商店、付费飞行、传送系统、便捷购买、红包、趣味钓鱼、局内热重载配置与**数据库管理前端**等玩法功能。

## 功能一览

- **资金系统**：`/bal`、`/pay`、`/baltop`（含服务器总资产）、`/eco`（管理员注入/回收）；SQLite 存储（`world/economy.db`）。
- **资金流水**：**一切资金变化**（买卖、转账、管理操作、系统扣费、红包）自动记录到数据库，买卖含物品完整 NBT/组件数据（`item_data`）；管理面板可查询/筛选/删除（删除 BUY/SELL 交易自动回滚资金）。
- **物品定价**：原版物品初始定价表 + 26.3 数据驱动物品按配方推导；附魔/耐久/容器内容物完整计价；价格以**真实 lore** 展示（纯净客户端可见）。
- **箱子商店**：`/shop create|remove|setpayee` + `/price`、`/buy`；商店免疫爆炸/外部破坏；每格出售写入流水。
- **付费飞行**：`/fly`（每秒扣费、余额不足自动关闭、低余额提醒、下线保留模式）。
- **传送系统**：`/home`、`/sethome`、`/delhome`、`/listhome`、`/tpa`、`/tpahere`、`/tpaccept`、`/back`（费用/冷却/开关可配置）。
- **便捷购买**：`/bm` 用原版创造界面购买——购买方向严格比对原版创造物品栏（改造物品一律不可购买）；拿起暂存、放回中性、丢弃/关界面卖出；数字键 1-9 槽间交换正确识别（不误扣款/不复制物品）。
- **数据库管理前端**：`/balop start|stop`（仅管理员）启动本机 HTTP 管理面板（默认 `localhost:8899`，地址/端口可配置）——玩家资金增删改查（**允许负余额**，管理回滚场景）、交易流水查询（类型/渠道多选、金额区间、玩家排序）、删除与批量删除（同步回滚资金）；服务器关闭时面板自动关闭。
- **红包**：`/hongbao 总金额 数量 口令` 发红包，聊天说出口令即自动领取（金额随机波动、全服广播、未领取/被覆盖自动返还）。
- **趣味钓鱼**：`/config funFishing true` 开启后替换为自定义钓鱼战利品（按概率抽取、补全项、每件物品自带 lore 小故事）。
- **局内配置**：`/config 配置项 参数`（Tab 补全、热重载写回 `config.json`，无需重启），含 `/config balop.host`、`/config balop.port`。
- **其他**：`/suicide`、进服公告、管理员红名等。

## 构建

- 需要 **JDK 25**；运行 `gradlew build`，产物为 `build/libs/economy-5.1.jar`。
- 依赖：Fabric Loader `0.19.3+`、Fabric API `0.158.0+26.3`；SQLite JDBC 已内置打包进 jar。

## 配置与数据

- 配置目录 `config/economy/`：`config.json`（主配置，`/config` 热重载；含 `balop` 段：管理前端 host/port）、`items.json`（物品价）、`enchantments.json`（附魔价）、`fishing.json`（钓鱼战利品）。
- 数据：`world/economy.db`（SQLite：账户/交易流水/家/死亡点；旧库自动迁移）、`world/economy-shops.json`（商店）。
- **客户端无需安装本模组**即可进入服务器（所有功能均由服务端下发，纯净端兼容）。

## 开发说明

本项目由 **AI 辅助开发**——代码、文档与提交信息由 AI 协作生成，并经过人工审阅与决策。接续开发前请先阅读：

- **[AGENTS.md](AGENTS.md)** — 项目开发规则书（提交粒度、Fabric API 优先、纯净端兼容、启动验证规范、资金流水强制约定、代码地图导航），已被 AI 自动加载为工作区约定；
- **[docs/architecture/README.md](docs/architecture/README.md)** — 代码地图总览（按包组织，含全局约定与已知坑位速查）。

## License

本项目基于 CC0-1.0 发布（源自 Fabric 模组模板），可自由学习与使用。
