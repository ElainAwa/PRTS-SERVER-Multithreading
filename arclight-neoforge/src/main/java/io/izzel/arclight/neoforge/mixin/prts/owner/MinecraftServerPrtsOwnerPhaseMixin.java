/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.mixin.prts.owner;

import io.izzel.arclight.common.prts.kernel.domain.entity.owner.ServerThreadOwnerPhase;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Behaviour: the stop path does not begin its save and unload while a stopped batch's body may
 * still touch shared state. Why here: this entry precedes the final save, unlike the save event.
 * Invariant: bounded wait with host demand serviced; a body still running aborts the stop instead of
 * handing the world over. Failure: the stop fails instead of pretending it stopped safely. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerPrtsOwnerPhaseMixin {

    @Inject(method = "stopServer", at = @At("HEAD"))
    private void prts$stopFence(CallbackInfo ci) {
        if (!ServerThreadOwnerPhase.awaitInFlightOnStop(10_000L)) {
            throw new IllegalStateException(
                "owner batch bodies are still running; refusing to stop the server");
        }
    }
}
