# `mois.buildmart.config` 包 — 配置与定价

配置文件目录：`config/economy/`（`FabricLoader.getConfigDir()` 下），首次运行自动生成。

## `EconomyConfig.java` — 主配置 config.json

- 键：
  - `itemPricesInLore`（boolean，默认 true）——物品价格以金色 lore 下发（纯净端可见）。
  - `partialRuleAdjust`（boolean，默认 false）——部分规则调整权限：true 时**普通玩家**可
    使用原版 /weather、/time 与本模组 /fixweather、/fixtime、/naturalmonsterspawn
    （权限等级 2 及以上管理员始终可用，判定按次读配置、热改即时生效）。
  - `fly.feePerSecond`（十进制元字符串，默认 `"500.00"`，即 50000 分/秒）——/fly 每秒扣费。
  - `funFishing`（boolean，默认 false）——趣味钓鱼开关（开启用 fishing.json 战利品，关闭用原版）。
  - `fly.digNoSlow`（boolean，默认 true）——飞行挖掘不减速开关：true=飞行中挖掘与地面一致
    （撤销 26.3 原版空中惩罚 `f / 5.0F`）；false=原版生存飞行挖掘速度。
    **服务端热改后通过 `FlyConfigSync` 网络包同步给全部在线玩家**（客户端本地预测一致）。
  - `shop.sellLog`（boolean，默认 false）——商店出售结算日志开关（每店每 60 秒一条，默认关闭防刷屏）。
  - `balop`（数据库管理前端段）——`host`（默认 "localhost"，改绑外部地址无鉴权请自担风险）、
    `port`（默认 8899，范围 1-65535）；由 `/balop start` 时读取。
    `domain`（默认 ""）/ `publicIp`（默认 ""）为 **/balop start 链接的展示主机**（仅影响
    返回给管理员的地址，实际监听仍是 host:port）：展示优先级 = `domain` → `publicIp` →
    本机探测的第一个非回环 IPv4 → "localhost"（`balopDisplayHost()`）。
    **同时是 /config 动态项**：`balop.host`（string）、`balop.port`（int）、
    `balop.domain`（string）、`balop.publicIp`（string），
    修改后需 `/balop stop` + `/balop start` 生效（domain/publicIp 无改动时无需重启，
    下次 start 即生效）。
  - `home` / `tpa` / `back`（传送配置段，见下）。
- 传送配置段字段（`home` 无 enabled；`tpa`/`back` 有 enabled；`tpa` 另有 timeoutSeconds）：
  `max`（home，默认 0 = 未开放）、`enabled`（tpa/back，默认 false）、`cooldownSeconds`（默认 0）、
  `fixedFee`（默认 false）、`fixedFeeAmount`（元字符串，默认 "500.00"）、
  `perDistanceFee`（默认 "1.00"）、`crossDimensionFee`（默认 "1000.00"）、
  `timeoutSeconds`（tpa，默认 60）。
- 配置记录：`TpFees(cooldownSeconds, fixedFee, fixedFeeAmountCents, perDistanceFeeCents,
  crossDimensionFeeCents)`、`HomeSettings(max, fees)`、`TpaSettings(enabled, fees, timeoutSeconds)`、
  `BackSettings(enabled, fees)`。
- API：`load(Path configDir)`、`itemPricesInLore()`、`flyFeeCents()`、`funFishing()`、
  `flyDigSpeedRestore()`、`shopSellLog()`、`balopHost()`、`balopPort()`、`balopDomain()`、
  `balopPublicIp()`、`balopDisplayHost()`、`partialRuleAdjust()`、`homeSettings()`、
  `tpaSettings()`、`backSettings()`；`/config` 热重载支持
  （`configKeys()` / `configType(key)` / `getValue(key)` / `apply(key, value)` / `save(configDir)`）。
- 行为：文件缺失时 `writeDefault` 写入含全部段的默认 JSON（`fly`/`shop` 为嵌套段）；
  已有文件缺段时该段用默认值；**旧版顶层 key（flyFeePerSecond/flyDigSpeedRestore/shopSellLog）
  自动兼容读取**（新段优先）；金额字段解析失败回退默认并记 warn 日志。

## `ItemValues.java` — 物品定价（items.json + 完整价值计算）

- `DEFAULT_CENTS = UNTRADEABLE`（**未配置物品默认不可交易**：后续新增物品默认 -1，不可购买/出售）；
  `UNTRADEABLE = -1`。
