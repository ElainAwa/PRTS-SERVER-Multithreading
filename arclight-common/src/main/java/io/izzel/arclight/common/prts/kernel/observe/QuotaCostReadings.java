/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import java.util.concurrent.atomic.LongAdder;

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

    private static final LongAdder[] WALL_NANOS = freshAdders();
    private static final LongAdder[] CALLS = freshAdders();

    private QuotaCostReadings() {
    }

    private static LongAdder[] freshAdders() {
        LongAdder[] adders = new LongAdder[QuotaClass.values().length];
        for (int index = 0; index < adders.length; index++) {
            adders[index] = new LongAdder();
        }
        return adders;
    }

    /** Adds one real entry span; a class with no production caller never reaches this method. */
    public static void note(QuotaClass quotaClass, long wallNanos) {
        int index = quotaClass.ordinal();
        CALLS[index].increment();
        WALL_NANOS[index].add(Math.max(0L, wallNanos));
    }

    public static long calls(QuotaClass quotaClass) {
        return CALLS[quotaClass.ordinal()].sum();
    }

    public static long wallNanos(QuotaClass quotaClass) {
        return WALL_NANOS[quotaClass.ordinal()].sum();
    }

    public static void reset() {
        for (int index = 0; index < WALL_NANOS.length; index++) {
            WALL_NANOS[index].reset();
            CALLS[index].reset();
        }
    }
}
