/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;

/** The single write entry of one tick: every producer reports here, the log resolves which plan step
 * ordered the report and accepts it only while each (world, domain) pair keeps rising. Nothing here
 * sorts; a report that contradicts the order is counted and dropped. A versioned report may also
 * declare its columns and point at an effect record, which is copied into the replay before the
 * reference is released; the plain path keeps writing exactly the columns it always did. */
public final class CommitLog {

    /** One commit a producer reached. The batch is the only shape that reaches the log, so no
     * producer can hand it a pre-ordered sequence of its own. */
    public record Batch(String worldId, String domainId, String nodeKey, Kind kind,
                        long expectedVersion, long writeSetDigest, int rows) {

        /** What the producer did with the write. The log records the kind; it never rewrites it. */
        public enum Kind {
            APPLY,
            MERGE,
            INTENT,
            RETRY,
            DROP
        }

        public Batch {
            if (worldId == null) {
                throw new IllegalArgumentException("a commit needs a world");
            }
            if (domainId == null || domainId.isEmpty()) {
                throw new IllegalArgumentException("a commit needs a domain");
            }
            if (kind == null) {
                throw new IllegalArgumentException("a commit needs a kind");
            }
        }
    }

    /** The schema a line declares. Version 0 is the plain protocol: the plan order, the position
     * and the write set of a step. Version 1 adds the typed columns. A line that declares nothing
     * carries {@link #SCHEMA_UNDECLARED} and is never read as version 0. */
    public static final int SCHEMA_UNDECLARED = -1;
    public static final int SCHEMA_LEGACY = 0;
    public static final int SCHEMA_TYPED = 1;

    /** The declared default of the instance column: no product instance is numbered zero. */
    public static final long NO_INSTANCE = 0L;

    /** The declared default of the effect column: a line that points at no record. */
    public static final long NO_EFFECT = 0L;

    /** The declared default of the world generation column. */
    public static final long NO_WORLD_EPOCH = 0L;

    /** The declared default of the chunk column, read only under a declared schema. */
    public static final long NO_CHUNK_POSITION = 0L;

    /** The declared default of the authorization column: a write right that was not carried. */
    public static final long NO_AUTHORIZATION_GENERATION = 0L;

    /** Whether this build knows a schema version. */
    public static boolean supportedSchema(int version) {
        return version == SCHEMA_LEGACY || version == SCHEMA_TYPED;
    }

    /** Whether a world generation column names a world that is live. The tracked world set gives an
     * absent world a negative generation and an untracked one zero, so a generation that names a
     * world is positive, and the column is read for more than "not the default". */
    public static boolean liveWorldEpoch(long worldEpoch) {
        return worldEpoch > NO_WORLD_EPOCH;
    }

    /** The refusal of a world generation the check above does not read as live, and the empty text for
     * one it does: untracked and lost are named apart, so a caller can say which one it holds. */
    public static String worldEpochRefusal(long worldEpoch) {
        if (liveWorldEpoch(worldEpoch)) {
            return "";
        }
        return worldEpoch == NO_WORLD_EPOCH ? "world-untracked" : "world-not-live";
    }

    /** Whether an instance column names one of the numbers a producer mints: the domain is positive,
     * so zero and every negative value declare no instance. It is the one reading of that domain and
     * a domain check only: what identifies a line is the mapping its own declaration sealed. A chunk
     * position is not read here, because a packed chunk is signed and a negative one is legal. */
    public static boolean liveInstance(long instanceId) {
        return instanceId > NO_INSTANCE;
    }

    /** The typed columns of one line. A line of the plain path carries {@link Identity#NONE}, which
     * declares nothing: no schema, no instance, no effect. */
    public record Identity(int schemaVersion, long instanceId, long worldEpoch, long chunkPos,
                           String targetStatus, long authorizationGeneration, long effectRef,
                           CommitNamespaces.Namespace producer,
                           CommitNamespaces.Namespace controller) {

        public static final Identity NONE = new Identity(SCHEMA_UNDECLARED, NO_INSTANCE, NO_WORLD_EPOCH,
            NO_CHUNK_POSITION, "", NO_AUTHORIZATION_GENERATION, NO_EFFECT,
            CommitNamespaces.Namespace.NONE, CommitNamespaces.Namespace.NONE);

        public Identity {
            targetStatus = targetStatus == null ? "" : targetStatus;
            producer = producer == null ? CommitNamespaces.Namespace.NONE : producer;
            controller = controller == null ? CommitNamespaces.Namespace.NONE : controller;
        }

        /** Whether every column a versioned line has to declare is there. A missing column is a
         * refusal, not a default that reads as a value: both namespaces are read. */
        public boolean declaredTyped() {
            return schemaVersion == SCHEMA_TYPED && liveInstance(instanceId)
                && liveWorldEpoch(worldEpoch) && !targetStatus.isEmpty()
                && authorizationGeneration != NO_AUTHORIZATION_GENERATION && effectRef != NO_EFFECT
                && producer.declared() && controller.declared();
        }
    }

    /** One versioned report: the plain report, the columns it declares and the effect it points at. */
    public record TypedBatch(Batch batch, Identity identity, EffectStore.Effect effect,
                             long planSequence, long lifecycle) {

        /** A batch that names no plan takes the legacy newest-first path. */
        public TypedBatch(Batch batch, Identity identity, EffectStore.Effect effect) {
            this(batch, identity, effect, 0L, 0L);
        }
    }

    /** One step of the versioned projection: the plain step, the typed columns of the line, the
     * columns of the batch it came from and the effect record copied while the ring was read. The
     * batch columns are carried so a comparison can read the relation between the two halves of the
     * line: the authorization generation is the expected version, the rows are the units of the
     * record, and the node is the logical instance of the frozen plan. A null effect could not be
     * resolved. */
    public record TypedStep(long planSequence, int position, long writeSetDigest, String worldId,
                            String domainId, String nodeKey, long expectedVersion, int rows,
                            Identity identity, Entry.Disposition disposition,
                            EffectStore.Recorded effect) {

        public boolean effectResolved() {
            return effect != null;
        }
    }

    /** What closing one tick read in the versioned projection: the steps of the lines of the same
     * traversal that declared no version, and the typed steps with the record each line pointed at.
     * A versioned side that carries plain steps as well read two protocols in one tick and is
     * refused by a comparison instead of being read as one of them. */
    public record SchemaReplay(int schemaVersion, List<Entry.Step> plainSteps,
                               List<TypedStep> typedSteps, int effectMissing) {

        public SchemaReplay {
            plainSteps = List.copyOf(plainSteps == null ? List.of() : plainSteps);
            typedSteps = List.copyOf(typedSteps == null ? List.of() : typedSteps);
        }
    }

