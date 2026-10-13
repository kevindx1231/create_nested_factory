# 1.21.1 同类问题修复指南

日期：2026-10-13。依据当前 1.20.1 分支对审查第 1、2、3、6 项完成的修复及回归验证。

本次没有修改 `D:\Program\create_nested_factory` 的 1.21.1 项目。本文给出需要移植的逻辑、可直接参考的实现和验证要求；文中的 1.21.1 示例尚未在原项目编译或启动游戏。

## 适用范围

| 检查项 | 1.20.1 实现位置 | 1.21.1 对应位置与适用性 |
| --- | --- | --- |
| 1：指定类型提取扣除错误资源 | `MekanismGasHandler` / `MekanismInfusionHandler` / `MekanismPigmentHandler` / `MekanismSlurryHandler` | `MekanismChemicalHandler`，必须修复 |
| 2：缓存与机器不同类型时丢弃补取结果 | 上述四个处理器的提取与合并路径 | `MekanismChemicalHandler.extractFromRoom` / `extractFromExternal` / `merge`，必须修复 |
| 3：游戏模式变化后恢复错误飞行权限 | `NestedFactoryBlock` / `ModAttachments` / `PocketEvents` | 属于 1.20.1 使用原版 `Abilities` 的适配问题；不能直接覆盖 1.21.1 的飞行属性实现 |
| 6：处理器分配、扩展委托和热量端点重复解析 | `LegacyCapabilities`、扩展代理、`MekanismHeatHandler` | 扩展委托和热量优化可移植；Forge `LazyOptional` 缓存实现不可照搬到 NeoForge 1.21.1 |

物流文件均位于 `logistics-compat/src/main/java/com/createnestedfactory/create_nested_factory_logistics/` 下。核心文件位于 `src/main/java/com/createnestedfactory/create_nested_factory/` 下。

## 一、化学品资源守恒

### 原因与失败证据

旧的 `extractChemical(ChemicalStack, Action)` 先调用按数量提取，再比较类型。请求氢气 10 时，可以实际扣除氧气 10，随后返回空结果。

旧的缓存补取路径先执行两次提取，再用 `merge()` 比较类型。当缓存和机器提供不同化学品时，第二份已扣除的资源被忽略。只改 `merge()`，或在已提取后把不匹配结果改为空，均不能修复资源守恒。

1.20.1 回归在修复前复现：四种化学品、内侧和外侧端口均出现 `buffered=40`（原为 50，错误类型请求 10）或 `source=0`（不同类型机器的 50 被扣除，却没有返回）。

### 必须保留的规则

1. 一次提取只允许一种资源类型。
2. 指定类型请求在扣除前验证缓存首项；不匹配立即返回空，保留现有单槽 FIFO 展示与缓存顺序。
3. 按数量请求优先使用缓存首项；缓存为空时通过 `SIMULATE` 选择一个可提取的来源类型。
4. 补取必须调用外部机器的**指定类型**提取方法，数量限制为剩余需求。
5. `SIMULATE` 不扣除资源、不调用 `setChanged()`、不新增真实传输或学习证据。
6. 所有返回数量以实际提取为准，总量不得大于请求量。
7. 对外领取历史输出缓存不重复记录生产来源；真实机器输出和房间侧领取输入仍按原边界规则记账。

### 1.21.1 的替换实现

在 `chemical/MekanismChemicalHandler.java` 中保留插入逻辑、端口方向、能力注册与邻接来源发现。用以下入口和共享流程替换原来的提取路径。

