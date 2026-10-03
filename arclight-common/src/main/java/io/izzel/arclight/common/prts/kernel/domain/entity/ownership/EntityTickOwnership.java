/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The ownership hook fixture: it claims the rows of one entity tick before the host runs them and
 * decides at the host entry whether a row keeps its original tick or is skipped under a settled
 * ownership token. Every row it cannot own is handed back to the host before the host continues,
 * so a row is either skipped or run, never both and never twice.
 *
 * <p><b>Off unless declared.</b> The only way to arm it is the JVM property
 * {@code arclight.prts.entityOwnership}; no configuration file, no reload and no command reaches
 * it, so a shipped server runs without it. It changes no default, no reading name, no rejection
 * code and no configuration key, and it never writes world state: a skipped row simply does not
 * run this tick. Its counters are observation only, and the host entry counts the two decisions
 * itself, so no number here is derived from what the pipeline committed.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.DomainReadings;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.dispatch.FaultInjection;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.ArmorStandTick;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickState;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * The lifecycle of one ownership fixture tick: the plan point issues a token per eligible row and
 * dispatches it, the host entry consumes the token of a row whose answer is settled and ready or
 * withdraws it, and the tick closes by recycling every token that never reached the host entry.
 */
public final class EntityTickOwnership {

    private static final boolean LIVE = Boolean.getBoolean("arclight.prts.entityOwnership");
    private static final int MAX_ROWS = Math.max(1,
        Math.min(65_536, Integer.getInteger("arclight.prts.entityOwnershipMax", 1_024)));
    private static final int TRACE_START = 1_024;
    private static final int EPOCH_MAP_LIMIT = 65_536;
    private static final Logger LOGGER = LogManager.getLogger("PRTS");

    /** Rows the plan point issued a token for. */
    static final LongAdder ISSUED = new LongAdder();
    /** Rows the host entry reached, claimed or not. */
    static final LongAdder CANDIDATES = new LongAdder();
    /** Claimed rows whose ownership was not exercised, so the host ran them. */
    static final LongAdder ELIGIBLE = new LongAdder();
    /** Rows whose original tick the host did not run. */
    static final LongAdder HOST_SKIPPED = new LongAdder();
    /** Rows that ran the original tick without an ownership claim. */
    static final LongAdder HOST_EXECUTED = new LongAdder();
    /** Tokens withdrawn at the host entry, the left end of the fallback pair. */
    static final LongAdder FALLBACK = new LongAdder();
    /** Issued tokens that never reached a host entry; their lease was recycled. */
    static final LongAdder NOT_ENTERED = new LongAdder();
    /** Every withdrawn token: those withdrawn at the host entry and those recycled at the close. */
    static final LongAdder REVOKED = new LongAdder();
    /** A second host entry for one row of one tick; must stay zero. */
    static final LongAdder OWNER_CONFLICT = new LongAdder();
    /** Host entries whose revalidation of the token failed. */
    static final LongAdder LIFECYCLE_REJECTED = new LongAdder();
    /** Tokens still held when their tick closed; must stay zero. */
    static final LongAdder TOKEN_LEAK = new LongAdder();
    /** Worker answers that arrived after their row was decided or their tick closed. */
    static final LongAdder LATE_DROPPED = new LongAdder();
    /** Ticks on which the closure or the partition equation did not hold; must stay zero. */
    static final LongAdder INVARIANT_VIOLATIONS = new LongAdder();
    /** Rows a worker settled. */
    static final LongAdder SETTLED_ROWS = new LongAdder();
    /** Answers the host entry committed onto their rows, so their original tick did not run. */
    static final LongAdder APPLIED = new LongAdder();
    /** Nanoseconds the plan point spent freezing the rows of one tick. */
    static final LongAdder PLAN_NANOS = new LongAdder();
    /** Why the plan point refused a row, by reason. */
    static final LongAdder[] REASON_COUNTS = adders(OwnershipEligibility.REASONS);

    private static volatile OwnershipLease current;
    private static OwnershipLease previous;
    private static long previousLate;
    private static long nextToken = 1L;
    private static long nextEntityEpoch = 1L;

    private static int[] traceIdsRead = new int[TRACE_START];
    private static int[] traceIdsWrite = new int[TRACE_START];
    private static ServerLevel[] traceLevelsRead = new ServerLevel[TRACE_START];
    private static ServerLevel[] traceLevelsWrite = new ServerLevel[TRACE_START];
    private static int traceReadRows;
    private static int traceWriteRows;

