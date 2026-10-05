/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.arena.ArenaPassthrough;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitRing;
import io.izzel.arclight.common.prts.kernel.exits.DualExits;
import io.izzel.arclight.common.prts.kernel.safety.SafetyNet;
import io.izzel.arclight.common.prts.kernel.safety.ZeroEffectDetector;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobIntake;
import io.izzel.arclight.common.prts.kernel.jobs.JobScheduler;
import io.izzel.arclight.common.prts.kernel.jobs.ShareMeterPoint;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanPlanner;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers.MeterWindow;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.kernel.shares.BudgetStateMachine;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner.ConservationCheck;
import io.izzel.arclight.common.prts.kernel.shares.OverrunRecord;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.ShareMeter;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.sites.IntentPayloadDirectory;
import io.izzel.arclight.common.prts.kernel.sites.ThreadOrigin;
import io.izzel.arclight.common.prts.kernel.sites.WritePath;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.waitpoints.SiteInventory.WaitClass;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitLadder;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitPointRegistry;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitProgress;
import io.izzel.arclight.common.prts.kernel.waitpoints.WaitSite;
import io.izzel.arclight.common.prts.kernel.dispatch.FaultInjection;
import io.izzel.arclight.common.prts.kernel.waitpoints.observe.WaitSiteObserver;
import io.izzel.arclight.common.prts.kernel.observe.ChunkDemandObserver;
import io.izzel.arclight.common.prts.kernel.observe.ChunkFlowObserver;
import io.izzel.arclight.common.prts.kernel.observe.LoadThreadObserver;
import io.izzel.arclight.common.prts.kernel.observe.PipelineRowObserver;
import io.izzel.arclight.common.prts.kernel.observe.SaveIdentityObserver;
import io.izzel.arclight.common.prts.kernel.observe.StallAttributionObserver;
import io.izzel.arclight.common.prts.kernel.meter.SelfCostTap;
import io.izzel.arclight.common.prts.support.PrtsSelfCosts;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** The driver reclaims expired tokens, plans the time budget, turns overruns into records that are
 * never executed, checks the accounting closure and publishes the metering window when its length
 * has passed. */
public final class KernelModule {

    /** The control plane of one tick: the five values the next tick would plan with. The state, the
     * rung it names and the tick that state was entered are read-only: nothing in this build plans
     * from them. */
    public record ControlFrame(long tickIndex, double minMarginMs, long overrunHits,
                               long waitBoundHits, double reserveUsedMs, double reserveRemainingMs,
                               String degradeState, long degradeEnteredTick) {

        public static ControlFrame empty() {
            return new ControlFrame(0L, 0.0, 0L, 0L, 0.0, 0.0, "none", 0L);
        }
    }

    /** The one seam a planning-period component may take a wall clock through, and the counter that
     * answers how often it did. Nothing in this build calls it: the planning period is a function of
     * the tick index, the plan sequence and the control frame the tick before published, and the
     * compiled classes of the planning period are scanned for any clock reference at build time.
     *
     * <p>The seam exists so the published count is a measurement and not a constant: a component that
     * obtained a clock through it would move the count, and the count is published next to the plan it
     * was taken for. */
    public static final class PlanClockSeam {

        private final java.util.concurrent.atomic.AtomicLong reads =
            new java.util.concurrent.atomic.AtomicLong();

        /** Hands out one wall-clock stamp and counts the read. */
        public long stampNanos() {
            reads.incrementAndGet();
            return System.nanoTime();
        }

        public long reads() {
            return reads.get();
        }

        public void reset() {
            reads.set(0L);
        }
    }

    private static final KernelModule INSTANCE = new KernelModule();
    private static final String RUNTIME_WORLD = "";
    private static final String SERVER_SITE = "host:server-thread";

