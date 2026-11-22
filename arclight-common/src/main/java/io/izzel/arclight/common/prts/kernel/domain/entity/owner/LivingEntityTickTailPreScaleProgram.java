/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.world.entity.LivingEntity;

import java.util.Set;

/** Runs the pre-scale prefix of {@code LivingEntity.tick} through the one entry the transformed host
 * calls; with a bound owner context the prefix is declared as one host-leg segment, without one the
 * stage runs exactly as before. The scale branch remains inline in the host method. */
public final class LivingEntityTickTailPreScaleProgram {

    private static final EntityUpdateSegmentPolicy.Segment SEGMENT =
        EntityUpdateSegmentPolicy.Segment.LIVING_ENTITY_TICK_TAIL;
    private static final Set<EntityUpdateSegmentPolicy.Segment> DECLARED = Set.of(SEGMENT);

    private LivingEntityTickTailPreScaleProgram() {
    }

    /** The single entry the transformed host calls; a missing or mismatched context delegates. */
    public static void run(LivingEntity living) {
        EntityUpdateProgramContext context = EntityUpdateProgramContext.current();
        boolean matches = context != null && context.matches(living);
        run(context, matches, (LivingEntityTickTailPreScaleStages) living);
    }

    static void run(EntityUpdateProgramContext context, boolean identityMatches,
                    LivingEntityTickTailPreScaleStages stages) {
        if (context == null || !identityMatches || !EntityUpdateSegmentPolicy.admitsWorker(DECLARED)) {
            stages.prts$livingTickTailPreScale();
            return;
        }
        if (!context.enterSegment(SEGMENT)) {
            throw new IllegalStateException("pre-scale segment entered twice for one owner row");
        }
        try {
            stages.prts$livingTickTailPreScale();
            context.noteSegmentExecuted(SEGMENT);
        } finally {
            context.leaveSegment(SEGMENT);
        }
    }
}
