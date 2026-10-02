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
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchSettings;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchSnapshot;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchWriteBack;
import io.izzel.arclight.common.prts.kernel.dispatch.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.dispatch.EntityIntegrator;
import io.izzel.arclight.common.prts.kernel.dispatch.MergeSegment;
import io.izzel.arclight.common.prts.kernel.dispatch.TaskLedger;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkPlan;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.meter.MeterWindow;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfRow;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.kernel.shares.ConservationCheck;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The module entry: one instance per process, driven once per tick by the platform listener.
 *
 * <p>The driver reclaims expired tokens, plans the time budget, turns overruns into records that are
 * never executed, checks the accounting closure and publishes the metering window when its length
 * has passed. It occupies no world write path and no lock, and the platform subscriber exists only
 * while the kernel category is on.</p>
 *
 * <p>Reclaiming an expired owner token belongs to the write-right lifecycle, not to the observation
 * of it: it runs on every driven tick whether or not the self timers are on, so a token can never
 * outlive its expiry just because an operator turned a metering switch off.</p>
 *
 * <p>The one world write the module can perform is the commit segment: it walks the intent channel
 * and applies what a routed write was deferred into. Its switch is off by default, and while it is
 * off the module only observes - nothing is consumed from the channel and no deferred write lands.
 * The walk belongs to the thread that drives the tick, and the segment carries that thread, so a
 * worker that reaches the segment is refused instead of draining the channel.</p>
 *
 * <p>The only clock read here is the one that measures the driver itself, so the cost of the
 * observation can be published as a row of its own. Planning reads the tick index and the metered
 * work, never the clock.</p>
 */
public final class KernelModule {

