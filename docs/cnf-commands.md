# 工厂查询指令 / Factory inspection commands

查询指令无需 OP；`info` 由玩家执行，其他查询指令也支持服务器控制台。
`copy` 和 `delete` 要求 OP 2 级；`copy` 由玩家执行，`delete` 支持服务器控制台。
ID 使用工厂界面中显示的永久工厂 ID（UUID），不是房间的内部数字槽位。

| 指令 | 功能 |
| --- | --- |
| `/cnf info` | 查询准心指向的嵌套工厂：名称、ID、维度及坐标、模式、层级，以及存在时的全部后代 ID 和直接父空间 ID。 |
| `/cnf list root` | 列出所有已登记的根工厂房间。 |
| `/cnf list nested` | 列出所有已登记的非根工厂房间。 |
| `/cnf mode <id>` | 查询工厂及其空间模式：常加载、黑盒准备/学习/运行、蓝图。 |
| `/cnf nested <id>` | 列出该空间下所有层级的嵌套房间，不含查询的房间本身。 |
| `/cnf copy <id>` | 给予玩家一个绑定已有 ID 和原空间的嵌套工厂复制品。 |
| `/cnf delete <id>` | 无嵌套后代时分批清除空间，完成后回收槽位，原 ID 永久失效。 |

每个房间显示 ID、绑定工厂名称以及绑定状态。已放置工厂显示维度与方块坐标；绑定物品显示 `item`。
多层查询另外显示缩进和父工厂 ID。点击结果中的 ID 可以填入 `/cnf mode <id>`。
`mode`、`nested`、`copy` 和 `delete` 的 ID 参数支持 Tab 补全已有 ID；不存在的 ID 会返回明确错误。
正在删除和已删除的 ID 不进入补全列表。

`info` 在服务端按照玩家实际视线和方块交互距离检测首先命中的方块，不穿过遮挡方块。
名称、ID、坐标、模式和层级读取目标方块当前信息，不要求该工厂已经进入查询目录。
未命中方块、命中其他方块、目标暂不可用或由控制台执行时，显示相应提示。
根工厂为 0 层，直接子工厂为 1 层，依次递增；被祖先冻结时在模式旁注明。
后代 ID 包含所有嵌套层级、物品形式和终端蓝图工厂，合并已有持久化父子关系及目标记录的直接子工厂，去重排序并排除自身和已删除 ID。
没有后代时省略后代 ID 行；根工厂或没有父空间时省略父空间 ID 行。

复制品关联原空间，保持原 UUID、名称、模式和绑定信息，不创建新空间。
复制命令不会拆除原工厂、改变其绑定位置或中止学习。
原工厂仍放置时，即使其区块已卸载，也不能放置复制品；拆除原工厂后可恢复放置。
放置时使用最近一次拆除保存的有效状态，避免旧复制品覆盖较新的模式、名称或黑盒运行数据。
嵌套复制品沿用原父空间的放置限制。背包已满时，复制品掉落在玩家身旁。
销毁未放置的命令复制品不会销毁其关联空间。

删除前检查持久化父子关系以及真实房间中的工厂，存在任何后代则拒绝删除并列出全部后代 ID。
物品形式的后代、未加载的已登记后代、没有独立空间的终端工厂也会阻止删除。
房间有人或正在建造、扩缩、清理时拒绝删除。
已放置工厂沿用现有空间销毁流程，移除绑定方块并返还普通未绑定工厂物品。
物品形式的工厂通过已保存的房间数据清理，不需要持有原物品。
空间分批清理，先提示开始，清理完成后提示完成；控制台同样收到完成消息。
根空间、嵌套空间和旧存档根空间的位置可在清理完成后复用，新工厂始终使用新 UUID。
旧 ID 的原物品和复制品此后均无法重新绑定，也不能清理已经复用的槽位。
删除中的记录和任务随世界保存，服务器重启后继续清理，未完成的空间不会提前回收。

房间记录随世界保存，区块卸载和服务器重启不会移除记录。工厂拆成物品时保留父子关系，重新放置后更新坐标。
永久清除空间完成后移除该房间的查询记录。终端蓝图工厂没有独立空间，不出现在房间列表中，但可以查询模式并补全其 ID。
空间被祖先黑盒或蓝图工厂冻结时，模式查询会额外注明冻结状态。

升级旧存档后，旧工厂在方块首次加载时登记；旧绑定物品在进入玩家物品栏或作为掉落物加载时登记。
仍在未加载区块或容器中的旧绑定物品，需要加载或取出一次才能进入查询目录。
此后查询无需加载工厂或房间区块。
复制或删除旧物品形式的工厂需要其完整绑定快照；数据不足时会提示加载原方块或取出原物品。

Inspection commands require no operator permission; all except `info` also support the server console.
`info` is player-only. It uses the player's server-side view and actual block interaction range, reports the
first block hit, and reads current factory fields. Root depth is 0. Descendant and parent ID lines are shown
only when present; descendants include portable and terminal factories and exclude the queried factory
and deleted IDs. Pointing at another block, missing a block or targeting an unavailable factory reports an error.
Use the permanent factory UUID shown in the factory UI. Placed bindings show dimension and block coordinates;
portable bindings show `item`. Both ID arguments offer Tab completion. Nested queries include every descendant
room and show its parent ID; roomless terminal factories are excluded from room lists but support mode queries.
The directory persists across chunk unloading and server restarts. Existing saves are indexed as factory blocks
or portable items become active after upgrading.
`copy` and `delete` require permission level 2. All four ID arguments offer Tab completion.
`copy` requires a player and gives a reference to the existing room and UUID; no new room is created.
A placed original binding blocks duplicate placement even when its chunk is unloaded. Restoring a copy uses
the latest portable state, and discarding a command copy does not delete the shared room.
`delete` also supports the console. Every descendant, including portable and roomless factories, blocks deletion.
Occupied or busy rooms cannot be deleted. Physical cleanup is incremental and restart-safe, and slots become
reusable only after completion. Deleted UUIDs stay permanently invalid for all old items and copies.
