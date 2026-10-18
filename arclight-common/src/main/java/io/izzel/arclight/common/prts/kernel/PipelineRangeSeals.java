/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.commit.RangeDigests;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealTable;
import io.izzel.arclight.common.prts.kernel.commit.RangeSettlementObligations;
import io.izzel.arclight.common.prts.kernel.jobs.DemandShadowCandidates;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineBatchLedger;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineRoundJobs;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineRunSeal;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

/** The one factory of the range seals a run start publishes. It is handed the ledger that cut the
 * batch, the plan authority the write side also asks, the instance source the freeze holds and the
 * declarations in force, and it builds the seal from those alone: the task's own stamp, the plan's
 * exact seat and rank, the ledger's cells and the live world generation. Every step that cannot be
 * proved answers null and keeps the table and the instance counter apart from it, so a refused seal
 * publishes nothing.
 *
 * <p>The checks, the mint and the publish run under the table's monitor. A reset that runs between
 * the window opening and the seal point therefore either overtakes the publish or is seen through
 * the generation the window captured; it cannot leave a seal of the previous lifecycle readable. */
public final class PipelineRangeSeals implements PipelineRunSeal.RangeSink {

    private final PipelineBatchLedger ledger;
    private final RangeSealTable table;
    private final TickPlanStore plans;
    private final LongSupplier instances;
    private final ToLongFunction<String> worldEpochs;
    private final Supplier<FrozenDeclarations> declarationsInForce;
    private final RangeSettlementObligations obligations;
    private long seals;
    private long refused;
    private long conflicts;
    private long overflows;
    private long rejected;

    /** Creates the factory with no settlement registry: a published seal is pinned by no one, which
     * keeps the default path exactly where it was. */
    public PipelineRangeSeals(PipelineBatchLedger ledger, RangeSealTable table, TickPlanStore plans,
                              LongSupplier instances, ToLongFunction<String> worldEpochs,
                              Supplier<FrozenDeclarations> declarationsInForce) {
        this(ledger, table, plans, instances, worldEpochs, declarationsInForce, null);
    }

    /** The same, with the registry a published seal is held by until its settlement. A null
     * registry is the production default: the seal is published and released as before. */
    public PipelineRangeSeals(PipelineBatchLedger ledger, RangeSealTable table, TickPlanStore plans,
                              LongSupplier instances, ToLongFunction<String> worldEpochs,
                              Supplier<FrozenDeclarations> declarationsInForce,
                              RangeSettlementObligations obligations) {
        this.ledger = ledger;
        this.table = table;
        this.plans = plans;
        this.instances = instances;
        this.worldEpochs = worldEpochs == null ? worldId -> CommitLog.NO_WORLD_EPOCH : worldEpochs;
        this.declarationsInForce = declarationsInForce;
        this.obligations = obligations;
    }

    @Override
    public long generation() {
        return table.generation();
    }

