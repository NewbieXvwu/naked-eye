# 眼见为实（Naked Eye）便携包说明

两个版本的脚本完全相同，唯一的区别是游戏文件是否已经放在包里：

| | 联网版 `naked-eye-online.tar.gz` | 离线版 `naked-eye-offline-linux-x64.tar.gz` |
|---|---|---|
| 平台 | macOS（Apple Silicon / Intel）、Linux x86_64 | Linux x86_64 |
| 第一次 `./setup.sh` | 下载 Java 25（已装就跳过）、MC 26.3、Fabric、服务器、模组，共约 1 GB | 只检查文件是否齐全，完全不联网 |
| 无头渲染 | 用系统自带的 Mesa | 系统没有 Mesa 时，自动改用包里自带的那份（要求 glibc ≥ 2.39） |

```sh
tar xzf naked-eye-*.tar.gz && cd naked-eye
./setup.sh
./start.sh              # 服务器和客户端在后台启动，API 能访问时才返回
tools/hc obs
./stop.sh               # 冻结世界、存档，再关闭（离开前一定要执行）
```

- 依赖：Python 3.8+、约 4 GB 内存、约 3 GB 磁盘。不需要 Gradle。启动方式和普通启动器一样：在原版 jar 上加载 Fabric。
- 世界：种子固定为 `20261126`，第一次启动时现场生成；出生点随机范围设为 0（`respawn_radius 0`），所以每个参赛者都站在同一个世界的同一个位置开局。
- 比赛模式：用 `HONEST_DEBUG=0 ./start.sh` 启动，会关闭 7071 实验者接口（它能读到客户端知道的全部真实信息）。规则写在 `AGENTS.md`，任何一家的 AI 都应该先读它。
- 公平性只能靠规则加事后审计。AI 在自己的沙箱里拥有完整的 shell，技术上可以直接用 RCON 给自己刷物品。录像在 `run/server/recordings/`，服务器日志里记录了每一条执行过的命令，赛后可以查。
- 开发：改完 mod 源码（`src/mod`），在联网机器上执行 `cd src/mod && ./gradlew build`，再把 `build/libs/honest-client-*.jar` 复制到 `mods/`。
- **观战（推荐）**：用你自己的 Minecraft **26.3** 客户端（官方启动器，可以开光影），选「多人游戏 → 直接连接 → `127.0.0.1`」（局域网里的其他电脑填这台机器的 IP）。
  - 一进去就附身在 Agent 身上：看到的是它的视角，HUD 也是它的：快捷栏、手持物品、血量、饥饿、经验、护甲，按 E 打开的物品栏也一样。但你什么都做不了，点击、挖掘、丢物品、聊天全部无效（聊天会被拦下，否则 Agent 会听到，等于场外指导）。
  - 按潜行键脱离，变成普通旁观者，可以随便飞（世界冻结时也能飞）。想回去：左键点 Agent，或者输入 `/watch`（`/watch 名字` 指定附身哪个，`/watch free` 脱离）。
  - Agent 死亡重生后，你会自动跟回去。人数不限，多少人同时观战都行。
  - Agent 完全察觉不到观众：旁观者的实体根本不会发给它，"某某加入了游戏"也不会广播；旁观者也不会让服务器加载新的区块，所以世界跟没人观战时完全一样。录像只录 Agent。
  - 多 Agent：在 `run/server/config/naked-eye/agents.txt` 里每行写一个 Agent 的名字，名单以外的玩家都算观众。
- 窗口与省电：进入世界后窗口默认隐藏，此时完全不绘制；世界冻结（agent 在想）时客户端只跑每秒 5 帧。
  - `./window.sh show` 打开窗口观战，`./window.sh hide` 收起，随时切换，agent 不会断开。
  - 点窗口的关闭按钮或按 Cmd+Q 也只是隐藏窗口；真要退出用 `./stop.sh`。
  - 窗口只能看，不能操作：鼠标和键盘都不会传给游戏，免得你一碰就干扰了 agent。
  - 想一启动就显示窗口：`HONEST_SHOW=1 ./start.sh`。
- 环境变量：
  - `HONEST_WINDOW=1`：Linux 桌面环境下也开窗口（默认：有显示器就开，没有就完全无头）；
  - `HONEST_MUTE=1`：静音（Linux 默认静音）；
  - `HONEST_XMX=3G`：调整客户端内存上限；
  - `HONEST_BUNDLED_MESA=1`：强制使用包里自带的 Mesa。
- ⚠️ 离线包里有 Mojang 的游戏文件，只能自己用（包括放进你自己的沙箱），不要公开发布。
