/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * One segment of one world of one tick: the rows the plan point claimed for takeover, the rows it
 * only observes, and the world inputs it froze for both. The two row sets are booked apart and are
 * asserted to share no row - an observed row has no token, no answer and no output, so it can
 * neither be committed nor counted as an ownership row. Every row carries the ordinal the host
 * visits it by, so the entry order can be checked against the order the rows were frozen in.
 *
 * <p>The segment holds three generations: the kernel generation of each row, the generation of the
 * world its inputs were frozen for, and its own freeze generation. The host entry compares all
 * three against frozen values only; it never asks the world again, so a world or a row that changed
 * between the two points fails the comparison instead of being read as its own successor.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.dispatch.FaultInjection;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import net.minecraft.server.level.ServerLevel;

/** The row sets and the frozen generations of one world's slice of one tick. */
final class SegmentWork {

    /** The owner mark of one host ordinal; a row is booked as owned or as observed, never both. */
    private static final byte OWNED = 1;
    private static final byte OBSERVED = 2;

    /** A row this segment took over: the identity frozen at the plan point and the token its answer
     * is spent on. */
    record RowRef(int entityId, long entityEpoch, int hostOrdinal, long token) {
    }

    /** A row this segment only observes: read-only, no token, no output row and no account. */
    record ObservedRef(int entityId, long entityEpoch, int hostOrdinal) {
    }

    /** One closed segment frame and every check it passed or failed. */
    record Frame(long tickIndex, String worldId, long segmentEpoch, int ownedRows, int observedRows,
        int committed, int fellBack, int notEntered, int observedEntries, int setConflicts,
        int unbookedCommits, int ordinalBroken, int entityRejected, int worldRejected,
        int segmentRejected, boolean ledgerOk) {
    }

    /**
     * The world inputs of one segment, frozen at its plan point: the world handle, its identity, the
     * generation the write guard tracks, and per row the verdict of the one world query the plan
     * point ran for it - whether the pushable-neighbour query came back empty. Blocks, fluids,
     * friction and collision shapes are not materialized here because the whole-tick models of this
     * segment read no world; freezing them would read the world for decisions that do not use it.
     */
    static final class FrozenWorldReadSet {

        private final ServerLevel level;
        private final String worldId;
        private final long worldEpoch;
        private long[] neighbourClear = new long[4];
        private int frozenRows;

        FrozenWorldReadSet(ServerLevel level, String worldId, long worldEpoch) {
            this.level = level;
            this.worldId = worldId;
            this.worldEpoch = worldEpoch;
        }

        ServerLevel level() {
            return level;
        }

        String worldId() {
            return worldId;
        }

        long worldEpoch() {
            return worldEpoch;
        }

        /** How many rows this set holds a frozen world verdict for. */
        int frozenRows() {
            return frozenRows;
        }

        /** Freezes the verdict of the neighbour query of one row; the only world read of the row. */
        void freezeNeighbourVerdict(int hostOrdinal, boolean clear) {
            int word = hostOrdinal >>> 6;
            if (word >= neighbourClear.length) {
                neighbourClear = java.util.Arrays.copyOf(neighbourClear,
                    Math.max(word + 1, neighbourClear.length * 2));
            }
            long bit = 1L << (hostOrdinal & 63);
            if (clear) {
                neighbourClear[word] |= bit;
            } else {
                neighbourClear[word] &= ~bit;
            }
            frozenRows++;
        }

        /** Whether the frozen verdict of this row says the neighbour query came back empty. */
        boolean neighbourClear(int hostOrdinal) {
            int word = hostOrdinal >>> 6;
            return word < neighbourClear.length
                && (neighbourClear[word] & (1L << (hostOrdinal & 63))) != 0L;
        }
    }

    private final long tickIndex;
    private final long segmentEpoch;
    private final FrozenWorldReadSet readSet;

    private RowRef[] owned = new RowRef[64];
    private int ownedRows;
    private ObservedRef[] observed = new ObservedRef[64];
    private int observedRows;
    private final Int2IntOpenHashMap observedById = new Int2IntOpenHashMap();

    private int[] ids = new int[64];
    private long[] epochs = new long[64];
    private long[] uuidHigh = new long[64];
    private long[] uuidLow = new long[64];
    private byte[] owner = new byte[64];
    private int ordinals;
    private int lastOrdinal = -1;

    private int committed;
    private int fellBack;
    private int notEntered;
    private int observedEntries;
    private int setConflicts;
    private int unbookedCommits;
    private int ordinalBroken;
    private int entityRejected;
    private int worldRejected;
    private int segmentRejected;
    private int conflictingEntity = -1;
    private int conflictingOrdinal = -1;

