/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand.DemandTap;
import io.izzel.arclight.common.prts.support.PrtsCpuClock;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counts the demand side of the chunk cache: how often a chunk was asked for, how often the ask was
 * answered with a chunk at the wanted status, how long a blocking ask took, and how many of the
 * futures the cache handed out are still outstanding.
 *
 * <p>An outstanding future is the queue depth of this face: the asks that were submitted and have
 * not completed. The counts are per world and per status; the long calls keep the stack of the ask
 * that blocked, which is the caller a stall inside the demand path belongs to. The long-call bound
 * is a declared value, and a stack is taken only for a blocking ask on the installing thread.
 */
public final class ChunkDemandObserver implements PrtsChunkDemand.DemandTap {

    /** One blocking ask or one future that took longer than this is kept. Declared value. */
    public static final long LONG_DEMAND_NANOS = 50_000_000L;
    public static final int LONG_RING = 16;
    public static final int FRAMES = 8;
    private static final int PENDING_CAP = 32;

    /** One ask that took long enough to keep, with the stack it was taken at when it was known. */
    public record LongCall(long wallNanos, long cpuNanos, String worldId, String status,
                           boolean blocking, String thread, String top) {
    }

    /** The demand face of one world. */
    public record WorldRow(String key, long requests, long satisfied, long missed,
                           long blockingRequests, long blockingNanos, long futures,
                           long futuresCompleted, long futuresSatisfied) {
    }

    /** The demand face of one status. */
    public record StatusRow(String key, long requests, long satisfied) {
    }

    /** One per thread and reused: the stack of a blocking ask is only taken when that ask turned
     * out to be long, so the common ask allocates nothing at all. */
    private static final class Pending {

        final long[] startedAt = new long[PENDING_CAP];
        final long[] cpuStartedAt = new long[PENDING_CAP];
        final long[] futureStamp = new long[PENDING_CAP];
        int depth;
        int futureDepth;
        // Pushes this thread refused because the stamp stack was already full. Each one is paid by
        // the matching read at that ask's return, and never by popping an older ask's stamp.
        int futureRefusals;
        long overflow;
    }

    private static final class WorldCell {

        final String key;
        final LongAdder requests = new LongAdder();
        final LongAdder satisfied = new LongAdder();
        final LongAdder missed = new LongAdder();
        final LongAdder blockingRequests = new LongAdder();
        final LongAdder blockingNanos = new LongAdder();
        final LongAdder futures = new LongAdder();
        final LongAdder futuresCompleted = new LongAdder();
        final LongAdder futuresSatisfied = new LongAdder();

        WorldCell(String key) {
            this.key = key;
        }
    }

    private final Map<String, WorldCell> worlds = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> statusRequests = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> statusSatisfied = new ConcurrentHashMap<>();
    private final LongAdder requests = new LongAdder();
    private final LongAdder satisfied = new LongAdder();
    private final LongAdder missed = new LongAdder();
    private final LongAdder blockingRequests = new LongAdder();
    private final LongAdder blockingNanos = new LongAdder();
    private final LongAdder futuresTaken = new LongAdder();
    private final LongAdder futuresCompleted = new LongAdder();
    private final LongAdder futuresSatisfied = new LongAdder();
    private final LongAdder futuresFailed = new LongAdder();
    private final LongAdder futuresInFlight = new LongAdder();
    private final LongAdder futuresNanos = new LongAdder();
    private final ThreadLocal<Pending> pending = ThreadLocal.withInitial(Pending::new);
    private final ConcurrentLinkedDeque<LongCall> longCalls = new ConcurrentLinkedDeque<>();
    private volatile long blockingMaxNanos;
    private volatile long futuresMaxNanos;
    private volatile int futuresPeak;
    private volatile boolean installed;
    private volatile Thread mainThread = Thread.currentThread();
    private volatile String mainThreadName = "-";
    private volatile long startedAtNanos;

    /** The second reader of the same asks, told after this face's own work. Null while none is
     * attached, which is also why a completion that arrives after a detach is dropped rather than
     * booked. */
    private volatile PrtsChunkDemand.DemandTap secondPath;

