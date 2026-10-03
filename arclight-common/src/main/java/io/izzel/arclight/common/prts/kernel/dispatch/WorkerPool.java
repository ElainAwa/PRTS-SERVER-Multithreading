/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.domain.entity.CancelToken;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkBody;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkBatch;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/** The pool is never shared with the host or with a mod: it has a fixed size, its threads are
 * named so a result can be attributed to the thread that produced it, their priority never exceeds
 * the priority of the tick thread, and none of them is bound to a core. */
public final class WorkerPool {

    /** The shape of one pool. */
    public record Spec(int count, String namePrefix, int priority, int queueCap,
                       int batchChunks) {

        /** Validates the shape. */
        public Spec {
            if (count < 1) {
                throw new IllegalArgumentException("a pool needs at least one worker");
            }
            if (namePrefix == null || namePrefix.isEmpty()) {
                throw new IllegalArgumentException("a pool needs a thread name prefix");
            }
            if (queueCap < 1) {
                throw new IllegalArgumentException("a pool needs a positive queue capacity");
            }
            if (batchChunks < 1) {
                throw new IllegalArgumentException("a region needs at least one chunk");
            }
        }
    }

    /** The declarative registration of one worker thread class. A worker is the kernel's own
     * thread, so it is registered as a kernel holder rather than as an unregistered one. */
    public record ThreadClass(String threadClass, String threadName, HolderKind holderKind,
                              int worldScope, long exemptFlags) {
    }

    /** What a shutdown left behind. */
    public record ShutdownReport(boolean stopped, int cancelledInFlight, int remainingInFlight,
                                 boolean terminated) {
    }

    private record Item(WorkBatch batch, CancelToken token, WorkBody body, ArenaSlot slot,
                        ArenaSlot.Lease lease, WorkerHandle handle) {
    }

    private final Spec spec;
    private final int retryBudget;
    private final DispatchReadings readings;
    private final ArenaLedger arena;
    private final Map<String, LinkedBlockingQueue<Item>> shards = new ConcurrentHashMap<>();
    private final Semaphore signals = new Semaphore(0);
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong newestEpoch = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final AtomicBoolean shuttingDown = new AtomicBoolean();
    private final Set<CancelToken> tokens = ConcurrentHashMap.newKeySet();
    private final List<Thread> threads = new CopyOnWriteArrayList<>();
    private final List<ThreadClass> threadClasses = new CopyOnWriteArrayList<>();

    private WorkerPool(Spec spec, int retryBudget, DispatchReadings readings, ArenaLedger arena) {
        this.spec = spec;
        this.retryBudget = retryBudget;
        this.readings = readings;
        this.arena = arena;
    }

    /** Opens a pool and starts its workers. */
    public static WorkerPool open(Spec spec, int retryBudget, DispatchReadings readings,
                                  ArenaLedger arena) {
        WorkerPool pool = new WorkerPool(spec, retryBudget, readings, arena);
        pool.start();
        return pool;
    }

