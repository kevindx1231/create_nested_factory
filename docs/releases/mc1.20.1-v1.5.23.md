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
