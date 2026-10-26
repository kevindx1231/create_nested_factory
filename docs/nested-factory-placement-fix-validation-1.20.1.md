# 1.20.1 嵌套工厂放置修复与 1.5.23 验证记录

实施日期：2026-10-20 至 2026-10-21（Asia/Shanghai）。仅修改 `create_nested_factory1.20.1` 项目。

依据：[修复方向](nested-factory-placement-fix-direction-1.20.1.md)。本次独立核对 1.20.1 代码与 API，没有直接移植 1.21.1 的数据组件或 NeoForge 1.21 能力 API。

## 修改前确认的问题

- `FactoryBlockItem` 因父工厂已有入口直接拒绝普通工厂和旧绑定工厂放置。
- `NestedFactoryPlacementRules` 将同层后放工厂判为无效，且不允许第二个终端。
- 初始化无效分支会清除槽位和原房间坐标；蓝图应用又拒绝这些不可进入目标。
- 护目镜使用依赖服务端父工厂查询的进入判定，在客户端会误报入口占用。
- 普通玩家破坏没有共用内部子工厂保护；Create 扳手和机器通过独立的战利品/破坏路径处理方块。
- Forge 正式服务端测试进一步发现：`onLoad` 可能早于物品 NBT 写入，此时提前初始化会用临时身份占用入口，导致原绑定无法恢复资格。

最小回归先执行 `./build-1.20.1.ps1 testCnfCommands`，修改前失败于：

```text
AssertionError: a second ordinary factory must be placeable as a blueprint target
```

## 实现与冗余清理

`hasPhysicalRoom()` 表示空间绑定归属；`canUsePhysicalRoom()` 表示物理房间使用资格；`isBlueprintOnly()` 包括同层受限与深度终端；`isProductionBlocked()` 统一约束生产和资源边界。

- `SiblingBlueprintOnly` 保存到世界 NBT 并同步客户端；普通加载不重新竞争入口。成功初始化消费 `PortableBinding`，导出搬运物品时重新添加该标记。
- 放置成功后立即完成分类和入口登记，避免同一游戏刻重复取得资格。首次 `onLoad` 在身份尚未初始化时延后，待物品数据安装完毕后完成；直接命令放置则在下一次 tick 初始化。
- 同层受限旧绑定继续注册原槽位、保留房间归属并持有物理冻结租约。取消蓝图、祖先解冻和区块重载均不解除其自身限制。
- 无空间蓝图工厂复用现有生产调度器和投入/交付托管，不复制真实资源，不生成房间，不将零坐标区域纳入冻结范围。
- 同层无空间和终端工厂拆除后结算真实资源并掉普通方块；持有旧空间的受限工厂仍掉完整绑定物品。
- `removalBlocker()` 共用于玩家破坏、扳手、GUI/指令和爆炸；`BlockHelperFactoryRemovalMixin` 接入 Create 不带玩家破坏事件的机器路径。绑定战利品与手动搬运保持一致。
- 子工厂检查和冻结遍历按房间覆盖区块扫描方块实体，包含无空间工厂，避免逐方块扫描和旧运行时索引漏检。
- 合并重复的蓝图取消分支，集中处理状态切换、冻结、能力刷新及同步；移除已被空间所有权判定替代的冗余嵌套有效性方法。
- 服务端测试脚本从 `gradle.properties` 读取版本，避免升级后误测旧版 JAR。

## 验证命令与覆盖

环境：Java 17.0.17、Minecraft 1.20.1、Create 6.0.8-291、Ponder 1.0.91、Flywheel 1.0.6-beta-266、Registrate MC1.20-1.3.3。

```powershell
.\build-1.20.1.ps1 testCnfCommands build freezeCompatTestJar -PfreezeCompatTest --offline
.\build-1.20.1.ps1 testCnfCommands --offline
.\test-freeze-compat-1.20.1.ps1 -Loader forge -Scenarios base
.\test-freeze-compat-1.20.1.ps1 -Loader neoforge -Scenarios base
```

`build` 包含 `check`，完整 21 组 JVM 回归通过。新增回归使用真实方块实体、序列化与生产调度器，外部世界/区块设施由 Mockito 提供；物理队列捕获由下面的正式服务端测试独立验证。

新增覆盖包括：首放与同刻后放、旧绑定受限和搬运恢复、早于 NBT 的 `onLoad`、独立槽位、无自动升级、物品/流体真实投入与单次交付、应力不足停机、祖先冻结与恢复、缓存能力在取消后的阻断、客户端提示、普通/创造/扳手/显式销毁保护、受限状态重载、旧无空间迁移、最大深度终端及降低深度上限后的旧绑定恢复。

正式服务端使用发布 JAR 和独立的 `test-only` 测试模组，执行真实 `BlockItem.place`、Create 机器拆除、绑定掉落、A→D→受限 A→恢复 A，以及经过 Mixin 转换的机器 tick、物理计划刻/事件捕获与恢复。测试在拆除前推进既有房间生成任务，遵守生产代码的房间变更锁。

| 正式服务端 | 真实放置及搬运回归 | 物理冻结/恢复回归 | 进程退出码 |
| --- | --- | --- | --- |
| Forge 47.3.0，基础依赖 | PASS | PASS | 0 |
| NeoForge 47.1.106，基础依赖 | PASS | PASS | 0 |

两次测试使用相同的正式 JAR。日志分别为 `build/freeze-compat/production-forge-base.log` 和 `build/freeze-compat/production-neoforge-base.log`；构建日志为 `build/placement-fix-before/validation-1.5.23.log`。新增的早期 `onLoad` JVM 回归另由上述第二条命令通过。

本次未进行客户端手动 GUI 验收，也未重跑 1.5.22 的 Radium/Observable 全组合矩阵；这些旧版结果不作为 1.5.23 的新增验证结论。

## 产物

正式安装文件：`build/libs/create_nested_factory-1.20.1-1.5.23.jar`。

源码：`build/libs/create_nested_factory-1.20.1-1.5.23-sources.jar`。

`cnf-freeze-compat-test-1.5.23-test-only.jar` 仅用于独立测试。正式 JAR 包含 Forge 47 所需 MixinExtras 0.4.1，且不包含回归或测试模组类。

正式 JAR SHA-256：`d75f6156a630f8eb5dd0661f995c807d840fcd9241b1619db8a3c6198423c912`。
