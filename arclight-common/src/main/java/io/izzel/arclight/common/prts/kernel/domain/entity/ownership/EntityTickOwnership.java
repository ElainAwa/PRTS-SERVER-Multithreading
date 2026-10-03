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
 *
 * <p>The entry returns its decision to the platform binder, so the binder cancels the original tick
 * of exactly the rows this class counted as skipped. When the observation face is declared, a
 * separate counter of the original tick body is compared with the entry row by row, and one
 * timeline record per tick carries the order of the plan point, the entries and the close.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.DomainReadings;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.dispatch.FaultInjection;
import io.izzel.arclight.common.prts.support.PrtsHostTickCalls;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.ArmorStandTick;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickModels;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickState;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.WholeTickModel;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.IntUnaryOperator;

/**
 * The lifecycle of one ownership fixture tick: the plan point issues a token per eligible row and
 * dispatches it, the host entry consumes the token of a row whose answer is settled and ready or
 * withdraws it, and the tick closes by recycling every token that never reached the host entry.
 */
public final class EntityTickOwnership {

    /** The original tick of this row runs; the platform binder must not cancel it. */
    public static final int RUN_HOST_TICK = 0;
    /** The original tick of this row is skipped; the platform binder cancels it. */
    public static final int SKIP_HOST_TICK = 1;

    private static final boolean LIVE = Boolean.getBoolean("arclight.prts.entityOwnership");
    private static final int MAX_ROWS = Math.max(1,
        Math.min(65_536, Integer.getInteger("arclight.prts.entityOwnershipMax", 1_024)));
    /** Whether the observation face is declared, so the independent counter is read and written. */
    private static final boolean PROBED = PrtsHostTickCalls.ARMED;
    private static final int TRACE_START = 1_024;
    private static final int TIMELINE_TICKS = 2_048;
    private static final String TRACE_PATH =
        System.getProperty("arclight.prts.entityOwnershipTrace");
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

    /** Rows the independent counter saw run although the entry counted them as skipped. */
    static final LongAdder PROBE_SKIPPED_RAN = new LongAdder();
    /** Rows the independent counter saw run more than once in one tick. */
    static final LongAdder PROBE_RAN_TWICE = new LongAdder();
    /** Rows the entry did not skip and the independent counter never saw run. */
    static final LongAdder PROBE_NEVER_RAN = new LongAdder();
    /** Rows of the tick the independent counter and the entry agreed about. */
    static final LongAdder PROBE_MATCHED = new LongAdder();
    /** Agreeing rows whose tick body the counter saw as well as the host path. */
    static final LongAdder PROBE_BODY_SEEN = new LongAdder();
    /** Agreeing rows whose class ticks without reaching the base body. */
    static final LongAdder PROBE_BODY_BYPASSED = new LongAdder();
    /** Rows the independent counter was asked about; equals the entries when nothing is dropped. */
    static final LongAdder PROBE_CHECKED = new LongAdder();
    /** Every row a host entry of the process saw. */
    static final LongAdder PROBE_ENTRIES = new LongAdder();
    /** Every row of them whose original tick the entry counted as skipped. */
    static final LongAdder PROBE_SKIPPED = new LongAdder();
    /** Nanoseconds the host entries of the process spent deciding their rows. */
    static final LongAdder ENTRY_NANOS = new LongAdder();
    /** The longest single host-entry decision; a waiting entry would show up here. */
    static final AtomicLong ENTRY_MAX_NANOS = new AtomicLong();
    /** Entries that found the answer settled and skipped the row. */
    static final LongAdder DECIDED_SKIP = new LongAdder();
    /** Entries that found no settled answer and handed the row back. */
    static final LongAdder DECIDED_PENDING = new LongAdder();
    /** Entries whose token no longer matched the row. */
    static final LongAdder DECIDED_STALE = new LongAdder();
    /** Entries that held no token for the row at all. */
    static final LongAdder DECIDED_ABSENT = new LongAdder();
    /** Entries that reached a row this tick had already decided. */
    static final LongAdder DECIDED_CONFLICT = new LongAdder();
    /** Ticks whose plan point, entries and close were not observed in that order. */
    static final LongAdder ORDER_VIOLATIONS = new LongAdder();

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

    private static int[] entryIds = new int[TRACE_START];
    private static byte[] entrySkips = new byte[TRACE_START];
    private static int entryRows;
    private static long sequence;
    private static long currentTickIndex;
    private static long atPlanIssued;
    private static long atPlanCandidates;
    private static long atPlanSkipped;
    private static long atPlanExecuted;
    private static long atPlanEligible;
    private static long atPlanNotEntered;
    private static long tickProbeChecked;
    private static long tickProbeMatched;
    private static long tickProbeViolations;
    private static long tickEntryNanos;
    private static long planSequence;
    private static long firstEntrySequence;
    private static long lastEntrySequence;
    private static boolean lastWithdrawn;

