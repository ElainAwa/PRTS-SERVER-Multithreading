/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.exits;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Publishes the control plane and the judgement plane as two frames over one counter base. Both
 * exits read the same values of the same tick through the same origins: the judgement frame keeps
 * the closing tick's readings as its tail, and that tail is what makes the two exits comparable
 * field by field instead of only in prose.
 *
 * <p>Three rules are enforced here rather than documented. The control exit refuses a window
 * statistic - {@link #offerWindowStatistic} always answers false and counts the attempt. The
 * judgement exit refuses to serve a write path - {@link #noteWriteDependency} always answers false
 * and counts the attempt. And a frame names the readings it could not take, so a missing counter is
 * a refusal code and not a zero. */
public final class DualExits {

    /** Which exit published a reading. */
    public enum Plane {
        CONTROL,
        JUDGEMENT
    }

    /** Which control loop a reading belongs to. The gate of a phase requires all three groups to be
     * judgeable at the same time. */
    public enum Group {
        A,
        B,
        SHARED
    }

    /** One value as one exit publishes it. The origin names the counter in the layer that owns it,
     * and it is the same string on both exits; a reading whose origin is not shared is not
     * comparable and says so by carrying a plane-specific origin. */
    public record Reading(String name, Group group, Plane plane, String origin, double value,
                          String text) {
    }

    /** One tick as the layer that owns the values read them. */
    public record Sample(long tickIndex, double minMarginMs, long overrunHits, long waitBoundHits,
                         double reserveUsedMs, double reserveRemainingMs, String degradeState) {
    }

    /** One exit published at one tick. A judgement frame aggregates a window and keeps the closing
     * tick's readings as its tail; a control frame is the tick itself and has an empty tail. */
    public record Frame(Plane plane, boolean complete, List<String> missing, long tickIndex,
                        long firstTick, long windowTicks, List<Reading> readings,
                        List<Reading> tail) {

        public Frame {
            missing = List.copyOf(missing);
            readings = List.copyOf(readings);
            tail = List.copyOf(tail);
        }

        public Reading reading(String name) {
            for (Reading reading : readings) {
                if (reading.name().equals(name)) {
                    return reading;
                }
            }
            return null;
        }

        public Reading tailReading(String name) {
            for (Reading reading : tail) {
                if (reading.name().equals(name)) {
                    return reading;
                }
            }
            return null;
        }

        public List<Reading> group(Group group) {
            List<Reading> ofGroup = new ArrayList<>();
            for (Reading reading : readings) {
                if (reading.group() == group) {
                    ofGroup.add(reading);
                }
            }
            return ofGroup;
        }

        public boolean groupComplete(Group group) {
            List<Reading> ofGroup = group(group);
            if (ofGroup.isEmpty()) {
                return false;
            }
            for (Reading reading : ofGroup) {
                if (missing.contains(reading.name())) {
                    return false;
                }
            }
            return true;
        }
    }

    /** One field compared across the two exits, at the tick both were published on. */
    public record CrossCheck(String name, String origin, String control, String judgement,
                             boolean equal) {
    }

    /** The same-source evidence: how many fields both exits name with one origin and read to one
     * value at one tick. */
    public record SameSource(long controlTick, long judgementTick, int fields, int sameOrigin,
                             int sameValue, boolean equal, List<CrossCheck> checks) {
    }

    private static final String ORIGIN_MARGIN = "shares.last_table.min_margin_ms";
    private static final String ORIGIN_OVERRUN = "shares.last_table.overrun_hits";
    private static final String ORIGIN_WAIT = "waitpoints.overrun_count";
    private static final String ORIGIN_RESERVE_USED = "shares.reserve_used_ms";
    private static final String ORIGIN_RESERVE_LEFT = "shares.last_table.reserve_remaining_ms";
    private static final String ORIGIN_PHASE = "budget.states.phase";

    private final int windowTicks;
    private Frame control = emptyFrame(Plane.CONTROL);
    private Frame judgement = emptyFrame(Plane.JUDGEMENT);
    private Frame closeControl = emptyFrame(Plane.CONTROL);
    private long firstTick;
    private double minMargin = Double.POSITIVE_INFINITY;
    private long overrunSum;
    private long waitBoundSum;
    private double reserveUsedMax;
    private double reserveLeftMin = Double.POSITIVE_INFINITY;
    private long degradedTicks;
    private long controlFrames;
    private long judgementFrames;
    private long controlWindowFeeds;
    private long judgementWriteDependencies;
    private long missingTotal;

    public DualExits(int windowTicks) {
        this.windowTicks = Math.max(1, windowTicks);
    }

    /** Publishes the control frame of one tick from that tick's sample and folds it into the open
     * judgement window. When the window has reached its length the judgement frame is published
     * from the same sample, and both carry the closing tick. */
    public synchronized Frame noteTick(Sample sample, List<String> missing) {
        if (sample == null) {
            return control;
        }
        List<String> absent = missing == null ? List.of() : List.copyOf(missing);
        if (absent.size() > control.missing().size()) {
            missingTotal += absent.size() - (long) control.missing().size();
        }
        Frame tick = new Frame(Plane.CONTROL, absent.isEmpty(), absent, sample.tickIndex(),
            sample.tickIndex(), 1L, tickReadings(sample, absent), List.of());
        control = tick;
        controlFrames++;
        if (firstTick == 0L) {
            firstTick = sample.tickIndex();
        }
        fold(sample);
        long covered = sample.tickIndex() - firstTick + 1L;
        if (covered >= windowTicks) {
            judgement = new Frame(Plane.JUDGEMENT, absent.isEmpty(), absent, sample.tickIndex(),
                firstTick, covered, windowReadings(sample, covered, absent),
                tickReadings(sample, absent));
            // The control frame of the closing tick is kept next to the judgement frame: the
            // comparison has to be between one tick's two exits, not between the newest control
            // frame and an older window.
            closeControl = tick;
            judgementFrames++;
            firstTick = 0L;
            minMargin = Double.POSITIVE_INFINITY;
            overrunSum = 0L;
            waitBoundSum = 0L;
            reserveUsedMax = 0.0;
            reserveLeftMin = Double.POSITIVE_INFINITY;
            degradedTicks = 0L;
        }
        return control;
    }

    private void fold(Sample sample) {
        minMargin = Math.min(minMargin, sample.minMarginMs());
        overrunSum += sample.overrunHits();
        waitBoundSum += sample.waitBoundHits();
        reserveUsedMax = Math.max(reserveUsedMax, sample.reserveUsedMs());
        reserveLeftMin = Math.min(reserveLeftMin, sample.reserveRemainingMs());
        if (sample.degradeState() != null && sample.degradeState().startsWith("degraded")) {
            degradedTicks++;
        }
    }

    private static List<Reading> tickReadings(Sample sample, List<String> missing) {
        List<Reading> readings = new ArrayList<>(6);
        readings.add(numeric("min_margin_ms", Group.B, Plane.CONTROL, ORIGIN_MARGIN,
            sample.minMarginMs(), missing));
        readings.add(numeric("overrun_hits", Group.B, Plane.CONTROL, ORIGIN_OVERRUN,
            sample.overrunHits(), missing));
        readings.add(numeric("wait_bound_hits", Group.A, Plane.CONTROL, ORIGIN_WAIT,
            sample.waitBoundHits(), missing));
        readings.add(numeric("reserve_used_ms", Group.A, Plane.CONTROL, ORIGIN_RESERVE_USED,
            sample.reserveUsedMs(), missing));
        readings.add(numeric("reserve_remaining_ms", Group.A, Plane.CONTROL, ORIGIN_RESERVE_LEFT,
            sample.reserveRemainingMs(), missing));
        readings.add(text("degrade_state", Group.SHARED, Plane.CONTROL, ORIGIN_PHASE,
            sample.degradeState(), missing));
        return readings;
    }

    private List<Reading> windowReadings(Sample sample, long covered, List<String> missing) {
        List<Reading> readings = new ArrayList<>(6);
        double margin = minMargin == Double.POSITIVE_INFINITY ? 0.0 : minMargin;
        double left = reserveLeftMin == Double.POSITIVE_INFINITY ? 0.0 : reserveLeftMin;
        readings.add(numeric("min_margin_ms.window_min", Group.B, Plane.JUDGEMENT,
            ORIGIN_MARGIN, margin, missing));
        readings.add(numeric("overrun_hits.window_sum", Group.B, Plane.JUDGEMENT, ORIGIN_OVERRUN,
            overrunSum, missing));
        readings.add(numeric("wait_bound_hits.window_sum", Group.A, Plane.JUDGEMENT, ORIGIN_WAIT,
            waitBoundSum, missing));
        readings.add(numeric("reserve_used_ms.window_max", Group.A, Plane.JUDGEMENT,
            ORIGIN_RESERVE_USED, reserveUsedMax, missing));
        readings.add(numeric("reserve_remaining_ms.window_min", Group.A, Plane.JUDGEMENT,
            ORIGIN_RESERVE_LEFT, left, missing));
        readings.add(numeric("degraded_ticks", Group.SHARED, Plane.JUDGEMENT, ORIGIN_PHASE,
            degradedTicks, missing));
        readings.add(numeric("window_ticks", Group.SHARED, Plane.JUDGEMENT, "exits.window",
            covered, missing));
        return readings;
    }

    private static Reading numeric(String name, Group group, Plane plane, String origin,
                                   double value, List<String> missing) {
        return new Reading(name, group, plane, origin, value, render(value, missing.contains(name)));
    }

    private static Reading text(String name, Group group, Plane plane, String origin, String value,
                                List<String> missing) {
        String shown = missing.contains(name) || value == null ? "missing" : value;
        return new Reading(name, group, plane, origin, 0.0, shown);
    }

    private static String render(double value, boolean absent) {
        return absent ? "missing" : String.format(Locale.ROOT, "%.3f", value);
    }

    /** A window statistic offered to the control exit: refused, always, and counted. */
    public synchronized boolean offerWindowStatistic(String name, double value) {
        controlWindowFeeds++;
        return false;
    }

    /** A write path asking the judgement exit to serve it: refused, always, and counted. */
    public synchronized boolean noteWriteDependency(Plane plane) {
        if (plane != Plane.JUDGEMENT) {
            return false;
        }
        judgementWriteDependencies++;
        return false;
    }

    public synchronized Frame control() {
        return control;
    }

    public synchronized Frame judgement() {
        return judgement;
    }

    /** Compares the two exits at the one tick both were published on: the control frame of the
     * closing tick against the judgement frame's tail. */
    public synchronized SameSource sameSource() {
        return compare(closeControl, judgement);
    }

    /** The same comparison over two frames the caller holds. The self check drives it with a frame
     * that was published from a different sample, so the check is known to be able to fail. */
    public static SameSource compare(Frame control, Frame judgement) {
        List<CrossCheck> checks = new ArrayList<>();
        int sameOrigin = 0;
        int sameValue = 0;
        List<Reading> fields = control == null ? List.of() : control.readings();
        for (Reading field : fields) {
            Reading other = judgement == null ? null : judgement.tailReading(field.name());
            if (other == null) {
                checks.add(new CrossCheck(field.name(), field.origin(), field.text(), "missing",
                    false));
                continue;
            }
            boolean origin = field.origin().equals(other.origin());
            boolean equal = origin && field.text().equals(other.text());
            if (origin) {
                sameOrigin++;
            }
            if (equal) {
                sameValue++;
            }
            checks.add(new CrossCheck(field.name(), field.origin(), field.text(), other.text(),
                equal));
        }
        boolean equal = !fields.isEmpty() && sameValue == fields.size();
        return new SameSource(control == null ? 0L : control.tickIndex(),
            judgement == null ? 0L : judgement.tickIndex(), fields.size(), sameOrigin, sameValue,
            equal, checks);
    }

    public synchronized long controlFrames() {
        return controlFrames;
    }

    public synchronized long judgementFrames() {
        return judgementFrames;
    }

    public synchronized long controlWindowFeeds() {
        return controlWindowFeeds;
    }

    public synchronized long judgementWriteDependencies() {
        return judgementWriteDependencies;
    }

    public synchronized long missingTotal() {
        return missingTotal;
    }

    public synchronized int windowTicks() {
        return windowTicks;
    }

    /** The names both exits publish, in the order the control plane lists them. */
    public static List<String> controlNames() {
        return List.of("min_margin_ms", "overrun_hits", "wait_bound_hits", "reserve_used_ms",
            "reserve_remaining_ms", "degrade_state");
    }

    /** The three groups and whether each one can be judged right now. */
    public synchronized Map<String, Boolean> groups() {
        Map<String, Boolean> groups = new LinkedHashMap<>();
        for (Group group : Group.values()) {
            groups.put(group.name().toLowerCase(Locale.ROOT), judgement.groupComplete(group));
        }
        return groups;
    }

    public synchronized void reset() {
        control = emptyFrame(Plane.CONTROL);
        judgement = emptyFrame(Plane.JUDGEMENT);
        closeControl = emptyFrame(Plane.CONTROL);
        firstTick = 0L;
        minMargin = Double.POSITIVE_INFINITY;
        overrunSum = 0L;
        waitBoundSum = 0L;
        reserveUsedMax = 0.0;
        reserveLeftMin = Double.POSITIVE_INFINITY;
        degradedTicks = 0L;
        controlFrames = 0L;
        judgementFrames = 0L;
        controlWindowFeeds = 0L;
        judgementWriteDependencies = 0L;
        missingTotal = 0L;
    }

    private static Frame emptyFrame(Plane plane) {
        return new Frame(plane, false, List.of(), 0L, 0L, 0L, List.of(), List.of());
    }
}
