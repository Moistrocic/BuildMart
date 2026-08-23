# `mois.economy.config` 包 — 配置与定价

配置文件目录：`config/economy/`（`FabricLoader.getConfigDir()` 下），首次运行自动生成。

## `EconomyConfig.java` — 主配置 config.json

- 键：
  - `itemPricesInLore`（boolean，默认 true）——物品价格以金色 lore 下发（纯净端可见）。
  - `flyFeePerSecond`（十进制元字符串，默认 `"500.00"`，即 50000 分/秒）——/fly 每秒扣费。
- API：`load(Path configDir)`、`itemPricesInLore()`、`flyFeeCents()`。
- 常量：`DEFAULT_ITEM_PRICES_IN_LORE = true`、`DEFAULT_FLY_FEE_CENTS = 50000L`。
- 行为：文件缺失时 `writeDefault` 写入默认 JSON；已有文件缺某键时该键用默认值；
  `flyFeePerSecond` 解析失败回退默认并记 error 日志。金额解析 `parseCents`：非负、最多两位小数。

## `ItemValues.java` — 物品定价（items.json + 完整价值计算）

- `DEFAULT_CENTS = 100`（未配置物品默认 1.00 元）；`UNTRADEABLE = -1`（不可购买/出售）。
- 配置 `items.json`：物品 ID → 十进制元字符串；`load(Path)` 首次生成全量表（见 ItemInitialPrices），
  解析失败整体回退默认。
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

- `public static final Map<String, String> INITIAL = build();`（约 1658 项，id → 元字符串）。
- `build()` 用 `new HashMap<>(2048)` 填充；`"-1.00"` 表示不可交易。
- 仅在首次生成 `items.json` 时被 `ItemValues.writeDefaults` 使用；之后改价直接改配置文件。
- 定价原则（表头注释）：成品价 = 材料价之和（烧炼 +0.10 加工费；附魔金苹果按旧配方 8 金块+1 苹果）。
