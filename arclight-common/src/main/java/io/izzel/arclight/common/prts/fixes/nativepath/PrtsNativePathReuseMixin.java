/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.nativepath;

import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathFinderState;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathRegionState;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathRequest;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathStage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

/**
 * Behaviour: assigns the staged path when a wave computed this exact search, else searches normally.
 * Why: the search is the wave's cost; the early return, region build and write-back keep their place.
 * Point: the one find call of the frozen request. Invariant: identical navigation, mob, targets,
 * range, accuracy, multiplier, cap, flags and chunk objects; failure: any mismatch searches. */
@Mixin(PathNavigation.class)
public abstract class PrtsNativePathReuseMixin {

    @Redirect(method = "createPath(Ljava/util/Set;IZIF)Lnet/minecraft/world/level/pathfinder/Path;",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/pathfinder/PathFinder;findPath"
                + "(Lnet/minecraft/world/level/PathNavigationRegion;"
                + "Lnet/minecraft/world/entity/Mob;Ljava/util/Set;FIF)"
                + "Lnet/minecraft/world/level/pathfinder/Path;"))
    private Path prts$reuseStagedPath(PathFinder finder, PathNavigationRegion region, Mob mob,
                                      Set<BlockPos> targets, float maxRange, int accuracy,
                                      float searchDepthMultiplier) {
        PathNavigation navigation = (PathNavigation) (Object) this;
        NodeEvaluator evaluator = navigation.getNodeEvaluator();
        int maxVisitedNodes = finder instanceof NativePathFinderState state
            ? state.prts$maxVisitedNodes() : Integer.MIN_VALUE;
        NativePathStage.Hit hit = NativePathStage.take(new NativePathRequest.Live(navigation, mob,
            evaluator == null ? Object.class : evaluator.getClass(),
            region instanceof NativePathRegionState state ? state : null,
            targets, maxRange, accuracy, searchDepthMultiplier, maxVisitedNodes,
            evaluator != null && evaluator.canPassDoors(),
            evaluator != null && evaluator.canOpenDoors(),
            evaluator != null && evaluator.canFloat(),
            evaluator != null && evaluator.canWalkOverFences()));
        if (hit.matched()) {
            return hit.path();
        }
        return finder.findPath(region, mob, targets, maxRange, accuracy, searchDepthMultiplier);
    }
}
