/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.pipeline;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsChunkSorterSubmit;
import io.izzel.arclight.common.prts.support.PrtsPipelineBatches;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Function;
import java.util.function.IntSupplier;

/** Gives a task submitted to the chunk sorter the identity of the submit it came from.
 *
 * <p>The sorter is the only call that holds the function, its cell and its processor together. The
 * submit call is redirected because the wrapped function must be the one the queue holds; only a
 * queue-clearing release may cancel a resident task, so the release lambda is observed at its head.
 * Every point requires one match, so a mapping drift fails the launch. Untracked tasks and unplaced
 * mailboxes are submitted exactly as they arrived.
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ChunkTaskPriorityQueueSorter.class)
public abstract class PrtsChunkSorterSubmitMixin {

    @Shadow
    private <T> void submit(ProcessorHandle<T> processor,
                            Function<ProcessorHandle<Unit>, T> task, long chunkPos,
                            IntSupplier level, boolean flush) {
        throw new AssertionError();
    }

    @Redirect(method = "lambda$getProcessor$3", require = 1, at = @At(value = "INVOKE",
        target = "Lnet/minecraft/server/level/ChunkTaskPriorityQueueSorter;submit"
            + "(Lnet/minecraft/util/thread/ProcessorHandle;Ljava/util/function/Function;J"
            + "Ljava/util/function/IntSupplier;Z)V"))
    private <T> void prts$submitWithIdentity(ChunkTaskPriorityQueueSorter sorter,
                                             ProcessorHandle<T> processor,
                                             Function<ProcessorHandle<Unit>, T> task,
                                             long chunkPos, IntSupplier level, boolean flush) {
        this.submit(processor, PrtsChunkSorterSubmit.wrap(processor, task, chunkPos), chunkPos,
            level, flush);
    }

    @Inject(method = "lambda$submit$12", require = 1, at = @At("HEAD"))
    private void prts$markResident(ProcessorHandle<?> processor, IntSupplier level, long chunkPos,
                                   Function<ProcessorHandle<Unit>, ?> task, boolean flush,
                                   CallbackInfo ci) {
        PrtsPipelineBatches.enqueued(task);
    }

    @Inject(method = "lambda$release$11", require = 1, at = @At("HEAD"))
    private void prts$releaseClearsQueue(ProcessorHandle<?> processor, long chunkPos,
                                         boolean clearQueue, Runnable task, CallbackInfo ci) {
        PrtsChunkSorterSubmit.release(processor, chunkPos, clearQueue);
    }
}
