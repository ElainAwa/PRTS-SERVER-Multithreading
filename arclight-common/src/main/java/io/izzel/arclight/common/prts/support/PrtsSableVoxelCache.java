/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

/**
 * Serializes the memoized block lookups of the optional physics mod.
 *
 * <p>That mod answers "is this voxel solid" and "is this voxel a full block" through two caches
 * that a memoizing function fills on a miss, and both its physics thread and the server thread call
 * them. Two threads that miss at the same time run the mapping function on the same table, and the
 * second resize leaves the table inconsistent: reading it then throws an index error out of the
 * cache library, on whichever thread is unlucky. The lookup is therefore taken under one lock.</p>
 *
 * <p>The lock covers the lookup alone, never a caller's own work, and the fast path uses
 * {@code tryLock} so an uncontended lookup costs one compare-and-set. The counter is the number an
 * operator wants here: it counts the lookups that would otherwise have raced.</p>
 */
public final class PrtsSableVoxelCache {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private PrtsSableVoxelCache() {
    }

    /**
     * Runs a memoized lookup under the lock of the cache it belongs to.
     *
     * @param lookup memoizing function of the mod
     * @param getter first argument the mod passes to that function
     * @param state  second argument the mod passes to that function
     * @return the value the lookup answers
     */
    public static Object lookup(BiFunction<Object, Object, Object> lookup, Object getter, Object state) {
        if (!LOCK.tryLock()) {
            PrtsModSupportStats.count("sable-voxel-lookups-contended");
            LOCK.lock();
        }
        try {
            return lookup.apply(getter, state);
        } finally {
            LOCK.unlock();
        }
    }
}
