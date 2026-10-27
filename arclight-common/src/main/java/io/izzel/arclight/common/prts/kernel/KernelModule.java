/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.arena.ArenaPassthrough;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitNamespaces;
import io.izzel.arclight.common.prts.kernel.commit.CommitReservation;
import io.izzel.arclight.common.prts.kernel.commit.CommitRing;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.commit.DeclarationInstanceAllocator;
import io.izzel.arclight.common.prts.kernel.commit.EffectStore;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.commit.IdentityWitness;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealTable;
import io.izzel.arclight.common.prts.kernel.commit.RangeSettlementObligations;
import io.izzel.arclight.common.prts.kernel.exits.DualExits;
import io.izzel.arclight.common.prts.kernel.safety.SafetyNet;
import io.izzel.arclight.common.prts.kernel.safety.ZeroEffectDetector;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.jobs.DemandShadowCandidates;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.JobIntake;
import io.izzel.arclight.common.prts.kernel.jobs.JobScheduler;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineBatchLedger;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineRoundJobs;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineRunSeal;
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
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots;
import io.izzel.arclight.common.prts.kernel.sites.WritePath;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.sites.WorldEpochs;
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
import io.izzel.arclight.common.prts.kernel.observe.ArrivalDigestObserver;
import io.izzel.arclight.common.prts.kernel.observe.ChunkDemandObserver;
import io.izzel.arclight.common.prts.kernel.observe.ChunkFlowObserver;
import io.izzel.arclight.common.prts.kernel.observe.ChunkMaterializationObserver;
import io.izzel.arclight.common.prts.kernel.observe.CloseSealWriter;
import io.izzel.arclight.common.prts.kernel.observe.LoadThreadObserver;
import io.izzel.arclight.common.prts.kernel.observe.PipelineRowObserver;
import io.izzel.arclight.common.prts.kernel.observe.RegionIdentityObserver;
import io.izzel.arclight.common.prts.kernel.observe.SaveIdentityObserver;
import io.izzel.arclight.common.prts.kernel.observe.StallAttributionObserver;
import io.izzel.arclight.common.prts.kernel.observe.TickDigestObserver;
import io.izzel.arclight.common.prts.kernel.meter.SelfCostTap;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import io.izzel.arclight.common.prts.support.PrtsChunkMaterialization;
import io.izzel.arclight.common.prts.support.PrtsPipelineBatches;
import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import io.izzel.arclight.common.prts.support.PrtsSelfCosts;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
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
     * answers how often it did. Nothing in this build calls it: the planning period is a function of the
     * tick index, the plan sequence and the control frame the tick before published, and the compiled
     * classes of the planning period are scanned for any clock reference at build time. It exists so the
     * published count is a measurement and not a constant: a component that took a clock through it would
     * move the count, and the count is published next to the plan it was taken for. */
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

    /** How many requests from other threads may wait for the owner at once. A queue that grew without a
     * bound would turn a repeated request into a memory leak: the one that finds it full is refused. */
    private static final int RESET_QUEUE_CAPACITY = 8;

    /** What the trace of a request whose reset threw says: neither answered nor refused-again. */
    private static final String RESET_FAULTED = "reset-faulted";

    /** The domain one applied intent is logged under: the intent channel, which is not a world domain
     * and is ordered by its own frozen sequence. */
    private static final String INTENT_DOMAIN = "intent";

    /** The piece that refuses an adoption while the declared face is off. The chain check gives the
     * same name, so the entry that refuses before a chain is built and the check after it agree. */
    public static final String REFUSAL_DECLARED_FACE_OFF = "declared-face-off";

    /** The owner a chain adopted at the tick boundary is minted under. */
    public static final String CHAIN_OWNER_ID = "host:commit-chain";

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
    private final WriteVersionSlots versionSlots = new WriteVersionSlots();
    private final OwnerGrantPoint ownerGrants = new OwnerGrantPoint(owners, versionSlots);
    private final WriteControlledSlots controlledSlots = new WriteControlledSlots(owners, versionSlots,
        guard.worldEpochs(), new ClaimSource());
    private final WaitPointRegistry waitPoints = new WaitPointRegistry(KernelSettings::waitBoundMs,
        KernelSettings::refuseUnregisteredWaits);
    private final WaitSiteObserver waitSites = new WaitSiteObserver(waitPoints, this::tickIndex,
        KernelSettings::waitBoundMs);
    private final PipelineRowObserver pipelineRows = new PipelineRowObserver();
    private final TickDigestObserver tickDigest = new TickDigestObserver();
    private final ArrivalDigestObserver arrivalDigest = new ArrivalDigestObserver();
    private final LoadThreadObserver loadThread = new LoadThreadObserver();
    private final ChunkFlowObserver chunkFlow = new ChunkFlowObserver();
    private final ChunkMaterializationObserver chunkMaterialization =
        new ChunkMaterializationObserver();
    private final SaveIdentityObserver saveIdentity = new SaveIdentityObserver();
    private final RegionIdentityObserver regionIdentity = new RegionIdentityObserver();
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
    private final PipelineBatchLedger pipelineBatches = new PipelineBatchLedger();
    /** The range seals a run start may publish: the table the identity source reads, sized by the
     * ledger's own pending bound and by no literal. */
    private final RangeSealTable rangeSeals = new RangeSealTable(pipelineBatches.pendingLimit());
    /** The settlement obligations the seals a run start publishes owe: a published seal is held here
     * until the settlement that names its seat releases it. */
    private final RangeSettlementObligations settlementObligations =
        new RangeSettlementObligations();
    private final PipelineRoundJobs pipelineJobs = new PipelineRoundJobs();
    /** The demands a freeze reads as shadow candidates. This reader seals nothing; the freeze does. */
    private final DemandShadowCandidates demandShadows =
        new DemandShadowCandidates(pipelineBatches.pendingLimit(), pipelineBatches::currentPlan);
    private final PipelineWriteClaims claims = new PipelineWriteClaims(versionSlots,
        guard.worldEpochs(), guard::onServerThread, LiveLevels::level,
        this::noteTypedSettlement);
    /** A bounded exact order for a plan a retained claim still names; cleared with the claim floor. */
    private final Map<Long, RetainedOrder> retainedOrders = new ConcurrentHashMap<>();
    private record RetainedOrder(long lifecycle, List<TickPlan.CommitStep> order) {
    }
    private final ShareMeterPoint jobMeter = new ShareMeterPoint();
    private final TickPlanStore plans = new TickPlanStore(KernelSettings.planHistoryCap());
    private final SafetyNet safety = new SafetyNet(KernelSettings::safetyDegrade,
        KernelSettings::safetyCascadeCap);
    private final ZeroEffectDetector zeroEffect = new ZeroEffectDetector();
    private final DualExits exits = new DualExits(KernelSettings.exitWindowTicks());
    /** The one plan authority of this module. A chain is adopted only when the log it carries asks
     * this very instance, so the identity of an order source is a reference and not a shape. */
    private final CommitLog.OrderSource commitOrderSource = new CommitLog.OrderSource() {

        @Override
        public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
            return resolveCommitOrder(worldId, domainId, nodeKey);
        }

        @Override
        public CommitLog.Resolved resolveAt(long planSequence, long lifecycle, String worldId,
                                            String domainId, String nodeKey) {
            return resolveCommitOrderAt(planSequence, lifecycle, worldId, domainId, nodeKey);
        }
    };
    /** Where a typed line's freeze is read from: the declarations this module sealed and the
     * generations live right now. The table is written by the freeze alone. */
    private final IdentityWitness.Source commitIdentitySource = new IdentityWitness.Source() {

        @Override
        public FrozenDeclarations declarations(String worldId, String domainId, long planSequence) {
            FrozenDeclarations held = declarationHistory.current();
            if (!held.freeze().lifecycleKey().equals(declarationLifecycleKey())) {
                // A table of a lifecycle this module has left authorizes nothing, whatever its plan.
                return FrozenDeclarations.none();
            }
            // The freeze it holds is the only one it can answer for: a seat of another plan is
            // answered with nothing, which is what makes the interval around a publish inert.
            return held.planSequence() == planSequence ? held : FrozenDeclarations.none();
        }

        @Override
        public IdentityWitness.Obligation oblige(FrozenDeclarations declarations) {
            return declarationHistory.oblige(declarations);
        }

        @Override
        public FrozenDeclarations.RangeSeal range(FrozenDeclarations.Seat seat, long cell) {
            return rangeSeals.locate(seat, cell);
        }

        @Override
        public boolean hasRange(FrozenDeclarations.Seat seat) {
            return rangeSeals.hasSeat(seat);
        }

        @Override
        public IdentityWitness.Obligation oblige(FrozenDeclarations.RangeSeal seal) {
            return rangeSeals.oblige(seal);
        }

        @Override
        public long liveWorldEpoch(String worldId) {
            return guard.worldEpochs().epochOf(worldId);
        }

        @Override
        public IdentityWitness.Authorization liveAuthorization(long planSequence,
                CommitNamespaces.Namespace planController, String worldId, String domainId,
                String nodeKey) {
            String lifecycleKey = declarationLifecycleKey();
            long held = versionSlots.grantedPlan();
            if (planController == null || !planController.lifecycleKey().equals(lifecycleKey)) {
                // The record is published under this module's own lifecycle and the reset path clears
                // it in the same move, so a plan controller of any other lifecycle is answered with a
                // refusal and never with the number the record happens to carry.
                return IdentityWitness.Authorization.refused(lifecycleKey, held,
                    IdentityWitness.Authorization.Face.notRead(), "record-of-another-lifecycle");
            }
            if (held == 0L) {
                // Nothing was published for any plan: this is not the same state as a record that
                // belongs to another plan, so it names the record's own sentinel and no lifecycle.
                return IdentityWitness.Authorization.refused("", 0L,
                    IdentityWitness.Authorization.Face.notRead(), "no-record");
            }
            if (held != planSequence) {
                return IdentityWitness.Authorization.refused(lifecycleKey, held,
                    IdentityWitness.Authorization.Face.notRead(), "record-of-another-plan");
            }
            Long granted = versionSlots.grantedFor(planSequence, worldId, WriteLevel.REGION, domainId);
            if (granted == null) {
                return IdentityWitness.Authorization.refused(lifecycleKey, planSequence,
                    IdentityWitness.Authorization.Face.absent(), "domain-not-granted");
            }
            if (granted == WriteVersionSlots.NOT_CARRIED) {
                return IdentityWitness.Authorization.refused(lifecycleKey, planSequence,
                    IdentityWitness.Authorization.Face.present(WriteVersionSlots.NOT_CARRIED),
                    "grant-not-carried");
            }
            // The validity face is the live slot read here and now, uncounted and never written back
            // over the frozen number; a slot that was retired is a declared absence, and a slot never
            // carries the declared zero because every value is minted from one.
            long live = versionSlots.peek(worldId, WriteLevel.REGION, domainId);
            return IdentityWitness.Authorization.answered(lifecycleKey, planSequence, granted,
                live == WriteVersionSlots.NOT_CARRIED
                    ? IdentityWitness.Authorization.Face.absent()
                    : IdentityWitness.Authorization.Face.present(live));
        }

        @Override
        public CommitNamespaces.Namespace currentController() {
            CommitController bound = commitController;
            return bound == null ? null : bound.reservations().controller();
        }
    };
    /** The declaration tables: the freeze in force authorizes and the replaced ones stay as bounded
     * snapshots. */
    private final FrozenDeclarations.History declarationHistory =
        new FrozenDeclarations.History(KernelSettings.planHistoryCap());
    /** The one source of the instance numbers a freeze seals: held here and handed to no one. */
    private final DeclarationInstanceAllocator declarationInstances =
        new DeclarationInstanceAllocator();
    /** The factory of the range seals a run start may publish. It is handed the same table the
     * identity source reads and the declarations in force, and it seals nothing until a window
     * asks. A seal it publishes is held by the settlement obligations until the settlement that
     * names its seat releases it. */
    private final PipelineRangeSeals rangeSealFactory = new PipelineRangeSeals(pipelineBatches,
        rangeSeals, plans, declarationInstances::mintOne,
        worldId -> guard.worldEpochs().epochOf(worldId), this::declarations,
        settlementObligations);
    private long declarationGeneration;
    private String declarationRefusal = "";
    private long declarationRefusals;
    private long declarationsSealed;
    /** The log this module built for itself: the path every tick uses while nothing is bound. */
    private final CommitLog ownCommits = new CommitLog(KernelSettings::commitRingCap,
        commitOrderSource, null, commitIdentitySource);
    /** The log in use: the module's own until a whole chain is adopted, and the log of that chain
     * afterwards. Only an install moves it, and an install moves it whole or not at all. */
    private CommitLog commits = ownCommits;
    /** The typed fan-out of one settlement period: it validates a whole batch before it writes a line
     * and writes nothing for a batch any of whose inputs is missing. */
    private final TypedConsumerSettlement typedConsumer = new TypedConsumerSettlement(
        () -> commits, rangeSeals, () -> commitIdentitySource.currentController());
    /** The generation holder of a chain this module adopts: the module is its lifecycle owner. */
    private final CommitNamespaces.ResetEpochs commitChainEpochs = new CommitNamespaces.ResetEpochs();
    private long tickIndex;
    /** The server whose live levels the claim point resolves; the host names it before the tick. */
    private volatile net.minecraft.server.MinecraftServer liveServer;
    private int waitCrossStreak;
    private boolean waitSiteTapInstalled;
    private boolean pipelineRowTapInstalled;
    private boolean pipelineOwnerTapInstalled;
    private boolean claimsTapInstalled;
    private long jobDeclarations;
    private long lastDispatchHandedOut;
    private boolean loadProbeInstalled;
    private long planNanosLast;
    private long planNanosTotal;
    private long planNanosMax;
    private long planNanosBuilds;
    private long planC1NanosLast;
    private long planC1NanosTotal;
    private long planC1NanosMax;
    private long planC1NanosBuilds;
    private long windowStartTick;
    private boolean started;
    private MeterWindow lastWindow;
    private ConservationCheck lastConservation = ConservationCheck.unplanned();
    private ShareMeter.TickReading lastMetering;
    private BudgetStateMachine.Decision lastDecision;
    private ControlFrame control = ControlFrame.empty();
    private TickPlanStore.Control previousControl = TickPlanStore.Control.missing();
    private CommitLog.Replay lastCommitReplay;
    private CommitController commitController;
    private String commitBindingRefusal = "";
    private long commitBindingLogLifetime;
    private long commitBindingStoreId;
    /** The one monitor of the reset mailbox: every field below is read under it, and nothing that
     * can take another lock or run a host callback is called while it is held. */
    private final Object resetMailbox = new Object();
    private final Deque<RequestItem> resetQueue = new ArrayDeque<>();
    /** The one reset an owner-thread call could not carry out; held apart from the queue. */
    private boolean resetDeferred;
    private RequestItem resetDeferredItem;
    /** Whether the mailbox has seen the chain closed; enqueueing is decided against it. */
    private boolean resetMailboxClosed;
    /** Whether the owner is inside one batch: a call arriving while it is set is a re-entry. */
    private boolean resetBatchOpen;
    /** Items the owner has taken out and not answered: they still count against the capacity. */
    private int resetInProgress;
    private long resetRequestSequence;
    private long resetArrivals;
    private long commitResetRequests;
    private long commitResetHandOffs;
    private long commitResetMerges;
    private long commitResetAdmits;
    private long commitResetQueueRefusals;
    private long commitResetClosedRefusals;
    private volatile ResetTrace resetTrace = ResetTrace.NONE;
    /** The one reset that threw and is owed a recovery: its line, held apart from the newest request. */
    private volatile ResetTrace resetFaultLine = ResetTrace.NONE;
    private boolean commitSealed;
    private long commitSealedTurn;
    private int commitSealedCount;
    private final List<Disposition> commitSealedIdentities = new ArrayList<>();
    private long commitSealDiscards;
    private long commitCloseCalls;
    private long commitAdoptionCalls;
    private long commitSafePointCalls;
    private long commitOwnerThreadNamed;
    private long commitOwnerThreadMismatches;
    private long commitOwnerThreadId;
    private long hostEntrySequence;
    private long stopFlushLastSequence;
    private long stopCloseLastSequence;
    private long stopStepSequence;
    private long commitStopFlushSessions;
    private long commitStopFlushRefusals;
    private String commitStopFlushRefusal = "";
    private final List<String> stopFlushWorlds = new ArrayList<>();
    private final List<WorldStopFlush> stopFlushRows = new ArrayList<>();
    private final List<WorldUnloadDelivery> unloadRows = new ArrayList<>();
    private final Map<String, Long> stopFlushEntries = new HashMap<>();
    private long unloadStopEntry;
    private long unloadDuplicateCount;
    private long unloadEpochAdvances;
    private long unloadTokenReclaims;
    private long unloadPlatformPosts;
    private long unloadHostRemovePosts;
    private boolean unloadDeliveryInstalled;
    private int commitCloseGivenBack;
    private int commitCloseUnrecorded;
    private int commitClosePublishing;
    private long planSequence;
    private long worldGeneration;
    private List<String> plannedWorlds = List.of();

    private KernelModule() {
        intents.bindPayload(guard);
        guard.versionSlots(versionSlots);
        guard.controlledSlots(controlledSlots);
        // The claim point seats a declared job's slot and reports the batches that landed.
        claims.table(controlledSlots);
        claims.owners(owners);
        claims.writeSpaces(KernelWriteRanges.reader());
        guard.claimLandedTap((slot, worldId, tick, row) -> claims.noteLanded(slot, worldId, row));
        pipelineJobs.bind(pipelineBatches);
        claims.ledger(pipelineBatches);
        // A seal of an older plan stays readable while a retained claim still names its batch.
        rangeSealFactory.retained(claims::retainsBatch);
        demandShadows.retained(claims::retainsBatch);
        rangeSealFactory.retainedOrders(this::retainedOrder);
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
            LiveLevels.clear();
            guard.refresh(false, false, false, tickIndex);
            versionSlots.refresh(false);
            ownerGrants.refresh(false);
            controlledSlots.refresh(false);
            claims.refresh(false);
            syncWaitSiteTap(false);
            syncPipelineRowTap(false);
            syncLoadProbe(false);
            shutdownDomains();
            return;
        }
        long startedAt = System.nanoTime();
        tickIndex++;
        LiveLevels.refresh(liveServer);
        loadThread.noteTick(tickIndex);
        chunkMaterialization.noteTick(tickIndex);
        if (!started) {
            started = true;
            windowStartTick = tickIndex;
        }
        commitSegment.bindOwnerThread(Thread.currentThread());
        if (!guard.serverThreadBound()) {
            guard.bindServerThread(Thread.currentThread(), SERVER_SITE);
        }
        guard.noteLiveWorlds(worldIds);
        versionSlots.refresh(KernelSettings.writeVersionSlots());
        versionSlots.noteWorlds(worldIds);
        ownerGrants.refresh(KernelSettings.writeOwnerGrants());
        controlledSlots.refresh(KernelSettings.writeControlledSlot());
        claims.refresh(KernelSettings.writeControlledSlot() && KernelSettings.pipelineJobs()
            && KernelSettings.tickPlan());
        claims.noteTick(tickIndex);
        pipelineBatches.noteTick(tickIndex);
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
        tickDigest.noteTick(tickIndex);
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

    /** Remembers the server the tick source is, so the save identity and the partition can be named
     * at a read. */
    public void noteTickSource(net.minecraft.server.MinecraftServer server) {
        liveServer = server;
        LiveLevels.refresh(server);
        saveIdentity.source(server);
        regionIdentity.source(server);
        tickDigest.source(server);
        tickDigest.ledger(ledger);
        tickDigest.deviation(FaultInjection::tickDigestDeviates);
        arrivalDigest.source(server);
        arrivalDigest.demand(chunkDemand);
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
            chunkMaterialization.attach();
            attribution.attach();
            chunkDemand.attach();
            chunkDemand.attachSecondPath(demandShadows);
            demandShadows.clearPending();
            PrtsChunkDemand.installPendingBinder(task -> demandShadows.bindPending(
                (PipelineBatchLedger.TrackedTask) task, pipelineBatches));
            PipelineRunSeal.install(demandShadows::statusFor);
            PipelineRunSeal.installRange(rangeSealFactory);
            PrtsSelfCosts.install(selfCosts);
            // The chunk row's declared signal is bound only while its producer is installed: a row
            // whose producer is switched off keeps reporting an unbound slot rather than a zero.
            waitPoints.progress().bind("chunk", PrtsChunkMaterialization.SOURCE,
                chunkMaterialization::value);
        } else {
            waitPoints.progress().unbind("chunk");
            loadThread.boundaryListener(null);
            loadThread.uninstall();
            chunkFlow.detach();
            chunkMaterialization.detach();
            attribution.detach();
            chunkDemand.detach();
            PrtsChunkDemand.installPendingBinder(null);
            demandShadows.clearPending();
            PipelineRunSeal.install(null);
            PipelineRunSeal.installRange(null);
            PrtsSelfCosts.install(null);
        }
    }

    private void syncPipelineRowTap(boolean wanted) {
        syncPipelineOwnerTap(wanted && KernelSettings.pipelineJobs() && KernelSettings.tickPlan());
        if (pipelineRowTapInstalled == wanted) {
            return;
        }
        pipelineRowTapInstalled = wanted;
        if (wanted) {
            pipelineRows.attach();
            tickDigest.attach();
            arrivalDigest.attach();
            // The arrival face is only handed to the tick boundary and to the write path while a
            // window is declared: a process that declares none keeps its block writes at the one
            // volatile read the write guard already made and folds nothing at the tick boundary.
            if (arrivalDigest.armed()) {
                tickDigest.arrival(arrivalDigest);
                guard.arrivalWriteTap(arrivalDigest);
            }
        } else {
            guard.arrivalWriteTap(null);
            tickDigest.arrival(null);
            arrivalDigest.detach();
            pipelineRows.detach();
            tickDigest.detach();
        }
    }

    /** Installs or removes the reader that turns the rounds of the chunk pipeline into jobs; it is
     * only installed while a plan exists to consume the declarations. */
    private void syncPipelineOwnerTap(boolean wanted) {
        boolean claimTap = wanted && KernelSettings.writeControlledSlot();
        if (pipelineOwnerTapInstalled == wanted && claimsTapInstalled == claimTap) {
            return;
        }
        if (pipelineOwnerTapInstalled) {
            PrtsPipelineRows.removeOwnerTap(pipelineJobs);
        }
        if (claimsTapInstalled) {
            PrtsPipelineRows.removeOwnerTap(claims);
        }
        pipelineOwnerTapInstalled = wanted;
        claimsTapInstalled = claimTap;
        if (wanted) {
            PrtsPipelineRows.installOwnerTap(pipelineJobs);
            PrtsPipelineBatches.install(pipelineBatches, () -> tickIndex);
        } else {
            PrtsPipelineBatches.uninstall();
        }
        if (claimTap) {
            PrtsPipelineRows.installOwnerTap(claims);
        }
    }

    /** A tool that borrows the seam - the self check is one - hands it back here instead of leaving the
     * process with whatever it installed last: the module forgets what it believed was installed and
     * installs the configured watcher again, so a borrowed seam cannot silently end the observation. */
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

    /** One rung per tick, so the order is walked and never skipped. */
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

    /** Takes the call-site classes in turn and enters through the same call the real sites use. */
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

    /** A run of ticks over the bound walks the ladder one rung further, in order; it only counts. */
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

    /** A tick whose control frame is missing is a bootstrap tick: no plan is built from it. */
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
        if (KernelSettings.pipelineJobs() && KernelSettings.tickPlan()) {
            long c1Started = System.nanoTime();
            noteJobDeclarations(pipelineJobs.declareInto(jobIntake, planSequence, tickIndex));
            long c1Nanos = System.nanoTime() - c1Started;
            planC1NanosLast = c1Nanos;
            planC1NanosTotal += c1Nanos;
            planC1NanosBuilds++;
            if (c1Nanos > planC1NanosMax) {
                planC1NanosMax = c1Nanos;
            }
        }
        // The demands of this freeze are read once, before the plan is built, and the declarations of
        // that one window are handed to the same intake the plan takes from below: a capture the
        // window handed over declares the node its candidate is matched at, and no other key is made.
        List<DemandShadowCandidates.Shadow> window = demandShadows.readFor(
            new DemandShadowCandidates.Freeze(planSequence, worldGeneration,
                new FrozenDeclarations.FreezeKey(declarationLifecycleKey(), declarationGeneration)
                    .next()), worldId -> guard.worldEpochs().epochOf(worldId));
        for (JobDeclaration declaration : demandShadows.declarationsOf(window)) {
            jobIntake.submit(declaration);
        }
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
            // The declared-level seals of the span that just closed are published from the retained
            // real statuses before the new plan's declarations replace the ones they name; a status
            // that cannot be proved publishes nothing.
            FrozenDeclarations settledDeclarations = declarations();
            rangeSealFactory.rememberDeclarations(settledDeclarations);
            List<DemandShadowCandidates.TaskStatus> retainedStatuses =
                demandShadows.retainedStatuses();
            if (!retainedStatuses.isEmpty()) {
                rangeSealFactory.publishDeclaredDeclared(settledDeclarations, retainedStatuses,
                    rangeSeals.generation());
                demandShadows.releaseBefore(result.plan().planSequence(),
                    claimsEnabled() ? claims::retainsBatch : null);
            }
            // Sealed against this plan's own commit order and bound before the plan is published:
            // the interval authorizes nothing because the pair is told apart by the plan sequence.
            sealDeclarations(result.plan());
            plans.publish(result.plan());
            grantVersionSlots(result.plan());
            ownerGrants.apply(result.plan().graph(), tickIndex);
            if (claimsEnabled()) {
                // The span that closed is settled against the plan that authorized it; the plan
                // that just froze is taken for the span that follows, after the settlement.
                claims.reportLanded(this::noteLandedBatch);
                claims.declare(result.plan().graph(), result.plan().planSequence(), tickIndex,
                    plans.lifecycle());
                // A plan that declared a write set is its own writer for the span that follows: the
                // seat comes from the declaration itself, so a span whose jobs run no task body still
                // covers the writes its own range names until the next freeze replaces the set.
                claims.seatDeclared();
                retainOrder(result.plan());
            }
            if (KernelSettings.pipelineJobs() && KernelSettings.tickPlan()) {
                // A seal whose plan the settlement has moved past can no longer be named by it: the
                // sweep ends its obligation here, so a window that published one leaves no pin.
                // A claim still holding an older plan keeps that plan's seals readable for the
                // settlement that has not reached it yet.
                long retainedFloor = claimsEnabled() ? claims.retainedPlanFloor() : Long.MAX_VALUE;
                rangeSealFactory.settleBefore(result.plan().planSequence(), retainedFloor);
                trimRetainedOrders(Math.min(result.plan().planSequence(), retainedFloor));
            }
            if (KernelSettings.pipelineJobs() && KernelSettings.tickPlan()) {
                dispatchFrozen(result.plan());
            }
        } else {
            plans.noteFailure(result.code());
        }
        plans.noteControl(controlFrameOf(worldGeneration));
        previousControl = plans.control();
        // Taken last, so the frame the next tick consumes carries this tick's deltas and nothing of
        // the plan that just read the previous one.
        plans.takeFeedback(tickIndex, planSequence);
    }

    private String declarationLifecycleKey() {
        CommitController bound = commitController;
        return bound == null ? "" : bound.reservations().controller().lifecycleKey();
    }

    /** Seals the declarations this freeze accepted against the places the plan's own commit order
     * gave them; the boundary is read against the freeze and not the plan counter. */
    private void sealDeclarations(TickPlan plan) {
        FrozenDeclarations.FreezeKey live =
            new FrozenDeclarations.FreezeKey(declarationLifecycleKey(), declarationGeneration);
        FrozenDeclarations held = declarationHistory.current();
        if (live.sameLifecycle(held.freeze()) && plan.planSequence() <= held.planSequence()) {
            declarationRefusal = "declaration-not-newer";
            declarationRefusals++;
            return;
        }
        List<FrozenDeclarations.Assignment> places = new ArrayList<>();
        for (TickPlan.CommitStep step : plan.commitOrder()) {
            places.add(new FrozenDeclarations.Assignment(step.worldId(), step.domainId(),
                step.nodeKey(), step.position()));
        }
        List<FrozenDeclarations.Candidate> handed =
            new ArrayList<>(pipelineJobs.candidateDeclarations());
        handed.addAll(demandShadows.candidatesOf(demandShadows.lastCandidates()));
        String epochRefusal = declarationEpochRefusal(handed);
        if (!epochRefusal.isEmpty()) {
            declarationRefusals++;
        }
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.seal(live.next(), plan.planSequence(),
            declarationInstances.mint(handed, places), places);
        if (!sealed.sealed()) {
            declarationRefusal = sealed.refusal();
            declarationRefusals++;
            declarationHistory.retire();
            return;
        }
        declarationRefusal = epochRefusal;
        declarationGeneration = sealed.declarations().freeze().generation();
        declarationHistory.seal(sealed.declarations());
        declarationsSealed++;
    }

    /** The named refusal of the first declaration that carries its artefact and whose world
     * generation is not live, and the empty text when every one of them names a live world. What such
     * a declaration gets is no number and no seat, so what is left to say about it is why. */
    private static String declarationEpochRefusal(List<FrozenDeclarations.Candidate> candidates) {
        if (candidates == null) {
            return "";
        }
        for (FrozenDeclarations.Candidate candidate : candidates) {
            if (candidate == null || !candidate.artefactDeclared()) {
                continue;
            }
            String refusal = CommitLog.worldEpochRefusal(candidate.worldEpoch());
            if (!refusal.isEmpty()) {
                return refusal;
            }
        }
        return "";
    }

    /** The declarations of the freeze that is published, as the readings and the commit chain read
     * them. A table that was never sealed reads a named state, not a zero. */
    public FrozenDeclarations declarations() {
        return declarationHistory.current();
    }

    /** The freeze the current declarations belong to: its lifecycle key and its own generation. */
    public String declarationFreeze() {
        FrozenDeclarations.FreezeKey freeze = declarations().freeze();
        return freeze.lifecycleKey() + "#" + freeze.generation();
    }

    /** The read-only snapshot of one named freeze: the export path, which knows the freeze its records
     * were judged under. The freeze is named whole, because a reset hands the plan counter back. */
    public FrozenDeclarations declarationSnapshot(FrozenDeclarations.FreezeKey freeze,
                                                  long planSequence) {
        return declarationHistory.snapshot(freeze, planSequence);
    }

    /** The newest replaced freeze with this plan number, for a reader that did not name its freeze.
     * A diagnostic convenience only: it must not pick the table of an export, because two lifecycles
     * can carry the same plan number and this answer cannot tell them apart. */
    public FrozenDeclarations declarationSnapshot(long planSequence) {
        return declarationHistory.newestSnapshot(planSequence);
    }

    public int declarationSnapshotsRetained() {
        return declarationHistory.retained();
    }

    public long declarationSnapshotsRetired() {
        return declarationHistory.retired();
    }

    /** How many replaced freezes the bounded retention had to give up: counted, never quiet. */
    public long declarationSnapshotsEvicted() {
        return declarationHistory.evicted();
    }

    /** How many evictions an obligation did not allow: the bound passed a freeze it still owes. */
    public long declarationSnapshotsWithheld() {
        return declarationHistory.withheld();
    }

    /** The generation of the freeze this holder is in: it only rises, one step per lifecycle boundary. */
    public long declarationGeneration() {
        return declarationGeneration;
    }

    /** The piece that refused the last seal, or an empty string. */
    public String declarationRefusal() {
        return declarationRefusal;
    }

    public long declarationRefusals() {
        return declarationRefusals;
    }

    public long declarationsSealed() {
        return declarationsSealed;
    }

    /** The candidate declarations the pipeline producer handed over, and how many carry no artefact. */
    public long pipelineJobCandidates() {
        return pipelineJobs.candidateCount();
    }

    public long pipelineCandidatesWithoutArtefact() {
        return pipelineJobs.candidatesWithoutArtefact();
    }

    /** Every node is taken in the order the graph froze and settled; the hand-out lands nothing. */
    private void dispatchFrozen(TickPlan plan) {
        JobGraph graph = plan.graph();
        if (graph == null || graph.empty()) {
            lastDispatchHandedOut = 0L;
            return;
        }
        long handed = 0L;
        scheduler.begin(graph);
        JobScheduler.Step step = scheduler.next();
        while (step != null) {
            scheduler.settle(step.nodeId());
            handed++;
            step = scheduler.next();
        }
        lastDispatchHandedOut = handed;
    }

    private boolean claimsEnabled() {
        return KernelSettings.writeControlledSlot() && KernelSettings.pipelineJobs()
            && KernelSettings.tickPlan();
    }

    /** A batch no plan ordered is counted as unplanned rather than reordered. The plan, seat and
     * lifecycle the claim point saved are carried to the log; the log checks them against the order
     * source instead of resolving the node key to the newest plan again. */
    private void noteLandedBatch(PipelineWriteClaims.Report report) {
        if (!KernelSettings.commitLog()) {
            return;
        }
        commits.reach(new CommitLog.Batch(report.worldId(), report.domainId(), report.nodeKey(),
            CommitLog.Batch.Kind.APPLY, report.expectedVersion(), report.writeSetDigest(),
            report.rows()), report.planSequence(), report.position(), report.lifecycle());
    }

    /** Settles one attributed batch: the typed fan-out writes the lines its inputs support, and the
     * seal obligations of its seat are settled whatever it wrote, so a refused batch cannot pin the
     * table. A settlement that names no seat settles nothing. */
    private void noteTypedSettlement(PipelineWriteClaims.Attributed payload) {
        if (payload == null) {
            return;
        }
        typedConsumer.settle(payload);
        PipelineWriteClaims.Report report = payload.report();
        rangeSealFactory.settleSeat(new FrozenDeclarations.Seat(report.worldId(), report.domainId(),
            report.planSequence(), report.position()));
    }

    /** Counts the declarations the domains handed to the planning period, whether the intake took
     * them or refused them: the count is what a reader of the job layer sees as declared. */
    public void noteJobDeclarations(int count) {
        jobDeclarations += count;
    }

    /** The declarations handed to the planning period since the counters were cleared. */
    public long jobDeclarations() {
        return jobDeclarations;
    }

    /** The nodes the last dispatch handed out. */
    public long lastDispatchHandedOut() {
        return lastDispatchHandedOut;
    }

    /** The rounds of the chunk pipeline this kernel declares as jobs. */
    public PipelineRoundJobs pipelineJobs() {
        return pipelineJobs;
    }

    /** The batches the chunk pipeline submitted, as the ledger that tracks them reads them. */
    public PipelineBatchLedger pipelineBatches() {
        return pipelineBatches;
    }

    /** The range seals a run start may publish: the one table the identity source reads and the
     * run-start factory is handed. Read-only for every caller. */
    public RangeSealTable rangeSeals() {
        return rangeSeals;
    }

    /** The read-only readiness of a typed consumer: which inputs the kernel already holds. The
     * settlement point is wired, so the columns describe the inputs of a build rather than the
     * build's own wiring. */
    public record TypedConsumerReadiness(boolean effectsBound, boolean controllerBound,
                                         boolean obligationsRegistered, boolean attributionArmed) {

        /** Whether this build wires the settlement point. */
        public static final boolean SETTLEMENT_WIRED = true;

        /** Whether every input a typed line needs is present and the settlement point is wired. */
        public boolean ready() {
            return SETTLEMENT_WIRED && inputsBound();
        }

        /** Whether every input except the settlement point itself is present. */
        public boolean inputsBound() {
            return effectsBound && controllerBound && obligationsRegistered && attributionArmed;
        }
    }

    /** The read-only readiness of a typed consumer: what the module already holds at this moment. It
     * reads state and produces nothing. */
    public TypedConsumerReadiness typedConsumerReadiness() {
        return new TypedConsumerReadiness(commits.effects() != null, commitController() != null,
            rangeSealFactory.settlementObligationsRegistered(), claims.settlementArmed());
    }

    /** The chunk demands the freeze reads as shadow candidates. The reader seals nothing; the freeze
     * seals a capture that carries every column. */
    public DemandShadowCandidates demandShadowCandidates() {
        return demandShadows;
    }

    /** The claims the declared write sets of the chunk pipeline are turned into. */
    public PipelineWriteClaims claims() {
        return claims;
    }

    /** The value is minted once per domain and kept until an invalidation retires it. The answer of
     * each grant is taken here and published as this plan's own record before the plan is handed
     * out, so a row that comes later wears the number this loop read back. */
    private void grantVersionSlots(TickPlan plan) {
        if (plan.graph() == null) {
            return;
        }
        Map<OwnershipDomain, Long> granted = new HashMap<>();
        for (JobGraph.Node node : plan.graph().nodes()) {
            for (JobDeclaration.DomainRef ref : node.writeSet()) {
                OwnershipDomain domain =
                    new OwnershipDomain(ref.worldId(), WriteLevel.REGION, ref.domainId());
                granted.put(domain,
                    versionSlots.grant(ref.worldId(), WriteLevel.REGION, ref.domainId()));
            }
        }
        versionSlots.noteGranted(plan.planSequence(), granted);
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
        // The domains installed in this run are declared here, once per tick, so a counter can be
        // read with the name of the domain it belongs to instead of an inferred one.
        List<String> installed = new java.util.ArrayList<>(domains.size());
        for (KernelDomain domain : domains) {
            installed.add(domain.id());
        }
        safety.declareDomains(installed);
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

    private DualExits.Sample exitSample() {
        return new DualExits.Sample(tickIndex, control.minMarginMs(), control.overrunHits(),
            control.waitBoundHits(), reserveUsedMs(), control.reserveRemainingMs(),
            control.degradeState());
    }

    private TickPlanStore.Control controlFrameOf(long generation) {
        return TickPlanStore.Control.of(tickIndex, planSequence, generation, control.minMarginMs(),
            control.overrunHits(), control.waitBoundHits(), control.reserveRemainingMs(),
            control.degradeState());
    }

    /** A key no plan holds answers no step, and a claim whose step is missing is refused. */
    private final class ClaimSource implements WriteControlledSlots.Source {

        @Override
        public String worldOf(Object levelRef) {
            String world = claims.worldOf(levelRef);
            if (world.isEmpty()) {
                world = LiveLevels.worldOf(levelRef);
            }
            return LiveLevels.ambiguous(world) ? "" : world;
        }

        @Override
        public Step stepOf(String worldId, String domainId, String nodeKey) {
            TickPlanStore.StepRef ref = plans.resolve(worldId, domainId, nodeKey);
            return ref == null ? null : new Step(ref.planSequence(), ref.position());
        }
    }

    /** Resolves the order of one commit against the plans of the recent ticks. */
    private CommitLog.Resolved resolveCommitOrder(String worldId, String domainId, String nodeKey) {
        TickPlanStore.StepRef ref = plans.resolve(worldId, domainId, nodeKey);
        if (ref == null) {
            return null;
        }
        return new CommitLog.Resolved(ref.planSequence(), ref.position(), ref.intent());
    }

    /** Resolves one commit against the exact plan and lifecycle a landing report names; the newest
     * plan that reused the key is never consulted here. */
    private CommitLog.Resolved resolveCommitOrderAt(long planSequence, long lifecycle,
                                                    String worldId, String domainId,
                                                    String nodeKey) {
        TickPlanStore.StepRef ref = plans.resolveIn(planSequence, lifecycle, worldId, domainId,
            nodeKey);
        if (ref == null) {
            if (!claimsEnabled() || !claims.holdsPlan(planSequence)) {
                return null;
            }
            return retainedOrderAt(planSequence, lifecycle, worldId, domainId, nodeKey);
        }
        return new CommitLog.Resolved(ref.planSequence(), ref.position(), ref.intent());
    }

    /** The exact seat of one retained plan when the bounded plan history no longer carries it. */
    private CommitLog.Resolved retainedOrderAt(long planSequence, long lifecycle, String worldId,
                                               String domainId, String nodeKey) {
        RetainedOrder held = retainedOrders.get(planSequence);
        if (held == null || held.lifecycle() != lifecycle) {
            return null;
        }
        for (TickPlan.CommitStep step : held.order()) {
            if (step.worldId().equals(worldId) && step.domainId().equals(domainId)
                && step.nodeKey().equals(nodeKey)) {
                return new CommitLog.Resolved(planSequence, step.position(), step.intent());
            }
        }
        return null;
    }

    /** The order of one plan a retained claim still names, or null when nothing retained it. */
    List<TickPlan.CommitStep> retainedOrder(long planSequence, long lifecycle) {
        RetainedOrder held = retainedOrders.get(planSequence);
        return held == null || held.lifecycle() != lifecycle ? null : held.order();
    }

    private void retainOrder(TickPlan plan) {
        if (!claimsEnabled()) {
            return;
        }
        retainedOrders.put(plan.planSequence(),
            new RetainedOrder(plans.lifecycle(), List.copyOf(plan.commitOrder())));
        trimRetainedOrders(claims.retainedPlanFloor());
    }

    private void trimRetainedOrders(long floor) {
        retainedOrders.keySet().removeIf(sequence -> sequence < floor);
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

    public WriteVersionSlots versionSlots() {
        return versionSlots;
    }

    public OwnerGrantPoint ownerGrants() {
        return ownerGrants;
    }

    public WriteControlledSlots controlledSlots() {
        return controlledSlots;
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

    /** The per tick digest of the chunk pipeline: observation only, armed by a declared window. */
    public TickDigestObserver tickDigest() {
        return tickDigest;
    }

    /** The per tick arrival face: observation only, folded for the span the digest is armed for. */
    public ArrivalDigestObserver arrivalDigest() {
        return arrivalDigest;
    }

    /** The producer of the chunk row's declared progress signal: the count of materialized
     * chunks per world and per tick. */
    public ChunkMaterializationObserver chunkMaterialization() {
        return chunkMaterialization;
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

    public RegionIdentityObserver regionIdentity() {
        return regionIdentity;
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

    /** How long the last chunk-pipeline declaration step took, in nanoseconds. */
    public long planC1NanosLast() {
        return planC1NanosLast;
    }

    /** How long all chunk-pipeline declaration steps took together, in nanoseconds. */
    public long planC1NanosTotal() {
        return planC1NanosTotal;
    }

    /** The longest chunk-pipeline declaration step so far, in nanoseconds. */
    public long planC1NanosMax() {
        return planC1NanosMax;
    }

    /** How many chunk-pipeline declaration steps were timed. */
    public long planC1NanosBuilds() {
        return planC1NanosBuilds;
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

    /** One request as the mailbox carries it: immutable, so an answer names the item it belongs to. */
    private record RequestItem(String source, long requestId, long arrivalThreadId, long arrivalOrder) {
    }

    /** What the newest request reads back as: one read-only snapshot, published whole. */
    private record ResetTrace(String source, long requestId, long requestThreadId, long answeredByThreadId,
                              long carriedEpoch, String result) {

        static final ResetTrace NONE = new ResetTrace("", 0L, 0L, 0L, NO_CARRIED_EPOCH, "");
    }

    /** One consistent read of the three exits and of the counters behind them; package-private, so
     * it is not part of the announced surface. */
    record Mailbox(int depth, List<String> sources, boolean pending, long calls, long handOffs,
                   long merged, long admitted, long refusedFull, long refusedClosed, int inProgress) {
    }

    /** Why a reset is attempted: the gate below reads it, so no batch is refused by its own flag. */
    private enum ResetAttempt {
        BATCH_ITEM,
        OUTSIDE_BATCH
    }

    /** The one gate every legal path to {@link #clearReadings()} passes through: the whole file reaches
     * the domain here or not at all. An unfinished reset, or a batch already running, refuses the
     * attempt — the batch's own items alone are no re-entry — and a throw is answered with its fault. */
    private boolean carryOutOwningReset(ResetAttempt attempt, RequestItem item) {
        synchronized (resetMailbox) {
            if (resetFaultLine != ResetTrace.NONE
                || (resetBatchOpen && attempt != ResetAttempt.BATCH_ITEM)) {
                return false;
            }
        }
        CommitController controller = this.commitController;
        if (controller != null && !controller.enterReset().granted()) {
            return false;
        }
        try {
            clearReadings();
        } catch (Throwable failure) {
            holdResetFault(item, failure);
            throw failure;
        }
        return true;
    }

    /** What one request for a whole reset did: whether the safe point carried it out, where it came from,
     * and whether it entered the bounded queue of requests the owner has not answered. Entering that
     * queue is not a completed hand-off: the owner answers it at a safe point. */
    public record ResetRequest(boolean carriedOut, String source, boolean handedOff, String reason) {
    }

    /** What the owner safe point did with the requests that were waiting for it: how many it carried out,
     * how many are still owed because the controller refused again, the depth left behind, and whether
     * the last one waited on a disposition instead. */
    public record SafePoint(int carried, int owed, int queued, boolean waitsForDisposition) {
    }

    /** When the host stops the server it asks the chain to close at a point it names. Until an entry
     * makes that call nothing in this tree closes a chain. */
    public static final String HOST_CLOSE_ENTRY = "host:server-stopping";

    /** One thing the host does at the tick boundary: it drains the resets other threads asked for. Until
     * an entry makes that call nothing drains the queue, and the readout says NOT_WIRED for it. */
    public static final String HOST_SAFE_POINT_ENTRY = "host:tick-boundary";

    /** The two sessions that ask this module for a whole reset on a real path: the command face and
     * the configuration reload. A test and a probe are neither of them. */
    public static final String HOST_COMMAND_RESET_SOURCE = "host:kernel-command";
    public static final String HOST_RELOAD_RESET_SOURCE = "host:config-reload";

    /** Where the identity of the owner thread is read from: the host's own answer about the thread it
     * runs the server on, never the name of a thread. */
    public static final String HOST_OWNER_THREAD_SOURCE = "server.getRunningThread";

    /** The generation column of a request that carried no generation, because no chain was bound when
     * it was answered. */
    public static final long NO_CARRIED_EPOCH = -1L;

    /** The two sources a delivery can name, read from the path that raised it: the platform posts while
     * the world is still in the level map, this tree posts after the removal path took it out. Neither
     * fact readable names the refusal below, which is never a guess. */
    public static final String PLATFORM_UNLOAD_SOURCE = "platform:stop-unload-post";
    public static final String HOST_UNLOAD_SOURCE = "host:world-remove";
    public static final String UNKNOWN_UNLOAD_SOURCE = "UNKNOWN_POST_PATH";

    /** The owner that takes a world's tokens back: the stop flush, never the delivery itself. */
    public static final String HOST_STOP_FLUSH_OWNER = "host:stop-flush";

    /** What a second delivery of one world in one stop entry is written as. */
    public static final String UNLOAD_DUPLICATE = "duplicate-delivery";

    /** What a missing cell reads, so that it is never the zero a count would be. */
    private static final String NEVER_RUN_CELL = "NEVER_RUN";
    private static final String NOT_EXECUTABLE_CELL = "NOT_EXECUTABLE";

    /** How many worlds one stop flush keeps rows for: the set the host carries is small, and a row per
     * world of one stop is what a diagnostic reads back. */
    private static final int STOP_FLUSH_ROW_CAP = 64;


    public long commitSealedTurn() {
        return commitSealedTurn;
    }

    /** How many requests the bounded queue of the owner holds right now. */
    public int resetQueueDepth() {
        synchronized (resetMailbox) {
            return resetQueue.size();
        }
    }


    public List<String> resetQueueSources() {
        synchronized (resetMailbox) {
            return waitingSources();
        }
    }

    /** One consistent read of the queue's three exits: a caller that reads them one at a time can
     * see a safe point between them, and this read cannot. */
    Mailbox mailbox() {
        synchronized (resetMailbox) {
            List<String> sources = waitingSources();
            return new Mailbox(sources.size(), sources, owesAReset(),
                commitResetRequests, commitResetHandOffs, commitResetMerges, commitResetAdmits,
                commitResetQueueRefusals, commitResetClosedRefusals, resetInProgress);
        }
    }

    private List<String> waitingSources() {
        List<String> sources = new ArrayList<>(resetQueue.size());
        for (RequestItem item : resetQueue) {
            sources.add(item.source());
        }
        return List.copyOf(sources);
    }

    /** Whether the mailbox still owes an answer. Read under the mailbox lock. */
    private boolean owesAReset() {
        return resetDeferred || !resetQueue.isEmpty() || resetBatchOpen
            || resetFaultLine != ResetTrace.NONE;
    }


    public static int resetQueueCapacity() {
        return RESET_QUEUE_CAPACITY;
    }

    /** The one request entry for a whole reset, named after where it came from. A request on the thread
     * the host named is answered there; one on any other thread enters a bounded queue, refused outright
     * when that queue is full, and the caller is told to ask on the owner thread. What waits is answered
     * by {@link #markSafePoint()}, one bounded batch of it per call; the identity of an owner-thread
     * request is minted before the reset runs, so a late answer is still read as the arrival it was. */
    public ResetRequest requestReadingsReset(String source) {
        String named = source == null || source.isEmpty() ? "unnamed" : source;
        if (!guard.serverThreadBound()) {
            return publishRequest(named, null, "owner-thread-unbound", false, false);
        }
        if (!guard.onServerThread()) {
            return queueResetRequest(named);
        }
        ResetRequest merged = mergeOwningReset(named);
        if (merged != null) {
            return merged;
        }
        RequestItem item = mintItem(named);
        if (carryOutOwningReset(ResetAttempt.OUTSIDE_BATCH, item)) {
            return publishRequest(named, item, "reset-at-safe-point", true, false);
        }
        if (!commitChainClosed()) {
            deferOwningReset(item);
            return publishRequest(named, item, "deferred", false, false);
        }
        return publishRequest(named, item, "lifecycle-closed", false, false);
    }

    /** Counts one call through the request entry and publishes the line it ended on. */
    private ResetRequest publishRequest(String source, RequestItem item, String result, boolean carriedOut,
                                        boolean handedOff) {
        long answeredBy = 0L;
        long epoch = NO_CARRIED_EPOCH;
        if (carriedOut) {
            answeredBy = Thread.currentThread().threadId();
            CommitController controller = this.commitController;
            epoch = controller == null ? NO_CARRIED_EPOCH : controller.epoch();
        }
        synchronized (resetMailbox) {
            commitResetRequests++;
            long requestId = item == null ? ++resetRequestSequence : item.requestId();
            if (theLineMayBeWrittenFor(requestId)) {
                resetTrace = new ResetTrace(source, requestId, Thread.currentThread().threadId(),
                    answeredBy, epoch, result);
            }
        }
        return new ResetRequest(carriedOut, source, handedOff, result);
    }

    /** Mints one logical request's identity: its id and the turn it arrived in, before the caller acts. */
    private RequestItem mintItem(String source) {
        synchronized (resetMailbox) {
            return new RequestItem(source, ++resetRequestSequence, Thread.currentThread().threadId(),
                ++resetArrivals);
        }
    }

    /** The whole merge an owner reset has: a call finding one reset already owed still unanswered mints
     * nothing and keeps that item's source, id and arrival. */
    private ResetRequest mergeOwningReset(String source) {
        synchronized (resetMailbox) {
            if (resetMailboxClosed || !resetDeferred) {
                return null;
            }
            commitResetRequests++;
            return new ResetRequest(false, source, false, "deferred");
        }
    }

    /** The whole other-thread path as one atomic step: merged, refused, or enqueued. Only an item
     * still waiting is a merge candidate, and a call that joins one does not take the trace over. */
    private ResetRequest queueResetRequest(String source) {
        long thread = Thread.currentThread().threadId();
        synchronized (resetMailbox) {
            commitResetRequests++;
            commitResetHandOffs++;
            if (resetMailboxClosed) {
                commitResetClosedRefusals++;
                resetTrace = new ResetTrace(source, ++resetRequestSequence, thread, 0L,
                    NO_CARRIED_EPOCH, "lifecycle-closed");
                return new ResetRequest(false, source, false, "lifecycle-closed");
            }
            for (RequestItem waiting : resetQueue) {
                if (waiting.source().equals(source)) {
                    commitResetMerges++;
                    return new ResetRequest(false, source, true, "queued-for-owner-safe-point");
                }
            }
            if (resetQueue.size() + resetInProgress >= RESET_QUEUE_CAPACITY) {
                commitResetQueueRefusals++;
                resetTrace = new ResetTrace(source, ++resetRequestSequence, thread, 0L,
                    NO_CARRIED_EPOCH, "owner-thread-queue-full");
                return new ResetRequest(false, source, false, "owner-thread-queue-full");
            }
            RequestItem item = new RequestItem(source, ++resetRequestSequence, thread, ++resetArrivals);
            resetQueue.addLast(item);
            commitResetAdmits++;
            resetTrace = new ResetTrace(source, item.requestId(), thread, 0L, NO_CARRIED_EPOCH,
                "queued-for-owner-safe-point");
            return new ResetRequest(false, source, true, "queued-for-owner-safe-point");
        }
    }

    /** Records the one reset an owner-thread call could not carry out; the item keeps its own identity. */
    private void deferOwningReset(RequestItem item) {
        synchronized (resetMailbox) {
            resetDeferred = true;
            resetDeferredItem = item;
        }
    }

    /** Writes one answer into the line of the item it belongs to, while that line is still its own. */
    private void noteResetAnswered(RequestItem item, String result) {
        long thread = Thread.currentThread().threadId();
        CommitController controller = this.commitController;
        long epoch = controller == null ? NO_CARRIED_EPOCH : controller.epoch();
        synchronized (resetMailbox) {
            if (item == null) {
                if (resetTrace.answeredByThreadId() == 0L && resetQueue.isEmpty() && !resetDeferred
                    && resetInProgress == 0) {
                    resetTrace = new ResetTrace(resetTrace.source(), resetTrace.requestId(),
                        resetTrace.requestThreadId(), thread, epoch, result);
                }
                return;
            }
            if (!theLineMayBeWrittenFor(item.requestId())) {
                return;
            }
            resetTrace = new ResetTrace(item.source(), item.requestId(), item.arrivalThreadId(), thread,
                epoch, result);
        }
    }

    /** Whether the line the trace holds is a fault instead of a request's answer. */
    private boolean traceHoldsAFault() {
        return resetTrace.result().startsWith(RESET_FAULTED);
    }

    /** Whether the mailbox may write a line for {@code requestId}: never back to a request that arrived
     * before the one it names; a line holding a fault is no item's line. Under the mailbox lock. */
    private boolean theLineMayBeWrittenFor(long requestId) {
        return requestId >= resetTrace.requestId() || traceHoldsAFault();
    }

    /** Answers what the queue of the owner holds, at a safe point and nowhere else. One call carries
     * out one batch of at most the capacity: taken in one atomic step, run outside the lock, so a
     * producer that keeps refilling the queue cannot make this call run past its budget. */
    public SafePoint markSafePoint() {
        if (!guard.serverThreadBound() || !guard.onServerThread()) {
            return mailboxSnapshot();
        }
        return drainQueuedResets();
    }

    /** One consistent read of what the queue holds, for a caller that is not the owner. */
    private SafePoint mailboxSnapshot() {
        synchronized (resetMailbox) {
            int depth = resetQueue.size();
            return new SafePoint(0, depth, depth, false);
        }
    }

    /** One batch, one pass. The deferred request is answered first and holds one place of this budget,
     * so at most the capacity including it is started here. An item refused again keeps its identity and
     * goes back to the head of the queue; one a closed lifecycle refuses is dropped and counted. A call
     * already inside a batch, or one reaching an unfinished mailbox, is refused instead. */
    private SafePoint drainQueuedResets() {
        List<RequestItem> batch = new ArrayList<>(RESET_QUEUE_CAPACITY);
        RequestItem deferred;
        boolean deferredOwed;
        synchronized (resetMailbox) {
            if (resetBatchOpen || resetFaultLine != ResetTrace.NONE) {
                return new SafePoint(0, resetQueue.size(), resetQueue.size(), false);
            }
            commitSafePointCalls++;
            deferredOwed = resetDeferred;
            deferred = resetDeferredItem;
            resetDeferred = false;
            resetDeferredItem = null;
            int budget = deferredOwed ? RESET_QUEUE_CAPACITY - 1 : RESET_QUEUE_CAPACITY;
            while (batch.size() < budget && !resetQueue.isEmpty()) {
                batch.add(resetQueue.removeFirst());
            }
            resetInProgress = batch.size();
            resetBatchOpen = true;
        }
        int carried = 0;
        int owed = 0;
        int queued = 0;
        boolean disposition = false;
        boolean closed = false;
        boolean stillOwed = false;
        int attempted = 0;
        List<RequestItem> retry = new ArrayList<>();
        try {
            if (deferredOwed) {
                if (carryOutOwningReset(ResetAttempt.BATCH_ITEM, deferred)) {
                    carried++;
                    noteResetAnswered(deferred, "reset-at-safe-point");
                } else if (commitChainClosed()) {
                    closed = true;
                    noteResetAnswered(deferred, "lifecycle-closed");
                } else {
                    owed++;
                    stillOwed = true;
                    disposition |= waitingDispositions();
                }
            }
            for (RequestItem item : batch) {
                attempted++;
                if (carryOutOwningReset(ResetAttempt.BATCH_ITEM, item)) {
                    carried++;
                    noteResetAnswered(item, "reset-at-safe-point");
                    continue;
                }
                if (commitChainClosed()) {
                    closed = true;
                    noteResetAnswered(item, "lifecycle-closed");
                    continue;
                }
                owed++;
                disposition |= waitingDispositions();
                retry.add(item);
            }
        } catch (Throwable failure) {
            // The gate answered the faulty item; a fatal Error is handed on, a RuntimeException is settled here.
            if (failure instanceof Error fatal) {
                throw fatal;
            }
        } finally {
            synchronized (resetMailbox) {
                resetInProgress = 0;
                resetBatchOpen = false;
                for (int index = attempted; index < batch.size(); index++) {
                    retry.add(batch.get(index));
                }
                for (int index = retry.size() - 1; index >= 0; index--) {
                    resetQueue.addFirst(retry.get(index));
                }
                if (stillOwed && !resetDeferred) {
                    resetDeferred = true;
                    resetDeferredItem = deferred;
                } else if (stillOwed) {
                    // The deferred place was taken from inside the batch: the older item keeps its own place.
                    retry.add(0, deferred);
                }
                if (closed) {
                    resetMailboxClosed = true;
                }
                queued = resetQueue.size();
            }
        }
        return new SafePoint(carried, owed, queued, disposition);
    }

    /** What the trace of a request whose reset threw names: the fault it hit. */
    private static String resetFault(Throwable failure) {
        return RESET_FAULTED + ":" + failure.getClass().getSimpleName();
    }

    /** Holds one reset that threw until that chain is given back, in the line it is answered with. */
    private void holdResetFault(RequestItem item, Throwable failure) {
        long thread = Thread.currentThread().threadId();
        CommitController controller = this.commitController;
        long epoch = controller == null ? NO_CARRIED_EPOCH : controller.epoch();
        synchronized (resetMailbox) {
            ResetTrace line = item == null
                ? new ResetTrace("", resetTrace.requestId(), thread, thread, epoch,
                    resetFault(failure))
                : new ResetTrace(item.source(), item.requestId(), item.arrivalThreadId(), thread, epoch,
                    resetFault(failure));
            resetTrace = line;
            resetFaultLine = line;
        }
    }

    /** Whether the chain holds an item whose side effect ran and whose line is not written. */
    private boolean waitingDispositions() {
        CommitController controller = this.commitController;
        return controller != null && controller.reservations().pendingCount() > 0;
    }

    /** Takes what the mailbox holds when the chain is closed here: one atomic step, every later
     * arrival refused, and each item it held counted as a closed refusal rather than read as carried. */
    private void disposeMailboxAtClose() {
        List<RequestItem> batch = new ArrayList<>(RESET_QUEUE_CAPACITY);
        RequestItem deferred;
        boolean deferredOwed;
        synchronized (resetMailbox) {
            resetMailboxClosed = true;
            deferredOwed = resetDeferred;
            deferred = resetDeferredItem;
            resetDeferred = false;
            resetDeferredItem = null;
            while (!resetQueue.isEmpty()) {
                batch.add(resetQueue.removeFirst());
            }
        }
        if (deferredOwed) {
            carryOutOwningReset(ResetAttempt.OUTSIDE_BATCH, deferred);
            noteResetAnswered(deferred, "lifecycle-closed");
        }
        for (RequestItem item : batch) {
            carryOutOwningReset(ResetAttempt.OUTSIDE_BATCH, item);
            noteResetAnswered(item, "lifecycle-closed");
        }
    }

    /** What one reset does inside a chain that has already been closed, read as one line per state. */
    public record ResetComposition(boolean chainClosed, boolean acceptsWork, int dispositionOwed,
                                   boolean clearAllowed, String blockedBy, boolean owedAtSafePoint) {
    }

    /** The composition of a reset with the close of the chain, read instead of assumed. An open chain
     * accepts work and a clean safe point clears. A closed one refuses every generation for good, and the
     * two cases are told apart by what it holds: an item waiting for its line blocks the reset and stays
     * readable with its room, and nothing owed blocks it because a closed lifecycle starts no generation. */
    public ResetComposition describeResetComposition() {
        CommitController controller = this.commitController;
        if (controller == null) {
            return new ResetComposition(false, true, 0, false, "not-wired", false);
        }
        CommitReservation reservations = controller.reservations();
        int dispositions = reservations.pendingCount();
        boolean closed = reservations.closed();
        String blockedBy = closed
            ? (dispositions > 0 ? "closed-with-pending-dispositions" : "lifecycle-closed")
            : "open";
        return new ResetComposition(closed, !closed && dispositions == 0, dispositions,
            !closed && dispositions == 0
                && reservations.publishing() == 0 && controller.dispositionGateReason().isEmpty(),
            blockedBy, !mailbox().sources().isEmpty());
    }


    /** One world's turn in the stop flush: the four steps taken in one session on the owner thread,
     * each stamped with the step it was taken as, so the order of a run is read and not assumed. A world
     * whose generation is absent or was never tracked is refused whole. */
    public record WorldStopFlush(String worldId, long worldEpoch, String refusal, long dispatchStep,
                                 long flushStep, long unloadRowStep, long reclaimStep, int flushed,
                                 int reclaimed) {

        /** The four step stamps of this row, in the order the session took them. */
        public String steps() {
            return dispatchStep + ">" + flushStep + ">" + unloadRowStep + ">" + reclaimStep;
        }

        /** Whether the session took the four steps in the order the set names, which is what the row
         * is read for: a run that reorders them shows it here. */
        public boolean ordered() {
            return dispatchStep < flushStep && flushStep < unloadRowStep
                && unloadRowStep < reclaimStep;
        }
    }

    /** Flushes one world through the four step set because the server is stopping: the unload event is
     * not delivered on this path and this tree calls removeLevel nowhere, so a world is walked here or
     * nowhere. The world stops taking new work, its waiting writes are handed to the payload, the row of
     * its unload is written, and what the flush could not land goes back. */
    public WorldStopFlush flushWorldAtStop(String worldId) {
        stopFlushLastSequence = ++hostEntrySequence;
        String named = worldId == null || worldId.isEmpty() ? "unnamed" : worldId;
        stopFlushEntries.put(named, stopFlushLastSequence);
        long epoch = guard.worldEpochs().epochOf(named);
        if (epoch == WorldEpochs.UNLIVE || epoch == WorldEpochs.UNTRACKED) {
            commitStopFlushRefusals++;
            commitStopFlushRefusal = epoch == WorldEpochs.UNLIVE ? "world-not-live" : "world-untracked";
            return noteStopFlush(new WorldStopFlush(named, epoch, commitStopFlushRefusal, 0L, 0L, 0L,
                0L, 0, 0));
        }
        long dispatchStep = ++stopStepSequence;
        guard.worldEpochs().forget(named);
        long flushStep = ++stopStepSequence;
        int flushed = flushWorldIntents(named);
        long unloadRowStep = ++stopStepSequence;
        commitStopFlushSessions++;
        if (stopFlushWorlds.size() < STOP_FLUSH_ROW_CAP) {
            stopFlushWorlds.add(named);
        }
        long reclaimStep = ++stopStepSequence;
        int reclaimed = intents.releaseWorld(named);
        return noteStopFlush(new WorldStopFlush(named, epoch, "", dispatchStep, flushStep, unloadRowStep,
            reclaimStep, flushed, reclaimed));
    }

    /** Step two of the set: the intents still waiting for one world are handed to the payload in the
     * frozen order of its shard; what is left is what step four takes back. */
    private int flushWorldIntents(String worldId) {
        int flushed = 0;
        for (long head = intents.headOrder(worldId); head >= 0L; head = intents.headOrder(worldId)) {
            if (!intents.commit(worldId, head, tickIndex).committed()) {
                break;
            }
            flushed++;
        }
        return flushed;
    }

    private WorldStopFlush noteStopFlush(WorldStopFlush row) {
        if (stopFlushRows.size() >= STOP_FLUSH_ROW_CAP) {
            stopFlushRows.remove(0);
        }
        stopFlushRows.add(row);
        return row;
    }

    /** Whether every flush row took the four steps in order; a dash means no world was flushed yet. */
    public String commitStopFlushOrdered() {
        int served = 0;
        for (WorldStopFlush row : stopFlushRows) {
            if (row.refusal().isEmpty()) {
                served++;
                if (!row.ordered()) {
                    return "0";
                }
            }
        }
        return served == 0 ? "-" : "1";
    }

    /** The order the newest entries of the two kinds were entered in, read from the sequence they
     * carry: which of the flush and the close arrived first is a fact of the run, not of this code. */
    public String commitStopFlushOrder() {
        boolean flushed = stopFlushLastSequence != 0L;
        boolean closed = stopCloseLastSequence != 0L;
        if (flushed && closed) {
            return stopCloseLastSequence > stopFlushLastSequence ? "flush-before-close"
                : "close-before-flush";
        }
        if (flushed) {
            return "flush-only";
        }
        return closed ? "close-only" : "NOT_WIRED";
    }

    public List<WorldStopFlush> commitStopFlushRows() {
        return List.copyOf(stopFlushRows);
    }

    public List<String> commitStopFlushWorlds() {
        return List.copyOf(stopFlushWorlds);
    }

    public long commitStopFlushSessions() {
        return commitStopFlushSessions;
    }

    public long commitStopFlushRefusals() {
        return commitStopFlushRefusals;
    }

    /** The refusal of the newest flush, or an empty string while none was refused. */
    public String commitStopFlushRefusal() {
        return commitStopFlushRefusal;
    }

    /** One world's unload delivery: the path that raised it, the generation and flush entry the shared
     * control held, what the shared flush took back and what this delivery took back. A cell it cannot
     * fill reads NEVER_RUN or NOT_EXECUTABLE and is never a zero. */
    public record WorldUnloadDelivery(String worldId, String source, long worldEpoch,
                                      long flushEntry, String steps, String flushRefusal,
                                      long stopEntry, long epochAtDelivery, int reclaimedByFlush,
                                      int reclaimedByDelivery, long tokenReleases, long tokensWaiting,
                                      boolean duplicate) {

        /** Whether this row is the one delivery of its world in its stop entry. */
        public boolean delivered() {
            return !duplicate;
        }

        /** The generation the shared flush took off the world, or the refusal it read as. */
        public String worldEpochCell() {
            return flushEntry == 0L ? NEVER_RUN_CELL : epochName(worldEpoch);
        }

        /** The reading a delayed write carrying the old generation gets: a refusal, never a zero. */
        public String oldGenerationCell() {
            return epochName(epochAtDelivery);
        }

        /** Who took what back: the shared flush, never this delivery. The arena and the reservation
         * groups are not per world and are named here rather than given a number of their own. */
        public String reclaimCell() {
            boolean served = flushRefusal.isEmpty();
            return "owner:" + (served ? HOST_STOP_FLUSH_OWNER : NOT_EXECUTABLE_CELL)
                + "|byFlush:" + (served ? Integer.toString(reclaimedByFlush) : NOT_EXECUTABLE_CELL)
                + "|byDelivery:" + reclaimedByDelivery + "|arena:NOT_PER_WORLD"
                + "|reservation:NOT_PER_WORLD|intent.released:" + tokenReleases
                + "|intent.queue_depth:" + tokensWaiting;
        }

        /** One line per delivery: the form the console copy, the reading cell and the evidence hold. */
        public String row() {
            return "world=" + worldId + "|worldEpoch=" + worldEpochCell()
                + "|source=" + source
                + "|stopEntry=" + (stopEntry == 0L ? NEVER_RUN_CELL : Long.toString(stopEntry))
                + "|flushEntry=" + (flushEntry == 0L ? NEVER_RUN_CELL : Long.toString(flushEntry))
                + "|flushSteps=" + steps + "|reclaim=" + reclaimCell()
                + "|oldGeneration=" + oldGenerationCell() + "|delivery="
                + (duplicate ? UNLOAD_DUPLICATE : "TRIGGERED");
        }
    }

    /** Keeps the source of a delivery inside the closed set: anything else is the named refusal, so a
     * delivery cannot be labelled by a literal its caller made up. */
    public static String closedSource(String source) {
        if (PLATFORM_UNLOAD_SOURCE.equals(source) || HOST_UNLOAD_SOURCE.equals(source)) {
            return source;
        }
        return UNKNOWN_UNLOAD_SOURCE;
    }

    private static String epochName(long epoch) {
        if (epoch == WorldEpochs.UNLIVE) {
            return "UNLIVE(" + WorldEpochs.UNLIVE + ")";
        }
        if (epoch == WorldEpochs.UNTRACKED) {
            return "UNTRACKED(" + WorldEpochs.UNTRACKED + ")";
        }
        return Long.toString(epoch);
    }

    /** Records one world's unload delivery, under the source the path that raised it reported. The
     * generation, the flush entry and the tokens come from the lifecycle control the tick, the stop
     * flush and the close already share, and are read only. A second delivery of the same world in the
     * same stop entry is counted and written as a repeat. */
    public WorldUnloadDelivery noteWorldUnload(String worldId, String source) {
        String named = worldId == null || worldId.isEmpty() ? "unnamed" : worldId;
        String namedSource = closedSource(source);
        long stopEntry = stopCloseLastSequence;
        long tokensBefore = intents.releasedCount();
        long epochsBefore = guard.worldEpochs().epochChanges();
        WorldStopFlush flush = stopFlushRowOf(named);
        long epochAtDelivery = guard.worldEpochs().epochOf(named);
        boolean duplicate = deliveredBefore(stopEntry, named);
        long tokensAfter = intents.releasedCount();
        if (PLATFORM_UNLOAD_SOURCE.equals(namedSource)) {
            unloadPlatformPosts++;
        } else if (HOST_UNLOAD_SOURCE.equals(namedSource)) {
            unloadHostRemovePosts++;
        }
        WorldUnloadDelivery row = new WorldUnloadDelivery(named, namedSource,
            flush == null ? epochAtDelivery : flush.worldEpoch(),
            stopFlushEntries.getOrDefault(named, 0L), flush == null ? "-" : flush.steps(),
            flush == null ? NEVER_RUN_CELL : flush.refusal(), stopEntry, epochAtDelivery,
            flush == null ? 0 : flush.reclaimed(), (int) (tokensAfter - tokensBefore), tokensAfter,
            intents.depth(named), duplicate);
        if (guard.worldEpochs().epochChanges() != epochsBefore) {
            unloadEpochAdvances++;
        }
        if (tokensAfter != tokensBefore) {
            unloadTokenReclaims++;
        }
        if (duplicate) {
            unloadDuplicateCount++;
        }
        unloadStopEntry = stopEntry;
        keepUnloadRow(row);
        System.out.println("[PRTS] unload delivery run=" + CloseSealWriter.forRun().runId() + " " + row.row());
        return row;
    }

    private WorldStopFlush stopFlushRowOf(String worldId) {
        for (int index = stopFlushRows.size() - 1; index >= 0; index--) {
            if (stopFlushRows.get(index).worldId().equals(worldId)) {
                return stopFlushRows.get(index);
            }
        }
        return null;
    }

    private boolean deliveredBefore(long stopEntry, String worldId) {
        for (WorldUnloadDelivery row : unloadRows) {
            if (row.stopEntry() == stopEntry && row.worldId().equals(worldId)) {
                return true;
            }
        }
        return false;
    }

    private void keepUnloadRow(WorldUnloadDelivery row) {
        if (unloadRows.size() >= STOP_FLUSH_ROW_CAP) {
            unloadRows.remove(0);
        }
        unloadRows.add(row);
    }

    public void installUnloadDelivery() {
        unloadDeliveryInstalled = true;
    }

    /** Removes the recording again, so a rollback claims no delivery behind it. */
    public void removeUnloadDelivery() {
        unloadDeliveryInstalled = false;
    }

    /** Whether this process records the unload deliveries of the worlds its host unloads. */
    public String unloadWiring() {
        return unloadDeliveryInstalled ? "WIRED" : "NOT_WIRED";
    }

    /** Every delivery of this process in the order it was recorded. */
    public List<WorldUnloadDelivery> unloadDeliveries() {
        return List.copyOf(unloadRows);
    }

    /** How many deliveries the platform raised in its own stopping walk. */
    public long unloadPlatformPosts() {
        return unloadPlatformPosts;
    }

    /** How many deliveries this tree raised on its world removal path. */
    public long unloadHostRemovePosts() {
        return unloadHostRemovePosts;
    }

    /** How many deliveries repeated a world their stop entry had already delivered. */
    public long unloadDuplicates() {
        return unloadDuplicateCount;
    }

    /** How many deliveries found the shared generation moved while they ran; must stay 0. */
    public long unloadEpochAdvances() {
        return unloadEpochAdvances;
    }

    /** How many deliveries found the shared token count moved while they ran; must stay 0. */
    public long unloadTokenReclaims() {
        return unloadTokenReclaims;
    }

    /** The stop entry the recorded deliveries belong to, or zero while no close entry has run. */
    public long unloadStopEntry() {
        return unloadStopEntry;
    }

    /** The most rows one world carries in one stop entry: the criterion is at most one. */
    public long unloadMaxPerWorld() {
        long most = 0L;
        for (WorldUnloadDelivery row : unloadRows) {
            long count = 0L;
            for (WorldUnloadDelivery other : unloadRows) {
                if (other.stopEntry() == row.stopEntry() && other.worldId().equals(row.worldId())) {
                    count++;
                }
            }
            most = Math.max(most, count);
        }
        return most;
    }

    /** How often the owner safe point was entered by the thread the host named. */
    public long commitSafePointCalls() {
        synchronized (resetMailbox) {
            return commitSafePointCalls;
        }
    }

    /** Reads the thread the host itself names as the one it runs the server on; a tick that arrives on
     * another thread is counted here instead of being taken for the owner. */
    public void noteTickThread(Thread hostThread) {
        if (hostThread == null) {
            return;
        }
        commitOwnerThreadNamed++;
        if (hostThread == Thread.currentThread()) {
            commitOwnerThreadId = hostThread.threadId();
        } else {
            commitOwnerThreadMismatches++;
        }
    }

    public long commitOwnerThreadNamed() {
        return commitOwnerThreadNamed;
    }

    public long commitOwnerThreadMismatches() {
        return commitOwnerThreadMismatches;
    }

    /** The identity of the thread the host named, zero while it named none. */
    public long commitOwnerThreadId() {
        return commitOwnerThreadId;
    }

    /** The line the readout holds now: the fault while one is owed, the newest request otherwise. */
    private ResetTrace heldTrace() {
        ResetTrace fault = resetFaultLine;
        return fault == ResetTrace.NONE ? resetTrace : fault;
    }

    /** What the newest request for a whole reset reads back as: where it came from, the thread that made
     * it, the thread that answered it, the generation it carried, and what came of it. */
    public String commitResetTrace() {
        ResetTrace trace = heldTrace();
        String answeredBy = trace.answeredByThreadId() == 0L ? "-"
            : Long.toString(trace.answeredByThreadId());
        String epoch = trace.carriedEpoch() == NO_CARRIED_EPOCH ? "none"
            : Long.toString(trace.carriedEpoch());
        String source = trace.source().isEmpty() ? "-" : trace.source();
        String result = trace.result().isEmpty() ? "-" : trace.result();
        return source + "|" + trace.requestThreadId() + "|" + answeredBy + "|" + epoch + "|" + result;
    }

    /** Whether the newest request was made on one thread and answered on another. */
    public boolean commitResetCrossedThreads() {
        ResetTrace trace = heldTrace();
        return trace.requestThreadId() != 0L && trace.answeredByThreadId() != 0L
            && trace.requestThreadId() != trace.answeredByThreadId();
    }

    /** What one close of the commit chain did: the room it gave back, the items that keep theirs,
     * and whether the chain is closed after the call. */
    public record CommitClose(boolean closed, String refusal, int givenBack, int unrecorded,
                              int publishing) {
    }

    /** Ends the commit chain this module holds, and only that one: it is asked through the bound
     * controller, so the module clears no lifecycle of its own while a chain is adopted. A close is
     * idempotent, it gives back only the reservations that never started, it keeps every item whose side
     * effect may have run readable with its room, and it writes no line of its own. An item whose line is
     * not written is sealed instead, with the identity a diagnostic reads back. */
    public CommitClose closeCommitChain() {
        stopCloseLastSequence = ++hostEntrySequence;
        CommitController controller = this.commitController;
        if (controller == null) {
            return new CommitClose(false, "not-wired", 0, 0, 0);
        }
        commitCloseCalls++;
        CommitReservation.CloseResult close = controller.close();
        commitCloseGivenBack = close.givenBack();
        commitCloseUnrecorded = close.unrecorded();
        commitClosePublishing = close.publishing();
        boolean closed = controller.reservations().closed();
        if (closed) {
            sealUnrecordedItems(controller.reservations());
            disposeMailboxAtClose();
        }
        return new CommitClose(closed, closed ? "" : "off-owner-thread", close.givenBack(),
            close.unrecorded(), close.publishing());
    }

    /** What one disposition of a sealed item did: whether the owner's line was written and what is
     * still owed under the seal. */
    public record Disposition(long ticketId, String step, boolean finalized, boolean sealed,
                              int stillOwed, RejectCode code, String diagnostic) {
    }

    /** One item the close sealed: the ticket a diagnostic reads back, the place it holds, why its
     * line is not written and how often it was asked for. The identity is carried, not reconstructed:
     * the ticket is the same instance the owner must hand its line to. */
    private void sealUnrecordedItems(CommitReservation reservations) {
        commitSealedCount = reservations.pendingCount();
        if (commitSealedCount == 0) {
            // This close sealed nothing, and the readout is about this close.
            commitSealed = false;
            commitSealedTurn = 0L;
            commitSealedIdentities.clear();
            return;
        }
        commitSealed = true;
        commitSealedTurn = tickIndex;
        commitSealedIdentities.clear();
        for (CommitReservation.Pending pending : reservations.pendingDispositions()) {
            commitSealedIdentities.add(new Disposition(pending.ticketId(), "sealed-at-close", false,
                true, commitSealedCount, pending.code(), pending.diagnostic()));
        }
    }

    /** The disposition the owner handed one sealed item, or a refusal that changes nothing. This is
     * the only step that ends a sealed item, it is taken by the owner thread, and it is never retried
     * on its own: the host asks for it, or the item stays sealed with its room. */
    public Disposition disposeCommitDisposition(long ticketId, CommitTicket.Outcome outcome) {
        CommitController controller = this.commitController;
        if (controller == null) {
            return new Disposition(ticketId, "not-wired", false, true, 0, null, "not-wired");
        }
        CommitReservation reservations = controller.reservations();
        for (CommitReservation.Pending pending : reservations.pendingDispositions()) {
            if (pending.ticketId() != ticketId) {
                continue;
            }
            CommitTicket ticket = reservations.ticket(pending.ticketId());
            if (ticket == null) {
                commitSealDiscards++;
                return new Disposition(ticketId, "sealed-without-ticket", false, true,
                    reservations.pendingCount(), null, "sealed-without-ticket");
            }
            CommitReservation.FinalizeResult done = reservations.finalize(ticket, outcome);
            if (done.finalized()) {
                commitSealed = reservations.pendingCount() > 0;
                commitSealedCount = reservations.pendingCount();
                commitSealedIdentities.removeIf(entry -> entry.ticketId() == ticketId);
                return new Disposition(ticketId, "disposed-by-owner", true, commitSealed,
                    commitSealedCount, null, "recorded");
            }
            return new Disposition(ticketId, "refused", false, true, reservations.pendingCount(),
                done.code(), done.diagnostic());
        }
        return new Disposition(ticketId, "not-pending", false, commitSealed,
            reservations.pendingCount(), null, "not-pending");
    }

    /** Whether a close sealed an item whose line is not written and that no one has disposed of. */
    public boolean commitChainSealed() {
        return commitSealed;
    }

    /** How many sealed items the newest close left. */
    public int commitSealedCount() {
        return commitSealedCount;
    }

    /** The identities of the sealed items, oldest first. */
    public List<Disposition> commitSealedIdentities() {
        return List.copyOf(commitSealedIdentities);
    }

    /** The items the chain holds right now for a disposition, whether or not a close sealed them. */
    public List<Disposition> commitChainDispositions() {
        CommitController controller = this.commitController;
        if (controller == null) {
            return List.of();
        }
        List<Disposition> items = new ArrayList<>();
        for (CommitReservation.Pending pending : controller.reservations().pendingDispositions()) {
            items.add(new Disposition(pending.ticketId(), "pending", false, commitSealed,
                controller.reservations().pendingCount(), pending.code(), pending.diagnostic()));
        }
        return List.copyOf(items);
    }

    /** How often the bounded queue refused a request because it was full. */
    public long commitResetQueueRefusals() {
        synchronized (resetMailbox) {
            return commitResetQueueRefusals;
        }
    }

    /** How often the mailbox refused a request because the chain was already closed; the readout adds
     * this to the refusals the controller counts. */
    public long commitResetClosedRefusals() {
        synchronized (resetMailbox) {
            return commitResetClosedRefusals;
        }
    }

    /** How often an item was sealed with no ticket left to hand it a line. */
    public long commitSealDiscards() {
        return commitSealDiscards;
    }

    /** What one install of a whole chain did: the columns of the binding, or the piece that refused
     * it. A refusal names the piece and leaves every field of the module exactly where it was. */
    public record CommitBinding(boolean bound, String refusal, long logLifetime, long storeId) {
    }

    /** Adopts the one chain of this process at the tick boundary: built from the declaration, taken
     * whole or not at all, and at most once. A declaration that is not executable is refused before
     * any piece is built, since a store with no room is refused by its own constructor. */
    public CommitBinding adoptCommitChainOnce() {
        commitAdoptionCalls++;
        if (this.commitController != null) {
            return new CommitBinding(true, "", commitBindingLogLifetime, commitBindingStoreId);
        }
        KernelSettings.ProductCommit declared = KernelSettings.productCommit();
        if (!declared.executable()) {
            commitBindingRefusal = REFUSAL_DECLARED_FACE_OFF;
            return new CommitBinding(false, REFUSAL_DECLARED_FACE_OFF, 0L, 0L);
        }
        EffectStore store = new EffectStore(declared.effectCapacityBytes(),
            declared.effectCapacityRecords());
        CommitLog log = new CommitLog(KernelSettings::commitRingCap, commitOrderSource, store,
            commitIdentitySource);
        CommitReservation reservations = new CommitReservation(log, store, commitOrderSource,
            CHAIN_OWNER_ID, commitChainEpochs);
        // The publish action is absent by construction: an adopted chain is bound, not executable.
        return installCommitChain(log, new CommitController(reservations, null));
    }

    /** Adopts one whole chain — the log, the store that log carries and the holder that mints its
     * tickets — as the single commit chain of this module, on the owner thread of the host and only
     * while the declared face is executable. Every check runs before anything is taken, and a
     * candidate that fails one of them is refused as a whole: the module keeps the log it had, binds
     * no controller and reads NOT_WIRED. Two chains are told apart by reference, never by shape. */
    public CommitBinding installCommitChain(CommitLog candidate, CommitController controller) {
        CommitReservation reservations = controller == null ? null : controller.reservations();
        String refusal = checkChain(candidate, controller, reservations);
        if (refusal != null) {
            commitBindingRefusal = refusal;
            return new CommitBinding(false, refusal, 0L, 0L);
        }
        CommitController previous = this.commitController;
        this.commits = candidate;
        this.commitController = controller;
        if (previous != controller) {
            // An unfinished reset ends when its chain is taken off or another takes its place, not by reinstalling it.
            synchronized (resetMailbox) {
                resetMailboxClosed = false;
                resetFaultLine = ResetTrace.NONE;
            }
        }
        declarationGeneration++;
        declarationHistory.retire();
        this.commitBindingRefusal = "";
        this.commitBindingLogLifetime = candidate.logLifetime();
        this.commitBindingStoreId = reservations.store().storeId();
        return new CommitBinding(true, "", commitBindingLogLifetime, commitBindingStoreId);
    }

    private String checkChain(CommitLog candidate, CommitController controller,
                              CommitReservation reservations) {
        if (reservations == null) {
            return "controller-missing";
        }
        if (candidate == null) {
            return "chain-log-missing";
        }
        if (reservations.closed()) {
            // A closed lifecycle is terminal: it takes nothing new and starts no generation after it,
            // so a chain that has been closed is never adopted again and never becomes live again.
            return "chain-closed";
        }
        if (reservations.log() != candidate) {
            // Two chains of the same shape are the normal case and not the exception: both mint a
            // first ticket of one and name the same owner. Only the log the holder itself mints in
            // may become the log of this module.
            return "chain-log-mismatch";
        }
        EffectStore store = reservations.store();
        if (store == null || candidate.effects() != store) {
            return "chain-store-mismatch";
        }
        if (candidate.orderSource() != commitOrderSource) {
            // The plan authority of the adopted log has to be the one instance of this module, or
            // the order a commit is judged by is not the order this module froze.
            return "order-source-mismatch";
        }
        if (store.halted()) {
            return "store-halted";
        }
        if (store.storeId() == 0L || candidate.logLifetime() == 0L) {
            return "chain-identity-missing";
        }
        if (store.capacityBytes() <= 0 || store.capacityRecords() <= 0) {
            return "store-room-missing";
        }
        if (!guard.serverThreadBound()) {
            return "owner-thread-unbound";
        }
        if (!guard.onServerThread()) {
            return "off-owner-thread";
        }
        if (reservations.ownerThread() != Thread.currentThread()) {
            return "owner-thread-mismatch";
        }
        KernelSettings.ProductCommit declared = KernelSettings.productCommit();
        if (declared.schemaVersion() != CommitLog.SCHEMA_TYPED) {
            return "declared-schema-mismatch";
        }
        if (!declared.executable()) {
            return REFUSAL_DECLARED_FACE_OFF;
        }
        if (commitController != null && commitController != controller) {
            return "already-bound";
        }
        return null;
    }

    /** Gives the module back its own log. The chain it adopted stays exactly as it is: every item
     * that waits for a line and every line in flight keeps its room and stays readable, nothing is
     * disposed of, retried or reported as a success. A rollback is refused while such an obligation
     * is outstanding, so the evidence cannot be hidden by taking the module off the chain. */
    public boolean rollbackCommitChain() {
        CommitController controller = this.commitController;
        if (controller == null) {
            return false;
        }
        CommitReservation reservations = controller.reservations();
        if (reservations.publishing() > 0) {
            commitBindingRefusal = "rollback-publishing";
            return false;
        }
        if (reservations.pendingCount() > 0) {
            commitBindingRefusal = "rollback-pending-dispositions";
            return false;
        }
        this.commitController = null;
        this.commits = ownCommits;
        synchronized (resetMailbox) {
            resetMailboxClosed = false;
            resetFaultLine = ResetTrace.NONE;
        }
        declarationGeneration++;
        declarationHistory.retire();
        this.commitBindingRefusal = "";
        this.commitBindingLogLifetime = 0L;
        this.commitBindingStoreId = 0L;
        return true;
    }

    public CommitController commitController() {
        return commitController;
    }

    /** The one plan authority of this module: a caller that builds a chain to be adopted binds this
     * instance, and an install that finds another one refuses the candidate whole. */
    public CommitLog.OrderSource commitOrderSource() {
        return commitOrderSource;
    }

    /** Whether the log in use is the log of the chain that was adopted: the invariant of an install,
     * read back instead of assumed. */
    public boolean commitChainIsTheOneLog() {
        CommitController controller = this.commitController;
        return controller != null && controller.reservations().log() == commits;
    }

    /** Whether the plan authority of the log in use is the one instance of this module. */
    public boolean commitOrderSourceBound() {
        return commitController != null && commits.orderSource() == commitOrderSource;
    }

    /** The piece that refused the last install or rollback, or an empty string. */
    public String commitBindingRefusal() {
        return commitBindingRefusal;
    }

    /** The lifetime of the adopted log, zero while nothing is adopted. */
    public long commitBindingLogLifetime() {
        return commitBindingLogLifetime;
    }

    /** The identity of the adopted store, zero while nothing is adopted. */
    public long commitBindingStoreId() {
        return commitBindingStoreId;
    }

    /** Whether the host has named the thread the owner of a chain is judged by. Read where it is
     * bound and never inferred from the name of a thread. */
    public boolean commitOwnerThreadBound() {
        return guard.serverThreadBound();
    }

    /** The registered site the owner was bound as, or an empty string while it is unbound. */
    public String commitOwnerSite() {
        return guard.serverSiteId();
    }

    /** Whether this module owes a reset it could not carry out when it was asked for: an item in the
     * queue, a batch taken out, the one owner-thread request refused, or a mailbox left unfinished. */
    public boolean commitResetPending() {
        synchronized (resetMailbox) {
            return owesAReset();
        }
    }

    /** Carries out the reset this module owes, in full and once, at an owner safe point, and answers
     * whether one whole reset ran. A request that was carried out leaves no debt, so a second call moves
     * neither the generation nor a count; one refused again leaves the debt for the call after it. It is
     * the boolean view of {@link #markSafePoint()}, which is what the host calls. */
    public boolean drainCommitReset() {
        if (!commitResetPending()) {
            return false;
        }
        return markSafePoint().carried() > 0;
    }

    /** Whether a controller is bound to this module: the readout says so of itself. */
    public String commitControllerWiring() {
        return commitController == null ? "NOT_WIRED" : "BOUND";
    }

    /** How often a whole reset was asked for through the one request entry: a count of calls, not of
     * items taken and not of generations produced. */
    public long commitResetRequests() {
        synchronized (resetMailbox) {
            return commitResetRequests;
        }
    }

    /** How often a request took the path of a thread the chain does not own. A count of calls, taken
     * before the merge and before the room is asked for: not the number of items to be answered. */
    public long commitResetHandOffs() {
        synchronized (resetMailbox) {
            return commitResetHandOffs;
        }
    }

    /** Where the newest reset request came from, or an empty string while none was made. */
    public String commitResetSource() {
        return heldTrace().source();
    }

    /** How often the host asked this module to adopt a chain, one call per tick boundary. */
    public long commitAdoptionCalls() {
        return commitAdoptionCalls;
    }

    /** How often the adopted chain was asked to close through {@link #closeCommitChain()}. */
    public long commitCloseCalls() {
        return commitCloseCalls;
    }

    /** The room the newest close gave back: only reservations that never started go back. */
    public int commitCloseGivenBack() {
        return commitCloseGivenBack;
    }

    /** The items the newest close keeps readable: their side effect may have run. */
    public int commitCloseUnrecorded() {
        return commitCloseUnrecorded;
    }

    /** The lines the newest close left publishing: they still owe their terminal line. */
    public int commitClosePublishing() {
        return commitClosePublishing;
    }

    /** Where the two host-side requests of the commit chain are driven from. The column reports what
     * arrived at those entries, so a build whose category is off keeps reading NOT_WIRED: it is a state
     * of this process and not a constant of the code. */
    public String commitHostDriveWiring() {
        boolean safePoint = commitSafePointCalls() > 0L;
        boolean close = commitCloseCalls > 0L;
        if (safePoint && close) {
            return "HOST_SAFE_POINT+CLOSE";
        }
        if (safePoint) {
            return "HOST_SAFE_POINT";
        }
        return close ? "HOST_CLOSE" : "NOT_WIRED";
    }

    /** Whether the adopted chain has been closed: it takes nothing new and no reset follows it. */
    public boolean commitChainClosed() {
        CommitController controller = this.commitController;
        return controller != null && controller.reservations().closed();
    }

    /** Clears the live counters. Where a commit controller is bound, its owner safe point decides
     * first: while a publish action or a publishing line is in flight, or while an item whose side
     * effect ran still waits for its terminal line, the whole reset is refused, nothing is cleared and
     * no generation advances. This entry is a request for the whole reset, and it carries it out
     * nowhere but at the one gate {@link #carryOutOwningReset}. */
    public void resetReadings() {
        if (carryOutOwningReset(ResetAttempt.OUTSIDE_BATCH, null)) {
            noteResetAnswered(null, "reset-at-safe-point");
            return;
        }
        if (!commitChainClosed()) {
            deferOwningReset(mintItem(""));
        }
    }

    private void clearReadings() {
        for (KernelDomain domain : domains) {
            domain.reset();
        }
        loadThread.reset();
        chunkFlow.reset();
        saveIdentity.reset();
        regionIdentity.reset();
        attribution.reset();
        chunkDemand.reset();
        arrivalDigest.reset();
        planNanosLast = 0L;
        planNanosTotal = 0L;
        planNanosMax = 0L;
        planNanosBuilds = 0L;
        planC1NanosLast = 0L;
        planC1NanosTotal = 0L;
        planC1NanosMax = 0L;
        planC1NanosBuilds = 0L;
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
        pipelineJobs.reset();
        pipelineBatches.reset();
        PrtsPipelineBatches.resetHostTicked();
        rangeSeals.reset();
        retainedOrders.clear();
        rangeSealFactory.reset();
        rangeSealFactory.resetObligations();
        demandShadows.resetStatuses();
        claims.reset();
        typedConsumer.reset();
        jobDeclarations = 0L;
        lastDispatchHandedOut = 0L;
        jobMeter.reset();
        plans.reset();
        versionSlots.reset();
        ownerGrants.reset();
        controlledSlots.reset();
        commitBindingRefusal = "";
        declarationGeneration++;
        declarationHistory.retire();
        declarationRefusal = "";
        commits.reset();
        safety.reset();
        zeroEffect.reset();
        exits.reset();
    }
}