    SegmentWork(long tickIndex, String worldId, long worldEpoch, ServerLevel level,
        long segmentEpoch) {
        this.tickIndex = tickIndex;
        this.segmentEpoch = segmentEpoch;
        this.readSet = new FrozenWorldReadSet(level, worldId, worldEpoch);
        this.observedById.defaultReturnValue(-1);
    }

    long tickIndex() {
        return tickIndex;
    }

    long segmentEpoch() {
        return segmentEpoch;
    }

    FrozenWorldReadSet readSet() {
        return readSet;
    }

    int ownedRows() {
        return ownedRows;
    }

    int observedRows() {
        return observedRows;
    }

    RowRef owned(int index) {
        return owned[index];
    }

    ObservedRef observed(int index) {
        return observed[index];
    }

    /** The identity frozen under one host ordinal; a value the entry reads and never re-derives
     * from the world. */
    int entityIdOf(int hostOrdinal) {
        return hostOrdinal >= 0 && hostOrdinal < ordinals ? ids[hostOrdinal] : -1;
    }

    long uuidHighOf(int hostOrdinal) {
        return hostOrdinal >= 0 && hostOrdinal < ordinals ? uuidHigh[hostOrdinal] : 0L;
    }

    long uuidLowOf(int hostOrdinal) {
        return hostOrdinal >= 0 && hostOrdinal < ordinals ? uuidLow[hostOrdinal] : 0L;
    }

    boolean neighbourClearOf(int hostOrdinal) {
        return readSet.neighbourClear(hostOrdinal);
    }

    /**
     * Freezes one claimed row under its host ordinal: the identity, the kernel generation of the
     * row and the two halves of its uuid. The declared host-order fault makes the frame start as if
     * the host had already passed the first two ordinals, so the rows frozen in them are handed back
     * when the host reaches them; it fires once per frame, on its first claimed row.
     */
    int claim(int entityId, long entityEpoch, long liveUuidHigh, long liveUuidLow) {
        int ordinal = ordinals++;
        if (ordinal == 0 && FaultInjection.ownershipOrdinalBreak()) {
            lastOrdinal = 1;
        }
        ensure(ordinal);
        ids[ordinal] = entityId;
        epochs[ordinal] = entityEpoch;
        uuidHigh[ordinal] = liveUuidHigh;
        uuidLow[ordinal] = liveUuidLow;
        return ordinal;
    }

    /** Books the claimed row with its token; from here on it is part of the ownership set. */
    void book(int ordinal, long token) {
        growOwned();
        owned[ownedRows++] = new RowRef(ids[ordinal], epochs[ordinal], ordinal, token);
        owner[ordinal] = OWNED;
    }

    /** Books a row this segment only observes: no token, no answer, no account of its own. */
    int observe(int entityId, long entityEpoch) {
        int ordinal = ordinals++;
        ensure(ordinal);
        ids[ordinal] = entityId;
        epochs[ordinal] = entityEpoch;
        owner[ordinal] = OBSERVED;
        growObserved();
        observed[observedRows++] = new ObservedRef(entityId, entityEpoch, ordinal);
        observedById.put(entityId, ordinal);
        return ordinal;
    }

    /** Whether the ownership set of this segment holds the row frozen under this ordinal. */
    boolean ownsOrdinal(int hostOrdinal) {
        return hostOrdinal >= 0 && hostOrdinal < ordinals && owner[hostOrdinal] == OWNED;
    }

    /** The ordinal a row booked as observed was frozen under, or -1 when the row is not observed. */
    int observedOrdinal(int entityId) {
        return observedById.get(entityId);
    }

    /**
     * Accepts the host entry of one frozen row only when it arrives after every frozen row entered
     * before it: a commit of an ownership row at an ordinal the host already passed would land out
     * of the frozen order.
     */
    boolean acceptOrdinal(int hostOrdinal) {
        if (hostOrdinal <= lastOrdinal) {
            ordinalBroken++;
            return false;
        }
        lastOrdinal = hostOrdinal;
        return true;
    }

    /** Books the commit of one row against its ownership mark; a commit of a row this segment does
     * not own is a row that was observed and must never be written. */
    void noteCommit(int hostOrdinal) {
        if (!ownsOrdinal(hostOrdinal)) {
            unbookedCommits++;
        }
    }

    /** Books the entry of a row this segment observes; the row runs its original tick unskipped. */
    void noteObservedEntry(int entityId) {
        if (observedOrdinal(entityId) >= 0) {
            observedEntries++;
        }
    }

