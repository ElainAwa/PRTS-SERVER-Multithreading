/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Counts the two ends of a chunk pipeline mailbox: what is handed to it and what it takes out. The
 * counts and the depth are read only while the load probe is installed, and no item is cancelled,
 * delayed or reordered by this carrier. */
package io.izzel.arclight.common.prts.fixes.pipeline;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsChunkFlow;
import net.minecraft.util.thread.ProcessorMailbox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reports the mailbox flow of the chunk pipeline; the queue size is the mailbox's own, read at the
 * two points where it changes, so no second model of the queue can drift from it. */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ProcessorMailbox.class)
public abstract class PrtsChunkFlowMailboxMixin {

    @Shadow
    public abstract String name();

    @Shadow
    public abstract int size();

    @Inject(method = "tell", at = @At("HEAD"))
    private void prts$countSubmission(Object task, CallbackInfo ci) {
        if (PrtsChunkFlow.installed()) {
            PrtsChunkFlow.submitted(name(), size());
        }
    }

    @Inject(method = "pollTask", at = @At("RETURN"))
    private void prts$countCompletion(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            PrtsChunkFlow.completed(name(), size());
        }
    }
}
