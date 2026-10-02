/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The two writes the world write seam hands over: the same setter call again, run later on the thread
 * that drives the tick. They live beside the seam because a class in a mixin package that is not
 * itself a mixin cannot be referenced.
 */
package io.izzel.arclight.common.prts.support;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.v.block.CraftBlock;

public final class PrtsDeferredWrites {

    private PrtsDeferredWrites() {
    }

    public static final class LevelWrite implements PrtsWorldWriteTaps.DeferredWrite {

        private final Level level;
        private final BlockPos pos;
        private final BlockState state;
        private final int flags;
        private final int recursionLeft;

        public LevelWrite(Level level, BlockPos pos, BlockState state, int flags, int recursionLeft) {
            this.level = level;
            // The caller keeps its own position object, mutable on many world-side paths, so the snapshot
            // is taken here: what is written later is the position the write was frozen at.
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

    public static final class PlatformWrite implements PrtsWorldWriteTaps.DeferredWrite {

        private final CraftBlock block;
        private final BlockData data;
        private final boolean applyPhysics;

        public PlatformWrite(CraftBlock block, BlockData data, boolean applyPhysics) {
            this.block = block;
            this.data = copyOf(data);
            this.applyPhysics = applyPhysics;
        }

        /** @return a copy of the data the caller handed over, so a later edit cannot change the write */
        public static BlockData copyOf(BlockData data) {
            return data.clone();
        }

        public BlockData data() {
            return data;
        }

        @Override
        public boolean apply() {
            block.setBlockData(data, applyPhysics);
            return true;
        }
    }
}
