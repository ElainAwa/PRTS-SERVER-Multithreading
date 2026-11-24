/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** Diagnostic-only view of the alternative read wave lifecycle. The property is off by default;
 * every line is built from the issued counts and the real witness, never recomputed. */
public final class AltWaveDiagnostic {

    private static final boolean ENABLED =
        ExperimentGate.parse(System.getProperty("arclight.prts.diag.altWave", ""));
    private static final AtomicInteger LINES = new AtomicInteger();
    private static volatile Boolean testEnabled = null;

    private AltWaveDiagnostic() {
    }

    private static boolean on() {
        return testEnabled != null ? testEnabled : ENABLED;
    }

    /** Emitted right after a node is handed to the wave. */
    public static void issued(int count) {
        if (!on()) return;
        LINES.incrementAndGet();
        System.out.println("[PRTS] altwave|phase=issued|count=" + count);
    }

    /** Emitted after the wave closes, using the real witness values. */
    public static void witness(NativeReadWave.Witness witness) {
        if (!on() || witness == null) return;
        LINES.incrementAndGet();
        System.out.println("[PRTS] altwave|phase=closed|issued=" + witness.issued()
            + "|ready=" + witness.ready() + "|refused=" + witness.refused()
            + "|refusals=" + witness.refusals());
    }

    /** Emitted at the staged write-back decision; the flag is the actual readiness test. */
    public static void stage(String kind, boolean ready) {
        if (!on()) return;
        LINES.incrementAndGet();
        System.out.println("[PRTS] altwave|phase=stage|kind=" + kind + "|ready=" + ready);
    }

    /** Test-only controls. */
    public static void setEnabledForTest(Boolean value) { testEnabled = value; }
    public static int linesForTest() { return LINES.get(); }
    public static void resetForTest() { testEnabled = null; LINES.set(0); }
}