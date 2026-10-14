/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.config.ExecutableProductFace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The declared-side authorization hand-over, driven end to end inside one process: the plan
 * identity a request names, the grant record its own plan published, and the two judgement moments
 * that read the pair-keyed answer - the source at the reservation and the current validity at the
 * start of the publish. The record and the slots are the module's own, the answer is read through
 * the module's own source, and the holder is a real reservation; only the declaration table is this
 * fixture's seal. It is not a server run: no host, no supplier and no instance take part. */
class DeclaredAuthorizationHandoverTest {

    private static final String WORLD = "world-a";
    private static final String OTHER_WORLD = "world-b";
    private static final String DOMAIN = "entity";
    private static final String OTHER_DOMAIN = "block";
    private static final String NODE = "a/0";
    private static final String NEIGHBOUR_NODE = "a/1";
    private static final long EFFECT = 9L;
    private static final long PLAN = 7L;
    private static final long LATER_PLAN = 8L;
    private static final long EPOCH = 5L;
    private static final long INSTANCE = 11L;
    private static final long CHUNK = 3L;
    private static final String STATUS = "published";
    private static final long DIGEST = 4242L;
    private static final int ROWS = 2;
    private static final String OWNER = "owner-a";

    /** The declared-side face of the drive: the declaration table of the fixture's seal in front of
     * the module's own authorization read, counting the reads so each judgement moment is read by
     * how often it asked. */
    private static final class DrivenSource implements IdentityWitness.Source {

        private final WitnessSource declared;
        private final IdentityWitness.Source authorization;
        private final AtomicLong reads = new AtomicLong();

        DrivenSource(WitnessSource declared, IdentityWitness.Source authorization) {
            this.declared = declared;
            this.authorization = authorization;
        }

        @Override
        public IdentityWitness.Authorization liveAuthorization(long planSequence,
                CommitNamespaces.Namespace planController, String worldId, String domainId,
                String nodeKey) {
            reads.incrementAndGet();
            return authorization.liveAuthorization(planSequence, planController, worldId, domainId,
                nodeKey);
        }

        @Override
        public FrozenDeclarations declarations(String worldId, String domainId, long planSequence) {
            return declared.declarations(worldId, domainId, planSequence);
        }

        @Override
        public IdentityWitness.Obligation oblige(FrozenDeclarations declarations) {
            return declared.oblige(declarations);
        }

        @Override
        public long liveWorldEpoch(String worldId) {
            return declared.liveWorldEpoch(worldId);
        }

        @Override
        public CommitNamespaces.Namespace currentController() {
            return authorization.currentController();
        }
    }

    /** One drive: a real chain over the module's own record and slots. */
    private final class Drive {

        private final KernelModule module = KernelModule.instance();
        private final EffectStore store = new EffectStore(4096, 16);
        private final CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        private final CommitLog.OrderSource plans;
        private final WitnessSource witness;
        private final DrivenSource face;
        private final CommitLog log;
        private final CommitReservation reservations;
        private final CommitController controller;
        private final WriteVersionSlots slots;
        private final List<CommitTicket> held = new ArrayList<>();

        Drive() throws IOException {
            System.setProperty("arclight.prts.kernel", "true");
            ExecutableProductFace.declare();
            module.serverTick(List.of(WORLD));
            IdentityWitness.Source production = module.commits().identitySource();
            plans = (worldId, domainId, nodeKey) -> {
                if (!WORLD.equals(worldId) || !DOMAIN.equals(domainId)) {
                    return null;
                }
                return switch (nodeKey) {
                    case NODE -> new CommitLog.Resolved(PLAN, 0, false);
                    case NEIGHBOUR_NODE -> new CommitLog.Resolved(LATER_PLAN, 0, false);
                    default -> null;
                };
            };
            witness = new WitnessSource(plans, WORLD, DOMAIN, CHUNK, STATUS, EPOCH, 0L, NODE,
                NEIGHBOUR_NODE).bind(INSTANCE);
            face = new DrivenSource(witness, production);
            log = new CommitLog(() -> 4, module.commitOrderSource(), store, face);
            reservations = new CommitReservation(log, store, plans, OWNER, epochs);
            controller = new CommitController(reservations, null);
            witness.freeze(reservations.controller().lifecycleKey(), 1L);
            KernelModule.CommitBinding binding = module.installCommitChain(log, controller);
            assertTrue(binding.bound(), "the fixture chain is adopted: " + binding.refusal());
            slots = module.versionSlots();
            slots.reset();
            slots.refresh(true);
        }

