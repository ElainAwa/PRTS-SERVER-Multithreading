/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;

import java.util.function.Function;

/**
 * Wraps the function a chunk pipeline submit enqueues and the body it produces, so the task the
 * ledger minted is the one that runs; an untracked task is handed back as the function submitted.
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
            T produced = delegate.apply(reply);
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
            try {
                if (window) {
                    // Pairing is kept even if a watcher refuses the begin: the end of a span that
                    // never opened a slot retires nothing.
                    begun = true;
                    PrtsPipelineRows.ownerTaskBegin(task.processor(), task.id().mailbox(), task);
                }
                delegate.run();
            } finally {
                if (begun) {
                    PrtsPipelineRows.ownerTaskEnd(task.processor(), task.id().mailbox(), task);
                }
                ledger.runEnd(task);
            }
        }
    }
}
