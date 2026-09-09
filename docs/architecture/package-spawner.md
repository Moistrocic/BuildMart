# `mois.buildmart.spawner` 包 — 刷怪笼玩法（仅限带标签刷怪笼）

## 玩法闭环

**钓鱼（休闲获取）→ 刷怪笼（生产资产）→ 升级/微调（投入）→ 刷怪掉落（产出卖钱）**

**只有带特殊标签（本模组生成）的刷怪笼参与玩法；原版刷怪笼（地牢笼等）完全不受影响**：
不可绑定、不可升级、不可 set、不可挖取掉落。

## 数据模型（全部存 BlockEntity NBT，随世界存档）

| key | 含义 |
|---|---|
| `economy_spawner` | 标签（bool）：是否为模组刷怪笼——钓鱼/管理员 give 的物品经 `BLOCK_ENTITY_DATA` 组件携带，放置时由 `SpawnerBlockEntityMixin.loadAdditional` 读取 |
| `economy_level` | 升级等级：**0 = 初始态（Lv 0 直接使用原版刷怪笼生成机制，不在配置中、不可配置/不可微调）**；1..maxLevel 为配置等级（独立于生成参数——升级开关关闭时按 Lv 0 生成，但等级数据保留，再次开启自动恢复） |
| `economy_override_*` | `/spawner set` 的参数微调（-1 = 未覆盖，受当前等级允许范围约束，升级时钳制到新等级范围） |
| `economy_auto_convert` | 直接转化（bool）：不生成生物，原本生成的生物按击杀掉落表转化为掉落物 |
| `economy_looting` | 抢夺等级 0-3（解锁随等级：8-10 级 0-3、5-7 级 0-2、2-4 级 0-1、1 级及以下仅 0；仅作用于转化掉落） |
| `economy_auto_sell` | 自动出售（bool）：每 60 秒批量出售存储掉落物给收款人 |
| `economy_display` | 悬浮信息显示（bool，默认 true）：false = 自动出售开启也不显示悬浮字（/spawner set display） |
| `economy_sell_timer` | 自动出售周期倒计时（tick，-1 = 未初始化） |
| `economy_hopper` | 漏斗（bool）：白名单物品自动放入同 y 水平相邻箱子 |
| `economy_hopper_whitelist` | 漏斗白名单（物品注册表 ID 列表，匹配按 ID） |
| `economy_drops` | 转化掉落物存储：**键值对（物品完整数据 → 数量）**，随方块存档（挖掉时随刷怪笼物品保存防丢失） |
| `economy_converted` | 已转化实体数（存储掉落物来源计数；取出/出售时清零） |
| `economy_owner_*` | 创建人（放置时由 `BlockItemMixin` 记录） |
| `economy_payee_*` | 收款人（`/spawner set payee`，默认 = 创建人） |
| `economy_display_uuid` | 自动出售悬浮实体 UUID（重建用） |

## 获取

- **钓鱼**：`fishing.json` 战利品可配置产出刷怪笼（物品自带标签）。刷怪笼定价 -1（不可交易）防倒卖。
- **管理员**：`/spawner give`（仅管理员）获得带标签空刷怪笼——物品的 `BLOCK_ENTITY_DATA`
  使用**全新 `SpawnerBlockEntity` 的完整存档作底版**（原版默认生成参数 + 标签 + 全部
  override 键），与「放置→挖回」掉落的物品 NBT 完全一致，**可互相堆叠**。

## 绑定与改怪

放置后**手持刷怪蛋右键**绑定实体类型（消耗一个蛋；`UseBlockCallback`，仅标签笼）——
**刷怪笼只能通过刷怪蛋绑定实体类型**（无其他改怪途径）。

## 升级与参数（分级配置驱动）

- **`config/economy/spawner.json`**（`SpawnerConfig`）：每级定义各参数的**允许范围**（`[min, max]`）
  与升级费用（**升级到该级**的费用，元字符串；Lv 1 费用即 Lv 0 → Lv 1）。
  升级后参数默认取**范围下限**；`/spawner set <参数> <值>` 在范围内微调
  （minDelay/maxDelay/count/nearby/playerRange/spawnRange，maxDelay ≥ minDelay 校验）。
  **Lv 0 不在配置中**（初始态，原版生成机制）。文件缺失回退内置默认表（10 级）。
- **`/bm config spawner.upgrade`**（默认 true）：总开关——关闭时：
  - `/spawner upgrade` 拒绝；
  - 已升级效果**按 Lv 0（原版生成机制）生成**（`BaseSpawnerMixin.serverTick` 每 tick
    计算生效参数时 `effLevel = 开关 ? 等级 : 0`，`effLevel <= 0` 不写参数），
    **升级数据（等级/微调）保留**，再次开启自动恢复；
  - 原版笼不参与任何计算。
