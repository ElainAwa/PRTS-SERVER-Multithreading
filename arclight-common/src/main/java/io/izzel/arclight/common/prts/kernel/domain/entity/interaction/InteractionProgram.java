/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.interaction;

import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.domain.entity.interaction.InteractionResources.PairKey;
import io.izzel.arclight.common.prts.kernel.domain.entity.program.EntityUpdateProgram;
import io.izzel.arclight.common.prts.kernel.domain.entity.program.ProgramPhase;

import java.util.List;
import java.util.Objects;

/**
 * The declared program of one cross-entity interaction: the attacker/victim pair, the interaction
 * kind from the closed set, the effects the controlled phase may apply and the resource keys the
 * interaction asks for. The pair is stored in the deterministic order, the resource keys in one
 * stable order, and one program never claims a live effect: it declares what would be admitted and
 * applied, and the interaction package runs nothing.
 */
public record InteractionProgram(PairKey pair, InteractionKind kind, List<EffectKind> effects,
                                 EntityUpdateProgram program,
                                 List<OwnerRegistry.OwnershipDomain> resourceKeys) {

    /** The interaction kinds this contract declares; the set is closed. */
    public enum InteractionKind {
        ATTACK,
        COLLISION,
        POI_TAKE,
        POI_RELEASE
    }

    /** The effects one interaction may apply in its controlled phase; the set is closed. */
    public enum EffectKind {
        DAMAGE,
        POI_OCCUPANCY
    }

    public InteractionProgram {
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(program, "program");
        effects = List.copyOf(effects);
        resourceKeys = List.copyOf(resourceKeys);
        if (effects.isEmpty()) {
            throw new IllegalArgumentException("an interaction declares at least one effect");
        }
        if (program.kind() != programKind(kind)) {
            throw new IllegalArgumentException("the program kind and the interaction kind must agree");
        }
        if (!program.phaseOrder().contains(ProgramPhase.CONTROLLED_WRITE)) {
            throw new IllegalArgumentException("an interaction declares its controlled phase");
        }
        for (int index = 1; index < resourceKeys.size(); index++) {
            if (InteractionResources.compareRows(pair.attacker(),
                pair.attacker()) == 0 && resourceKeys.get(index - 1).domainId()
                .compareTo(resourceKeys.get(index).domainId()) > 0) {
                throw new IllegalArgumentException("resource keys are kept in one stable order");
            }
        }
    }

    /** One attack: the attacker hits the victim, its controlled phase declares the damage effect. */
    public static InteractionProgram attack(PairKey pair, EntityUpdateProgram.Input input) {
        return new InteractionProgram(pair, InteractionKind.ATTACK, List.of(EffectKind.DAMAGE),
            writeProgram(EntityUpdateProgram.ProgramKind.ATTACK,
                EntityUpdateProgram.EffectKind.DAMAGE, "health", input),
            InteractionResources.keys(pair, true, false, false, null));
    }

    /** One collision: the pair pushes each other; the controlled phase declares the damage effect. */
    public static InteractionProgram collision(PairKey pair, EntityUpdateProgram.Input input) {
        return new InteractionProgram(pair, InteractionKind.COLLISION, List.of(EffectKind.DAMAGE),
            writeProgram(EntityUpdateProgram.ProgramKind.COLLISION,
                EntityUpdateProgram.EffectKind.DAMAGE, "motion", input),
            InteractionResources.keys(pair, false, false, false, null));
    }

    /** One point-of-interest take: the controlled phase declares the occupancy effect. */
    public static InteractionProgram poiTake(PairKey pair, String recordKey,
                                             EntityUpdateProgram.Input input) {
        return poi(pair, recordKey, EntityUpdateProgram.ProgramKind.POI_TAKE,
            InteractionKind.POI_TAKE, input);
    }

    /** One point-of-interest release: the controlled phase declares the occupancy effect. */
    public static InteractionProgram poiRelease(PairKey pair, String recordKey,
                                                EntityUpdateProgram.Input input) {
        return poi(pair, recordKey, EntityUpdateProgram.ProgramKind.POI_RELEASE,
            InteractionKind.POI_RELEASE, input);
    }

    private static InteractionProgram poi(PairKey pair, String recordKey,
                                          EntityUpdateProgram.ProgramKind programKind,
                                          InteractionKind kind, EntityUpdateProgram.Input input) {
        OwnerRegistry.OwnershipDomain occupancy = InteractionResources.poiOccupancy(
            pair.attacker().worldId(), recordKey);
        return new InteractionProgram(pair, kind, List.of(EffectKind.POI_OCCUPANCY),
            writeProgram(programKind, EntityUpdateProgram.EffectKind.POI_OCCUPANCY, "occupancy",
                input),
            InteractionResources.keys(pair, false, false, false, occupancy));
    }

    private static EntityUpdateProgram writeProgram(EntityUpdateProgram.ProgramKind kind,
                                                    EntityUpdateProgram.EffectKind effect,
                                                    String writtenResource,
                                                    EntityUpdateProgram.Input input) {
        return new EntityUpdateProgram(kind, input,
            new EntityUpdateProgram.RwSet(List.of(new EntityUpdateProgram.Resource("self", "row")),
                List.of(new EntityUpdateProgram.Resource("self", writtenResource))),
            java.util.Set.of(EntityUpdateProgram.Capability.LIVE_READ,
                EntityUpdateProgram.Capability.EVENT_RESULT,
                EntityUpdateProgram.Capability.OWNER_LOCAL_WRITE),
            List.of(ProgramPhase.INPUT, ProgramPhase.READ_COMPUTE, ProgramPhase.DECISION,
                ProgramPhase.CONTROLLED_WRITE, ProgramPhase.SUCCESSOR),
            List.of(new EntityUpdateProgram.AllowedEffect(effect, ProgramPhase.CONTROLLED_WRITE)),
            List.of(new EntityUpdateProgram.CancelPoint(ProgramPhase.INPUT, "input"),
                new EntityUpdateProgram.CancelPoint(ProgramPhase.READ_COMPUTE, "read"),
                new EntityUpdateProgram.CancelPoint(ProgramPhase.DECISION, "decision")),
            List.of(new EntityUpdateProgram.Successor(kind, ProgramPhase.SUCCESSOR,
                "after-interaction")));
    }

    private static EntityUpdateProgram.ProgramKind programKind(InteractionKind kind) {
        return switch (kind) {
            case ATTACK -> EntityUpdateProgram.ProgramKind.ATTACK;
            case COLLISION -> EntityUpdateProgram.ProgramKind.COLLISION;
            case POI_TAKE -> EntityUpdateProgram.ProgramKind.POI_TAKE;
            case POI_RELEASE -> EntityUpdateProgram.ProgramKind.POI_RELEASE;
        };
    }
}
