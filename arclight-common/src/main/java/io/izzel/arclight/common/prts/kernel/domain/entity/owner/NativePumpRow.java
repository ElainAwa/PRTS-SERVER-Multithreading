/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeGoalStage;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathRequest;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeWritePump;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeMoveStage;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeNeighborStage;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathStage;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathState;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership;
import io.izzel.arclight.common.prts.fixes.ownership.PrtsLivingEntityTickStateMixin;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.pathfinder.Path;

import java.util.List;

/** One deferred row whose body is split into two seat passes: phase A runs the original tick with
 * the later stages deferred, the collision-query wave closes, and phase B runs the deferred stages and publishes
 * the same ticket. The seat order and the original host stage order are unchanged. */
final class NativePumpRow implements NativeWritePump.Row {

    private static final String OPERATION_KIND = "entity-tick";

    private final ServerThreadOwnerPhase.DeferredRow row;
    private final OwnerControlledWrite.Admission admission;
    private final String stageKey;
    private final NativePathRequest request;
    private final Path stagedPath;
    private final boolean staged;
    private final boolean ordinary;
    private OwnerControlledExecution record;
    private EntityEffectPayload.Snapshot before;
    private boolean boundPath;
    private boolean boundNeighbours;
    private boolean boundGoals;
    private boolean executed;

    NativePumpRow(ServerThreadOwnerPhase.DeferredRow row, OwnerControlledWrite.Admission admission,
                  String stageKey, NativePathRequest request, Path stagedPath, boolean staged,
                  boolean ordinary) {
        this.row = row;
        this.admission = admission;
        this.stageKey = stageKey;
        this.request = request;
        this.stagedPath = stagedPath;
        this.staged = staged && request != null;
        this.ordinary = ordinary;
    }

    static boolean stages(boolean staged, boolean ordinary, boolean delayedRecomputation) {
        return staged && (!ordinary || !delayedRecomputation);
    }

    private boolean delayedRecomputation() {
        return row.mob().getNavigation() instanceof NativePathState state
            && state.prts$hasDelayedRecomputation();
    }

    @Override
    public int seat() {
        return admission.entry().seat().position();
    }

    @Override
    public boolean readReady() {
        return staged;
    }

    @Override
    public boolean begin() {
        StageCounters.note("ownerBatchBody");
        CommitController controller = admission.controller();
        CommitController.BeginResult begin = controller.beginControl(admission.request());
        if (!begin.admitted()) {
            ServerThreadOwnerPhase.notePreEffectRefusal(row);
            return false;
        }
        record = new OwnerControlledExecution(row, admission, begin.ticket());
        return true;
    }

    /** Runs the prefix pass on the Server thread. The ticket stays open for phase B. */
    boolean applyPhaseA() {
        OwnerControlledExecution held = record;
        if (held == null) {
            throw new IllegalStateException("a pumped row has no begun ticket");
        }
        FrozenDeclarations.Entry entry = admission.entry();
        CommitTicket ticket = held.ticket;
        if (!ticket.batch().worldId().equals(entry.seat().worldId())
            || !ticket.batch().domainId().equals(entry.seat().domainId())
            || !ticket.batch().nodeKey().equals(entry.nodeKey())
            || ticket.identity().effectRef() != admission.published().effectRef()) {
            throw new IllegalStateException("a pumped ticket does not match its declaration");
        }
        long scope = EntityTickOwnership.beginOwnerBodyScope(row.entity());
        OwnerRandomContext.enter(row.level(), row.mob(), "entity");
        EntityUpdateProgramContext.begin(
            ServerThreadOwnerPhase.programIdentity(row.phase(), row.entity()));
        NativeMovePhaseContext.beginPhaseA();
        try {
            held.witness.markEffectPossible();
            before = ServerThreadOwnerPhase.captureRow(row);
            boundPath = stages(staged, ordinary, staged && ordinary && delayedRecomputation());
            if (boundPath) {
                NativePathStage.bind(request, stagedPath, ordinary);
            }
            String key = rowKey();
            boundNeighbours = NativeNeighborStage.has(key);
            if (boundNeighbours) {
                NativeNeighborStage.bind(key);
            }
            boundGoals = NativeGoalStage.has(key);
            if (boundGoals) {
                NativeGoalStage.bind(key);
            }
            try {
                executed = ServerThreadOwnerPhase.runWalkBody(row);
            // Approved stable-read checkpoint: capture the live control inputs at phase-A end on
            // this Server thread only; host rows have no phase context and take no action here.
            if (NativeMovePhaseContext.bound()
                && row.mob() instanceof PrtsLivingEntityTickStateMixin state) {
                NativeMovePhaseContext.freezeControl(
                    state.prts$xxa(), state.prts$zza(), state.prts$jumping());
            }
            } finally {
                if (boundGoals) NativeGoalStage.take();
                if (boundNeighbours) NativeNeighborStage.take();
                if (boundPath) NativePathStage.unbind();
            }
            return executed;
        } finally {
            NativeMovePhaseContext.endPhaseA();
            EntityUpdateProgramContext.end();
            OwnerRandomContext.exit();
            EntityTickOwnership.endOwnerBodyScope(scope);
        }
    }

