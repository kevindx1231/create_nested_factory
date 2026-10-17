# 1.20.1 冻结兼容修复方向

日期：2026-10-18。本文最初在 1.21.1 项目中编写，作为 1.20.1 的移植方案参考；下文保留当时的待验证分析。1.20.1 的实际修复、固定版本与已完成验证见[实施与验证报告](freeze-compatibility-validation-1.20.1.md)。

## 问题与目标

1.21.1 错误报告 `error/错误报告-2026-10-18_3.46.57/latest.log` 记录：嵌套工厂与 Observable 5.4.4 同时对 `Level.tickBlockEntities()` 中的 `TickingBlockEntity.tick()` 使用 `@Redirect`。Observable 先占用调用点，嵌套工厂注入成功数为零，触发强制注入失败，游戏退出。

此前 Lithium 0.15.4 的冲突发生在 `ServerLevel.tickChunk()` 的方块、流体随机刻调用处；1.21.1 的 v1.5.1 已将这两个入口改为条件包装。两次问题属于同类调用点争用，修复位置不同。

修复目标：非冻结区域保留第三方优化、计时和真实 tick；冻结区域不执行真实 tick；解冻后恢复，且不重复执行。被冻结的机器没有实际 tick，Observable 不应为它生成本次实际执行的计时样本。

## 先检查 1.20.1 的实际实现

- 确认 Minecraft 1.20.1、Forge、Java 17、映射和实际模组版本。
- 查找本项目的 `@Redirect`、`@Overwrite`、可取消 `@Inject`，以及第三方修改的同一调用点。
- 对照运行时异常，区分方块实体 tick、实体 tick、随机刻、计划刻和方块事件。不能因为都属于冻结就使用同一个注入目标。
- 在 1.20.1 对应源码或转换后的字节码中核实方法描述符、参数和局部变量；不要直接复制 1.21.1 的 lambda 名称，例如 `lambda$tick$2`。
- 核实实际使用的是哪些性能优化模组及其 1.20.1 实现，不能套用本次 Lithium 0.15.4 的结论。

## 首选修复

参考本项目：

- `src/main/java/com/createnestedfactory/create_nested_factory/mixin/LevelFreezeMixin.java`
- `src/main/java/com/createnestedfactory/create_nested_factory/mixin/ServerLevelRandomTickFreezeMixin.java`
- `src/main/java/com/createnestedfactory/create_nested_factory/PocketFreezeHooks.java`

将“判断冻结，然后手动调用原方法”的 `@Redirect` 改为 MixinExtras v2 `@WrapWithCondition`。允许执行时返回 `true`，交还既有调用链；冻结时返回 `false`。删除手动 `ticker.tick()`、对应 Redirect import 和不再使用的参数或辅助方法。

以方块实体入口为例，以下只是待核对版本和映射后的移植骨架：

```java
@Mixin(value = Level.class, priority = 900)
abstract class LevelFreezeMixin {
    @WrapWithCondition(
            method = "tickBlockEntities",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/level/block/entity/TickingBlockEntity;tick()V")
    )
    private boolean createNestedFactory$allowBlockEntityTick(TickingBlockEntity ticker) {
        return !PocketFreezeHooks.isFrozen((Level) (Object) this, ticker.getPos());
    }
}
```

`priority = 900` 参考当前已知第三方默认 1000 的情况。它安排 Mixin 的处理顺序，不是对任意模组的通用保证；需要确认实际变换后包装的是第三方处理后的操作。

单纯的执行条件优先使用 `@WrapWithCondition`；确实需要前后处理或返回值时才使用 `@WrapOperation`，正常路径调用传入的 `Operation`，不直接重走原版方法。不能提高 Redirect 优先级以覆盖第三方逻辑，也不能仅通过 `require = 0` 忽略冻结入口失效。

## MixinExtras 与 Forge 依赖

1.21.1 当前环境提供 MixinExtras 0.5.3，已有 v2 条件包装。1.20.1 是否提供该能力需要独立检查。

- 确认使用的版本含 `com.llamalad7.mixinextras.injector.v2.WrapWithCondition`，支持 Java 17 和对应 Mixin/Forge 环境。
- 如需新增依赖，使用 Forge 对应的 MixinExtras 制品和官方推荐的打包/初始化方式；不能直接搬用 NeoForge 制品。
- 核对发布 JAR 是否正确携带必要依赖，避免仅在 IDE 中成功、用户环境缺类或没有初始化注入器。
- 核实是否需要 refmap 及重映射设置。1.21.1 此次异常中的 `No refMap loaded` 不是已证实根因，不能据此禁用 1.20.1 所需的重映射。

参考官方说明：

- [WrapWithCondition](https://github.com/LlamaLad7/MixinExtras/wiki/WrapWithCondition)
- [WrapOperation](https://github.com/LlamaLad7/MixinExtras/wiki/WrapOperation)
- [MixinExtras](https://github.com/LlamaLad7/MixinExtras)

## 冻结范围与专项适配

冻结判断继续集中在已有的房间冻结查询中，Mixin 仅负责拦截。保留维度、XYZ 范围和解冻事务规则，不能改成冻结整个区块；同区块内其他高度的工厂和主世界必须继续运行。

计划刻及方块事件继续执行已有捕获、剩余时间保存和恢复语义，不应简单丢弃。实体入口要保留玩家排除和跨边界实体处理。

若第三方完全替换调度器、删除原版调用点或通过异步线程直接执行机器，通用包装不足，需要面向该模组及版本的适配。通用入口和专项入口必须互斥，避免重复 tick。未知关键入口失效应给出明确错误，不能静默放行被冻结机器。

## 验证与交付

1. 先用原版本和冲突模组复现，保留直接指向冲突调用点的日志。
2. 分别验证仅嵌套工厂、加性能优化模组、加 Observable、三者同时安装。模组版本固定为 1.20.1 实际可用版本。
3. 用真实加载了 Mixin 的环境验证方块实体：冻结后零 tick，同区块其他高度和其他维度仍 tick；解冻后执行次数与正常邻居一致。
4. 启用 Observable 计时，检查正常机器统计次数等于实际 tick 次数，冻结机器不产生执行样本，计时上下文正常清理。
5. 验证随机刻、计划刻、方块事件、实体冻结和玩家活动；特别检查解冻与重载后没有重复执行、丢失计划刻或资源增加。
6. 构建发布 JAR，检查版本、Mixin 配置和依赖，在实际发行环境复测。开发环境成功不能代替发行 JAR 验证。

1.21.1 新增的 `src/freezeCompatTest/` 是不进入发布 JAR 的集成测试，可参考测试场景，但 1.20.1 的 GameTest 注解、注册、资源位置和 Gradle 运行配置需要重新核对。
