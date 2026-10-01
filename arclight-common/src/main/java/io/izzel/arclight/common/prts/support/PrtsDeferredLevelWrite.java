/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The write a level block edit is handed over as: the same setter call, run again later on the
 * thread that drives the tick. It lives beside the seam rather than in the package the mixins are
 * declared in, because a class in a mixin package that is not itself a mixin cannot be referenced.
 */
package io.izzel.arclight.common.prts.support;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** One level block write, deferred until the commit segment reaches it. */
public final class PrtsDeferredLevelWrite implements PrtsWorldWriteTaps.DeferredWrite {

    private final Level level;
    private final BlockPos pos;
    private final BlockState state;
    private final int flags;
    private final int recursionLeft;

    /**
     * Creates the deferred write.
     *
     * @param level         the level the write targets
     * @param pos           the position the write targets
     * @param state         the state to write
     * @param flags         the update flags of the original call
     * @param recursionLeft the recursion budget of the original call
     */
    public PrtsDeferredLevelWrite(Level level, BlockPos pos, BlockState state, int flags,
                                  int recursionLeft) {
        this.level = level;
        this.pos = pos;
        this.state = state;
        this.flags = flags;
        this.recursionLeft = recursionLeft;
    }

    @Override
    public boolean apply() {
        level.setBlock(pos, state, flags, recursionLeft);
        return true;
    }
}