    /** One ring slot handed out before anything is stored in it, carrying the lifetime and reset
     * generation it was handed out under, so an earlier handle releases no later slot. */
    public record RingSlot(String worldId, String domainId, long ticket, long lifetime,
                           long resetEpoch) {
    }

    /** One permission to touch the inside of one ring. Only the log mints one, where it already
     * decided the touch is allowed, and a fill spends it in the same call. */
    static final class RingAuthority {

        enum Kind {
            FILL,
            READ,
            CLEAR
        }

        private final Kind kind;
        private final long logLifetime;
        private final long ringId;
        private final String worldId;
        private final String domainId;
        private final long slotTicket;
        private final Object key;
        private boolean spent;

        private RingAuthority(Kind kind, long logLifetime, long ringId, String worldId,
                              String domainId, long slotTicket, Object key) {
            this.kind = kind;
            this.logLifetime = logLifetime;
            this.ringId = ringId;
            this.worldId = worldId;
            this.domainId = domainId;
            this.slotTicket = slotTicket;
            this.key = key;
        }

        Object key() {
            return key;
        }

        Kind kind() {
            return kind;
        }

        long logLifetime() {
            return logLifetime;
        }

        long ringId() {
            return ringId;
        }

        String worldId() {
            return worldId;
        }

        String domainId() {
            return domainId;
        }

        long slotTicket() {
            return slotTicket;
        }

        boolean spend() {
            if (spent) {
                return false;
            }
            spent = true;
            return true;
        }
    }

    /** The one entry a producer reaches. A producer with no sink bound keeps its own path unchanged;
     * a producer with one reports what it did and is answered whether that was the planned order. The
     * answer is a verdict, not an interception. */
    @FunctionalInterface
    public interface Sink {

        void reach(Batch batch);
    }

    /** Where the order of a step comes from. The log asks; it never keeps a plan of its own. */
    public interface OrderSource {

        Resolved resolve(String worldId, String domainId, String nodeKey);

        /** Resolves one step in the exact plan and lifecycle a reporter names; no newest-key fallback. */
        default Resolved resolveAt(long planSequence, long lifecycle, String worldId, String domainId,
                                   String nodeKey) {
            return null;
        }
    }

    /** The order one commit was given by the planning period. A {@code null} answer means no plan
     * ordered this key. */
    public record Resolved(long planSequence, int position, boolean intent) {
    }

    /** One line of the log: the order the step had, what the producer did and what the log decided. */
    public record Entry(long sequence, long tickIndex, long planSequence, int position,
                        String worldId, String domainId, String nodeKey, long handle, Batch.Kind kind,
                        long expectedVersion, long writeSetDigest, int rows, Disposition disposition,
                        RejectCode code, boolean intent, Identity identity) {

        /** The line of a report of the plain path: the same columns as before, with the versioned
         * ones at their declared defaults. */
        public Entry(long sequence, long tickIndex, long planSequence, int position, String worldId,
                     String domainId, String nodeKey, long handle, Batch.Kind kind, long expectedVersion,
                     long writeSetDigest, int rows, Disposition disposition, RejectCode code,
                     boolean intent) {
            this(sequence, tickIndex, planSequence, position, worldId, domainId, nodeKey, handle, kind,
                expectedVersion, writeSetDigest, rows, disposition, code, intent, Identity.NONE);
        }

        /** What the log did with one reached commit. */
        public enum Disposition {
            ACCEPTED,
            MERGED,
            INTENT,
            DROPPED,
            RETRIED
        }

        /** The pair a replayed log is compared on: the order the plan assigned and the fold of the
         * values written under it. */
        public record Step(long planSequence, int position, long writeSetDigest) {
        }

        public Step step() {
            return new Step(planSequence, position, writeSetDigest);
        }
    }

    /** What the log did with one report. */
    public record Verdict(boolean logged, Entry.Disposition disposition, RejectCode code,
                          long sequence, int position) {
    }

    /** What closing one tick read. The steps are the pairs a replayed run is compared on. */
    public record Replay(long tickIndex, int loggedSteps, int orderViolations, boolean orderMatches,
                         long logDigest, List<Entry.Step> steps, int watermarks) {

        public Replay {
            steps = List.copyOf(steps);
        }

        /** Asked of the run that happened and of the run that was replayed: zero means the two wrote
         * the same values in the same order. */
        public static int compare(Replay left, Replay right) {
            if (left == null || right == null) {
                return -1;
            }
            int differences = Math.abs(left.loggedSteps() - right.loggedSteps());
            int shared = Math.min(left.steps().size(), right.steps().size());
            for (int index = 0; index < shared; index++) {
                Entry.Step a = left.steps().get(index);
                Entry.Step b = right.steps().get(index);
                if (a.planSequence() != b.planSequence() || a.position() != b.position()
                    || a.writeSetDigest() != b.writeSetDigest()) {
                    differences++;
                }
            }
            return differences;
        }
    }

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private final IntSupplier ringCapacity;
    private final OrderSource source;
    private final Map<String, CommitRing> rings = new LinkedHashMap<>();
    private final Map<String, Long> lastPlanOfRing = new LinkedHashMap<>();
    private final Map<String, Integer> lastPositionOfRing = new LinkedHashMap<>();
    private long sequence;
    private long tickIndex;
    private long accepted;
    private long merged;
    private long intents;
    private long dropped;
    private long retried;
    private long orderViolations;
    private long refusedFull;
    private long unplanned;
    private long ticks;
    private int watermarks;
    private Replay last;
    private long firstDivergencePosition = -1L;
    private final EffectStore effects;
    private final IdentityWitness.Source identitySource;
    private final long lifetime = CommitNamespaces.Lifetimes.mint();
    private final Object ringKey = new Object();
    /** What the rings still owe: a stored typed line holds the freeze it was judged against until read. */
    private final List<IdentityWitness.Obligation> unread = new ArrayList<>();
    private final Map<Long, RingSlot> heldSlots = new LinkedHashMap<>();
    private final Map<Long, long[]> heldPlans = new LinkedHashMap<>();
    private final Map<String, long[]> reservedFloors = new LinkedHashMap<>();
    private final Map<String, Integer> slotsByKey = new LinkedHashMap<>();
    private final Map<String, String> instanceNamespaces = new LinkedHashMap<>();
    private SchemaReplay lastSchema;
    private SchemaReplay lastReset;
    private long slotTickets;
    private long resetEpoch;
    private long reservationsTaken;
    private long reservationsReleased;
    private long reservationsConsumed;
    private long reservationRefusals;
    private long typedStored;
    private long typedRefused;
    private long identityCollisions;
    private long effectMissingSteps;
    private long hybridOrderRefusals;
    private long ticketStored;
    private long ticketRefused;
    private long crossLogRefusals;
    private long staleTicketRefusals;
    private long integrityFaults;

