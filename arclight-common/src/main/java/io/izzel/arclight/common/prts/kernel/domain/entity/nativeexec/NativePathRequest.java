/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.ChunkAccess;

import java.util.Objects;
import java.util.Set;

/** One frozen path search: the navigation and mob it was planned for, the request values the search
 * consumed and the region coverage it read. A live call reuses the staged result only when every one
 * of them still matches, so any drift costs one search and never produces a different path. */
public record NativePathRequest(Object navigation, Object mob, Class<?> evaluatorClass,
                                Set<BlockPos> targets, float maxRange, int accuracy,
                                float searchDepthMultiplier, int maxVisitedNodes, boolean canPassDoors,
                                boolean canOpenDoors, boolean canFloat, boolean canWalkOverFences,
                                NativePathRegionState region) {

    public NativePathRequest {
        // The two holders are compared by identity; a request without them never matches a live
        // call, so the absence is safe and only the comparison values need to be present.
        Objects.requireNonNull(evaluatorClass, "evaluatorClass");
        Objects.requireNonNull(targets, "targets");
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("a path request needs at least one target");
        }
        targets = Set.copyOf(targets);
    }

    /** What a live call site presents for the same decision: the request values plus the region. */
    public record Live(Object navigation, Object mob, Class<?> evaluatorClass,
                       NativePathRegionState region, Set<BlockPos> targets, float maxRange,
                       int accuracy, float searchDepthMultiplier, int maxVisitedNodes,
                       boolean canPassDoors, boolean canOpenDoors, boolean canFloat,
                       boolean canWalkOverFences) {

        public Live {
            Objects.requireNonNull(targets, "targets");
            targets = Set.copyOf(targets);
        }
    }

    /** Whether a live call names exactly the frozen search: same objects, same values, same chunk
     * coverage. A float that differs by one step is a different search, never an equivalent one. */
    public boolean matches(Live live) {
        if (live == null) {
            return false;
        }
        return navigation == live.navigation()
            && mob == live.mob()
            && evaluatorClass == live.evaluatorClass()
            && targets.equals(live.targets())
            && Float.compare(maxRange, live.maxRange()) == 0
            && accuracy == live.accuracy()
            && Float.compare(searchDepthMultiplier, live.searchDepthMultiplier()) == 0
            && maxVisitedNodes == live.maxVisitedNodes()
            && canPassDoors == live.canPassDoors()
            && canOpenDoors == live.canOpenDoors()
            && canFloat == live.canFloat()
            && canWalkOverFences == live.canWalkOverFences()
            && sameRegion(live.region());
    }

    private boolean sameRegion(NativePathRegionState live) {
        if (region == null || live == null) {
            return false;
        }
        if (region.prts$centerX() != live.prts$centerX()
            || region.prts$centerZ() != live.prts$centerZ()) {
            return false;
        }
        ChunkAccess[][] own = region.prts$chunks();
        ChunkAccess[][] other = live.prts$chunks();
        if (own == null || other == null || own.length != other.length) {
            return false;
        }
        for (int x = 0; x < own.length; x++) {
            if (own[x] == null || other[x] == null || own[x].length != other[x].length) {
                return false;
            }
            for (int z = 0; z < own[x].length; z++) {
                if (own[x][z] != other[x][z]) {
                    return false;
                }
            }
        }
        return true;
    }
}
