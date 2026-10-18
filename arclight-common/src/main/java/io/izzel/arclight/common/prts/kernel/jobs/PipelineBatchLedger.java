/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.support.PrtsPipelineBatches;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Function;

/**
 * The batch a chunk pipeline task belongs to, published in the one state object each transition
 * installs.
 *
 * <p>A task is minted where the pipeline submits it; a freeze turns the tasks of at most
 * {@value #MAX_CELLS} distinct cells into one batch. The batch travels inside the state the claim
 * transition publishes, so no reader can observe a claimed task that names no batch; the range and
 * the members of a batch are read from those claimed states until the freeze publishes its complete
 * binding, so a task that opens a window always has its cell in the range. The
 * pre-run states keep where the task is: a release that clears the sorter queue cancels a task only
 * while it is still resident there, never one the sorter already applied and handed to its mailbox.
 *
 * <p>Tracking is bounded: at most the pending limit tasks are indexed, a terminal transition removes
 * its task again, and a task minted above the bound is not wrapped at all, so the pipeline runs it
 * exactly as it would without this ledger. The closed-batch history is bounded too, but a binding a
 * pending task still runs under is never dropped: its range has to outlive the task that may open it.
 */
public final class PipelineBatchLedger implements PrtsPipelineBatches.TaskSink {

    /** The declared bound of the cells one batch may name; the same bound the write-range table
     * declares, and a task count is not bounded by it. */
    public static final int MAX_CELLS = 16;

    public static final int DEFAULT_PENDING_LIMIT = 4096;

    /** Ticks a minted task may stay unenqueued before the sweep drops its index entry. */
    public static final int DEFAULT_MINT_TTL_TICKS = 400;

    // The closed-batch history is bounded by this many batches, except for the bindings a pending
    // task still needs: dropping one of those would leave a window without the range it opened over.
    private static final int BATCH_LIMIT = 64;
    private static final int REPORT_LIMIT = 256;

    /** One batch a freeze cut: the world and mailbox it belongs to, which freeze made it and which
     * plan ordered it. */
    public record BatchId(String worldId, String mailbox, long freezeOrdinal, long planSequence) {
    }

    /** One task as the ledger names it. A task is the work one submit carried; several tasks can
     * share a cell, so a batch names cells and never a bound on tasks. */
    public record TaskId(long ordinal, String worldId, String mailbox, long cell, long mintTick) {
    }

    /** Where a task is while a freeze may still claim it. */
    public enum Place {
        MINTED,
        QUEUED,
        HANDED_OFF
    }

    /** Why a task that never ran left the pending index. */
    public enum CancelPoint {
        QUEUED_CLEARED
    }

    /** The immutable task state. A claimed task carries the batch in the state itself, so the batch
     * and the claim are installed by one compare-and-set and are never read apart. */
    public sealed interface TaskState permits Minted, Queued, HandedOff, Claimed, Running,
        RunningUnclaimed, Done, DoneUnclaimed, Cancelled, CancelledUnclaimed {
    }

    public record Minted() implements TaskState {
    }

    public record Queued() implements TaskState {
    }

    public record HandedOff() implements TaskState {
    }

    public record Claimed(BatchId batch, Place place) implements TaskState {
    }

    public record Running(BatchId batch) implements TaskState {
    }

    public record RunningUnclaimed() implements TaskState {
    }

    public record Done(BatchId batch) implements TaskState {
    }

    public record DoneUnclaimed() implements TaskState {
    }

    public record Cancelled(BatchId batch, CancelPoint point) implements TaskState {
    }

    public record CancelledUnclaimed(CancelPoint point) implements TaskState {
    }

    /** One closed batch: what its tasks reached when the next freeze of its mailbox superseded it. */
    public record BatchReport(BatchId batch, int tasks, int done, int cancelled, int unfinished,
                              long closedTick) {
    }

    // What one freeze publishes: the identity, the tasks it claimed and the cells those tasks name,
    // prepared complete and installed by the one map write a reader sees.
    private record BatchBinding(BatchId batch, List<TrackedTask> tasks, long[] cells) {
    }

    /** One task the ledger tracks. The state reference is the only mutable part; a detached task
     * (reset, expiry or a bound that stopped observation) accepts no transition any more. */
    public static final class TrackedTask {

        private final TaskId id;
        private final Object processor;
        private final boolean tracked;
        private final long epoch;
        private final AtomicReference<TaskState> state = new AtomicReference<>(new Minted());
        private volatile boolean live = true;

