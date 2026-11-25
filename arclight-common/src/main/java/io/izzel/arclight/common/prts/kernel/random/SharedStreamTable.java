/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.random;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The persistent table of keyed shared streams.
 *
 * <p>A stream is created once from its key and kept for the lifetime of the table; a second lookup
 * of the same key returns the same instance, never a re-derived or reseeded copy, so a tick cannot
 * silently reset a stream. The table keeps first-use order, which is the declaration order a
 * snapshot is written in. The table is not thread-safe: draws are taken in the program's declared
 * operation order, and the owner that drains that order is the only writer. A bounded restore also
 * records the publication revision of the snapshot it installed; ordinary lookups and draws never
 * consult it, and that single writer owns it together with the streams.
 */
public final class SharedStreamTable {

    private final Map<StreamKey, SharedStream> streams = new LinkedHashMap<>();
    private long boundRevision = StreamStateBinding.UNBOUND_REVISION;

    /**
     * Returns the stream of the given key, creating it on first use and never re-deriving it on
     * later lookups.
     *
     * @param key the key naming the stream
     * @return the stream of that key
     */
    public synchronized SharedStream stream(StreamKey key) {
        SharedStream existing = streams.get(key);
        if (existing != null) {
            return existing;
        }
        SharedStream created = new SharedStream(key);
        streams.put(key, created);
        return created;
    }

    /**
     * Returns the stream of the given key if it was already created.
     *
     * @param key the key naming the stream
     * @return the created stream or {@code null}
     */
    public SharedStream peek(StreamKey key) {
        return streams.get(key);
    }

    /** The number of streams created in this table. */
    public int size() {
        return streams.size();
    }

    /** The keys of the created streams in the order they were declared or first used. */
    public List<StreamKey> keys() {
        return List.copyOf(streams.keySet());
    }

    /**
     * Takes the versioned state of every stream in declaration order.
     *
     * @return one state per created stream, in declaration order
     */
    public List<StreamState> snapshot() {
        return streams.values().stream().map(SharedStream::snapshot).toList();
    }

    /**
     * Replaces this table's streams with the given snapshots; the next draws continue from the
     * recorded positions instead of reseeding, and the given order becomes the declaration order.
     *
     * @param states one state per stream, in declaration order
     */
    public void restoreState(List<StreamState> states) {
        Objects.requireNonNull(states, "states");
        streams.clear();
        for (StreamState state : states) {
            Objects.requireNonNull(state, "state");
            streams.put(state.key(), SharedStream.restore(state));
        }
    }

    /**
     * The publication revision of the bounded snapshot this table holds, or the absent revision
     * when no bounded snapshot has been installed. The table's single writer owns this value with
     * its streams; draws do not consult it.
     *
     * @return the bound revision, or {@link StreamStateBinding#UNBOUND_REVISION}
     */
    public long boundRevision() {
        return boundRevision;
    }

    /**
     * Restores a revision-bound snapshot, but only when its revision is present and is the one
     * this table already serves. A binding from another revision is refused before any stream is
     * replaced, so an older state can never move a newer revision backwards. On success the states
     * and their revision become this table's current state, and later bindings from a different
     * revision are refused as well.
     *
     * @param binding the archivable binding to restore
     * @throws IllegalStateException if the revision is absent, an entry disagrees with the
     *     binding's revision, or the binding belongs to another revision
     */
    public void restoreBounded(StreamStateBinding binding) {
        Objects.requireNonNull(binding, "binding");
        if (!binding.hasRevision()) {
            throw new IllegalStateException("stream-state-binding-revision-missing");
        }
        for (StreamStateBinding.Entry entry : binding.entries()) {
            if (entry.revision() != binding.publicationRevision()) {
                throw new IllegalStateException("stream-state-binding-entry-revision-mismatch");
            }
        }
        if (boundRevision != StreamStateBinding.UNBOUND_REVISION && boundRevision != binding.publicationRevision()) {
            throw new IllegalStateException("stream-state-binding-revision-mismatch: current=" + boundRevision
                + " incoming=" + binding.publicationRevision());
        }
        restoreState(binding.states());
        boundRevision = binding.publicationRevision();
    }

    /**
     * Rebuilds a table from snapshots; streams are restored exactly, including their gaussian cache
     * and fork count, and the given order becomes the declaration order of the restored table.
     *
     * @param states one state per stream, in declaration order
     * @return the restored table
     */
    public static SharedStreamTable restore(List<StreamState> states) {
        Objects.requireNonNull(states, "states");
        SharedStreamTable table = new SharedStreamTable();
        for (StreamState state : states) {
            Objects.requireNonNull(state, "state");
            table.streams.put(state.key(), SharedStream.restore(state));
        }
        return table;
    }
}