    /** Closes the frame: every claimed row of this segment ends in exactly one outcome, the two row
     * sets share no row, and no commit landed on a row that is not an ownership row. */
    Frame closeFrame(OwnershipLease lease) {
        if (lease == null) {
            // No lease of this tick reached the segment, so no ownership row of it was entered.
            notEntered = ownedRows;
        } else {
            for (int index = 0; index < lease.rows(); index++) {
                OwnershipLease.EntityCapability capability = lease.capability(index);
                if (capability.segmentEpoch() != segmentEpoch) {
                    continue;
                }
                if (!lease.looked(index)) {
                    notEntered++;
                } else if (lease.state(index) == OwnershipLease.CONSUMED) {
                    committed++;
                } else {
                    fellBack++;
                }
            }
        }
        checkSetsApart(lease);
        boolean ledgerOk = ownedRows == committed + fellBack + notEntered;
        return new Frame(tickIndex, readSet.worldId(), segmentEpoch, ownedRows, observedRows,
            committed, fellBack, notEntered, observedEntries, setConflicts, unbookedCommits,
            ordinalBroken, entityRejected, worldRejected, segmentRejected, ledgerOk);
    }

    /**
     * The set-level check of the frame: no row is both claimed and observed. The ownership set is
     * reachable by entity id through the lease and by ordinal through the marks here, so a row that
     * appears in both sets is found whichever way it was double booked.
     */
    private void checkSetsApart(OwnershipLease lease) {
        for (int at = 0; at < observedRows; at++) {
            ObservedRef ref = observed[at];
            boolean ownedHere = ref.hostOrdinal() >= 0 && ref.hostOrdinal() < ordinals
                && owner[ref.hostOrdinal()] == OWNED;
            boolean ownedElsewhere = lease != null && lease.indexOf(ref.entityId()) >= 0;
            if (ownedHere || ownedElsewhere) {
                setConflicts++;
                if (conflictingOrdinal < 0) {
                    conflictingEntity = ref.entityId();
                    conflictingOrdinal = ref.hostOrdinal();
                }
            }
        }
    }

    /** The identity of the first row this frame found in both sets, for a broken frame's log. */
    int conflictingEntity() {
        return conflictingEntity;
    }

    /** The ordinal of the first row this frame found in both sets, or -1 when they stayed apart. */
    int conflictingOrdinal() {
        return conflictingOrdinal;
    }

    int entityRejected() {
        return entityRejected;
    }

    int worldRejected() {
        return worldRejected;
    }

    int segmentRejected() {
        return segmentRejected;
    }

    int ordinalBroken() {
        return ordinalBroken;
    }

    void noteEntityRejected() {
        entityRejected++;
    }

    void noteWorldRejected() {
        worldRejected++;
    }

    void noteSegmentRejected() {
        segmentRejected++;
    }

    /** Books a row that is observed and owned at once; the declared fault that proves the set check
     * of the frame is not vacuous. */
    void conflictForFault(int entityId, long entityEpoch, int hostOrdinal) {
        growObserved();
        observed[observedRows++] = new ObservedRef(entityId, entityEpoch, hostOrdinal);
        observedById.put(entityId, hostOrdinal);
    }

    private void ensure(int ordinal) {
        if (ordinal < ids.length) {
            return;
        }
        int grown = Math.max(ordinal + 1, ids.length * 2);
        ids = java.util.Arrays.copyOf(ids, grown);
        epochs = java.util.Arrays.copyOf(epochs, grown);
        uuidHigh = java.util.Arrays.copyOf(uuidHigh, grown);
        uuidLow = java.util.Arrays.copyOf(uuidLow, grown);
        owner = java.util.Arrays.copyOf(owner, grown);
    }

    private void growOwned() {
        if (ownedRows == owned.length) {
            owned = java.util.Arrays.copyOf(owned, ownedRows * 2);
        }
    }

    private void growObserved() {
        if (observedRows == observed.length) {
            observed = java.util.Arrays.copyOf(observed, observedRows * 2);
        }
    }

    /** The segments of one tick, addressed by the world handle the row lives in. */
    static final class Table {

        private SegmentWork[] segments = new SegmentWork[2];
        private int rows;
        private long freezeSeq;

        void reset() {
            rows = 0;
        }

        /** The segment of that world, frozen here if this is the first row of the tick in it. */
        SegmentWork of(ServerLevel level, String worldId, long worldEpoch, long tickIndex) {
            SegmentWork known = live(level);
            if (known != null) {
                return known;
            }
            if (rows == segments.length) {
                segments = java.util.Arrays.copyOf(segments, rows * 2);
            }
            SegmentWork created = new SegmentWork(tickIndex, worldId, worldEpoch, level,
                ++freezeSeq);
            segments[rows++] = created;
            return created;
        }

        /** The segment of that world, or null when the plan point froze no row in it. */
        SegmentWork live(ServerLevel level) {
            for (int at = 0; at < rows; at++) {
                if (segments[at].readSet().level() == level) {
                    return segments[at];
                }
            }
            return null;
        }

        void adopt(SegmentWork segment) {
            if (rows == segments.length) {
                segments = java.util.Arrays.copyOf(segments, rows * 2);
            }
            segments[rows++] = segment;
        }

        int rows() {
            return rows;
        }

        SegmentWork at(int index) {
            return segments[index];
        }
    }
}
