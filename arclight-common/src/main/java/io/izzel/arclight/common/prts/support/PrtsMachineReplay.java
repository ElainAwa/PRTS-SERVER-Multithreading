/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import io.izzel.arclight.common.prts.kernel.quota.MachineTickDeferral;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/** Replays one deferred root neighbour update through the same public level method the tick called,
 * so the deferred call is the original call, only later in the same tick. */
public final class PrtsMachineReplay implements MachineTickDeferral.Replay {

    public static final PrtsMachineReplay INSTANCE = new PrtsMachineReplay();

    private PrtsMachineReplay() {
    }

    @Override
    public void update(Object level, BlockPos pos, Object block, Direction facing) {
        if (!(level instanceof ServerLevel serverLevel)) {
            throw new IllegalStateException("a deferred neighbour update needs its server level");
        }
        if (!(block instanceof Block source)) {
            throw new IllegalStateException("a deferred neighbour update needs its block");
        }
        if (facing == null) {
            serverLevel.updateNeighborsAt(pos, source);
        } else {
            serverLevel.updateNeighborsAtExceptFromFacing(pos, source, facing);
        }
    }
}
