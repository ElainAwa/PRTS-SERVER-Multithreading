/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.pipeline;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsChunkMaterialization;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatusTasks;
import net.minecraft.world.level.chunk.status.ChunkStep;
import net.minecraft.world.level.chunk.status.WorldGenContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Reports one completed chunk materialization at the full step's own completion, the one place
 * both a generated chunk and a chunk read back from storage pass through: a stored chunk never
 * swaps a proto chunk, so the swap alone would miss it. World, position and generation are read
 * from the step's own arguments and its holder. */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ChunkStatusTasks.class)
public abstract class PrtsChunkMaterializationMixin {

    @Inject(method = "full", at = @At("RETURN"))
    private static void prts$countMaterializedChunk(WorldGenContext context, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache, ChunkAccess chunk,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!PrtsChunkMaterialization.installed()) {
            return;
        }
        String worldId = context.level().dimension().location().toString();
        long chunkPos = chunk.getPos().toLong();
        cir.getReturnValue().whenComplete((materialized, failure) -> {
            if (failure != null || materialized == null) {
                return;
            }
            GenerationChunkHolder holder = cache.get(chunk.getPos().x, chunk.getPos().z);
            PrtsChunkMaterialization.materialized(PrtsChunkMaterialization.nextTicket(), worldId,
                chunkPos, PrtsChunkMaterialization.STATUS_FULL,
                holder == null ? 0 : holder.getGenerationRefCount());
        });
    }
}