    /** Whether this row still owns a ticket after its phase-A pass. */
    boolean begun() {
        return record != null;
    }

    /** Runs the successor pass, consumes the staged collision answer and publishes the ticket. */
    boolean applyPhaseB() {
        OwnerControlledExecution held = record;
        if (held == null || before == null) {
            throw new IllegalStateException("a pumped row has no phase-A result");
        }
        long scope = EntityTickOwnership.beginOwnerBodyScope(row.entity());
        OwnerRandomContext.enter(row.level(), row.mob(), "entity");
        EntityUpdateProgramContext.begin(
            ServerThreadOwnerPhase.programIdentity(row.phase(), row.entity()));
        NativeMovePhaseContext.beginPhaseB();
        try {
            LivingEntity stages = row.mob();
            String key = rowKey();
            boolean bindMove = NativeMoveStage.has(key);
            if (bindMove) {
                NativeMoveStage.bind(key);
            }
            try {
                NativeMovePhaseContext.Control control = NativeMovePhaseContext.restoreControl();
                if (control != null
                    && stages instanceof PrtsLivingEntityTickStateMixin state) {
                    // Restore the frozen inputs into the live entity before entering phase B, so both
                    // serial and parallel arms enter travel with the same control checkpoint.
                    state.prts$setXxa(control.xxa());
                    state.prts$setZza(control.zza());
                    state.prts$setJumping(control.jumping());
                }
                if (stages instanceof LivingEntityAiStepStages split) {
                    split.prts$aiStepPhaseB();
                    split.prts$tickTail();
                    if (stages instanceof MobTickTailStages mobTail) {
                        mobTail.prts$mobTickTail();
                    }
                }
            } finally {
                NativeMoveStage.unbind();
            }
            executed = executed && stages.isAlive();
            String changed = EntityEffectPayload.changedValues(before,
                ServerThreadOwnerPhase.captureRow(row));
            held.effectRow = EntityEffectPayload.row(row.entity().getUUID(),
                executed ? "PUBLISHED" : "FAILED", changed);
            held.outcome = executed
                ? new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, OPERATION_KIND,
                    List.of(held.effectRow), 1, "")
                : new CommitTicket.Outcome(CommitTicket.Terminal.FAILED, OPERATION_KIND,
                    List.of(held.effectRow), 0, "");
            return executed;
        } catch (Throwable failure) {
            held.bodyFailure = failure;
            if (held.effectRow == null) {
                held.effectRow = EntityEffectPayload.row(row.entity().getUUID(), "PARTIAL",
                    "unverified");
            }
            return false;
        } finally {
            NativeMovePhaseContext.end();
            EntityUpdateProgramContext.end();
            OwnerRandomContext.exit();
            EntityTickOwnership.endOwnerBodyScope(scope);
        }
    }

    private String rowKey() {
        return stageKey;
    }

    /** The wave-scoped staging key of this row; the pump discards any leftover after the write. */
    String stageKey() {
        return stageKey;
    }

    @Override
    public boolean apply() {
        return applyPhaseA() && applyPhaseB();
    }

    @Override
    public void complete() {
        OwnerControlledExecution held = record;
        record = null;
        if (held == null) {
            return;
        }
        ServerThreadOwnerPhase.completeControlledExecution(held, true);
    }
}
