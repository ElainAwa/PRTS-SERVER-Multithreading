/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.publish;

import io.izzel.arclight.common.prts.kernel.domain.entity.migration.CompositeWorldIdentity;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The committed publish store: one object publishes its state only after its owner effect
 * committed, the revision only moves forward, and a session generation invalidates every reader of
 * an older session. A half-written or uncommitted state is never published, so a tracker that reads
 * the store sees the last committed state instead of a partial one. The store keeps no world object
 * and waits for nothing.
 */
public final class PublishStore {

    /** Why one tracker read had no state to send. */
    public enum ReadVerdict {
        COMMITTED,
        NOT_PUBLISHED,
        SESSION_STALE
    }

    /** What one tracker read found: a committed snapshot or an honest verdict. */
    public record Read(ReadVerdict verdict, PublishedSnapshot snapshot) {

        public Read {
            Objects.requireNonNull(verdict, "verdict");
            if ((verdict == ReadVerdict.COMMITTED) != (snapshot != null)) {
                throw new IllegalArgumentException("only a committed read carries a snapshot");
            }
        }

        public boolean committed() {
            return verdict == ReadVerdict.COMMITTED;
        }
    }

    private final ConcurrentHashMap<CompositeWorldIdentity, PublishedSnapshot> published =
        new ConcurrentHashMap<>();
    private final ConcurrentHashMap<CompositeWorldIdentity, Long> generations =
        new ConcurrentHashMap<>();
    private final AtomicLong runs = new AtomicLong();
    private final AtomicLong revisions = new AtomicLong();

    /** Publishes one committed state; the revision is minted here and only moves forward. */
    public PublishedSnapshot publishAfterEffect(CompositeWorldIdentity identity,
                                                long publishedTick, double x, double y, double z,
                                                double yaw, double pitch, double velX,
                                                double velY, double velZ) {
        Objects.requireNonNull(identity, "identity");
        long generation = generations.computeIfAbsent(identity, key -> 0L);
        long revision = revisions.incrementAndGet();
        PublishedSnapshot snapshot = new PublishedSnapshot(revision, publishedTick, generation, x,
            y, z, yaw, pitch, velX, velY, velZ);
        published.put(identity, snapshot);
        runs.incrementAndGet();
        return snapshot;
    }

    /** The last committed snapshot of one object, without any fallback to a live row. */
    public Read readCommitted(CompositeWorldIdentity identity, long readerGeneration) {
        Objects.requireNonNull(identity, "identity");
        long generation = generations.getOrDefault(identity, 0L);
        if (readerGeneration != generation) {
            return new Read(ReadVerdict.SESSION_STALE, null);
        }
        PublishedSnapshot snapshot = published.get(identity);
        return snapshot == null ? new Read(ReadVerdict.NOT_PUBLISHED, null)
            : new Read(ReadVerdict.COMMITTED, snapshot);
    }

    /** Invalidates every reader of an older session; the next publication carries the new one. */
    public long invalidateSession(CompositeWorldIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return generations.compute(identity, (key, generation) ->
            generation == null ? 1L : generation + 1L);
    }

    /** The generation a reader must present to read one object. */
    public long generationOf(CompositeWorldIdentity identity) {
        return generations.getOrDefault(identity, 0L);
    }

    /** The revision of the last committed state, or zero when nothing was published. */
    public long revisionOf(CompositeWorldIdentity identity) {
        PublishedSnapshot snapshot = published.get(identity);
        return snapshot == null ? 0L : snapshot.revision();
    }

    public int publishedObjects() {
        return published.size();
    }

    public long publishedRuns() {
        return runs.get();
    }

    /** The whole store as a plain map, for an evidence dump; it holds committed snapshots only. */
    public Map<CompositeWorldIdentity, PublishedSnapshot> snapshotMap() {
        return Map.copyOf(published);
    }
}
