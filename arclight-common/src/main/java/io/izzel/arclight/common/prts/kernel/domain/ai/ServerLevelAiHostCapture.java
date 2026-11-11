/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.ai;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Host-server AI capture: self/neighbour/target/nav rows from real ServerLevel/Villager objects. */
public final class ServerLevelAiHostCapture {

    private ServerLevelAiHostCapture() {
    }

    public static AiArenaPayload.Snapshot capture(ServerLevel level, List<Villager> actors,
                                                   long tick, long worldEpoch, long generation) {
        if (level == null || actors == null) {
            throw new IllegalArgumentException("capture needs a level and actors");
        }
        Map<Long, List<LivingEntity>> cells = new HashMap<>();
        for (net.minecraft.world.entity.Entity entity : level.getEntities().getAll()) {
            if (entity instanceof LivingEntity living && living.isAlive()) {
                cells.computeIfAbsent(AiSpatialGrid.cellKey((int) living.getX(), (int) living.getZ()),
                    key -> new ArrayList<>()).add(living);
            }
        }
        List<AiArenaPayload.Row> rows = new ArrayList<>();
        int poiBudget = 64;
        for (Villager actor : actors) {
            if (actor == null || !actor.isAlive()) continue;
            rows.add(new AiArenaPayload.Row(AiArenaPayload.Kind.ACTOR, actor.getId(),
                actor.getX(), actor.getY(), actor.getZ(), generation, true));
            LivingEntity target = actor.getTarget();
            if (target != null && target.isAlive()) {
                rows.add(new AiArenaPayload.Row(AiArenaPayload.Kind.TARGET, target.getId(),
                    target.getX(), target.getY(), target.getZ(), generation,
                    actor.hasLineOfSight(target)));
            }
            int actorX = (int) actor.getX();
            int actorZ = (int) actor.getZ();
            for (long key : AiSpatialGrid.around(actorX, actorZ)) {
                List<LivingEntity> bucket = cells.get(key);
                if (bucket == null) continue;
                for (LivingEntity other : bucket) {
                    if (other == actor || !other.isAlive()) continue;
                    if (!AiSpatialGrid.inRange(actorX, actorZ, (int) other.getX(), (int) other.getZ())) {
                        continue;
                    }
                    rows.add(new AiArenaPayload.Row(AiArenaPayload.Kind.NEIGHBOUR, other.getId(),
                        other.getX(), other.getY(), other.getZ(), generation,
                        actor.hasLineOfSight(other)));
                }
            }
            actor.getBrain().getMemory(MemoryModuleType.HOME).ifPresent(home ->
                rows.add(new AiArenaPayload.Row(AiArenaPayload.Kind.MEMORY, actor.getId(),
                    home.pos().getX(), home.pos().getY(), home.pos().getZ(), generation, true)));
            if (poiBudget > 0) {
                poiBudget--;
                java.util.List<BlockPos> pois = level.getPoiManager()
                    .findAll(holder -> true, pos -> true, actor.blockPosition(), 16,
                        PoiManager.Occupancy.ANY)
                    .limit(4).toList();
                for (BlockPos poi : pois) {
                    rows.add(new AiArenaPayload.Row(AiArenaPayload.Kind.POI, actor.getId(),
                        poi.getX(), poi.getY(), poi.getZ(), generation, true));
                }
            }
            if (actor.getNavigation().getPath() != null) {
                rows.add(new AiArenaPayload.Row(AiArenaPayload.Kind.NAV, actor.getId(),
                    actor.getX(), actor.getY(), actor.getZ(), generation, true));
            }
        }
        return new AiArenaPayload.Snapshot(worldEpoch, generation, rows);
    }
}
