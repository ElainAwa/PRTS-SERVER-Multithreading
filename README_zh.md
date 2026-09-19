# PRTS-Multithreading — Minecraft 1.21.1 / NeoForge（多线程并行分支）

**[English](./README.md) · [中文文档](./README_zh.md)**

>当前服务端common中mixin文件较乱，且当前架构存在较多bug。所以当前分支的更新会放缓，目前正在筹划重构服务端，寻找可能的新架构，想要加入开发的请联系我，谢谢！

> PRTS 是面向 Minecraft 1.21.1 的**独立多线程服务端核心**，以 GPL-3.0 发布，属于基于 [Arclight](https://github.com/IzzelAliz/Arclight) → [Luminara](https://github.com/CraftAmethyst/Luminara) 的**衍生作品**；上游代码保留其原始版权与许可，详见 LICENSE / THIRD-PARTY.md / NOTICE。

> ⚠ 本项目当前全部为 vibecoding 开发，开发人员不足，项目推进缓慢。有兴趣参与多线程服务端开发请联系：QQ 3031917948 / Telegram [t.me/Mon3trQAQ](https://t.me/Mon3trQAQ)

主线程只做调度与同步：维度 worker、区域 worker 与独立子任务池承担重负载计算，玩家与世界权威写入保持主线程。所有并行子系统都有兜底路径，环境过载或不兼容时按序降级，而不是卡死或损坏存档。

## 并行引擎

将单线程世界 tick 拆分为多线程执行，以下功能全部默认开启：

- **异步寻路** (`pathfinding-async`)：怪物/村民寻路在工作线程计算，并复用有效导航路径
- **维度并行** (`dimension-parallel`)：主世界/下界/末地在独立 worker 线程 tick
- **区域并行** (`region-parallel`)：实体按区块条带划分为多个区域并行 tick
  - 区域数 2/4/8/16（`region-count`，默认 4）
  - 按负载自动增减区域（`region-auto-scale`）
  - 不等宽条带：繁忙区域向相邻区域让渡边界组（`uneven-stripes`）
- **实体批并行** (`entity-batch-parallel`)：区域内的实体阶段再扇出到子任务池，村民等持久化生物离开主线程 tick
- **区块环境并行** (`chunk-env-parallel`)：随机刻/流体刻扇出到子任务池，并用 3×3 区块锁（`chunk-env-lock`）保证相邻区块不会并发写入
- **方块实体分档调度** (`be-parallel-allow` / `be-main-thread-force`)：Create 方块实体默认在区域 worker 上 tick；已知必须主线程的类型（Create 轨道、红石链、移动储存接口、注液器、Lootr 箱子）锁定主线程
- **异步传送门** (`portal-async`)：传送进入未生成区块时提交异步加载，而不是卡住 worker
- **无玩家维度多 tick** (`dimension-worker-multitick`，默认 4)：无玩家维度每个 barrier 会话可连 tick 多次，更快消化积压

### ThreadPolicy 自动学习

运行时检测 worker 访问主线程专属 API（`getBlockEntity` / `setBlock` 等）的违规，自动把不安全实体类路由回主线程：

- 滑动窗口统计（默认 2400 tick；窗口内 5 次违规触发路由）
- 路由持久化（`config/prts-learned-routes.json`，启动加载 / 关服保存）
- 双向 Probation：被路由的类定期回 worker 试跑，成功后恢复并行（指数退避）
- 安全阀：worker 异常 → 永久降级主线程
- 默认策略 `thread-policy: stats` 只统计不拦截；`enforce` 仅用于测试服定位

## 区块系统

- **细粒度状态机** (`chunk-system-enabled`)：区块生成按「区块 × 状态」拆成任务，带优先级与依赖门控并行调度，新地形按序到达且多区块同时推进
- **并行 worldgen 调度器** (`chunk-system-scheduler`)：依赖门控的 worldgen 任务图在工作线程执行，FEATURES 前后使用分级锁域
- **需求调度**：每 tick 需求预算（`chunk-demand-per-tick`）、最低排空窗口（`chunk-demand-min-drain-ms`）、按玩家距离分桶优先且带饥饿兜底（`chunk-demand-player-priority`）、区块发送速率下限（`chunk-send-rate-floor`，默认 128 块/tick）
- **方向感知预取** (`chunk-prefetch`)：沿移动方向预铺窗口与走廊、静止时后台预生成（`idle-enabled`）、登录预热（`login-warmup-enabled`）
- **异步区块 IO** (`chunk-async-io-enabled`)：区块反序列化离开主线程
- **生成预算**：每 tick 提交上限（`generation-tasks-per-tick`）、滚动 2 秒窗口（`chunkgen-inflight-limit`）、堆压力卫兵（`generation-memory-guard-*`）在逼近堆上限时降速或暂停生成；`-Xms` 与 `-Xmx` 相同的启动参数同样适用

## Barrier 与故障处理

- Watchdog 感知（`barrier-watchdog-aware`）：并行等待不误触发看门狗强杀
- 软降级（`barrier-soft-degrade`，默认开）：主线程落后时，晚到的区域跳过本轮剩余工作，而不是拖住全服
- 硬超时动作（`barrier-timeout-action`，默认 `degrade`）：真卡死时该维度退回主线程串行并自动恢复（`barrier-timeout-recover-ticks`）；`crash` 则 dump 全线程后停机
- 整服兜底（`on-fault-fallback-vanilla`）：连续硬超时后退回原版串行 tick，重启恢复

## 模组兼容

- **Create**：动力网络拓扑变更加锁串行，无源分裂网络自动愈合，锁链与应力仪表保持正确。装配器、移动储存接口、溜槽、注液器安全路由；长轨道假轨栅格化跨 tick 分摊（`create-track-lazy-spread`）；传送带乘客注册延迟到主线程
- **Minecolonies**：殖民地 NPC 在区域 worker 工作（安全方块实体读取），工作 AI 相位错峰（`colony-npc-phase-stagger`），工作间隔可配置
- **Lithium**：检测到 Lithium 时生成驱动收口主线程，规避其线程检查
- **Sable / Simulated**：绳索物理的 native 入口已串行化，目前只解决跨线程 native 死锁导致的崩溃；物理结构仍存在 bug，属后续修复方向
- **SuperbWarfare**：引擎侧适配——弹道局部查询、IFF 节流、广播裁剪；可选载具休眠（`sbw-vehicle-sleep`）

## 事件桥与主分支优化

与主分支 `1.21.1` 对齐：

- 事件桥按需注册 Bukkit/Forge 转发器，无插件监听时短路跳过（`event-bridge.*`、`event-shortcircuit.*`）
- 实体空间索引、POI 查询预检、碰撞收集批处理、邻居更新熔断（默认开）
- 光照：每 tick 传播预算（`lighting.budget-*`）与独立光照线程（`lighting.threaded`）
- 看门狗、实体清理、AE2LT 节流、可靠区块保存（WAL）
- 容器菜单广播预检（`menu-broadcast`，默认关——先实测归因再开）
- Moonrise 快速调色板读取路径已移植（默认未注册，待验证）

## 配置

`prts-features.yml`（服务端根目录）首次启动自动生成，每个配置项都带一行注释。注释默认中文，把 `locale` 改为 `en_us` 并重启即可切换为英文；旧配置文件缺失的新配置项会自动补齐。

主要开关（生成时的默认值）：

```yaml
locale: zh_cn                  # 配置注释语言（zh_cn / en_us）

parallel:
  pathfinding-async: true      # 异步寻路：工作线程计算
  dimension-parallel: true     # 维度并行：各维度独立 worker tick
  region-parallel: true        # 区域并行：实体按区块条带并行 tick
  region-count: 4              # 区域数（2/4/8/16）
  region-auto-scale: true      # 按负载自动增减区域数
  entity-batch-parallel: true  # 实体阶段扇出到子任务池
  chunk-env-parallel: true     # 随机刻/流体刻进子任务池
  portal-async: true           # 未生成区块的传送门异步加载
  thread-policy: stats         # worker 世界访问策略：off/stats/enforce
  main-thread-routing: auto    # 违规实体类自动路由回主线程
  route-threshold: 5           # 触发路由的违规次数
  route-window-ticks: 2400     # 违规统计窗口（tick）
  chunk-system-enabled: true   # 区块×状态细粒度状态机
  chunk-send-rate-floor: 128   # 区块发送速率下限（块/tick）
  be-parallel-allow: ["create:*"]  # 允许在区域 worker tick 的方块实体
  barrier-soft-degrade: true   # 晚到区域跳过本轮剩余工作
  barrier-timeout-action: degrade  # 硬超时：降级串行并自动恢复
  dimension-worker-multitick: 4    # 无玩家维度每会话 tick 数

chunk-prefetch:
  enabled: true                # 沿玩家移动方向预取
  idle-enabled: true           # 静止时后台预生成

generation-tasks-per-tick: 50  # 每 tick 生成提交上限
chunkgen-inflight-limit: 128   # 滚动 2 秒提交窗口
generation-memory-guard-enabled: true  # 堆压力高时降速生成
barrier-watchdog-aware: true   # 并行等待时不误触发看门狗
barrier-timeout-ms: 120000     # barrier 卡死超时（毫秒）
```

完整配置以生成的文件为准：每个键都有双语一行注释，升级后新增配置项自动出现。

## 构建与部署

**环境**：JDK 21

**构建**：
```bash
./gradlew collect --no-daemon
```

**部署**：复制 `build/libs/PRTS-neoforge-1.21.1-*-Multithreading.jar` 到服务端根目录，启动。

**下载**：[GitHub Releases](https://github.com/ElainAwa/PRTS-SERVER-Multithreading/releases)

## 未来计划

- 修复 Sable/Simulated 物理结构 bug，并逐步把物理迁出主线程（当前 native 入口串行化只保证不崩溃）
- 依据生产实测逐步扩大方块实体分档白名单（当前默认 Create）
- Journal 读己所写覆盖层（接口保留，默认关）
- 容器菜单广播预检完成生产归因后启用
- 所有新并行能力继续以可复现合成负载验证，再调整默认值

## 其他功能

与主分支 `1.21.1` 一致（ServerCore 移植、实体追踪、区块保存、异步日志、动力铁轨优化、客户端模组防护、红石/寻路优化、模组命令桥接等）。

## 许可与版权

[GPL v3](LICENSE)，与上游一致。第三方代码的版权、许可证与署名集中声明于 `THIRD-PARTY.md`（英文版 `THIRD-PARTY.en.md`）。
