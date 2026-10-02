/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The two locks that serialize the optional physics mod: its physics thread and the server thread
 * reach the same non-reentrant native library and cache tables, and the counters record waits.
 */
package io.izzel.arclight.common.prts.support;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;

import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiFunction;

public final class PrtsSableLocks {

    private PrtsSableLocks() {
    }

    /** Serializes the native entry points of the physics mod. */
    public static final class NativeCalls {

        private static final ReentrantLock LOCK = new ReentrantLock();

        public static boolean enabled() {
            return PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, "serialize-sable-native-calls");
        }

        public static void lock() {
            if (!LOCK.tryLock()) {
                PrtsModSupportStats.count("sable-native-calls-contended");
                LOCK.lock();
            }
        }

        public static void unlock() {
            LOCK.unlock();
        }

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