- 升级费用：`SpawnerConfig.level(level+1).upgradeFeeCents()`（升到目标级的费用）；
  扣款写流水 `SPAWNER_UPGRADE/SPAWNER`。

## 回收

玩家用镐破坏**带标签**刷怪笼（非创造）→ 掉落带完整数据物品（`saveCustomOnly` 序列化，
含标签/等级/微调/实体类型）→ 重新放置恢复。原版笼不掉落。
掉落物品经 `normalizeForStacking` 归一化，与 `/spawner give` 底版 NBT 完全一致：
- `Delay`（生成倒计时）是放置后的运行时变量（每次挖回都不同，挖回物品之间也不堆叠）→
  重置为底版初始值 20；
- 未绑定实体的默认 `SpawnData`（entity 为空 = 猪）在放置加载后被原版写出，底版没有该键
  → 移除；**绑定过实体（SpawnData.entity 含 id）则保留**，放置后绑定不丢失。

## 实现

- `SpawnerManager`：give/绑定/升级/set/info/take/targetedSpawner（准星射线 5 格）。
- `SpawnerStateAccess` + `SpawnerBlockEntityMixin`：NBT 状态读写
  （`loadAdditional`/`saveAdditional` RETURN 注入，ValueInput/ValueOutput；
  掉落物存储用 `ItemStack.OPTIONAL_CODEC.listOf()` 编解码）。
- `SpawnerAccess` + `BaseSpawnerMixin`：生成参数读取 + `serverTick` HEAD 每 tick
  按「等级/微调/开关」计算写入（仅标签笼，1 tick 内生效）；**直接转化**：`spawnDelay ≤ 0`
  时改为逐只（`spawnCount` 只/周期）计算击杀掉落存入方块并重置倒计时；**自动出售**：
  每 60 秒（`economy_sell_timer`）批量出售存储掉落物 + 刷新金色悬浮。
- `SpawnerBlockMixin`（目标 `Block`）：`playerDestroy` HEAD——仅标签笼掉落
  （mixin 不搜索父类方法，须注入声明处；运行时 instanceof SpawnerBlock；掉落归一化见「回收」；
  挖掉时移除悬浮实体）。
- `BlockItemMixin`（目标 `BlockItem`）：`updateCustomBlockEntityTag` 内
  `canUseGameMasterBlocks` 调用点 @Redirect——带 `economy_spawner` 标签的物品放行
  `onlyOpCanSetNbt` 检查，非 OP 玩家也能放置（见模式矩阵）；首次放置记录创建人。
- `SpawnerCommands`：`/spawner info|upgrade|set|take|give`（set 的 value 为字符串，
  枚举参数 true/false、looting 0-3、payee 在线玩家名全部提供 tab 补全）。

## 直接转化 / 抢夺 / 自动出售

- **直接转化**（`/spawner set autoconvert true`）：不生成生物，每个生成周期按原版
  `spawnCount` 逐只独立计算击杀掉落（loot table + 假实体击杀上下文），掉落物按
  **物品完整数据合并存储**（键值对：物品 → 数量）；转化强制提供
  `LAST_DAMAGE_PLAYER`（优先创建人在线，否则笼子附近最近玩家）——`killed_by_player`
  条件只检查该参数是否存在，**凋灵骷髅头颅等特殊掉落物正常产出**。
- **抢夺**（`/spawner set looting 0-3`）：解锁随等级（8-10 级 0-3、5-7 级 0-2、2-4 级
  0-1、1 级及以下仅 0）；作用于转化掉落——26.3 的 looting 由附魔效果组件
  （`enchanted_count_increase` 等）读取 `ATTACKING_ENTITY` 的附魔等级，转化时创建
  同类假实体作攻击者并挂 Looting N 的剑模拟。
- **自动出售**（`/spawner set autosell true`）：每 60 秒批量出售**全部**存储掉落物
  （含开启前积累的）给收款人（默认创建人；`/spawner set payee` 可改，允许离线玩家但
  必须已注册资金账户）；流水 `SELL/SPAWNER`；结算日志受 `/bm config shop.sellLog` 控制；
  开启时显示金色悬浮（与 shop 一致）：创建人/收款人/出售倒计时；`/spawner set display true|false` 可独立隐藏悬浮（默认 true，随 NBT 存档）。
