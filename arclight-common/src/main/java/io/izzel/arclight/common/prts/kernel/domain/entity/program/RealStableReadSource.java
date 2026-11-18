/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.goal.WrappedGoal;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.level.pathfinder.Path;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/** The real stable read source for the existing brain, goal, sensor and navigation permit groups:
 * it reads scalars from the real Brain/goal selector/memories/path on the owner thread and keeps no
 * Brain, Level, Goal or Path object. The sensor group reads Brain memory counts (the sensor results
 * already present in the brain), not a raw Sensor object. The values are frozen into one immutable
 * row. */
public final class RealStableReadSource {

    /** The existing permit groups this source answers; no group is added here. */
    public static final Set<ReadPermit.FieldGroup> GROUPS = Set.of(ReadPermit.FieldGroup.BRAIN,
        ReadPermit.FieldGroup.GOAL, ReadPermit.FieldGroup.SENSOR, ReadPermit.FieldGroup.NAVIGATION);

    /** One frozen read of the real object: plain numbers and activity names only. */
    public record Row(long checksum, int brainActivityMask, String activeActivity,
                      int goalCount, int goalRunningCount, int goalPriorityMask, long homePos,
                      long bedPos, long jobSitePos, int nearestLivingCount,
                      boolean nearestVisiblePlayer, boolean nearestHostile,
                      boolean pathPresent, long pathTarget, int pathNodes, int pathNext,
                      boolean navigationInProgress, Set<ReadPermit.FieldGroup> groups) {

        public Row {
            if (!GROUPS.containsAll(groups)) {
                throw new IllegalArgumentException("the read source cannot answer an added group");
            }
            groups = Set.copyOf(groups);
        }
    }

    private RealStableReadSource() {
    }

    /** Reads one real villager; the caller owns the row inside the window. */
    public static Row capture(Villager villager) {
        if (villager == null) {
            throw new IllegalArgumentException("a read row needs its villager");
        }
        Brain<Villager> brain = villager.getBrain();
        int mask = 0;
        mask |= brain.isActive(Activity.CORE) ? 1 : 0;
        mask |= brain.isActive(Activity.IDLE) ? 2 : 0;
        mask |= brain.isActive(Activity.WORK) ? 4 : 0;
        mask |= brain.isActive(Activity.REST) ? 8 : 0;
        mask |= brain.isActive(Activity.MEET) ? 16 : 0;
        mask |= brain.isActive(Activity.PANIC) ? 32 : 0;
        mask |= brain.isActive(Activity.FIGHT) ? 64 : 0;
        String active = brain.getActiveNonCoreActivity().map(Activity::getName).orElse("");
        int goals = 0;
        int running = 0;
        int priorities = 0;
        for (WrappedGoal goal : villager.goalSelector.getAvailableGoals()) {
            goals++;
            priorities |= 1 << Math.min(30, goal.getPriority());
            if (goal.isRunning()) {
                running++;
            }
        }
        int nearestLiving = brain.getMemory(MemoryModuleType.NEAREST_LIVING_ENTITIES)
            .map(List::size).orElse(0);
        boolean visiblePlayer = brain.getMemory(MemoryModuleType.NEAREST_VISIBLE_PLAYER)
            .isPresent();
        boolean hostile = brain.getMemory(MemoryModuleType.NEAREST_HOSTILE).isPresent();
        Optional<GlobalPos> home = brain.getMemory(MemoryModuleType.HOME);
        Optional<GlobalPos> bed = brain.getMemory(MemoryModuleType.NEAREST_BED)
            .map(pos -> GlobalPos.of(villager.level().dimension(), pos));
        Optional<GlobalPos> job = brain.getMemory(MemoryModuleType.JOB_SITE);
        Path path = villager.getNavigation().getPath();
        boolean pathPresent = path != null;
        long target = pathPresent ? path.getTarget().asLong() : 0L;
        int nodes = pathPresent ? path.getNodeCount() : 0;
        int next = pathPresent ? path.getNextNodeIndex() : 0;
        long checksum = checksum(mask, active, goals, running, priorities, home, bed, job,
            nearestLiving, visiblePlayer, hostile, pathPresent, target, nodes, next);
        return new Row(checksum, mask, active, goals, running, priorities, pack(home),
            pack(bed), pack(job), nearestLiving, visiblePlayer, hostile, pathPresent, target,
            nodes, next, villager.getNavigation().isInProgress(), GROUPS);
    }

    private static long pack(Optional<GlobalPos> pos) {
        return pos.map(global -> BlockPos.asLong(global.pos().getX(), global.pos().getY(),
            global.pos().getZ())).orElse(0L);
    }

    private static long checksum(int mask, String active, int goals, int running, int priorities,
                                 Optional<GlobalPos> home, Optional<GlobalPos> bed,
                                 Optional<GlobalPos> job, int living, boolean player,
                                 boolean hostile, boolean path, long target, int nodes, int next) {
        long value = 0xcbf29ce484222325L;
        value = mix(value, mask);
        value = mix(value, active.hashCode());
        value = mix(value, goals);
        value = mix(value, running);
        value = mix(value, priorities);
        value = mix(value, pack(home));
        value = mix(value, pack(bed));
        value = mix(value, pack(job));
        value = mix(value, living);
        value = mix(value, player ? 1 : 0);
        value = mix(value, hostile ? 1 : 0);
        value = mix(value, path ? 1 : 0);
        value = mix(value, target);
        value = mix(value, nodes);
        return mix(value, next);
    }

    private static long mix(long value, long part) {
        return (value ^ part) * 0x100000001b3L;
    }
}
