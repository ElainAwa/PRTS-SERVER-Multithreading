/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.ExperimentGate;
import net.minecraft.util.RandomSource;

import java.util.concurrent.atomic.AtomicLong;

/** Experiment-only stable seed for freshly constructed entities. Vanilla derives the entity seed
 * from System.nanoTime, which makes two runs of the same world snapshot diverge before any kernel
 * work. This helper is active only when the diagnostic property is set; the shipped path calls the
 * vanilla factory unchanged. The key is a per-process birth ordinal, never a clock, thread id or
 * object hash, so a fixed summon order gives a bit-identical initial stream on every run. */
public final class StableEntityRandom {

    private static final String PROPERTY = "arclight.prts.diag.stableEntityRandom";
    private static final boolean ENABLED = ExperimentGate.parse(System.getProperty(PROPERTY, ""));
    private static final AtomicLong ORDINAL = new AtomicLong();

    private StableEntityRandom() {
    }

    /** The factory the transformed Entity constructor calls instead of RandomSource.create(). */
    public static RandomSource create() {
        if (!ENABLED) {
            return RandomSource.create();
        }
        return RandomSource.create(seedFor(ORDINAL.getAndIncrement()));
    }

    /** Stable key derivation: fixed constants and the birth ordinal only. */
    public static long seedFor(long ordinal) {
        return 0x5DEECE66DL ^ Long.rotateLeft(ordinal * 0x9E3779B97F4A7C15L, 17);
    }

    /** Test-only counter reset; production reads the ordinal from the process start. */
    public static void resetForTest() {
        ORDINAL.set(0);
    }
}