    /** The control plane of one tick: the five values the next tick would plan with. */
    public record ControlFrame(long tickIndex, double minMarginMs, long overrunHits,
                               long waitBoundHits, double reserveUsedMs, double reserveRemainingMs,
                               String degradeState) {

        /** @return a frame for a tick the driver did not reach */
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
    private final Map<String, Long> dispatchWorldEpochs = new HashMap<>();
    private final Set<String> dispatchLiveWorlds = new HashSet<>();

    private long tickIndex;
    private long dispatchWorldEpochSeq;
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

    /** @return the module of this process */
    public static KernelModule instance() {
        return INSTANCE;
    }

    /**
     * Advances the kernel by one tick.
     *
     * @param worldIds the worlds the tick carries, in the order the platform lists them
     */
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

    /**
     * Installs the write path watcher.
     *
     * <p>Called while the category is on. The seam costs the write paths one volatile read when the
     * kernel is off, and nothing is installed at all then.</p>
     */
    public void installWritePathTap() {
        PrtsWorldWriteTaps.install(guard);
    }

    /** Removes the write path watcher. */
    public void removeWritePathTap() {
        PrtsWorldWriteTaps.install(null);
    }

    /**
     * Installs the wait observation watcher.
     *
     * <p>Called while the category is on. The call sites cost one volatile read each while nothing
     * is installed, and nothing is installed at all then.</p>
     */
    public void installWaitSiteTap() {
        syncWaitSiteTap(KernelSettings.waitRegistry());
    }

    /** Removes the wait observation watcher. */
    public void removeWaitSiteTap() {
        syncWaitSiteTap(false);
    }

    /**
     * Puts the wait observation seam back to the watcher the configuration asks for.
     *
     * <p>A tool that borrows the seam - the self check is one - hands it back here instead of leaving
     * the process with whatever it installed last: the module forgets what it believed was installed
     * and installs the configured watcher again, so a borrowed seam cannot silently end the
     * observation of the twenty real call sites.</p>
     */
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

    /**
     * Drives the first parallel domain: merge the previous tick's pass at this tick's entry, then
     * freeze and dispatch this tick's plan.
     *
     * <p>The tick boundary is the hard deadline. The previous plan is merged first - with the
     * configured grace, which is zero by default - and whatever did not answer by then is cancelled
     * and redone on this thread in the frozen order. The new plan is frozen from a read-only entity
     * view and offered to the pool; planning itself reads no clock.</p>
     */
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
            List<EntityCandidateView> views = DispatchSnapshot.capture(this::dispatchWorldEpoch);
            DispatchWriteBack.noteSnapshot(dispatchReadings, RUNTIME_WORLD, "snapshot",
                System.nanoTime() - snapshotStart);
            forgetAbsentDispatchWorlds(views);
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
                }
            }
        } else if (dispatchPool != null) {
            shutdownDispatch();
        }
    }

    /**
     * Answers whether the commit segment may walk this tick.
     *
     * <p>The switch that walks the channel is the one that already existed, read through the line
     * the takeover tier adds to it: a write-back the merge handed over has to land, or the values a
     * worker computed would sit in the channel forever while the frame hash said they were committed.
     * The compute-only tier - the default - hands nothing over, so it does not make the segment walk;
     * with the dispatcher off the expression is exactly the old switch.</p>
     *
     * @return {@code true} when a deferred write may be applied this tick
     */
    private static boolean commitWanted() {
        return KernelSettings.commitIntents() || KernelSettings.dispatchTakeover();
    }

    /** @return the entity row of the self timer, in milliseconds */
    private double selfEntityMs() {
        for (SelfRow row : window().rows()) {
            if (row.selfClass() == SelfClass.ENTITY) {
                return row.totalMs();
            }
        }
        return 0.0;
    }

    private synchronized long dispatchWorldEpoch(String worldId) {
        if (!dispatchLiveWorlds.contains(worldId)) {
            dispatchWorldEpochSeq++;
            dispatchWorldEpochs.put(worldId, dispatchWorldEpochSeq);
            dispatchLiveWorlds.add(worldId);
        }
        return dispatchWorldEpochs.getOrDefault(worldId, dispatchWorldEpochSeq);
    }

    private synchronized void forgetAbsentDispatchWorlds(List<EntityCandidateView> views) {
        Set<String> seen = new HashSet<>();
        for (EntityCandidateView view : views) {
            seen.add(view.worldId());
        }
        dispatchLiveWorlds.retainAll(seen);
    }

    /**
     * Stops the pool in the ordered shutdown steps and gives the arena segments back.
     *
     * <p>The steps are the ones world unload uses as well: stop offering work, wait for what is in
     * flight, end the threads inside a bounded wait, and only then return the slots. A worker that
     * answers after its handle was cancelled is refused by the handle and counted as late, never
     * silently accepted.</p>
     */
    private void shutdownDispatch() {
        WorkerPool current = dispatchPool;
        dispatchPool = null;
        pendingDispatch = null;
        if (current != null) {
            current.shutdown(true, true, 250L);
        }
        arena.releaseAll();
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

    /**
     * Returns the window to publish.
     *
     * @return the last completed window, or a live view when none has completed yet
     */
    public MeterWindow window() {
        if (lastWindow != null) {
            return lastWindow;
        }
        long covered = started ? tickIndex - windowStartTick : 0L;
        return SelfTimers.snapshot(tickIndex, (int) Math.min(Integer.MAX_VALUE, covered),
            tickIndex <= KernelSettings.selfWarmupTicks());
    }

    /** @return the tick index the module reached */
    public long tickIndex() {
        return tickIndex;
    }

    /** @return whether a tick was driven at all */
    public boolean started() {
        return started;
    }

    /** @return the owner registry */
    public OwnerRegistry owners() {
        return owners;
    }

    /** @return the intent queue */
    public IntentQueue intents() {
        return intents;
    }

    /** @return the commit segment that walks the intent queue */
    public CommitSegment commitSegment() {
        return commitSegment;
    }

    /** @return the write ledger */
    public WriteLedger ledger() {
        return ledger;
    }

    /** @return the write decision point */
    public WriteAuthority authority() {
        return authority;
    }

    /** @return the guard that reaches the decision point from the real write paths */
    public WorldWriteGuard guard() {
        return guard;
    }

    /** @return the wait point registry */
    public WaitPointRegistry waitPoints() {
        return waitPoints;
    }

    /** @return the observer that turns the waits of the real call sites into observations */
    public WaitSiteObserver waitSites() {
        return waitSites;
    }

    /** @return the share planner */
    public SharePlanner shares() {
        return shares;
    }

    /** @return the conservation of the last planned table */
    public ConservationCheck conservation() {
        return lastConservation;
    }

    /** @return the control plane of the last tick */
    public ControlFrame control() {
        return control;
    }

    /** Clears the live counters. Used by the readout reset and by tests, never by the scheduler. */
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