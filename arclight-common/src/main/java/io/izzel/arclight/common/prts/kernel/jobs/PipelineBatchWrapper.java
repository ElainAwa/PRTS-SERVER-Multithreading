/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;

import java.util.function.Function;

/**
 * Wraps the function a chunk pipeline submit enqueues and the body it produces, so the task the
 * ledger minted is the one that runs; an untracked task is handed back as the function submitted.
 *
 * <p>Neither face may leave an index entry behind: a delegate that throws ends the task that never
 * produced a body, and the body closes the ledger in the outermost finally. A watcher failure neither
 * skips the submitted body nor stands in for the exception the body raised; it rides on that
 * exception as a suppressed one.
 */
public final class PipelineBatchWrapper {

    private PipelineBatchWrapper() {
    }

    /** Wraps one enqueued function; an untracked task answers the delegate itself. */
    public static <T> Function<ProcessorHandle<Unit>, T> function(PipelineBatchLedger ledger,
                                                                  PipelineBatchLedger.TrackedTask task,
                                                                  Function<ProcessorHandle<Unit>, T>
                                                                      delegate) {
        if (!task.tracked()) {
            return delegate;
        }
        return new WrappedFunction<>(ledger, task, delegate);
    }

    private record WrappedFunction<T>(PipelineBatchLedger ledger,
                                      PipelineBatchLedger.TrackedTask task,
                                      Function<ProcessorHandle<Unit>, T> delegate)
        implements Function<ProcessorHandle<Unit>, T> {

        @Override
        public T apply(ProcessorHandle<Unit> reply) {
            // The sorter popped the function before it applied it, so from here a queue-clearing
            // release can no longer reach this task.
            ledger.handedOff(task);
            T produced;
            try {
                produced = delegate.apply(reply);
            } catch (RuntimeException | Error failure) {
                // No body will run for this task: it leaves the index with the failure it raised.
                ledger.notRunnable(task);
                throw failure;
            }
            if (produced instanceof Runnable body) {
                return cast(new WrappedBody(ledger, task, body));
            }
            ledger.notRunnable(task);
            return produced;
        }

        @SuppressWarnings("unchecked")
        private static <T> T cast(Object value) {
            return (T) value;
        }
    }

    private record WrappedBody(PipelineBatchLedger ledger,
                               PipelineBatchLedger.TrackedTask task,
                               Runnable delegate) implements Runnable {

        @Override
        public void run() {
            boolean window = ledger.runStart(task);
            boolean begun = false;
            Throwable failure = null;
            try {
                if (window) {
                    begun = true;
                    failure = tapped(() -> PrtsPipelineRows.ownerTaskBegin(task.processor(),
                        task.id().mailbox(), task));
                }
                try {
                    delegate.run();
                } catch (RuntimeException | Error thrown) {
                    failure = merge(thrown, failure);
                }
            } finally {
                failure = merge(failure, begun ? tapped(() -> PrtsPipelineRows.ownerTaskEnd(
                    task.processor(), task.id().mailbox(), task)) : null);
                failure = merge(failure, tapped(() -> ledger.runEnd(task)));
            }
            rethrow(failure);
        }

        // A watcher that refuses a span opens nothing, but it must not skip the submitted body or
        // stand in for the body's exception: the body runs, the end pairs with the attempted begin,
        // and the body's own exception stays the primary one.
        private static Throwable tapped(Runnable span) {
            try {
                span.run();
                return null;
            } catch (RuntimeException | Error failure) {
                return failure;
            }
        }

        private static Throwable merge(Throwable primary, Throwable late) {
            if (late == null || late == primary) {
                return primary;
            }
            if (primary == null) {
                return late;
            }
            primary.addSuppressed(late);
            return primary;
        }

        private static void rethrow(Throwable failure) {
            if (failure instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure instanceof Error error) {
                throw error;
            }
        }
    }
}
