/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The read-only host parity reference: the host serial path produces one segment digest per
 * read-only phase, and the program witness is compared against that reference before any effect of
 * the program is trusted. Only the phases before the first controlled write are compared: this
 * slice claims read-only parity and nothing about write effects. The host segments are read from
 * the existing trace anchors; no new instrumentation is added here.
 */
public final class ProgramHostParity {

    /** One read-only host segment: its phase and the digest the host serial path produced. */
    public record HostSegment(ProgramPhase phase, long digest) {

        public HostSegment {
            Objects.requireNonNull(phase, "phase");
        }
    }

    /** What one comparison found. */
    public record Result(boolean matches, boolean readOnly, List<ProgramPhase> compared,
                         ProgramPhase firstMismatch, String detail) {

        public Result {
            compared = List.copyOf(compared);
            detail = detail == null ? "" : detail;
        }
    }

    private ProgramHostParity() {
    }

    /** One host segment of one phase that cannot write; a phase after a read-only successor is
     * still part of the read-only reference because no effect was declared before it. */
    public static HostSegment segment(ProgramPhase phase, long digest) {
        if (phase.writes()) {
            throw new IllegalArgumentException("the host parity reference is read-only: " + phase);
        }
        return new HostSegment(phase, digest);
    }

    /**
     * Compares the read-only prefix of one program witness with the host serial reference. The
     * compared phases are the witness phases up to and including the decision phase; a host segment
     * of a write phase is refused, and a missing, extra or reordered phase is a mismatch.
     */
    public static Result compare(ProgramWitness program, List<HostSegment> host) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(host, "host");
        List<ProgramPhase> compared = readOnlyPrefix(program);
        for (HostSegment segment : host) {
            if (segment.phase().writes()) {
                return new Result(false, true, compared, segment.phase(), "host-write-phase");
            }
        }
        if (host.size() != compared.size()) {
            ProgramPhase first = host.size() > compared.size() ? host.get(compared.size()).phase()
                : compared.get(host.size());
            return new Result(false, true, compared, first, "phase-count");
        }
        for (int index = 0; index < compared.size(); index++) {
            ProgramPhase phase = compared.get(index);
            HostSegment reference = host.get(index);
            if (reference.phase() != phase) {
                return new Result(false, true, compared, phase, "phase-order");
            }
            ProgramWitness.PhaseMark mark = program.mark(phase);
            if (mark == null || mark.outputDigest() != reference.digest()) {
                return new Result(false, true, compared, phase, "digest");
            }
        }
        return new Result(true, true, compared, null, "read-only-prefix");
    }

    /** The read-only prefix the comparison covers: the witness phases before the first phase that
     * may write, so a program that declares no write phase compares its whole run. */
    public static List<ProgramPhase> readOnlyPrefix(ProgramWitness program) {
        List<ProgramPhase> prefix = new ArrayList<>();
        for (ProgramWitness.PhaseMark mark : program.marks()) {
            if (mark.phase().writes()) {
                break;
            }
            prefix.add(mark.phase());
        }
        return List.copyOf(prefix);
    }
}
