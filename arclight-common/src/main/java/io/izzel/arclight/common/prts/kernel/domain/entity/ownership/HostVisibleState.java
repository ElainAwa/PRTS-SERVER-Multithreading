/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.bridge.core.server.level.ChunkMapBridge;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickState;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

/** Finishes the commit of a row whose host tick the fixture skips: the model writes the row's
 * rotations, motion and animation, but the host tick is also what advances the counter and leaves
 * the previous position and the tracker impulse the client-facing send reads. This is the one place
 * the fixture carries those host-visible values back onto the live row. */
public final class HostVisibleState {

    /** The values the host-visible state must carry after a skipped row commits. */
    public record Visible(double x, double y, double z, double xo, double yo, double zo,
                          int tickCount, boolean impulse) {
    }

    private HostVisibleState() {
    }

    /** Reads the host-visible values a skipped commit has to land, without touching the row. */
    public static Visible of(TickState state) {
        return new Visible(state.x, state.y, state.z, state.xo, state.yo, state.zo,
            state.tickCount, true);
    }

    /** Writes the committed row back through the same host setters a tick would have reached,
     * updates the previous position and the tick counter, and marks the impulse the tracker sends
     * on. Purely local to the tick thread; it never waits and never reads the world. */
    public static void commit(Entity entity, TickState state) {
        entity.setPos(state.x, state.y, state.z);
        entity.xo = state.xo;
        entity.yo = state.yo;
        entity.zo = state.zo;
        entity.tickCount = state.tickCount;
        entity.hasImpulse = true;
        syncTracker(entity);
    }

    /** The tracker only checks the impulse when its own send pass runs; a skipped row has no
     * other host step, so drive the tracked entity's send for this commit directly. */
    private static void syncTracker(Entity entity) {
        try {
            if (entity.level() instanceof ServerLevel level) {
                ((ChunkMapBridge) level.getChunkSource().chunkMap).bridge$syncTrackedEntity(entity);
            }
        } catch (Throwable ignored) {
            // The fixture never turns a tracker edge into a tick fault; the next pass is the fallback.
        }
    }
}
