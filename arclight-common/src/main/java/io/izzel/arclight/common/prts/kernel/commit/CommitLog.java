/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/** The single write entry of one tick. Every producer that reaches a commit reports it here, the log
 * resolves which step of which plan ordered it, and it accepts the report only while the order of
 * each (world, domain) pair keeps rising. The buffers are one ring per pair; the traversal that
 * closes the tick walks them in a fixed order and folds what it read, which is what a replayed run is
 * compared on. Nothing here sorts: the order is the plan's, and a report that contradicts it is
 * counted and dropped rather than reordered. */
public final class CommitLog {

    /** One commit a producer reached. The batch is the only shape that reaches the log, so no
     * producer can hand it a pre-ordered sequence of its own. */
    public record Batch(String worldId, String domainId, String nodeKey, Kind kind,
                        long expectedVersion, long writeSetDigest, int rows) {

        /** What the producer did with the write. The log records the kind; it never rewrites it. */
        public enum Kind {
            APPLY,
            MERGE,
            INTENT,
            RETRY,
            DROP
        }

        public Batch {
            if (worldId == null) {
                throw new IllegalArgumentException("a commit needs a world");
            }
            if (domainId == null || domainId.isEmpty()) {
                throw new IllegalArgumentException("a commit needs a domain");
            }
            if (kind == null) {
                throw new IllegalArgumentException("a commit needs a kind");
            }
        }
    }

    /** The one entry a producer reaches. A producer with no sink bound keeps its own path unchanged;
     * a producer with one reports what it did and is answered whether that was the planned order. The
     * answer is a verdict, not an interception. */
    @FunctionalInterface
    public interface Sink {

        void reach(Batch batch);
    }

    /** Where the order of a step comes from. The log asks; it never keeps a plan of its own. */
    public interface OrderSource {

        Resolved resolve(String worldId, String domainId, String nodeKey);
    }

    /** The order one commit was given by the planning period. A {@code null} answer means no plan
     * ordered this key. */
    public record Resolved(long planSequence, int position, boolean intent) {
    }

    /** One line of the log: the order the step had, what the producer did and what the log decided. */
    public record Entry(long sequence, long tickIndex, long planSequence, int position,
                        String worldId, String domainId, String nodeKey, long handle, Batch.Kind kind,
                        long expectedVersion, long writeSetDigest, int rows, Disposition disposition,
                        RejectCode code, boolean intent) {

        /** What the log did with one reached commit. */
        public enum Disposition {
            ACCEPTED,
            MERGED,
            INTENT,
            DROPPED,
            RETRIED
        }

        /** The pair a replayed log is compared on: the order the plan assigned and the fold of the
         * values written under it. */
        public record Step(long planSequence, int position, long writeSetDigest) {
        }

        public Step step() {
            return new Step(planSequence, position, writeSetDigest);
        }
    }

    /** What the log did with one report. */
    public record Verdict(boolean logged, Entry.Disposition disposition, RejectCode code,
                          long sequence, int position) {
    }

    /** What closing one tick read. The steps are the pairs a replayed run is compared on. */
    public record Replay(long tickIndex, int loggedSteps, int orderViolations, boolean orderMatches,
                         long logDigest, List<Entry.Step> steps, int watermarks) {

        public Replay {
            steps = List.copyOf(steps);
        }

        /** Asked of the run that happened and of the run that was replayed: zero means the two wrote
         * the same values in the same order. */
        public static int compare(Replay left, Replay right) {
            if (left == null || right == null) {
                return -1;
            }
            int differences = Math.abs(left.loggedSteps() - right.loggedSteps());
            int shared = Math.min(left.steps().size(), right.steps().size());
            for (int index = 0; index < shared; index++) {
                Entry.Step a = left.steps().get(index);
                Entry.Step b = right.steps().get(index);
                if (a.planSequence() != b.planSequence() || a.position() != b.position()
                    || a.writeSetDigest() != b.writeSetDigest()) {
                    differences++;
                }
            }
            return differences;
        }
    }

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private final IntSupplier ringCapacity;
    private final OrderSource source;
    private final Map<String, CommitRing> rings = new LinkedHashMap<>();
    private final Map<String, Long> lastPlanOfRing = new LinkedHashMap<>();
    private final Map<String, Integer> lastPositionOfRing = new LinkedHashMap<>();
    private long sequence;
    private long tickIndex;
    private long accepted;
    private long merged;
    private long intents;
    private long dropped;
    private long retried;
    private long orderViolations;
    private long refusedFull;
    private long unplanned;
    private long ticks;
    private int watermarks;
    private Replay last;
    private long firstDivergencePosition = -1L;

