/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass.DispatchSettings;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchSnapshot;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchWriteBack;
import io.izzel.arclight.common.prts.kernel.dispatch.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.dispatch.EntityIntegrator;
import io.izzel.arclight.common.prts.kernel.dispatch.MergeSegment;
import io.izzel.arclight.common.prts.kernel.dispatch.TaskLedger;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkPlan;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers.MeterWindow;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers.SelfRow;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner.ConservationCheck;
import io.izzel.arclight.common.prts.kernel.shares.OverrunRecord;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.sites.IntentPayloadDirectory;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;
import io.izzel.arclight.common.prts.kernel.waitpoints.observe.WaitSiteObserver;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The driver reclaims expired tokens, plans the time budget, turns overruns into records that are
 * never executed, checks the accounting closure and publishes the metering window when its length
 * has passed. */
public final class KernelModule {

    /** The control plane of one tick: the five values the next tick would plan with. */
    public record ControlFrame(long tickIndex, double minMarginMs, long overrunHits,
                               long waitBoundHits, double reserveUsedMs, double reserveRemainingMs,
                               String degradeState) {

        public static ControlFrame empty() {
            return new ControlFrame(0L, 0.0, 0L, 0L, 0.0, 0.0, "none");
        }
    }

    private static final KernelModule INSTANCE = new KernelModule();
    private static final String RUNTIME_WORLD = "";
    private static final String SERVER_SITE = "host:server-thread";
    private static final String DISPATCH_DOMAIN = "entity";
    private static final String DISPATCH_THREAD_PREFIX = "prts-worker-";
    private static final long DISPATCH_EVIDENCE_TICKS = 400L;
    private static final long DISPATCH_SHUTDOWN_WAIT_MS = 250L;
    private static final Logger DISPATCH_EVIDENCE = LogManager.getLogger("PRTS");

    private final OwnerRegistry owners = new OwnerRegistry();
    private final IntentQueue intents = new IntentQueue(KernelSettings::intentQueueCap,
        KernelSettings::retryBudget);
    private final CommitSegment commitSegment = new CommitSegment(intents,
        KernelModule::commitWanted, KernelSettings::commitBudget);
    private final WriteLedger ledger = new WriteLedger();
    private final WritePathCounters pathCounters = new WritePathCounters();
    private final IntentPayloadDirectory payloads = new IntentPayloadDirectory();
    private final WriteAuthority authority = new WriteAuthority(owners, intents, ledger,
        KernelSettings::enforceUnregisteredWrites, KernelSettings::retryBudget);
    private final WorldWriteGuard guard = new WorldWriteGuard(pathCounters, authority, intents,
        payloads, ledger);
    private final WaitPointRegistry waitPoints = new WaitPointRegistry(KernelSettings::waitBoundMs);
    private final WaitSiteObserver waitSites = new WaitSiteObserver(waitPoints, this::tickIndex,
        KernelSettings::waitBoundMs);
    private final SharePlanner shares = new SharePlanner();

    private final ArenaLedger arena = new ArenaLedger();
    private final DispatchReadings dispatchReadings = new DispatchReadings();
    private final TaskLedger dispatchLedger = new TaskLedger(0L);
    private final DiffProbe diffProbe = new DiffProbe();
    private final MergeSegment mergeSegment = new MergeSegment();
    private final DispatchWriteBack dispatchWriteBack = new DispatchWriteBack(intents, payloads::bind,
        payloads::drop, guard.worldEpochs()::epochOf, dispatchReadings,
        KernelSettings::dispatchTakeover);
    private long tickIndex;
    private long dispatchTaskSeq;
    private long lastDispatchEvidenceTick;
    private WorkerPool dispatchPool;
    private DispatchPass pendingDispatch;
    private MergeSegment.Frame lastDispatchFrame = MergeSegment.Frame.empty();
    private boolean waitSiteTapInstalled;
    private long windowStartTick;
    private boolean started;
    private MeterWindow lastWindow;
    private ConservationCheck lastConservation = ConservationCheck.holds();
    private ControlFrame control = ControlFrame.empty();

