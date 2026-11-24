/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.pipeline;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Reaches the visible chunk holder from the owner read path: the cache's getChunkNow refuses every
 * non-main thread, so the read-only holder lookup has to be called through this accessor. */
@Mixin(ChunkMap.class)
public interface PrtsChunkMapAccessor {

    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder prts$visibleChunk(long chunkPos);
}