        private TrackedTask(TaskId id, Object processor, boolean tracked, long epoch) {
            this.id = id;
            this.processor = processor;
            this.tracked = tracked;
            this.epoch = epoch;
        }

        public TaskId id() {
            return id;
        }

        public Object processor() {
            return processor;
        }

        public boolean tracked() {
            return tracked;
        }

        public TaskState state() {
            return state.get();
        }

        public boolean live() {
            return live;
        }

        public BatchId batch() {
            return batchOf(state.get());
        }

        private void detach() {
            live = false;
        }
    }

    private final AtomicLong ordinals = new AtomicLong();
    private final AtomicLong freezeOrdinals = new AtomicLong();
    private final AtomicLong epoch = new AtomicLong();
    private final AtomicLong trackedCount = new AtomicLong();
    private final ConcurrentSkipListMap<Long, TrackedTask> pending = new ConcurrentSkipListMap<>();
    private final ConcurrentSkipListMap<Long, BatchBinding> batches = new ConcurrentSkipListMap<>();
    // Freezes whose binding is not published yet; their members and range are read from the claims
    // that already carry the batch, so a window never opens over a range that is still empty.
    private final Set<Long> forming = ConcurrentHashMap.newKeySet();
    // A mint's epoch read, bound claim and index insert against a reset's detach, clear and epoch
    // bump: one critical section, so a registration a reset overtakes is detached and counted out
    // instead of landing in the next generation's index and count.
    private final Object registration = new Object();
    private final Queue<BatchReport> reports = new ConcurrentLinkedQueue<>();
    private final Map<String, BatchId> current = new ConcurrentHashMap<>();
    private final ThreadLocal<TrackedTask> running = new ThreadLocal<>();
    private final LongAdder minted = new LongAdder();
    private final LongAdder overflowed = new LongAdder();
    private final LongAdder enqueued = new LongAdder();
    private final LongAdder handedOffs = new LongAdder();
    private final LongAdder claimedTasks = new LongAdder();
    private final LongAdder claimLost = new LongAdder();
    private final LongAdder emptyFreezes = new LongAdder();
    private final LongAdder deferred = new LongAdder();
    private final LongAdder planningFailures = new LongAdder();
    private final LongAdder earlyRuns = new LongAdder();
    private final LongAdder lateRuns = new LongAdder();
    private final LongAdder releaseBeforeEnqueue = new LongAdder();
    private final LongAdder releaseAfterHandoff = new LongAdder();
    private final LongAdder queuedCleared = new LongAdder();
    private final LongAdder notRunnable = new LongAdder();
    private final LongAdder expired = new LongAdder();
    private final LongAdder closedBatches = new LongAdder();
    private final LongAdder anomalies = new LongAdder();
    private final LongAdder resets = new LongAdder();
    private final int pendingLimit;
    private final int mintTtlTicks;
    private volatile long currentPlan;
    private volatile boolean planKnown;
    private volatile long lastTick;

    public PipelineBatchLedger() {
        this(DEFAULT_PENDING_LIMIT, DEFAULT_MINT_TTL_TICKS);
    }

    public PipelineBatchLedger(int pendingLimit, int mintTtlTicks) {
        this.pendingLimit = Math.max(1, pendingLimit);
        this.mintTtlTicks = Math.max(1, mintTtlTicks);
    }

    /** Mints the identity of one submit. Above the pending bound the task is returned untracked:
     * the caller must wrap nothing, and the task runs exactly as the pipeline submitted it. */
    public TrackedTask mint(Object processor, String worldId, String mailbox, long cell,
                            long tick) {
        TaskId id = new TaskId(ordinals.incrementAndGet(), worldId == null ? "" : worldId,
            mailbox == null ? "" : mailbox, cell, tick);
        minted.increment();
        synchronized (registration) {
            long generation = epoch.get();
            if (trackedCount.incrementAndGet() > pendingLimit) {
                trackedCount.decrementAndGet();
                overflowed.increment();
                return new TrackedTask(id, processor, false, generation);
            }
            TrackedTask task = new TrackedTask(id, processor, true, generation);
            pending.put(id.ordinal(), task);
            return task;
        }
    }

    /** The sink face of {@link #enqueued(TrackedTask)}: an opaque handle names the task. */
    @Override
    public void enqueued(Object task) {
        if (task instanceof TrackedTask tracked) {
            enqueued(tracked);
        }
    }