    @Override
    public FrozenDeclarations.RangeSeal seal(PipelineBatchLedger.TrackedTask task,
                                             DemandShadowCandidates.TaskStatus status,
                                             long generation) {
        synchronized (table) {
            if (table.generation() != generation) {
                // The lifecycle this window opened in is over: nothing it read may be published.
                rejected++;
                return null;
            }
            if (task == null || !task.tracked() || !task.live()
                || !(task.state() instanceof PipelineBatchLedger.Running running)) {
                return refuse();
            }
            if (status == null || !status.present() || status.stamp() == null) {
                return refuse();
            }
            PipelineBatchLedger.BatchId batch = running.batch();
            PipelineBatchLedger.TaskId id = task.id();
            DemandShadowCandidates.TaskStamp read = status.stamp();
            if (batch == null || !stampMatches(read, id, batch)) {
                return refuse();
            }
            FrozenDeclarations.Stamp stamp = new FrozenDeclarations.Stamp(read.taskOrdinal(),
                read.world(), read.mailbox(), read.cell(), read.mintTick(),
                read.batchFreezeOrdinal(), read.planSequence(), read.demandToken());
            if (!ledger.bindingPublished(batch)) {
                // A binding still being formed, or one the bounded history dropped, carries no range
                // to publish: refusing here keeps the forming members from standing in for it.
                return refuse();
            }
            if (ledger.currentPlan() != batch.planSequence()) {
                return refuse();
            }
            if (ledger.ownerOrdinal(batch, id.cell()) != id.ordinal()) {
                // One cell has one owner: the task with the smallest ordinal the published binding
                // carries. A later task of the same cell never publishes a second seal.
                return refuse();
            }
            FrozenDeclarations declared =
                declarationsInForce == null ? null : declarationsInForce.get();
            if (declared == null || !declared.freeze().declared()) {
                return refuse();
            }
            if (declared.planSequence() != batch.planSequence()) {
                // A freeze has no plan number of its own, so pairing the freeze in force with a
                // batch of another plan would publish a seal no replay can place: refuse here,
                // before the alignment reads are turned into an identity.
                return refuse();
            }
            FrozenDeclarations.FreezeKey freeze = declared.freeze();
            if (plans == null) {
                return refuse();
            }
            long lifecycle = plans.lifecycle();
            List<TickPlan.CommitStep> order = plans.orderIn(batch.planSequence(), lifecycle);
            if (order == null) {
                return refuse();
            }
            String nodeKey = PipelineRoundJobs.batchKeyOf(batch);
            TickPlan.CommitStep step = stepOf(order, nodeKey, batch);
            if (step == null || step.position() < 0) {
                return refuse();
            }
            int role = roleOf(order, step);
            if (role <= 0) {
                return refuse();
            }
            long[] cells = ledger.cellsOf(batch);
            if (!RangeDigests.wellFormed(cells) || !contains(cells, id.cell())) {
                return refuse();
            }
            long worldEpoch = worldEpochs.applyAsLong(batch.worldId());
            if (!CommitLog.liveWorldEpoch(worldEpoch)) {
                return refuse();
            }
            FrozenDeclarations.Seat seat = new FrozenDeclarations.Seat(step.worldId(),
                step.domainId(), batch.planSequence(), step.position());
            if (!crossChecked(seat, declared, nodeKey, role, id.cell(), status.status(),
                worldEpoch)) {
                return refuse();
            }
            long instanceId = instances == null ? CommitLog.NO_INSTANCE : instances.getAsLong();
            if (!CommitLog.liveInstance(instanceId)) {
                return refuse();
            }
            Map<Long, FrozenDeclarations.CellStatus> statuses = Map.of(id.cell(),
                new FrozenDeclarations.CellStatus(status.status(), stamp));
            FrozenDeclarations.RangeSeal seal = new FrozenDeclarations.RangeSeal(seat, nodeKey, role,
                batch.worldId(), batch.mailbox(), batch.freezeOrdinal(), batch.planSequence(), freeze,
                cells, RangeDigests.spaceDigest(cells), statuses,
                RangeDigests.statusMapDigest(statuses), instanceId, worldEpoch, stamp);
            RangeSealTable.Publish outcome = table.publish(seal, generation);
            switch (outcome) {
                case PUBLISHED -> {
                    seals++;
                    hold(seal);
                    return seal;
                }
                case IDEMPOTENT -> {
                    return seal;
                }
                case CONFLICT -> {
                    conflicts++;
                    return null;
                }
                case FULL -> {
                    overflows++;
                    return null;
                }
                case REJECTED -> {
                    rejected++;
                    return null;
                }
            }
            return null;
        }
    }