    public CommitLog(IntSupplier ringCapacity, OrderSource source) {
        this(ringCapacity, source, null, IdentityWitness.NONE);
    }

    /** The same log with an effect store bound. A log with no store cannot execute a versioned
     * report: it answers a refusal instead of writing a line whose effect nothing could resolve,
     * which is the state of the running server today. */
    public CommitLog(IntSupplier ringCapacity, OrderSource source, EffectStore effects) {
        this(ringCapacity, source, effects, IdentityWitness.NONE);
    }

    /** The same log with the holder that answers for the freeze a typed line claims to come from.
     * A chain bound without one admits no typed report. */
    public CommitLog(IntSupplier ringCapacity, OrderSource source, EffectStore effects,
                     IdentityWitness.Source identitySource) {
        this.ringCapacity = ringCapacity;
        this.source = source;
        this.effects = effects;
        this.identitySource = identitySource == null ? IdentityWitness.NONE : identitySource;
    }

    /** Opens one tick. The rings keep what a previous tick did not read, so a slow reader never loses
     * an entry to a new tick. */
    public void beginTick(long tick) {
        this.tickIndex = tick;
        firstDivergencePosition = -1L;
    }

    private record Attribution(long planSequence, int position, long lifecycle) {
    }

    /** The one entry. */
    public Verdict reach(Batch batch) {
        return reach(batch, null);
    }

    /** One report that names the plan, seat and lifecycle that authorized it: the log resolves that
     * exact plan and refuses a missing, retired or differently seated one as an unplanned line, never
     * by the newest plan that reused the key. */
    public Verdict reach(Batch batch, long planSequence, int position, long lifecycle) {
        return reach(batch, new Attribution(planSequence, position, lifecycle));
    }

    private Verdict reach(Batch batch, Attribution attribution) {
        if (batch == null) {
            return new Verdict(false, Entry.Disposition.DROPPED, RejectCode.COUNTER_MISSING, 0L, -1);
        }
        Resolved resolved;
        if (attribution == null) {
            resolved = source == null ? null
                : source.resolve(batch.worldId(), batch.domainId(), batch.nodeKey());
        } else {
            resolved = source == null ? null
                : source.resolveAt(attribution.planSequence(), attribution.lifecycle(),
                    batch.worldId(), batch.domainId(), batch.nodeKey());
            if (resolved != null && (resolved.planSequence() != attribution.planSequence()
                || resolved.position() != attribution.position())) {
                // The plan holds another seat, so this report did not come from it.
                resolved = null;
            }
        }
        long position = resolved == null ? -1 : resolved.position();
        long planSequence = resolved == null ? 0L : resolved.planSequence();
        boolean intent = resolved != null ? resolved.intent()
            : batch.kind() == Batch.Kind.INTENT;
        Entry.Disposition disposition;
        RejectCode code = null;
        if (resolved == null && !intent) {
            // A write no plan ordered is not put in front of the planned ones: it is counted as
            // unplanned and dropped from the ordered sequence, and the reason is readable.
            unplanned++;
            disposition = Entry.Disposition.DROPPED;
            code = RejectCode.COMMIT_ORDER_VIOLATION;
        } else if (resolved != null && !ascending(batch.worldId(), batch.domainId(), planSequence,
            position)) {
            orderViolations++;
            if (firstDivergencePosition < 0L) {
                firstDivergencePosition = position;
            }
            disposition = Entry.Disposition.DROPPED;
            code = RejectCode.COMMIT_ORDER_VIOLATION;
        } else {
            disposition = dispositionOf(batch.kind());
        }
        Entry entry = new Entry(++sequence, tickIndex, planSequence, (int) position,
            batch.worldId(), batch.domainId(), batch.nodeKey() == null ? "" : batch.nodeKey(), 0L,
            batch.kind(), batch.expectedVersion(), batch.writeSetDigest(), batch.rows(),
            disposition, code, intent);
        CommitRing ring = ring(batch.worldId(), batch.domainId());
        if (resolved != null && crossesReservedFloor(ring.key(), planSequence, position)) {
            // A reservation of this pair holds this place: the plain path does not carry the pair's
            // order past a place a versioned line reserved, nor onto it a second time. With no
            // reservation held there is no floor and nothing here reads. The refusal is counted where
            // it happened and the place the pair had taken is left where it was.
            hybridOrderRefusals++;
            orderViolations++;
            dropped++;
            if (firstDivergencePosition < 0L) {
                firstDivergencePosition = position;
            }
            return new Verdict(false, Entry.Disposition.DROPPED, RejectCode.COMMIT_ORDER_VIOLATION,
                sequence, (int) position);
        }
        if (freeSlots(ring) <= 0) {
            // The room a ring has is what its capacity leaves after the slots already handed out, so
            // a plain report never takes a slot a versioned commit is holding. With no slot held the
            // answer is the one the ring itself gives, refusal code and counters included.
            refusedFull++;
            dropped++;
            return new Verdict(false, Entry.Disposition.DROPPED, RejectCode.QUEUE_CAP_EXCEEDED,
                sequence, (int) position);
        }
        CommitRing.Push push = store(ring, 0L, entry);
        if (!push.stored()) {
            refusedFull++;
            dropped++;
            return new Verdict(false, Entry.Disposition.DROPPED, push.code(), sequence,
                (int) position);
        }
        // The line is stored: only now does it move the order of its pair and the count of what it
        // did. Every refusal above took none of that with it.
        if (resolved != null) {
            lastPlanOfRing.put(ring.key(), planSequence);
            lastPositionOfRing.put(ring.key(), (int) position);
        }
        switch (disposition) {
            case ACCEPTED -> accepted++;
            case MERGED -> merged++;
            case INTENT -> intents++;
            case RETRIED -> retried++;
            case DROPPED -> dropped++;
        }
        watermarks = Math.max(watermarks, ring.depth());
        return new Verdict(true, disposition, code, sequence, (int) position);
    }

