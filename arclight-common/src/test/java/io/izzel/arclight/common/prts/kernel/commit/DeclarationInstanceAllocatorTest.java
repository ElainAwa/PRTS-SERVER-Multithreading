/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The one source of instance numbers a freeze seals: what it mints, what it refuses to mint, and
 * the two things a number is not - a column the caller may write and an identity that crosses runs.
 * What must be refused sits beside what must be admitted, because a source that numbered everything,
 * or refused everything, would pass one half of these cases and prove nothing.
 *
 * <p>The composition under test is the one the freeze point performs: the plan's own commit order
 * numbers the declarations that carry an artefact, and the seal writes the numbered declarations
 * against the same places. */
class DeclarationInstanceAllocatorTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "chunk-pipeline";
    private static final String NODE = "chunk-pipeline/world-a/mailbox-1";
    private static final String OTHER_NODE = "chunk-pipeline/world-a/mailbox-2";
    private static final String LIFECYCLE = "owner-a/5150/0";
    private static final String PRODUCER = "producer";
    private static final String STATUS = "full";
    private static final long EPOCH = 5L;
    private static final long GRANT = 7L;
    private static final long EFFECT = 100L;
    private static final long CHUNK = 64L;
    private static final long OTHER_CHUNK = 65L;

    /** The packed position of the chunk at (-1, 0): a packed chunk is signed, and a negative one is a
     * legal position rather than the absence of one. */
    private static final long NEGATIVE_CHUNK = -4294967296L;

    /** The two ways a world generation fails the check: a world the tracked set never held, and one
     * it has lost. */
    private static final long UNTRACKED = 0L;
    private static final long UNLIVE = -1L;

    /** The number a caller writes into the instance column itself: no source ever minted it. */
    private static final long SELF_REPORTED = 424242L;

    private static FrozenDeclarations.Candidate declaration(String node, long packedChunk, String status,
                                                            long instance, long epoch) {
        return new FrozenDeclarations.Candidate(WORLD, DOMAIN, node,
            new FrozenDeclarations.DeclaredChunk(packedChunk), status, instance, epoch);
    }

    /** A declaration of the shape the one production producer hands in: no chunk and no state. */
    private static FrozenDeclarations.Candidate countOnly(String node) {
        return new FrozenDeclarations.Candidate(WORLD, DOMAIN, node, null, "");
    }

    private static FrozenDeclarations.Assignment place(String node, int position) {
        return new FrozenDeclarations.Assignment(WORLD, DOMAIN, node, position);
    }

    private static FrozenDeclarations.Seat seat(long plan, int position) {
        return new FrozenDeclarations.Seat(WORLD, DOMAIN, plan, position);
    }

    /** The order the plan commits its nodes in, which is the order the freeze numbers by. */
    private static List<FrozenDeclarations.Assignment> commitOrder(String... nodes) {
        List<FrozenDeclarations.Assignment> places = new ArrayList<>();
        for (int position = 0; position < nodes.length; position++) {
            places.add(place(nodes[position], position));
        }
        return places;
    }

    /** The freeze point's own composition: one source in front of the seal, the places shared. */
    private static FrozenDeclarations freeze(DeclarationInstanceAllocator source,
                                             List<FrozenDeclarations.Candidate> handedIn,
                                             List<FrozenDeclarations.Assignment> places, long plan,
                                             long generation) {
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.seal(
            new FrozenDeclarations.FreezeKey(LIFECYCLE, generation), plan,
            source.mint(handedIn, places), places);
        assertTrue(sealed.sealed(), sealed.refusal());
        return sealed.declarations();
    }

    /** The same seal with no source in front of it: the freeze point of a rolled back tree. */
    private static FrozenDeclarations freezeUnwired(List<FrozenDeclarations.Candidate> handedIn,
                                                    List<FrozenDeclarations.Assignment> places,
                                                    long plan) {
        FrozenDeclarations.Sealed sealed = FrozenDeclarations.seal(
            new FrozenDeclarations.FreezeKey(LIFECYCLE, 1L), plan, handedIn, places);
        assertTrue(sealed.sealed(), sealed.refusal());
        return sealed.declarations();
    }

    private static CommitLog.Identity worn(long instance, long packedChunk, String status) {
        return new CommitLog.Identity(CommitLog.SCHEMA_TYPED, instance, EPOCH, packedChunk, status,
            GRANT, EFFECT, new CommitNamespaces.Namespace(PRODUCER, 5150L, 0L, 77L), controller());
    }

    private static CommitNamespaces.Namespace controller() {
        return new CommitNamespaces.Namespace("owner-a", 5150L, 0L, CommitNamespaces.NO_THREAD);
    }

    private static IdentityWitness.Verdict verify(FrozenDeclarations table, long plan, int position,
                                                  String node, CommitLog.Identity identity) {
        return IdentityWitness.verify(table, new CommitLog.Resolved(plan, position, false), WORLD,
            DOMAIN, node, identity, controller());
    }

    private static Set<Long> numbersOf(List<FrozenDeclarations.Candidate> declarations) {
        Set<Long> numbers = new LinkedHashSet<>();
        for (FrozenDeclarations.Candidate candidate : declarations) {
            numbers.add(candidate.instanceId());
        }
        return numbers;
    }

    private static String key(String node, int role, long packedChunk, String status) {
        return new IdentityWitness.LogicalKey(WORLD, DOMAIN, node, role, packedChunk, status)
            .canonical();
    }

    @Test
    void aDeclarationThatReportsItsOwnNumberGetsTheOneTheFreezeMinted() {
        List<FrozenDeclarations.Candidate> handedIn =
            List.of(declaration(NODE, CHUNK, STATUS, SELF_REPORTED, EPOCH));
        FrozenDeclarations table =
            freeze(new DeclarationInstanceAllocator(), handedIn, commitOrder(NODE), 9L, 1L);
        FrozenDeclarations.Entry entry = table.entryAt(seat(9L, 0));

        assertEquals(FrozenDeclarations.SEALED, table.state());
        assertNotNull(entry, "a declaration carrying its artefact takes the seat the plan gave it");
        assertNotEquals(SELF_REPORTED, entry.instanceId(),
            "the instance column of the declaration handed in is not what the table carries");
        assertTrue(CommitLog.liveInstance(entry.instanceId()), "what it carries is positive");
        assertTrue(entry.bindingDeclared());
        assertEquals(SELF_REPORTED, handedIn.get(0).instanceId(),
            "the declaration the caller owns is copied, not edited");
    }

    @Test
    void numbersFollowThePlansCommitOrderNotTheOrderTheDeclarationsArrivedIn() {
        List<FrozenDeclarations.Candidate> handedIn = List.of(
            declaration(NODE, CHUNK, STATUS, SELF_REPORTED, EPOCH),
            declaration(OTHER_NODE, OTHER_CHUNK, STATUS, SELF_REPORTED, EPOCH));
        FrozenDeclarations table = freeze(new DeclarationInstanceAllocator(), handedIn,
            commitOrder(OTHER_NODE, NODE), 9L, 1L);

        assertEquals(OTHER_NODE, table.entryAt(seat(9L, 0)).nodeKey());
        assertEquals(1L, table.entryAt(seat(9L, 0)).instanceId(),
            "the node the plan commits first takes the first number");
        assertEquals(2L, table.entryAt(seat(9L, 1)).instanceId(),
            "and the one it commits second takes the second, whatever order they arrived in");
    }

    @Test
    void theRequestSideThatReportsItsOwnNumberIsRefused() {
        List<FrozenDeclarations.Candidate> handedIn =
            List.of(declaration(NODE, CHUNK, STATUS, SELF_REPORTED, EPOCH));
        FrozenDeclarations table =
            freeze(new DeclarationInstanceAllocator(), handedIn, commitOrder(NODE), 9L, 1L);
        long minted = table.entryAt(seat(9L, 0)).instanceId();

        assertEquals("declaration-instance-mismatch",
            verify(table, 9L, 0, NODE, worn(SELF_REPORTED, CHUNK, STATUS)).reason(),
            "a number the request chose for itself is no proof of the mapping");
        assertTrue(verify(table, 9L, 0, NODE, worn(minted, CHUNK, STATUS)).accepted(),
            "while the number the freeze minted is admitted: the refusal is about the source");
    }

    @Test
    void bothSidesReportingTheSameNumberAreRefusedWhereTheyUsedToBeAdmitted() {
        List<FrozenDeclarations.Candidate> handedIn =
            List.of(declaration(NODE, CHUNK, STATUS, SELF_REPORTED, EPOCH));
        List<FrozenDeclarations.Assignment> places = commitOrder(NODE);
        FrozenDeclarations wired =
            freeze(new DeclarationInstanceAllocator(), handedIn, places, 9L, 1L);

        assertEquals("declaration-instance-mismatch",
            verify(wired, 9L, 0, NODE, worn(SELF_REPORTED, CHUNK, STATUS)).reason(),
            "both sides may report the same number and the table still carries another one");

        FrozenDeclarations asItWas = freezeUnwired(handedIn, places, 9L);
        assertEquals(SELF_REPORTED, asItWas.entryAt(seat(9L, 0)).instanceId(),
            "with no source in front of it the seal writes the column the caller filled");
        assertTrue(verify(asItWas, 9L, 0, NODE, worn(SELF_REPORTED, CHUNK, STATUS)).accepted(),
            "which is the admission the wired freeze has to refuse: the case can really fail");
    }

    @Test
    void aFreezeWithNothingToNumberMintsNothing() {
        DeclarationInstanceAllocator source = new DeclarationInstanceAllocator();
        List<FrozenDeclarations.Assignment> places = commitOrder(NODE);

        assertTrue(source.mint(List.of(), places).isEmpty(), "no declaration, no number");
        assertTrue(source.mint(null, places).isEmpty());
        FrozenDeclarations table = freeze(source, List.of(countOnly(NODE)), places, 9L, 1L);

        assertEquals(FrozenDeclarations.NOT_WIRED, table.state(),
            "a declaration carrying no artefact takes no entry");
        assertEquals(0, table.size());
        assertEquals(1L, source.mint(List.of(declaration(NODE, CHUNK, STATUS, 0L, EPOCH)), places)
            .get(0).instanceId(),
            "and the freezes with nothing to number took no number: the first one minted is still one");
    }

    @Test
    void theSameDeclarationsNumberedAgainAreNeverHandedANumberTwice() {
        DeclarationInstanceAllocator source = new DeclarationInstanceAllocator();
        List<FrozenDeclarations.Candidate> handedIn = List.of(
            declaration(NODE, CHUNK, STATUS, 0L, EPOCH),
            declaration(OTHER_NODE, OTHER_CHUNK, STATUS, 0L, EPOCH));
        List<FrozenDeclarations.Assignment> places = commitOrder(NODE, OTHER_NODE);

        Set<Long> first = numbersOf(source.mint(handedIn, places));
        Set<Long> second = numbersOf(source.mint(handedIn, places));
        Set<Long> third = numbersOf(source.mint(handedIn, places));

        assertEquals(Set.of(1L, 2L), first);
        assertEquals(Set.of(3L, 4L), second, "one freeze run twice does not hand a number back");
        assertEquals(Set.of(5L, 6L), third);
        assertTrue(first.stream().noneMatch(second::contains), "no number is handed out twice");
    }

    @Test
    void aNewGenerationAndARefusedSealNeverHandBackANumber() {
        DeclarationInstanceAllocator source = new DeclarationInstanceAllocator();
        List<FrozenDeclarations.Assignment> places = commitOrder(NODE);
        List<FrozenDeclarations.Candidate> handedIn =
            List.of(declaration(NODE, CHUNK, STATUS, 0L, EPOCH));

        FrozenDeclarations first = freeze(source, handedIn, places, 9L, 1L);
        FrozenDeclarations next = freeze(source, handedIn, places, 10L, 2L);
        FrozenDeclarations.Sealed refused = FrozenDeclarations.seal(
            new FrozenDeclarations.FreezeKey(LIFECYCLE, 3L), 11L,
            source.mint(List.of(declaration(NODE, CHUNK, STATUS, 0L, EPOCH),
                declaration(NODE, OTHER_CHUNK, STATUS, 0L, EPOCH)), places), places);
        FrozenDeclarations after = freeze(source, handedIn, places, 12L, 4L);

        assertFalse(refused.sealed(), "one node declared twice in one freeze is refused");
        assertEquals("declaration-duplicate", refused.refusal());
        assertEquals(1L, first.entryAt(seat(9L, 0)).instanceId());
        assertEquals(2L, next.entryAt(seat(10L, 0)).instanceId(),
            "the freeze after a new generation numbers forward and never back");
        assertEquals(4L, after.entryAt(seat(12L, 0)).instanceId(),
            "the numbers a refused seal took are spent, not handed back");
    }

    @Test
    void theSameLogicalDeclarationComparesEqualWhateverNumbersTwoRunsMinted() {
        DeclarationInstanceAllocator source = new DeclarationInstanceAllocator();
        List<FrozenDeclarations.Assignment> places = commitOrder(NODE);
        List<FrozenDeclarations.Candidate> handedIn =
            List.of(declaration(NODE, CHUNK, STATUS, 0L, EPOCH));

        FrozenDeclarations firstRun = freeze(source, handedIn, places, 9L, 1L);
        freeze(source, List.of(declaration(OTHER_NODE, OTHER_CHUNK, STATUS, 0L, EPOCH)),
            commitOrder(OTHER_NODE), 10L, 2L);
        FrozenDeclarations secondRun = freeze(source, handedIn, places, 11L, 3L);
        long first = firstRun.entryAt(seat(9L, 0)).instanceId();
        long second = secondRun.entryAt(seat(11L, 0)).instanceId();
        IdentityWitness.Verdict left = verify(firstRun, 9L, 0, NODE, worn(first, CHUNK, STATUS));
        IdentityWitness.Verdict right = verify(secondRun, 11L, 0, NODE, worn(second, CHUNK, STATUS));

        assertNotEquals(first, second, "the two runs minted two numbers");
        assertTrue(left.accepted(), left.reason());
        assertTrue(right.accepted(), right.reason());
        assertEquals(left.instance(), right.instance(),
            "what two runs compare is the key the declaration sealed, not the number");
        assertEquals(key(NODE, 1, CHUNK, STATUS), left.instance());
    }

    @Test
    void anotherLogicalDeclarationIsNeverAnsweredEqual() {
        FrozenDeclarations table = freeze(new DeclarationInstanceAllocator(), List.of(
            declaration(NODE, CHUNK, STATUS, 0L, EPOCH),
            declaration(OTHER_NODE, OTHER_CHUNK, STATUS, 0L, EPOCH)),
            commitOrder(NODE, OTHER_NODE), 9L, 1L);
        long first = table.entryAt(seat(9L, 0)).instanceId();
        long second = table.entryAt(seat(9L, 1)).instanceId();

        assertNotEquals(key(NODE, 1, CHUNK, STATUS), key(OTHER_NODE, 2, OTHER_CHUNK, STATUS));
        assertEquals("declaration-seat-mismatch",
            verify(table, 9L, 0, OTHER_NODE, worn(first, OTHER_CHUNK, STATUS)).reason(),
            "the number of one seat is not a name for another");
        assertEquals("chunk-witness-mismatch",
            verify(table, 9L, 1, OTHER_NODE, worn(second, CHUNK, STATUS)).reason(),
            "nor is the right number with the wrong artefact");
        assertTrue(verify(table, 9L, 1, OTHER_NODE, worn(second, OTHER_CHUNK, STATUS)).accepted(),
            "and the whole of it, the number and the artefact of that seat, is admitted");
    }

    @Test
    void aNumberOfAnotherRunIsNotAnIdentity() {
        DeclarationInstanceAllocator source = new DeclarationInstanceAllocator();
        List<FrozenDeclarations.Assignment> places = commitOrder(NODE);
        List<FrozenDeclarations.Candidate> handedIn =
            List.of(declaration(NODE, CHUNK, STATUS, 0L, EPOCH));

        FrozenDeclarations firstRun = freeze(source, handedIn, places, 9L, 1L);
        FrozenDeclarations secondRun = freeze(source, handedIn, places, 11L, 2L);
        long first = firstRun.entryAt(seat(9L, 0)).instanceId();
        long second = secondRun.entryAt(seat(11L, 0)).instanceId();

        assertNotEquals(first, second, "a minted number is a run's own and not a stable name");
        assertEquals("declaration-instance-mismatch",
            verify(secondRun, 11L, 0, NODE, worn(first, CHUNK, STATUS)).reason(),
            "a line wearing the other run's number is refused by the table that did not mint it");
        assertTrue(verify(secondRun, 11L, 0, NODE, worn(second, CHUNK, STATUS)).accepted());
    }

    @Test
    void removingTheSourceLeavesTheFreezePointExactlyAsItWas() {
        List<FrozenDeclarations.Candidate> handedIn =
            List.of(declaration(NODE, CHUNK, STATUS, SELF_REPORTED, EPOCH));
        List<FrozenDeclarations.Assignment> places = commitOrder(NODE);

        FrozenDeclarations asItWas = freezeUnwired(handedIn, places, 9L);
        FrozenDeclarations wired =
            freeze(new DeclarationInstanceAllocator(), handedIn, places, 9L, 1L);
        FrozenDeclarations production = freezeUnwired(List.of(countOnly(NODE)), places, 9L);

        assertEquals(SELF_REPORTED, asItWas.entryAt(seat(9L, 0)).instanceId(),
            "with the source gone the seal writes the column the caller filled again");
        assertTrue(verify(asItWas, 9L, 0, NODE, worn(SELF_REPORTED, CHUNK, STATUS)).accepted(),
            "and the self reported line is admitted through that table, which is the state a rollback "
                + "returns to");
        assertNotEquals(asItWas.entryAt(seat(9L, 0)).instanceId(),
            wired.entryAt(seat(9L, 0)).instanceId(),
            "the two trees are told apart by the column the table carries");
        assertEquals(FrozenDeclarations.NOT_WIRED, production.state(),
            "and the production shape reads the named state rather than an empty success");
        assertEquals(0, production.size());
    }

    @Test
    void aDeclarationOfALiveWorldIsNumberedAndItsLineIsAdmitted() {
        DeclarationInstanceAllocator source = new DeclarationInstanceAllocator();
        List<FrozenDeclarations.Assignment> places = commitOrder(NODE);
        FrozenDeclarations table =
            freeze(source, List.of(declaration(NODE, CHUNK, STATUS, 0L, EPOCH)), places, 9L, 1L);
        FrozenDeclarations.Entry entry = table.entryAt(seat(9L, 0));

        assertEquals(FrozenDeclarations.SEALED, table.state());
        assertNotNull(entry, "a live world generation is not a reason to refuse a declaration");
        assertTrue(CommitLog.liveWorldEpoch(EPOCH), "a positive generation names a live world");
        assertTrue(CommitLog.worldEpochRefusal(EPOCH).isEmpty(),
            "so the epoch check has nothing to refuse here");
        assertTrue(CommitLog.liveInstance(entry.instanceId()));
        assertTrue(entry.bindingDeclared());
        assertTrue(verify(table, 9L, 0, NODE, worn(entry.instanceId(), CHUNK, STATUS)).accepted());
    }

    @Test
    void aDeclarationOfAnUntrackedWorldTakesNoNumberAndNoSeat() {
        DeclarationInstanceAllocator source = new DeclarationInstanceAllocator();
        List<FrozenDeclarations.Assignment> places = commitOrder(NODE);
        FrozenDeclarations table = freeze(source,
            List.of(declaration(NODE, CHUNK, STATUS, 0L, UNTRACKED)), places, 9L, 1L);

        assertEquals(FrozenDeclarations.NOT_WIRED, table.state(),
            "a declaration whose world generation is not live takes no entry");
        assertEquals(0, table.size());
        assertNull(table.entryAt(seat(9L, 0)), "and no seat is written for it");
        assertEquals("world-untracked", CommitLog.worldEpochRefusal(UNTRACKED));
        assertEquals(1L, source.mint(List.of(declaration(NODE, CHUNK, STATUS, 0L, EPOCH)), places)
            .get(0).instanceId(),
            "the counter did not move for it, so the first number really minted is still one");
    }

    @Test
    void aDeclarationOfAWorldTheTrackedSetLostIsRefusedForAnotherReason() {
        DeclarationInstanceAllocator source = new DeclarationInstanceAllocator();
        List<FrozenDeclarations.Assignment> places = commitOrder(NODE);
        FrozenDeclarations table = freeze(source,
            List.of(declaration(NODE, CHUNK, STATUS, 0L, UNLIVE)), places, 9L, 1L);

        assertEquals(FrozenDeclarations.NOT_WIRED, table.state());
        assertEquals(0, table.size());
        assertNull(table.entryAt(seat(9L, 0)));
        assertEquals("world-not-live", CommitLog.worldEpochRefusal(UNLIVE));
        assertNotEquals(CommitLog.worldEpochRefusal(UNTRACKED), CommitLog.worldEpochRefusal(UNLIVE),
            "a world that was never tracked and one the tracked set lost are two different refusals");
        assertEquals(1L, source.mint(List.of(declaration(NODE, CHUNK, STATUS, 0L, EPOCH)), places)
            .get(0).instanceId(), "and the counter did not move for this one either");
    }

    @Test
    void theTwoWaysAGenerationFailsTheCheckAreNamedApart() {
        assertEquals("", CommitLog.worldEpochRefusal(EPOCH));
        assertEquals("", CommitLog.worldEpochRefusal(EPOCH + 1L),
            "every generation of a live world is answered with no refusal at all");
        assertEquals("world-untracked", CommitLog.worldEpochRefusal(UNTRACKED));
        assertEquals("world-not-live", CommitLog.worldEpochRefusal(UNLIVE));
        assertEquals("world-not-live", CommitLog.worldEpochRefusal(UNLIVE - 1L),
            "a lost world is one refusal however negative its generation is");
        assertNotEquals(CommitLog.worldEpochRefusal(UNTRACKED), CommitLog.worldEpochRefusal(UNLIVE));
    }

    @Test
    void aNegativeChunkPositionIsStillAccepted() {
        DeclarationInstanceAllocator source = new DeclarationInstanceAllocator();
        List<FrozenDeclarations.Assignment> places = commitOrder(NODE);
        FrozenDeclarations table = freeze(source,
            List.of(declaration(NODE, NEGATIVE_CHUNK, STATUS, 0L, EPOCH)), places, 9L, 1L);
        FrozenDeclarations.Entry entry = table.entryAt(seat(9L, 0));

        assertEquals(FrozenDeclarations.SEALED, table.state());
        assertNotNull(entry, "a packed chunk is signed, so a negative one is a legal position");
        assertEquals(NEGATIVE_CHUNK, entry.packedChunkPos());
        assertTrue(entry.bindingDeclared(), "the check reads the epoch column and not the chunk");
        assertTrue(verify(table, 9L, 0, NODE, worn(entry.instanceId(), NEGATIVE_CHUNK, STATUS))
            .accepted());
        assertEquals("chunk-witness-mismatch",
            verify(table, 9L, 0, NODE, worn(entry.instanceId(), CHUNK, STATUS)).reason(),
            "and the column is still read for what it says rather than for its sign");
    }

    @Test
    void theNumberARefusedDeclarationNeverTookGoesToTheNextLiveOne() {
        DeclarationInstanceAllocator source = new DeclarationInstanceAllocator();
        List<FrozenDeclarations.Assignment> places = commitOrder(NODE, OTHER_NODE);
        FrozenDeclarations table = freeze(source, List.of(
            declaration(NODE, CHUNK, STATUS, 0L, UNTRACKED),
            declaration(OTHER_NODE, OTHER_CHUNK, STATUS, 0L, EPOCH)), places, 9L, 1L);

        assertNull(table.entryAt(seat(9L, 0)), "the declaration of the untracked world takes no seat");
        assertEquals(1L, table.entryAt(seat(9L, 1)).instanceId(),
            "and the live one takes the first number, not the second: none was skipped for it");
    }
}
