/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The read wave of one compute-only program: it owns the permits of the read phase, keeps the
 * staged outputs of each task out of every other task's sight, and records the committed revision
 * of the own state so a legal successor reads exactly the state that was applied. The wave closes
 * in the close of the read phase; a read after that is refused, not served from a stale copy.
 */
public final class ReadWave {

    /** The outputs one task staged in its own data flow; another task never reads them. */
    private final Map<String, Long> staged = new LinkedHashMap<>();
    private final Map<EntityRowIdentity, Long> applied = new LinkedHashMap<>();
    private final EntityUpdateProgram program;
    private final ReadScope scope;
    private final long tick;
    private final long expiresAt;
    private boolean closed;

    private ReadWave(EntityUpdateProgram program, long waveId, long tick, long expiresAt,
                     Set<ReadPermit.FieldGroup> declaredGroups) {
        this.program = program;
        this.scope = new ReadScope(waveId, declaredGroups);
        this.tick = tick;
        this.expiresAt = expiresAt;
    }

    /** Opens the read phase of one compute-only program; a program that writes has no such wave. */
    public static ReadWave open(EntityUpdateProgram program, long waveId, long tick,
                                long expiresAt, Set<ReadPermit.FieldGroup> declaredGroups) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(declaredGroups, "declaredGroups");
        if (program.writes()) {
            throw new IllegalArgumentException(
                "this slice reads only; a writing program has no compute-only read wave");
        }
        if (!program.phaseOrder().contains(ProgramPhase.READ_COMPUTE)) {
            throw new IllegalArgumentException("the program declares no read phase");
        }
        if (!program.phaseOrder().contains(ProgramPhase.DECISION)) {
            throw new IllegalArgumentException("a read wave ends at the decision phase");
        }
        if (declaredGroups.isEmpty()) {
            throw new IllegalArgumentException("a read wave declares at least one field group");
        }
        return new ReadWave(program, waveId, tick, expiresAt, declaredGroups);
    }

    public long waveId() {
        return scope.waveId();
    }

    public EntityUpdateProgram program() {
        return program;
    }

    /** Issues one permit of this wave under one row identity. */
    public ReadPermit permit(EntityRowIdentity identity, long objectGeneration,
                             long expectedRevision, Set<ReadPermit.FieldGroup> groups) {
        return scope.issue(new ReadPermit(identity, objectGeneration, expectedRevision, groups,
            scope.waveId(), tick, expiresAt));
    }

    /** Reads one row under its permit; the permit is checked before the value is used. */
    public ReadScope.Read read(ReadPermit permit, LiveRowSnapshot live) {
        return scope.read(permit, live, tick);
    }

    /** Records the revision one controlled write applied to the own state. */
    public void noteApplied(EntityRowIdentity identity, long revision) {
        Objects.requireNonNull(identity, "identity");
        applied.put(identity, revision);
    }

    /** The committed revision of the own state one successor may read, or zero when none. */
    public long appliedRevision(EntityRowIdentity identity) {
        Long revision = applied.get(identity);
        return revision == null ? 0L : revision;
    }

    /** Stages one task's output in its own data flow; it is visible to no other task. */
    public void stage(String owner, long digest) {
        Objects.requireNonNull(owner, "owner");
        if (owner.isEmpty()) {
            throw new IllegalArgumentException("a staged output needs its task identity");
        }
        staged.put(owner, digest);
    }

    /** The output one task staged, or null when it staged none. */
    public Long staged(String owner) {
        return staged.get(owner);
    }

    /** Declares that a writer holds one row; no permit may be issued or read until it ends. */
    public void beginWrite(EntityRowIdentity identity) {
        if (!closed) {
            scope.beginWrite(identity);
        }
    }

    /** Ends a writer's hold of one row. */
    public void endWrite(EntityRowIdentity identity) {
        scope.endWrite(identity);
    }

    /** Records a foreign write of one row; every later read of the row is refused. */
    public void noteForeignWrite(EntityRowIdentity identity) {
        scope.noteForeignWrite(identity);
    }

    /** Closes the read phase and returns its witness. */
    public ReadScope.Result close() {
        closed = true;
        return scope.close();
    }

    public boolean closed() {
        return closed;
    }
}
