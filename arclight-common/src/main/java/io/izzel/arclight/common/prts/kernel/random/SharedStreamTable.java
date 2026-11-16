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
 * operation order, and the owner that drains that order is the only writer.
 */
public final class SharedStreamTable {

    private final Map<StreamKey, SharedStream> streams = new LinkedHashMap<>();

    /**
     * Returns the stream of the given key, creating it on first use and never re-deriving it on
     * later lookups.
     *
     * @param key the key naming the stream
     * @return the stream of that key
     */
    public SharedStream stream(StreamKey key) {
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
