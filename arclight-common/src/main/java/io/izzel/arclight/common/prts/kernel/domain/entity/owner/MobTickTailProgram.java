/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.world.entity.Mob;

import java.util.Set;

/** Runs the {@code Mob.tick} tail through the one program entry the transformed host calls: with a
 * bound owner context the tail is declared as one segment, without one the host stage runs as before. */
public final class MobTickTailProgram {

    private static final EntityUpdateSegmentPolicy.Segment SEGMENT =
        EntityUpdateSegmentPolicy.Segment.MOB_TICK_TAIL;
    private static final Set<EntityUpdateSegmentPolicy.Segment> DECLARED = Set.of(SEGMENT);

    private MobTickTailProgram() {
    }

    /** The single entry the transformed Mob.tick calls; a missing or mismatched context delegates. */
    public static void run(Mob mob) {
        EntityUpdateProgramContext context = EntityUpdateProgramContext.current();
        boolean matches = context != null && context.matches(mob);
        run(context, matches, (MobTickTailStages) mob);
    }

    static void run(EntityUpdateProgramContext context, boolean identityMatches,
                    MobTickTailStages stages) {
        // A split owner batch runs this tail in its phase-B pass, after the read wave.
        if (NativeMovePhaseContext.inPhaseA()) {
            return;
        }
        if (context == null || !identityMatches || !EntityUpdateSegmentPolicy.admitsWorker(DECLARED)) {
            stages.prts$mobTickTail();
            return;
        }
        if (!context.enterSegment(SEGMENT)) {
            throw new IllegalStateException("mob tick tail entered twice for one owner row");
        }
        try {
            stages.prts$mobTickTail();
            context.noteSegmentExecuted(SEGMENT);
        } finally {
            context.leaveSegment(SEGMENT);
        }
    }
}