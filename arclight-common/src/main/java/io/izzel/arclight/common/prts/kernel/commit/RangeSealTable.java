/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The immutable publish table of the range seals one lifecycle produced. A seal is keyed by its
 * freeze, its seat and its status cell, is published once, and is never replaced: the same key with
 * the same value is an idempotent publish, another value under it is a conflict, and a full table
 * refuses the newcomer instead of evicting a seal some unread line still names.
 *
 * <p>An obligation pins a seal across the end of its window; a release removes only an unpinned
 * seal and otherwise marks it so the obligation's settlement removes it. A reset starts another
 * generation: it empties the table and the pins, and a publish that captured the previous
 * generation is refused. A snapshot is a value, so a reader that held one still reads it after a
 * reset. */
public final class RangeSealTable {

    /** What one publish did. */
    public enum Publish {
        PUBLISHED,
        IDEMPOTENT,
        CONFLICT,
        FULL,
        REJECTED
    }

    /** The exact place a seal is published at: the freeze it was sealed in, the seat it proves and
     * the one cell whose status it carries. */
    public record Key(FrozenDeclarations.FreezeKey freeze, FrozenDeclarations.Seat seat, long cell) {

        public Key {
            freeze = freeze == null ? FrozenDeclarations.FreezeKey.NONE : freeze;
        }
    }

    /** A read-only view of a table at one moment, with the fold of every seal it carried. A replay
     * closure carries one of these: a reader that holds a snapshot compares the fold it recomputes
     * with the one the snapshot names, and never reads the live table again. */
    public record Snapshot(FrozenDeclarations.FreezeKey freeze, long planSequence,
                           List<FrozenDeclarations.RangeSeal> seals, long digest) {

        public Snapshot {
            freeze = freeze == null ? FrozenDeclarations.FreezeKey.NONE : freeze;
            seals = seals == null ? List.of() : List.copyOf(seals);
        }

        /** The snapshot a table with no seal answers: no freeze, no plan and no seal. */
        public static Snapshot none() {
            return new Snapshot(FrozenDeclarations.FreezeKey.NONE, 0L, List.of(),
                fold(FrozenDeclarations.FreezeKey.NONE, 0L, List.of()));
        }

        /** The snapshot of one seal list, sorted by freeze ordinal, position and cell. */
        public static Snapshot of(FrozenDeclarations.FreezeKey freeze, long planSequence,
                                  List<FrozenDeclarations.RangeSeal> seals) {
            List<FrozenDeclarations.RangeSeal> ordered = sorted(seals);
            return new Snapshot(freeze, planSequence, ordered, fold(freeze, planSequence, ordered));
        }

        public boolean empty() {
            return seals.isEmpty();
        }

        /** Whether this snapshot may be read as the closure of one freeze: a declared freeze, a
         * positive plan, well-formed seals that all name that same freeze and plan, and one seal per
         * (position, cell) key. */
        public boolean wellFormed() {
            if (!freeze.declared() || planSequence <= 0L || seals.isEmpty()) {
                return false;
            }
            FrozenDeclarations.RangeSeal previous = null;
            for (FrozenDeclarations.RangeSeal seal : seals) {
                if (seal == null || !seal.wellFormed() || !freeze.equals(seal.freeze())
                    || seal.planSequence() != planSequence || seal.seat() == null) {
                    return false;
                }
                if (previous != null && ORDER.compare(previous, seal) >= 0) {
                    return false;
                }
                previous = seal;
            }
            return true;
        }

        /** The fold this snapshot's seals produce now. */
        public long recompute() {
            return fold(freeze, planSequence, seals);
        }

        /** Whether one typed seat's cell carries a seal in this snapshot. */
        public boolean has(long planSequence, int position, long cell) {
            return sealAt(planSequence, position, cell) != null;
        }

        /** The seal of one typed seat and cell, or null when the snapshot carries none. */
        public FrozenDeclarations.RangeSeal sealAt(long planSequence, int position, long cell) {
            for (FrozenDeclarations.RangeSeal seal : seals) {
                if (seal.planSequence() == planSequence && seal.seat() != null
                    && seal.seat().planSequence() == planSequence
                    && seal.seat().position() == position && cellOf(seal) == cell) {
                    return seal;
                }
            }
            return null;
        }
    }

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;
    private static final Comparator<FrozenDeclarations.RangeSeal> ORDER =
        Comparator.comparingLong(FrozenDeclarations.RangeSeal::freezeOrdinal)
            .thenComparingInt(seal -> seal.seat() == null ? 0 : seal.seat().position())
            .thenComparingLong(RangeSealTable::cellOf);

