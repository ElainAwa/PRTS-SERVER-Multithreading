/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The biome resend: it walks the chunks of the players it is called for and reads each one's biome
 * container, which has to be in memory.
 *
 * The hook opens an observation at the head of the method and closes it at the return, and that is
 * all it does: no upper bound is read into a decision, no wait is shortened, delayed or cancelled,
 * and the call proceeds exactly as it did before. With no watcher installed both calls are a single
 * volatile read each.
 */
package io.izzel.arclight.common.prts.fixes.waitpoints;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ChunkMap.class)
public abstract class PrtsChunkMapWaitSiteMixin {

    @Inject(method = "resendBiomesForChunks", at = @At("HEAD"))
    private void prts$openResendBiomesForChunks(List<ChunkAccess> chunks, CallbackInfo ci) {
        PrtsWaitSites.begin(PrtsWaitSites.CHUNK_MAP_RESEND_BIOMES_FOR_CHUNKS);
    }

    @Inject(method = "resendBiomesForChunks", at = @At("RETURN"))
    private void prts$closeResendBiomesForChunks(List<ChunkAccess> chunks, CallbackInfo ci) {
        PrtsWaitSites.end(PrtsWaitSites.CHUNK_MAP_RESEND_BIOMES_FOR_CHUNKS);
    }
}
