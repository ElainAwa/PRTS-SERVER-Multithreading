package io.izzel.arclight.common.compat.prts;

import io.izzel.arclight.common.optimization.ownership.ClassAffinityLedger;
import io.izzel.arclight.common.optimization.ownership.ThreadPolicy;
import io.izzel.arclight.common.optimization.ownership.WorldAccessGuard;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bukkit.configuration.file.YamlConfiguration;

/** PRTS 轻量防卡功能的配置。 */
public class PRTSFeaturesConfig {

    private static final Logger LOGGER = LogManager.getLogger("PRTS-Features");

    public static YamlConfiguration config;

    // EntityClear - 定时清理掉落物
    public static boolean clearItemEnabled;
    public static long clearItemInterval;
    public static List<String> clearItemWhitelist;
    public static String clearItemMsg;

    // EntityClear - 定时清理怪物（无自定义名）
    public static boolean clearMonsterEnabled;
    public static long clearMonsterInterval;
    public static List<String> clearMonsterWhitelist;
    public static String clearMonsterMsg;

    // Watchdog - 主线程卡顿看门狗（默认关；开启后主线程 > threshold-ms 未推进即告警并 dump 栈）
    public static boolean watchdogEnabled;
    public static long watchdogThresholdMs;
    public static long watchdogWarnCooldownMs;

    // Neighbor-update circuit breaker - 邻居更新风暴熔断（防看门狗强杀）
    public static boolean neighborUpdateBreakerEnabled;
    public static long neighborUpdateBreakerMaxPerTick;

    // ae2lt TeslaCoil setWorking 节流（缓解每 tick 翻转触发的邻居风暴）
    public static boolean ae2ltSetWorkingThrottleEnabled;
    public static int ae2ltSetWorkingThrottleMinTicks;

    // Parallel tick engine - 异步寻路 / 维度并行 / 区域并行（PRTS 自研多线程引擎，默认全开）
    public static boolean parallelPathfindingAsync;
    public static boolean parallelDimension;
    public static boolean parallelRegion;
    /** 主世界区域数 (2/4/8, 默认 4)。 */
    public static int parallelRegionCount;
    /** 方块实体 tick 是否参与区域并行（默认关——BE 间交互复杂，并行有竞态风险）。 */
    public static boolean regionBlockEntityParallel;
    /** 殖民地 NPC 工作 AI 相位错峰（按 citizenId 摊平每 5 tick 的集中尖峰）。 */
    public static boolean parallelColonyPhaseStagger;
    /** 殖民地 NPC 工作 AI 执行间隔（tick）。原版固定 5；2-60 可调。 */
    public static int colonyNpcWorkInterval;
    /** 村民主线程 POI / 单目标寻路预算（每 tick；0 = 关闭）。 */
    public static int villagerPoiPathBudget;
    /** 主线程 routed entity drain 每 tick 处理上限（0 = 不分批，全量 drain）。 */
    public static int mainThreadEntityDrainBudget;
    /** 主线程单目标移动寻路异步化（routed villager 主线程 A* 削峰；默认关）。 */
    public static boolean mainThreadPathAsync;
    /** Create TreeCutter BFS 节点预算（0 = 关闭防护，保持原行为；建议 4096）。 */
    public static int treeCutterNodeBudget;
    /** Create TreeCutter 单次搜索时间预算毫秒（0 = 关闭该层；建议 10）。 */
    public static long treeCutterTimeBudgetMs;
    /** learned routes JSON 文件路径。 */
    public static String learnedRoutesFile;
    /** 最多持久化的 learned routes 数量。 */
    public static int learnedRoutesLimit;
    /** 启用双向 probation（自动路由类定期试跑 worker，无违规则解除路由）。 */
    public static boolean routeProbationEnabled;
    /** probation 间隔（tick）。 */
    public static int routeProbationTicks;
    /** 允许 probation 的最大历史违规数（超过此值的类不做 probation）。 */
    public static int routeProbationMaxViolations;
    /** 殖民地管理器 ServerTick 事件里的 getAllColonies 快照缓存（默认关；测试服 A/B 后定默认）。 */
    public static boolean colonyManagerTickCacheEnabled;
    /** 殖民地列表快照的 TTL（tick）；过期自动重建，create/delete 路径立即失效。 */
    public static int colonyManagerTickCacheInterval;
    /** EventBus 事件分发遥测（诊断用，默认关；开启后会给每个事件类挂 HIGHEST/LOWEST 计时监听器）。 */
    public static boolean eventBusTelemetryEnabled;
    /** 主线程每 tick 处理的 chunk 需求上限（统一需求调度，默认 50）。 */
    public static int chunkDemandPerTick;
    /** 主线程 chunk 需求排空最低保证窗口（ms，默认 2；0=关闭）：低 TPS 超预算仍至少排空，防死亡螺旋。 */
    public static int chunkDemandMinDrainMs;
    /** chunk 需求玩家距离优先级（分 4 桶优先消费，默认关）。 */
    public static boolean chunkDemandPlayerPriority;
    /** 低优先级桶队头超龄即优先消费的阈值（tick，饿死兜底）。 */
    public static int chunkDemandStarveTicks;
    /** 区块发送目标速率下限（块/tick），0=关闭下限。 */
    public static float chunkSendRateFloor;
    /** 玩家方向区块预取（默认关）：按移动方向对视距外投临时 ticket（到期自动回收）。 */
    public static boolean chunkPrefetchEnabled;
    /** 进服热点预热：启动后把配置中心区域加载到 FULL（玩家首登免磁盘等待）。 */
    public static boolean loginWarmupEnabled;
    public static int loginWarmupRadius;
    public static int loginWarmupPerTick;
    public static int[][] loginWarmupCenters;
    /** 视距外预取深度（格）。 */
    public static int chunkPrefetchDepth;
    /** 每玩家预取重算间隔（tick）。 */
    public static int chunkPrefetchIntervalTicks;
    /** 预取 ticket 存活时长（tick，到期自动回收）。 */
    public static int chunkPrefetchTimeoutTicks;
    /** 预铺窗口深度（区块；depth 已废弃）。 */
    public static int chunkPrefetchWindow;
    /** 预铺走廊宽度（区块；窄走廊控管线负载，防饱和）。 */
    public static int chunkPrefetchWindowWidth;
    /** 窗口重算触发：跨块 ≥ windowStep。 */
    public static int chunkPrefetchWindowStep;
    /** 窗口重算触发：距上次重算 ≥ windowRecomputeTicks。 */
    public static int chunkPrefetchWindowRecomputeTicks;
    /** 预铺任务优先级档（56-63，需求档 clamp 到其下一档）。 */
    public static int chunkPrefetchPriority;
    /** 窗口内未完成预铺任务上限（窗口重算时补货，最近优先）。 */
    public static int chunkPrefetchMaxPending;
    /** idle 背景预生成（玩家静止时低优先级铺根，默认开）。 */
    public static boolean chunkPrefetchIdleEnabled;
    /** idle 预生成半径（区块）。 */
    public static int chunkPrefetchIdleRadius;
    /** idle 每 tick 预铺上限（平均速率）。 */
    public static int chunkPrefetchIdlePerTick;
    /** 进入 idle：连续 ticks 位移 < 2 块。 */
    public static int chunkPrefetchIdleEnterTicks;
    /** 退出 idle 阈值（保留兼容；现按任意跨块退出）。 */
    public static int chunkPrefetchIdleExitBlocks;
    /** processQueue 按需唤醒（marshal 请求在 tick 间及时服务；纯延迟优化，默认开）。 */
    public static boolean processQueueWake;
    /** 整服熔断（默认关）：连续 3 次 barrier 硬超时后,整个并行引擎退原版串行(重启恢复)。 */
    public static boolean onFaultFallbackVanilla;
    /** 生物间碰撞开关（默认关）：关闭后生物不阻挡、可重叠穿行；玩家碰撞保留。 */
    public static boolean mobCollisionEnabled;
    /** 玩家是否也参与碰撞关闭（谨慎选项，默认关）。 */
    public static boolean mobCollisionPlayersAffected;
    /** 区块环境 tick(随机/流体)并行（默认关）：主线程 tickChunks 扇出到独立子任务池。 */
    public static boolean chunkEnvParallel;
    /** 区块环境并行子任务池大小（0=auto=CPU）。 */
    public static int chunkEnvThreads;
    /** 区块环境并行 3×3 区块锁（默认开，互斥相邻 chunk 并发写）。 */
    public static boolean chunkEnvLock;
    /** 实体批并行（默认关）：region worker 实体阶段内扇出到独立子任务池。 */
    public static boolean entityBatchParallel;
    /** 实体批并行子任务池大小（0=auto=max(2, CPU-region_count)）。 */
    public static int entityBatchThreads;
    /** 实体批并行白名单（显式放行 modded 类，registry key 或前缀*）。 */
    public static List<String> entityBatchAllow = new ArrayList<>();
    /** 实体批并行黑名单（强制排除，优先级高于白名单）。 */
    public static List<String> entityBatchDeny = new ArrayList<>();
    /** 异步传送门（默认关）：worker 上目标维度区块未 FULL 时提交异步加载并延后一 tick。 */
    public static boolean portalAsync;
    /** 动态区域自动扩容：按负载周期性地调整区域数。 */
    public static boolean regionAutoScale;
    public static long regionScaleIntervalSeconds;
    public static double regionScaleHighMspt;
    public static double regionScaleLowMspt;
    public static int regionScaleStablePeriods;
    public static int regionScaleMin;
    public static int regionScaleMax;
    public static double regionScaleCrossReadRatio;
    /** 不等宽条带：组内边界重平衡总开关（默认关）。 */
    public static boolean unevenStripes;
    public static long rebalanceIntervalSeconds;
    public static int rebalanceMaxMoves;
    public static int rebalanceMinGroups;
    public static double rebalanceImbalanceRatio;
    /** worker 世界访问策略：off（关闭）/ stats（只统计，默认）/ enforce（测服定位用）。 */
    public static ThreadPolicy threadPolicy;
    /** 违规日志每分钟每类限流条数（>0）。 */
    public static int violationLogPerMinute;
    /** [诊断] MAIN_ONLY 违规调用栈追踪：owner 类名含该子串时抓一次栈（空=关闭，默认关）。 */
    public static String threadPolicyTraceClass;
    /** 自动路由：auto（违规学习）/ manual（只认前缀种子 + force/allow 列表）。 */
    public static String mainThreadRouting;
    /** 时间窗内 MAIN_ONLY 违规达到该次数即把实体类路由主线程（0 = 禁用学习）。 */
    public static int routeThreshold;
    /** 违规学习窗口（tick，默认 2400 = 2 分钟）。 */
    public static long routeWindowTicks;
    /** MAIN_ONLY_READ 是否计入 auto-route 窗口。worker 读 BE 恒返回 null（vanilla 语义），
     *  默认 true = 保持现有行为；false = 只按写路由（读为 null-安全降级，不路由）。 */
    public static boolean routeOnRead;
    /** 跨区引用探针：采样 worker 对方块实体的访问，按调用方分桶成区内/跨区（默认关）。 */
    public static boolean crossrefProbe;
    /** 值快照：探针命中容器类方块实体时读时复制，做影子验证（默认关）。 */
    public static boolean crossrefValueSnapshot;
    /** 影子快照单条缓存（证伪实验，默认关）：需同时开 crossref-value-snapshot 才有影子流量，否则无效果。 */
    public static boolean crossrefSnapshotCache;
    /** B4: worker 上实体撞 Create 传送带时把 passenger 注册延迟到主线程执行，
     *  使 villager 等回并行的实体仍能被传送带运输。默认 false（配合 route-on-read=false 使用）。 */
    public static boolean beltPassengerDefer;
    /** 强制主线程 tick 的类名/前缀（优先级最高）。 */
    public static List<String> mainThreadEntityForce;
    /** 强制不路由的类名/前缀（危险调试用，覆盖学习与种子）。 */
    public static List<String> mainThreadEntityAllow;
    /** SBW 空车休眠（S2.11 §2.16，默认关）：静止无乘客的非残骸 SBW 载具在调度层
     *  降为心跳 tick（本 tick 跳过 dispatch）。只作用于 SBW 载具，其它模组实体零影响。 */
    public static boolean sbwVehicleSleep;
    /** 空车休眠心跳间隔（tick，默认 10 = 0.5 秒一跳，被推/上车/受击经心跳轮询恢复）。 */
    public static int sbwVehicleSleepInterval;
    /** 优雅停机时把本会话学到的路由追加写回配置文件（默认关，后续版本启用）。 */
    public static boolean persistLearnedRoutes;
    /** 跨区写 journal 每区域队列上限（最旧条目丢弃，默认 4096）。 */
    public static int journalMaxPerRegion;
    /** 跨区写 journal LWW 去重——同 pos 未应用条目合并为最新一条（默认开；关=逐条排队旧行为）。 */
    public static boolean journalLwwDedup;
    /** 跨区写 journal 每维度每 tick 提交上限（超出丢弃计 budgetDropped，下 tick 调用方自然重提；0=不限，默认 512）。 */
    public static int journalMaxPerTick;
    /** 串行回退路径（红石网络优化 mod 在位）的方块 tick：在维度 worker 上执行时延迟到主线程 POST（默认开；关=回到 inline worker 旧行为）。 */
    public static boolean blockTickMainThreadWhenSerialized;
    /** read-your-writes overlay 总开关（默认关，仅预留接口，未接入方块读路径）。 */
    public static boolean journalReadBack;
    /** 确定性模式：跨区 journal 按区域序在主调度线程统一应用（默认关）。 */
    public static boolean determinismMode;
    /** BE 三档调度：允许在区域 worker 上 tick 的方块实体类型（registry key 或前缀*）。 */
    public static List<String> beParallelAllow;
    /** BE 三档调度：强制主线程 tick 的方块实体类型（优先级最高）。 */
    public static List<String> beMainThreadForce;
    /** Create 长轨道假轨光栅化分摊（默认关）：每 tick 处理一条连接的一个区块。 */
    public static boolean createTrackLazySpread;
    /** 分摊时单连接每 tick 最多处理的栅格块数（默认 64）。 */
    public static int createTrackLazyChunkBlocks;

