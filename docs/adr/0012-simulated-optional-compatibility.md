---
status: accepted
---

# 将 Simulated 兼容作为独立可选模组

create_nested_factory 的基础模组保持不依赖 Simulated、Sable 和 Aeronautics；所有相关类型、Mixin、事件订阅和物理生命周期适配放入独立的 `create_nested_factory_simulated` 兼容包。这样未安装 Simulated 时，基础模组不会加载相关类；安装 Aeronautics 时则沿用 Aeronautics 已经要求的 Simulated 与 Sable 运行路径。

工厂身份、Pocket 房间和生产状态属于基础模组的持久化逻辑；SubLevel 只作为可移动物理载体。物理化、解体、旋转、拆分和世界重载都不得重新生成工厂身份或房间绑定。运行时 SubLevel 身份不写入工厂存档，而是在 SubLevel 生命周期中重建。

兼容包第一阶段只保证同一 SubLevel 内的 Create 动力、物品、流体、能量和工厂机制。普通 Create 机械装配继续将工厂视为不可移动；跨 SubLevel 与静止世界的实时连接另行通过物理边界适配器解决。绑定失效时应阻止迁移或使迁移失败，不得静默创建新工厂、重新分配房间或丢弃持久化状态。

## Considered Options

- 在主模组中声明 Simulated 为可选依赖，并直接放置兼容类：拒绝，因为元数据的 optional 声明不能阻止入口类、事件扫描或 Mixin 提前解析缺失类型。
- 将 Pocket 房间整体替换为 SubLevel：拒绝，因为 Pocket 负责持久化逻辑空间，SubLevel 负责物理位置与姿态，两者生命周期和所有权不同。
- 让 Aeronautics 成为单独的兼容目标：拒绝，因为当前 Aeronautics 已经依赖 Simulated 和 Sable，并与 Simulated 共享同一套 Sable 物理系统。
