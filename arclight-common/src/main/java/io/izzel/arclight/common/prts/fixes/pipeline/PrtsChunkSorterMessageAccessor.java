/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The three fields of one sorter message, readable without reflection. */
package io.izzel.arclight.common.prts.fixes.pipeline;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import net.minecraft.server.level.ChunkTaskPriorityQueueSorter;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Function;
import java.util.function.IntSupplier;

/** Reads the task, its cell and its level from the message the sorter was handed. */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ChunkTaskPriorityQueueSorter.Message.class)
public interface PrtsChunkSorterMessageAccessor {

    @Accessor("task")
    Function<ProcessorHandle<Unit>, ?> task();

    @Accessor("pos")
    long pos();

    @Accessor("level")
    IntSupplier level();
}