    /** The sorter enqueued the task's function: from here a release that clears the queue reaches
     * it, which is the only window in which a cancellation may still cancel it. */
    public void enqueued(TrackedTask task) {
        if (!accepts(task)) {
            return;
        }
        for (;;) {
            TaskState now = task.state();
            TaskState next = now instanceof Minted ? new Queued()
                : now instanceof Claimed claimed && claimed.place() == Place.MINTED
                    ? new Claimed(claimed.batch(), Place.QUEUED) : null;
            if (next == null) {
                if (terminal(now)) {
                    anomalies.increment();
                }
                return;
            }
            if (task.state.compareAndSet(now, next)) {
                enqueued.increment();
                return;
            }
        }
    }

    /** The sorter popped the task's function, applied it and handed its body to the processor
     * mailbox. A release arriving from now on can no longer cancel it. */
    public void handedOff(TrackedTask task) {
        if (!accepts(task)) {
            return;
        }
        for (;;) {
            TaskState now = task.state();
            Place place = placeOf(now);
            if (place == null) {
                if (terminal(now)) {
                    anomalies.increment();
                }
                return;
            }
            if (place == Place.HANDED_OFF) {
                return;
            }
            TaskState next = now instanceof Claimed claimed
                ? new Claimed(claimed.batch(), Place.HANDED_OFF) : new HandedOff();
            if (task.state.compareAndSet(now, next)) {
                handedOffs.increment();
                return;
            }
        }
    }

    /** Starts one body. The batch, when there is one, travels with the running state; the answer is
     * whether the window of a currently declared batch may be opened for it. */
    public boolean runStart(TrackedTask task) {
        if (!accepts(task)) {
            return false;
        }
        for (;;) {
            TaskState now = task.state();
            if (now instanceof Claimed claimed) {
                if (task.state.compareAndSet(now, new Running(claimed.batch()))) {
                    running.set(task);
                    boolean current = planKnown && claimed.batch().planSequence() == currentPlan;
                    if (!current) {
                        lateRuns.increment();
                    }
                    return current;
                }
                continue;
            }
            if (placeOf(now) != null) {
                if (task.state.compareAndSet(now, new RunningUnclaimed())) {
                    running.set(task);
                    earlyRuns.increment();
                    return false;
                }
                continue;
            }
            if (now instanceof Running || now instanceof RunningUnclaimed) {
                return false;
            }
            anomalies.increment();
            return false;
        }
    }

    /** Ends one body: every start closes its own state, an exception path included. */
    public void runEnd(TrackedTask task) {
        if (running.get() == task) {
            running.remove();
        }
        if (!accepts(task)) {
            return;
        }
        for (;;) {
            TaskState now = task.state();
            TaskState next = now instanceof Running runningBatch
                ? new Done(runningBatch.batch())
                : now instanceof RunningUnclaimed ? new DoneUnclaimed() : null;
            if (next == null) {
                if (terminal(now)) {
                    return;
                }
                anomalies.increment();
                return;
            }
            if (task.state.compareAndSet(now, next)) {
                dechain(task);
                return;
            }
        }
    }

    /** The task's product was not a body this ledger can observe: it leaves the index without a run. */
    public void notRunnable(TrackedTask task) {
        if (!accepts(task)) {
            return;
        }
        for (;;) {
            TaskState now = task.state();
            TaskState next = now instanceof HandedOff
                ? new DoneUnclaimed()
                : now instanceof Claimed claimed && claimed.place() == Place.HANDED_OFF
                    ? new Done(claimed.batch()) : null;
            if (next == null) {
                if (terminal(now)) {
                    return;
                }
                anomalies.increment();
                return;
            }
            if (task.state.compareAndSet(now, next)) {
                notRunnable.increment();
                dechain(task);
                return;
            }
        }
    }

