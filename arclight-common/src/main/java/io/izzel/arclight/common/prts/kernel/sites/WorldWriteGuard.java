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

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * The write right decision, reached from the write paths the host actually runs.
 *
 * <p>The host thread writing its own world takes the short path: one volatile read, one identity
 * compare and two counter cells, with nothing allocated and no record built. Every other writer -
 * another thread, or a thread nobody declared - takes the long path, where the attempt is named,
 * judged and remembered as the last decision of the readout.</p>
 *
 * <p>The long path never changes what a write does while enforcement is off: an attempt the
 * decision point refuses is counted with its code and the write still proceeds. With enforcement on
 * the same attempt is refused for real; with routing on an undeclared write is handed to the commit
 * segment instead, which applies it in the order frozen at planning time.</p>
 *
 * <p>Nothing here registers a thread on its own. An undeclared thread is remembered per thread so
 * classifying it stays allocation free, and that memory is deliberately kept outside the site
 * registry: the registry must not grow just because a thread appeared.</p>
 */
public final class WorldWriteGuard implements PrtsWorldWriteTaps.BlockWriteTap, IntentPayload {

    private final WritePathCounters counters;
    private final WriteAuthority authority;
    private final IntentQueue intents;
    private final IntentPayloadDirectory payloads;
    private final WriteLedger ledger;
    private final AtomicLong nextAttemptId = new AtomicLong(1L);
    private final AtomicLong nextPlanOrder = new AtomicLong(0L);
    private final Map<Thread, HolderIdentity> declared = new ConcurrentHashMap<>();
    private final LongAdder undeclaredThreads = new LongAdder();
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

    /**
     * Creates the guard.
     *
     * @param counters the per-path accounting
     * @param authority the decision point the long path asks
     * @param intents the controlled channel a handed-over write is frozen into
     * @param payloads the store of handed-over writes
     * @param ledger the ledger refusal codes are counted in
     */
    public WorldWriteGuard(WritePathCounters counters, WriteAuthority authority, IntentQueue intents,
                           IntentPayloadDirectory payloads, WriteLedger ledger) {
        this.counters = counters;
        this.authority = authority;
        this.intents = intents;
        this.payloads = payloads;
        this.ledger = ledger;
    }

    /**
     * Applies the switches the guard reads on every attempt.
     *
     * @param active       whether the write paths should judge at all
     * @param enforce      whether an undeclared writer is refused instead of recorded
     * @param routeIntents whether an undeclared write is handed to the commit segment
     * @param commitIntents whether the commit segment applies what it is handed
     * @param tickIndex    tick the next attempts belong to
     */
    public void refresh(boolean active, boolean enforce, boolean routeIntents, boolean commitIntents,
                        long tickIndex) {
        this.active = active;
        this.enforce = enforce;
        this.routing = routeIntents && commitIntents;
        this.tickIndex = tickIndex;
    }

    /** @return whether the write paths judge at all */
    public boolean active() {
        return active;
    }

    /** @return whether an undeclared writer is refused rather than recorded */
    public boolean enforcing() {
        return enforce;
    }

    /** @return whether an undeclared write is handed to the commit segment */
    public boolean routing() {
        return routing;
    }

    /**
     * Names the thread the host ticks the server on.
     *
     * @param thread the server thread
     * @param siteId the site identity its writes are counted under
     */
    public void bindServerThread(Thread thread, String siteId) {
        this.serverHolder = HolderIdentity.registered(siteId);
        this.serverThread = thread;
    }

    /** @return whether the server thread was named */
    public boolean serverThreadBound() {
        return serverThread != null;
    }

    /**
     * Declares a thread as a registered site.
     *
     * @param thread the thread
     * @param kind   the holder kind its writes are judged as
     * @param siteId the site identity
     */
    public void registerHolder(Thread thread, HolderKind kind, String siteId) {
        declared.put(thread, new HolderIdentity(kind, siteId));
    }

    /** Forgets every declared thread. Used by tests. */
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
            WriteVerdict verdict = authority.authorize(attemptOf(path, origin, holder, worldId));
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
    public RejectCode apply(WriteIntent intent) {
        Thread thread = Thread.currentThread();
        ThreadOrigin origin = thread == serverThread ? ThreadOrigin.MAIN : ThreadOrigin.WORKER;
        counters.noteAttempt(WritePath.KERNEL_COMMIT, origin, HolderKind.KERNEL);
        RejectCode code = payloads.apply(intent);
        counters.noteVerdict(WritePath.KERNEL_COMMIT, origin, HolderKind.KERNEL,
            code == null ? WriteDisposition.GRANT : WriteDisposition.DENY);
        if (code != null) {
            ledger.noteCode(code);
        }
        lastDecision = new WriteDecision(WritePath.KERNEL_COMMIT, origin, HolderKind.KERNEL,
            code == null ? WriteDisposition.GRANT : WriteDisposition.DENY, code, intent.siteId(),
            thread.getName(), intent.dstWorldId(), tickIndex);
        return code;
    }

    /** @return the per-path accounting */
    public WritePathCounters counters() {
        return counters;
    }

    /** @return the store of handed-over writes */
    public IntentPayloadDirectory payloads() {
        return payloads;
    }

    /** @return the last decision a write path took, or {@code null} when none was taken */
    public WriteDecision lastDecision() {
        return lastDecision;
    }

    /** @return threads that were classified without ever being declared */
    public long undeclaredThreads() {
        return undeclaredThreads.sum();
    }

    /** @return threads that were declared as a site */
    public int declaredHolders() {
        return declared.size();
    }

    /** Clears the live counters. Used by the readout reset and by tests. */
    public void resetReadings() {
        counters.reset();
        nextAttemptId.set(1L);
        nextPlanOrder.set(0L);
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
        long order = nextPlanOrder.getAndIncrement();
        WriteIntent intent = new WriteIntent(intents.nextIntentId(), worldId, worldId, path.key(), 0L,
            order, handle, "xdomain", holder.siteId());
        IntentQueue.EnqueueResult result = intents.enqueue(intent);
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
            .admitted(true)
            .build();
    }
}
