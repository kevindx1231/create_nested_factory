# Changelog - Minecraft 1.20.1

# v1.5.22 - Minecraft 1.20.1

## 简体中文

需要 Minecraft 1.20.1、Java 17 和 Create 6.0.8–6.0.x。专用服务器验证版本：Forge 47.3.0 / NeoForge 47.1.106。

### 修复与改进

- 改进树场/农场学习：按树苗种类或作物 ID 独立认证，有生长或生产活动的来源才参与等待，闲置来源不阻塞其他路线；生长活动不等于认证完成。
- 学习默认绝对期限由 6000 改为 3600 tick（20 TPS 时 3 分钟），生长不会重置期限；已有存档的显式配置保持原值。
- 发布计划前重新核对完整学习账本，避免沿用过早锁定的加工路线、错误归属再生产物或忽略库存净损耗；拆分再生路线时保留启动资本合同。
- 修正 1.20.1/Create 配方字段、序列装配中间物和循环次数、锯切时长、超高温搅拌要求及应力端口的两种仪表材料，并新增全部 15 个配方的反序列化回归。
- 修复工厂冻结与 Observable 4.4.2 的 Mixin 注入冲突，避免加载世界前退出。
- 方块实体冻结改用优先级 900 的 MixinExtras v2 `WrapWithCondition`。正常机器保留第三方 tick/性能分析调用链；冻结机器跳过整个操作，包括 Observable 计时样本，解冻后恢复执行。
- 发布 JAR 通过 Jar-in-Jar 内嵌 MixinExtras Forge 0.4.1，适用于 Forge 47 和 NeoForge 47；保留 SRG refmap 和严格注入检查，无需额外安装 MixinExtras。
- 保留 Pocket 维度与 XYZ 冻结边界、计划刻/方块事件捕获及恢复、实体边界判断和玩家排除规则。
- 增加独立测试模组与生产服务器兼容验证脚本；测试模组和 Observable/Radium 等测试依赖不会进入正式 JAR。
- 同步 README 的版本、下载链接、兼容说明、测试范围及上游 MIT 许可元数据。

### 验证范围

完整构建及 21 组 JVM 回归通过。同一正式 JAR 在以下 8 种专用服务器组合中通过冻结与恢复测试：

| 加载器 | 基础依赖 | Radium 0.12.4 | Observable 4.4.2 | 两者同时 |
|---|---|---|---|---|
| Forge 47.3.0 | 通过 | 通过 | 通过 | 通过 |
| NeoForge 47.1.106 | 通过 | 通过 | 通过 | 通过 |

本次未重测客户端 GUI，也未穷举其他加载器或性能模组版本。完整报告：[冻结兼容验证](https://github.com/kevindx1231/create_nested_factory/blob/mc1.20.1/docs/freeze-compatibility-validation-1.20.1.md)。

安装附件 `create_nested_factory-1.20.1-1.5.22.jar`。源码 JAR 和 `test-only` 测试模组不用于正常游戏。

## English

Requires Minecraft 1.20.1, Java 17, and Create 6.0.8–6.0.x. Dedicated-server tested on Forge 47.3.0 and NeoForge 47.1.106.

### Fixes and improvements

- Improves tree/crop learning with independent sapling-type/crop-ID certification. Only sources with observed growth or production participate in the wait; idle groups do not block other routes, and growth alone does not certify production.
- Changes the default absolute learning deadline from 6000 to 3600 ticks (3 minutes at 20 TPS). Growth never resets it; existing explicit world configuration retains its value.
- Reconciles the complete learning ledger before publishing a plan, preventing stale processing candidates, misattributed regenerative output, and ignored inventory losses. Splitting a regenerative route now retains its startup-capital contract.
- Corrects 1.20.1/Create recipe fields, assembly transitional items/loop counts, cutting duration, superheated mixing, and the stress port's two alternative gauges. Adds deserialization regression coverage for all 15 recipes.
- Fixes the factory-freeze Mixin injection conflict with Observable 4.4.2 that could abort world loading.
- Replaces the block-entity freeze Redirect with MixinExtras v2 `WrapWithCondition` at priority 900. Live machines retain third-party tick/profiling behavior; frozen machines skip the whole operation, including Observable timing samples. Thawing restores execution.
- Bundles MixinExtras Forge 0.4.1 through Jar-in-Jar for Forge 47 and NeoForge 47, preserving the SRG refmap and strict injection checks. No separate MixinExtras installation is required.
- Preserves Pocket dimension/XYZ freeze boundaries, scheduled-tick and block-event capture/restoration, entity bounds, and player exclusions.
- Adds a separate test mod and production-server compatibility runner; neither the test mod nor optional profiling/optimization dependencies enter the release JAR.
- Updates README versions, download links, compatibility/validation scope, and upstream MIT license metadata.

### Validation

Full build and all 21 JVM regression groups passed. The same production JAR passed all eight dedicated-server combinations of Forge 47.3.0 / NeoForge 47.1.106 with base dependencies, Radium 0.12.4, Observable 4.4.2, and both. Client GUI and other loader/mod versions were not retested. See the [freeze compatibility report](https://github.com/kevindx1231/create_nested_factory/blob/mc1.20.1/docs/freeze-compatibility-validation-1.20.1.md).

Install `create_nested_factory-1.20.1-1.5.22.jar`; sources and `test-only` JARs are not game releases.

Previous release / 上一版本：[v1.5.21](https://github.com/kevindx1231/create_nested_factory/releases/tag/mc1.20.1-v1.5.21)。

SHA-256: `c2f8a8cdc0f647f9c1d1e29aed5a85fabbc01629531c82e1d05bb127fe2396c3`.

# v1.5.21 - Minecraft 1.20.1

Requires Minecraft 1.20.1, Java 17, and Create 6.0.8–6.0.x. Development loader: NeoForge 47.1.106. Optional compatibility targets: Mekanism 10.4.16.80 and Pipez 1.20.1.

## Fixes and improvements

- Corrected GUI textures for face modes, pressed buttons, and the overclock slider.
- Fixed flight and night-vision synchronization, Shift-return while flying, previous effect restoration, cancelled teleports, and flight restoration after game-mode changes.
- Prevented factory-blueprint crashes and accidental entry into Create's schematic editor; corrected capability refresh after applying a blueprint and removed duplicate Shift hints.
- Fixed Mekanism pipe disconnections during capability invalidation and automatic reconnection after mode changes, preparation, thawing, and late port loading.
- Fixed Pipez fluid/universal-pipe endpoint detection for the 1.20.1 API.
- Prevented wrong-type and mixed-type extraction from losing gas, infusion, pigment, or slurry resources.
- Reused capability handlers, extension delegates, and heat endpoint lookups; reduced redundant port refreshes, blueprint parsing, and session NBT work.
- Corrected the 1.20.1 Pocket dimension light-provider format.

## Validation

Full build and all 20 JVM regression groups passed. Release metadata, Java 17 bytecode, resources, and Mixin/refmap packaging were checked. These fixes have not yet received a complete in-game retest or client/dedicated-server validation on both loaders.

Aeronautics, Simulated, and Sable moving-structure integration is not included in this 1.20.1 port. Minecraft 1.21.1 worlds are not converted to 1.20.1.

Install `create_nested_factory-1.20.1-1.5.21.jar`; the sources JAR is not a game mod.

Historical 1.21.1 releases: [1.21.1 changelog](https://github.com/kevindx1231/create_nested_factory/blob/mc1.21.1/CHANGELOG.md).
