/*
 * PRTS - Arclight/Luminara fork
 * Copyright (c) 2024-2026 ElainAwa
 */

package io.izzel.arclight.common.optimization.eventbridge;

import org.bukkit.event.Event;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * GAP-4／GAP-6 事件监听器 per-mod 归因（M4 插桩 · 独立分支 feat/gap-telemetry · <b>默认关</b>）。
 *
 * <p>归属埋点：{@code org.bukkit.plugin.RegisteredListener#callEvent} 的前后置位／清位
 * （{@link io.izzel.arclight.common.mixin.optimization.eventbridge.RegisteredListenerMixin_EventAttribution}）。
 * 该方法是 Bukkit 事件分发的<b>唯一</b>每监听器调用点（{@code HandlerList} 在本版 spigot-api 无
 * {@code broadcast}，分发循环由服务端实现调用 {@code callEvent}）⇒ 一个注入点覆盖全部注册路径。</p>
 *
 * <p>口径（FIND-g05／S-6 写死）：三桶占比 = <b>实测</b>；{@code unclassified = 100 − 三桶和} 为残差，
 * 不计入三桶分母、不得摊回；四桶形态只作派生展示。桶的<b>分母来自周期栈采样</b>
 * （{@link ModThreadCensus}：每轮普查对每个 RUNNABLE 线程记 1 个样本），不是 wall-clock 累加。</p>
 *
 * <p>零语义变更：不取消、不重排、不跳过任何监听器；不做事件池、不改 {@code EventBus.post} 本体；
 * 关闭开关时每个监听器调用只多一次 volatile 读。异常逃逸（RETURN 未执行）会留下过期帧，
 * 由 {@link #listenerEnter} 的超时清理兜底（30s）。</p>
 */
public final class EventAttributionStats {

    /** 开关：{@code parallel.event-listener-attribution.enabled}（默认 false）。 */
    public static volatile boolean ENABLED;

    private static final int MAX_EVENT_CLASSES = 2048;
    private static final int MAX_OWNERS_PER_EVENT = 64;
    /** 过期帧阈值：监听器抛异常时 RETURN 不执行，超过该时长的栈帧直接丢弃。 */
    private static final long STALE_NANOS = 30_000_000_000L;

    /** 事件类 → (归属名 → 计数)。归属名 = {@code RegisteredListener.getPlugin().getName()}。 */
    private static final ConcurrentHashMap<Class<?>, ConcurrentHashMap<String, Entry>> BY_EVENT = new ConcurrentHashMap<>();
    private static final LongAdder TOTAL_NANOS = new LongAdder();
    private static final LongAdder TOTAL_COUNT = new LongAdder();
    private static final LongAdder OVERFLOW = new LongAdder();

    private EventAttributionStats() {
    }

    /** 每线程配对栈（复用数组，稳态零分配；线程内事件可嵌套）。 */
    private static final class Ctx {
        long[] starts = new long[16];
        Object[] entries = new Object[16];
        int depth;
    }

    private static final ThreadLocal<Ctx> CTX = ThreadLocal.withInitial(Ctx::new);

    private static final class Entry {
        final LongAdder nanos = new LongAdder();
        final LongAdder count = new LongAdder();
    }

    /** 配置注入（{@code PRTSFeaturesConfig.init}）。 */
    public static void setEnabled(boolean enabled) {
        ENABLED = enabled;
    }

    /** 主线程每 tick 驱动（普查节流在 {@link ModThreadCensus} 内）。 */
    public static void tick(long serverTick) {
        ModThreadCensus.tick(serverTick);
    }

    /** 监听器调用前：置位归属并记录起点。 */
    public static void listenerEnter(Event event, Plugin plugin) {
        if (!ENABLED || event == null) {
            return;
        }
        Ctx ctx = CTX.get();
        long now = System.nanoTime();
        // 上一次调用异常逃逸（RETURN 未执行）会留下过期帧：先清理再入栈
        while (ctx.depth > 0 && now - ctx.starts[ctx.depth - 1] > STALE_NANOS) {
            ctx.depth--;
            ctx.entries[ctx.depth] = null;
        }
        if (ctx.depth == ctx.starts.length) {
            grow(ctx);
        }
        ctx.entries[ctx.depth] = entryFor(event.getClass(), plugin == null ? "unknown" : plugin.getName());
        ctx.starts[ctx.depth] = now;
        ctx.depth++;
    }

    /** 监听器调用后：清位并记账。 */
    public static void listenerExit() {
        if (!ENABLED) {
            return;
        }
        Ctx ctx = CTX.get();
        if (ctx.depth <= 0) {
            return;
        }
        int index = --ctx.depth;
        Object entry = ctx.entries[index];
        ctx.entries[index] = null;
        long nanos = System.nanoTime() - ctx.starts[index];
        if (entry instanceof Entry e) {
            e.nanos.add(nanos);
            e.count.increment();
            TOTAL_NANOS.add(nanos);
            TOTAL_COUNT.increment();
        }
    }

    /** 当前线程是否在监听器回调内（供线程普查判定；跨线程不可读 —— 别线程用栈帧判定）。 */
    public static boolean inCallback() {
        return CTX.get().depth > 0;
    }

    private static void grow(Ctx ctx) {
        int n = ctx.starts.length * 2;
        ctx.starts = Arrays.copyOf(ctx.starts, n);
        ctx.entries = Arrays.copyOf(ctx.entries, n);
    }

    private static Entry entryFor(Class<?> eventClass, String owner) {
        ConcurrentHashMap<String, Entry> byOwner = BY_EVENT.get(eventClass);
        if (byOwner == null) {
            if (BY_EVENT.size() >= MAX_EVENT_CLASSES) {
                OVERFLOW.increment();
                return null;
            }
            byOwner = BY_EVENT.computeIfAbsent(eventClass, k -> new ConcurrentHashMap<>());
        }
        Entry entry = byOwner.get(owner);
        if (entry == null) {
            if (byOwner.size() >= MAX_OWNERS_PER_EVENT) {
                OVERFLOW.increment();
                return null;
            }
            entry = byOwner.computeIfAbsent(owner, k -> new Entry());
        }
        return entry;
    }

    /**
     * GAP-4 读数：三桶栈采样（{@link ModThreadCensus}）+ per-mod 监听器时间 + 自有线程归属。
     * 供新增子命令 {@code servercore eventattr [N]} 使用。
     */
    public static String statusText(int limit) {
        StringBuilder sb = new StringBuilder();
        sb.append(ModThreadCensus.bucketText());
        sb.append(" listenerCalls=").append(TOTAL_COUNT.sum())
                .append(" listenerMs=").append(TOTAL_NANOS.sum() / 1_000_000L);
        long overflow = OVERFLOW.sum();
        if (overflow > 0L) {
            sb.append(" overflow=").append(overflow);
        }
        List<ModRow> mods = modTotals();
        sb.append(" mods=[");
        appendMods(sb, mods, limit);
        sb.append(']');
        sb.append(" threads=[").append(ModThreadCensus.threadText(limit)).append(']');
        sb.append("\n  note: listenerMs 为监听器调用累计（本探针），bucket 为周期栈采样占比；"
                + "unclassified = 100 - 三桶和（残差，不计入三桶分母）");
        return sb.toString();
    }

    /**
     * GAP-6 读数：模组级风暴生成者 Top-N（复用 GAP-4 的 per-mod 归因）。
     * 供新增子命令 {@code servercore eventstorm [N]} 使用。
     */
    public static String stormText(int limit) {
        List<Row> rows = rows();
        StringBuilder sb = new StringBuilder();
        sb.append("rows=").append(rows.size())
                .append(" totalMs=").append(TOTAL_NANOS.sum() / 1_000_000L)
                .append(" totalCalls=").append(TOTAL_COUNT.sum());
        if (rows.isEmpty()) {
            sb.append("\n  (no samples: parallel.event-listener-attribution.enabled=false, or no listener calls yet)");
            return sb.toString();
        }
        int shown = 0;
        for (Row row : rows) {
            if (shown >= limit) {
                break;
            }
            sb.append("\n  ").append(row.eventClass).append(' ').append(row.owner)
                    .append(' ').append(row.nanos / 1_000_000L).append("ms/").append(row.count);
            shown++;
        }
        return sb.toString();
    }

    /** FIND-g03：探针生效面（关闭也打 {@code =false}）。 */
    public static String probeText() {
        return "parallel.event-listener-attribution.enabled=" + ENABLED
                + " parallel.mod-thread-census.enabled=" + ModThreadCensus.ENABLED;
    }

    private static void appendMods(StringBuilder sb, List<ModRow> mods, int limit) {
        int shown = 0;
        for (ModRow row : mods) {
            if (shown >= limit) {
                break;
            }
            if (shown > 0) {
                sb.append(',');
            }
            sb.append(row.owner).append('=').append(row.nanos / 1_000_000L).append("ms/").append(row.count);
            shown++;
        }
    }

    private static List<ModRow> modTotals() {
        ConcurrentHashMap<String, long[]> byOwner = new ConcurrentHashMap<>();
        for (ConcurrentHashMap<String, Entry> byOwnerMap : BY_EVENT.values()) {
            for (Map.Entry<String, Entry> e : byOwnerMap.entrySet()) {
                long[] sums = byOwner.computeIfAbsent(e.getKey(), k -> new long[2]);
                sums[0] += e.getValue().nanos.sum();
                sums[1] += e.getValue().count.sum();
            }
        }
        List<ModRow> rows = new ArrayList<>(byOwner.size());
        for (Map.Entry<String, long[]> e : byOwner.entrySet()) {
            rows.add(new ModRow(e.getKey(), e.getValue()[0], e.getValue()[1]));
        }
        rows.sort(Comparator.comparingLong((ModRow r) -> r.nanos).reversed());
        return rows;
    }

    private static List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Class<?>, ConcurrentHashMap<String, Entry>> byEvent : BY_EVENT.entrySet()) {
            String eventClass = byEvent.getKey().getName();
            for (Map.Entry<String, Entry> byOwner : byEvent.getValue().entrySet()) {
                rows.add(new Row(eventClass, byOwner.getKey(),
                        byOwner.getValue().nanos.sum(), byOwner.getValue().count.sum()));
            }
        }
        rows.sort(Comparator.comparingLong((Row r) -> r.nanos).reversed());
        return rows;
    }

    private record Row(String eventClass, String owner, long nanos, long count) {
    }

    private record ModRow(String owner, long nanos, long count) {
    }
}