    private final int capacity;
    private final Map<Key, FrozenDeclarations.RangeSeal> seals = new LinkedHashMap<>();
    private final Map<Key, Integer> pins = new LinkedHashMap<>();
    private final Set<Key> released = new LinkedHashSet<>();
    private long generation = 1L;
    private long published;
    private long idempotent;
    private long conflicts;
    private long fulls;
    private long rejected;
    private long releasals;

    public RangeSealTable(int capacity) {
        this.capacity = Math.max(1, capacity);
    }

    /** The number of seals this table holds at most. */
    public synchronized int capacity() {
        return capacity;
    }

    /** The current lifecycle generation; a window captures it and a publish it no longer matches is
     * refused. */
    public synchronized long generation() {
        return generation;
    }

    /** Publishes a seal under the current generation. */
    public synchronized Publish publish(FrozenDeclarations.RangeSeal seal) {
        return publish(seal, generation);
    }

    /** Publishes a seal only if the generation a window captured is still the current one. A seal
     * that is not well formed is refused before anything is written; the table never rewrites a
     * carried digest. */
    public synchronized Publish publish(FrozenDeclarations.RangeSeal seal, long expectedGeneration) {
        if (seal == null || !seal.wellFormed() || expectedGeneration != generation) {
            rejected++;
            return Publish.REJECTED;
        }
        Key key = keyOf(seal, cellOf(seal));
        FrozenDeclarations.RangeSeal held = seals.get(key);
        if (held != null) {
            if (held.equals(seal)) {
                idempotent++;
                return Publish.IDEMPOTENT;
            }
            conflicts++;
            return Publish.CONFLICT;
        }
        if (seals.size() >= capacity) {
            fulls++;
            return Publish.FULL;
        }
        seals.put(key, seal);
        published++;
        return Publish.PUBLISHED;
    }

    /** The seal of one exact key, or null. Never the newest seal that happens to share the cell. */
    public synchronized FrozenDeclarations.RangeSeal lookup(FrozenDeclarations.FreezeKey freeze,
                                                           FrozenDeclarations.Seat seat, long cell) {
        return seals.get(new Key(freeze, seat, cell));
    }

    /** The one seal of a seat and cell whatever freeze it names; null when there is none or when
     * two lifecycles left one under the same seat. */
    public synchronized FrozenDeclarations.RangeSeal locate(FrozenDeclarations.Seat seat, long cell) {
        FrozenDeclarations.RangeSeal found = null;
        for (Map.Entry<Key, FrozenDeclarations.RangeSeal> entry : seals.entrySet()) {
            Key key = entry.getKey();
            if (key.seat().equals(seat) && key.cell() == cell) {
                if (found != null && !found.equals(entry.getValue())) {
                    return null;
                }
                found = entry.getValue();
            }
        }
        return found;
    }

