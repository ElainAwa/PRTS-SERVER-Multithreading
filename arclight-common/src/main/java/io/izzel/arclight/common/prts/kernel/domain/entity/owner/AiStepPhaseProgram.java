/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.world.entity.LivingEntity;

/** The one entry the transformed AI step calls at its phase boundary. With no split batch bound the
 * phase-B stage runs inline exactly as before; inside a phase-A pass it is deferred to the seat pump
 * so the read wave can close before any movement result is consumed. */
public final class AiStepPhaseProgram {

    private AiStepPhaseProgram() {
    }

    /** Runs phase B inline unless a phase-A batch pass has claimed it. */
    public static void run(LivingEntity entity) {
        if (NativeMovePhaseContext.inPhaseA()) {
            return;
        }
        ((LivingEntityAiStepStages) entity).prts$aiStepPhaseB();
    }
}
