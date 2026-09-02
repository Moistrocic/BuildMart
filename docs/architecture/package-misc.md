# `mois.buildmart.util` 包、client 源集与资源文件

## `util/AdminUtil.java` — 管理员判定

- `isAdmin(ServerPlayer)` → 单人游戏所有者（`server.isSingleplayerOwner(NameAndId)`）
  **或** 权限 ≥ `PermissionLevel.ADMINS`（原 /op 3 级，`getProfilePermissions`）。
- ⚠️ **必须用 `player.level().getServer()` 取服务器**：`createCommandSourceStack()` 会调用
  `getDisplayName()`，与 `PlayerMixin` 的注入形成无限递归。

## client 源集（`src/client`）

- `mois.buildmart.client.BuildMartClient`：`ClientModInitializer`，仅调用 `FastbuyClient.init()`。
- `mois.buildmart.client.FastbuyClient`：**快速投影购买客户端模块（可选增强）**——
  通过**反射**接入 litematica 的 `SchematicPickBlockEventHandler`（动态代理实现
  `ISchematicPickBlockEventListener`），在 `onSchematicPickBlockPrePick` 检查
  生存玩家背包是否有该物品——没有则发送 `FastbuyRequestPayload`（C2S，服务端
  `/fastbuy` 开启时自动购买一组）。litematica 未安装时 `ClassNotFound` 静默跳过；
  服务端未注册 payload 时 `canSend` 检查不发；不修改 litematica 拾取流程本身。
- 价格提示完全由服务端线路层真实 lore 提供（纯净端同样可见）；飞行挖掘加速经原版
  属性同步（BLOCK_BREAK_SPEED 修改器）自动下发，无需自定义网络；
  客户端增强必须保持可选（规则书 3.2），服务端逻辑禁止放客户端。

## 资源文件

### `src/main/resources/fabric.mod.json`

- id `buildmart`，name `BuildMart`，license `CC0-1.0`，`environment: "*"`（双端）。
- entrypoints：main `mois.buildmart.BuildMart`；client `mois.buildmart.client.BuildMartClient`。
- mixins：`buildmart.mixins.json`。
- depends：fabricloader ≥0.19.3、minecraft `~26.2`、java ≥25、fabric-api `*`。
- `version` 由 `processResources` 从 `gradle.properties` 展开（`${version}`）。

### `src/main/resources/buildmart.mixins.json`

- `required: true`，`package: "mois.buildmart.mixin"`，`compatibilityLevel: "JAVA_21"`，
  `injectors.defaultRequire: 1`。
- mixins 列表（12 个）：ServerPlayerMixin、PlayerMixin、LivingEntityMixin、InventoryMixin、
  AbstractContainerMenuMixin、CraftingMenuMixin、ServerGamePacketListenerImplMixin、
  ServerPlayerGameModeMixin、FishingHookMixin、ExplosionDamageCalculatorMixin、
  ServerExplosionMixin、LevelMixin。

### `assets/buildmart/icon.png`

- 模组图标（fabric.mod.json 引用）。

## 其它文档

- `AGENTS.md` — 项目开发规则书：提交粒度（规则 1）、Fabric API 优先（规则 2）、
  纯净端兼容与单人游戏（规则 3，其中 3.1 禁止自定义命令参数类型）、
  启动验证规范（规则 4：后台启动 + 日志监视，成功标记以模组初始化标记 `BuildMart Loaded!` 为准）。
