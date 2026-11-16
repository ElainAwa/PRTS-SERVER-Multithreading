/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The declared shape of one complete entity update program: the frozen input, the read and write
 * set, the capabilities the program needs, the phases it runs, the effects the write phase may
 * apply, the cancel points before the first effect, and the successor steps it declares. The shape
 * is validated once and is immutable; it declares what a program would do and grants nothing. This
 * slice builds the shape only: no production path constructs one and no effect is written.
 */
public record EntityUpdateProgram(ProgramKind kind, Input input, RwSet rw,
                                  Set<Capability> capabilities, List<ProgramPhase> phases,
                                  List<AllowedEffect> effects, List<CancelPoint> cancelPoints,
                                  List<Successor> successors) {

    /** The entity steps a program may name. The set is closed: an unnamed step is no program. */
    public enum ProgramKind {
        BASE_TICK,
        LIVING_TICK,
        MOB_TICK,
        BRAIN_TICK,
        GOAL_SELECTOR,
        SENSOR_TICK,
        NAVIGATION,
        MOVE_CONTROL
    }

    /** What a program needs before it may run. */
    public enum Capability {
        /** The program reads the live row under a read permit instead of a copy. */
        LIVE_READ,
        /** The program needs the decision event result before its first effect. */
        EVENT_RESULT,
        /** The program writes its own row in the controlled write phase. */
        OWNER_LOCAL_WRITE,
        /** The program touches another entity's state; its ordering is a separate dependency. */
        CROSS_ENTITY,
        /** The program publishes its result to a declared visible exit. */
        PUBLISH
    }

    /** The kind of effect one controlled phase may apply. The set is closed. */
    public enum EffectKind {
        POSITION,
        ROTATION,
        MOTION,
        AIR,
        HEALTH,
        TARGET,
        INVENTORY
    }

    /** The frozen input of one update: the row identity and the state the program was frozen at. */
    public record Input(String worldId, long worldEpoch, EntityRowIdentity identity,
                        long objectGeneration, long expectedStateRevision,
                        long logicalUpdateOrdinal) {

        public Input {
            Objects.requireNonNull(worldId, "worldId");
            if (worldId.isEmpty()) {
                throw new IllegalArgumentException("a program input needs a world");
            }
            Objects.requireNonNull(identity, "identity");
            if (!identity.worldId().equals(worldId)) {
                throw new IllegalArgumentException("the input identity belongs to another world");
            }
            if (worldEpoch < 0L || objectGeneration < 0L || expectedStateRevision < 0L
                || logicalUpdateOrdinal < 0L) {
                throw new IllegalArgumentException("an input generation cannot be negative");
            }
        }
    }

    /** One declared resource of the read or write set: a scope and a stable name in it. */
    public record Resource(String scope, String name) implements Comparable<Resource> {

        public Resource {
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(name, "name");
            if (scope.isEmpty() || name.isEmpty()) {
                throw new IllegalArgumentException("a resource needs a scope and a name");
            }
        }

        @Override
        public int compareTo(Resource other) {
            int scopeOrder = scope.compareTo(other.scope);
            return scopeOrder != 0 ? scopeOrder : name.compareTo(other.name);
        }
    }

    /** The read and write set of one program, kept in one stable key order. */
    public record RwSet(List<Resource> reads, List<Resource> writes) {

        public RwSet {
            reads = sorted(reads);
            writes = sorted(writes);
            if (!java.util.Collections.disjoint(reads, writes)) {
                throw new IllegalArgumentException("a resource cannot be read and written at once");
            }
        }

        private static List<Resource> sorted(List<Resource> resources) {
            if (resources == null || resources.isEmpty()) {
                return List.of();
            }
            List<Resource> copy = new ArrayList<>(resources);
            copy.sort(Resource::compareTo);
            Set<Resource> distinct = new HashSet<>(copy);
            if (distinct.size() != copy.size()) {
                throw new IllegalArgumentException("a resource is declared twice");
            }
            return List.copyOf(copy);
        }
    }

    /** One effect the controlled write phase may apply. */
    public record AllowedEffect(EffectKind kind, ProgramPhase phase) {

        public AllowedEffect {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(phase, "phase");
        }
    }

    /** One declared safe cancel point: the phase and the step it may stop the update at. */
    public record CancelPoint(ProgramPhase phase, String name) {

        public CancelPoint {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(name, "name");
            if (name.isEmpty()) {
                throw new IllegalArgumentException("a cancel point needs a name");
            }
        }
    }

    /** One declared successor step of this update, started from a phase of this program. */
    public record Successor(ProgramKind kind, ProgramPhase start, String note) {

        public Successor {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(start, "start");
            note = note == null ? "" : note;
        }
    }

    public EntityUpdateProgram {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(rw, "rw");
        capabilities = capabilities == null ? Set.of() : Set.copyOf(capabilities);
        phases = validatedPhases(phases);
        effects = effects == null ? List.of() : List.copyOf(effects);
        cancelPoints = cancelPoints == null ? List.of() : List.copyOf(cancelPoints);
        successors = successors == null ? List.of() : List.copyOf(successors);
        validateShape(kind, rw, capabilities, phases, effects, cancelPoints, successors);
    }

    private static List<ProgramPhase> validatedPhases(List<ProgramPhase> phases) {
        if (phases == null || phases.isEmpty()) {
            throw new IllegalArgumentException("a program needs at least its input phase");
        }
        if (phases.get(0) != ProgramPhase.INPUT) {
            throw new IllegalArgumentException("a program starts at the input phase");
        }
        EnumSet<ProgramPhase> seen = EnumSet.noneOf(ProgramPhase.class);
        ProgramPhase previous = null;
        for (ProgramPhase phase : phases) {
            Objects.requireNonNull(phase, "phase");
            if (!seen.add(phase)) {
                throw new IllegalArgumentException("a phase is declared twice: " + phase);
            }
            if (previous != null && phase.ordinal() <= previous.ordinal()) {
                throw new IllegalArgumentException("phases out of order: " + previous + " -> " + phase);
            }
            previous = phase;
        }
        return List.copyOf(phases);
    }

    private static void validateShape(ProgramKind kind, RwSet rw, Set<Capability> capabilities,
                                      List<ProgramPhase> phases, List<AllowedEffect> effects,
                                      List<CancelPoint> cancelPoints, List<Successor> successors) {
        boolean hasOwnedWrite = capabilities.contains(Capability.OWNER_LOCAL_WRITE);
        if (hasOwnedWrite != phases.contains(ProgramPhase.CONTROLLED_WRITE)) {
            throw new IllegalArgumentException(
                "the owner-local write capability and the controlled phase must be declared together");
        }
        if (effects.isEmpty() != rw.writes().isEmpty() || effects.isEmpty() == hasOwnedWrite) {
            throw new IllegalArgumentException(
                "the allowed effects, the write set and the write capability must agree");
        }
        for (AllowedEffect effect : effects) {
            if (effect.phase() != ProgramPhase.CONTROLLED_WRITE) {
                throw new IllegalArgumentException("an effect is only allowed in the controlled phase");
            }
        }
        if (effects.stream().map(AllowedEffect::kind).distinct().count() != effects.size()) {
            throw new IllegalArgumentException("an effect kind is declared twice");
        }
        Set<String> cancelNames = new HashSet<>();
        for (CancelPoint point : cancelPoints) {
            if (!phases.contains(point.phase())) {
                throw new IllegalArgumentException("a cancel point names an undeclared phase");
            }
            if (!point.phase().readOnly()) {
                // Cancel is atomic only before the first effect; after it the update has a prefix.
                throw new IllegalArgumentException("a cancel point cannot follow the first effect");
            }
            if (!cancelNames.add(point.name())) {
                throw new IllegalArgumentException("a cancel point is declared twice");
            }
        }
        for (Successor successor : successors) {
            if (!phases.contains(successor.start())) {
                throw new IllegalArgumentException("a successor starts on an undeclared phase");
            }
            if (successor.kind() == kind && successor.start() == ProgramPhase.INPUT) {
                throw new IllegalArgumentException("a program cannot succeed itself from its input");
            }
        }
    }

    /** The default dormant shape: a read-only program that stops at its successor declaration. */
    public static EntityUpdateProgram dormant(ProgramKind kind, Input input) {
        return new EntityUpdateProgram(kind, input,
            new RwSet(List.of(new Resource("self", "row")), List.of()),
            Set.of(Capability.LIVE_READ),
            List.of(ProgramPhase.INPUT, ProgramPhase.READ_COMPUTE, ProgramPhase.DECISION,
                ProgramPhase.SUCCESSOR),
            List.of(),
            List.of(new CancelPoint(ProgramPhase.INPUT, "input"),
                new CancelPoint(ProgramPhase.READ_COMPUTE, "read"),
                new CancelPoint(ProgramPhase.DECISION, "decision")),
            List.of());
    }

    /** Whether this program declares any effect; a dormant program declares none. */
    public boolean writes() {
        return !effects.isEmpty();
    }

    /** The phases this program runs, in the fixed execution order. */
    public List<ProgramPhase> phaseOrder() {
        return phases;
    }
}
