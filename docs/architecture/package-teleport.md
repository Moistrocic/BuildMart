# `mois.economy.teleport` 包 — 传送系统

## `TeleportManager.java` — 传送核心（费用/冷却/tpa 请求/执行）

配置见 `EconomyConfig` 的 home/tpa/back 段（`TpFees`）。

- 状态：三张冷却表 `HOME_COOLDOWN`/`TPA_COOLDOWN`/`BACK_COOLDOWN`（`Map<UUID, Long>`，tick 值，
  内存态、重启清空）；tpa 请求表 `REQUESTS`（键 = 被请求人 UUID → `List<PendingRequest>`）。
- `PendingRequest(long seq, UUID requesterUuid, String requesterName, boolean toTarget, long expireTick)`
  — toTarget=true 表示请求方传送到对方位置（/tpa），false 表示对方传到请求方（/tpahere）。
- `TpOutcome(boolean ok, String message)` — 执行结果（ok=false 时 message 为可读失败原因）。
- API：
  | 方法 | 说明 |
  |---|---|
  | `homeCooldowns()/tpaCooldowns()/backCooldowns()` | 暴露冷却表（TeleportCommands 传入） |
  | `request(requester, target, toTarget, server)` | 发请求（同请求方重复请求会覆盖旧请求） |
  | `latestRequest(targetUuid, server)` | 接受者视角最近（seq 最大）且未过期的请求 |
  | `accept(accepter, server)` | 接受并执行；费用由请求方承担，冷却记在被传送者 |
  | `onServerTick(server)` | 每 20 tick 清理过期请求 |
  | `recordDeath(player)` | 死亡点记录（back 配置关闭时不记录） |
  | `teleportAndCharge(mover, payerUuid, payerName, toLevel, toPos, fees, cooldownMap, server)` | 核心执行 |
- **执行流程**（teleportAndCharge）：冷却检查（剩余秒数提示）→ 费用计算 → 读余额/扣款
  （不足或 DB 错误返回失败）→ `mover.teleportTo(toLevel, x, y, z, Set.of(), yaw, pitch, false)` →
  记录冷却 → 成功消息（含费用）。
- **费用规则**：fixedFee 开启 → 固定 fixedFeeAmount；否则同维度 → `ceil(距离) × perDistanceFee`；
  跨维度 → 仅收 crossDimensionFee（额外，不计距离）。
- 注意：26.3 中 `ServerPlayer.level()` 协变返回 `ServerLevel`（本项目统一用它取维度/坐标，
  `serverLevel()` 方法不存在）；`ResourceKey.identifier()` 取维度 ID 字符串。

## `TeleportCommands.java` — 传送指令

- 注册：`/home [名称]`、`/sethome 名称`、`/delhome 名称`、`/listhome [页码]`（每页 10 行）、
  `/tpa 玩家`、`/tpahere 玩家`、`/tpaccept`、`/back`。
- `/home`：无名称 → 最近设置的家（`getHomes` 按 created 倒序取第一项）；有名称 → 忽略大小写匹配；
  无家/未找到/维度不可用各有提示。费用/冷却用 home 配置。
- `/sethome`：名称 ≤ 16 字符；`max <= 0`（默认 0）拒绝；达到上限拒绝（覆盖已存在的同名家不占新名额）；
  存 `EconomyDb.setHome`（维度 ID 字符串 + 坐标）。
- `/delhome`：`EconomyDb.removeHome`（新增 API），不存在提示“没有找到名为 X 的家”。
- `/listhome`：分页展示（名称 - 维度 (x, y, z)），页码超出范围提示。
- `/tpa`/`/tpahere`：tpa.enabled 关闭时拒绝；目标必须在线、不能是自己；请求消息用
  `playerName(player)`（`getDisplayName()` 可能为 null，回退档案名）构造组件，
  不能用字符串拼接 `Component`（会输出 Component.toString() 的原始结构）。
- `/tpaccept`：接受最近请求；**费用始终由请求方承担**（tpa：请求方被传送；tpahere：被请求方
  被传送，目标位置为请求方位置）；成功时被请求方看到“费用 X 元由请求方支付”，
  请求方（付费方）单独收到扣费确认；失败时付费方收到原始措辞（“你的资金不足”对请求方
  准确），被请求方（若并非付费方）看到“对方的资金不足…”，避免误以为是自己扣款
  （指令层只负责本地失败提示，避免重复）。
- `/back`：back.enabled 关闭时拒绝；无死亡点拒绝；传送**成功后立即清除死亡点**
  （再次 /back 视为无死亡点）；费用/冷却用 back 配置。
- 维度解析：`ResourceKey.create(Registries.DIMENSION, Identifier.parse(world))`。
- 玩家目标用原版 `GameProfileArgument`（`NameAndId` 离线 UUID 回退），符合规则书 3.1。
