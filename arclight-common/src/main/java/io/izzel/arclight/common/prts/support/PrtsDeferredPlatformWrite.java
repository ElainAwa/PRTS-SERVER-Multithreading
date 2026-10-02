/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The write a platform block edit is handed over as: the same data setter, run again later on the
 * thread that drives the tick. It lives beside the seam rather than in the package the mixins are
 * declared in, because a class in a mixin package that is not itself a mixin cannot be referenced.
 * The second call takes the short path of the same hook - it runs on the thread that owns the
 * world - so a handed-over write cannot be handed over a second time.
 */
package io.izzel.arclight.common.prts.support;

import org.bukkit.block.data.BlockData;
import org.bukkit.craftbukkit.v.block.CraftBlock;

/** One platform block write, deferred until the commit segment reaches it. */
public final class PrtsDeferredPlatformWrite implements PrtsWorldWriteTaps.DeferredWrite {

    private final CraftBlock block;
    private final BlockData data;
    private final boolean applyPhysics;

    /**
     * Creates the deferred write.
     *
     * @param block        the block the write targets
     * @param data         the data to write
     * @param applyPhysics whether the original call wanted physics
     */
    public PrtsDeferredPlatformWrite(CraftBlock block, BlockData data, boolean applyPhysics) {
        this.block = block;
        this.data = copyOf(data);
        this.applyPhysics = applyPhysics;
    }

    /**
     * Copies the data a platform write will perform.
     *
     * <p>A caller that reuses or edits its {@code BlockData} after handing the write over must not
     * change what the commit segment writes, so the deferred write keeps a copy of its own.</p>
     *
     * @param data the data the caller handed over
     * @return a copy of it
     */
    public static BlockData copyOf(BlockData data) {
        return data.clone();
    }

    /** @return the copy of the data this write will perform */
    public BlockData data() {
        return data;
    }

    @Override
    public boolean apply() {
        block.setBlockData(data, applyPhysics);
        return true;
    }
}
