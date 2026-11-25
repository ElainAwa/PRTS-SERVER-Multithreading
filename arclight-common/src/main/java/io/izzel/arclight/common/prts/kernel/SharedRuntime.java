/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.generic.GenericJobExecutor;
import io.izzel.arclight.common.prts.kernel.jobs.WorkAccount;

/**
 * The one execution runtime of this process: one worker pool created and named by that pool, one
 * domain-neutral executor that seats jobs and closes the units of one work account, and the arena
 * and readings the pool books into. Workers start only on {@link #open()}, opening an open runtime
 * or closing a closed one changes nothing, and the account survives a close.
 */
public final class SharedRuntime {

    /** Leading part of every worker thread name; the pool appends the worker index. */
    public static final String THREAD_PREFIX = "prts-worker-";

    private static final long CLOSE_WAIT_MS = 250L;

    private final ArenaLedger arena = new ArenaLedger();
    private final DispatchReadings readings = new DispatchReadings();
    private final WorkAccount account = new WorkAccount();
    private volatile WorkerPool pool;
    private volatile GenericJobExecutor executor;
    /** The pool whose shutdown was requested but whose bodies have not all exited yet. */
    private volatile WorkerPool draining;
    private volatile int opens;
    private volatile int closes;

    private SharedRuntime() {
    }

    /** The runtime of this process, created on first call without starting any worker. */
    public static SharedRuntime shared() {
        return Holder.INSTANCE;
    }

    /** Starts the one pool if it is not running, using the shape the existing dispatch policy
     * resolves; opening twice keeps the first pool. Answers whether a pool is available. */
    public synchronized boolean open() {
        if (draining != null) {
            // The old generation is still draining: a new pool would let new work run while the
            // previous generation's bodies and resources are still on the books.
            return false;
        }
        if (pool != null) {
            return true;
        }
        DispatchPass.DispatchSettings.Policy policy = DispatchPass.DispatchSettings.resolve();
        try {
            WorkerPool opened = WorkerPool.open(new WorkerPool.Spec(policy.workerCount(),
                THREAD_PREFIX, Thread.NORM_PRIORITY, policy.queueCap(), policy.batchChunks()),
                policy.retryBudget(), readings, arena);
            executor = new GenericJobExecutor(opened, null, null, account);
            pool = opened;
            opens++;
            return true;
        } catch (Throwable failure) {
            readings.notePoolOpenFailed();
            return false;
        }
    }

    /** Stops the pool, cancels queued work, waits a bounded time for running bodies and releases the
     * arena; answers whether the stop was confirmed. A second close is a no-op. */
    public synchronized boolean close() {
        if (pool != null) {
            draining = pool;
            pool = null;
            executor = null;
        }
        WorkerPool current = draining;
        if (current == null) {
            return true;
        }
        WorkerPool.ShutdownReport report = current.shutdown(true, true, CLOSE_WAIT_MS);
        boolean confirmed = report.terminated() && report.remainingInFlight() == 0;
        readings.noteShutdown(report.remainingInFlight(), report.terminated());
        if (!confirmed) {
            // The old generation keeps its workers, arena and account open; a later close retries
            // the same pool until the last body has really exited.
            arena.quarantineAll();
            return false;
        }
        draining = null;
        arena.releaseAll();
        closes++;
        return true;
    }

    /** The pool, or null while the runtime is closed. */
    public WorkerPool pool() {
        return pool;
    }

    /** The shared executor, or null while the runtime is closed. */
    public GenericJobExecutor executor() {
        return executor;
    }

    /** The arena every cohort of this runtime claims its slots from. */
    public ArenaLedger arena() {
        return arena;
    }

    /** The counters of the pool. */
    public DispatchReadings readings() {
        return readings;
    }

    /** The one window account every execution of this runtime closes its units in. */
    public WorkAccount account() {
        return account;
    }

    boolean opened() {
        return pool != null;
    }

    int opens() {
        return opens;
    }

    int closes() {
        return closes;
    }

    private static final class Holder {

        private static final SharedRuntime INSTANCE = new SharedRuntime();
    }
}
