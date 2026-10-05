/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Counts the demand side of the chunk cache: what was asked of it, whether the ask was satisfied,
 * and the futures it handed out with the moment each one completes. No ask is cancelled, delayed or
 * reordered; every count is read only while the demand tap is installed. */
package io.izzel.arclight.common.prts.fixes.pipeline;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;

/** The two entries of the cache that answer a chunk request: the synchronous ask and the future. The
 * world comes from the cache's own level field, so a count can never be booked to the wrong one. */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ServerChunkCache.class)
public abstract class PrtsChunkDemandMixin {

    @Shadow
    @Final
    private ServerLevel level;

    private String prts$worldId() {
        return level.dimension().location().toString();
    }

    @Inject(method = "getChunk", at = @At("HEAD"))
    private void prts$demandOpened(int x, int z, ChunkStatus status, boolean blocking,
                                   CallbackInfoReturnable<ChunkAccess> cir) {
        if (PrtsChunkDemand.installed()) {
            PrtsChunkDemand.demandStarted(prts$worldId(), status.getName(), blocking);
        }
    }

    @Inject(method = "getChunk", at = @At("RETURN"))
    private void prts$demandClosed(int x, int z, ChunkStatus status, boolean blocking,
                                   CallbackInfoReturnable<ChunkAccess> cir) {
        if (!PrtsChunkDemand.installed()) {
            return;
        }
        ChunkAccess chunk = cir.getReturnValue();
        boolean satisfied = chunk != null && chunk.getPersistedStatus().isOrAfter(status);
        PrtsChunkDemand.demandFinished(prts$worldId(), status.getName(), blocking, satisfied);
    }

    @Inject(method = "getChunkFuture", at = @At("HEAD"))
    private void prts$futureOpened(int x, int z, ChunkStatus status, boolean load,
                                   CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir) {
        if (PrtsChunkDemand.installed()) {
            PrtsChunkDemand.futureOpened(prts$worldId(), status.getName());
        }
    }

    @Inject(method = "getChunkFuture", at = @At("RETURN"))
    private void prts$futureTaken(int x, int z, ChunkStatus status, boolean load,
                                  CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir) {
        if (!PrtsChunkDemand.installed()) {
            return;
        }
        long startedAt = PrtsChunkDemand.futureOpenedAt();
        if (startedAt < 0L) {
            return;
        }
        CompletableFuture<ChunkResult<ChunkAccess>> future = cir.getReturnValue();
        String worldId = prts$worldId();
        String statusName = status.getName();
        PrtsChunkDemand.futureTaken(worldId, statusName);
        future.whenComplete((result, failure) -> PrtsChunkDemand.futureCompleted(worldId, statusName,
            startedAt, failure == null && result != null && result.isSuccess()));
    }
}