    /** Publishes the declared-level seals of the settlement that just closed: one seal per owner
     * cell of the retained statuses, built from the declaration table of the plan being settled.
     * A status whose batch, cell ownership, seat, freeze, instance or generation cannot be proved
     * is skipped; no seal is guessed and the task run-start path is unchanged. */
    public int publishDeclaredDeclared(FrozenDeclarations declared,
                                       List<DemandShadowCandidates.TaskStatus> retained,
                                       long generation) {
        if (declared == null || !declared.freeze().declared() || retained == null
            || retained.isEmpty() || plans == null) {
            return 0;
        }
        synchronized (table) {
            if (table.generation() != generation) {
                rejected++;
                return 0;
            }
            long plan = declared.planSequence();
            long lifecycle = plans.lifecycle();
            List<TickPlan.CommitStep> order = plans.orderIn(plan, lifecycle);
            if (order == null) {
                return 0;
            }
            FrozenDeclarations.FreezeKey freeze = declared.freeze();
            int published = 0;
            for (DemandShadowCandidates.TaskStatus held : retained) {
                if (held == null || !held.present() || held.stamp() == null) {
                    continue;
                }
                DemandShadowCandidates.TaskStamp read = held.stamp();
                if (read.planSequence() != plan) {
                    continue;
                }
                PipelineBatchLedger.BatchId batch = new PipelineBatchLedger.BatchId(read.world(),
                    read.mailbox(), read.batchFreezeOrdinal(), read.planSequence());
                if (!ledger.bindingPublished(batch)) {
                    continue;
                }
                PipelineBatchLedger.TaskId id = new PipelineBatchLedger.TaskId(read.taskOrdinal(),
                    read.world(), read.mailbox(), read.cell(), read.mintTick());
                if (ledger.ownerOrdinal(batch, id.cell()) != id.ordinal()) {
                    continue;
                }
                String nodeKey = PipelineRoundJobs.batchKeyOf(batch);
                TickPlan.CommitStep step = stepOf(order, nodeKey, batch);
                if (step == null || step.position() < 0) {
                    continue;
                }
                int role = roleOf(order, step);
                if (role <= 0) {
                    continue;
                }
                long[] cells = ledger.cellsOf(batch);
                if (!RangeDigests.wellFormed(cells) || !contains(cells, id.cell())) {
                    continue;
                }
                long worldEpoch = worldEpochs.applyAsLong(batch.worldId());
                if (!CommitLog.liveWorldEpoch(worldEpoch)) {
                    continue;
                }
                FrozenDeclarations.Seat seat = new FrozenDeclarations.Seat(step.worldId(),
                    step.domainId(), plan, step.position());
                if (!crossChecked(seat, declared, nodeKey, role, id.cell(), held.status(),
                    worldEpoch)) {
                    continue;
                }
                FrozenDeclarations.RangeSeal existing = table.locate(seat, id.cell());
                if (existing != null && existing.statusFor(id.cell()) != null
                    && existing.statusFor(id.cell()).targetStatus().equals(held.status())) {
                    published++;
                    continue;
                }
                long instanceId = instances == null ? CommitLog.NO_INSTANCE
                    : instances.getAsLong();
                if (!CommitLog.liveInstance(instanceId)) {
                    continue;
                }
                FrozenDeclarations.Stamp stamp = new FrozenDeclarations.Stamp(read.taskOrdinal(),
                    read.world(), read.mailbox(), read.cell(), read.mintTick(),
                    read.batchFreezeOrdinal(), read.planSequence(), read.demandToken());
                Map<Long, FrozenDeclarations.CellStatus> statuses = Map.of(id.cell(),
                    new FrozenDeclarations.CellStatus(held.status(), stamp));
                FrozenDeclarations.RangeSeal seal = new FrozenDeclarations.RangeSeal(seat, nodeKey,
                    role, batch.worldId(), batch.mailbox(), batch.freezeOrdinal(), plan, freeze,
                    cells, RangeDigests.spaceDigest(cells), statuses,
                    RangeDigests.statusMapDigest(statuses), instanceId, worldEpoch, stamp);
                switch (table.publish(seal, generation)) {
                    case PUBLISHED -> {
                        seals++;
                        hold(seal);
                        published++;
                    }
                    case IDEMPOTENT -> published++;
                    case CONFLICT -> conflicts++;
                    case FULL -> overflows++;
                    case REJECTED -> rejected++;
                }
            }
            return published;
        }
    }

    /** Keeps one published seal pinned until its settlement; a registry that is absent leaves the
     * seal unpinned, and a place some holder already names is not pinned a second time. */
    private void hold(FrozenDeclarations.RangeSeal seal) {
        if (obligations == null || obligations.holds(seal)) {
            return;
        }
        obligations.hold(seal, table.oblige(seal));
    }