    /** Closes the tick: the rings are walked in a fixed order and what they read is folded. */
    public Replay closeTick() {
        ticks++;
        List<String> keys = new ArrayList<>(rings.keySet());
        keys.sort(String::compareTo);
        List<Entry.Step> steps = new ArrayList<>();
        List<Entry.Step> plain = new ArrayList<>();
        List<TypedStep> typed = new ArrayList<>();
        long hash = OFFSET_BASIS;
        int read = 0;
        for (String key : keys) {
            CommitRing ring = rings.get(key);
            RingAuthority permission = read(ring);
            Entry entry;
            while ((entry = ring.pop(permission)) != null) {
                steps.add(entry.step());
                hash = fold(hash, entry.planSequence() + ":" + entry.position() + ":"
                    + entry.writeSetDigest() + ":" + entry.disposition());
                if (entry.identity().effectRef() != NO_EFFECT) {
                    typed.add(typedStep(entry));
                } else {
                    plain.add(entry.step());
                }
                read++;
            }
        }
        Replay replay = new Replay(tickIndex, read, (int) orderViolations, orderViolations == 0L,
            hash, steps, watermarks);
        last = replay;
        lastSchema = new SchemaReplay(typed.isEmpty() ? SCHEMA_UNDECLARED : SCHEMA_TYPED, plain, typed,
            unresolvedOf(typed));
        settleUnread();
        return replay;
    }

    private static int unresolvedOf(List<TypedStep> typed) {
        int unresolved = 0;
        for (TypedStep step : typed) {
            if (!step.effectResolved()) {
                unresolved++;
            }
        }
        return unresolved;
    }

    /** The replay of the newest closed tick. */
    public Replay last() {
        return last;
    }

    /** The versioned projection of the newest closed tick: the version the records declare, the
     * plain steps of the same traversal, and the typed steps with the record each line pointed at. */
    public SchemaReplay lastTyped() {
        return lastSchema;
    }

    /** The effect store bound to this log, or null when nothing is bound. */
    public EffectStore effects() {
        return effects;
    }

    /** The order source this log asks: a chain of the same shape is told apart by this reference. */
    public OrderSource orderSource() {
        return source;
    }

    /** Where this log reads the declarations a typed line is judged against. Read-only. */
    public IdentityWitness.Source identitySource() {
        return identitySource;
    }

    /** The number of steps two runs of the same input differ in; -1 means one of them is missing. */
    public static int compareRuns(Replay left, Replay right) {
        return Replay.compare(left, right);
    }

    /** The versioned report: the plain report plus its declared columns and its effect. The ring slot
     * and the room are taken before anything is stored; a refusal leaves neither line nor record and
     * takes no report number. */
    public Verdict reachTyped(TypedBatch typed) {
        if (typed == null || typed.batch() == null || typed.identity() == null
            || typed.effect() == null) {
            return typedRefusal(RejectCode.COUNTER_MISSING, -1);
        }
        if (effects == null) {
            return typedRefusal(RejectCode.COUNTER_MISSING, -1);
        }
        Batch batch = typed.batch();
        Identity identity = typed.identity();
        // A versioned line declares every column and its halves agree: the authorization generation
        // is the expected version, the line names its record and a node of the frozen plan.
        if (identity.schemaVersion() != SCHEMA_TYPED || !identity.declaredTyped()
            || identity.effectRef() != typed.effect().effectRef()
            || identity.authorizationGeneration() != batch.expectedVersion()
            || batch.nodeKey() == null || batch.nodeKey().isEmpty()) {
            return typedRefusal(RejectCode.VERSION_MISMATCH, -1);
        }
        Resolved resolved = source == null ? null
            : typed.planSequence() > 0L
                ? source.resolveAt(typed.planSequence(), typed.lifecycle(), batch.worldId(),
                    batch.domainId(), batch.nodeKey())
                : source.resolve(batch.worldId(), batch.domainId(), batch.nodeKey());
        if (resolved == null) {
            return typedRefusal(RejectCode.COMMIT_ORDER_VIOLATION, -1);
        }
        int position = resolved.position();
        if (!ascending(batch.worldId(), batch.domainId(), resolved.planSequence(), position)) {
            return typedRefusal(RejectCode.COMMIT_ORDER_VIOLATION, position);
        }
        if (crossesReservedFloor(key(batch.worldId(), batch.domainId()), resolved.planSequence(),
            position)) {
            // A reservation of this pair holds this place or one before it: this line does not take
            // its own slot and write past a place that reservation is still holding.
            orderViolations++;
            return typedRefusal(RejectCode.COMMIT_ORDER_VIOLATION, position);
        }
        FrozenDeclarations.Seat seat = new FrozenDeclarations.Seat(batch.worldId(), batch.domainId(),
            resolved.planSequence(), position);
        FrozenDeclarations.RangeSeal range = identitySource.range(seat, identity.chunkPos());
        if (range == null && identitySource.hasRange(seat)) {
            // The seat published a range this line names no seal of: no entry-path fallback.
            return typedRefusal(RejectCode.VERSION_MISMATCH, position);
        }
        String instance = instanceOf(identity, range);
        String instanceKey = instanceKeyOf(batch.worldId(), batch.domainId(), identity.instanceId());
        String held = instanceNamespaces.get(instanceKey);
        if (held != null && !held.equals(instance)) {
            // One instance number names one instance: lifecycle plus artefact or range folds.
            identityCollisions++;
            return typedRefusal(RejectCode.VERSION_MISMATCH, position);
        }
        FrozenDeclarations sealed = identitySource.declarations(batch.worldId(), batch.domainId(),
            resolved.planSequence());
        IdentityWitness.Verdict witness = range != null
            ? IdentityWitness.verifyRange(range, resolved, batch.worldId(), batch.domainId(),
                batch.nodeKey(), identity, identitySource.currentController())
            : IdentityWitness.verify(sealed, resolved, batch.worldId(), batch.domainId(),
                batch.nodeKey(), identity, identitySource.currentController());
        if (!witness.accepted()) {
            return typedRefusal(RejectCode.VERSION_MISMATCH, position);
        }
        CommitRing ring = ring(batch.worldId(), batch.domainId());
        RingSlot slot = reserveSlot(batch.worldId(), batch.domainId(), resolved.planSequence(), position);
        if (slot == null) {
            return typedRefusal(RejectCode.QUEUE_CAP_EXCEEDED, position);
        }
        EffectStore.Result charged = effects.reserve(typed.effect());
        if (!charged.admitted()) {
            releaseSlot(slot);
            return typedRefusal(charged.code(), position);
        }
        EffectStore.Result stored = effects.commit(charged.slot(), typed.effect());
        if (!stored.admitted()) {
            releaseSlot(slot);
            return typedRefusal(stored.code(), position);
        }
        if (stored.record().content().size() != batch.rows()) {
            // The batch declares its row count and the record carries exactly those.
            giveBack(identity.effectRef(), slot);
            return typedRefusal(RejectCode.VERSION_MISMATCH, position);
        }
        Entry.Disposition disposition = dispositionOf(batch.kind());
        Entry entry = new Entry(++sequence, tickIndex, resolved.planSequence(), position,
            batch.worldId(), batch.domainId(), batch.nodeKey() == null ? "" : batch.nodeKey(), 0L,
            batch.kind(), batch.expectedVersion(), batch.writeSetDigest(), batch.rows(), disposition,
            null, resolved.intent(), identity);
        CommitRing.Push push = store(ring, slot.ticket(), entry);
        if (!push.stored()) {
            // A slot was held for this line, so a ring that still refuses it is a defect and not a
            // shortage: the record and the reservation go with it and no half line stays behind.
            giveBack(identity.effectRef(), slot);
            return typedRefusal(push.code(), position);
        }
        consumeSlot(slot);
        owe(sealed, range);
        lastPlanOfRing.put(key(batch.worldId(), batch.domainId()), resolved.planSequence());
        lastPositionOfRing.put(key(batch.worldId(), batch.domainId()), position);
        instanceNamespaces.put(instanceKey, instance);
        switch (disposition) {
            case ACCEPTED -> accepted++;
            case MERGED -> merged++;
            case INTENT -> intents++;
            case RETRIED -> retried++;
            case DROPPED -> dropped++;
        }
        typedStored++;
        watermarks = Math.max(watermarks, ring.depth());
        return new Verdict(true, disposition, null, sequence, position);
    }

