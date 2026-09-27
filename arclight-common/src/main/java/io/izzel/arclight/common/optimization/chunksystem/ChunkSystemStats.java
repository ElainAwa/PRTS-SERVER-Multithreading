/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.chunksystem;

import io.izzel.arclight.common.compat.prts.PRTSFeaturesConfig;
import io.izzel.arclight.common.optimization.chunksystem.guards.ChunkIoMainThreadQueue;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 区块系统调度器遥测（M1：FlowSched 驱动原版生成 future 链）。
 * 只读计数，供 /servercore status 的 ChunkSystem 行与验收断言使用。
 *
 * <p>阶段一仪表化（吞吐瓶颈定性，零语义变化）：状态步耗时直方图、锁等待直方图、
 * tryLock 失败计数、队列深度采样、端到端延迟直方图、worker 忙碌率、features 并行度计。
 */
public final class ChunkSystemStats {

    private static final LongAdder SUBMITTED = new LongAdder();
    /** future 等待结束后重新入队的次数（任务推进的续段数）。 */
    private static final LongAdder RESUBMITTED = new LongAdder();
    private static final LongAdder EXECUTED = new LongAdder();
    /** 单段同步执行耗时（runUntilWait 同步段）。 */
    private static final LongAdder EXEC_NANOS = new LongAdder();
    /** 出队到开始执行的等待时长（排队延迟）。 */
    private static final LongAdder QUEUE_WAIT_NANOS = new LongAdder();
    private static final LongAdder EXCEPTIONS = new LongAdder();
    /** 决策后自愈升级为生成模式（原版此处会抛 IllegalStateException → fatal 崩服）。 */
    private static final LongAdder ESCALATED = new LongAdder();
    /** 按优先级段统计的提交数（0=最高优先级，6 段）。 */
    private static final LongAdder[] PRIORITY_SUBMITTED = new LongAdder[6];

    // ===== 阶段一仪表化 =====
    /** tryLock 失败次数（任务转 listener 等待）。 */
    private static final LongAdder LOCK_FAILS = new LongAdder();
    /** 两阶段拆分：窄锁→宽锁换锁次数（阶段二验证）。 */
    private static final LongAdder LOCK_UPGRADES = new LongAdder();
    /** 依赖门控（阶段四）：经「全层完成」挂起的次数与挂起时未完成 future 数累计。 */
    private static final LongAdder GATED_SUSPENDS = new LongAdder();
    private static final LongAdder GATED_PENDING_SUM = new LongAdder();
    /** 锁等待直方图（首次出队尝试 → 获得锁全程），桶界 ms：1/10/50/100/500。 */
    private static final long[] LOCK_WAIT_BOUNDS_MS = {1, 10, 50, 100, 500};
    private static final LongAdder[] LOCK_WAIT_BUCKETS = newBuckets(LOCK_WAIT_BOUNDS_MS.length + 1);
    /** 任务端到端延迟直方图（首次提交 → 最终完成），桶界 ms：100/250/500/1000/2000/5000。 */
    private static final long[] E2E_BOUNDS_MS = {100, 250, 500, 1000, 2000, 5000};
    private static final LongAdder[] E2E_BUCKETS = newBuckets(E2E_BOUNDS_MS.length + 1);
    /** 依赖等待直方图（挂起点 → 续段出队执行），桶界 ms：10/50/250/1000/5000，末桶为 >5000 溢出。 */
    private static final long[] DEP_WAIT_BOUNDS_MS = {10, 50, 250, 1000, 5000};
    private static final LongAdder[] DEP_WAIT_BUCKETS = newBuckets(DEP_WAIT_BOUNDS_MS.length + 1);
    /** 依赖等待按状态层归因（槽位与状态步耗时共用 STATUS_SLOTS）。 */
    private static final LongAdder[] DEP_WAIT_NANOS = newBuckets(64);
    private static final LongAdder[] DEP_WAIT_COUNTS = newBuckets(64);
    /** 每任务续段次数分布（槽位 0..4 = 0..4 次，槽位 5 = ≥5 次）。 */
    private static final LongAdder[] RESUB_DIST = newBuckets(6);
    /** 队列深度采样（每次入队采一次）。 */
    private static final AtomicLong QUEUE_DEPTH_MAX = new AtomicLong();
    private static final LongAdder QUEUE_DEPTH_SUM = new LongAdder();
    private static final LongAdder QUEUE_DEPTH_SAMPLES = new LongAdder();
    /** worker 忙碌纳秒累计（全体 worker 之和）与调度器启动基准。 */
    private static final LongAdder WORKER_BUSY_NANOS = new LongAdder();
    private static volatile long schedulerStartNanos = 0;
    private static volatile int schedulerWorkerCount = 0;
    /** features 段并发度：当前值与峰值（H1 锁串行判据）。 */
    private static final AtomicInteger FEATURES_ACTIVE = new AtomicInteger();
    private static final AtomicInteger FEATURES_PEAK = new AtomicInteger();
    /** applyStep 内联计时配对（线程内一进一出，不嵌套）。 */
    private static final ThreadLocal<Long> STEP_TIMING_START = new ThreadLocal<>();
    /** 按状态步的耗时统计（槽位按需分配）。 */
    private static final ConcurrentHashMap<ChunkStatus, Integer> STATUS_SLOTS = new ConcurrentHashMap<>();
    private static final String[] STATUS_NAMES = new String[64];
    private static final LongAdder[] STEP_NANOS = newBuckets(64);
    private static final LongAdder[] STEP_COUNTS = newBuckets(64);
    private static final AtomicInteger SLOT_NEXT = new AtomicInteger();

