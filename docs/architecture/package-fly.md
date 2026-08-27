# `mois.economy.fly` 包 — /fly 付费飞行

## `FlyManager.java` — 飞行模式状态机与每秒扣费（内存状态）

费用：`EconomyConfig.flyFeeCents()`（config.json 的 `flyFeePerSecond`，默认 500.00 元/秒）。

- 状态（全部 `ConcurrentHashMap` 包装的 Set）：
  - `ENABLED`：已开启飞行的玩家（**跨下线保留**，服务器重启清空）；
  - `WARN_OFF`：关闭了“余额不足 1 分钟提醒”的玩家；
  - `LOW_WARNED`：本次低余额事件已提醒过（余额回升到阈值以上后移除，可再次提醒）。
- API：
  | 方法 | 说明 |
  |---|---|
  | `isEnabled(ServerPlayer)` | 查询 |
  | `isWarnEnabled(UUID)` | 提醒是否开启（默认开） |
  | `toggleWarn(UUID)` | 切换提醒，返回切换后的开启状态 |
  | `enable(ServerPlayer)` | 置 ENABLED + `mayfly=true, flying=true` + `onUpdateAbilities`（立即悬空） |
  | `disable(ServerPlayer)` | 移除 ENABLED + 收回能力 |
  | `onJoin(ServerPlayer)` | 上线时若 ENABLED 则重新授予能力（防出生在空中坠落） |
  | `onDisconnect(ServerPlayer)` | 下线收回能力（**不改 ENABLED**），避免能力写入存档 |
  | `onServerTick(MinecraftServer)` | 见下 |
  | `checkLowBalanceWarn(player, balance, fee)` | 低余额提醒（命令开启时也调用） |
- **每秒结算**（`getTickCount() % 20 == 0`，遍历在线玩家，仅 ENABLED）：
  1. 只保证 `mayfly = true`（**不强制 `flying`**，玩家双击空格下降时不会被每秒拉起）；
  2. `fee <= 0` → 免费飞行，跳过检查；
  3. 读余额（DB 异常跳过本轮，不误关）；
  4. 余额 < fee → `disable` + 红字“资金不足（每秒扣费 X 元），飞行模式已自动关闭，剩余资产 X 元”；
  5. `checkLowBalanceWarn`：余额 ≤ fee×60 → 按提醒开关提醒一次（黄字，含 1 分钟约需金额）；
     余额 > 阈值 → 移除 LOW_WARNED 标记；
  6. `EconomyDb.deduct(uuid, fee)`（失败仅记日志）+ 资金流水 FEE（channel=FLY，
     每秒一条；记录失败静默）。
- **能力管理**：`revokeAbilities` 跳过创造/旁观玩家（其能力由游戏模式管理）；
  飞行能力不写存档——下线时收回，上线/tick 重新授予。
- **飞行挖掘速度**：`PlayerMixin.economy$restoreDigSpeedWhileFlying` 撤销空中挖掘惩罚
  （26.3 原版 `getDestroySpeed` 对 `!onGround` 玩家末尾 `f / 5.0F`）——`/fly` 开启且
  正在飞行（`abilities.flying`）时挖掘速度与地面一致。**`/config fly.digNoSlow` 开关**
  （默认 true）：false 时保留原版生存飞行挖掘速度。
  - mod 客户端：开关值经 `FlyConfigSync`（S2C payload `economy:fly_dig_no_slow`）在
    JOIN 与热改时下发，本地预测与服务端权威一致。
  - **纯净客户端（无 mod）**：26.3 服务端对普通破坏只广播裂纹、破坏时刻由客户端本地
    进度满后发送的 DESTROY_BLOCK 包决定——纯净端本地未恢复会实际变慢。
    `ServerPlayerGameModeMixin.economy$earlyDestroyForVanillaClient`（tick RETURN）在
    飞行加速生效且服务端权威进度已满时由服务端直接 `destroyBlock`，使纯净端实际
    挖掘速率与 mod 客户端一致（下一 tick 原版 isAir 分支自动复位；商店保护/buymode
    禁挖的既有拦截一并生效）。
- `FlyCommands` 是唯一外部入口（命令只切换状态与提示，扣费全在本类）。
- 工具：`satMul`（fee×60 防溢出）。

## 交互约定（改动时注意）

- 开启前拒绝：创造/旁观模式、余额不足 1 秒费用（由 `FlyCommands.toggle` 负责）。
- 低余额提醒“一次即可”：以 `LOW_WARNED` 标记为准，余额回升后自动解除，可再次提醒。
- 与 `/bm` 无耦合；两者都是“会话级内存状态 + tick 结算”模式。
