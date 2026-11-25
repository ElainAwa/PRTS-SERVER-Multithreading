/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.random;

import java.util.List;
import java.util.Objects;

/**
 * The archivable record of a keyed stream table at one publication revision.
 *
 * <p>Every stream state in this record carries the revision of the published prefix it was
 * captured from, so a state from one revision cannot be replayed over another. A persistence layer
 * may store the record beside the object it belongs to and hand it back for a bounded restore.
 * Nothing is installed into a running table until a caller explicitly restores the record, and
 * the record publishes no command, reading key or product configuration.
 *
 * @param publicationRevision the revision of the published prefix the states were captured at
 * @param entries the stream states with their bound revision, in declaration order
 */
public record StreamStateBinding(long publicationRevision, List<Entry> entries) {

    /** The absent revision of an archive written before revision binding; bounded restore refuses it. */
    public static final long UNBOUND_REVISION = Long.MIN_VALUE;

    /**
     * One stream state tied to the revision of the prefix it came from.
     *
     * @param state the versioned stream state
     * @param revision the publication revision that state belongs to
     */
    public record Entry(StreamState state, long revision) {
        public Entry {
            Objects.requireNonNull(state, "state");
        }
    }

    public StreamStateBinding {
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }

    /**
     * Captures every stream of a table at a publication revision.
     *
     * @param table the table to capture
     * @param publicationRevision the revision of the published prefix being captured
     * @return the archivable binding, with every state bound to that revision
     */
    public static StreamStateBinding capture(SharedStreamTable table, long publicationRevision) {
        Objects.requireNonNull(table, "table");
        if (publicationRevision == UNBOUND_REVISION) {
            throw new IllegalArgumentException("publication revision must be present");
        }
        List<Entry> captured = table.snapshot().stream()
            .map(state -> new Entry(state, publicationRevision)).toList();
        return new StreamStateBinding(publicationRevision, captured);
    }

    /**
     * Builds the shape an archive written before revision binding has, so a bounded restore refuses
     * it instead of guessing which revision its states belong to.
     *
     * @param states the stream states in declaration order
     * @return a binding whose revision is absent
     */
    public static StreamStateBinding unbound(List<StreamState> states) {
        Objects.requireNonNull(states, "states");
        return new StreamStateBinding(UNBOUND_REVISION,
            states.stream().map(state -> new Entry(state, UNBOUND_REVISION)).toList());
    }

    /** Whether a publication revision is present. */
    public boolean hasRevision() {
        return publicationRevision != UNBOUND_REVISION;
    }

    /** The stream states in declaration order, without their revision wrappers. */
    public List<StreamState> states() {
        return entries.stream().map(Entry::state).toList();
    }
}
