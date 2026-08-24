# `mois.economy.buymode` 包 — /balshop buymode 便捷购买

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
- 退出时机（外部触发）：`ServerPlayerMixin` 的 `doCloseContainer`（关界面）与
  `Economy.java` 的 DISCONNECT（下线）——两条路径都走 `exit`，结算时机天然统一。
- 内存状态：服务器重启清空；玩家下线时退出（`BuyModeManager.exit` 幂等）。

## `BuyModeSession.java` — 会话追踪状态与结算（判定模型核心）

判定模型（服务端权威，比较一律先 untag 归一，价格行不参与匹配）：

| 服务端事件 | 判定 | 动作 |
|---|---|---|
| 槽位变空 / 同物品数量减少（拿起、拆分拿起、顶出） | 消失 | 物品入暂存（含数量），**不结算** |
| 槽位出现物品（放回） | 增量匹配暂存（同 item+组件） | 增量 ≤ 暂存剩余 → 中性；超出部分 → **购买** |
| 槽位出现物品（与暂存完全不同） | 面板来源 | **购买**：严格比对 + 余额 + 扣款 |
| `-1` 丢弃包完全匹配暂存 | 先拿起再丢（点击外部/丢弃光标） | **卖出**：按丢弃数量退款，暂存扣减 |
| `-1` 不匹配暂存 | 挂起 pendingDrop | 下一槽位包用「槽位原内容」判定：匹配 → 背包 ctrl+q 直接丢 = **卖出**；否则 → 面板 ctrl+q = **购买**（生成实体） |
| 关闭物品栏 / 退出模式 / 掉线 | `settleAndClear` | 暂存剩余统一**卖出**；pendingDrop 作废 |

- 消失/出现只改暂存不动资金；购买失败（严格比对不过/余额不足）回滚槽位与暂存快照。
- 为什么拿起不立即退款：拿起可能是中性重组（放回匹配）或卖出（丢弃/关闭），
  统一延迟到「确认去向」再结算；数量守恒兜底同物品混叠（面板增量必然造成
  放回增量 > 暂存剩余 → 按购买处理）。
- 结算与标签补发见 package-mixin.md 的 `ServerGamePacketListenerImplMixin`。