    private static volatile long lastExecMs = 0;
    /** M2 状态机活跃任务数（创建→future 结算）；M1 恒 0。 */
    private static final AtomicInteger LIVE_TASKS = new AtomicInteger();
    /** M2：worker 线程投递的延迟重调度请求数（突发风暴死锁修复遥测）。 */
    private static final LongAdder RESCHEDULE_DEFERRED = new LongAdder();
    /** M2 诊断：当前 park 中任务的挂起原因分布（任务唤醒/排空时移除）。 */
    private static final ConcurrentHashMap<String, LongAdder> PARK_REASONS = new ConcurrentHashMap<>();
    /** M2 诊断：当前挂起波起始时刻（首个挂起置位、全部唤醒清零），用于判断挂起持续多久。 */
    private static final AtomicLong PARK_WAVE_START = new AtomicLong();
    /** park 看门狗：重评估次数与排空次数（兜底生效证据）。 */
    private static final LongAdder PARK_WATCHDOG_REDRIVE = new LongAdder();
    private static final LongAdder PARK_WATCHDOG_DRAIN = new LongAdder();
    /** M2 诊断：任务排空（静默 return）原因累计。 */
    private static final ConcurrentHashMap<String, LongAdder> DRAIN_REASONS = new ConcurrentHashMap<>();

    static {
        for (int i = 0; i < PRIORITY_SUBMITTED.length; i++) {
            PRIORITY_SUBMITTED[i] = new LongAdder();
        }
    }

    private ChunkSystemStats() {
    }

    private static LongAdder[] newBuckets(int n) {
        LongAdder[] arr = new LongAdder[n];
        for (int i = 0; i < n; i++) {
            arr[i] = new LongAdder();
        }
        return arr;
    }

    public static void submitted(int priority) {
        SUBMITTED.increment();
        PRIORITY_SUBMITTED[Math.min(priority / 11, PRIORITY_SUBMITTED.length - 1)].increment();
    }

    public static void resubmitted() {
        RESUBMITTED.increment();
    }

    public static void rescheduleDeferred() {
        RESCHEDULE_DEFERRED.increment();
    }

    public static void parkStart(String reason) {
        PARK_REASONS.computeIfAbsent(reason, k -> new LongAdder()).increment();
        PARK_WAVE_START.compareAndSet(0L, System.nanoTime());
    }

    public static void parkEnd(String reason) {
        LongAdder adder = PARK_REASONS.get(reason);
        if (adder != null) {
            adder.decrement();
        }
        // 全部唤醒后清零，下一波重新计时
        for (LongAdder live : PARK_REASONS.values()) {
            if (live.sum() > 0) {
                return;
            }
        }
        PARK_WAVE_START.set(0L);
    }