    private static final long[] TL_TICK = new long[TIMELINE_TICKS];
    private static final long[] TL_PLAN_SEQ = new long[TIMELINE_TICKS];
    private static final long[] TL_FIRST_SEQ = new long[TIMELINE_TICKS];
    private static final long[] TL_LAST_SEQ = new long[TIMELINE_TICKS];
    private static final long[] TL_CLOSE_SEQ = new long[TIMELINE_TICKS];
    private static final long[] TL_ISSUED = new long[TIMELINE_TICKS];
    private static final long[] TL_ENTRIES = new long[TIMELINE_TICKS];
    private static final long[] TL_SKIPPED = new long[TIMELINE_TICKS];
    private static final long[] TL_EXECUTED = new long[TIMELINE_TICKS];
    private static final long[] TL_ELIGIBLE = new long[TIMELINE_TICKS];
    private static final long[] TL_NOT_ENTERED = new long[TIMELINE_TICKS];
    private static final long[] TL_PROBE_CHECKED = new long[TIMELINE_TICKS];
    private static final long[] TL_PROBE_MATCHED = new long[TIMELINE_TICKS];
    private static final long[] TL_PROBE_BROKEN = new long[TIMELINE_TICKS];
    private static final long[] TL_ENTRY_NANOS = new long[TIMELINE_TICKS];
    private static int timelineRows;

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
        beginTickRecord(tick);
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
            WholeTickModel model = TickModels.of(entity);
            if (model == null) {
                // The predicate admits no other class, so this is a row the plan point must not own.
                continue;
            }
            TickState state = new TickState();
            model.capture(entity, state);
            int entityId = entity.getId();
            long worldEpoch = worldEpochOf(level);
            int index = lease.issue(entityId, worldEpoch, entityEpochOf(entityId, tick),
                entity.tickCount + 1, OwnershipEligibility.fingerprintOf(entity), model, state);
            if (index >= 0) {
                ISSUED.increment();
            }
        }
        nextToken = lease.lastToken();
        lease.publish(pool(), Math.max(1, workerCount() * 4));
        PLAN_NANOS.add(System.nanoTime() - startedAt);
        current = lease;
    }

    /**
     * The host entry of one row: it decides, counts the decision right here and returns it, so the
     * platform binder cancels exactly the rows this entry counted as skipped. It never waits, never
     * throws and reads no world value of its own.
     */
    public static int onEntityTickPre(Entity entity) {
        if (!LIVE) {
            return RUN_HOST_TICK;
        }
        long startedAt = PROBED ? System.nanoTime() : 0L;
        int verdict = RUN_HOST_TICK;
        Level level = entity.level();
        if (level instanceof ServerLevel serverLevel) {
            noteTrace(serverLevel, entity.getId());
            WholeTickModel model = TickModels.of(entity);
            if (model == null) {
                // The entry of a row no model covers is still an entry: it is counted here, at the
                // host entry, so the partition of this fixture spans every row the host walked past.
                CANDIDATES.increment();
                HOST_EXECUTED.increment();
            } else {
                int index = decideRow(entity.getId(), worldEpochOf(serverLevel), entity.tickCount,
                    OwnershipEligibility.fingerprintOf(entity), FaultInjection.ownershipEpochBreak(),
                    entity.xo, entity.yo, entity.zo);
                OwnershipLease lease = current;
                boolean withdrawn = lastWithdrawn;
                if (index >= 0 && lease != null) {
                    // The commit segment of the row: the host applies the settled answer on the tick
                    // thread, takes the steps whose input is the row's own random stream or the level
                    // clock, and then skips the original tick. No worker ever touches the entity.
                    TickState answer = lease.answer(index);
                    model.apply(entity, answer);
                    model.commitHostSteps(entity, answer);
                    model.noteApplied();
                    APPLIED.increment();
                    verdict = SKIP_HOST_TICK;
                } else if (withdrawn && FaultInjection.ownershipDoubleRuns()) {
                    // The declared fault: a row the entry handed back runs its original tick here
                    // and then again on the host path. Only the counter of the tick body sees it.
                    entity.tick();
                }
            }
            noteEntry(entity.getId(), verdict == SKIP_HOST_TICK);
        }
        if (PROBED) {
            long spent = System.nanoTime() - startedAt;
            ENTRY_NANOS.add(spent);
            ENTRY_MAX_NANOS.accumulateAndGet(spent, Math::max);
            tickEntryNanos += spent;
        }
        return verdict;
    }

    /** The decision of one host entry, on the values the entry read; no world access, no wait.
     * A non-negative return is the index of the answer the caller must commit and then skip. */
    static int decideRow(int entityId, long worldEpoch, int tickCount, byte liveFingerprint,
        boolean epochBreak, double xo, double yo, double zo) {
        lastWithdrawn = false;
        OwnershipLease lease = current;
        if (lease == null) {
            CANDIDATES.increment();
            HOST_EXECUTED.increment();
            DECIDED_ABSENT.increment();
            return -1;
        }
        int index = lease.indexOf(entityId);
        if (index < 0) {
            CANDIDATES.increment();
            HOST_EXECUTED.increment();
            DECIDED_ABSENT.increment();
            return -1;
        }
        if (lease.looked(index)) {
            // One row of one tick must have at most one owner. A second entry is a conflict: the
            // row goes back to the host and its token is withdrawn, so it cannot stay skipped. The
            // row was already counted as a candidate, and a token that was already withdrawn is
            // not withdrawn twice.
            OWNER_CONFLICT.increment();
            DECIDED_CONFLICT.increment();
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
            DECIDED_STALE.increment();
            withdraw(lease, index);
            return -1;
        }
        if (lease.state(index) != OwnershipLease.SETTLED) {
            // No answer before the host needs the row: the token is withdrawn right here, in front
            // of the host, and the row runs its original tick exactly once. This is the only branch
            // a worker that did not finish in time can take, and it takes it without waiting.
            DECIDED_PENDING.increment();
            withdraw(lease, index);
            return -1;
        }
        lease.consume(index);
        HOST_SKIPPED.increment();
        DECIDED_SKIP.increment();
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
        closeTickRecord();
    }

    /** Opens the record of one tick: the phase order, the row tally and the plan-time snapshot. */
    private static void beginTickRecord(long tick) {
        currentTickIndex = tick;
        atPlanIssued = ISSUED.sum();
        atPlanCandidates = CANDIDATES.sum();
        atPlanSkipped = HOST_SKIPPED.sum();
        atPlanExecuted = HOST_EXECUTED.sum();
        atPlanEligible = ELIGIBLE.sum();
        atPlanNotEntered = NOT_ENTERED.sum();
        if (!PROBED) {
            return;
        }
        entryRows = 0;
        planSequence = ++sequence;
        firstEntrySequence = 0L;
        lastEntrySequence = 0L;
        tickProbeChecked = 0L;
        tickProbeMatched = 0L;
        tickProbeViolations = 0L;
        tickEntryNanos = 0L;
        PrtsHostTickCalls.beginTick();
    }

    /** Remembers one host entry of one row; the independent counter is compared against these. */
    private static void noteEntry(int entityId, boolean skipped) {
        if (!PROBED) {
            return;
        }
        long at = ++sequence;
        if (firstEntrySequence == 0L) {
            firstEntrySequence = at;
        }
        lastEntrySequence = at;
        if (entryRows == entryIds.length) {
            int grown = entryIds.length * 2;
            entryIds = java.util.Arrays.copyOf(entryIds, grown);
            entrySkips = java.util.Arrays.copyOf(entrySkips, grown);
        }
        entryIds[entryRows] = entityId;
        entrySkips[entryRows] = skipped ? (byte) 1 : (byte) 0;
        entryRows++;
    }

    /** Closes the record of one tick: compare the counter, check the order, keep the timeline row. */
    private static void closeTickRecord() {
        if (!PROBED) {
            return;
        }
        long closeSequence = ++sequence;
        long violations = crossCheckProbe(entryIds, entryRows, entrySkips,
            PrtsHostTickCalls::pathCallsOf, PrtsHostTickCalls::bodyCallsOf);
        long skippedRows = 0L;
        for (int at = 0; at < entryRows; at++) {
            if (entrySkips[at] != 0) {
                skippedRows++;
            }
        }
        PROBE_ENTRIES.add(entryRows);
        PROBE_SKIPPED.add(skippedRows);
        boolean ordered = planSequence != 0L && closeSequence > planSequence
            && (firstEntrySequence == 0L || firstEntrySequence > planSequence)
            && (lastEntrySequence == 0L || closeSequence > lastEntrySequence);
        if (!ordered) {
            ORDER_VIOLATIONS.increment();
        }
        int slot = (int) (timelineRows % TIMELINE_TICKS);
        TL_TICK[slot] = currentTickIndex;
        TL_PLAN_SEQ[slot] = planSequence;
        TL_FIRST_SEQ[slot] = firstEntrySequence;
        TL_LAST_SEQ[slot] = lastEntrySequence;
        TL_CLOSE_SEQ[slot] = closeSequence;
        TL_ISSUED[slot] = ISSUED.sum() - atPlanIssued;
        TL_ENTRIES[slot] = CANDIDATES.sum() - atPlanCandidates;
        TL_SKIPPED[slot] = HOST_SKIPPED.sum() - atPlanSkipped;
        TL_EXECUTED[slot] = HOST_EXECUTED.sum() - atPlanExecuted;
        TL_ELIGIBLE[slot] = ELIGIBLE.sum() - atPlanEligible;
        TL_NOT_ENTERED[slot] = NOT_ENTERED.sum() - atPlanNotEntered;
        TL_PROBE_CHECKED[slot] = tickProbeChecked;
        TL_PROBE_MATCHED[slot] = tickProbeMatched;
        TL_PROBE_BROKEN[slot] = violations;
        TL_ENTRY_NANOS[slot] = tickEntryNanos;
        timelineRows++;
        PrtsHostTickCalls.endTick();
    }

    /**
     * Compares the rows one tick's host entries saw with the calls the independent counter made. A
     * row the entry skipped must show no call at all, on either path; a row it did not skip must
     * show exactly one call of the host path, and never more body calls than path calls. Anything
     * else is a row the two records disagree about.
     *
     * @return the number of rows the two records disagree about
     */
    static long crossCheckProbe(int[] ids, int rows, byte[] skips, IntUnaryOperator paths,
        IntUnaryOperator bodies) {
        long violations = 0L;
        long matched = 0L;
        for (int at = 0; at < rows; at++) {
            int id = ids[at];
            int path = paths.applyAsInt(id);
            int body = bodies.applyAsInt(id);
            if (skips[at] != 0) {
                if (path == 0 && body == 0) {
                    matched++;
                } else {
                    violations++;
                    PROBE_SKIPPED_RAN.increment();
                }
                continue;
            }
            if (path > 1 || body > path) {
                violations++;
                PROBE_RAN_TWICE.increment();
            } else if (path == 0) {
                violations++;
                PROBE_NEVER_RAN.increment();
            } else {
                matched++;
                // A class whose tick does not reach the base body (a player) is seen on the host
                // path only; that is a property of the class, not a disagreement about the row.
                if (body == path) {
                    PROBE_BODY_SEEN.increment();
                } else {
                    PROBE_BODY_BYPASSED.increment();
                }
            }
        }
        PROBE_MATCHED.add(matched);
        PROBE_CHECKED.add(rows);
        tickProbeChecked = rows;
        tickProbeMatched = matched;
        tickProbeViolations = violations;
        return violations;
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
            + " probe_checked=" + PROBE_CHECKED.sum()
            + " probe_matched=" + PROBE_MATCHED.sum()
            + " probe_skipped_ran=" + PROBE_SKIPPED_RAN.sum()
            + " probe_ran_twice=" + PROBE_RAN_TWICE.sum()
            + " probe_never_ran=" + PROBE_NEVER_RAN.sum()
            + " " + PrtsHostTickCalls.evidence()
            + " probe_entries=" + PROBE_ENTRIES.sum()
            + " probe_skipped=" + PROBE_SKIPPED.sum()
            + " probe_body_seen=" + PROBE_BODY_SEEN.sum()
            + " probe_body_bypassed=" + PROBE_BODY_BYPASSED.sum()
            + " entry_nanos=" + ENTRY_NANOS.sum()
            + " entry_max_nanos=" + ENTRY_MAX_NANOS.get()
            + " decided_skip=" + DECIDED_SKIP.sum()
            + " decided_pending=" + DECIDED_PENDING.sum()
            + " decided_stale=" + DECIDED_STALE.sum()
            + " decided_absent=" + DECIDED_ABSENT.sum()
            + " decided_conflict=" + DECIDED_CONFLICT.sum()
            + " order_violations=" + ORDER_VIOLATIONS.sum()
            + " timeline_ticks=" + timelineRows
            + " closure=" + (closureOk() ? "ok" : "broken")
            + " invariants=" + (INVARIANT_VIOLATIONS.sum() == 0L ? "ok" : "broken")
            + " live=" + (LIVE ? 1 : 0);
    }

    /** One evidence line for the whole-tick models: what each answered, and why it refused a row. */
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
        for (WholeTickModel model : TickModels.all()) {
            String prefix = " " + model.name() + "_";
            builder.append(prefix).append("applied=").append(model.appliedCount());
            builder.append(prefix).append("compute_rows=").append(model.computeRows());
            builder.append(prefix).append("compute_ns_per_row=").append(String.format(Locale.ROOT,
                "%.1f", model.computeRows() == 0L ? 0.0
                    : (double) model.computeNanos() / (double) model.computeRows()));
            LongAdder[] counts = model.refusalCounts();
            String[] names = model.refusalNames();
            for (int index = 1; index < counts.length && index < names.length; index++) {
                builder.append(prefix).append("refused_").append(names[index]).append('=')
                    .append(counts[index].sum());
            }
        }
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
        PROBE_SKIPPED_RAN.reset();
        PROBE_RAN_TWICE.reset();
        PROBE_NEVER_RAN.reset();
        PROBE_MATCHED.reset();
        PROBE_BODY_SEEN.reset();
        PROBE_BODY_BYPASSED.reset();
        PROBE_CHECKED.reset();
        PROBE_ENTRIES.reset();
        PROBE_SKIPPED.reset();
        ENTRY_NANOS.reset();
        ENTRY_MAX_NANOS.set(0L);
        DECIDED_SKIP.reset();
        DECIDED_PENDING.reset();
        DECIDED_STALE.reset();
        DECIDED_ABSENT.reset();
        DECIDED_CONFLICT.reset();
        ORDER_VIOLATIONS.reset();
        for (WholeTickModel model : TickModels.all()) {
            model.reset();
        }
        entryRows = 0;
        sequence = 0L;
        planSequence = 0L;
        firstEntrySequence = 0L;
        lastEntrySequence = 0L;
        lastWithdrawn = false;
        timelineRows = 0;
        tickEntryNanos = 0L;
        tickProbeChecked = 0L;
        tickProbeMatched = 0L;
        tickProbeViolations = 0L;
        PrtsHostTickCalls.reset();
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
        dumpTimeline();
        reset();
        synchronized (EntityTickOwnership.class) {
            ThreadPoolExecutor open = pool;
            pool = null;
            if (open != null) {
                open.shutdownNow();
            }
        }
    }

    /**
     * Writes the timeline of the observed ticks when the harness declared a trace path: one line per
     * tick with the order of its three phases, the counts of each phase and the verdict of the
     * independent counter. Nothing is written while no path was declared.
     */
    static void dumpTimeline() {
        if (TRACE_PATH == null || TRACE_PATH.isEmpty() || timelineRows == 0) {
            return;
        }
        Path path = Path.of(TRACE_PATH);
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("# entity-ownership timeline: " + timelineRows + " tick(s), "
                + Math.min(timelineRows, TIMELINE_TICKS) + " kept, order_violations="
                + ORDER_VIOLATIONS.sum() + " " + PrtsHostTickCalls.evidence() + "\n");
            writer.write("# columns: tick plan_seq first_seq last_seq close_seq issued entries"
                + " skipped executed eligible not_entered probe_checked probe_matched probe_broken"
                + " entry_nanos\n");
            int from = Math.max(0, timelineRows - TIMELINE_TICKS);
            for (int at = from; at < timelineRows; at++) {
                int slot = at % TIMELINE_TICKS;
                writer.write(Long.toString(TL_TICK[slot]));
                writer.write(' ' + Long.toString(TL_PLAN_SEQ[slot]));
                writer.write(' ' + Long.toString(TL_FIRST_SEQ[slot]));
                writer.write(' ' + Long.toString(TL_LAST_SEQ[slot]));
                writer.write(' ' + Long.toString(TL_CLOSE_SEQ[slot]));
                writer.write(' ' + Long.toString(TL_ISSUED[slot]));
                writer.write(' ' + Long.toString(TL_ENTRIES[slot]));
                writer.write(' ' + Long.toString(TL_SKIPPED[slot]));
                writer.write(' ' + Long.toString(TL_EXECUTED[slot]));
                writer.write(' ' + Long.toString(TL_ELIGIBLE[slot]));
                writer.write(' ' + Long.toString(TL_NOT_ENTERED[slot]));
                writer.write(' ' + Long.toString(TL_PROBE_CHECKED[slot]));
                writer.write(' ' + Long.toString(TL_PROBE_MATCHED[slot]));
                writer.write(' ' + Long.toString(TL_PROBE_BROKEN[slot]));
                writer.write(' ' + Long.toString(TL_ENTRY_NANOS[slot]));
                writer.write('\n');
            }
        } catch (IOException failed) {
            LOGGER.warn("[PRTS] entity-ownership: cannot write the timeline to {}: {}",
                TRACE_PATH, failed.toString());
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
        lastWithdrawn = true;
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
