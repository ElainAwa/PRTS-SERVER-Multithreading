/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

/** One detached row and its frozen read-set; the clone is never the live entity. */
public final class SnapshotFork {

    public record EntityState(int entityId, long uuidMost, long uuidLeast, int tickCount,
                              double x, double y, double z, double vx, double vy, double vz) {
    }

    public record Commit(long entityId, double x, double y, double z, double vx, double vy,
                         double vz, long readSetFingerprint, long epoch, boolean committed) {
    }

    private final EntityState source;
    private final EntityState clone;
    private final FrozenReadSet readSet;
    private final long epoch;

    public SnapshotFork(EntityState source, FrozenReadSet readSet, long epoch) {
        this.source = source;
        this.clone = new EntityState(source.entityId(), source.uuidMost(), source.uuidLeast(),
            source.tickCount(), source.x(), source.y(), source.z(), source.vx(), source.vy(),
            source.vz());
        this.readSet = readSet;
        this.epoch = epoch;
    }

    public EntityState source() {
        return source;
    }

    /** Clone isolation: this is the only object a worker may mutate. */
    public EntityState clone() {
        return clone;
    }

    public FrozenReadSet readSet() {
        return readSet;
    }

    public long epoch() {
        return epoch;
    }

    /** The host commit: exactly one apply; fingerprint/epoch mismatch becomes fallback. */
    public Commit hostCommit(EntityState answer, long readSetFingerprint, long answerEpoch,
                             CommitSink sink) {
        if (answerEpoch != epoch || readSetFingerprint != readSet.fingerprint()) {
            return new Commit(answer.entityId(), source.x(), source.y(), source.z(), source.vx(),
                source.vy(), source.vz(), readSet.fingerprint(), epoch, false);
        }
        sink.applyOnce(answer);
        return new Commit(answer.entityId(), answer.x(), answer.y(), answer.z(), answer.vx(),
            answer.vy(), answer.vz(), readSet.fingerprint(), epoch, true);
    }

    @FunctionalInterface
    public interface CommitSink {
        void applyOnce(EntityState answer);
    }
}
