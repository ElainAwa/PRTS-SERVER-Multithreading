/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.WriteAttempt;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVerdict;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.intent.IntentPayload;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.intent.WriteIntent;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** The host thread writing its own world takes the short path: one volatile read, one identity
 * compare and two counter cells, with nothing allocated and no record built. */
public final class WorldWriteGuard implements PrtsWorldWriteTaps.BlockWriteTap, IntentPayload {

    private final WritePathCounters counters;
    private final WriteAuthority authority;
    private final IntentQueue intents;
    private final IntentPayloadDirectory payloads;
    private final WriteLedger ledger;
    private final WorldEpochs epochs = new WorldEpochs();
    private final AtomicLong nextAttemptId = new AtomicLong(1L);
    private final Map<Thread, HolderIdentity> declared = new ConcurrentHashMap<>();
    private final LongAdder undeclaredThreads = new LongAdder();
    private final LongAdder foreignCommits = new LongAdder();
    private final LongAdder staleWorldRefusals = new LongAdder();
    private final ThreadLocal<HolderIdentity> undeclared = ThreadLocal.withInitial(() -> {
        undeclaredThreads.increment();
        return HolderIdentity.unregistered("UNREGISTERED:" + Thread.currentThread().getName() + ":0");
    });

    private volatile boolean active;
    private volatile boolean enforce;
    private volatile boolean routing;
    private volatile Thread serverThread;
    private volatile HolderIdentity serverHolder = HolderIdentity.unregistered("server-thread-unbound");
    private volatile long tickIndex;
    private volatile WriteDecision lastDecision;
    private volatile ArrivalWriteTap arrivalTap;

    /** Creates the guard. */
    public WorldWriteGuard(WritePathCounters counters, WriteAuthority authority, IntentQueue intents,
                           IntentPayloadDirectory payloads, WriteLedger ledger) {
        this.counters = counters;
        this.authority = authority;
        this.intents = intents;
        this.payloads = payloads;
        this.ledger = ledger;
    }

    /** Remembers the arrival face the write attempts are counted into; none unless one is armed.
     * It is one volatile read on the short path and nothing else while no window is declared. */
    public void arrivalWriteTap(ArrivalWriteTap tap) {
        this.arrivalTap = tap;
    }

    /** The per tick write face: one call per write attempt, from the thread about to write. */
    public interface ArrivalWriteTap {

        /** One attempt on the short path, addressed by the level it was made against. */
        void writeAtLevel(Object levelRef);

        /** One attempt on the judged path, which already carries the world id. */
        void writeAtWorld(String worldId);
    }

    /** Routing is read here on its own: a routed write is frozen into the intent channel whether
     * or not the segment is walking, so the two switches answer different questions - which writes
     * are deferred, and when a deferred write lands. */
    public void refresh(boolean active, boolean enforce, boolean routeIntents, long tickIndex) {
        this.active = active;
        this.enforce = enforce;
        this.routing = routeIntents;
        this.tickIndex = tickIndex;
    }

    /** Records the worlds the platform reports as live, so a deferred write can be checked against
     * the world it was frozen for. */
    public void noteLiveWorlds(List<String> worldIds) {
        epochs.observe(worldIds);
    }

    public WorldEpochs worldEpochs() {
        return epochs;
    }

    public boolean active() {
        return active;
    }

    public boolean enforcing() {
        return enforce;
    }

    public boolean routing() {
        return routing;
    }

    /** Names the thread the host ticks the server on. */
    public void bindServerThread(Thread thread, String siteId) {
        this.serverHolder = HolderIdentity.registered(siteId);
        this.serverThread = thread;
    }

    public boolean serverThreadBound() {
        return serverThread != null;
    }

    /** Declares a thread as a registered site. */
    public void registerHolder(Thread thread, HolderKind kind, String siteId) {
        declared.put(thread, new HolderIdentity(kind, siteId));
    }

