/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import net.minecraft.world.level.pathfinder.WalkNodeEvaluator;

import java.util.Set;

/** The one read node of the first native slice: the path search the navigation would run at its next
 * delayed recomputation. The host freezes the request, the region and a task-local finder before the
 * wave; the node only searches, and the original method keeps its early return, its profiler scope
 * and every write-back. A plan that cannot be frozen exactly is absent, and the row stays the whole
 * body. */
public final class NativePathNode {

    /** The region offset the delayed recomputation uses when it builds its navigation region. */
    private static final int REGION_OFFSET = 8;
    /** The recomputation window the navigation enforces between two searches. */
    private static final long RECOMPUTE_WINDOW = 20L;

    /** One frozen search: the request the host calls with, the mob it was planned for, the region it
     * reads and its own finder. */
    public record Plan(NativePathRequest request, PathNavigationRegion region, PathFinder finder,
                       Mob mob) {
    }

    private NativePathNode() {
    }

    /** The plan of one delayed recomputation, or null when this row has no search the wave may take:
     * another navigation family, a non-walk evaluator, no delayed recomputation, a search window the
     * navigation would refuse, an unloaded target chunk or a target the ground adjustment moves. */
    public static Plan plan(ServerLevel level, Mob mob) {
        if (level == null || mob == null) {
            return null;
        }
        PathNavigation navigation = mob.getNavigation();
        if (!(navigation instanceof GroundPathNavigation)) {
            return null;
        }
        if (!(navigation instanceof NativePathState state)) {
            return null;
        }
        if (!state.prts$hasDelayedRecomputation()) {
            return null;
        }
        if (mob.getY() < (double) level.getMinBuildHeight()) {
            return null;
        }
        if (!(mob.onGround() || mob.isInLiquid() || mob.isPassenger())) {
            return null;
        }
        BlockPos target = state.prts$targetPos();
        if (target == null) {
            return null;
        }
        if (level.getGameTime() - state.prts$timeLastRecompute() <= RECOMPUTE_WINDOW) {
            return null;
        }
        NodeEvaluator evaluator = navigation.getNodeEvaluator();
        if (evaluator == null || evaluator.getClass() != WalkNodeEvaluator.class) {
            return null;
        }
        Set<BlockPos> targets = groundTargets(level, target);
        if (targets == null || targets.isEmpty()) {
            return null;
        }
        Path path = navigation.getPath();
        if (path != null && !path.isDone() && targets.contains(state.prts$targetPos())) {
            return null;
        }
        float followRange = (float) mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        BlockPos center = mob.blockPosition();
        int radius = (int) (followRange + (float) REGION_OFFSET);
        PathNavigationRegion region = new PathNavigationRegion(level,
            center.offset(-radius, -radius, -radius), center.offset(radius, radius, radius));
        if (!(region instanceof NativePathRegionState regionState)) {
            return null;
        }
        PathFinder liveFinder = state.prts$pathFinder();
        if (!(liveFinder instanceof NativePathFinderState finderState)) {
            return null;
        }
        int maxVisitedNodes = finderState.prts$maxVisitedNodes();
        WalkNodeEvaluator taskEvaluator = new WalkNodeEvaluator();
        taskEvaluator.setCanPassDoors(evaluator.canPassDoors());
        taskEvaluator.setCanOpenDoors(evaluator.canOpenDoors());
        taskEvaluator.setCanFloat(evaluator.canFloat());
        taskEvaluator.setCanWalkOverFences(evaluator.canWalkOverFences());
        PathFinder finder = new PathFinder(taskEvaluator, maxVisitedNodes);
        NativePathRequest request = new NativePathRequest(navigation, mob, evaluator.getClass(),
            targets, followRange, state.prts$reachRange(), state.prts$maxVisitedNodesMultiplier(),
            maxVisitedNodes, evaluator.canPassDoors(), evaluator.canOpenDoors(), evaluator.canFloat(),
            evaluator.canWalkOverFences(), regionState);
        return new Plan(request, region, finder, mob);
    }

    /** Runs the frozen search; null is the original "no path" answer and is staged as one. */
    public static Path search(Plan plan) {
        NativePathRequest request = plan.request();
        return plan.finder().findPath(plan.region(), plan.mob(), request.targets(),
            request.maxRange(), request.accuracy(), request.searchDepthMultiplier());
    }

    /** The effective target of a ground navigation: the same ground adjustment the walk navigation
     * applies before it enters the region search, read through the same chunk the navigation uses.
     * A null answer means the target chunk is not loaded and the navigation would not search. */
    private static Set<BlockPos> groundTargets(ServerLevel level, BlockPos target) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(
            SectionPos.blockToSectionCoord(target.getX()), SectionPos.blockToSectionCoord(target.getZ()));
        if (chunk == null) {
            return null;
        }
        BlockPos pos = target;
        if (chunk.getBlockState(pos).isAir()) {
            BlockPos below = pos.below();
            while (below.getY() > level.getMinBuildHeight() && chunk.getBlockState(below).isAir()) {
                below = below.below();
            }
            if (below.getY() > level.getMinBuildHeight()) {
                return Set.of(below.above());
            }
            while (below.getY() < level.getMaxBuildHeight() && chunk.getBlockState(below).isAir()) {
                below = below.above();
            }
            pos = below;
        }
        if (!chunk.getBlockState(pos).isSolid()) {
            return Set.of(pos);
        }
        BlockPos above = pos.above();
        while (above.getY() < level.getMaxBuildHeight() && chunk.getBlockState(above).isSolid()) {
            above = above.above();
        }
        return Set.of(above);
    }
}
