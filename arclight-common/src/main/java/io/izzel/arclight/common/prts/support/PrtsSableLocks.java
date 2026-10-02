/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The two locks that serialize the optional physics mod's native calls and voxel caches: its physics
 * thread and the server thread reach the same non-reentrant native library and the same cache tables,
 * so every entry this layer knows about is taken under one lock. Each lock covers the call alone, and
 * the fast path uses tryLock; the counters record the calls that had to wait.
 */
package io.izzel.arclight.common.prts.support;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

/** The locks that serialize the optional physics module's native calls and voxel caches. */
public final class PrtsSableLocks {

    private PrtsSableLocks() {
    }

    /** Serializes the native entry points of the physics mod. */
    public static final class NativeCalls {

        private static final ReentrantLock LOCK = new ReentrantLock();

        /** @return the value of the switch in effect */
        public static boolean enabled() {
            return PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, "serialize-sable-native-calls");
        }

        /** Takes the lock, recording a call that had to wait for another thread. */
        public static void lock() {
            if (!LOCK.tryLock()) {
                PrtsModSupportStats.count("sable-native-calls-contended");
                LOCK.lock();
            }
        }

        /** Releases the lock. */
        public static void unlock() {
            LOCK.unlock();
        }

        /** Records one native call that was serialized by the lock. */
        public static void noteSerializedCall() {
            PrtsModSupportStats.count("sable-native-calls-serialized");
        }
    }

    /** Serializes the memoized block lookups of the physics mod. */
    public static final class VoxelCache {

        private static final ReentrantLock LOCK = new ReentrantLock();

        /** Runs a memoized lookup under the lock of the cache it belongs to. */
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
}
