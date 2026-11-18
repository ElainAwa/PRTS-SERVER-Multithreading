/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueCapture;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueWindow;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;

import java.util.ArrayList;
import java.util.List;

/** The B3-A server-thread owner phase: due sleeping REST villagers are taken from the frozen
 * window and ticked on the server thread before the host entity pass; the host entry skips the
 * rows recorded as executed. No thread, queue or scheduler is created. */
public final class ServerThreadOwnerPhase {

    public static final int MAX_ROWS = 8;

    private ServerThreadOwnerPhase() {
    }

    /** Runs the phase for one frozen window; property off returns before reading anything. */
    public static void enter(ServerLevel level, EntityDueCapture.Window window) {
        if (!EntityRowOwnershipWindow.enabled() || level == null || window == null) {
            return;
        }
        List<Villager> selected = select(level, window, level.dimension().location().toString());
        int executed = 0;
        int terminal = 0;
        int rejected = 0;
        for (Villager villager : selected) {
            try {
                if (RealEntityOwnerBody.runRestedOnServer(level, villager)) {
                    executed++;
                } else {
                    rejected++;
                }
            } catch (Throwable failure) {
                terminal++;
                System.out.println("[PRTS] owner-phase terminal row=" + villager.getId()
                    + " type=" + failure.getClass().getSimpleName());
                throw failure;
            }
        }
        if (!selected.isEmpty()) {
            System.out.println("[PRTS] owner-phase selected=" + selected.size() + " executed="
                + executed + " terminal=" + terminal + " fallback=" + rejected
                + " hostSkipReady=" + RestOwnerWiring.skips());
        }
    }

    /** Selects at most MAX_ROWS due villagers that are alive, sleeping and in the REST brain. */
    public static List<Villager> select(ServerLevel level, EntityDueCapture.Window window,
                                        String levelId) {
        List<Villager> selected = new ArrayList<>();
        if (level == null || window == null || levelId == null) {
            return selected;
        }
        for (EntityDueWindow.World world : window.worlds()) {
            if (selected.size() >= MAX_ROWS) {
                break;
            }
            if (!levelId.equals(world.worldId())) {
                continue;
            }
            for (EntityDueWindow.Row row : world.rows()) {
                if (selected.size() >= MAX_ROWS) {
                    break;
                }
                if (row.removed() || row.classTag() == null
                    || !row.classTag().endsWith("Villager")) {
                    continue;
                }
                net.minecraft.world.entity.Entity entity = level.getEntity(row.entityId());
                if (!(entity instanceof Villager villager) || !villager.isAlive()
                    || villager.isRemoved() || villager.getPose() != Pose.SLEEPING
                    || !villager.getBrain().isActive(Activity.REST)) {
                    continue;
                }
                selected.add(villager);
            }
        }
        return selected;
    }
}