    /** Records the terminal line of a reservation: the frozen order, the slot and the room the ticket
     * holds, and the record written inside that room. A ticket of another log, store or lifecycle is
     * refused instead of written. */
    public Verdict recordTicket(CommitTicket ticket, CommitTicket.Outcome outcome) {
        if (ticket == null || outcome == null || effects == null) {
            return ticketRefusal(RejectCode.COUNTER_MISSING, -1);
        }
        if (ticket.logLifetime() != lifetime || ticket.effectSlot().storeId() != effects.storeId()) {
            crossLogRefusals++;
            return ticketRefusal(RejectCode.COUNTER_MISSING, ticket.position());
        }
        RingSlot ringSlot = ticket.ringSlot();
        RingSlot held = heldSlots.get(ringSlot.ticket());
        if (held == null || !held.equals(ringSlot) || held.lifetime() != lifetime
            || held.resetEpoch() != resetEpoch) {
            staleTicketRefusals++;
            return ticketRefusal(RejectCode.COUNTER_MISSING, ticket.position());
        }
        if (outcome.rows().size() != ticket.batch().rows()) {
            return ticketRefusal(RejectCode.VERSION_MISMATCH, ticket.position());
        }
        String pairKey = key(ticket.batch().worldId(), ticket.batch().domainId());
        if (!ringSlot.worldId().equals(ticket.batch().worldId())
            || !ringSlot.domainId().equals(ticket.batch().domainId())) {
            // The slot a ticket holds is one of the pair the ticket publishes to: a handle of one
            // pair is never a permission to write into the ring of another.
            crossLogRefusals++;
            return ticketRefusal(RejectCode.COUNTER_MISSING, ticket.position());
        }
        long[] frozen = heldPlans.get(ringSlot.ticket());
        if (frozen == null || frozen[0] != ticket.planSequence() || frozen[1] != ticket.position()
            || !ascending(ticket.batch().worldId(), ticket.batch().domainId(), ticket.planSequence(),
                ticket.position())
            || holdsAnEarlierPlace(pairKey, ticket.planSequence(), ticket.position())) {
            // The place the plan froze into this ticket is judged here, before the room is filled and
            // before a line is stored: a terminal of a place an earlier reservation of the pair still
            // holds waits for that one, and a place the pair has already passed is refused. A refusal
            // leaves the ticket, its slot, its room and the order of the pair where they were.
            orderViolations++;
            return ticketRefusal(RejectCode.COMMIT_ORDER_VIOLATION, ticket.position());
        }
        FrozenDeclarations.Seat seat = new FrozenDeclarations.Seat(ticket.batch().worldId(),
            ticket.batch().domainId(), ticket.planSequence(), ticket.position());
        FrozenDeclarations.RangeSeal range = identitySource.range(seat,
            ticket.identity().chunkPos());
        if (range == null && identitySource.hasRange(seat)) {
            // The seat published a range and this identity names no seal of its cell.
            return ticketRefusal(RejectCode.VERSION_MISMATCH, ticket.position());
        }
        EffectStore.CapacityResult finished = effects.finalizeCapacity(ticket.effectSlot(),
            outcome.asEffect(ticket.identity().effectRef()));
        if (!finished.admitted()) {
            // The record outgrew the room that was promised for it: the item stays readable in the
            // store, the slot stays held and the log counts the fault instead of writing a line
            // whose record was never stored.
            integrityFaults++;
            return ticketRefusal(finished.code() == null ? RejectCode.QUEUE_CAP_EXCEEDED
                : finished.code(), ticket.position());
        }
        boolean published = outcome.terminal() == CommitTicket.Terminal.PUBLISHED;
        Entry.Disposition disposition = published ? dispositionOf(ticket.batch().kind())
            : Entry.Disposition.DROPPED;
        Entry entry = new Entry(++sequence, tickIndex, ticket.planSequence(), ticket.position(),
            ticket.batch().worldId(), ticket.batch().domainId(), ticket.batch().nodeKey(), 0L,
            published ? ticket.batch().kind() : Batch.Kind.DROP, ticket.batch().expectedVersion(),
            ticket.batch().writeSetDigest(), ticket.batch().rows(), disposition, null, false,
            ticket.identity());
        CommitRing ring = ring(ticket.batch().worldId(), ticket.batch().domainId());
        CommitRing.Push push = store(ring, ringSlot.ticket(), entry);
        if (!push.stored()) {
            giveBack(ticket.identity().effectRef(), ringSlot);
            return ticketRefusal(push.code(), ticket.position());
        }
        consumeSlot(ringSlot);
        lastPlanOfRing.put(pairKey, ticket.planSequence());
        lastPositionOfRing.put(pairKey, ticket.position());
        instanceNamespaces.put(instanceKeyOf(ticket.batch().worldId(), ticket.batch().domainId(),
            ticket.identity().instanceId()), instanceOf(ticket.identity(), range));
        switch (disposition) {
            case ACCEPTED -> accepted++;
            case MERGED -> merged++;
            case INTENT -> intents++;
            case RETRIED -> retried++;
            case DROPPED -> dropped++;
        }
        ticketStored++;
        watermarks = Math.max(watermarks, ring.depth());
        return new Verdict(true, disposition, null, sequence, ticket.position());
    }

    /** Whether this instance number is already held by another lifecycle, chunk or published state.
     * Read-only: it counts nothing, and it is asked before a reservation is taken. */
    public boolean instanceCollision(String worldId, String domainId, Identity identity) {
        return instanceCollision(worldId, domainId, identity, null);
    }

