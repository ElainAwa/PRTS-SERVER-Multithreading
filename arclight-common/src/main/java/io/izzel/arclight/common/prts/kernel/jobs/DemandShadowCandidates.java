/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import io.izzel.arclight.common.prts.support.PrtsPipelineBatches;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Reads the chunk demands one freeze saw as shadow candidates: every column is a parameter of the one
 * call that asked for the chunk, the freeze that read it, or a pure function of those two. What one
 * window becomes is handed over through the two entry points of this type and only from the very list
 * the one read returned; this type seals nothing, mints nothing and derives no key a second time.
 */
public final class DemandShadowCandidates implements PrtsChunkDemand.DemandTap {

    public static final String DOMAIN = "chunk-materialize";

    /** What a capture that is no declaration reads; nothing may be sealed from it. */
    public static final String NOT_WIRED = "NOT_WIRED";

    /** A capture carrying every column a declaration carries reads this while no witness exists. */
    public static final String NOT_EXECUTABLE = "NOT_EXECUTABLE";

    public static final String LATE_BEFORE_FREEZE = "late-before-freeze";

    public static final int CAP = 64;

    /** A deferred demand is dropped after this many ticks; one freeze cycle is the bound. */
    public static final int PENDING_TTL_TICKS = 3;

    /** Deferred demands are a fraction of the ledger's task bound, never the whole register. */
    public static final int PENDING_CAPACITY_DIVISOR = 8;

    /** The identity one demand call was made under: the task's own fields and the batch state that
     * carried it, plus the name of that call. */
    public record TaskStamp(long taskOrdinal, String world, String mailbox, long cell, long mintTick,
                            long batchFreezeOrdinal, long planSequence, long demandToken) {

        public TaskStamp {
            world = world == null ? "" : world;
            mailbox = mailbox == null ? "" : mailbox;
        }
    }

    /** The one status of a task before its run-start seal, or an empty answer when the task cannot
     * be joined to this register. */
    public record TaskStatus(String status, TaskStamp stamp) {

        public TaskStatus {
            status = status == null ? "" : status;
        }

        /** @return whether this answer names one status of one joined task */
        public boolean present() {
            return !status.isEmpty() && stamp != null;
        }
    }

    /** The parameters of one ask for a chunk as that call named them, and the chunk it named packed
     * by the platform's own function. An ask that named no coordinates carries no chunk: the origin
     * is a chunk a caller named, and nothing here turns "not named" into it. */
    public record DemandDescriptor(String worldId, String targetStatus, boolean requireChunk,
                                   FrozenDeclarations.DeclaredChunk chunk) {

        public DemandDescriptor {
            worldId = worldId == null ? "" : worldId;
            targetStatus = targetStatus == null ? "" : targetStatus;
        }

        public static DemandDescriptor at(String worldId, int chunkX, int chunkZ, String status,
                                          boolean requireChunk) {
            return new DemandDescriptor(worldId, status, requireChunk,
                new FrozenDeclarations.DeclaredChunk(ChunkPos.asLong(chunkX, chunkZ)));
        }

        public static DemandDescriptor withoutChunk(String worldId, String status) {
            return new DemandDescriptor(worldId, status, false, null);
        }
    }

    public record Freeze(long planSequence, long planGeneration, FrozenDeclarations.FreezeKey key) {

        public Freeze {
            key = key == null ? FrozenDeclarations.FreezeKey.NONE : key;
        }
    }

    public interface LiveWorldEpoch {

        long epochOf(String worldId);
    }

    /** One shadow candidate: what one call asked for, and the freeze that read it. */
    public record Shadow(String worldId, String domainId, String nodeKey,
                         FrozenDeclarations.DeclaredChunk chunk, String targetStatus,
                         boolean requireChunk, long worldEpoch, long planSequence, long planGeneration,
                         FrozenDeclarations.FreezeKey freeze, boolean late) {

        public Shadow {
            worldId = worldId == null ? "" : worldId;
            domainId = domainId == null ? "" : domainId;
            nodeKey = nodeKey == null ? "" : nodeKey;
            targetStatus = targetStatus == null ? "" : targetStatus;
            freeze = freeze == null ? FrozenDeclarations.FreezeKey.NONE : freeze;
        }

        /** Whether this capture carries every column a declaration carries. Presence is the chunk
         * object and never its packed value: the origin packs as no chunk and both are legal. */
        public boolean declared() {
            return !worldId.isEmpty() && !domainId.isEmpty() && !nodeKey.isEmpty() && chunk != null
                && !targetStatus.isEmpty() && CommitLog.liveWorldEpoch(worldEpoch);
        }

        /** A complete capture still reads unexecutable: the freeze has no witness for it. */
        public String state() {
            return declared() && !late ? NOT_EXECUTABLE : NOT_WIRED;
        }

        public String refusal() {
            String epoch = CommitLog.worldEpochRefusal(worldEpoch);
            if (!epoch.isEmpty()) {
                return epoch;
            }
            return late ? LATE_BEFORE_FREEZE : "";
        }

        /** Whether the frozen generation is still the live one: re-reading and comparing is what a
         * drift check does, and writing the live value back would read a rebuilt world as current. */
        public boolean currentFor(long liveWorldEpoch) {
            return declared() && liveWorldEpoch == worldEpoch;
        }
    }

