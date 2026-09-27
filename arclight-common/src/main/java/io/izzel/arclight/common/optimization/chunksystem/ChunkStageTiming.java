/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.chunksystem;

import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.ArrayDeque;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * GAP-1 分阶段计时探针（M4 插桩 · 独立分支 feat/gap-telemetry · <b>默认关</b> · 零语义变更）。
 *
 * <p>只加不改：既有计数器（{@code ChunkSystemStats} 的 stepMs/execAvg/e2eMs 等）与
 * {@code /servercore status} 既有行格式一律不动；本类只服务<b>新增</b>行
 * {@code ChunkStages:}。全部埋点先读一次 volatile 开关，关闭时不留任何计数副作用。</p>
 *
 * <p><b>A–F 段归属（本表为本探针唯一定义处）</b>：E7 六段语义见
 * {@code E7/e7-3-workstreams.md · §5.1 表 @ 68-76}；{@code ChunkStatus} 与六段无现成映射
 * （{@code RESEARCH.md} D2）⇒ 本探针按下表映射，双臂同表同口径：</p>
 * <ul>
 *   <li><b>A</b> 磁盘 IO／区域文件扇区 —— {@code RegionFileStorage.read} 边界（含 NBT 读取，
 *       与 B 在调用层前后相继、不重叠；边界为近似口径，**未核实**）。</li>
 *   <li><b>B</b> 解压 + 结构解析 —— {@code ChunkSerializer.read} 边界（{@code CompoundTag} → ProtoChunk）。</li>
 *   <li><b>C</b> 世界生成数值 —— {@code ChunkStatus} ≤ {@code FEATURES} 的状态任务体。</li>
 *   <li><b>D</b> 光照／高度图／位压缩 —— {@code INITIALIZE_LIGHT}／{@code LIGHT} 的状态任务体。</li>
 *   <li><b>E</b> 对象落地（宿主线程保序）—— {@code SPAWN}／{@code FULL} 的状态任务体；完成通知
 *       （{@code GenerationChunkHolder.completeFuture}）只作端到端终点，不再计时（其方法体仅为 future
 *       记账，计时会给出 ~0 的假读数）。</li>
 *   <li><b>F</b> 保存 + 网络发送 —— {@code PlayerChunkSender.sendNextChunks} 边界（按玩家批量，无单块坐标）。</li>
 * </ul>
 *
 * <p><b>服务 vs 等待分栏（lead 裁定）</b>：{@link #recordService} 系列只记<b>服务时间</b>；
 * 排队／锁／屏障等待走 {@link #waitNanos}（{@code parallel.chunk-step-telemetry.wait-split.enabled}）。
 * wall-clock（含等待）不得单独当作 {@code C_i}。</p>
 *
 * <p><b>A 臂端到端（QA FIND-g02）</b>：{@code e2eMs} 直方图的唯一写入者 {@code taskCompleted} 只在
 * {@code chunk-system-enabled} 路径被调用 ⇒ vanilla A 臂恒空、残差不可判。本探针为<b>两臂共用</b>的
 * 端到端单独埋点：同一区块<b>首次</b> {@code applyStep} 进入 → {@code FULL} 步 future 完成。
 * 残差 {@code residualVsE2e} = |Σ(该区块已计 A–F 服务时间) − 端到端| / 端到端，<b>同区块配对</b>；
 * 口径为<b>均值替代 p50</b>（直方图未落盘）且 A/B 段在 IO 线程上可能落在配对窗口外 ⇒ 该数只作探针
 * 自检，报告层必须按 {@code RD/report/GAP-1_residual.md} 的可复算规则重算，<b>不得直接引用</b>
 * （E7 {@code @ 1234}：阈值 ≠ 实测）。</p>
 *
 * <p>线程纪律：埋点可在主线程／region worker／IO 线程并发写；计数全为 {@link LongAdder}，
 * 配对栈为 {@link ThreadLocal}（每线程独立）。</p>
 */
public final class ChunkStageTiming {

    /** 开关：{@code parallel.chunk-step-telemetry.enabled}（默认 false）。 */
    public static volatile boolean ENABLED;
    /** 开关：{@code parallel.chunk-step-telemetry.wait-split.enabled}（默认 false）。 */
    public static volatile boolean WAIT_SPLIT_ENABLED;

    /** A–F 段序号（数组下标即段号）。 */
    public static final int A = 0;
    public static final int B = 1;
    public static final int C = 2;
    public static final int D = 3;
    public static final int E = 4;
    public static final int F = 5;
    /** 未映射段（兜底；正常配置下恒 0）。 */
    public static final int UNMAPPED = 6;
    private static final String[] STAGE_NAMES = {"A", "B", "C", "D", "E", "F", "UNMAPPED"};

    /** 无单块坐标的段（F 段按玩家批量发送）传入本哨兵值。 */
    public static final long NO_POS = Long.MIN_VALUE;

    private static final LongAdder[] STAGE_NANOS = newBuckets(STAGE_NAMES.length);
    private static final LongAdder[] STAGE_COUNTS = newBuckets(STAGE_NAMES.length);

    private static final String[] WAIT_NAMES = {"queue", "lock", "barrier"};
    private static final LongAdder[] WAIT_NANOS = newBuckets(WAIT_NAMES.length);
    private static final LongAdder[] WAIT_COUNTS = newBuckets(WAIT_NAMES.length);

    /** 在飞区块：posKey → [首次进入纳秒, 已计 A–F 服务纳秒之和]。 */
    private static final ConcurrentHashMap<Long, long[]> IN_FLIGHT = new ConcurrentHashMap<>();
    private static final int MAX_IN_FLIGHT = 262144;
    private static final LongAdder E2E_NANOS = new LongAdder();
    private static final LongAdder E2E_COUNT = new LongAdder();
    private static final AtomicLong E2E_MAX_NANOS = new AtomicLong();
    private static final LongAdder RESIDUAL_PERMILLE_SUM = new LongAdder();
    private static final LongAdder RESIDUAL_COUNT = new LongAdder();

    /** 段计时配对栈（HEAD 入、RETURN 出；线程内可嵌套）。 */
    private static final ThreadLocal<ArrayDeque<long[]>> STACK = ThreadLocal.withInitial(ArrayDeque::new);

    private ChunkStageTiming() {
    }

    private static LongAdder[] newBuckets(int n) {
        LongAdder[] arr = new LongAdder[n];
        for (int i = 0; i < n; i++) {
            arr[i] = new LongAdder();
        }
        return arr;
    }

    /**
     * {@code ChunkStatus} → A–F 段映射（本探针唯一定义处）。
     * {@code EMPTY..FEATURES} → C；{@code INITIALIZE_LIGHT}/{@code LIGHT} → D；{@code SPAWN}/{@code FULL} → E。
     */
    public static int stageForStatus(ChunkStatus status) {
        if (status == null) {
            return UNMAPPED;
        }
        if (status.isOrBefore(ChunkStatus.FEATURES)) {
            return C;
        }
        if (status.isOrBefore(ChunkStatus.LIGHT)) {
            return D;
        }
        return E;
    }

    /** HEAD 侧：开始一段服务计时（未开启时零成本返回）。 */
    public static void begin(int stage) {
        if (!ENABLED) {
            return;
        }
        STACK.get().push(new long[]{stage, System.nanoTime()});
    }

    /**
     * RETURN 侧：弹出配对并记入段桶。
     *
     * @param posKey 该段的区块坐标（{@link #NO_POS} = 无单块坐标）；用于同区块端到端配对
     */
    public static void endAndRecord(long posKey) {
        if (!ENABLED) {
            return;
        }
        long[] frame = STACK.get().poll();
        if (frame == null) {
            return;
        }
        int stage = (int) frame[0];
        long nanos = System.nanoTime() - frame[1];
        if (stage < 0 || stage >= STAGE_NAMES.length) {
            stage = UNMAPPED;
        }
        STAGE_NANOS[stage].add(nanos);
        STAGE_COUNTS[stage].increment();
        if (posKey != NO_POS) {
            long[] inflight = IN_FLIGHT.get(posKey);
            if (inflight != null) {
                inflight[1] += nanos;
            }
        }
    }

    /** 等待分栏：queue／lock／barrier（仅在 wait-split 开关打开时记录）。 */
    public static void waitNanos(String kind, long nanos) {
        if (!WAIT_SPLIT_ENABLED || nanos <= 0L) {
            return;
        }
        for (int i = 0; i < WAIT_NAMES.length; i++) {
            if (WAIT_NAMES[i].equals(kind)) {
                WAIT_NANOS[i].add(nanos);
                WAIT_COUNTS[i].increment();
                return;
            }
        }
    }

    /** 端到端起点：区块首次 {@code applyStep} 进入（两臂共用，FIND-g02）。 */
    public static void e2eEnter(long posKey) {
        if (!ENABLED) {
            return;
        }
        if (IN_FLIGHT.size() > MAX_IN_FLIGHT) {
            // 防长跑泄漏：清空即放弃在飞样本（只影响残差样本数，不污染任何既有计数）
            IN_FLIGHT.clear();
        }
        IN_FLIGHT.putIfAbsent(posKey, new long[]{System.nanoTime(), 0L});
    }

    /** 端到端终点：{@code FULL} 步 future 完成（{@code GenerationChunkHolder.completeFuture}）。 */
    public static void e2eComplete(long posKey) {
        if (!ENABLED) {
            return;
        }
        long[] inflight = IN_FLIGHT.remove(posKey);
        if (inflight == null) {
            return;
        }
        long e2e = System.nanoTime() - inflight[0];
        if (e2e <= 0L) {
            return;
        }
        E2E_NANOS.add(e2e);
        E2E_COUNT.increment();
        E2E_MAX_NANOS.accumulateAndGet(e2e, Math::max);
        long service = inflight[1];
        if (service > 0L) {
            RESIDUAL_PERMILLE_SUM.add(Math.abs(service - e2e) * 1000L / e2e);
            RESIDUAL_COUNT.increment();
        }
    }

    /** 新增 status 行内容（不改既有行；格式见 {@code RD/PLAN.md} @95）。 */
    public static String statusText() {
        StringBuilder sb = new StringBuilder();
        sb.append("stageMs={");
        boolean first = true;
        for (int i = 0; i < STAGE_NAMES.length; i++) {
            long count = STAGE_COUNTS[i].sum();
            if (count <= 0L) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(STAGE_NAMES[i]).append('=')
                    .append(formatMs(STAGE_NANOS[i].sum() / (double) count))
                    .append("ms*").append(count);
        }
        sb.append('}');
        long waitTotal = 0L;
        for (LongAdder adder : WAIT_COUNTS) {
            waitTotal += adder.sum();
        }
        if (waitTotal > 0L) {
            sb.append(" waitMs={");
            for (int i = 0; i < WAIT_NAMES.length; i++) {
                if (i > 0) {
                    sb.append(',');
                }
                long count = WAIT_COUNTS[i].sum();
                double avgMs = count > 0L ? WAIT_NANOS[i].sum() / (double) count : 0.0d;
                sb.append(WAIT_NAMES[i]).append('=').append(formatMs(avgMs)).append("ms*").append(count);
            }
            sb.append('}');
        } else {
            // barrier 等待按 PLAN @95 取既有 Barrier 行快照（本探针不出数）
            sb.append(" waitMs={queue=0,lock=0,barrier=0}");
        }
        long e2eCount = E2E_COUNT.sum();
        if (e2eCount > 0L) {
            sb.append(String.format(Locale.ROOT, " e2eMs={n=%d,avg=%.3f,max=%.3f}", e2eCount,
                    E2E_NANOS.sum() / (double) e2eCount / 1_000_000.0d,
                    E2E_MAX_NANOS.get() / 1_000_000.0d));
        } else {
            sb.append(" e2eMs={n=0}");
        }
        long residualCount = RESIDUAL_COUNT.sum();
        if (residualCount > 0L) {
            sb.append(String.format(Locale.ROOT, " residualVsE2e=%.1f%%(n=%d,mean-based)",
                    RESIDUAL_PERMILLE_SUM.sum() / (double) residualCount / 10.0d, residualCount));
        } else {
            sb.append(" residualVsE2e=na");
        }
        sb.append(" inFlight=").append(IN_FLIGHT.mappingCount());
        return sb.toString();
    }

    /** FIND-g03：探针生效面（关闭也打 {@code =false}）。 */
    public static String probeText() {
        return "parallel.chunk-step-telemetry.enabled=" + ENABLED
                + " parallel.chunk-step-telemetry.wait-split.enabled=" + WAIT_SPLIT_ENABLED;
    }

    private static String formatMs(double ms) {
        return String.format(Locale.ROOT, "%.4f", ms);
    }
}
