# `mois.economy.fishing` 包 — 趣味钓鱼

## `FishingInitialLoot.java` — 初始战利品配置（初始化类，类似 ItemInitialPrices）

- `INITIAL_JSON`：首次启动写入 `config/economy/fishing.json`（不存在时才生成；之后改配置直接改文件）。
- **用 Map/List + Gson 构建**（非硬编码 JSON 字符串）：`item(id)` / `item(id, count, components)`
  （components 任意嵌套 Map/List，键为组件 ID，如 `minecraft:lore`、`minecraft:ominous_bottle_amplifier`）、
  `lore1(text, color[, bold])`（单行故事，`italic: false`）、`lootEntry(item, chance)`。
- ⚠️ 静态初始化顺序：`GSON` 字段必须声明在 `INITIAL_JSON` 之前（`buildInitialJson()` 依赖它）。
- 默认战利品（9 组 32 项 + 空气补全项）：鱼类 40%（4 种 lore aqua）、矿物 40%（11 种细分 lore gold）、
  稀有 10%（五级不详之瓶 + echo_shard 等，light_purple）、高级 5%（red）、传说 4%（dark_purple）、
  下界合金斧 0.5%（dark_red）、金色传说 0.3%（gold 粗体）、龙蛋 0.19%、刷怪笼 0.01%。

## `FishingManager.java` — 配置加载与概率抽取

- 配置格式：JSON 数组，每项 `{"item": {ItemStack.CODEC 格式}, "chance": 概率}`；
  普通项概率 (0,1]，**总和 ≤ 1**（`> 1 + 1e-9` 拒绝，含浮点容差）；可含至多一个补全项
  （`"chance": "remaining"`）自动补足剩余概率；无补全项且总和 < 1 时剩余概率 = 钓不到东西。
- ⚠️ `ItemStack.CODEC` 拒绝解析 `minecraft:air`（"Item must not be minecraft:air"）——
  空气补全项特殊处理为 `ItemStack.EMPTY`（不走 CODEC）。
- `load(Path configDir, RegistryAccess)` — SERVER_STARTED 时由 `Economy` 调用
  （需已就绪的 RegistryAccess 解析物品组件）；解析/校验失败 → 清空并回退原版。
- `roll(RandomSource)` — 加权随机；返回 null = 配置未加载（回退原版），
  `ItemStack.EMPTY` = 未命中（正常收竿无掉落）。
- 开关：`EconomyConfig.funFishing()`（`/config funFishing` 热重载）。

## `FishingHookMixin`（目标 `FishingHook`）— 战利品替换

- 注入 `retrieve(ItemStack)` @HEAD（cancellable）：开关开启 + 服务端 + 战利品路径
  （`hookedIn == null && nibble > 0`）时，用 `FishingManager.roll` 的结果替换原版战利品表。
- 命中战利品：触发 `FISHING_ROD_HOOKED` 成就、生成 `ItemEntity`（鱼钩位置、原版双重 sqrt 速度公式）、
  经验球（1-6）、鱼标签物品计 `FISH_CAUGHT` 统计。
- ⚠️ **cancel 后必须补做原版收尾**：`hook.discard()`（否则鱼钩不销毁可重复收竿刷战利品）、
  返回值 `onGround() ? 2 : 1`（原版战利品路径语义）。未命中战利品（EMPTY）同样正常收竿。
- 关闭开关 / 钩住实体 / 未上钩 / 配置未加载 → 放行原版处理。
