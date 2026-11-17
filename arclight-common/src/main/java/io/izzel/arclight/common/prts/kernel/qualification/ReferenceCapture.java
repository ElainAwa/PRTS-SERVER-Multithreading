/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueCapture;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueWindow;
import io.izzel.arclight.common.prts.kernel.random.SharedStream;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The real arrival capture: when a due window is available it becomes the fixed ReferenceInput
 * rows in their real order with a unique identity per row; without plan or kernel the capture
 * answers unbound by name and never invents rows. Capture/restore round-trips through the same
 * ReferenceInput record; nothing here reads a world or changes a schedule. */
public final class ReferenceCapture {

    /** One capture attempt: an input or a named unbound reason. */
    public record Result(ReferenceInput input, String unboundReason) {

        public boolean bound() {
            return input != null && (unboundReason == null || unboundReason.isEmpty());
        }
    }

    private ReferenceCapture() {
    }

    /** Captures one due window as the fixed input; an empty window is unbound, never empty rows. */
    public static Result fromDueWindow(EntityDueCapture.Window window, long planTick,
                                       long worldIncarnation, SharedStream stream,
                                       Map<Long, Long> initialState) {
        if (window == null) {
            return new Result(null, "unbound:no-due-window");
        }
        EntityDueWindow.World world = null;
        for (EntityDueWindow.World candidate : window.worlds()) {
            if (candidate != null && !candidate.rows().isEmpty()) {
                world = candidate;
                break;
            }
        }
        if (world == null) {
            return new Result(null, "unbound:no-arrival-rows");
        }
        if (worldCounter(world) < 0L) {
            return new Result(null, "world-incarnation");
        }
        List<ReferenceInput.Arrival> arrivals = new ArrayList<>(world.rows().size());
        long order = 1L;
        for (EntityDueWindow.Row row : world.rows()) {
            arrivals.add(new ReferenceInput.Arrival(identityOf(row), row.entityId(), row.x(),
                row.y(), row.z(), order++, 0L));
        }
        ReferenceInput input = ReferenceInput.capture(worldIncarnation, world.worldId(), planTick,
            planTick, 1, arrivals, stream, initialState);
        return new Result(input, "");
    }

    /** Captures the current due window when the capture and the kernel plan are available. */
    public static Result captureCurrent(long planTick, SharedStream stream,
                                        Map<Long, Long> initialState) {
        EntityDueCapture.Window window = EntityDueCapture.published(planTick);
        if (window == null) {
            return new Result(null, "unbound:due-window-not-armed-or-no-plan");
        }
        if (window.worlds().isEmpty()) {
            return new Result(null, "unbound:no-arrival-rows");
        }
        String worldId = window.worlds().get(0).worldId();
        long incarnation = -1L;
        try {
            if (!worldId.isEmpty()) {
                incarnation = KernelModule.instance().guard().worldEpochs().epochOf(worldId);
            }
        } catch (Throwable unavailable) {
            incarnation = -1L;
        }
        if (incarnation < 0L) {
            return new Result(null, "unbound:no-world-incarnation");
        }
        return fromDueWindow(window, planTick, incarnation, stream, initialState);
    }

    private static long identityOf(EntityDueWindow.Row row) {
        return (((long) row.entityId()) * 1_000_003L) ^ row.uuidHigh()
            ^ Long.rotateLeft(row.uuidLow(), 17) ^ ((long) row.tickCount() * 131L);
    }

    private static long worldCounter(EntityDueWindow.World world) {
        return world.loaded() >= 0 ? 1L : -1L;
    }
}