    public static void parkWatchdogRedrive() {
        PARK_WATCHDOG_REDRIVE.increment();
    }

    public static void parkWatchdogDrain() {
        PARK_WATCHDOG_DRAIN.increment();
    }

    public static void drained(String reason) {
        DRAIN_REASONS.computeIfAbsent(reason, k -> new LongAdder()).increment();
    }

    public static void executed(long execNanos, long queueWaitNanos) {
        EXECUTED.increment();
        EXEC_NANOS.add(execNanos);
        QUEUE_WAIT_NANOS.add(queueWaitNanos);
        waitNanos("queue", queueWaitNanos);
        if (execNanos > lastExecMs * 1_000_000L) {
            lastExecMs = execNanos / 1_000_000L;
        }
    }

    public static void exception() {
        EXCEPTIONS.increment();
    }

    public static void escalated() {
        ESCALATED.increment();
    }

    /** M2 状态机任务创建（与 {@link #taskSettled()} 配对，供 inflight 实活计数）。 */
    public static void taskCreated() {
        LIVE_TASKS.incrementAndGet();
    }

    public static void taskSettled() {
        LIVE_TASKS.decrementAndGet();
    }

    // ===== 阶段一仪表化入口 =====

    /** 调度器初始化时记录 worker 数与起始时刻（忙碌率分母）。 */
    public static void initScheduler(int workers) {
        schedulerWorkerCount = workers;
        schedulerStartNanos = System.nanoTime();
    }

    public static void lockTryFailed() {
        LOCK_FAILS.increment();
    }

    public static void lockUpgrade() {
        LOCK_UPGRADES.increment();
    }

    /** 依赖门控挂起：记录一次，并累计当时未完成 future 数（均值反映层内等待规模）。 */
    public static void gatedSuspend(int pendingCount) {
        GATED_SUSPENDS.increment();
        GATED_PENDING_SUM.add(pendingCount);
    }

    /** 锁等待全程（首次出队尝试 → 获得锁），含 listener 等待重试。 */
    public static void lockWait(long nanos) {
        bucket(LOCK_WAIT_BUCKETS, LOCK_WAIT_BOUNDS_MS, nanos);
        waitNanos("lock", nanos);
    }

    /**
     * GAP-1 等待分栏新增入口（M4 插桩 · 只新增方法，既有计数器字段与 status 行格式一律不动）：
     * 把既有等待读数镜像进 {@link ChunkStageTiming} 的 queue/lock/barrier 桶，供新增行
     * {@code ChunkStages: waitMs={...}} 使用。默认关（{@code parallel.chunk-step-telemetry.wait-split.enabled}）
     * ⇒ 关闭时只有一次 volatile 读，不影响既有语义。
     *
     * <p>barrier 等待按 {@code RD/PLAN.md} @95 取既有 {@code Barrier} 行快照（本探针不出数），
     * 因此 {@code "barrier"} 分支只作为对外入口保留。</p>
     */
    public static void waitNanos(String kind, long nanos) {
        ChunkStageTiming.waitNanos(kind, nanos);
    }

    /** 任务端到端延迟（首次提交 → 最终完成）。 */
    public static void taskCompleted(long nanos) {
        bucket(E2E_BUCKETS, E2E_BOUNDS_MS, nanos);
    }

    /** 依赖等待全程（挂起点 → 续段出队执行），按挂起时状态层归因。 */
    public static void depWait(ChunkStatus status, long nanos) {
        bucket(DEP_WAIT_BUCKETS, DEP_WAIT_BOUNDS_MS, nanos);
        if (status != null) {
            int slot = slotFor(status);
            DEP_WAIT_NANOS[slot].add(nanos);
            DEP_WAIT_COUNTS[slot].increment();
        }
    }

    /** 任务完成时记录续段次数分布。 */
    public static void resubDist(int count) {
        RESUB_DIST[Math.min(count, RESUB_DIST.length - 1)].increment();
    }

    public static void sampleQueueDepth(int depth) {
        QUEUE_DEPTH_SUM.add(depth);
        QUEUE_DEPTH_SAMPLES.increment();
        QUEUE_DEPTH_MAX.updateAndGet(prev -> Math.max(prev, depth));
    }

