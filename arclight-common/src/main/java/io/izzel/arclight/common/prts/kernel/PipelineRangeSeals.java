/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.commit.IdentityWitness;
import io.izzel.arclight.common.prts.kernel.commit.RangeDigests;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealTable;
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
    private final Supplier<FrozenDeclarations.FreezeKey> freezes;
    private final IdentityWitness.Source declarations;
    private long seals;
    private long refused;
    private long conflicts;
    private long overflows;
    private long rejected;

    public PipelineRangeSeals(PipelineBatchLedger ledger, RangeSealTable table, TickPlanStore plans,
                              LongSupplier instances, ToLongFunction<String> worldEpochs,
                              Supplier<FrozenDeclarations.FreezeKey> freezes,
                              IdentityWitness.Source declarations) {
        this.ledger = ledger;
        this.table = table;
        this.plans = plans;
        this.instances = instances;
        this.worldEpochs = worldEpochs == null ? worldId -> CommitLog.NO_WORLD_EPOCH : worldEpochs;
        this.freezes = freezes;
        this.declarations = declarations;
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
            if (ledger.currentPlan() != batch.planSequence()) {
                return refuse();
            }
            if (ownerOrdinal(batch, id.cell()) != id.ordinal()) {
                // One cell has one owner: the smallest task ordinal the published binding carries
                // for it. A later task of the same cell never publishes a second seal.
                return refuse();
            }
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
            FrozenDeclarations.FreezeKey freeze = freezes == null ? null : freezes.get();
            if (freeze == null || !freeze.declared()) {
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
            if (!crossChecked(seat, freeze, batch, nodeKey, role, id.cell(), status, worldEpoch)) {
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

    @Override
    public void release(FrozenDeclarations.RangeSeal seal) {
        table.release(seal);
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

    private long ownerOrdinal(PipelineBatchLedger.BatchId batch, long cell) {
        long owner = -1L;
        for (PipelineBatchLedger.TrackedTask member : ledger.tasksOf(batch)) {
            if (member.id().cell() == cell && (owner < 0L || member.id().ordinal() < owner)) {
                owner = member.id().ordinal();
            }
        }
        return owner;
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

    private boolean crossChecked(FrozenDeclarations.Seat seat, FrozenDeclarations.FreezeKey freeze,
                                 PipelineBatchLedger.BatchId batch, String nodeKey, int role,
                                 long cell, DemandShadowCandidates.TaskStatus status, long worldEpoch) {
        if (declarations == null) {
            return true;
        }
        FrozenDeclarations held = declarations.declarations(batch.worldId(), seat.domainId(),
            batch.planSequence());
        if (held == null || held.size() == 0) {
            // Nothing sealed for this seat: the rank and the plan's own snapshot are the proof.
            return true;
        }
        if (!freeze.equals(held.freeze())) {
            return false;
        }
        FrozenDeclarations.Entry entry = held.entryAt(seat);
        return entry == null || (entry.nodeKey().equals(nodeKey) && entry.role() == role
            && entry.packedChunkPos() == cell && entry.targetStatus().equals(status.status())
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

    public long seals() {
        return seals;
    }

    public long refused() {
        return refused;
    }

    public long conflicts() {
        return conflicts;
    }

    public long overflows() {
        return overflows;
    }

    public long rejected() {
        return rejected;
    }
}