    /** Cuts the batch of one world and mailbox out of the pending tasks, in mint order, over at
     * most {@value #MAX_CELLS} distinct cells. The batch and every claim it installs share the one
     * state each claim publishes; a task whose state moved on is left for a later freeze. */
    public BatchId freeze(String worldId, String mailbox, long planSequence, long tick) {
        if (worldId == null || worldId.isEmpty() || mailbox == null || mailbox.isEmpty()
            || planSequence <= 0L) {
            planningFailures.increment();
            return null;
        }
        currentPlan = planSequence;
        planKnown = true;
        closeSuperseded(worldId, mailbox, tick);
        List<TrackedTask> selected = new ArrayList<>();
        TreeSet<Long> cells = new TreeSet<>();
        for (TrackedTask task : pending.values()) {
            if (!accepts(task) || !worldId.equals(task.id().worldId())
                || !mailbox.equals(task.id().mailbox()) || !claimable(task.state())) {
                continue;
            }
            long cell = task.id().cell();
            if (cells.size() < MAX_CELLS || cells.contains(cell)) {
                selected.add(task);
                cells.add(cell);
            } else {
                deferred.increment();
            }
        }
        if (selected.isEmpty()) {
            emptyFreezes.increment();
            return null;
        }
        BatchId batch = new BatchId(worldId, mailbox, freezeOrdinals.incrementAndGet(),
            planSequence);
        forming.add(batch.freezeOrdinal());
        try {
            List<TrackedTask> claimed = new ArrayList<>(selected.size());
            for (TrackedTask task : selected) {
                TaskState now = task.state();
                Place place = placeOf(now);
                if (place == null || !accepts(task) || !claimable(now)) {
                    claimLost.increment();
                    continue;
                }
                if (task.state.compareAndSet(now, new Claimed(batch, place))) {
                    claimed.add(task);
                } else {
                    claimLost.increment();
                }
            }
            if (claimed.isEmpty()) {
                emptyFreezes.increment();
                return null;
            }
            batches.put(batch.freezeOrdinal(), binding(batch, claimed));
            current.put(key(worldId, mailbox), batch);
            claimedTasks.add(claimed.size());
            trimBatches();
            return batch;
        } finally {
            forming.remove(batch.freezeOrdinal());
        }
    }

    /** Cuts one batch per world and mailbox pair that has claimable tasks, the pairs in a fixed
     * order so one freeze declares the same set whatever order the submits arrived in. A pair whose
     * tasks a competing freeze already moved on is left for the next freeze. */
    public List<BatchId> freezeAll(long planSequence, long tick) {
        TreeMap<String, String[]> pairs = new TreeMap<>();
        for (TrackedTask task : pending.values()) {
            if (!accepts(task) || !claimable(task.state())) {
                continue;
            }
            String world = task.id().worldId();
            String mailbox = task.id().mailbox();
            if (world.isEmpty() || mailbox.isEmpty()) {
                continue;
            }
            pairs.putIfAbsent(world + "@@@" + mailbox, new String[] {world, mailbox});
        }
        List<BatchId> batches = new ArrayList<>(pairs.size());
        for (String[] pair : pairs.values()) {
            BatchId batch = freeze(pair[0], pair[1], planSequence, tick);
            if (batch != null) {
                batches.add(batch);
            }
        }
        return List.copyOf(batches);
    }

    /** The function the sorter enqueues, wrapped around the task this mint produced. */
    @Override
    public <T> Function<ProcessorHandle<Unit>, T> wrap(Object task,
                                                       Function<ProcessorHandle<Unit>, T> delegate) {
        return task instanceof TrackedTask tracked
            ? PipelineBatchWrapper.function(this, tracked, delegate) : delegate;
    }

    /** The release of a sorter queue. Only a task still resident there is cancelled; one minted but
     * not yet enqueued, or one already handed to its mailbox, keeps its execution. A claim a freeze
     * installs between the read and the cancellation is re-read, so the cancellation still lands
     * while the task is resident. */
    public void released(Object processor, long cell, boolean clearQueue) {
        if (!clearQueue) {
            return;
        }
        for (TrackedTask task : pending.values()) {
            if (!accepts(task) || task.processor() != processor || task.id().cell() != cell) {
                continue;
            }
            for (;;) {
                TaskState now = task.state();
                Place place = placeOf(now);
                if (place == null) {
                    releaseAfterHandoff.increment();
                    break;
                }
                if (place == Place.MINTED) {
                    releaseBeforeEnqueue.increment();
                    break;
                }
                if (place == Place.HANDED_OFF) {
                    releaseAfterHandoff.increment();
                    break;
                }
                TaskState next = now instanceof Claimed claimed
                    ? new Cancelled(claimed.batch(), CancelPoint.QUEUED_CLEARED)
                    : new CancelledUnclaimed(CancelPoint.QUEUED_CLEARED);
                if (task.state.compareAndSet(now, next)) {
                    queuedCleared.increment();
                    dechain(task);
                    break;
                }
                // A freeze claimed the task between the read and the set: it is still resident, so
                // the cancellation is completed against the re-read state.
                if (!accepts(task)) {
                    break;
                }
            }
        }
    }