    // Reliable chunk save - WAL 预写日志（PRTS 自研可靠区块保存，默认关）
    public static boolean reliableChunkSave;
    public static long journalIntervalSeconds;
    public static int journalChunksPerTick;

    // Generation task intake budget: caps how many pending chunk-generation tasks the
    // main thread hands to the worldgen mailbox per tick, spreading the intake storm;
    // 0 = unlimited (vanilla behavior).
    public static int generationTasksPerTick;

    // Chunkgen submission window: caps how many generation tasks are submitted within a
    // rolling 2s window so worldgen completions arrive at a steady rate instead of a
    // storm; 0 = unlimited.
    public static int chunkgenInflightLimit;

    // 堆压力卫兵：committed/max 超阈值时压缩生成提交预算，让 GC 追回内存
    //（登录/预热 chunk 加载风暴把 committed 顶到 Xmx 导致 RSS 超面板限制被杀）。
    public static boolean generationMemoryGuardEnabled;
    public static double generationMemoryGuardThrottleRatio;
    public static double generationMemoryGuardPauseRatio;

    // 区域 worker 每阶段时间片（ms）：实体/方块刻/BE 循环到点让出，剩余丢弃
    // 计 droppedWork（下 tick dispatch 重新入队，与原版省略 tick 同谱系）。
    public static long regionWorkerSliceMs;

    // 维度 worker 每 tick 方块/流体计划 tick 预算（0 = vanilla 65536）。岩浆扩散
    // backlog 会让 worker 单 tick 磨数分钟，主线程 barrier 干等被用户视为卡死。
    public static int workerTickBudget;

    // 无玩家维度每 barrier 会话最多 tick 数（1 = 单 tick）：多 tick 批量消化
    // backlog，维度时钟跑快 N 倍（机器加速）；有玩家维度恒 1，防世界速度漂移。
    public static int dimensionWorkerMultitick;

    // 多 tick 会话墙钟上限（ms）：到点停（当前 tick 完成即止），防 barrier 超时。
    public static long dimensionWorkerSessionMs;

    // 主线程 POST 各 drain 的时间预算（ms）：岩浆机背压大时延迟队列积压数万条，
    // 无界清空会把主线程卡死数十秒；到点即停，剩余顺延下一会话。
    public static long postDrainBudgetMs;

    // Barrier semantics: make the vanilla watchdog barrier-aware so a main thread waiting
    // in the dimension barrier is not falsely killed after max-tick-time; false = vanilla
    // watchdog behavior.
    public static boolean barrierWatchdogAware;
    /** Barrier await timeout ms; on expiry dump all threads and crash with a report. */
    public static long barrierTimeoutMs;
    /**
     * 每次 {@code drainScheduleTasks} 的时间预算（毫秒）。风暴期新生块大量灌入计划
     * tick（液体扩散回环）时，无界清空会卡死维度 tick 线程直到屏障超时崩溃；
     * 超限部分顺延下一 tick 的 drain（ScheduledTick 的绝对触发时刻不变，最多少数
     * tick 晚落地几 tick，与原版高负载下的延迟行为同向）。
     */
    public static long scheduleDrainBudgetMs;
    // B 组 barrier 软降级(时间切片 join, docs/2026-08-27-region-parallel-barrier-idle-fix.md §2)。
    // 主线程该 tick 已超预算(落后)时,把 barrier 等待从 barrierTimeoutMs 改为本 tick 剩余预算
    // (target - elapsed,下限 10ms),超时的 region 本轮降级:其未达成的 work 取出即丢弃
    // (计 barrier.droppedWork),存活实体下一 tick 由 dispatch 重新入队恰好 tick 一次。
    // 默认关(§7 逐个开):开启会改变低 TPS 时的 tick 完整性,必须靠 droppedRegion 遥测对比后再留用。
    public static boolean barrierSoftDegrade;
    /** B:整 tick 目标预算 ms;elapsed > 该值才激活软降级(0 = 永不激活)。 */
    public static int barrierTargetMs;
    /** §1.1:量化 barrier 等待与 join 后主线程工作(players/localTicks)的重叠空间(纯遥测,默认关)。 */
    public static boolean mainWastedMsTelemetry;
    // 硬超时行为(barrierTimeoutMs 超时后):degrade = ERROR+dump + 该维度转主线程串行 + 自动恢复;crash = 崩服。
    public enum BarrierTimeoutAction { CRASH, DEGRADE }
    /** 硬超时后的行为(默认 degrade)。 */
    public static BarrierTimeoutAction barrierTimeoutAction = BarrierTimeoutAction.DEGRADE;
    /** degraded 状态自动恢复并行所需的连续正常 tick 数。 */
    public static int barrierTimeoutRecoverTicks = 6000;

    /** 区块状态机任务挂起看门狗：挂起超过该毫秒数就重评估一次（0=关）。 */
    public static long chunkSystemParkWatchdogMs = 20000L;

    /**
     * 任务持续挂起超过该毫秒数仍未唤醒即排空并结算，避免依赖图永久滞留（0=关）。
     * 排空会让等待该状态的依赖方判死（级联），因此必须远大于正常挂起时长：
     * 生成风暴下 20~60s 的挂起属正常（实测 oldestPark=59s），故默认 5 分钟。
     */
    public static long chunkSystemParkDrainMs = 300000L;

    // Lighting - per-tick light propagation budget + telemetry (PRTS 光照预算化).
    // 限制每 tick 光照传播工作量，风暴时超出部分顺延下一 tick（最终光照一致，只是延迟）。
    public static boolean lightBudgetEnabled;
    /** 每 tick 最多传播的方块数（0 = 不限/vanilla）。 */
    public static int lightBudgetPerTick;
    /** 采集光照队列长度/耗时进 AsyncTaskStats（[light-engine] 日志）。 */
    public static boolean lightTelemetryEnabled;
    /** 独立光照线程——light 邮箱 + 任务排序器迁出共享后台池，
     *  隔离光照传播与 worldgen 线程池争抢（每维度一个守护线程，默认关）。 */
    public static boolean lightThreadEnabled;

    /** 区块系统调度器：驱动原版生成 future 链，启动期生效。 */
    public static boolean chunkSystemSchedulerEnabled;
    /** 调度器 worker 线程数。 */
    public static int chunkSystemSchedulerWorkers;
    /** 调度器锁域半径；feature 阶段存在跨区块写，过小会触发并发写。 */
    public static int chunkSystemSchedulerLockRadius;
    /** 两阶段锁域拆分：FEATURES 前只锁中心块，进入前换大锁续跑。 */
    public static boolean chunkSystemSchedulerSplitStages;
    /** 依赖门控：层内 future 全完成后才重新入队，减少空转。 */
    public static boolean chunkSystemSchedulerDepGating;
    /** 区块系统状态机：细粒度单区块×单状态任务图，启动期生效。 */
    public static boolean chunkSystemEnabled;
    /** 主线程边界 fail-fast 守卫。 */
    public static boolean chunkSystemFailFastGuards;
    /** IO 反序列化移出主线程。 */
    public static boolean chunkAsyncIoEnabled;
    /** 反序列化线程数（固定 1，保持原语义）。 */
    public static int chunkDeserializeThreads;
    /** World.random 跨线程检测模式：warn=限流日志，throw=抛异常。 */
    public static String worldgenRandomCheck;

    // GAP 遥测探针（M4 插桩 · 独立分支 feat/gap-telemetry · 全部默认 false，零语义变更）。
    // 与既有计数器/既有 status 行格式解耦：新读数一律走新增行（ChunkStages/Probe）或新增子命令
    // （eventattr / topapi / eventstorm）。默认关闭 => 只留一次 volatile 读，探针开销仅在同臂
    // 「开/关」对照窗口内存在。
    /** GAP-1 分阶段计时：A–F 六段服务时间（纳秒分辨率）+ A 臂端到端（FIND-g02）。 */
    public static boolean chunkStepTelemetryEnabled;
    /** GAP-1 等待分栏：queue/lock/barrier 与六段服务时间分栏（服务时间不得混入等待）。 */
    public static boolean chunkStepTelemetryWaitSplitEnabled;
    /** GAP-4 事件监听器 per-mod 归因：按 (事件类, 归属) 聚合 nanos/count（GAP-6 复用）。 */
    public static boolean eventListenerAttributionEnabled;
    /** GAP-4 周期线程普查：Thread.getAllStackTraces() 快照 + 三桶栈采样（不碰线程创建路径）。 */
    public static boolean modThreadCensusEnabled;
    /** GAP-5 宿主 API 调用者归因：按 (api, caller) 计数（主线程口径可单列）。 */
    public static boolean hostApiAttributionEnabled;
    /** GAP-5 采样步长：每 N 次调用做一次 StackWalker 归因；0 = 关闭该探针（FIND-g08）。 */
    public static int hostApiAttributionSampleEvery = 64;

    // Entity spatial index - EntitySection 内懒 4×4×4 子格索引（默认开，2026-08-16 真机 A/B 验证）。
    // 加速纯空间 AABB 查询（getEntities(AABB)）与 typed 查询（getEntitiesOfClass 等，二期：
    // vanilla 类列表按覆盖格子预筛，顺序/语义与原版逐位一致）；玩家交互路径不动；
    // 返回顺序与原版一致（插入序号排序）；对 Lithium/Canary/Radium/Recruits 让位。
    public static boolean entitySpatialIndexEnabled;
    /** section 实体数达到该值才建索引（小 section 走原版线性扫描，零成本）。 */
    public static int entitySpatialIndexMinSectionSize;
    /** 采集查询/候选数进 AsyncTaskStats（[entity-spatial-index] 日志）。 */
    public static boolean entitySpatialIndexTelemetryEnabled;

    // POI query fast path - PoiManager.getInChunk 空 chunk 存在性预检（默认开，2026-08-16 落地）。
    // 1.21.1 的 PoiSection 已按 PoiType 分桶（vanilla 自带），剩余成本 = 查询范围内大量无 POI
    // 区块的全垂直 section 扫描；本优化维护「区块是否有 POI」位掩码，无 POI 区块直接跳过。
    // 语义零变化（跳过空区块 = 结果集不变）；冷区块（未读盘）保持原版 getOrLoad 语义。
    public static boolean poiQueryEnabled;
    /** 采集命中/跳过计数进 AsyncTaskStats（[poi-query] 日志）。 */
    public static boolean poiQueryTelemetryEnabled;

    // Collision batch - Entity.collide 上台阶分支二次收集去重（默认开，2026-08-16 落地）。
    // 1.21.1 collideBoundingBox 已「一次收集、逐轴 clip」；但 step-up 分支会对扩展区域再次
    // 全量 collectColliders（走路生物每次地面移动都付）。本优化在同一 collide 帧内缓存首次
    // 收集结果，step-up 只增量补取顶部条带。语义零变化（补集合并 = 全量结果）。
    // 对 Lithium/Canary/Radium 让位（它们重写同一条碰撞路径）。
    public static boolean collisionBatchEnabled;
    /** 采集命中/增量/全量计数进 AsyncTaskStats（[collision-batch] 日志）。 */
    public static boolean collisionBatchTelemetryEnabled;

    // Menu broadcast precheck - AbstractContainerMenu.broadcastChanges 全等预检短路（默认关）。
    // 1.21.1 的 broadcastChanges 每 tick 对每个打开菜单全量遍历所有槽位：每槽 getItem +
    // requireNonNull + memoize lambda 分配，随后 triggerSlotListeners/synchronizeSlotToRemote
    // 内部都做 lastSlots diff（无变化时纯浪费，但 lambda 已分配）。本优化在 HEAD 用与原版
    // 逐条等价的判定（lastSlots/remoteCarried/dataSlots 值缓存）预检：全部相等 = 原版循环
    // 必然无动作，直接跳过整个循环。语义逐位一致（预检不是脏槽跟踪，是全量 diff 的提前
    // 等价物——mod 直写容器同样被同一 diff 捕获，零漏检）。默认关：定位「先实测归因」，
    // 生产服 spark 确认 broadcastChanges 子树占比后再开。
    public static boolean menuBroadcastEnabled;
    /** 采集短路/全量/槽位检查数进 AsyncTaskStats（[menu-broadcast] 日志）。 */
    public static boolean menuBroadcastTelemetryEnabled;

