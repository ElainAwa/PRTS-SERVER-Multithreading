/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityRowIdentity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The state of one read wave: the permits it issued, the live rows it read, and the writer
 * exclusion that keeps a reader out of a write. A reader is never sandwiched into a write: a writer
 * may not begin while any permit of the wave is open, a permit issued while a writer holds the row
 * is refused, and a foreign write of a row during the wave invalidates that row's permits. The
 * scope is owned by the thread that opened it; closing it refuses every later read.
 */
public final class ReadScope {

    /** Why one read was refused; only {@link #ALLOWED} reads a row. */
    public enum Verdict {
        ALLOWED,
        REFUSED_WAVE_CLOSED,
        REFUSED_EXPIRED,
        REFUSED_IDENTITY,
        REFUSED_GENERATION,
        REFUSED_VERSION,
        REFUSED_WRITER,
        REFUSED_FOREIGN_WRITE,
        REFUSED_GROUP_NOT_DECLARED
    }

    /** What one read saw: the verdict and the revision it read at, zero unless allowed. */
    public record Read(Verdict verdict, long revision) {

        public Read {
            Objects.requireNonNull(verdict, "verdict");
        }

        public boolean allowed() {
            return verdict == Verdict.ALLOWED;
        }
    }

    /** What one closed wave witnessed: the permits it issued and the reads it allowed. */
    public record Result(long waveId, int issued, int allowed, int refused, int foreignWrites,
                         long lastRevision) {
    }

    private final long waveId;
    private final Set<ReadPermit.FieldGroup> declaredGroups;
    private final List<ReadPermit> permits = new ArrayList<>();
    private final Map<ReadPermit, Long> readRevisions = new LinkedHashMap<>();
    private final Set<EntityObjectKey> foreignWrites = new LinkedHashSet<>();
    private final Set<EntityObjectKey> writerRows = new LinkedHashSet<>();
    private boolean closed;
    private boolean readPhaseClosed;
    private boolean writeStarted;
    private Result settled;
    private int allowed;
    private int refused;
    private long lastRevision;

    private record EntityObjectKey(String worldId, long worldEpoch, long uuidHigh, long uuidLow) {
    }

    ReadScope(long waveId, Set<ReadPermit.FieldGroup> declaredGroups) {
        this.waveId = waveId;
        this.declaredGroups = Set.copyOf(declaredGroups);
    }

    public long waveId() {
        return waveId;
    }

    /** Issues one permit for the declared groups of this wave; a group outside them is refused. */
    public ReadPermit issue(ReadPermit permit) {
        Objects.requireNonNull(permit, "permit");
        if (closed || writeStarted) {
            throw new IllegalStateException("the read wave cannot issue a permit any more");
        }
        for (ReadPermit.FieldGroup group : permit.groups()) {
            if (!declaredGroups.contains(group)) {
                throw new IllegalArgumentException("the wave declares no such field group: " + group);
            }
        }
        if (writerRows.contains(key(permit.identity()))) {
            throw new IllegalStateException("the row is being written; a reader is not admitted");
        }
        permits.add(permit);
        return permit;
    }

    /** Reads one row under its permit; the permit is checked before the value is read. */
    public Read read(ReadPermit permit, LiveRowSnapshot live, long tick) {
        Objects.requireNonNull(permit, "permit");
        if (closed) {
            refused++;
            return new Read(Verdict.REFUSED_WAVE_CLOSED, 0);
        }
        if (!permit.validAt(tick)) {
            refused++;
            return new Read(Verdict.REFUSED_EXPIRED, 0);
        }
        if (live == null) {
            refused++;
            return new Read(Verdict.REFUSED_IDENTITY, 0);
        }
        if (!live.identity().sameObject(permit.identity())) {
            refused++;
            return new Read(Verdict.REFUSED_IDENTITY, 0);
        }
        if (live.objectGeneration() != permit.objectGeneration()) {
            refused++;
            return new Read(Verdict.REFUSED_GENERATION, 0);
        }
        EntityObjectKey key = key(permit.identity());
        if (writerRows.contains(key) || foreignWrites.contains(key)) {
            refused++;
            return new Read(foreignWrites.contains(key) ? Verdict.REFUSED_FOREIGN_WRITE
                : Verdict.REFUSED_WRITER, 0);
        }
        if (permit.expectedRevision() > 0L && live.revision() != permit.expectedRevision()) {
            refused++;
            return new Read(Verdict.REFUSED_VERSION, 0);
        }
        for (ReadPermit.FieldGroup group : permit.groups()) {
            if (!live.availableGroups().contains(group)) {
                refused++;
                return new Read(Verdict.REFUSED_GROUP_NOT_DECLARED, 0);
            }
        }
        allowed++;
        lastRevision = live.revision();
        readRevisions.put(permit, live.revision());
        return new Read(Verdict.ALLOWED, live.revision());
    }

    /** Declares that a writer holds the row: no permit may be issued or read until it ends. */
    public void beginWrite(EntityRowIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        if (!permits.isEmpty()) {
            throw new IllegalStateException("a writer cannot begin while read permits are open");
        }
        writerRows.add(key(identity));
        writeStarted = true;
    }

    /** Whether the read phase already settled its permits and handed the rights to the write wave. */
    public boolean readPhaseClosed() {
        return readPhaseClosed;
    }

    /** Ends the writer's hold of one row. */
    public void endWrite(EntityRowIdentity identity) {
        writerRows.remove(key(identity));
    }

    /** Records a foreign write of one row; every later read of the row is refused. */
    public void noteForeignWrite(EntityRowIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        foreignWrites.add(key(identity));
    }

    /** Closes the wave; a later read is refused instead of being served from stale state. */
    public Result close() {
        return closeReadPhase();
    }

    /**
     * Closes the read phase: every active permit is settled and retires, so the read phase and the
     * write wave hand over atomically - a writer may begin after this call and no stale permit can
     * read once the phase is closed. A second call returns the same witness.
     */
    public Result closeReadPhase() {
        if (settled != null) {
            return settled;
        }
        settled = new Result(waveId, permits.size(), allowed, refused, foreignWrites.size(),
            lastRevision);
        permits.clear();
        readRevisions.clear();
        readPhaseClosed = true;
        closed = true;
        return settled;
    }

    public boolean closed() {
        return closed;
    }

    /** The revision one permit read at, or zero when it never read. */
    public long revisionOf(ReadPermit permit) {
        Long revision = readRevisions.get(permit);
        return revision == null ? 0L : revision;
    }

    private static EntityObjectKey key(EntityRowIdentity identity) {
        return new EntityObjectKey(identity.worldId(), identity.worldEpoch(), identity.uuidHigh(),
            identity.uuidLow());
    }
}
