/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.List;

/** Host-plan capture of a nearest-living sensor read set into a P0 arena lease; no world writes. */
public final class AiSensorCapture {

    public record Row(long entityId, double x, double y, double z, double radius,
                      boolean visible) {
    }

    private AiSensorCapture() {
    }

    /** Captures self + visible living neighbours for each AI row; POI/block read set is a gap. */
    public static List<Row> capture(ServerLevel level, List<Villager> actors) {
        List<Row> rows = new ArrayList<>(actors.size());
        for (Villager actor : actors) {
            double radius = 16.0;
            AABB box = actor.getBoundingBox().inflate(radius, 16.0, radius);
            List<LivingEntity> nearby = level.getEntitiesOfClass(LivingEntity.class, box,
                e -> e != actor && e.isAlive());
            boolean visible = false;
            for (LivingEntity other : nearby) {
                if (actor.hasLineOfSight(other)) {
                    visible = true;
                    break;
                }
            }
            rows.add(new Row(actor.getId(), actor.getX(), actor.getY(), actor.getZ(), radius, visible));
        }
        return rows;
    }

    /** Writes captured rows into the plan-point arena lease; only the plan thread may call this. */
    public static void write(ArenaSlot.Lease lease, List<Row> rows) {
        int i = 0;
        for (Row row : rows) {
            lease.scratch().write(i++, row.x(), row.y(), row.z(), row.radius(), 0.0,
                0.0, 0.0, 0.0, row.visible() ? 1L : 0L);
        }
    }
}