    public static void workerBusy(long nanos) {
        WORKER_BUSY_NANOS.add(nanos);
    }

    /** applyStep 进入：记录计时起点，features 段并发 +1。 */
    public static void stepBegin(ChunkStatus status) {
        STEP_TIMING_START.set(System.nanoTime());
        if (status == ChunkStatus.FEATURES) {
            int active = FEATURES_ACTIVE.incrementAndGet();
            FEATURES_PEAK.updateAndGet(prev -> Math.max(prev, active));
        }
    }

    /** applyStep 返回：记录该状态步内联耗时，features 段并发 -1。 */
    public static void stepEnd(ChunkStatus status) {
        Long start = STEP_TIMING_START.get();
        if (start != null) {
            STEP_TIMING_START.remove();
            int slot = slotFor(status);
            STEP_NANOS[slot].add(System.nanoTime() - start);
            STEP_COUNTS[slot].increment();
        }
        if (status == ChunkStatus.FEATURES) {
            FEATURES_ACTIVE.decrementAndGet();
        }
    }

    private static int slotFor(ChunkStatus status) {
        return STATUS_SLOTS.computeIfAbsent(status, s -> {
            int slot = SLOT_NEXT.getAndIncrement();
            if (slot < STATUS_NAMES.length) {
                STATUS_NAMES[slot] = s.getName();
            }
            return slot;
        });
    }

    private static void bucket(LongAdder[] buckets, long[] boundsMs, long nanos) {
        long ms = nanos / 1_000_000L;
        int i = 0;
        while (i < boundsMs.length && ms >= boundsMs[i]) {
            i++;
        }
        buckets[i].increment();
    }

