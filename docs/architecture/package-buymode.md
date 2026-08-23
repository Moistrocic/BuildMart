# `mois.economy.buymode` 包 — /balshop buymode 便捷购买

## `BuyModeManager.java` — 便捷购买会话管理（内存状态）

思路：临时授予 `instabuild`，让**纯净客户端**打开背包时自动进入原版创造物品栏界面，
用原版创造界面充当购买 UI；拿取扣款/放回退款由 `ServerGamePacketListenerImplMixin` 结算。

- 状态：
  - `ACTIVE: Set<UUID>`（`ConcurrentHashMap` 包装）；
  - `PREV_INSTABUILD: Map<UUID, Boolean>`（进入前是否已有 instabuild，退出时还原）。
- API：
  - `isActive(ServerPlayer)` — 查询。
  - `enter(ServerPlayer)` — 记录原值 + 置 `abilities.instabuild = true` + `onUpdateAbilities()`。
  - `exit(ServerPlayer)` — 还原 instabuild 并 `onUpdateAbilities()`。
- 退出时机（外部触发）：`ServerPlayerMixin` 的 `doCloseContainer`（关界面）与
  `Economy.java` 的 DISCONNECT（下线）。
- 结算与标签补发见 package-mixin.md 的 `ServerGamePacketListenerImplMixin`。
- 内存状态：服务器重启清空；玩家下线时退出（`BuyModeManager.exit` 幂等）。
