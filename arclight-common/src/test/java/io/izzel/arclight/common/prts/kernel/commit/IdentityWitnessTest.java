/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The witness core: the seat a line is judged at, the artefact its declaration carries, the
 * agreement of the three namespaces, and the canonical comparison key two runs are read by. The
 * cases that must be refused are here beside the cases that must be admitted, because a policy that
 * refuses everything would pass every negative and prove nothing. */
class IdentityWitnessTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "chunk-pipeline";
    private static final String NODE = "chunk-pipeline/world-a/mailbox-1";
    private static final String OTHER_NODE = "chunk-pipeline/world-a/mailbox-2";
    private static final long EPOCH = 5L;
    private static final long GRANT = 7L;
    private static final long EFFECT = 100L;
    private static final long INSTANCE = 1L;
    private static final String LIFECYCLE = "owner-a/5150/0";

    /** A holder that answers with one sealed table and the generations it was sealed under, both of
     * which a test may move to drive the drift check. */
    private static final class Holder implements IdentityWitness.Source {

        private FrozenDeclarations sealed;
        private final CommitLog.OrderSource plans;
        long worldEpoch = EPOCH;
        long authorization = GRANT;
        CommitNamespaces.Namespace current;

        Holder(FrozenDeclarations sealed, CommitLog.OrderSource plans) {
            this.sealed = sealed;
            this.plans = plans;
        }

        @Override
        public FrozenDeclarations declarations(String worldId, String domainId, long planSequence) {
            return sealed.planSequence() == planSequence ? sealed : FrozenDeclarations.none();
        }

        @Override
        public long liveWorldEpoch(String worldId) {
            return worldEpoch;
        }

        @Override
        public IdentityWitness.Authorization liveAuthorization(long planSequence,
                CommitNamespaces.Namespace planController, String worldId, String domainId,
                String nodeKey) {
            if (planController == null || (current != null
                && !planController.lifecycleKey().equals(current.lifecycleKey()))) {
                return IdentityWitness.Authorization.refused("", 0L,
                    IdentityWitness.Authorization.Face.notRead(), "not-this-double");
            }
            return IdentityWitness.Authorization.answered(planController.lifecycleKey(), planSequence,
                authorization, IdentityWitness.Authorization.Face.present(authorization));
        }

        @Override
        public CommitNamespaces.Namespace currentController() {
            return current;
        }
    }

    private static CommitLog.OrderSource plans(long plan, int position) {
        return (worldId, domainId, nodeKey) -> NODE.equals(nodeKey) || OTHER_NODE.equals(nodeKey)
            ? new CommitLog.Resolved(plan, NODE.equals(nodeKey) ? position : position + 1, false)
            : null;
    }

    /** One declared artefact: the node, the chunk that packs to this value and the state. */
    private static FrozenDeclarations.Candidate candidate(String node, long packedChunk, String status) {
        return candidate(WORLD, DOMAIN, node, packedChunk, status, INSTANCE, EPOCH);
    }

    private static FrozenDeclarations.Candidate candidate(String worldId, String domainId, String node,
                                                         long packedChunk, String status, long instance,
                                                         long epoch) {
        return new FrozenDeclarations.Candidate(worldId, domainId, node,
            new FrozenDeclarations.DeclaredChunk(packedChunk), status, instance, epoch);
    }

    private static FrozenDeclarations.FreezeKey freeze(long generation) {
        return new FrozenDeclarations.FreezeKey(LIFECYCLE, generation);
    }

    private static FrozenDeclarations.FreezeKey freeze(long generation, String lifecycleKey) {
        return new FrozenDeclarations.FreezeKey(lifecycleKey, generation);
    }

    private static FrozenDeclarations sealed(long plan, String node, int position, long packedChunk,
                                             String status) {
        return sealed(plan, node, position, packedChunk, status, INSTANCE, EPOCH, LIFECYCLE);
    }

    private static FrozenDeclarations sealed(long plan, String node, int position, long packedChunk,
                                             String status, long instance, long epoch) {
        return sealed(plan, node, position, packedChunk, status, instance, epoch, LIFECYCLE);
    }

    /** One freeze of one declaration, sealed under the generation and the lifecycle it belongs to:
     * the two numbers a run of its own authorized are what the table is read back by. */
    private static FrozenDeclarations sealed(long plan, String node, int position, long packedChunk,
                                             String status, long instance, long epoch,
                                             String lifecycleKey) {
        FrozenDeclarations.Sealed result = FrozenDeclarations.seal(freeze(1L, lifecycleKey), plan,
            List.of(candidate(WORLD, DOMAIN, node, packedChunk, status, instance, epoch)),
            List.of(new FrozenDeclarations.Assignment(WORLD, DOMAIN, node, position)));
        assertTrue(result.sealed(), result.refusal());
        return result.declarations();
    }

    private static FrozenDeclarations.Seat seat(long plan, int position) {
        return new FrozenDeclarations.Seat(WORLD, DOMAIN, plan, position);
    }

    private static CommitLog.Identity identity(long instance, long chunk, String status,
                                               CommitNamespaces.Namespace controller) {
        return identity(instance, EPOCH, chunk, status, controller);
    }

    private static CommitLog.Identity identity(long instance, long epoch, long chunk, String status,
                                               CommitNamespaces.Namespace controller) {
        return new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instance, epoch, chunk, status, GRANT,
            EFFECT, new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L), controller);
    }

    private static CommitNamespaces.Namespace controller() {
        return new CommitNamespaces.Namespace("owner-a", 5150L, 0L, CommitNamespaces.NO_THREAD);
    }

    private static IdentityWitness.Verdict verify(FrozenDeclarations table, long plan, int position,
                                                  String node, CommitLog.Identity identity,
                                                  CommitNamespaces.Namespace current) {
        return IdentityWitness.verify(table, new CommitLog.Resolved(plan, position, false), WORLD,
            DOMAIN, node, identity, current);
    }

    /** The cross-run key is rebuilt at the one point both sides read, and the text the witness core
     * answers is that rebuild's canonical form and not a second formulation that renders alike. */
    @Test
    void theLogicalKeyIsRebuiltAtOnePointAndBothSidesReadThatOnePoint() {
        FrozenDeclarations table = sealed(9L, NODE, 0, 64L, "full");
        CommitLog.Identity line = identity(INSTANCE, 64L, "full", controller());
        IdentityWitness.Verdict witness = verify(table, 9L, 0, NODE, line, controller());

        assertTrue(witness.accepted(), witness.reason());
        FrozenDeclarations.Entry entry = table.entryAt(seat(9L, 0));
        assertNotNull(entry);
        IdentityWitness.LogicalKey declared = CommitLog.logicalKeyOf(entry);
        IdentityWitness.LogicalKey carried = CommitLog.logicalKeyOf(WORLD, DOMAIN, NODE, entry.role(),
            line);
        assertNotNull(declared);
        assertNotNull(carried);
        assertEquals(declared, carried);
        assertEquals(declared.canonical(), witness.instance());
        assertEquals(declared, IdentityWitness.parse(witness.instance()));
    }

    /** A seat the table wrote nothing for is answered with no key at all: an empty key would compare
     * equal to another empty one and read as an identity nothing declared. */
    @Test
    void aSeatTheTableWroteNothingForIsAnsweredWithNoKeyInsteadOfAnEmptyOne() {
        FrozenDeclarations table = sealed(9L, NODE, 0, 64L, "full");

        assertNull(CommitLog.logicalKeyOf((FrozenDeclarations.Entry) null));
        assertNull(CommitLog.logicalKeyOf(table.entryAt(seat(9L, 3))));
        assertNull(CommitLog.logicalKeyOf(WORLD, DOMAIN, NODE, 1, null));
    }

    /** The number a run assigned is not in the key: two freezes of the same declaration that minted
     * different instance numbers rebuild one key, and the write side still reads both. */
    @Test
    void twoRunsThatAssignedOtherInstanceNumbersRebuildTheSameLogicalKey() {
        FrozenDeclarations first = sealed(9L, NODE, 0, 64L, "full", 11L, EPOCH);
        FrozenDeclarations second = sealed(9L, NODE, 0, 64L, "full", 12L, EPOCH);
        IdentityWitness.LogicalKey a = CommitLog.logicalKeyOf(first.entryAt(seat(9L, 0)));
        IdentityWitness.LogicalKey b = CommitLog.logicalKeyOf(second.entryAt(seat(9L, 0)));

        assertNotNull(a);
        assertNotNull(b);
        assertEquals(a, b);
        assertEquals(a.canonical(), b.canonical());
        assertEquals(11L, first.entryAt(seat(9L, 0)).instanceId());
        assertEquals(12L, second.entryAt(seat(9L, 0)).instanceId());
    }

    /** Another logical declaration never rebuilds to the same key, however alike the run's numbers
     * were: the chunk and the state are part of what was declared. */
    @Test
    void anotherLogicalDeclarationNeverRebuildsToTheSameKey() {
        IdentityWitness.LogicalKey base = CommitLog.logicalKeyOf(
            sealed(9L, NODE, 0, 64L, "full").entryAt(seat(9L, 0)));
        IdentityWitness.LogicalKey otherChunk = CommitLog.logicalKeyOf(
            sealed(9L, NODE, 0, 65L, "full").entryAt(seat(9L, 0)));
        IdentityWitness.LogicalKey otherStatus = CommitLog.logicalKeyOf(
            sealed(9L, NODE, 0, 64L, "empty").entryAt(seat(9L, 0)));

        assertNotNull(base);
        assertNotEquals(base, otherChunk);
        assertNotEquals(base, otherStatus);
        assertNotEquals(base.canonical(), otherChunk.canonical());
        assertNotEquals(base.canonical(), otherStatus.canonical());
    }

    @Test
    void theFinalSeatOfTheGraphIsWhatBindsNotTheOrderOfTheDeclarations() {
        List<FrozenDeclarations.Candidate> declared = List.of(candidate(NODE, 64L, "full"),
            candidate(OTHER_NODE, 65L, "full"));
        List<FrozenDeclarations.Assignment> graph = List.of(
            new FrozenDeclarations.Assignment(WORLD, DOMAIN, OTHER_NODE, 0),
            new FrozenDeclarations.Assignment(WORLD, DOMAIN, NODE, 1));
        FrozenDeclarations.Sealed result = FrozenDeclarations.seal(freeze(1L), 9L, declared, graph);

        assertTrue(result.sealed(), result.refusal());
        FrozenDeclarations table = result.declarations();
        assertEquals(64L, table
            .entryAt(new FrozenDeclarations.Seat(WORLD, DOMAIN, 9L, 1)).packedChunkPos(),
            "the node declared first took the seat the graph gave it, not the one it arrived with");
        assertEquals(OTHER_NODE,
            table.entryAt(new FrozenDeclarations.Seat(WORLD, DOMAIN, 9L, 0)).nodeKey());
        assertEquals(2, table.size());
    }

    @Test
    void aTableOfAnotherFreezeAndTheSeatOfAnotherPlanAreBothRefused() {
        FrozenDeclarations table = sealed(9L, NODE, 1, 64L, "full");
        CommitLog.Identity worn = identity(1L, 64L, "full", controller());

        assertEquals("declaration-plan-mismatch",
            verify(table, 10L, 1, NODE, worn, controller()).reason());
        assertEquals("declaration-missing",
            verify(FrozenDeclarations.none(), 9L, 1, NODE, worn, controller()).reason());
        assertEquals("declaration-missing",
            verify(table, 9L, 2, NODE, worn, controller()).reason());
    }

    @Test
    void theNextPlanOfANodeDoesNotReadTheTableOfTheLastOne() {
        FrozenDeclarations older = sealed(9L, NODE, 1, 64L, "full");
        FrozenDeclarations newer = sealed(10L, NODE, 1, 64L, "full");
        Holder holder = new Holder(newer, plans(10L, 1));
        holder.current = controller();
        CommitLog.Identity worn = identity(1L, 64L, "full", controller());

        assertTrue(verify(holder.declarations(WORLD, DOMAIN, 10L), 10L, 1, NODE, worn,
            controller()).accepted());
        assertEquals("declaration-missing",
            verify(holder.declarations(WORLD, DOMAIN, 9L), 9L, 1, NODE, worn, controller()).reason(),
            "the holder answers for the freeze it published and for no other");
        assertNotNull(older.entryAt(new FrozenDeclarations.Seat(WORLD, DOMAIN, 9L, 1)));
    }

    @Test
    void aRefusedSealWritesNoTableAndNothingMayBeAuthorizedThroughIt() {
        FrozenDeclarations.Sealed duplicate = FrozenDeclarations.seal(freeze(1L), 9L,
            List.of(candidate(NODE, 64L, "full"), candidate(NODE, 65L, "full")),
            List.of(new FrozenDeclarations.Assignment(WORLD, DOMAIN, NODE, 0)));

        assertFalse(duplicate.sealed(), "one node of one pair is declared once in one freeze");
        assertEquals("declaration-duplicate", duplicate.refusal());
        assertNull(duplicate.declarations());
        assertEquals("declaration-missing", verify(FrozenDeclarations.none(), 9L, 0, NODE,
            identity(1L, 64L, "full", controller()), controller()).reason());
    }

    @Test
    void aDeclarationWithoutItsArtefactTakesNoEntry() {
        FrozenDeclarations.Sealed result = FrozenDeclarations.seal(freeze(1L), 9L,
            List.of(new FrozenDeclarations.Candidate(WORLD, DOMAIN, NODE, null, "")),
            List.of(new FrozenDeclarations.Assignment(WORLD, DOMAIN, NODE, 0)));

        assertTrue(result.sealed());
        assertEquals(FrozenDeclarations.NOT_WIRED, result.declarations().state());
        assertEquals(0, result.declarations().size());
        assertEquals("declaration-missing", verify(result.declarations(), 9L, 0, NODE,
            identity(1L, 64L, "full", controller()), controller()).reason());
    }

    @Test
    void theSameLogicalDeclarationComparesEqualWhateverTheRunCounted() {
        FrozenDeclarations first = sealed(4L, NODE, 3, 64L, "full", 1L, 5L);
        FrozenDeclarations second = sealed(91L, NODE, 17, 64L, "full", 8848L, 21L);
        IdentityWitness.Verdict left = verify(first, 4L, 3, NODE, identity(1L, 64L, "full", controller()),
            controller());
        IdentityWitness.Verdict right = verify(second, 91L, 17, NODE,
            identity(8848L, 21L, 64L, "full", controller()), controller());

        assertTrue(left.accepted(), "the mapping of this run is the one its own freeze authorized");
        assertTrue(right.accepted(), "and the other run's mapping is the one the other freeze did");
        assertEquals(left.instance(), right.instance(),
            "the plan counter, the seat, the instance number, the generation and the lifetime are not "
                + "part of it: two valid mappings of two runs compare equal");
    }

    @Test
    void anotherLogicalDeclarationIsNeverAnsweredEqualOnAFirstAppearance() {
        FrozenDeclarations table = sealed(9L, NODE, 0, 64L, "full");
        FrozenDeclarations other = sealed(9L, NODE, 0, 65L, "full");
        String left = verify(table, 9L, 0, NODE, identity(1L, 64L, "full", controller()),
            controller()).instance();
        String right = verify(other, 9L, 0, NODE, identity(1L, 65L, "full", controller()),
            controller()).instance();

        assertNotEquals(left, right, "the first appearance of either side would have been one");
        assertNotEquals(left, verify(sealed(9L, NODE, 0, 64L, "partial"), 9L, 0, NODE,
            identity(1L, 64L, "partial", controller()), controller()).instance(), "another state");
        assertNotEquals(left, verify(sealed(9L, OTHER_NODE, 0, 64L, "full"), 9L, 0, OTHER_NODE,
            identity(1L, 64L, "full", controller()), controller()).instance(), "another node");
    }

    @Test
    void aLineWearingAnIdentityItsDeclarationDoesNotCarryIsRefused() {
        FrozenDeclarations table = sealed(9L, NODE, 1, 64L, "full");
        CommitLog.Identity worn = identity(1L, 64L, "full", controller());

        assertEquals("chunk-witness-mismatch",
            verify(table, 9L, 1, NODE, identity(1L, 999L, "full", controller()), controller()).reason());
        assertEquals("declaration-status-mismatch",
            verify(table, 9L, 1, NODE, identity(1L, 64L, "empty", controller()), controller()).reason());
        assertEquals("declaration-seat-mismatch",
            verify(table, 9L, 1, OTHER_NODE, worn, controller()).reason());
        assertEquals("identity-undeclared", verify(table, 9L, 1, NODE,
            new CommitLog.Identity(CommitLog.SCHEMA_TYPED, 1L, EPOCH, 64L, "full", GRANT, EFFECT,
                new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L),
                CommitNamespaces.Namespace.NONE), controller()).reason());
        assertEquals("controller-namespace-mismatch", verify(table, 9L, 1, NODE,
            identity(1L, 64L, "full", new CommitNamespaces.Namespace("owner-b", 5150L, 0L, 0L)),
            controller()).reason());
    }

    @Test
    void theOriginAndTheNegativeCornersAreLegalChunkWitnesses() {
        FrozenDeclarations origin = sealed(9L, NODE, 0, 0L, "full");
        FrozenDeclarations corner = sealed(9L, NODE, 0, -1099511627776L, "full");

        assertTrue(verify(origin, 9L, 0, NODE, identity(1L, 0L, "full", controller()), controller())
            .accepted(), "the chunk at the origin packs to the same value a missing one would");
        assertTrue(verify(corner, 9L, 0, NODE, identity(1L, -1099511627776L, "full", controller()),
            controller()).accepted(), "a negative corner packs to a negative value and is legal");
        assertEquals("declaration-missing", verify(FrozenDeclarations.of(freeze(1L), 9L, List.of())
            .declarations(), 9L, 0, NODE, identity(1L, 0L, "full", controller()), controller()).reason(),
            "a table with no entry is not a table that declared the origin");
    }

    @Test
    void aNumberThatHappensToMatchIsNeitherAProofNorAnError() {
        FrozenDeclarations table = sealed(9L, NODE, 0, 64L, "full");
        IdentityWitness.Verdict first = verify(table, 9L, 0, NODE, identity(1L, 64L, "full", controller()),
            controller());
        IdentityWitness.Verdict second = verify(table, 9L, 0, NODE,
            identity(1L, 64L, "full", controller()), controller());

        assertTrue(first.accepted());
        assertTrue(second.accepted(), "two lines may carry the same number and both be backed");
        assertEquals(first.instance(), second.instance());
    }

    @Test
    void theCanonicalFormRoundTripsAndNoSpliceCanBeReadTwoWays() {
        IdentityWitness.LogicalKey key = new IdentityWitness.LogicalKey("a/b", "c", "d;e", 3, -7L,
            "f/g");
        IdentityWitness.LogicalKey back = IdentityWitness.parse(key.canonical());

        assertEquals(key, back);
        assertNotEquals(new IdentityWitness.LogicalKey("a", "b/c", "d;e", 3, -7L, "f/g").canonical(),
            key.canonical(), "an unescaped splice would read these two as one");
        assertNotEquals(new IdentityWitness.LogicalKey("a/b", "c", "d;e", 3, -7L, "f").canonical(),
            key.canonical());
        assertNull(IdentityWitness.parse(key.canonical() + "x"));
        assertNull(IdentityWitness.parse("I1"));
        assertNull(IdentityWitness.parse(null));
    }

    @Test
    void noCanonicalFormOfTwoDifferentKeysCollides() {
        List<String> values = List.of("", "a", "a/b", "0", "-7", "#", ":", "ab");
        List<String> forms = new ArrayList<>();
        for (String world : values) {
            for (String domain : values) {
                for (String status : values) {
                    for (long chunk : new long[] {0L, 1L, -1L}) {
                        forms.add(new IdentityWitness.LogicalKey(world, domain, "n", 1, chunk, status)
                            .canonical());
                    }
                }
            }
        }
        assertEquals(forms.size(), List.copyOf(new java.util.LinkedHashSet<>(forms)).size(),
            "every distinct key carries a distinct form and every form parses back to its key");
        for (String form : forms) {
            assertNotNull(IdentityWitness.parse(form), form);
        }
    }

    @Test
    void theReservationRefusesBeforeARingSlotOrARoomIsTakenAndCallsNoAction() {
        AtomicLong calls = new AtomicLong();
        EffectStore store = new EffectStore(65536, 16);
        CommitLog log = new CommitLog(() -> 4, plans(9L, 0), store);
        CommitReservation reservations = new CommitReservation(log, store, plans(9L, 0), "owner-a",
            new CommitNamespaces.ResetEpochs());
        CommitController controller = new CommitController(reservations, line -> {
            calls.incrementAndGet();
            return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
                List.of("a=1", "b=2"), 2, "");
        });
        log.beginTick(1L);

        CommitController.SubmitResult refused = controller.submit(request(reservations, 1L));

        assertFalse(refused.admitted());
        assertEquals("declaration-missing", refused.diagnostic(), "the freeze backs no such line");
        assertEquals(0L, calls.get(), "no publish action is reached");
        assertEquals(0, log.ringDepth(), "the ring holds nothing");
        assertEquals(0, log.reservedSlots(), "no slot was handed out");
        assertEquals(0, store.capacitySlots(), "no room was taken");
        assertEquals(0, reservations.outstanding(), "the reservation was never taken");
        assertEquals(0L, reservations.reserveSuccess());
        assertEquals(1L, reservations.witnessRefusals());
    }

    @Test
    void aLineThatMovesAfterItsReservationIsNotStartedAndIsGivenBackUnstarted() {
        EffectStore store = new EffectStore(65536, 16);
        Holder holder = new Holder(FrozenDeclarations.none(), plans(9L, 0));
        CommitLog log = new CommitLog(() -> 4, plans(9L, 0), store, holder);
        CommitReservation reservations = new CommitReservation(log, store, plans(9L, 0), "owner-a",
            new CommitNamespaces.ResetEpochs());
        holder.sealed = sealed(9L, NODE, 0, 64L, "full", INSTANCE, EPOCH,
            reservations.controller().lifecycleKey());
        holder.current = reservations.controller();

        CommitReservation.ReserveResult first = reservations.reserve(request(reservations, 1L));
        CommitTicket moved = first.ticket();
        assertNotNull(moved, "the freeze backs this line while its generations still hold: "
            + first.diagnostic());

        holder.worldEpoch = EPOCH + 1L;
        assertFalse(reservations.beginPublish(moved), "the world generation moved on");
        assertEquals(1L, reservations.driftRefusals());

        holder.worldEpoch = EPOCH;
        holder.authorization = GRANT + 1L;
        assertFalse(reservations.beginPublish(moved), "the grant was retired");
        assertEquals(2L, reservations.driftRefusals());

        assertEquals("released",
            reservations.release(moved, CommitTicket.ReleaseReason.CANCELLED).diagnostic(),
            "a line that never started is given back as an unstarted ticket");
        assertEquals(0, log.ringDepth(), "and it wrote no line");
        assertEquals(0, store.capacitySlots());

        holder.authorization = GRANT;
        CommitTicket still = reservations.reserve(request(reservations, 1L)).ticket();
        assertNotNull(still, "the mapping of the seat is the one its own freeze authorized");
        assertTrue(reservations.beginPublish(still), "with nothing moved the line may run");
    }

    @Test
    void theThreeNamespacesHaveToAgreeAndTheHoldersAnswerIsTheOneThatCounts() {
        CommitNamespaces.Namespace holder = controller();
        FrozenDeclarations table = sealed(9L, NODE, 0, 64L, "full");
        CommitLog.Identity worn = identity(1L, 64L, "full", holder);

        assertTrue(verify(table, 9L, 0, NODE, worn, holder).accepted());
        assertEquals("controller-namespace-mismatch",
            verify(table, 9L, 0, NODE, worn, new CommitNamespaces.Namespace("owner-a", 5151L, 0L, 0L))
                .reason(), "the holder judges by the lifecycle it holds now");
        assertEquals("declaration-lifecycle-mismatch",
            verify(sealed(9L, NODE, 0, 64L, "full", INSTANCE, EPOCH, "owner-a/5151/0"), 9L, 0, NODE, worn,
                holder).reason(),
            "a table sealed under another lifecycle is evidence at most, whatever the line wears");
    }

    @Test
    void aStrictReplayNeedsItsClosureAndRefusesWithoutOne() {
        CommitLog.SchemaReplay side = typedSide();
        ReplayComparator comparator = new ReplayComparator();
        FrozenDeclarations table = sealed(1L, NODE, 0, 64L, "full");
        ReplayComparator.Evidence good = new ReplayComparator.Evidence("85bcdea", 
            ReplayComparator.recordsDigest(side), table, side);

        assertTrue(comparator.compare(good, good).equal(), "one run against itself is equal");
        assertTrue(comparator.compare(new ReplayComparator.Evidence("85bcdea",
                ReplayComparator.recordsDigest(side), table, side),
            new ReplayComparator.Evidence("85bcdea", ReplayComparator.recordsDigest(side), table, side))
            .equal());
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, comparator.compare(
            new ReplayComparator.Evidence("85bcdea", ReplayComparator.recordsDigest(side), null, side),
            good).reason(), "no table, no strict conclusion");
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, comparator.compare(
            new ReplayComparator.Evidence("", ReplayComparator.recordsDigest(side), table, side),
            good).reason(), "a closure without the commit it came from is refused");
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, comparator.compare(
            new ReplayComparator.Evidence("85bcdea", "deadbee", table, side),
            good).reason(), "a summary that is not this side's own is another run's closure");
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, comparator.compare(
            new ReplayComparator.Evidence("85bcdea", ReplayComparator.recordsDigest(side), sealed(2L,
                NODE, 0, 64L, "full"), side), good).reason(),
            "a table of another freeze is not the freeze of these records");
    }

    @Test
    void theHistoricalReadingStillParsesAndSaysItsProvenanceIsNotVerified() {
        CommitLog.SchemaReplay side = typedSide();
        ReplayComparator comparator = new ReplayComparator();
        ReplayComparator.Verdict typed = comparator.compare(side, side);

        assertTrue(typed.equal(), "a version 1 side still reads under its own declared version");
        assertTrue(typed.note().contains("provenance"),
            "and says the instance column of that reading is not backed by a seal");
        CommitLog legacy = new CommitLog(() -> 8, (worldId, domainId, nodeKey) ->
            new CommitLog.Resolved(1L, 0, false), null);
        legacy.beginTick(1L);
        legacy.reach(new CommitLog.Batch(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY, GRANT, 11L,
            1));
        legacy.closeTick();
        ReplayComparator.Verdict plain = comparator.compare(comparator.legacyDeclared(legacy.last()),
            comparator.legacyDeclared(legacy.last()));
        assertTrue(plain.equal());
        assertTrue(plain.note().contains("version 0"), "version 0 stays readable and unchanged");
    }

    @Test
    void aPolicyThatRefusesEverythingCannotPassThisGate() {
        FrozenDeclarations table = sealed(9L, NODE, 0, 64L, "full");

        assertTrue(verify(table, 9L, 0, NODE, identity(1L, 64L, "full", controller()), controller())
            .accepted(), "a legal line is admitted, so refusing all of them is not a policy");
        assertEquals(ReplayComparator.Decision.EQUAL, new ReplayComparator().compare(typedSide(),
            typedSide()).decision(), "and a legal pair is still answered");
    }

