/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.wiring;

import io.izzel.arclight.common.prts.kernel.DomainReadings;
import io.izzel.arclight.common.prts.kernel.KernelDomain;
import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.diff.DiffProbe;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass.DispatchSettings;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchWriteBack;
import io.izzel.arclight.common.prts.kernel.dispatch.FaultInjection;
import io.izzel.arclight.common.prts.kernel.dispatch.MergeSegment;
import io.izzel.arclight.common.prts.kernel.dispatch.TaskLedger;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.domain.entity.DispatchSnapshot;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityIntegrator;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.support.PrtsEntityCapability;
import io.izzel.arclight.common.prts.support.PrtsEntityRescope;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers.SelfRow;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/** The entity domain of this build: it freezes the row view of one tick, hands the batches to the
 * pool, merges what came back and turns a settled frame into writes the commit segment lands. The
 * kernel drives it through {@link KernelDomain} and reads it through {@link DomainReadings}; no type
 * of this package is reachable from the kernel by name. */
public final class EntityDomain implements KernelDomain {

    /** The name the readout knows this domain by; it is also the domain id of the state hash. */
    public static final String ID = "entity";

    private static final String THREAD_PREFIX = "prts-worker-";
    private static final String RUNTIME_WORLD = "";
    private static final long EVIDENCE_TICKS = 400L;
    private static final long SHUTDOWN_WAIT_MS = 250L;
    private static final Logger EVIDENCE = LogManager.getLogger("PRTS");

    private final KernelModule module;
    private final ArenaLedger arena;
    private final DispatchReadings readings = new DispatchReadings();
    private final TaskLedger ledger = new TaskLedger(0L);
    private final DiffProbe probe = new DiffProbe();
    private final MergeSegment merge = new MergeSegment();
    private final DispatchWriteBack writeBack;

    private long taskSeq;
    private long lastEvidenceTick;
    private WorkerPool pool;
    private DispatchPass pending;
    private MergeSegment.Frame lastFrame = MergeSegment.Frame.empty();

