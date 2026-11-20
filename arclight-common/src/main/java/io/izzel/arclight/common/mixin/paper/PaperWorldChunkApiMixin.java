/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.mixin.paper;

import io.izzel.arclight.common.paper.PaperChunkLoads;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.v.CraftWorld;
import org.spongepowered.asm.mixin.Mixin;

import java.util.concurrent.CompletableFuture;

@Mixin(value = World.class, remap = false)
public interface PaperWorldChunkApiMixin {
    // Adds the async chunk lookups on World.
    // Plugins call them on the world, so a helper type would not match the call.
    // World is the type plugins hold, so the methods are injected there.
    // The two-argument form starts generation. A world that is not a CraftWorld completes with null.

    /** Returns the chunk at these coordinates, generating it when it is not loaded. */
    default CompletableFuture<Chunk> getChunkAtAsync(int x, int z) {
        return this.getChunkAtAsync(x, z, true);
    }

    /** Returns the chunk at these coordinates, generating it only when asked. */
    default CompletableFuture<Chunk> getChunkAtAsync(int x, int z, boolean generate) {
        if (!(this instanceof CraftWorld world)) {
            return CompletableFuture.completedFuture(null);
        }
        ServerLevel level = world.getHandle();
        ServerChunkCache cache = level.getChunkSource();
        return PaperChunkLoads.bukkitChunk(
            cache.getChunkFuture(x, z, ChunkStatus.FULL, PaperChunkLoads.generationFlag(generate)));
    }
}
