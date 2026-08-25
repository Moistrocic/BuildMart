# `mois.economy.util` 包、client 源集与资源文件

## `util/AdminUtil.java` — 管理员判定

- `isAdmin(ServerPlayer)` → 单人游戏所有者（`server.isSingleplayerOwner(NameAndId)`）
  **或** 权限 ≥ `PermissionLevel.ADMINS`（原 /op 3 级，`getProfilePermissions`）。
- ⚠️ **必须用 `player.level().getServer()` 取服务器**：`createCommandSourceStack()` 会调用
  `getDisplayName()`，与 `PlayerMixin` 的注入形成无限递归。

## client 源集（`src/client`）

- `mois.economy.client.EconomyClient`：空 `ClientModInitializer`（`onInitializeClient` 无逻辑）。
- 价格提示完全由服务端线路层真实 lore 提供（纯净端同样可见），客户端目前无任何逻辑；
  未来客户端增强功能（HUD、快捷键等）放这里，必须保持可选（规则书 3.2）。

## 资源文件

### `src/main/resources/fabric.mod.json`

- id `economy`，name `Economy`，license `CC0-1.0`，`environment: "*"`（双端）。
- entrypoints：main `mois.economy.Economy`；client `mois.economy.client.EconomyClient`。
- mixins：`economy.mixins.json`。
- depends：fabricloader ≥0.19.3、minecraft `~26.3-`、java ≥25、fabric-api `*`。
- `version` 由 `processResources` 从 `gradle.properties` 展开（`${version}`）。

### `src/main/resources/economy.mixins.json`

- `required: true`，`package: "mois.economy.mixin"`，`compatibilityLevel: "JAVA_21"`，
  `injectors.defaultRequire: 1`。
- mixins 列表（12 个）：ServerPlayerMixin、PlayerMixin、LivingEntityMixin、InventoryMixin、
  AbstractContainerMenuMixin、CraftingMenuMixin、ServerGamePacketListenerImplMixin、
  ServerPlayerGameModeMixin、FishingHookMixin、ExplosionDamageCalculatorMixin、
  ServerExplosionMixin、LevelMixin。

### `assets/economy/icon.png`

- 模组图标（fabric.mod.json 引用）。

## 其它文档

- `AGENTS.md` — 项目开发规则书：提交粒度（规则 1）、Fabric API 优先（规则 2）、
  纯净端兼容与单人游戏（规则 3，其中 3.1 禁止自定义命令参数类型）、
  启动验证规范（规则 4：后台启动 + 日志监视，成功标记以模组初始化标记 `Economy Mod Loaded!` 为准）。
