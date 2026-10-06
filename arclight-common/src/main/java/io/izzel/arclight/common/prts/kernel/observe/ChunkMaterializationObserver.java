/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsChunkMaterialization;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * The producer of the chunk row's declared progress signal: a value reaches this ledger only from
 * the seam on the host's own materialization swap, and it is grouped by world and by landing tick.
 *
 * <p>A completion is counted once: a repeat inside its own tick is a duplicate, a repeat in a later
 * tick or under another world is refused rather than moved, and every refusal is counted. Not
 * executable, a readable zero and a readable signal are published apart, so a zero out of an
 * unbound slot never passes as a measurement.
 */
public final class ChunkMaterializationObserver implements PrtsChunkMaterialization.MaterializationTap {

    /** Where one reported completion ended up. */
    public enum Outcome {
        COUNTED,
        DUPLICATE,
        CROSS_TICK,
        CROSS_WORLD
    }

    /** The three states the declared progress signal can be read in. */
    public enum LedgerState {
        NOT_EXECUTABLE,
        PROGRESS_ZERO,
        PROGRESS_SIGNAL
    }

    /** One counted completion, with every marker the producer has to stamp. */
    public record Event(long ticket, String worldId, long chunkPos, String status, int generation,
                        long tick, String source) {
    }

    /** One export of the producer: what was read, how it moved, and the state it is in. */
    public record Reading(boolean bound, String source, long value, long delta, long events,
                          long duplicates, long crossWorld, long crossTick, long nonMonotonic,
                          LedgerState state) {
    }

    /** How many tick groups one world keeps between two exports; anything past it is folded. */
    private static final int TICK_GROUPS = 256;

    private final LongAdder counted = new LongAdder();
    private final LongAdder touched = new LongAdder();
    private final LongAdder duplicates = new LongAdder();
    private final LongAdder crossWorldRefusals = new LongAdder();
    private final LongAdder crossTickRefusals = new LongAdder();
    private final LongAdder nonMonotonicRefusals = new LongAdder();
    private final LongAdder foldedTicks = new LongAdder();
    private final Map<String, LongAdder> byWorld = new ConcurrentHashMap<>();
    private final Map<String, TreeMap<Long, LongAdder>> byWorldTick = new ConcurrentHashMap<>();
    private final List<String> violations = Collections.synchronizedList(new ArrayList<>());

    private volatile boolean bound;
    private volatile long tick;
    private volatile Event lastEvent;
    private volatile long published;
    private long ledgerTick = Long.MIN_VALUE;
    private Map<String, Long> currentTickKeys = new ConcurrentHashMap<>();
    private Map<String, Long> previousTickKeys = new ConcurrentHashMap<>();
    private Map<Long, String> currentTickTickets = new ConcurrentHashMap<>();

    @Override
    public void materialized(long ticket, String worldId, long chunkPos, String status,
                             int generation) {
        accept(ticket, worldId, chunkPos, status, generation, tick);
    }

    /** Records one reported completion against an explicit tick - the seam's, or the self-check's.
     * @return where the completion ended up */
    public Outcome accept(long ticket, String worldId, long chunkPos, String status, int generation,
                          long reportedTick) {
        touched.increment();
        if (worldId == null || status == null) {
            violations.add("a completion arrived without a world or a status");
            return Outcome.CROSS_WORLD;
        }
        String key = worldId + '|' + chunkPos + '|' + status + '|' + generation;
        synchronized (this) {
            if (reportedTick != ledgerTick) {
                // The ended tick's keys are kept, so a repeat in a later tick is still caught.
                previousTickKeys = currentTickKeys;
                currentTickKeys = new ConcurrentHashMap<>();
                currentTickTickets = new ConcurrentHashMap<>();
                ledgerTick = reportedTick;
            }
            if (currentTickKeys.containsKey(key)) {
                duplicates.increment();
                return Outcome.DUPLICATE;
            }
            if (previousTickKeys.containsKey(key)) {
                crossTickRefusals.increment();
                violations.add("the completion " + key + " was reported again at tick "
                    + reportedTick);
                return Outcome.CROSS_TICK;
            }
            String owner = currentTickTickets.putIfAbsent(ticket, worldId);
            if (owner != null && !owner.equals(worldId)) {
                crossWorldRefusals.increment();
                violations.add("ticket " + ticket + " was reported by world " + owner + " and by "
                    + worldId);
                return Outcome.CROSS_WORLD;
            }
            currentTickKeys.put(key, reportedTick);
        }
        counted.increment();
        byWorld.computeIfAbsent(worldId, ignored -> new LongAdder()).increment();
        TreeMap<Long, LongAdder> groups =
            byWorldTick.computeIfAbsent(worldId, ignored -> new TreeMap<>());
        synchronized (groups) {
            if (groups.size() >= TICK_GROUPS && !groups.containsKey(reportedTick)) {
                foldedTicks.increment();
            } else {
                groups.computeIfAbsent(reportedTick, ignored -> new LongAdder()).increment();
            }
        }
        lastEvent = new Event(ticket, worldId, chunkPos, status, generation, reportedTick,
            bound ? PrtsChunkMaterialization.SOURCE : "unbound");
        return Outcome.COUNTED;
    }

