/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.nativepath;

import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeEntityPhase;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathRequest;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathStage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Set;

/**
 * Behaviour: assigns the staged path, or in shadow recomputes it inline and returns the inline answer;
 * the one find call of the frozen request is the injection point. Why: the search is the wave's cost,
 * shadow must prove the key before publishing, and the early return, region build and write-back stay.
 * Invariant: same navigation, mob, targets, range, accuracy, multiplier, cap, flags, finder, chunks and
 * pose; an ordinary prediction is one-shot, a delayed recomputation drops it, and a mismatch searches.
 */
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
        if (!NativePathStage.bound()) {
            return finder.findPath(region, mob, targets, maxRange, accuracy, searchDepthMultiplier);
        }
        PathNavigation navigation = (PathNavigation) (Object) this;
        NativePathStage.Hit hit = NativePathStage.take(NativePathRequest.Live.of(navigation, mob,
            region, finder, targets, maxRange, accuracy, searchDepthMultiplier));
        if (!hit.matched()) {
            return finder.findPath(region, mob, targets, maxRange, accuracy, searchDepthMultiplier);
        }
        if (NativeEntityPhase.mode() == NativeEntityPhase.Mode.SHADOW) {
            Path inline = finder.findPath(region, mob, targets, maxRange, accuracy,
                searchDepthMultiplier);
            NativePathStage.recordComparison(hit, inline);
            return inline;
        }
        return hit.path();
    }
}
