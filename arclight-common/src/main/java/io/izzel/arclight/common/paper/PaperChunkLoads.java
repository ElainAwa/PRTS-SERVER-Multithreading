/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.paper;

import io.izzel.arclight.common.bridge.core.world.level.chunk.LevelChunkBridge;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import org.bukkit.Chunk;

import java.util.concurrent.CompletableFuture;

/**
 * Turns a chunk future into a Bukkit chunk. The two-argument lookup starts generation.
 */
public final class PaperChunkLoads {

    private PaperChunkLoads() {
    }

    public static boolean generationFlag(boolean generate) {
        return generate;
    }

    /**
     * Turns one chunk future into a Bukkit chunk.
     * The completion stays on the chunk future's thread and is not moved to the server thread.
     */
    public static CompletableFuture<Chunk> bukkitChunk(CompletableFuture<ChunkResult<ChunkAccess>> existing) {
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
