/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.eventbridge;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * GAP-4 周期线程普查（M4 插桩 · 独立分支 feat/gap-telemetry · <b>默认关</b>）。
 *
 * <p><b>不碰线程创建路径</b>：不 hook {@code Thread.start}／线程池工厂，只在主线程按固定节拍
 * （{@value #INTERVAL_TICKS} tick = 10s）取一次 {@link Thread#getAllStackTraces()} 快照，对快照逐线程分类。
 * 探针自身异常一律吞掉（观测不得影响服务）。</p>
 *
 * <p><b>三桶口径（FIND-g05／S-6）</b>：每轮普查对每个处于 {@code RUNNABLE}/{@code NEW} 的线程记 1 个样本；
 * 非 RUNNABLE（WAITING/TIMED_WAITING/BLOCKED）计 {@code idle}，<b>不进任何桶</b>。分类规则（自上而下，先命中先算）：</p>
 * <ol>
 *   <li><b>event-cb</b>：本线程归属槽非空（{@link EventAttributionStats#inCallback()}），或栈上出现
 *       {@code org.bukkit.plugin.RegisteredListener.callEvent}／{@code org.bukkit.event.HandlerList}。</li>
 *   <li><b>mod-thread</b>：栈上第一个非宿主帧的类名（宿主前缀见 {@link #HOST_PREFIXES}）⇒ 该线程归属该模组
 *       （归属名 = 类名第二段包名，见 {@link #modHint}；完整类名不落盘，只作排行键）。</li>
 *   <li><b>host-loop</b>：栈上存在宿主帧（{@code net.minecraft.}/{@code io.izzel.}/{@code net.neoforged.} 等）。</li>
 *   <li><b>unclassified</b>：以上皆不成立（纯 {@code java.*}/{@code jdk.*} 的 JVM／库线程）⇒ 残差桶。</li>
 * </ol>
 *
 * <p>采样单位是「线程 × 普查轮次」，因此占比是 CPU 时间的<b>采样估计</b>，与 spark 的执行采样同谱系；
 * 报告层必须标口径（三桶和 = 100% ± 1% 为 E7 阈值，不是实测值，{@code e7-3-workstreams.md} @ 1234）。</p>
 */
public final class ModThreadCensus {

    /** 开关：{@code parallel.mod-thread-census.enabled}（默认 false）。 */
    public static volatile boolean ENABLED;

    /** 普查节拍（tick；200 = 10s @ 20tps）。 */
    public static final int INTERVAL_TICKS = 200;

    public static final int HOST_LOOP = 0;
    public static final int EVENT_CB = 1;
    public static final int MOD_THREAD = 2;
    public static final int UNCLASSIFIED = 3;

    private static final int MAX_MODS = 4096;
    private static final int MAX_THREAD_NAMES_PER_MOD = 8;
    private static final int MAX_PRINTED_NAMES = 3;

    /** 宿主（含 JVM／库）类名前缀；不在其中且出现在栈上的第一帧即视为模组自有线程。 */
    private static final String[] HOST_PREFIXES = {
            "java.", "javax.", "jdk.", "sun.", "com.sun.",
            "net.minecraft.", "io.izzel.", "net.neoforged.", "cpw.mods.", "net.minecraftforge.",
            "org.bukkit.", "org.spigotmc.", "io.netty.", "org.apache.",
            "it.unimi.", "com.mojang.", "org.slf4j.", "org.objectweb.asm.",
            "org.spongepowered.", "com.google.", "org.jetbrains."
    };

    /** 宿主帧前缀（判定 host-loop 用；比 HOST_PREFIXES 更窄 —— 纯 java.* 栈不算宿主）。 */
    private static final String[] HOST_FRAME_PREFIXES = {
            "net.minecraft.", "io.izzel.", "net.neoforged.", "cpw.mods.", "net.minecraftforge.",
            "org.bukkit.", "org.spigotmc."
    };

    private static final LongAdder[] BUCKET_SAMPLES = newBuckets(4);
    private static final LongAdder IDLE_SAMPLES = new LongAdder();
    private static final LongAdder ROUNDS = new LongAdder();
    /** 上次普查的 tick（从 0 起算；不能用 Long.MIN_VALUE —— 相减会溢出导致永不普查）。 */
    private static final AtomicLong LAST_TICK = new AtomicLong(0L);
    /** 本次运行内见过的模组自有线程名（按模组归组，上限防泄漏）。 */
    private static final ConcurrentHashMap<String, Set<String>> MOD_THREADS = new ConcurrentHashMap<>();
    /** 最近一轮普查的模组自有线程数（当前值，不是累计）。 */
    private static final ConcurrentHashMap<String, AtomicInteger> LAST_ROUND_COUNTS = new ConcurrentHashMap<>();

    private ModThreadCensus() {
    }

    private static LongAdder[] newBuckets(int n) {
        LongAdder[] arr = new LongAdder[n];
        for (int i = 0; i < n; i++) {
            arr[i] = new LongAdder();
        }
        return arr;
    }

    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    /** 主线程每 tick 调用；按 {@link #INTERVAL_TICKS} 节流。 */
    public static void tick(long serverTick) {
        if (!ENABLED) {
            return;
        }
        long last = LAST_TICK.get();
        if (serverTick - last < INTERVAL_TICKS) {
            return;
        }
        if (!LAST_TICK.compareAndSet(last, serverTick)) {
            return;
        }
        try {
            sample();
        } catch (Throwable ignored) {
            // 观测不得影响服务：任何快照异常一律吞掉（下一轮自然重试）
        }
    }

    /** 取一次全线程栈快照并逐线程分类（主线程调用；见类注释的分类规则）。 */
    public static void sample() {
        Map<Thread, StackTraceElement[]> snapshot = Thread.getAllStackTraces();
        ConcurrentHashMap<String, AtomicInteger> round = new ConcurrentHashMap<>();
        int hostLoop = 0;
        int eventCb = 0;
        int modThread = 0;
        int unclassified = 0;
        long idle = 0L;
        for (Map.Entry<Thread, StackTraceElement[]> entry : snapshot.entrySet()) {
            Thread thread = entry.getKey();
            StackTraceElement[] stack = entry.getValue();
            if (thread == null || stack == null || !thread.isAlive()) {
                continue;
            }
            Thread.State state = thread.getState();
            if (state != Thread.State.RUNNABLE && state != Thread.State.NEW) {
                idle++;
                continue;
            }
            if (isEventCallback(thread, stack)) {
                eventCb++;
                continue;
            }
            String owner = modOwner(stack);
            if (owner != null) {
                modThread++;
                round.computeIfAbsent(owner, k -> new AtomicInteger()).incrementAndGet();
                recordModThread(owner, thread.getName());
                continue;
            }
            if (hasHostFrame(stack)) {
                hostLoop++;
                continue;
            }
            unclassified++;
        }
        BUCKET_SAMPLES[HOST_LOOP].add(hostLoop);
        BUCKET_SAMPLES[EVENT_CB].add(eventCb);
        BUCKET_SAMPLES[MOD_THREAD].add(modThread);
        BUCKET_SAMPLES[UNCLASSIFIED].add(unclassified);
        IDLE_SAMPLES.add(idle);
        ROUNDS.increment();
        LAST_ROUND_COUNTS.clear();
        LAST_ROUND_COUNTS.putAll(round);
    }

    private static boolean isEventCallback(Thread thread, StackTraceElement[] stack) {
        if (thread == Thread.currentThread() && EventAttributionStats.inCallback()) {
            return true;
        }
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if ("org.bukkit.plugin.RegisteredListener".equals(className) && "callEvent".equals(frame.getMethodName())) {
                return true;
            }
            if ("org.bukkit.event.HandlerList".equals(className)) {
                return true;
            }
        }
        return false;
    }

    private static String modOwner(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className == null || isHostClass(className)) {
                continue;
            }
            return modHint(className);
        }
        return null;
    }

    private static boolean hasHostFrame(StackTraceElement[] stack) {
        for (StackTraceElement frame : stack) {
            String className = frame.getClassName();
            if (className == null) {
                continue;
            }
            for (String prefix : HOST_FRAME_PREFIXES) {
                if (className.startsWith(prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isHostClass(String className) {
        for (String prefix : HOST_PREFIXES) {
            if (className.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /** 类名 → 归属提示（模组名近似）：取 TLD 后的第一段包名，如 {@code com.minecolonies.core.X → minecolonies}。 */
    public static String modHint(String className) {
        int first = className.indexOf('.');
        if (first < 0) {
            return className;
        }
        int second = className.indexOf('.', first + 1);
        if (second < 0) {
            return className;
        }
        return className.substring(first + 1, second);
    }

    private static void recordModThread(String owner, String threadName) {
        Set<String> names = MOD_THREADS.get(owner);
        if (names == null) {
            if (MOD_THREADS.size() >= MAX_MODS) {
                return;
            }
            names = MOD_THREADS.computeIfAbsent(owner, k -> ConcurrentHashMap.newKeySet());
        }
        if (names.size() < MAX_THREAD_NAMES_PER_MOD && threadName != null) {
            names.add(threadName);
        }
    }

    /** 三桶 + 残差（GAP-4 主读数；口径见类注释）。 */
    public static String bucketText() {
        if (!ENABLED) {
            return "bucket=disabled (parallel.mod-thread-census.enabled=false)";
        }
        long host = BUCKET_SAMPLES[HOST_LOOP].sum();
        long event = BUCKET_SAMPLES[EVENT_CB].sum();
        long mod = BUCKET_SAMPLES[MOD_THREAD].sum();
        long unclassified = BUCKET_SAMPLES[UNCLASSIFIED].sum();
        long total = host + event + mod + unclassified;
        if (total <= 0L) {
            return "bucket={} unclassified=na (no RUNNABLE samples yet; interval=" + INTERVAL_TICKS + "ticks)";
        }
        double factor = 100.0d / total;
        return String.format(Locale.ROOT,
                "bucket={host-loop=%.1f%%,event-cb=%.1f%%,mod-thread=%.1f%%} unclassified=%.1f%% "
                        + "(residual=100-三桶和; RUNNABLE samples=%d, idle samples=%d, rounds=%d, interval=%dticks)",
                host * factor, event * factor, mod * factor, unclassified * factor,
                total, IDLE_SAMPLES.sum(), ROUNDS.sum(), INTERVAL_TICKS);
    }

    /** 模组自有线程归属（最近一轮线程数 + 见过的线程名样本）。 */
    public static String threadText(int limit) {
        if (!ENABLED) {
            return "disabled";
        }
        if (MOD_THREADS.isEmpty()) {
            return "none";
        }
        List<Map.Entry<String, Set<String>>> entries = new ArrayList<>(MOD_THREADS.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String, Set<String>> e) -> e.getValue().size()).reversed());
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (Map.Entry<String, Set<String>> entry : entries) {
            if (shown >= limit) {
                break;
            }
            if (shown > 0) {
                sb.append(", ");
            }
            AtomicInteger current = LAST_ROUND_COUNTS.get(entry.getKey());
            sb.append(entry.getKey()).append('=').append(current == null ? 0 : current.get()).append('/')
                    .append(entry.getValue().size()).append('(').append(joinNames(entry.getValue())).append(')');
            shown++;
        }
        return sb.toString();
    }

    private static String joinNames(Set<String> names) {
        StringBuilder sb = new StringBuilder();
        int shown = 0;
        for (String name : names) {
            if (shown >= MAX_PRINTED_NAMES) {
                break;
            }
            if (shown > 0) {
                sb.append('|');
            }
            sb.append(name.length() > 40 ? name.substring(0, 40) : name);
            shown++;
        }
        return sb.toString();
    }
}
