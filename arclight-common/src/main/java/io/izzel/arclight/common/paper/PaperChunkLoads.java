/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.paper;

import io.izzel.arclight.common.bridge.core.world.level.chunk.LevelChunkBridge;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Chunk;

import java.util.concurrent.CompletableFuture;

/**
 * Joins a chunk the server already has. The two-argument lookup does not start generation.
 */
public final class PaperChunkLoads {

    public static final boolean START_GENERATION = false;

    private PaperChunkLoads() {
    }

    public static boolean generationFlag(boolean generate) {
        return generate ? true : START_GENERATION;
    }

    public static CompletableFuture<Chunk> withoutGenerating(Chunk alreadyLoaded,
                                                             CompletableFuture<ChunkResult<ChunkAccess>> existing) {
        if (alreadyLoaded != null) {
            return CompletableFuture.completedFuture(alreadyLoaded);
        }
        if (existing == null) {
            return CompletableFuture.completedFuture(null);
        }
        return existing.thenApply(PaperChunkLoads::bukkitChunkOrNull);
    }

    private static Chunk bukkitChunkOrNull(ChunkResult<ChunkAccess> result) {
        if (result == null || !result.isSuccess()) {
            return null;
        }
        ChunkAccess access = result.orElse(null);
        if (!(access instanceof LevelChunk chunk)) {
            return null;
        }
        return ((LevelChunkBridge) chunk).bridge$getBukkitChunk();
    }
}