    /** Whether an operation already done before the freeze refuses the capture. It does: what a
     * freeze declares has to be outstanding at it. The other answer is not its negation - a handle
     * that is not done may have started - so it authorizes nothing either. */
    public static boolean lateBeforeFreeze(boolean doneBeforeFreeze) {
        return doneBeforeFreeze;
    }

    /** The key a captured demand would be declared under: a pure function of the capture. */
    public static String keyOf(DemandDescriptor descriptor) {
        DemandDescriptor named = descriptor == null
            ? new DemandDescriptor("", "", false, null) : descriptor;
        String chunk = named.chunk() == null ? "-" : Long.toString(named.chunk().packedPos());
        return DOMAIN + "/" + named.worldId() + "/" + chunk + "/" + named.targetStatus();
    }

    private record PendingDemand(long token, String status, long cell, String world,
                                 long planSequence, long createdTick) {
    }

    private static final class Slot {

        private DemandDescriptor descriptor;
        private boolean completionSeen;
        /** The call this capture belongs to, or no name at all for a capture that arrived through
         * the entries written before the stage existed. It matches a completion and is read into no
         * column. */
        private long token = PrtsChunkDemand.NO_TOKEN;
        /** The task identity the call was made under, or null; it is read into no column. */
        private Object taskToken;
    }

    /** One task's one status and the stamp it was joined through. */
    private static final class StatusEntry {

        private final PipelineBatchLedger.TrackedTask task;
        private final PipelineBatchLedger.BatchId batch;
        private final TaskStamp stamp;
        private final String status;
        private boolean conflicted;
        private boolean sealed;

        private StatusEntry(PipelineBatchLedger.TrackedTask task, PipelineBatchLedger.BatchId batch,
                            TaskStamp stamp, String status) {
            this.task = task;
            this.batch = batch;
            this.stamp = stamp;
            this.status = status;
        }
    }

    private final Slot[] ring = new Slot[CAP];
    private int head;
    private int size;
    /** Captures that arrived named but not yet confirmed: they are held here, never in the ring,
     * and a window cannot read them. Bounded by the same capacity as the ring, oldest first. */
    private final Slot[] staged = new Slot[CAP];
    private int stagedHead;
    private int stagedSize;
    private long stagedTotal;
    private long promoted;
    private long windows;
    private long captured;
    private long dropped;
    private long completions;
    private volatile List<Shadow> last = List.of();
    private long unexecutable;
    private long notWired;
    private long lateRefusals;
    private long unshaped;
    private long unlive;
    /** The register holds one entry per live running task and refuses above this bound. */
    private final int statusCapacity;
    private final Map<PipelineBatchLedger.TrackedTask, StatusEntry> statuses = new IdentityHashMap<>();
    /** Demands whose caller is a live task that has not been claimed yet: they wait for the target
     * cell's claimed window, keyed by the call name so one demand is consumed at most once. */
    private final int pendingCapacity;
    private final Map<Long, PendingDemand> pending = new LinkedHashMap<>();
    /** The plan current when a deferred demand arrived: a target window of another plan is not its owner. */
    private final Object planSource;
    private long statusOverflows;
    private long statusConflicts;
    private long statusLate;
    private long statusDuplicates;
    private long statusReclaimed;
    private long statusRefused;

    public DemandShadowCandidates() {
        this(PipelineBatchLedger.DEFAULT_PENDING_LIMIT, () -> 0L);
    }

    public DemandShadowCandidates(int taskCapacity) {
        this(taskCapacity, () -> 0L);
    }

