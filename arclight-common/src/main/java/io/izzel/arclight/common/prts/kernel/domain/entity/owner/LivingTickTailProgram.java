/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.world.entity.LivingEntity;

/** The one entry the transformed LivingEntity.tick calls for its tail. A phase-A batch pass defers
 * the tail so phase B and the host tail keep their original order after the read wave. */
public final class LivingTickTailProgram {

    private LivingTickTailProgram() {
    }

    /** Runs the host tail inline unless a phase-A batch pass has claimed it. */
    public static void run(LivingEntity entity) {
        if (NativeMovePhaseContext.inPhaseA()) {
            return;
        }
        ((LivingEntityAiStepStages) entity).prts$tickTail();
    }
}