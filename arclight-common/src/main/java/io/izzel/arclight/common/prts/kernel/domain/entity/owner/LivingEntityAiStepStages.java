/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.world.entity.LivingEntity;

/** The callable phases of the transformed LivingEntity update, exposed so the same seat can run
 * phase A, open its read wave, then run phase B and the host tail in the original order. */
public interface LivingEntityAiStepStages {

    /** Runs the prefix phase up to the jump profiler section, including its entry selector. */
    void prts$aiStepPhaseA(int entry);

    /** Runs the jump and travel section that follows the read wave. */
    void prts$aiStepPhaseB();

    /** Runs the host tail that follows the AI step inside LivingEntity.tick. */
    void prts$tickTail();
}