    /** The same question for a range line: the mapping is the lifecycle and the two range folds. */
    public boolean instanceCollision(String worldId, String domainId, Identity identity,
                                     FrozenDeclarations.RangeSeal range) {
        String held = instanceNamespaces.get(instanceKeyOf(worldId, domainId, identity.instanceId()));
        return held != null && !held.equals(instanceOf(identity, range));
    }

    private static String instanceKeyOf(String worldId, String domainId, long instanceId) {
        return key(worldId, domainId) + "/" + instanceId;
    }

    /** Holds the freeze, and for a range line the seal, while the line waits unread. */
    private void owe(FrozenDeclarations sealed, FrozenDeclarations.RangeSeal range) {
        IdentityWitness.Obligation held = identitySource.oblige(sealed);
        if (range != null) {
            held = IdentityWitness.combine(held, identitySource.oblige(range));
        }
        if (held != null && held != IdentityWitness.Obligation.NONE) {
            unread.add(held);
        }
    }

    void holdUnread(IdentityWitness.Obligation held) {
        if (held != null && held != IdentityWitness.Obligation.NONE) {
            unread.add(held);
        }
    }

    private void settleUnread() {
        for (IdentityWitness.Obligation held : unread) {
            held.settle();
        }
        unread.clear();
    }

    public int unreadObligations() {
        return unread.size();
    }

    private Verdict ticketRefusal(RejectCode code, int position) {
        ticketRefused++;
        return new Verdict(false, Entry.Disposition.DROPPED, code, sequence, position);
    }

    /** Hands out one ring slot of a pair before it is stored in; the pair has that much less room
     * while the slot is held, and {@link #releaseSlot} gives it back. */
    public RingSlot reserveSlot(String worldId, String domainId) {
        return reserveSlot(worldId, domainId, null);
    }

    /** The same, with the place the frozen plan gave the line that will fill the slot. While such a
     * slot is held the plain path may not carry the pair's order onto that place or past it. */
    public RingSlot reserveSlot(String worldId, String domainId, long planSequence, int position) {
        return reserveSlot(worldId, domainId, new long[] {planSequence, position});
    }

    private RingSlot reserveSlot(String worldId, String domainId, long[] plan) {
        CommitRing ring = ring(worldId, domainId);
        if (freeSlots(ring) <= 0) {
            reservationRefusals++;
            return null;
        }
        if (plan != null && !ascending(worldId, domainId, plan[0], (int) plan[1])) {
            // A place the pair has already passed can never be written: a slot held for it could only
            // end in a refusal, so none is handed out.
            reservationRefusals++;
            return null;
        }
        RingSlot slot = new RingSlot(worldId, domainId, ++slotTickets, lifetime, resetEpoch);
        heldSlots.put(slot.ticket(), slot);
        slotsByKey.merge(key(worldId, domainId), 1, Integer::sum);
        if (plan != null) {
            heldPlans.put(slot.ticket(), plan);
            raiseFloor(key(worldId, domainId), plan[0], (int) plan[1]);
        }
        reservationsTaken++;
        return slot;
    }

    /** Whether a line of this place still rises above what the pair has taken. Read-only: it counts
     * nothing and takes no slot, so a caller that reserves can tell a place that is gone from a ring
     * that has no room. */
    public boolean placeAscending(String worldId, String domainId, long planSequence, int position) {
        return ascending(worldId, domainId, planSequence, position);
    }

    /** Whether this place is the next one the pair may hand to a side effect: no reservation of the
     * pair holds a place before it. Read-only, like the check above: the side effect of a later place
     * waits until the earlier one ended. */
    public boolean placeRunnable(String worldId, String domainId, long planSequence, int position) {
        long[] floor = reservedFloors.get(key(worldId, domainId));
        return floor == null || (floor[0] == planSequence && floor[1] == position);
    }

    private void raiseFloor(String key, long planSequence, int position) {
        long[] floor = reservedFloors.get(key);
        if (floor == null || planSequence < floor[0]
            || (planSequence == floor[0] && position < floor[1])) {
            reservedFloors.put(key, new long[] {planSequence, position});
        }
    }

    private void lowerFloor(String key) {
        long[] floor = null;
        for (RingSlot held : heldSlots.values()) {
            if (!key(held.worldId(), held.domainId()).equals(key)) {
                continue;
            }
            long[] plan = heldPlans.get(held.ticket());
            if (plan == null) {
                continue;
            }
            if (floor == null || plan[0] < floor[0] || (plan[0] == floor[0] && plan[1] < floor[1])) {
                floor = plan;
            }
        }
        if (floor == null) {
            reservedFloors.remove(key);
        } else {
            reservedFloors.put(key, floor);
        }
    }

    /** Gives back a slot that was not filled, answered against the whole handle this log saved: a
     * handle of another log, pair or lifecycle is not a slot of this one. */
    public boolean releaseSlot(RingSlot slot) {
        if (slot == null) {
            return false;
        }
        RingSlot held = heldSlots.get(slot.ticket());
        if (held == null || !held.equals(slot) || held.lifetime() != lifetime
            || held.resetEpoch() != resetEpoch) {
            return false;
        }
        heldSlots.remove(held.ticket());
        dropHeld(held);
        reservationsReleased++;
        return true;
    }

    /** Fills a held slot with the line that was reserved for it. */
    private void consumeSlot(RingSlot slot) {
        RingSlot held = heldSlots.get(slot.ticket());
        if (held == null || !held.equals(slot)) {
            return;
        }
        heldSlots.remove(held.ticket());
        dropHeld(held);
        reservationsConsumed++;
    }

    /** Drops the room one saved slot held, by the pair the log saved with it. */
    private void dropHeld(RingSlot held) {
        String key = key(held.worldId(), held.domainId());
        Integer count = slotsByKey.get(key);
        if (count == null || count <= 1) {
            slotsByKey.remove(key);
        } else {
            slotsByKey.put(key, count - 1);
        }
        heldPlans.remove(held.ticket());
        lowerFloor(key);
    }

    /** Gives back the record and the ring slot of a line that is not stored after all. */
    private void giveBack(long effectRef, RingSlot slot) {
        effects.unpin(effectRef);
        effects.evict(effectRef);
        releaseSlot(slot);
    }

    private int freeSlots(CommitRing ring) {
        return ring.capacity() - ring.depth() - slotsByKey.getOrDefault(ring.key(), 0);
    }

    private Verdict typedRefusal(RejectCode code, int position) {
        typedRefused++;
        return new Verdict(false, Entry.Disposition.DROPPED, code, sequence, position);
    }