    private static final Int2LongOpenHashMap ENTITY_EPOCH = new Int2LongOpenHashMap();
    private static final Int2IntOpenHashMap ENTITY_EPOCH_TICK = new Int2IntOpenHashMap();
    private static ServerLevel epochLevel;
    private static long epochValue;
    private static long epochTick = Long.MIN_VALUE;

    private static volatile ThreadPoolExecutor pool;

    private EntityTickOwnership() {
    }

    /** Whether this process declares the fixture; false means every entry point returns at once. */
    public static boolean live() {
        return LIVE;
    }

    /** The plan point of one tick: freeze the eligible rows, issue their tokens, dispatch them. */
    public static void onServerTickPre(MinecraftServer server) {
        if (!LIVE) {
            return;
        }
        swapTrace();
        current = null;
        // The generation of a world is read once per plan point and once per host entry; the cache
        // is dropped here so a world replaced between two ticks cannot keep the old number.
        epochTick = Long.MIN_VALUE;
        long tick = server.getTickCount();
        long startedAt = System.nanoTime();
        OwnershipLease lease = new OwnershipLease(tick, MAX_ROWS, nextToken);
        for (int row = 0; row < traceReadRows && lease.rows() < MAX_ROWS; row++) {
            ServerLevel level = traceLevelsRead[row];
            if (level == null) {
                continue;
            }
            Entity entity = level.getEntity(traceIdsRead[row]);
            if (entity == null) {
                continue;
            }
            int reason = OwnershipEligibility.reasonOf(level, entity);
            if (reason != OwnershipEligibility.WIDENED) {
                REASON_COUNTS[reason].increment();
                continue;
            }
            if (!(entity instanceof ArmorStand stand)) {
                // The predicate admits no other class, so this is a row the plan point must not own.
                continue;
            }
            TickState state = new TickState();
            ArmorStandTick.capture(stand, state);
            int entityId = entity.getId();
            long worldEpoch = worldEpochOf(level);
            int index = lease.issue(entityId, worldEpoch, entityEpochOf(entityId, tick),
                entity.tickCount + 1, OwnershipEligibility.fingerprintOf(entity), state);
            if (index >= 0) {
                ISSUED.increment();
            }
        }
        nextToken = lease.lastToken();
        lease.publish(pool(), Math.max(1, workerCount() * 4));
        PLAN_NANOS.add(System.nanoTime() - startedAt);
        current = lease;
    }

    /** The host entry of one row: {@code true} means the original tick of that row does not run. */
    public static boolean onEntityTickPre(Entity entity) {
        if (!LIVE) {
            return false;
        }
        Level level = entity.level();
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        noteTrace(serverLevel, entity.getId());
        if (!(entity instanceof ArmorStand stand)) {
            // The entry of a row no model covers is still an entry: it is counted here, at the host
            // entry, so the partition of this fixture spans every row the host walked past.
            CANDIDATES.increment();
            HOST_EXECUTED.increment();
            return false;
        }
        int index = decideRow(entity.getId(), worldEpochOf(serverLevel), entity.tickCount,
            OwnershipEligibility.fingerprintOf(entity), FaultInjection.ownershipEpochBreak(),
            entity.xo, entity.yo, entity.zo);
        OwnershipLease lease = current;
        if (index < 0 || lease == null) {
            return false;
        }
        // The commit segment of the row: the host applies the settled answer on the tick thread and
        // then skips the original tick. No worker ever touches the entity.
        ArmorStandTick.apply(stand, lease.answer(index));
        APPLIED.increment();
        return true;
    }