```java
private boolean canExtract(int tank) {
    return tank == 0 && active() && (externalSide != isInput());
}

@Override
public ChemicalStack extractChemical(int tank, long amount, Action action) {
    return amount <= 0 || !canExtract(tank)
            ? ChemicalStack.EMPTY : extract(amount, null, action);
}

@Override
public ChemicalStack extractChemical(long amount, Action action) {
    return extractChemical(0, amount, action);
}

@Override
public ChemicalStack extractChemical(ChemicalStack stack, Action action) {
    return stack.isEmpty() || !canExtract(0)
            ? ChemicalStack.EMPTY : extract(stack.getAmount(), stack, action);
}

private ChemicalStack extract(long amount, ChemicalStack requested, Action action) {
    ChemicalLedger buffer = ledger();
    String key = ledgerKey();
    ChemicalStack buffered = buffer.peek(key);
    if (requested != null && !buffered.isEmpty()
            && !ChemicalStack.isSameChemical(buffered, requested)) {
        return ChemicalStack.EMPTY;
    }

    List<IChemicalHandler> sources = null;
    if (requested == null) {
        requested = buffered;
        if (requested.isEmpty()) {
            sources = externalSide ? chemicalsInRoom() : chemicalsOutside();
            for (IChemicalHandler source : sources) {
                requested = source.extractChemical(amount, Action.SIMULATE);
                if (!requested.isEmpty()) break;
            }
        }
        if (requested.isEmpty()) return ChemicalStack.EMPTY;
    }

    ChemicalStack result = buffered.isEmpty()
            ? ChemicalStack.EMPTY : buffer.extract(key, amount, action);
    long remaining = amount - result.getAmount();
    boolean sourceTransferred = false;
    if (remaining > 0) {
        if (sources == null) sources = externalSide ? chemicalsInRoom() : chemicalsOutside();
        for (IChemicalHandler source : sources) {
            ChemicalStack taken = source.extractChemical(requested.copyWithAmount(remaining), action);
            if (taken.isEmpty()) continue;
            if (!ChemicalStack.isSameChemical(taken, requested) || taken.getAmount() > remaining) {
                throw new IllegalStateException("Chemical source violated its extraction contract");
            }
            result = requested.copyWithAmount(result.getAmount() + taken.getAmount());
            sourceTransferred = true;
            break;
        }
    }
    if (action.execute() && !result.isEmpty()) {
        if (sourceTransferred || !externalSide) buffer.markBoundaryTransfer();
        factory.setChanged();
    }
    return result;
}
```

删除不再调用的 `extractFromRoom(long, Action)`、`extractFromExternal(long, Action)`、按数量提取来源的静态 `extractFrom(...)` 和 `merge(...)`。扩展接口继续把指定类型请求传递给这个处理器，不能降为按数量请求。

这段实现要求外部来源遵守 Mekanism 的指定类型、限量提取合同。合同违反时显式报错，不能静默吞掉错误资源；它不是针对违反合同的第三方来源的通用事务回滚机制。

`ChemicalLedger` 的 NBT 键、参与者身份、封口、恢复和销毁规则均可保留，无需存档格式升级。1.20.1 同样没有改变四套账本格式。

## 二、可移植的扩展接口与热量优化

### 扩展委托复用

1.20.1 新增 `MekanismExtensionDelegate<T>`，被四种化学品扩展代理和热量扩展代理共用。

它每次操作仍调用 `extension.resolvePort(side)`，检查红石、绑定、宿主是否移除、端口是否配置以及宿主是否处于真实物流模式。只有工厂对象、宿主面、模式和逻辑端口编号均未变化时才复用委托；禁用或解绑时清除委托，恢复后重建。

1.21.1 可将这个模式用于 `MekanismExtensionChemicalHandler` 和 `MekanismExtensionHeatHandler`。不要缓存一次解析出的端口后永久使用，也不要因为复用委托而跳过红石及身份检查。

### 热量端点快照

旧 `MekanismHeatHandler` 每个读取或传热操作都重新解析邻接端点并创建 `VirtualHeatCapacitor`。新的实现仅复用端点关系：

- 缓存游戏刻、工厂物流拓扑代次、房间端口集合和实际邻接端点。
- 同刻且拓扑有效时复用目标列表与虚拟热容对象。
- 每次使用前检查邻接方块实体身份、移除状态和能力寿命。
- 区块卸载、端点替换、能力失效、端口集合变化或拓扑代次变化后重新解析。
- 下一游戏刻重新解析，防止静默变化的端点长期陈旧。
- 温度、热容、导热参数和实际热量交接仍实时读取，不缓存热量、不增加跨刻缓冲。
- 房间端口、模式、绑定或邻接发生变化时，在原有刷新路径中递增物流拓扑代次；递增必须发生在失效回调之前。

1.21.1 没有 Forge `LazyOptional`。应通过 NeoForge 对应的能力失效回调／缓存机制及方块实体身份检查实现能力寿命验证，不能复制当前 `Connection` 记录里的 Forge 类型。保留原项目已有的移动兼容刷新触发，并让这些触发同步更新拓扑代次。

### 冗余清理

- `VirtualHeatCapacitor.getHeat()` 在参考 1.21.1 实现中也是未使用的私有方法，可以删除。
- `weightedAverage(boolean)` 只有 `true` 调用，可改为明确的导热计算，删除不使用的绝热分支。
- 扫描每个热处理器时先保存 `getHeatCapacitorCount()`，不要在循环条件里重复调用。
- 温度和导热加权计算可在一次遍历中累计权重与总热容，避免重复读取热容。
- 共享内侧／外侧化学品提取流程，删除不再需要的合并与发现函数。

