/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.nativepath;

import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathState;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Behaviour: read-only accessors for the navigation state a read node freezes; no field or decision
 * changes. Why: a second derivation would be a second source. Point: the fields. Failure: reads only. */
@Mixin(PathNavigation.class)
public interface PrtsNativePathStateMixin extends NativePathState {

    @Accessor("targetPos")
    BlockPos prts$targetPos();

    @Accessor("reachRange")
    int prts$reachRange();

    @Accessor("hasDelayedRecomputation")
    boolean prts$hasDelayedRecomputation();

    @Accessor("timeLastRecompute")
    long prts$timeLastRecompute();

    @Accessor("maxVisitedNodesMultiplier")
    float prts$maxVisitedNodesMultiplier();

    @Accessor("pathFinder")
    PathFinder prts$pathFinder();
}
