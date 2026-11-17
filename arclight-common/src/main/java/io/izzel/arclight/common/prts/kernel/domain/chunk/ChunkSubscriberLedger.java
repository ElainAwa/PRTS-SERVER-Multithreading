/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The subscriber ledger of the chunk requests: one production task may serve several subscribers
 * with different target statuses and deadlines, every subscriber is delivered on its own, a cancel
 * removes only the named subscriber and never kills the others, and a result of an older holder
 * generation is refused instead of being delivered. The ledger is a contract layer; it performs no
 * load, no generation and no send.
 */
public final class ChunkSubscriberLedger {

    /** Where one subscriber of one request stands. */
    public enum State {
        PENDING,
        DELIVERED,
        CANCELLED
    }

    /** One subscriber's request and its state. */
    public record Entry(ChunkRequestIdentity identity, State state) {

        public Entry {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(state, "state");
        }
    }

    /** What one completion delivered and what it had to refuse. */
    public record Completion(int delivered, int refusedStale) {
    }

    private record Key(long chunkPos, long holderGen, String subscriberId, String targetStatus) {
    }

    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    private final Map<Long, Long> holders = new LinkedHashMap<>();
    private int staleResults;

    /** Registers one subscriber's request; a stale holder generation is refused. */
    public synchronized Entry register(ChunkRequestIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        long current = holders.getOrDefault(identity.chunkPos(), 0L);
        if (identity.holderGen() < current) {
            staleResults++;
            return new Entry(identity, State.CANCELLED);
        }
        holders.merge(identity.chunkPos(), identity.holderGen(), Math::max);
        Key key = key(identity);
        Entry existing = entries.get(key);
        if (existing != null) {
            return existing;
        }
        Entry entry = new Entry(identity, State.PENDING);
        entries.put(key, entry);
        return entry;
    }

    /**
     * Delivers every pending subscriber of one production completion whose target status is reached
     * by the achieved status; a stale holder generation delivers nothing. Each subscriber is counted
     * and delivered on its own, so a later different status cannot swallow it.
     */
    public synchronized Completion complete(ChunkRequestIdentity completion, String achievedStatus) {
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(achievedStatus, "achievedStatus");
        long current = holders.getOrDefault(completion.chunkPos(), 0L);
        if (completion.holderGen() < current) {
            staleResults++;
            return new Completion(0, 1);
        }
        holders.merge(completion.chunkPos(), completion.holderGen(), Math::max);
        int delivered = 0;
        List<Key> keys = new ArrayList<>(entries.keySet());
        for (Key key : keys) {
            Entry entry = entries.get(key);
            if (entry.state() != State.PENDING) {
                continue;
            }
            ChunkRequestIdentity identity = entry.identity();
            if (!identity.sameProduction(completion)
                || !satisfies(achievedStatus, identity.targetStatus())) {
                continue;
            }
            entries.put(key, new Entry(identity, State.DELIVERED));
            delivered++;
        }
        return new Completion(delivered, 0);
    }

    /** Cancels only the named subscriber's pending requests; every other subscriber is untouched. */
    public synchronized int cancel(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId");
        int cancelled = 0;
        for (Map.Entry<Key, Entry> held : entries.entrySet()) {
            Entry entry = held.getValue();
            if (entry.state() == State.PENDING
                && entry.identity().subscriberId().equals(subscriberId)) {
                entries.put(held.getKey(), new Entry(entry.identity(), State.CANCELLED));
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Advances the holder generation of one chunk; an older generation becomes stale. */
    public synchronized void advanceHolder(long chunkPos, long holderGen) {
        holders.merge(chunkPos, holderGen, Math::max);
    }

    /** The state of one subscriber's request, or null when it never registered. */
    public synchronized Entry state(ChunkRequestIdentity identity) {
        Entry entry = entries.get(key(identity));
        return entry == null ? null : new Entry(entry.identity(), visibleState(entry));
    }

    /** How many entries one state holds. */
    public synchronized int count(State state) {
        int count = 0;
        for (Entry entry : entries.values()) {
            if (visibleState(entry) == state) {
                count++;
            }
        }
        return count;
    }

    /** The subscribers currently pending, in registration order. */
    public synchronized Set<String> pendingSubscribers() {
        Set<String> subscribers = new LinkedHashSet<>();
        for (Entry entry : entries.values()) {
            if (visibleState(entry) == State.PENDING) {
                subscribers.add(entry.identity().subscriberId());
            }
        }
        return Set.copyOf(subscribers);
    }

    /** Results refused because their holder generation was older than the current one. */
    public synchronized int staleResults() {
        return staleResults;
    }

    /** The entry state after a holder advance: an older pending entry is stale and not delivered. */
    private State visibleState(Entry entry) {
        if (entry.state() != State.PENDING) {
            return entry.state();
        }
        long current = holders.getOrDefault(entry.identity().chunkPos(), 0L);
        return entry.identity().holderGen() < current ? State.CANCELLED : State.PENDING;
    }

    private static boolean satisfies(String achieved, String needed) {
        int achievedRank = rank(achieved);
        int neededRank = rank(needed);
        if (achievedRank < 0 || neededRank < 0) {
            return achieved.equals(needed);
        }
        return achievedRank >= neededRank;
    }

    private static int rank(String status) {
        return switch (status) {
            case "full" -> 100;
            case "features" -> 90;
            case "light" -> 80;
            case "spawn" -> 70;
            case "surface" -> 60;
            case "carvers" -> 50;
            case "structure_starts" -> 40;
            case "structure_references" -> 35;
            case "biomes" -> 30;
            case "noise" -> 20;
            case "empty" -> 0;
            default -> -1;
        };
    }

    private static Key key(ChunkRequestIdentity identity) {
        return new Key(identity.chunkPos(), identity.holderGen(), identity.subscriberId(),
            identity.targetStatus());
    }
}
