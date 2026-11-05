/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/** The one deferred write the real Level setBlock path hands to the commit order. */
public final class PrtsDeferredWrites {

    private PrtsDeferredWrites() {
    }

    /** The same Level.setBlock call, replayed later against its frozen position and state. */
    public static final class LevelWrite implements PrtsWorldWriteTaps.DeferredWrite {

        private final Level level;
        private final BlockPos pos;
        private final BlockState state;
        private final int flags;
        private final int recursionLeft;

        public LevelWrite(Level level, BlockPos pos, BlockState state, int flags, int recursionLeft) {
            this.level = level;
            this.pos = pos.immutable();
            this.state = state;
            this.flags = flags;
            this.recursionLeft = recursionLeft;
        }

        public BlockPos position() {
            return pos;
        }

        @Override
        public boolean apply() {
            return level.setBlock(pos, state, flags, recursionLeft);
        }
    }
}
