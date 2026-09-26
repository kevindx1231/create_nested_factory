# Create Nested Factory

Create Nested Factory 是面向 Minecraft 1.21.1、NeoForge 和 Create 的自动化模组。它允许玩家把真实工厂建在持久化的 Pocket 空间中，再通过端口、动力、通道和黑盒生产把复杂产线封装成可嵌套的工厂方块。

> Build real factories inside persistent pocket rooms, connect them through Create logistics, and turn verified production lines into nestable black-box factories.

## 主要功能

- 创建、进入、命名、扩张和收缩持久化工厂房间。
- 在工厂内部继续放置嵌套工厂，并维护独立身份和父子关系。
- 六面逻辑端口支持物品、流体和 Create 包裹传输，可把多个面映射到同一端口组。
- 扩展接口把一个工厂面扩展为五个外露面，并支持红石禁用和 capability 失效。
- 应力端口在内外 Create 动力网络之间传递应力，并支持机械超频。
- 三窗口学习流程记录真实产线的输入、输出、工具、来源事件和应力需求。
- 黑盒模式保存预付输入、启动资本、工具租约、在制事务和待领取输出，避免重启或堵塞时重复提交。
- 蓝图可以复制已验证的生产规则，但不会复制真实资源；首次运行仍需提供启动资本。
- 工厂通道支持工厂内外通行、配对、方向切换和循环保护。
- 根工厂进入模拟模式时会冻结完整嵌套树，并在解冻后恢复受保护的 tick 和方块事件。
- 支持专用服务器，并提供 Simulated/Sable、Mekanism 与 Pipez 的可选兼容。

## 运行要求

| 项目 | 版本 |
|---|---|
| Minecraft | 1.21.1 |
| Java | 21 |
| NeoForge | 21.1.248；元数据允许 21.x |
| Create | `>= 6.0.9` 且 `< 6.1.0`；开发版本为 6.0.10-280 |

客户端和专用服务器应安装相同版本的本模组及必需依赖。

### 可选兼容

| 模组 | 支持内容 |
|---|---|
| Mekanism 10.7+ | Chemical 与 Heat capability 桥接 |
| Pipez | 流体/物流网络适配 |
| Simulated 1.0+ 与 Sable 2.x | 移动结构、旋转及返回锚点兼容；该集成需要两者同时安装 |

没有安装可选模组时，对应兼容类不会参与初始化。

## 安装

1. 安装 Minecraft 1.21.1 和 NeoForge 21.1.x。
2. 安装兼容版本的 Create。
3. 从 [Releases](https://github.com/kevindx1231/create_nested_factory/releases) 下载 `create_nested_factory-1.5.0.jar`。
4. 把 JAR 放入客户端和服务器的 `mods` 目录。
5. 如果使用可选兼容，再安装上表中的对应模组。

升级既有世界前请先备份存档。不要在同一实例中同时放置两个版本的 Create Nested Factory JAR。

## 基本使用流程

1. 放置嵌套工厂方块并进入 Pocket 房间。
2. 使用空间扩张/收缩工具调整房间，在工厂面配置输入、输出和端口编号。
3. 在房间内放置普通端口和真实加工设备，连接物品、流体与动力网络。
4. 启动学习，让产线完整运行三个观察窗口。
5. 学习成功后激活黑盒；需要时加入烈焰电池选择超频档位。
6. 使用蓝图复制生产规则，或使用工厂通道连接嵌套层级。

详细规则和故障测试参见：

- [领域规则与持久化约束](CONTEXT.md)
- [Simulated/Sable 兼容规则](CONTEXT-simulated.md)
- [架构决策记录](docs/adr/)
- [黑盒手动测试矩阵](docs/blackbox-v9-manual-test-matrix.md)
- [扩展接口手动测试矩阵](docs/nested-extension-interface-manual-test-matrix.md)

## 从源码构建

仓库包含 Gradle Wrapper。需要 Java 21。

Windows：

```powershell
.\gradlew.bat check build --stacktrace
```

Linux/macOS：

```bash
./gradlew check build --stacktrace
```

正式 JAR 输出到 `build/libs/create_nested_factory-1.5.0.jar`。`check` 会同时运行通道、冻结索引、服务器隔离、学习控制器、房间几何、生命周期转换和发布修复回归。

## 问题反馈

提交问题前请确认使用的是支持的 Minecraft、NeoForge 和 Create 版本，并附上：

- `latest.log` 和相关崩溃报告；
- 单人或专用服务器环境；
- 已安装的可选兼容模组及版本；
- 可重复的方块结构、操作步骤和预期结果；
- 是否从旧版本升级以及问题是否能在新世界复现。

问题追踪：[GitHub Issues](https://github.com/kevindx1231/create_nested_factory/issues)

## 版本

当前正式版：**v1.5.0**。完整变更见 [CHANGELOG.md](CHANGELOG.md)。

## 许可

Copyright © 2026 Kevin_Dish1231. All Rights Reserved.

本仓库公开用于源码查看和问题定位。除非版权所有者另行书面授权，否则不授予复制、修改、再发布或分发许可。详见 [LICENSE](LICENSE)。
