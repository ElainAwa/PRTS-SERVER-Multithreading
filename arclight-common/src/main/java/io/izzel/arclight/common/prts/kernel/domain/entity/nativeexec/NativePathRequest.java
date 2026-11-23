/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.PathFinder;

import java.util.Objects;
import java.util.Set;

/** One frozen path search: the navigation and mob it was planned for, the request values the search
 * consumed and the region coverage it read. A live call reuses the staged result only when every one
 * of them still matches, so any drift costs one search and never produces a different path. */
public record NativePathRequest(Object navigation, Object mob, Class<?> evaluatorClass,
                                Set<BlockPos> targets, float maxRange, int accuracy,
                                float searchDepthMultiplier, int maxVisitedNodes, boolean canPassDoors,
                                boolean canOpenDoors, boolean canFloat, boolean canWalkOverFences,
                                NativePathRegionState region, Class<?> finderClass, MobKey mobKey) {

    /** The live state a search reads from the mob itself: the start node, the bounding box and the
     * step height. The region only freezes blocks, so a previous row or a plugin that moved the mob,
     * changed its pose or mounted it would otherwise reuse a path from another start. */
    public record MobKey(double x, double y, double z, boolean onGround, boolean isInWater,
                         boolean isInLiquid, boolean isPassenger, float bbWidth, float bbHeight,
                         float maxUpStep) {

        /** Reads the live key once; the values are cheap field reads and are never cached. */
        public static MobKey of(Mob mob) {
            Objects.requireNonNull(mob, "mob");
            return new MobKey(mob.getX(), mob.getY(), mob.getZ(), mob.onGround(), mob.isInWater(),
                mob.isInLiquid(), mob.isPassenger(), mob.getBbWidth(), mob.getBbHeight(),
                mob.maxUpStep());
        }
    }

    public NativePathRequest {
        // The two holders are compared by identity; a request without them never matches a live
        // call, so the absence is safe and only the comparison values need to be present.
        Objects.requireNonNull(evaluatorClass, "evaluatorClass");
        Objects.requireNonNull(targets, "targets");
        Objects.requireNonNull(finderClass, "finderClass");
        Objects.requireNonNull(mobKey, "mobKey");
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
                       boolean canWalkOverFences, Class<?> finderClass, MobKey mobKey) {

        public Live {
            Objects.requireNonNull(targets, "targets");
            targets = Set.copyOf(targets);
        }

        /** Freezes the live call into the same values a plan stores; only called when a stage is
         * bound, so the off path never reads the mob state for a key nobody will compare. */
        public static Live of(PathNavigation navigation, Mob mob, PathNavigationRegion region,
                              PathFinder finder, Set<BlockPos> targets, float maxRange,
                              int accuracy, float searchDepthMultiplier) {
            NodeEvaluator evaluator = navigation.getNodeEvaluator();
            int maxVisitedNodes = finder instanceof NativePathFinderState state
                ? state.prts$maxVisitedNodes() : Integer.MIN_VALUE;
            return new Live(navigation, mob, evaluator == null ? Object.class : evaluator.getClass(),
                region instanceof NativePathRegionState state ? state : null, targets, maxRange,
                accuracy, searchDepthMultiplier, maxVisitedNodes,
                evaluator != null && evaluator.canPassDoors(),
                evaluator != null && evaluator.canOpenDoors(),
                evaluator != null && evaluator.canFloat(),
                evaluator != null && evaluator.canWalkOverFences(),
                finder.getClass(), MobKey.of(mob));
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
            && finderClass == live.finderClass()
            && targets.equals(live.targets())
            && Float.compare(maxRange, live.maxRange()) == 0
            && accuracy == live.accuracy()
            && Float.compare(searchDepthMultiplier, live.searchDepthMultiplier()) == 0
            && maxVisitedNodes == live.maxVisitedNodes()
            && canPassDoors == live.canPassDoors()
            && canOpenDoors == live.canOpenDoors()
            && canFloat == live.canFloat()
            && canWalkOverFences == live.canWalkOverFences()
            && mobKey.equals(live.mobKey())
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