    /** The domain one applied intent is logged under: the intent channel, which is not a world domain
     * and is ordered by its own frozen sequence. */
    private static final String INTENT_DOMAIN = "intent";

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
    private final WaitPointRegistry waitPoints = new WaitPointRegistry(KernelSettings::waitBoundMs,
        KernelSettings::refuseUnregisteredWaits);
    private final WaitSiteObserver waitSites = new WaitSiteObserver(waitPoints, this::tickIndex,
        KernelSettings::waitBoundMs);
    private final PipelineRowObserver pipelineRows = new PipelineRowObserver();
    private final LoadThreadObserver loadThread = new LoadThreadObserver();
    private final ChunkFlowObserver chunkFlow = new ChunkFlowObserver();
    private final SaveIdentityObserver saveIdentity = new SaveIdentityObserver();
    private final StallAttributionObserver attribution = new StallAttributionObserver();
    private final ChunkDemandObserver chunkDemand = new ChunkDemandObserver();
    private final SelfCostTap selfCosts = new SelfCostTap();
    private final SharePlanner shares = new SharePlanner();
    private final BudgetStateMachine budgetStates = new BudgetStateMachine();
    private final DegradeLadder ladder = new DegradeLadder(KernelSettings::degradeActions);
    private final WaitLadder waitLadder = new WaitLadder(KernelSettings::waitActions);

    private final ArenaLedger arena = new ArenaLedger();
    private final ArenaPassthrough passthrough = new ArenaPassthrough();
    private final DiffProbe arms = new DiffProbe();
    private final PlanClockSeam planClock = new PlanClockSeam();
    private final List<KernelDomain> domains = new ArrayList<>();
    private final JobIntake jobIntake = new JobIntake(KernelSettings::jobQueueCap);
    private final JobScheduler scheduler = new JobScheduler();
    private final ShareMeterPoint jobMeter = new ShareMeterPoint();
    private final TickPlanStore plans = new TickPlanStore(KernelSettings.planHistoryCap());
    private final SafetyNet safety = new SafetyNet(KernelSettings::safetyDegrade,
        KernelSettings::safetyCascadeCap);
    private final ZeroEffectDetector zeroEffect = new ZeroEffectDetector();
    private final DualExits exits = new DualExits(KernelSettings.exitWindowTicks());
    private final CommitLog commits = new CommitLog(KernelSettings::commitRingCap,
        this::resolveCommitOrder);
    private long tickIndex;
    private int waitCrossStreak;
    private boolean waitSiteTapInstalled;
    private boolean pipelineRowTapInstalled;
    private boolean loadProbeInstalled;
    private long planNanosLast;
    private long planNanosTotal;
    private long planNanosMax;
    private long planNanosBuilds;
    private long windowStartTick;
    private boolean started;
    private MeterWindow lastWindow;
    private ConservationCheck lastConservation = ConservationCheck.unplanned();
    private ShareMeter.TickReading lastMetering;
    private BudgetStateMachine.Decision lastDecision;
    private ControlFrame control = ControlFrame.empty();
    private TickPlanStore.Control previousControl = TickPlanStore.Control.missing();
    private CommitLog.Replay lastCommitReplay;
    private long planSequence;
    private long worldGeneration;
    private List<String> plannedWorlds = List.of();

    private KernelModule() {
        intents.bindPayload(guard);
        // The progress side of the two rows whose producer already exists: the intent channel depth
        // and the world epoch change count are the same counters the readout publishes, so a signal
        // reading and its control-plane value can never disagree.
        waitPoints.progress().bind("xdomain", "intent.queue_depth", intents::depth);
        waitPoints.progress().bind("worldlife", "write.world_epochs_changes",
            () -> guard.worldEpochs().epochChanges());
        // The two return conditions whose producer already exists are bound to the same values the
        // readout publishes; the other three rungs stay unbound and say so instead of reporting a
        // condition nothing observes.
        ladder.bindSecondCondition(DegradeLevel.B1, "budget.margin.ai", this::aiMarginWithin);
        ladder.bindSecondCondition(DegradeLevel.B5, "budget.conservation_ok",
            () -> lastConservation.ok());
        // The first rung of the wait ladder returns on the progress signal of the wait points: the
        // reading is the same one the readout publishes, so a gate can never pass on a value nobody
        // published. The other two rungs stay unbound and say so.
        waitLadder.bindSecondCondition(WaitLadder.Level.A1, "wp.signal.moved",
            this::anyProgressMoved);
    }

    /** Whether any wait point's progress signal moved in the newest reading. */
    private boolean anyProgressMoved() {
        for (WaitProgress.Reading reading : waitPoints.progress().readings()) {
            if (reading.bound() && reading.delta() > 0L) {
                return true;
            }
        }
        return false;
    }

    public static KernelModule instance() {
        return INSTANCE;
    }