@Test
    void aLineWearingAnInstanceItsOwnFreezeDidNotAuthorizeIsRefusedBeforeAnyRoomIsTaken() {
        EffectStore store = new EffectStore(65536, 16);
        CommitLog.OrderSource plans = plans(9L, 0);
        Holder holder = new Holder(FrozenDeclarations.none(), plans);
        CommitLog log = new CommitLog(() -> 4, plans, store, holder);
        CommitReservation reservations = new CommitReservation(log, store, plans, "owner-a",
            new CommitNamespaces.ResetEpochs());
        AtomicLong calls = new AtomicLong();
        CommitController controller = new CommitController(reservations, line -> {
            calls.incrementAndGet();
            return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
                List.of("a=1", "b=2"), 2, "");
        });
        holder.sealed = sealed(9L, NODE, 0, 64L, "full", 77L, EPOCH,
            reservations.controller().lifecycleKey());
        holder.current = reservations.controller();
        log.beginTick(1L);

        CommitController.SubmitResult refused = controller.submit(request(reservations, 999L));

        assertFalse(refused.admitted(), "a positive instance number of its own is not a proof");
        assertEquals("declaration-instance-mismatch", refused.diagnostic());
        assertEquals(0L, calls.get(), "no publish action is reached");
        assertEquals(0, log.ringDepth(), "the ring holds nothing");
        assertEquals(0, log.reservedSlots(), "no slot was handed out");
        assertEquals(0, store.capacitySlots(), "no room was taken");
        assertEquals(0, reservations.outstanding());
        assertEquals(0L, reservations.reserveSuccess());
        assertEquals(1L, reservations.witnessRefusals());
    }

    @Test
    void aLineWearingAnotherWorldGenerationThanItsFreezeIsRefusedBeforeAnyRoomIsTaken() {
        EffectStore store = new EffectStore(65536, 16);
        CommitLog.OrderSource plans = plans(9L, 0);
        Holder holder = new Holder(FrozenDeclarations.none(), plans);
        CommitLog log = new CommitLog(() -> 4, plans, store, holder);
        CommitReservation reservations = new CommitReservation(log, store, plans, "owner-a",
            new CommitNamespaces.ResetEpochs());
        AtomicLong calls = new AtomicLong();
        CommitController controller = new CommitController(reservations, line -> {
            calls.incrementAndGet();
            return new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
                List.of("a=1", "b=2"), 2, "");
        });
        holder.sealed = sealed(9L, NODE, 0, 64L, "full", INSTANCE, EPOCH,
            reservations.controller().lifecycleKey());
        holder.current = reservations.controller();
        log.beginTick(1L);

        CommitController.SubmitResult refused = controller.submit(request(reservations, INSTANCE, 666L));

        assertFalse(refused.admitted(), "a live generation of its own is not the frozen one");
        assertEquals("declaration-epoch-mismatch", refused.diagnostic());
        assertEquals(0L, calls.get());
        assertEquals(0, log.ringDepth());
        assertEquals(0, log.reservedSlots());
        assertEquals(0, store.capacitySlots());
        assertEquals(0, reservations.outstanding());
        assertEquals(0L, reservations.reserveSuccess());
        assertEquals(1L, reservations.witnessRefusals());
    }

    @Test
    void twoRunsWithTheirOwnMappingsStillCompareEqualUnderTheirClosures() {
        CommitLog.SchemaReplay left = typedSide(4L, 1L, 5L, 64L, "full");
        CommitLog.SchemaReplay right = typedSide(91L, 8848L, 21L, 64L, "full");
        ReplayComparator comparator = new ReplayComparator();

        ReplayComparator.Verdict verdict = comparator.compare(
            new ReplayComparator.Evidence("aaaaaaa", ReplayComparator.recordsDigest(left),
                sealed(4L, NODE, 0, 64L, "full", 1L, 5L), left),
            new ReplayComparator.Evidence("bbbbbbb", ReplayComparator.recordsDigest(right),
                sealed(91L, NODE, 0, 64L, "full", 8848L, 21L), right));

        assertTrue(verdict.equal(), "each run wore the mapping its own freeze authorized");
        assertEquals(0, verdict.differences());
        assertEquals(0L, comparator.refused());
    }

    @Test
    void aFreezeThatWasReplacedAuthorizesNothingAndStaysReadableAsEvidence() {
        FrozenDeclarations old = sealed(9L, NODE, 0, 64L, "full", INSTANCE, EPOCH);
        FrozenDeclarations.History history = new FrozenDeclarations.History(4);
        history.seal(old);
        assertEquals(9L, history.authorizing(9L).planSequence(), "the freeze it is in authorizes");

        history.retire();
        assertEquals(0, history.authorizing(9L).size(),
            "a reset hands the plan counter back, and the table of the lifecycle that ended is not an "
                + "authorization whatever number it carries");
        FrozenDeclarations.Sealed newer = FrozenDeclarations.seal(freeze(2L), 1L,
            List.of(candidate(WORLD, DOMAIN, NODE, 64L, "full", 2L, 6L)),
            List.of(new FrozenDeclarations.Assignment(WORLD, DOMAIN, NODE, 0)));
        history.seal(newer.declarations());

        assertEquals(0, history.authorizing(9L).size(), "the old plan number is still not this freeze");
        assertEquals(1L, history.authorizing(1L).planSequence(), "and the new freeze works");
        assertEquals(9L, history.snapshot(freeze(1L), 9L).planSequence(),
            "while the replaced freeze stays whole as read-only evidence");
        assertEquals(0L, history.evicted(), "nothing was given up");
    }

    @Test
    void aStrictClosureOfTheFreezeThatOutlivedItsResetIsStillFormable() {
        CommitLog.SchemaReplay old = typedSide(9L, 1L, 5L, 64L, "full");
        FrozenDeclarations table = sealed(9L, NODE, 0, 64L, "full", 1L, 5L);
        FrozenDeclarations.History history = new FrozenDeclarations.History(4);
        history.seal(table);
        history.retire();

        FrozenDeclarations evidence = history.snapshot(freeze(1L), 9L);
        String digest = ReplayComparator.recordsDigest(old);

        assertFalse(new ReplayComparator().compare(
            new ReplayComparator.Evidence("aaaaaaa", digest, history.authorizing(9L), old),
            new ReplayComparator.Evidence("aaaaaaa", digest, evidence, old)).equal(),
            "the freeze of this lifecycle backs no step of the old plan");
        assertTrue(new ReplayComparator().compare(
            new ReplayComparator.Evidence("aaaaaaa", digest, evidence, old),
            new ReplayComparator.Evidence("aaaaaaa", digest, evidence, old)).equal(),
            "and the ticket that finished under its own freeze still exports its closure");
    }

    @Test
    void theRetentionOfReplacedFreezesIsBoundedAndCountsWhatItGaveUp() {
        FrozenDeclarations.History history = new FrozenDeclarations.History(2);
        for (long plan = 1L; plan <= 4L; plan++) {
            history.seal(sealed(plan, NODE, 0, 64L, "full"));
        }

        assertEquals(2, history.retained(), "the retention is bounded");
        assertEquals(3L, history.retired());
        assertEquals(1L, history.evicted(), "an eviction is counted and never happens quietly");
        assertEquals(0, history.snapshot(freeze(1L), 1L).size(), "the oldest freeze was given up");
        assertNotNull(history.snapshot(freeze(1L), 3L).entryAt(seat(3L, 0)),
            "the newest ones are kept whole");
        assertEquals(4L, history.current().planSequence(), "and the current one is not a snapshot");
    }

    @Test
    void aLineTheRingsHaveNotReadKeepsItsFreezeAsWellAsATicketDoes() {
        EffectStore store = new EffectStore(1 << 16, 16);
        CommitLog.OrderSource plans = plans(9L, 0);
        HistoryHolder holder = new HistoryHolder(2, INSTANCE, EPOCH, GRANT, true)
            .lifecycle(controller().lifecycleKey());
        CommitLog log = new CommitLog(() -> 8, plans, store, holder);
        FrozenDeclarations sealed = holder.seal(9L, NODE, 0, 64L, "full");
        log.beginTick(1L);

        assertTrue(log.reachTyped(new CommitLog.TypedBatch(
            new CommitLog.Batch(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY, GRANT, 4242L, 2),
            identity(INSTANCE, EPOCH, 64L, "full", controller()),
            new EffectStore.Effect(EFFECT, "publish", "PUBLISHED", List.of("a=1", "b=2")))).logged());
        assertEquals(1, log.unreadObligations(), "the line is stored and not read yet");

        holder.seal(11L, NODE, 0, 64L, "full");
        holder.seal(12L, NODE, 0, 64L, "full");
        holder.seal(13L, NODE, 0, 64L, "full");

        assertSame(sealed, holder.history.snapshot(holder.freezeOf(1L), 9L),
            "a line the rings have not read is an obligation on its declarations too");
        assertEquals(1L, holder.history.evicted(), "the freezes nothing owes are still given up");
        assertEquals(1L, holder.history.withheld(), "and the one that is owed is counted where it was passed");

        log.closeTick();
        assertEquals(0, log.unreadObligations(), "reading the line settles its obligation");

        holder.seal(14L, NODE, 0, 64L, "full");
        assertEquals(0, holder.history.snapshot(holder.freezeOf(1L), 9L).size(),
            "with nothing owing it any more the freeze is evictable");
    }

    @Test
    void anItemWaitingForItsDispositionKeepsTheFreezeItWasJudgedUnder() {
        Chain chain = new Chain(2, true);
        CommitTicket ticket = chain.take();
        FrozenDeclarations sealed = chain.holder.history.current();
        assertTrue(chain.reservations.beginPublish(ticket));

        CommitReservation.FinalizeResult malformed = chain.reservations.finalize(ticket,
            new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "", List.of("a=1", "b=2"), 2, ""));
        assertFalse(malformed.finalized(), "a terminal that cannot be read is not a terminal");
        assertEquals("operation-undeclared", malformed.diagnostic());
        assertEquals(1, chain.reservations.pendingCount(), "the item waits for a disposition");

        chain.holder.seal(11L, NODE, 0, 64L, "full");
        chain.holder.seal(12L, NODE, 0, 64L, "full");
        chain.holder.seal(13L, NODE, 0, 64L, "full");

        assertSame(sealed, chain.holder.history.snapshot(chain.holder.freezeOf(1L), 9L),
            "an item waiting for the owner keeps the freeze its line was judged under");

        CommitReservation.FinalizeResult done = chain.reservations.finalize(ticket,
            new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish", List.of("a=1", "b=2"),
                2, ""));
        assertTrue(done.finalized(), done.diagnostic());
        chain.log.closeTick();
        assertEquals(1, chain.log.lastTyped().typedSteps().size(), "the disposition wrote the line");

        chain.holder.seal(14L, NODE, 0, 64L, "full");
        assertEquals(0, chain.holder.history.snapshot(chain.holder.freezeOf(1L), 9L).size(),
            "and the obligation ends with the disposition");
    }

    @Test
    void aTableOfTheFreezeInForceAuthorizesAndASnapshotOnlyAnswersEvidence() {
        FrozenDeclarations.History history = new FrozenDeclarations.History(4);
        FrozenDeclarations replaced = sealed(9L, NODE, 0, 64L, "full");
        FrozenDeclarations inForce = sealed(9L, NODE, 0, 64L, "full", INSTANCE, EPOCH, "owner-a/5151/0");
        history.seal(replaced);
        history.retire();
        history.seal(inForce);

        assertSame(inForce, history.authorizing(9L), "the freeze in force is the one that authorizes");
        assertSame(replaced, history.snapshot(freeze(1L), 9L),
            "and the replaced one stays readable as evidence");
        history.retire();
        assertEquals(0, history.authorizing(9L).size(), "a replaced freeze authorizes nothing");
        assertSame(replaced, history.snapshot(freeze(1L), 9L),
            "while the evidence interface still answers with the table itself");
    }

    @Test
    void anInstanceOutsideThePositiveDomainDeclaresNothingAtEveryEntryPoint() {
        long[] outside = {0L, -1L, Long.MIN_VALUE};
        for (long instance : outside) {
            FrozenDeclarations.Sealed sealed = FrozenDeclarations.seal(freeze(1L), 9L,
                List.of(candidate(WORLD, DOMAIN, NODE, 64L, "full", instance, EPOCH)),
                List.of(new FrozenDeclarations.Assignment(WORLD, DOMAIN, NODE, 0)));
            assertTrue(sealed.sealed(), sealed.refusal());
            assertEquals(FrozenDeclarations.NOT_WIRED, sealed.declarations().state(),
                "a seal takes no entry at instance " + instance);
            assertEquals(0, sealed.declarations().size());
            assertEquals("declaration-binding-missing", FrozenDeclarations.of(freeze(1L), 9L,
                List.of(new FrozenDeclarations.Entry(seat(9L, 0), NODE, 1, 64L, "full", instance, EPOCH)))
                .refusal(), "and the rebuild refuses instance " + instance + " by name");
            assertFalse(new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instance, EPOCH, 64L, "full",
                GRANT, EFFECT, new CommitNamespaces.Namespace("producer", 5150L, 0L, 77L),
                controller()).declaredTyped(), "a live line of instance " + instance + " declares nothing");
        }
        assertTrue(CommitLog.liveInstance(1L), "the positive domain is where a minted number lives");
        assertFalse(CommitLog.liveInstance(0L));
        assertFalse(CommitLog.liveInstance(-1L));

        FrozenDeclarations legal = sealed(9L, NODE, 0, -1099511627776L, "full", 8848L, EPOCH);
        assertTrue(verify(legal, 9L, 0, NODE,
            identity(8848L, EPOCH, -1099511627776L, "full", controller()), controller()).accepted(),
            "a legal positive instance and a negative packed chunk are both legal, and the domain of "
                + "one is not read into the other");
    }

    @Test
    void aLiveLineOfAnotherInstanceDomainTakesNoRingSlotAndNoRoom() {
        EffectStore store = new EffectStore(65536, 16);
        CommitLog.OrderSource plans = plans(9L, 0);
        HistoryHolder holder = new HistoryHolder(2, INSTANCE, EPOCH, GRANT, true)
            .lifecycle(controller().lifecycleKey());
        CommitLog log = new CommitLog(() -> 4, plans, store, holder);
        CommitReservation reservations = new CommitReservation(log, store, plans, "owner-a",
            new CommitNamespaces.ResetEpochs());
        holder.seal(9L, NODE, 0, 64L, "full");

        for (long instance : new long[] {0L, -1L}) {
            CommitReservation.ReserveResult refused = reservations.reserve(
                new CommitTicket.ReserveRequest(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY, 9L, 0,
                    GRANT, 4242L, 2, identity(instance, EPOCH, 64L, "full",
                        new CommitNamespaces.Namespace("owner-a", reservations.controller().lifetime(),
                            0L, CommitNamespaces.NO_THREAD)),
                    new CommitNamespaces.Namespace("owner-a", reservations.controller().lifetime(), 0L,
                        CommitNamespaces.NO_THREAD), variant(), variant(), variant()));
            assertFalse(refused.admitted(), "instance " + instance + " is outside the domain");
            assertEquals("identity-undeclared", refused.diagnostic());
        }
        assertEquals(0, log.ringDepth(), "no line was stored");
        assertEquals(0, log.reservedSlots(), "no slot was handed out");
        assertEquals(0, store.capacitySlots(), "no room was taken");
        assertEquals(0, reservations.outstanding());
    }

    @Test
    void aStrictReplayRefusesARecordWhoseInstanceIsOutsideThePositiveDomain() {
        CommitLog.SchemaReplay legal = typedSide();
        CommitLog.TypedStep step = legal.typedSteps().get(0);

        for (long instance : new long[] {0L, -1L}) {
            CommitLog.Identity outside = new CommitLog.Identity(step.identity().schemaVersion(), instance,
                step.identity().worldEpoch(), step.identity().chunkPos(), step.identity().targetStatus(),
                step.identity().authorizationGeneration(), step.identity().effectRef(),
                step.identity().producer(), step.identity().controller());
            CommitLog.TypedStep bad = new CommitLog.TypedStep(step.planSequence(), step.position(),
                step.writeSetDigest(), step.worldId(), step.domainId(), step.nodeKey(),
                step.expectedVersion(), step.rows(), outside, step.disposition(), step.effect());
            CommitLog.SchemaReplay side = new CommitLog.SchemaReplay(legal.schemaVersion(),
                legal.plainSteps(), List.of(bad), legal.effectMissing());

            assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY,
                new ReplayComparator().compare(side, side).reason(),
                "a record of instance " + instance + " is no identity a strict reading concludes on");
        }
        assertTrue(new ReplayComparator().compare(legal, legal).equal(),
            "while the run whose own freeze authorized its number is still answered");
    }

    @Test
    void aRebuildOfATableWithAMissingFieldIsRefusedByNameInsteadOfThrowing() {
        FrozenDeclarations.Seat place = seat(9L, 0);
        FrozenDeclarations.Entry good = new FrozenDeclarations.Entry(place, NODE, 1, 64L, "full",
            INSTANCE, EPOCH);

        assertEquals("declaration-field-missing", FrozenDeclarations.of(freeze(1L), 9L, List.of(
            new FrozenDeclarations.Entry(place, NODE, 1, 64L, null, INSTANCE, EPOCH))).refusal(),
            "a table that carries no state is refused by name and not by a fault of the reader");
        assertEquals("declaration-field-missing", FrozenDeclarations.of(freeze(1L), 9L, List.of(
            new FrozenDeclarations.Entry(place, null, 1, 64L, "full", INSTANCE, EPOCH))).refusal());
        assertEquals("declaration-field-missing", FrozenDeclarations.of(freeze(1L), 9L, List.of(
            new FrozenDeclarations.Entry(new FrozenDeclarations.Seat(null, null, 9L, 0), NODE, 1, 64L,
                "full", INSTANCE, EPOCH))).refusal());
        assertTrue(FrozenDeclarations.of(freeze(1L), 9L, List.of(good)).sealed(),
            "the same table with its state is rebuilt, which is the contrast this case turns on");
        assertTrue(FrozenDeclarations.of(freeze(1L), 9L, List.of(new FrozenDeclarations.Entry(place, NODE,
            2, 64L, "full", INSTANCE, EPOCH))).sealed(),
            "and a rank with a gap in front of it is a table a seal can write");
    }

    private static EffectStore.Effect variant() {
        return new EffectStore.Effect(EFFECT, "publish", "PUBLISHED", List.of("a=1", "b=2"));
    }

    @Test
    void theFreezeATicketStillOwesIsNotEvictedAndItsClosureStillForms() {
        Chain chain = new Chain(2, true);
        CommitTicket ticket = chain.take();
        FrozenDeclarations.FreezeKey original = chain.holder.freezeOf(1L);
        FrozenDeclarations sealed = chain.holder.history.current();

        chain.holder.seal(11L, NODE, 0, 64L, "full");
        chain.holder.seal(12L, NODE, 0, 64L, "full");
        chain.holder.seal(13L, NODE, 0, 64L, "full");

        assertEquals(1, chain.reservations.outstanding(), "the ticket of plan 9 is still in flight");
        assertEquals(1L, chain.holder.history.evicted(), "the bound gave one freeze up and counted it");
        assertSame(sealed, chain.holder.history.snapshot(original, 9L),
            "and the one it gave up is not the freeze the ticket still names");

        chain.write(ticket);
        chain.holder.seal(14L, NODE, 0, 64L, "full");
        assertSame(sealed, chain.holder.history.snapshot(original, 9L),
            "the terminal line is stored and not read, so the freeze it names is still owed");
        assertEquals(2L, chain.holder.history.evicted(),
            "and the bound gives up what nothing owes while it passes that one");

        chain.log.closeTick();
        CommitLog.SchemaReplay side = chain.log.lastTyped();
        assertEquals(1, side.typedSteps().size(), "the terminal line of the ticket was written");
        String digest = ReplayComparator.recordsDigest(side);
        FrozenDeclarations exported = chain.holder.history.snapshot(original, 9L);
        ReplayComparator.Verdict verdict = new ReplayComparator().compare(
            new ReplayComparator.Evidence("aaaaaaa", digest, exported, side),
            new ReplayComparator.Evidence("aaaaaaa", digest, exported, side));
        assertTrue(verdict.equal(), "the lawful terminal of a ticket that outlived its freeze still "
            + "forms the closure of that freeze: " + verdict.decision() + "/" + verdict.reason());

        chain.holder.seal(15L, NODE, 0, 64L, "full");
        assertEquals(3L, chain.holder.history.evicted(),
            "with the obligation settled the freeze may be given up");
        assertEquals(0, chain.holder.history.snapshot(original, 9L).size());
        assertEquals(0, chain.reservations.outstanding(), "and no reservation is left behind");
    }

    @Test
    void aHolderThatKeepsNoObligationGivesTheOriginalFreezeUpAndRefusesTheClosure() {
        Chain chain = new Chain(2, false);
        CommitTicket ticket = chain.take();
        FrozenDeclarations.FreezeKey original = chain.holder.freezeOf(1L);

        chain.holder.seal(11L, NODE, 0, 64L, "full");
        chain.holder.seal(12L, NODE, 0, 64L, "full");
        chain.holder.seal(13L, NODE, 0, 64L, "full");

        assertEquals(1, chain.reservations.outstanding(), "the ticket of plan 9 is still in flight");
        assertEquals(1L, chain.holder.history.evicted(), "the retention gave one freeze up");
        assertEquals(FrozenDeclarations.NOT_WIRED, chain.holder.history.snapshot(original, 9L).state(),
            "and the freeze the ticket still names is the one that is gone");
        assertEquals(0, chain.holder.history.snapshot(original, 9L).size());

        CommitLog.SchemaReplay side = chain.record(ticket);
        assertEquals(1, side.typedSteps().size(), "the terminal line was written all the same");
        String digest = ReplayComparator.recordsDigest(side);
        FrozenDeclarations gone = chain.holder.history.snapshot(original, 9L);
        ReplayComparator.Verdict verdict = new ReplayComparator().compare(
            new ReplayComparator.Evidence("aaaaaaa", digest, gone, side),
            new ReplayComparator.Evidence("aaaaaaa", digest, gone, side));
        assertEquals(ReplayComparator.Decision.REFUSED, verdict.decision(),
            "an export that lost the freeze of a lawful terminal cannot conclude anything");
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, verdict.reason(),
            "and the reason it gives is that the closure itself is illegal");
    }

    @Test
    void theFreezeIsChosenByItsWholeKeyAndNotByThePlanNumberItReused() {
        FrozenDeclarations.History history = new FrozenDeclarations.History(4);
        FrozenDeclarations first = sealed(9L, NODE, 0, 64L, "full", INSTANCE, EPOCH, "owner-a/5150/0");
        FrozenDeclarations second = sealed(9L, NODE, 0, 64L, "full", INSTANCE, EPOCH, "owner-a/5151/0");
        history.seal(first);
        history.retire();
        history.seal(second);
        history.retire();

        assertSame(first, history.snapshot(freeze(1L, "owner-a/5150/0"), 9L),
            "the whole key names the freeze the records were ordered by");
        assertSame(second, history.snapshot(freeze(1L, "owner-a/5151/0"), 9L),
            "and the other lifecycle of the same plan number is another freeze");
        assertSame(second, history.newestSnapshot(9L),
            "the plan number alone answers with the newest freeze that reused it");
        assertEquals(0, history.snapshot(freeze(2L, "owner-a/5150/0"), 9L).size(),
            "a key of another generation of the same lifecycle cannot take the place of the original");
        assertEquals(0, history.snapshot(freeze(1L, "owner-a/5152/0"), 9L).size(),
            "and neither can a lifecycle the freeze was never taken under");
    }


    @Test
    void aSplicedWorldAndDomainCannotConnectADeclarationToAnotherSeat() {
        FrozenDeclarations.Sealed spliced = FrozenDeclarations.seal(freeze(1L), 9L,
            List.of(candidate("a/b", "c", "n", 64L, "full", INSTANCE, EPOCH)),
            List.of(new FrozenDeclarations.Assignment("a", "b/c", "n", 0)));

        assertTrue(spliced.sealed());
        assertEquals(FrozenDeclarations.NOT_WIRED, spliced.declarations().state(),
            "an unescaped splice would have read these two pairs as the same node");
        assertEquals(0, spliced.declarations().size());
        assertEquals("declaration-missing", IdentityWitness.verify(spliced.declarations(),
            new CommitLog.Resolved(9L, 0, false), "a", "b/c", "n",
            identity(INSTANCE, EPOCH, 64L, "full", controller()), controller()).reason(),
            "and no line is judged at a seat nothing was sealed for");
    }

    @Test
    void twoDeclarationsOfSplicedNamesKeepTheirOwnSeatsAndTheirOwnChunks() {
        FrozenDeclarations.Sealed result = FrozenDeclarations.seal(freeze(1L), 9L,
            List.of(candidate("a/b", "c", "n", 64L, "full", INSTANCE, EPOCH),
                candidate("a", "b/c", "n", 65L, "full", INSTANCE, EPOCH)),
            List.of(new FrozenDeclarations.Assignment("a/b", "c", "n", 0),
                new FrozenDeclarations.Assignment("a", "b/c", "n", 1)));

        assertTrue(result.sealed(), result.refusal());
        FrozenDeclarations table = result.declarations();
        assertEquals(64L, table.entryAt(new FrozenDeclarations.Seat("a/b", "c", 9L, 0)).packedChunkPos());
        assertEquals(65L, table.entryAt(new FrozenDeclarations.Seat("a", "b/c", 9L, 1)).packedChunkPos());
        assertTrue(IdentityWitness.verify(table, new CommitLog.Resolved(9L, 1, false), "a", "b/c", "n",
            identity(INSTANCE, EPOCH, 65L, "full", controller()), controller()).accepted(),
            "each seat is read with the declaration of its own pair");
        assertEquals("chunk-witness-mismatch", IdentityWitness.verify(table,
            new CommitLog.Resolved(9L, 1, false), "a", "b/c", "n",
            identity(INSTANCE, EPOCH, 64L, "full", controller()), controller()).reason(),
            "and the chunk of the other pair is not this one's");
    }

    @Test
    void aRecordWhoseRowsWereReplacedUnderTheDigestItKeptIsRefused() {
        CommitLog.SchemaReplay honest = typedSide();
        FrozenDeclarations table = sealed(1L, NODE, 0, 64L, "full");
        String kept = ReplayComparator.recordsDigest(honest);
        ReplayComparator comparator = new ReplayComparator();
        CommitLog.TypedStep step = honest.typedSteps().get(0);
        List<String> rows = List.of("x=1", "y=2");
        List<String> names = List.of("x", "y");
        CommitLog.SchemaReplay replaced = withRecord(honest, step.effect().declared(), rows,
            step.effect().contentDigest());

        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, comparator.compare(
            new ReplayComparator.Evidence("aaaaaaa", kept, table, replaced),
            new ReplayComparator.Evidence("aaaaaaa", kept, table, honest)).reason(),
            "the summary the caller kept is not the summary these rows produce");
        assertNotEquals(kept, ReplayComparator.recordsDigest(replaced),
            "the summary covers the rows that are really there");
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, comparator.compare(
            new ReplayComparator.Evidence("aaaaaaa", ReplayComparator.recordsDigest(replaced), table,
                replaced),
            new ReplayComparator.Evidence("aaaaaaa", ReplayComparator.recordsDigest(replaced), table,
                replaced)).reason(),
            "a record whose rows and digest disagree is no closure either");
        CommitLog.SchemaReplay restale = withRecord(honest, names, rows,
            step.effect().contentDigest());
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, comparator.compare(
            new ReplayComparator.Evidence("aaaaaaa", ReplayComparator.recordsDigest(restale), table,
                restale),
            new ReplayComparator.Evidence("aaaaaaa", ReplayComparator.recordsDigest(restale), table,
                restale)).reason(),
            "a record whose rows were replaced under the digest that came with it is refused even "
                + "when the names it declares were replaced with them");
        CommitLog.SchemaReplay relabelled = withRecord(honest, step.effect().declared(), rows,
            EffectStore.contentDigest(rows));
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, comparator.compare(
            new ReplayComparator.Evidence("aaaaaaa", ReplayComparator.recordsDigest(relabelled), table,
                relabelled),
            new ReplayComparator.Evidence("aaaaaaa", ReplayComparator.recordsDigest(relabelled), table,
                relabelled)).reason(),
            "and neither is one whose rows no longer declare the names it carries");
    }

    @Test
    void theRebuildEntryPointRefusesTablesNoSealCouldHaveWritten() {
        FrozenDeclarations.Seat place = seat(9L, 0);
        FrozenDeclarations.Entry good = new FrozenDeclarations.Entry(place, NODE, 1, 64L, "full",
            INSTANCE, EPOCH);
        FrozenDeclarations.Sealed rebuilt = FrozenDeclarations.of(freeze(1L), 9L, List.of(good));

        assertTrue(rebuilt.sealed(), "a table a seal could have written is rebuilt");
        assertTrue(IdentityWitness.verify(rebuilt.declarations(), new CommitLog.Resolved(9L, 0, false),
            WORLD, DOMAIN, NODE, identity(INSTANCE, EPOCH, 64L, "full", controller()), controller())
            .accepted(), "and its normal rebuild backs a line");
        assertEquals("declaration-seat-duplicate", FrozenDeclarations.of(freeze(1L), 9L, List.of(good,
            new FrozenDeclarations.Entry(place, OTHER_NODE, 2, 65L, "full", INSTANCE, EPOCH))).refusal(),
            "two entries at one seat are a table no seal wrote");
        assertEquals("declaration-duplicate", FrozenDeclarations.of(freeze(1L), 9L,
            List.of(good, good)).refusal(), "and so is one node declared twice");
        assertEquals("declaration-role-illegal", FrozenDeclarations.of(freeze(1L), 9L, List.of(
            new FrozenDeclarations.Entry(place, NODE, -7, 64L, "full", INSTANCE, EPOCH))).refusal(),
            "a rank a seal never hands out is refused");
        assertEquals("declaration-plan-mismatch", FrozenDeclarations.of(freeze(1L), 10L, List.of(good))
            .refusal(), "an entry of another plan is not this table's");
        assertEquals("declaration-field-missing", FrozenDeclarations.of(freeze(1L), 9L, List.of(
            new FrozenDeclarations.Entry(place, NODE, 1, 64L, "", INSTANCE, EPOCH))).refusal());
        assertEquals("declaration-binding-missing", FrozenDeclarations.of(freeze(1L), 9L, List.of(
            new FrozenDeclarations.Entry(place, NODE, 1, 64L, "full", 0L, EPOCH))).refusal(),
            "an entry with no mapping this side authorized is not a seal's entry");
        assertEquals("declaration-freeze-missing", FrozenDeclarations.of(FrozenDeclarations.FreezeKey.NONE,
            9L, List.of(good)).refusal(), "and the rebuild is not a weaker source than the seal");
    }

    @Test
    void theClosureTiesTheRecordsToTheirTableAndTheCommitLabelIsOnlyALabel() {
        CommitLog.SchemaReplay side = typedSide();
        FrozenDeclarations table = sealed(1L, NODE, 0, 64L, "full");
        String digest = ReplayComparator.recordsDigest(side);
        ReplayComparator comparator = new ReplayComparator();

        assertTrue(comparator.compare(
            new ReplayComparator.Evidence("aaaaaaa", digest, table, side),
            new ReplayComparator.Evidence("bbbbbbb", digest, table, side)).equal(),
            "the label is carried and two runs of another commit still compare equal");
        ReplayComparator.Verdict wrongTable = comparator.compare(
            new ReplayComparator.Evidence("aaaaaaa", digest, sealed(2L, NODE, 0, 64L, "full"), side),
            new ReplayComparator.Evidence("aaaaaaa", digest, table, side));
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, wrongTable.reason(),
            "but the table has to be the freeze these records were ordered by");
        assertEquals("closure", wrongTable.note(),
            "and the receipt refuses that side itself, before any projection of it is read");
        assertEquals(ReplayComparator.REFUSED_ILLEGAL_IDENTITY, comparator.compare(
            new ReplayComparator.Evidence("aaaaaaa", digest,
                FrozenDeclarations.of(freeze(1L), 1L, List.of()).declarations(), side),
            new ReplayComparator.Evidence("aaaaaaa", digest, table, side)).reason(),
            "and a table that sealed nothing is no closure");
        assertTrue(ReplayComparator.EVIDENCE_TIER.contains("not a release gate"),
            "what a strict reading proves is named, and the label is not a proof of provenance");
    }

    @Test
    void theRoleIsTheRankOfTheDeclaredOrderAndAnExtraNodeMovesIt() {
        List<FrozenDeclarations.Assignment> two = List.of(
            new FrozenDeclarations.Assignment(WORLD, DOMAIN, NODE, 0),
            new FrozenDeclarations.Assignment(WORLD, DOMAIN, OTHER_NODE, 1));
        FrozenDeclarations.Sealed ordered = FrozenDeclarations.seal(freeze(1L), 9L,
            List.of(candidate(NODE, 64L, "full")), two);
        FrozenDeclarations.Sealed reversed = FrozenDeclarations.seal(freeze(1L), 9L,
            List.of(candidate(NODE, 64L, "full")), List.of(
                new FrozenDeclarations.Assignment(WORLD, DOMAIN, OTHER_NODE, 0),
                new FrozenDeclarations.Assignment(WORLD, DOMAIN, NODE, 1)));
        FrozenDeclarations.Sealed extra = FrozenDeclarations.seal(freeze(1L), 9L,
            List.of(candidate(NODE, 64L, "full")), List.of(
                new FrozenDeclarations.Assignment(WORLD, DOMAIN, "a", 0),
                new FrozenDeclarations.Assignment(WORLD, DOMAIN, NODE, 1),
                new FrozenDeclarations.Assignment(WORLD, DOMAIN, OTHER_NODE, 2)));

        assertEquals(1, ordered.declarations().entryAt(seat(9L, 0)).role(),
            "the rank comes from the declared order and not from the seat");
        assertEquals(1, reversed.declarations().entryAt(seat(9L, 1)).role(),
            "the graph took another seat and the rank stayed where the declaration put it");
        assertEquals(2, extra.declarations().entryAt(seat(9L, 1)).role(),
            "a node the plan carried without an artefact moves the rank of the ones after it, so the "
                + "rank is read against the declared set and is not an absolute of the node");
    }

    /** A holder that answers from a real bounded history the way the module does: one freeze is
     * sealed into the history at a time, and a reader that names a freeze and a plan reads back the
     * table the history still holds for it. */
    private static final class HistoryHolder implements IdentityWitness.Source {

        private final FrozenDeclarations.History history;
        private final long instance;
        private final long epoch;
        private final long authorization;
        private final boolean obliged;
        private String lifecycleKey = "";
        private long generation;

        HistoryHolder(int cap, long instance, long epoch, long authorization, boolean obliged) {
            this.history = new FrozenDeclarations.History(cap);
            this.instance = instance;
            this.epoch = epoch;
            this.authorization = authorization;
            this.obliged = obliged;
        }

        @Override
        public IdentityWitness.Obligation oblige(FrozenDeclarations declarations) {
            if (!obliged) {
                // A holder that keeps no obligation: the negative case of the counterexample.
                return IdentityWitness.Obligation.NONE;
            }
            return history.oblige(declarations);
        }

        HistoryHolder lifecycle(String lifecycleKey) {
            this.lifecycleKey = lifecycleKey;
            return this;
        }

        /** Seals the declaration of one freeze into the history, as the freeze of the module does. */
        FrozenDeclarations seal(long plan, String node, int position, long packedChunk, String status) {
            FrozenDeclarations.Sealed sealed = FrozenDeclarations.seal(
                new FrozenDeclarations.FreezeKey(lifecycleKey, ++generation), plan,
                List.of(candidate(WORLD, DOMAIN, node, packedChunk, status, instance, epoch)),
                List.of(new FrozenDeclarations.Assignment(WORLD, DOMAIN, node, position)));
            assertTrue(sealed.sealed(), sealed.refusal());
            history.seal(sealed.declarations());
            return sealed.declarations();
        }

        FrozenDeclarations.FreezeKey freezeOf(long generation) {
            return new FrozenDeclarations.FreezeKey(lifecycleKey, generation);
        }

        @Override
        public FrozenDeclarations declarations(String worldId, String domainId, long planSequence) {
            FrozenDeclarations held = history.current();
            if (!held.freeze().lifecycleKey().equals(lifecycleKey)) {
                return FrozenDeclarations.none();
            }
            return held.planSequence() == planSequence ? held : FrozenDeclarations.none();
        }

        @Override
        public long liveWorldEpoch(String worldId) {
            return epoch;
        }

        @Override
        public IdentityWitness.Authorization liveAuthorization(long planSequence,
                CommitNamespaces.Namespace planController, String worldId, String domainId,
                String nodeKey) {
            if (planController == null || (!lifecycleKey.isEmpty()
                && !lifecycleKey.equals(planController.lifecycleKey()))) {
                return IdentityWitness.Authorization.refused("", 0L,
                    IdentityWitness.Authorization.Face.notRead(), "not-this-double");
            }
            return IdentityWitness.Authorization.answered(planController.lifecycleKey(), planSequence,
                authorization, IdentityWitness.Authorization.Face.present(authorization));
        }

        @Override
        public CommitNamespaces.Namespace currentController() {
            return null;
        }
    }

    /** The chain of the counterexample: one lawful reservation taken under one freeze, the freezes
     * that replace it, the terminal line of that ticket and the closure an export builds from it. */
    private static final class Chain {

        final CommitNamespaces.ResetEpochs epochs = new CommitNamespaces.ResetEpochs();
        final EffectStore store = new EffectStore(1 << 16, 16);
        final HistoryHolder holder;
        final CommitLog log;
        final CommitReservation reservations;

        Chain(int cap, boolean obliged) {
            holder = new HistoryHolder(cap, INSTANCE, EPOCH, GRANT, obliged);
            log = new CommitLog(() -> 8, plans(9L, 0), store, holder);
            reservations = new CommitReservation(log, store, plans(9L, 0), "owner-a", epochs);
            holder.lifecycle(reservations.controller().lifecycleKey());
        }

        /** The freeze of plan 9 is sealed first, and it is generation 1 of this holder: the ticket
         * is taken under it before any freeze replaces it. */
        CommitTicket take() {
            holder.seal(9L, NODE, 0, 64L, "full");
            CommitReservation.ReserveResult taken = reservations.reserve(request(reservations, INSTANCE));
            assertTrue(taken.admitted(), taken.diagnostic());
            return taken.ticket();
        }

        /** Runs the side effect and writes the terminal line, as the controller does. The line is
         * stored and not read: reading it is what the next close of a tick does. */
        void write(CommitTicket ticket) {
            assertTrue(reservations.beginPublish(ticket), "the line was never started");
            CommitReservation.FinalizeResult done = reservations.finalize(ticket,
                new CommitTicket.Outcome(CommitTicket.Terminal.PUBLISHED, "publish",
                    List.of("a=1", "b=2"), 2, ""));
            assertTrue(done.finalized(), done.diagnostic());
        }

        CommitLog.SchemaReplay record(CommitTicket ticket) {
            write(ticket);
            log.closeTick();
            return log.lastTyped();
        }
    }

    private static CommitTicket.ReserveRequest request(CommitReservation reservations, long instance) {
        return request(reservations, instance, EPOCH);
    }

    private static CommitTicket.ReserveRequest request(CommitReservation reservations, long instance,
                                                        long epoch) {
        CommitNamespaces.Namespace held = reservations.controller();
        CommitLog.Identity identity = identity(instance, epoch, 64L, "full", held);
        EffectStore.Effect variant = new EffectStore.Effect(EFFECT, "publish", "PUBLISHED",
            List.of("a=1", "b=2"));
        return new CommitTicket.ReserveRequest(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY, 9L, 0,
            GRANT, 4242L, 2, identity, held, variant, variant, variant);
    }

    /** One run of the versioned path on the real storage chain, sealed by the table of its freeze:
     * the plan it was ordered by and the instance and generation its own freeze authorized. */
    private static CommitLog.SchemaReplay typedSide(long plan, long instance, long epoch, long chunk,
                                                    String status) {
        CommitLog.OrderSource plans = plans(plan, 0);
        CommitLog log = new CommitLog(() -> 8, plans, new EffectStore(1 << 16, 16),
            new WitnessSource(plans, WORLD, DOMAIN, chunk, status, epoch, GRANT, NODE).bind(instance));
        log.beginTick(1L);
        CommitLog.Identity identity = identity(instance, epoch, chunk, status, controller());
        assertTrue(log.reachTyped(new CommitLog.TypedBatch(
            new CommitLog.Batch(WORLD, DOMAIN, NODE, CommitLog.Batch.Kind.APPLY, GRANT, 4242L, 2),
            identity, new EffectStore.Effect(EFFECT, "publish", "PUBLISHED", List.of("a=1", "b=2"))))
            .logged());
        log.closeTick();
        return log.lastTyped();
    }

    private static CommitLog.SchemaReplay typedSide() {
        return typedSide(1L, INSTANCE, EPOCH, 64L, "full");
    }

    /** The same run with the stored record of every step replaced by other rows, the names it
     * declares and the digest it carries: the counterexample this case is about. */
    private static CommitLog.SchemaReplay withRecord(CommitLog.SchemaReplay side, List<String> declared,
                                                     List<String> content, String contentDigest) {
        List<CommitLog.TypedStep> steps = new ArrayList<>();
        for (CommitLog.TypedStep step : side.typedSteps()) {
            EffectStore.Recorded held = step.effect();
            EffectStore.Recorded replaced = new EffectStore.Recorded(held.effectRef(),
                held.operationKind(), held.outcome(), declared, content, contentDigest, held.bytes(),
                held.sequence());
            steps.add(new CommitLog.TypedStep(step.planSequence(), step.position(), step.writeSetDigest(),
                step.worldId(), step.domainId(), step.nodeKey(), step.expectedVersion(), content.size(),
                step.identity(), step.disposition(), replaced));
        }
        return new CommitLog.SchemaReplay(side.schemaVersion(), side.plainSteps(), steps,
            side.effectMissing());
    }
}
