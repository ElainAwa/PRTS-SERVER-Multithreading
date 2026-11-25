/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

/** Diagnostic-only view of the exact moment the host freezes self-row inputs. The property is off
 * by default; every printed value comes from the frozen records and the tick clock already present
 * at the call site, never from a fresh entity read. */
public final class AltFreezeDiagnostic {

    private static final boolean ENABLED =
        io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.ExperimentGate.parse(
            System.getProperty("arclight.prts.diag.altFreeze", ""));
    private static volatile Boolean testEnabled = null;

    private AltFreezeDiagnostic() {
    }

    /** Emits the frozen inputs together with the world/game/entity clocks of the freeze instant. */
    public static void note(String rowKey, long tick, long gameTime, int entityTick,
                            AltPhaseSubComputation.AiHead head,
                            AltPhaseSubComputation.Controls controls,
                            AltPhaseSubComputation.TickTail tail) {
        if (testEnabled != null ? !testEnabled : !ENABLED) return;
        System.out.println("[PRTS] altfreeze|tick=" + tick + "|rowKey=" + rowKey
            + "|gameTime=" + gameTime + "|entityTick=" + entityTick
            + "|effectiveAi=" + (head == null ? "NA" : head.effectiveAi())
            + "|immobile=" + (head == null ? "NA" : head.immobile())
            + "|lerpSteps=" + (head == null ? "NA" : head.lerpSteps())
            + "|request=" + (head == null ? "NA" : head.vx() + "," + head.vy() + "," + head.vz())
            + "|rotation=" + (controls == null ? "NA" : controls.rotation())
            + "|xxa=" + (controls == null ? "NA" : controls.xxa())
            + "|zza=" + (controls == null ? "NA" : controls.zza())
            + "|jumping=" + (controls == null ? "NA" : controls.jumping())
            + "|yBodyRot=" + (tail == null ? "NA" : tail.yBodyRot())
            + "|run=" + (tail == null ? "NA" : tail.run())
            + "|attackAnim=" + (tail == null ? "NA" : tail.attackAnim())
            + "|swingTime=" + (tail == null ? "NA" : tail.swing()));
    }

    /** Test-only control. */
    public static void setEnabledForTest(Boolean value) { testEnabled = value; }
}