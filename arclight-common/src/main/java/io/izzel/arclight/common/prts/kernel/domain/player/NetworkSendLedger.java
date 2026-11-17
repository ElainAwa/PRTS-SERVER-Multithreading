/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.player;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** The network send completion ledger: a message is put into QUEUED, then ENCODED, FLUSHED and
 * ACKED in that strict order; only ACKED counts as complete, a cancelled or failed send never
 * publishes, a duplicate advance publishes nothing twice, and a new session generation invalidates
 * every queued message of the old session instead of writing it out. */
public final class NetworkSendLedger {

    /** The four completion layers of one outbound message, in their strict order. */
    public enum Layer {
        QUEUED,
        ENCODED,
        FLUSHED,
        ACKED
    }

    /** One message identity: its connection session, object and published revision. */
    public record MessageKey(String connectionId, long sessionGeneration, long objectIdentity,
                             long publishedRevision) {

        public MessageKey {
            Objects.requireNonNull(connectionId, "connectionId");
            if (connectionId.isEmpty() || sessionGeneration < 0L || publishedRevision < 0L) {
                throw new IllegalArgumentException("a message names its connection and revisions");
            }
        }
    }

    /** What one layer advance did. */
    public record Advance(boolean changed, Layer layer, String reason) {
    }

    private final Map<MessageKey, Layer> messages = new LinkedHashMap<>();
    private long completed;
    private long cancelled;
    private long invalidated;
    private long refused;

    /** Queues one message; an already queued message is idempotent. */
    public synchronized Advance queue(MessageKey key) {
        Objects.requireNonNull(key, "key");
        if (messages.containsKey(key)) {
            return new Advance(false, messages.get(key), "already-queued");
        }
        messages.put(key, Layer.QUEUED);
        return new Advance(true, Layer.QUEUED, "queued");
    }

    /** Advances to the next layer only; a jump is refused. */
    public synchronized Advance advance(MessageKey key, Layer layer) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(layer, "layer");
        Layer current = messages.get(key);
        if (current == null) {
            refused++;
            return new Advance(false, null, "not-queued");
        }
        if (layer.ordinal() != current.ordinal() + 1) {
            refused++;
            return new Advance(false, current, "not-next-layer");
        }
        messages.put(key, layer);
        if (layer == Layer.ACKED) {
            completed++;
        }
        return new Advance(true, layer, "advanced");
    }

    /** Cancels one message; a cancelled message never completes. */
    public synchronized boolean cancel(MessageKey key) {
        if (messages.remove(key) != null) {
            cancelled++;
            return true;
        }
        return false;
    }

    /** Invalidates every queued message of an older session generation of one connection. */
    public synchronized int invalidateSession(String connectionId, long newSessionGeneration) {
        int dropped = 0;
        java.util.Iterator<Map.Entry<MessageKey, Layer>> walk = messages.entrySet().iterator();
        while (walk.hasNext()) {
            MessageKey key = walk.next().getKey();
            if (key.connectionId().equals(connectionId)
                && key.sessionGeneration() < newSessionGeneration) {
                walk.remove();
                dropped++;
                invalidated++;
            }
        }
        return dropped;
    }

    /** The layer of one message, or null when it was completed/cancelled upstream. */
    public synchronized Layer layer(MessageKey key) {
        return messages.get(key);
    }

    public synchronized long completed() {
        return completed;
    }

    public synchronized long cancelled() {
        return cancelled;
    }

    public synchronized long invalidated() {
        return invalidated;
    }

    public synchronized long refused() {
        return refused;
    }
}