- 配置 `items.json`：物品 ID → 十进制元字符串；`load(Path)` 首次生成全量表（见 ItemInitialPrices），
  解析失败整体回退默认（不可交易）。
- **`load` 增量合并**：初始定价表（ItemInitialPrices）中存在而旧配置缺失的条目（26.3 数据驱动
  注册表新增物品）自动补入并写回；`MIGRATED_IDS`（鸡蛋变体、风弹）强制迁移为初始价。
- API：
  - `get(Item)` / `get(Identifier)` — 基础配置价（分）。
  - **`price(ItemStack)`** — 完整价值 =（基础价 + 附魔总价 + 容器内容物递归价）× 数量；
    不可交易（含内容物 -1）返回 `UNTRADEABLE`。
  - **`unitPrice(ItemStack)`** — 单件完整价值（不含数量），供 lore“单价”展示。
  - `isTradable(Item)` / `isTradable(ItemStack)`（后者递归容器内容物）。
  - `toJson()` — 当前配置序列化（历史遗留，当前无消费方）。
- 定价细则（`pricePerItem`，`MAX_CONTAINER_DEPTH = 8` 防递归过深）：
  - 附魔书基础价记 0，价值全部来自存储附魔（与铁砧合并价值守恒）；
  - **耐久折算**：有耐久物品基础价 ×（剩余耐久/最大耐久）；
  - **药水组件定价**（`POTION_CONTENTS`，`POTION_PRICES` 表 = 酿造配方链离线推导）：
    药水 = 表价；喷溅 = 表价 + 100（火药）；滞留 = 表价 + 2100（火药 + 龙息）；
    药水箭 = 箭基础价 + ⌈(滞留价)/8⌉（8 箭 + 1 滞留药水）；无标准药水（纯自定义效果）回退基础价；
    无法酿造的药水（luck/wind_charged/weaving/oozing/infested）按 4.00 分布价兜底；
  - **烟花组件定价**（`FIREWORKS`）：`flight` 等级 1/2/3 → 纸 + 火药 × 等级（1.20 / 2.20 / 3.20），
    三等级价格不同；
  - **不详之瓶组件定价**（`OMINOUS_BOTTLE_AMPLIFIER`）：10 元 × 等级（amplifier 0 = I 级）；
  - 附魔：1 级价（`EnchantmentValues.get`）× 2^(等级-1)，ENCHANTMENTS 与 STORED_ENCHANTMENTS 都计；
  - 容器内容物（CONTAINER / BUNDLE_CONTENTS）递归计入，按件计。
- 工具：`satAdd` / `satMul` / `satPow2Mul`（饱和运算）。

## `EnchantmentValues.java` — 附魔 1 级定价（enchantments.json）

- 配置覆盖表：附魔 ID → 1 级价（元字符串）；未配置按村民交易默认：
  `NORMAL_EMERALDS = 7` 绿宝石 + 1 书；宝藏附魔 `TREASURE_EMERALDS = 14`。
- `TREASURE` 集合（`Set.of`，9 项）：mending、frost_walker、binding_curse、vanishing_curse、
  swift_sneak、soul_speed、wind_burst、density、breach。
- **`get(Holder<Enchantment>)`** — 返回 1 级价（分）：
  配置覆盖优先；否则 = 绿宝石数 × `ItemValues.get(EMERALD)` + `ItemValues.get(BOOK)`
  （价格跟随物品定价配置）。
- 每升 1 级翻倍的倍增逻辑在 `ItemValues.addEnchantments`，不在本类。
- `load(Path)` 首次写入空 JSON `{}`；失败回退全默认。

## `ItemInitialPrices.java` — 原版物品初始定价表

- `public static final Map<String, String> INITIAL = build();`（约 1665 项，id → 元字符串）。
- `build()` 用 `new HashMap<>(2048)` 填充；`"-1.00"` 表示不可交易。
- 表内含 26.3 **数据驱动注册表**物品（羊毛/混凝土/铜系列变体/床/潜影盒/旗帜/染料/坐垫/挽具等，
  离线按配方推导：成品价 = 材料价之和 ÷ 产出数（向上取整），染色 = 基准价 + 染料价，
  烧炼 +0.10，氧化铜变体与基础同价，waxed = +1.00 蜜脾）。
- 仅在首次生成 `items.json` 时被 `ItemValues.writeDefaults` 使用；之后改价直接改配置文件
  （`load` 会对新增条目做增量合并，见上）。
- 定价原则（表头注释）：成品价 = 材料价之和（烧炼 +0.10 加工费；附魔金苹果按旧配方 8 金块+1 苹果）。