    private static String histogram(LongAdder[] buckets) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < buckets.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(buckets[i].sum());
        }
        return sb.append(']').toString();
    }

    public static String statusText() {
        long submitted = SUBMITTED.sum();
        long resubmitted = RESUBMITTED.sum();
        long executed = EXECUTED.sum();
        // M1 账本语义（提交-执行）；M2 任务多次 park 重入队，账本失衡，改用实活计数
        long inflight = PRTSFeaturesConfig.chunkSystemEnabled
                ? LIVE_TASKS.get() : submitted + resubmitted - executed;
        long exceptions = EXCEPTIONS.sum();
        StringBuilder sb = new StringBuilder();
        sb.append("submitted=").append(submitted)
                .append(" resub=").append(resubmitted)
                .append(" inflight=").append(inflight)
                .append(" executed=").append(executed)
                .append(" exceptions=").append(exceptions);
        long escalated = ESCALATED.sum();
        if (escalated > 0) {
            sb.append(" escalated=").append(escalated);
        }
        if (executed > 0) {
            sb.append(String.format(" execAvg=%.2fms queueWaitAvg=%.2fms execMax=%dms",
                    EXEC_NANOS.sum() / (double) executed / 1_000_000.0,
                    QUEUE_WAIT_NANOS.sum() / (double) executed / 1_000_000.0,
                    lastExecMs));
        }
        sb.append(" byPriority=[");
        for (int i = 0; i < PRIORITY_SUBMITTED.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(PRIORITY_SUBMITTED[i].sum());
        }
        sb.append(']');
        // 阶段一仪表化输出
        sb.append(" lockFails=").append(LOCK_FAILS.sum())
                .append(" lockUpgrades=").append(LOCK_UPGRADES.sum())
                .append(" lockWaitMs=").append(histogram(LOCK_WAIT_BUCKETS))
                .append(" e2eMs=").append(histogram(E2E_BUCKETS))
                .append(" depWaitMs=").append(histogram(DEP_WAIT_BUCKETS));
        long gated = GATED_SUSPENDS.sum();
        if (gated > 0) {
            sb.append(String.format(" gated=%d(avgPending=%.1f)", gated,
                    GATED_PENDING_SUM.sum() / (double) gated));
        }
        long resched = RESCHEDULE_DEFERRED.sum();
        if (resched > 0) {
            sb.append(" resched=").append(resched);
        }
        StringBuilder parks = new StringBuilder();
        for (Map.Entry<String, LongAdder> e : PARK_REASONS.entrySet()) {
            long n = e.getValue().sum();
            if (n > 0) {
                parks.append(' ').append(e.getKey()).append('=').append(n);
            }
        }
        long watchdogRedrive = PARK_WATCHDOG_REDRIVE.sum();
        long watchdogDrain = PARK_WATCHDOG_DRAIN.sum();
        if (watchdogRedrive > 0 || watchdogDrain > 0) {
            sb.append(" parkWatchdog={redrive=").append(watchdogRedrive)
                    .append(" drain=").append(watchdogDrain).append('}');
        }
        if (parks.length() > 0) {
            sb.append(" parks={").append(parks.substring(1)).append('}');
            // 挂起波年龄：判断"卡住不动"与"只是慢"的分界
            long waveStart = PARK_WAVE_START.get();
            if (waveStart != 0L) {
                sb.append(" oldestPark=").append((System.nanoTime() - waveStart) / 1_000_000_000L).append('s');
            }
        }
        StringBuilder drains = new StringBuilder();
        DRAIN_REASONS.forEach((reason, adder) -> {
            long n = adder.sum();
            if (n > 0) {
                drains.append(' ').append(reason).append('=').append(n);
            }
        });
        if (drains.length() > 0) {
            sb.append(" drains={").append(drains.substring(1)).append('}');
        }
        StringBuilder depByStatus = new StringBuilder();
        for (int i = 0; i < SLOT_NEXT.get() && i < STATUS_NAMES.length; i++) {
            long depCount = DEP_WAIT_COUNTS[i].sum();
            if (depCount > 0 && STATUS_NAMES[i] != null) {
                if (depByStatus.length() > 0) depByStatus.append(',');
                depByStatus.append(STATUS_NAMES[i]).append('=')
                        .append(DEP_WAIT_NANOS[i].sum() / depCount / 1_000_000L)
                        .append("ms*").append(depCount);
            }
        }
        if (depByStatus.length() > 0) {
            sb.append(" depWaitByStatus={").append(depByStatus).append('}');
        }
        sb.append(" resubDist=").append(histogram(RESUB_DIST));
        long samples = QUEUE_DEPTH_SAMPLES.sum();
        if (samples > 0) {
            sb.append(String.format(" qDepthMax=%d qDepthAvg=%.1f",
                    QUEUE_DEPTH_MAX.get(), QUEUE_DEPTH_SUM.sum() / (double) samples));
        }
        long startNanos = schedulerStartNanos;
        int workers = schedulerWorkerCount;
        if (startNanos != 0 && workers > 0) {
            double capacity = (double) workers * (System.nanoTime() - startNanos);
            sb.append(String.format(" busy=%.0f%%", WORKER_BUSY_NANOS.sum() / capacity * 100.0));
        }
        sb.append(" featNow=").append(FEATURES_ACTIVE.get())
                .append(" featPeak=").append(FEATURES_PEAK.get());
        // M2.3 遥测：IO 反序列化事件延迟队列（安装/排水计数与排队等待，对应计划 installMs）
        long ioCaptured = ChunkIoMainThreadQueue.captured();
        if (ioCaptured > 0) {
            sb.append(String.format(" ioEvent[captured=%d drained=%d waitAvg=%.2fms]",
                    ioCaptured, ChunkIoMainThreadQueue.drained(), ChunkIoMainThreadQueue.waitAvgMs()));
        }
        StringBuilder steps = new StringBuilder();
        for (int i = 0; i < SLOT_NEXT.get() && i < STATUS_NAMES.length; i++) {
            long count = STEP_COUNTS[i].sum();
            if (count > 0) {
                if (steps.length() > 0) steps.append(',');
                steps.append(STATUS_NAMES[i]).append('=')
                        .append(STEP_NANOS[i].sum() / count / 1_000_000L).append("ms*").append(count);
            }
        }
        if (steps.length() > 0) {
            sb.append(" stepMs={").append(steps).append('}');
        }
        // M3 诊断（§4.4）：活跃任务栈快照（仅生成中输出，供卡顿时定位「哪块哪个状态步」）
        String taskStack = ChunkSystemThreadState.dump();
        if (!taskStack.isEmpty()) {
            sb.append(" taskStack{").append(taskStack).append('}');
        }
        return sb.toString();
    }
}
