/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.diff.DomainHash;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * Folds the arrival face of one tick, per world: the asks the chunk cache was given, the answers,
 * the asks that waited, the futures handed out and completed, the ones still outstanding and the
 * writes the block write path was handed. This is the per tick resolution the interval readout of
 * the demand and flow observers cannot give, so two runs of one scenario can be asked which ticks
 * they were handed the same arrival on.
 *
 * <p><b>Off unless the tick digest is armed.</b> The window is the one {@link TickDigestObserver}
 * already reads - no key of its own - and the rows are folded from the tick the digest closes, so
 * both faces carry the same tick and the same world set.
 *
 * <p><b>The counts are sampled, not counted twice.</b> The demand side is the difference of the
 * cumulative per world counters the demand observer already keeps, so arming this window adds no
 * work to a chunk ask. Only the block writes are counted here, because no counter in this tree
 * buckets a write by world: one atomic add per attempt, on the thread about to write anyway.
 */
public final class ArrivalDigestObserver
    implements WorldWriteGuard.ArrivalWriteTap, TickDigestObserver.ArrivalTickTap {

    /** The row identity of the arrival face; the domain the rows are folded under. */
    public static final String PROBE = "arrival";

    /** The region every arrival row carries: the face is per world, not per region. */
    private static final String REGION = "arrival";

    /** How many ticks between two reads of the level to world identity. */
    private static final int LEVEL_REFRESH_TICKS = 20;

    /** The demand counters a row carries a delta of, in the order they are folded, plus the
     * outstanding futures, which are a level and not a difference. */
    private static final int DEMAND_SLOTS = 6;
    private static final int DEMAND_SAMPLE = DEMAND_SLOTS + 1;

    /** One folded row: the tick, the world, the eight arrival values with their exact bits, and the
     * value the fold produced. */
    public record Row(long tick, String world, long requests, long satisfied, long missed,
                      long blocking, long futuresTaken, long futuresCompleted, long inFlight,
                      long writes, long value, String algorithmId, long headerDigest,
                      long rowDigest) {
    }

    /** One level the write path can be addressed by, with the world id its rows use. */
    private record WorldRef(Object level, String world) {
    }

    private final int declaredWindow;
    private final Map<Long, List<Row>> window = new LinkedHashMap<>();
    private final TreeSet<String> worldSet = new TreeSet<>();
    private final Map<String, long[]> demandSeen = new HashMap<>();
    private final Map<String, long[]> writeSeen = new HashMap<>();
    private final Map<String, LongAdder> writes = new ConcurrentHashMap<>();
    private final LongAdder writesTotal = new LongAdder();
    private final LongAdder unplacedWrites = new LongAdder();
    private volatile WorldRef[] refs = new WorldRef[0];
    private volatile MinecraftServer source;
    private volatile ChunkDemandObserver demand;
    private long lastLevelTick = Long.MIN_VALUE;
    private long ticks;
    private long rowsFolded;
    private long firstTick = -1L;
    private long lastTick = -1L;
    private boolean attached;

    public ArrivalDigestObserver() {
        this(TickDigestObserver.windowTicksDeclared());
    }

    ArrivalDigestObserver(int window) {
        this.declaredWindow = Math.max(0, Math.min(TickDigestObserver.WINDOW_MAX, window));
    }

    /** Whether this process declared a window at all; the digest declares the same one. */
    public boolean armed() {
        return declaredWindow > 0;
    }

    public int windowTicks() {
        return declaredWindow;
    }

    /** Remembers the server the level identity of a write is read from. */
    public void source(MinecraftServer server) {
        this.source = server;
    }

    /** Remembers the demand side the counts are sampled from. */
    public void demand(ChunkDemandObserver observer) {
        this.demand = observer;
    }

    /** Arms the window for the span the digest is attached for. */
    public synchronized void attach() {
        attached = armed();
        if (attached) {
            refreshLevels(true);
        }
    }

    /** Disarms the window and forgets the level identity table. */
    public synchronized void detach() {
        attached = false;
        refs = new WorldRef[0];
        lastLevelTick = Long.MIN_VALUE;
    }

    /** One block write attempt on the short path, addressed by the level it was made against. */
    @Override
    public void writeAtLevel(Object levelRef) {
        WorldRef[] table = refs;
        for (WorldRef ref : table) {
            if (ref.level() == levelRef) {
                count(ref.world());
                return;
            }
        }
        unplacedWrites.increment();
    }

    /** One block write attempt on the judged path, which already carries the world id. */
    @Override
    public void writeAtWorld(String worldId) {
        count(worldId == null ? "-" : worldId);
    }

    private void count(String world) {
        writesTotal.increment();
        writes.computeIfAbsent(world, key -> new LongAdder()).increment();
    }

    /**
     * Closes one arrival tick: folds one row per world and keeps the newest
     * {@link #windowTicks()} ticks. Called by the digest with the tick it just closed and the worlds
     * it folded, so the two faces can never disagree about either.
     */
    @Override
    public void arrivalTick(long tick, List<String> worlds) {
        if (!attached || worlds.isEmpty()) {
            return;
        }
        if ((tick - lastLevelTick) >= LEVEL_REFRESH_TICKS || refs.length == 0) {
            lastLevelTick = tick;
            refreshLevels(false);
        }
        List<Row> folded = new ArrayList<>(worlds.size());
        for (String world : worlds) {
            folded.add(fold(tick, world));
        }
        window.put(tick, folded);
        while (window.size() > declaredWindow) {
            Iterator<Long> oldest = window.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        worldSet.addAll(worlds);
        ticks++;
        rowsFolded += folded.size();
        if (firstTick < 0L) {
            firstTick = tick;
        }
        lastTick = tick;
    }

    /** The eight values of one world on one tick: the sample less the previous sample, except the
     * outstanding futures, which are a level and not a difference. */
    private Row fold(long tick, String world) {
        long[] seen = demandSeen.computeIfAbsent(world, key -> new long[DEMAND_SLOTS]);
        long[] now = demandSample(world);
        for (int index = 0; index < DEMAND_SLOTS; index++) {
            long previous = seen[index];
            seen[index] = now[index];
            now[index] -= previous;
        }
        long requests = now[0];
        long satisfied = now[1];
        long missed = now[2];
        long blocking = now[3];
        long futuresTaken = now[4];
        long futuresCompleted = now[5];
        long inFlight = now[DEMAND_SLOTS];
        long writesNow = writeSample(world);
        long[] writeBefore = writeSeen.computeIfAbsent(world, key -> new long[1]);
        long writesHere = writesNow - writeBefore[0];
        writeBefore[0] = writesNow;
        StateHasher.Slice slice = new StateHasher.Slice(world, REGION, 0L, requests, requests,
            satisfied, missed, blocking, futuresTaken, futuresCompleted, inFlight, writesHere, 0L,
            tick, 0L);
        DomainHash hash = StateHasher.hash(PROBE, tick, List.of(slice), HashWhitelist.bitexact());
        return new Row(tick, world, requests, satisfied, missed, blocking, futuresTaken,
            futuresCompleted, inFlight, writesHere, hash.value(), hash.algorithmId(),
            hash.segmentHeaderDigest(), hash.rows().rowDigests()[0]);
    }

    /** The cumulative demand counters of one world, as the demand observer reads them, and the
     * futures it handed out less the ones that completed: the queue depth of this face. */
    private long[] demandSample(String world) {
        ChunkDemandObserver observer = demand;
        if (observer != null) {
            for (ChunkDemandObserver.WorldRow row : observer.worldRows()) {
                if (row.key().equals(world)) {
                    return new long[]{row.requests(), row.satisfied(), row.missed(),
                        row.blockingRequests(), row.futures(), row.futuresCompleted(),
                        row.futures() - row.futuresCompleted()};
                }
            }
        }
        return new long[DEMAND_SAMPLE];
    }

    private long writeSample(String world) {
        LongAdder cell = writes.get(world);
        return cell == null ? 0L : cell.sum();
    }

    /** Places every level on the world id its writes are counted under. */
    private void refreshLevels(boolean force) {
        MinecraftServer server = source;
        if (server == null) {
            return;
        }
        List<WorldRef> placed = new ArrayList<>();
        try {
            for (ServerLevel level : server.getAllLevels()) {
                placed.add(new WorldRef(level, level.dimension().location().toString()));
            }
        } catch (Throwable failure) {
            return;
        }
        refs = placed.toArray(new WorldRef[0]);
    }

    /** The newest {@link #windowTicks()} ticks, oldest first. */
    public List<Row> rows() {
        List<Row> all = new ArrayList<>();
        for (List<Row> tick : window.values()) {
            all.addAll(tick);
        }
        return all;
    }

    public List<String> worlds() {
        return List.copyOf(worldSet);
    }

    public long ticks() {
        return window.size();
    }

    public long rowsFolded() {
        return rowsFolded;
    }

    public long firstTick() {
        return firstTick;
    }

    public long lastTick() {
        return lastTick;
    }

    public int placedLevels() {
        return refs.length;
    }

    public long writesTotal() {
        return writesTotal.sum();
    }

    public long unplacedWrites() {
        return unplacedWrites.sum();
    }

    /** The writes one world was handed, as the rows count them. */
    public long writes(String world) {
        LongAdder cell = writes.get(world);
        return cell == null ? 0L : cell.sum();
    }

    /** Clears the window and every counter but keeps the server and the demand side. */
    public synchronized void reset() {
        window.clear();
        worldSet.clear();
        demandSeen.clear();
        writeSeen.clear();
        writes.clear();
        writesTotal.reset();
        unplacedWrites.reset();
        ticks = 0L;
        rowsFolded = 0L;
        firstTick = -1L;
        lastTick = -1L;
        lastLevelTick = Long.MIN_VALUE;
    }
}
