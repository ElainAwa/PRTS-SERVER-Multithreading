/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The witness of one program run: every declared phase is marked once in the declared order with
 * the digest of the input and of the output the phase produced, and a phase that reads after the
 * controlled write records whether it observed the own write. The witness is complete only when
 * every declared phase was marked; an incomplete or out-of-order run is refused at build time
 * instead of being reported as a finished update. One builder belongs to one owner thread and the
 * built witness is immutable.
 */
public final class ProgramWitness {

    /** One marked phase and what it read and produced. */
    public record PhaseMark(ProgramPhase phase, long inputDigest, long outputDigest,
                            boolean readOwnWriteVisible) {

        public PhaseMark {
            Objects.requireNonNull(phase, "phase");
        }
    }

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private final EntityUpdateProgram.ProgramKind kind;
    private final List<PhaseMark> marks;
    private final List<ProgramPhase> programPhases;
    private final long inputDigest;
    private final long outputDigest;

    private ProgramWitness(EntityUpdateProgram.ProgramKind kind, List<PhaseMark> marks,
                           List<ProgramPhase> programPhases) {
        this.kind = kind;
        this.marks = List.copyOf(marks);
        this.programPhases = List.copyOf(programPhases);
        long input = OFFSET_BASIS;
        long output = OFFSET_BASIS;
        for (PhaseMark mark : marks) {
            input = fold(input, mark.inputDigest());
            output = fold(output, mark.outputDigest());
        }
        this.inputDigest = input;
        this.outputDigest = output;
    }

    /** The kind of the program this witness belongs to. */
    public EntityUpdateProgram.ProgramKind kind() {
        return kind;
    }

    /** The phase marks in the order the run produced them. */
    public List<PhaseMark> marks() {
        return marks;
    }

    /** The folded digest of every phase's input, for the host parity reference. */
    public long inputDigest() {
        return inputDigest;
    }

    /** The folded digest of every phase's output, for the host parity reference. */
    public long outputDigest() {
        return outputDigest;
    }

    /** The mark of one phase, or null when the run never reached it. */
    public PhaseMark mark(ProgramPhase phase) {
        for (PhaseMark mark : marks) {
            if (mark.phase() == phase) {
                return mark;
            }
        }
        return null;
    }

    /** The phases the witness never marked. */
    public List<ProgramPhase> missingPhases() {
        return missing(marks);
    }

    private List<ProgramPhase> missing(List<PhaseMark> marked) {
        List<ProgramPhase> missing = new ArrayList<>();
        for (ProgramPhase phase : programPhases) {
            boolean found = false;
            for (PhaseMark mark : marked) {
                if (mark.phase() == phase) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                missing.add(phase);
            }
        }
        return List.copyOf(missing);
    }

    /** Whether every declared phase was marked once. */
    public boolean complete() {
        return missingPhases().isEmpty() && marks.size() == programPhases.size();
    }

    /** Whether any phase observed its own write; a read-only program can never claim one. */
    public boolean readOwnWriteVisible() {
        for (PhaseMark mark : marks) {
            if (mark.readOwnWriteVisible()) {
                return true;
            }
        }
        return false;
    }

    /** The phases the run marked, in order. */
    public List<ProgramPhase> markedPhases() {
        List<ProgramPhase> phases = new ArrayList<>(marks.size());
        for (PhaseMark mark : marks) {
            phases.add(mark.phase());
        }
        return List.copyOf(phases);
    }

    public static Builder builder(EntityUpdateProgram program) {
        return new Builder(program);
    }

    /** Folds one digest into the running witness digest. */
    static long fold(long digest, long value) {
        long folded = digest ^ value;
        return folded * PRIME;
    }

    /** One owner thread's builder of a program witness. */
    public static final class Builder {

        private final EntityUpdateProgram program;
        private final List<ProgramPhase> phases;
        private final List<PhaseMark> marks = new ArrayList<>();

        private Builder(EntityUpdateProgram program) {
            this.program = Objects.requireNonNull(program, "program");
            this.phases = program.phaseOrder();
        }

        /**
         * Marks the next declared phase with the digests of what it read and produced. A phase is
         * marked once and in the declared order; a phase that runs after the controlled write may
         * report that it observed the own write, and no phase before it can claim one.
         */
        public Builder mark(ProgramPhase phase, long inputDigest, long outputDigest,
                            boolean readOwnWriteVisible) {
            Objects.requireNonNull(phase, "phase");
            if (marks.size() >= phases.size() || phases.get(marks.size()) != phase) {
                throw new IllegalStateException("phase out of order or undeclared: " + phase);
            }
            if (readOwnWriteVisible && !afterControlledWrite(phase)) {
                throw new IllegalArgumentException(
                    "only a phase after the controlled write can observe the own write: " + phase);
            }
            marks.add(new PhaseMark(phase, inputDigest, outputDigest, readOwnWriteVisible));
            return this;
        }

        private boolean afterControlledWrite(ProgramPhase phase) {
            int write = phases.indexOf(ProgramPhase.CONTROLLED_WRITE);
            return write >= 0 && phase.ordinal() > ProgramPhase.CONTROLLED_WRITE.ordinal();
        }

        /** Builds the immutable witness; an incomplete run is refused, never reported as done. */
        public ProgramWitness build() {
            if (marks.size() != phases.size()) {
                List<ProgramPhase> missing = new ArrayList<>();
                for (int index = marks.size(); index < phases.size(); index++) {
                    missing.add(phases.get(index));
                }
                throw new IllegalStateException("the program run is incomplete, missing: " + missing);
            }
            return new ProgramWitness(program.kind(), marks, phases);
        }
    }
}