    /** Forgets every declared thread. */
    public void clearDeclaredHolders() {
        declared.clear();
    }

    @Override
    public int classifyBlockWrite(Object levelRef) {
        if (!active) {
            return PASS;
        }
        if (Thread.currentThread() == serverThread && serverHolder.registered()) {
            counters.noteAttempt(WritePath.BLOCK_WRITE, ThreadOrigin.MAIN, serverHolder.kind());
            counters.noteVerdict(WritePath.BLOCK_WRITE, ThreadOrigin.MAIN, serverHolder.kind(),
                WriteDisposition.GRANT);
            ArrivalWriteTap arrival = arrivalTap;
            if (arrival != null) {
                arrival.writeAtLevel(levelRef);
            }
            return PASS;
        }
        return JUDGE;
    }

    @Override
    public boolean admitBlockWrite(Object levelRef, String worldId, PrtsWorldWriteTaps.DeferredWrite deferred) {
        if (!active) {
            return true;
        }
        Thread thread = Thread.currentThread();
        ThreadOrigin origin = thread == serverThread ? ThreadOrigin.MAIN : ThreadOrigin.WORKER;
        HolderIdentity holder = holderOf(thread);
        WritePath path = WritePath.BLOCK_WRITE;
        counters.noteAttempt(path, origin, holder.kind());
        ArrivalWriteTap arrival = arrivalTap;
        if (arrival != null) {
            arrival.writeAtWorld(worldId);
        }
        WriteDisposition disposition;
        RejectCode code = null;
        boolean handedOver = false;
        if (!holder.registered()) {
            if (enforce) {
                disposition = WriteDisposition.DENY;
                code = RejectCode.WRITE_DENIED_NOT_OWNER;
            } else if (routing && deferred != null) {
                disposition = WriteDisposition.INTENT;
                code = handOver(path, origin, holder, worldId, deferred);
                handedOver = code == null;
                if (!handedOver) {
                    disposition = WriteDisposition.DENY;
                }
            } else {
                disposition = WriteDisposition.INTENT;
                code = RejectCode.WRITE_DENIED_NOT_OWNER;
            }
        } else {
            WriteVerdict verdict;
            try {
                verdict = authority.authorize(attemptOf(path, origin, holder, worldId));
            } catch (Throwable thrown) {
                // The decision point left the attempt without a verdict; the pair has to say so.
                counters.noteUnjudged(path, origin, holder.kind());
                throw thrown;
            }
            disposition = verdict.disposition();
            code = verdict.code();
        }
        counters.noteVerdict(path, origin, holder.kind(), disposition);
        if (code != null) {
            ledger.noteCode(code);
        }
        lastDecision = new WriteDecision(path, origin, holder.kind(), disposition, code, holder.siteId(),
            thread.getName(), worldId, tickIndex);
        return !handedOver && !(enforce && disposition == WriteDisposition.DENY);
    }

    @Override
    public Outcome apply(WriteIntent intent) {
        Thread thread = Thread.currentThread();
        ThreadOrigin origin = thread == serverThread ? ThreadOrigin.MAIN : ThreadOrigin.WORKER;
        counters.noteAttempt(WritePath.KERNEL_COMMIT, origin, HolderKind.KERNEL);
        Outcome outcome;
        Thread owner = serverThread;
        if (owner != null && thread != owner) {
            foreignCommits.increment();
            outcome = Outcome.retryable(RejectCode.WRITE_DENIED_NOT_OWNER);
        } else if (epochs.epochOf(intent.dstWorldId()) != intent.worldEpoch()) {
            staleWorldRefusals.increment();
            payloads.release(intent);
            outcome = Outcome.rejected(RejectCode.WORLD_LIFECYCLE_DENIED);
        } else {
            outcome = payloads.apply(intent);
        }
        RejectCode code = outcome.code();
        counters.noteVerdict(WritePath.KERNEL_COMMIT, origin, HolderKind.KERNEL,
            code == null ? WriteDisposition.GRANT : WriteDisposition.DENY);
        if (code != null) {
            ledger.noteCode(code);
        }
        lastDecision = new WriteDecision(WritePath.KERNEL_COMMIT, origin, HolderKind.KERNEL,
            code == null ? WriteDisposition.GRANT : WriteDisposition.DENY, code, intent.siteId(),
            thread.getName(), intent.dstWorldId(), tickIndex);
        return outcome;
    }

