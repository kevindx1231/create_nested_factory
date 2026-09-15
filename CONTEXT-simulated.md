# Simulated Compatibility Context

本上下文描述 create_nested_factory 与 Simulated、Sable 及 Aeronautics 共存时使用的领域语言和边界。

## Spaces

**Pocket 房间**:
工厂所拥有的持久化逻辑空间，保存房间方块、机器状态、库存和嵌套关系。Pocket 房间的身份不因工厂的物理移动而改变。

**SubLevel 物理载体**:
由 Sable 管理的可移动结构空间，承载实际参与物理运动的方块、BlockEntity 和实体。它描述物理位置与姿态，不拥有工厂的逻辑身份。

**物理化**:
将一组方块纳入 SubLevel，使其成为可受物理系统驱动的结构。

**物理边界**:
SubLevel 与外部静止世界之间的连接边界。动力、物流和流体跨越该边界时，需要明确的坐标和能力转换。

## Identity

**工厂身份**:
唯一标识一个工厂及其持久化状态的稳定身份。工厂身份独立于世界坐标、SubLevel 和当前方块实例。

**房间绑定**:
工厂身份与其 Pocket 房间之间的稳定关系。绑定关系不会因装配、解体、旋转或世界重载而改变。

**逻辑端口**:
工厂与 Pocket 房间之间传递物品、流体、能量或应力的逻辑接口。逻辑端口的位置可以变化，但所属工厂身份不变。

## Connections

**内部连接**:
完全位于同一个 SubLevel 内的动力、物品、流体或能量连接。第一阶段的物理化兼容以内部连接为主要保证范围。

**外部连接**:
连接 SubLevel 与静止世界的动力、物品、流体或能量连接。外部连接必须经过物理边界适配，不等同于普通局部坐标查询。

**物理载体拆分**:
同一工厂的相关方块被纳入不同 SubLevel 的状态。拆分不改变工厂身份和房间绑定。

## Modules

**基础模组**:
不依赖 Simulated、Sable 或 Aeronautics 的 create_nested_factory 主模组，提供普通 Pocket 工厂功能。

**Simulated 兼容包**:
独立加载的可选模组，提供 create_nested_factory 对 Sable SubLevel 和 Simulated 物理生命周期的适配。

**航空学共存路径**:
Simulated、Sable 和 Aeronautics 同时存在时的运行路径。Aeronautics 与 Simulated 共享 Sable 物理系统，不构成另一套物理载体。
