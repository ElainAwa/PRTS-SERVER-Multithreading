/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

/** Staged intermediate results of the admitted self-row calculations for one owner row.
 * A result is consumed exactly once by
 * its original write-back point; a second take returns null so no value can be applied twice. */
public final class AltPhaseStage {

    private static final Map<String, Object> READY = new ConcurrentHashMap<>();
    private static final ThreadLocal<Map<String, Object>> CURRENT = new ThreadLocal<>();
    private static final String TARGET = System.getProperty("arclight.prts.diag.altOutRow", "").trim();
    private static final int LIMIT = limit(System.getProperty("arclight.prts.diag.altOutLimit", ""));
    private static final AtomicInteger LINES = new AtomicInteger();
    private static volatile String testTarget = null;
    private static volatile int testLimit = -1;

    private AltPhaseStage() {
    }

    private static int limit(String raw) {
        try { return Math.max(0, Integer.parseInt(raw.trim())); }
        catch (RuntimeException invalid) { return 0; }
    }

    /** Publishes one worker result under rowKey and kind. The optional tick is diagnostic only. */
    public static void stage(String rowKey, String kind, Object result) {
        stage(rowKey, kind, result, -1L);
    }

    /** Publishes one worker result and, when the experiment property selects this row, prints the
     * actual staged value together with the inputs frozen at issue time. Both texts are derived from
     * the published result and the frozen record; neither is recomputed from the live entity. */
    public static void stage(String rowKey, String kind, Object result, long tick, Object frozen) {
        if (result == null) return;
        READY.put(rowKey + "|" + kind, result);
        String target = testTarget != null ? testTarget : TARGET;
        int limit = testLimit >= 0 ? testLimit : LIMIT;
        if (target.isEmpty() || !rowKey.contains(target) || limit <= 0 || LINES.get() >= limit) return;
        LINES.incrementAndGet();
        System.out.println("[PRTS] altout|tick=" + tick + "|rowKey=" + rowKey
            + "|kind=" + kind + "|inputs=" + formatInputs(frozen)
            + "|value=" + format(result));
    }

    /** Kept for callers that have no frozen record available. */
    public static void stage(String rowKey, String kind, Object result, long tick) {
        stage(rowKey, kind, result, tick, null);
    }

    private static String formatInputs(Object frozen) {
        if (frozen instanceof AltPhaseSubComputation.AiHead head) {
            return "effectiveAi=" + head.effectiveAi() + ",immobile=" + head.immobile()
                + ",lerpSteps=" + head.lerpSteps() + ",request=" + head.vx() + "," + head.vy()
                + "," + head.vz();
        }
        if (frozen instanceof AltPhaseSubComputation.Controls controls) {
            return "rotation=" + controls.rotation() + ",xxa=" + controls.xxa()
                + ",zza=" + controls.zza() + ",jumping=" + controls.jumping();
        }
        if (frozen instanceof AltPhaseSubComputation.TickTail tail) {
            return "yBodyRot=" + tail.yBodyRot() + ",run=" + tail.run()
                + ",attackAnim=" + tail.attackAnim() + ",swingTime=" + tail.swing();
        }
        return "NA";
    }

    private static String format(Object result) {
        if (result instanceof double[] values) {
            StringBuilder out = new StringBuilder();
            for (double value : values) out.append(out.length() == 0 ? "" : ",").append(value);
            return out.toString();
        }
        if (result instanceof float[] values) {
            StringBuilder out = new StringBuilder();
            for (float value : values) out.append(out.length() == 0 ? "" : ",").append(value);
            return out.toString();
        }
        return String.valueOf(result);
    }

    /** Test-only overrides and reset. */
    public static void setTargetForTest(String value) { testTarget = value; }
    public static void setLimitForTest(int value) { testLimit = value; }
    public static int linesForTest() { return LINES.get(); }
    public static void resetForTest() { testTarget = null; testLimit = -1; LINES.set(0); }

    /** Moves one row's staged results onto the Server-thread seat. */
    public static void bind(String rowKey) {
        Map<String, Object> bound = new java.util.HashMap<>();
        for (String kind : new String[]{"aiHead", "controls", "tickTail"}) {
            Object value = READY.remove(rowKey + "|" + kind);
            if (value != null) bound.put(kind, value);
        }
        if (!bound.isEmpty()) CURRENT.set(bound);
    }

    /** Takes and consumes one staged result; the write-back point is single by construction. */
    @SuppressWarnings("unchecked")
    public static <T> T take(String kind) {
        Map<String, Object> bound = CURRENT.get();
        if (bound == null || !bound.containsKey(kind)) return null;
        Object value = bound.remove(kind);
        if (bound.isEmpty()) CURRENT.remove();
        return (T) value;
    }

    /** Ends one seat; unconsumed results are dropped, never carried to another row. */
    public static void unbind() {
        CURRENT.remove();
    }
}