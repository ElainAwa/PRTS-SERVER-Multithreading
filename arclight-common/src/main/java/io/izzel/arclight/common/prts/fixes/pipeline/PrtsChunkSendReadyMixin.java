/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Counts the host's real send-ready notification; it changes no send, order or future. */
package io.izzel.arclight.common.prts.fixes.pipeline;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.observe.ChunkPipelineProgress;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Observes the point where the host has already decided a chunk is ready to send. The callback
 * only counts the transition; it does not change the send path, its order or any future. */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ChunkMap.class)
public abstract class PrtsChunkSendReadyMixin {

    @Shadow
    @Final
    public ServerLevel level;

    @Inject(method = "onChunkReadyToSend", at = @At("HEAD"), require = 1)
    private void prts$observeSendReady(LevelChunk chunk, CallbackInfo ci) {
        if (!ChunkPipelineProgress.installed()) {
            return;
        }
        try {
            String worldId = this.level.dimension().location().toString();
            long epoch = ChunkPipelineProgress.epochOf(worldId);
            ChunkPipelineProgress.noteSend(worldId, epoch, chunk.getPos().toLong());
        } catch (Throwable ignored) {
            // observation must not disturb the host's send-ready notification
        }
    }
}
