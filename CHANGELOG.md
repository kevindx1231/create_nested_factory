# Changelog - Minecraft 1.20.1

# v1.5.23 - Minecraft 1.20.1

## 简体中文

需要 Minecraft 1.20.1、Java 17、Forge/NeoForge 47 和 Create 6.0.8–6.0.x。开发基线：NeoForge 47.1.106、Create 6.0.8-291、Ponder 1.0.91。

### 新增 Ponder 思索教程

- 新增五类组件的八段教程：嵌套工厂的进入与返回、物流、黑盒与搬迁、多层嵌套，以及嵌套端口、嵌套扩展接口、应力端口和工厂通道。
- 迁入十个结构资源和中英文各 89 条翻译，保留场景时序、坐标、关键帧、镜头及实体动画，并适配 1.20.1 的物品堆 NBT、结构数据版本与客户端注册接口。
- 在物品界面对上述组件按住 W 打开思索。包括分类页标题的 1.20.1 适配。

### 嵌套工厂放置、蓝图与搬运修复

- 同层后放工厂可作为无空间蓝图工厂使用；深度上限下一层允许多个终端蓝图工厂，不额外分配房间或占用入口。
- 分离原空间绑定与当前入口资格。旧工厂 A 搬走后，新工厂 D 可以获得入口；受限放回 A 时保留其完整绑定并冻结原房间。入口空闲后重新搬运 A 可恢复原空间，不会自动升级或错误复用 D 的房间。
- 修复 Forge 在物品 NBT 写入前触发 `onLoad` 时，临时身份提前分配房间或抢占入口的问题；正确保存、同步受限状态，并处理旧无空间方块迁移及缺失槽位字段。
- 同层蓝图工厂复用真实投入托管、物品/流体产出和应力合同；取消蓝图后继续阻止旧房间运行。缓存能力遵守冻结、移除和受限状态。
- 统一玩家挖掘、创造模式、潜行扳手、GUI/指令销毁、爆炸，以及 Create 机械钻头和移动破坏器的内部子工厂保护。
- 普通搬运和机器掉落保留原空间绑定；潜行扳手只返回一份正确物品；无空间工厂结算真实资源后掉落普通工厂方块。修复客户端护目镜对同层受限状态的误报。
- 合并蓝图取消、生产禁用和拆除保护判断；子工厂与冻结遍历按房间覆盖区块扫描方块实体，包含无空间工厂。

### 文档与验证

- 更新中英文 README 的当前版本、下载链接、同层/终端工厂规则、搬运恢复、思索入口和测试范围。
- 2026-10-26 对包含 Ponder 的最终产物执行完整 `build --offline`，全部 22 组 JVM 回归通过；Ponder 回归覆盖五类组件的八段场景、十个结构、59 段内嵌补丁、19 处可读取物品堆和中英文翻译。
- 放置修复阶段已使用正式 JAR 在 Forge 47.3.0 / NeoForge 47.1.106 的基础依赖专用服务器上通过真实放置、搬运、机器拆除及物理冻结/恢复测试；对应历史产物校验值见[放置修复验证报告](https://github.com/kevindx1231/create_nested_factory/blob/mc1.20.1/docs/nested-factory-placement-fix-validation-1.20.1.md)。该记录早于本次加入 Ponder 后的最终构建。
- 本次未重跑最终产物的专用服务器矩阵、Radium/Observable 组合或客户端 GUI/思索画面验收。1.5.22 的八组合结果属于历史记录，不作为本次最终产物的测试结论。参见[思索验收说明](https://github.com/kevindx1231/create_nested_factory/blob/mc1.20.1/docs/ponder-1.20.1.md)。

安装附件 `create_nested_factory-1.20.1-1.5.23.jar`，替换旧版；客户端与服务器使用同一版本。源码和 `test-only` JAR 不用于正常游戏。正式 JAR 内嵌 MixinExtras Forge 0.4.1，无需额外安装。升级既有世界前请备份存档。

## English

Requires Minecraft 1.20.1, Java 17, Forge/NeoForge 47, and Create 6.0.8–6.0.x. Development baseline: NeoForge 47.1.106, Create 6.0.8-291, and Ponder 1.0.91.

### New Ponder tutorials

- Adds eight scenes for five components: factory entry/return, logistics, black-box operation/relocation, nesting, Nested Ports, Extension Interfaces, Stress Ports, and Factory Passages.
- Includes ten structures and 89 translations each in English and Simplified Chinese. Preserves scene timing, coordinates, keyframes, camera work, and entity animation while adapting item-stack NBT, structure data versions, client registration, and category titles to 1.20.1.
- Hold W over the corresponding component in the item UI to open its tutorials.

### Nested placement, blueprints, and relocation

- Later siblings can operate as roomless blueprint factories. Multiple terminal blueprint factories are allowed one layer beyond the room depth limit without allocating rooms or taking the entrance.
- Separates original-room ownership from current entrance eligibility. After moving A away, D may claim the entrance; replacing A in a restricted state preserves its binding and freezes its original room. Relocating A again after the entrance becomes free restores access, without automatic promotion or reusing D's room.
- Fixes Forge calling `onLoad` before item NBT is installed, which allowed a temporary identity to allocate a room or claim an entrance. Persists/synchronizes restrictions and handles legacy roomless blocks and missing slot fields.
- Reuses real input escrow, item/fluid output, and stress contracts for sibling blueprint factories. Cancelling a blueprint keeps the old room blocked; cached capabilities honor restrictions, freezing, and removal.
- Unifies child-factory protection across player/creative breaking, sneak-wrench removal, GUI/commands, explosions, and Create drills/moving breakers.
- Preserves bindings for relocation and machine drops, prevents duplicate wrench drops, settles real resources before roomless factories drop an ordinary block, and fixes client goggle status.
- Consolidates blueprint cancellation, production blocking, and removal checks. Child/freeze traversal scans block entities in covered room chunks, including roomless factories.

### Documentation and validation

- Updates the bilingual README, version/download links, placement/relocation rules, Ponder entry points, and validation scope.
- The final Ponder-enabled artifact passed the full `build --offline` and all 22 JVM regression groups on 2026-10-26. Ponder coverage includes eight scenes/five components, ten structures, 59 inline patches, 19 readable item stacks, and both translations.
- Before Ponder was added, the placement-fix artifact passed real placement, relocation, machine removal, and physical freeze/thaw tests on base-dependency Forge 47.3.0 and NeoForge 47.1.106 dedicated servers. Its historical artifact hash is recorded in the [placement validation report](https://github.com/kevindx1231/create_nested_factory/blob/mc1.20.1/docs/nested-factory-placement-fix-validation-1.20.1.md).
- The final artifact's dedicated-server matrix, Radium/Observable combinations, client GUI, and Ponder visuals were not rerun. The eight-combination 1.5.22 matrix remains historical evidence. See the [Ponder acceptance guide](https://github.com/kevindx1231/create_nested_factory/blob/mc1.20.1/docs/ponder-1.20.1.md).

Install `create_nested_factory-1.20.1-1.5.23.jar` on both client and server, replacing the old version. Sources and `test-only` JARs are not game releases. MixinExtras Forge 0.4.1 is bundled. Back up existing worlds before upgrading.

Previous release / 上一版本：[v1.5.22](https://github.com/kevindx1231/create_nested_factory/releases/tag/mc1.20.1-v1.5.22)。

SHA-256: `35290d3fe6a2a4ac4c2a7110b92693fb7c4751ef744455dea20f234276ca3aef`.

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
