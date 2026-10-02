/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

/**
 * Wraps the block reader of a tree search so it cannot leave the loaded world: an unbounded walk over a
 * chunk that is not loaded would pull a whole ring of chunks onto the server thread, so the wrapper
 * answers air outside the loaded world. Optional lookup and wall-clock budgets can end a search the
 * same way; both are off by default, because truncating a cut changes what the mod does.
 */
public final class PrtsTreeCutterGuard {

    private PrtsTreeCutterGuard() {
    }

    /**
     * Wraps a reader for one tree search, or hands it back when nothing is switched on.
     */
    public static BlockGetter guard(BlockGetter reader) {
        boolean bounds = PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT,
            "guard-create-tree-cutter-bounds");
        int nodeBudget = PrtsConfigManager.number(PrtsConfigManager.MODSUPPORT, "tree-cutter-node-budget");
        int timeBudgetMs = PrtsConfigManager.number(PrtsConfigManager.MODSUPPORT, "tree-cutter-time-budget-ms");
        if (!bounds && nodeBudget <= 0 && timeBudgetMs <= 0) {
            return reader;
        }
        return new Guarded(reader, bounds, nodeBudget, timeBudgetMs * 1_000_000L);
    }

    /** One reader for one search: it owns the budget of that search and is used by one thread. */
    private static final class Guarded implements BlockGetter {

        private final BlockGetter delegate;
        private final boolean bounds;
        private final int nodeBudget;
        private final long timeBudgetNanos;
        private final long startedAt = System.nanoTime();
        private int nodes;
        private boolean truncated;

        private Guarded(BlockGetter delegate, boolean bounds, int nodeBudget, long timeBudgetNanos) {
            this.delegate = delegate;
            this.bounds = bounds;
            this.nodeBudget = nodeBudget;
            this.timeBudgetNanos = timeBudgetNanos;
        }

        @Override
        public BlockState getBlockState(BlockPos pos) {
            if (this.truncated) {
                return Blocks.VOID_AIR.defaultBlockState();
            }
            if (this.bounds && this.delegate instanceof Level level && !level.isLoaded(pos)) {
                PrtsModSupportStats.count("tree-cutter-out-of-bounds-reads");
                return Blocks.VOID_AIR.defaultBlockState();
            }
            if (this.timeBudgetNanos > 0L && System.nanoTime() - this.startedAt >= this.timeBudgetNanos) {
                PrtsModSupportStats.count("tree-cutter-searches-truncated");
                this.truncated = true;
                return Blocks.VOID_AIR.defaultBlockState();
            }
            if (this.nodeBudget > 0 && ++this.nodes > this.nodeBudget) {
                PrtsModSupportStats.count("tree-cutter-searches-truncated");
                this.truncated = true;
                return Blocks.VOID_AIR.defaultBlockState();
            }
            return this.delegate.getBlockState(pos);
        }

        @Override
        public BlockEntity getBlockEntity(BlockPos pos) {
            return this.delegate.getBlockEntity(pos);
        }

        @Override
        public FluidState getFluidState(BlockPos pos) {
            return this.delegate.getFluidState(pos);
        }

        @Override
        public int getHeight() {
            return this.delegate.getHeight();
        }

        @Override
        public int getMinBuildHeight() {
            return this.delegate.getMinBuildHeight();
        }
    }
}
