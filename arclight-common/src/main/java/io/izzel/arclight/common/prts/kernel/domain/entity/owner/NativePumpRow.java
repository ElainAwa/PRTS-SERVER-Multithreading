/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathRequest;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathStage;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathState;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeWritePump;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership;
import net.minecraft.world.level.pathfinder.Path;

import java.util.List;

/** One deferred row in the native seat pump. The ticket is taken and started on the server thread,
 * the original body then runs as the whole-row C form on that same thread with the staged search
 * bound when the read wave produced one, and the terminal, publish and host Post follow the worker
 * batch's own path. The pump binds no owner unit permit: the server thread is its own host, so an
 * event chain must dispatch inline instead of joining a channel nobody serves. */
final class NativePumpRow implements NativeWritePump.Row {

    private static final String OPERATION_KIND = "entity-tick";

    private final ServerThreadOwnerPhase.DeferredRow row;
    private final OwnerControlledWrite.Admission admission;
    private final NativePathRequest request;
    private final Path stagedPath;
    private final boolean staged;
    private final boolean ordinary;
    private OwnerControlledExecution record;

    NativePumpRow(ServerThreadOwnerPhase.DeferredRow row, OwnerControlledWrite.Admission admission,
                  NativePathRequest request, Path stagedPath, boolean staged, boolean ordinary) {
        this.row = row;
        this.admission = admission;
        this.request = request;
        this.stagedPath = stagedPath;
        this.staged = staged && request != null;
        this.ordinary = ordinary;
    }

    /** Whether this row binds its staged search. An ordinary start prediction is dropped when the
     * navigation already has a delayed recomputation pending: the body searches that first, so the
     * ordinary stage is a stale answer. A delayed stage is that pending search and is kept. */
    static boolean stages(boolean staged, boolean ordinary, boolean delayedRecomputation) {
        return staged && (!ordinary || !delayedRecomputation);
    }

    private boolean delayedRecomputation() {
        return row.villager().getNavigation() instanceof NativePathState state
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
        // The batch counts every row it attempted, refused or not, exactly as the worker loop did.
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

    @Override
    public boolean apply() {
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
        OwnerRandomContext.enter(row.level(), row.villager(), "entity");
        EntityUpdateProgramContext.begin(
            ServerThreadOwnerPhase.programIdentity(row.phase(), row.entity()));
        boolean executed = false;
        try {
            held.witness.markEffectPossible();
            EntityEffectPayload.Snapshot before = EntityEffectPayload.capture(row.villager());
            boolean bindStage = stages(staged, ordinary,
                staged && ordinary && delayedRecomputation());
            if (bindStage) {
                NativePathStage.bind(request, stagedPath, ordinary);
            }
            try {
                executed = RealEntityOwnerBody.runWalkOnServer(row.level(), row.villager());
            } finally {
                if (bindStage) {
                    NativePathStage.unbind();
                }
            }
            String changed = EntityEffectPayload.changedValues(before,
                EntityEffectPayload.capture(row.villager()));
            held.effectRow = EntityEffectPayload.row(row.entity().getUUID(),
                executed ? "PUBLISHED" : "FAILED", changed);
            held.outcome = executed
                ? new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, OPERATION_KIND,
                    List.of(held.effectRow), 1, "")
                : new CommitTicket.Outcome(CommitTicket.Terminal.FAILED, OPERATION_KIND,
                    List.of(held.effectRow), 0, "");
        } catch (Throwable failure) {
            held.bodyFailure = failure;
            if (held.effectRow == null) {
                held.effectRow = EntityEffectPayload.row(row.entity().getUUID(), "PARTIAL",
                    "unverified");
            }
        } finally {
            EntityUpdateProgramContext.end();
            OwnerRandomContext.exit();
            EntityTickOwnership.endOwnerBodyScope(scope);
        }
        return executed;
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
