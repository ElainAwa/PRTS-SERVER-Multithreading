/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.safety;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Answers whether a rung that claims an effect moved what it acted on. The criterion is written
 * once and evaluated here; with {@code w} ticks between the two samples it is
 * {@code ZERO_EFFECT := entered > 0 && effective > 0 && metric(w) - metric(entry) == 0}.
 * {@code UNPROVEN} (entered > 0, effective == 0) is the honest state of a rung whose action never
 * ran and is not a finding. */
public final class ZeroEffectDetector {

    public enum State {
        NONE,
        PENDING,
        UNPROVEN,
        CHANGED,
        ZERO_EFFECT
    }

    public record Watch(long tickIndex, double metric) {
    }

    /** The judgement of one target. */
    public record Verdict(String target, long entered, long effective, long windowTicks,
                          long elapsedTicks, double metricAtEntry, double metricAtWindow,
                          double delta, State state) {

        public boolean violation() {
            return state == State.ZERO_EFFECT;
        }
    }

    private final Map<String, Watch> watches = new LinkedHashMap<>();
    private final Map<String, State> lastStates = new LinkedHashMap<>();
    private long zeroEffect;
    private long changed;
    private long unproven;

    /** Starts watching one target from the tick its rung was entered on. A target that already has
     * a watch is left alone, and a target that has been judged is not watched again until the
     * detector is reset: one finding per rung per run is what the window can support. */
    public synchronized void watch(String target, long tickIndex, double metric) {
        if (target == null || target.isEmpty() || lastStates.containsKey(target)) {
            return;
        }
        watches.putIfAbsent(target, new Watch(tickIndex, metric));
    }

    /** Judges one target. A verdict ends the watch, so the same entry is never counted twice. */
    public synchronized Verdict evaluate(String target, long tickIndex, double metric,
                                         long entered, long effective, int windowTicks) {
        int window = Math.max(1, windowTicks);
        Watch watch = target == null ? null : watches.get(target);
        if (watch == null) {
            return new Verdict(target == null ? "" : target, entered, effective, window, 0L, 0.0,
                metric, 0.0, State.NONE);
        }
        long elapsed = tickIndex - watch.tickIndex();
        if (elapsed < window) {
            return new Verdict(target, entered, effective, window, elapsed, watch.metric(), metric,
                0.0, State.PENDING);
        }
        watches.remove(target);
        double delta = metric - watch.metric();
        State state;
        if (entered <= 0L) {
            state = State.NONE;
        } else if (effective <= 0L) {
            state = State.UNPROVEN;
            unproven++;
        } else if (delta == 0.0) {
            state = State.ZERO_EFFECT;
            zeroEffect++;
        } else {
            state = State.CHANGED;
            changed++;
        }
        lastStates.put(target, state);
        return new Verdict(target, entered, effective, window, elapsed, watch.metric(), metric, delta,
            state);
    }

    public synchronized long zeroEffectTotal() {
        return zeroEffect;
    }

    public synchronized long changedTotal() {
        return changed;
    }

    public synchronized long unprovenTotal() {
        return unproven;
    }

    public synchronized int pending() {
        return watches.size();
    }

    public synchronized List<String> lastStates() {
        List<String> rows = new ArrayList<>(lastStates.size());
        for (Map.Entry<String, State> entry : lastStates.entrySet()) {
            rows.add(entry.getKey() + "=" + entry.getValue().name().toLowerCase(java.util.Locale.ROOT));
        }
        return rows;
    }

    public synchronized void reset() {
        watches.clear();
        lastStates.clear();
        zeroEffect = 0L;
        changed = 0L;
        unproven = 0L;
    }
}