    /** Advances the kernel by one tick. */
    public void serverTick(List<String> worldIds) {
        if (!KernelSettings.enabled()) {
            guard.refresh(false, false, false, tickIndex);
            syncWaitSiteTap(false);
            syncPipelineRowTap(false);
            syncLoadProbe(false);
            shutdownDomains();
            return;
        }
        long startedAt = System.nanoTime();
        tickIndex++;
        loadThread.noteTick(tickIndex);
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
        syncPipelineRowTap(true);
        syncLoadProbe(true);
        if (KernelSettings.commitLog()) {
            // The log of this tick collects what the commit walk and the domain work reach. The
            // order it judges against comes from the plans of the recent ticks, so a write that was
            // ordered one tick earlier still resolves.
            commits.beginTick(tickIndex);
        }
        commitSegment.run(tickIndex);
        if (KernelSettings.commitLog()) {
            for (CommitSegment.AppliedStep step : commitSegment.appliedSteps()) {
                commits.reach(new CommitLog.Batch(step.worldId(), INTENT_DOMAIN, step.worldId()
                    + "/intent", CommitLog.Batch.Kind.INTENT, step.frozenOrder(), step.frozenOrder(), 1));
            }
        }
        owners.reclaimExpired(tickIndex);
        if (KernelSettings.shareTable()) {
            planBudget(worldIds);
        } else {
            SelfTimers.discardTickTotals();
        }
        if (KernelSettings.safetyNet()) {
            reviewSafety();
        }
        if (KernelSettings.tickPlan()) {
            freezePlan(worldIds);
        }
        if (KernelSettings.dualExits()) {
            exits.noteTick(exitSample(), List.of());
        }
        if (KernelSettings.selfTimers()) {
            publishWindowIfDue();
        }
        ledger.verifyClosure();
        tickDomains();
        if (KernelSettings.commitLog()) {
            lastCommitReplay = commits.closeTick();
        }
        long overrunsBefore = waitPoints.waitOverrunCount();
        injectWaits();
        waitPoints.noteTick();
        noteWaitLadder(waitPoints.waitOverrunCount() > overrunsBefore);
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

    /** Installs the chunk pipeline row watcher; counting only, nothing the pipeline runs changes. */
    public void installPipelineRowTap() {
        syncPipelineRowTap(KernelSettings.enabled());
    }

    /** Removes the chunk pipeline row watcher. */
    public void removePipelineRowTap() {
        syncPipelineRowTap(false);
    }

    /** Installs the load observation: the tick boundary, the mailbox flow tap and the sampler. */
    public void installLoadProbe() {
        syncLoadProbe(KernelSettings.enabled());
    }

    /** Removes the load observation. */
    public void removeLoadProbe() {
        syncLoadProbe(false);
    }

    /** Remembers the server the tick source is, so the save identity can be named at a read. */
    public void noteTickSource(net.minecraft.server.MinecraftServer server) {
        saveIdentity.source(server);
    }

    private void syncLoadProbe(boolean wanted) {
        if (loadProbeInstalled == wanted) {
            return;
        }
        loadProbeInstalled = wanted;
        if (wanted) {
            loadThread.boundaryListener(attribution);
            loadThread.install();
            chunkFlow.attach();
            attribution.attach();
            chunkDemand.attach();
            PrtsSelfCosts.install(selfCosts);
        } else {
            loadThread.boundaryListener(null);
            loadThread.uninstall();
            chunkFlow.detach();
            attribution.detach();
            chunkDemand.detach();
            PrtsSelfCosts.install(null);
        }
    }

    private void syncPipelineRowTap(boolean wanted) {
        if (pipelineRowTapInstalled == wanted) {
            return;
        }
        pipelineRowTapInstalled = wanted;
        if (wanted) {
            pipelineRows.attach();
        } else {
            pipelineRows.detach();
        }
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

    private static boolean commitWanted() {
        return KernelSettings.commitIntents() || KernelSettings.dispatchTakeover();
    }

    private void planBudget(List<String> worldIds) {
        Map<String, long[]> totals = KernelSettings.selfTimers() ? SelfTimers.consumeTickTotals()
            : Map.of();
        Map<String, EnumMap<ShareClass, Double>> used = ShareMeter.usedFromTickTotals(totals);
        lastMetering = ShareMeter.read(tickIndex, KernelSettings.selfTimers(), totals);
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
            ladder.noteEntered(record.wouldDegradeLevel(), tickIndex);
            overruns++;
        }
        if (minMargin == Double.POSITIVE_INFINITY) {
            minMargin = 0.0;
        }
        lastDecision = budgetStates.judge(tickIndex, table, overruns, waitPoints.waitOverrunCount(),
            lastConservation);
        ladder.noteTick(lastDecision.degraded());
        control = new ControlFrame(tickIndex, minMargin, overruns, waitPoints.waitOverrunCount(),
            reserveUsedMs(), table.reserve().remainingMs(), degradeStateKey(),
            lastDecision.enteredTick());
        injectLadderWalk();
    }

    /** The controlled leg of the resource ladder: walks or returns the rungs an injection declared,
     * one rung per tick, so the order is walked and never skipped. Off unless a directive names it;
     * a rung reports its action only while the switch that would allow one is on. */
    private void injectLadderWalk() {
        DegradeLevel[] levels = DegradeLevel.values();
        int walk = FaultInjection.ladderWalkStep();
        if (walk > 0) {
            DegradeLevel target = levels[Math.min(walk, levels.length - 1)];
            ladder.noteEntered(target, tickIndex);
            ladder.noteEffective(target);
        }
        int back = FaultInjection.ladderReturnStep();
        if (back > 0) {
            ladder.noteReturned(levels[Math.min(back, levels.length - 1)]);
        }
    }

    /** The controlled leg of the wait walk: observes the waits an injection declared, taking the
     * call-site classes in turn so one declaration covers each class. Off unless a directive names
     * it, and the observation enters through the same call the twenty real call sites use, so the
     * counters it moves are the counters the live path moves. */
    private void injectWaits() {
        int step = FaultInjection.waitWalkStep();
        if (step <= 0) {
            return;
        }
        List<WaitClass> classes = WaitClass.classified();
        WaitClass wanted = classes.get((step - 1) % classes.size());
        WaitSite chosen = null;
        for (WaitSite site : waitPoints.sites().sites()) {
            if (WaitClass.ofPhase(site.tickPhase()) == wanted) {
                chosen = site;
                break;
            }
        }
        if (chosen == null) {
            return;
        }
        String world = plannedWorlds.isEmpty() ? RUNTIME_WORLD : plannedWorlds.get(0);
        waitPoints.observeWait(chosen.wpId(), new WaitPointRegistry.WaitSpan(chosen.wpId(),
            chosen.classRef() + "." + chosen.methodRef(), chosen.siteId(), world, tickIndex,
            KernelSettings.waitBoundMs() + 1L, "injected"));
        waitPoints.noteInjectionWalkthrough(chosen.wpId(), wanted);
    }

    /** One tick of the wait ladder. A tick that carried a wait over the bound enters the first rung;
     * a run of such ticks walks it one rung further, in order, because the ladder may not skip. The
     * ladder only counts: no rung changes the bound, the parallel degree or the order of the tick in
     * this build, and a rung reports its action only while the switch that would allow one is on. */
    private void noteWaitLadder(boolean crossed) {
        if (crossed) {
            waitCrossStreak++;
            WaitLadder.Level target = waitCrossStreak >= 3 ? WaitLadder.Level.A3
                : waitCrossStreak == 2 ? WaitLadder.Level.A2 : WaitLadder.Level.A1;
            for (WaitLadder.Level level : waitLadder.noteEntered(target, tickIndex).entered()) {
                waitLadder.noteEffective(level);
            }
        } else {
            waitCrossStreak = 0;
        }
        waitLadder.noteTick(crossed);
    }

    /** Freezes the plan of one tick. The declarations the domains handed in since the last plan are
     * taken here, the job graph is built from them, and the plan is published with the control frame
     * the next planning period consumes. A tick whose control frame is missing is a bootstrap tick:
     * it is counted and no plan is built from values nobody measured. */
    private void freezePlan(List<String> worldIds) {
        List<String> worlds = worldIds == null ? List.of() : worldIds;
        List<String> sorted = new ArrayList<>(worlds);
        sorted.sort(String::compareTo);
        if (!sorted.equals(plannedWorlds)) {
            plannedWorlds = List.copyOf(sorted);
            worldGeneration++;
        }
        if (!previousControl.observed()) {
            plans.noteBootstrapSkip();
            plans.noteControl(controlFrameOf(worldGeneration));
            previousControl = plans.control();
            plans.takeFeedback(tickIndex, planSequence);
            return;
        }
        planSequence++;
        List<String> domainIds = new ArrayList<>();
        for (KernelDomain domain : domains) {
            domainIds.add(domain.id());
        }
        TickPlanStore.Feedback feedback = KernelSettings.planFeedback() ? plans.feedback()
            : TickPlanStore.Feedback.none();
        long freezeStartedAt = System.nanoTime();
        TickPlanPlanner.Result result = TickPlanPlanner.plan(new TickPlanPlanner.Input(tickIndex,
            planSequence, worldGeneration, worlds, domainIds, previousControl, jobIntake.take(),
            shares.lastTable(), KernelSettings.jobQueueCap(), feedback));
        long freezeNanos = System.nanoTime() - freezeStartedAt;
        planNanosLast = freezeNanos;
        planNanosTotal += freezeNanos;
        planNanosBuilds++;
        if (freezeNanos > planNanosMax) {
            planNanosMax = freezeNanos;
        }
        if (result.ok()) {
            plans.publish(result.plan());
        } else {
            plans.noteFailure(result.code());
        }
        plans.noteControl(controlFrameOf(worldGeneration));
        previousControl = plans.control();
        // Taken last, so the frame the next tick consumes carries this tick's deltas and nothing of
        // the plan that just read the previous one.
        plans.takeFeedback(tickIndex, planSequence);
    }

    /** The four detectors of the safety net, reading the counters the layers around it published
     * and reporting the difference against the tick before. */
    private void reviewSafety() {
        WritePathCounters paths = guard.counters();
        Map<SafetyNet.Point, Long> denied = new java.util.LinkedHashMap<>();
        for (WritePath path : WritePath.values()) {
            for (ThreadOrigin origin : ThreadOrigin.values()) {
                for (HolderKind holder : HolderKind.values()) {
                    long count = paths.count(path, origin, holder, WriteDisposition.DENY);
                    if (count > 0L) {
                        denied.put(new SafetyNet.Point(SafetyNet.NO_WORLD_SITE,
                            path.key() + "|" + origin.key() + "|" + holder.name().toLowerCase(Locale.ROOT)),
                            count);
                    }
                }
            }
        }
        Map<SafetyNet.Point, Long> commitViolations = new java.util.LinkedHashMap<>();
        long order = commits.orderViolations() + commits.unplanned() + commits.retried();
        if (order > 0L) {
            commitViolations.put(new SafetyNet.Point(SafetyNet.NO_WORLD_SITE, "commit:order"), order);
        }
        for (CommitRing ring : commits.rings()) {
            if (ring.refusedFull() > 0L) {
                commitViolations.put(new SafetyNet.Point(ring.worldId(),
                    "commit:ring|" + ring.domainId()), ring.refusedFull());
            }
        }
        Map<SafetyNet.Point, Long> waitOverruns = new java.util.LinkedHashMap<>();
        for (WaitPointRegistry.WaitPointEntry row : waitPoints.rows()) {
            long overrun = waitPoints.overrunOf(row.wpId());
            if (overrun > 0L) {
                waitOverruns.put(new SafetyNet.Point(SafetyNet.NO_WORLD_SITE,
                    "wait:" + row.wpId()), overrun);
            }
        }
        SafetyNet.TickSources sources = new SafetyNet.TickSources(denied, commitViolations,
            waitOverruns, ledger.codeCount(RejectCode.VERSION_MISMATCH),
            waitPoints.unregisteredCallSites(), ladder.enteredTotal(),
            ladder.sign().skippedCount());
        safety.review(sources, tickIndex);
        reviewZeroEffect();
    }

    /** Watches the metered value of the class each rung of the ladder stands for and asks, one
     * window after the rung was entered, whether it moved. */
    private void reviewZeroEffect() {
        int window = KernelSettings.safetyZeroEffectTicks();
        for (DegradeLadder.Counters counters : ladder.counters()) {
            String target = counters.level().name().toLowerCase(Locale.ROOT);
            double metric = shareMetric(counters.level());
            if (counters.entered() > 0L) {
                zeroEffect.watch(target, tickIndex, metric);
            }
            zeroEffect.evaluate(target, tickIndex, metric, counters.entered(), counters.effective(),
                window);
        }
    }

    /** The metered milliseconds of the class one rung degrades; the value the zero-effect detector
     * compares across its window. */
    private double shareMetric(DegradeLevel level) {
        ShareClass shareClass = switch (level) {
            case B1 -> ShareClass.AI;
            case B2 -> ShareClass.GRAPH;
            case B3 -> ShareClass.ENTITY;
            case B4 -> ShareClass.EVENT;
            case B5 -> ShareClass.BLOCKENTITY;
            case NONE -> null;
        };
        if (shareClass == null || lastMetering == null) {
            return 0.0;
        }
        ShareMeter.ClassReading row = lastMetering.row(shareClass);
        return row == null ? 0.0 : row.usedMs();
    }

    /** The five values of this tick as both exits read them: one sample, one set of origins. */
    private DualExits.Sample exitSample() {
        return new DualExits.Sample(tickIndex, control.minMarginMs(), control.overrunHits(),
            control.waitBoundHits(), reserveUsedMs(), control.reserveRemainingMs(),
            control.degradeState());
    }

    /** The five control values of this tick, as the next planning period reads them. */
    private TickPlanStore.Control controlFrameOf(long generation) {
        return TickPlanStore.Control.of(tickIndex, planSequence, generation, control.minMarginMs(),
            control.overrunHits(), control.waitBoundHits(), control.reserveRemainingMs(),
            control.degradeState());
    }

    /** Resolves the order of one commit against the plans of the recent ticks. */
    private CommitLog.Resolved resolveCommitOrder(String worldId, String domainId, String nodeKey) {
        TickPlanStore.StepRef ref = plans.resolve(worldId, domainId, nodeKey);
        if (ref == null) {
            return null;
        }
        return new CommitLog.Resolved(ref.planSequence(), ref.position(), ref.intent());
    }

    /** The state the control plane publishes: the phase, and the rung the ladder stands on when it
     * is a degraded tick. */
    private String degradeStateKey() {
        BudgetStateMachine.Decision decision = lastDecision;
        if (decision == null) {
            return "none";
        }
        String phase = BudgetStateMachine.key(decision.phase());
        DegradeLevel deepest = ladder.sign().deepest();
        if (decision.degraded() && deepest != DegradeLevel.NONE) {
            return phase + ":" + deepest.name().toLowerCase(Locale.ROOT);
        }
        return phase;
    }

    /** No AI row of the newest table is over its share. Used as the second return condition of a
     * rung, so it answers about the newest table and not about the tick that entered the rung. */
    private boolean aiMarginWithin() {
        ShareTable table = shares.lastTable();
        if (table == null) {
            return false;
        }
        for (ShareTable.ShareRow row : table.rows()) {
            if (row.shareClass() == ShareClass.AI && row.overrun()) {
                return false;
            }
        }
        return true;
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

    /** The arena the domains share with the module. */
    public ArenaLedger arena() {
        return arena;
    }

    /** The passthrough slot and the version slot of the arena face. Neither is on a production path
     * in this build; the counters answer for a controlled round trip. */
    public ArenaPassthrough passthrough() {
        return passthrough;
    }

    /** The differential of the two arms. The controlled leg compares the arm a run computed with the
     * arm the host path produced; a pair is counted once it was compared, not once it was equal. */
    public DiffProbe arms() {
        return arms;
    }

    /** The counter behind the planning period's clock ban. */
    public PlanClockSeam planClock() {
        return planClock;
    }

    /** The three rungs of the wait ladder and their return gates. */
    public WaitLadder waitLadder() {
        return waitLadder;
    }

    /** The directory the intent payloads of the write path are bound in. */
    public IntentPayloadDirectory payloads() {
        return payloads;
    }

    /** Installs one domain; installing the same identity again replaces the previous instance. */
    public void installDomain(KernelDomain domain) {
        domains.removeIf(existing -> existing.id().equals(domain.id()));
        domains.add(domain);
    }

    /** The domains this module drives, in installation order. */
    public List<KernelDomain> domains() {
        return List.copyOf(domains);
    }

    private void tickDomains() {
        for (KernelDomain domain : domains) {
            domain.tick(tickIndex);
        }
    }

    private void shutdownDomains() {
        for (KernelDomain domain : domains) {
            domain.shutdown();
        }
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

    /** The counters of the chunk pipeline's own mailboxes: observation only, never a decision. */
    public PipelineRowObserver pipelineRows() {
        return pipelineRows;
    }

    /** The thread-state sampler of the load windows. */
    public LoadThreadObserver loadThread() {
        return loadThread;
    }

    /** The mailbox flow counters of the chunk pipeline. */
    public ChunkFlowObserver chunkFlow() {
        return chunkFlow;
    }

    /** The identity of the save the run was made on. */
    public SaveIdentityObserver saveIdentity() {
        return saveIdentity;
    }

    /** The per-instance attribution of the block entity and entity tick faces. */
    public StallAttributionObserver attribution() {
        return attribution;
    }

    /** The demand side counters of the chunk cache. */
    public ChunkDemandObserver chunkDemand() {
        return chunkDemand;
    }

    /** How long the last plan build took, in nanoseconds. */
    public long planNanosLast() {
        return planNanosLast;
    }

    /** How long every plan build took together, in nanoseconds. */
    public long planNanosTotal() {
        return planNanosTotal;
    }

    /** The longest plan build so far, in nanoseconds. */
    public long planNanosMax() {
        return planNanosMax;
    }

    /** How many plan builds were timed. */
    public long planNanosBuilds() {
        return planNanosBuilds;
    }

    /** The bounded intake a domain hands its declarations to. */
    public JobIntake jobIntake() {
        return jobIntake;
    }

    /** The scheduler of the frozen job graph of one tick. */
    public JobScheduler scheduler() {
        return scheduler;
    }

    /** The metering point of the job layer: it books into the same timers the share table is
     * planned from, so the job layer has no conversion of its own. */
    public ShareMeterPoint jobMeter() {
        return jobMeter;
    }

    /** The plans of the recent ticks and the control frame the next one consumes. */
    public TickPlanStore plans() {
        return plans;
    }

    /** The safety net: the violations it counted and the escalation candidates it published. */
    public SafetyNet safety() {
        return safety;
    }

    /** The detector that asks whether a rung that claims an effect moved what it acted on. */
    public ZeroEffectDetector zeroEffect() {
        return zeroEffect;
    }

    /** The two exits of the observation layer. */
    public DualExits exits() {
        return exits;
    }

    /** The single write entry of this tick. */
    public CommitLog commits() {
        return commits;
    }

    /** The replay of the newest closed tick; null before the first closed one. */
    public CommitLog.Replay commitReplay() {
        return lastCommitReplay;
    }

    /** The generation of the world set the newest plan belongs to. */
    public long worldGeneration() {
        return worldGeneration;
    }

    public SharePlanner shares() {
        return shares;
    }

    public BudgetStateMachine budgetStates() {
        return budgetStates;
    }

    public DegradeLadder ladder() {
        return ladder;
    }

    /** The state the newest tick was judged into; null before the first planned tick. */
    public BudgetStateMachine.Decision budgetState() {
        return lastDecision;
    }

    /** The per-class metering of the newest tick; null before the first planned tick. */
    public ShareMeter.TickReading metering() {
        return lastMetering;
    }

    public ConservationCheck conservation() {
        return lastConservation;
    }

    public ControlFrame control() {
        return control;
    }

    /** Clears the live counters. */
    public void resetReadings() {
        for (KernelDomain domain : domains) {
            domain.reset();
        }
        loadThread.reset();
        chunkFlow.reset();
        saveIdentity.reset();
        attribution.reset();
        chunkDemand.reset();
        planNanosLast = 0L;
        planNanosTotal = 0L;
        planNanosMax = 0L;
        planNanosBuilds = 0L;
        arena.reset();
        passthrough.reset();
        arms.reset();
        planClock.reset();
        waitLadder.reset();
        waitCrossStreak = 0;
        SelfTimers.resetAll();
        guard.resetReadings();
        waitSites.reset();
        waitPoints.resetReadings();
        commitSegment.reset();
        tickIndex = 0L;
        windowStartTick = 0L;
        started = false;
        lastWindow = null;
        lastConservation = ConservationCheck.unplanned();
        lastMetering = null;
        lastDecision = null;
        budgetStates.reset();
        ladder.reset();
        control = ControlFrame.empty();
        previousControl = TickPlanStore.Control.missing();
        lastCommitReplay = null;
        planSequence = 0L;
        worldGeneration = 0L;
        plannedWorlds = List.of();
        jobIntake.reset();
        scheduler.reset();
        jobMeter.reset();
        plans.reset();
        commits.reset();
        safety.reset();
        zeroEffect.reset();
        exits.reset();
    }
}
