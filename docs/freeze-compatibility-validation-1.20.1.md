# 1.20.1 冻结兼容修复与验证

日期：2026-10-18。仅修改 `create_nested_factory1.20.1` 项目。
依据：`freeze-compatibility-1.20.1.md`。交付版本：1.5.22。

## 根因与修复

本项目的 `LevelFreezeMixin` 使用默认优先级 1000 的 `@Redirect` 拦截
`Level.tickBlockEntities()V` 中的 `TickingBlockEntity.tick()V`，并手动调用 tick。
在本机 1.20.1 + Observable 4.4.2 上复现调用点争用：Observable 的 Redirect 报
`(0/1) succeeded`，游戏在加载世界之前退出。
失败基线命令：

```powershell
.\build-1.20.1.ps1 runFreezeCompat -PfreezeCompatTest -PfreezeCompatMods=observable
```

基线日志：`build/freeze-compat/baseline-observable.log`。
由于已有明确冲突日志，直接验证该调用点，没有将随机刻或其他冻结入口当作同一个问题修改。

修复使用 `com.llamalad7.mixinextras.injector.v2.WrapWithCondition`，优先级为 900。
条件返回 `!PocketFreezeHooks.isFrozen(level, ticker.getPos())`；不再手动调用 `ticker.tick()`。
原有随机刻入口已经采用 v2 条件包装，保持描述符及冻结判断，只校正了不适用于所有版本的 Lithium 注释。

真实变换后的 `Level` 字节码确认：条件在 Observable 的整个 Redirect 操作外层，
冻结时跳过计时和实际执行；正常时保留 Observable 的调用链。
没有提高 Redirect 优先级、禁用第三方优化、降低 `defaultRequire=1` 或设置 `require=0`。

