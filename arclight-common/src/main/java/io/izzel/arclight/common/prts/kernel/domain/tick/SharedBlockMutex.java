/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.tick;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The one shared mutex of one block: an entity, a block entity and a redstone writer acquire the
 * same key before they touch the block or its inventory, a multi-block write acquires all keys or
 * none, and a refused acquirer is counted instead of writing beside the current holder. */
public final class SharedBlockMutex {

    /** The domains that may write one shared block. */
    public enum Owner {
        ENTITY,
        BLOCK_ENTITY,
        REDSTONE,
        RANDOM_TICK
    }

    /** One shared block key: the world incarnation and the block position. */
    public record Key(long worldIncarnation, long blockPos) {

        public Key {
            if (worldIncarnation < 0L) {
                throw new IllegalArgumentException("a block key cannot name a negative incarnation");
            }
        }
    }

    /** The answer of one acquisition. */
    public record Acquired(boolean granted, String reason) {
    }

    private final Map<Key, Owner> holders = new LinkedHashMap<>();
    private long refused;
    private long acquired;
    private long released;

    /** Acquires one block for one owner; a held block hands out no second right. */
    public synchronized Acquired acquire(Key key, Owner owner) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(owner, "owner");
        Owner current = holders.get(key);
        if (current != null) {
            refused++;
            return new Acquired(false, current == owner ? "already-held" : "held-by-" + current);
        }
        holders.put(key, owner);
        acquired++;
        return new Acquired(true, "acquired");
    }

    /** Acquires every key or none; a partial conflict leaves no key held. */
    public synchronized Acquired acquireAll(Set<Key> keys, Owner owner) {
        Objects.requireNonNull(keys, "keys");
        Objects.requireNonNull(owner, "owner");
        for (Key key : keys) {
            Owner current = holders.get(key);
            if (current != null) {
                refused++;
                return new Acquired(false, current == owner ? "already-held" : "held-by-" + current);
            }
        }
        for (Key key : keys) {
            holders.put(key, owner);
        }
        acquired += keys.size();
        return new Acquired(true, "acquired-all");
    }

    /** Releases one key; only the recorded owner may release it. */
    public synchronized boolean release(Key key, Owner owner) {
        if (holders.get(key) != owner) {
            return false;
        }
        holders.remove(key);
        released++;
        return true;
    }

    /** Releases every key of one owner that it still holds. */
    public synchronized int releaseAll(Owner owner) {
        int dropped = 0;
        java.util.Iterator<Map.Entry<Key, Owner>> walk = holders.entrySet().iterator();
        while (walk.hasNext()) {
            if (walk.next().getValue() == owner) {
                walk.remove();
                dropped++;
                released++;
            }
        }
        return dropped;
    }

    /** The current owner of one block, or null when it is free. */
    public synchronized Owner owner(Key key) {
        return holders.get(key);
    }

    public synchronized long refused() {
        return refused;
    }

    public synchronized long acquired() {
        return acquired;
    }

    public synchronized long released() {
        return released;
    }
}
