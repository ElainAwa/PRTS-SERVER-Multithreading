/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.jobs.DemandShadowCandidates.TaskStatus;

/** One chunk pipeline body's run-start window: it reads the status of its task once, joined to that
 * task and batch, refuses every later read, and carries no status it did not read. */
public final class PipelineRunSeal {

    /** Reads the one status of the task the window covers. */
    public interface StatusSource {

        TaskStatus statusFor(Object taskToken);
    }

    /** Reads the one range seal of a run start; a sink that refuses, throws or answers null means the
     * window carries no range and never changes the status answer. */
    public interface RangeSink {

        /** The table generation the seal is published under, read when the window opens. */
        long generation();

        /** The seal of this run, or null when no range can be published for it. */
        FrozenDeclarations.RangeSeal seal(PipelineBatchLedger.TrackedTask task, TaskStatus status,
                                          long generation);

        /** Ends the window of one published seal; an obligation the seal still owes keeps it. */
        default void release(FrozenDeclarations.RangeSeal seal) {
        }
    }

    private static final ThreadLocal<PipelineRunSeal> CURRENT = new ThreadLocal<>();
    private static final TaskStatus EMPTY = new TaskStatus("", null);
    private static volatile StatusSource source;
    private static volatile RangeSink rangeSource;

    private final PipelineBatchLedger.TrackedTask task;
    private final StatusSource installed;
    private final RangeSink installedRanges;
    private final long rangeGeneration;
    private final PipelineRunSeal previous;
    private TaskStatus early = EMPTY;
    private TaskStatus sealed;
    private FrozenDeclarations.RangeSeal range;
    private boolean rangeRead;
    private boolean read;
    private boolean closed;

    private PipelineRunSeal(PipelineBatchLedger.TrackedTask task, StatusSource source,
                            PipelineRunSeal previous) {
        this.task = task;
        this.installed = source;
        this.previous = previous;
        this.early = read(source, task);
        RangeSink ranges = rangeSource;
        this.installedRanges = ranges;
        this.rangeGeneration = ranges == null ? 0L : ranges.generation();
    }

    /** Names the register every later window reads; a null source opens no window. */
    public static void install(StatusSource installed) {
        source = installed;
    }

    /** Names the range sink every later window reads; a null sink seals no range. */
    public static void installRange(RangeSink installed) {
        rangeSource = installed;
    }

    /** Opens the run-start window of one task body, or null when no register is installed; the
     * run-start read keeps a present answer and leaves an absent one to the body. */
    public static PipelineRunSeal begin(PipelineBatchLedger.TrackedTask task) {
        StatusSource current = source;
        if (current == null || task == null) {
            return null;
        }
        PipelineRunSeal seal = new PipelineRunSeal(task, current, CURRENT.get());
        CURRENT.set(seal);
        return seal;
    }

    /** The window the calling thread runs a body of, or null outside a wrapped body. */
    public static PipelineRunSeal current() {
        return CURRENT.get();
    }

    /** The one status of this run's task; the first present read asks the range sink once. */
    public synchronized TaskStatus status() {
        if (closed) {
            return EMPTY;
        }
        if (!read) {
            sealed = early.present() ? early : read(installed, task);
            read = true;
            sealRange();
        }
        return sealed;
    }

    /** The range seal this window published, or null when it published none. */
    public synchronized FrozenDeclarations.RangeSeal range() {
        return range;
    }

    private void sealRange() {
        if (rangeRead || installedRanges == null || sealed == null || !sealed.present()) {
            return;
        }
        rangeRead = true;
        try {
            range = installedRanges.seal(task, sealed, rangeGeneration);
        } catch (Throwable refused) {
            // A sink that cannot answer means this window carries no range; the status answer stands.
            range = null;
        }
    }

    /** Whether this window already read its status. */
    public synchronized boolean read() {
        return read;
    }

    /** Whether the body of this window already ended. */
    public synchronized boolean closed() {
        return closed;
    }

    /** Ends the window, restores the one it was opened inside and releases the range first. */
    public synchronized void close() {
        closed = true;
        FrozenDeclarations.RangeSeal held = range;
        if (held != null && installedRanges != null) {
            try {
                installedRanges.release(held);
            } catch (Throwable refused) {
                // A release that throws does not keep the window open; the obligation still settles it.
            }
        }
        range = null;
        rangeRead = true;
        early = EMPTY;
        sealed = null;
        if (CURRENT.get() == this) {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }

    private static TaskStatus read(StatusSource source, PipelineBatchLedger.TrackedTask task) {
        try {
            TaskStatus status = source.statusFor(task);
            return status == null ? EMPTY : status;
        } catch (Throwable ignored) {
            return EMPTY;
        }
    }
}