    /** The decision of one host entry, on the values the entry read; no world access, no wait.
     * A non-negative return is the index of the answer the caller must commit and then skip. */
    static int decideRow(int entityId, long worldEpoch, int tickCount, byte liveFingerprint,
        boolean epochBreak, double xo, double yo, double zo) {
        OwnershipLease lease = current;
        if (lease == null) {
            CANDIDATES.increment();
            HOST_EXECUTED.increment();
            return -1;
        }
        int index = lease.indexOf(entityId);
        if (index < 0) {
            CANDIDATES.increment();
            HOST_EXECUTED.increment();
            return -1;
        }
        if (lease.looked(index)) {
            // One row of one tick must have at most one owner. A second entry is a conflict: the
            // row goes back to the host and its token is withdrawn, so it cannot stay skipped. The
            // row was already counted as a candidate, and a token that was already withdrawn is
            // not withdrawn twice.
            OWNER_CONFLICT.increment();
            if (lease.state(index) == OwnershipLease.CONSUMED) {
                HOST_SKIPPED.decrement();
                withdraw(lease, index);
            } else {
                lease.revoke(index);
            }
            return -1;
        }
        CANDIDATES.increment();
        lease.markLooked(index);
        boolean valid = !epochBreak
            && lease.worldEpoch(index) == worldEpoch
            && lease.hostTickVersion(index) == tickCount
            && lease.fingerprint(index) == liveFingerprint
            && lease.positionHolds(index, xo, yo, zo);
        if (!valid) {
            LIFECYCLE_REJECTED.increment();
            withdraw(lease, index);
            return -1;
        }
        if (lease.state(index) != OwnershipLease.SETTLED) {
            // No answer before the host needs the row: the token is withdrawn right here, in front
            // of the host, and the row runs its original tick exactly once.
            withdraw(lease, index);
            return -1;
        }
        lease.consume(index);
        HOST_SKIPPED.increment();
        return index;
    }

    /** Closes the tick: recycle every lease that never reached the host, then check the accounts. */
    public static void onServerTickPost() {
        if (!LIVE) {
            return;
        }
        closeLease();
    }

    private static void closeLease() {
        OwnershipLease lease = current;
        current = null;
        if (lease != null) {
            for (int index = 0; index < lease.rows(); index++) {
                if (!lease.looked(index)) {
                    NOT_ENTERED.increment();
                    REVOKED.increment();
                    lease.recycle(index);
                }
            }
            lease.close();
            TOKEN_LEAK.add(lease.unresolved());
            SETTLED_ROWS.add(lease.settledRows());
        }
        if (previous != null) {
            long now = previous.lateDroppedRows();
            LATE_DROPPED.add(now - previousLate);
            previousLate = now;
        }
        previous = lease;
        checkInvariants();
    }

    /** One evidence line with the counters of the fixture and the two accounting equations. */
    public static String evidenceLine() {
        long issued = ISSUED.sum();
        long skipped = HOST_SKIPPED.sum();
        long eligible = ELIGIBLE.sum();
        long executed = HOST_EXECUTED.sum();
        long candidates = CANDIDATES.sum();
        long notEntered = NOT_ENTERED.sum();
        long revoked = REVOKED.sum();
        return "[PRTS] entity-ownership: issued=" + issued
            + " owned=" + (issued - revoked)
            + " candidates=" + candidates
            + " eligible=" + eligible
            + " host_skipped=" + skipped
            + " host_executed=" + executed
            + " fallback=" + FALLBACK.sum()
            + " not_entered=" + notEntered
            + " revoked=" + revoked
            + " owner_conflict=" + OWNER_CONFLICT.sum()
            + " lifecycle_rejected=" + LIFECYCLE_REJECTED.sum()
            + " token_leak=" + TOKEN_LEAK.sum()
            + " late_dropped=" + LATE_DROPPED.sum()
            + " settled=" + SETTLED_ROWS.sum()
            + " coverage=" + format(coverage())
            + " closure=" + (closureOk() ? "ok" : "broken")
            + " invariants=" + (INVARIANT_VIOLATIONS.sum() == 0L ? "ok" : "broken")
            + " live=" + (LIVE ? 1 : 0);
    }

    /** One evidence line for the whole-tick model: what it answered, and why it refused a row. */
    public static String replicaLine() {
        StringBuilder builder = new StringBuilder("[PRTS] entity-replica:");
        builder.append(" applied=").append(APPLIED.sum());
        builder.append(" settled=").append(SETTLED_ROWS.sum());
        builder.append(" compute_rows=").append(ArmorStandTick.computeRows());
        builder.append(" compute_ns_per_row=").append(String.format(Locale.ROOT, "%.1f",
            ArmorStandTick.computeRows() == 0L ? 0.0
                : (double) ArmorStandTick.computeNanos() / (double) ArmorStandTick.computeRows()));
        LongAdder[] refusals = ArmorStandTick.refusalCounts();
        builder.append(" refused_physics=").append(refusals[ArmorStandTick.PHYSICS].sum());
        builder.append(" refused_state=").append(refusals[ArmorStandTick.STATE].sum());
        builder.append(" refused_equipment=").append(refusals[ArmorStandTick.EQUIPMENT].sum());
        builder.append(" refused_effects=").append(refusals[ArmorStandTick.EFFECTS].sum());
        builder.append(" refused_combat=").append(refusals[ArmorStandTick.COMBAT].sum());
        builder.append(" refused_portal=").append(refusals[ArmorStandTick.PORTAL].sum());
        builder.append(" refused_fluid=").append(refusals[ArmorStandTick.FLUID].sum());
        return builder.toString();
    }