    private void start() {
        // The tick thread's priority is the ceiling: a worker must never preempt it.
        int priority = Math.min(Thread.NORM_PRIORITY, currentPriority());
        for (int index = 0; index < spec.count(); index++) {
            Thread thread = new Thread(this::work, spec.namePrefix() + index);
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Math.min(priority, spec.priority())));
            threads.add(thread);
            threadClasses.add(new ThreadClass(threadClassOf(spec.namePrefix()), thread.getName(),
                HolderKind.KERNEL, 2, 0L));
            readings.noteThreadStarted();
            thread.start();
        }
        active.set(spec.count());
    }

    private static String threadClassOf(String namePrefix) {
        return namePrefix.endsWith("-") ? namePrefix.substring(0, namePrefix.length() - 1)
            : namePrefix;
    }

    private static int currentPriority() {
        try {
            return Thread.currentThread().getPriority();
        } catch (Throwable ignored) {
            return Thread.NORM_PRIORITY;
        }
    }

    /** Offers one batch to the pool. */
    public WorkerHandle submit(WorkBatch batch, CancelToken token, WorkBody body, ArenaSlot slot,
                               ArenaSlot.Lease lease) {
        if (!accepting.get() || active.get() <= 0) {
            return null;
        }
        if (inFlight.incrementAndGet() > spec.queueCap()) {
            inFlight.decrementAndGet();
            return null;
        }
        WorkerHandle handle = new WorkerHandle(batch.batchId(), batch.batchEpoch());
        newestEpoch.accumulateAndGet(batch.batchEpoch(), Math::max);
        tokens.add(token);
        Item item = new Item(batch, token, body, slot, lease, handle);
        shards.computeIfAbsent(batch.task().worldId(), key -> new LinkedBlockingQueue<>())
            .offer(item);
        readings.noteQueueDepth(inFlight.get());
        signals.release();
        return handle;
    }

    private void work() {
        Thread self = Thread.currentThread();
        while (!shuttingDown.get()) {
            try {
                signals.tryAcquire(50L, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                self.interrupt();
                return;
            }
            if (shuttingDown.get()) {
                return;
            }
            Item item = poll();
            if (item != null) {
                runItem(item);
            }
            if (!threads.contains(self)) {
                return;
            }
        }
    }

    private Item poll() {
        for (LinkedBlockingQueue<Item> shard : shards.values()) {
            Item item = shard.poll();
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    private void runItem(Item item) {
        Thread self = Thread.currentThread();
        long startedAt = System.nanoTime();
        TaskOutcome outcome;
        int attempts = 0;
        try {
            while (true) {
                try {
                    if (item.token().cancelled()) {
                        throw new WorkBody.CancelledFault();
                    }
                    long checksum = item.body().run(item.batch(), item.lease().scratch(),
                        item.token());
                    boolean published = item.slot().publish(item.lease());
                    if (!published) {
                        outcome = TaskOutcome.fellback(item.batch().batchId(), attempts, self,
                            RejectCode.WRITE_DENIED_NOT_OWNER.text());
                        break;
                    }
                    outcome = TaskOutcome.executed(item.batch().batchId(), attempts, self,
                        item.slot().ref(), checksum);
                    break;
                } catch (WorkBody.RetryableFault e) {
                    attempts++;
                    if (attempts <= retryBudget && !item.token().cancelled()) {
                        continue;
                    }
                    outcome = TaskOutcome.retried(item.batch().batchId(), attempts, self,
                        RejectCode.VERSION_MISMATCH.text());
                    break;
                } catch (WorkBody.CancelledFault e) {
                    outcome = TaskOutcome.cancelled(item.batch().batchId(), attempts,
                        self.threadId(), self.getName(), RejectCode.TICK_BUDGET_EXHAUSTED.text());
                    break;
                } catch (WorkBody.NonRetryableFault e) {
                    outcome = TaskOutcome.fellback(item.batch().batchId(), attempts, self,
                        RejectCode.WRITE_DENIED_NOT_OWNER.text());
                    break;
                } catch (Throwable t) {
                    outcome = TaskOutcome.failed(item.batch().batchId(), attempts, self,
                        RejectCode.PROGRESS_UNOBSERVED.text());
                    retire(self);
                    break;
                }
            }
        } finally {
            readings.noteExecution(self.getName(), System.nanoTime() - startedAt);
            inFlight.decrementAndGet();
            tokens.remove(item.token());
            readings.noteQueueDepth(inFlight.get());
        }
        // The epoch makes a result late whether or not the merge completed the handle first; the
        // release goes through the lease, so a reused slot refuses it instead of being freed.
        boolean currentEpoch = item.batch().batchEpoch() >= newestEpoch.get();
        boolean accepted = currentEpoch && item.handle().complete(outcome);
        if (!currentEpoch) {
            readings.noteLateEpochResult();
        } else if (!accepted) {
            readings.noteLateResult();
        }
        if (!accepted || outcome.status() != TaskOutcome.Status.EXECUTED) {
            // The body has returned, so the buffer may be reused; a release the merge made first is
            // answered ALREADY_RELEASED and changes nothing.
            arena.release(item.lease(), true);
        }
    }

    private void retire(Thread thread) {
        if (threads.remove(thread)) {
            active.decrementAndGet();
            readings.noteThreadRetired();
        }
    }

    /** Stops the pool in the ordered steps shutdown uses everywhere. */
    public ShutdownReport shutdown(boolean stopDispatch, boolean awaitInFlight,
                                   long awaitTerminationMs) {
        if (stopDispatch) {
            accepting.set(false);
        }
        int cancelled = 0;
        for (CancelToken token : tokens) {
            if (token.cancel()) {
                cancelled++;
            }
        }
        int remaining = inFlight.get();
        if (awaitInFlight) {
            long deadline = System.nanoTime() + Math.max(0L, awaitTerminationMs) * 1_000_000L;
            while (inFlight.get() > 0 && System.nanoTime() < deadline) {
                try {
                    Thread.sleep(2L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            remaining = inFlight.get();
        }
        shuttingDown.set(true);
        signals.release(threads.size() * 2 + spec.count());
        boolean terminated = true;
        for (Thread thread : threads) {
            try {
                thread.join(Math.max(1L, awaitTerminationMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (thread.isAlive()) {
                terminated = false;
            }
        }
        return new ShutdownReport(stopDispatch, cancelled, remaining, terminated);
    }

    /** Retires one worker after a hard fault and shrinks the pool. */
    public synchronized boolean shrink(int index) {
        if (index < 0 || index >= threads.size()) {
            return active.get() > 0;
        }
        Thread thread = threads.get(index);
        retire(thread);
        return active.get() > 0;
    }

    public int count() {
        return spec.count();
    }

    public int alive() {
        return active.get();
    }

    public int queueDepth() {
        return inFlight.get();
    }

    public List<ThreadClass> threadClasses() {
        return List.copyOf(threadClasses);
    }

    public boolean accepting() {
        return accepting.get();
    }

    public Spec spec() {
        return spec;
    }

    /** The one-shot answer slot of a dispatched batch. The worker writes the outcome, and the merge
     * may write it first when the deadline passes. */
    public static final class WorkerHandle {

        private final long batchId;
        private final long batchEpoch;
        private final CompletableFuture<TaskOutcome> outcome = new CompletableFuture<>();

        /** Creates the handle of one batch. */
        public WorkerHandle(long batchId, long batchEpoch) {
            this.batchId = batchId;
            this.batchEpoch = batchEpoch;
        }

        public long batchId() {
            return batchId;
        }

        public long batchEpoch() {
            return batchEpoch;
        }

        /** Publishes an outcome if none was published yet. */
        public boolean complete(TaskOutcome result) {
            return outcome.complete(result);
        }

        public boolean isDone() {
            return outcome.isDone();
        }

        /** Waits for the outcome until the deadline. */
        public TaskOutcome await(long timeoutNanos) {
            if (outcome.isDone()) {
                return outcome.getNow(null);
            }
            try {
                return outcome.get(Math.max(0L, timeoutNanos), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                return null;
            } catch (ExecutionException e) {
                return null;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }

        public TaskOutcome peek() {
            return outcome.getNow(null);
        }
    }
}
