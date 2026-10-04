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
 *
 * <p>The plan point freezes one capability per claimed row and the entry reads that record instead
 * of asking the world again; every claimed row ends in exactly one of four outcomes, and the four
 * are checked against the claim count on every tick.
 *
 * <p>The rows of one world of one tick are a segment: its ownership set, the rows it only observes,
 * and the world inputs and three generations they were frozen under. The two row sets are booked
 * apart and checked against each other on every frame; an observed row has no token and no answer,
 * so it can neither be committed nor counted as an ownership row.
 */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.DomainReadings;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.dispatch.FaultInjection;
import io.izzel.arclight.common.prts.kernel.dispatch.SegmentFrames;
import io.izzel.arclight.common.prts.support.PrtsHostTickCalls;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.ArmorStandTick;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickModels;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickState;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.WholeTickModel;
import io.izzel.arclight.common.prts.support.PrtsEntityRescope;
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
    /** Rows the plan point claimed for takeover; one claim per issued token. */
    static final LongAdder CLAIMED = new LongAdder();
    /** Rows a host entry reached, claimed or not. */
    static final LongAdder CANDIDATES = new LongAdder();
    /** Claimed rows whose ownership was not exercised, so the host ran them. */
    static final LongAdder ELIGIBLE = new LongAdder();
    /** Claims handed back at the host entry without an answer, so the host runs the row. */
    static final LongAdder CLAIM_EXECUTED = new LongAdder();
    /** Claims withdrawn because the row no longer matched them, so the host runs the row. */
    static final LongAdder CLAIM_REVOKED = new LongAdder();
    /** Entries of rows the fixture never claimed; their original tick was never in question. */
    static final LongAdder NEVER_CLAIMED = new LongAdder();
    /** Rows of a world the lifecycle guard does not track; such a row is never claimed. */
    static final LongAdder WORLD_NOT_LIVE = new LongAdder();
    /** Host-thread nanoseconds the entries spent committing a settled answer onto its row. */
    static final LongAdder COMMIT_NANOS = new LongAdder();
    static final LongAdder COMMITTED_ROWS = new LongAdder();
    /** The times the fixture disarmed itself after a failure; must stay zero in a passing run. */
    static final LongAdder DISARMS = new LongAdder();
    /** Rows whose original tick the host did not run. */
    static final LongAdder HOST_SKIPPED = new LongAdder();
    /** Rows a host entry left to the host: every row it reached except the skipped ones. */
    static final LongAdder HOST_EXECUTED = new LongAdder();
    /** Tokens withdrawn at the host entry, the left end of the fallback pair. */
    static final LongAdder FALLBACK = new LongAdder();
    /** Issued tokens that never reached a host entry; their lease was recycled. */
    static final LongAdder NOT_ENTERED = new LongAdder();
    /** Every withdrawn token: those withdrawn at a host entry and those recycled at the close. */
    static final LongAdder WITHDRAWN = new LongAdder();
    /** Host entries whose lookup threw; the row was handed back and the host ran it. */
    static final LongAdder ENTRY_FAULTS = new LongAdder();
    /** Worker rows whose answer threw; the host entry handed every one of them back. */
    static final LongAdder WORKER_FAULTS = new LongAdder();
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

    /** Segment frames closed; one per world the plan point claimed or watched a row in. */
    static final LongAdder SEGMENT_FRAMES = new LongAdder();
    /** Rows booked in an ownership set. */
    static final LongAdder OWNED_ROWS = new LongAdder();
    /** Rows booked as observed: read-only, never committed and never counted as owned. */
    static final LongAdder OBSERVED_ROWS = new LongAdder();
    /** Observed rows that reached the host entry and ran their original tick there. */
    static final LongAdder OBSERVED_ENTRIES = new LongAdder();
    /** Frames in which one row was booked in both sets; must stay zero. */
    static final LongAdder SET_CONFLICTS = new LongAdder();
    /** Commits of rows this segment did not book as ownership rows; must stay zero. */
    static final LongAdder UNBOOKED_COMMITS = new LongAdder();
    /** Commits that landed before a row the host had already passed in the frozen order. */
    static final LongAdder ORDINAL_VIOLATIONS = new LongAdder();
    /** Claims rejected because the row is no longer the entity the plan point froze. */
    static final LongAdder LAYER_ENTITY_REJECTED = new LongAdder();
    /** Claims rejected because the world inputs or the world generation no longer match. */
    static final LongAdder LAYER_WORLD_REJECTED = new LongAdder();
    /** Claims rejected because the segment frame they were frozen in is not the live one. */
    static final LongAdder LAYER_SEGMENT_REJECTED = new LongAdder();
    /** Frames whose ownership set and observed rows did not stay apart; must stay zero. */
    static final LongAdder BROKEN_FRAMES = new LongAdder();
    /** Frames whose ownership rows did not all end in exactly one outcome; must stay zero. */
    static final LongAdder BROKEN_SEGMENT_LEDGERS = new LongAdder();

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
    private static volatile boolean disarmed;
    private static volatile String disarmReason = "";
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
    // The segments of the tick being planned, one per world: the entry finds the segment of the row
    // by the world it is in and reads every value it compares from that frozen frame.
    private static final SegmentWork.Table SEGMENTS = new SegmentWork.Table();
    private static final int SEGMENT_RING = 4_096;
    private static SegmentFrames.Frame[] segmentRing = new SegmentFrames.Frame[SEGMENT_RING];
    private static int segmentFrameRows;
    // The frames one world closed, so a run that ticks two worlds can account for both: frames,
    // owned rows, commits, fallbacks, rows that never entered and frames whose ledger broke.
    private static final java.util.LinkedHashMap<String, long[]> WORLD_TOTALS =
        new java.util.LinkedHashMap<>();

    private static volatile ThreadPoolExecutor pool;

    private static int[] entryIds = new int[TRACE_START];
    private static byte[] entrySkips = new byte[TRACE_START];
    private static int entryRows;
    private static long sequence;
    private static long currentTickIndex;
    private static long atPlanIssued;
    private static long atPlanClaimed;
    private static long atPlanCandidates;
    private static long atPlanSkipped;
    private static long atPlanExecuted;
    private static long atPlanEligible;
    private static long atPlanNotEntered;
    private static long atPlanClaimExecuted;
    private static long atPlanClaimRevoked;
    private static long atPlanNeverClaimed;
    private static long tickProbeChecked;
    private static long tickProbeMatched;
    private static long tickProbeViolations;
    private static long tickEntryNanos;
    private static long tickSegments;
    private static long tickOwned;
    private static long tickObserved;
    private static long tickSetConflicts;
    private static long tickUnbookedCommits;
    private static long tickOrdinalBroken;
    private static long tickEntityRejected;
    private static long tickWorldRejected;
    private static long tickSegmentRejected;
    private static long tickBrokenFrames;
    private static long tickBrokenLedgers;
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
    private static final long[] TL_CLAIMED = new long[TIMELINE_TICKS];
    private static final long[] TL_CLAIM_EXECUTED = new long[TIMELINE_TICKS];
    private static final long[] TL_CLAIM_REVOKED = new long[TIMELINE_TICKS];
    private static final long[] TL_NEVER_CLAIMED = new long[TIMELINE_TICKS];
    private static final long[] TL_LEDGER_OK = new long[TIMELINE_TICKS];
    private static final long[] TL_SEGMENTS = new long[TIMELINE_TICKS];
    private static final long[] TL_OWNED = new long[TIMELINE_TICKS];
    private static final long[] TL_OBSERVED = new long[TIMELINE_TICKS];
    private static final long[] TL_SET_CONFLICTS = new long[TIMELINE_TICKS];
    private static final long[] TL_UNBOOKED_COMMITS = new long[TIMELINE_TICKS];
    private static final long[] TL_ORDINAL_BROKEN = new long[TIMELINE_TICKS];
    private static final long[] TL_ENTITY_REJECTED = new long[TIMELINE_TICKS];
    private static final long[] TL_WORLD_REJECTED = new long[TIMELINE_TICKS];
    private static final long[] TL_SEGMENT_REJECTED = new long[TIMELINE_TICKS];
    private static final long[] TL_BROKEN_FRAMES = new long[TIMELINE_TICKS];
    private static int timelineRows;

    /** The timeline columns in the order the row of one tick writes them. */
    static final String[] TIMELINE_COLUMNS = {"tick", "plan_seq", "first_seq", "last_seq",
        "close_seq", "issued", "claimed", "entries", "skipped", "executed", "eligible",
        "not_entered", "claim_executed", "claim_revoked", "never_claimed", "ledger_ok",
        "probe_checked", "probe_matched", "probe_broken", "entry_nanos", "segments", "owned",
        "observed", "set_conflicts", "unbooked_commits", "ordinal_broken", "entity_rejected",
        "world_rejected", "segment_rejected", "broken_frames"};

    private EntityTickOwnership() {
    }

    /** Whether this process declares the fixture; false means every entry point returns at once. */
    public static boolean live() {
        return LIVE;
    }

    /** Whether the fixture is still taking rows over. A declared fixture that failed an account,
     * a frame check or the independent counter disarms itself, and from the next tick on every row
     * runs its original tick on the host path; the latch is never released inside the process. */
    public static boolean armed() {
        return LIVE && !disarmed;
    }

    public static long disarms() {
        return DISARMS.sum();
    }

    /** Whether the latch tripped; a test and the evidence line read it while the arm is off. */
    static boolean latched() {
        return disarmed;
    }

    /** Disarms the fixture after a failure. Idempotent, and it never releases the latch. */
    private static void disarm(String reason) {
        if (disarmed) {
            return;
        }
        disarmed = true;
        disarmReason = reason;
        DISARMS.increment();
        if (LIVE) {
            LOGGER.warn("[PRTS] entity-ownership: disarmed ({}); the fixture claims no further row"
                + " and every row runs its original tick on the host path", reason);
        }
    }

    /** The plan point of one tick: freeze the eligible rows, issue their tokens, dispatch them. */
    public static void onServerTickPre(MinecraftServer server) {
        if (!armed()) {
            return;
        }
        swapTrace();
        current = null;
        // A segment frame belongs to one tick; the next plan point drops the frames it replaced.
        SEGMENTS.reset();
        long tick = server.getTickCount();
        beginTickRecord(tick);
        long startedAt = System.nanoTime();
        OwnershipLease lease = new OwnershipLease(tick, MAX_ROWS, nextToken);
        for (int row = 0; row < traceReadRows; row++) {
            ServerLevel level = traceLevelsRead[row];
            if (level == null) {
                continue;
            }
            Entity entity = level.getEntity(traceIdsRead[row]);
            if (entity == null) {
                continue;
            }
            int entityId = entity.getId();
            long entityEpoch = entityEpochOf(entityId, tick);
            int slot = TickModels.slotOf(entity);
            TakeoverWhitelist.noteSeen(slot, entity.getClass());
            long worldEpoch = worldEpochOf(level);
            SegmentWork segment = SEGMENTS.of(level, worldIdOf(level), worldEpoch, tick);
            if (worldEpoch <= 0L) {
                // The lifecycle guard does not track this world, so the row is not a row this
                // takeover may answer for: it is watched, never claimed.
                WORLD_NOT_LIVE.increment();
                observe(segment, entityId, entityEpoch, OwnershipEligibility.LIFECYCLE);
                continue;
            }
            int reason = OwnershipEligibility.reasonOf(level, entity);
            if (reason == OwnershipEligibility.RIDING) {
                TakeoverWhitelist.notePassenger(slot);
            }
            if (reason != OwnershipEligibility.WIDENED) {
                REASON_COUNTS[reason].increment();
                // A refused row is watched, not owned: booked as observed, it carries no token.
                observe(segment, entityId, entityEpoch, reason);
                continue;
            }
            WholeTickModel model = TickModels.of(entity);
            if (model == null || lease.rows() >= MAX_ROWS || lease.indexOf(entityId) >= 0) {
                observe(segment, entityId, entityEpoch, OwnershipEligibility.MODEL);
                continue;
            }
            TickState state = new TickState();
            model.capture(entity, state);
            // The capability of the row is frozen here, from values read once on the tick thread:
            // the whole-tick model is the proof that this row's tick is pure kinematics, and the
            // row-level recheck says whether this row is one that model still covers. Every value
            // the host entry compares later is in this record, so the entry reads no world state.
            boolean covered = model.retains(entity);
            int ordinal = segment.claim(entityId, entityEpoch,
                entity.getUUID().getMostSignificantBits(),
                entity.getUUID().getLeastSignificantBits());
            // The neighbour query the predicate ran came back empty; the verdict is frozen here.
            segment.readSet().freezeNeighbourVerdict(ordinal, true);
            OwnershipLease.EntityCapability capability = new OwnershipLease.EntityCapability(entityId,
                entityEpoch, segment.readSet().worldEpoch(), entity.tickCount + 1,
                true, covered, OwnershipEligibility.fingerprintOf(entity), state.x, state.y, state.z,
                segment.segmentEpoch(), ordinal);
            int index = lease.issue(capability, model, state);
            if (index >= 0) {
                ISSUED.increment();
                CLAIMED.increment();
                TakeoverWhitelist.noteClaimed(slot);
                segment.book(ordinal, lease.token(index));
                if (FaultInjection.ownershipObserveClaims()) {
                    // The declared fault: the frame has to report one row in both sets.
                    segment.conflictForFault(entityId, entityEpoch, ordinal);
                }
            }
        }
        nextToken = lease.lastToken();
        lease.publish(pool(), Math.max(1, workerCount() * 4));
        PLAN_NANOS.add(System.nanoTime() - startedAt);
        current = lease;
    }

    /**
     * The host entry of one row: it reads the frozen capability of the row once, decides right there,
     * counts the decision and returns it, so the platform binder cancels exactly the rows this entry
     * counted as skipped. It waits for no worker, throws nothing outward and asks the world nothing:
     * a row it cannot take runs its original tick on the host path, exactly once.
     */
    public static int onEntityTickPre(Entity entity) {
        if (!armed()) {
            return RUN_HOST_TICK;
        }
        long startedAt = PROBED ? System.nanoTime() : 0L;
        int verdict = RUN_HOST_TICK;
        try {
            Level level = entity.level();
            if (level instanceof ServerLevel serverLevel) {
                noteTrace(serverLevel, entity.getId());
                verdict = lookup(entity, serverLevel);
                noteEntry(entity.getId(), verdict == SKIP_HOST_TICK);
            }
        } catch (Throwable fault) {
            // A host entry must never throw: whatever failed in the lookup hands the row back.
            ENTRY_FAULTS.increment();
            verdict = RUN_HOST_TICK;
        }
        if (PROBED) {
            long spent = System.nanoTime() - startedAt;
            ENTRY_NANOS.add(spent);
            ENTRY_MAX_NANOS.accumulateAndGet(spent, Math::max);
            tickEntryNanos += spent;
        }
        return verdict;
    }

    /** The live values of one row, read once at the host entry and compared with its frozen
     * capability; the three generations are read from the segment the row is in. */
    record LiveRow(int entityId, long uuidHigh, long uuidLow, int tickVersion, byte fingerprint,
        double xo, double yo, double zo) {
    }

    /** The host entry of a row on a server level: the frozen capability of the row is read once and
     * compared with the row's own fields; a skip commits the settled answer on the tick thread. */
    private static int lookup(Entity entity, ServerLevel level) {
        SegmentWork segment = SEGMENTS.live(level);
        LiveRow live = new LiveRow(entity.getId(), entity.getUUID().getMostSignificantBits(),
            entity.getUUID().getLeastSignificantBits(), entity.tickCount,
            OwnershipEligibility.fingerprintOf(entity), entity.xo, entity.yo, entity.zo);
        int index = decideRow(live, segment);
        // The outcome of this row, by class: a row of a class outside the list can only land on the
        // executed side, which is what the census of the whitelist has to show.
        TakeoverWhitelist.noteEntry(TickModels.slotOf(entity), index >= 0);
        OwnershipLease lease = current;
        if (index >= 0 && lease != null) {
            // The commit segment of the row: the host applies the settled answer on the tick thread,
            // takes the steps whose input is the row's own random stream or the level clock, and
            // then skips the original tick. No worker ever touches the entity.
            WholeTickModel model = lease.model(index);
            TickState answer = lease.answer(index);
            long commitStartedAt = System.nanoTime();
            model.apply(entity, answer);
            model.commitHostSteps(entity, answer);
            COMMIT_NANOS.add(System.nanoTime() - commitStartedAt);
            model.noteApplied();
            APPLIED.increment();
            // The commit is booked against the ownership row it belongs to, or counted instead.
            segment.noteCommit(lease.capability(index).hostOrdinal());
            return SKIP_HOST_TICK;
        }
        if (lastWithdrawn && FaultInjection.ownershipDoubleRuns()) {
            // The declared fault: a row the entry handed back runs its original tick here and then
            // again on the host path. Only the counter of the tick body sees it.
            entity.tick();
        }
        return RUN_HOST_TICK;
    }

    /** The decision of one host entry: the frozen capability of the row, compared with the values
     * the entry read from the row itself and with the three generations of its segment. No world
     * access, no wait; a non-negative return is the index of the answer the caller must commit and
     * then skip. */
    static int decideRow(LiveRow live, SegmentWork segment) {
        lastWithdrawn = false;
        OwnershipLease lease = current;
        if (lease == null) {
            absent(segment, live.entityId());
            return -1;
        }
        int index = lease.indexOf(live.entityId());
        if (index < 0) {
            absent(segment, live.entityId());
            return -1;
        }
        OwnershipLease.EntityCapability capability = lease.capability(index);
        if (lease.looked(index)) {
            // One row of one tick must have at most one owner. A second entry is a conflict, and it
            // is counted as one: the row is not a second row, so it does not enter the partition of
            // the entries, and a token that was already withdrawn is not withdrawn twice. The claim
            // the conflict undoes is booked where the withdrawal happens.
            OWNER_CONFLICT.increment();
            DECIDED_CONFLICT.increment();
            disarm("owner-conflict");
            if (lease.state(index) == OwnershipLease.CONSUMED) {
                HOST_SKIPPED.decrement();
                withdraw(lease, index, true);
            } else {
                lease.revoke(index);
            }
            return -1;
        }
        CANDIDATES.increment();
        lease.markLooked(index);
        if (segment == null) {
            // No row of the world this row is in now was frozen, so this claim is not its claim.
            LAYER_SEGMENT_REJECTED.increment();
            return reject(lease, index);
        }
        int ordinal = capability.hostOrdinal();
        // Entity layer: the kernel generation of the row and the identity it was frozen with.
        if (capability.entityEpoch() != ENTITY_EPOCH.get(live.entityId())
            || segment.entityIdOf(ordinal) != live.entityId()
            || segment.uuidHighOf(ordinal) != live.uuidHigh()
            || segment.uuidLowOf(ordinal) != live.uuidLow()
            || FaultInjection.ownershipEntityBreak()) {
            segment.noteEntityRejected();
            return reject(lease, index);
        }
        // World layer: the world generation and the frozen verdict of its neighbour query.
        if (FaultInjection.ownershipEpochBreak()
            || capability.worldEpoch() != segment.readSet().worldEpoch()
            || !segment.neighbourClearOf(ordinal)) {
            segment.noteWorldRejected();
            return reject(lease, index);
        }
        // Segment layer: the frame the row was frozen in is the frame the host is running now.
        if (capability.segmentEpoch() != segment.segmentEpoch()
            || FaultInjection.ownershipSegmentBreak()) {
            segment.noteSegmentRejected();
            return reject(lease, index);
        }
        if (!segment.acceptOrdinal(ordinal)) {
            return reject(lease, index);
        }
        boolean matches = capability.pureKinematics()
            && capability.eligibleForTakeover()
            && capability.hostTickVersion() == live.tickVersion()
            && capability.fingerprint() == live.fingerprint()
            && capability.holdsPosition(live.xo(), live.yo(), live.zo());
        if (!matches) {
            return reject(lease, index);
        }
        if (lease.state(index) != OwnershipLease.SETTLED) {
            // No answer before the host needs the row: the token is withdrawn right here, in front
            // of the host, and the row runs its original tick exactly once. This is the only branch
            // a worker that did not finish in time can take, and it takes it without waiting.
            DECIDED_PENDING.increment();
            withdraw(lease, index, false);
            return -1;
        }
        lease.consume(index);
        HOST_SKIPPED.increment();
        DECIDED_SKIP.increment();
        return index;
    }

    /** Hands a claim whose row, world or segment generation no longer matches back to the host. */
    private static int reject(OwnershipLease lease, int index) {
        LIFECYCLE_REJECTED.increment();
        DECIDED_STALE.increment();
        withdraw(lease, index, true);
        return -1;
    }

    /** The entry of a row the fixture holds no claim for: the host runs its original tick. */
    private static void absent(SegmentWork segment, int entityId) {
        CANDIDATES.increment();
        HOST_EXECUTED.increment();
        NEVER_CLAIMED.increment();
        DECIDED_ABSENT.increment();
        if (segment != null) {
            segment.noteObservedEntry(entityId);
        }
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
                    WITHDRAWN.increment();
                    lease.recycle(index);
                }
            }
            lease.close();
            TOKEN_LEAK.add(lease.unresolved());
            SETTLED_ROWS.add(lease.settledRows());
            WORKER_FAULTS.add(lease.workerFaults());
        }
        if (previous != null) {
            long now = previous.lateDroppedRows();
            LATE_DROPPED.add(now - previousLate);
            previousLate = now;
        }
        previous = lease;
        closeSegmentFrames(lease);
        checkInvariants();
        closeTickRecord();
    }

    /**
     * Closes every segment frame of this tick: the ownership set and the observed rows of each world
     * are checked against each other, every ownership row is checked to end in exactly one outcome,
     * and the per-row generations each entry compared are summed into the tick record. A frame that
     * fails either check is logged with the row that broke it and counted; nothing here can change
     * what the tick did.
     */
    private static void closeSegmentFrames(OwnershipLease lease) {
        for (int at = 0; at < SEGMENTS.rows(); at++) {
            SegmentWork segment = SEGMENTS.at(at);
            SegmentWork.Frame frame = segment.closeFrame(lease);
            SEGMENT_FRAMES.increment();
            OWNED_ROWS.add(frame.ownedRows());
            OBSERVED_ROWS.add(frame.observedRows());
            OBSERVED_ENTRIES.add(frame.observedEntries());
            SET_CONFLICTS.add(frame.setConflicts());
            UNBOOKED_COMMITS.add(frame.unbookedCommits());
            ORDINAL_VIOLATIONS.add(frame.ordinalBroken());
            COMMITTED_ROWS.add(frame.committed());
            noteWorldTotals(frame);
            LAYER_ENTITY_REJECTED.add(frame.entityRejected());
            LAYER_WORLD_REJECTED.add(frame.worldRejected());
            LAYER_SEGMENT_REJECTED.add(frame.segmentRejected());
            tickSegments++;
            tickOwned += frame.ownedRows();
            tickObserved += frame.observedRows();
            tickSetConflicts += frame.setConflicts();
            tickUnbookedCommits += frame.unbookedCommits();
            tickOrdinalBroken += frame.ordinalBroken();
            tickEntityRejected += frame.entityRejected();
            tickWorldRejected += frame.worldRejected();
            tickSegmentRejected += frame.segmentRejected();
            if (frame.setConflicts() > 0) {
                BROKEN_FRAMES.increment();
                tickBrokenFrames++;
                LOGGER.warn("[PRTS] entity-segment: a row is in both the ownership set and the"
                        + " observed rows: entity={} ordinal={} world={}",
                    segment.conflictingEntity(), segment.conflictingOrdinal(), frame.worldId());
            }
            if (!frame.ledgerOk()) {
                BROKEN_SEGMENT_LEDGERS.increment();
                tickBrokenLedgers++;
            }
            if (frame.setConflicts() > 0 || frame.unbookedCommits() > 0 || frame.ordinalBroken() > 0
                || !frame.ledgerOk()) {
                // A frame whose accounts do not close is a failure of the takeover, not a reading:
                // the fixture stops claiming from the next tick on.
                disarm("frame");
            }
            segmentRing[segmentFrameRows % SEGMENT_RING] = asFrame(frame);
            segmentFrameRows++;
        }
    }

    /** Opens the record of one tick: the phase order, the row tally and the plan-time snapshot. */
    private static void beginTickRecord(long tick) {
        currentTickIndex = tick;
        atPlanIssued = ISSUED.sum();
        atPlanClaimed = CLAIMED.sum();
        atPlanCandidates = CANDIDATES.sum();
        atPlanSkipped = HOST_SKIPPED.sum();
        atPlanExecuted = HOST_EXECUTED.sum();
        atPlanEligible = ELIGIBLE.sum();
        atPlanNotEntered = NOT_ENTERED.sum();
        atPlanClaimExecuted = CLAIM_EXECUTED.sum();
        atPlanClaimRevoked = CLAIM_REVOKED.sum();
        atPlanNeverClaimed = NEVER_CLAIMED.sum();
        tickSegments = 0L;
        tickOwned = 0L;
        tickObserved = 0L;
        tickSetConflicts = 0L;
        tickUnbookedCommits = 0L;
        tickOrdinalBroken = 0L;
        tickEntityRejected = 0L;
        tickWorldRejected = 0L;
        tickSegmentRejected = 0L;
        tickBrokenFrames = 0L;
        tickBrokenLedgers = 0L;
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
        if (violations > 0L) {
            // The independent counter saw a row the entry did not: a skipped row that ran, a row
            // that ran twice, or a row that never ran. The fixture stops claiming at once.
            disarm("probe");
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
        TL_CLAIMED[slot] = CLAIMED.sum() - atPlanClaimed;
        TL_CLAIM_EXECUTED[slot] = CLAIM_EXECUTED.sum() - atPlanClaimExecuted;
        TL_CLAIM_REVOKED[slot] = CLAIM_REVOKED.sum() - atPlanClaimRevoked;
        TL_NEVER_CLAIMED[slot] = NEVER_CLAIMED.sum() - atPlanNeverClaimed;
        TL_LEDGER_OK[slot] = claimLedgerOk() ? 1L : 0L;
        TL_SEGMENTS[slot] = tickSegments;
        TL_OWNED[slot] = tickOwned;
        TL_OBSERVED[slot] = tickObserved;
        TL_SET_CONFLICTS[slot] = tickSetConflicts;
        TL_UNBOOKED_COMMITS[slot] = tickUnbookedCommits;
        TL_ORDINAL_BROKEN[slot] = tickOrdinalBroken;
        TL_ENTITY_REJECTED[slot] = tickEntityRejected;
        TL_WORLD_REJECTED[slot] = tickWorldRejected;
        TL_SEGMENT_REJECTED[slot] = tickSegmentRejected;
        TL_BROKEN_FRAMES[slot] = tickBrokenFrames;
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
        long withdrawn = WITHDRAWN.sum();
        return "[PRTS] entity-ownership: issued=" + issued
            + " claimed=" + CLAIMED.sum()
            + " owned=" + (issued - withdrawn)
            + " candidates=" + candidates
            + " eligible=" + eligible
            + " host_skipped=" + skipped
            + " host_executed=" + executed
            + " never_claimed=" + NEVER_CLAIMED.sum()
            + " fallback=" + FALLBACK.sum()
            + " committed=" + COMMITTED_ROWS.sum()
            + " applied=" + APPLIED.sum()
            + " world_not_live=" + WORLD_NOT_LIVE.sum()
            + " plan_nanos=" + PLAN_NANOS.sum()
            + " commit_nanos=" + COMMIT_NANOS.sum()
            + " k_carried_ns=" + format(kCarriedNanos())
            + " c_move_ns=" + format(cMoveNanos())
            + " net_per_unit=" + format(netPerUnit())
            + " net_per_entry_row=" + format(netPerEntryRow())
            + " not_entered=" + notEntered
            + " revoked=" + withdrawn
            + " claim_executed=" + CLAIM_EXECUTED.sum()
            + " claim_revoked=" + CLAIM_REVOKED.sum()
            + " worker_faults=" + WORKER_FAULTS.sum()
            + " entry_faults=" + ENTRY_FAULTS.sum()
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
            + " claim_ledger=" + (claimLedgerOk() ? "ok" : "broken")
            + " closure=" + (closureOk() ? "ok" : "broken")
            + " invariants=" + (INVARIANT_VIOLATIONS.sum() == 0L ? "ok" : "broken")
            + " armed=" + (armed() ? 1 : 0)
            + " disarms=" + DISARMS.sum()
            + " disarm_reason=" + (disarmReason.isEmpty() ? "none" : disarmReason)
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
        long withdrawn = WITHDRAWN.sum();
        sink.add("entity.candidates", CANDIDATES.sum());
        sink.add("entity.eligible", ELIGIBLE.sum());
        sink.add("entity.owned", issued - withdrawn);
        sink.add("entity.host_skipped", HOST_SKIPPED.sum());
        sink.add("entity.host_executed", HOST_EXECUTED.sum());
        sink.add("entity.fallback", FALLBACK.sum());
        sink.add("entity.owner_conflict", OWNER_CONFLICT.sum());
        sink.add("entity.lifecycle_rejected", LIFECYCLE_REJECTED.sum());
        sink.add("entity.coverage", coverage());
        sink.add("entity.issued", issued);
        sink.add("entity.not_entered", NOT_ENTERED.sum());
        sink.add("entity.revoked", withdrawn);
        sink.add("entity.token_leak", TOKEN_LEAK.sum());
        sink.add("entity.late_dropped", LATE_DROPPED.sum());
        sink.add("entity.settled", SETTLED_ROWS.sum());
        sink.add("entity.invariant_violations", INVARIANT_VIOLATIONS.sum());
        sink.add("entity.commit", COMMITTED_ROWS.sum());
        sink.add("entity.applied", APPLIED.sum());
        sink.add("entity.world_not_live", WORLD_NOT_LIVE.sum());
        sink.add("entity.plan_nanos", PLAN_NANOS.sum());
        sink.add("entity.entry_nanos", ENTRY_NANOS.sum());
        sink.add("entity.commit_nanos", COMMIT_NANOS.sum());
        sink.add("entity.k_carried_ns", kCarriedNanos());
        sink.add("entity.c_move_ns", cMoveNanos());
        sink.add("entity.net_per_unit", netPerUnit());
        sink.add("entity.net_per_entry_row", netPerEntryRow());
        sink.add("entity.disarmed", disarmed ? 1L : 0L);
        sink.add("entity.disarms", DISARMS.sum());
        TakeoverWhitelist.readings(sink);
    }

    /** One line with the five quantities of the controlled takeover and the digest of the state the
     * takeover answered with, so a reader gets the two host decisions, the fallback, the commits and
     * the hash from one place of one run. */
    public static String takeoverLine(String hash, String algorithm) {
        return "[PRTS] entity-takeover: host_skip=" + HOST_SKIPPED.sum()
            + " host_execute=" + HOST_EXECUTED.sum()
            + " fallback=" + FALLBACK.sum()
            + " commit=" + COMMITTED_ROWS.sum()
            + " applied=" + APPLIED.sum()
            + " hash=" + hash
            + " algorithm=" + algorithm
            + " frames=" + SEGMENT_FRAMES.sum()
            + " worlds=" + WORLD_TOTALS.size()
            + " armed=" + (armed() ? 1 : 0);
    }

    /** The host-thread cost the fixture added per row it took over. */
    static double kCarriedNanos() {
        long carried = HOST_SKIPPED.sum();
        if (carried == 0L) {
            return 0.0;
        }
        return (double) (PLAN_NANOS.sum() + ENTRY_NANOS.sum() + COMMIT_NANOS.sum())
            / (double) carried;
    }

    /** The host whole-tick nanoseconds per row this run measured on the whitelisted classes it did
     * not take over; zero while the host-cost observation is off. The batch criterion is the paired
     * run, where the cost comes from the arm that runs every row on the host. */
    static double cMoveNanos() {
        double weighted = 0.0;
        long rows = 0L;
        for (int slot = 0; slot < TakeoverWhitelist.SLOTS; slot++) {
            long taken = TakeoverWhitelist.skipped(slot);
            if (taken == 0L) {
                continue;
            }
            double perRow = PrtsEntityRescope.hostNanosPerRowNotWidened(TickModels.classOf(slot));
            if (perRow <= 0.0) {
                continue;
            }
            weighted += perRow * (double) taken;
            rows += taken;
        }
        return rows == 0L ? 0.0 : weighted / (double) rows;
    }

    /** What one carried row is worth: the host cost it removes minus the host cost it adds. */
    static double netPerUnit() {
        return cMoveNanos() - kCarriedNanos();
    }

    /** The same net per row a host entry saw, with the fixed cost of the tick charged to that
     * denominator: {@code coverage * c_move - added / entries}. */
    static double netPerEntryRow() {
        long candidates = CANDIDATES.sum();
        if (candidates == 0L) {
            return 0.0;
        }
        long added = PLAN_NANOS.sum() + ENTRY_NANOS.sum() + COMMIT_NANOS.sum();
        return coverage() * cMoveNanos() - (double) added / (double) candidates;
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
        WITHDRAWN.reset();
        CLAIMED.reset();
        CLAIM_EXECUTED.reset();
        CLAIM_REVOKED.reset();
        NEVER_CLAIMED.reset();
        WORLD_NOT_LIVE.reset();
        COMMIT_NANOS.reset();
        COMMITTED_ROWS.reset();
        DISARMS.reset();
        ENTRY_FAULTS.reset();
        WORKER_FAULTS.reset();
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
        SEGMENT_FRAMES.reset();
        OWNED_ROWS.reset();
        OBSERVED_ROWS.reset();
        OBSERVED_ENTRIES.reset();
        SET_CONFLICTS.reset();
        UNBOOKED_COMMITS.reset();
        ORDINAL_VIOLATIONS.reset();
        LAYER_ENTITY_REJECTED.reset();
        LAYER_WORLD_REJECTED.reset();
        LAYER_SEGMENT_REJECTED.reset();
        BROKEN_FRAMES.reset();
        BROKEN_SEGMENT_LEDGERS.reset();
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
        SEGMENTS.reset();
        segmentFrameRows = 0;
        tickSegments = 0L;
        tickOwned = 0L;
        tickObserved = 0L;
        tickSetConflicts = 0L;
        tickUnbookedCommits = 0L;
        tickOrdinalBroken = 0L;
        tickEntityRejected = 0L;
        tickWorldRejected = 0L;
        tickSegmentRejected = 0L;
        tickBrokenFrames = 0L;
        tickBrokenLedgers = 0L;
        WORLD_TOTALS.clear();
        TakeoverWhitelist.reset();
    }

    /** Re-arms the latch; only the unit tests use it, a failed process stays disarmed. */
    static void rearmForTests() {
        disarmed = false;
        disarmReason = "";
    }

    /**
     * Writes the segment frames of the observed ticks when the harness declared a trace path: one
     * line per world slice with its row sets, its three generations and the verdict of the frame
     * checks. Nothing is written while no path was declared.
     */
    static void dumpSegments() {
        if (TRACE_PATH == null || TRACE_PATH.isEmpty() || segmentFrameRows == 0) {
            return;
        }
        Path path = Path.of(TRACE_PATH + ".segments.tsv");
        try (Writer writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8)) {
            writer.write("# entity-segment frames: " + segmentFrameRows + " frame(s), "
                + Math.min(segmentFrameRows, SEGMENT_RING) + " kept, set_conflicts="
                + SET_CONFLICTS.sum() + " broken_ledgers=" + BROKEN_SEGMENT_LEDGERS.sum() + "\n");
            writer.write("# columns: tick world segment_epoch owned observed committed fellback"
                + " not_entered observed_entries set_conflicts unbooked_commits ordinal_broken"
                + " entity_rejected world_rejected segment_rejected ledger_ok\n");
            int from = Math.max(0, segmentFrameRows - SEGMENT_RING);
            for (int at = from; at < segmentFrameRows; at++) {
                SegmentFrames.Frame frame = segmentRing[at % SEGMENT_RING];
                writer.write(Long.toString(frame.tickIndex()));
                writer.write(' ' + frame.worldId());
                writer.write(' ' + Long.toString(frame.segmentEpoch()));
                writer.write(' ' + Integer.toString(frame.ownedRows()));
                writer.write(' ' + Integer.toString(frame.observedRows()));
                writer.write(' ' + Integer.toString(frame.committed()));
                writer.write(' ' + Integer.toString(frame.fellBack()));
                writer.write(' ' + Integer.toString(frame.notEntered()));
                writer.write(' ' + Integer.toString(frame.observedEntries()));
                writer.write(' ' + Integer.toString(frame.setConflicts()));
                writer.write(' ' + Integer.toString(frame.unbookedCommits()));
                writer.write(' ' + Integer.toString(frame.ordinalBroken()));
                writer.write(' ' + Integer.toString(frame.entityRejected()));
                writer.write(' ' + Integer.toString(frame.worldRejected()));
                writer.write(' ' + Integer.toString(frame.segmentRejected()));
                writer.write(' ' + (frame.ledgerOk() ? "1" : "0"));
                writer.write('\n');
            }
        } catch (IOException failed) {
            LOGGER.warn("[PRTS] entity-segment: cannot write the frames to {}: {}",
                path, failed.toString());
        }
    }

    /** One evidence line for the segment face: the row sets, the frame checks and the three
     * generations the entries compared. Every count is written per frame, none is derived from what
     * a commit landed. */
    public static String segmentLine() {
        return "[PRTS] entity-segment: worlds=" + WORLD_TOTALS.size()
            + " frames=" + SEGMENT_FRAMES.sum()
            + " owned_rows=" + OWNED_ROWS.sum()
            + " observed_rows=" + OBSERVED_ROWS.sum()
            + " observed_entries=" + OBSERVED_ENTRIES.sum()
            + " set_conflicts=" + SET_CONFLICTS.sum()
            + " unbooked_commits=" + UNBOOKED_COMMITS.sum()
            + " ordinal_violations=" + ORDINAL_VIOLATIONS.sum()
            + " layer_entity_rejected=" + LAYER_ENTITY_REJECTED.sum()
            + " layer_world_rejected=" + LAYER_WORLD_REJECTED.sum()
            + " layer_segment_rejected=" + LAYER_SEGMENT_REJECTED.sum()
            + " broken_frames=" + BROKEN_FRAMES.sum()
            + " broken_segment_ledgers=" + BROKEN_SEGMENT_LEDGERS.sum()
            + " sets_apart=" + (SET_CONFLICTS.sum() == 0L ? "ok" : "broken");
    }

    /** How many segment frames this process closed so far; zero while no fixture is live. */
    public static long framesClosed() {
        return segmentFrameRows;
    }

    /** How many of the most recent frames the ring keeps. */
    public static int framesKept() {
        return SEGMENT_RING;
    }

    /** One closed segment frame by its index, counting from the first frame of the process. */
    public static SegmentFrames.Frame frameAt(long index) {
        return segmentRing[(int) (index % SEGMENT_RING)];
    }

    /** Books one closed frame into the totals of its world. */
    private static void noteWorldTotals(SegmentWork.Frame frame) {
        long[] totals = WORLD_TOTALS.computeIfAbsent(frame.worldId(), key -> new long[6]);
        totals[0]++;
        totals[1] += frame.ownedRows();
        totals[2] += frame.committed();
        totals[3] += frame.fellBack();
        totals[4] += frame.notEntered();
        if (!frame.ledgerOk()) {
            totals[5]++;
        }
    }

    /** One line per world: the frames it closed and what their ownership rows ended in. A run that
     * ticks two worlds in one tick closes one frame per world per tick, and both are listed here. */
    public static String worldLine() {
        StringBuilder builder = new StringBuilder("[PRTS] entity-segment-world:");
        if (WORLD_TOTALS.isEmpty()) {
            builder.append(" none");
        }
        for (java.util.Map.Entry<String, long[]> entry : WORLD_TOTALS.entrySet()) {
            long[] totals = entry.getValue();
            builder.append(' ').append(entry.getKey()).append('=')
                .append(totals[0]).append(',').append(totals[1]).append(',')
                .append(totals[2]).append(',').append(totals[3]).append(',')
                .append(totals[4]).append(',').append(totals[5]);
        }
        return builder.toString();
    }

    /** One closed frame as the frame shape a reader outside this package books. */
    private static SegmentFrames.Frame asFrame(SegmentWork.Frame frame) {
        return new SegmentFrames.Frame(frame.tickIndex(), frame.worldId(), frame.segmentEpoch(),
            frame.ownedRows(), frame.observedRows(), frame.committed(), frame.fellBack(),
            frame.notEntered(), frame.observedEntries(), frame.setConflicts(),
            frame.unbookedCommits(), frame.ordinalBroken(), frame.entityRejected(),
            frame.worldRejected(), frame.segmentRejected(), frame.ledgerOk());
    }

    /** Stops the pool; declared so a server that stops does not leave worker threads behind. */
    public static void shutdown() {
        dumpTimeline();
        dumpSegments();
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
            writer.write("# columns: " + String.join(" ", TIMELINE_COLUMNS) + "\n");
            int from = Math.max(0, timelineRows - TIMELINE_TICKS);
            for (int at = from; at < timelineRows; at++) {
                long[] values = timelineRow(at % TIMELINE_TICKS);
                for (int column = 0; column < values.length; column++) {
                    if (column > 0) {
                        writer.write(' ');
                    }
                    writer.write(Long.toString(values[column]));
                }
                writer.write('\n');
            }
        } catch (IOException failed) {
            LOGGER.warn("[PRTS] entity-ownership: cannot write the timeline to {}: {}",
                TRACE_PATH, failed.toString());
        }
    }

    /** The values of one timeline row, in the order {@link #TIMELINE_COLUMNS} names them. */
    static long[] timelineRow(int slot) {
        return new long[]{TL_TICK[slot], TL_PLAN_SEQ[slot], TL_FIRST_SEQ[slot], TL_LAST_SEQ[slot],
            TL_CLOSE_SEQ[slot], TL_ISSUED[slot], TL_CLAIMED[slot], TL_ENTRIES[slot], TL_SKIPPED[slot],
            TL_EXECUTED[slot], TL_ELIGIBLE[slot], TL_NOT_ENTERED[slot], TL_CLAIM_EXECUTED[slot],
            TL_CLAIM_REVOKED[slot], TL_NEVER_CLAIMED[slot], TL_LEDGER_OK[slot], TL_PROBE_CHECKED[slot],
            TL_PROBE_MATCHED[slot], TL_PROBE_BROKEN[slot], TL_ENTRY_NANOS[slot], TL_SEGMENTS[slot],
            TL_OWNED[slot], TL_OBSERVED[slot], TL_SET_CONFLICTS[slot], TL_UNBOOKED_COMMITS[slot],
            TL_ORDINAL_BROKEN[slot], TL_ENTITY_REJECTED[slot], TL_WORLD_REJECTED[slot],
            TL_SEGMENT_REJECTED[slot], TL_BROKEN_FRAMES[slot]};
    }

    /** Every claimed row ends in exactly one outcome: skipped, handed back for lack of an answer,
     * withdrawn because the row no longer matched, or recycled without reaching an entry. */
    static boolean claimLedgerOk() {
        return CLAIMED.sum() == HOST_SKIPPED.sum() + CLAIM_EXECUTED.sum() + CLAIM_REVOKED.sum()
            + NOT_ENTERED.sum();
    }

    /** Every issued token is either spent on a skip, handed back at an entry, or recycled. */
    static boolean closureOk() {
        return ISSUED.sum() == HOST_SKIPPED.sum() + ELIGIBLE.sum() + NOT_ENTERED.sum();
    }

    /** The withdrawals are the handed-back claims and the recycled ones, and nothing else. */
    static boolean withdrawalOk() {
        return WITHDRAWN.sum() == ELIGIBLE.sum() + NOT_ENTERED.sum()
            && ELIGIBLE.sum() == CLAIM_EXECUTED.sum() + CLAIM_REVOKED.sum();
    }

    /**
     * The partition of every row a host entry saw: such a row either ran its original tick on the
     * host path or was skipped, and a row that never reached an entry belongs to no partition.
     */
    static boolean partitionOk() {
        return CANDIDATES.sum() == HOST_SKIPPED.sum() + HOST_EXECUTED.sum();
    }

    /** Why a row a host entry reached ran on the host: no claim, a claim without an answer, or a
     * claim that no longer matched its row. */
    static boolean hostPathOk() {
        return HOST_EXECUTED.sum() == CLAIM_EXECUTED.sum() + CLAIM_REVOKED.sum()
            + NEVER_CLAIMED.sum();
    }

    /** Rows actually taken over, over the rows the host entry saw. */
    static double coverage() {
        long candidates = CANDIDATES.sum();
        return candidates == 0L ? 0.0 : (double) HOST_SKIPPED.sum() / (double) candidates;
    }

    static String format(double value) {
        return String.format(Locale.ROOT, "%.6f", value);
    }

    /**
     * Hands a claim back in front of the host: the token is withdrawn, the row is counted as one the
     * host runs, and the withdrawal is booked either as an answer that never arrived or as a token
     * that no longer matched the row it was issued for.
     */
    private static void withdraw(OwnershipLease lease, int index, boolean mismatched) {
        lease.revoke(index);
        ELIGIBLE.increment();
        FALLBACK.increment();
        WITHDRAWN.increment();
        HOST_EXECUTED.increment();
        if (mismatched) {
            CLAIM_REVOKED.increment();
        } else {
            CLAIM_EXECUTED.increment();
        }
        lastWithdrawn = true;
    }

    private static void checkInvariants() {
        boolean holds = claimLedgerOk() && closureOk() && withdrawalOk() && partitionOk()
            && hostPathOk() && TOKEN_LEAK.sum() == 0L;
        if (!holds && INVARIANT_VIOLATIONS.sum() == 0L) {
            LOGGER.warn("[PRTS] entity-ownership: the ownership accounts do not close: {}",
                evidenceLine());
        }
        if (!holds) {
            INVARIANT_VIOLATIONS.increment();
            disarm("accounts");
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

    /** Books a row as observed, with the world verdict the predicate reached for it, if any. */
    private static void observe(SegmentWork segment, int entityId, long entityEpoch, int reason) {
        int ordinal = segment.observe(entityId, entityEpoch);
        if (reason == OwnershipEligibility.MODEL || reason == OwnershipEligibility.NEIGHBOURS) {
            segment.readSet().freezeNeighbourVerdict(ordinal,
                reason == OwnershipEligibility.MODEL);
        }
    }

    /** The identity of one world, read once per segment and frozen with its other inputs. */
    private static String worldIdOf(ServerLevel level) {
        return level.dimension().location().toString();
    }

    /** The generation of one world, read once per segment; the write guard tracks the same number. */
    private static long worldEpochOf(ServerLevel level) {
        return KernelModule.instance().guard().worldEpochs().epochOf(worldIdOf(level));
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

    static SegmentWork beginSegment(long tick, String worldId, long worldEpoch, long segmentEpoch) {
        return new SegmentWork(tick, worldId, worldEpoch, null, segmentEpoch);
    }

    static void install(OwnershipLease lease, SegmentWork... segments) {
        ISSUED.add(lease.rows());
        CLAIMED.add(lease.rows());
        for (int index = 0; index < lease.rows(); index++) {
            OwnershipLease.EntityCapability capability = lease.capability(index);
            noteEntityEpoch(capability.entityId(), capability.entityEpoch());
        }
        SEGMENTS.reset();
        for (SegmentWork segment : segments) {
            if (segment != null) {
                SEGMENTS.adopt(segment);
            }
        }
        current = lease;
    }

    /** Records the kernel generation of one row; the plan point writes it, the entry reads it. */
    static void noteEntityEpoch(int entityId, long epoch) {
        ENTITY_EPOCH.put(entityId, epoch);
    }

    static OwnershipLease installed() {
        return current;
    }

    static void closeInstalled() {
        closeLease();
    }
}