    /** Contributes the observation fields of the fixture; every one of them reads zero when off. */
    public static void readings(DomainReadings sink) {
        long issued = ISSUED.sum();
        long revoked = REVOKED.sum();
        sink.add("entity.candidates", CANDIDATES.sum());
        sink.add("entity.eligible", ELIGIBLE.sum());
        sink.add("entity.owned", issued - revoked);
        sink.add("entity.host_skipped", HOST_SKIPPED.sum());
        sink.add("entity.host_executed", HOST_EXECUTED.sum());
        sink.add("entity.fallback", FALLBACK.sum());
        sink.add("entity.owner_conflict", OWNER_CONFLICT.sum());
        sink.add("entity.lifecycle_rejected", LIFECYCLE_REJECTED.sum());
        sink.add("entity.coverage", coverage());
        sink.add("entity.issued", issued);
        sink.add("entity.not_entered", NOT_ENTERED.sum());
        sink.add("entity.revoked", revoked);
        sink.add("entity.token_leak", TOKEN_LEAK.sum());
        sink.add("entity.late_dropped", LATE_DROPPED.sum());
        sink.add("entity.settled", SETTLED_ROWS.sum());
        sink.add("entity.invariant_violations", INVARIANT_VIOLATIONS.sum());
    }

    /** Clears every counter and forgets the trace; the readout reset and the tests use it. */
    public static void reset() {
        ISSUED.reset();
        CANDIDATES.reset();
        ELIGIBLE.reset();
        HOST_SKIPPED.reset();
        HOST_EXECUTED.reset();
        FALLBACK.reset();
        NOT_ENTERED.reset();
        REVOKED.reset();
        OWNER_CONFLICT.reset();
        LIFECYCLE_REJECTED.reset();
        TOKEN_LEAK.reset();
        LATE_DROPPED.reset();
        INVARIANT_VIOLATIONS.reset();
        SETTLED_ROWS.reset();
        PLAN_NANOS.reset();
        for (LongAdder counter : REASON_COUNTS) {
            counter.reset();
        }
        APPLIED.reset();
        ArmorStandTick.reset();
        current = null;
        previous = null;
        previousLate = 0L;
        traceReadRows = 0;
        traceWriteRows = 0;
        ENTITY_EPOCH.clear();
        ENTITY_EPOCH_TICK.clear();
        epochLevel = null;
        epochTick = Long.MIN_VALUE;
    }

    /** Stops the pool; declared so a server that stops does not leave worker threads behind. */
    public static void shutdown() {
        reset();
        synchronized (EntityTickOwnership.class) {
            ThreadPoolExecutor open = pool;
            pool = null;
            if (open != null) {
                open.shutdownNow();
            }
        }
    }

    /** Whether the two accounting equations hold on the live counters. */
    static boolean closureOk() {
        long issued = ISSUED.sum();
        long skipped = HOST_SKIPPED.sum();
        long eligible = ELIGIBLE.sum();
        long revoked = REVOKED.sum();
        long notEntered = NOT_ENTERED.sum();
        return issued == skipped + eligible + notEntered && revoked == eligible + notEntered;
    }

    /**
     * The partition of every row the host entry saw, and the reason a row that never entered is
     * not part of it: a row that never reached the host entry has no partition to belong to.
     */
    static boolean partitionOk() {
        return CANDIDATES.sum() == ELIGIBLE.sum() + HOST_EXECUTED.sum() + HOST_SKIPPED.sum();
    }

    /** Rows actually taken over, over the rows the host entry saw. */
    static double coverage() {
        long candidates = CANDIDATES.sum();
        return candidates == 0L ? 0.0 : (double) HOST_SKIPPED.sum() / (double) candidates;
    }