    // Event bridge on-demand registration.
    // Arclight 的 5 个 Forge 桥 dispatcher 从「启动时无条件注册」改为按「有插件在听对应
    // Bukkit 事件」按需注册/注销（SimplePluginManager 注册/注销路径挂钩，0→1 注册、1→0
    // 注销）。无插件监听的服务器上 Forge 事件照发、mod 监听器照收，只是桥自己的监听器
    // 不在总线上——桥开销（CraftBlock/事件构造 + 空派发 + 回写）整块归零。
    // 防御层：dispatcher 入口 O(1) 空监听器预检（HandlerList 空则跳过构造+派发）。
    // 兼容约束：事件数量与时机零变化（只动 Arclight 自己的监听器是否在总线）。
    public static boolean eventBridgeOnDemandEnabled;
    /** 恢复 mod 加载期常驻注册（顺序敏感场景的逃生门）。 */
    public static boolean eventBridgeEagerRegistration;
    /** 采集转发/跳过/注册注销计数进 [event-bridge] 日志。 */
    public static boolean eventBridgeTelemetryEnabled;

    // Event short-circuit.
    // EntityTickEvent（每实体每 tick ×2，频率之王）与 NeighborNotifyEvent（结果被丢弃）
    // 在无监听器时短路掉事件构造与 post——零语义风险（无监听器 = Pre 恒未取消 = tick 照跑；
    // onNeighborNotify 的 isCanceled 结果原代码直接丢弃）。
    public static boolean eventShortcircuitEntityTickEnabled;
    public static boolean eventShortcircuitNeighborNotifyEnabled;
    /** 采集短路/转发计数进 [event-shortcircuit] 日志。 */
    public static boolean eventShortcircuitTelemetryEnabled;
    /** 直接调用点短路：callBlockFormEvent（BlockFormEvent/EntityBlockFormEvent）无监听器时返回 null。 */
    public static boolean eventShortcircuitBlockFormEnabled;
    /** 刷怪事件短路：MobSpawnEvent.PositionCheck / MobDespawnEvent 无监听器时跳过构造与派发（内联原版判定结果）。 */
    public static boolean eventShortcircuitMobSpawnEnabled;