    public EntityDomain(KernelModule module) {
        this.module = module;
        this.arena = module.arena();
        this.writeBack = new DispatchWriteBack(module.intents(), module.payloads()::bind,
            module.payloads()::drop, module.guard().worldEpochs()::epochOf, readings,
            KernelSettings::dispatchTakeover);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public void tick(long tick) {
        // The evidence line is exported before the merge of this tick and after the commit of this
        // tick, so the orders the commit consumed account for every task the plan froze: a task is
        // counted when its plan is built and its intent is consumed at the next commit boundary.
        if (tick - lastEvidenceTick >= EVIDENCE_TICKS) {
            lastEvidenceTick = tick;
            DispatchReadings.Window window = new DispatchReadings.Window(
                pool == null ? 0 : pool.alive(), lastFrame.closureOk(), module.commitSegment().cursor(),
                module.intents().depth(), module.intents().orderViolationCount(), selfEntityMs());
            EVIDENCE.info(readings.evidenceLine(arena, window));
            EVIDENCE.info(writeBack.sampleLine());
            // The census is observation only: it says which rows a takeover could claim and what
            // those rows cost the host, and no decision of this domain reads it.
            EVIDENCE.info(PrtsEntityCapability.censusLine());
            EVIDENCE.info(PrtsEntityCapability.classLine());
            // The re-scoping census is observation only too: it counts the rows the host entry
            // really ticked and how long their whole tick ran, and no decision reads it.
            if (PrtsEntityRescope.timed()) {
                EVIDENCE.info(PrtsEntityRescope.censusLine());
                EVIDENCE.info(PrtsEntityRescope.classLine());
                EVIDENCE.info(PrtsEntityRescope.reasonLine());
            }
            // The ownership fixture reports itself; every one of its counters is written at the
            // host entry or at the plan point, never derived from what a commit landed.
            if (EntityTickOwnership.live()) {
                EVIDENCE.info(EntityTickOwnership.evidenceLine());
                EVIDENCE.info(EntityTickOwnership.replicaLine());
            }
        }
        if (!KernelSettings.dispatchParallel()) {
            if (pool != null || pending != null) {
                shutdown();
            }
            merge.noteSkippedMerge();
            return;
        }
        DispatchSettings.Policy policy = DispatchSettings.resolve();
        if (pending != null) {
            // A declared fault keeps this pass pending and stops the next plan until its world is
            // reloaded, so the generation refusal of the stale batch happens on the live path.
            if (FaultInjection.holdsMerge(pending.plan(), module.guard().worldEpochs()::epochOf)) {
                // The pass is held, so this tick closes without a merge and the world runs on: the
                // frame the merge still holds is not the frame the next commit reaches.
                merge.noteSkippedMerge();
                return;
            }
            // The merge closes the window of the pass it reads; the epoch of the ledger only moves
            // here, so a pass that is closed without a merge must move it itself.
            long grace = policy.deadlineGraceMs();
            long deadline = System.nanoTime() + grace * 1_000_000L;
            lastFrame = merge.merge(pending, deadline, arena, readings, probe, HashWhitelist.bitexact(),
                ID, writeBack);
            pending = null;
            if (lastFrame == null) {
                lastFrame = MergeSegment.Frame.empty();
                merge.noteSkippedMerge();
            }
        } else {
            merge.noteSkippedMerge();
        }
        long snapshotStart = System.nanoTime();
        // One epoch source for the freeze and the commit: the task carries the generation the
        // write-right guard tracks, so the write-back can freeze it and the commit can compare the
        // very same number.
        List<EntityCandidateView> views =
            DispatchSnapshot.capture(module.guard().worldEpochs()::epochOf);
        DispatchWriteBack.noteSnapshot(readings, RUNTIME_WORLD, "snapshot",
            System.nanoTime() - snapshotStart);
        WorkPlan plan = WorkPlan.freeze(tick, ledger.epoch(), views, policy.batchChunks(),
            taskSeq + 1L);
        taskSeq += plan.taskCount();
        readings.noteTasks(plan.taskCount());
        if (!plan.empty()) {
            if (pool == null) {
                try {
                    pool = WorkerPool.open(new WorkerPool.Spec(policy.workerCount(), THREAD_PREFIX,
                        Thread.NORM_PRIORITY, policy.queueCap(), policy.batchChunks()),
                        policy.retryBudget(), readings, arena);
                } catch (Throwable t) {
                    readings.notePoolOpenFailed();
                    pool = null;
                }
            }
            if (pool != null) {
                pending = DispatchPass.dispatch(plan, pool, EntityIntegrator.INSTANCE, arena, readings,
                    ledger);
            } else {
                // No pool this tick: the plan still receives one terminal outcome per task, so the
                // accounting of the tick closes and the merge redos the batches on the tick thread in
                // the frozen order.
                pending = DispatchPass.serialFallback(plan, readings, ledger);
            }
        }
    }

    @Override
    public void shutdown() {
        WorkerPool current = pool;
        pool = null;
        DispatchPass open = pending;
        pending = null;
        if (open != null) {
            open.abort(RejectCode.TICK_BUDGET_EXHAUSTED.text());
        }
        boolean confirmed = true;
        if (current != null) {
            WorkerPool.ShutdownReport report = current.shutdown(true, true, SHUTDOWN_WAIT_MS);
            confirmed = report.terminated() && report.remainingInFlight() == 0;
            readings.noteShutdown(report.remainingInFlight(), report.terminated());
        }
        if (confirmed) {
            arena.releaseAll();
        } else {
            // A worker that ignored the deadline may still hold a lease; the arena is quarantined so
            // its slots can never be handed to the next pass.
            arena.quarantineAll();
        }
    }

    @Override
    public void reset() {
        shutdown();
        readings.reset();
        ledger.reset();
        merge.reset();
        probe.reset();
        PrtsEntityCapability.reset();
        PrtsEntityRescope.reset();
        EntityTickOwnership.reset();
        lastFrame = MergeSegment.Frame.empty();
        lastEvidenceTick = 0L;
    }

    @Override
    public void readings(DomainReadings sink) {
        sink.add("self.dispatch_snapshot_ms", readings.snapshotNanos() / 1_000_000.0);
        sink.add("self.dispatch_verify_ms", readings.verifyNanos() / 1_000_000.0);
        sink.add("self.dispatch_verify_rows", readings.verifyRows());
        sink.add("self.dispatch_compute_ms", readings.computeNanos() / 1_000_000.0);
        sink.add("self.dispatch_redo_ms", readings.redoNanos() / 1_000_000.0);
        sink.add("self.dispatch_rows", readings.rowsTotal());
        sink.add("self.dispatch_commit_channel_ms", readings.commitChannelNanos() / 1_000_000.0);
        sink.add("self.entity_tick_open", PrtsEntityCapability.tickOpens());
        sink.add("self.entity_tick_close", PrtsEntityCapability.tickCloses());
        sink.add("self.entity_tick_unpaired", PrtsEntityCapability.tickUnpaired());
        sink.add("self.entity_tick_cancelled", PrtsEntityCapability.tickCancelled());
        sink.add("self.entity_move_open", PrtsEntityCapability.moveOpens());
        sink.add("self.entity_move_close", PrtsEntityCapability.moveCloses());
        sink.add("self.entity_candidates", PrtsEntityCapability.candidates());
        sink.add("self.entity_eligible", PrtsEntityCapability.eligible());
        sink.add("self.entity_owner_conflict", PrtsEntityCapability.ownerConflicts());
        sink.add("self.entity_cap_dropped", PrtsEntityCapability.capDropped());
        sink.add("self.entity_no_ai_mobs", PrtsEntityCapability.noAiMobs());
        sink.add("self.entity_rejected_lifecycle", PrtsEntityCapability.reasonCount(PrtsEntityCapability.LIFECYCLE));
        sink.add("self.entity_rejected_ai", PrtsEntityCapability.reasonCount(PrtsEntityCapability.AI));
        sink.add("self.entity_rejected_riding", PrtsEntityCapability.reasonCount(PrtsEntityCapability.RIDING));
        sink.add("self.entity_rejected_cross_entity",
            PrtsEntityCapability.reasonCount(PrtsEntityCapability.CROSS_ENTITY));
        sink.add("self.entity_rejected_host_callback",
            PrtsEntityCapability.reasonCount(PrtsEntityCapability.HOST_CALLBACK));
        sink.add("self.entity_rejected_unknown", PrtsEntityCapability.reasonCount(PrtsEntityCapability.UNKNOWN));
        // The ownership fields are observation only: the fixture contributes them even when it is
        // off, where every one of them reads zero.
        EntityTickOwnership.readings(sink);
    }

    @Override
    public List<String> selfCheck(List<String> failures, long tick) {
        return EntityDomainSelfCheck.run(failures, tick);
    }

    private double selfEntityMs() {
        for (SelfRow row : module.window().rows()) {
            if (row.selfClass() == SelfClass.ENTITY) {
                return row.totalMs();
            }
        }
        return 0.0;
    }

    /** The live counters of this domain. */
    public DispatchReadings readings() {
        return readings;
    }

    /** The batch ledger of this domain. */
    public TaskLedger ledger() {
        return ledger;
    }

    /** The frame the last merge produced. */
    public MergeSegment.Frame lastFrame() {
        return lastFrame;
    }

    /** Hands the domain the pass the next merge must close; used by the switch test, not the
     * driver. */
    public void stage(DispatchPass pass) {
        this.pending = pass;
    }
}