    /** Drops tasks that no sorter enqueue reached within the mint lifetime, a task a freeze claimed
     * while it was still minted included, so such a claim cannot hold its slot for good; a task that
     * runs after this sweep still runs, only its index entry is gone. */
    public void noteTick(long tick) {
        lastTick = tick;
        for (TrackedTask task : pending.values()) {
            if (!unenqueued(task.state()) || tick - task.id().mintTick() <= mintTtlTicks) {
                continue;
            }
            synchronized (registration) {
                if (pending.remove(task.id().ordinal(), task)) {
                    task.detach();
                    trackedCount.decrementAndGet();
                    expired.increment();
                }
            }
        }
    }

    /** Drops every index entry and reading. A task already handed to its mailbox still runs, and
     * its transitions are no-ops against this ledger. */
    public void reset() {
        synchronized (registration) {
            for (TrackedTask task : pending.values()) {
                task.detach();
            }
            pending.clear();
            batches.clear();
            forming.clear();
            reports.clear();
            current.clear();
            running.remove();
            trackedCount.set(0L);
            currentPlan = 0L;
            planKnown = false;
            lastTick = 0L;
            epoch.incrementAndGet();
        }
        resets.increment();
        minted.reset();
        overflowed.reset();
        enqueued.reset();
        handedOffs.reset();
        claimedTasks.reset();
        claimLost.reset();
        emptyFreezes.reset();
        deferred.reset();
        planningFailures.reset();
        earlyRuns.reset();
        lateRuns.reset();
        releaseBeforeEnqueue.reset();
        releaseAfterHandoff.reset();
        queuedCleared.reset();
        notRunnable.reset();
        expired.reset();
        closedBatches.reset();
        anomalies.reset();
    }

    public TaskState stateOf(TrackedTask task) {
        return task.state();
    }

    public int pendingCount() {
        return pending.size();
    }

    public long trackedCount() {
        return trackedCount.get();
    }

    public long mintedCount() {
        return minted.sum();
    }

    public long overflowedCount() {
        return overflowed.sum();
    }

    public long claimedTaskCount() {
        return claimedTasks.sum();
    }

    public long claimLostCount() {
        return claimLost.sum();
    }

    public long queuedClearedCount() {
        return queuedCleared.sum();
    }

    public long releaseBeforeEnqueueCount() {
        return releaseBeforeEnqueue.sum();
    }

    public long releaseAfterHandoffCount() {
        return releaseAfterHandoff.sum();
    }

    public long earlyRunCount() {
        return earlyRuns.sum();
    }

    public long lateRunCount() {
        return lateRuns.sum();
    }

    public long notRunnableCount() {
        return notRunnable.sum();
    }

    public long expiredCount() {
        return expired.sum();
    }

    public long deferredCount() {
        return deferred.sum();
    }

    public long emptyFreezeCount() {
        return emptyFreezes.sum();
    }

    public long planningFailureCount() {
        return planningFailures.sum();
    }

    public long closedBatchCount() {
        return closedBatches.sum();
    }

    public long anomalyCount() {
        return anomalies.sum();
    }

    public long resetCount() {
        return resets.sum();
    }

    public long lastTick() {
        return lastTick;
    }

    public long currentPlan() {
        return currentPlan;
    }

    /** The task the calling thread is running a body of, or null. */
    public TrackedTask runningTask() {
        return running.get();
    }

    @Override
    public Object currentTask() {
        return running.get();
    }

    /** The number of tracked tasks this ledger indexes at most; a task above it is untracked. */
    public int pendingLimit() {
        return pendingLimit;
    }

    /** The tasks one batch claimed, in mint order; while its binding is still being formed they are
     * read from the claims that already carry it, and empty once the batch fell out of the bounded
     * history. */
    public List<TrackedTask> tasksOf(BatchId batch) {
        if (batch == null) {
            return List.of();
        }
        // The forming mark is read first: its removal happens after the binding write, so observing
        // it gone also observes the binding, and no reader can take the empty branch beside a claim.
        if (forming.contains(batch.freezeOrdinal())) {
            BatchBinding published = batches.get(batch.freezeOrdinal());
            return published != null ? published.tasks() : formingMembers(batch);
        }
        BatchBinding published = batches.get(batch.freezeOrdinal());
        return published != null ? published.tasks() : List.of();
    }

    private List<TrackedTask> formingMembers(BatchId batch) {
        List<TrackedTask> claimed = new ArrayList<>();
        for (TrackedTask task : pending.values()) {
            if (batch.equals(task.batch())) {
                claimed.add(task);
            }
        }
        return List.copyOf(claimed);
    }