    /** 幂等守卫：主维度 ChunkMap 在 createLevels HEAD 就需要本配置，
     *  早加载后 PRTSFeatures.start() 再次调用直接跳过。 */
    private static boolean initialized;

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        File file = new File("prts-features.yml");
        // locale 决定配置文件注释语言（zh_cn=中文默认，en_us=英文）
        String locale = "zh_cn";
        if (file.exists()) {
            String v = YamlConfiguration.loadConfiguration(file).getString("locale", "");
            if (v != null && v.trim().toLowerCase(java.util.Locale.ROOT).startsWith("en")) {
                locale = "en_us";
            }
        }
        String template = locale.startsWith("en") ? TEMPLATE_EN : TEMPLATE_ZH;
        if (!file.exists()) {
            writeDefaultConfig(file, template);
        }
        // refresh comments to the chosen-locale template wording while keeping existing
        // values; runs before append so stale-copy values migrate to their template path
        normalizeConfigComments(file, template);
        // append keys still missing from the file so older configs gain new options.
        appendMissingKeys(file, template, locale.startsWith("en") ? "# auto-added missing keys" : "# 自动补充缺失的配置项");
        config = YamlConfiguration.loadConfiguration(file);
        clearItemEnabled = config.getBoolean("entity-clear.item.enabled", false);
        clearItemInterval = config.getLong("entity-clear.item.interval-seconds", 300);
        clearItemWhitelist = new ArrayList<>(config.getStringList("entity-clear.item.whitelist"));
        clearItemMsg = config.getString("entity-clear.item.message", "");
        clearMonsterEnabled = config.getBoolean("entity-clear.monster.enabled", false);
        clearMonsterInterval = config.getLong("entity-clear.monster.interval-seconds", 600);
        clearMonsterWhitelist = new ArrayList<>(config.getStringList("entity-clear.monster.whitelist"));
        clearMonsterMsg = config.getString("entity-clear.monster.message", "");
        watchdogEnabled = config.getBoolean("watchdog.enabled", false);
        watchdogThresholdMs = config.getLong("watchdog.threshold-ms", 2000);
        watchdogWarnCooldownMs = config.getLong("watchdog.warn-cooldown-ms", 60000);
        neighborUpdateBreakerEnabled = config.getBoolean("neighbor-update-breaker.enabled", true);
        neighborUpdateBreakerMaxPerTick = config.getLong("neighbor-update-breaker.max-per-tick", 200000);
        ae2ltSetWorkingThrottleEnabled = config.getBoolean("ae2lt-setworking-throttle.enabled", true);
        ae2ltSetWorkingThrottleMinTicks = config.getInt("ae2lt-setworking-throttle.min-ticks", 4);
        eventBusTelemetryEnabled = config.getBoolean("eventbus.telemetry-enabled", false);
        parallelPathfindingAsync = config.getBoolean("parallel.pathfinding-async", true);
        parallelDimension = config.getBoolean("parallel.dimension-parallel", true);
        parallelRegion = config.getBoolean("parallel.region-parallel", true);
        regionBlockEntityParallel = config.getBoolean("parallel.region-block-entity-parallel", true);
        parallelColonyPhaseStagger = config.getBoolean("parallel.colony-npc-phase-stagger", true);
        colonyNpcWorkInterval = Math.max(1, Math.min(60, config.getInt("parallel.colony-npc-work-interval", 5)));
        colonyManagerTickCacheEnabled = config.getBoolean("parallel.colony-manager-tick-cache-enabled", true);
        colonyManagerTickCacheInterval = Math.max(1, Math.min(120, config.getInt("parallel.colony-manager-tick-cache-interval", 20)));
        villagerPoiPathBudget = Math.max(0, config.getInt("parallel.villager-poi-path-budget", 0));
        mainThreadPathAsync = config.getBoolean("parallel.main-thread-path-async", true);
        treeCutterNodeBudget = Math.max(0, config.getInt("parallel.tree-cutter-node-budget", 4096));
        treeCutterTimeBudgetMs = Math.max(0, config.getLong("parallel.tree-cutter-time-budget-ms", 10));
        mainThreadEntityDrainBudget = Math.max(0, config.getInt("parallel.main-thread-entity-drain-budget", 0));
        persistLearnedRoutes = config.getBoolean("parallel.persist-learned-routes", true);
        learnedRoutesFile = config.getString("parallel.learned-routes-file", "config/prts-learned-routes.json");
        learnedRoutesLimit = Math.max(1, config.getInt("parallel.learned-routes-limit", 200));
        routeProbationEnabled = config.getBoolean("parallel.route-probation-enabled", true);
        routeProbationTicks = Math.max(100, config.getInt("parallel.route-probation-ticks", 12000));
        routeProbationMaxViolations = Math.max(0, config.getInt("parallel.route-probation-max-violations", 2));
        chunkDemandPerTick = config.getInt("parallel.chunk-demand-per-tick", 50);
        // 非正数会让需求 drain 永久不执行（budget <= 0），退回默认值。
        if (chunkDemandPerTick < 1) chunkDemandPerTick = 50;
        io.izzel.arclight.common.optimization.chunksystem.ChunkDemandQueue.maxPerTick = chunkDemandPerTick;
        chunkDemandMinDrainMs = Math.max(0, config.getInt("parallel.chunk-demand-min-drain-ms", 2));
        io.izzel.arclight.common.optimization.chunksystem.ChunkDemandQueue.minDrainMs = chunkDemandMinDrainMs;
        chunkDemandPlayerPriority = config.getBoolean("parallel.chunk-demand-player-priority", true);
        chunkDemandStarveTicks = Math.max(20, config.getInt("parallel.chunk-demand-starve-ticks", 600));
        io.izzel.arclight.common.optimization.chunksystem.ChunkDemandQueue.playerPriorityEnabled = chunkDemandPlayerPriority;
        io.izzel.arclight.common.optimization.chunksystem.ChunkDemandQueue.starveNanos = chunkDemandStarveTicks * 50_000_000L;
        chunkSendRateFloor = Math.max(0f, (float) config.getDouble("parallel.chunk-send-rate-floor", 128.0));
        LOGGER.info("parallel chunk-demand priority={} starve={} ticks", chunkDemandPlayerPriority, chunkDemandStarveTicks);
        // 玩家方向区块预取（默认关）。
        chunkPrefetchEnabled = config.getBoolean("chunk-prefetch.enabled", true);
        chunkPrefetchDepth = Math.max(1, config.getInt("chunk-prefetch.depth", 6));
        chunkPrefetchIntervalTicks = Math.max(1, config.getInt("chunk-prefetch.interval-ticks", 5));
        chunkPrefetchTimeoutTicks = Math.max(20, config.getInt("chunk-prefetch.timeout-ticks", 200));
        // 窗口预铺（depth 已废弃，保留解析兼容）。
        chunkPrefetchWindow = Math.max(4, config.getInt("chunk-prefetch.window", 16));
        chunkPrefetchWindowWidth = Math.max(3, config.getInt("chunk-prefetch.window-width", 5));
        chunkPrefetchWindowStep = Math.max(1, config.getInt("chunk-prefetch.window-step", 2));
        chunkPrefetchWindowRecomputeTicks = Math.max(10, config.getInt("chunk-prefetch.window-recompute-ticks", 40));
        chunkPrefetchPriority = Math.min(63, Math.max(56, config.getInt("chunk-prefetch.prefetch-priority", 56)));
        chunkPrefetchMaxPending = Math.max(16, config.getInt("chunk-prefetch.max-pending", 512));
        chunkPrefetchIdleEnabled = config.getBoolean("chunk-prefetch.idle-enabled", true);
        chunkPrefetchIdleRadius = Math.max(1, config.getInt("chunk-prefetch.idle-radius", 8));
        chunkPrefetchIdlePerTick = Math.max(1, config.getInt("chunk-prefetch.idle-per-tick", 16));
        chunkPrefetchIdleEnterTicks = Math.max(20, config.getInt("chunk-prefetch.idle-enter-ticks", 100));
        chunkPrefetchIdleExitBlocks = Math.max(2, config.getInt("chunk-prefetch.idle-exit-blocks", 4));
        LOGGER.info("chunk-prefetch enabled={} window={}x{} step={} recompute={} priority={} maxPending={} idle(enabled={} radius={} perTick={} enter={} exit={}) timeout={}",
                chunkPrefetchEnabled, chunkPrefetchWindow, chunkPrefetchWindowWidth, chunkPrefetchWindowStep,
                chunkPrefetchWindowRecomputeTicks, chunkPrefetchPriority, chunkPrefetchMaxPending,
                chunkPrefetchIdleEnabled, chunkPrefetchIdleRadius, chunkPrefetchIdlePerTick,
                chunkPrefetchIdleEnterTicks, chunkPrefetchIdleExitBlocks, chunkPrefetchTimeoutTicks);
        processQueueWake = config.getBoolean("parallel.process-queue-wake", true);
        loginWarmupEnabled = config.getBoolean("parallel.login-warmup-enabled", true);
        loginWarmupRadius = Math.max(0, config.getInt("parallel.login-warmup-radius", 4));
        loginWarmupPerTick = Math.max(1, config.getInt("parallel.login-warmup-per-tick", 4));
        loginWarmupCenters = parseNestedPairs(config.getString("parallel.login-warmup-centers",
                "[[472,1358],[2113,1925],[-629,-278],[-672,256],[1242,2292]]"));
        onFaultFallbackVanilla = config.getBoolean("parallel.on-fault-fallback-vanilla", true);
        mobCollisionEnabled = config.getBoolean("mob-collision.enabled", false);
        mobCollisionPlayersAffected = config.getBoolean("mob-collision.players-affected", false);
        chunkEnvParallel = config.getBoolean("parallel.chunk-env-parallel", true);
        chunkEnvThreads = Math.max(0, config.getInt("parallel.chunk-env-threads", 0));
        chunkEnvLock = config.getBoolean("parallel.chunk-env-lock", true);
        portalAsync = config.getBoolean("parallel.portal-async", true);
        entityBatchParallel = config.getBoolean("parallel.entity-batch-parallel", true);
        entityBatchThreads = Math.max(0, config.getInt("parallel.entity-batch-threads", 0));
        entityBatchAllow = parseInlineList(config.getString("parallel.entity-batch-allow", "[]"));
        entityBatchDeny = parseInlineList(config.getString("parallel.entity-batch-deny", "[]"));
        io.izzel.arclight.common.optimization.entitybatch.EntityBatchScheduler
                .configure(entityBatchAllow, entityBatchDeny);
        int count = config.getInt("parallel.region-count", 4);
        if (count < 2) count = 2;
        if (count > 16) count = 16;
        if (Integer.bitCount(count) != 1) count = 4;
        parallelRegionCount = count;
        regionAutoScale = config.getBoolean("parallel.region-auto-scale", true);
        regionScaleIntervalSeconds = config.getLong("parallel.region-scale-interval-seconds", 300);
        regionScaleHighMspt = config.getDouble("parallel.region-scale-high-mspt", 60.0);
        regionScaleLowMspt = config.getDouble("parallel.region-scale-low-mspt", 15.0);
        regionScaleStablePeriods = Math.max(1, config.getInt("parallel.region-scale-stable-periods", 2));
        regionScaleMin = clampPower(config.getInt("parallel.region-scale-min", 2), 2, 16);
        regionScaleMax = clampPower(config.getInt("parallel.region-scale-max", 8), 2, 16);
        if (regionScaleMin > regionScaleMax) regionScaleMin = regionScaleMax;
        regionScaleCrossReadRatio = Math.max(0.0, config.getDouble("parallel.region-scale-cross-read-ratio", 0.05));
        unevenStripes = config.getBoolean("parallel.uneven-stripes", true);
        rebalanceIntervalSeconds = config.getLong("parallel.rebalance-interval-seconds", 300);
        rebalanceMaxMoves = Math.max(1, config.getInt("parallel.rebalance-max-moves", 1));
        rebalanceMinGroups = Math.max(1, config.getInt("parallel.rebalance-min-groups", 1));
        rebalanceImbalanceRatio = Math.max(1.0, config.getDouble("parallel.rebalance-imbalance-ratio", 2.0));
        // worker 世界访问策略：解析失败/未知值回退 stats（只统计不拦截，生产安全）。
        // YAML 会把裸写的 off/on 解析成布尔值，这里先还原成字符串再交给 ThreadPolicy。
        Object threadPolicyValue = config.get("parallel.thread-policy", "stats");
        String threadPolicyRaw;
        if (threadPolicyValue instanceof Boolean bool) {
            threadPolicyRaw = bool ? "on" : "off";
        } else {
            threadPolicyRaw = String.valueOf(threadPolicyValue);
        }
        threadPolicy = ThreadPolicy.parse(threadPolicyRaw);
        LOGGER.info("parallel.thread-policy raw={} parsed={}", threadPolicyRaw, threadPolicy);
        if (threadPolicy == ThreadPolicy.ENFORCE) {
            LOGGER.warn("parallel.thread-policy=enforce is for test-server debugging only; violations will abort the offending entity tick");
        }
        violationLogPerMinute = Math.max(1, config.getInt("parallel.violation-log-per-minute", 20));
        threadPolicyTraceClass = config.getString("parallel.thread-policy-trace-class", "").trim();
        WorldAccessGuard.applyConfig(threadPolicy, violationLogPerMinute);
        WorldAccessGuard.setTraceClass(threadPolicyTraceClass);
        mainThreadRouting = config.getString("parallel.main-thread-routing", "auto").trim().toLowerCase(java.util.Locale.ROOT);
        if (!"auto".equals(mainThreadRouting) && !"manual".equals(mainThreadRouting)) {
            LOGGER.warn("parallel.main-thread-routing={} is invalid; falling back to auto", mainThreadRouting);
            mainThreadRouting = "auto";
        }
        routeThreshold = Math.max(0, config.getInt("parallel.route-threshold", 5));
        routeWindowTicks = Math.max(20, config.getLong("parallel.route-window-ticks", 2400));
        routeOnRead = config.getBoolean("parallel.route-on-read", true);
        beltPassengerDefer = config.getBoolean("parallel.belt-passenger-defer", true);
        mainThreadEntityForce = new ArrayList<>(config.getStringList("parallel.main-thread-entity-force"));
        mainThreadEntityAllow = new ArrayList<>(config.getStringList("parallel.main-thread-entity-allow"));
        if (mainThreadEntityForce.isEmpty()) {
            // 殖民地矿车/坐凳实体在主线程，避免 worker 上 ENTITY_MOUNT 游戏事件被 ModernFix 跳过刷屏
            mainThreadEntityForce.add("com.minecolonies.api.entity.other.MinecoloniesMinecart");
            mainThreadEntityForce.add("com.minecolonies.core.entity.other.SittingEntity");
        }
        if (mainThreadEntityAllow.isEmpty()) {
            // 默认放行村民上 worker（08-28 实锤：主线程实体时间 50.5ms -> 4.3ms）
            mainThreadEntityAllow.add("net.minecraft.world.entity.npc");
            // 默认放行殖民地 NPC 上 worker（worker 读 BE 已放行；自毁/异常由安全阀兜底回主线程）
            mainThreadEntityAllow.add("com.minecolonies.");
        }
        sbwVehicleSleep = config.getBoolean("parallel.sbw-vehicle-sleep", false);
        sbwVehicleSleepInterval = Math.max(2, Math.min(120, config.getInt("parallel.sbw-vehicle-sleep-interval", 10)));
        LOGGER.info("parallel sbw-vehicle-sleep={} interval={}", sbwVehicleSleep, sbwVehicleSleepInterval);
        persistLearnedRoutes = config.getBoolean("parallel.persist-learned-routes", false);
        ClassAffinityLedger.applyConfig(routeThreshold, routeWindowTicks, routeOnRead);
        crossrefProbe = config.getBoolean("parallel.crossref-probe", false);
        crossrefValueSnapshot = config.getBoolean("parallel.crossref-value-snapshot", false);
        crossrefSnapshotCache = config.getBoolean("parallel.crossref-snapshot-cache", false);
        io.izzel.arclight.common.optimization.ownership.CrossRefProbe.applyConfig(crossrefProbe, crossrefValueSnapshot, crossrefSnapshotCache);
        LOGGER.info("parallel crossref-probe={} value-snapshot={} snapshot-cache={}", crossrefProbe, crossrefValueSnapshot, crossrefSnapshotCache);
        LOGGER.info("parallel main-thread-routing={} threshold={} window={} ticks force={} allow={} persist={}",
                mainThreadRouting, routeThreshold, routeWindowTicks,
                mainThreadEntityForce.size(), mainThreadEntityAllow.size(), persistLearnedRoutes);
        journalMaxPerRegion = Math.max(16, config.getInt("parallel.journal-max-per-region", 4096));
        journalLwwDedup = config.getBoolean("parallel.journal-lww-dedup", true);
        journalMaxPerTick = Math.max(0, config.getInt("parallel.journal-max-per-tick", 512));
        LOGGER.info("parallel journal lww-dedup={} max-per-tick={} max-per-region={}",
                journalLwwDedup, journalMaxPerTick, journalMaxPerRegion);
        blockTickMainThreadWhenSerialized = config.getBoolean("parallel.block-tick-main-thread-when-serialized", true);
        LOGGER.info("parallel block-tick-main-thread-when-serialized={}", blockTickMainThreadWhenSerialized);
        journalReadBack = config.getBoolean("parallel.journal-read-back", false);
        determinismMode = config.getBoolean("parallel.determinism-mode", true);
        beParallelAllow = new ArrayList<>(config.getStringList("parallel.be-parallel-allow"));
        if (beParallelAllow.isEmpty()) {
            // 默认放行 Create BE 上区域 worker（08-28 实验 F 验证：动画正常、主线程释放）
            beParallelAllow.add("create:*");
        }
        beMainThreadForce = new ArrayList<>(config.getStringList("parallel.be-main-thread-force"));
        if (beMainThreadForce.isEmpty()) {
            // 实测：create:track 单次 tick 最大 342ms（列车图全局计算），铁主线程。
            beMainThreadForce.add("create:track");
            // 灰度实测：lootr:lootr_chest 在 worker 上复现 ReportedException
            // （08-16 13:18），安全阀兜住后永久 unsafe——默认直接锁主线程。
            beMainThreadForce.add("lootr:lootr_chest");
            // 红石图网络（RedstoneLinkNetworkHandler）主线程专用：worker 并行 tick
            // 读网络导致无线红石信号激活不了红石装置（08-29 实测 main_only_read），锁主线程。
            beMainThreadForce.add("create:redstone_link");
        }
        createTrackLazySpread = config.getBoolean("parallel.create-track-lazy-spread", true);
        createTrackLazyChunkBlocks = Math.max(8, Math.min(512, config.getInt("parallel.create-track-lazy-chunk-blocks", 64)));
        LOGGER.info("parallel be-policy allow={} force={} parallelEnabled={} trackLazySpread={} chunk={} villagerPoiBudget={}",
                beParallelAllow, beMainThreadForce, regionBlockEntityParallel,
                createTrackLazySpread, createTrackLazyChunkBlocks, villagerPoiPathBudget);
        reliableChunkSave = config.getBoolean("reliable-chunk-save.enabled", false);
        journalIntervalSeconds = config.getLong("reliable-chunk-save.interval-seconds", 30);
        journalChunksPerTick = config.getInt("reliable-chunk-save.chunks-per-tick", 50);
        generationTasksPerTick = config.getInt("generation-tasks-per-tick", 50);
        if (generationTasksPerTick < 0) generationTasksPerTick = 0;
        chunkgenInflightLimit = config.getInt("chunkgen-inflight-limit", 128);
        if (chunkgenInflightLimit < 0) chunkgenInflightLimit = 0;
        generationMemoryGuardEnabled = config.getBoolean("generation-memory-guard-enabled", true);
        generationMemoryGuardThrottleRatio = Math.min(0.95, Math.max(0.3,
                config.getDouble("generation-memory-guard-throttle-ratio", 0.65)));
        generationMemoryGuardPauseRatio = Math.min(0.99, Math.max(0.4,
                config.getDouble("generation-memory-guard-pause-ratio", 0.85)));
        workerTickBudget = config.getInt("parallel.worker-tick-budget", 4096);
        if (workerTickBudget < 0) workerTickBudget = 0;
        dimensionWorkerMultitick = Math.max(1, config.getInt("parallel.dimension-worker-multitick", 4));
        dimensionWorkerSessionMs = Math.max(500, config.getLong("parallel.dimension-worker-session-ms", 8000L));
        postDrainBudgetMs = Math.max(50, config.getLong("parallel.post-drain-budget-ms", 1000L));
        regionWorkerSliceMs = Math.max(50, config.getLong("parallel.region-worker-slice-ms", 2000L));
        barrierWatchdogAware = config.getBoolean("barrier-watchdog-aware", true);
        barrierTimeoutMs = config.getLong("barrier-timeout-ms", 120000L);
        if (barrierTimeoutMs < 1000L) barrierTimeoutMs = 120000L;
        scheduleDrainBudgetMs = config.getLong("schedule-drain-budget-ms", 8L);
        if (scheduleDrainBudgetMs < 1L) scheduleDrainBudgetMs = 8L;
        // B 组:barrier 软降级(时间切片 join)。默认关——改动 tick 完整性语义,须遥测对比后逐个开。
        barrierSoftDegrade = config.getBoolean("parallel.barrier-soft-degrade", true);
        barrierTargetMs = Math.max(0, config.getInt("parallel.barrier-target-ms", 50));
        mainWastedMsTelemetry = config.getBoolean("parallel.main-wasted-ms-telemetry", false);
        LOGGER.info("parallel barrier-soft-degrade={} target-ms={} main-wasted-ms-telemetry={}",
                barrierSoftDegrade, barrierTargetMs, mainWastedMsTelemetry);
        String barrierTimeoutActionRaw = config.getString("parallel.barrier-timeout-action", "degrade");
        barrierTimeoutAction = "crash".equalsIgnoreCase(barrierTimeoutActionRaw)
                ? BarrierTimeoutAction.CRASH : BarrierTimeoutAction.DEGRADE;
        barrierTimeoutRecoverTicks = Math.max(1, config.getInt("parallel.barrier-timeout-recover-ticks", 6000));
        LOGGER.info("parallel barrier-timeout-action={} recover-ticks={}",
                barrierTimeoutAction, barrierTimeoutRecoverTicks);
        chunkSystemParkWatchdogMs = Math.max(0L, config.getLong("parallel.chunk-system-park-watchdog-ms", 20000L));
        chunkSystemParkDrainMs = Math.max(0L, config.getLong("parallel.chunk-system-park-drain-ms", 300000L));
        if (chunkSystemParkDrainMs > 0L && chunkSystemParkWatchdogMs > 0L
                && chunkSystemParkDrainMs < chunkSystemParkWatchdogMs) {
            chunkSystemParkDrainMs = chunkSystemParkWatchdogMs;
        }
        LOGGER.info("parallel chunk-system park watchdog={}ms drain={}ms",
                chunkSystemParkWatchdogMs, chunkSystemParkDrainMs);
        lightBudgetEnabled = config.getBoolean("lighting.budget-enabled", true);
        lightBudgetPerTick = config.getInt("lighting.budget-per-tick", 100000);
        if (lightBudgetPerTick < 0) lightBudgetPerTick = 0;
        lightTelemetryEnabled = config.getBoolean("lighting.telemetry-enabled", true);
        lightThreadEnabled = config.getBoolean("lighting.threaded", true);
        chunkSystemSchedulerEnabled = config.getBoolean("parallel.chunk-system-scheduler.enabled", true);
        chunkSystemSchedulerWorkers = Math.max(1, config.getInt("parallel.chunk-system-scheduler.workers", 4));
        chunkSystemSchedulerLockRadius = Math.max(0, Math.min(2, config.getInt("parallel.chunk-system-scheduler.lock-radius", 2)));
        chunkSystemSchedulerSplitStages = config.getBoolean("parallel.chunk-system-scheduler.split-stages", true);
        chunkSystemSchedulerDepGating = config.getBoolean("parallel.chunk-system-scheduler.dep-gating", true);
        chunkSystemEnabled = config.getBoolean("parallel.chunk-system-enabled", true);
        chunkSystemFailFastGuards = config.getBoolean("parallel.chunk-system-fail-fast-guards", true);
        chunkAsyncIoEnabled = config.getBoolean("parallel.chunk-async-io-enabled", true);
        // >1 会并发反序列化触发 POI rehash AIOOBE，强制收敛为 1（ChunkSystemScheduler 注释）
        chunkDeserializeThreads = Math.min(1, Math.max(1, config.getInt("parallel.chunk-deserialize-threads", 1)));
        worldgenRandomCheck = config.getString("parallel.worldgen-random-check", "warn");
        // GAP 遥测探针（M4）：全部默认 false；.sample-every 为 int，0 = 关闭（无真假语义，FIND-g08）。
        chunkStepTelemetryEnabled = config.getBoolean("parallel.chunk-step-telemetry.enabled", false);
        chunkStepTelemetryWaitSplitEnabled = config.getBoolean("parallel.chunk-step-telemetry.wait-split.enabled", false);
        eventListenerAttributionEnabled = config.getBoolean("parallel.event-listener-attribution.enabled", false);
        modThreadCensusEnabled = config.getBoolean("parallel.mod-thread-census.enabled", false);
        hostApiAttributionEnabled = config.getBoolean("parallel.hostapi-attribution.enabled", false);
        hostApiAttributionSampleEvery = config.getInt("parallel.hostapi-attribution.sample-every", 64);
        if (hostApiAttributionSampleEvery < 0) {
            hostApiAttributionSampleEvery = 0;
        }
        // 探针本体不读配置（热路径零解析）：启动期把开关推给各自的统计类。
        io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming.ENABLED = chunkStepTelemetryEnabled;
        io.izzel.arclight.common.optimization.chunksystem.ChunkStageTiming.WAIT_SPLIT_ENABLED = chunkStepTelemetryWaitSplitEnabled;
        io.izzel.arclight.common.optimization.eventbridge.EventAttributionStats.setEnabled(eventListenerAttributionEnabled);
        io.izzel.arclight.common.optimization.eventbridge.ModThreadCensus.setEnabled(modThreadCensusEnabled);
        io.izzel.arclight.common.optimization.ownership.HostApiAttribution.setEnabled(hostApiAttributionEnabled, hostApiAttributionSampleEvery);
        LOGGER.info("parallel gap-telemetry probes chunkStages={} waitSplit={} eventAttr={} threadCensus={} hostApi={} hostApiSampleEvery={}",
                chunkStepTelemetryEnabled, chunkStepTelemetryWaitSplitEnabled, eventListenerAttributionEnabled,
                modThreadCensusEnabled, hostApiAttributionEnabled, hostApiAttributionSampleEvery);
        entitySpatialIndexEnabled = config.getBoolean("entity-spatial-index.enabled", true);
        entitySpatialIndexMinSectionSize = config.getInt("entity-spatial-index.min-section-size", 16);
        if (entitySpatialIndexMinSectionSize < 4) entitySpatialIndexMinSectionSize = 4;
        entitySpatialIndexTelemetryEnabled = config.getBoolean("entity-spatial-index.telemetry-enabled", true);
        io.izzel.arclight.common.optimization.entityspatial.EntitySpatialIndexStats.setEnabled(entitySpatialIndexTelemetryEnabled);
        poiQueryEnabled = config.getBoolean("poi-query.enabled", true);
        poiQueryTelemetryEnabled = config.getBoolean("poi-query.telemetry-enabled", true);
        io.izzel.arclight.common.optimization.poi.PoiQueryStats.setEnabled(poiQueryTelemetryEnabled);
        collisionBatchEnabled = config.getBoolean("collision-batch.enabled", true);
        collisionBatchTelemetryEnabled = config.getBoolean("collision-batch.telemetry-enabled", true);
        io.izzel.arclight.common.optimization.collision.CollisionBatchStats.setEnabled(collisionBatchTelemetryEnabled);
        menuBroadcastEnabled = config.getBoolean("menu-broadcast.enabled", false);
        menuBroadcastTelemetryEnabled = config.getBoolean("menu-broadcast.telemetry-enabled", true);
        io.izzel.arclight.common.optimization.menubroadcast.MenuBroadcastStats.setEnabled(menuBroadcastTelemetryEnabled);
        eventBridgeOnDemandEnabled = config.getBoolean("event-bridge.on-demand-registration.enabled", true);
        eventBridgeEagerRegistration = config.getBoolean("event-bridge.on-demand-registration.eager-registration", false);
        eventBridgeTelemetryEnabled = config.getBoolean("event-bridge.on-demand-registration.telemetry-enabled", true);
        io.izzel.arclight.common.optimization.eventbridge.EventBridgeStats.setEnabled(eventBridgeTelemetryEnabled);
        // enabled=false 或 eager-registration=true 都恢复「启动即全注册」旧行为；否则按门收敛。
        io.izzel.arclight.common.optimization.eventbridge.EventBridgeRegistry.setActive(
                eventBridgeOnDemandEnabled && !eventBridgeEagerRegistration);
        eventShortcircuitEntityTickEnabled = config.getBoolean("event-shortcircuit.entity-tick-event.enabled", true);
        eventShortcircuitNeighborNotifyEnabled = config.getBoolean("event-shortcircuit.neighbor-notify-event.enabled", true);
        eventShortcircuitTelemetryEnabled = config.getBoolean("event-shortcircuit.telemetry-enabled", true);
        eventShortcircuitBlockFormEnabled = config.getBoolean("event-shortcircuit.block-form-event.enabled", true);
        eventShortcircuitMobSpawnEnabled = config.getBoolean("event-shortcircuit.mob-spawn-event.enabled", true);
        io.izzel.arclight.common.optimization.eventbridge.EventShortcircuitStats.setEnabled(eventShortcircuitTelemetryEnabled);
        LOGGER.info("event-bridge on-demand={} eager={} | event-shortcircuit entityTick={} neighborNotify={}",
                eventBridgeOnDemandEnabled, eventBridgeEagerRegistration,
                eventShortcircuitEntityTickEnabled, eventShortcircuitNeighborNotifyEnabled);
    }

    /** Persist learned routes to independent JSON file (replaces old YAML append logic). */
    public static void persistLearnedRoutes() {
        try {
            io.izzel.arclight.common.optimization.ownership.LearnedRoutePersistence.saveOnShutdown();
        } catch (Exception e) {
            LOGGER.error("persist-learned-routes failed", e);
        }
    }

    /** 解析 [[x,z],[x,z],...] 为 int[][]；空/非法返回空数组。 */
    private static int[][] parseNestedPairs(String value) {
        String text = value.strip();
        List<int[]> result = new ArrayList<>();
        if (!text.startsWith("[") || !text.endsWith("]")) {
            return new int[0][];
        }
        text = text.substring(1, text.length() - 1);
        int idx = 0;
        while (idx < text.length()) {
            int open = text.indexOf('[', idx);
            if (open < 0) {
                break;
            }
            int close = text.indexOf(']', open);
            if (close < 0) {
                break;
            }
            String pair = text.substring(open + 1, close).trim();
            String[] parts = pair.split(",");
            if (parts.length == 2) {
                try {
                    result.add(new int[]{Integer.parseInt(parts[0].trim()),
                            Integer.parseInt(parts[1].trim())});
                } catch (NumberFormatException ignored) {
                    // 忽略非法项
                }
            }
            idx = close + 1;
        }
        return result.toArray(new int[0][]);
    }

    private static List<String> parseInlineList(String value) {
        String text = value.strip();
        List<String> result = new ArrayList<>();
        if (!text.startsWith("[") || !text.endsWith("]")) {
            return result;
        }
        text = text.substring(1, text.length() - 1);
        if (text.isBlank()) {
            return result;
        }
        for (String part : text.split(",")) {
            String item = part.strip().replace("\"", "").replace("'", "");
            if (!item.isEmpty()) {
                result.add(item);
            }
        }
        return result;
    }

    private static int clampPower(int v, int lo, int hi) {
        if (v <= 1) return lo;
        int p = 1;
        while (p < v) p <<= 1;
        return Math.max(lo, Math.min(hi, p));
    }

    /** Append template keys missing from the file (upgrades add new options without touching existing values). */
    /** Rebuild the config file from the english template, replacing template values
     *  with existing file values where present. Keys absent from the template are
     *  appended at the end. Runs once per startup. */
    private static void normalizeConfigComments(File file, String template) {
        try {
            YamlConfiguration curCfg = YamlConfiguration.loadConfiguration(file);
            java.util.Set<String> known = new java.util.LinkedHashSet<>(curCfg.getKeys(true));
            // leaf -> single template path map (used to relocate stale copies whose leaf
            // matches exactly one template key, e.g. an old normalize bug appended
            // "chunk-prefetch.window" as a flat 2-space line under the last section).
            java.util.Map<String, String> leafPaths = templateLeafPaths(template);
            java.util.Map<String, Object> migrated = new java.util.HashMap<>();
            for (String key : known) {
                String leaf = key.substring(key.lastIndexOf('.') + 1);
                String tplPath = leafPaths.get(leaf);
                if (tplPath != null && !tplPath.isEmpty() && !tplPath.equals(key)) {
                    migrated.putIfAbsent(leaf, curCfg.get(key));
                }
            }
            StringBuilder sb = new StringBuilder();
            String[] stack = new String[24];
            for (String line : template.split(System.lineSeparator())) {
                String stripped = line.strip();
                if (stripped.isEmpty() || stripped.startsWith("#")) {
                    // keep template comments and blank lines verbatim (text block already
                    // strips the common java indent, so the line is emitted as-is)
                    sb.append(line).append(System.lineSeparator());
                    continue;
                }
                if (stripped.startsWith("-")) {
                    // list items come from the parent key value; skip template items
                    continue;
                }
                int lead = line.length() - line.stripLeading().length();
                int level = lead / 2;
                String key = stripped.substring(0, stripped.indexOf(':')).trim();
                stack[level] = key;
                for (int i = level + 1; i < stack.length; i++) {
                    stack[i] = null;
                }
                StringBuilder path = new StringBuilder();
                for (int i = 0; i <= level; i++) {
                    if (stack[i] == null) {
                        break;
                    }
                    if (path.length() > 0) {
                        path.append('.');
                    }
                    path.append(stack[i]);
                }
                String full = path.toString();
                String indent = "  ".repeat(level);
                String comment = stripped.contains("#")
                        ? " " + stripped.substring(stripped.indexOf('#')).strip() : "";
                if (known.contains(full)) {
                    // existing key: emit file value + template comment (section keys bare)
                    if (curCfg.isConfigurationSection(full)) {
                        sb.append(indent).append(key).append(":").append(System.lineSeparator());
                    } else {
                        Object v = curCfg.get(full);
                        sb.append(indent).append(key).append(": ").append(serialize(v))
                                .append(comment).append(System.lineSeparator());
                    }
                } else {
                    // absent key: use migrated stale-copy value when a unique template
                    // leaf exists, else the template default; always template comment
                    String val = stripped.substring(stripped.indexOf(':') + 1).strip();
                    int hash = val.indexOf('#');
                    if (hash >= 0) {
                        val = val.substring(0, hash).strip();
                    }
                    Object migratedVal = migrated.get(key);
                    if (migratedVal != null && !curCfg.isConfigurationSection(full)) {
                        val = serialize(migratedVal);
                    }
                    sb.append(indent).append(key).append(": ").append(val)
                            .append(comment).append(System.lineSeparator());
                }
            }
            // append keys present in the file but absent from the template;
            // stale copies whose leaf resolves to a unique template path are dropped
            // (their value was migrated to the template path above)
            for (String key : known) {
                if (templateHasKey(key, template)) {
                    continue;
                }
                if (curCfg.isConfigurationSection(key)) {
                    continue; // section ancestors are covered by their leaves
                }
                String leaf = key.substring(key.lastIndexOf('.') + 1);
                if (leafPaths.containsKey(leaf)) {
                    continue; // stale copy, value migrated to its template path
                }
                Object v = curCfg.get(key);
                String indent = key.contains(".") ? "  " : "";
                sb.append(indent).append(leaf).append(": ").append(serialize(v))
                        .append(System.lineSeparator());
            }
            java.nio.file.Files.writeString(file.toPath(), sb.toString(),
                    java.nio.charset.StandardCharsets.UTF_8);
            LOGGER.info("[PRTS-Features] normalized config comments (locale template)");
        } catch (Exception ex) {
            LOGGER.warn("Failed to normalize config comments", ex);
        }
    }

    /** Map every leaf name to its single template path (absent for ambiguous leaves). */
    private static java.util.Map<String, String> templateLeafPaths(String template) {
        java.util.Map<String, String> out = new java.util.HashMap<>();
        String[] stack = new String[24];
        for (String line : template.split(System.lineSeparator())) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("-")) {
                continue;
            }
            int lead = line.length() - line.stripLeading().length();
            int level = lead / 2;
            String k = stripped.substring(0, stripped.indexOf(':')).trim();
            stack[level] = k;
            for (int i = level + 1; i < stack.length; i++) {
                stack[i] = null;
            }
            StringBuilder path = new StringBuilder();
            for (int i = 0; i <= level; i++) {
                if (stack[i] == null) {
                    break;
                }
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(stack[i]);
            }
            String full = path.toString();
            String prev = out.put(k, full);
            if (prev != null && !prev.equals(full)) {
                out.put(k, ""); // ambiguous leaf (e.g. "enabled"): present, no migration target
            }
        }
        return out;
    }

    /** True when the template contains a line whose full key path equals {@code key}. */
    private static boolean templateHasKey(String key, String template) {
        String[] stack = new String[24];
        for (String line : template.split(System.lineSeparator())) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("-")) {
                continue;
            }
            int lead = line.length() - line.stripLeading().length();
            int level = lead / 2;
            String k = stripped.substring(0, stripped.indexOf(':')).trim();
            stack[level] = k;
            for (int i = level + 1; i < stack.length; i++) {
                stack[i] = null;
            }
            StringBuilder path = new StringBuilder();
            for (int i = 0; i <= level; i++) {
                if (stack[i] == null) {
                    break;
                }
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(stack[i]);
            }
            if (path.toString().equals(key)) {
                return true;
            }
        }
        return false;
    }

        private static void appendMissingKeys(File file, String template, String addHeader) {
        try {
            // merge template keys with existing file; append missing subtrees as valid
            // YAML via saveToString, attaching template comments to leaf lines.
            YamlConfiguration tplCfg = YamlConfiguration.loadConfiguration(
                    new java.io.StringReader(template));
            YamlConfiguration curCfg = YamlConfiguration.loadConfiguration(file);
            java.util.Set<String> missingLeaves = new java.util.LinkedHashSet<>();
            for (String key : tplCfg.getKeys(true)) {
                if (!curCfg.contains(key)) {
                    missingLeaves.add(key);
                }
            }
            if (missingLeaves.isEmpty()) {
                return;
            }
            // group leaves by nearest existing ancestor so we only emit absent subtrees
            java.util.Map<String, java.util.Set<String>> byRoot = new java.util.TreeMap<>();
            for (String key : missingLeaves) {
                String root = nearestExistingAncestor(curCfg, key);
                byRoot.computeIfAbsent(root, k -> new java.util.TreeSet<>()).add(key);
            }
            StringBuilder missing = new StringBuilder();
            for (java.util.Map.Entry<String, java.util.Set<String>> e : byRoot.entrySet()) {
                String root = e.getKey();
                // build a sub-config holding only the missing leaves under root
                YamlConfiguration sub = new YamlConfiguration();
                for (String leaf : e.getValue()) {
                    String rel = leaf.substring(root.isEmpty() ? 0 : root.length() + 1);
                    sub.set(rel, tplCfg.get(leaf));
                }
                String dump = sub.saveToString();
                int depth = root.isEmpty() ? 0 : root.split("\\.").length;
                String pad = "  ".repeat(depth);
                for (String line : dump.split(System.lineSeparator())) {
                    String out = line.isEmpty() ? "" : pad + line;
                    int colon = line.indexOf(':');
                    if (colon > 0 && !line.contains("#")) {
                        String keyPart = line.substring(0, colon).strip();
                        String full = root.isEmpty() ? keyPart : root + "." + keyPart;
                        String comment = findTemplateComment(full, template);
                        if (comment != null) {
                            out = out + " # " + comment;
                        }
                    }
                    missing.append(out).append(System.lineSeparator());
                }
            }
            try (java.io.BufferedWriter w = java.nio.file.Files.newBufferedWriter(
                    file.toPath(), java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.APPEND)) {
                w.write(System.lineSeparator() + addHeader + System.lineSeparator());
                w.write(missing.toString());
            }
            LOGGER.info("[PRTS-Features] appended {} missing config keys to prts-features.yml",
                    missingLeaves.size());
        } catch (Exception ex) {
            LOGGER.warn("Failed to append missing config keys", ex);
        }
    }

    /** Find the template comment text for a full key path (walks template indentation). */
    private static String findTemplateComment(String rel, String template) {
        String[] stack = new String[24];
        for (String line : template.split(System.lineSeparator())) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.startsWith("#") || stripped.startsWith("-")) {
                continue;
            }
            int lead = line.length() - line.stripLeading().length();
            int level = lead / 2;
            String key = stripped.substring(0, stripped.indexOf(':')).trim();
            stack[level] = key;
            for (int i = level + 1; i < stack.length; i++) {
                stack[i] = null;
            }
            StringBuilder path = new StringBuilder();
            for (int i = 0; i <= level; i++) {
                if (stack[i] == null) {
                    break;
                }
                if (path.length() > 0) {
                    path.append('.');
                }
                path.append(stack[i]);
            }
            if (path.toString().equals(rel)) {
                int hash = stripped.indexOf('#');
                return hash >= 0 ? stripped.substring(hash + 1).strip() : null;
            }
        }
        return null;
    }

    /** Nearest ancestor of {@code key} that already exists in the file, or empty string for top level. */
    private static String nearestExistingAncestor(YamlConfiguration curCfg, String key) {
        String[] parts = key.split("\\.");
        for (int i = parts.length - 1; i >= 1; i--) {
            String prefix = String.join(".", java.util.Arrays.copyOf(parts, i));
            if (curCfg.contains(prefix)) {
                return prefix;
            }
        }
        return "";
    }

    private static String serialize(Object v) {
        if (v instanceof java.util.List) {
            StringBuilder sb = new StringBuilder("[");
            for (Object o : (java.util.List<?>) v) {
                if (sb.length() > 1) sb.append(", ");
                sb.append(o instanceof String ? "\"" + o + "\"" : o);
            }
            return sb.append("]").toString();
        }
        if (v instanceof String) {
            return "\"" + v + "\"";
        }
        return String.valueOf(v);
    }

    /** 首次运行写出默认配置模板（带说明注释），后续修改需重启生效。 */
    /** Default config template (English comments; shared by appendMissingKeys and writeDefaultConfig). */
    private static final String TEMPLATE_EN = """
                # PRTS server feature config (auto-generated on first run; restart to apply changes)

                # Config comment language (zh_cn = Chinese, en_us = English)
                locale: en_us              # config comment language (zh_cn = Chinese, en_us = English)

                # Chunk prefetch toward player movement (default off): temporary tickets ahead of view distance
                chunk-prefetch:
                  enabled: true               # master switch
                  depth: 6                # prefetch depth in chunks beyond view distance
                  interval-ticks: 5       # per-player recompute interval
                  timeout-ticks: 200      # ticket lifetime in ticks (auto-expired)
                  window: 16              # prefetch window depth in chunks (depth is deprecated)
                  window-width: 5         # corridor width in chunks (narrow = lower pipeline load)
                  window-step: 2          # window recompute trigger: crossing block count
                  window-recompute-ticks: 40  # window recompute trigger: min interval in ticks
                  prefetch-priority: 56   # prefetch task priority band (56-63)
                  max-pending: 512        # max pending prefetch tasks inside the window
                  idle-enabled: true      # idle background prefill when player stands still (default on)
                  idle-radius: 8          # idle prefill radius in chunks
                  idle-per-tick: 16       # idle prefill rate cap per tick
                  idle-enter-ticks: 100   # enter idle after this many low-movement ticks
                  idle-exit-blocks: 4     # exit idle above this movement in chunks

                # Mob collision (default off): mobs no longer block or push each other; player collision kept
                mob-collision:
                  enabled: false              # master switch
                  players-affected: false   # true = players also lose collision (full, use with care)

                # Item/monster clearing (all off by default)
                entity-clear:
                  item:
                    enabled: false         # clear dropped items
                    interval-seconds: 30       # flush interval0       # scan interval
                    whitelist: []              # item ids to keep
                    message: ''                # broadcast on clear
                  monster:
                    enabled: false         # clear monsters
                    interval-seconds: 600       # scan interval
                    whitelist: []              # monster ids to keep
                    message: ''                # broadcast on clear

                # Server watchdog (default off)
                watchdog:
                  enabled: false              # master switch
                  threshold-ms: 2000           # stall threshold
                  warn-cooldown-ms: 60000      # warn cooldown

                # Neighbor update circuit breaker (stops million-scale update storms)
                neighbor-update-breaker:
                  enabled: true               # master switch
                  max-per-tick: 200000        # update cap per tick

                # EventBus dispatch telemetry (diagnostic; attaches timing listeners, default off)
                eventbus:
                  telemetry-enabled: false      # attach timing listeners

                # AE2LT SetWorking throttle
                ae2lt-setworking-throttle:
                  enabled: true               # master switch
                  min-ticks: 4              # min ticks between checks

                # Multithreaded parallel engine
                parallel:
                  pathfinding-async: true            # async pathfinding
                  dimension-parallel: true           # per-dimension worker ticks
                  region-parallel: true              # overworld region workers (entity tick)
                  region-block-entity-parallel: true  # block entity ticks on region workers (BE interactions are racy, keep off if issues)
                  colony-npc-phase-stagger: true      # stagger MineColonies NPC work AI phases (default on)
                  colony-npc-work-interval: 5        # colony NPC work AI interval in ticks (vanilla 5; big cities: 10/20)
                  colony-manager-tick-cache-enabled: true  # cache getAllColonies snapshot for ServerTick event (A/B: -60% main-thread self time)
                  colony-manager-tick-cache-interval: 20   # snapshot TTL in ticks (1-120; invalidated immediately on create/delete)
                  chunk-demand-per-tick: 50           # max chunk demands processed by main thread per tick
                  chunk-demand-min-drain-ms: 2         # minimum drain window per tick even when over budget (anti death-spiral; 0=off)
                  chunk-demand-player-priority: true  # prioritize chunk demands by player distance (4 buckets)
                  chunk-demand-starve-ticks: 600      # low-priority bucket head older than this is consumed first (only with priority on)
                  chunk-send-rate-floor: 128        # floor for player chunk-send desired rate (chunks/tick; 0=vanilla ack tracking)
                  chunk-system-scheduler:            # scheduler driving vanilla generation futures (startup-only)
                    enabled: true                    # master switch (off = vanilla worldgen mailbox FIFO, bit-identical)
                    workers: 4                       # worker threads for worldgen task graph (statemachine; more = faster fresh-chunk gen)
                    split-stages: true               # two-stage lock split: 1x1 lock before features, 5x5 before FEATURES
                    dep-gating: true                # dependency gating: batch wait for layer futures before re-queue
                  chunk-system-enabled: true         # chunk state machine: per-chunk per-status task graph (startup-only; overrides scheduler)
                  chunk-system-fail-fast-guards: true # main-thread boundary fail-fast guards (only with chunk-system-enabled)
                  chunk-async-io-enabled: true       # IO deserialization off main thread (only with chunk-system-enabled)
                  worldgen-random-check: warn        # World.random cross-thread detection: warn/throw/off (only with chunk-system-enabled)
                  # GAP telemetry probes (M4 instrumentation on feat/gap-telemetry; all default off, read-only counters,
                  # zero semantics change). Output goes to NEW lines/subcommands only: "ChunkStages:"/"Probe:" in
                  # /servercore status and "servercore eventattr|topapi|eventstorm".
                  chunk-step-telemetry:
                    enabled: false                 # GAP-1: A-F six-segment service time (ns resolution) + arm-A end-to-end
                    wait-split:
                      enabled: false               # GAP-1: split queue/lock/barrier waiting from service time
                  event-listener-attribution:
                    enabled: false                 # GAP-4: per-(event class, owner) listener attribution (GAP-6 reuses it)
                  mod-thread-census:
                    enabled: false                 # GAP-4: periodic thread census (mod-owned threads + 3-bucket stack samples)
                  hostapi-attribution:
                    enabled: false                 # GAP-5: host API caller attribution
                    sample-every: 64               # GAP-5: one StackWalker attribution every N calls; 0 = disable probe
                  region-count: 4                    # region count (2/4/8/16; stripe width auto-expands at 16)
                  region-auto-scale: true            # auto-adjust region count by load
                  region-scale-interval-seconds: 300   # scale eval period
                  region-scale-high-mspt: 60.0     # scale up above this mspt
                  region-scale-low-mspt: 15.0      # scale down below this mspt
                  region-scale-stable-periods: 2    # stable windows required
                  region-scale-min: 2              # min region count
                  region-scale-max: 8              # max region count
                  region-scale-cross-read-ratio: 0.05  # allowed cross reads
                  uneven-stripes: true               # uneven stripes: busy regions yield boundary groups to neighbors
                  rebalance-interval-seconds: 300    # rebalance evaluation period (same window as auto-scale)
                  rebalance-max-moves: 1             # max boundary groups moved per round (fixed 1)
                  rebalance-min-groups: 1            # min groups per region after moving (skipped when width=1 group at N>=8)
                  rebalance-imbalance-ratio: 2.0     # rebalance only when norm(H) > norm(L)*ratio (normalized, anti-jitter)
                  thread-policy: stats             # worker world access policy: off/stats/enforce (prod: stats)
                  thread-policy-trace-class: ''    # [diag] capture one violation stack per class containing this substring
                  violation-log-per-minute: 20     # per-class violation log rate limit per minute
                  main-thread-routing: auto        # auto = learn from violations / manual = seed list only
                  route-threshold: 5               # MAIN_ONLY violations in window before routing to main thread (0=no learning)
                  route-window-ticks: 2400         # violation learning window (ticks; 2400 = 2 min)
                  route-on-read: true              # count MAIN_ONLY_READ toward routing (worker BE reads are null; false=writes only)
                  crossref-probe: false              # cross-region reference probe: sample worker BE access buckets (default off)
                  crossref-value-snapshot: false      # value snapshot: copy-on-read shadow validation on probe hits (default off)
                  crossref-snapshot-cache: false      # single-entry shadow snapshot cache (falsification experiment; default off)
                  belt-passenger-defer: true       # defer passenger registration to main thread on belt hit (with route-on-read=false)
                  main-thread-entity-force: []     # class names/prefixes forced to main-thread tick
                  main-thread-entity-allow: ["net.minecraft.world.entity.npc", "com.minecolonies."] # class names/prefixes allowed on workers (overrides seed/learned routing)
                  persist-learned-routes: true     # write learned routes back to config on shutdown (entities only, max 200)
                  block-tick-main-thread-when-serialized: true # deferred block ticks run on main thread POST in serial fallback (default on)
                  journal-max-per-region: 4096     # cross-region write journal cap per region (oldest dropped)
                  journal-lww-dedup: true          # LWW merge for unapplied same-pos entries (retry-storm defense, default on)
                  journal-max-per-tick: 512        # journal submissions cap per dimension per tick (over = budgetDropped; 0=unlimited)
                  journal-read-back: false         # read-your-writes overlay (reserved; default off)
                  determinism-mode: true            # determinism: cross-region journal applied in region order on scheduler thread
                  be-parallel-allow: ["create:*"] # BE tiers: registry keys/prefixes* allowed to tick on region workers
                  be-main-thread-force: ["create:track", "lootr:lootr_chest", "create:redstone_link", "create:portable_storage_interface", "create:spout"] # BE tiers: forced to main thread (spikes/cross-region deps)
                  create-track-lazy-spread: true    # spread Create long-track fake rail rasterization
                  create-track-lazy-chunk-blocks: 64 # max rasterized blocks per connection per tick
                  villager-poi-path-budget: 0       # villager main-thread POI/single-target path budget (0=off)
                  tree-cutter-node-budget: 4096     # Create TreeCutter BFS node budget (0=off)
                  tree-cutter-time-budget-ms: 10    # TreeCutter per-search time budget ms (0=off)
                  barrier-soft-degrade: true        # time-sliced barrier join when main thread is behind (late regions skip remaining work)
                  barrier-target-ms: 50             # whole-tick target budget ms; soft degrade activates when elapsed > it (0=never)
                  main-wasted-ms-telemetry: false   # measure barrier wait vs post-join main-thread overlap upper bound (telemetry only)
                  barrier-timeout-action: degrade    # hard timeout behavior: crash|degrade (degrade = serial main-thread + auto-recover)
                  process-queue-wake: true           # wake processQueue on demand (marshal requests served between ticks)
                  on-fault-fallback-vanilla: true     # fall back to vanilla serial after 3 consecutive barrier hard timeouts (restart to recover)
                  chunk-env-parallel: true            # chunk environment ticks (random/fluid) fanned out to a sub-pool
                  chunk-env-threads: 0                # sub-pool size (0=auto=CPU)
                  chunk-env-lock: true                # 3x3 chunk lock: mutually exclude concurrent writes on adjacent chunks (default on)
                  portal-async: true                   # async portal: submit async load when target chunk not FULL, defer 1 tick
                  entity-batch-parallel: true           # entity batch parallel: region worker entity phase fanned out to a sub-pool
                  entity-batch-threads: 0               # batch sub-pool size (0=auto=max(2, CPU-region_count))
                  entity-batch-allow: []                # batch whitelist (explicitly allow modded classes; registry key or prefix*)
                  entity-batch-deny: []                 # batch blacklist (forced exclusion; higher priority than whitelist)
                  barrier-timeout-recover-ticks: 6000 # consecutive normal ticks required to auto-recover from degraded
                  worker-tick-budget: 4096        # dimension worker scheduled-tick cap per tick (lava backlog anti-freeze)
                  dimension-worker-multitick: 4    # playerless dimension ticks per barrier session (backlog catch-up)
                  dimension-worker-session-ms: 8000 # multitick session wall-clock cap (anti barrier timeout)
                  login-warmup-enabled: true           # login warmup: smaller radius/rate to avoid boot load storms
                  login-warmup-radius: 4            # warmup radius in chunks (keep small for large worlds)
                  login-warmup-per-tick: 4         # chunks loaded per tick (smooth IO)

                # Reliable chunk save (WAL pre-write log, default off)
                reliable-chunk-save:
                  enabled: false             # master switch
                  interval-seconds: 30       # flush interval in seconds
                  chunks-per-tick: 50        # chunks flushed per tick

                # Chunk generation (0 = unlimited for that limit)
                generation-tasks-per-tick: 50        # chunk generation submissions per tick (0 = unlimited)
                chunkgen-inflight-limit: 128         # rolling 2s submission window (should >= worldgen capacity)
                generation-memory-guard-enabled: true  # heap pressure guard: throttle submissions when committed is high
                generation-memory-guard-throttle-ratio: 0.65 # committed ratio at which submissions are halved
                generation-memory-guard-pause-ratio: 0.85   # committed ratio at which submissions pause
                chunk-system-park-watchdog-ms: 20000  # re-evaluate a parked chunk task after this long (0 = off)
                chunk-system-park-drain-ms: 300000    # settle a still-parked task after this long (0 = off; draining kills dependents)
                # Barrier robustness
                barrier-watchdog-aware: true         # watchdog aware of parallel barrier (no false kills)
                barrier-timeout-ms: 120000           # barrier stall timeout in ms

                # Lighting: per-tick propagation budget + telemetry (1.21.1 light propagates on light threads)
                # Budget caps per-tick propagation work; storms spill to next tick. Final light is consistent,
                # just delayed. 0 = unlimited (vanilla).
                lighting:
                  budget-enabled: true               # per-tick light propagation budget switch
                  budget-per-tick: 100000            # max propagated blocks per tick (conservative; tune with [light-engine] log)
                  telemetry-enabled: true            # queue depth/elapsed into [light-engine] log
                  threaded: true                     # dedicated light threads: light mailbox + sorter off shared pool (one daemon per dimension)

                # Entity spatial index: lazy 4x4x4 sub-grid index inside EntitySections (default on)
                # Speeds up pure-space AABB queries (getEntities(AABB)) and typed queries; result order
                # is bit-identical to vanilla; yields to Lithium/Canary/Radium/Recruits.
                entity-spatial-index:
                  enabled: true                      # default on (disable if [entity-spatial-index] shows anomalies)
                  min-section-size: 16               # section entity count before building the index (small sections stay vanilla-linear)
                  telemetry-enabled: true            # query/candidate counts into [entity-spatial-index] log

                # POI query acceleration: empty-chunk existence precheck in PoiManager.getInChunk (default on)
                # 1.21.1 PoiSection is already bucketed by PoiType; remaining cost is full vertical section
                # scans over chunks without POIs. This maintains a per-chunk "has POI" bitmask and skips
                # known-empty chunks. Zero semantics change; cold chunks keep vanilla getOrLoad (sync disk read).
                poi-query:
                  enabled: true                      # default on (read-only acceleration, zero semantics change)
                  telemetry-enabled: true            # hit/skip counts into [poi-query] log

                # Collision batch: dedupe the step-up re-collection in Entity.collide (default on)
                # collideBoundingBox already collects once and clips per axis, but the step-up branch
                # re-collects the raised region on every ground move. This caches the first collection
                # within one collide frame and only fetches the top cap incrementally. Zero semantics
                # change; yields to Lithium/Canary/Radium.
                collision-batch:
                  enabled: true                      # default on (read-only acceleration, zero semantics change)
                  telemetry-enabled: true            # reused/incremental/full counts into [collision-batch] log

                # Container menu broadcast precheck short-circuit (default off; measure first)
                # broadcastChanges walks every slot of every open menu each tick even when idle. This
                # prechecks with per-slot-equivalent predicates (lastSlots/remoteCarried/dataSlots diff)
                # and skips the whole loop when nothing changed. Bit-identical: it is the equivalent of
                # the full diff done earlier, not dirty-slot tracking; mod direct writes are still caught.
                menu-broadcast:
                  enabled: false                     # default off (enable after prod spark attribution)
                  telemetry-enabled: true            # short/full/slot counts into [menu-broadcast] log

                # Event bridge on-demand registration (default on) with empty-listener precheck
                # Arclight's 5 Forge bridge dispatchers register only when a plugin listens to the
                # corresponding Bukkit event (0->1 register, 1->0 unregister). Servers without plugins
                # keep Forge events flowing and mod listeners intact; only the bridge's own listeners
                # leave the bus. Event count and timing unchanged.
                event-bridge:
                  on-demand-registration:
                    enabled: true                     # bridge listeners register on demand (default on)
                    eager-registration: false         # restore mod-loading-time permanent registration (escape hatch)
                    telemetry-enabled: true           # forwarded/skipped/register-unregister counts into [event-bridge] log

                # Event short-circuit (default on, zero semantic risk)
                # EntityTickEvent: per entity per tick x2 (top frequency); with no listeners Pre is never
                # cancelled = entity.tick() runs anyway, skipping is bit-equivalent. NeighborNotifyEvent:
                # NeoForge fires it on the vanilla empty shell and discards the isCanceled result; with
                # no listeners the fire itself can be skipped. Auto-yields when listeners exist.
                event-shortcircuit:
                  entity-tick-event:
                    enabled: true                     # skip EntityTickEvent construction+post when no listeners
                  neighbor-notify-event:
                    enabled: true                     # skip NeighborNotifyEvent construction+post when no listeners
                  block-form-event:
                    enabled: true                     # skip BlockFormEvent/EntityBlockFormEvent when no listeners
                  mob-spawn-event:
                    enabled: true                     # skip MobSpawnEvent.PositionCheck/MobDespawnEvent when no listeners
                  telemetry-enabled: true             # short/forward counts into [event-shortcircuit] log

                """;

    /** 默认配置模板（中文注释；appendMissingKeys / normalizeConfigComments / writeDefaultConfig 共用）。 */
    private static final String TEMPLATE_ZH = """
                # PRTS 服务器功能配置（首次启动自动生成，修改后重启生效）

                # 配置注释语言（zh_cn=中文，en_us=英文）
                locale: zh_cn  # 配置注释语言（zh_cn=中文，en_us=英文）

                # 区块预取：向玩家移动方向提前加载区块（默认关），为视距外生成临时加载票证
                chunk-prefetch:   # 区块预取：向玩家移动方向提前加载区块
                  enabled: true  # 总开关
                  depth: 6  # 视距外预取深度（区块）
                  interval-ticks: 5  # 每玩家预取重算间隔（tick）
                  timeout-ticks: 200  # 票证存活时间（tick，到期自动回收）
                  window: 16  # 预铺窗口深度（区块；depth 已废弃）
                  window-width: 5  # 预铺走廊宽度（区块）
                  window-step: 2  # 窗口重算触发：跨块阈值
                  window-recompute-ticks: 40  # 窗口重算触发：最小间隔（tick）
                  prefetch-priority: 56  # 预铺任务优先级档（56-63）
                  max-pending: 512  # 窗口内未完成预铺任务上限
                  idle-enabled: true  # idle 背景预生成（默认开）
                  idle-radius: 8  # idle 预生成半径（区块）
                  idle-per-tick: 16  # idle 每 tick 预铺上限
                  idle-enter-ticks: 100  # 进入 idle：连续低位移 tick 数
                  idle-exit-blocks: 4  # 退出 idle 位移阈值（区块）

                # 生物碰撞（默认关）：生物之间不再互相阻挡，保留玩家碰撞
                mob-collision:   # 生物碰撞（默认关）：生物不再互相阻挡，玩家碰撞保留
                  enabled: false  # 总开关
                  players-affected: false  # true=玩家也失去碰撞（谨慎）

                # 物品/怪物清理（默认全关）
                entity-clear:   # 物品/怪物清理（默认全关）
                  item:   # 掉落物清理
                    enabled: false  # 清理掉落物
                    interval-seconds: 30  # 扫描间隔（秒）
                    whitelist: []  # 保留的物品 ID 列表
                    message: ''  # 清理时广播消息
                  monster:   # 怪物清理
                    enabled: false  # 清理怪物
                    interval-seconds: 600  # 扫描间隔（秒）
                    whitelist: []  # 保留的怪物 ID 列表
                    message: ''  # 清理时广播消息

                # 服务器看门狗（默认关）
                watchdog:   # 服务器看门狗（默认关）
                  enabled: false  # 总开关
                  threshold-ms: 2000  # 卡顿判定阈值（毫秒）
                  warn-cooldown-ms: 60000  # 告警冷却（毫秒）

                # 邻居更新熔断：防止百万级连锁更新风暴
                neighbor-update-breaker:   # 邻居更新熔断：防止百万级连锁更新风暴
                  enabled: true  # 总开关
                  max-per-tick: 200000  # 每 tick 更新上限

                # 事件总线分发遥测（诊断用，附加计时监听器，默认关）
                eventbus:   # 事件总线分发遥测（诊断用）
                  telemetry-enabled: false  # 附加计时监听器

                # AE2LT 工作节流
                ae2lt-setworking-throttle:   # AE2LT 工作节流
                  enabled: true  # 总开关
                  min-ticks: 4  # 最小检查间隔（tick）

                # 多线程并行引擎
                parallel:   # 多线程并行引擎
                  pathfinding-async: true  # 异步寻路
                  dimension-parallel: true  # 维度并行：每维度独立工作线程
                  region-parallel: true  # 主世界区域并行（实体 tick）
                  region-block-entity-parallel: true  # 方块实体 tick 上区域工作线程（实体间交互有竞态，出问题可关）
                  colony-npc-phase-stagger: true  # 殖民地 NPC 工作 AI 相位错峰
                  colony-npc-work-interval: 5  # NPC 工作 AI 执行间隔（tick；大城市建议 10/20）
                  colony-manager-tick-cache-enabled: true  # 殖民地快照缓存（主线程自耗 -60%）
                  colony-manager-tick-cache-interval: 20  # 快照缓存有效期（tick）
                  chunk-demand-per-tick: 50  # 主线程每 tick 处理的区块需求上限
                  chunk-demand-min-drain-ms: 2  # 超预算时最低排空窗口（防死循环；0=关）
                  chunk-demand-player-priority: true  # 区块需求按玩家距离优先
                  chunk-demand-starve-ticks: 600  # 低优先级队头超龄即消费（仅开启优先时生效）
                  chunk-send-rate-floor: 128  # 玩家区块发送速率下限（块/tick；0=跟随客户端 ack）
                  chunk-system-scheduler:   # 区块调度器：驱动原版生成 future 链（启动期生效）
                    enabled: true  # 总开关（关=原版 FIFO，逐位一致）
                    workers: 4  # worldgen 任务图工作线程数（状态机；越大全新区块生成越快）
                    split-stages: true  # 两阶段锁域拆分：features 前只锁中心块，FEATURES 前换 5x5 锁
                    dep-gating: true  # 依赖门控：层内 future 全部完成才重新入队
                  chunk-system-enabled: true  # 区块状态机：单区块单状态任务图（启动期生效）
                  chunk-system-fail-fast-guards: true  # 主线程边界快速失败守卫
                  chunk-async-io-enabled: true  # IO 反序列化移出主线程
                  worldgen-random-check: warn  # 世界生成随机数跨线程检测：warn/throw/off
                  # GAP 遥测探针（M4 插桩，独立分支 feat/gap-telemetry；默认全关，纯只读计数，零语义变更）
                  # 读数只走新增行/新增子命令：status 的 "ChunkStages:"/"Probe:" 与 "servercore eventattr|topapi|eventstorm"
                  chunk-step-telemetry:   # GAP-1 分阶段计时
                    enabled: false  # A–F 六段服务时间（纳秒分辨率）+ A 臂端到端（FIND-g02）
                    wait-split:   # 等待分栏
                      enabled: false  # queue/lock/barrier 与六段服务时间分栏
                  event-listener-attribution:   # GAP-4 事件监听器 per-mod 归因（GAP-6 复用）
                    enabled: false  # 按（事件类, 归属）聚合 nanos/count
                  mod-thread-census:   # GAP-4 周期线程普查
                    enabled: false  # 模组自有线程归属 + 三桶栈采样（不碰线程创建路径）
                  hostapi-attribution:   # GAP-5 宿主 API 调用者归因
                    enabled: false  # 按（api, caller）计数，主线程口径可单列
                    sample-every: 64  # 每 N 次调用做一次 StackWalker 归因；0 = 关闭该探针
                  region-count: 4  # 区域数（2/4/8/16）
                  region-auto-scale: true  # 按负载自动调整区域数
                  region-scale-interval-seconds: 300  # 缩放评估周期（秒）
                  region-scale-high-mspt: 60.0  # 高于此 mspt 时扩容
                  region-scale-low-mspt: 15.0  # 低于此 mspt 时缩容
                  region-scale-stable-periods: 2  # 需要稳定的周期数
                  region-scale-min: 2  # 最小区域数
                  region-scale-max: 8  # 最大区域数
                  region-scale-cross-read-ratio: 0.05  # 允许的跨区读取比例
                  uneven-stripes: true  # 不等宽条带：繁忙区让出边界给相邻区
                  rebalance-interval-seconds: 300  # 重平衡评估周期（秒）
                  rebalance-max-moves: 1  # 单轮最多移动边界组数
                  rebalance-min-groups: 1  # 移动后每区最少组数
                  rebalance-imbalance-ratio: 2.0  # 负载差超过此比例才重平衡
                  thread-policy: stats  # 工作线程世界访问策略：off/stats/enforce
                  thread-policy-trace-class: ''  # 违规栈追踪：类名含该子串时抓一次调用栈（诊断）
                  violation-log-per-minute: 20  # 每类违规日志每分钟限流条数
                  main-thread-routing: auto  # auto=违规学习 / manual=只认种子列表
                  route-threshold: 5  # 窗口内违规次数即路由主线程（0=不学习）
                  route-window-ticks: 2400  # 违规学习窗口（tick，2400=2分钟）
                  route-on-read: true  # 读违规是否计入路由（worker 读实体恒空）
                  crossref-probe: false  # 跨区引用探针（诊断）
                  crossref-value-snapshot: false  # 值快照：读时复制影子验证（诊断）
                  crossref-snapshot-cache: false  # 影子快照单条缓存（诊断）
                  belt-passenger-defer: true  # 传送带乘客注册延迟到主线程
                  main-thread-entity-force: []  # 强制主线程 tick 的实体类名/前缀
                  main-thread-entity-allow: ["net.minecraft.world.entity.npc", "com.minecolonies."]  # 放行到工作线程的实体类名/前缀（覆盖种子和学习结果）
                  persist-learned-routes: true  # 停机时把学到的路由写回配置
                  block-tick-main-thread-when-serialized: true  # 串行回退时方块 tick 延迟到主线程
                  journal-max-per-region: 4096  # 跨区写日志每区域上限（最旧丢弃）
                  journal-lww-dedup: true  # 同位置未应用条目合并（防重试风暴）
                  journal-max-per-tick: 512  # 每维度每 tick 提交上限（0=不限）
                  journal-read-back: false  # 读己写覆盖（预留接口）
                  determinism-mode: true  # 确定性模式：跨区日志按区域序应用
                  be-parallel-allow: ["create:*"]  # 允许上区域工作线程的实体类型（注册键或前缀*）
                  be-main-thread-force: ["create:track", "lootr:lootr_chest", "create:redstone_link", "create:portable_storage_interface", "create:spout"]  # 强制主线程的实体类型（尖峰/跨区依赖；create PSI/spout 跨实体交互必须主线程串行）
                  create-track-lazy-spread: true  # Create 长轨道假轨栅格化分摊：每 tick 处理一条连接的一个区块
                  create-track-lazy-chunk-blocks: 64  # 分摊时每连接每 tick 最大栅格块数
                  villager-poi-path-budget: 0  # 村民主线程寻路预算（0=关）
                  tree-cutter-node-budget: 4096  # Create TreeCutter BFS 节点预算（0=关）
                  tree-cutter-time-budget-ms: 10  # TreeCutter 单次搜索时间预算 ms（0=关）
                  barrier-soft-degrade: true  # 主线程落后时 barrier 时间切片等待
                  barrier-target-ms: 50  # 整 tick 目标预算（毫秒），超出则激活软降级
                  main-wasted-ms-telemetry: false  # 量化 barrier 等待重叠（纯遥测）
                  barrier-timeout-action: degrade  # 硬超时行为：crash/降级
                  process-queue-wake: true  # 按需唤醒进程队列
                  on-fault-fallback-vanilla: true  # 连续 3 次硬超时后退回原版串行（重启恢复）
                  chunk-env-parallel: true  # 区块环境 tick（随机/流体）并行
                  chunk-env-threads: 0  # 子任务池大小（0=自动）
                  chunk-env-lock: true  # 3x3 区块锁：互斥相邻区块并发写
                  portal-async: true  # 异步传送门：目标区块未就绪时延后一 tick
                  entity-batch-parallel: true  # 实体批并行：区域实体阶段扇出到子池
                  entity-batch-threads: 0  # 批池大小（0=自动）
                  entity-batch-allow: []  # 批并行白名单（模组类）
                  entity-batch-deny: []  # 批并行黑名单（优先于白名单）
                  barrier-timeout-recover-ticks: 6000  # 降级后自动恢复所需连续正常 tick
                  worker-tick-budget: 4096  # 维度工作线程每 tick 计划 tick 上限
                  dimension-worker-multitick: 4  # 无玩家维度每会话多 tick（补 backlog）
                  dimension-worker-session-ms: 8000  # 多 tick 会话墙钟上限（防 barrier 超时）
                  login-warmup-enabled: true  # 进服预热：小半径/速率防启动加载风暴
                  login-warmup-radius: 4  # 预热半径（区块；大存档用小值防 IO 风暴）
                  login-warmup-per-tick: 4  # 预热每 tick 加载数（小值更平滑）

                # 可靠区块保存（WAL 预写日志，默认关）
                reliable-chunk-save:   # 可靠区块保存（预写日志，默认关）
                  enabled: false  # 总开关
                  interval-seconds: 30  # 落盘间隔（秒）
                  chunks-per-tick: 50  # 每 tick 落盘区块数

                # 区块生成（0 = 该上限不限）
                generation-tasks-per-tick: 50  # 区块生成每 tick 提交预算（0=不限）
                chunkgen-inflight-limit: 128  # 滚动 2 秒提交窗口上限
                generation-memory-guard-enabled: true  # 堆压力卫兵：高占用时限流生成
                generation-memory-guard-throttle-ratio: 0.65  # 提交减半的堆占用比例
                generation-memory-guard-pause-ratio: 0.85  # 暂停提交的堆占用比例
                chunk-system-park-watchdog-ms: 20000  # 区块任务挂起多久后重评估一次（0=关）
                chunk-system-park-drain-ms: 300000  # 持续挂起多久仍未唤醒就排空结算（0=关；排空会让依赖方判死，须远大于正常挂起时长）
                # 屏障鲁棒性
                barrier-watchdog-aware: true  # 看门狗感知并行 barrier（防误杀）
                barrier-timeout-ms: 120000  # barrier 卡死超时（毫秒）

                # 光照：每 tick 传播预算 + 遥测（1.21.1 光照在光照线程上传播）
                # 预算限制每 tick 传播工作量，超量顺延到下一 tick；最终光照一致，只是延迟。0 = 不限（原版）

                lighting:   # 光照：每 tick 传播预算+遥测
                  budget-enabled: true  # 每 tick 光照预算开关
                  budget-per-tick: 100000  # 每 tick 最大传播方块数（0=不限）
                  telemetry-enabled: true  # 队列深度/耗时进日志
                  threaded: true  # 独立光照线程：光照邮箱+排序器迁出共享池

                # 实体空间索引：EntitySections 内惰性 4x4x4 子网格索引（默认开）
                # 加速纯空间 AABB 查询（getEntities(AABB)）与类型化查询；结果顺序与原版完全一致
                # 与 Lithium/Canary/Radium 等优化模组兼容
                entity-spatial-index:   # 实体空间索引：加速 AABB/类型查询（默认开）
                  enabled: true  # 总开关
                  min-section-size: 16  # 建索引的最小实体数（小分区走线性）
                  telemetry-enabled: true  # 查询/候选计数进日志

                # POI 查询加速：PoiManager.getInChunk 空区块存在性预检（默认开）
                # 1.21.1 PoiSection 已按 PoiType 分桶；剩余开销是对无 POI 区块的完整纵向扫描
                # 维护每区块“有 POI”位掩码并跳过已知空区块。零语义变化；冷区块保持原版 getOrLoad（同步磁盘读）

                poi-query:   # POI 查询加速：空区块预检（默认开）
                  enabled: true  # 总开关
                  telemetry-enabled: true  # 命中/跳过计数进日志

                # 碰撞批量：Entity.collide 上台阶重收集去重（默认开）
                # collideBoundingBox 已收集一次并按轴裁剪，但上台阶分支每次地面移动都会重收集抬高区域
                # 在一个碰撞帧内缓存首次收集，只增量获取顶部部分，零语义变化
                # 与 Lithium/Canary/Radium 等优化模组兼容

                collision-batch:   # 碰撞批量收集：上台阶分支去重（默认开）
                  enabled: true  # 总开关
                  telemetry-enabled: true  # 复用/增量/全量计数进日志

                # 容器菜单广播预检短路（默认关，先测量再开启）
                # broadcastChanges 每 tick 遍历所有打开菜单的所有槽位，即使空闲
                # 用每槽等价谓词（lastSlots/remoteCarried/dataSlots 差异）预检，无变化时跳过整个循环
                # 位级一致：等价于提前完成的完整差异，而非脏槽跟踪；mod 直接写入仍能捕获

                menu-broadcast:   # 容器菜单广播预检短路
                  enabled: false  # 总开关
                  telemetry-enabled: true  # 短路/全量计数进日志

                # 事件桥按需注册（默认开）+ 空监听器预检
                # Arclight 的 5 个 Forge 桥接分发器仅在插件监听对应 Bukkit 事件时注册（0->1 注册，1->0 注销）
                # 无插件的服务器保持 Forge 事件流动、mod 监听器完整；只有桥自身的监听器离开总线
                # 事件数量与时机不变

                event-bridge:   # 事件桥按需注册（默认开）+空监听器预检
                  on-demand-registration:   # 按需注册
                    enabled: true  # 总开关
                    eager-registration: false  # 恢复常驻注册（顺序敏感逃生门）
                    telemetry-enabled: true  # 转发/跳过计数进日志

                # 事件短路（默认开，零语义风险）
                # EntityTickEvent：每实体每 tick 触发 2 次（最高频）；无监听器时 Pre 永不被取消 = entity.tick() 照常运行，跳过位级等价
                # NeighborNotifyEvent：NeoForge 在原版空壳上触发并丢弃 isCanceled 结果；无监听器时可跳过触发本身
                # 有监听器时自动让步

                event-shortcircuit:   # 事件短路（默认开，零语义风险）
                  entity-tick-event:   # 无监听器时跳过实体 tick 事件
                    enabled: true  # 总开关
                  neighbor-notify-event:   # 无监听器时跳过邻居通知事件
                    enabled: true  # 总开关
                  block-form-event:   # 无监听器时跳过方块生成事件
                    enabled: true  # 总开关
                  mob-spawn-event:   # 无监听器时跳过刷怪事件
                    enabled: true  # 总开关
                  telemetry-enabled: true  # 短路/转发计数进日志
                """;

    private static void writeDefaultConfig(File file, String template) {
        try {
            Files.writeString(file.toPath(), template, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOGGER.warn("Failed to write default prts-features.yml", e);
        }
    }
}