    @Override
    public void release(FrozenDeclarations.RangeSeal seal) {
        table.release(seal);
    }

    /** Settles the obligations of one seat after its settlement: a settlement that names a seat
     * the registry does not hold settles nothing. */
    public void settleSeat(FrozenDeclarations.Seat seat) {
        if (obligations != null) {
            obligations.settleSeat(seat);
        }
    }

    /** Settles the obligations of the plans older than the given one: a settlement period that has
     * moved past them can no longer name their seals. */
    public void settleBefore(long planSequence) {
        if (obligations != null) {
            obligations.settleBefore(planSequence);
        }
    }

    /** Drops the registry after the table reset it named has cleared its pins. It is called where
     * the range table is reset so no entry of the previous lifecycle outlives it. */
    public void resetObligations() {
        if (obligations != null) {
            obligations.reset();
        }
    }

    /** Whether a settlement registry is wired: the production factory is handed none, so this
     * answers false and no seal it publishes is pinned. */
    public boolean settlementObligationsRegistered() {
        return obligations != null;
    }

    private FrozenDeclarations.RangeSeal refuse() {
        refused++;
        return null;
    }

    private static boolean stampMatches(DemandShadowCandidates.TaskStamp stamp,
                                        PipelineBatchLedger.TaskId id,
                                        PipelineBatchLedger.BatchId batch) {
        return stamp.taskOrdinal() == id.ordinal() && stamp.world().equals(id.worldId())
            && stamp.mailbox().equals(id.mailbox()) && stamp.cell() == id.cell()
            && stamp.mintTick() == id.mintTick()
            && stamp.batchFreezeOrdinal() == batch.freezeOrdinal()
            && stamp.planSequence() == batch.planSequence() && stamp.demandToken() > 0L
            && batch.worldId().equals(id.worldId()) && batch.mailbox().equals(id.mailbox());
    }

    private static TickPlan.CommitStep stepOf(List<TickPlan.CommitStep> order, String nodeKey,
                                              PipelineBatchLedger.BatchId batch) {
        for (TickPlan.CommitStep step : order) {
            if (step.nodeKey().equals(nodeKey) && step.worldId().equals(batch.worldId())) {
                return step;
            }
        }
        return null;
    }

    private static int roleOf(List<TickPlan.CommitStep> order, TickPlan.CommitStep target) {
        List<FrozenDeclarations.Assignment> places = new ArrayList<>(order.size());
        for (TickPlan.CommitStep step : order) {
            places.add(new FrozenDeclarations.Assignment(step.worldId(), step.domainId(),
                step.nodeKey(), step.position()));
        }
        Integer role = FrozenDeclarations.rolesOf(places).get(new FrozenDeclarations.NodeKey(
            target.worldId(), target.domainId(), target.nodeKey()));
        return role == null ? 0 : role;
    }

    private static boolean crossChecked(FrozenDeclarations.Seat seat, FrozenDeclarations declared,
                                        String nodeKey, int role, long cell, String targetStatus,
                                        long worldEpoch) {
        if (declared.size() == 0) {
            // Nothing sealed for this seat: the rank and the plan's own snapshot are the proof.
            return true;
        }
        FrozenDeclarations.Entry entry = declared.entryAt(seat);
        return entry == null || (entry.nodeKey().equals(nodeKey) && entry.role() == role
            && entry.packedChunkPos() == cell && entry.targetStatus().equals(targetStatus)
            && entry.worldEpoch() == worldEpoch);
    }

    private static boolean contains(long[] cells, long cell) {
        for (long held : cells) {
            if (held == cell) {
                return true;
            }
        }
        return false;
    }

    /** The seals this factory published. */
    public long seals() {
        return seals;
    }

    /** The attempts refused before the table was asked, so no instance number was spent. */
    public long refused() {
        return refused;
    }

    /** The publishes the table refused because the key already carried another seal. */
    public long conflicts() {
        return conflicts;
    }

    /** The publishes refused because the table was full. */
    public long overflows() {
        return overflows;
    }

    /** The attempts refused because the lifecycle generation they captured is no longer current. */
    public long rejected() {
        return rejected;
    }
}