    /** The cells one batch names, ascending and distinct. */
    public long[] cellsOf(BatchId batch) {
        BatchBinding published = batch == null ? null : batches.get(batch.freezeOrdinal());
        return published != null ? published.cells().clone() : cellsOfTasks(tasksOf(batch));
    }

    /** The closed batches, oldest first. */
    public List<BatchReport> reports() {
        return List.copyOf(reports);
    }

    private void closeSuperseded(String worldId, String mailbox, long tick) {
        BatchId previous = current.remove(key(worldId, mailbox));
        if (previous == null) {
            return;
        }
        List<TrackedTask> tasks = tasksOf(previous);
        int done = 0;
        int cancelled = 0;
        int unfinished = 0;
        for (TrackedTask task : tasks) {
            TaskState state = task.state();
            if (state instanceof Done || state instanceof DoneUnclaimed) {
                done++;
            } else if (state instanceof Cancelled || state instanceof CancelledUnclaimed) {
                cancelled++;
            } else {
                unfinished++;
            }
        }
        reports.add(new BatchReport(previous, tasks.size(), done, cancelled, unfinished, tick));
        while (reports.size() > REPORT_LIMIT) {
            reports.poll();
        }
        closedBatches.increment();
    }

    private void dechain(TrackedTask task) {
        synchronized (registration) {
            if (pending.remove(task.id().ordinal(), task)) {
                trackedCount.decrementAndGet();
            }
        }
    }

    /** Drops the oldest binding no live, non-terminal task still names. A binding a task may still run
     * under is kept past the bound, so the set of retained bindings stays bounded by the pending index
     * instead of the mailbox count. */
    private void trimBatches() {
        while (batches.size() > BATCH_LIMIT) {
            boolean dropped = false;
            for (Map.Entry<Long, BatchBinding> entry : batches.entrySet()) {
                if (needed(entry.getValue())) {
                    continue;
                }
                if (batches.remove(entry.getKey(), entry.getValue())) {
                    dropped = true;
                    break;
                }
            }
            if (!dropped) {
                return;
            }
        }
    }

    private static boolean needed(BatchBinding binding) {
        for (TrackedTask task : binding.tasks()) {
            if (task.live() && !terminal(task.state())) {
                return true;
            }
        }
        return false;
    }

    private static BatchBinding binding(BatchId batch, List<TrackedTask> claimed) {
        List<TrackedTask> members = List.copyOf(claimed);
        return new BatchBinding(batch, members, cellsOfTasks(members));
    }

    private static long[] cellsOfTasks(List<TrackedTask> tasks) {
        TreeSet<Long> cells = new TreeSet<>();
        for (TrackedTask task : tasks) {
            cells.add(task.id().cell());
        }
        long[] out = new long[cells.size()];
        int index = 0;
        for (long cell : cells) {
            out[index++] = cell;
        }
        return out;
    }

    private boolean accepts(TrackedTask task) {
        return task != null && task.tracked() && task.live() && task.epoch == epoch.get();
    }

    private static BatchId batchOf(TaskState state) {
        if (state instanceof Claimed claimed) {
            return claimed.batch();
        }
        if (state instanceof Running running) {
            return running.batch();
        }
        if (state instanceof Done done) {
            return done.batch();
        }
        if (state instanceof Cancelled cancelled) {
            return cancelled.batch();
        }
        return null;
    }

    private static Place placeOf(TaskState state) {
        if (state instanceof Minted) {
            return Place.MINTED;
        }
        if (state instanceof Queued) {
            return Place.QUEUED;
        }
        if (state instanceof HandedOff) {
            return Place.HANDED_OFF;
        }
        if (state instanceof Claimed claimed) {
            return claimed.place();
        }
        return null;
    }

    private static boolean claimable(TaskState state) {
        return state instanceof Minted || state instanceof Queued
            || state instanceof HandedOff;
    }

    private static boolean unenqueued(TaskState state) {
        return state instanceof Minted
            || state instanceof Claimed claimed && claimed.place() == Place.MINTED;
    }

    private static boolean terminal(TaskState state) {
        return state instanceof Done || state instanceof DoneUnclaimed
            || state instanceof Cancelled || state instanceof CancelledUnclaimed;
    }

    private static String key(String worldId, String mailbox) {
        return worldId + "@@@" + mailbox;
    }
}
