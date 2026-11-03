/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Reports the real completion of the generation/light step future; it changes no future or order. */
package io.izzel.arclight.common.prts.fixes.pipeline;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.observe.ChunkPipelineProgress;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.GenerationChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.StaticCache2D;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.status.ChunkStep;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** Reports one step's completion to the internal per-world progress face. The returned future is
 * left in place; the callback is attached as an observation and never rethrows into the future.
 * A generation step counts as gen progress; the initialize-light and light steps count as light
 * progress. */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ChunkMap.class)
public abstract class PrtsChunkPipelineProgressMixin {

    @Shadow
    @Final
    public ServerLevel level;

    @Inject(method = "applyStep", at = @At("RETURN"), require = 1)
    private void prts$observeStep(GenerationChunkHolder holder, ChunkStep step,
            StaticCache2D<GenerationChunkHolder> cache,
            CallbackInfoReturnable<CompletableFuture<ChunkAccess>> cir) {
        if (!ChunkPipelineProgress.installed()) {
            return;
        }
        CompletableFuture<ChunkAccess> future = cir.getReturnValue();
        if (future == null) {
            return;
        }
        String worldId = this.level.dimension().location().toString();
        long epoch = ChunkPipelineProgress.epochOf(worldId);
        long ticket = ChunkPipelineProgress.nextTicket();
        long chunkPos = holder.getPos().toLong();
        boolean light = step.targetStatus() == ChunkStatus.INITIALIZE_LIGHT
            || step.targetStatus() == ChunkStatus.LIGHT;
        try {
            future.whenComplete((result, failure) -> {
                try {
                    ChunkPipelineProgress.note(ticket, worldId, epoch, chunkPos, light, failure,
                        result != null);
                } catch (Throwable ignored) {
                    // observation must never reach the future's dependent stage
                }
            });
        } catch (Throwable ignored) {
            // an observation that cannot be attached does not change the host's future
        }
    }
}
