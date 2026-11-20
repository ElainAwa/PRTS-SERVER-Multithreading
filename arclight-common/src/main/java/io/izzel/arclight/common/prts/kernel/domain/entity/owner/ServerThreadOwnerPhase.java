/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueCapture;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueWindow;
import io.izzel.arclight.common.prts.support.EntityEligibility;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;

import io.izzel.arclight.common.bridge.core.entity.EntityBridge;
import io.izzel.arclight.common.prts.kernel.SharedRuntime;
import io.izzel.arclight.common.prts.kernel.auth.OwnerUnitPermit;
import io.izzel.arclight.common.prts.kernel.domain.entity.CancelToken;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.generic.ExecutionCohort;
import io.izzel.arclight.common.prts.kernel.generic.GenericJobExecutor;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.WorkAccount;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/** The owner phase: rest rows are selected first, then (when declared) walking/standing AI rows;
 * both run from the host pre-tick wrapper. The walking slice is non-sleeping, non-PANIC/hurt AI
 * Villagers and executes the original server-thread tick without REST intent contracts. */
public final class ServerThreadOwnerPhase {

    public static final int MAX_ROWS = 8;
    public static final String TRACE_PROPERTY = "arclight.prts.ownerPhaseTrace";
    private static final Map<String, LevelPhase> PENDING = new LinkedHashMap<>();
    private static volatile boolean phaseActive;
    private static long totalSelected;
    private static long totalExecuted;
    private static long totalFallback;
    private static long totalCompat;
    private static long totalLeft;
    private static volatile int lastSelected;
    public static final String BATCH_PROPERTY = "arclight.prts.ownerBatch";
    public static final String BATCH_PARALLEL_PROPERTY = "arclight.prts.ownerBatchParallel";
    record DeferredRow(LevelPhase phase, ServerLevel level, Villager villager,
                               net.minecraft.world.entity.Entity entity, long hostStep) {
    }
    private static final List<DeferredRow> DEFERRED = new ArrayList<>();
    private static final Set<Integer> DEFERRED_IDS = new HashSet<>();
    /** Bodies whose batch returned before the body stopped: the next batch must not start while one
     * of them may still touch shared state. The references are kept until the future settles. */
    private static final List<WorkerPool.GenericHandle> IN_FLIGHT =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile java.util.function.Consumer<net.minecraft.world.entity.Entity> postReplay;

    private ServerThreadOwnerPhase() {
    }

    private enum Slice { REST, WALK }

    /** The exact logical row of one selected entity: the incarnation and object a due row was read
     * from, its uuid and object generation, and the update tick it is being handed for. A short id
     * never stands for this identity. */
    record RowIdentity(String worldId, long worldEpoch, long uuidHigh, long uuidLow,
                       long generation, int updateTick) {
    }

    static final class LevelPhase {

        ServerLevel level;
        final Map<Integer, Villager> rows = new LinkedHashMap<>();
        final Map<Integer, Long> hostSteps = new LinkedHashMap<>();
        final Map<Integer, Slice> slice = new LinkedHashMap<>();
        int walkSelected;
        int walkExecuted;
        int preSeen;
        int expectedPost;
        int postSeen;
        int postDuplicate;
        int leftGone;
        int leftInactive;
        int leftPreCanceled;
        int leftNoPre;
        int leftBodySkipped;
        final java.util.Set<Integer> preSeenRows = new java.util.HashSet<>();
        final java.util.Set<Integer> preCanceledRows = new java.util.HashSet<>();
        final java.util.Set<Integer> inactiveRows = new java.util.HashSet<>();
        final java.util.Set<Integer> expectedPostRows = new java.util.HashSet<>();
        final java.util.Set<Integer> postSeenRows = new java.util.HashSet<>();
        int selected;
        int executed;
        int fallback;
        int compat;
        int terminal;
        final java.util.Map<Integer, String> nativeSkips = new java.util.HashMap<>();
        final java.util.Map<Integer, RowIdentity> identities = new java.util.HashMap<>();
        int truncated;
        long skipsAtStart;
        long tick;
    }

    /** Marks due rows of this level; only this level's outstanding set is cleared. */
    public static void enter(ServerLevel level, EntityDueCapture.Window window) {
        if (!EntityRowOwnershipWindow.enabled() || level == null || window == null) {
            return;
        }
        String dimension = level.dimension().location().toString();
        LevelPhase phase = new LevelPhase();
        phase.level = level;
        phase.tick = level.getServer().getTickCount();
        phase.skipsAtStart = RestOwnerWiring.skips();
        List<Villager> selected = select(level, window, dimension, phase);
        // Only rows that will be deferred into the controlled batch are declared: a declared seat
        // that never starts would hold the pair's order and block every later controlled row.
        java.util.List<Villager> declared = new java.util.ArrayList<>(selected.size());
        for (Villager candidate : selected) {
            if (batchEnabled() && phase.slice.get(candidate.getId()) == Slice.WALK) {
                declared.add(candidate);
            }
        }
        // Safe point: the host's due window is frozen and every check above read live state; the one
        // plan service now freezes the current tick with these rows declared, before any body runs.
        io.izzel.arclight.common.prts.kernel.EntityDeclarationIntake.arm(level, window, declared);
        io.izzel.arclight.common.prts.kernel.KernelModule.instance()
            .freezeAtEntityPhase(phase.tick);
        phase.selected = selected.size();
        lastSelected = selected.size();
        io.izzel.arclight.common.prts.kernel.KernelModule module =
            io.izzel.arclight.common.prts.kernel.KernelModule.instance();
        long worldEpoch = module.guard().worldEpochs().epochOf(dimension);
        for (Villager villager : selected) {
            phase.rows.put(villager.getId(), villager);
            phase.identities.put(villager.getId(), new RowIdentity(dimension, worldEpoch,
                villager.getUUID().getMostSignificantBits(),
                villager.getUUID().getLeastSignificantBits(),
                io.izzel.arclight.common.prts.kernel.domain.entity.EntityObjectGenerations.shared()
                    .generationOf(dimension, villager.getUUID().getMostSignificantBits(),
                        villager.getUUID().getLeastSignificantBits()),
                villager.tickCount));
        }
        for (EntityDueWindow.World world : window.worlds()) {
            for (EntityDueWindow.Row row : world.rows()) {
                if (phase.rows.containsKey(row.entityId())) {
                    phase.hostSteps.put(row.entityId(), row.hostStep());
                }
            }
        }
        if (trace() && (!selected.isEmpty() || levelIdRows(window, dimension) > 0)) {
            System.out.println("[PRTS] owner-phase selected=" + selected.size()
                + " pending=" + phase.rows.size() + " reasons="
                + histogram(window, dimension));
        }
        if (trace() && phase.walkSelected > 0) {
            System.out.println("[PRTS] owner-walk selected=" + phase.walkSelected
                + " total=" + selected.size());
        }
        PENDING.put(dimension, phase);
        phaseActive = true;
    }

    /** Whether this entity is selected and still pending in the current level tick. */
    public static boolean hasPending(net.minecraft.world.entity.Entity entity) {
        if (!phaseActive || !EntityRowOwnershipWindow.enabled() || entity == null) {
            return false;
        }
        LevelPhase phase = PENDING.get(entity.level().dimension().location().toString());
        if (phase == null) {
            return false;
        }
        Object row = phase.rows.get(entity.getId());
        if (!(row instanceof Villager selected) || !(entity.level() instanceof ServerLevel level)) {
            return false;
        }
        return sameLogicalRow(level, selected, entity);

    }