    public void attach() {
        installed = true;
        mainThread = Thread.currentThread();
        mainThreadName = mainThread.getName();
        startedAtNanos = System.nanoTime();
        PrtsChunkDemand.install(this);
    }

    public void detach() {
        installed = false;
        // The second path is dropped first: a callback that already holds this face still finds no
        // receiver, and no reader of the next run inherits a window of this one.
        secondPath = null;
        PrtsChunkDemand.install(null);
    }

    /** Hands the same asks to a second reader, which is told after this face has counted them. */
    public void attachSecondPath(PrtsChunkDemand.DemandTap second) {
        secondPath = second;
    }

    public void releaseSecondPath() {
        secondPath = null;
    }

    public boolean installed() {
        return installed;
    }

    public String mainThreadName() {
        return mainThreadName;
    }

    public long observedNanos() {
        long started = startedAtNanos;
        return started == 0L ? 0L : System.nanoTime() - started;
    }

    @Override
    public void demandStarted(String worldId, String status, boolean blocking) {
        requests.increment();
        world(worldId).requests.increment();
        status(statusRequests, status).increment();
        // Told once per ask, before the timing stack: a non-blocking ask and an ask whose stack is
        // already full are still asks, and neither one is this face's timing record.
        tellSecondPath(worldId, status, blocking);
        if (!blocking) {
            return;
        }
        blockingRequests.increment();
        world(worldId).blockingRequests.increment();
        Pending stack = pending.get();
        if (stack.depth >= PENDING_CAP) {
            stack.overflow++;
            return;
        }
        stack.startedAt[stack.depth] = System.nanoTime();
        stack.cpuStartedAt[stack.depth] = PrtsCpuClock.now();
        stack.depth++;
    }

    /** The second path is told once per ask and after the count above, in its own catch: a reader
     * that throws costs its own reading and neither this face's counts nor the ask. */
    private void tellSecondPath(String worldId, String status, boolean blocking) {
        DemandTap second = secondPath;
        if (second == null) {
            return;
        }
        try {
            second.demandStarted(worldId, status, blocking);
        } catch (Throwable ignored) {
        }
    }

    /** A synchronous ask that named the chunk it wants: the chunk is read here and kept nowhere. */
    @Override
    public void demandStarted(String worldId, int chunkX, int chunkZ, String status,
                              boolean requireChunk) {
        // Answered by this face rather than by the seam's default, which drops the chunk before the
        // ask reaches anyone: the count is the one the ask without a chunk keeps, and no row of this
        // face is per chunk.
        demandStarted(worldId, status, requireChunk);
    }

    @Override
    public void demandFinished(String worldId, String status, boolean blocking, boolean answered) {
        countDemandFinished(worldId, status, blocking, answered);
        DemandTap second = secondPath;
        if (second == null) {
            return;
        }
        try {
            second.demandFinished(worldId, status, blocking, answered);
        } catch (Throwable ignored) {
        }
    }

    private void countDemandFinished(String worldId, String status, boolean blocking,
                                     boolean answered) {
        if (answered) {
            satisfied.increment();
            world(worldId).satisfied.increment();
            status(statusSatisfied, status).increment();
        } else {
            missed.increment();
            world(worldId).missed.increment();
        }
        if (!blocking) {
            return;
        }
        Pending stack = pending.get();
        if (stack.depth <= 0) {
            return;
        }
        stack.depth--;
        long wall = System.nanoTime() - stack.startedAt[stack.depth];
        blockingNanos.add(wall);
        world(worldId).blockingNanos.add(wall);
        if (wall > blockingMaxNanos) {
            blockingMaxNanos = wall;
        }
        if (wall >= LONG_DEMAND_NANOS) {
            record(new LongCall(wall, PrtsCpuClock.since(stack.cpuStartedAt[stack.depth]), worldId,
                status, true, Thread.currentThread().getName(), topFrames()));
        }
    }

    @Override
    public void futureOpened(String worldId, String status) {
        if (!openFutureStamp()) {
            return;
        }
        DemandTap second = secondPath;
        if (second == null) {
            return;
        }
        try {
            second.futureOpened(worldId, status);
        } catch (Throwable ignored) {
        }
    }

