/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

/** Internal per-class cost accumulators for the quota source. They are not published as readout
 * keys and change no scheduling decision; only entries with a real production caller receive
 * samples, so an unsourced class stays at zero rather than being presented as measured. */
public final class QuotaCostReadings {

    public enum QuotaClass {
        MACHINE,
        DEVICE,
        ENTITY,
        NET
    }

    private static final long[] WALL_NANOS = new long[QuotaClass.values().length];
    private static final long[] CALLS = new long[QuotaClass.values().length];

    private QuotaCostReadings() {
    }

    /** Adds one real entry span; a class with no production caller never reaches this method. */
    public static synchronized void note(QuotaClass quotaClass, long wallNanos) {
        int index = quotaClass.ordinal();
        CALLS[index]++;
        WALL_NANOS[index] += Math.max(0L, wallNanos);
    }

    public static synchronized long calls(QuotaClass quotaClass) {
        return CALLS[quotaClass.ordinal()];
    }

    public static synchronized long wallNanos(QuotaClass quotaClass) {
        return WALL_NANOS[quotaClass.ordinal()];
    }

    public static synchronized void reset() {
        for (int index = 0; index < WALL_NANOS.length; index++) {
            WALL_NANOS[index] = 0L;
            CALLS[index] = 0L;
        }
    }
}
