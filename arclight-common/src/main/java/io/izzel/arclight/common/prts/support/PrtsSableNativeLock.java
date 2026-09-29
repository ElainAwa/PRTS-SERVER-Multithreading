/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;

import java.util.concurrent.locks.ReentrantLock;

/**
 * One lock for the native entry points of the optional physics mod.
 *
 * <p>That mod steps its physics simulation on its own thread while the server thread adds, reads
 * and removes ropes, and both paths end in the same native library. The library is not reentrant
 * across those paths, so the two threads can hold part of it each and wait for the other one
 * forever. Every entry into it that this layer knows about is therefore taken under one lock.</p>
 *
 * <p>The lock is only held across the native call, never across the Java side of the caller: that
 * keeps the critical section as short as the problem is, and an exception on the Java side cannot
 * strand the lock for the next caller. {@code tryLock} keeps the uncontended case at one
 * compare-and-set, and the counter records the calls that had to wait.</p>
 */
public final class PrtsSableNativeLock {

    private static final ReentrantLock LOCK = new ReentrantLock();

    private PrtsSableNativeLock() {
    }

    /**
     * Reports whether the serialization is switched on.
     *
     * @return the value of the switch in effect
     */
    public static boolean enabled() {
        return PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, "serialize-sable-native-calls");
    }

    /**
     * Takes the lock, recording a call that had to wait for another thread.
     */
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
