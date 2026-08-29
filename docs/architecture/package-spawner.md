# `mois.economy.spawner` 包 — 刷怪笼玩法（仅限带标签刷怪笼）

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

## 获取

- **钓鱼**：`fishing.json` 战利品可配置产出刷怪笼（物品自带标签）。刷怪笼定价 -1（不可交易）防倒卖。
- **管理员**：`/spawner give`（仅管理员）获得带标签空刷怪笼。

## 绑定与改怪

放置后**手持刷怪蛋右键**绑定实体类型（消耗一个蛋；`UseBlockCallback`，仅标签笼）——
**刷怪笼只能通过刷怪蛋绑定实体类型**（无其他改怪途径）。

## 升级与参数（分级配置驱动）

- **`config/economy/spawner.json`**（`SpawnerConfig`）：每级定义各参数的**允许范围**（`[min, max]`）
  与升级费用（**升级到该级**的费用，元字符串；Lv 1 费用即 Lv 0 → Lv 1）。
  升级后参数默认取**范围下限**；`/spawner set <参数> <值>` 在范围内微调
  （minDelay/maxDelay/count/nearby/playerRange/spawnRange，maxDelay ≥ minDelay 校验）。
  **Lv 0 不在配置中**（初始态，原版生成机制）。文件缺失回退内置默认表（10 级）。
- **`/config spawner.upgrade`**（默认 true）：总开关——关闭时：
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

## 实现

- `SpawnerManager`：give/绑定/升级/set/info/targetedSpawner（准星射线 5 格）。
- `SpawnerStateAccess` + `SpawnerBlockEntityMixin`：NBT 状态读写
  （`loadAdditional`/`saveAdditional` RETURN 注入，ValueInput/ValueOutput）。
- `SpawnerAccess` + `BaseSpawnerMixin`：生成参数读取 + `serverTick` HEAD 每 tick
  按「等级/微调/开关」计算写入（仅标签笼，1 tick 内生效）。
- `SpawnerBlockMixin`（目标 `Block`）：`playerDestroy` HEAD——仅标签笼掉落
  （mixin 不搜索父类方法，须注入声明处；运行时 instanceof SpawnerBlock）。
- `SpawnerCommands`：`/spawner info|upgrade|set entity|set 参数|give`。

## 指令

| 指令 | 说明 |
|---|---|
| `/spawner info` | 查看信息（类型/等级/生效参数/可调范围/升级费用）——仅标签笼 |
| `/spawner upgrade` | 升级（纯金钱；Lv 0 → 1 起）——仅标签笼，受 `spawner.upgrade` 开关控制 |
| `/spawner set <参数> <值>` | 微调生成参数（受等级范围约束；Lv 0 不可微调） |
| `/spawner give` | 管理员获得带标签刷怪笼 |