    /** The one place the cross-run logical key of a verified seat is rebuilt, read by all three
     * sites; a seat the table wrote nothing for is answered with null and never with an empty key. */
    public static IdentityWitness.LogicalKey logicalKeyOf(FrozenDeclarations.Entry entry) {
        if (entry == null || entry.seat() == null) {
            return null;
        }
        return new IdentityWitness.LogicalKey(entry.seat().worldId(), entry.seat().domainId(),
            entry.nodeKey(), entry.role(), entry.packedChunkPos(), entry.targetStatus());
    }

    /** The same key read from the line: its artefact columns and the rank the frozen order gave it. */
    public static IdentityWitness.LogicalKey logicalKeyOf(String worldId, String domainId,
                                                          String nodeKey, int role,
                                                          Identity identity) {
        if (identity == null) {
            return null;
        }
        return new IdentityWitness.LogicalKey(worldId, domainId, nodeKey, role, identity.chunkPos(),
            identity.targetStatus());
    }

    /** The range key of one seal as its witness carries it, folds included. */
    public static IdentityWitness.LogicalKey logicalKeyOf(FrozenDeclarations.RangeSeal range) {
        if (range == null || range.seat() == null) {
            return null;
        }
        return IdentityWitness.LogicalKey.ranged(range.seat().worldId(), range.seat().domainId(),
            range.nodeKey(), range.role(), range.spaceDigest(), range.statusMapDigest());
    }

    /** The same key rebuilt with both folds recomputed from the cells and the status map. */
    public static IdentityWitness.LogicalKey logicalKeyOfRange(String worldId, String domainId,
                                                               String nodeKey, int role,
                                                               FrozenDeclarations.RangeSeal range) {
        if (range == null) {
            return null;
        }
        return IdentityWitness.LogicalKey.ranged(worldId, domainId, nodeKey, role,
            RangeDigests.spaceDigest(range.cells()),
            RangeDigests.statusMapDigest(range.statuses()));
    }

    /** The lifecycle of a versioned line: the owner, the lifetime and the reset generation of both
     * namespaces. The thread column is a diagnostic and is not part of it. */
    private static String lifecycleKeyOf(Identity identity) {
        return identity.producer().lifecycleKey() + "|" + identity.controller().lifecycleKey();
    }

    /** One instance number names one instance in one log: its lifecycle, the chunk it publishes and
     * the state it publishes there. The same instance met on another thread is the same instance. */
    private static String instanceOf(Identity identity) {
        return lifecycleKeyOf(identity) + "|" + identity.chunkPos() + "|" + identity.targetStatus();
    }

    /** The same mapping for a range line: lifecycle plus both range folds. */
    private static String instanceOf(Identity identity, FrozenDeclarations.RangeSeal range) {
        if (range == null) {
            return instanceOf(identity);
        }
        return lifecycleKeyOf(identity) + "|" + range.spaceDigest() + "|"
            + range.statusMapDigest();
    }

    private static Entry.Disposition dispositionOf(Batch.Kind kind) {
        return switch (kind) {
            case APPLY -> Entry.Disposition.ACCEPTED;
            case MERGE -> Entry.Disposition.MERGED;
            case INTENT -> Entry.Disposition.INTENT;
            case RETRY -> Entry.Disposition.RETRIED;
            case DROP -> Entry.Disposition.DROPPED;
        };
    }

    private TypedStep typedStep(Entry entry) {
        long effectRef = entry.identity().effectRef();
        EffectStore.Recorded record = effects == null ? null : effects.record(effectRef);
        if (record == null) {
            effectMissingSteps++;
        } else {
            // The full normalized record is copied here, before the reference the line held is
            // released: a reader of the replay never needs the store to still be holding it.
            effects.unpin(effectRef);
        }
        return new TypedStep(entry.planSequence(), entry.position(), entry.writeSetDigest(),
            entry.worldId(), entry.domainId(), entry.nodeKey(), entry.expectedVersion(), entry.rows(),
            entry.identity(), entry.disposition(), record);
    }

    /** Whether this place is at or past the floor a reservation of the pair holds. The floor is the
     * earliest place a held reservation carries, and a plain or versioned line stays strictly before
     * it; with nothing held there is no floor and nothing here reads. */
    private boolean crossesReservedFloor(String pairKey, long planSequence, long position) {
        long[] floor = reservedFloors.get(pairKey);
        return floor != null && (planSequence > floor[0]
            || (planSequence == floor[0] && position >= floor[1]));
    }

    /** Whether a reservation of this pair still holds a place before this one. Only the earliest
     * place the pair holds may end, so the terminal of a later place waits for it instead of writing
     * past it and leaving the ring in an order the plan never gave. */
    private boolean holdsAnEarlierPlace(String pairKey, long planSequence, int position) {
        long[] floor = reservedFloors.get(pairKey);
        return floor != null && (floor[0] < planSequence
            || (floor[0] == planSequence && floor[1] < position));
    }

    private boolean ascending(String worldId, String domainId, long planSequence, long position) {
        String key = key(worldId, domainId);
        Long plan = lastPlanOfRing.get(key);
        Integer lastPosition = lastPositionOfRing.get(key);
        if (plan == null || lastPosition == null) {
            return true;
        }
        if (planSequence > plan) {
            return true;
        }
        return planSequence == plan && position >= lastPosition;
    }

    private CommitRing ring(String worldId, String domainId) {
        return rings.computeIfAbsent(key(worldId, domainId),
            key -> new CommitRing(worldId, domainId, ringCapacity.getAsInt(), lifetime, ringKey));
    }

    /** The one place a ring is filled: the permission is looked up and spent in the same call, so
     * a line the log refused to admit takes no entry with it. */
    private CommitRing.Push store(CommitRing ring, long slotTicket, Entry entry) {
        return ring.push(entry, fill(ring, slotTicket));
    }

    /** A permission to fill one ring, minted only while the reservation it names is this log's, of
     * this pair and lifecycle; every mint is spent by the push in the same call. */
    private RingAuthority fill(CommitRing ring, long slotTicket) {
        if (slotTicket == 0L) {
            // The plain path is admitted by the room check that precedes it, and the mint repeats it
            // rather than trusting the caller: a line that was never admitted gets no permission.
            return freeSlots(ring) > 0 ? mint(ring, 0L) : null;
        }
        RingSlot held = heldSlots.get(slotTicket);
        if (held == null || held.lifetime() != lifetime || held.resetEpoch() != resetEpoch
            || !ring.worldId().equals(held.worldId()) || !ring.domainId().equals(held.domainId())) {
            return null;
        }
        return mint(ring, slotTicket);
    }

    private RingAuthority mint(CommitRing ring, long slotTicket) {
        return new RingAuthority(RingAuthority.Kind.FILL, lifetime, ring.ringId(), ring.worldId(),
            ring.domainId(), slotTicket, ringKey);
    }

