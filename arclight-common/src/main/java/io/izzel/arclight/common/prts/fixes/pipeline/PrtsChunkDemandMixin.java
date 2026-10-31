/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Counts the demand side of the chunk cache: which chunk was asked for, whether the ask was
 * satisfied, and the futures it handed out with the moment each one completes. No ask is cancelled,
 * delayed or reordered; every count is read only while the demand tap is installed. */
package io.izzel.arclight.common.prts.fixes.pipeline;

import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalLongRef;
import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.observe.UnloadedReadReadings;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import io.izzel.arclight.common.prts.support.PrtsPipelineBatches;
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
 * world comes from the cache's own level field, so a count can never be booked to the wrong one, and
 * the chunk each ask named is handed on with it: both coordinates are parameters the cache itself
 * turns into the chunk position it schedules, so the report needs no second lookup of the world. */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ServerChunkCache.class)
public abstract class PrtsChunkDemandMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    @Final
    private Thread mainThread;

    private String prts$worldId() {
        return level.dimension().location().toString();
    }

    @Inject(method = "getChunk", at = @At("HEAD"))
    private void prts$demandOpened(int x, int z, ChunkStatus status, boolean blocking,
                                   CallbackInfoReturnable<ChunkAccess> cir) {
        if (blocking && Thread.currentThread() == mainThread) {
            ServerChunkCache cache = (ServerChunkCache) (Object) this;
            if (UnloadedReadReadings.shouldCount(true, true, cache.getChunkNow(x, z) != null)) {
                UnloadedReadReadings.noteColdLoad();
            }
        }
        if (PrtsChunkDemand.installed()) {
            PrtsChunkDemand.demandStarted(prts$worldId(), x, z, status.getName(), blocking);
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

    // The call's own name travels in a share: MixinExtras hands both handlers of one invocation the
    // same carrier, so two asks of one world and status keep their own name.
    @Inject(method = "getChunkFuture", at = @At("HEAD"))
    private void prts$futureOpened(int x, int z, ChunkStatus status, boolean load,
                                   CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir,
                                   @Share("prts$callToken") LocalLongRef callToken) {
        if (PrtsChunkDemand.installed()) {
            // A frame that never reaches this line reads the carrier's own start: no name.
            callToken.set(PrtsChunkDemand.demandDescriptorToken(prts$worldId(), x, z,
                status.getName(), load, PrtsPipelineBatches.currentTask()));
        }
    }

    @Inject(method = "getChunkFuture", at = @At("RETURN"))
    private void prts$futureTaken(int x, int z, ChunkStatus status, boolean load,
                                  CallbackInfoReturnable<CompletableFuture<ChunkResult<ChunkAccess>>> cir,
                                  @Share("prts$callToken") LocalLongRef callToken) {
        if (!PrtsChunkDemand.installed()) {
            return;
        }
        long token = callToken.get();
        long startedAt = PrtsChunkDemand.futureOpenedAt();
        // The early exit that was always here; a call with a stamp but no name still walks the chain.
        if (startedAt < 0L) {
            return;
        }
        CompletableFuture<ChunkResult<ChunkAccess>> future = cir.getReturnValue();
        String worldId = prts$worldId();
        String statusName = status.getName();
        PrtsChunkDemand.futureTaken(worldId, statusName, token);
        try {
            future.whenComplete((result, failure) -> PrtsChunkDemand.futureCompleted(worldId, statusName,
                startedAt, failure == null && result != null && result.isSuccess(), token));
        } catch (Throwable thrown) {
            // Told that the registration failed: nothing is promoted, and the error the host call
            // would have seen keeps travelling exactly as before this stage existed.
            PrtsChunkDemand.registrationConfirmed(worldId, statusName, token, false);
            throw thrown;
        }
        PrtsChunkDemand.registrationConfirmed(worldId, statusName, token, true);
    }
}
