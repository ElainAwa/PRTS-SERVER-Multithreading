/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import java.util.List;

/** The first-slice child budget of the world-resource row: the parent {@link ShareClass#WORLDRES}
 * row of one world and tick is split into a generation share, a light share, the explicit parent
 * other part and an explicit margin. The split is a partition, not a second budget: the parent row
 * is counted once and the children never add to it.
 *
 * <p>This slice is internal: it is not wired to a readout, no production path calls it, and the
 * metering action stays NOT_WIRED. The split source is explicit; an unknown demand is reported as
 * unknown and is never replaced by a fifty-fifty default. Only time cost enters the split: the two
 * demands are task nanoseconds, no count or capacity is read.
 */
public final class WorldResChildBudget {

    /** Where the two child shares came from. */
    public enum SplitSource {

        /** Both child demands are declared time costs; the shares are proportional to them. */
        DECLARED_TASK_TIME,

        /** Both child demands are a known zero; no child share is handed out. */
        KNOWN_ZERO_DEMAND,

        /** The child demand is unknown; no split is invented. */
        DEMAND_UNKNOWN,

        /** The inputs were invalid or the parent was exceeded; no split is handed out. */
        NONE
    }

    /** The settlement state of one child budget. */
    public enum Status {

        /** The children plus the parent other part plus the margin fit the parent row. */
        SETTLED,

        /** The demand is unknown; the children stay at zero and the rest stays unallocated. */
        UNKNOWN_DEMAND,

        /** The declared parent other part plus margin exceeds the parent row. */
        PARENT_OVER,

        /** A negative or non-finite input, or a non-finite parent row. */
        INVALID_INPUT,

        /** The world has no world-resource row in the table. */
        NO_PARENT
    }

    /** Two demands measured only as time, never as a count: the task nanoseconds of generation and
     * of light. {@link #unknown()} is the explicit "the demand is not known" answer. */
    public record ChildDemand(double genTaskNanos, double lightTaskNanos) {

        /** The explicit unknown demand; no default split is applied to it. */
        public static ChildDemand unknown() {
            return new ChildDemand(Double.NaN, Double.NaN);
        }

        /** A declared demand from two task-nanosecond samples. */
        public static ChildDemand ofTaskNanos(double genTaskNanos, double lightTaskNanos) {
            return new ChildDemand(genTaskNanos, lightTaskNanos);
        }

        /** Both values are the unknown sentinel. */
        public boolean isUnknown() {
            return Double.isNaN(genTaskNanos) && Double.isNaN(lightTaskNanos);
        }

        /** Both values are finite and non-negative. */
        public boolean isKnown() {
            return Double.isFinite(genTaskNanos) && genTaskNanos >= 0.0
                && Double.isFinite(lightTaskNanos) && lightTaskNanos >= 0.0;
        }

        /** A known demand whose two time samples are both zero. */
        public boolean isKnownZero() {
            return isKnown() && genTaskNanos == 0.0 && lightTaskNanos == 0.0;
        }

        public double genTaskMs() {
            return genTaskNanos / 1_000_000.0;
        }

        public double lightTaskMs() {
            return lightTaskNanos / 1_000_000.0;
        }
    }

    private static final double TOLERANCE = 1.0e-9;

    private final String worldId;
    private final long tickIndex;
    private final long epoch;
    private final double parentShareMs;
    private final double genShareMs;
    private final double lightShareMs;
    private final double otherMs;
    private final double marginMs;
    private final double unallocatedMs;
    private final double genTaskNanos;
    private final double lightTaskNanos;
    private final SplitSource splitSource;
    private final Status status;

    private WorldResChildBudget(String worldId, long tickIndex, long epoch, double parentShareMs,
                                double genShareMs, double lightShareMs, double otherMs,
                                double marginMs, double unallocatedMs, double genTaskNanos,
                                double lightTaskNanos, SplitSource splitSource, Status status) {
        this.worldId = worldId;
        this.tickIndex = tickIndex;
        this.epoch = epoch;
        this.parentShareMs = parentShareMs;
        this.genShareMs = genShareMs;
        this.lightShareMs = lightShareMs;
        this.otherMs = otherMs;
        this.marginMs = marginMs;
        this.unallocatedMs = unallocatedMs;
        this.genTaskNanos = genTaskNanos;
        this.lightTaskNanos = lightTaskNanos;
        this.splitSource = splitSource;
        this.status = status;
    }