    private RingAuthority read(CommitRing ring) {
        return new RingAuthority(RingAuthority.Kind.READ, lifetime, ring.ringId(), ring.worldId(),
            ring.domainId(), 0L, ringKey);
    }

    private RingAuthority clear(CommitRing ring) {
        return new RingAuthority(RingAuthority.Kind.CLEAR, lifetime, ring.ringId(), ring.worldId(),
            ring.domainId(), 0L, ringKey);
    }

    private static String key(String worldId, String domainId) {
        return worldId + "/" + domainId;
    }

    private static long fold(long hash, String text) {
        long value = hash;
        for (int index = 0; index < text.length(); index++) {
            value ^= text.charAt(index);
            value *= PRIME;
        }
        return value;
    }

    /** The terminal projection of the lifecycle that ended at the last reset: what the rings still
     * carried then, with the records copied. A further reset replaces it instead of piling up. */
    public SchemaReplay lastResetTyped() {
        return lastReset;
    }

    private SchemaReplay drain() {
        List<Entry.Step> plain = new ArrayList<>();
        List<TypedStep> typed = new ArrayList<>();
        List<String> keys = new ArrayList<>(rings.keySet());
        keys.sort(String::compareTo);
        for (String key : keys) {
            CommitRing ring = rings.get(key);
            RingAuthority permission = read(ring);
            Entry entry;
            while ((entry = ring.pop(permission)) != null) {
                if (entry.identity().effectRef() != NO_EFFECT) {
                    typed.add(typedStep(entry));
                } else {
                    plain.add(entry.step());
                }
            }
        }
        settleUnread();
        return new SchemaReplay(typed.isEmpty() ? SCHEMA_UNDECLARED : SCHEMA_TYPED, plain, typed,
            unresolvedOf(typed));
    }

    public long sequence() {
        return sequence;
    }

    public long accepted() {
        return accepted;
    }

    public long merged() {
        return merged;
    }

    public long intents() {
        return intents;
    }

    public long dropped() {
        return dropped;
    }

    public long retried() {
        return retried;
    }

    public long orderViolations() {
        return orderViolations;
    }

    public long refusedFull() {
        return refusedFull;
    }

    public long unplanned() {
        return unplanned;
    }

    public long ticks() {
        return ticks;
    }

    public int watermarks() {
        return watermarks;
    }

    public long firstDivergencePosition() {
        return firstDivergencePosition;
    }

    public int ringCount() {
        return rings.size();
    }

    public int ringDepth() {
        int depth = 0;
        for (CommitRing ring : rings.values()) {
            depth += ring.depth();
        }
        return depth;
    }

    public int ringCapacity() {
        return Math.max(1, ringCapacity.getAsInt());
    }

    /** The rings in a fixed order, for a reader that publishes them. */
    public List<CommitRing> rings() {
        List<String> keys = new ArrayList<>(rings.keySet());
        keys.sort(String::compareTo);
        List<CommitRing> ordered = new ArrayList<>(keys.size());
        for (String key : keys) {
            ordered.add(rings.get(key));
        }
        return ordered;
    }

    /** The number of accepted writes still held by the rings. */
    public int held() {
        return ringDepth();
    }

    /** The ring slots handed out and neither filled nor given back. */
    public int reservedSlots() {
        return heldSlots.size();
    }

    public long reservationsTaken() {
        return reservationsTaken;
    }

    public long reservationsReleased() {
        return reservationsReleased;
    }

    public long reservationsConsumed() {
        return reservationsConsumed;
    }

    public long reservationRefusals() {
        return reservationRefusals;
    }

    /** The versioned reports the log took. */
    public long typedStored() {
        return typedStored;
    }

    /** The versioned reports the log refused, which took no report number of their own. */
    public long typedRefused() {
        return typedRefused;
    }

    /** The versioned reports refused because their instance number is already held by another
     * lifecycle. */
    public long identityCollisions() {
        return identityCollisions;
    }

    /** The typed steps whose effect could not be resolved when the ring was read. */
    public long effectMissingSteps() {
        return effectMissingSteps;
    }

    /** The plain reports refused because a reservation of their pair held the place they named. */
    public long hybridOrderRefusals() {
        return hybridOrderRefusals;
    }

    /** The lines recorded from a reservation that was taken before the side effect. */
    public long ticketStored() {
        return ticketStored;
    }

    public long ticketRefused() {
        return ticketRefused;
    }

    /** Tickets of another log or of another store, answered as unknown instead of written. */
    public long crossLogRefusals() {
        return crossLogRefusals;
    }

    /** Tickets whose slot this log no longer holds: an earlier lifecycle, or a spent reservation. */
    public long staleTicketRefusals() {
        return staleTicketRefusals;
    }

    /** Terminal records that outgrew the room reserved for them. */
    public long integrityFaults() {
        return integrityFaults;
    }

    /** The reservations that carry the place the frozen plan gave them. */
    public int heldPlans() {
        return heldPlans.size();
    }

    /** The identity of this log, minted with it: a ticket names the log it belongs to. */
    public long logLifetime() {
        return lifetime;
    }

    /** Ends this lifecycle. The lines the rings still carry are folded into the terminal projection
     * of that lifecycle first, and the record a versioned line points at is copied before the one
     * reference the line holds is given back: a reset that is repeated cannot leave a reference
     * behind and cannot exhaust the store, while a reference held by someone else stays theirs. */
    public void reset() {
        lastReset = drain();
        for (CommitRing ring : rings.values()) {
            ring.reset(clear(ring));
        }
        rings.clear();
        lastPlanOfRing.clear();
        lastPositionOfRing.clear();
        sequence = 0L;
        tickIndex = 0L;
        accepted = 0L;
        merged = 0L;
        intents = 0L;
        dropped = 0L;
        retried = 0L;
        orderViolations = 0L;
        refusedFull = 0L;
        unplanned = 0L;
        ticks = 0L;
        watermarks = 0;
        last = null;
        firstDivergencePosition = -1L;
        lastSchema = null;
        heldSlots.clear();
        heldPlans.clear();
        reservedFloors.clear();
        slotsByKey.clear();
        instanceNamespaces.clear();
        resetEpoch++;
        reservationsTaken = 0L;
        reservationsReleased = 0L;
        reservationsConsumed = 0L;
        reservationRefusals = 0L;
        typedStored = 0L;
        typedRefused = 0L;
        identityCollisions = 0L;
        effectMissingSteps = 0L;
        hybridOrderRefusals = 0L;
        ticketStored = 0L;
        ticketRefused = 0L;
        crossLogRefusals = 0L;
        staleTicketRefusals = 0L;
        integrityFaults = 0L;
    }
}