    static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    private static void withdraw(OwnershipLease lease, int index) {
        lease.revoke(index);
        ELIGIBLE.increment();
        FALLBACK.increment();
        REVOKED.increment();
    }

    private static void checkInvariants() {
        boolean holds = closureOk() && partitionOk() && TOKEN_LEAK.sum() == 0L;
        if (!holds && INVARIANT_VIOLATIONS.sum() == 0L) {
            LOGGER.warn("[PRTS] entity-ownership: the ownership accounts do not close: {}",
                evidenceLine());
        }
        if (!holds) {
            INVARIANT_VIOLATIONS.increment();
        }
    }

    private static long entityEpochOf(int entityId, long tick) {
        if (ENTITY_EPOCH_TICK.get(entityId) == (int) tick - 1) {
            return ENTITY_EPOCH.get(entityId);
        }
        if (ENTITY_EPOCH.size() >= EPOCH_MAP_LIMIT) {
            ENTITY_EPOCH.clear();
            ENTITY_EPOCH_TICK.clear();
        }
        long epoch = nextEntityEpoch++;
        ENTITY_EPOCH.put(entityId, epoch);
        ENTITY_EPOCH_TICK.put(entityId, (int) tick);
        return epoch;
    }

    /** The generation the world of this row is on; the write guard tracks it for the same worlds. */
    private static long worldEpochOf(ServerLevel level) {
        long tick = current == null ? epochTick : current.tickIndex();
        if (level == epochLevel && tick == epochTick) {
            return epochValue;
        }
        epochLevel = level;
        epochTick = tick;
        epochValue = KernelModule.instance().guard().worldEpochs()
            .epochOf(level.dimension().location().toString());
        return epochValue;
    }

    private static void swapTrace() {
        int[] ids = traceIdsRead;
        traceIdsRead = traceIdsWrite;
        traceIdsWrite = ids;
        ServerLevel[] levels = traceLevelsRead;
        traceLevelsRead = traceLevelsWrite;
        traceLevelsWrite = levels;
        traceReadRows = traceWriteRows;
        traceWriteRows = 0;
    }

    private static void noteTrace(ServerLevel level, int entityId) {
        if (traceWriteRows == traceIdsWrite.length) {
            int grown = traceIdsWrite.length * 2;
            traceIdsWrite = java.util.Arrays.copyOf(traceIdsWrite, grown);
            traceLevelsWrite = java.util.Arrays.copyOf(traceLevelsWrite, grown);
        }
        traceIdsWrite[traceWriteRows] = entityId;
        traceLevelsWrite[traceWriteRows] = level;
        traceWriteRows++;
    }

    private static ThreadPoolExecutor pool() {
        ThreadPoolExecutor open = pool;
        if (open != null) {
            return open;
        }
        synchronized (EntityTickOwnership.class) {
            if (pool == null) {
                int workers = workerCount();
                ThreadPoolExecutor created = new ThreadPoolExecutor(workers, workers, 30L,
                    TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), new OwnershipThreads(),
                    new ThreadPoolExecutor.AbortPolicy());
                created.allowCoreThreadTimeOut(true);
                pool = created;
            }
            return pool;
        }
    }

    private static int workerCount() {
        return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() - 1));
    }

    private static LongAdder[] adders(int count) {
        LongAdder[] adders = new LongAdder[count];
        for (int index = 0; index < count; index++) {
            adders[index] = new LongAdder();
        }
        return adders;
    }

    /** Named daemon workers of the fixture: a process that stops does not wait for them. */
    private static final class OwnershipThreads implements ThreadFactory {

        private final AtomicInteger seq = new AtomicInteger();

        @Override
        public Thread newThread(Runnable body) {
            Thread thread = new Thread(body, "prts-owner-" + seq.getAndIncrement());
            thread.setDaemon(true);
            return thread;
        }
    }

    /** The plan-point helpers the tests drive without a world. */
    static OwnershipLease beginLease(long tick, int capacity) {
        return new OwnershipLease(tick, capacity, nextToken);
    }

    static void install(OwnershipLease lease) {
        ISSUED.add(lease.rows());
        current = lease;
    }

    static OwnershipLease installed() {
        return current;
    }

    static void closeInstalled() {
        closeLease();
    }
}