    /** The register is sized to the owning ledger's pending limit: every entry is a live running
     * task and the ledger indexes no more than that, so a full register refuses, never evicts. */
    public DemandShadowCandidates(int taskCapacity, LongSupplier currentPlan) {
        if (taskCapacity < 1) {
            throw new IllegalArgumentException("task capacity must be positive");
        }
        this.statusCapacity = taskCapacity;
        this.pendingCapacity = Math.max(1, taskCapacity / PENDING_CAPACITY_DIVISOR);
        this.planSource = currentPlan;
        for (int index = 0; index < CAP; index++) {
            ring[index] = new Slot();
            staged[index] = new Slot();
        }
    }

    @Override
    public void demandStarted(String worldId, String status, boolean blocking) {
        // A synchronous ask is not a future and holds no pending operation a freeze could declare.
    }

    @Override
    public void demandFinished(String worldId, String status, boolean blocking, boolean satisfied) {
    }

    @Override
    public void futureOpened(String worldId, String status) {
        capture(DemandDescriptor.withoutChunk(worldId, status));
    }

    @Override
    public long futureOpenedAt() {
        return -1L;
    }

    @Override
    public void futureTaken(String worldId, String status) {
    }

    @Override
    public void futureCompleted(String worldId, String status, long startedNanos, boolean satisfied) {
        markCompleted(worldId, status);
    }

    @Override
    public void futureCompleted(String worldId, String status, long startedNanos, boolean satisfied,
                                long token) {
        markCompleted(token);
    }

    /** A named call reached the end of its registration. Only here does what it captured leave the
     * staging area, and it leaves it once: a second confirmation of the same name finds nothing to
     * promote, and neither does a confirmation of a name that was never seen. */
    @Override
    public void registrationConfirmed(String worldId, String status, long token, boolean ok) {
        if (!ok || token == PrtsChunkDemand.NO_TOKEN) {
            return;
        }
        promote(token);
    }

