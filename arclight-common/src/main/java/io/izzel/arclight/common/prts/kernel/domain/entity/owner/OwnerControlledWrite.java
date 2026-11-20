/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.commit.CommitController;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.CommitNamespaces;
import io.izzel.arclight.common.prts.kernel.commit.CommitTicket;
import io.izzel.arclight.common.prts.kernel.commit.EffectRequestFactory;
import io.izzel.arclight.common.prts.kernel.commit.EffectStore;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.commit.IdentityWitness;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityObjectGenerations;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.wiring.OwnerNativeCommitBinding;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.Villager;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** The controlled write of one due entity row on the host owner thread: the same frozen declaration
 * gives the seat, the chain's own grant gives the version and the authorization generation, and the
 * real walk body runs inside the controller's publish action. A row without that chain-ready state
 * is not admitted here, so it is handled by the host at the original call site instead. */
public final class OwnerControlledWrite {

    private static final AtomicLong EFFECT_REFS = new AtomicLong(1L);

    private static final String OPERATION_KIND = "entity-tick";

    public record Admission(CommitController controller, FrozenDeclarations.Entry entry,
                            CommitTicket.ReserveRequest request, EffectStore.Effect published,
                            EffectStore.Effect partial, EffectStore.Effect failed,
                            long expectedVersion) {
    }

    private OwnerControlledWrite() {
    }

    /** The admission for this row, or null when the chain, the plan, the declaration or the grant is
     * not ready: null is a pre-effect refusal and the host keeps the row. */
    public static Admission prepare(ServerLevel level, Entity entity) {
        KernelModule module = KernelModule.instance();
        CommitController controller = module.commitController();
        if (controller == null || !controller.executable()) {
            return null;
        }
        TickPlan plan = module.plans().latest();
        FrozenDeclarations declarations = module.declarations();
        if (plan == null) {
            return null;
        }
        if (declarations == null) {
            return null;
        }
        String worldId = level.dimension().location().toString();
        long uuidHigh = entity.getUUID().getMostSignificantBits();
        long uuidLow = entity.getUUID().getLeastSignificantBits();
        long generation = EntityObjectGenerations.shared()
            .generationOf(worldId, uuidHigh, uuidLow);
        String domain = FrozenDeclarations.ENTITY_STATE_PREFIX + uuidHigh + ":" + uuidLow + ":"
            + generation;
        int position = -1;
        for (TickPlan.CommitStep step : plan.commitOrder()) {
            if (step.worldId().equals(worldId) && step.domainId().equals("entity")
                && step.nodeKey().equals(domain)) {
                position = step.position();
                break;
            }
        }
        if (position < 0) {
            return null;
        }
        FrozenDeclarations.Entry entry = declarations.entryAt(new FrozenDeclarations.Seat(worldId,
            "entity", plan.planSequence(), position));
        if (entry == null) {
            return null;
        }
        if (entry.kind() != FrozenDeclarations.TargetKind.ENTITY) {
            return null;
        }
        long version = module.versionSlots().grantedFor(plan.planSequence(), worldId,
            WriteLevel.REGION, "entity");
        if (version <= 0L) {
            return null;
        }
        IdentityWitness.Source source = module.commits().identitySource();
        long liveEpoch = source.liveWorldEpoch(worldId);
        if (liveEpoch != entry.worldEpoch()) {
            return null;
        }
        CommitNamespaces.Namespace carrier = controller.reservations().controller();
        IdentityWitness.Authorization authorization = source.liveAuthorization(plan.planSequence(),
            carrier, worldId, "entity", domain);
        if (!authorization.admitted()) {
            return null;
        }
        if (authorization.source().generation() != version) {
            return null;
        }
        long peek = module.versionSlots().peek(worldId, WriteLevel.REGION, "entity");
        if (peek != version) {
            return null;
        }
        long effectRef = EFFECT_REFS.getAndIncrement();
        EffectStore.Effect published = effect(effectRef, "published");
        EffectStore.Effect partial = effect(effectRef, "partial");
        EffectStore.Effect failed = effect(effectRef, "failed");
        CommitNamespaces.Namespace controllerNamespace = controller.reservations().controller();
        CommitNamespaces.Namespace producer = new CommitNamespaces.Namespace(
            module.ownerContext().primarySite().siteId(), CommitNamespaces.Lifetimes.mint(),
            controllerNamespace.resetEpoch(), CommitNamespaces.NO_THREAD);
        CommitTicket.ReserveRequest request = EffectRequestFactory.request(entry, version, version,
            (long) domain.hashCode(), CommitLog.Batch.Kind.APPLY, producer, controllerNamespace,
            published, partial, failed);
        return new Admission(controller, entry, request, published, partial, failed, version);
    }

    /** Runs the real walk body inside the controller's publish action on this thread. */
    public static CommitController.SubmitResult run(ServerLevel level, Villager villager,
                                                     Admission admission,
                                                     OwnerNativeCommitBinding binding) {
        boolean[] executed = new boolean[1];
        RuntimeException[] failure = new RuntimeException[1];
        OwnerNativeCommitBinding.Fragment fragment = ticket -> {
            if (!ticket.worldId().equals(admission.entry().seat().worldId())
                || !ticket.domainId().equals(admission.entry().seat().domainId())
                || !ticket.nodeKey().equals(admission.entry().nodeKey())
                || ticket.effectRef() != admission.published().effectRef()) {
                throw new IllegalStateException(
                    "the controller published a ticket this declaration did not admit");
            }
            try {
                executed[0] = RealEntityOwnerBody.runWalkOnServer(level, villager);
                return executed[0] ? outcome(CommitTicket.Terminal.PUBLISHED, admission.published())
                    : outcome(CommitTicket.Terminal.FAILED, admission.failed());
            } catch (RuntimeException thrown) {
                failure[0] = thrown;
                return outcome(CommitTicket.Terminal.PARTIAL, admission.partial());
            }
        };
        CommitController.SubmitResult result = binding.submit(admission.controller(),
            admission.request(), fragment);
        if (failure[0] != null) {
            throw failure[0];
        }
        return result;
    }

    /** Runs the real walk body for an already begun controlled ticket and maps it to an outcome;
     * the caller writes that outcome through finishControl and must not submit another reservation. */
    static CommitTicket.Outcome runBodyOnOwner(ServerLevel level, Villager villager,
                                               Admission admission) {
        boolean executed = RealEntityOwnerBody.runWalkOnServer(level, villager);
        return executed ? outcome(CommitTicket.Terminal.PUBLISHED, admission.published())
            : outcome(CommitTicket.Terminal.FAILED, admission.failed());
    }

    /** The outcome shape for a begun ticket whose body failed after it started. */
    static CommitTicket.Outcome partialOutcome(Admission admission) {
        return outcome(CommitTicket.Terminal.PARTIAL, admission.partial());
    }

    /** The published outcome of one declared program span. */
    static CommitTicket.Outcome publishedOutcome(Admission admission) {
        return outcome(CommitTicket.Terminal.PUBLISHED, admission.published());
    }

    private static CommitTicket.Outcome outcome(CommitTicket.Terminal terminal,
                                                EffectStore.Effect effect) {
        return new CommitTicket.Outcome(terminal, OPERATION_KIND,
            EffectStore.normalizedRows(effect.rows()), terminal == CommitTicket.Terminal.PUBLISHED
                ? 1 : 0, "");
    }

    private static EffectStore.Effect effect(long effectRef, String outcome) {
        return new EffectStore.Effect(effectRef, OPERATION_KIND, outcome,
            List.of("entity-state"));
    }
}
