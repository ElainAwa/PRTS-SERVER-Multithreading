/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.chunk;

import it.unimi.dsi.fastutil.longs.Long2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;

import java.util.ArrayList;
import java.util.Iterator;
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
 * generation is refused and evicted. The ledger is bounded: it keeps at most {@code cap} entries,
 * a settled (delivered or cancelled) entry leaves the ledger at once, an older holder generation is
 * evicted on advance, and a registration that cannot fit is refused and counted instead of dropping
 * a pending subscriber. It performs no load, no generation and no send.
 */
public final class ChunkSubscriberLedger {

    /** The default bound of the pending entries one ledger keeps. */
    public static final int DEFAULT_CAP = 4096;
    /** The default bound of the holder-generation records one ledger keeps. */
    public static final int DEFAULT_HOLDERS_CAP = 4096;

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

        /** Mix the packed position and generation so the pending map never treeifies. */
        @Override
        public int hashCode() {
            int hash = (int) (chunkPos ^ (chunkPos >>> 32));
            hash = hash * 0x9E3779B9;
            hash ^= (int) (holderGen ^ (holderGen >>> 32));
            hash = hash * 0x85EBCA6B;
            hash ^= subscriberId.hashCode();
            hash = hash * 0xC2B2AE35;
            return hash ^ targetStatus.hashCode();
        }
    }

    private final int cap;
    private final int holdersCap;
    private final Map<Key, Entry> entries = new LinkedHashMap<>();
    /** Bounded holder-generation LRU: a chunk that left the recorder's memory is evicted oldest. */
    private final Long2LongLinkedOpenHashMap holders;
    private long holdersEvicted;
    private int staleResults;
    private long deliveredCount;
    private long cancelledCount;
    private long evicted;
    private long refused;

    /** One ledger with the default bound. */
    public ChunkSubscriberLedger() {
        this(DEFAULT_CAP, DEFAULT_HOLDERS_CAP);
    }

    /** One ledger with an explicit pending bound; a non-positive bound refuses every entry. */
    public ChunkSubscriberLedger(int cap) {
        this(cap, DEFAULT_HOLDERS_CAP);
    }

    /** One ledger with explicit pending and holder-record bounds. */
    public ChunkSubscriberLedger(int cap, int holdersCap) {
        if (cap < 0 || holdersCap < 0) {
            throw new IllegalArgumentException("a ledger cap cannot be negative");
        }
        this.cap = cap;
        this.holdersCap = holdersCap;
        this.holders = new Long2LongLinkedOpenHashMap();
    }

    /**
     * Registers one subscriber's request. A settled entry leaves the ledger at once, an older holder
     * generation is refused, and a full ledger rejects the new registration (counting the refusal)
     * rather than dropping a pending subscriber.
     */
    public synchronized Entry register(ChunkRequestIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        long current = holders.get(identity.chunkPos());
        if (identity.holderGen() < current) {
            staleResults++;
            return new Entry(identity, State.CANCELLED);
        }
        touchHolder(identity.chunkPos(), identity.holderGen());
        Key key = key(identity);
        Entry existing = entries.get(key);
        if (existing != null) {
            return existing;
        }
        if (entries.size() >= cap) {
            evictSettled();
        }
        if (entries.size() >= cap) {
            refused++;
            return new Entry(identity, State.CANCELLED);
        }
        Entry entry = new Entry(identity, State.PENDING);
        entries.put(key, entry);
        return entry;
    }

    /**
     * Delivers every pending subscriber of one production completion whose target status is reached
     * by the achieved status; a stale holder generation delivers nothing. A delivered entry leaves
     * the ledger immediately, so the ledger size only counts pending subscribers.
     */
    public synchronized Completion complete(ChunkRequestIdentity completion, String achievedStatus) {
        Objects.requireNonNull(completion, "completion");
        Objects.requireNonNull(achievedStatus, "achievedStatus");
        long current = holders.get(completion.chunkPos());
        if (completion.holderGen() < current) {
            staleResults++;
            return new Completion(0, 1);
        }
        touchHolder(completion.chunkPos(), completion.holderGen());
        int delivered = 0;
        Iterator<Map.Entry<Key, Entry>> walk = entries.entrySet().iterator();
        while (walk.hasNext()) {
            Map.Entry<Key, Entry> held = walk.next();
            ChunkRequestIdentity identity = held.getValue().identity();
            if (!identity.sameProduction(completion)
                || !satisfies(achievedStatus, identity.targetStatus())) {
                continue;
            }
            walk.remove();
            deliveredCount++;
            delivered++;
        }
        return new Completion(delivered, 0);
    }

    /** Cancels only the named subscriber's pending requests; every other subscriber is untouched. */
    public synchronized int cancel(String subscriberId) {
        Objects.requireNonNull(subscriberId, "subscriberId");
        int cancelled = 0;
        Iterator<Map.Entry<Key, Entry>> walk = entries.entrySet().iterator();
        while (walk.hasNext()) {
            Entry entry = walk.next().getValue();
            if (entry.identity().subscriberId().equals(subscriberId)) {
                walk.remove();
                cancelledCount++;
                cancelled++;
            }
        }
        return cancelled;
    }

    /** Advances the holder generation of one chunk and evicts the entries of the older one. */
    public synchronized void advanceHolder(long chunkPos, long holderGen) {
        long current = holders.get(chunkPos);
        if (holderGen <= current) {
            return;
        }
        touchHolder(chunkPos, holderGen);
        Iterator<Map.Entry<Key, Entry>> walk = entries.entrySet().iterator();
        while (walk.hasNext()) {
            Entry entry = walk.next().getValue();
            if (entry.identity().chunkPos() == chunkPos
                && entry.identity().holderGen() < holderGen) {
                walk.remove();
                staleResults++;
                evicted++;
            }
        }
    }

    /** The state of one pending request, or null once it settled, was evicted or was never known. */
    public synchronized Entry state(ChunkRequestIdentity identity) {
        return entries.get(key(identity));
    }

    /** How many subscribers one state currently holds; settled counts are cumulative. */
    public synchronized int count(State state) {
        if (state == State.PENDING) {
            return entries.size();
        }
        return (int) (state == State.DELIVERED ? deliveredCount : cancelledCount);
    }

    /** The subscribers currently pending, in registration order. */
    public synchronized Set<String> pendingSubscribers() {
        Set<String> subscribers = new LinkedHashSet<>();
        for (Entry entry : entries.values()) {
            subscribers.add(entry.identity().subscriberId());
        }
        return Set.copyOf(subscribers);
    }

    /** The current number of pending entries. */
    public synchronized int size() {
        return entries.size();
    }

    public int cap() {
        return cap;
    }

    /** The entries evicted by a holder generation advance. */
    public synchronized long evicted() {
        return evicted;
    }

    /** The registrations refused because the ledger was full. */
    public synchronized long refused() {
        return refused;
    }

    /** Holder-generation records dropped by the bounded holder LRU. */
    public synchronized long holdersEvicted() {
        return holdersEvicted;
    }

    public int holdersCap() {
        return holdersCap;
    }

    /** The tracked chunk positions, for the lifecycle evidence. */
    public synchronized int holderCount() {
        return holders.size();
    }

    /** Results refused because their holder generation was older than the current one. */
    public synchronized int staleResults() {
        return staleResults;
    }

    /** Drops every registration and counter; test and evidence wiring only. */
    public synchronized void reset() {
        entries.clear();
        holders.clear();
        staleResults = 0;
        holdersEvicted = 0;
        deliveredCount = 0;
        cancelledCount = 0;
        evicted = 0;
        refused = 0;
    }

    private void touchHolder(long chunkPos, long holderGen) {
        if (!holders.containsKey(chunkPos) && holders.size() >= holdersCap) {
            LongIterator eldest = holders.keySet().iterator();
            if (eldest.hasNext()) {
                eldest.nextLong();
                eldest.remove();
                holdersEvicted++;
            }
        }
        holders.put(chunkPos, Math.max(holders.get(chunkPos), holderGen));
    }

    private void evictSettled() {
        // Nothing settled stays in the map, so this is only reachable when every entry is pending;
        // kept as the explicit hook that a future settled-cache would use.
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
