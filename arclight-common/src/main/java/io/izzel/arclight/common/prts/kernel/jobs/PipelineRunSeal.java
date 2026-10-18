/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.jobs.DemandShadowCandidates.TaskStatus;

/** One chunk pipeline body's run-start window: it reads the status of the task it runs once, joined
 * to that same task and batch, and refuses every later read. An empty answer is a refusal, never a
 * default; the window carries no status and derives none. */
public final class PipelineRunSeal {

    /** Reads the one status of the task the window covers. */
    public interface StatusSource {

        TaskStatus statusFor(Object taskToken);
    }

    private static final ThreadLocal<PipelineRunSeal> CURRENT = new ThreadLocal<>();
    private static final TaskStatus EMPTY = new TaskStatus("", null);
    private static volatile StatusSource source;

    private final PipelineBatchLedger.TrackedTask task;
    private final StatusSource installed;
    private final PipelineRunSeal previous;
    private TaskStatus early = EMPTY;
    private TaskStatus sealed;
    private boolean read;
    private boolean closed;

    private PipelineRunSeal(PipelineBatchLedger.TrackedTask task, StatusSource source,
                            PipelineRunSeal previous) {
        this.task = task;
        this.installed = source;
        this.previous = previous;
        this.early = read(source, task);
    }

    /** Names the register every later window reads; a null source opens no window. */
    public static void install(StatusSource installed) {
        source = installed;
    }

    /** Opens the run-start window of one task body; null when no register is installed. The
     * run-start read keeps an answer that is already present and leaves an absent one to the body. */
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

    /** The one status of this run's task; the first read closes the window. */
    public synchronized TaskStatus status() {
        if (closed) {
            return EMPTY;
        }
        if (!read) {
            sealed = early.present() ? early : read(installed, task);
            read = true;
        }
        return sealed;
    }

    /** Whether this window already read its status. */
    public synchronized boolean read() {
        return read;
    }

    /** Whether the body of this window already ended. */
    public synchronized boolean closed() {
        return closed;
    }

    /** Ends the window and restores the one it was opened inside, if any. */
    public synchronized void close() {
        closed = true;
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
