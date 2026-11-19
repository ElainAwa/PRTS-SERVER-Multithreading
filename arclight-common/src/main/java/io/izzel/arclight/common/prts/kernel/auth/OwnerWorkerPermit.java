/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

/** The narrow base-access permit one owner worker holds while the host thread is parked waiting for
 * that worker's span. It lifts only the thread check of the base async guard for that one thread
 * for the duration of one span; every write still goes through the owner and write authority. */
public final class OwnerWorkerPermit {

    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private OwnerWorkerPermit() {
    }

    /** Whether the current worker holds the span permit. */
    public static boolean allowed() {
        return ACTIVE.get();
    }

    public static void enter() {
        ACTIVE.set(Boolean.TRUE);
    }

    public static void exit() {
        ACTIVE.remove();
    }
}