    public CommitLog(IntSupplier ringCapacity, OrderSource source) {
        this.ringCapacity = ringCapacity;
        this.source = source;
    }

    /** Opens one tick. The rings keep what a previous tick did not read, so a slow reader never loses
     * an entry to a new tick. */
    public void beginTick(long tick) {
        this.tickIndex = tick;
        firstDivergencePosition = -1L;
    }

    /** The one entry. */
    public Verdict reach(Batch batch) {
        if (batch == null) {
            return new Verdict(false, Entry.Disposition.DROPPED, RejectCode.COUNTER_MISSING, 0L, -1);
        }
        Resolved resolved = source == null ? null
            : source.resolve(batch.worldId(), batch.domainId(), batch.nodeKey());
        long position = resolved == null ? -1 : resolved.position();
        long planSequence = resolved == null ? 0L : resolved.planSequence();
        boolean intent = resolved != null ? resolved.intent()
            : batch.kind() == Batch.Kind.INTENT;
        Entry.Disposition disposition;
        RejectCode code = null;
        if (resolved == null && !intent) {
            // A write no plan ordered is not put in front of the planned ones: it is counted as
            // unplanned and dropped from the ordered sequence, and the reason is readable.
            unplanned++;
            disposition = Entry.Disposition.DROPPED;
            code = RejectCode.COMMIT_ORDER_VIOLATION;
            dropped++;
        } else if (resolved != null && !ascending(batch.worldId(), batch.domainId(), planSequence,
            position)) {
            orderViolations++;
            if (firstDivergencePosition < 0L) {
                firstDivergencePosition = position;
            }
            disposition = Entry.Disposition.DROPPED;
            code = RejectCode.COMMIT_ORDER_VIOLATION;
            dropped++;
        } else {
            if (resolved != null) {
                lastPlanOfRing.put(key(batch.worldId(), batch.domainId()), planSequence);
                lastPositionOfRing.put(key(batch.worldId(), batch.domainId()), (int) position);
            }
            disposition = switch (batch.kind()) {
                case APPLY -> Entry.Disposition.ACCEPTED;
                case MERGE -> Entry.Disposition.MERGED;
                case INTENT -> Entry.Disposition.INTENT;
                case RETRY -> Entry.Disposition.RETRIED;
                case DROP -> Entry.Disposition.DROPPED;
            };
            switch (disposition) {
                case ACCEPTED -> accepted++;
                case MERGED -> merged++;
                case INTENT -> intents++;
                case RETRIED -> retried++;
                case DROPPED -> dropped++;
            }
        }
        Entry entry = new Entry(++sequence, tickIndex, planSequence, (int) position,
            batch.worldId(), batch.domainId(), batch.nodeKey() == null ? "" : batch.nodeKey(), 0L,
            batch.kind(), batch.expectedVersion(), batch.writeSetDigest(), batch.rows(),
            disposition, code, intent);
        CommitRing ring = ring(batch.worldId(), batch.domainId());
        CommitRing.Push push = ring.push(entry);
        if (!push.stored()) {
            refusedFull++;
            dropped++;
            return new Verdict(false, Entry.Disposition.DROPPED, push.code(), sequence,
                (int) position);
        }
        watermarks = Math.max(watermarks, ring.depth());
        return new Verdict(true, disposition, code, sequence, (int) position);
    }

