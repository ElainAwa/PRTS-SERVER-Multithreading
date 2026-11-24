/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.AltPhaseStage;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.AltPhaseSubComputation;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.ExperimentGate;
import net.minecraft.world.entity.LivingEntity;

/** Default-off host freeze for the three admitted self-row sub-computations. With the property
 * unset no stage is touched, so the product path keeps vanilla behavior bit for bit. */
public final class AltPhaseProgram {

    private static final String PROPERTY = "arclight.prts.altPhase";
    private static final boolean ENABLED = ExperimentGate.parse(System.getProperty(PROPERTY, ""));

    private AltPhaseProgram() {
    }

    /** Whether the experimental read wave is active; off by default. */
    public static boolean enabled() {
        return ENABLED;
    }

    /** Stage one row's pure results from fields already frozen on the host. */
    public static void stageFrom(LivingEntity entity, String rowKey) {
        if (!ENABLED) return;
        AltPhaseStage.stage(rowKey, "aiHead", new AltPhaseSubComputation.AiHead(
            entity.isEffectiveAi(), false, 0, entity.getDeltaMovement().x,
            entity.getDeltaMovement().y, entity.getDeltaMovement().z).compute());
    }
}