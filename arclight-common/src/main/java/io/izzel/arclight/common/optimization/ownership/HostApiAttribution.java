/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.ownership;

import io.izzel.arclight.common.optimization.eventbridge.ModThreadCensus;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * GAP-5 宿主 API 调用者归因（M4 插桩 · 独立分支 feat/gap-telemetry · <b>默认关</b>）。
 *
 * <p>靶点：{@code RESEARCH.md} D7 —— 既有的 {@code WorldAccessGuard.record} 与 {@code CrossRefProbe}
 * 只在 worker 侧归因，<b>主线程的宿主 API 调用没有调用者归因</b>。本探针在宿主 API 入口按
 * {@code (api, caller)} 计数，{@code caller} = {@link StackWalker} 最近的<b>非宿主</b>帧的类名
 * （宿主前缀见 {@link #HOST_PREFIXES}），并按主线程／其它线程分计。</p>
 *
 * <p><b>必须采样</b>（PLAN @97）：{@code getBlockState}/{@code getBlockEntity} 是最热路径，
 * 全量 {@code StackWalker} 不可接受 ⇒ 每 {@code sample-every} 次调用（默认 64，<b>每线程</b>计数、
 * 无跨线程争用）做一次归因；{@code sample-every = 0} = 关闭该探针（FIND-g08：int 键无真假语义）。</p>
 *
 * <p><b>口径诚实性</b>：本探针<b>不出 {@code self} 占比</b>（self = spark 剔 idle 总样本口径，
 * 见 PLAN @41；此处以 {@code self=na} 显式标记，不得用本探针数字冒充）。输出为
 * 调用<b>频次</b>份额 {@code share} + 调用者归因 + {@code unattributed}（宿主内部调用，无可归因模组帧）。</p>
 *
 * <p>零语义变更：HEAD 注入只读；不 cancel、不改返回值、不缓存任何宿主状态。</p>
 */
public final class HostApiAttribution {

    /** 开关：{@code parallel.hostapi-attribution.enabled}（默认 false）。 */
    public static volatile boolean ENABLED;
    /** 采样步长：每 N 次调用归因一次；0 = 关闭该探针（默认 64）。 */
    public static volatile int SAMPLE_EVERY = 64;

    public static final String API_GET_BLOCK_STATE = "Level.getBlockState";
    public static final String API_GET_BLOCK_ENTITY = "Level.getBlockEntity";
    public static final String API_GET_ENTITIES = "Level.getEntities";

    /** 宿主（含 JVM／库）类名前缀：命中即继续向上找调用者。 */
    private static final String[] HOST_PREFIXES = {
            "net.minecraft.", "io.izzel.", "net.neoforged.", "cpw.mods.", "net.minecraftforge.",
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "org.bukkit.", "org.spigotmc.", "io.netty.", "org.apache.",
            "it.unimi.", "com.mojang.", "org.slf4j.", "org.objectweb.asm.",
            "org.spongepowered.", "com.google.", "org.jetbrains."
    };

    private static final StackWalker WALKER = StackWalker.getInstance();
    private static final int MAX_API_KEYS = 64;
    private static final int MAX_CALLER_KEYS = 4096;
    /** 宿主内部调用（无模组帧）的归因名。 */
    public static final String HOST_INTERNAL = "_host_internal";

    /** api → (调用者类名 → 采样计数)。 */
    private static final ConcurrentHashMap<String, ConcurrentHashMap<String, LongAdder>> BY_API = new ConcurrentHashMap<>();
    private static final LongAdder TOTAL = new LongAdder();
    private static final LongAdder UNATTRIBUTED = new LongAdder();
    private static final LongAdder MAIN_THREAD = new LongAdder();

    /** 每线程采样计数器（1 元素数组，稳态零分配）。 */
    private static final ThreadLocal<int[]> SAMPLE = ThreadLocal.withInitial(() -> new int[1]);

    private HostApiAttribution() {
    }

    /** 配置注入（{@code PRTSFeaturesConfig.init}）。 */
    public static void setEnabled(boolean enabled, int sampleEvery) {
        SAMPLE_EVERY = Math.max(0, sampleEvery);
        ENABLED = enabled;
    }

    /** 宿主 API 入口（HEAD 注入调用）。 */
    public static void onEnter(String api) {
        if (!ENABLED) {
            return;
        }
        int every = SAMPLE_EVERY;
        if (every <= 0) {
            return;
        }
        if (every > 1) {
            int[] counter = SAMPLE.get();
            int n = (counter[0] + 1) & 0x7FFFFFFF;
            counter[0] = n;
            if (n % every != 0) {
                return;
            }
        }
        boolean main = "Server thread".equals(Thread.currentThread().getName());
        String caller = callerClass();
        ConcurrentHashMap<String, LongAdder> byCaller = BY_API.get(api);
        if (byCaller == null) {
            if (BY_API.size() >= MAX_API_KEYS) {
                TOTAL.increment();
                UNATTRIBUTED.increment();
                return;
            }
            byCaller = BY_API.computeIfAbsent(api, k -> new ConcurrentHashMap<>());
        }
        String key = caller == null ? HOST_INTERNAL : caller;
        LongAdder adder = byCaller.get(key);
        if (adder == null) {
            if (byCaller.size() >= MAX_CALLER_KEYS) {
                TOTAL.increment();
                UNATTRIBUTED.increment();
                return;
            }
            adder = byCaller.computeIfAbsent(key, k -> new LongAdder());
        }
        adder.increment();
        TOTAL.increment();
        if (caller == null) {
            UNATTRIBUTED.increment();
        }
        if (main) {
            MAIN_THREAD.increment();
        }
    }

    private static String callerClass() {
        return WALKER.walk(frames -> frames
                .map(StackWalker.StackFrame::getClassName)
                .filter(className -> !isHostClass(className))
                .findFirst()
                .orElse(null));
    }

    private static boolean isHostClass(String className) {
        for (String prefix : HOST_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 读数（供新增子命令 {@code servercore topapi [N]}）：按 api 降序，每行
     * {@code <api> n=<采样数> share=<占全部采样比> self=na caller=<模组>}，
     * 末尾给出 {@code unattributed} 与主线程份额。
     */
    public static String statusText(int limit) {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<String, ConcurrentHashMap<String, LongAdder>> apiEntry : BY_API.entrySet()) {
            ConcurrentHashMap<String, long[]> byMod = new ConcurrentHashMap<>();
            for (Map.Entry<String, LongAdder> callerEntry : apiEntry.getValue().entrySet()) {
                String caller = callerEntry.getKey();
                String mod = HOST_INTERNAL.equals(caller) ? HOST_INTERNAL : ModThreadCensus.modHint(caller);
                long[] sums = byMod.computeIfAbsent(mod, k -> new long[1]);
                sums[0] += callerEntry.getValue().sum();
            }
            for (Map.Entry<String, long[]> modEntry : byMod.entrySet()) {
                rows.add(new Row(apiEntry.getKey(), modEntry.getKey(), modEntry.getValue()[0]));
            }
        }
        rows.sort(Comparator.comparingLong((Row r) -> r.count).reversed());
        long total = TOTAL.sum();
        StringBuilder sb = new StringBuilder();
        sb.append("samples=").append(total)
                .append(" sampleEvery=").append(SAMPLE_EVERY);
        int shown = 0;
        for (Row row : rows) {
            if (shown >= limit) {
                break;
            }
            sb.append("\n  ").append(row.api).append(" n=").append(row.count)
                    .append(String.format(Locale.ROOT, " share=%.1f%%", total > 0L ? row.count * 100.0d / total : 0.0d))
                    .append(" self=na caller=").append(row.mod);
            shown++;
        }
        sb.append(String.format(Locale.ROOT, "\nunattributed=%.1f%% (host-internal: no mod frame on stack)",
                total > 0L ? UNATTRIBUTED.sum() * 100.0d / total : 0.0d));
        sb.append(String.format(Locale.ROOT, "\nmainThreadShare=%.1f%%",
                total > 0L ? MAIN_THREAD.sum() * 100.0d / total : 0.0d));
        sb.append("\nnote: self 占比 = spark 口径（PLAN @41，未核实）；本探针只出频次 + 调用者归因，"
                + "不得用 share 冒充 self");
        if (total <= 0L) {
            sb.append("\n(no samples: parallel.hostapi-attribution.enabled=false / sample-every=0, or no calls yet)");
        }
        return sb.toString();
    }

    /** FIND-g03：探针生效面（关闭也打 {@code =false}）。 */
    public static String probeText() {
        return "parallel.hostapi-attribution.enabled=" + ENABLED
                + " parallel.hostapi-attribution.sample-every=" + SAMPLE_EVERY;
    }

    private record Row(String api, String mod, long count) {
    }
}
