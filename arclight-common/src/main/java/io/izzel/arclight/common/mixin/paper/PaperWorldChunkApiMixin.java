/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mixin.paper;

import io.izzel.arclight.common.bridge.core.world.level.chunk.LevelChunkBridge;
import io.izzel.arclight.common.paper.PaperChunkLoads;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.v.CraftWorld;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;

@Mixin(value = World.class, remap = false)
public interface PaperWorldChunkApiMixin {

    default CompletableFuture<Chunk> getChunkAtAsync(int x, int z) {
        if (!(this instanceof CraftWorld world)) {
            return CompletableFuture.completedFuture(null);
        }
        ServerLevel level = world.getHandle();
        ServerChunkCache cache = level.getChunkSource();
        LevelChunk loaded = cache.getChunkNow(x, z);
        Chunk alreadyLoaded = loaded == null ? null : ((LevelChunkBridge) loaded).bridge$getBukkitChunk();
        CompletableFuture<ChunkResult<ChunkAccess>> existing = loaded == null
            ? cache.getChunkFuture(x, z, ChunkStatus.FULL, PaperChunkLoads.START_GENERATION)
            : null;
        return PaperChunkLoads.withoutGenerating(alreadyLoaded, existing);
    }

    default CompletableFuture<Chunk> getChunkAtAsync(int x, int z, boolean generate) {
        if (!(this instanceof CraftWorld world)) {
            return CompletableFuture.completedFuture(null);
        }
        ServerLevel level = world.getHandle();
        ServerChunkCache cache = level.getChunkSource();
        LevelChunk loaded = cache.getChunkNow(x, z);
        Chunk alreadyLoaded = loaded == null ? null : ((LevelChunkBridge) loaded).bridge$getBukkitChunk();
        CompletableFuture<ChunkResult<ChunkAccess>> existing = loaded == null
            ? cache.getChunkFuture(x, z, ChunkStatus.FULL, PaperChunkLoads.generationFlag(generate))
            : null;
        return PaperChunkLoads.withoutGenerating(alreadyLoaded, existing);
    }
}
