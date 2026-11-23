/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.pathfinder.PathFinder;

/** The navigation state one read node freezes its search against; the game class reads these
 * through added accessors that change no field, no method and no decision of that class. */
public interface NativePathState {

    BlockPos prts$targetPos();

    int prts$reachRange();

    boolean prts$hasDelayedRecomputation();

    long prts$timeLastRecompute();

    float prts$maxVisitedNodesMultiplier();

    PathFinder prts$pathFinder();
}
