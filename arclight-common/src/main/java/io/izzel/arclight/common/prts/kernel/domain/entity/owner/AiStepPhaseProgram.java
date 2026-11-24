/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.world.entity.LivingEntity;

/** The one entry the transformed AI step calls at its phase boundary. With no split batch bound the
 * phase-B stage runs inline exactly as before; inside a phase-A pass it is deferred to the seat pump
 * so the read wave can close before any movement result is consumed. */
public final class AiStepPhaseProgram {

    private AiStepPhaseProgram() {
    }

    /** Runs phase B inline unless a phase-A batch pass has claimed it. Rows that stay on the host
     * seat still cross the split-body boundary here: the approved stable-read checkpoint is taken at
     * phase-A end and restored before travel, so owner and host rows enter phase B from the same
     * control input. A product build with no split body never calls this entry, so shipped/off-path
     * behavior is unchanged. */
    public static void run(LivingEntity entity) {
        if (NativeMovePhaseContext.inPhaseA()) {
            return;
        }
        if (entity instanceof io.izzel.arclight.common.prts.fixes.ownership.PrtsLivingEntityTickStateMixin state) {
            NativeMovePhaseContext.freezeControl(state.prts$xxa(), state.prts$zza(), state.prts$jumping());
            NativeMovePhaseContext.Control frozen = NativeMovePhaseContext.restoreControl();
            if (frozen != null) {
                state.prts$setXxa(frozen.xxa());
                state.prts$setZza(frozen.zza());
                state.prts$setJumping(frozen.jumping());
            }
        }
        ((LivingEntityAiStepStages) entity).prts$aiStepPhaseB();
    }
}