    @Override
    public void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                 boolean requireChunk) {
        capture(DemandDescriptor.at(worldId, chunkX, chunkZ, status, requireChunk));
    }

    @Override
    public void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                 boolean requireChunk, long token) {
        if (token == PrtsChunkDemand.NO_TOKEN) {
            // A call the issuer could not name cannot be confirmed, so staging it would hold a row
            // no window is allowed to read. The untokened entry remains the one that captures.
            return;
        }
        stage(DemandDescriptor.at(worldId, chunkX, chunkZ, status, requireChunk), token, null);
    }

    @Override
    public void demandDescriptor(String worldId, int chunkX, int chunkZ, String status,
                                 boolean requireChunk, long token, Object taskToken) {
        if (token == PrtsChunkDemand.NO_TOKEN) {
            return;
        }
        DemandDescriptor descriptor =
            DemandDescriptor.at(worldId, chunkX, chunkZ, status, requireChunk);
        stage(descriptor, token, taskToken);
        if (isDeferredCaller(taskToken) && descriptor.chunk() != null) {
            deferPending(descriptor, status, token, (PipelineBatchLedger.TrackedTask) taskToken);
        } else {
            rememberStatus(taskToken, status, token);
        }
    }

    /** A caller that has run but was never claimed: the target cell's claimed window is the owner
     * this demand is waiting for. Other states keep the existing refusal path. */
    private static boolean isDeferredCaller(Object taskToken) {
        if (!(taskToken instanceof PipelineBatchLedger.TrackedTask task) || !task.live()) {
            return false;
        }
        return task.state() instanceof PipelineBatchLedger.RunningUnclaimed;
    }

    private synchronized void deferPending(DemandDescriptor descriptor, String status, long token,
                                           PipelineBatchLedger.TrackedTask caller) {
        long now = PrtsPipelineBatches.tick();
        purgePending(now);
        if (token == PrtsChunkDemand.NO_TOKEN || pending.containsKey(token)) {
            return;
        }
        if (pending.size() >= pendingCapacity) {
            statusRefused++;
            return;
        }
        long cell = descriptor.chunk().packedPos();
        PipelineBatchLedger.TaskId id = caller.id();
        // A zero plan means the plan has not frozen yet (first tick). Only the TTL keeps such a
        // demand alive; the explicit target TaskId remains its owner.
        pending.put(token, new PendingDemand(token, status, cell, id.worldId(),
            demandPlan(), now));
    }

    private long demandPlan() {
        return planSource instanceof LongSupplier supplier ? supplier.getAsLong() : 0L;
    }

    private void purgePending(long now) {
        Iterator<Map.Entry<Long, PendingDemand>> walk = pending.entrySet().iterator();
        while (walk.hasNext()) {
            PendingDemand held = walk.next().getValue();
            long age = now - held.createdTick();
            if (age > PENDING_TTL_TICKS || age < 0L) {
                walk.remove();
                statusRefused++;
            }
        }
    }

    /** Binds every deferred demand of this claimed window's cell before the window reads its one
     * status. The target TaskId is the explicit owner: a window of another cell, batch or plan is
     * not a candidate and leaves the pending record for a later owner. */
    public synchronized int bindPending(PipelineBatchLedger.TrackedTask task,
                                        PipelineBatchLedger ledger) {
        return task == null ? 0 : bindPending(task, ledger, task.id().mailbox());
    }

    /** The same bind with an explicit target mailbox check: a TaskId of another mailbox is not the
     * window a caller named, even when its cell and plan agree. */
    public synchronized int bindPending(PipelineBatchLedger.TrackedTask task,
                                        PipelineBatchLedger ledger, String expectedMailbox) {
        if (task == null || expectedMailbox == null
            || !expectedMailbox.equals(task.id().mailbox())) {
            return 0;
        }
        if (ledger == null || !task.live()
            || !(task.state() instanceof PipelineBatchLedger.Running running)) {
            return 0;
        }
        PipelineBatchLedger.TaskId id = task.id();
        PipelineBatchLedger.BatchId batch = running.batch();
        if (batch == null || !batch.worldId().equals(id.worldId())
            || !batch.mailbox().equals(id.mailbox())
            || !ledger.bindingPublished(batch)
            || ledger.currentPlan() != batch.planSequence()
            || ledger.ownerOrdinal(batch, id.cell()) != id.ordinal()) {
            return 0;
        }
        purgePending(PrtsPipelineBatches.tick());
        int bound = 0;
        Iterator<Map.Entry<Long, PendingDemand>> walk = pending.entrySet().iterator();
        while (walk.hasNext()) {
            PendingDemand held = walk.next().getValue();
            // The target TaskId is the explicit owner: the caller's mailbox is not the target
            // window's mailbox in a cross-world future, so the window's own id is authoritative.
            // A zero demand plan is the early unfrozen plan: TTL is its only freshness bound;
            // the explicit target TaskId remains the owner. A non-zero plan must match.
            if (!held.world().equals(id.worldId()) || held.cell() != id.cell()
                || (held.planSequence() != 0L
                    && held.planSequence() != batch.planSequence())) {
                continue;
            }
            walk.remove();
            rememberStatus(task, held.status(), held.token());
            bound++;
        }
        return bound;
    }

    /** Number of demands waiting for a claimed target window, for tests and diagnostics. */
    public synchronized int pendingCount() {
        return pending.size();
    }

    /** Drops every deferred demand, for a reset or an uninstall; it neither seals nor publishes. */
    public synchronized void clearPending() {
        pending.clear();
    }

    private synchronized void capture(DemandDescriptor descriptor) {
        captureIntoRing(descriptor, PrtsChunkDemand.NO_TOKEN, null);
    }

    private void captureIntoRing(DemandDescriptor descriptor, long token, Object taskToken) {
        captured++;
        Slot slot;
        if (size < CAP) {
            slot = ring[(head + size) % CAP];
            size++;
        } else {
            slot = ring[head];
            head = (head + 1) % CAP;
            dropped++;
        }
        slot.descriptor = descriptor;
        slot.completionSeen = false;
        slot.token = token;
        slot.taskToken = taskToken;
    }

    /** Holds one named capture until its call confirms its registration. The area is bounded and
     * evicts its oldest entry, which is a discarded diagnosis and not a ring overwrite. */
    private synchronized void stage(DemandDescriptor descriptor, long token, Object taskToken) {
        stagedTotal++;
        Slot slot;
        if (stagedSize < CAP) {
            slot = staged[(stagedHead + stagedSize) % CAP];
            stagedSize++;
        } else {
            slot = staged[stagedHead];
            stagedHead = (stagedHead + 1) % CAP;
        }
        slot.descriptor = descriptor;
        slot.completionSeen = false;
        slot.token = token;
        slot.taskToken = taskToken;
    }

    /** Moves the one capture of this call into the ring and only that one. A call whose completion
     * was already seen enters marked, so the window it lands in is refused as a whole rather than
     * the call being revived; a call with no entry left -- evicted, already promoted, or never
     * staged -- is dropped here and no entry is made for it. */
    private synchronized void promote(long token) {
        for (int index = 0; index < stagedSize; index++) {
            Slot slot = staged[(stagedHead + index) % CAP];
            if (slot.descriptor == null || slot.token != token) {
                continue;
            }
            DemandDescriptor descriptor = slot.descriptor;
            boolean completed = slot.completionSeen;
            Object taskToken = slot.taskToken;
            removeStaged(index);
            captureIntoRing(descriptor, token, taskToken);
            ring[(head + size - 1 + CAP) % CAP].completionSeen = completed;
            promoted++;
            return;
        }
    }

    private void removeStaged(int index) {
        for (int at = index; at < stagedSize - 1; at++) {
            Slot from = staged[(stagedHead + at + 1) % CAP];
            Slot to = staged[(stagedHead + at) % CAP];
            to.descriptor = from.descriptor;
            to.completionSeen = from.completionSeen;
            to.token = from.token;
            to.taskToken = from.taskToken;
        }
        Slot last = staged[(stagedHead + stagedSize - 1) % CAP];
        last.descriptor = null;
        last.completionSeen = false;
        last.token = PrtsChunkDemand.NO_TOKEN;
        last.taskToken = null;
        stagedSize--;
    }

    /** Marks the captures a completion answers, by the name of the call for a named one and by the
     * world and the status for a capture that arrived through the entries written before the stage
     * existed. Either answer reaches only its own kind: a completion never crosses to a capture of
     * the other shape, and a name that matches nothing is dropped. */
    private synchronized void markCompleted(String worldId, String status) {
        completions++;
        String world = worldId == null ? "" : worldId;
        String state = status == null ? "" : status;
        for (int index = 0; index < size; index++) {
            Slot slot = ring[(head + index) % CAP];
            DemandDescriptor descriptor = slot.descriptor;
            if (descriptor != null && slot.token == PrtsChunkDemand.NO_TOKEN
                && descriptor.worldId().equals(world)
                && descriptor.targetStatus().equals(state)) {
                slot.completionSeen = true;
            }
        }
    }

    private synchronized void markCompleted(long token) {
        completions++;
        if (token == PrtsChunkDemand.NO_TOKEN) {
            return;
        }
        for (int index = 0; index < stagedSize; index++) {
            Slot slot = staged[(stagedHead + index) % CAP];
            if (slot.descriptor != null && slot.token == token) {
                slot.completionSeen = true;
                return;
            }
        }
        for (int index = 0; index < size; index++) {
            Slot slot = ring[(head + index) % CAP];
            if (slot.descriptor != null && slot.token == token) {
                slot.completionSeen = true;
                return;
            }
        }
    }

    /** Records the one status a live running task named. A second different value conflicts, a value
     * after the read is late, and a full register refuses the new task instead of dropping an old
     * one; the demand itself is never delayed or refused. */
    private synchronized void rememberStatus(Object taskToken, String status, long token) {
        if (status == null || status.isEmpty() || token == PrtsChunkDemand.NO_TOKEN
            || !(taskToken instanceof PipelineBatchLedger.TrackedTask task)) {
            return;
        }
        PipelineBatchLedger.TaskState state = task.state();
        if (!task.live() || !(state instanceof PipelineBatchLedger.Running running)) {
            statusRefused++;
            return;
        }
        PipelineBatchLedger.TaskId id = task.id();
        PipelineBatchLedger.BatchId batch = running.batch();
        if (batch == null || !batch.worldId().equals(id.worldId())
            || !batch.mailbox().equals(id.mailbox())) {
            statusRefused++;
            return;
        }
        StatusEntry entry = statuses.get(task);
        if (entry != null) {
            registerOnEntry(entry, status);
            return;
        }
        reclaimStatuses();
        while (statuses.size() >= statusCapacity) {
            PipelineBatchLedger.TrackedTask evict = null;
            for (StatusEntry held : statuses.values()) {
                PipelineBatchLedger.TaskState heldState = held.task.state();
                if (heldState instanceof PipelineBatchLedger.Done
                    || heldState instanceof PipelineBatchLedger.DoneUnclaimed) {
                    evict = held.task;
                    break;
                }
            }
            if (evict == null) {
                break;
            }
            statuses.remove(evict);
            statusReclaimed++;
        }
        if (statuses.size() >= statusCapacity) {
            statusOverflows++;
            return;
        }
        statuses.put(task, new StatusEntry(task, batch, stampOf(id, batch, token), status));
    }

    private void registerOnEntry(StatusEntry entry, String status) {
        if (entry.sealed) {
            statusLate++;
            return;
        }
        if (entry.conflicted) {
            statusConflicts++;
            return;
        }
        if (status.equals(entry.status)) {
            statusDuplicates++;
            return;
        }
        entry.conflicted = true;
        statusConflicts++;
    }

    private void reclaimStatuses() {
        Iterator<Map.Entry<PipelineBatchLedger.TrackedTask, StatusEntry>> walk =
            statuses.entrySet().iterator();
        while (walk.hasNext()) {
            StatusEntry entry = walk.next().getValue();
            PipelineBatchLedger.TaskState state = entry.task.state();
            if (!entry.task.live() || !(state instanceof PipelineBatchLedger.Running
                || state instanceof PipelineBatchLedger.Done
                || state instanceof PipelineBatchLedger.DoneUnclaimed)) {
                walk.remove();
                statusReclaimed++;
            }
        }
    }

    /** The one status of the task the demand was made under, read before its run-start seal. The
     * answer is empty unless the same live task still runs in the same batch the stamp names. */
    public synchronized TaskStatus statusFor(Object taskToken) {
        if (!(taskToken instanceof PipelineBatchLedger.TrackedTask task)) {
            return new TaskStatus("", null);
        }
        StatusEntry entry = statuses.get(task);
        if (entry == null) {
            return new TaskStatus("", null);
        }
        if (entry.task != task) {
            statuses.remove(task);
            return new TaskStatus("", null);
        }
        if (!task.live()) {
            statuses.remove(task);
            return new TaskStatus("", null);
        }
        PipelineBatchLedger.TaskState state = task.state();
        if (!(state instanceof PipelineBatchLedger.Running running)) {
            statuses.remove(task);
            return new TaskStatus("", null);
        }
        PipelineBatchLedger.TaskId id = task.id();
        if (!sameTask(entry.stamp, id)) {
            statuses.remove(task);
            return new TaskStatus("", null);
        }
        if (!sameBatch(entry.stamp, running.batch(), id)) {
            statuses.remove(task);
            return new TaskStatus("", null);
        }
        if (entry.conflicted || entry.status.isEmpty()) {
            return new TaskStatus("", null);
        }
        entry.sealed = true;
        return new TaskStatus(entry.status, entry.stamp);
    }

    /** The statuses retained for the settlement of one plan: the task reached Done and its status
     * is still the same task/batch the ledger minted, so a declared-level seal can read it once the
     * run-start window is gone. An entry is released by the settlement that names its batch. */
    public synchronized List<TaskStatus> retainedStatuses() {
        List<TaskStatus> retained = new ArrayList<>();
        for (Map.Entry<PipelineBatchLedger.TrackedTask, StatusEntry> held : statuses.entrySet()) {
            StatusEntry entry = held.getValue();
            PipelineBatchLedger.TrackedTask task = held.getKey();
            PipelineBatchLedger.TaskState state = task.state();
            if (!task.live() || entry.conflicted || entry.status.isEmpty()) {
                continue;
            }
            PipelineBatchLedger.BatchId batch;
            if (state instanceof PipelineBatchLedger.Done
                || state instanceof PipelineBatchLedger.DoneUnclaimed) {
                batch = task.batch();
            } else {
                continue;
            }
            if (!sameTask(entry.stamp, task.id()) || !sameBatch(entry.stamp, batch, task.id())) {
                continue;
            }
            retained.add(new TaskStatus(entry.status, entry.stamp));
        }
        return List.copyOf(retained);
    }

    /** Releases the retained statuses of one batch at its settlement. */
    public synchronized int releaseBatch(PipelineBatchLedger.BatchId batch) {
        if (batch == null) {
            return 0;
        }
        int released = 0;
        Iterator<Map.Entry<PipelineBatchLedger.TrackedTask, StatusEntry>> walk =
            statuses.entrySet().iterator();
        while (walk.hasNext()) {
            StatusEntry entry = walk.next().getValue();
            if (entry.batch.equals(batch)) {
                walk.remove();
                released++;
                statusReclaimed++;
            }
        }
        return released;
    }

    /** Releases every retained status of a plan the settlement has moved past. */
    public synchronized int releaseBefore(long planSequence) {
        int released = 0;
        Iterator<Map.Entry<PipelineBatchLedger.TrackedTask, StatusEntry>> walk =
            statuses.entrySet().iterator();
        while (walk.hasNext()) {
            StatusEntry entry = walk.next().getValue();
            if (entry.stamp.planSequence() < planSequence) {
                walk.remove();
                released++;
                statusReclaimed++;
            }
        }
        return released;
    }

    /** Drops every retained status: called where the ledger generation is reset. */
    public synchronized void resetStatuses() {
        statusReclaimed += statuses.size();
        statuses.clear();
        pending.clear();
    }

    /** How many status entries are retained for a settlement. */
    public synchronized int retainedCount() {
        return statuses.size();
    }

    private static TaskStamp stampOf(PipelineBatchLedger.TaskId id,
                                     PipelineBatchLedger.BatchId batch, long token) {
        return new TaskStamp(id.ordinal(), id.worldId(), id.mailbox(), id.cell(), id.mintTick(),
            batch.freezeOrdinal(), batch.planSequence(), token);
    }

    private static boolean sameTask(TaskStamp stamp, PipelineBatchLedger.TaskId id) {
        return stamp.taskOrdinal() == id.ordinal() && stamp.world().equals(id.worldId())
            && stamp.mailbox().equals(id.mailbox()) && stamp.cell() == id.cell()
            && stamp.mintTick() == id.mintTick();
    }

    private static boolean sameBatch(TaskStamp stamp, PipelineBatchLedger.BatchId batch,
                                     PipelineBatchLedger.TaskId id) {
        return batch != null && stamp.batchFreezeOrdinal() == batch.freezeOrdinal()
            && stamp.planSequence() == batch.planSequence()
            && batch.worldId().equals(id.worldId()) && batch.mailbox().equals(id.mailbox());
    }

    /** Reads the captures of one freeze and takes them: a window belongs to the freeze that read it.
     * The generation of a capture's own world is read once here and never re-read into the column. */
    public synchronized List<Shadow> readFor(Freeze freeze, LiveWorldEpoch epochs) {
        windows++;
        FrozenDeclarations.FreezeKey key = freeze == null
            ? FrozenDeclarations.FreezeKey.NONE : freeze.key();
        long planSequence = freeze == null ? 0L : freeze.planSequence();
        long planGeneration = freeze == null ? 0L : freeze.planGeneration();
        List<Shadow> shadows = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            Slot slot = ring[(head + index) % CAP];
            DemandDescriptor descriptor = slot.descriptor;
            slot.descriptor = null;
            if (descriptor == null) {
                continue;
            }
            long epoch = epochs == null ? CommitLog.NO_WORLD_EPOCH
                : epochs.epochOf(descriptor.worldId());
            Shadow shadow = new Shadow(descriptor.worldId(), DOMAIN, keyOf(descriptor),
                descriptor.chunk(), descriptor.targetStatus(), descriptor.requireChunk(), epoch,
                planSequence, planGeneration, key, lateBeforeFreeze(slot.completionSeen));
            slot.completionSeen = false;
            slot.token = PrtsChunkDemand.NO_TOKEN;
            slot.taskToken = null;
            count(shadow);
            shadows.add(shadow);
        }
        head = 0;
        size = 0;
        last = List.copyOf(shadows);
        return last;
    }

    /** The declarations one freeze hands to its intake: one per capture of the window that carries
     * every column a declaration carries, in the order the window gave them, and none at all when the
     * window holds a capture a completion marked - that refusal is the window's, so the captures it
     * did not mark are not handed over either. The key it declares is the capture's own node key,
     * which is the string the plan matches the candidate on.
     *
     * @param window the list the one read of that freeze returned
     * @return the declarations of that window, in its order
     * @throws IllegalStateException when the list is not the window the one read returned */
    public List<JobDeclaration> declarationsOf(List<Shadow> window) {
        requireTheWindowItRead(window);
        if (late(window)) {
            return List.of();
        }
        List<JobDeclaration> declarations = new ArrayList<>(window.size());
        for (Shadow shadow : window) {
            if (shadow != null && shadow.declared()) {
                declarations.add(declarationOf(shadow));
            }
        }
        return declarations;
    }

    /** The candidates of the window one read returned, for the merged list of the freeze that read
     * it: the same list the declarations above are derived from, and the same whole-window refusal.
     *
     * @param window the list the one read of that freeze returned
     * @return one candidate per handed capture of that window, in its order
     * @throws IllegalStateException when the list is not the window the one read returned */
    public List<FrozenDeclarations.Candidate> candidatesOf(List<Shadow> window) {
        requireTheWindowItRead(window);
        if (late(window)) {
            return List.of();
        }
        List<FrozenDeclarations.Candidate> candidates = new ArrayList<>(window.size());
        for (Shadow shadow : window) {
            if (shadow != null && shadow.declared()) {
                candidates.add(candidateOf(shadow));
            }
        }
        return candidates;
    }

    /** Whether a completion the freeze saw marked a capture of the window: the refusal is the
     * window's and is never a fact about one ask. */
    private static boolean late(List<Shadow> window) {
        for (Shadow shadow : window) {
            if (shadow != null && shadow.late()) {
                return true;
            }
        }
        return false;
    }

    /** One declaration per handed capture. It asks for no write right and names no site of its own:
     * what it adds to the plan is the node the candidate of that same capture is matched at. */
    private static JobDeclaration declarationOf(Shadow shadow) {
        return new JobDeclaration(shadow.nodeKey(), 0L, shadow.worldId(), shadow.domainId(), 0,
            List.of(), 0, shadow.nodeKey(), "world:" + shadow.worldId(), List.of(), List.of(),
            ShareClass.OTHER, JobDeclaration.SiteClass.PARALLEL, 0, 1, List.of());
    }

    /** One candidate per handed capture: the three names the declaration carries, the chunk and the
     * target state the call named, and no instance number - the freeze point mints that one and this
     * side never fills the column in. */
    private static FrozenDeclarations.Candidate candidateOf(Shadow shadow) {
        return new FrozenDeclarations.Candidate(shadow.worldId(), shadow.domainId(), shadow.nodeKey(),
            shadow.chunk(), shadow.targetStatus(), 0L, shadow.worldEpoch());
    }

    private void requireTheWindowItRead(List<Shadow> window) {
        if (window != last) {
            // An equal copy is not this freeze's window: two hand-offs that read two lists of one
            // freeze would declare the same node twice, and the seal would refuse the whole table.
            throw new IllegalStateException(
                "a hand-off derives from the window the one read returned");
        }
    }

    private void count(Shadow shadow) {
        if (shadow.state().equals(NOT_EXECUTABLE)) {
            unexecutable++;
        } else {
            notWired++;
        }
        if (shadow.late()) {
            lateRefusals++;
        }
        if (shadow.chunk() == null || shadow.targetStatus().isEmpty() || shadow.worldId().isEmpty()) {
            unshaped++;
        } else if (!CommitLog.liveWorldEpoch(shadow.worldEpoch())) {
            unlive++;
        }
    }

    public long windows() {
        return windows;
    }

    public long captured() {
        return captured;
    }

    public long dropped() {
        return dropped;
    }

    /** @return how many named captures were held for confirmation; with {@link #promoted()} and
     *     {@link #stagedHeld()} this is the fixture's own account of what the staging area cost,
     *     which is deliberately not {@link #dropped()} */
    public long staged() {
        return stagedTotal;
    }

    /** @return how many named captures reached the ring through their own confirmation */
    public long promoted() {
        return promoted;
    }

    public synchronized int stagedHeld() {
        return stagedSize;
    }

    public long completions() {
        return completions;
    }

    public synchronized int held() {
        return size;
    }

    public long lastRead() {
        return last.size();
    }

    /** What the last freeze read, read-only and bounded by the ring. */
    public List<Shadow> lastCandidates() {
        return last;
    }

    public long unexecutable() {
        return unexecutable;
    }

    public long notWired() {
        return notWired;
    }

    public long lateRefusals() {
        return lateRefusals;
    }

    public long unshaped() {
        return unshaped;
    }

    public long unlive() {
        return unlive;
    }

    int statusCapacity() {
        return statusCapacity;
    }

    synchronized int statusHeld() {
        return statuses.size();
    }

    long statusOverflows() {
        return statusOverflows;
    }

    long statusConflicts() {
        return statusConflicts;
    }

    long statusLate() {
        return statusLate;
    }

    long statusDuplicates() {
        return statusDuplicates;
    }

    long statusReclaimed() {
        return statusReclaimed;
    }

    long statusRefused() {
        return statusRefused;
    }

    public synchronized void reset() {
        for (Slot slot : ring) {
            slot.descriptor = null;
            slot.completionSeen = false;
            slot.taskToken = null;
        }
        for (Slot slot : staged) {
            slot.descriptor = null;
            slot.completionSeen = false;
            slot.token = PrtsChunkDemand.NO_TOKEN;
            slot.taskToken = null;
        }
        stagedHead = 0;
        stagedSize = 0;
        stagedTotal = 0L;
        promoted = 0L;
        head = 0;
        size = 0;
        windows = 0L;
        captured = 0L;
        dropped = 0L;
        completions = 0L;
        last = List.of();
        unexecutable = 0L;
        notWired = 0L;
        lateRefusals = 0L;
        unshaped = 0L;
        unlive = 0L;
        statuses.clear();
        statusOverflows = 0L;
        statusConflicts = 0L;
        statusLate = 0L;
        statusDuplicates = 0L;
        statusReclaimed = 0L;
        statusRefused = 0L;
    }
}