        /** One grant of this plan's own planning period, and the record it publishes in one move. */
        long publishGrant(long plan) {
            OwnershipDomain domain = new OwnershipDomain(WORLD, WriteLevel.REGION, DOMAIN);
            long granted = slots.grant(WORLD, WriteLevel.REGION, DOMAIN);
            slots.noteGranted(plan, Map.of(domain, granted));
            return granted;
        }

        /** Takes the reservation and keeps the ticket, so the drive can give back what it took. */
        CommitReservation.ReserveResult reserve(CommitTicket.ReserveRequest request) {
            CommitReservation.ReserveResult result = reservations.reserve(request);
            if (result.ticket() != null) {
                held.add(result.ticket());
            }
            return result;
        }

        CommitReservation.ReserveResult reserve(long plan, long worn) {
            return reserve(request(plan, worn));
        }

        /** Ends every ticket still held, through the paths the holder already has. */
        void settle() {
            for (CommitTicket ticket : held) {
                CommitReservation.FinalizeResult ended = reservations.finalize(ticket,
                    new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
                        List.of("a=1", "b=2"), ROWS, ""));
                if (!ended.finalized()) {
                    reservations.release(ticket, CommitTicket.ReleaseReason.CANCELLED);
                }
            }
            held.clear();
        }

        CommitTicket.ReserveRequest request(long plan, long worn) {
            return request(NODE, plan, worn);
        }