### 不直接移植的内容

`LegacyCapabilities` 的新 provider 可用性谓词，是 1.20.1 Forge／早期 NeoForge 的能力适配优化。1.21.1 使用 NeoForge 方块能力注册，应在其现有机制中复用稳定处理器并处理失效，不引入 `net.minecraftforge`、`LazyOptional` 或四套旧化学品接口。

## 三、飞行修复的版本边界

1.20.1 的会话新增可选 `flightGameMode` 字段，记录恢复飞行权限所依据的模式。进入、嵌套、重新登录与退出会保留或校正此字段。

每个 Pocket 玩家刻先比较一个瞬态模式标记，只有实际模式变化才解析并更新持久会话。这样不会在可能被 Forge 取消的模式切换前提交快照，也不需要每刻解析完整返回栈。

模式不变时恢复进入前已有的飞行授权；模式改变后，以当前模式的原版权限建立新的恢复快照，并在工厂内继续提供探索飞行。退出时创造保持飞行权限，旁观保持飞行状态，生存／冒险移除探索临时权限。

旧会话缺少模式字段时保留历史快照，并首次建立模式基线；当前创造／旁观权限不会被旧快照撤销。旧存档无法凭空重建此前未记录的模式变化。

1.21.1 使用 `NeoForgeMod.CREATIVE_FLIGHT` 属性及本模组的探索修饰符，应继续维护修饰符的授予／撤销边界。不要用 1.20.1 的 `mayfly` 快照赋值直接覆盖原实现。若在 1.21.1 审查游戏模式变化，只移植测试场景并按其属性机制处理。

## 四、回归与验收

新增 `MekanismLogisticsRegression` 使用真实的四种处理器与真实账本；注册表、世界和外部机器为测试替身。覆盖每种化学品的内侧／外侧提取：

| 场景 | 验收结果 |
| --- | --- |
| 缓存 A=50，请求 B=10 | 返回空，A 仍为 50，来源不变 |
| 缓存 A=50，来源 B=50，按数量请求 100 | 返回 A=50，B 仍为 50 |
| 缓存 A=50，来源 A=50，请求 A=75 | 返回 75，缓存为 0，来源为 25 |
| 连续三次模拟后执行 | 模拟不改变归属，执行仅扣除一次 |
| 缓存为空，来源 B，分别请求 A 和按数量请求 | A 请求不扣 B；按数量请求正确发现并提取 B |
| 拒绝请求后保存／重载账本 | NBT 不变，已有格式可读 |
| 已缓存的扩展代理收到红石禁用／恢复 | 禁用不传输，恢复后正常工作 |
| 已配置能力连续查询 | 复用处理器；禁用失效，恢复后重新提供能力 |

新增 `MekanismHeatRegression` 验证按热容分配的守恒、实时温度／热容、同刻端点复用、下一刻更新、能力失效、方块替换、拓扑代次、禁用恢复与扩展委托复用。100 次相同扩展绑定查询只创建 1 次委托。

扩展的 `ExplorationSessionRegression` 覆盖四种游戏模式之间的切换、工厂内连续切换、退出恢复、旧字段缺失及进入前已有的外部飞行授权。原有夜视、普通返回、嵌套、登录、取消传送等用例继续通过。

在 1.21.1 中应把四种旧接口的测试场景转换为统一 `ChemicalStack`／`IChemicalHandler` 测试，并加入实际 Mekanism／Pipez 游戏测试。不得只测试 `merge()`，必须覆盖实际扣除资源的完整提取调用链。

1.20.1 已执行：

```powershell
.\build-1.20.1.ps1 testExplorationSession testMekanismHeat testMekanismLogistics --offline
.\build-1.20.1.ps1 build --offline
python migration/verify_artifact.py
```

完整构建及 19 组独立 JVM 回归全部通过；发布包有 258 个 Java 17 类、15 个配方。原 1.21.1 项目记录的 274 个文件保持不变。运行资源没有改动，生产计划、账本格式、配方、蓝图和通道资源均保留。

验证记录：`migration/fixes-1-2-3-6-build.log` 与 `migration/artifact-verification.json`。未启动游戏客户端或专用服务器，因此这些结果不替代双加载器实机验收。
