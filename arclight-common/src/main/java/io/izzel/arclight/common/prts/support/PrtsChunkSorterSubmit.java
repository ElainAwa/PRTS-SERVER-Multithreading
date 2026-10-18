/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Where the sorter takes a chunk pipeline message: mint, wrap, cancel. */
package io.izzel.arclight.common.prts.support;

import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;

import java.util.function.Function;

/** The identity a submit carries: an unplaced mailbox or a kernel with no sink mints nothing. */
public final class PrtsChunkSorterSubmit {

    private PrtsChunkSorterSubmit() {
    }

    /** Mints the identity of one submit and wraps the function the sorter will enqueue. */
    public static <T> Function<ProcessorHandle<Unit>, T> wrap(ProcessorHandle<T> processor,
                                                              Function<ProcessorHandle<Unit>, T>
                                                                  task,
                                                              long cell) {
        return wrap(null, processor, task, cell);
    }

    /** The same wrap with the sorter object the submit came through: a sorter the placement walk
     * classified as non-target never mints, even while a sink is installed. */
    public static <T> Function<ProcessorHandle<Unit>, T> wrap(Object sorter,
                                                              ProcessorHandle<T> processor,
                                                              Function<ProcessorHandle<Unit>, T>
                                                                  task,
                                                              long cell) {
        if (PrtsPipelineRows.isNonTargetSorter(sorter)) {
            return task;
        }
        if (!PrtsPipelineBatches.installed()) {
            return task;
        }
        String world = PrtsPipelineRows.worldOf(processor);
        if (PrtsPipelineRows.UNPLACED_WORLD.equals(world)) {
            return task;
        }
        Object minted = PrtsPipelineBatches.mint(processor, world, processor.name(), cell);
        return PrtsPipelineBatches.wrap(minted, task);
    }

    /** Marks the tasks one sorter queue clears as cancelled; the task body still decides. */
    public static void release(ProcessorHandle<?> processor, long cell, boolean clearQueue) {
        if (clearQueue) {
            PrtsPipelineBatches.released(processor, cell, true);
        }
    }
}