    /** Runs a pending owner body at the original host tick call site; false means host may tick. */
    public static boolean runPendingAtBody(net.minecraft.world.entity.Entity entity) {
        if (!EntityRowOwnershipWindow.enabled() || entity == null) {
            return false;
        }
        LevelPhase phase = PENDING.get(entity.level().dimension().location().toString());
        if (phase == null || !(phase.rows.get(entity.getId()) instanceof Villager selected)
            || !sameLogicalRow((ServerLevel) entity.level(), selected, entity)) {
            return false;
        }
        Villager villager = phase.rows.remove(entity.getId());
        Slice slice = phase.slice.remove(entity.getId());
        if (slice == null) {
            phase.fallback++;
            io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership
                .beginLegacyPoiFallback(entity);
            noteExpectedPost(entity);
            return false;
        }
        if (batchEnabled() && slice == Slice.WALK) {
            OwnerControlledWrite.Admission admission =
                OwnerControlledWrite.prepare((ServerLevel) entity.level(), entity);
            if (admission == null) {
                // Not admitted: the host keeps the original call at this very site, before any owner
                // effect. This is a compat row of this tick, not a re-run after a deferred body.
                phase.compat++;
                return false;
            }
            DeferredRow row = new DeferredRow(phase, (ServerLevel) entity.level(), villager, entity,
                phase.hostSteps.getOrDefault(entity.getId(), 0L));
            if (Boolean.getBoolean("arclight.prts.ownerBatchInline")) {
                if (Boolean.getBoolean("arclight.prts.ownerHostSerial")) {
                    return runHostSerialControlledRow(row, admission);
                }
                return runInlineControlledRow(row, admission);
            }
            DEFERRED.add(row);
            DEFERRED_IDS.add(entity.getId());
            StageCounters.note("ownerBatchRowDeferred");
            return true;
        }
        if (io.izzel.arclight.common.prts.kernel.domain.entity.owner.OwnerCommitGate.read()
            .ready()) {
            // The chain is ready, so an owner body that has no reservation would be a native effect
            // the chain never judged. This row was not admitted into the controlled batch: keep the
            // original host call at this site instead of running a second kind of owner body.
            phase.compat++;
            return false;
        }
        long ownerScope = io.izzel.arclight.common.prts.kernel.domain.entity.ownership
            .EntityTickOwnership.beginOwnerBodyScope(entity);
        boolean fallbackHost = false;
        boolean walkBody = slice == Slice.WALK && OwnerNavigationWorker.enabled();
        if (walkBody) {
            OwnerNavigationWorker.enterOwnerBody();
        }
        try {
            boolean executed = slice == Slice.WALK
                ? RealEntityOwnerBody.runWalkOnServer((ServerLevel) entity.level(), villager)
                : RealEntityOwnerBody.runRestedOnServer((ServerLevel) entity.level(), villager);
            if (executed) {
                phase.executed++;
                if (slice == Slice.WALK) {
                    phase.walkExecuted++;
                }
                RestOwnerWiring.noteSkip();
                io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership
                    .noteOwnerBodyExecuted();
                noteExpectedPost(entity);
            } else {
                phase.fallback++;
                noteExpectedPost(entity);
                fallbackHost = true;
            }
            return executed;
        } catch (Throwable failure) {
            phase.terminal++;
            RestOwnerWiring.noteSkip();
            io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership
                .noteOwnerBodyExecuted();
            throw failure;
        } finally {
            if (walkBody) {
                OwnerNavigationWorker.leaveOwnerBody();
            }
            io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership
                .endOwnerBodyScope(ownerScope);
            if (fallbackHost) {
                // The host body runs right after this return, so it keeps the legacy POI scope that
                // the pre-tick listener used to bind for it; the post event clears it.
                io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership
                    .beginLegacyPoiFallback(entity);
            }
        }
    }