    /** Closes the tick: the rings are walked in a fixed order and what they read is folded. */
    public Replay closeTick() {
        ticks++;
        List<String> keys = new ArrayList<>(rings.keySet());
        keys.sort(String::compareTo);
        List<Entry.Step> steps = new ArrayList<>();
        long hash = OFFSET_BASIS;
        int read = 0;
        for (String key : keys) {
            CommitRing ring = rings.get(key);
            Entry entry;
            while ((entry = ring.pop()) != null) {
                steps.add(entry.step());
                hash = fold(hash, entry.planSequence() + ":" + entry.position() + ":"
                    + entry.writeSetDigest() + ":" + entry.disposition());
                read++;
            }
        }
        Replay replay = new Replay(tickIndex, read, (int) orderViolations, orderViolations == 0L,
            hash, steps, watermarks);
        last = replay;
        return replay;
    }

    /** The replay of the newest closed tick. */
    public Replay last() {
        return last;
    }

    /** The number of steps two runs of the same input differ in; -1 means one of them is missing. */
    public static int compareRuns(Replay left, Replay right) {
        return Replay.compare(left, right);
    }

    private boolean ascending(String worldId, String domainId, long planSequence, long position) {
        String key = key(worldId, domainId);
        Long plan = lastPlanOfRing.get(key);
        Integer lastPosition = lastPositionOfRing.get(key);
        if (plan == null || lastPosition == null) {
            return true;
        }
        if (planSequence > plan) {
            return true;
        }
        return planSequence == plan && position >= lastPosition;
    }

    private CommitRing ring(String worldId, String domainId) {
        return rings.computeIfAbsent(key(worldId, domainId),
            key -> new CommitRing(worldId, domainId, ringCapacity.getAsInt()));
    }

    private static String key(String worldId, String domainId) {
        return worldId + "/" + domainId;
    }

    private static long fold(long hash, String text) {
        long value = hash;
        for (int index = 0; index < text.length(); index++) {
            value ^= text.charAt(index);
            value *= PRIME;
        }
        return value;
    }

    public long sequence() {
        return sequence;
    }

    public long accepted() {
        return accepted;
    }

    public long merged() {
        return merged;
    }

    public long intents() {
        return intents;
    }

    public long dropped() {
        return dropped;
    }

    public long retried() {
        return retried;
    }

    public long orderViolations() {
        return orderViolations;
    }

    public long refusedFull() {
        return refusedFull;
    }

    public long unplanned() {
        return unplanned;
    }

    public long ticks() {
        return ticks;
    }

    public int watermarks() {
        return watermarks;
    }

    public long firstDivergencePosition() {
        return firstDivergencePosition;
    }

    public int ringCount() {
        return rings.size();
    }

    public int ringDepth() {
        int depth = 0;
        for (CommitRing ring : rings.values()) {
            depth += ring.depth();
        }
        return depth;
    }

    public int ringCapacity() {
        return Math.max(1, ringCapacity.getAsInt());
    }

    /** The rings in a fixed order, for a reader that publishes them. */
    public List<CommitRing> rings() {
        List<String> keys = new ArrayList<>(rings.keySet());
        keys.sort(String::compareTo);
        List<CommitRing> ordered = new ArrayList<>(keys.size());
        for (String key : keys) {
            ordered.add(rings.get(key));
        }
        return ordered;
    }

    /** The number of accepted writes still held by the rings. */
    public int held() {
        return ringDepth();
    }

    public void reset() {
        rings.clear();
        lastPlanOfRing.clear();
        lastPositionOfRing.clear();
        sequence = 0L;
        tickIndex = 0L;
        accepted = 0L;
        merged = 0L;
        intents = 0L;
        dropped = 0L;
        retried = 0L;
        orderViolations = 0L;
        refusedFull = 0L;
        unplanned = 0L;
        ticks = 0L;
        watermarks = 0;
        last = null;
        firstDivergencePosition = -1L;
    }
}
