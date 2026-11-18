/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership;
import io.izzel.arclight.common.prts.support.EntityEligibility;
import io.izzel.arclight.common.prts.support.PrtsEntityCapability;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** The one server-tick window of frozen due rows: captured by the host tick list before it iterates
 * it, compiled by the entity domain of the same server tick, and written only on the server thread. */
public final class EntityDueCapture {

    /** One frozen server tick: the levels that reached their entity loop and the rows each carried. */
    public record Window(long serverTick, List<EntityDueWindow.World> worlds) {

        public Window {
            worlds = List.copyOf(worlds);
        }
    }

    private static final SharedDueWindow SHARED = new SharedDueWindow();
    private static long windowTick = Long.MIN_VALUE;
    private static final List<EntityDueWindow.World> WORLDS = new ArrayList<>();
    private static List<EntityDueWindow.Row> rows;
    private static String worldId;
    private static int loaded;
    private static boolean captured;
    private static boolean failed;

    private EntityDueCapture() {
    }

    /**
     * Whether the same-tick due source is declared: the kernel, its plan switch and its job graph
     * must all be on. Off, the level tick keeps its host loop untouched and the entity domain keeps
     * the legacy loaded scan.
     */
    public static boolean armed() {
        return armed(KernelSettings.enabled(), KernelSettings.dispatchParallel(),
            KernelSettings.jobGraph(), Boolean.getBoolean("arclight.prts.entityRowOwner"));
    }

    static boolean armed(boolean kernelEnabled, boolean dispatchParallel, boolean jobGraph,
                         boolean ownerPhase) {
        return kernelEnabled && jobGraph && (dispatchParallel || ownerPhase);
    }

    /** Starts the freeze of one level's tick list; a new server tick drops the previous window. */
    public static void begin(long serverTick, String levelId, int loadedTotal) {
        if (windowTick != serverTick) {
            WORLDS.clear();
            windowTick = serverTick;
            failed = false;
            captured = false;
            SHARED.begin(serverTick);
        }
        worldId = levelId;
        loaded = loadedTotal;
        rows = new ArrayList<>();
    }

    /** Freezes one row of the tick list; called once per identity the host will iterate. */
    public static void row(Entity entity) {
        if (rows == null) {
            return;
        }
        try {
            if (EntityTickOwnership.live()
                && EntityTickOwnership.claimedForDispatch(entity.getId())) {
                return;
            }
            if (entity instanceof Player) {
                PrtsEntityCapability.playerRow();
                return;
            }
            EntityEligibility.Reason reason = PrtsEntityCapability.census(entity);
            Vec3 motion = entity.getDeltaMovement();
            rows.add(new EntityDueWindow.Row(entity.getId(),
                entity.getUUID().getMostSignificantBits(),
                entity.getUUID().getLeastSignificantBits(), entity.tickCount,
                entity.isPassenger(), entity.isRemoved(), entity.getX(), entity.getY(),
                entity.getZ(), entity.getYRot(), entity.getXRot(), motion.x, motion.y, motion.z,
                DispatchSnapshot.hostStep(entity), reason, entity.getClass().getName()));
        } catch (Throwable failure) {
            fail(failure);
        }
    }

    /** Finishes the freeze of the level whose rows were just read. */
    public static void end() {
        if (rows == null) {
            return;
        }
        WORLDS.add(new EntityDueWindow.World(worldId, loaded, rows));
        rows = null;
        captured = true;
    }

    /** Marks the freeze of this server tick failed; the window of a partial level is not used. */
    public static void fail(Throwable failure) {
        failed = true;
        rows = null;
    }

    /** Takes the window of the server tick that just ran: each level that reached its entity loop,
     * with the rows frozen before that loop. A tick that captured nothing (no level reached its loop,
     * or the seam is not applied) keeps the legacy work source instead of planning an empty tick.
     *
     * @return the window, or null when the source is not declared, nothing was captured, or a
     * freeze failed and the legacy path must run
     */
    public static Window take(long serverTick) {
        if (!armed() || failed || !captured || windowTick != serverTick || WORLDS.isEmpty()) {
            return null;
        }
        // All levels of the tick have ended before the post-tick take; publish the complete window.
        SHARED.publish(new Window(windowTick, WORLDS));
        return SHARED.take(serverTick);
    }

    /** The window the kernel published for one tick; reading it neither publishes nor clears. */
    public static Window published(long serverTick) {
        SharedDueWindow shared = SHARED;
        return shared == null ? null : shared.take(serverTick);
    }

    /** The frozen window of the server tick if a capture finished; read-only, no publish. */
    public static Window current(long serverTick) {
        if (!captured || failed || windowTick != serverTick) {
            return null;
        }
        return new Window(windowTick, List.copyOf(WORLDS));
    }

    /** The shared window cache of the current tick; test and evidence code only. */
    static SharedDueWindow sharedWindow() {
        return SHARED;
    }
}