    private static void dumpOwnerStacks() {
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            boolean owner = thread.getName().startsWith(SharedRuntime.THREAD_PREFIX)
                || thread.getName().equals("Server thread");
            if (!owner) {
                return;
            }
            StringBuilder line = new StringBuilder("[PRTS] owner-batch stack " + thread.getName());
            for (StackTraceElement element : stack) {
                line.append(" <- ").append(element);
            }
            System.out.println(line);
        });
    }

    /** Whether the opt-in batch deferral is declared; off in every shipped build. */
    public static boolean batchEnabled() {
        return Boolean.getBoolean(BATCH_PROPERTY);
    }

    /** Whether deferred bodies may overlap on several workers; off keeps one body in flight. */
    private static boolean parallelBatches() {
        return Boolean.getBoolean(BATCH_PARALLEL_PROPERTY);
    }

    /** Whether this row's body was deferred to the end of the level pass. */
    public static boolean isDeferred(net.minecraft.world.entity.Entity entity) {
        return entity != null && DEFERRED_IDS.contains(entity.getId());
    }

    /** Binds the platform Post replay the deferred rows need; a process that binds nothing keeps the host path. */
    public static void setPostReplay(java.util.function.Consumer<net.minecraft.world.entity.Entity> replay) {
        postReplay = replay;
    }

    private static void runDeferredBody(DeferredRow row, int slot, boolean[] executed) {
        StageCounters.note("ownerBatchBody");
        OwnerUnitPermit.enter(row.level().dimension().location().toString());
        OwnerBodyLease lease = OwnerBodyLease.acquire(row.level(), row.entity(),
            row.phase().tick, row.hostStep());
        long scope = io.izzel.arclight.common.prts.kernel.domain.entity.ownership
            .EntityTickOwnership.beginOwnerBodyScope(row.entity());
        try {
            executed[slot] = RealEntityOwnerBody.runWalkOnServer(row.level(), row.villager());
        } finally {
            if (lease != null) {
                lease.release();
            }
            io.izzel.arclight.common.prts.kernel.domain.entity.ownership
                .EntityTickOwnership.endOwnerBodyScope(scope);
            OwnerUnitPermit.exit();
        }
    }

    private static String rowKey(DeferredRow row, int index) {
        return "entity-state:" + row.entity().getUUID().getMostSignificantBits() + ":"
            + row.entity().getUUID().getLeastSignificantBits() + ":" + row.phase().tick
            + ":" + index;
    }

    /** Runs one admitted row on the host owner thread inside the batch's seat order: the begun
     * ticket and the publisher contract are the worker path's, but the body runs here. The batch
     * already holds its reservation, so this must not submit a second one. */
    private static void runHostSerialBatchRow(DeferredRow row,
                                              OwnerControlledWrite.Admission admission) {
        io.izzel.arclight.common.prts.kernel.commit.CommitController controller =
            admission.controller();
        io.izzel.arclight.common.prts.kernel.commit.CommitController.BeginResult begin =
            controller.beginControl(admission.request());
        if (!begin.admitted()) {
            row.phase().terminal++;
            throw new IllegalStateException("controlled entity write refused: "
                + begin.code() + " " + begin.diagnostic());
        }
        io.izzel.arclight.common.prts.kernel.commit.CommitTicket ticket = begin.ticket();
        io.izzel.arclight.common.prts.kernel.commit.CommitTicket.Outcome answered;
        try {
            answered = OwnerControlledWrite.runBodyOnOwner(row.level(), row.villager(), admission);
        } catch (RuntimeException | Error thrown) {
            controller.finishControl(ticket, OwnerControlledWrite.partialOutcome(admission));
            row.phase().terminal++;
            throw thrown;
        }
        io.izzel.arclight.common.prts.kernel.commit.CommitController.SubmitResult result =
            controller.finishControl(ticket, answered);
        if (!result.admitted()) {
            row.phase().terminal++;
            throw new IllegalStateException("controlled entity write terminal refused: "
                + result.code() + " " + result.diagnostic());
        }
        replayDeferredRow(row);
        controller.consumePublished(ticket);
    }

    /** Runs one admitted row on the host owner thread at the original call site through the same
     * controller and publisher contract: the first version of the controlled chain that does not
     * hand the body to a worker. It is the serial baseline, not a worker/G1 claim. */
    private static boolean runHostSerialControlledRow(DeferredRow row,
                                                      OwnerControlledWrite.Admission admission) {
        StageCounters.note("ownerBatchBody");
        io.izzel.arclight.common.prts.kernel.commit.CommitController.SubmitResult result =
            OwnerControlledWrite.run(row.level(), row.villager(), admission,
                io.izzel.arclight.common.prts.kernel.wiring.OwnerNativeCommitBinding.instance());
        if (!result.admitted()
            || result.terminal()
                != io.izzel.arclight.common.prts.kernel.commit.CommitTicket.Terminal.PUBLISHED) {
            row.phase().terminal++;
            throw new IllegalStateException("controlled entity write refused: "
                + result.code() + " " + result.diagnostic());
        }
        row.phase().executed++;
        row.phase().walkExecuted++;
        RestOwnerWiring.noteSkip();
        io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership
            .noteOwnerBodyExecuted();
        noteExpectedPost(row.entity());
        return true;
    }

    /** Runs one admitted row at its original host call site instead of deferring it: the begun
     * ticket, worker body and owner terminal happen inside the same tick position the host would
     * have ticked, and the wrapper still fires the one normal Post/portal afterwards. */
    private static boolean runInlineControlledRow(DeferredRow row,
                                                  OwnerControlledWrite.Admission admission) {
        StageCounters.note("ownerBatchBody");
        SharedRuntime runtime = SharedRuntime.shared();
        if (runtime.pool() == null && !runtime.open()) {
            throw new IllegalStateException("owner batch pool is unavailable");
        }
        runControlledWorkerRow(runtime, row, admission, false);
        row.phase().executed++;
        row.phase().walkExecuted++;
        RestOwnerWiring.noteSkip();
        io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership
            .noteOwnerBodyExecuted();
        noteExpectedPost(row.entity());
        return true;
    }

    /** Runs one begun row's real body on a worker through the existing cohort, then writes its
     * terminal on the owner thread after the body answered. The begun ticket and the worker answer
     * live in one persistent record until the owner finish or fault ends it; a worker future being
     * done only ends the body node. */
    private static void runControlledWorkerRow(SharedRuntime runtime, DeferredRow row,
                                               OwnerControlledWrite.Admission admission,
                                               boolean replay) {
        io.izzel.arclight.common.prts.kernel.commit.CommitController controller =
            admission.controller();
        io.izzel.arclight.common.prts.kernel.commit.CommitController.BeginResult begin =
            controller.beginControl(admission.request());
        if (!begin.admitted()) {
            row.phase().terminal++;
            throw new IllegalStateException("controlled entity write refused: "
                + begin.code() + " " + begin.diagnostic());
        }
        io.izzel.arclight.common.prts.kernel.commit.CommitTicket ticket = begin.ticket();
        WorkerPool pool = runtime.pool();
        if (pool == null || !pool.accepting()) {
            controller.abandonControl(ticket, "pool-unavailable");
            row.phase().terminal++;
            throw new IllegalStateException("controlled entity write has no worker to start");
        }
        OwnerControlledExecution record = new OwnerControlledExecution(row, admission, ticket);
        String worldId = row.level().dimension().location().toString();
        String bodyKey = "entity-control:" + ticket.ticketId() + ":body";
        long bodyId = 1L;
        io.izzel.arclight.common.prts.kernel.jobs.JobGraph graph =
            new io.izzel.arclight.common.prts.kernel.jobs.JobGraph(1L, 0L, 0L,
                List.of(new io.izzel.arclight.common.prts.kernel.jobs.JobGraph.Node(bodyId,
                    bodyKey, bodyId, worldId, bodyKey, 0, List.of(), List.of(), 0, "", "",
                    List.of(), List.of(), List.of(),
                    io.izzel.arclight.common.prts.kernel.shares.ShareClass.ENTITY,
                    io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration.SiteClass.PARALLEL,
                    0, 1, false, 0)),
                List.of(bodyId), List.of(bodyId), List.of(), 1, 0);
        Map<String, Runnable> bodies = new java.util.LinkedHashMap<>();
        bodies.put(bodyKey, () -> runControlledWorkerBody(record));
        OwnerControlledExecution.waiting(record);
        io.izzel.arclight.common.prts.kernel.generic.GenericJobExecutor.Execution execution;
        try {
            execution = new io.izzel.arclight.common.prts.kernel.generic.GenericJobExecutor(pool)
                .executeCohort(graph, bodies);
        } catch (RuntimeException | Error rejected) {
            OwnerControlledExecution.doneWaiting();
            record.endOwnerWait();
            controller.abandonControl(ticket, "cohort-refused");
            row.phase().terminal++;
            throw rejected;
        }
        record.cohort = execution.cohort();
        record.handle = execution.cohort().handle(bodyKey);
        if (record.handle == null) {
            OwnerControlledExecution.doneWaiting();
            record.endOwnerWait();
            record.stage = OwnerControlledExecution.Stage.ABANDONED;
            controller.abandonControl(ticket, "dispatch-refused");
            row.phase().terminal++;
            throw new IllegalStateException("controlled entity write was not accepted by the cohort");
        }
        OwnerControlledExecution.register(record);
        long deadline = System.nanoTime()
            + java.util.concurrent.TimeUnit.SECONDS.toNanos(5L);
        MinecraftServer server = row.level().getServer();
        try {
            while (!record.handle.future().isDone() && System.nanoTime() < deadline) {
                OwnerReadDemand.service();
                OwnerHostEventDispatch.service();
                while (server.pollTask()) {
                    // The owner thread keeps servicing the host while the worker body runs.
                }
                Thread.yield();
            }
        } finally {
            OwnerControlledExecution.doneWaiting();
            record.endOwnerWait();
        }
        if (!record.handle.future().isDone()) {
            record.witness.cancel();
            row.phase().terminal++;
            throw new IllegalStateException(
                "controlled entity write did not answer before its deadline");
        }
        completeControlledExecution(record, replay);
        OwnerControlledExecution.retire(record);
    }

    /** Writes the owner terminal or transfers the unresolved obligation for one ended body. Called
     * once on the owner thread; late calls after the batch ended only finish/fault, never replay. */
    static void completeControlledExecution(OwnerControlledExecution record, boolean replay) {
        if (record == null || record.stage == OwnerControlledExecution.Stage.OWNER_DONE
            || record.stage == OwnerControlledExecution.Stage.ABANDONED) {
            return;
        }
        io.izzel.arclight.common.prts.kernel.commit.CommitController controller = record.controller();
        io.izzel.arclight.common.prts.kernel.commit.CommitTicket.Outcome answered = record.outcome;
        Throwable nativeFailure = record.bodyFailure;
        Throwable cleanupFailure = record.cleanupFailure;
        if (answered == null) {
            if (!record.witness.effectPossible() && cleanupFailure == null) {
                if (controller.abandonControl(record.ticket, "body-not-entered")) {
                    record.stage = OwnerControlledExecution.Stage.ABANDONED;
                    OwnerControlledExecution.retire(record);
                    if (nativeFailure instanceof RuntimeException runtimeFailure) {
                        throw runtimeFailure;
                    }
                    if (nativeFailure instanceof Error errorFailure) {
                        throw errorFailure;
                    }
                    return;
                }
            }
            answered = partialOutcome(record, "effect-unknown");
        }
        if (cleanupFailure != null
            && answered.terminal() != io.izzel.arclight.common.prts.kernel.commit.CommitTicket
                .Terminal.PARTIAL) {
            answered = partialOutcome(record, "cleanup-failed");
        }
        nativeFailure = firstFailure(record.bodyFailure, record.cleanupFailure);
        io.izzel.arclight.common.prts.kernel.commit.CommitController.SubmitResult result =
            controller.finishControl(record.ticket, answered);
        if (!result.admitted() && nativeFailure != null) {
            nativeFailure.addSuppressed(new IllegalStateException(
                "terminal-not-recorded:" + result.code() + "|" + result.diagnostic()));
        }
        if (result.admitted()) {
            record.stage = OwnerControlledExecution.Stage.OWNER_DONE;
            OwnerControlledExecution.retire(record);
        }
        if (nativeFailure != null) {
            if (nativeFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (nativeFailure instanceof Error errorFailure) {
                throw errorFailure;
            }
            throw new IllegalStateException("controlled entity body failed", nativeFailure);
        }
        if (!result.admitted()
            || result.terminal()
                != io.izzel.arclight.common.prts.kernel.commit.CommitTicket.Terminal.PUBLISHED) {
            throw new IllegalStateException("controlled entity write refused: "
                + result.code() + " " + result.diagnostic());
        }
        if (replay) {
            replayDeferredRow(record.row);
        }
        controller.consumePublished(record.ticket);
    }

    private static Throwable firstFailure(Throwable body, Throwable cleanup) {
        if (body != null) {
            if (cleanup != null && cleanup != body) {
                body.addSuppressed(cleanup);
            }
            return body;
        }
        return cleanup;
    }

    private static io.izzel.arclight.common.prts.kernel.commit.CommitTicket.Outcome partialOutcome(
        OwnerControlledExecution record, String cause) {
        return new io.izzel.arclight.common.prts.kernel.commit.CommitTicket.Outcome(
            io.izzel.arclight.common.prts.kernel.commit.CommitTicket.Terminal.PARTIAL,
            "entity-tick",
            io.izzel.arclight.common.prts.kernel.commit.EffectStore
                .normalizedRows(record.admission.partial().rows()), 0, cause);
    }

    /** The permitted worker segment: ticket identity is checked before the first write, the real
     * body's effect witness is set immediately before the action, and every acquired permit/lease/
     * scope is released in reverse order with cleanup failures attached instead of hiding the
     * native cause. It never reports a terminal; the owner record does that. */
    private static void runControlledWorkerBody(OwnerControlledExecution record) {
        record.stage = OwnerControlledExecution.Stage.BODY_RUNNING;
        DeferredRow row = record.row;
        try {
            io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations.Entry entry =
                record.admission.entry();
            if (!record.ticket.batch().worldId().equals(entry.seat().worldId())
                || !record.ticket.batch().domainId().equals(entry.seat().domainId())
                || !record.ticket.batch().nodeKey().equals(entry.nodeKey())
                || record.ticket.identity().effectRef()
                    != record.admission.published().effectRef()) {
                throw new IllegalStateException("a worker ticket does not match its declaration");
            }
            OwnerUnitPermit.enter(row.level().dimension().location().toString());
        } catch (Throwable failure) {
            record.bodyFailure = failure;
            record.stage = OwnerControlledExecution.Stage.BODY_RETURNED;
            OwnerHostEventDispatch.postOnHost(
                () -> ServerThreadOwnerPhase.completeLateControlled(record));
            return;
        }
        try {
            OwnerBodyLease lease = null;
            long scope = 0L;
            boolean scopeStarted = false;
            Throwable cleanup = null;
            try {
                lease = OwnerBodyLease.acquire(row.level(), row.entity(), row.phase().tick,
                    row.hostStep());
                scope = io.izzel.arclight.common.prts.kernel.domain.entity.ownership
                    .EntityTickOwnership.beginOwnerBodyScope(row.entity());
                scopeStarted = true;
                record.stage = OwnerControlledExecution.Stage.EFFECT_ENTERED;
                record.witness.markEffectPossible();
                OwnerRandomContext.enter(row.level(), row.villager(), "entity");
                boolean executed;
                try {
                    executed = RealEntityOwnerBody.runWalkOnServer(row.level(), row.villager());
                } finally {
                    OwnerRandomContext.exit();
                }
                record.outcome = executed
                    ? new io.izzel.arclight.common.prts.kernel.commit.CommitTicket.Outcome(
                        io.izzel.arclight.common.prts.kernel.commit.CommitTicket.Terminal.PUBLISHED,
                        "entity-tick", io.izzel.arclight.common.prts.kernel.commit.EffectStore
                            .normalizedRows(record.admission.published().rows()), 1, "")
                    : new io.izzel.arclight.common.prts.kernel.commit.CommitTicket.Outcome(
                        io.izzel.arclight.common.prts.kernel.commit.CommitTicket.Terminal.FAILED,
                        "entity-tick", io.izzel.arclight.common.prts.kernel.commit.EffectStore
                            .normalizedRows(record.admission.failed().rows()), 0, "");
            } catch (Throwable failure) {
                record.bodyFailure = failure;
            } finally {
                OwnerBodyLease heldLease = lease;
                long heldScope = scope;
                boolean heldScopeStarted = scopeStarted;
                cleanup = OwnerControlledWorkerScope.closeSteps(null,
                    heldScopeStarted ? () -> io.izzel.arclight.common.prts.kernel.domain.entity
                        .ownership.EntityTickOwnership.endOwnerBodyScope(heldScope) : null,
                    heldLease == null ? null : heldLease::release,
                    OwnerUnitPermit::exit);
                if (cleanup != null) {
                    record.cleanupFailure = cleanup;
                }
            }
        } finally {
            record.stage = OwnerControlledExecution.Stage.BODY_RETURNED;
            OwnerHostEventDispatch.postOnHost(
                () -> ServerThreadOwnerPhase.completeLateControlled(record));
        }
    }

    private static Throwable append(Throwable base, Throwable extra) {
        base.addSuppressed(extra);
        return base;
    }

    /** A body that ended after its batch left can no longer replay host Post; it still writes the
     * terminal or transfers the fault on the owner thread, so the ticket is never left orphaned. */
    static void completeLateControlled(OwnerControlledExecution record) {
        if (record.ownerWaiting) {
            return;
        }
        try {
            completeControlledExecution(record, false);
        } catch (Throwable ignored) {
            // The terminal is recorded; a late caller has no host frame to propagate the body cause.
        }
    }

    private static void replayDeferredRow(DeferredRow row) {
        row.phase().executed++;
        row.phase().walkExecuted++;
        RestOwnerWiring.noteSkip();
        io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership
            .noteOwnerBodyExecuted();
        noteExpectedPost(row.entity());
        StageCounters.note("ownerBatchPortal");
        ((EntityBridge) row.entity()).bridge$postTick();
        java.util.function.Consumer<net.minecraft.world.entity.Entity> replay = postReplay;
        if (replay != null) {
            StageCounters.note("ownerBatchPost");
            replay.accept(row.entity());
        }
    }

    /** Submits one batch as a real frozen job graph through the generic executor. Every row is two
     * nodes: its owner body and the post/publish step that depends on it. A serial batch chains the
     * rows through their successors, so the graph itself is the serial strategy and the host keeps
     * servicing its demand channels instead of blocking in a per-node future. */
    private static ExecutionCohort submitCohort(SharedRuntime runtime, List<DeferredRow> rows,
                                                WorkerPool.GenericHandle[] handles,
                                                boolean[] executed, boolean serial) {
        int count = rows.size();
        List<JobGraph.Node> nodes = new ArrayList<>(count * 2);
        List<Long> order = new ArrayList<>(count * 2);
        List<Long> ready = new ArrayList<>();
        Map<String, Runnable> bodies = new LinkedHashMap<>();
        String previousPost = null;
        for (int index = 0; index < count; index++) {
            DeferredRow row = rows.get(index);
            String key = rowKey(row, index);
            String bodyKey = key + ":body";
            String postKey = key + ":post";
            long bodyId = index * 2L + 1L;
            long postId = bodyId + 1L;
            List<String> bodyPredecessors = serial && previousPost != null
                ? List.of(previousPost) : List.of();
            List<Long> bodySuccessors = List.of(postId);
            nodes.add(new JobGraph.Node(bodyId, bodyKey, bodyId,
                row.level().dimension().location().toString(), key, 0,
                bodyPredecessors, bodySuccessors, 0, "", "", List.of(), List.of(), List.of(),
                ShareClass.ENTITY, JobDeclaration.SiteClass.PARALLEL, 0, 1, false, index * 2));
            long postPredecessor = bodyId;
            nodes.add(new JobGraph.Node(postId, postKey, postId,
                row.level().dimension().location().toString(), key, 0,
                List.of(bodyKey), List.of(), 0, "", "", List.of(), List.of(), List.of(),
                ShareClass.ENTITY, JobDeclaration.SiteClass.PARALLEL, 0, 1, false, index * 2 + 1));
            order.add(bodyId);
            order.add(postId);
            if (bodyPredecessors.isEmpty()) {
                ready.add(bodyId);
            }
            final int slot = index;
            bodies.put(bodyKey, () -> runDeferredBody(row, slot, executed));
            bodies.put(postKey, () -> io.izzel.arclight.common.prts.kernel.domain.entity.owner
                .OwnerHostEventDispatch.postOnHost(() -> replayDeferredRow(row)));
            previousPost = postKey;
        }
        JobGraph graph = new JobGraph(1L, 0L, 0L, nodes, order, ready,
            List.of(), count * 2, 0);
        GenericJobExecutor executor = new GenericJobExecutor(runtime.pool());
        GenericJobExecutor.Execution execution = executor.executeCohort(graph, bodies);
        ExecutionCohort cohort = execution.cohort();
        for (int index = 0; index < count; index++) {
            handles[index] = cohort.handle(rowKey(rows.get(index), index) + ":post");
        }
        return cohort;
    }

    private static void awaitOwner(WorkerPool.GenericHandle[] handles, int count,
                                   MinecraftServer server, long deadline) {
        while (true) {
            boolean unsettled = false;
            for (int index = 0; index < count; index++) {
                if (handles[index] != null && !handles[index].future().isDone()) {
                    unsettled = true;
                    break;
                }
            }
            if (!unsettled || System.nanoTime() > deadline) {
                return;
            }
            OwnerReadDemand.service();
            OwnerHostEventDispatch.service();
            while (server.pollTask()) {
                // Drains the host queue while the batch waits; chunk demand itself is served above.
            }
            Thread.yield();
        }
    }

    /** Waits for bodies a previous batch left in flight, servicing their host demand channels; false
     * while any of them is still running at the deadline. */
    private static boolean awaitInFlight(MinecraftServer server, long deadline) {
        if (OwnerControlledExecution.waitingOnThisThread()) {
            return false;
        }
        while ((!IN_FLIGHT.isEmpty() || OwnerControlledExecution.anyPending())
            && System.nanoTime() < deadline) {
            OwnerReadDemand.service();
            OwnerHostEventDispatch.service();
            while (server.pollTask()) {
                // The host keeps serving a stopped batch's demand until its bodies really stopped.
            }
            IN_FLIGHT.removeIf(handle -> handle.future().isDone());
            if (!IN_FLIGHT.isEmpty()) {
                Thread.yield();
            }
        }
        IN_FLIGHT.removeIf(handle -> handle.future().isDone());
        return IN_FLIGHT.isEmpty() && !OwnerControlledExecution.anyPending();
    }

    /** Waits for bodies a batch left in flight before the server stops and services their demand
     * channels; false while any of them is still running at the deadline. */
    public static boolean awaitInFlightOnStop(long waitMs) {
        if (OwnerControlledExecution.waitingOnThisThread()) {
            return false;
        }
        long deadline = System.nanoTime()
            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(waitMs);
        while ((!IN_FLIGHT.isEmpty() || OwnerControlledExecution.anyPending())
            && System.nanoTime() < deadline) {
            OwnerReadDemand.service();
            OwnerHostEventDispatch.service();
            IN_FLIGHT.removeIf(handle -> handle.future().isDone());
            if (!IN_FLIGHT.isEmpty()) {
                Thread.yield();
            }
        }
        IN_FLIGHT.removeIf(handle -> handle.future().isDone());
        return IN_FLIGHT.isEmpty() && !OwnerControlledExecution.anyPending();
    }

    /** Runs every deferred walk row, parks the host once on them, then replays the host Post/portal
     * for each row that really executed. A failure is terminal: no replay and no host fallback after
     * the body was admitted. */
    public static void finishDeferred() {
        if (DEFERRED.isEmpty()) {
            return;
        }
        List<DeferredRow> rows = new ArrayList<>(DEFERRED);
        DEFERRED.clear();
        DEFERRED_IDS.clear();
        SharedRuntime runtime = SharedRuntime.shared();
        if (runtime.pool() == null && !runtime.open()) {
            throw new IllegalStateException("owner batch pool is unavailable");
        }
        if (!OwnerBodyLease.enabled()
            || !Boolean.getBoolean(OwnerBodyLease.AUTHORITY_PROPERTY)) {
            throw new IllegalStateException(
                "owner batch needs the admitted lease and the authority decision");
        }
        int count = rows.size();
        StageCounters.note("ownerBatchDeferred");
        MinecraftServer server = rows.get(0).level().getServer();
        long fenceDeadline = System.nanoTime()
            + java.util.concurrent.TimeUnit.SECONDS.toNanos(5L);
        if (!awaitInFlight(server, fenceDeadline)) {
            throw new IllegalStateException(
                "owner batch refuses to start while a previous body is still running");
        }
        OwnerCommitGate.Readiness readiness = OwnerCommitGate.read();
        if (!readiness.ready()) {
            throw new IllegalStateException(
                "owner batch needs a ready commit chain: " + readiness.reason());
        }
        WorkAccount account = SharedRuntime.shared().account();
        String batchKey = "owner-batch:"
            + rows.get(0).level().dimension().location() + ":" + rows.get(0).phase().tick
            + ":" + count;
        String batchUnit = WorkAccount.unitId(batchKey, 0L);
        if (!account.offer(new WorkAccount.Unit(batchUnit, batchKey,
            rows.get(0).level().dimension().location().toString(), batchKey))) {
            throw new IllegalStateException("owner batch unit was refused");
        }
        account.markReady(batchUnit);
        account.markRunning(batchUnit);
        // A body a previous batch left in flight has no terminal yet: no new controlled row may
        // start while its record still owns the ticket. Late completions are delivered on the host
        // queue, so the owner drains that queue before refusing the batch.
        OwnerHostEventDispatch.service();
        if (OwnerControlledExecution.anyPending()) {
            throw new IllegalStateException(
                "a previous controlled body still owns its terminal");
        }
        // The controlled rows are the only bodies this batch runs; a row the host already ended
        // (removed or discarded) closes its seat as a native no-effect terminal before any later
        // seat may start.
        disposeNativeCancelledRows(rows);
        io.izzel.arclight.common.prts.kernel.wiring.OwnerNativeCommitBinding binding =
            io.izzel.arclight.common.prts.kernel.wiring.OwnerNativeCommitBinding.instance();
        java.util.List<DeferredRow> controlled = new java.util.ArrayList<>(rows);
        java.util.Map<DeferredRow, OwnerControlledWrite.Admission> admissions =
            new java.util.IdentityHashMap<>();
        for (DeferredRow row : controlled) {
            OwnerControlledWrite.Admission admission =
                OwnerControlledWrite.prepare(row.level(), row.entity());
            if (admission == null) {
                throw new IllegalStateException("owner row lost its frozen admission");
            }
            admissions.put(row, admission);
        }
        // The controller starts a place only after every earlier place of the same pair ended: the
        // frozen plan's own seat order is the order the side effects have to take.
        controlled.sort(java.util.Comparator.comparingInt(
            row -> admissions.get(row).entry().seat().position()));
        try {
            for (DeferredRow row : controlled) {
                OwnerControlledWrite.Admission admission = admissions.get(row);
                StageCounters.note("ownerBatchBody");
                if (Boolean.getBoolean("arclight.prts.ownerHostSerial")) {
                    runHostSerialBatchRow(row, admission);
                } else {
                    runControlledWorkerRow(runtime, row, admission, true);
                }
            }
        } catch (RuntimeException | Error failure) {
            account.close(batchUnit, WorkAccount.Outcome.PARTIAL_FAULT, "batch-terminal");
            throw failure;
        }
        account.close(batchUnit, WorkAccount.Outcome.DONE, "batch-published");
    }

    /** Records that a pending row reached the normal Pre observer. */
    public static void notePreSeen(net.minecraft.world.entity.Entity entity) {
        LevelPhase phase = phaseOf(entity);
        if (phase != null && phase.rows.get(entity.getId()) == entity) {
            if (phase.preSeenRows.add(entity.getId())) {
                phase.preSeen++;
            }
        }
    }

    /** Observes the final Pre verdict at the wrapper's real consumption point. */
    public static void observePreVerdict(net.minecraft.world.entity.Entity entity, boolean canceled) {
        if (!phaseActive || entity == null) {
            return;
        }
        LevelPhase phase = phaseOf(entity);
        if (phase == null || phase.rows.get(entity.getId()) != entity) {
            return;
        }
        notePreSeen(entity);
        if (canceled) {
            notePreCanceled(entity);
        }
    }

    /** Records a later-priority cancellation of a pending row; no body runs. */
    public static void notePreCanceled(net.minecraft.world.entity.Entity entity) {
        LevelPhase phase = phaseOf(entity);
        if (phase != null && phase.rows.get(entity.getId()) == entity) {
            phase.preCanceledRows.add(entity.getId());
        }
    }

    /** Records an activation-range inactive skip of a pending row. */
    public static void noteInactive(net.minecraft.world.entity.Entity entity) {
        LevelPhase phase = phaseOf(entity);
        if (phase != null && phase.rows.get(entity.getId()) instanceof Villager selected
            && sameLogicalRow(phase, selected, entity)) {
            phase.inactiveRows.add(entity.getId());
        }
    }

    /** Records that a body at the call site successfully entered the host Post boundary. */
    public static void noteExpectedPost(net.minecraft.world.entity.Entity entity) {
        LevelPhase phase = phaseOf(entity);
        if (phase != null && phase.expectedPostRows.add(entity.getId())) {
            phase.expectedPost++;
        }
    }

    /** Records an actual Post for a body that reached the call site; duplicate is diagnostic. */
    public static void notePost(net.minecraft.world.entity.Entity entity) {
        LevelPhase phase = phaseOf(entity);
        if (phase == null || !phase.expectedPostRows.contains(entity.getId())) {
            return;
        }
        if (!phase.postSeenRows.add(entity.getId())) {
            phase.postDuplicate++;
        } else {
            phase.postSeen++;
        }
    }

    private static LevelPhase phaseOf(net.minecraft.world.entity.Entity entity) {
        if (!phaseActive || !EntityRowOwnershipWindow.enabled() || entity == null) {
            return null;
        }
        return PENDING.get(entity.level().dimension().location().toString());
    }

    /** Whether the passed entity is the same logical update this phase selected: world incarnation,
     * UUID, object generation and the tick count read when the due window froze. A reused short id
     * never inherits the row. */
    static boolean sameLogicalRow(LevelPhase phase, Villager selected,
                                  net.minecraft.world.entity.Entity entity) {
        if (phase == null || selected == null || entity == null
            || !(entity instanceof Villager)) {
            return false;
        }
        RowIdentity identity = phase.identities.get(entity.getId());
        if (identity == null) {
            return false;
        }
        String worldId = phase.level.dimension().location().toString();
        if (!identity.worldId().equals(worldId)
            || !identity.worldId().equals(entity.level().dimension().location().toString())) {
            return false;
        }
        io.izzel.arclight.common.prts.kernel.KernelModule module =
            io.izzel.arclight.common.prts.kernel.KernelModule.instance();
        if (module.guard().worldEpochs().epochOf(worldId) != identity.worldEpoch()) {
            return false;
        }
        if (entity.getUUID().getMostSignificantBits() != identity.uuidHigh()
            || entity.getUUID().getLeastSignificantBits() != identity.uuidLow()) {
            return false;
        }
        long generation = io.izzel.arclight.common.prts.kernel.domain.entity
            .EntityObjectGenerations.shared().generationOf(worldId,
                entity.getUUID().getMostSignificantBits(),
                entity.getUUID().getLeastSignificantBits());
        if (generation != identity.generation()) {
            return false;
        }
        // The body call site increments the entity tick count before Entity#tick, so the same
        // logical row is seen either before that increment or once after it.
        return entity.tickCount == identity.updateTick()
            || entity.tickCount == identity.updateTick() + 1;
    }

    static boolean sameLogicalRow(ServerLevel level, Villager selected,
                                  net.minecraft.world.entity.Entity entity) {
        LevelPhase phase = level == null ? null
            : PENDING.get(level.dimension().location().toString());
        return sameLogicalRow(phase, selected, entity);
    }

    /** Records the host's own terminal for one pending row that will not run its owner body: the
     * entity was removed or discarded by the level pass. The frozen seat is disposed without an
     * effect only when the same logical update is proven; every other case keeps the seat open. */
    public static void noteNativeSkip(ServerLevel level,
                                      net.minecraft.world.entity.Entity entity, String cause) {
        if (!EntityRowOwnershipWindow.enabled() || level == null || entity == null) {
            return;
        }
        LevelPhase phase = PENDING.get(level.dimension().location().toString());
        if (phase == null) {
            return;
        }
        Villager selected = phase.rows.get(entity.getId());
        if (!sameLogicalRow(level, selected, entity)) {
            return;
        }
        phase.nativeSkips.put(entity.getId(), cause);
        io.izzel.arclight.common.prts.kernel.KernelModule module =
            io.izzel.arclight.common.prts.kernel.KernelModule.instance();
        io.izzel.arclight.common.prts.kernel.commit.CommitController controller =
            module.commitController();
        if (controller == null || !controller.executable()) {
            return;
        }
        io.izzel.arclight.common.prts.kernel.plan.TickPlan plan = module.plans().latest();
        io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations declarations =
            module.declarations();
        if (plan == null || declarations == null) {
            return;
        }
        String worldId = level.dimension().location().toString();
        long uuidHigh = entity.getUUID().getMostSignificantBits();
        long uuidLow = entity.getUUID().getLeastSignificantBits();
        long generation = io.izzel.arclight.common.prts.kernel.domain.entity.EntityObjectGenerations
            .shared().generationOf(worldId, uuidHigh, uuidLow);
        String nodeKey = io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations
            .ENTITY_STATE_PREFIX + uuidHigh + ":" + uuidLow + ":" + generation;
        int position = -1;
        for (io.izzel.arclight.common.prts.kernel.plan.TickPlan.CommitStep step
            : plan.commitOrder()) {
            if (step.worldId().equals(worldId) && step.domainId().equals("entity")
                && step.nodeKey().equals(nodeKey)) {
                position = step.position();
                break;
            }
        }
        if (position < 0) {
            return;
        }
        io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations.Entry entry =
            declarations.entryAt(new io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations
                .Seat(worldId, "entity", plan.planSequence(), position));
        if (entry == null
            || entry.kind() != io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations
                .TargetKind.ENTITY) {
            return;
        }
        controller.reservations().disposeNoEffect(declarations, entry, "native-" + cause);
    }

    /** Closes the seat of a declared row the host ended without an owner body, using only the
     * native state of this same level pass; a still-live row stays open. */
    private static void disposeNativeCancelledRows(java.util.List<DeferredRow> controlled) {
        java.util.Set<Integer> started = new java.util.HashSet<>();
        for (DeferredRow row : controlled) {
            started.add(row.entity().getId());
        }
        for (java.util.Map.Entry<String, LevelPhase> levelEntry : PENDING.entrySet()) {
            LevelPhase phase = levelEntry.getValue();
            ServerLevel level = phase.level;
            if (level == null) {
                continue;
            }
            for (java.util.Map.Entry<Integer, Villager> entry : phase.rows.entrySet()) {
                if (started.contains(entry.getKey())) {
                    continue;
                }
                Villager villager = entry.getValue();
                if (villager == null) {
                    continue;
                }
                String cause = null;
                if (phase.nativeSkips.containsKey(entry.getKey())) {
                    cause = phase.nativeSkips.get(entry.getKey());
                } else if (phase.inactiveRows.contains(entry.getKey())) {
                    // The activation branch at the original tick entry ended this pass before the
                    // body: only the controlled full body is known to be skipped, not the whole
                    // update or its other native obligations.
                    cause = "inactive-tick";
                } else if (phase.preCanceledRows.contains(entry.getKey())) {
                    cause = "pre-cancelled";
                } else if (villager.isRemoved() || !villager.isAlive()) {
                    cause = "removed";
                }
                if (cause != null) {
                    noteNativeSkip(level, villager, cause);
                }
            }
        }
    }

    /** Reconciles and reports one level's owner phase after the host entity pass. */
    public static void endTick(ServerLevel level) {
        if (level == null) {
            return;
        }
        LevelPhase phase = PENDING.remove(level.dimension().location().toString());
        phaseActive = !PENDING.isEmpty();
        if (phase == null) {
            return;
        }
        long hostSkips = RestOwnerWiring.skips();
        long delta = hostSkips - phase.skipsAtStart;
        int leftGone = 0;
        int leftInactive = 0;
        int leftPreCanceled = 0;
        int leftNoPre = 0;
        int leftBodySkipped = 0;
        for (java.util.Map.Entry<Integer, Villager> entry : phase.rows.entrySet()) {
            Villager villager = entry.getValue();
            int id = entry.getKey();
            // Actual reached boundaries take precedence over removal/activation guesses.
            if (phase.preCanceledRows.contains(id)) {
                leftPreCanceled++;
            } else if (phase.preSeenRows.contains(id)) {
                leftBodySkipped++;
            } else if ("removed".equals(phase.nativeSkips.get(id))
                || villager == null || villager.isRemoved() || !villager.isAlive()) {
                leftGone++;
            } else if (phase.nativeSkips.containsKey(id)
                || phase.inactiveRows.contains(id)) {
                leftInactive++;
            } else {
                leftNoPre++;
            }
        }
        int left = leftGone + leftInactive + leftPreCanceled + leftNoPre + leftBodySkipped;
        totalSelected += phase.selected;
        totalExecuted += phase.executed;
        totalFallback += phase.fallback;
        totalCompat += phase.compat;
        totalLeft += left;
        String line = "tick=" + phase.tick + " selected=" + phase.selected + " executed="
            + phase.executed + " fallback=" + phase.fallback + " terminal=" + phase.terminal
            + " leftPending=" + left + " due=" + phase.preSeen + " leftGone=" + leftGone
            + " leftInactive=" + leftInactive + " leftPreCanceled=" + leftPreCanceled
            + " leftNoPre=" + leftNoPre + " leftBodySkipped=" + leftBodySkipped
            + " expectedPost=" + phase.expectedPost + " postSeen=" + phase.postSeen
            + " postDuplicate=" + phase.postDuplicate
            + " hostSkipsDelta=" + delta + " hostSkipsTotal=" + hostSkips;
        if (!trace()) {
            return;
        }
        boolean equation = phase.selected == phase.executed + phase.fallback + phase.terminal + left
            && phase.preSeen == phase.executed + phase.fallback + phase.terminal
                + leftPreCanceled + leftBodySkipped
            && delta == phase.executed + phase.terminal;
        System.out.println("[PRTS] phaseEnd " + line);
        if (phase.postSeen != phase.expectedPost) {
            System.out.println("[PRTS] boundary mismatch expectedPost=" + phase.expectedPost
                + " postSeen=" + phase.postSeen + " tick=" + phase.tick);
        }
        if (leftBodySkipped > 0) {
            System.out.println("[PRTS] leftBodySkipped=" + leftBodySkipped + " tick=" + phase.tick);
        }
        if (leftNoPre > 0) {
            System.out.println("[PRTS] leftNoPre=" + leftNoPre + " tick=" + phase.tick);
        }
        if (!equation) {
            System.out.println("[PRTS] phaseEquation ok=false selected=" + phase.selected
                + " executed=" + phase.executed + " fallback=" + phase.fallback
                + " terminal=" + phase.terminal + " left=" + left + " due=" + phase.preSeen
                + " preCanceled=" + leftPreCanceled + " bodySkipped=" + leftBodySkipped
                + " hostSkipsDelta=" + delta);
        }
        if (phase.walkSelected > 0) {
            System.out.println("[PRTS] owner-walk-end dim="
                + level.dimension().location() + " selected=" + phase.walkSelected
                + " executed=" + phase.walkExecuted + " total=" + phase.selected);
        }
        try {
            Path path = Path.of("logs/owner-phase-end.tsv");
            Files.createDirectories(path.getParent());
            boolean header = !Files.exists(path);
            String row = line.replace(' ', '\t');
            String text = (header ? "tick\tselected\texecuted\tfallback\tterminal"
                + "\tleftPending\tdue\tleftGone\tleftInactive\tleftPreCanceled\tleftNoPre"
                + "\tleftBodySkipped\texpectedPost\tpostSeen\tpostDuplicate\thostSkipsDelta"
                + "\thostSkipsTotal\n" : "") + row
                .replace("tick=", "").replace("selected=", "").replace("executed=", "")
                .replace("fallback=", "").replace("terminal=", "").replace("leftPending=", "")
                .replace("due=", "").replace("leftGone=", "").replace("leftInactive=", "")
                .replace("leftPreCanceled=", "").replace("leftNoPre=", "")
                .replace("leftBodySkipped=", "").replace("expectedPost=", "")
                .replace("postSeen=", "").replace("postDuplicate=", "")
                .replace("hostSkipsDelta=", "").replace("hostSkipsTotal=", "") + "\n";
            Files.writeString(path, text, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ignored) {
            // A diagnostic write failure must not change the tick.
        }
    }

    /** Pure selector after admission; kept separate so tests can construct due rows. */
    static List<EntityDueWindow.Row> selectRows(EntityDueCapture.Window window, String levelId) {
        List<EntityDueWindow.Row> rows = new ArrayList<>();
        if (window == null || levelId == null) {
            return rows;
        }
        for (EntityDueWindow.World world : window.worlds()) {
            if (!levelId.equals(world.worldId())) {
                continue;
            }
            for (EntityDueWindow.Row row : world.rows()) {
                if (OwnerZoneAdmission.admits(row) && row.classTag() != null
                    && row.classTag().endsWith("Villager")) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    /** Evidence-only filter: when a tag is declared, the owner phase selects only rows carrying it,
     * so one server run can tick a host reference group and an owner group on the same scene. */
    static boolean ownerTagAdmits(net.minecraft.world.entity.Entity entity) {
        String wanted = System.getProperty("arclight.prts.ownerOnlyTags");
        if (wanted == null || wanted.isEmpty()) {
            return true;
        }
        for (String tag : entity.getTags()) {
            if (wanted.equals(tag)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the host tick loop would run the full body of this entity in this pass: a row the
     * host would only inactive-tick must not be taken by the owner, or the two paths diverge. */
    private static boolean hostWouldRunFullBody(net.minecraft.world.entity.Entity entity) {
        if (!io.izzel.arclight.i18n.ArclightConfig.spec().getOptimization()
            .useActivationAndTrackingRange()) {
            return true;
        }
        return org.spigotmc.ActivationRange.checkIfActive(entity);
    }

    private static List<Villager> select(ServerLevel level, EntityDueCapture.Window window,
                                         String levelId, LevelPhase phase) {
        List<Villager> selected = new ArrayList<>();
        for (EntityDueWindow.Row row : selectRows(window, levelId)) {
            net.minecraft.world.entity.Entity entity = level.getEntity(row.entityId());
            if (!(entity instanceof Villager villager) || !villager.isAlive()
                || villager.isRemoved() || villager.isNoAi()
                || !hostWouldRunFullBody(entity) || !ownerTagAdmits(entity)
                || villager.getPose() != Pose.SLEEPING
                || !villager.getBrain().isActive(Activity.REST)) {
                continue;
            }
            if (selected.size() < MAX_ROWS) {
                selected.add(villager);
                phase.slice.put(villager.getId(), Slice.REST);
            } else {
                phase.truncated++;
            }
        }
        if (OwnerZoneAdmission.walkSlice()) {
            for (EntityDueWindow.Row row : selectRowsWalk(window, levelId)) {
                net.minecraft.world.entity.Entity entity = level.getEntity(row.entityId());
                if (!(entity instanceof Villager villager) || !villager.isAlive()
                    || villager.isRemoved() || villager.isNoAi() || villager.isPassenger()
                    || !hostWouldRunFullBody(entity) || !ownerTagAdmits(entity)) {
                    continue;
                }
                if (villager.getPose() == Pose.SLEEPING
                    || villager.getBrain().isActive(Activity.REST)
                    || villager.getBrain().isActive(Activity.PANIC)
                    || villager.hurtTime != 0) {
                    continue;
                }
                if (selected.size() < MAX_ROWS) {
                    selected.add(villager);
                    phase.slice.put(villager.getId(), Slice.WALK);
                    phase.walkSelected++;
                } else {
                    phase.truncated++;
                }
            }
        }
        if (phase.truncated > 0) {
            System.out.println("[PRTS] owner-phase truncated=" + phase.truncated);
        }
        return selected;
    }

    /** Pure walking-row selector; live pose/passenger checks happen in select. */
    static List<EntityDueWindow.Row> selectRowsWalk(EntityDueCapture.Window window,
                                                    String levelId) {
        List<EntityDueWindow.Row> rows = new ArrayList<>();
        if (window == null || levelId == null) {
            return rows;
        }
        for (EntityDueWindow.World world : window.worlds()) {
            if (!levelId.equals(world.worldId())) {
                continue;
            }
            for (EntityDueWindow.Row row : world.rows()) {
                if (OwnerZoneAdmission.admitsWalk(row) && row.classTag() != null
                    && row.classTag().endsWith("Villager")) {
                    rows.add(row);
                }
            }
        }
        return rows;
    }

    private static int levelIdRows(EntityDueCapture.Window window, String levelId) {
        int count = 0;
        for (EntityDueWindow.World world : window.worlds()) {
            if (levelId.equals(world.worldId())) {
                count += world.rows().size();
            }
        }
        return count;
    }

    private static Map<EntityEligibility.Reason, Integer> histogram(
        EntityDueCapture.Window window, String levelId) {
        Map<EntityEligibility.Reason, Integer> counts = new EnumMap<>(EntityEligibility.Reason.class);
        if (window == null) {
            return counts;
        }
        for (EntityDueWindow.World world : window.worlds()) {
            if (!levelId.equals(world.worldId())) {
                continue;
            }
            for (EntityDueWindow.Row row : world.rows()) {
                counts.merge(row.reason(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** One gated summary at server stop; no per-tick I/O when the diagnostic is off. */
    public static void stopSummary() {
        if (totalSelected > 0L || totalExecuted > 0L || totalLeft > 0L) {
            System.out.println("[PRTS] phaseSummary selected=" + totalSelected + " executed="
                + totalExecuted + " fallback=" + totalFallback + " compat=" + totalCompat
                + " leftPending=" + totalLeft);
        }
    }

    public static boolean trace() {
        return Boolean.getBoolean(TRACE_PROPERTY);
    }

    static int lastSelected() {
        return lastSelected;
    }
}