    private KernelModule() {
        intents.bindPayload(guard);
    }

    public static KernelModule instance() {
        return INSTANCE;
    }

    /** Advances the kernel by one tick. */
    public void serverTick(List<String> worldIds) {
        if (!KernelSettings.enabled()) {
            guard.refresh(false, false, false, tickIndex);
            syncWaitSiteTap(false);
            shutdownDispatch();
            return;
        }
        long startedAt = System.nanoTime();
        tickIndex++;
        if (!started) {
            started = true;
            windowStartTick = tickIndex;
        }
        commitSegment.bindOwnerThread(Thread.currentThread());
        if (!guard.serverThreadBound()) {
            guard.bindServerThread(Thread.currentThread(), SERVER_SITE);
        }
        guard.noteLiveWorlds(worldIds);
        guard.refresh(KernelSettings.writePathGuard(),
            KernelSettings.enforceUnregisteredWrites(),
            KernelSettings.routeUnregisteredWrites(), tickIndex);
        syncWaitSiteTap(KernelSettings.waitRegistry());
        commitSegment.run(tickIndex);
        owners.reclaimExpired(tickIndex);
        if (KernelSettings.shareTable()) {
            planBudget(worldIds);
        } else {
            SelfTimers.discardTickTotals();
        }
        if (KernelSettings.selfTimers()) {
            publishWindowIfDue();
        }
        ledger.verifyClosure();
        driveDispatch();
        if (KernelSettings.selfTimers()) {
            SelfTimers.note(SelfClass.OBSERVE, RUNTIME_WORLD, "runtime",
                System.nanoTime() - startedAt);
        }
    }

    /** Installs the write path watcher. Called while the category is on. */
    public void installWritePathTap() {
        PrtsWorldWriteTaps.install(guard);
    }

    /** Removes the write path watcher. */
    public void removeWritePathTap() {
        PrtsWorldWriteTaps.install(null);
    }

    /** Installs the wait observation watcher. Called while the category is on. */
    public void installWaitSiteTap() {
        syncWaitSiteTap(KernelSettings.waitRegistry());
    }

    /** Removes the wait observation watcher. */
    public void removeWaitSiteTap() {
        syncWaitSiteTap(false);
    }

    /** A tool that borrows the seam - the self check is one - hands it back here instead of
     * leaving the process with whatever it installed last: the module forgets what it believed was
     * installed and installs the configured watcher again, so a borrowed seam cannot silently end
     * the observation of the twenty real call sites. */
    public synchronized void resyncWaitSiteTap() {
        // What is installed now is read first: a tool that handed the seam back leaves the module
        // agreeing with it instead of installing its own watcher over a seam somebody else owns.
        waitSiteTapInstalled = PrtsWaitSites.watcher() == waitSites;
        syncWaitSiteTap(KernelSettings.enabled() && KernelSettings.waitRegistry());
    }

    private void syncWaitSiteTap(boolean wanted) {
        if (waitSiteTapInstalled == wanted) {
            return;
        }
        waitSiteTapInstalled = wanted;
        PrtsWaitSites.install(wanted ? waitSites : null);
    }