    /** Settles one world's child budget from the real table's world-resource row. The caller passes
     * the epoch and the two declared parent parts explicitly; nothing is defaulted. */
    public static WorldResChildBudget settle(ShareTable table, String worldId, long epoch,
                                             ChildDemand demand, double otherMs,
                                             double marginMs) {
        long tick = table == null ? -1L : table.tickIndex();
        ShareTable.ShareRow parent = table == null || worldId == null ? null
            : table.row(worldId, ShareClass.WORLDRES);
        if (parent == null) {
            return new WorldResChildBudget(worldId, tick, epoch, 0.0, 0.0, 0.0,
                finiteOrZero(otherMs), finiteOrZero(marginMs), 0.0, 0.0, 0.0,
                SplitSource.NONE, Status.NO_PARENT);
        }
        double parentShare = parent.shareMs();
        if (!Double.isFinite(parentShare) || parentShare < 0.0
            || !validPart(otherMs) || !validPart(marginMs)) {
            return new WorldResChildBudget(worldId, tick, epoch, finiteOrZero(parentShare), 0.0,
                0.0, finiteOrZero(otherMs), finiteOrZero(marginMs), 0.0, 0.0, 0.0,
                SplitSource.NONE, Status.INVALID_INPUT);
        }
        ChildDemand safeDemand = demand == null ? ChildDemand.unknown() : demand;
        if (!safeDemand.isUnknown() && !safeDemand.isKnown()) {
            return new WorldResChildBudget(worldId, tick, epoch, parentShare, 0.0, 0.0,
                otherMs, marginMs, 0.0, 0.0, 0.0, SplitSource.NONE, Status.INVALID_INPUT);
        }
        double available = parentShare - otherMs - marginMs;
        if (available < 0.0) {
            return new WorldResChildBudget(worldId, tick, epoch, parentShare, 0.0, 0.0,
                otherMs, marginMs, 0.0, 0.0, 0.0, SplitSource.NONE, Status.PARENT_OVER);
        }
        if (safeDemand.isUnknown()) {
            return new WorldResChildBudget(worldId, tick, epoch, parentShare, 0.0, 0.0,
                otherMs, marginMs, available, 0.0, 0.0, SplitSource.DEMAND_UNKNOWN,
                Status.UNKNOWN_DEMAND);
        }
        if (safeDemand.isKnownZero()) {
            return new WorldResChildBudget(worldId, tick, epoch, parentShare, 0.0, 0.0,
                otherMs, marginMs, available, safeDemand.genTaskNanos(),
                safeDemand.lightTaskNanos(), SplitSource.KNOWN_ZERO_DEMAND, Status.SETTLED);
        }
        double demandNanos = safeDemand.genTaskNanos() + safeDemand.lightTaskNanos();
        double genShare = available * safeDemand.genTaskNanos() / demandNanos;
        double lightShare = available * safeDemand.lightTaskNanos() / demandNanos;
        return new WorldResChildBudget(worldId, tick, epoch, parentShare, genShare, lightShare,
            otherMs, marginMs, available - genShare - lightShare, safeDemand.genTaskNanos(),
            safeDemand.lightTaskNanos(), SplitSource.DECLARED_TASK_TIME, Status.SETTLED);
    }

    private static boolean validPart(double millis) {
        return Double.isFinite(millis) && millis >= 0.0;
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    public String worldId() {
        return worldId;
    }

    public long tickIndex() {
        return tickIndex;
    }

    public long epoch() {
        return epoch;
    }

    public double parentShareMs() {
        return parentShareMs;
    }

    public double genShareMs() {
        return genShareMs;
    }

    public double lightShareMs() {
        return lightShareMs;
    }

    public double otherMs() {
        return otherMs;
    }

    public double marginMs() {
        return marginMs;
    }

    public double unallocatedMs() {
        return unallocatedMs;
    }

    public double genTaskNanos() {
        return genTaskNanos;
    }

    public double lightTaskNanos() {
        return lightTaskNanos;
    }

    public SplitSource splitSource() {
        return splitSource;
    }

    public Status status() {
        return status;
    }

    /** The two child shares together. */
    public double childShareMs() {
        return genShareMs + lightShareMs;
    }

    /** Everything the settlement names inside the parent row: the children, the parent other part,
     * the margin and the rest that stayed unallocated. */
    public double plannedShareMs() {
        return genShareMs + lightShareMs + otherMs + marginMs + unallocatedMs;
    }

    /** The whole settlement fits the parent row within the rounding tolerance. */
    public boolean conserved() {
        return status != Status.PARENT_OVER && status != Status.INVALID_INPUT
            && status != Status.NO_PARENT
            && Math.abs(plannedShareMs() - parentShareMs) <= TOLERANCE
                * Math.max(1.0, parentShareMs());
    }

    /** The children plus the parent other part plus the margin never exceed the parent row. */
    public boolean withinParent() {
        return childShareMs() + otherMs + marginMs <= parentShareMs + TOLERANCE
            * Math.max(1.0, parentShareMs());
    }

    /** The child rows this settlement would name, in generation/light order; the parent row is not
     * repeated here, so a reader counting both levels can avoid a double count. */
    public List<ChildRow> childRows() {
        return List.of(new ChildRow(worldId, tickIndex, epoch, "gen", genShareMs),
            new ChildRow(worldId, tickIndex, epoch, "light", lightShareMs));
    }

    /** One child row of the world-resource parent; internal only, no readout key. */
    public record ChildRow(String worldId, long tickIndex, long epoch, String child, double shareMs) {
    }
}