MixinExtras 0.4.1 已包含所需 v2 注入器，保留该版本并通过 `jarJar` 打包
`mixinextras-forge`，最低解析版本为 0.4.1。该 Forge 引导制品适用于这里的两个 47.x 加载器。
保留注解处理器、Mixin 配置及 1.20.1 SRG refmap。
官方参考：[条件包装](https://github.com/LlamaLad7/MixinExtras/wiki/WrapWithCondition)、
[Forge 依赖打包](https://github.com/LlamaLad7/MixinExtras)、
[LegacyForge 重混淆与 refmap](https://github.com/neoforged/ModDevGradle/blob/main/LEGACY.md)。

`PocketFreezeHooks` 和冻结事务未修改：继续按 Pocket 维度和 XYZ 查询；
计划刻、方块事件仍保存并恢复；实体按包围盒判断，玩家继续排除。

## 固定环境

| 项目 | 版本 |
|---|---|
| Minecraft / Java | 1.20.1 / Eclipse Temurin 17.0.17 |
| Forge | 47.3.0 |
| NeoForge | 47.1.106 |
| Create | 6.0.8-291 slim |
| Ponder / Flywheel / Registrate | 1.0.91 / 1.0.6-beta-266 / MC1.20-1.3.3 |
| Observable | 4.4.2，Modrinth 版本 ID `QtSVNyjm` |
| Radium（Lithium 的 Forge 移植） | 0.12.4+git.26c9d8e，版本 ID `n947JjJH` |
| Architectury / Kotlin for Forge | 9.2.14 / 4.12.0，版本 ID `1MKTLiiG` / `Zsh14XeQ` |
| MixinExtras | 0.4.1，发布 JAR 内嵌 Forge 制品 |

没有将 1.21.1 的 Observable 5.4.4 或 Lithium 0.15.4 当作 1.20.1 测试对象。
Radium 使用默认优化配置，无规则覆盖。

## 验证内容

独立 `src/freezeCompatTest` 测试模组使用真实服务器、真实 Pocket 维度和真实 Mixin：

- 向 `Level` 注册计数 ticker，执行变换后的完整 tick 循环：冻结 8 次均为零；
  同区块其他高度、XYZ 范围外和主世界 ticker 各执行 8 次。
- `THAWING` 阶段仍冻结；移除冻结事务后再执行 8 次，冻结 ticker 和邻居的增量相等。
- 安装 Observable 时开启其计时开关并检查真实统计表：冻结位置没有样本，
  正常位置样本数等于执行次数；解冻后恰好 8 个样本，`currentTarget` 清理为 null。
- 两个完整区段使用无副作用测试方块/流体，以排除随机选点未命中的误判。
  冻结区段的两类随机刻均为零，同区块其他高度各执行 64 次；解冻后两者各增加 64 次。
- 对真实 `LevelTicks` 队列和方块事件队列验证冻结前捕获、冻结后新入队、NBT 保存加载、
  解冻恢复；17 tick 剩余延迟保留，方块和流体计划刻各执行一次，两条事件各恢复一次。
- 实体包围盒跨越边界时不 tick，解冻后执行一次；冻结区域内玩家继续 tick。

这些测试模组仅用于测试服务器，包含探针注册项，不能放进正式存档。
普通 `build` 不编译或打包它们；发布主 JAR 也不包含测试类或 Observable/Radium 等测试依赖。

## 发布服务器矩阵

使用同一份重混淆后的 `build/libs/create_nested_factory-1.20.1-1.5.22.jar`，
安装进官方安装器生成的生产服务器。没有使用 IDE 类目录替代发布 JAR。

| 加载器 | 基础依赖 | 加 Radium | 加 Observable | 两者同时 |
|---|---|---|---|---|
| Forge 47.3.0 | 通过 | 通过 | 通过 | 通过 |
| NeoForge 47.1.106 | 通过 | 通过 | 通过 | 通过 |

脚本同时检查进程退出码和 `[CNF-FREEZE-TEST] PASS:` 标记，避免服务器启动失败但退出码为零造成误报。
日志与 JSON 结果位于 `build/freeze-compat/production-*-*.log` 和 `production-*-results.json`。
完整构建和现有 21 组 JVM 回归通过：`build/freeze-compat/build-validation.log`。发布前再次执行 `check build`，记录为 `.publish/build-1.5.22.log`；同步上游 MIT 许可元数据后重新打包，并使用最终 JAR 复跑上述八组服务器组合。

发布包检查通过：版本为 1.5.22，260 个模组 class 均为 Java 17 字节码（major 61），
包含两个 Mixin 配置、SRG refmap、Jar-in-Jar 元数据和 Forge 版 MixinExtras 的 v2 注入器，
不含测试类或测试依赖。发布前重新检查的记录位于 `build/freeze-compat/release-artifact-verification.json`。JAR 中许可元数据为 MIT，与上游 LICENSE 一致。

交付文件：`build/libs/create_nested_factory-1.20.1-1.5.22.jar`，1,061,639 字节。
SHA-256：`c2f8a8cdc0f647f9c1d1e29aed5a85fabbc01629531c82e1d05bb127fe2396c3`。

## 重跑

先执行 `./build-1.20.1.ps1 build freezeCompatTestJar reobfFreezeCompatTestJar -PfreezeCompatTest`。
测试依赖下载到 `build/freeze-compat/mods`，可用上述版本 ID 获取固定版本并验证 SHA-512：

```powershell
$testMods = Join-Path $PWD 'build\freeze-compat\mods'
New-Item -ItemType Directory -Force -Path $testMods | Out-Null
foreach ($id in @('QtSVNyjm', 'n947JjJH', '1MKTLiiG', 'Zsh14XeQ')) {
    $version = Invoke-RestMethod "https://api.modrinth.com/v2/version/$id"
    $asset = $version.files | Where-Object primary | Select-Object -First 1
    $path = Join-Path $testMods $asset.filename
    Invoke-WebRequest -Uri $asset.url -OutFile $path
    if ((Get-FileHash -LiteralPath $path -Algorithm SHA512).Hash.ToLowerInvariant() -ne $asset.hashes.sha512) {
        throw "Hash mismatch: $($asset.filename)"
    }
}
```

从官方 Maven 下载并使用 Java 17 执行以下安装器的 `--installServer` 参数：

- [Forge 安装器](https://maven.minecraftforge.net/net/minecraftforge/forge/1.20.1-47.3.0/forge-1.20.1-47.3.0-installer.jar)，
  安装目标 `build/freeze-compat/production-forge`。
- [NeoForge 安装器](https://maven.neoforged.net/releases/net/neoforged/forge/1.20.1-47.1.106/forge-1.20.1-47.1.106-installer.jar)，
  安装目标 `build/freeze-compat/production-neoforge`。

然后执行：

```powershell
.\test-freeze-compat-1.20.1.ps1 -Loader forge
.\test-freeze-compat-1.20.1.ps1 -Loader neoforge
```

测试脚本仅监听本机，使用独立临时世界，自动停止服务器。
可用 `-Scenarios base`、`radium`、`observable` 或 `both` 单独重跑。
开发环境也可使用 `runFreezeCompat -PfreezeCompatTest -PfreezeCompatMods=observable,radium`。

## 验证边界

本轮是专用服务器逻辑集成测试；没有验证客户端 GUI，也没有穷举全部 47.x 加载器或其他性能模组。
NBT 重载检查覆盖冻结账本的数据往返，不等于冻结中的完整游戏进程断电/重启测试。
方块事件检查覆盖捕获和恢复队列，未模拟活塞等所有事件消费者的资源变化。
完全替换调度器、删除调用点或异步直接调用机器的其他模组仍需独立适配和验证。
