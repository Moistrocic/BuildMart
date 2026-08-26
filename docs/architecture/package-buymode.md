# `mois.economy.buymode` 包 — /bm 便捷购买

## `BuyModeManager.java` — 便捷购买会话管理（内存状态）

思路：临时授予 `instabuild`，让**纯净客户端**打开背包时自动进入原版创造物品栏界面，
用原版创造界面充当购买 UI；拿取/放回/丢弃的判定由
`ServerGamePacketListenerImplMixin` + `BuyModeSession` 结算。

- 状态：
  - `ACTIVE: Set<UUID>`（`ConcurrentHashMap` 包装）；
  - `PREV_INSTABUILD: Map<UUID, Boolean>`（进入前是否已有 instabuild，退出时还原）；
  - `SESSIONS: Map<UUID, BuyModeSession>`（会话追踪状态，见下）。
- API：
  - `isActive(ServerPlayer)` — 查询。
  - `session(ServerPlayer)` — 取当前会话（未激活返回 null）。
  - `enter(ServerPlayer)` — 记录原值 + 建会话 + 置 `abilities.instabuild = true` + `onUpdateAbilities()`。
  - `exit(ServerPlayer)` — **先结算**（`session.settleAndClear`：暂存剩余统一按卖出退款，
    pendingDrop 作废）再还原 instabuild 并 `onUpdateAbilities()`。
  - `onServerTick(MinecraftServer)` — 结算挂起超过宽限期的 pendingDrop（面板 ctrl+q 购买），
    由 `Economy.java` 的 END_SERVER_TICK 注册。
- 退出时机（外部触发）：`ServerPlayerMixin` 的 `doCloseContainer`（关界面）与
  `Economy.java` 的 DISCONNECT（下线）——两条路径都走 `exit`，结算时机天然统一。
- 内存状态：服务器重启清空；玩家下线时退出（`BuyModeManager.exit` 幂等）。

## `BuyModeSession.java` — 会话追踪状态与结算（判定模型核心）

判定模型（服务端权威，比较一律先 untag 归一，价格行不参与匹配）：

| 服务端事件 | 判定 | 动作 |
|---|---|---|
| 槽位变空 / 同物品数量减少（拿起、拆分拿起、顶出） | 消失 | 物品入暂存（含数量），**不结算** |
| 槽位出现物品（放回） | 增量匹配暂存（同 item+组件） | 增量 ≤ 暂存剩余 → 中性；超出部分 → **挂起**（见下） |
| 槽位出现物品（与暂存完全不同） | 面板来源/交换候选 | **挂起** pendingSlot（不立即购买），由下一槽位包配对或超时结算 |
| 挂起槽位 + 下一槽位包构成**对称交换**（本包原内容 = 挂起出现内容，本包出现 = 挂起消失内容，同 item+组件+数量） | 槽间交换（创造界面数字键 1-9 SWAP） | 双槽中性放回：不扣款、不提示、无暂存残留 |
| 挂起槽位配对失败 / 挂起 ≥2 tick 无配对包 | 非交换 | 挂起内容与原版创造物品栏一致 → **购买**（按未吸收增量扣款）；否则 → **拒绝**（槽位保持原状，撤销消失记录，提示） |
| `-1` 丢弃包完全匹配暂存 | 先拿起再丢（点击外部/丢弃光标） | **卖出**：按丢弃数量退款，暂存扣减 |
| `-1` 不匹配暂存 | 挂起 pendingDrop | 下一槽位包用「槽位原内容」判定：匹配 → 背包 ctrl+q 直接丢 = **卖出**；否则 → 面板 ctrl+q = **购买**（生成实体）；**挂起 ≥2 tick 仍无槽位包认领（面板 ctrl+q 无后续包）→ 服务端 tick 直接结算为购买**（`BuyModeManager.onServerTick` → `settlePendingDrop`，避免滞后一拍） |
| 关闭物品栏 / 退出模式 / 掉线 | `settleAndClear` | 暂存剩余统一**卖出**；pendingDrop 作废；pendingSlot 撤销（槽位从未被修改，物品未丢失，撤销其消失记录） |

- 消失/出现只改暂存不动资金；购买失败（余额不足）槽位保持原状并撤销消失记录。
- **数字键交换（26.3 关键行为）**：创造界面数字键 1-9 对悬停物品执行 SWAP，客户端本地
  `InventoryMenu.clicked` 交换后经 `broadcastChanges` 把**两个**变化槽以 `SetCreativeModeSlot`
  逐槽上报。若逐包直接购买，交换会被误判为面板购买：改造物品交换 → 提示 + 消失记录残留
  （关闭界面白嫖退款）；纯净物品交换 → 误扣款/残留吸收免费复制。因此「出现不匹配暂存」
  一律**先挂起**（pendingSlot），下一个槽位包能配对成对称交换即中性双槽放回，否则才按
  面板购买/拒绝结算（延迟一拍，超时 2 tick 兜底）。
- 为什么拿起不立即退款：拿起可能是中性重组（放回匹配）或卖出（丢弃/关闭），
  统一延迟到「确认去向」再结算；数量守恒兜底同物品混叠（面板增量必然造成
  放回增量 > 暂存剩余 → 按购买处理）。
- 结算与标签补发见 package-mixin.md 的 `ServerGamePacketListenerImplMixin`。
