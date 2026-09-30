# Create Nested Factory

[简体中文](#简体中文) | [English](#english)

> Build real factories inside persistent Pocket rooms, connect them through Create logistics, and turn verified production lines into nestable black-box factories.

当前正式版 / Latest stable release: **v1.5.11**

<a id="简体中文"></a>

## 简体中文

Create Nested Factory 是面向 Minecraft 1.21.1、NeoForge 和 Create 的自动化模组。它允许玩家在持久化的 Pocket 空间中建造真实产线，再通过物品、流体、包裹、应力、通道和黑盒生产，把复杂工厂封装成可以搬运、复制生产规则并继续嵌套的工厂方块。

### 运行要求

| 项目 | v1.5.11 支持版本 |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 |
| NeoForge | 开发和发布验证版本为 21.1.248；元数据允许 21.x |
| Create | `>= 6.0.9` 且 `< 6.1.0`；开发版本为 6.0.10-280 |

客户端和专用服务器应安装相同版本的本模组及必需依赖。

### v1.5.11 完整功能

#### Pocket 工厂与嵌套空间

- 创建具有永久工厂 ID 的 Pocket 房间；房间、机器、库存、生产状态和父子关系会随世界保存。
- 普通右键工厂打开控制界面，潜行右键进入工厂；在 Pocket 内对嵌套工厂使用相同操作。
- 控制界面支持工厂命名、六面端口配置、模式切换、实时输入/输出查看、烈焰电池和超频档位管理。
- 玩家进入 Pocket 后临时获得探索所需的飞行和夜视能力，离开或会话恢复时还原进入前状态。
- 支持在工厂房间内继续放置嵌套工厂，并保存根工厂、父工厂和嵌套深度关系。
- 每个房间最多拥有一个可进入的直接子工厂；已有子工厂时拒绝创建同层兄弟，避免父子身份歧义。
- 默认最大有房间嵌套深度为 8，可配置为 1–16；有房间的嵌套工厂使用固定 `16×16×16` 空间。
- 在最大有房间深度的下一层可放置“终端蓝图工厂”：它没有独立 Pocket 房间，只能运行已应用的蓝图，仍参与父子关系和冻结逻辑。
- 普通破坏工厂不会销毁 Pocket 空间，而是掉落携带原工厂身份和状态的单个绑定物品；重新合法放置后连接回同一房间。
- 根工厂只能在 Pocket 维度之外恢复放置；嵌套工厂只能回到原父工厂空间，不能借搬运更换层级或父工厂。
- 控制界面的永久清除流程会检查玩家、子工厂和房间状态，并以持久化后台任务清理真实空间。
- 玩家离线、服务器重启或返回路径失效时，会验证完整会话和返回栈；无法恢复时安全结束会话，并返回外部锚点或主世界出生点。
- 工厂身份与服务器实例隔离；坐标复用、区块卸载和方块实体重建不会把旧工厂状态绑定到新工厂。

#### 空间扩张、收缩与区块加载

- 根工厂房间可使用空间扩张机构或空间收缩机构，按玩家视线方向在上、下、北、南、西、东六个方向逐区块调整。
- 嵌套房间保持固定 `16×16×16`，不能扩张或收缩。
- 收缩前异步检查目标区域；存在方块、流体、实体或玩家时拒绝收缩，避免删除真实内容。
- 房间创建、验证、扩缩和销毁按每 tick 预算分批执行，并把任务保存到世界数据；服务器中断后可继续收敛，而不是留下半间房。
- 常加载模式为 Pocket 房间持有区块票；切换到黑盒/蓝图或进入祖先冻结时释放不再需要的票据。
- 无玩家的真实工厂仅在房间中存在需要随机刻的方块时按需申请随机刻票，支持树木和作物生长，同时避免让所有常加载房间持续执行随机刻。

#### 六面端口、普通端口与物流

- 工厂六个外壳面都可独立设置为 `NONE`、`INPUT` 或 `OUTPUT`。
- 多个外壳面可以映射到同一个逻辑端口 ID；端口合同按逻辑端口而不是物理朝向记录。
- Pocket 内的普通端口通过右键循环目标端口 ID，用于把真实房间中的物流连接到对应外壳面组。
- 支持物品、NeoForge 流体和完整 Create 包裹；包裹只会整包接受或拒绝，不会部分解包。
- Create 机械泵的流体压力可跨工厂边界映射到同组端口，实际流体数量仍由共享协调器守恒。
- 资源身份包含物品 Data Components/NBT 和流体组件；同注册 ID 但组件不同的资源不会错误合并。
- 常加载模式使用真实能力和所有权交接记录传输；黑盒与蓝图模式使用共享输入需求和共享输出托管。
- 黑盒/蓝图中，无歧义原料可从任意 `INPUT` 面提交；具有多条可能路线的原料仍遵守学习到的逻辑端口合同。
- 已提交产物可从任意 `OUTPUT` 面领取；修改外壳面不会复制资源、重置事务或重新生成产物。
- 没有有效内部端口、方向不匹配、事务已满或能力失效时会拒绝传输，不用隐藏缓存伪造成功。

#### 嵌套扩展接口

- 扩展接口在放置时绑定“工厂身份 + 被点击宿主面”，把该宿主面的同一个逻辑端口扩展到接口的五个外露面。
- 接口不创建新端口、不复制库存或合同；贴附面不暴露能力，并且扩展接口不能继续串联扩展。
- 每个工厂面最多由一个接口接管；接管后原外壳面停止直接提供物流和应力边界。
- 用 Create 扳手点击接口可循环宿主面的 `NONE → INPUT → OUTPUT → NONE`，模式仍由工厂宿主面唯一保存。
- 任意相邻红石信号会禁用五面物流和应力；解除信号后恢复。已被管道缓存的旧能力引用也会重新校验状态。
- 原工厂被搬走后接口保持原身份绑定，不会误连占据相同坐标的另一座工厂；原身份和邻接恢复后可重新启用。
- 五个外露面支持物品、流体、Create 包裹和应力；安装兼容模组后还支持 Mekanism Chemical 和 Heat。Heat 保持双向，Forge Energy/电力不在扩展集合内。

#### Create 应力与内部动力

- 应力端口在外部 Create 动力网络和 Pocket 内部网络之间中继转速与应力需求。
- 多个外部网络同时连接时，只选择剩余应力容量最大的一个有效网络，不合并多个网络的容量；相同网络从多面接入只计算一次。
- 内部端口保留各自网络的旋转方向，同时镜像所选外部转速大小。
- 内部需求为零时以零容量空转保持动力拓扑；来源消失或正需求无法完整满足时停止生产，不提交免费产物。
- 学习会记录外部负载、内部发电、总消耗、峰值、参考转速和应力时间；黑盒先用已验证的内部供给抵扣，再向外部网络预留净需求。
- 应力合同参与计划指纹、蓝图复制和运行校验，模拟运行不会重新扫描已冻结的真实房间。

#### 三窗口学习与来源认证

- 工厂模式完整覆盖：`CHUNK_LOADED`、`BLACKBOX_PREPARING`、`BLACKBOX_LEARNING`、`BLACKBOX_ACTIVE` 和 `BLUEPRINT`。
- 封口准备阶段结束旧能力交互并建立新的学习代次；它不要求把真实物流缓存清空。
- 所有普通、再生和混合工厂使用一次连续学习会话，并以三个互不重叠的观察区间统计投入、产出、房间净额、工具、来源和应力。
- 前两个等长区间建立候选计划，第三个区间确认连续运行；资源只需在任一区间出现，最终效率按完整证据计算。
- 没有完整产物时当前区间可自适应延长；整个会话受 `blackboxMaxLearningTicks` 绝对期限限制。
- 普通加工、再生来源和来源后加工可编译为加工路线、再生路线或来源耦合路线；一个组合计划最多包含一条加工路线和一条再生路线。
- 内建来源适配器支持圆石、玄武岩、树场和农场。树木按树苗种类分组，作物按方块注册 ID 分组，每组都必须达到配置的来源事件次数并产生对应边界输出。
- 第三方代码可通过 `RegenerativeSourceAdapter` 注册额外来源类型；无效或没有来源适配器授权的无输入生产不会成为永久配方。
- 来源事件授予有限产出额度；同身份外部输入与来源产出重叠、隐藏库存消耗或无法解释的房间净损耗不会被当作免费产量。
- 支持普通加工与独立再生路线的组合，也支持“内部来源 → 内部加工 → 最终边界产物”的耦合路线。
- 父工厂不会吸收嵌套黑盒/蓝图中的无输入路线；检测到这种结构时拒绝开始父级学习，避免重复计算再生生产权。
- 学习开始时固定本次超时和来源验证次数；运行中修改配置只影响下一次学习。
- 超时、证据不足或验证失败会清除临时证据并返回常加载模式；学习期间已经真实发生的资源变化不会回滚。
- 学习期间移动或拆除工厂会先中止未完成学习；已激活计划则可以携带完整事务状态迁移。

#### 计划、速率与黑盒运行

- 黑盒计划保存版本化的路线、完整资源身份、端口合同、时间、工具、启动资本、来源证明和应力合同，并用稳定指纹校验。
- 与最近正整数速率相差不超过 `0.2/s` 时贴合为该整数；否则保留实测有理速率。
- 小数速率会转换为最简整数资源向量和整数周期，实际批次只交接完整物品或完整流体。
- 每条路线使用“活动批次 + 预备批次”双批次流水线；活动批次运行或等待输出时，可继续为下一批接收原料。
- 两批都满时拒绝多余输入；外部库存或物流系统负责继续缓存，工厂不会无界增长内部队列。
- 输入只有在完整批次提交时消耗，产物只有在进度和应力条件完成后生成；批次提升、重启和能力重建不会复制资源。
- 输出空间不足时，成品保留在有界共享输出托管中并对生产施加背压；恢复空间后每份产物只提交一次。
- 缺料、缺工具、耐久不足或缺应力时暂停在安全状态；条件恢复后继续，不补算停机期间的免费批次。
- 黑盒激活前仍位于输入边界、且与计划投入匹配的真实资源会成为预付输入；学习前已由机器交出的产物作为历史输出优先交付。
- 可损坏工具以受托工具保存，并按学习到的每批耐久成本损伤；耐久不足时不启动新批次。
- 第三方来源可声明不可按批消耗的启动资本。蓝图只复制资本合同，目标工厂必须提交真实资本；内建圆石、玄武岩、树场和农场不要求重新提交其结构资源或种植物。
- 事务、预付输入、工具、资本、历史输出、活动/预备批次、进度和待领取产物均持久化，可在正常重启和绑定工厂搬运后恢复。
- 已提交随机结果和资源所有权会保存；尚未提交的随机批次不保存随机序列，因此在结算前读档可能得到不同随机结果。

#### 烈焰电池与机械超频

- 工厂提供四个按顺序解锁的烈焰电池槽，每槽只接受一块；移除时必须从最后一个已占用槽开始。
- 无电池时固定为 `1×`；第一块电池解锁 `0.5×` 与 `2×`，之后依次解锁 `3×`、`4×` 和 `5×`。
- 超频按档位缩放生产输入、输出节奏和生产负载；已学习的内部发电能力保持固定，新增净需求必须由外部网络满足。
- 超频只在黑盒和蓝图生产中生效。应用、取消或复制蓝图不会移动、复制或清除目标工厂已有的电池。
- 工厂被破坏时已安装电池随工厂状态安全处理，不会静默消失。

#### 蓝图

- 可从没有玩家停留、可进入且已激活的黑盒工厂复制生产蓝图。
- 蓝图保存生产合同、来源证明、应力、工具/资本要求和建议面布局，但不复制真实输入、工具、资本、在制批次或待领取输出。
- 潜行右键另一座兼容工厂应用蓝图；不能应用到蓝图来源本身，也不能覆盖尚未取消的现有蓝图。
- 蓝图模式不加载真实 Pocket 产线，直接按经过验证的合同运行；取消蓝图时按所有权规则结算或清理模拟状态。
- 终端蓝图工厂专门用于在最大房间深度之外继续封装生产。
- v1.5.11 使用当前计划/蓝图格式；缺少所有权、来源或应力证明的旧格式不会猜测迁移，必须重新学习并重新复制蓝图。

#### 工厂通道

- 工厂通道是两格高、两端可独立开关的玩家通行结构；打开后走入通道即可穿越，在 Pocket 内也可潜行右键主动通行。
- 手持通道物品右键一个工厂可临时选择目标，再在任意位置放置外侧端点；选择保存在玩家会话中，不写入物品 NBT。
- 在目标 Pocket 房间内未选择目标放置的通道作为内侧端点；同一房间最早放置且仍有效的端点是主内侧端点，移除后由下一个接替。
- 一个工厂可有多个外侧入口，但只使用一个主内侧端点。经外侧入口进入的玩家会返回自己的来源入口，不会被其他入口劫持。
- 没有内侧端点时使用工厂默认进入位置；普通进入后的返回会选择最早仍有效的外侧端点。
- 通道只在目标工厂处于常加载且满足正常进入条件时工作；目标缺失、冻结、黑盒化或绑定歧义时拒绝通行。
- 允许自环、双向环和多工厂循环路线；玩家级防重入保护阻止同一次碰撞在两端之间无限弹跳。
- 通道、所属工厂或目标工厂位于 Simulated/Sable 物理化结构时暂时禁用，避免静态坐标传送到移动目标。
- 客户端提供通道目标标记、高亮和状态提示；端点索引持久化，并使用身份索引查找目标而不是扫描并强制加载无关区块。

#### 冻结、恢复和移动结构

- 根工厂进入黑盒或蓝图模式时，为整棵嵌套工厂树建立持久化冻结租约。
- 后代记录祖先冻结状态并释放 Pocket 区块票；真实空间中的方块实体 tick、计划刻和方块事件在冻结期间停止。
- 解冻时按保存的树快照和事件队列恢复；重启、部分区块卸载和嵌套层级不会让后代提前运行或永久冻结。
- 普通拆除和重新放置保留工厂身份、房间绑定、计划、事务和托管资源。
- 安装 Simulated 与 Sable 后，工厂可随 SubLevel 结构移动、旋转、装配和解体，同时保持逻辑身份与 Pocket 绑定。
- 移动开始前中止未完成学习；已激活黑盒在空闲、运行和输出托管阶段都迁移同一份所有权状态。
- 移动结构保存动态返回锚点；结构仍在加载时返回操作会要求稍后重试，不会传送到过期坐标。

#### 界面、提示、语言与服务器

- 提供工厂控制界面、六面模式按钮、改名、模式切换、永久清除、超频滑块和四个电池槽。
- Create 工程师护目镜显示工厂身份、模式、深度、学习状态、输入/输出、内外应力、端口目标、扩展接口绑定和通道状态。
- 工厂物品与蓝图 Tooltip 显示身份、来源工厂、来源位置/维度/深度、效率、输入输出、应力和启动资本。
- 内置简体中文和英文资源，注册独立创造模式标签、方块模型、物品模型、配方、战利品表和配方解锁进度。
- 客户端渲染、标记和界面注册均有逻辑侧隔离；支持专用服务器运行。
- 高频黑盒诊断默认关闭；需要排查学习、事务、应力或所有权问题时可开启结构化 `[CNF-BLACKBOX]` 日志。

### 可选兼容

| 模组组合 | v1.5.11 支持内容 |
|---|---|
| 不安装可选模组 | 完整基础 Pocket、端口、应力、学习、黑盒、蓝图、冻结和通道功能 |
| Mekanism 10.7+ | 工厂面与扩展接口的 Chemical 和 Heat capability；Heat 双向，Chemical 遵守端口方向 |
| Pipez | Pipez 流体网络搜索、压力/端点适配；支持仅安装 Pipez、仅安装 Mekanism 或两者同时安装 |
| Simulated 1.0+ 与 Sable 2.x | SubLevel 移动、旋转、身份迁移、接口绑定、通道禁用和动态返回锚点；该集成需要两者同时存在 |
| Aeronautics 共存 | 沿用其 Simulated/Sable 物理系统路径，不建立第二套工厂身份 |

可选模组不存在时，对应兼容类不会初始化，基础模组不依赖其类加载。

### 配置项

配置文件由 NeoForge 生成。学习开始时会快照与本次会话有关的配置。

| 配置键 | 默认值 | 范围 | 作用 |
|---|---:|---:|---|
| `maxNestingDepth` | 8 | 1–16 | 拥有真实 Pocket 房间的最大嵌套深度 |
| `roomMutationBlocksPerTick` | 16384 | 64–65536 | 每 tick 房间任务最多检查或写入的方块数 |
| `blackboxMaxLearningTicks` | 6000 | 2–2147483647 | 一次统一黑盒学习的绝对最大 tick 数 |
| `cobblestoneSourceVerificationCycles` | 3 | 3–5 | 圆石来源所需完整事件数 |
| `basaltSourceVerificationCycles` | 3 | 3–5 | 玄武岩来源所需完整事件数 |
| `treeSourceVerificationCycles` | 3 | 3–5 | 每个树木来源组所需完整事件数 |
| `cropSourceVerificationCycles` | 3 | 3–5 | 每个作物来源组所需完整事件数 |
| `blackboxDebugLogging` | `false` | 布尔值 | 输出结构化黑盒学习、运行、应力与所有权诊断日志 |

### 注册内容与制作链

方块和设备：

- 嵌套工厂、普通端口、嵌套扩展接口、应力端口、双层工厂通道。
- Pocket 内部雪墙和白色混凝土墙。
- 空间扩张机构和空间收缩机构。

材料和中间产物：

- 坚固合金锭、坚固合金板、浸泡坚固合金锭、浸泡坚固合金板。
- 坚固机壳、电池机壳、未激活烈焰电池、烈焰电池。
- 用于 Create 序列装配的未完成机壳、板材、扩缩机构和烈焰电池中间物。

数据包配方包含普通合成、混合、洗涤和 Create 序列装配链；创造模式标签只展示最终设备、工具和主要材料，中间装配物仍已注册供配方使用。

### 安装

1. 安装 Minecraft 1.21.1、Java 21 和 NeoForge 21.1.x。
2. 安装 Create 6.0.9–6.0.x。
3. 从 [Releases](https://github.com/kevindx1231/create_nested_factory/releases) 下载 `create_nested_factory-1.5.11.jar`。
4. 把 JAR 放入客户端和服务器的 `mods` 目录；不要同时保留本模组的其他版本。
5. 如需兼容功能，再安装上表中的可选模组。

升级既有世界前请备份存档。旧计划或旧蓝图因安全格式不兼容而失效时，应进入真实空间重新学习，而不是修改 NBT 强制恢复。

### 快速开始

1. 放置嵌套工厂，普通右键打开界面并命名，潜行右键进入 Pocket。
2. 在根房间内使用扩张/收缩机构调整空间；放置普通端口和应力端口，并用工厂界面设置六面模式和逻辑端口。
3. 建造真实 Create 产线，让物品、流体、包裹和动力完整穿过工厂边界。
4. 在真实模式下稳定运行后切换黑盒，等待三个观察区间和所有来源事件完成。
5. 黑盒激活后验证原料、应力、工具和输出；需要时安装烈焰电池并选择 `0.5×`–`5×` 档位。
6. 从已验证工厂复制蓝图并应用到另一座工厂，或使用工厂通道连接不同位置和嵌套层级。

### 设计与测试文档

- [领域规则与持久化约束](CONTEXT.md)
- [Simulated/Sable 兼容规则](CONTEXT-simulated.md)
- [架构决策记录](docs/adr/)
- [黑盒 v9 手动验收清单](docs/blackbox-v9-manual-test-matrix.md)
- [嵌套扩展接口手动测试矩阵](docs/nested-extension-interface-manual-test-matrix.md)

### 从源码构建

仓库包含 Gradle Wrapper，需要 Java 21。

Windows：

```powershell
.\gradlew.bat check build --stacktrace
```

Linux/macOS：

```bash
./gradlew check build --stacktrace
```

正式 JAR 输出到 `build/libs/create_nested_factory-1.5.11.jar`。`check` 会运行通道、防重入、冻结索引、服务器隔离、学习控制器、房间几何、生命周期转换、蓝图资本和发布修复回归任务。

### 问题反馈

请在 [GitHub Issues](https://github.com/kevindx1231/create_nested_factory/issues) 提交问题，并附上：

- `latest.log` 和相关崩溃报告；
- 单人游戏或专用服务器环境；
- Minecraft、NeoForge、Create 和可选兼容模组版本；
- 可重复的方块结构、操作步骤、预期结果和实际结果；
- 是否从旧版本升级，以及问题能否在新世界复现。

### 许可

Copyright © 2026 Kevin_Dish1231. All Rights Reserved.

本仓库公开用于源码查看和问题定位。除非版权所有者另行书面授权，否则不授予复制、修改、再发布或分发许可。详见 [LICENSE](LICENSE)。

[返回顶部](#create-nested-factory) | [English](#english)

---

<a id="english"></a>

## English

Create Nested Factory is an automation mod for Minecraft 1.21.1, NeoForge, and Create. It lets players build real production lines inside persistent Pocket spaces, then package those lines into portable and nestable factory blocks through item, fluid, package, stress, passage, and black-box systems.

### Requirements

| Component | Supported by v1.5.11 |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 |
| NeoForge | Developed and release-tested on 21.1.248; metadata accepts NeoForge 21.x |
| Create | `>= 6.0.9` and `< 6.1.0`; development version 6.0.10-280 |

Clients and dedicated servers should install the same version of this mod and all required dependencies.

### Complete v1.5.11 feature set

#### Pocket factories and nested spaces

- Creates Pocket rooms with permanent factory IDs. Rooms, machines, inventories, production state, and parent-child relationships persist with the world.
- Right-click a factory to open its control screen; sneak-right-click to enter it. The same interaction model applies to nested factories inside a Pocket.
- The control screen supports naming, six-face port configuration, mode switching, live input/output rates, Blaze Battery slots, and overclock selection.
- Players temporarily receive flight and night vision for Pocket exploration. Their previous abilities are restored when they leave or when a session is recovered.
- Factories can be placed inside factory rooms, with persistent root, parent, and nesting-depth relationships.
- Each room may have at most one enterable direct child factory. A second sibling at the same level is rejected to keep parent-child identity unambiguous.
- The default maximum room-owning nesting depth is 8 and can be configured from 1 to 16. Nested factories with rooms use fixed `16×16×16` spaces.
- One layer beyond the room depth limit may contain a terminal blueprint-only factory. It owns no Pocket room, runs only an applied blueprint, and still participates in hierarchy and freeze logic.
- Normally breaking a factory preserves its Pocket. It drops one bound item carrying the factory identity and state; legal replacement reconnects to the same room.
- Root factories can only be restored outside the Pocket dimension. Nested factories can only return to their original parent space and cannot be moved to change parent or hierarchy level.
- The permanent-clear action in the control screen validates players, child factories, and room state, then cleans the physical room through a persistent background task.
- Offline players, server restarts, and invalid return paths are handled by validating the complete session and return stack. Failed recovery safely ends the session and returns the player to an external anchor or the Overworld spawn.
- Factory identities are scoped to the current server. Coordinate reuse, chunk unloads, and block-entity reconstruction do not bind a new factory to stale state.

#### Room expansion, collapse, and chunk loading

- Root rooms can use Space Expand and Space Collapse Mechanisms to add or remove one chunk in any of the six directions selected by the player's view.
- Nested rooms remain fixed at `16×16×16` and cannot be expanded or collapsed.
- Collapse validates the target area asynchronously. Blocks, fluids, entities, or players in that area prevent removal, protecting real contents.
- Room construction, validation, resizing, and destruction are processed in per-tick batches and persisted in world data. Interrupted servers resume toward a complete state instead of leaving half-mutated rooms.
- Chunk-loaded mode keeps the Pocket room ticketed. Black-box, blueprint, and ancestor-frozen states release tickets they no longer need.
- Empty real factories request random-tick tickets only while their rooms contain blocks that need random ticks. This supports trees and crops without continuously random-ticking every loaded factory.

#### Six-sided ports, room ports, and logistics

- Each of the six shell faces independently supports `NONE`, `INPUT`, or `OUTPUT`.
- Multiple shell faces may map to the same logical port ID. Port contracts use logical port identity rather than physical direction.
- Right-clicking an internal Nested Port cycles its target port ID, connecting Pocket logistics to the corresponding shell-face group.
- Supports items, NeoForge fluids, and complete Create packages. Packages are accepted or rejected atomically and are never partially unpacked.
- Create mechanical-pump pressure is mapped across the factory boundary to grouped ports, while actual fluid quantities remain conserved by one shared coordinator.
- Resource identity includes item Data Components/NBT and fluid components. Resources with the same registry ID but different components remain distinct.
- Chunk-loaded mode uses real capabilities and ownership handoff events. Black-box and blueprint modes expose shared input demand and shared output escrow.
- In simulation modes, unambiguous ingredients may enter through any `INPUT` face. Ingredients that could belong to several routes still obey their learned logical-port contract.
- Committed products may be collected from any `OUTPUT` face. Changing shell faces does not duplicate resources, reset transactions, or regenerate products.
- Transfers are rejected when no valid room port exists, direction is wrong, both transaction slots are full, or a cached capability is invalid. No hidden cache reports false success.

#### Nested Extension Interfaces

- On placement, an interface binds to a specific factory identity and clicked host face. It exposes the same logical port through its five outward faces.
- It creates no new port, inventory, or contract. The attached face exposes no capability, and interfaces cannot be chained.
- At most one interface takes over a factory face. Once claimed, the original shell face stops exposing logistics and stress directly.
- Using a Create wrench cycles the host face through `NONE → INPUT → OUTPUT → NONE`; the host factory remains the single owner of the mode.
- Any adjacent redstone signal disables all five logistics faces and stress relay. Cached handlers revalidate the enabled state, and functionality returns when the signal is removed.
- Moving the factory away does not let the interface bind to another factory placed at the same coordinates. The interface resumes only when its original identity and adjacency return.
- The five outward faces support items, fluids, Create packages, and stress. With optional integrations installed they also support Mekanism Chemical and Heat. Heat remains bidirectional; Forge Energy/electricity is not part of this interface.

#### Create stress and internal power

- Stress Ports relay rotation and stress demand between an external Create network and internal Pocket networks.
- When several external networks are connected, the factory selects the valid network with the greatest remaining stress capacity. Capacities are not combined, and multiple faces connected to the same network are counted once.
- Internal ports preserve the rotation direction of their own networks while mirroring the magnitude of the selected external speed.
- A zero-demand network idles with zero capacity to preserve topology. Missing sources or insufficient capacity for a positive demand stop production without committing free products.
- Learning records external demand, internal generation, total consumption, peaks, reference speed, and stress time. Black-box production deducts verified internal supply before reserving net demand externally.
- Stress contracts participate in plan fingerprints, blueprint copying, and runtime validation. Simulation never rescans a frozen physical room for power.

#### Three-window learning and certified sources

- The complete mode set is `CHUNK_LOADED`, `BLACKBOX_PREPARING`, `BLACKBOX_LEARNING`, `BLACKBOX_ACTIVE`, and `BLUEPRINT`.
- Preparing seals old capability interactions and creates a new learning generation. It does not wait for every real transit buffer to become empty.
- Ordinary, regenerative, and mixed factories share one continuous learning session with three non-overlapping observation windows for inputs, outputs, room deltas, tools, sources, and stress.
- The first two equal windows create a candidate plan; the third confirms continuous operation. A resource may appear in any window, while final efficiency uses the complete evidence set.
- A window may extend adaptively when no complete product has appeared. The entire session is bounded by `blackboxMaxLearningTicks`.
- Ordinary processing, regenerative sources, and downstream source processing compile into processing, regenerative, or source-coupled routes. A combined plan contains at most one processing route and one regenerative route.
- Built-in source adapters cover cobblestone, basalt, tree farms, and crop farms. Trees are grouped by sapling type and crops by block registry ID; every group must meet its configured event count and produce matching boundary output.
- Third-party code may register additional source types through `RegenerativeSourceAdapter`. Invalid sources or material-free production without adapter-certified evidence cannot become permanent recipes.
- Source events grant limited output credit. Overlapping external input, hidden inventory consumption, and unexplained room losses are not treated as free production.
- Plans may combine an ordinary processing line with an independent regenerative route, or learn a coupled route such as internal source → internal processing → final boundary product.
- A parent factory refuses to absorb a nested black-box or blueprint route with no material input, preventing duplicated regenerative production rights.
- Learning snapshots its timeout and required source event counts at session start. Configuration changes during learning affect only the next session.
- Timeout, insufficient evidence, or validation failure clears temporary evidence and returns to chunk-loaded mode. Real resource changes that occurred during learning are not rolled back.
- Moving or breaking a factory during learning aborts the incomplete session first. An already activated plan migrates with its full transaction state.

#### Plans, rates, and black-box runtime

- A versioned plan stores routes, complete resource identities, port contracts, time, tools, startup capital, source evidence, and stress contracts, protected by a stable fingerprint.
- A measured positive rate within `0.2/s` of its nearest integer snaps to that integer; otherwise the measured rational rate is preserved.
- Fractional rates are converted into the smallest integer resource vector and integer period, so runtime batches transfer only whole items or fluid units.
- Every route uses a two-stage active/prepared pipeline. While the active batch runs or waits for output, inputs may fill one prepared batch.
- When both batches are full, extra input is rejected and must remain in external storage. The factory does not grow an unbounded internal queue.
- Inputs are consumed only when a complete batch commits, and outputs are created only after progress and stress requirements finish. Batch promotion, reloads, and capability reconstruction cannot duplicate ownership.
- When output is blocked, completed products remain in bounded shared output escrow and apply backpressure. Once space returns, each product is committed exactly once.
- Missing ingredients, missing tools, insufficient durability, or insufficient stress pause safely. Production resumes when the condition is restored and does not award batches for downtime.
- Matching real resources still present at the input boundary when black-box mode activates become prepaid inputs. Products already handed out by machines before activation remain historical output and are delivered first.
- Damageable tools are held privately and lose the learned durability cost per batch. A new batch will not start if remaining durability is insufficient.
- Third-party sources may require real startup capital that is retained rather than consumed per batch. Blueprints copy only the contract; built-in cobblestone, basalt, tree, and crop sources do not require their structural materials or plants to be resubmitted.
- Transactions, prepaid input, tools, capital, historical output, active/prepared batches, progress, and pending output are persisted across normal restarts and bound-factory movement.
- Committed random results and ownership are persistent. The random sequence of an uncommitted batch is intentionally not persisted, so reloading before settlement may produce a different random result.

#### Blaze Batteries and mechanical overclocking

- A factory has four ordered Blaze Battery slots, one battery per slot. Batteries must be removed from the last occupied slot first.
- With no battery, speed is fixed at `1×`. The first battery unlocks `0.5×` and `2×`; additional batteries unlock `3×`, `4×`, and `5×` in order.
- Overclock tiers scale production input/output timing and production load. Verified internal generation remains fixed, so additional net demand must be supplied externally.
- Overclocking applies only to black-box and blueprint production. Applying, cancelling, or copying a blueprint does not move, copy, or clear batteries already installed in the target factory.
- Installed batteries are handled with factory state when the block is broken and are not silently lost.

#### Blueprints

- A production blueprint can be copied from an enterable, activated black-box factory while no player remains inside.
- It stores the production contract, source evidence, stress, tool/capital requirements, and suggested face layout, but never copies real inputs, tools, capital, in-flight batches, or pending output.
- Sneak-right-click another compatible factory to apply it. A blueprint cannot target its source factory or overwrite an existing blueprint until that mode is cancelled.
- Blueprint mode runs the verified contract without loading the physical Pocket line. Cancelling it settles or clears simulated state according to ownership rules.
- Terminal blueprint-only factories allow one additional encapsulation layer beyond the maximum room depth.
- v1.5.11 uses the current plan and blueprint format. Older formats without ownership, source, or stress evidence are not guessed or migrated; relearn and recopy them.

#### Factory Passages

- A Factory Passage is a two-block-high player doorway whose two endpoints have independent open/closed switches. Walk into an open passage to travel; inside a Pocket, sneak-right-click may also trigger travel.
- Right-click a factory while holding the passage item to select it temporarily, then place an outer endpoint anywhere. The selection belongs to the player session and is not written to item NBT.
- A passage placed without a selected target inside that target Pocket becomes an inner endpoint. The earliest still-valid inner endpoint is the primary one; removing it promotes the next oldest endpoint.
- A factory may have multiple outer entrances but only one primary inner endpoint. Players entering through an outer endpoint return to their own source endpoint instead of another entrance.
- Without an inner endpoint, entry uses the factory's default destination. A normal factory entry returns through the earliest valid outer endpoint when available.
- Passage travel works only while the target factory is chunk-loaded and passes the normal entry checks. Missing, frozen, simulated, or ambiguous targets reject travel.
- Self-loops, two-way loops, and larger factory cycles are allowed. A per-player re-entry guard prevents one collision from bouncing indefinitely between endpoints.
- A passage, owning factory, or target factory on a Simulated/Sable physical structure is temporarily disabled to avoid teleporting to stale static coordinates.
- Client markers, highlights, and status messages expose passage targets. A persistent identity index resolves endpoints without scanning every passage or force-loading unrelated candidate chunks.

#### Freeze, recovery, and moving structures

- When a root factory enters black-box or blueprint mode, it creates a persistent freeze lease for its complete nested factory tree.
- Descendants record ancestor freeze state and release Pocket chunk tickets. Block-entity ticks, scheduled ticks, and block events in their physical spaces stop while frozen.
- Thawing restores the saved tree snapshot and event queues. Restarts, partially unloaded chunks, and deep nesting do not let descendants run early or remain permanently frozen.
- Normal breaking and replacement preserve factory identity, Pocket binding, plans, transactions, and held resources.
- With Simulated and Sable installed, factories may move, rotate, assemble, and disassemble with a SubLevel structure while retaining logical identity and Pocket binding.
- Movement aborts incomplete learning first. Activated black-boxes migrate the same ownership state while idle, running, or holding blocked output.
- Moving structures store dynamic return anchors. If the structure is still loading, return travel asks the player to retry instead of using a stale coordinate.

#### UI, tooltips, languages, and server support

- Includes a factory control screen with six face-mode buttons, rename, mode toggle, permanent clear, overclock slider, and four battery slots.
- Create Engineer's Goggles show factory identity, mode, depth, learning state, input/output, internal/external stress, port target, interface binding, and passage status.
- Factory items and blueprints show identity, source factory, source position/dimension/depth, efficiency, I/O, stress, and startup capital in their tooltips.
- Includes Simplified Chinese and English resources, a dedicated creative tab, block/item models, recipes, loot tables, and recipe advancements.
- Client rendering, markers, and screens are side-gated, and the mod supports dedicated servers.
- High-frequency black-box diagnostics are disabled by default. Enable them when structured `[CNF-BLACKBOX]` learning, runtime, stress, and ownership logs are needed.

### Optional compatibility

| Mod combination | v1.5.11 integration |
|---|---|
| No optional mods | Complete base Pocket, port, stress, learning, black-box, blueprint, freeze, and passage features |
| Mekanism 10.7+ | Chemical and Heat capabilities on factory faces and extension interfaces; Heat is bidirectional and Chemical follows port direction |
| Pipez | Pipez fluid-network search plus pressure/endpoint adaptation; Pipez-only, Mekanism-only, and combined installations are supported |
| Simulated 1.0+ and Sable 2.x | SubLevel movement, rotation, identity migration, interface binding, passage disabling, and dynamic return anchors; both mods are required for this integration |
| Aeronautics coexistence | Uses its existing Simulated/Sable physics path instead of creating a second factory identity system |

When an optional mod is absent, its compatibility classes are not initialized and the base mod does not load that API.

### Configuration

NeoForge generates the configuration file. Values relevant to a learning session are snapshotted when that session begins.

| Key | Default | Range | Purpose |
|---|---:|---:|---|
| `maxNestingDepth` | 8 | 1–16 | Maximum nesting depth that owns a real Pocket room |
| `roomMutationBlocksPerTick` | 16384 | 64–65536 | Maximum block checks or writes performed by room tasks each tick |
| `blackboxMaxLearningTicks` | 6000 | 2–2147483647 | Absolute maximum duration of one unified learning session |
| `cobblestoneSourceVerificationCycles` | 3 | 3–5 | Complete events required for a cobblestone source |
| `basaltSourceVerificationCycles` | 3 | 3–5 | Complete events required for a basalt source |
| `treeSourceVerificationCycles` | 3 | 3–5 | Complete events required for each tree source group |
| `cropSourceVerificationCycles` | 3 | 3–5 | Complete events required for each crop source group |
| `blackboxDebugLogging` | `false` | Boolean | Writes structured black-box learning, runtime, stress, and ownership diagnostics |

### Registered content and crafting chain

Blocks and devices:

- Nested Factory, Nested Port, Nested Extension Interface, Nested Stress Port, and the two-block Factory Passage.
- Snow Wall and White Concrete Wall used inside Pocket spaces.
- Space Expand Mechanism and Space Collapse Mechanism.

Materials and intermediate products:

- Sturdy Alloy Ingot, Sturdy Alloy Sheet, Soaked Sturdy Alloy Ingot, and Soaked Sturdy Alloy Sheet.
- Sturdy Casing, Battery Casing, Inactive Blaze Battery, and Blaze Battery.
- Incomplete casings, sheets, room mechanisms, and Blaze Batteries used by Create sequenced assembly.

Data-pack recipes cover ordinary crafting, mixing, splashing, and Create sequenced assembly. The creative tab lists final devices, tools, and primary materials; incomplete assembly items remain registered for recipe processing.

### Installation

1. Install Minecraft 1.21.1, Java 21, and NeoForge 21.1.x.
2. Install Create 6.0.9–6.0.x.
3. Download `create_nested_factory-1.5.11.jar` from [Releases](https://github.com/kevindx1231/create_nested_factory/releases).
4. Put the JAR in the `mods` directory on both client and server. Do not keep another version of Create Nested Factory in the same instance.
5. Install any optional mods required for the integrations listed above.

Back up an existing world before upgrading. If an old plan or blueprint is rejected because its safety format is obsolete, relearn it in the physical room instead of forcing NBT migration.

### Quick start

1. Place a Nested Factory, right-click to open and name it, then sneak-right-click to enter its Pocket.
2. Resize a root room with the expansion/collapse mechanisms. Place Nested Ports and Stress Ports, then configure all shell faces and logical ports in the factory screen.
3. Build and stabilize a real Create production line whose items, fluids, packages, and power cross the factory boundary.
4. Switch to black-box mode and wait for all three observation windows and required source events to finish.
5. After activation, verify ingredients, stress, tools, and output. Add Blaze Batteries if `0.5×`–`5×` operation is needed.
6. Copy the verified plan to another factory with a blueprint, or connect locations and nesting levels with Factory Passages.

### Design and test documentation

- [Domain rules and persistence constraints](CONTEXT.md)
- [Simulated/Sable compatibility rules](CONTEXT-simulated.md)
- [Architecture Decision Records](docs/adr/)
- [Black-box v9 manual acceptance checklist](docs/blackbox-v9-manual-test-matrix.md)
- [Nested Extension Interface manual test matrix](docs/nested-extension-interface-manual-test-matrix.md)

### Building from source

The repository includes the Gradle Wrapper and requires Java 21.

Windows:

```powershell
.\gradlew.bat check build --stacktrace
```

Linux/macOS:

```bash
./gradlew check build --stacktrace
```

The release JAR is written to `build/libs/create_nested_factory-1.5.11.jar`. `check` runs passage/re-entry, freeze-index, server-isolation, learning-controller, room-geometry, lifecycle-transition, blueprint-capital, and release-fix regression tasks.

### Reporting issues

Open an issue at [GitHub Issues](https://github.com/kevindx1231/create_nested_factory/issues) and include:

- `latest.log` and relevant crash reports;
- whether the problem occurred in single-player or on a dedicated server;
- Minecraft, NeoForge, Create, and optional integration versions;
- a reproducible block layout, exact steps, expected result, and actual result;
- whether the world was upgraded and whether the issue reproduces in a new world.

### License

Copyright © 2026 Kevin_Dish1231. All Rights Reserved.

This repository is public for source inspection and issue diagnosis. No permission to copy, modify, republish, or distribute is granted without separate written authorization from the copyright owner. See [LICENSE](LICENSE).

[Back to top](#create-nested-factory) | [简体中文](#简体中文)
