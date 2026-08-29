# `mois.economy.spawner` 包 — 刷怪笼玩法（休闲经济闭环）

## 玩法闭环

**钓鱼（休闲获取）→ 刷怪笼（生产资产）→ 升级（投入）→ 刷怪掉落（产出卖钱）**

1. **获取**：`fishing.json` 战利品可配置产出刷怪笼（空笼物品）。刷怪笼定价 -1（不可交易）——
   防倒卖，只能自用。
2. **绑定**：放置空刷怪笼后手持刷怪蛋右键（`UseBlockCallback`，服务端事件）→ 绑定实体类型
   （消耗一个蛋，等级参数重置为 Lv 1）。已绑定的笼（含原版地牢笼）不可改绑。
3. **升级**：`/spawner upgrade`（对准刷怪笼，5 格内）——纯金钱升级：
   - 费用：`1000 × 等级²` 元（1→2 级 1000 元、9→10 级 81000 元），
     扣款写流水 `SPAWNER_UPGRADE/SPAWNER`（AGENTS.md 第 6 节强制约定）；
   - 参数随等级提升：生成间隔 ×0.75、每次数量 +1、附近上限 +3、激活距离 +2、范围 +1
     （Lv 10：约 45~60 tick 一只 ×13 只/次，效率约 15 倍）；
   - `MAX_LEVEL = 10`。
4. **回收**：玩家用镐破坏刷怪笼（非创造）掉落**带完整数据**的刷怪笼物品
   （`BLOCK_ENTITY_DATA` 组件：实体类型 + 升级参数），重新放置即恢复——升级投入不白费；
   爆炸等非玩家破坏不掉落（仅 `playerDestroy` 路径）。
5. **产出**：刷怪掉落物按 `items.json` 定价出售（商店自动出售 / /bm）→ 赚钱。

## `SpawnerManager.java` — 核心逻辑

- 等级参数公式：`minDelay = max(20, round(600 × 0.75^(level-1)))`、`maxDelay = max(40, round(800 × 0.75^(level-1)))`、
  `spawnCount = 4 + (level-1)`、`maxNearby = 6 + (level-1)*3`、`playerRange = 16 + (level-1)*2`、
  `spawnRange = 4 + (level-1)`。
- `inferLevel(minDelay)`：**等级 = 由生成参数反推**（参数完全由等级公式决定，遍历 1..10 取最接近者）——
  无需自定义 NBT 存储等级，原版存档格式不变。
- `bindWithEgg` / `upgrade` / `describe` / `targetedSpawner`（准星射线，同 BalshopCommands 模式）。

## `SpawnerAccess.java` + `BaseSpawnerMixin`

- `BaseSpawner` 生成参数均为私有字段且无公开 setter → mixin 实现 `SpawnerAccess` 接口
  （min/maxDelay、spawnCount、maxNearby、playerRange、spawnRange、hasPotentials、applyLevel）。
- `economyApplyLevel` 同时重置当前生成倒计时（升级立即生效）。

## `SpawnerBlockMixin`（目标 `Block`）

- 注入 `Block.playerDestroy`（mixin 不搜索父类方法，须注入声明处；运行时
  `instanceof SpawnerBlock` 判断）：非创造玩家破坏刷怪笼 → `saveCustomOnly` 序列化
  BlockEntity 数据 → `TypedEntityData.of(BLOCK_ENTITY_TYPE 注册表取值, tag)` 写入
  `BLOCK_ENTITY_DATA` 组件 → `popResource` 掉落。

## `SpawnerCommands.java` — 指令

- `/spawner`：查看准星对准的刷怪笼（类型/等级/生成参数/下次升级费用）。
- `/spawner upgrade`：升级（余额检查 → 扣款 → 流水 → 应用参数 → 客户端同步）。
- 已加入 `/balhelp` 帮助列表；注册于 `EconomyCommands.register`。
