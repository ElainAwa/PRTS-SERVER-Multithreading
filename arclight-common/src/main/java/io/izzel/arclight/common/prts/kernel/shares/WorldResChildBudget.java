/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import java.util.List;

/** The child budget of the world-resource row: the parent WORLDRES row of one world and tick is
 * split into generation, light, the parent other part and the declared margin, and the parent row is
 * counted once. */
public final class WorldResChildBudget {

    /** Where the two child shares came from; every value names a different declared answer. */
    public enum SplitSource {

        DECLARED_TASK_TIME,

        KNOWN_ZERO_DEMAND,

        DEMAND_UNKNOWN,

        NONE
    }

    /** The settlement state of one child budget. */
    public enum Status {

        SETTLED,

        UNKNOWN_DEMAND,

        PARENT_OVER,

        INVALID_INPUT,

        NO_PARENT
    }

    /** Two task-nanosecond samples: the generation and light time of one world. */
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

        /** The generation sample in milliseconds. */
        public double genTaskMs() {
            return genTaskNanos / 1_000_000.0;
        }

        /** The light sample in milliseconds. */
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

    /** Settles one world's child budget from the real table's world-resource row; nothing is defaulted. */
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
        if (!Double.isFinite(available)) {
            return new WorldResChildBudget(worldId, tick, epoch, parentShare, 0.0, 0.0,
                otherMs, marginMs, 0.0, 0.0, 0.0, SplitSource.NONE, Status.INVALID_INPUT);
        }
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
        double genNanos = safeDemand.genTaskNanos();
        double lightNanos = safeDemand.lightTaskNanos();
        double demandNanos = genNanos + lightNanos;
        double genRatio;
        if (Double.isFinite(demandNanos) && demandNanos > 0.0) {
            genRatio = genNanos / demandNanos;
        } else {
            // Both samples are finite but their sum can overflow: scale by the larger one.
            double max = Math.max(genNanos, lightNanos);
            if (!Double.isFinite(max) || max <= 0.0) {
                return new WorldResChildBudget(worldId, tick, epoch, parentShare, 0.0, 0.0,
                    otherMs, marginMs, 0.0, 0.0, 0.0, SplitSource.NONE, Status.INVALID_INPUT);
            }
            double genScaled = genNanos / max;
            double lightScaled = lightNanos / max;
            double scaledSum = genScaled + lightScaled;
            if (!Double.isFinite(scaledSum) || scaledSum <= 0.0) {
                return new WorldResChildBudget(worldId, tick, epoch, parentShare, 0.0, 0.0,
                    otherMs, marginMs, 0.0, 0.0, 0.0, SplitSource.NONE, Status.INVALID_INPUT);
            }
            genRatio = genScaled / scaledSum;
        }
        double genShare = available * genRatio;
        // One multiply and one subtraction: a second multiply could round past the row.
        double lightShare = available - genShare;
        if (!Double.isFinite(genShare) || !Double.isFinite(lightShare) || lightShare < 0.0) {
            return new WorldResChildBudget(worldId, tick, epoch, parentShare, 0.0, 0.0,
                otherMs, marginMs, 0.0, 0.0, 0.0, SplitSource.NONE, Status.INVALID_INPUT);
        }
        return new WorldResChildBudget(worldId, tick, epoch, parentShare, genShare, lightShare,
            otherMs, marginMs, 0.0, genNanos, lightNanos,
            SplitSource.DECLARED_TASK_TIME, Status.SETTLED);
    }

    private static boolean validPart(double millis) {
        return Double.isFinite(millis) && millis >= 0.0;
    }

    private static double finiteOrZero(double value) {
        return Double.isFinite(value) ? value : 0.0;
    }

    /** The world the settlement belongs to. */
    public String worldId() {
        return worldId;
    }

    /** The tick of the table the settlement answered. */
    public long tickIndex() {
        return tickIndex;
    }

    /** The world epoch the settlement was taken under. */
    public long epoch() {
        return epoch;
    }

    /** The parent world-resource row the settlement partitions. */
    public double parentShareMs() {
        return parentShareMs;
    }

    /** The generation child share. */
    public double genShareMs() {
        return genShareMs;
    }

    /** The light child share. */
    public double lightShareMs() {
        return lightShareMs;
    }

    /** The explicit parent other part. */
    public double otherMs() {
        return otherMs;
    }

    /** The explicit margin kept inside the parent row. */
    public double marginMs() {
        return marginMs;
    }

    /** The parent allowance no declared part named. */
    public double unallocatedMs() {
        return unallocatedMs;
    }

    /** The generation task-nanosecond sample the ratio was taken from, zero when none is known. */
    public double genTaskNanos() {
        return genTaskNanos;
    }

    /** The light task-nanosecond sample the ratio was taken from, zero when none is known. */
    public double lightTaskNanos() {
        return lightTaskNanos;
    }

    /** Where the two child shares came from. */
    public SplitSource splitSource() {
        return splitSource;
    }

    /** The settlement state of this budget. */
    public Status status() {
        return status;
    }

    /** The two child shares together. */
    public double childShareMs() {
        return genShareMs + lightShareMs;
    }

    /** Everything the settlement names inside the parent row, unallocated rest included. */
    public double plannedShareMs() {
        return genShareMs + lightShareMs + otherMs + marginMs + unallocatedMs;
    }

    /** The whole settlement fits the parent row within the rounding tolerance; a non-finite term never passes. */
    public boolean conserved() {
        if (status == Status.PARENT_OVER || status == Status.INVALID_INPUT
            || status == Status.NO_PARENT) {
            return false;
        }
        double planned = plannedShareMs();
        return Double.isFinite(planned) && Double.isFinite(parentShareMs)
            && Math.abs(planned - parentShareMs) <= TOLERANCE
                * Math.max(1.0, parentShareMs());
    }

    /** The children plus the parent other part plus the margin never exceed the parent row. */
    public boolean withinParent() {
        return childShareMs() + otherMs + marginMs <= parentShareMs + TOLERANCE
            * Math.max(1.0, parentShareMs());
    }

    /** The two child rows, generation then light; the parent row is not repeated. */
    public List<ChildRow> childRows() {
        return List.of(new ChildRow(worldId, tickIndex, epoch, "gen", genShareMs),
            new ChildRow(worldId, tickIndex, epoch, "light", lightShareMs));
    }

    /** One child row of the world-resource parent. */
    public record ChildRow(String worldId, long tickIndex, long epoch, String child, double shareMs) {
    }
}