    private void driveDispatch() {
        boolean parallel = KernelSettings.dispatchParallel();
        // The line is exported before the merge of this tick and after the commit of this tick, so
        // the orders the commit consumed account for every task the plan froze: a task is counted
        // when its plan is built and its intent is consumed at the next commit boundary.
        if (tickIndex - lastDispatchEvidenceTick >= DISPATCH_EVIDENCE_TICKS) {
            lastDispatchEvidenceTick = tickIndex;
            DispatchReadings.Window window = new DispatchReadings.Window(
                dispatchPool == null ? 0 : dispatchPool.alive(), lastDispatchFrame.closureOk(),
                commitSegment.cursor(), intents.depth(), intents.orderViolationCount(),
                selfEntityMs());
            DISPATCH_EVIDENCE.info(dispatchReadings.evidenceLine(arena, window));
            DISPATCH_EVIDENCE.info(dispatchWriteBack.sampleLine());
        }
        if (parallel) {
            DispatchSettings.Policy policy = DispatchSettings.resolve();
            if (pendingDispatch != null) {
                // The merge closes the window of the pass it reads; the epoch of the ledger only
                // moves here, so a pass that is closed without a merge must move it itself.
                long grace = policy.deadlineGraceMs();
                long deadline = System.nanoTime() + grace * 1_000_000L;
                lastDispatchFrame = mergeSegment.merge(pendingDispatch, deadline, arena,
                    dispatchReadings, diffProbe, HashWhitelist.bitexact(), DISPATCH_DOMAIN,
                    dispatchWriteBack);
                pendingDispatch = null;
                if (lastDispatchFrame == null) {
                    lastDispatchFrame = MergeSegment.Frame.empty();
                }
            }
            long snapshotStart = System.nanoTime();
            // One epoch source for the freeze and the commit: the task carries the generation the
            // write-right guard tracks, so the write-back can freeze it and the commit can compare
            // the very same number.
            List<EntityCandidateView> views =
                DispatchSnapshot.capture(guard.worldEpochs()::epochOf);
            DispatchWriteBack.noteSnapshot(dispatchReadings, RUNTIME_WORLD, "snapshot",
                System.nanoTime() - snapshotStart);
            WorkPlan plan = WorkPlan.freeze(tickIndex, dispatchLedger.epoch(), views,
                policy.batchChunks(), dispatchTaskSeq + 1L);
            dispatchTaskSeq += plan.taskCount();
            dispatchReadings.noteTasks(plan.taskCount());
            if (!plan.empty()) {
                if (dispatchPool == null) {
                    try {
                        dispatchPool = WorkerPool.open(new WorkerPool.Spec(policy.workerCount(),
                            DISPATCH_THREAD_PREFIX, Thread.NORM_PRIORITY, policy.queueCap(),
                            policy.batchChunks()), policy.retryBudget(), dispatchReadings, arena);
                    } catch (Throwable t) {
                        dispatchReadings.notePoolOpenFailed();
                        dispatchPool = null;
                    }
                }
                if (dispatchPool != null) {
                    pendingDispatch = DispatchPass.dispatch(plan, dispatchPool,
                        EntityIntegrator.INSTANCE, arena, dispatchReadings, dispatchLedger);
                } else {
                    // No pool this tick: the plan still receives one terminal outcome per task, so
                    // the accounting of the tick closes and the merge redos the batches on the tick
                    // thread in the frozen order.
                    pendingDispatch = DispatchPass.serialFallback(plan, dispatchReadings,
                        dispatchLedger);
                }
            }
        } else if (dispatchPool != null || pendingDispatch != null) {
            shutdownDispatch();
        }
    }

    private static boolean commitWanted() {
        return KernelSettings.commitIntents() || KernelSettings.dispatchTakeover();
    }

    private double selfEntityMs() {
        for (SelfRow row : window().rows()) {
            if (row.selfClass() == SelfClass.ENTITY) {
                return row.totalMs();
            }
        }
        return 0.0;
    }

    private void shutdownDispatch() {
        WorkerPool current = dispatchPool;
        dispatchPool = null;
        DispatchPass pending = pendingDispatch;
        pendingDispatch = null;
        if (pending != null) {
            pending.abort(RejectCode.TICK_BUDGET_EXHAUSTED.text());
        }
        boolean confirmed = true;
        if (current != null) {
            WorkerPool.ShutdownReport report =
                current.shutdown(true, true, DISPATCH_SHUTDOWN_WAIT_MS);
            confirmed = report.terminated() && report.remainingInFlight() == 0;
            dispatchReadings.noteShutdown(report.remainingInFlight(), report.terminated());
        }
        if (confirmed) {
            arena.releaseAll();
        } else {
            // A worker that ignored the deadline may still hold a lease; the arena is quarantined so
            // its slots can never be handed to the next pass.
            arena.quarantineAll();
        }
    }