- **取出**（`/spawner take`）：存储掉落物发到背包（按单堆上限拆分），放不下的掉落脚下。
- **漏斗**（`/spawner set hopper true`）：转化掉落物中白名单物品直接放入**同 y 水平相邻**
  箱子（优先堆叠已有同种、再空槽，支持多个相邻箱子，放不下回退存储）；白名单
  `/spawner hopper add|remove|list 物品`（按注册表 ID 匹配）；开启/添加/移除白名单时
  各触发**一次**迁移（把已积累的白名单物品搬进箱子，无轮询开销）。
- info 与 `/spawner hopper list` 显示漏斗白名单列表（金色标题 + 逐行中文名（英文 ID），
  不显示数量，与存储掉落物列表样式一致，共用 `whitelistDisplay`）。

## 生存/创造模式行为矩阵（模式敏感点清单）

| 功能 | 生存模式 | 创造模式（instabuild） | 说明 |
|---|---|---|---|
| 放置带标签刷怪笼 | 恢复标签/等级/绑定/微调 | 同左 | `BlockItemMixin` 放行原版 `onlyOpCanSetNbt` 检查（`MOB_SPAWNER` 属 OP-only 类型，非 OP 放置时原版会跳过 BLOCK_ENTITY_DATA 加载）——仅对带 `economy_spawner` 标签的物品放行，原版笼不受影响 |
| 刷怪蛋绑定/换绑 | **消耗一个蛋** | **不消耗蛋**（原版规则：创造使用物品不消耗） | `bindWithEgg` 检查 `abilities.instabuild` |
| `/spawner upgrade` | 扣款升级 | 同左（仍扣钱） | 与模式无关；受 `spawner.upgrade` 开关控制 |
| `/spawner set` | 微调参数 | 同左 | 纯逻辑，无模式差异 |
| `/spawner give` | 获得带标签笼 | 同左 | 管理员指令 |
| 挖掘掉落（镐挖） | **掉落带数据物品** | **不掉落**（与原版一致） | `playerDestroy` 检查 `isCreative`；爆炸等其他破坏路径也不掉落 |
| 创造中键拾取（getCloneItemStack） | — | 拿到**无标签**空笼 | 原版行为：中键不携带 BlockEntity 数据——放置后为普通空笼（不受玩法影响），已升级数据不随中键复制 |
| 刷怪笼生成机制 | 激活需附近玩家（含创造） | 同左 | 参数计算与玩家模式无关 |
| bm 便捷购买（instabuild） | — | — | `ServerPlayerGameModeMixin` 已禁止 buymode 期间破坏方块（含刷怪笼） |

**设计约定**：除上表外，刷怪笼玩法的所有逻辑（升级/参数/标签/持久化）与玩家游戏模式**完全无关**；
模式差异只存在于「物品消耗（绑定蛋）」与「破坏掉落（创造不掉）」两处——均遵循原版规则。

## 指令

**归属规则（与箱子商店 `/shop` 一致）**：`info`、`hopper list` 为只读命令，所有人可用；
其余写操作（`upgrade`/`set`/`take`/`hopper add|remove`）以及**手持刷怪蛋右键绑定类型**
（`UseBlockCallback` → `SpawnerManager.bindWithEgg`）**仅创建人或管理员**可用，否则提示
`只能操作自己的刷怪笼（创建人：X）`。无创建人记录的笼子（早期版本、直接放置、`/spawner give`
发放后未由玩家放置）视为公开。校验单点收口在 `SpawnerManager.checkOwner`，命令与右键两条路径共用。

| 指令 | 说明 |
|---|---|
| `/spawner info` | 查看信息（Lv/绑定生物/所有者/参数区间/直接转化/抢夺/自动出售/存储掉落物列表）——仅标签笼，只读公开；格式与颜色规范：前 3 行白色、区间行蓝色、可设置属性按状态（开蓝/关灰）、存储标题金色、Lv 当前<上限红色=绿色 |
| `/spawner upgrade` | 升级（纯金钱；Lv 0 → 1 起）——仅标签笼，受 `spawner.upgrade` 开关控制，限创建人/管理员 |
| `/spawner set <参数> <值>` | 生成参数（minDelay/maxDelay/count/nearby/playerRange/spawnRange，受等级范围约束；Lv 0 不可微调）与配置：`autoconvert true|false`、`looting 0-3`（等级解锁）、`autosell true|false`、`display true|false`（悬浮信息开关）、`payee 玩家名`（离线需已注册账户）——枚举值全部可 tab 补全；限创建人/管理员 |
| `/spawner take` | 取出存储的转化掉落物（放不下的掉落脚下）；限创建人/管理员 |
| `/spawner hopper add\|remove\|list` | 漏斗白名单（add/remove 限创建人/管理员，list 只读公开） |
| `/spawner give` | 管理员获得带标签刷怪笼 |
