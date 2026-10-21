/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.CellKind;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.Decision;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.Refusal;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.Slot;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.SlotKey;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.Verdict;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.WritePosition;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.WriteRefusal;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.WriteSpace;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The claim table and the one judgement the short path gained: a write inside the range a declared
 * slot carries and whose contract holds is captured and handed to the judged path, a write inside
 * such a range whose contract fails is refused, and a write no declared range covers keeps the path
 * it always had. The guard, the authority, the owner registry and the version slots are the
 * production classes; only the slot table and the claim source are fixtures. */
class WriteControlledSlotsTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String OTHER_WORLD = "minecraft:the_nether";
    private static final String SITE = "host:server-thread";
    private static final String OTHER_SITE = "domain:second-holder";
    private static final String DOMAIN = WritePath.BLOCK_WRITE.key();
    private static final long TICK = 100L;
    private static final long PLAN_SEQUENCE = 7L;
    private static final int POSITION = 3;
    private static final long GENERATION = 1L;
    private static final Object LEVEL = new Object();
    private static final Object OTHER_LEVEL = new Object();

    /** Two sections of one chunk column at y 64..79, and one position inside each: the ground the
     * spatial terms are driven on. */
    private static final long SECTION_A = SectionPos.asLong(0, 4, 0);
    private static final long SECTION_B = SectionPos.asLong(1, 4, 0);
    private static final WriteSpace SPACE = WriteSpace.of(CellKind.SECTION, SECTION_B, SECTION_A);
    private static final WritePosition IN_A = WritePosition.at(8, 64, 8);
    private static final WritePosition IN_B = WritePosition.at(24, 70, 8);
    private static final WritePosition NEITHER = WritePosition.at(8, 64, 40);
    private static final String SEGMENT = SlotKey.segmentOf(SPACE);
    private static final String NODE_KEY = DOMAIN + "/" + WORLD + "/" + SEGMENT;

    @Test
    void aCoveredWriteWhoseContractHoldsIsCapturedAndReachesTheJudgedPath() {
        Scratch scratch = new Scratch();
        Slot slot = scratch.seatSyntheticSlot();

        Decision decision = scratch.slots.judge(LEVEL, DOMAIN, IN_A, TICK);

        assertEquals(Verdict.CAPTURED, decision.verdict());
        assertEquals(NODE_KEY, decision.planNodeKey());
        assertEquals(PLAN_SEQUENCE, decision.planSequence());
        assertEquals(POSITION, decision.position());
        assertEquals(slot.pathDigest(), decision.pathDigest());
        assertEquals(slot.spaceDigest(), decision.spaceDigest());
        assertEquals(1, decision.covering());
        assertFalse(decision.pathDigest().isEmpty());
        assertFalse(decision.spaceDigest().isEmpty());
        assertNotEquals(decision.pathDigest(), decision.spaceDigest(),
            "the path categories and the spatial range are named apart");

        assertTrue(scratch.guard.admitBlockWrite(LEVEL, WORLD, null),
            "the existing judged path takes the captured write");
        assertEquals(WriteDisposition.GRANT, scratch.guard.lastDecision().disposition(),
            () -> "code=" + scratch.guard.lastDecision().code());
        assertEquals(1L, scratch.slots.capturedCount());
        assertEquals(1L, scratch.slots.attemptsCount());
        assertEquals(0L, scratch.slots.defaultPathCount());
        assertEquals(0L, scratch.slots.rejectedCount());
        assertTrue(scratch.slots.conservationHolds());
        assertEquals(1L, scratch.counters.attempts(WritePath.BLOCK_WRITE, ThreadOrigin.MAIN,
            HolderKind.REGISTERED), "the captured write is counted once, on the judged path");
        assertTrue(scratch.counters.closureHolds());
    }

    @Test
    void aWriteNoSlotCoversStaysOnTheDefaultPath() {
        Scratch scratch = new Scratch();
        scratch.seatSyntheticSlot();

        assertEquals(Verdict.DEFAULT_PATH, scratch.slots.judge(OTHER_LEVEL, DOMAIN, IN_A, TICK).verdict(),
            "a slot for one level does not cover another level");
        assertEquals(Verdict.CAPTURED, scratch.slots.judge(LEVEL, DOMAIN, IN_A, TICK).verdict(),
            "the same level is covered again");

        assertEquals(1L, scratch.slots.defaultPathCount(), "only the uncovered write is a default");
        assertEquals(1L, scratch.slots.capturedCount(),
            () -> "decision=" + scratch.slots.lastDecision() + " refusal=" + scratch.slots.lastRefusal());
        assertEquals(0L, scratch.slots.rejectedCount(),
            () -> "refusal=" + scratch.slots.lastRefusal());
        assertEquals(2L, scratch.slots.attemptsCount());
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void aWriteOutsideTheDeclaredRangeOfTheOnlySlotStaysOnTheDefaultPath() {
        Scratch scratch = new Scratch();
        scratch.seatSyntheticSlot();

        assertEquals(Verdict.DEFAULT_PATH,
            scratch.slots.judge(LEVEL, DOMAIN, NEITHER, TICK).verdict(),
            "a position no declared cell names is not covered by a world");
        assertEquals(0L, scratch.slots.rejectedCount(), "a write outside every range is never refused");
        assertEquals(1L, scratch.slots.defaultPathCount());
        assertEquals(0L, scratch.slots.capturedCount());
    }

    @Test
    void aWriteOutsideTheDeclaredWriteSetOfTheOnlySlotStaysOnTheDefaultPath() {
        Scratch scratch = new Scratch();
        scratch.seatSyntheticSlot();
        WriteControlledSlots table = scratch.slots;

        assertEquals(Verdict.DEFAULT_PATH, table.judge(LEVEL, "kernel_commit", IN_A, TICK).verdict());
        assertEquals(0L, table.rejectedCount());
        assertEquals(1L, table.defaultPathCount());
    }

    @Test
    void aDeclarationOwnerTokenOfAnotherWorldIsRefusedWithoutLiveFallback() {
        Scratch scratch = new Scratch();
        long version = scratch.version();
        scratch.slots.refresh(true);
        OwnerToken live = new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, GENERATION, version, 0L,
            TICK + 40L, HolderKind.REGISTERED, SITE);
        scratch.owners.acquire(live);
        OwnerToken wrong = new OwnerToken(OTHER_WORLD, WriteLevel.REGION, DOMAIN, GENERATION, version,
            0L, TICK + 40L, HolderKind.REGISTERED, SITE);
        Slot slot = scratch.syntheticSlot(version, TICK + 40L, NODE_KEY);
        assertEquals(Refusal.NONE, scratch.slots.register(slot, wrong));

        assertEquals(Verdict.REJECTED, scratch.slots.judge(LEVEL, DOMAIN, IN_A, TICK).verdict());
        assertEquals("owner", scratch.slots.lastRefusal().reason(),
            "a mismatched declaration token cannot fall back to the live token");
    }

    @Test
    void aStaleDeclarationTokenIsRemovedWhenTheSameKeyIsRedeclaredWithoutOne() {
        Scratch scratch = new Scratch();
        long version = scratch.version();
        scratch.slots.refresh(true);
        OwnerToken live = new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, GENERATION, version, 0L,
            TICK + 40L, HolderKind.REGISTERED, SITE);
        scratch.owners.acquire(live);
        OwnerToken stale = new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, GENERATION, version, 0L,
            TICK + 40L, HolderKind.REGISTERED, OTHER_SITE);
        Slot first = scratch.syntheticSlot(version, TICK + 40L, NODE_KEY);
        assertEquals(Refusal.NONE, scratch.slots.register(first, stale));
        Slot second = new Slot(first.key(), LEVEL, NODE_KEY, POSITION, version, GENERATION + 1L,
            first.worldEpoch(), TICK + 40L, HolderKind.REGISTERED, SITE, Set.of(DOMAIN), SPACE);
        assertEquals(Refusal.NONE, scratch.slots.register(second, true));

        assertEquals(Verdict.CAPTURED, scratch.slots.judge(LEVEL, DOMAIN, IN_A, TICK).verdict(),
            "the re-registration without a token uses the live token, not the stale one");
    }

    @Test
    void aDeclarationTokenExpiryIsCheckedAtTheBoundary() {
        Scratch scratch = new Scratch();
        long version = scratch.version();
        scratch.slots.refresh(true);
        OwnerToken live = new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, GENERATION, version, 0L,
            TICK + 40L, HolderKind.REGISTERED, SITE);
        scratch.owners.acquire(live);
        OwnerToken shortLived = new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, GENERATION, version,
            0L, TICK + 1L, HolderKind.REGISTERED, SITE);
        Slot slot = scratch.syntheticSlot(version, TICK + 40L, NODE_KEY);
        assertEquals(Refusal.NONE, scratch.slots.register(slot, shortLived));

        assertEquals(Verdict.CAPTURED, scratch.slots.judge(LEVEL, DOMAIN, IN_A, TICK).verdict(),
            "the declaration token is still alive before its expiry");
        assertEquals(Verdict.REJECTED, scratch.slots.judge(LEVEL, DOMAIN, IN_A, TICK + 1L).verdict(),
            "the declaration token expires at the boundary");
        assertEquals("expiry", scratch.slots.lastRefusal().reason());
    }

    @Test
    void aDeclarationHeldSlotDoesNotNeedTheLivePlanStep() {
        Scratch scratch = new Scratch();
        long version = scratch.version();
        scratch.slots.refresh(true);
        OwnerToken token = new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, GENERATION, version, 0L,
            TICK + 40L, HolderKind.REGISTERED, SITE);
        scratch.owners.acquire(token);
        Slot slot = scratch.syntheticSlot(version, TICK + 40L, NODE_KEY);
        assertEquals(Refusal.NONE, scratch.slots.register(slot, token));
        scratch.source.planHolds = false;

        assertEquals(Verdict.CAPTURED, scratch.slots.judge(LEVEL, DOMAIN, IN_A, TICK).verdict());
        assertEquals(PLAN_SEQUENCE, scratch.slots.lastDecision().planSequence(),
            "the decision uses the declared plan identity when the live step is absent");
    }

    @Test
    void aManualSlotStillRefusesAMissingLivePlanStep() {
        Scratch scratch = new Scratch();
        long version = scratch.version();
        Slot slot = scratch.syntheticSlot(version, TICK + 40L, NODE_KEY);
        scratch.seat(slot);
        scratch.source.planHolds = false;

        assertEquals(Verdict.REJECTED, scratch.slots.judge(LEVEL, DOMAIN, IN_A, TICK).verdict());
        assertEquals("plan", scratch.slots.lastRefusal().reason());
    }

    @Test
    void aDeclarationHeldSlotStillRefusesAStepOfAnotherPlan() {
        Scratch scratch = new Scratch();
        long version = scratch.version();
        scratch.slots.refresh(true);
        OwnerToken token = new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, GENERATION, version, 0L,
            TICK + 40L, HolderKind.REGISTERED, SITE);
        scratch.owners.acquire(token);
        Slot slot = scratch.syntheticSlot(version, TICK + 40L, NODE_KEY);
        assertEquals(Refusal.NONE, scratch.slots.register(slot, token));
        scratch.source.step = new WriteControlledSlots.Source.Step(PLAN_SEQUENCE + 1L, POSITION);

        assertEquals(Verdict.REJECTED, scratch.slots.judge(LEVEL, DOMAIN, IN_A, TICK).verdict());
        assertEquals("plan_sequence", scratch.slots.lastRefusal().reason());
    }

    @Test
    void noSlotAtAllIsTheDefaultPathAndNotARefusal() {
        Scratch scratch = new Scratch();

        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL));
        assertEquals(Verdict.DEFAULT_PATH, scratch.slots.lastDecision().verdict());
        assertEquals(0L, scratch.slots.rejectedCount(), "a write without a claim is never refused");
        assertEquals(1L, scratch.slots.defaultPathCount());
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void aClaimTableThatIsOffLeavesEveryWriteOnTheDefaultPath() {
        Scratch scratch = new Scratch();
        scratch.slots.register(scratch.syntheticSlot(scratch.version(), TICK + 40L, NODE_KEY));

        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL));
        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL));
        assertEquals(2L, scratch.slots.defaultPathCount());
        assertEquals(0L, scratch.slots.capturedCount());
        assertEquals(0L, scratch.slots.rejectedCount());
    }

    @Test
    void aWriteWhosePositionCannotBeReadIsRefusedInsteadOfMatchedByWorld() {
        Scratch scratch = new Scratch();
        scratch.seatSyntheticSlot();

        assertEquals(1, scratch.guard.classifyBlockWrite(LEVEL),
            "a write a declared range could cover does not take the short path");

        assertEquals(Verdict.REJECTED, scratch.slots.lastDecision().verdict());
        assertEquals(0L, scratch.slots.defaultPathCount(), "it does not silently become a default write");
        assertEquals(1L, scratch.slots.rejectedCount());
        WriteRefusal refusal = scratch.slots.lastRefusal();
        assertEquals("segment", refusal.reason());
        assertEquals(SEGMENT, refusal.segment());
        assertEquals(0, refusal.covering(), "nothing was placed, so nothing covered it");
        assertFalse(refusal.spaceDigest().isEmpty());
        assertEquals(WORLD, refusal.worldId());
        assertNull(scratch.slots.lastCoveredSlot(), "a refused write seats no covering slot");
    }

    @Test
    void aWriteNoDeclaredRangeCouldCoverKeepsItsPathWhenThePositionIsUnreadable() {
        Scratch scratch = new Scratch();

        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL),
            "with no slot declared there is nothing a missing position could hide");
        assertEquals(Verdict.DEFAULT_PATH, scratch.slots.lastDecision().verdict());
        assertEquals(0L, scratch.slots.rejectedCount());
    }

    @Test
    void theTwoDigestsAreReportedApartAndMembershipIsReadFromTheCells() {
        Scratch scratch = new Scratch();
        Slot slot = scratch.seatSyntheticSlot();

        assertArrayEquals(new long[] {SECTION_A, SECTION_B}, slot.space().cells(),
            "the range reads back as the canonical cell keys");
        assertTrue(slot.covers(IN_A));
        assertTrue(slot.covers(IN_B));
        assertFalse(slot.covers(NEITHER));
        assertFalse(slot.covers(WritePosition.NONE), "an unreadable position is inside nothing");
        assertFalse(slot.space().contains(SectionPos.asLong(0, 4, 2)),
            "membership is decided on the keys themselves, not on the fold");
        assertNotEquals(slot.pathDigest(), slot.spaceDigest());
        assertArrayEquals(slot.space().cells(), scratch.slots.spaceOf(slot.key()).cells(),
            "the exact range is readable beside its fold");
        assertEquals(0, scratch.slots.spaceOf(null).cells().length,
            "a key that names no seat carries no range");
    }

    @Test
    void aRangeIsNamedByItsCellsAndItsGranularity() {
        WriteSpace sections = WriteSpace.of(CellKind.SECTION, SectionPos.asLong(-1, 3, -1));
        WriteSpace oneMoreSection = WriteSpace.of(CellKind.SECTION, SectionPos.asLong(-1, 3, -1),
            SectionPos.asLong(-1, 3, 0));
        WriteSpace asBlocks = WriteSpace.of(CellKind.BLOCK, SectionPos.asLong(-1, 3, -1));

        assertTrue(sections.covers(WritePosition.at(-1, 63, -1)),
            "a negative coordinate is placed by the packing the game itself uses");
        assertEquals(SectionPos.asLong(-1, 3, -1), sections.cells()[0]);
        assertNotEquals(SlotKey.segmentOf(sections), SlotKey.segmentOf(oneMoreSection),
            "two different ranges never spell the same segment");
        assertEquals(SlotKey.segmentOf(sections), SlotKey.segmentOf(
            WriteSpace.of(CellKind.SECTION, SectionPos.asLong(-1, 3, -1))),
            "the same range always spells the same segment");
        assertNotEquals(SlotKey.segmentOf(sections), SlotKey.segmentOf(asBlocks),
            "the granularity is part of the name");
        assertFalse(asBlocks.covers(WritePosition.at(-1, 63, -1)),
            "a range declared in blocks is not asked about a section");
        assertEquals("", SlotKey.segmentOf(WriteSpace.NONE), "a range that cannot be placed spells nothing");
    }

    @Test
    void twoRangesCoveringTheSameWriteRefuseItInsteadOfPickingOne() {
        Scratch scratch = new Scratch();
        scratch.seatRange(WriteSpace.of(CellKind.SECTION, SECTION_A));
        scratch.seatRange(WriteSpace.of(CellKind.SECTION, SECTION_A, SECTION_B));

        Decision decision = scratch.slots.judge(LEVEL, DOMAIN, IN_A, TICK);

        assertEquals(Verdict.REJECTED, decision.verdict(), "two covering ranges are an ambiguity");
        assertEquals("owner", decision.reason());
        assertEquals(2, decision.covering());
        assertEquals(2, scratch.slots.lastRefusal().covering());
        assertEquals(1L, scratch.slots.rejectedCount());
        assertEquals(0L, scratch.slots.capturedCount());
        assertEquals(0L, scratch.slots.defaultPathCount());
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void twoSlotsWhoseRangesDoNotMeetEachCoverTheirOwnWrite() {
        Scratch scratch = new Scratch();
        scratch.seatRange(WriteSpace.of(CellKind.SECTION, SECTION_A));
        scratch.seatRange(WriteSpace.of(CellKind.SECTION, SECTION_B));

        assertEquals(Verdict.CAPTURED, scratch.slots.judge(LEVEL, DOMAIN, IN_A, TICK).verdict(),
            "the first range covers its own write and is not refused by its neighbour");
        assertEquals(1, scratch.slots.lastDecision().covering());
        assertEquals(Verdict.CAPTURED, scratch.slots.judge(LEVEL, DOMAIN, IN_B, TICK).verdict(),
            "the second range covers its own write");
        assertEquals(1, scratch.slots.lastDecision().covering());
        assertEquals(Verdict.DEFAULT_PATH, scratch.slots.judge(LEVEL, DOMAIN, NEITHER, TICK).verdict(),
            "a write in neither range is a default write");
        assertEquals(0L, scratch.slots.rejectedCount());
        assertEquals(2L, scratch.slots.capturedCount());
        assertEquals(1L, scratch.slots.defaultPathCount());
    }

    @Test
    void aSlotThatNamesNoRangeIsRefusedWhenItIsDeclared() {
        Scratch scratch = new Scratch();
        long version = scratch.version();

        assertEquals(Refusal.EMPTY_WRITE_SET, scratch.slots.register(
            scratch.syntheticSlotWithoutSpace(version)));
        assertEquals(Refusal.EMPTY_WRITE_SET, scratch.slots.register(
            scratch.syntheticSlotOf(WriteSpace.of(CellKind.SECTION, SECTION_A), "r.0.0", version)),
            "a key whose segment is not the token of the range it carries names no range");
        assertEquals(Refusal.EMPTY_WRITE_SET, scratch.slots.register(
            scratch.syntheticSlotOf(WriteSpace.NONE, "", version)));
        long[] tooMany = new long[WriteSpace.MAX_CELLS + 1];
        for (int index = 0; index < tooMany.length; index++) {
            tooMany[index] = SectionPos.asLong(index, 4, 0);
        }
        assertEquals(Refusal.EMPTY_WRITE_SET, scratch.slots.register(
            scratch.syntheticSlotOf(WriteSpace.of(CellKind.SECTION, tooMany), "", version)),
            "a range past the declared bound is not declared");
        assertEquals(0, scratch.slots.slots(), "no refused slot was seated");
        assertEquals(4L, scratch.slots.refusedCount(Refusal.EMPTY_WRITE_SET));
        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL), "the write keeps the default path");
        assertEquals(0L, scratch.slots.rejectedCount());
    }

    @Test
    void theRangeOfASlotIsCanonicalWhateverOrderItIsDeclaredIn() {
        WriteSpace shuffled = WriteSpace.of(CellKind.SECTION, SECTION_B, SECTION_A, SECTION_B);

        assertEquals(2, shuffled.size(), "a repeated cell is one cell");
        assertArrayEquals(new long[] {SECTION_A, SECTION_B}, shuffled.cells());
        assertEquals(SEGMENT, SlotKey.segmentOf(shuffled));
        assertEquals(SPACE, shuffled);
    }

    @Test
    void aSlotThatCannotBeJudgedIsRefusedWhenItIsDeclared() {
        Scratch scratch = new Scratch();
        long version = scratch.version();

        assertEquals(Refusal.WILDCARD_KEY, scratch.slots.register(
            scratch.syntheticSlotWith("*", DOMAIN, SEGMENT, PLAN_SEQUENCE, version, Set.of(DOMAIN))));
        assertEquals(Refusal.WILDCARD_KEY, scratch.slots.register(
            scratch.syntheticSlotWith(WORLD, "*", SEGMENT, PLAN_SEQUENCE, version, Set.of(DOMAIN))));
        assertEquals(Refusal.EMPTY_WRITE_SET, scratch.slots.register(
            scratch.syntheticSlotWith(WORLD, DOMAIN, SEGMENT, PLAN_SEQUENCE, version, Set.of())));
        assertEquals(Refusal.PLAN_SEQUENCE_INVALID, scratch.slots.register(
            scratch.syntheticSlotWith(WORLD, DOMAIN, SEGMENT, 0L, version, Set.of(DOMAIN))));
        assertEquals(0, scratch.slots.slots(), "no refused slot was seated");
        assertEquals(2L, scratch.slots.refusedCount(Refusal.WILDCARD_KEY));
        assertEquals(1L, scratch.slots.refusedCount(Refusal.EMPTY_WRITE_SET));
        assertEquals(1L, scratch.slots.refusedCount(Refusal.PLAN_SEQUENCE_INVALID));
        assertEquals(4L, scratch.slots.refusedCount());
        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL), "the write keeps the default path");
        assertEquals(0L, scratch.slots.rejectedCount());
    }

    @Test
    void aWriteWhoseDomainHoldsNoVersionIsRefused() {
        Scratch scratch = new Scratch();
        scratch.seat(scratch.syntheticSlot(WriteVersionSlots.NOT_CARRIED, TICK + 40L, NODE_KEY),
            WriteVersionSlots.NOT_CARRIED, 0L);

        assertRefused(scratch, "version", WriteVersionSlots.NOT_CARRIED);
    }

    @Test
    void aSlotWhoseExpiryTickHasPassedIsRefused() {
        Scratch scratch = new Scratch();
        scratch.seat(scratch.syntheticSlot(scratch.version(), TICK, NODE_KEY));

        assertRefused(scratch, "expiry", 1L);
    }

    @Test
    void aWriteOfAnotherWorldThanTheSlotNamesIsRefused() {
        Scratch scratch = new Scratch();
        scratch.seat(scratch.syntheticSlot(scratch.version(), TICK + 40L, NODE_KEY));
        scratch.source.world = OTHER_WORLD;

        assertRefused(scratch, "world", 1L);
    }

    @Test
    void aSecondHolderOfTheSameDomainIsRefused() {
        Scratch scratch = new Scratch();
        long version = scratch.version();
        scratch.seat(scratch.syntheticSlotForHolder(OTHER_SITE, version));
        assertFalse(scratch.owners.acquire(new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, 2L,
            version, 0L, 0L, HolderKind.REGISTERED, OTHER_SITE)), "the first holder keeps the domain");
        assertEquals(1L, scratch.owners.doubleHolderCount());

        assertRefused(scratch, "owner", version);
    }

    @Test
    void aCoveredWriteWhosePlanNodeIsMissingIsRefused() {
        Scratch scratch = new Scratch();
        scratch.seat(scratch.syntheticSlot(scratch.version(), TICK + 40L, NODE_KEY));
        scratch.source.planHolds = false;

        assertRefused(scratch, "plan", 1L);
    }

    @Test
    void aCoveredWriteWhosePlanStepIsOfAnotherSequenceIsRefused() {
        Scratch scratch = new Scratch();
        scratch.seat(scratch.syntheticSlot(scratch.version(), TICK + 40L, NODE_KEY));
        scratch.source.step = new WriteControlledSlots.Source.Step(PLAN_SEQUENCE + 1L, POSITION);

        assertRefused(scratch, "plan_sequence", 1L);
    }

    @Test
    void aWriteCoveredByARetiredGenerationIsRefused() {
        Scratch scratch = new Scratch();
        Slot slot = scratch.syntheticSlot(scratch.version(), TICK + 40L, NODE_KEY);
        scratch.seat(slot);
        assertNotEquals(GENERATION, scratch.slots.advance(slot.key()));

        assertRefused(scratch, "generation", 1L);
    }

    @Test
    void aWriteCoveredWhileTheWorldEpochMovedOnIsRefused() {
        Scratch scratch = new Scratch();
        scratch.seat(scratch.syntheticSlot(scratch.version(), TICK + 40L, NODE_KEY));
        scratch.guard.noteLiveWorlds(List.of(OTHER_WORLD));

        assertRefused(scratch, "world_epoch", 1L);
    }

    @Test
    void aSecondSlotOfTheSameGenerationIsRefused() {
        Scratch scratch = new Scratch();
        Slot slot = scratch.syntheticSlot(scratch.version(), TICK + 40L, NODE_KEY);

        assertEquals(Refusal.NONE, scratch.slots.register(slot));
        assertEquals(Refusal.DUPLICATE_KEY, scratch.slots.register(slot));
        assertEquals(1, scratch.slots.slots());
        assertEquals(1L, scratch.slots.refusedCount(Refusal.DUPLICATE_KEY));
    }

    @Test
    void anInputWithoutALevelOrAWritePathIsNotExecutable() {
        Scratch scratch = new Scratch();
        scratch.seatSyntheticSlot();

        assertEquals(Verdict.DEFAULT_PATH, scratch.slots.judge(null, DOMAIN, IN_A, TICK).verdict());
        assertEquals("NOT-EXECUTABLE", scratch.slots.lastDecision().reason());
        assertEquals(Verdict.DEFAULT_PATH, scratch.slots.judge(LEVEL, "", TICK).verdict());
        assertEquals(2L, scratch.slots.notExecutableCount());
        assertEquals(0L, scratch.slots.attemptsCount(), "nothing was judged, so nothing is conserved");
        assertEquals(0L, scratch.slots.rejectedCount());
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void theShortPathKeepsTheSequenceOfTheRevisionBeforeTheClaimTable() {
        Scratch bare = new Scratch();
        bare.guard.controlledSlots(null);
        Scratch off = new Scratch();
        off.slots.register(off.syntheticSlot(off.version(), TICK + 40L, NODE_KEY));
        Scratch open = new Scratch();
        open.slots.refresh(true);
        Scratch wired = new Scratch();
        wired.seatSyntheticSlot();

        List<Integer> parent = parentSequence(bare);
        assertEquals(List.of(0, 0, 1, 0, 1), parent, "the sequence of the revision before this change");
        assertEquals(parent, bare.shortPathSequence(), "no claim table wired");
        assertEquals(parent, off.shortPathSequence(), "claim table wired, every write uncovered");
        assertEquals(parent, open.shortPathSequence(), "claim table on, no slot declared");
        assertEquals(List.of(0, 1, 1, 0, 1), wired.shortPathSequence(),
            "only the write a declared range could cover leaves the short path");
    }

    @Test
    void theReadoutPublishesTheClaimFaceAndItsZeroValues() {
        List<String> export = KernelReadings.export(KernelModule.instance());

        for (String key : List.of("write.controlled.enabled=", "write.controlled.slots=",
            "write.controlled.refused=", "write.controlled.refused.wildcard=",
            "write.controlled.attempts=", "write.controlled.default_path=",
            "write.controlled.captured=", "write.controlled.rejected=",
            "write.controlled.not_executable=", "write.controlled.conservation_ok=",
            "write.controlled.landed=", "write.controlled.last_refusal.reason=",
            "write.controlled.last_refusal.expected_version=",
            "write.controlled.last_refusal.carried_version=",
            "write.controlled.last.write_set_digest=", "write.controlled.last.space_digest=",
            "write.controlled.last.covering=", "write.controlled.last_refusal.write_set_digest=",
            "write.controlled.last_refusal.space_digest=", "write.controlled.last_refusal.covering=")) {
            assertTrue(export.stream().anyMatch(line -> line.startsWith(key)), "missing " + key);
        }
        assertTrue(export.stream().anyMatch(line -> line.startsWith("commit.entries=")),
            "the landing face keeps its own field");
    }

    private static void assertRefused(Scratch scratch, String reason, long version) {
        assertEquals(Verdict.REJECTED, scratch.slots.judge(LEVEL, DOMAIN, IN_A, TICK).verdict(),
            "a write inside a declared range whose contract fails is refused");

        assertEquals(Verdict.REJECTED, scratch.slots.lastDecision().verdict());
        assertEquals(reason, scratch.slots.lastDecision().reason());
        WriteRefusal refusal = scratch.slots.lastRefusal();
        assertEquals(reason, refusal.reason());
        assertEquals(WORLD, refusal.worldId());
        assertEquals(DOMAIN, refusal.domainId());
        assertEquals(WriteLevel.REGION, refusal.level());
        assertEquals(SEGMENT, refusal.segment());
        assertEquals(PLAN_SEQUENCE, refusal.planSequence());
        assertEquals(NODE_KEY, refusal.planNodeKey());
        assertEquals(TICK, refusal.tickIndex());
        assertEquals(version, refusal.expectedVersion());
        assertEquals(GENERATION, refusal.slotGeneration());
        assertEquals(1, refusal.covering());
        assertFalse(refusal.pathDigest().isEmpty());
        assertFalse(refusal.spaceDigest().isEmpty());
        assertEquals(1L, scratch.slots.rejectedCount());
        assertEquals(0L, scratch.slots.capturedCount());
        assertEquals(0L, scratch.slots.defaultPathCount());
        assertTrue(scratch.slots.conservationHolds());
        assertEquals(0L, scratch.counters.attempts(WritePath.BLOCK_WRITE, ThreadOrigin.MAIN,
            HolderKind.REGISTERED), "a refused claim is not counted on the short path");
    }

    /** The short path of the revision before this change, driven over the same states. */
    private static List<Integer> parentSequence(Scratch scratch) {
        List<Integer> sequence = new ArrayList<>();
        scratch.guard.refresh(false, false, false, TICK);
        sequence.add(scratch.guard.classifyBlockWrite(LEVEL));
        scratch.guard.refresh(true, false, false, TICK);
        sequence.add(scratch.guard.classifyBlockWrite(LEVEL));
        sequence.add(scratch.otherThreadClassification());
        scratch.guard.refresh(false, false, false, TICK);
        sequence.add(scratch.guard.classifyBlockWrite(LEVEL));
        scratch.guard.refresh(true, false, false, TICK);
        sequence.add(scratch.otherThreadClassification());
        return sequence;
    }

    /** The write paths, the registries and the claim table of one process, wired the way the kernel
     * wires them; only the slot table's contents and the claim source are fixtures. */
    private static final class Scratch {

        private final WritePathCounters counters = new WritePathCounters();
        private final WriteLedger ledger = new WriteLedger();
        private final IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        private final OwnerRegistry owners = new OwnerRegistry();
        private final IntentQueue intents = new IntentQueue(() -> 8, () -> 2);
        private final CommitSegment segment = new CommitSegment(intents, () -> true, intents::capacity);
        private final WriteAuthority authority =
            new WriteAuthority(owners, intents, ledger, () -> false, () -> 2);
        private final WorldWriteGuard guard =
            new WorldWriteGuard(counters, authority, intents, payloads, ledger);
        private final WriteVersionSlots versions = new WriteVersionSlots();
        private final FixtureSource source = new FixtureSource();
        private final WriteControlledSlots slots =
            new WriteControlledSlots(owners, versions, guard.worldEpochs(), source);

        private Scratch() {
            segment.bindOwnerThread(Thread.currentThread());
            intents.bindPayload(guard);
            guard.bindServerThread(Thread.currentThread(), SITE);
            guard.versionSlots(versions);
            guard.controlledSlots(slots);
            guard.refresh(true, false, false, TICK);
            guard.noteLiveWorlds(List.of(WORLD));
            versions.refresh(true);
            slots.refresh(false);
        }

        /** The generation the plan this fixture stands for granted, published the way the plan
         * point publishes it: the value the grant answered is the one a row may wear. */
        private long version() {
            long granted = versions.grant(WORLD, WriteLevel.REGION, DOMAIN);
            versions.noteGranted(PLAN_SEQUENCE,
                Map.of(new OwnershipDomain(WORLD, WriteLevel.REGION, DOMAIN), granted));
            return granted;
        }

        private void seat(Slot slot) {
            seat(slot, version(), GENERATION);
        }

        private void seat(Slot slot, long version, long epoch) {
            owners.acquire(new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, epoch, version, 0L, 0L,
                HolderKind.REGISTERED, SITE));
            slots.refresh(true);
            assertEquals(Refusal.NONE, slots.register(slot));
        }

        private Slot seatSyntheticSlot() {
            long version = version();
            Slot slot = syntheticSlot(version, TICK + 40L, NODE_KEY);
            seat(slot, version, GENERATION);
            return slot;
        }

        /** Declares one more slot of the same contract with a range of its own, so two ranges can be
         * held over one write without sharing a slot key. */
        private Slot seatRange(WriteSpace space) {
            long version = version();
            Slot slot = syntheticSlotOfRange(space, NODE_KEY, version);
            seat(slot, version, GENERATION);
            return slot;
        }

        private Slot syntheticSlotForHolder(String site, long expectedVersion) {
            return new Slot(new SlotKey(WORLD, DOMAIN, WriteLevel.REGION, SEGMENT, PLAN_SEQUENCE),
                LEVEL, NODE_KEY, POSITION, expectedVersion, GENERATION,
                guard.worldEpochs().epochOf(WORLD), TICK + 40L, HolderKind.REGISTERED, site,
                Set.of(DOMAIN), SPACE);
        }

        private Slot syntheticSlot(long expectedVersion, long expireTick, String nodeKey) {
            return syntheticSlotWith(WORLD, DOMAIN, SEGMENT, PLAN_SEQUENCE, expectedVersion,
                Set.of(DOMAIN), expireTick, nodeKey);
        }

        private Slot syntheticSlotOf(WriteSpace space, String segment, long expectedVersion) {
            return syntheticSlotOf(space, segment, NODE_KEY, expectedVersion);
        }

        private Slot syntheticSlotOfRange(WriteSpace space, String nodeKey, long expectedVersion) {
            return syntheticSlotOf(space, SlotKey.segmentOf(space), nodeKey, expectedVersion);
        }

        private Slot syntheticSlotOf(WriteSpace space, String segment, String nodeKey,
                                     long expectedVersion) {
            return new Slot(new SlotKey(WORLD, DOMAIN, WriteLevel.REGION, segment, PLAN_SEQUENCE),
                LEVEL, nodeKey, POSITION, expectedVersion, GENERATION,
                guard.worldEpochs().epochOf(WORLD), TICK + 40L, HolderKind.REGISTERED, SITE,
                Set.of(DOMAIN), space);
        }

        private Slot syntheticSlotWithoutSpace(long expectedVersion) {
            return new Slot(new SlotKey(WORLD, DOMAIN, WriteLevel.REGION, SEGMENT, PLAN_SEQUENCE),
                LEVEL, NODE_KEY, POSITION, expectedVersion, GENERATION,
                guard.worldEpochs().epochOf(WORLD), TICK + 40L, HolderKind.REGISTERED, SITE,
                Set.of(DOMAIN));
        }

        private Slot syntheticSlotWith(String world, String domain, String segment, long planSequence,
                                       long expectedVersion, Set<String> writeSet) {
            return syntheticSlotWith(world, domain, segment, planSequence, expectedVersion, writeSet,
                TICK + 40L, NODE_KEY);
        }

        private Slot syntheticSlotWith(String world, String domain, String segment, long planSequence,
                                       long expectedVersion, Set<String> writeSet, long expireTick,
                                       String nodeKey) {
            return new Slot(new SlotKey(world, domain, WriteLevel.REGION, segment, planSequence), LEVEL,
                nodeKey, POSITION, expectedVersion, GENERATION, guard.worldEpochs().epochOf(WORLD),
                expireTick, HolderKind.REGISTERED, SITE, writeSet, SPACE);
        }

        private WriteVersionSlots versionSlots() {
            return versions;
        }

        private List<Integer> shortPathSequence() {
            return parentSequence(this);
        }

        private int otherThreadClassification() {
            int[] answer = new int[1];
            Thread thread = new Thread(() -> answer[0] = guard.classifyBlockWrite(LEVEL),
                "another-thread");
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            return answer[0];
        }

        /** The claim source a deployment would wire: which world a level is, and which plan step the
         * frozen plan holds. Both answers can be moved to drive the contract terms. */
        private static final class FixtureSource implements WriteControlledSlots.Source {

            private String world = WORLD;
            private boolean planHolds = true;
            private Step step = new Step(PLAN_SEQUENCE, POSITION);

            @Override
            public String worldOf(Object levelRef) {
                return levelRef == LEVEL ? world : "";
            }

            @Override
            public Step stepOf(String worldId, String domainId, String planNodeKey) {
                return planHolds && planNodeKey.equals(NODE_KEY) ? step : null;
            }
        }
    }
}
