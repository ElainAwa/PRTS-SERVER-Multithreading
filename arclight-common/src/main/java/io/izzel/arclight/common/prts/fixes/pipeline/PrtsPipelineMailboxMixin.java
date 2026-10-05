/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Counts the chunk pipeline's own work items where the pipeline runs them. */
package io.izzel.arclight.common.prts.fixes.pipeline;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import net.minecraft.util.thread.ProcessorMailbox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Counts an executed task and a drain round of the chunk pipeline's own mailboxes: nothing is
 * cancelled, delayed or reordered, and the count is a counter update while a watcher is installed. */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ProcessorMailbox.class)
public abstract class PrtsPipelineMailboxMixin {

    @Shadow
    public abstract String name();

    /** One mailbox runs at most one task at a time, so one start stamp per mailbox is enough. */
    @Unique
    private long prts$taskStartedAt;

    @Inject(method = "pollTask", at = @At("HEAD"))
    private void prts$startPipelineTask(CallbackInfoReturnable<Boolean> cir) {
        if (PrtsPipelineRows.installed()) {
            prts$taskStartedAt = System.nanoTime();
        }
    }

    @Inject(method = "pollTask", at = @At("RETURN"))
    private void prts$countPipelineTask(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            PrtsPipelineRows.task(name(), System.nanoTime() - prts$taskStartedAt);
        }
    }

    @Inject(method = "run", at = @At("RETURN"))
    private void prts$countPipelineRound(CallbackInfo ci) {
        PrtsPipelineRows.round(name());
    }
}