    /** Whether this freeze's seat carries at least one seal. */
    public synchronized boolean hasSeat(FrozenDeclarations.FreezeKey freeze,
                                        FrozenDeclarations.Seat seat) {
        for (Key key : seals.keySet()) {
            if (key.freeze().equals(freeze) && key.seat().equals(seat)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the seat carries at least one seal under any freeze. */
    public synchronized boolean hasSeat(FrozenDeclarations.Seat seat) {
        for (Key key : seals.keySet()) {
            if (key.seat().equals(seat)) {
                return true;
            }
        }
        return false;
    }

    /** Ends a window: an unpinned seal leaves now, a pinned one leaves when its obligation is
     * settled. */
    public synchronized boolean release(FrozenDeclarations.RangeSeal seal) {
        if (seal == null) {
            return false;
        }
        Key key = keyOf(seal, cellOf(seal));
        if (!seals.containsKey(key)) {
            return false;
        }
        if (pins.getOrDefault(key, 0) > 0) {
            released.add(key);
        } else {
            seals.remove(key);
        }
        releasals++;
        return true;
    }

    /** Pins a seal while a line judged against it is still unread. A seal this table does not hold
     * is answered with the empty obligation. */
    public synchronized IdentityWitness.Obligation oblige(FrozenDeclarations.RangeSeal seal) {
        if (seal == null) {
            return IdentityWitness.Obligation.NONE;
        }
        Key key = keyOf(seal, cellOf(seal));
        if (!seals.containsKey(key)) {
            return IdentityWitness.Obligation.NONE;
        }
        pins.merge(key, 1, Integer::sum);
        return new IdentityWitness.Obligation() {

            private boolean settled;

            @Override
            public synchronized void settle() {
                if (settled) {
                    return;
                }
                settled = true;
                RangeSealTable.this.settle(key);
            }
        };
    }

    private synchronized void settle(Key key) {
        Integer held = pins.get(key);
        if (held == null) {
            return;
        }
        if (held <= 1) {
            pins.remove(key);
        } else {
            pins.put(key, held - 1);
        }
        if (!pins.containsKey(key) && released.remove(key)) {
            // A released seal leaves when the last obligation on it is settled, not when any one of
            // them is.
            seals.remove(key);
        }
    }

    /** A snapshot of the table as a value: a reader keeps it after a reset. */
    public synchronized Snapshot snapshot() {
        List<FrozenDeclarations.RangeSeal> held = new ArrayList<>(seals.values());
        FrozenDeclarations.FreezeKey freeze = FrozenDeclarations.FreezeKey.NONE;
        long planSequence = 0L;
        boolean same = !held.isEmpty();
        for (FrozenDeclarations.RangeSeal seal : held) {
            if (freeze.equals(FrozenDeclarations.FreezeKey.NONE)) {
                freeze = seal.freeze();
                planSequence = seal.planSequence();
            } else if (!freeze.equals(seal.freeze()) || planSequence != seal.planSequence()) {
                same = false;
            }
        }
        if (!same) {
            freeze = FrozenDeclarations.FreezeKey.NONE;
            planSequence = 0L;
        }
        return Snapshot.of(freeze, planSequence, held);
    }

    /** Ends the lifecycle: the table and every pin are cleared, the generation advances and a
     * snapshot a reader already holds stays readable. */
    public synchronized void reset() {
        generation++;
        seals.clear();
        pins.clear();
        released.clear();
        published = 0L;
        idempotent = 0L;
        conflicts = 0L;
        fulls = 0L;
        rejected = 0L;
        releasals = 0L;
    }

    public synchronized int size() {
        return seals.size();
    }

    public synchronized int pinnedCount() {
        return pins.size();
    }

    public synchronized long published() {
        return published;
    }

    public synchronized long idempotent() {
        return idempotent;
    }

    public synchronized long conflicts() {
        return conflicts;
    }

    public synchronized long fulls() {
        return fulls;
    }

    public synchronized long rejected() {
        return rejected;
    }

    public synchronized long releasals() {
        return releasals;
    }

    private static Key keyOf(FrozenDeclarations.RangeSeal seal, long cell) {
        return new Key(seal.freeze(), seal.seat(), cell);
    }

    private static long cellOf(FrozenDeclarations.RangeSeal seal) {
        if (seal.statuses().size() != 1) {
            return Long.MIN_VALUE;
        }
        return seal.statuses().keySet().iterator().next();
    }

    private static List<FrozenDeclarations.RangeSeal> sorted(
        List<FrozenDeclarations.RangeSeal> input) {
        List<FrozenDeclarations.RangeSeal> ordered = new ArrayList<>(
            input == null ? List.of() : input);
        ordered.sort(ORDER);
        return List.copyOf(ordered);
    }

    private static long fold(FrozenDeclarations.FreezeKey freeze, long planSequence,
                             List<FrozenDeclarations.RangeSeal> seals) {
        long hash = fold(OFFSET_BASIS, segment(freeze.lifecycleKey())
            + segment(Long.toString(freeze.generation())) + segment(Long.toString(planSequence)));
        for (FrozenDeclarations.RangeSeal seal : seals) {
            hash = foldSeal(hash, seal);
        }
        return hash;
    }

    private static long foldSeal(long hash, FrozenDeclarations.RangeSeal seal) {
        long value = hash;
        value = fold(value, segment(seal.seat() == null ? "" : seal.seat().worldId()));
        value = fold(value, segment(seal.seat() == null ? "" : seal.seat().domainId()));
        value = fold(value, segment(Long.toString(seal.planSequence())));
        value = fold(value, segment(Integer.toString(seal.seat() == null ? 0 : seal.seat().position())));
        value = fold(value, segment(Long.toString(seal.freezeOrdinal())));
        value = fold(value, segment(Long.toString(cellOf(seal))));
        value = fold(value, segment(seal.nodeKey()));
        value = fold(value, segment(Integer.toString(seal.role())));
        value = fold(value, segment(seal.worldId()));
        value = fold(value, segment(seal.mailbox()));
        value = fold(value, segment(seal.freeze().lifecycleKey()));
        value = fold(value, segment(Long.toString(seal.freeze().generation())));
        for (long cell : seal.cells()) {
            value = fold(value, segment(Long.toUnsignedString(cell, 16)));
        }
        value = fold(value, segment(seal.spaceDigest()));
        Map<Long, FrozenDeclarations.CellStatus> statuses = seal.statuses();
        List<Long> cells = new ArrayList<>(statuses.keySet());
        cells.sort(Comparator.naturalOrder());
        for (Long cell : cells) {
            FrozenDeclarations.CellStatus status = statuses.get(cell);
            value = fold(value, segment(Long.toUnsignedString(cell, 16)));
            value = fold(value, segment(status == null ? "" : status.targetStatus()));
            value = fold(value, stampSegment(status == null ? null : status.stamp()));
        }
        value = fold(value, segment(seal.statusMapDigest()));
        value = fold(value, segment(Long.toString(seal.instanceId())));
        value = fold(value, segment(Long.toString(seal.worldEpoch())));
        value = fold(value, stampSegment(seal.stamp()));
        return value;
    }

    private static String stampSegment(FrozenDeclarations.Stamp stamp) {
        if (stamp == null) {
            return segment("");
        }
        return segment(Long.toString(stamp.taskOrdinal())) + segment(stamp.world())
            + segment(stamp.mailbox()) + segment(Long.toString(stamp.cell()))
            + segment(Long.toString(stamp.mintTick()))
            + segment(Long.toString(stamp.batchFreezeOrdinal()))
            + segment(Long.toString(stamp.planSequence()))
            + segment(Long.toString(stamp.demandToken()));
    }

    private static String segment(String value) {
        String text = value == null ? "" : value;
        return text.length() + ":" + text;
    }

    private static long fold(long hash, String text) {
        long value = hash;
        for (int index = 0; index < text.length(); index++) {
            value = (value ^ text.charAt(index)) * PRIME;
        }
        return value;
    }
}
