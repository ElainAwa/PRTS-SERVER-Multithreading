/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Delays one send pass under a live parent-over budget; it keeps the host pending set untouched. */
package io.izzel.arclight.common.prts.fixes.pipeline;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.kernel.SendDelayAction;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Skips a player's send pass for this tick while the action says a fresh B01-A parent-over budget
 * is still present. It cancels the pass only; the pending set, batch quota, order and packets are
 * untouched and the next tick retries. */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(PlayerChunkSender.class)
public abstract class PrtsChunkSendDelayMixin {

    @Shadow
    @Final
    private LongSet pendingChunks;

    @Inject(method = "sendNextChunks", at = @At("HEAD"), cancellable = true, require = 1)
    private void prts$delayUnderBudget(ServerPlayer player, CallbackInfo ci) {
        if (player == null) {
            return;
        }
        try {
            String worldId = player.serverLevel().dimension().location().toString();
            if (SendDelayAction.shouldDefer(worldId, this.pendingChunks.size())) {
                ci.cancel();
            }
        } catch (Throwable ignored) {
            // fail open: an observer failure must never withhold the host send pass
        }
    }
}