    /** Advances the tick the seam stamps its completions with. */
    public void noteTick(long tickIndex) {
        this.tick = tickIndex;
    }

    /** Publishes one value for this producer; a value below the one already published is refused
     * as non-monotonic and kept as a violation, so a producer that loses its place is named.
     * @return whether the value was accepted */
    public boolean settle(long value) {
        if (value < published) {
            nonMonotonicRefusals.increment();
            violations.add("the producer went backwards: " + published + " then " + value);
            return false;
        }
        published = value;
        return true;
    }

    /** Reads the producer for one export: the value, the move since the previous export, and the
     * state; published through {@link #settle(long)}, so a fallen producer keeps the old value. */
    public Reading read() {
        long raw = counted.sum();
        long previous = published;
        long value = settle(raw) ? raw : previous;
        long delta = value - previous;
        LedgerState state;
        if (!bound || value == 0L) {
            state = LedgerState.NOT_EXECUTABLE;
        } else if (delta > 0L) {
            state = LedgerState.PROGRESS_SIGNAL;
        } else {
            state = LedgerState.PROGRESS_ZERO;
        }
        return new Reading(bound, bound ? PrtsChunkMaterialization.SOURCE : "unbound", value, delta,
            touched.sum(), duplicates.sum(), crossWorldRefusals.sum(), crossTickRefusals.sum(),
            nonMonotonicRefusals.sum(), state);
    }

    /** @return the cumulative count of materialized chunks, the reading the wait point row binds */
    public long value() {
        return counted.sum();
    }

    public boolean bound() {
        return bound;
    }

    public synchronized void attach() {
        bound = true;
        ledgerTick = Long.MIN_VALUE;
        PrtsChunkMaterialization.install(this);
    }

    public synchronized void detach() {
        bound = false;
        if (PrtsChunkMaterialization.watcher() == this) {
            PrtsChunkMaterialization.install(null);
        }
    }

    /** @return the world ids that materialized a chunk, in name order */
    public List<String> worlds() {
        List<String> ids = new ArrayList<>(byWorld.keySet());
        Collections.sort(ids);
        return ids;
    }

    public long worldTotal(String worldId) {
        LongAdder cell = byWorld.get(worldId);
        return cell == null ? 0L : cell.sum();
    }

    /** @return the per tick groups of one world, in tick order */
    public Map<Long, Long> tickGroups(String worldId) {
        TreeMap<Long, LongAdder> groups = byWorldTick.get(worldId);
        Map<Long, Long> snapshot = new LinkedHashMap<>();
        if (groups == null) {
            return snapshot;
        }
        synchronized (groups) {
            for (Map.Entry<Long, LongAdder> entry : groups.entrySet()) {
                snapshot.put(entry.getKey(), entry.getValue().sum());
            }
        }
        return snapshot;
    }

    /** @return how many tick groups were folded because a world held more than the ring keeps */
    public long foldedTicks() {
        return foldedTicks.sum();
    }

    public Event lastEvent() {
        return lastEvent;
    }

    public long duplicates() {
        return duplicates.sum();
    }

    public long crossWorld() {
        return crossWorldRefusals.sum();
    }

    public long crossTick() {
        return crossTickRefusals.sum();
    }

    public long nonMonotonic() {
        return nonMonotonicRefusals.sum();
    }

    /** @return the highest value this producer published, which a later read may never fall below */
    public long lastPublished() {
        return published;
    }

    /** @return what the producer refused to count, oldest first; empty when it refused nothing */
    public List<String> violations() {
        synchronized (violations) {
            return List.copyOf(violations);
        }
    }

    public synchronized void reset() {
        counted.reset();
        touched.reset();
        duplicates.reset();
        crossWorldRefusals.reset();
        crossTickRefusals.reset();
        nonMonotonicRefusals.reset();
        foldedTicks.reset();
        byWorld.clear();
        byWorldTick.clear();
        violations.clear();
        lastEvent = null;
        // The published mark goes with the counters: an old mark would report the next read as a fall.
        published = 0L;
        ledgerTick = Long.MIN_VALUE;
        previousTickKeys = new ConcurrentHashMap<>();
        currentTickKeys = new ConcurrentHashMap<>();
        currentTickTickets = new ConcurrentHashMap<>();
    }
}
