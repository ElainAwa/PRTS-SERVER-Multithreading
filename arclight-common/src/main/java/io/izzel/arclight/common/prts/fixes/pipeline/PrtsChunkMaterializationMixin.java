/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Reports a completed chunk materialization: the moment the pipeline's full generation step hands
 * back the level chunk the world will tick. Mailbox items, demand futures and plan builds all run
 * on either side of that moment and none of them answers whether a chunk became materialized, so
 * the report is taken at the step's own completion, whose behaviour is unchanged. */
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

/** A chunk reports itself materialized once, when its full step completes, which is the one place
 * both a generated chunk and a chunk read back from storage pass through: a chunk that was already
 * a level chunk never swaps a proto chunk, so the swap alone would miss every stored chunk. The
 * world and the position come from the step's own arguments and the generation cycle from the
 * holder the step was given, so a count cannot be booked to a world the chunk is not in. */
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
