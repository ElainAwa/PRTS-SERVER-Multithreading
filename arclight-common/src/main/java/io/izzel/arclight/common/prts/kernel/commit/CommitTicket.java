/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.List;

/** One reservation taken before the first side effect, and the only thing a terminal line may be
 * written from: the frozen order of the line, the two slots the reservation holds, the lifecycle
 * handle it was minted under and the identity it publishes. It is immutable, spent once, and it
 * carries no thread: the lifecycle handle is the owner, the lifetime and the reset generation.
 *
 * <p>Its room covers every terminal the line may report with every note its outcome column may
 * carry ({@link #NOTES}), and a record that grows past that bound faults. A batch ticket holds one window's rows; the single-ticket shape is this same record with none. */
public record CommitTicket(long ticketId, long logLifetime, CommitLog.Batch batch,
                           CommitLog.Identity identity, long planSequence, int position,
                           CommitLog.RingSlot ringSlot, EffectStore.CapacitySlot effectSlot,
                           CommitNamespaces.Namespace controller, List<Row> lines) {

    /** The notes the outcome column may carry beside the state of the terminal. */
    public static final List<String> NOTES = List.of("", "reset-during-publish", "plan-rebuilt",
        "publisher-threw", "publisher-silent");

    public CommitTicket {
        if (batch == null || identity == null || ringSlot == null || effectSlot == null
            || controller == null) {
            throw new IllegalArgumentException("a ticket needs a batch, an identity, two slots and a "
                + "lifecycle handle");
        }
        controller = withoutThread(controller);
        lines = lines == null ? List.of() : List.copyOf(lines);
        identity = new CommitLog.Identity(identity.schemaVersion(), identity.instanceId(),
            identity.worldEpoch(), identity.chunkPos(), identity.targetStatus(),
            identity.authorizationGeneration(), identity.effectRef(), withoutThread(identity.producer()),
            withoutThread(identity.controller()));
    }

    /** The single-line shape: a ticket whose only row is the one its own columns describe. */
    public CommitTicket(long ticketId, long logLifetime, CommitLog.Batch batch,
                        CommitLog.Identity identity, long planSequence, int position,
                        CommitLog.RingSlot ringSlot, EffectStore.CapacitySlot effectSlot,
                        CommitNamespaces.Namespace controller) {
        this(ticketId, logLifetime, batch, identity, planSequence, position, ringSlot, effectSlot,
            controller, List.of());
    }

    /** One window ticket over consecutive seats of one pair and plan. The head columns describe the
     * first row, so a reader of the single-line accessors still sees a real seat. */
    public static CommitTicket batch(long ticketId, long logLifetime, List<Row> rows,
                                     CommitNamespaces.Namespace controller) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("a batch ticket needs at least one row");
        }
        Row first = rows.get(0);
        for (int index = 1; index < rows.size(); index++) {
            Row row = rows.get(index);
            if (row.position() != rows.get(index - 1).position() + 1
                || row.planSequence() != first.planSequence()
                || !row.batch().worldId().equals(first.batch().worldId())
                || !row.batch().domainId().equals(first.batch().domainId())) {
                // One pair and one plan over consecutive seats lets the log open the held floor in order.
                throw new IllegalArgumentException("batch rows must hold consecutive seats of one pair");
            }
        }
        CommitLog.Batch head = new CommitLog.Batch(first.batch().worldId(), first.batch().domainId(),
            first.batch().nodeKey(), first.batch().kind(), first.batch().expectedVersion(),
            first.batch().writeSetDigest(), rows.size());
        return new CommitTicket(ticketId, logLifetime, head, first.identity(), first.planSequence(),
            first.position(), first.ringSlot(), first.effectSlot(), controller, rows);
    }

    private static CommitNamespaces.Namespace withoutThread(CommitNamespaces.Namespace namespace) {
        return new CommitNamespaces.Namespace(namespace.ownerId(), namespace.lifetime(),
            namespace.resetEpoch(), CommitNamespaces.NO_THREAD);
    }

    public long ringSlotTicket() {
        return ringSlot.ticket();
    }

    public long effectStoreId() {
        return effectSlot.storeId();
    }

    public long effectRef() {
        return identity.effectRef();
    }

    public String worldId() {
        return batch.worldId();
    }

    public String domainId() {
        return batch.domainId();
    }

    public String nodeKey() {
        return batch.nodeKey();
    }

    public int rows() {
        return batch.rows();
    }

    public EffectStore.Effect effectOf(Outcome outcome) {
        return outcome.asEffect(effectRef());
    }

    /** One window row: its frozen seat, two slots, identity and three terminal payloads, each with
     * the room it was reserved under so recording one never borrows another row's slot. */
    public record Row(CommitLog.Batch batch, CommitLog.Identity identity, long planSequence,
                      int position, CommitLog.RingSlot ringSlot,
                      EffectStore.CapacitySlot effectSlot, EffectStore.Effect published,
                      EffectStore.Effect partial, EffectStore.Effect failed) {

        /** The terminal payload this row will record when the effect ends in that terminal. */
        public EffectStore.Effect variantOf(Terminal terminal) {
            return switch (terminal) {
                case PUBLISHED -> published;
                case PARTIAL -> partial;
                case FAILED -> failed;
            };
        }

        /** The single-line ticket this row becomes when the log records it. */
        public CommitTicket asTicket(long ticketId, long logLifetime,
                                     CommitNamespaces.Namespace controller) {
            return new CommitTicket(ticketId, logLifetime, batch, identity, planSequence, position,
                ringSlot, effectSlot, controller);
        }
    }

    /** The three terminal records a reservation may end in. */
    public enum Terminal {
        PUBLISHED,
        PARTIAL,
        FAILED
    }

    /** Why a reservation was given back without a line. */
    public enum ReleaseReason {
        CANCELLED,
        PLAN_REBUILT,
        LIFECYCLE_ENDED
    }

    /** What one reservation asks for: the frozen place of the line, the identity it publishes
     * under, and the payload of each terminal it may reach. */
    public record ReserveRequest(String worldId, String domainId, String nodeKey,
                                 CommitLog.Batch.Kind kind, long planSequence, int position,
                                 long expectedVersion, long writeSetDigest, int rows,
                                 CommitLog.Identity identity, CommitNamespaces.Namespace controller,
                                 EffectStore.Effect published, EffectStore.Effect partial,
                                 EffectStore.Effect failed, boolean prepared) {

        public ReserveRequest(String worldId, String domainId, String nodeKey,
                              CommitLog.Batch.Kind kind, long planSequence, int position,
                              long expectedVersion, long writeSetDigest, int rows,
                              CommitLog.Identity identity, CommitNamespaces.Namespace controller,
                              EffectStore.Effect published, EffectStore.Effect partial,
                              EffectStore.Effect failed) {
            this(worldId, domainId, nodeKey, kind, planSequence, position, expectedVersion,
                writeSetDigest, rows, identity, controller, published, partial, failed, false);
        }

        /** A prepared request re-labels one already witnessed by a frozen declaration. Only the
         * admission builder, which derives every identity column from that declaration, may set it. */
        public ReserveRequest asPrepared() {
            return new ReserveRequest(worldId, domainId, nodeKey, kind, planSequence, position,
                expectedVersion, writeSetDigest, rows, identity, controller, published, partial,
                failed, true);
        }

        public List<EffectStore.Effect> variants() {
            return List.of(published, partial, failed);
        }

        public EffectStore.Effect variantOf(Terminal terminal) {
            return switch (terminal) {
                case PUBLISHED -> published;
                case PARTIAL -> partial;
                case FAILED -> failed;
            };
        }
    }

    /** The room a reservation promises: the largest terminal the line may report. */
    public record Covered(int maxRecords, int maxBytes) {
    }

    /** What a publish action reported. The unit count is its own number: it is never read out of
     * the rows of the record or the field names it declares. */
    public record Outcome(Terminal terminal, String operationKind, List<String> rows, int unitCount,
                          String note) {

        public Outcome {
            operationKind = operationKind == null ? "" : operationKind;
            rows = List.copyOf(rows == null ? List.of() : rows);
            note = note == null ? "" : note;
        }

        public String label() {
            return note.isEmpty() ? terminal.name() : terminal.name() + "|" + note;
        }

        public EffectStore.Effect asEffect(long effectRef) {
            return new EffectStore.Effect(effectRef, operationKind, label(), rows);
        }
    }
}
