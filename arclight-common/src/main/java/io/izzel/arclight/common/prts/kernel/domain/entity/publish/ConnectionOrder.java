/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.publish;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The per-connection tracker order: a connection never sends an older revision after a newer one,
 * keeps its own bounded pending window, and waits for nothing. The order is per connection, so two
 * connections never block each other; the encoding and the network stay with the host.
 */
public final class ConnectionOrder {

    /** What one send offer did. */
    public enum Decision {
        SEND,
        SUPPRESSED_OLD_REVISION,
        BACKPRESSURE
    }

    /** What one connection answered: the decision and the revision it last sent. */
    public record Send(Decision decision, long lastSentRevision, int pending) {

        public Send {
            Objects.requireNonNull(decision, "decision");
        }

        public boolean send() {
            return decision == Decision.SEND;
        }
    }

    private record State(long lastSent, int pending) {
    }

    private final Map<String, State> connections = new ConcurrentHashMap<>();
    private final int bound;

    /** Creates one order with the per-connection pending bound; a zero bound refuses every send. */
    public ConnectionOrder(int bound) {
        if (bound < 0) {
            throw new IllegalArgumentException("a pending bound cannot be negative");
        }
        this.bound = bound;
    }

    /** Offers one object revision to one connection; older revisions are suppressed, never queued. */
    public Send offer(String connectionId, long revision) {
        Objects.requireNonNull(connectionId, "connectionId");
        if (revision < 1L) {
            throw new IllegalArgumentException("a sent revision starts at one");
        }
        State state = connections.getOrDefault(connectionId, new State(0L, 0));
        if (revision <= state.lastSent()) {
            return new Send(Decision.SUPPRESSED_OLD_REVISION, state.lastSent(), state.pending());
        }
        if (state.pending() >= bound) {
            return new Send(Decision.BACKPRESSURE, state.lastSent(), state.pending());
        }
        connections.put(connectionId, new State(revision, state.pending() + 1));
        return new Send(Decision.SEND, revision, state.pending() + 1);
    }

    /** Acknowledges one sent revision, freeing one pending slot of that connection only. */
    public Send ack(String connectionId, long revision) {
        Objects.requireNonNull(connectionId, "connectionId");
        State state = connections.getOrDefault(connectionId, new State(0L, 0));
        int pending = Math.max(0, state.pending() - 1);
        connections.put(connectionId, new State(state.lastSent(), pending));
        return new Send(Decision.SEND, state.lastSent(), pending);
    }

    public int pendingOf(String connectionId) {
        State state = connections.get(connectionId);
        return state == null ? 0 : state.pending();
    }

    public long lastSentOf(String connectionId) {
        State state = connections.get(connectionId);
        return state == null ? 0L : state.lastSent();
    }
}
