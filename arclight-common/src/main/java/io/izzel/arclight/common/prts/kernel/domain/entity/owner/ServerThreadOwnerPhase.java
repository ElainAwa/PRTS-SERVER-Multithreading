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
    private static long totalLeft;
    private static volatile int lastSelected;
    public static final String BATCH_PROPERTY = "arclight.prts.ownerBatch";
    public static final String BATCH_PARALLEL_PROPERTY = "arclight.prts.ownerBatchParallel";
    private record DeferredRow(LevelPhase phase, ServerLevel level, Villager villager,
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

    private static final class LevelPhase {
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
        int terminal;
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
        phase.tick = level.getServer().getTickCount();
        phase.skipsAtStart = RestOwnerWiring.skips();
        List<Villager> selected = select(level, window, dimension, phase);
        phase.selected = selected.size();
        lastSelected = selected.size();
        for (Villager villager : selected) {
            phase.rows.put(villager.getId(), villager);
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
        return phase != null && phase.rows.get(entity.getId()) == entity;
    }

    /** Runs a pending owner body at the original host tick call site; false means host may tick. */
    public static boolean runPendingAtBody(net.minecraft.world.entity.Entity entity) {
        if (!EntityRowOwnershipWindow.enabled() || entity == null) {
            return false;
        }
        LevelPhase phase = PENDING.get(entity.level().dimension().location().toString());
        if (phase == null || phase.rows.get(entity.getId()) != entity) {
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
            DEFERRED.add(new DeferredRow(phase, (ServerLevel) entity.level(), villager, entity,
                phase.hostSteps.getOrDefault(entity.getId(), 0L)));
            DEFERRED_IDS.add(entity.getId());
            StageCounters.note("ownerBatchRowDeferred");
            return true;
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
        while (!IN_FLIGHT.isEmpty() && System.nanoTime() < deadline) {
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
        return IN_FLIGHT.isEmpty();
    }

    /** Waits for bodies a batch left in flight before the server stops and services their demand
     * channels; false while any of them is still running at the deadline. */
    public static boolean awaitInFlightOnStop(long waitMs) {
        long deadline = System.nanoTime()
            + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(waitMs);
        while (!IN_FLIGHT.isEmpty() && System.nanoTime() < deadline) {
            OwnerReadDemand.service();
            OwnerHostEventDispatch.service();
            IN_FLIGHT.removeIf(handle -> handle.future().isDone());
            if (!IN_FLIGHT.isEmpty()) {
                Thread.yield();
            }
        }
        IN_FLIGHT.removeIf(handle -> handle.future().isDone());
        return IN_FLIGHT.isEmpty();
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
        WorkerPool.GenericHandle[] handles = new WorkerPool.GenericHandle[count];
        boolean[] executed = new boolean[count];
        StageCounters.note("ownerBatchDeferred");
        boolean serial = !parallelBatches();
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
        try {
            submitCohort(runtime, rows, handles, executed, serial);
        } catch (RuntimeException | Error submissionFailure) {
            account.close(batchUnit, WorkAccount.Outcome.REJECTED_BEFORE_EFFECT,
                "batch-submit-refused");
            throw submissionFailure;
        }
        long deadline = System.nanoTime()
            + java.util.concurrent.TimeUnit.SECONDS.toNanos(20L);
        System.out.println("[PRTS] owner-batch start rows=" + count);
        awaitOwner(handles, count, server, deadline);
        for (WorkerPool.GenericHandle handle : handles) {
            if (handle == null || !handle.future().isDone()) {
                dumpOwnerStacks();
                break;
            }
        }
        System.out.println("[PRTS] owner-batch settled");
        boolean unsettled = false;
        for (WorkerPool.GenericHandle handle : handles) {
            if (handle == null || !handle.future().isDone()) {
                unsettled = true;
                break;
            }
        }
        if (unsettled) {
            for (WorkerPool.GenericHandle pending : handles) {
                if (pending != null && !pending.future().isDone()) {
                    pending.cancel();
                    IN_FLIGHT.add(pending);
                }
            }
            account.close(batchUnit, WorkAccount.Outcome.PARTIAL_FAULT, "batch-deadline");
            throw new IllegalStateException("owner batch did not settle before the deadline");
        }
        Throwable firstFailure = null;
        for (int index = 0; index < count; index++) {
            DeferredRow row = rows.get(index);
            try {
                handles[index].future().get();
            } catch (Throwable failure) {
                if (firstFailure == null) {
                    firstFailure = failure;
                }
            }
            boolean ok = !handles[index].future().isCompletedExceptionally() && executed[index];
            if (!ok) {
                row.phase().terminal++;
                if (firstFailure == null) {
                    firstFailure = new IllegalStateException("deferred owner body or post did not complete");
                }
            }
        }
        if (firstFailure != null) {
            account.close(batchUnit, WorkAccount.Outcome.PARTIAL_FAULT, "batch-terminal");
            if (firstFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (firstFailure instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("deferred owner body failed", firstFailure);
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
        if (phase != null && phase.rows.get(entity.getId()) == entity) {
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
            } else if (villager == null || villager.isRemoved() || !villager.isAlive()) {
                leftGone++;
            } else if (phase.inactiveRows.contains(id)) {
                leftInactive++;
            } else {
                leftNoPre++;
            }
        }
        int left = leftGone + leftInactive + leftPreCanceled + leftNoPre + leftBodySkipped;
        totalSelected += phase.selected;
        totalExecuted += phase.executed;
        totalFallback += phase.fallback;
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

    private static List<Villager> select(ServerLevel level, EntityDueCapture.Window window,
                                         String levelId, LevelPhase phase) {
        List<Villager> selected = new ArrayList<>();
        for (EntityDueWindow.Row row : selectRows(window, levelId)) {
            net.minecraft.world.entity.Entity entity = level.getEntity(row.entityId());
            if (!(entity instanceof Villager villager) || !villager.isAlive()
                || villager.isRemoved() || villager.isNoAi()
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
                    || villager.isRemoved() || villager.isNoAi() || villager.isPassenger()) {
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
                + totalExecuted + " fallback=" + totalFallback + " leftPending=" + totalLeft);
        }
    }

    public static boolean trace() {
        return Boolean.getBoolean(TRACE_PROPERTY);
    }

    static int lastSelected() {
        return lastSelected;
    }
}