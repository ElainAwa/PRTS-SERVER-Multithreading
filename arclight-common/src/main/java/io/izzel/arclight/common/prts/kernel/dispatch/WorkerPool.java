/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

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

/**
 * The fixed pool of worker threads the kernel owns.
 *
 * <p>The pool is never shared with the host or with a mod: it has a fixed size, its threads are
 * named so a result can be attributed to the thread that produced it, their priority never exceeds
 * the priority of the tick thread, and none of them is bound to a core. A batch is offered to one
 * shard per world and taken by exactly one worker, so two workers never hold the same batch.</p>
 *
 * <p>The queue depth is bounded. When it is full the batch is refused rather than queued, and the
 * caller falls back to the tick thread; an unbounded queue would only hide the exhaustion.</p>
 */
public final class WorkerPool {

    /**
     * The shape of one pool.
     *
     * @param count       how many worker threads
     * @param namePrefix  the prefix of every thread name
     * @param priority    the priority every worker is created with
     * @param queueCap    how many batches may be in flight at once
     * @param batchChunks how many chunks one region covers on a side
     */
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

    /**
     * The declarative registration of one worker thread class.
     *
     * <p>A worker is the kernel's own thread, so it is registered as a kernel holder rather than as
     * an unregistered one. It serves every world, so its world scope is the multi-world one, and it
     * claims no world-dimension exemption bit.</p>
     *
     * @param threadClass  the stable class name of the thread, the pool prefix
     * @param threadName   the name of the thread instance
     * @param holderKind   where the thread comes from
     * @param worldScope   one single world, two many worlds, zero no world dimension
     * @param exemptFlags  the world-dimension exemption bits, none for a worker
     */
    public record ThreadClass(String threadClass, String threadName, HolderKind holderKind,
                              int worldScope, long exemptFlags) {
    }

    /**
     * What a shutdown left behind.
     *
     * @param stopped           whether new work stopped being accepted
     * @param cancelledInFlight batches whose cancellation was requested
     * @param remainingInFlight batches still in flight when the wait ended
     * @param terminated        whether every worker thread ended inside the wait
     */
    public record ShutdownReport(boolean stopped, int cancelledInFlight, int remainingInFlight,
                                 boolean terminated) {
    }

    private record Item(WorkBatch batch, CancelToken token, WorkBody body, ArenaSlot slot,
                        long slotGeneration, WorkerHandle handle) {
    }

    private final Spec spec;
    private final int retryBudget;
    private final DispatchReadings readings;
    private final ArenaLedger arena;
    private final Map<String, LinkedBlockingQueue<Item>> shards = new ConcurrentHashMap<>();
    private final Semaphore signals = new Semaphore(0);
    private final AtomicInteger inFlight = new AtomicInteger();
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

    /**
     * Opens a pool and starts its workers.
     *
     * @param spec        the shape of the pool
     * @param retryBudget how many retryable faults a batch may carry
     * @param readings    where the pool publishes
     * @param arena       the arena ledger a late result releases into
     * @return the running pool
     */
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

    /**
     * Offers one batch to the pool.
     *
     * @param batch      the batch to run
     * @param token      its cancellation token
     * @param body       the body to run
     * @param slot       the slot the body writes
     * @param slotGeneration the generation the slot was claimed at
     * @return the handle of the batch, or {@code null} when the pool refused it
     */
    public WorkerHandle submit(WorkBatch batch, CancelToken token, WorkBody body, ArenaSlot slot,
                               long slotGeneration) {
        if (!accepting.get() || active.get() <= 0) {
            return null;
        }
        if (inFlight.incrementAndGet() > spec.queueCap()) {
            inFlight.decrementAndGet();
            return null;
        }
        WorkerHandle handle = new WorkerHandle(batch.batchId(), batch.batchEpoch());
        tokens.add(token);
        Item item = new Item(batch, token, body, slot, slotGeneration, handle);
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
                    long checksum = item.body().run(item.batch(), item.slot().scratch(),
                        item.token());
                    boolean published = item.slot().publish(item.batch().batchId(),
                        item.slotGeneration());
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
        boolean accepted = item.handle().complete(outcome);
        if (!accepted) {
            readings.noteLateResult();
            arena.release(item.slot());
        } else if (outcome.status() != TaskOutcome.Status.EXECUTED) {
            arena.release(item.slot());
        }
    }

    private void retire(Thread thread) {
        if (threads.remove(thread)) {
            active.decrementAndGet();
            readings.noteThreadRetired();
        }
    }

    /**
     * Stops the pool in the ordered steps shutdown uses everywhere.
     *
     * @param stopDispatch     whether new submissions stop being accepted
     * @param awaitInFlight    whether to wait for work in flight
     * @param awaitTerminationMs how long the worker threads are given to end
     * @return what the shutdown left behind
     */
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

    /**
     * Retires one worker after a hard fault and shrinks the pool.
     *
     * @param index the worker index that died
     * @return whether the pool still has a live worker
     */
    public synchronized boolean shrink(int index) {
        if (index < 0 || index >= threads.size()) {
            return active.get() > 0;
        }
        Thread thread = threads.get(index);
        retire(thread);
        return active.get() > 0;
    }

    /** @return how many workers the pool was opened with */
    public int count() {
        return spec.count();
    }

    /** @return how many workers are alive right now */
    public int alive() {
        return active.get();
    }

    /** @return the batches in flight right now */
    public int queueDepth() {
        return inFlight.get();
    }

    /** @return the declarative registration of this pool's thread class */
    public List<ThreadClass> threadClasses() {
        return List.copyOf(threadClasses);
    }

    /** @return whether the pool still accepts work */
    public boolean accepting() {
        return accepting.get();
    }

    /** @return the shape the pool was opened with */
    public Spec spec() {
        return spec;
    }
}