    private void planBudget(List<String> worldIds) {
        Map<String, EnumMap<ShareClass, Double>> used = KernelSettings.selfTimers()
            ? SharePlanner.usedFromTickTotals(SelfTimers.consumeTickTotals())
            : Map.of();
        ShareTable table = shares.plan(worldIds == null ? List.of() : worldIds, tickIndex, used);
        lastConservation = shares.checkConservation(table);
        double minMargin = Double.POSITIVE_INFINITY;
        long overruns = 0L;
        for (ShareTable.ShareRow row : table.rows()) {
            minMargin = Math.min(minMargin, row.marginMs());
            if (!row.overrun()) {
                continue;
            }
            OverrunRecord record = shares.record(row, "runtime", tickIndex);
            shares.recordWouldDegrade(record);
            overruns++;
        }
        if (minMargin == Double.POSITIVE_INFINITY) {
            minMargin = 0.0;
        }
        control = new ControlFrame(tickIndex, minMargin, overruns, waitPoints.waitOverrunCount(),
            reserveUsedMs(), shares.lastTable().reserve().remainingMs(), "none");
    }

    private void publishWindowIfDue() {
        long length = KernelSettings.selfWindowTicks();
        long covered = tickIndex - windowStartTick;
        if (covered >= length) {
            lastWindow = SelfTimers.consume(tickIndex, (int) Math.min(Integer.MAX_VALUE, covered),
                tickIndex <= KernelSettings.selfWarmupTicks());
            windowStartTick = tickIndex;
        }
    }

    private double reserveUsedMs() {
        double used = 0.0;
        for (double value : shares.reserveUsedByPurpose().values()) {
            used += value;
        }
        return used;
    }

    /** Returns the window to publish. */
    public DispatchReadings dispatchReadings() {
        return dispatchReadings;
    }

    TaskLedger dispatchLedger() {
        return dispatchLedger;
    }

    MergeSegment.Frame lastDispatchFrame() {
        return lastDispatchFrame;
    }

    ArenaLedger dispatchArena() {
        return arena;
    }

    /** Hands the module the pass the next merge must close; used by the switch test, not the
     * driver. */
    void stagePendingDispatch(DispatchPass pass) {
        this.pendingDispatch = pass;
    }

    public MeterWindow window() {
        if (lastWindow != null) {
            return lastWindow;
        }
        long covered = started ? tickIndex - windowStartTick : 0L;
        return SelfTimers.snapshot(tickIndex, (int) Math.min(Integer.MAX_VALUE, covered),
            tickIndex <= KernelSettings.selfWarmupTicks());
    }

    public long tickIndex() {
        return tickIndex;
    }

    public boolean started() {
        return started;
    }

    public OwnerRegistry owners() {
        return owners;
    }

    public IntentQueue intents() {
        return intents;
    }

    public CommitSegment commitSegment() {
        return commitSegment;
    }

    public WriteLedger ledger() {
        return ledger;
    }

    public WriteAuthority authority() {
        return authority;
    }

    public WorldWriteGuard guard() {
        return guard;
    }

    public WaitPointRegistry waitPoints() {
        return waitPoints;
    }

    public WaitSiteObserver waitSites() {
        return waitSites;
    }

    public SharePlanner shares() {
        return shares;
    }

    public ConservationCheck conservation() {
        return lastConservation;
    }

    public ControlFrame control() {
        return control;
    }

    /** Clears the live counters. */
    public void resetReadings() {
        shutdownDispatch();
        dispatchReadings.reset();
        dispatchLedger.reset();
        mergeSegment.reset();
        diffProbe.reset();
        arena.reset();
        lastDispatchFrame = MergeSegment.Frame.empty();
        lastDispatchEvidenceTick = 0L;
        SelfTimers.resetAll();
        guard.resetReadings();
        waitSites.reset();
        commitSegment.reset();
        tickIndex = 0L;
        windowStartTick = 0L;
        started = false;
        lastWindow = null;
        lastConservation = ConservationCheck.holds();
        control = ControlFrame.empty();
    }
}
