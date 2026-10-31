/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

/** The recursion guard of the neighbour-update probe: only the outermost call is reported, so a
 * redstone propagation is not counted once per nested update. */
public final class QuotaCostSpans {

    private static final ThreadLocal<Integer> MACHINE_DEPTH = ThreadLocal.withInitial(() -> 0);

    private QuotaCostSpans() {
    }

    /** @return true only for the outermost call on this thread */
    public static boolean enterMachine() {
        int depth = MACHINE_DEPTH.get();
        MACHINE_DEPTH.set(depth + 1);
        return depth == 0;
    }

    public static void exitMachine(boolean root, long startedAt) {
        int depth = MACHINE_DEPTH.get();
        MACHINE_DEPTH.set(Math.max(0, depth - 1));
        if (root && startedAt > 0L) {
            QuotaCostReadings.note(QuotaCostReadings.QuotaClass.MACHINE,
                System.nanoTime() - startedAt);
        }
    }
}