    /** Pushes the stamp of one future-returning ask, once, and answers whether it was pushed with a
     * usable value: the caller of the ask learns of a full stack from this return and from nowhere
     * else, so nothing has to be popped to find out. */
    private boolean openFutureStamp() {
        Pending stack = pending.get();
        if (stack.futureDepth >= PENDING_CAP) {
            // The ask still returns through futureOpenedAt. Counting the refusal here is what keeps
            // that read from spending a stamp that belongs to an ask already on the stack.
            stack.futureRefusals++;
            return false;
        }
        stack.futureStamp[stack.futureDepth] = System.nanoTime();
        stack.futureDepth++;
        return true;
    }

    /** A future-returning ask that named the chunk it wants: the chunk is read here and the stamp
     * is taken once. */
    @Override
    public void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                 boolean requireChunk) {
        // An ask that arrives without a name is still an ask: it is stamped and told on, and the
        // reader that needs a name reads none rather than one made up here.
        openAndForwardDescriptor(worldId, chunkX, chunkZ, status, requireChunk,
            PrtsChunkDemand.NO_TOKEN);
    }

    @Override
    public void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                 boolean requireChunk, long token) {
        openAndForwardDescriptor(worldId, chunkX, chunkZ, status, requireChunk, token);
    }

    private void openAndForwardDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                          boolean requireChunk, long token) {
        if (!openFutureStamp()) {
            return;
        }
        DemandTap second = secondPath;
        if (second == null) {
            return;
        }
        try {
            second.demandDescriptor(worldId, chunkX, chunkZ, status, requireChunk, token);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public long futureOpenedAt() {
        Pending stack = pending.get();
        if (stack.futureRefusals > 0) {
            stack.futureRefusals--;
            return -1L;
        }
        if (stack.futureDepth <= 0) {
            return -1L;
        }
        stack.futureDepth--;
        return stack.futureStamp[stack.futureDepth];
    }

    @Override
    public void futureTaken(String worldId, String status) {
        countFutureTaken(worldId);
    }

    @Override
    public void futureTaken(String worldId, String status, long token) {
        countFutureTaken(worldId);
        DemandTap second = secondPath;
        if (second == null) {
            return;
        }
        try {
            second.futureTaken(worldId, status, token);
        } catch (Throwable ignored) {
        }
    }

    private void countFutureTaken(String worldId) {
        futuresTaken.increment();
        world(worldId).futures.increment();
        futuresInFlight.increment();
        int inFlight = (int) futuresInFlight.sum();
        if (inFlight > futuresPeak) {
            futuresPeak = inFlight;
        }
    }

    @Override
    public void futureCompleted(String worldId, String status, long startedNanos, boolean answered) {
        countFutureCompleted(worldId, status, startedNanos, answered);
    }

    @Override
    public void futureCompleted(String worldId, String status, long startedNanos, boolean answered,
                                long token) {
        countFutureCompleted(worldId, status, startedNanos, answered);
        DemandTap second = secondPath;
        if (second == null) {
            return;
        }
        try {
            second.futureCompleted(worldId, status, startedNanos, answered, token);
        } catch (Throwable ignored) {
        }
    }

    /** The confirmation of one registration: this face keeps no count of it, so all it can do is
     * hand it to the reader that asked for the stage. */
    @Override
    public void registrationConfirmed(String worldId, String status, long token, boolean ok) {
        DemandTap second = secondPath;
        if (second == null) {
            return;
        }
        try {
            second.registrationConfirmed(worldId, status, token, ok);
        } catch (Throwable ignored) {
        }
    }

    private void countFutureCompleted(String worldId, String status, long startedNanos,
                                      boolean answered) {
        futuresCompleted.increment();
        world(worldId).futuresCompleted.increment();
        futuresInFlight.decrement();
        if (answered) {
            futuresSatisfied.increment();
            world(worldId).futuresSatisfied.increment();
        } else {
            futuresFailed.increment();
        }
        if (startedNanos < 0L) {
            return;
        }
        long wall = System.nanoTime() - startedNanos;
        futuresNanos.add(wall);
        if (wall > futuresMaxNanos) {
            futuresMaxNanos = wall;
        }
        if (wall >= LONG_DEMAND_NANOS) {
            record(new LongCall(wall, -1L, worldId, status, false,
                Thread.currentThread().getName(), null));
        }
    }

    private void record(LongCall call) {
        longCalls.addLast(call);
        while (longCalls.size() > LONG_RING) {
            longCalls.pollFirst();
        }
    }

    private static LongAdder status(Map<String, LongAdder> map, String status) {
        return map.computeIfAbsent(status == null ? "-" : status, key -> new LongAdder());
    }

    private WorldCell world(String worldId) {
        return worlds.computeIfAbsent(worldId == null ? "-" : worldId, WorldCell::new);
    }

    /** Taken at the return of a long ask, which is still inside the ask's frame; the frames above
     * this method are the callers the ask belongs to. */
    private static String topFrames() {
        StackTraceElement[] frames = new Throwable().getStackTrace();
        StringBuilder builder = new StringBuilder();
        for (int index = 1; index < frames.length && index <= FRAMES; index++) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(frames[index].getClassName()).append('.')
                .append(frames[index].getMethodName());
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    public long requests() {
        return requests.sum();
    }

    public long satisfied() {
        return satisfied.sum();
    }

    public long missed() {
        return missed.sum();
    }

    public long blockingRequests() {
        return blockingRequests.sum();
    }

    public long blockingNanos() {
        return blockingNanos.sum();
    }

    public long blockingMaxNanos() {
        return blockingMaxNanos;
    }

    public long futuresTaken() {
        return futuresTaken.sum();
    }

    public long futuresCompleted() {
        return futuresCompleted.sum();
    }

    public long futuresSatisfied() {
        return futuresSatisfied.sum();
    }

    public long futuresFailed() {
        return futuresFailed.sum();
    }

    public long futuresInFlight() {
        return futuresInFlight.sum();
    }

    public int futuresPeak() {
        return futuresPeak;
    }

    public long futuresNanos() {
        return futuresNanos.sum();
    }

    public long futuresMaxNanos() {
        return futuresMaxNanos;
    }

    /** @return the per-world rows, busiest first */
    public List<WorldRow> worldRows() {
        List<WorldRow> rows = new ArrayList<>();
        for (WorldCell cell : worlds.values()) {
            rows.add(new WorldRow(cell.key, cell.requests.sum(), cell.satisfied.sum(),
                cell.missed.sum(), cell.blockingRequests.sum(), cell.blockingNanos.sum(),
                cell.futures.sum(), cell.futuresCompleted.sum(), cell.futuresSatisfied.sum()));
        }
        rows.sort(Comparator.comparingLong(WorldRow::requests).reversed());
        return rows;
    }

    /** @return the per-status rows, busiest first */
    public List<StatusRow> statusRows() {
        List<StatusRow> rows = new ArrayList<>();
        for (Map.Entry<String, LongAdder> entry : statusRequests.entrySet()) {
            LongAdder done = statusSatisfied.get(entry.getKey());
            rows.add(new StatusRow(entry.getKey(), entry.getValue().sum(),
                done == null ? 0L : done.sum()));
        }
        rows.sort(Comparator.comparingLong(StatusRow::requests).reversed());
        return rows;
    }

    /** @return the kept long calls, longest first */
    public List<LongCall> longCalls() {
        List<LongCall> calls = new ArrayList<>(longCalls);
        calls.sort(Comparator.comparingLong(LongCall::wallNanos).reversed());
        return calls;
    }

    public synchronized void reset() {
        worlds.clear();
        statusRequests.clear();
        statusSatisfied.clear();
        requests.reset();
        satisfied.reset();
        missed.reset();
        blockingRequests.reset();
        blockingNanos.reset();
        futuresTaken.reset();
        futuresCompleted.reset();
        futuresSatisfied.reset();
        futuresFailed.reset();
        futuresInFlight.reset();
        futuresNanos.reset();
        longCalls.clear();
        blockingMaxNanos = 0L;
        futuresMaxNanos = 0L;
        futuresPeak = 0;
        startedAtNanos = System.nanoTime();
    }
}
