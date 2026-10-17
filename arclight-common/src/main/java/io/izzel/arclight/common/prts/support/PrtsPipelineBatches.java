/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The seam a chunk pipeline submit reports through; it stays a no-op while no sink is installed. */
package io.izzel.arclight.common.prts.support;

import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;

import java.util.function.Function;
import java.util.function.LongSupplier;

/** The tasks the chunk pipeline submits, handed to the kernel through opaque handles; with no sink
 * every entry is a no-op and the submitted function is handed back as it arrived. */
public final class PrtsPipelineBatches {

    /** A wrapped function that still names the task it carries. */
    public interface Tracked {

        Object task();
    }

    /** The kernel face of one tracked submit: mint, wrap, enqueue and cancel, all on opaque handles. */
    public interface TaskSink {

        Object mint(Object processor, String worldId, String mailbox, long cell, long tick);

        <T> Function<ProcessorHandle<Unit>, T> wrap(Object task,
                                                    Function<ProcessorHandle<Unit>, T> delegate);

        void enqueued(Object task);

        void released(Object processor, long cell, boolean clearQueue);
    }

    private static volatile TaskSink sink;
    private static volatile LongSupplier tick = () -> 0L;

    private PrtsPipelineBatches() {
    }

    /** Installs the sink the sorter side reports to, and the tick its mints are stamped with. */
    public static void install(TaskSink installed, LongSupplier tickSource) {
        tick = tickSource == null ? () -> 0L : tickSource;
        sink = installed;
    }

    public static void uninstall() {
        sink = null;
    }

    public static boolean installed() {
        return sink != null;
    }

    public static long tick() {
        return tick.getAsLong();
    }

    /** Mints one tracked submit, or answers null while no sink is installed. */
    public static Object mint(Object processor, String worldId, String mailbox, long cell) {
        TaskSink current = sink;
        return current == null ? null
            : current.mint(processor, worldId, mailbox, cell, tick.getAsLong());
    }

    /** Wraps one submitted function around the tracked task, or hands the function back unchanged. */
    public static <T> Function<ProcessorHandle<Unit>, T> wrap(Object task,
                                                              Function<ProcessorHandle<Unit>, T>
                                                                  delegate) {
        TaskSink current = sink;
        return current == null || task == null ? delegate : current.wrap(task, delegate);
    }

    /** Reports that the sorter put one wrapped function into its queue; an untracked one is ignored. */
    public static void enqueued(Function<?, ?> function) {
        if (!(function instanceof Tracked tracked)) {
            return;
        }
        TaskSink current = sink;
        if (current != null) {
            current.enqueued(tracked.task());
        }
    }

    /** Reports a sorter queue release; only a queue-clearing release can cancel a resident task. */
    public static void released(Object processor, long cell, boolean clearQueue) {
        TaskSink current = sink;
        if (current != null) {
            current.released(processor, cell, clearQueue);
        }
    }
}