        CommitTicket.ReserveRequest request(String node, long plan, long worn) {
            CommitNamespaces.Namespace held = reservations.controller();
            CommitNamespaces.Namespace producer =
                new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L);
            CommitLog.Identity identity = new CommitLog.Identity(CommitLog.SCHEMA_TYPED, INSTANCE,
                EPOCH, CHUNK, STATUS, worn, EFFECT, producer, held);
            EffectStore.Effect variant =
                new EffectStore.Effect(EFFECT, "publish", "PUBLISHED", List.of("a=1", "b=2"));
            return new CommitTicket.ReserveRequest(WORLD, DOMAIN, node, CommitLog.Batch.Kind.APPLY,
                plan, 0, worn, DIGEST, ROWS, identity, held, variant, variant, variant);
        }

        CommitTicket.ReserveRequest request(long plan, long worn, CommitNamespaces.Namespace held) {
            CommitTicket.ReserveRequest named = request(plan, worn);
            CommitLog.Identity identity = named.identity();
            CommitLog.Identity renamed = new CommitLog.Identity(identity.schemaVersion(),
                identity.instanceId(), identity.worldEpoch(), identity.chunkPos(),
                identity.targetStatus(), identity.authorizationGeneration(), identity.effectRef(),
                identity.producer(), held);
            return new CommitTicket.ReserveRequest(named.worldId(), named.domainId(), named.nodeKey(),
                named.kind(), named.planSequence(), named.position(), named.expectedVersion(),
                named.writeSetDigest(), named.rows(), renamed, held, named.published(),
                named.partial(), named.failed());
        }

        IdentityWitness.Authorization read(long plan) {
            return face.liveAuthorization(plan, reservations.controller(), WORLD, DOMAIN, NODE);
        }

        IdentityWitness.Authorization read(long plan, CommitNamespaces.Namespace asker) {
            return face.liveAuthorization(plan, asker, WORLD, DOMAIN, NODE);
        }

        long reads() {
            return face.reads.get();
        }
    }

    private Drive drive;

    private Drive open() throws IOException {
        drive = new Drive();
        return drive;
    }

    @AfterEach
    void leaveTheModuleAsItWas() throws IOException {
        System.clearProperty("arclight.prts.kernel");
        if (drive != null) {
            drive.settle();
        }
        KernelModule module = KernelModule.instance();
        module.markSafePoint();
        module.rollbackCommitChain();
        module.versionSlots().refresh(false);
        module.versionSlots().reset();
        ExecutableProductFace.restore();
        drive = null;
    }

    /** One: a reservation that wears the number this plan's own grant answered is admitted, and the
     * declared-side read answers that number for the pair the request names. */
    @Test
    void aReservationWearingTheNumberItsOwnPlanGrantedIsAdmitted() throws IOException {
        Drive drive = open();
        long granted = drive.publishGrant(PLAN);

        IdentityWitness.Authorization answer = drive.read(PLAN);
        assertEquals(drive.reservations.controller().lifecycleKey(), answer.lifecycleKey(),
            "the answer names the lifecycle the record was published under");
        assertEquals(PLAN, answer.planSequence(), "and the plan number the record carries");
        assertEquals(IdentityWitness.Authorization.Presence.PRESENT, answer.source().presence());
        assertEquals(granted, answer.source().generation());
        assertEquals(IdentityWitness.Authorization.Presence.PRESENT, answer.validity().presence());
        assertEquals(granted, answer.validity().generation());
        assertEquals("", answer.refusal(), "the source judgement refuses nothing here");

        long reads = drive.reads();
        CommitReservation.ReserveResult result = drive.reserve(PLAN, granted);
        assertTrue(result.admitted(), "the record is this plan's own: " + result.diagnostic());
        assertNotNull(result.ticket());
        assertEquals(1L, drive.reads() - reads, "the reservation asks one question once");
        assertEquals(1L, drive.reservations.reserveSuccess());

        reads = drive.reads();
        assertTrue(drive.reservations.beginPublish(result.ticket()),
            "the live slot still carries the frozen number");
        assertEquals(1L, drive.reads() - reads, "the start of the publish takes its own one read");
        assertEquals(0L, drive.reservations.driftRefusals());
    }

    /** Two: the request and the live slot both report a number this plan's own record did not grant,
     * and the source judgement refuses it. This is the case the live-slot legs alone admit. */
    @Test
    void aRequestThatReportsALiveNumberItsOwnRecordDidNotGrantIsRefusedAtTheSource()
        throws IOException {
        Drive drive = open();
        long granted = drive.publishGrant(PLAN);
        long moved = drive.slots.advance(WORLD, WriteLevel.REGION, DOMAIN);
        assertNotEquals(granted, moved, "the slot left the number the plan granted");

        IdentityWitness.Authorization answer = drive.read(PLAN);
        assertEquals(granted, answer.source().generation(),
            "this plan's own record still answers the number it granted");
        assertEquals(moved, answer.validity().generation(),
            "while the live slot answers the number the request wears");
        assertEquals("", answer.refusal(), "and the answer is not a refusal of the source face");

        long reads = drive.reads();
        CommitReservation.ReserveResult refused =
            drive.reserve(PLAN, moved);
        assertFalse(refused.admitted(), "a row wearing a number its own plan did not grant");
        assertEquals(RejectCode.VERSION_MISMATCH, refused.code());
        assertEquals("authorization-mismatch", refused.diagnostic(), "the existing name is reused");
        assertNull(refused.ticket());
        assertEquals(1L, drive.reads() - reads, "the refusal is the source face's, read once");
        assertEquals(0, drive.log.reservedSlots(), "a refused request takes no ring slot");
        assertEquals(0, drive.store.capacitySlots());
    }

    /** Three: the record still answers the frozen number while the slot moved on after the
     * reservation, and the start of the publish refuses from the current-validity face. */
    @Test
    void theStartOfThePublishRefusesASlotThatMovedOnAfterTheReservation() throws IOException {
        Drive drive = open();
        long granted = drive.publishGrant(PLAN);
        CommitReservation.ReserveResult result =
            drive.reserve(PLAN, granted);
        assertTrue(result.admitted(), result.diagnostic());
        CommitTicket ticket = result.ticket();
        long witnessRefusals = drive.reservations.witnessRefusals();

        long moved = drive.slots.advance(WORLD, WriteLevel.REGION, DOMAIN);
        assertEquals(granted, drive.read(PLAN).source().generation(),
            "no new grant was published, so the record still answers the frozen number");

        long reads = drive.reads();
        assertFalse(drive.reservations.beginPublish(ticket), "the frozen number is not the live one");
        assertEquals(1L, drive.reads() - reads, "the start of the publish reads the live slot once");
        assertEquals(1L, drive.reservations.driftRefusals(), "the existing drift counter carries it");
        assertEquals(witnessRefusals, drive.reservations.witnessRefusals(),
            "and it was not the source judgement that refused");
        assertEquals(moved, drive.read(PLAN).validity().generation());
    }

    /** Four: the answer taken at the reservation is not the check of the start of the publish. The
     * answer of the first moment still reads as a pass while the second moment refuses. */
    @Test
    void theAnswerTakenAtTheReservationIsNotReusedAtTheStartOfThePublish() throws IOException {
        Drive drive = open();
        long granted = drive.publishGrant(PLAN);
        long reads = drive.reads();
        CommitReservation.ReserveResult result =
            drive.reserve(PLAN, granted);
        assertTrue(result.admitted(), result.diagnostic());
        assertEquals(1L, drive.reads() - reads);

        IdentityWitness.Authorization takenAtTheReservation = drive.read(PLAN);
        assertEquals(IdentityWitness.Authorization.Presence.PRESENT,
            takenAtTheReservation.validity().presence());
        assertEquals(granted, takenAtTheReservation.validity().generation(),
            "at the reservation the live slot carried the number the line was frozen at");

        long moved = drive.slots.advance(WORLD, WriteLevel.REGION, DOMAIN);
        reads = drive.reads();
        assertFalse(drive.reservations.beginPublish(result.ticket()),
            "the drift after the reservation is judged again");
        assertEquals(1L, drive.reads() - reads, "and judged by a fresh read, never by the old answer");
        IdentityWitness.Authorization fresh = drive.read(PLAN);
        assertEquals(granted, takenAtTheReservation.validity().generation(),
            "the answer taken earlier still says what it said: it was not written over");
        assertEquals(moved, fresh.validity().generation(),
            "while a fresh read of the same face answers the moved slot");
        assertEquals(1L, drive.reservations.driftRefusals());
    }

    /** Five: a slot that was retired with its world is refused, and an absent slot is not a granted
     * zero. */
    @Test
    void aSlotRetiredWithItsWorldRefusesTheStartAndIsNotAGrantedZero() throws IOException {
        Drive drive = open();
        long granted = drive.publishGrant(PLAN);
        CommitReservation.ReserveResult result =
            drive.reserve(PLAN, granted);
        assertTrue(result.admitted(), result.diagnostic());
        assertEquals(granted, drive.read(PLAN).validity().generation());

        drive.slots.noteWorlds(List.of(WORLD));
        assertEquals(1, drive.slots.noteWorlds(List.of(OTHER_WORLD)), "the world's slot is dropped");
        IdentityWitness.Authorization answer = drive.read(PLAN);
        assertEquals(IdentityWitness.Authorization.Presence.PRESENT, answer.source().presence(),
            "the record stayed where it was");
        assertEquals(IdentityWitness.Authorization.Presence.ABSENT, answer.validity().presence(),
            "and the slot is a declared absence, not a value");
        assertNotEquals(WriteVersionSlots.NOT_CARRIED, answer.validity().generation(),
            "an absent slot is never reported as a granted zero");

        assertFalse(drive.reservations.beginPublish(result.ticket()));
        assertEquals(1L, drive.reservations.driftRefusals());
    }

    /** Six: after a lifecycle move the same plan number is handed out again, and the record of the
     * other lifecycle answers nothing for it. */
    @Test
    void aRecordOfAnotherLifecycleAnswersNothingForTheSamePlanNumber() throws IOException {
        Drive drive = open();
        long granted = drive.publishGrant(PLAN);
        CommitNamespaces.Namespace moved = new CommitNamespaces.Namespace(OWNER,
            drive.reservations.controller().lifetime(), drive.epochs.current() + 1L, 77L);
        assertNotEquals(drive.reservations.controller().lifecycleKey(), moved.lifecycleKey());

        IdentityWitness.Authorization answer = drive.read(PLAN, moved);
        assertEquals(IdentityWitness.Authorization.Presence.NOT_READABLE,
            answer.source().presence(), "another lifecycle's record is never read as this one's");
        assertNotEquals(WriteVersionSlots.NOT_CARRIED, answer.source().generation(),
            "and it is not answered as the declared zero either");
        assertNotEquals(0L, answer.source().generation());
        assertEquals(IdentityWitness.Authorization.Presence.NOT_READABLE,
            answer.validity().presence(), "the live slot is not read once the source refuses");
        assertTrue(answer.refusal().length() > 0, "the refusal names its own state");
        assertEquals(PLAN, answer.planSequence(),
            "the answer names the number the record carries, which is the trap of the reset");
        assertEquals(drive.reservations.controller().lifecycleKey(), answer.lifecycleKey());

        assertEquals(granted, drive.read(PLAN).source().generation(),
            "the record itself is untouched and still answers this lifecycle");

        long reads = drive.reads();
        CommitReservation.ReserveResult refused =
            drive.reservations.reserve(drive.request(PLAN, granted, moved));
        assertEquals("controller-namespace-mismatch", refused.diagnostic(),
            "the pre-existing lifecycle leg refuses the request first, by its own name");
        assertEquals(0L, drive.reads() - reads, "and the source face is never asked");
    }

    /** Seven: a record replaced by a later plan, no record at all, a grant that answered the
     * declared zero and a domain this plan did not grant are four answers that stay apart. */
    @Test
    void everyStateOfTheRecordIsReadAsItsOwnState() throws IOException {
        Drive drive = open();
        long granted = drive.publishGrant(PLAN);

        OwnershipDomain domain = new OwnershipDomain(WORLD, WriteLevel.REGION, DOMAIN);
        drive.slots.noteGranted(LATER_PLAN, Map.of(domain, granted + 1L));
        IdentityWitness.Authorization replaced = drive.read(PLAN);
        assertEquals(IdentityWitness.Authorization.Presence.NOT_READABLE,
            replaced.source().presence());
        assertEquals(LATER_PLAN, replaced.planSequence(), "the answer names the plan in hand");
        assertEquals(drive.reservations.controller().lifecycleKey(), replaced.lifecycleKey());
        long replacedReads = drive.reads();
        assertEquals("authorization-mismatch",
            drive.reserve(PLAN, granted).diagnostic());
        assertEquals(1L, drive.reads() - replacedReads,
            "the answer was read once for the refusal and not again for a comparison");

        drive.slots.reset();
        IdentityWitness.Authorization missing = drive.read(PLAN);
        assertEquals(IdentityWitness.Authorization.Presence.NOT_READABLE,
            missing.source().presence());
        assertEquals(0L, missing.planSequence(), "no record reads as the record's own sentinel");
        assertEquals("", missing.lifecycleKey(), "and names no lifecycle at all");
        assertNotEquals(replaced.refusal(), missing.refusal(),
            "a replaced record and no record are two states and not one");

        drive.slots.refresh(false);
        assertEquals(WriteVersionSlots.NOT_CARRIED, drive.slots.grant(WORLD, WriteLevel.REGION, DOMAIN));
        drive.slots.noteGranted(PLAN, Map.of(domain, WriteVersionSlots.NOT_CARRIED));
        IdentityWitness.Authorization zero = drive.read(PLAN);
        assertEquals(IdentityWitness.Authorization.Presence.PRESENT, zero.source().presence(),
            "a grant that answered the declared zero is a readable answer");
        assertEquals(WriteVersionSlots.NOT_CARRIED, zero.source().generation());
        assertEquals(PLAN, zero.planSequence());
        assertNotEquals("", zero.refusal(), "and it is still never a pass");
        assertNotEquals(missing.source().presence(), zero.source().presence());

        drive.slots.refresh(true);
        drive.slots.noteGranted(PLAN, Map.of(new OwnershipDomain(WORLD, WriteLevel.REGION,
            OTHER_DOMAIN), granted));
        IdentityWitness.Authorization elsewhere = drive.read(PLAN);
        assertEquals(IdentityWitness.Authorization.Presence.ABSENT, elsewhere.source().presence(),
            "this plan's own record holds no grant for this domain");
        assertEquals(PLAN, elsewhere.planSequence(), "and it is this plan's record that was read");
        assertNotEquals(zero.source().presence(), elsewhere.source().presence(),
            "a domain that was not granted is not a grant that answered zero");
    }

    /** Eight: the two in-tree constants fail closed, and the declared face answers for no plan
     * identity but the one it was asked about. */
    @Test
    void theInTreeConstantsFailClosedForAnotherPlanOrLifecycle() throws IOException {
        Drive drive = open();
        long granted = drive.publishGrant(PLAN);
        IdentityWitness.Authorization fromNone = IdentityWitness.NONE.liveAuthorization(PLAN,
            drive.reservations.controller(), WORLD, DOMAIN, NODE);
        assertEquals(IdentityWitness.Authorization.Presence.NOT_READABLE,
            fromNone.source().presence(), "the constant with nothing behind it answers no value");
        assertEquals(IdentityWitness.Authorization.Presence.NOT_READABLE,
            fromNone.validity().presence());

        IdentityWitness.Source fixed = IdentityWitness.fixed(drive.witness.of(PLAN).declarations(),
            EPOCH, granted);
        IdentityWitness.Authorization itsOwn = fixed.liveAuthorization(PLAN,
            drive.reservations.controller(), WORLD, DOMAIN, NODE);
        assertEquals(IdentityWitness.Authorization.Presence.PRESENT, itsOwn.source().presence(),
            "it answers for the plan and the lifecycle its own declaration names");
        assertEquals(granted, itsOwn.source().generation());

        IdentityWitness.Authorization anotherPlan = fixed.liveAuthorization(LATER_PLAN,
            drive.reservations.controller(), WORLD, DOMAIN, NODE);
        assertEquals(IdentityWitness.Authorization.Presence.NOT_READABLE,
            anotherPlan.source().presence(), "another plan receives no value");
        CommitNamespaces.Namespace moved = new CommitNamespaces.Namespace(OWNER,
            drive.reservations.controller().lifetime(), drive.epochs.current() + 1L, 77L);
        IdentityWitness.Authorization anotherLifecycle =
            fixed.liveAuthorization(PLAN, moved, WORLD, DOMAIN, NODE);
        assertEquals(IdentityWitness.Authorization.Presence.NOT_READABLE,
            anotherLifecycle.source().presence(), "and neither does another lifecycle");

        IdentityWitness.Authorization unknown = drive.read(LATER_PLAN);
        assertEquals(IdentityWitness.Authorization.Presence.NOT_READABLE,
            unknown.source().presence(), "a plan the record does not belong to answers nothing");
        assertNotEquals(0L, unknown.source().generation());
    }

    /** Nine: a refusal takes nothing, forges nothing and releases nothing. */
    @Test
    void aRefusedStartLeavesTheDutiesTheResourcesAndTheEvidenceWhereTheyWere() throws IOException {
        Drive drive = open();
        long granted = drive.publishGrant(PLAN);
        CommitReservation.ReserveResult result =
            drive.reserve(PLAN, granted);
        assertTrue(result.admitted(), result.diagnostic());
        CommitTicket ticket = result.ticket();
        assertEquals(1, drive.reservations.outstanding());
        assertEquals(1, drive.log.reservedSlots());
        assertEquals(1, drive.store.capacitySlots());

        drive.slots.noteGranted(LATER_PLAN, Map.of(new OwnershipDomain(WORLD, WriteLevel.REGION,
            DOMAIN), granted + 1L));
        assertFalse(drive.reservations.beginPublish(ticket), "the record is no longer this plan's");
        assertEquals(1L, drive.reservations.driftRefusals());
        assertEquals(0L, drive.controller.submits(), "no publish action was reached");
        assertEquals(0L, drive.controller.supplierCalls(), "and no supplier was called");
        assertEquals(1, drive.reservations.outstanding(), "the ticket is still held");
        assertEquals(0, drive.reservations.publishing(), "and it never started publishing");
        assertEquals(0, drive.reservations.pendingCount(), "no terminal was forged for it");
        assertEquals(0L, drive.log.sequence(), "and no line was written");
        assertEquals(1, drive.log.reservedSlots(), "its ring slot is still its own");
        assertEquals(1, drive.store.capacitySlots(), "and so is its effect room");
        assertEquals(1L, drive.log.reservedSlots());

        assertEquals("released", drive.reservations
            .release(ticket, CommitTicket.ReleaseReason.CANCELLED).diagnostic(),
            "a ticket that never published goes back through the existing release path");
        assertEquals(0, drive.reservations.outstanding());
        assertEquals(0, drive.log.reservedSlots());
        assertEquals(0, drive.store.capacitySlots());
    }

    /** Ten: the lifecycle leg of the request keeps its own name and its own place, earlier than the
     * pair-keyed source judgement. */
    @Test
    void theControllerNamespaceLegStillFiresFirstAndByItsOwnName() throws IOException {
        Drive drive = open();
        long granted = drive.publishGrant(PLAN);
        CommitNamespaces.Namespace stale = new CommitNamespaces.Namespace(OWNER,
            drive.reservations.controller().lifetime(), drive.epochs.current() + 1L, 77L);

        long reads = drive.reads();
        CommitReservation.ReserveResult refused =
            drive.reservations.reserve(drive.request(PLAN, granted, stale));
        assertEquals("controller-namespace-mismatch", refused.diagnostic());
        assertEquals(RejectCode.VERSION_MISMATCH, refused.code());
        assertEquals(0L, drive.reads() - reads,
            "it is the earlier leg that refuses, so the source is not asked at all");
        assertEquals(0L, drive.reservations.witnessRefusals());
        assertEquals(1L, drive.reservations.requestRefusals());
    }

    /** Eleven: a line of the neighbouring plan wearing the number the live slot carries is refused
     * at the source, because the record in hand is not this plan's own. A parent tree that compares
     * two request-side numbers admits this row: the live slot agreeing with the row says nothing
     * about which plan granted the number. */
    @Test
    void aLineOfANeighbouringPlanIsRefusedAtTheSourceWhileTheLiveSlotAgreesWithIt()
        throws IOException {
        Drive drive = open();
        long granted = drive.publishGrant(PLAN);
        long moved = drive.slots.advance(WORLD, WriteLevel.REGION, DOMAIN);

        CommitTicket.ReserveRequest neighbour = drive.request(NEIGHBOUR_NODE, LATER_PLAN, moved);
        assertEquals(moved, drive.slots.peek(WORLD, WriteLevel.REGION, DOMAIN),
            "the live slot carries the very number the line wears");
        IdentityWitness.Authorization answer = drive.face.liveAuthorization(LATER_PLAN,
            drive.reservations.controller(), WORLD, DOMAIN, NEIGHBOUR_NODE);
        assertEquals(IdentityWitness.Authorization.Presence.NOT_READABLE,
            answer.source().presence(), "and the record in hand belongs to another plan");
        assertEquals(PLAN, answer.planSequence(), "the answer names the plan that granted it");
        assertEquals(granted, drive.read(PLAN).source().generation(),
            "which is not the number the neighbour wears");

        CommitReservation.ReserveResult refused = drive.reserve(neighbour);
        assertFalse(refused.admitted(), "a neighbouring plan never answers for this one");
        assertEquals("authorization-mismatch", refused.diagnostic());
        assertEquals(RejectCode.VERSION_MISMATCH, refused.code());
        assertNull(refused.ticket());
        assertEquals(0, drive.log.reservedSlots(), "and it takes no ring slot on its way out");
        assertEquals(0, drive.store.capacitySlots());
    }
}
