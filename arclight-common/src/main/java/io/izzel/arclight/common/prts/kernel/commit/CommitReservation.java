/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The one holder that mints a ticket. It takes the three reservations a controlled write needs
 * before the first side effect — the place the frozen plan gave the line, a ring slot and the room of
 * the terminal record — and it is the only place that ends one: a ticket is filled by
 * {@link #finalize}, given back by {@link #release} or voided by a reset of its lifecycle.
 *
 * <p>Every control operation is confined to the owner thread that bound itself here. A call from
 * another thread is refused and counted: the reservation never steals the thread of the owner, and
 * the ledgers are left exactly where they were.
 *
 * <p>A refusal takes nothing: the ring slot and the room are given back, the three levels read what
 * they read before the call, and no publish action has run. A terminal the log refuses after the side
 * effect has run is not a refusal of the reservation: the item leaves the publishing state, stays in
 * flight and is readable with the reason it is waiting. */
public final class CommitReservation {

    /** What one reservation did. A refusal says which check refused it. */
    public record ReserveResult(boolean admitted, RejectCode code, String diagnostic,
                                CommitTicket ticket) {
    }

    /** What one terminal did. */
    public record FinalizeResult(boolean finalized, RejectCode code, String diagnostic,
                                 CommitTicket.Terminal terminal, int successBatch, long unitCount) {
    }

    /** What one give-back did. */
    public record ReleaseResult(boolean released, String diagnostic) {
    }

    /** What a reset of the lifecycle did: how many reservations were voided, and how many lines
     * were publishing and were left alone. */
    public record ResetResult(int voided, int publishing) {
    }

    /** One entry of the bounded terminal ledger: every ticket that ended, with the state it ended
     * in and the units it published. */
    public record TerminalEntry(long ticketId, long effectRef, String state, long planSequence,
                                int position, int unitCount, int successBatch, String note) {
    }

    private enum Phase {
        RESERVED,
        PUBLISHING,
        /** The side effect has run and the terminal line could not be written: the item waits for a
         * disposition instead of reading as a publish that never came back. */
        UNRECORDED,
        FINISHED
    }

    /** One item whose terminal line is not written: the ticket, the place it holds, why the record
     * was refused and how often it was tried. The table is bounded by the reservations still in
     * flight, and an entry leaves it when the ticket ends. */
    public record Pending(long ticketId, long effectRef, long planSequence, int position,
                          RejectCode code, String diagnostic, int attempts) {
    }

    /** What closing the holder did: the items it gave back, the ones whose side effect has run and
     * whose line is not written, and the ones still publishing. */
    public record CloseResult(int givenBack, int unrecorded, int publishing) {
    }

    private record Refusal(RejectCode code, String diagnostic) {
    }

    /** What one request was judged against: the piece that refused it, or the freeze the seat it
     * claims was verified in. The table is carried out of the check so the ticket can hold it. */
    private record Judged(Refusal refusal, FrozenDeclarations declarations) {

        static Judged refused(Refusal refusal) {
            return new Judged(refusal, FrozenDeclarations.none());
        }
    }

    private static final int LEDGER_LIMIT = 64;

    private final CommitLog log;
    private final EffectStore store;
    private final CommitLog.OrderSource plans;
    private final CommitNamespaces.Namespace controller;
    private final CommitNamespaces.ResetEpochs epochs;
    private final Map<Long, CommitTicket> tickets = new LinkedHashMap<>();
    private final Map<Long, Phase> phases = new LinkedHashMap<>();
    private final Map<Long, Pending> pendings = new LinkedHashMap<>();
    /** What every ticket in flight owes: the freeze its seat was verified in. It is handed to the log
     * when the terminal line is stored and given up when the ticket is given back. */
    private final Map<Long, IdentityWitness.Obligation> obligations = new LinkedHashMap<>();
    private final Deque<TerminalEntry> ledger = new ArrayDeque<>();
    private final Deque<Long> finished = new ArrayDeque<>();
    private Thread ownerThread = Thread.currentThread();
    private long ticketIds;
    private long reserveSuccess;
    private long finalized;
    private long released;
    private long publishedTotal;
    private long partialTotal;
    private long failedTotal;
    private long unitTotal;
    private long partialUnits;
    private long failedUnits;
    private long doubleFinalize;
    private long unknownTicket;
    private long crossLogRefusals;
    private long staleRefusals;
    private long offThreadRefusals;
    private long requestRefusals;
    private long reservationRefusals;
    private long resetVoided;
    private long resetDuringPublish;
    private long publishRefusals;
    private long ledgerEvicted;
    private long witnessRefusals;
    private long driftRefusals;
    private boolean closed;

    public CommitReservation(CommitLog log, EffectStore store, CommitLog.OrderSource plans,
                             String ownerId, CommitNamespaces.ResetEpochs epochs) {
        this.log = log;
        this.store = store;
        this.plans = plans;
        this.epochs = epochs;
        this.controller = new CommitNamespaces.Namespace(ownerId, CommitNamespaces.Lifetimes.mint(),
            epochs.current(), CommitNamespaces.NO_THREAD);
    }

    /** Binds the thread every control operation of this holder must run on. The owner is not moved
     * while a reservation is in flight: the holder and the controller that judges by it must not end
     * up naming two threads. */
    public boolean bindOwnerThread(Thread thread) {
        if (!tickets.isEmpty()) {
            return false;
        }
        this.ownerThread = thread == null ? Thread.currentThread() : thread;
        return true;
    }

    public Thread ownerThread() {
        return ownerThread;
    }

    public boolean ownsThread() {
        return Thread.currentThread() == ownerThread;
    }

    public CommitNamespaces.Namespace controller() {
        return new CommitNamespaces.Namespace(controller.ownerId(), controller.lifetime(),
            epochs.current(), CommitNamespaces.NO_THREAD);
    }

    public CommitLog log() {
        return log;
    }

    /** The reset generation of the lifecycle a reservation is minted under. */
    public long epoch() {
        return epochs.current();
    }

    /** Enters the next lifecycle. Called by the controller that reached the owner safe point: a
     * call from another thread is refused and counted, and the generation stays where it was. */
    public long advanceReset() {
        if (Thread.currentThread() != ownerThread) {
            offThreadRefusals++;
            return epochs.current();
        }
        return epochs.advance();
    }

    public EffectStore store() {
        return store;
    }

    /** Takes the place, the ring slot and the room, or none of them. */
    public ReserveResult reserve(CommitTicket.ReserveRequest request) {
        if (Thread.currentThread() != ownerThread) {
            offThreadRefusals++;
            return new ReserveResult(false, RejectCode.COUNTER_MISSING, "off-owner-thread", null);
        }
        if (closed) {
            requestRefusals++;
            return new ReserveResult(false, RejectCode.QUEUE_CAP_EXCEEDED, "lifecycle-closed", null);
        }
        Judged judged = validate(request);
        if (judged.refusal() != null) {
            requestRefusals++;
            return new ReserveResult(false, judged.refusal().code(), judged.refusal().diagnostic(), null);
        }
        if (!log.placeAscending(request.worldId(), request.domainId(), request.planSequence(),
            request.position())) {
            // The place the plan gave this line is one the pair has already passed: a reservation for
            // it could only end in a refusal, so none is taken.
            requestRefusals++;
            return new ReserveResult(false, RejectCode.COMMIT_ORDER_VIOLATION, "place-passed", null);
        }
        CommitTicket.Covered covered = covered(request);
        CommitLog.RingSlot ringSlot = log.reserveSlot(request.worldId(), request.domainId(),
            request.planSequence(), request.position());
        if (ringSlot == null) {
            reservationRefusals++;
            return new ReserveResult(false, RejectCode.QUEUE_CAP_EXCEEDED, "ring-room", null);
        }
        EffectStore.CapacityResult room = store.reserveCapacity(request.identity().effectRef(),
            covered.maxRecords(), covered.maxBytes());
        if (!room.admitted()) {
            log.releaseSlot(ringSlot);
            reservationRefusals++;
            return new ReserveResult(false, room.code() == null ? RejectCode.QUEUE_CAP_EXCEEDED
                : room.code(), "effect-room", null);
        }
        CommitTicket ticket = new CommitTicket(++ticketIds, log.logLifetime(),
            new CommitLog.Batch(request.worldId(), request.domainId(), request.nodeKey(),
                request.kind(), request.expectedVersion(), request.writeSetDigest(), request.rows()),
            request.identity(), request.planSequence(), request.position(), ringSlot, room.slot(),
            controller());
        tickets.put(ticket.ticketId(), ticket);
        phases.put(ticket.ticketId(), Phase.RESERVED);
        obligations.put(ticket.ticketId(), log.identitySource().oblige(judged.declarations()));
        reserveSuccess++;
        return new ReserveResult(true, null, "reserved", ticket);
    }

    private Judged validate(CommitTicket.ReserveRequest request) {
        if (request == null || request.identity() == null || request.worldId() == null
            || request.domainId() == null || request.worldId().isEmpty() || request.domainId().isEmpty()
            || request.nodeKey() == null || request.nodeKey().isEmpty() || request.kind() == null) {
            return Judged.refused(new Refusal(RejectCode.COUNTER_MISSING, "request-incomplete"));
        }
        if (store.halted()) {
            return Judged.refused(new Refusal(RejectCode.QUEUE_CAP_EXCEEDED, "log-integrity-fault"));
        }
        CommitLog.Identity identity = request.identity();
        if (!identity.declaredTyped() || !CommitLog.supportedSchema(identity.schemaVersion())) {
            return Judged.refused(new Refusal(RejectCode.VERSION_MISMATCH, "identity-undeclared"));
        }
        if (!CommitLog.liveWorldEpoch(identity.worldEpoch())) {
            return Judged.refused(new Refusal(RejectCode.VERSION_MISMATCH, "world-epoch-not-live"));
        }
        if (request.controller() == null
            || !request.controller().lifecycleKey().equals(controller().lifecycleKey())) {
            // The request names the lifecycle it is taken under. Whether the producer and the world
            // are really the ones this line writes to is a question only a bound host can answer, and
            // it is not asked here.
            return Judged.refused(new Refusal(RejectCode.VERSION_MISMATCH, "controller-namespace-mismatch"));
        }
        CommitLog.Resolved resolved = plans == null ? null
            : plans.resolve(request.worldId(), request.domainId(), request.nodeKey());
        if (resolved == null || resolved.planSequence() != request.planSequence()
            || resolved.position() != request.position()) {
            return Judged.refused(new Refusal(RejectCode.COMMIT_ORDER_VIOLATION, "plan-ref-mismatch"));
        }
        if (identity.authorizationGeneration() != request.expectedVersion()
            || identity.authorizationGeneration() == CommitLog.NO_AUTHORIZATION_GENERATION) {
            return Judged.refused(new Refusal(RejectCode.VERSION_MISMATCH, "authorization-mismatch"));
        }
        // The seat the plan resolved is what the sealed declarations are looked up by, so this runs
        // after the plan reference was checked and before a ring slot or a room is taken. The
        // namespace agreement is asked of the holder's current controller, not of the request.
        FrozenDeclarations sealed = log.identitySource().declarations(request.worldId(),
            request.domainId(), resolved.planSequence());
        IdentityWitness.Verdict witness = IdentityWitness.verify(sealed, resolved, request.worldId(),
            request.domainId(), request.nodeKey(), identity, controller());
        if (!witness.accepted()) {
            witnessRefusals++;
            return Judged.refused(new Refusal(RejectCode.VERSION_MISMATCH, witness.reason()));
        }
        if (log.instanceCollision(request.worldId(), request.domainId(), identity)) {
            return Judged.refused(new Refusal(RejectCode.VERSION_MISMATCH, "instance-collision"));
        }
        if (placeHeld(request.worldId(), request.domainId(), request.planSequence(),
            request.position())) {
            return Judged.refused(new Refusal(RejectCode.COMMIT_ORDER_VIOLATION, "duplicate-place"));
        }
        for (CommitTicket.Terminal terminal : CommitTicket.Terminal.values()) {
            EffectStore.Effect variant = request.variantOf(terminal);
            if (variant == null || variant.effectRef() != identity.effectRef()
                || EffectStore.normalizedRows(variant.rows()).size() != request.rows()) {
                return Judged.refused(new Refusal(RejectCode.VERSION_MISMATCH, "terminal-payload-mismatch"));
            }
        }
        // The source judgement: the record this plan's own grant published, read once for the pair
        // the request names. A record of another plan or of another lifecycle answers no value, and
        // the number the request wears has to be the number that record answered - the live slot
        // agreeing with the request proves nothing about which record the number came from. It
        // stands last among the judgements so that no refusal that used to be reported earlier is
        // now reported as a source refusal instead, and it still runs before any slot or room is
        // taken, which happens after this method returns.
        IdentityWitness.Authorization authorization = log.identitySource().liveAuthorization(
            request.planSequence(), request.controller(), request.worldId(), request.domainId(),
            request.nodeKey());
        if (!authorization.admitted()
            || authorization.source().generation() != identity.authorizationGeneration()) {
            return Judged.refused(new Refusal(RejectCode.VERSION_MISMATCH, "authorization-mismatch"));
        }
        return new Judged(null, sealed);
    }

    /** Whether a reservation in flight already holds this place of this pair. */
    public boolean placeHeld(String worldId, String domainId, long planSequence, int position) {
        for (CommitTicket held : tickets.values()) {
            if (held.planSequence() == planSequence && held.position() == position
                && held.batch().worldId().equals(worldId) && held.batch().domainId().equals(domainId)) {
                return true;
            }
        }
        return false;
    }

    /** The bound the reservation charges: every terminal the line may report with every note its
     * outcome column may carry. */
    static CommitTicket.Covered covered(CommitTicket.ReserveRequest request) {
        long bytes = 0L;
        int records = 0;
        for (CommitTicket.Terminal terminal : CommitTicket.Terminal.values()) {
            EffectStore.Effect variant = request.variantOf(terminal);
            records = Math.max(records, EffectStore.normalizedRows(variant.rows()).size());
            for (String note : CommitTicket.NOTES) {
                String label = note.isEmpty() ? terminal.name() : terminal.name() + "|" + note;
                bytes = Math.max(bytes, EffectStore.chargeOf(variant.operationKind(), label,
                    variant.rows()));
            }
        }
        return new CommitTicket.Covered(records, (int) Math.min(Integer.MAX_VALUE, bytes));
    }

    /** Says that the side effect of this ticket is about to run. It runs on the owner thread like
     * every other control operation, for the handle this holder minted and for the lifecycle it was
     * minted under, and only where the pair may run it: a reservation whose place an earlier one of
     * the pair still holds does not start its side effect at all. Every refusal here leaves the
     * phases, the reservations and the places exactly where they were. */
    public boolean beginPublish(CommitTicket ticket) {
        if (Thread.currentThread() != ownerThread) {
            offThreadRefusals++;
            return false;
        }
        if (ticket == null || tickets.get(ticket.ticketId()) != ticket) {
            unknownTicket++;
            return false;
        }
        if (ticket.logLifetime() != log.logLifetime() || ticket.effectSlot().storeId() != store.storeId()
            || ticket.controller().lifetime() != controller.lifetime()
            || ticket.controller().resetEpoch() != epochs.current()) {
            crossLogRefusals++;
            return false;
        }
        if (frozenValuesMoved(ticket)) {
            // The generations the line was frozen under are read again here and never written back:
            // a world that moved on or a grant that was retired refuses the start instead of the
            // frozen columns being replaced by today's values.
            driftRefusals++;
            return false;
        }
        if (phases.get(ticket.ticketId()) != Phase.RESERVED) {
            return false;
        }
        if (!log.placeRunnable(ticket.batch().worldId(), ticket.batch().domainId(),
            ticket.planSequence(), ticket.position())) {
            // An earlier place of this pair has not ended: the side effect of a later one waits for it
            // instead of running ahead of the line the ring is about to take.
            publishRefusals++;
            return false;
        }
        phases.put(ticket.ticketId(), Phase.PUBLISHING);
        return true;
    }

    /** Whether the world generation or the grant the line was frozen under has moved since. A plain
     * line declares neither, so nothing of it can drift. The current validity of the grant is its
     * own criterion: it is the live slot read here, against the number the line ORIGINALLY froze,
     * and it is never answered from this plan's own record - a record whose slot moved on without a
     * new grant still carries the old number, so a record read here could not see the drift at all.
     * The answer taken at the reservation is never reused as this check either. */
    private boolean frozenValuesMoved(CommitTicket ticket) {
        CommitLog.Identity identity = ticket.identity();
        if (identity.schemaVersion() != CommitLog.SCHEMA_TYPED) {
            return false;
        }
        IdentityWitness.Source source = log.identitySource();
        if (source == null) {
            return true;
        }
        if (source.liveWorldEpoch(ticket.batch().worldId()) != identity.worldEpoch()) {
            return true;
        }
        IdentityWitness.Authorization authorization = source.liveAuthorization(
            ticket.planSequence(), ticket.controller(), ticket.batch().worldId(),
            ticket.batch().domainId(), ticket.batch().nodeKey());
        // A record that is no longer this plan's own refuses the start as well: the number the line
        // holds cannot be confirmed against a source that answers nothing.
        return !authorization.refusal().isEmpty() || !authorization.validity().carries()
            || authorization.validity().generation() != identity.authorizationGeneration();
    }

    /** Writes the terminal line of a publishing ticket from the reservations it holds. */
    public FinalizeResult finalize(CommitTicket ticket, CommitTicket.Outcome outcome) {
        if (Thread.currentThread() != ownerThread) {
            offThreadRefusals++;
            return refused(RejectCode.COUNTER_MISSING, "off-owner-thread", null);
        }
        if (ticket == null || outcome == null) {
            unknownTicket++;
            return refused(RejectCode.COUNTER_MISSING, "unknown-ticket", null);
        }
        if (finished.contains(ticket.ticketId())) {
            doubleFinalize++;
            return refused(RejectCode.COUNTER_MISSING, "already-finalized", outcome.terminal());
        }
        if (tickets.get(ticket.ticketId()) != ticket) {
            unknownTicket++;
            return refused(RejectCode.COUNTER_MISSING, "unknown-ticket", outcome.terminal());
        }
        if (ticket.logLifetime() != log.logLifetime() || ticket.effectSlot().storeId() != store.storeId()
            || ticket.controller().lifetime() != controller.lifetime()) {
            crossLogRefusals++;
            return refused(RejectCode.COUNTER_MISSING, "cross-log-handle", outcome.terminal());
        }
        if (ticket.controller().resetEpoch() != epochs.current()) {
            staleRefusals++;
            if (phases.get(ticket.ticketId()) == Phase.PUBLISHING) {
                // Its side effect has run and this lifecycle can no longer write its line: the item is
                // carried as one waiting for a disposition, not as a publish still in flight.
                phases.put(ticket.ticketId(), Phase.UNRECORDED);
                pend(ticket, RejectCode.COUNTER_MISSING, "stale-ticket");
            }
            return refused(RejectCode.COUNTER_MISSING, "stale-ticket", outcome.terminal());
        }
        if (phases.get(ticket.ticketId()) == Phase.FINISHED) {
            doubleFinalize++;
            return refused(RejectCode.COUNTER_MISSING, "already-finalized", outcome.terminal());
        }
        Phase phase = phases.get(ticket.ticketId());
        if (phase != Phase.PUBLISHING && phase != Phase.UNRECORDED) {
            requestRefusals++;
            return refused(RejectCode.COMMIT_ORDER_VIOLATION, "not-publishing", outcome.terminal());
        }
        String malformed = malformedOutcome(outcome, ticket);
        if (malformed != null) {
            // A terminal that cannot be read is not a terminal. The item stops reading as a publish and
            // is carried as one whose line is not written, with the reason it is waiting on.
            phases.put(ticket.ticketId(), Phase.UNRECORDED);
            pend(ticket, RejectCode.VERSION_MISMATCH, malformed);
            return refused(RejectCode.VERSION_MISMATCH, malformed, outcome.terminal());
        }
        CommitLog.Verdict verdict;
        try {
            verdict = log.recordTicket(ticket, outcome);
        } catch (RuntimeException | Error thrown) {
            phases.put(ticket.ticketId(), Phase.UNRECORDED);
            pend(ticket, RejectCode.COUNTER_MISSING, "record-threw");
            return refused(RejectCode.COUNTER_MISSING, "record-threw", outcome.terminal());
        }
        if (!verdict.logged()) {
            // The side effect has run and the line is not written. The ticket leaves the publishing
            // state for the one that says so and is readable as an item still waiting for its line;
            // a later call may still write it, and the counters say how often it was tried.
            RejectCode refusedCode = verdict.code() == null ? RejectCode.COUNTER_MISSING : verdict.code();
            phases.put(ticket.ticketId(), Phase.UNRECORDED);
            pend(ticket, refusedCode, "record-refused");
            return refused(refusedCode, "record-refused", outcome.terminal());
        }
        phases.remove(ticket.ticketId());
        tickets.remove(ticket.ticketId());
        pendings.remove(ticket.ticketId());
        remember(ticket.ticketId());
        handOver(ticket.ticketId());
        finalized++;
        int successBatch = outcome.terminal() == CommitTicket.Terminal.PUBLISHED ? 1 : 0;
        switch (outcome.terminal()) {
            case PUBLISHED -> {
                publishedTotal++;
                unitTotal += outcome.unitCount();
            }
            case PARTIAL -> {
                partialTotal++;
                partialUnits += outcome.unitCount();
            }
            case FAILED -> {
                failedTotal++;
                failedUnits += outcome.unitCount();
            }
        }
        append(new TerminalEntry(ticket.ticketId(), ticket.identity().effectRef(),
            outcome.terminal().name(), ticket.planSequence(), ticket.position(),
            outcome.unitCount(), successBatch, outcome.note()));
        return new FinalizeResult(true, null, "recorded", outcome.terminal(), successBatch,
            outcome.unitCount());
    }

    /** Gives a reservation back without a line. Only a ticket that never published can go back. */
    public ReleaseResult release(CommitTicket ticket, CommitTicket.ReleaseReason reason) {
        if (Thread.currentThread() != ownerThread) {
            offThreadRefusals++;
            return new ReleaseResult(false, "off-owner-thread");
        }
        if (ticket == null || tickets.get(ticket.ticketId()) != ticket) {
            unknownTicket++;
            return new ReleaseResult(false, "unknown-ticket");
        }
        if (ticket.controller().resetEpoch() != epochs.current()) {
            staleRefusals++;
            return new ReleaseResult(false, "stale-ticket");
        }
        Phase phase = phases.get(ticket.ticketId());
        if (phase == Phase.FINISHED) {
            doubleFinalize++;
            return new ReleaseResult(false, "already-finalized");
        }
        if (phase != Phase.RESERVED) {
            requestRefusals++;
            return new ReleaseResult(false, phase == Phase.UNRECORDED ? "unrecorded" : "publishing");
        }
        giveBack(ticket);
        released++;
        append(new TerminalEntry(ticket.ticketId(), ticket.identity().effectRef(),
            "RELEASED", ticket.planSequence(), ticket.position(), 0, 0,
            reason == null ? "" : reason.name()));
        return new ReleaseResult(true, "released");
    }

    /** Asks the frozen plan again for a ticket that has not published. A plan that no longer gives
     * this line its place is not a reason to move the line: the reservation is given back. */
    public ReleaseResult revalidate(CommitTicket ticket) {
        if (Thread.currentThread() != ownerThread) {
            offThreadRefusals++;
            return new ReleaseResult(false, "off-owner-thread");
        }
        if (ticket == null || tickets.get(ticket.ticketId()) != ticket
            || phases.get(ticket.ticketId()) != Phase.RESERVED) {
            return new ReleaseResult(false, "not-reserved");
        }
        CommitLog.Resolved resolved = plans == null ? null
            : plans.resolve(ticket.batch().worldId(), ticket.batch().domainId(), ticket.batch().nodeKey());
        if (resolved != null && resolved.planSequence() == ticket.planSequence()
            && resolved.position() == ticket.position()) {
            return new ReleaseResult(false, "still-planned");
        }
        ReleaseResult gone = release(ticket, CommitTicket.ReleaseReason.PLAN_REBUILT);
        return new ReleaseResult(gone.released(), gone.released() ? "plan-rebuilt" : gone.diagnostic());
    }

    private void giveBack(CommitTicket ticket) {
        store.releaseCapacity(ticket.effectSlot());
        log.releaseSlot(ticket.ringSlot());
        phases.remove(ticket.ticketId());
        tickets.remove(ticket.ticketId());
        pendings.remove(ticket.ticketId());
        // A reservation that wrote no line owes no freeze: the obligation ends with the ticket.
        settle(ticket.ticketId());
    }

    /** Gives the obligation of one ticket to the log once its terminal line is stored: from there the
     * unread line is what keeps the freeze readable, and the log settles it when it reads it. */
    private void handOver(long ticketId) {
        IdentityWitness.Obligation held = obligations.remove(ticketId);
        if (held != null) {
            log.holdUnread(held);
        }
    }

    private void settle(long ticketId) {
        IdentityWitness.Obligation held = obligations.remove(ticketId);
        if (held != null) {
            held.settle();
        }
    }

    private void pend(CommitTicket ticket, RejectCode code, String diagnostic) {
        Pending held = pendings.get(ticket.ticketId());
        pendings.put(ticket.ticketId(), new Pending(ticket.ticketId(), ticket.identity().effectRef(),
            ticket.planSequence(), ticket.position(), code, diagnostic,
            held == null ? 1 : held.attempts() + 1));
    }

    /** Why a terminal cannot be read as one, or null when it can. A unit count is a number of
     * published things: a negative one is not a count, and a terminal without a state or without an
     * operation names nothing to record. Whether the payload fits the promise the reservation made is
     * judged by the store, which faults and stops handing out room when it does not. */
    private static String malformedOutcome(CommitTicket.Outcome outcome, CommitTicket ticket) {
        if (outcome.terminal() == null) {
            return "terminal-undeclared";
        }
        if (outcome.unitCount() < 0) {
            return "unit-count-negative";
        }
        if (outcome.operationKind().isEmpty()) {
            return "operation-undeclared";
        }
        if (outcome.rows().size() != ticket.batch().rows()) {
            return "row-count-mismatch";
        }
        return null;
    }

    private void remember(long ticketId) {
        finished.addLast(ticketId);
        while (finished.size() > LEDGER_LIMIT) {
            finished.removeFirst();
        }
    }

    private FinalizeResult refused(RejectCode code, String diagnostic, CommitTicket.Terminal terminal) {
        return new FinalizeResult(false, code, diagnostic, terminal, 0, 0L);
    }

    private void append(TerminalEntry entry) {
        ledger.addLast(entry);
        while (ledger.size() > LEDGER_LIMIT) {
            ledger.removeFirst();
            ledgerEvicted++;
        }
    }

    /** Ends the lifecycle: a reservation that never published is given back, a line that is
     * publishing is left alone, and the caller is told how many of each there were. */
    public ResetResult onReset() {
        if (Thread.currentThread() != ownerThread) {
            offThreadRefusals++;
            return new ResetResult(0, publishing());
        }
        int voided = giveBackUnpublished("VOIDED", "reset");
        finished.clear();
        int publishing = publishing();
        resetDuringPublish += publishing;
        return new ResetResult(voided, publishing);
    }

    /** Stops this holder from taking anything new and gives back every reservation that never
     * published. A reset ends one generation and keeps the holder; a close ends the holder, and the
     * items whose side effect has run keep their room, their slot and their entry: a line that may
     * have been published by the host is not thrown away with the table it was waiting in. */
    public CloseResult close() {
        if (Thread.currentThread() != ownerThread) {
            offThreadRefusals++;
            return new CloseResult(0, pendings.size(), publishing());
        }
        closed = true;
        int givenBack = giveBackUnpublished("CLOSED", "close");
        finished.clear();
        return new CloseResult(givenBack, pendings.size(), publishing());
    }

    /** Gives back every reservation of this holder that never published and writes one terminal
     * entry for each. What it gave back is counted as released: the balance of the lifecycle closes
     * over a reset and over a close alike. */
    private int giveBackUnpublished(String state, String note) {
        List<Long> voided = new ArrayList<>();
        for (Map.Entry<Long, Phase> entry : phases.entrySet()) {
            if (entry.getValue() == Phase.RESERVED) {
                voided.add(entry.getKey());
            }
        }
        for (Long id : voided) {
            CommitTicket ticket = tickets.get(id);
            if (ticket == null) {
                continue;
            }
            giveBack(ticket);
            released++;
            resetVoided++;
            append(new TerminalEntry(id, ticket.identity().effectRef(), state, ticket.planSequence(),
                ticket.position(), 0, 0, note));
        }
        return voided.size();
    }

    /** The reservations still in flight, by the state they are in. */
    public int outstanding() {
        return tickets.size();
    }

    /** The lines whose side effect is running right now. An item whose record was refused is not
     * one of them: it waits for a disposition and is read through {@link #pendingDispositions()}. */
    public int publishing() {
        int count = 0;
        for (Phase phase : phases.values()) {
            if (phase == Phase.PUBLISHING) {
                count++;
            }
        }
        return count;
    }

    /** The items whose side effect has run and whose terminal line is not written, newest last. */
    public List<Pending> pendingDispositions() {
        return List.copyOf(pendings.values());
    }

    public int pendingCount() {
        return pendings.size();
    }

    /** The ticket one item is carried under, or null once it has ended. A disposed item leaves
     * nothing behind, so the identity a caller held is only good while the item waits. */
    public CommitTicket ticket(long ticketId) {
        return tickets.get(ticketId);
    }

    /** The entries this holder keeps for the reservations in flight: an ended ticket leaves none
     * behind, so the table cannot grow with the number of lines a lifecycle took. */
    public int stateEntries() {
        return tickets.size() + phases.size() + pendings.size();
    }

    /** The ids of the tickets that ended recently, kept only to answer a repeated terminal. */
    public int finishedEntries() {
        return finished.size();
    }

    /** Whether this holder takes nothing new. */
    public boolean closed() {
        return closed;
    }

    /** Whether every reservation ever taken is finalized, given back, or still in flight. */
    public boolean conservationHolds() {
        return reserveSuccess == finalized + released + outstanding();
    }

    public List<TerminalEntry> terminalLedger() {
        return List.copyOf(ledger);
    }

    public long reserveSuccess() {
        return reserveSuccess;
    }

    public long finalized() {
        return finalized;
    }

    public long releasedCount() {
        return released;
    }

    public long publishedTotal() {
        return publishedTotal;
    }

    public long partialTotal() {
        return partialTotal;
    }

    public long failedTotal() {
        return failedTotal;
    }

    /** The physical units the published lines reported. It is never read out of the rows of a
     * record or the field names it declares, and a terminal that did not publish counts here not at
     * all: those units are read through {@link #partialUnits()} and {@link #failedUnits()}. */
    public long unitTotal() {
        return unitTotal;
    }

    /** The units the partial terminals reported, kept apart from the published ones. */
    public long partialUnits() {
        return partialUnits;
    }

    /** The units the failed terminals reported, kept apart from the published ones. */
    public long failedUnits() {
        return failedUnits;
    }

    public long doubleFinalize() {
        return doubleFinalize;
    }

    public long unknownTicket() {
        return unknownTicket;
    }

    public long crossLogRefusals() {
        return crossLogRefusals;
    }

    public long staleRefusals() {
        return staleRefusals;
    }

    public long offThreadRefusals() {
        return offThreadRefusals;
    }

    public long requestRefusals() {
        return requestRefusals;
    }

    public long reservationRefusals() {
        return reservationRefusals;
    }

    public long resetVoided() {
        return resetVoided;
    }

    public long resetDuringPublish() {
        return resetDuringPublish;
    }

    public long ledgerEvicted() {
        return ledgerEvicted;
    }

    /** The side effects that were not started because an earlier place of their pair had not ended. */
    public long publishRefusals() {
        return publishRefusals;
    }

    /** The reservations the sealed declarations refused before a ring slot or a room was taken. */
    public long witnessRefusals() {
        return witnessRefusals;
    }

    /** The starts refused because the live world generation or grant no longer matched the frozen
     * one. */
    public long driftRefusals() {
        return driftRefusals;
    }
}