    @Override
    public void release(WriteIntent intent) {
        payloads.release(intent);
    }

    public WritePathCounters counters() {
        return counters;
    }

    public IntentPayloadDirectory payloads() {
        return payloads;
    }

    public WriteDecision lastDecision() {
        return lastDecision;
    }

    public long undeclaredThreads() {
        return undeclaredThreads.sum();
    }

    public int declaredHolders() {
        return declared.size();
    }

    public long foreignCommits() {
        return foreignCommits.sum();
    }

    public long staleWorldRefusals() {
        return staleWorldRefusals.sum();
    }

    /** Clears the live counters. */
    public void resetReadings() {
        counters.reset();
        nextAttemptId.set(1L);
        lastDecision = null;
    }

    private HolderIdentity holderOf(Thread thread) {
        if (thread == serverThread) {
            return serverHolder;
        }
        HolderIdentity holder = declared.get(thread);
        return holder == null ? undeclared.get() : holder;
    }

    private RejectCode handOver(WritePath path, ThreadOrigin origin, HolderIdentity holder,
                                String worldId, PrtsWorldWriteTaps.DeferredWrite deferred) {
        String handle = payloads.bind(path.key(), deferred);
        WriteIntent draft = WriteIntent.draft(intents.nextIntentId(), worldId, worldId, path.key(),
            0L, epochs.epochOf(worldId), handle, "xdomain", holder.siteId());
        IntentQueue.EnqueueResult result = intents.enqueue(draft);
        if (result.accepted()) {
            return null;
        }
        payloads.drop(handle);
        return RejectCode.QUEUE_CAP_EXCEEDED;
    }

    private WriteAttempt attemptOf(WritePath path, ThreadOrigin origin, HolderIdentity holder,
                                   String worldId) {
        Set<String> domains = Set.of(path.key());
        return WriteAttempt.builder(nextAttemptId.getAndIncrement(), worldId, holder.siteId())
            .holder(holder.kind(), holder.siteId(), Thread.currentThread().getName())
            .target(WriteLevel.REGION, path.key())
            .domains(domains, domains)
            .version(0L, tickIndex, 0L)
            .worldEpoch(epochs.epochOf(worldId))
            .admitted(true)
            .build();
    }

    /** Who is writing, as the write paths see them. A holder is a registered site, the kernel itself,
     * or a thread nobody ever declared. */
    public record HolderIdentity(HolderKind kind, String siteId) {

        public HolderIdentity {
            if (kind == null || siteId == null) {
                throw new IllegalArgumentException("a holder needs a kind and a site identity");
            }
        }

        public static HolderIdentity registered(String siteId) {
            return new HolderIdentity(HolderKind.REGISTERED, siteId);
        }

        public static HolderIdentity unregistered(String siteId) {
            return new HolderIdentity(HolderKind.UNREGISTERED, siteId);
        }

        public static HolderIdentity kernel(String siteId) {
            return new HolderIdentity(HolderKind.KERNEL, siteId);
        }

        public boolean registered() {
            return kind == HolderKind.REGISTERED || kind == HolderKind.KERNEL;
        }
    }

    /** The last decision a write path took, kept for a reader of the readout. The five elements a
     * diagnosis needs are here: the code, the site, the thread, the world and the tick. */
    public record WriteDecision(WritePath path, ThreadOrigin origin, HolderKind holder,
                                WriteDisposition disposition, RejectCode code, String siteId,
                                String threadRef, String worldId, long tickIndex) {
    }
}
