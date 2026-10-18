/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.jobs.JobIntake;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineBatchLedger;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineRoundJobs;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.sites.IntentPayloadDirectory;
import io.izzel.arclight.common.prts.kernel.sites.WriteClaimTable;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.CellKind;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.Verdict;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.WritePosition;
import io.izzel.arclight.common.prts.kernel.sites.WriteControlledSlots.WriteSpace;
import io.izzel.arclight.common.prts.kernel.sites.WritePath;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The claim a declared write set becomes, and the write it covers.
 *
 * <p>The declarations are the real ones the chunk pipeline hands in, the right and its version come
 * from the real grant point, and the guard, the authority and the registries are the production
 * classes; only the level a world is read as and the step the plan holds are fixtures. */
class PipelineWriteClaimsTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String OTHER_WORLD = "minecraft:the_nether";
    private static final String DOMAIN = WriteClaimTable.CHUNK_PIPELINE;
    private static final String MAILBOX = "sorter";
    private static final String NODE_KEY = DOMAIN + "/" + WORLD + "/" + MAILBOX;
    private static final String HOLDER = "domain:" + DOMAIN;
    private static final String OTHER_HOLDER = "domain:second-holder";
    private static final String SITE = "host:server-thread";
    private static final long TICK = 100L;
    private static final long PLAN_SEQUENCE = 7L;
    private static final long ROUNDS = 5L;
    private static final long CELL = ChunkPos.asLong(0, 0);
    private static final Object LEVEL = new Object();
    private static final Object OTHER_LEVEL = new Object();

    /** The one section the fixture declares every job writes in, and one position inside it. A
     * deployment reads the range out of the declaration a job carries; no such declaration exists
     * yet, so the reader here is the fixture and the default reader names none. */
    private static final WriteSpace SPACE =
        WriteSpace.of(CellKind.SECTION, SectionPos.asLong(0, 4, 0));
    private static final WritePosition WITNESS = WritePosition.at(8, 64, 8);

    @Test
    void aDeclaredJobOfAClaimedDomainClaimsTheWritePathKeyOfItsOwnWriteSet() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);

        List<PipelineWriteClaims.Claim> rows = scratch.claims.claims();
        assertEquals(1, rows.size(), () -> "claims=" + rows);
        PipelineWriteClaims.Claim claim = rows.get(0);
        assertTrue(claim.nodeKey().startsWith(NODE_KEY + "#"), claim.nodeKey());
        assertEquals(WORLD, claim.worldId());
        assertEquals(MAILBOX, claim.mailbox());
        assertEquals(DOMAIN, claim.domainId());
        assertEquals(PLAN_SEQUENCE, claim.planSequence());
        assertEquals(Set.of(WritePath.BLOCK_WRITE.key()), claim.writeSet());
        assertFalse(claim.writeSet().contains("*"), "the table never hands out a wildcard");
        assertTrue(claim.expectedVersion() > 0L, "a claim names the version its slot carries");
        assertTrue(claim.space().wellFormed(), "a claim names the range its writes fall in");
        assertEquals(scratch.claims.worldOf(LEVEL), WORLD, "the level is placed");
    }

    @Test
    void aJobWhoseDeclarationNamesNoRangeIsNeverClaimed() {
        Scratch scratch = new Scratch();
        scratch.claims.writeSpaces(null);
        scratch.declare(ROUNDS);

        assertEquals(0, scratch.claims.active(), "a write set that cannot be placed makes no claim");
        assertEquals(1L, scratch.claims.emptyWriteSetCount());
        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        assertEquals(0, scratch.slots.slots(), "no slot is declared for an unplaceable write set");
        assertEquals(0L, scratch.claims.seatedCount());
        assertEquals(0L, scratch.slots.registeredCount(), "the register point is never reached");
        assertEquals(Verdict.DEFAULT_PATH, scratch.judge(WritePath.BLOCK_WRITE.key()));
        assertEquals(0L, scratch.slots.rejectedCount());
    }

    @Test
    void aClaimIsSeatedForTheWindowOfItsOwnJobAndCoversTheWriteOfThatWindow() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);

        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        assertEquals(1, scratch.slots.slots(), "the claim is seated while its job runs");
        assertEquals(1L, scratch.claims.seatedCount());

        WriteControlledSlots.Slot claim = scratch.capture();
        assertTrue(scratch.slots.lastDecision().planNodeKey().startsWith(NODE_KEY + "#"));
        assertEquals(PLAN_SEQUENCE, scratch.slots.lastDecision().planSequence());
        assertEquals(scratch.graph.nodes().get(0).position(), scratch.slots.lastDecision().position());
        assertFalse(claim.pathDigest().isEmpty());
        assertFalse(claim.spaceDigest().isEmpty());

        assertEquals(1, scratch.guard.classifyBlockWrite(LEVEL),
            "a write a declared range could cover leaves the short path");
        assertEquals(Verdict.REJECTED, scratch.slots.lastDecision().verdict(),
            "the short path names no position, so the write is refused and not matched by world");
        assertEquals("segment", scratch.slots.lastRefusal().reason());
        assertNull(scratch.slots.lastCoveredSlot(), "a refused write seats no claim either");

        scratch.claims.noteLanded(claim, WORLD);
        assertEquals(1L, scratch.claims.landedWriteCount());

        scratch.claims.ownerTaskEnd(WORLD, MAILBOX);
        assertEquals(0, scratch.slots.slots());
        assertEquals(1L, scratch.claims.disarmedCount());
        assertEquals(Verdict.DEFAULT_PATH, scratch.judge(WritePath.BLOCK_WRITE.key()),
            "the write after the window is not a write of that job");
        assertEquals(1L, scratch.slots.defaultPathCount());
        assertEquals(1L, scratch.slots.capturedCount());
        assertEquals(1L, scratch.slots.rejectedCount());
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void aWriteTheClaimOfTheRunningJobDoesNotCoverKeepsTheDefaultPath() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);

        assertEquals(Verdict.DEFAULT_PATH, scratch.judge(WritePath.KERNEL_COMMIT.key()));
        assertEquals(0L, scratch.slots.rejectedCount(), "a write outside the set is never refused");
        assertEquals(1L, scratch.slots.defaultPathCount());

        assertEquals(0, scratch.guard.classifyBlockWrite(OTHER_LEVEL),
            "another level is another world's write until a claim of it is seated");
        assertEquals(2L, scratch.slots.defaultPathCount());
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void aDomainTheClaimTableDoesNotNameClaimsNothing() {
        Scratch scratch = new Scratch();
        scratch.declareForeignDomain();

        assertEquals(0, scratch.claims.active());
        assertEquals(1L, scratch.claims.unclaimedDomainCount());
        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        assertEquals(0, scratch.slots.slots());
        assertEquals(1L, scratch.claims.unclaimedWindowCount());
        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL));
        assertEquals(1L, scratch.slots.defaultPathCount());
        assertEquals(0L, scratch.slots.rejectedCount());
    }

    @Test
    void aMailboxThePlacementTableDidNotPlaceSeatsTheClaimOfTheWorldTheWriteIsIn() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);

        scratch.claims.ownerTaskBegin(PrtsPipelineRows.UNPLACED_WORLD, MAILBOX);
        assertEquals(1L, scratch.claims.unplacedWindowCount());
        assertEquals(1, scratch.slots.slots(), "the declared job of that mailbox is seated");
        scratch.capture();
        assertEquals(Verdict.CAPTURED, scratch.slots.lastDecision().verdict(),
            "the level the write arrives on decides which claim covers it");
        scratch.claims.ownerTaskEnd(PrtsPipelineRows.UNPLACED_WORLD, MAILBOX);
        assertEquals(0, scratch.slots.slots());
        assertEquals(1L, scratch.claims.disarmedCount());
    }

    @Test
    void anUnplacedMailboxNoPlanDeclaredClaimsNothingAndNamesItself() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);

        scratch.claims.ownerTaskBegin(PrtsPipelineRows.UNPLACED_WORLD, "no-such-mailbox");
        assertEquals(0, scratch.slots.slots());
        assertEquals(1L, scratch.claims.unclaimedWindowCount());
        assertEquals(PrtsPipelineRows.UNPLACED_WORLD + "|no-such-mailbox", scratch.claims.lastWindow());
    }

    @Test
    void aClaimIsOnlySeatedForTheThreadTheWritesAreJudgedOn() {
        Scratch scratch = new Scratch();
        scratch.judge = false;
        scratch.declare(ROUNDS);

        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        assertEquals(0, scratch.slots.slots(), "no claim is seated off the judged thread");
        assertEquals(1L, scratch.claims.offThreadWindowCount());
        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL));
        assertEquals(1L, scratch.slots.defaultPathCount());
    }

    @Test
    void aTokenizedSpanSeatsItsClaimOnTheThreadThatRunsTheBody() throws Exception {
        Scratch scratch = new Scratch();
        scratch.judge = false;
        scratch.declare(ROUNDS);
        Object token = new Object();
        CountDownLatch seated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread body = new Thread(() -> {
            scratch.claims.ownerTaskBegin(WORLD, MAILBOX, token);
            seated.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            scratch.claims.ownerTaskEnd(WORLD, MAILBOX, token);
        }, "pipeline-task-body");
        body.start();
        assertTrue(seated.await(5, TimeUnit.SECONDS), "the body thread reached its begin");

        assertEquals(1, scratch.slots.slots(),
            "a tokenized span seats its claim on the thread that runs the body");
        assertEquals(1L, scratch.claims.seatedCount());
        assertEquals(0L, scratch.claims.offThreadWindowCount(),
            "a tokenized span is not refused for running off the judged thread");

        assertEquals(1, scratch.guard.classifyBlockWrite(LEVEL, 8, 64, 8),
            "the judged write finds the slot the body declared");
        assertEquals(Verdict.CAPTURED, scratch.slots.lastDecision().verdict(),
            () -> "refusal=" + scratch.slots.lastRefusal());
        assertEquals(1L, scratch.guard.claimedWrites(),
            "the captured path is taken instead of the default path");

        release.countDown();
        body.join(5000L);
        assertFalse(body.isAlive(), "the body thread closed its window");
        assertEquals(0, scratch.slots.slots(), "the token's end retires the slot");
    }

    @Test
    void aTokenizedSpanWithNoClaimIsUnclaimedExactlyLikeTheJudgedThreadPath() {
        Scratch scratch = new Scratch();
        scratch.judge = false;

        scratch.claims.ownerTaskBegin(WORLD, MAILBOX, new Object());
        assertEquals(0, scratch.slots.slots());
        assertEquals(1L, scratch.claims.unclaimedWindowCount(),
            "a token names a body, not a claim: with no declaration it claims nothing");
        assertEquals(0L, scratch.claims.offThreadWindowCount());
        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL));
        assertEquals(1L, scratch.slots.defaultPathCount());
    }

    @Test
    void aDisabledClaimPointStillSeatsNothingForATokenizedSpan() {
        Scratch scratch = new Scratch();
        scratch.judge = false;
        scratch.declare(ROUNDS);
        scratch.claims.refresh(false);

        scratch.claims.ownerTaskBegin(WORLD, MAILBOX, new Object());
        assertEquals(0, scratch.slots.slots(), "the claim point is off, so no window is seated");
        assertEquals(0L, scratch.claims.seatedCount());
        assertEquals(0L, scratch.claims.offThreadWindowCount());
        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL));
        assertEquals(1L, scratch.slots.defaultPathCount(),
            "the write keeps the default path it had");
    }

    @Test
    void aWindowLeftOpenIsRetiredWhenTheTickMovesOn() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        assertEquals(1, scratch.slots.slots());

        scratch.claims.noteTick(TICK + 1);
        assertEquals(1L, scratch.claims.sweptCount());
        assertEquals(0, scratch.slots.slots());
        assertEquals(0, scratch.guard.classifyBlockWrite(LEVEL),
            "a claim the writer never closed does not cover a later tick");
        assertEquals(1L, scratch.slots.defaultPathCount());
    }

    @Test
    void aClaimOfAWorldNoLevelWasReadForClaimsNothing() {
        Scratch scratch = new Scratch();
        scratch.levelPresent = false;
        scratch.declare(ROUNDS);

        assertEquals(0, scratch.claims.active());
        assertEquals(1L, scratch.claims.noWorldCount());
    }

    @Test
    void aWriteSetThatReachesIntoAnotherWorldIsRefusedBeforeItIsClaimed() {
        Scratch scratch = new Scratch();

        JobGraphBuilder.Freeze frozen = scratch.reachingPastItsJob();
        assertFalse(frozen.ok(), "a write set that names another world than its job is refused");
        assertEquals(RejectCode.CROSS_WORLD_WRITE_DENIED, frozen.code());
        assertEquals(0, scratch.claims.active());
    }

    @Test
    void thePartOfAWriteSetThatIsNotItsOwnJobIsNeverClaimed() {
        Scratch scratch = new Scratch();
        scratch.declarePastItsOwnJobByHand();

        assertEquals(1L, scratch.claims.foreignRefCount(),
            "the part of the set that names another world is not claimed");
        List<PipelineWriteClaims.Claim> rows = scratch.claims.claims();
        assertEquals(1, rows.size());
        assertEquals(Set.of(WritePath.BLOCK_WRITE.key()), rows.get(0).writeSet());
        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        assertEquals(1, scratch.slots.slots());
        assertEquals(Verdict.CAPTURED, scratch.judge(WritePath.BLOCK_WRITE.key()),
            "the write of its own world is covered, the other world is not");
    }

    @Test
    void aDemandForAWorldItsOwnJobIsNotInIsRefused() {
        Scratch scratch = new Scratch();
        scratch.declareCrossWorldDemand();

        assertEquals(1L, scratch.grants.refusedCrossWorldCount());
        assertNotNull(scratch.grants.lastRefusal());
        assertEquals(RejectCode.CROSS_WORLD_WRITE_DENIED.text(), scratch.grants.lastRefusal().code());
        assertEquals(OTHER_WORLD, scratch.grants.lastRefusal().worldId());
        assertFalse(scratch.grants.lastRefusal().threadRef().isEmpty());
        assertEquals(TICK, scratch.grants.lastRefusal().tickIndex());
    }

    @Test
    void aClaimWhoseContractFailsIsRefusedAndReadable() {
        assertEquals("version", scratchOf(scratch -> scratch.versions.advance(WORLD, WriteLevel.REGION,
            DOMAIN)));
        assertEquals("version", scratchOf(scratch -> scratch.versions.reset()));
        assertEquals("expiry", scratchOf(scratch -> {
            scratch.guard.refresh(true, false, false, TICK + 41L);
            scratch.tick = TICK + 41L;
        }));
        assertEquals("world", scratchOf(scratch -> scratch.source.world = OTHER_WORLD));
        assertEquals("plan", scratchOf(scratch -> scratch.source.planHolds = false));
        assertEquals("plan_sequence", scratchOf(scratch -> scratch.source.step =
            new WriteControlledSlots.Source.Step(PLAN_SEQUENCE + 1L, 0)));
    }

    @Test
    void aSecondHolderOfTheClaimedDomainIsRefused() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        long version = scratch.versions.carried(WORLD, WriteLevel.REGION, DOMAIN);
        assertTrue(scratch.owners.release(new OwnerRegistry.OwnershipDomain(WORLD, WriteLevel.REGION,
            DOMAIN), HOLDER));
        assertTrue(scratch.owners.acquire(new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, 2L,
            version, 0L, 0L, HolderKind.REGISTERED, OTHER_HOLDER)));

        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        assertEquals(Verdict.REJECTED, scratch.judge(WritePath.BLOCK_WRITE.key()));
        assertEquals("owner", scratch.slots.lastRefusal().reason());
        assertEquals(HOLDER, scratch.slots.lastRefusal().siteId(),
            "the refusal names the holder the slot was declared with");
        assertTrue(scratch.guard.admitBlockWrite(LEVEL, WORLD, null));
        assertEquals(WriteDisposition.DENY, scratch.guard.lastDecision().disposition(),
            "the judged path refuses a write the claim of the domain no longer names");
        assertEquals(RejectCode.VERSION_MISMATCH, scratch.guard.lastDecision().code(),
            "the write path carries no version of its own, so the path face refuses it");
        assertEquals(SITE, scratch.guard.lastDecision().siteId());
        assertEquals(WORLD, scratch.guard.lastDecision().worldId());
        assertEquals(TICK, scratch.guard.lastDecision().tickIndex());
        assertFalse(scratch.guard.lastDecision().threadRef().isEmpty());
        scratch.guard.refresh(true, true, false, TICK);
        assertFalse(scratch.guard.admitBlockWrite(LEVEL, WORLD, null),
            "with the write path enforcing, the refused write does not proceed");
    }

    @Test
    void aPeriodWithoutADeclarationIsNotExecutableRatherThanPassed() {
        Scratch scratch = new Scratch();

        assertEquals(0, scratch.claims.active());
        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        assertEquals(1L, scratch.claims.unclaimedWindowCount());
        assertEquals(0, scratch.slots.slots());
        assertEquals(Verdict.DEFAULT_PATH, scratch.slots.judge(null, WritePath.BLOCK_WRITE.key(),
            TICK).verdict());
        assertEquals("NOT-EXECUTABLE", scratch.slots.lastDecision().reason());
        assertEquals(1L, scratch.slots.notExecutableCount());
        assertEquals(0L, scratch.slots.attemptsCount());
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void aClaimThatLandedNothingIsNotReportedAndOneThatLandedIsReportedOnce() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        List<PipelineWriteClaims.Report> reports = new ArrayList<>();

        assertEquals(0, scratch.claims.reportLanded(reports::add));
        assertTrue(reports.isEmpty());

        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        scratch.claims.noteLanded(scratch.capture(), WORLD);
        scratch.claims.ownerTaskEnd(WORLD, MAILBOX);
        assertEquals(1, scratch.claims.reportLanded(reports::add));
        PipelineWriteClaims.Report report = reports.get(0);
        assertEquals(WORLD, report.worldId());
        assertEquals(DOMAIN, report.domainId());
        assertTrue(report.nodeKey().startsWith(NODE_KEY + "#"), report.nodeKey());
        assertEquals(1, report.rows());
        assertEquals(PLAN_SEQUENCE, report.planSequence(),
            "the report names the plan that declared the span");
        assertEquals(scratch.graph.nodes().get(0).position(), report.position(),
            "and the seat that plan gave the node");
        assertEquals(0L, report.lifecycle(),
            "and the lifecycle that plan was published in");
        assertTrue(report.expectedVersion() > 0L);
        assertFalse(Long.toHexString(report.writeSetDigest()).isEmpty());
        assertEquals(0, scratch.claims.reportLanded(reports::add), "a batch is reported once");
        assertEquals(1L, scratch.claims.reportedBatchCount());
    }

    @Test
    void aBatchThatLandedForANodeThePlanNoLongerCarriesIsCountedAndNotReported() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        scratch.claims.noteLanded(scratch.capture(), WORLD);
        scratch.claims.ownerTaskEnd(WORLD, MAILBOX);
        JobGraph next = JobGraphBuilder.freeze(List.of(), TICK, PLAN_SEQUENCE + 1L, 1L, 16).graph();
        scratch.claims.declare(next, PLAN_SEQUENCE + 1L, TICK);
        List<PipelineWriteClaims.Report> reports = new ArrayList<>();

        assertEquals(0, scratch.claims.reportLanded(reports::add));
        assertTrue(reports.isEmpty());
        assertEquals(1L, scratch.claims.landedWithoutNodeCount());
        assertEquals(1L, scratch.claims.landedWriteCount());
    }

    @Test
    void aLandingIsSettledByThePlanThatAuthorizedItAcrossTheTick() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        scratch.claims.noteLanded(scratch.capture(), WORLD);
        scratch.claims.ownerTaskEnd(WORLD, MAILBOX);
        scratch.claims.noteTick(TICK + 1);
        List<PipelineWriteClaims.Report> reports = new ArrayList<>();

        assertEquals(1, scratch.claims.reportLanded(reports::add));
        assertTrue(reports.get(0).nodeKey().startsWith(NODE_KEY + "#"));
        assertEquals(1, reports.get(0).rows());
        assertEquals(0L, scratch.claims.landedWithoutNodeCount());
        assertEquals(1L, scratch.claims.reportedBatchCount());
    }

    @Test
    void aLandingIsNotAdoptedByTheNextPlanThatReusesItsKey() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        scratch.claims.noteLanded(scratch.capture(), WORLD);
        scratch.claims.ownerTaskEnd(WORLD, MAILBOX);
        scratch.claims.declare(scratch.graph, PLAN_SEQUENCE + 1L, TICK + 1);
        List<PipelineWriteClaims.Report> reports = new ArrayList<>();

        assertEquals(0, scratch.claims.reportLanded(reports::add),
            "the next plan carries the same node key but did not authorize the landing");
        assertTrue(reports.isEmpty());
        assertEquals(1L, scratch.claims.landedWithoutNodeCount(),
            "the landing stays uncarried instead of being put on the newer plan");
    }

    @Test
    void aResetDropsTheSavedPlanAndTheLandingItHeld() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        scratch.claims.noteLanded(scratch.capture(), WORLD);
        scratch.claims.ownerTaskEnd(WORLD, MAILBOX);
        assertEquals(1L, scratch.claims.landedWriteCount());

        scratch.claims.reset();
        assertEquals(0, scratch.claims.active());
        List<PipelineWriteClaims.Report> reports = new ArrayList<>();
        assertEquals(0, scratch.claims.reportLanded(reports::add));
        assertTrue(reports.isEmpty());
        assertEquals(0L, scratch.claims.landedWithoutNodeCount(),
            "a landing reset went with its plan instead of becoming a landing without a node");
        assertEquals(0L, scratch.claims.landedWriteCount());

        scratch.claims.declare(scratch.graph, PLAN_SEQUENCE, TICK);
        assertEquals(0, scratch.claims.reportLanded(reports::add),
            "a plan declared after the reset does not inherit the landing of the old generation");
        assertEquals(0L, scratch.claims.landedWithoutNodeCount());
    }

    @Test
    void theClaimTableNamesNoDomainItWasNotGivenAndNoWildcard() {
        assertFalse(WriteClaimTable.claims("entity"));
        assertTrue(WriteClaimTable.pathsOf("entity").isEmpty());
        assertTrue(WriteClaimTable.claims(DOMAIN));
        assertEquals(Set.of(WritePath.BLOCK_WRITE.key()),
            WriteClaimTable.pathsOf(DOMAIN));
        assertEquals(1, WriteClaimTable.domains().size());
        assertEquals(WriteClaimTable.DOMAINS, WriteClaimTable.domains().size());
        assertEquals(WriteClaimTable.PATHS, WriteClaimTable.pathsOf(DOMAIN).size());
    }

    @Test
    void theReadoutPublishesTheClaimFaceAndItsZeroValues() {
        List<String> export = KernelReadings.export(KernelModule.instance());

        for (String key : List.of("r02.claim.enabled=", "r02.claim.table_domains=",
            "r02.claim.table_paths=", "r02.claim.declared=", "r02.claim.active=",
            "r02.claim.open_windows=", "r02.claim.seated=", "r02.claim.disarmed=",
            "r02.claim.swept=", "r02.claim.refused=", "r02.claim.windows_unclaimed=",
            "r02.claim.windows_off_thread=", "r02.claim.landed_writes=",
            "r02.claim.reported_batches=", "r02.claim.landed_without_node=",
            "r02.claim.unclaimed_domain=", "r02.claim.foreign_ref=",
            "r02.claim.empty_write_set=", "r02.claim.no_mailbox=", "r02.claim.no_demand=",
            "r02.claim.no_world=", "r02.claim.no_version=", "r02.claim.no_epoch=")) {
            assertTrue(export.stream().anyMatch(line -> line.startsWith(key)), "missing " + key);
        }
        assertTrue(export.stream().anyMatch(line -> line.startsWith("write.controlled.captured=")),
            "the claim face keeps the three verdicts of the write face apart from itself");
        assertTrue(export.stream().anyMatch(line -> line.startsWith("commit.entries=")),
            "the landing face keeps its own field");
    }

    /** The two readings answer different questions: one counts the threads that hold a window, the
     * other counts the frames those threads hold. Both are driven against what the bookkeeping holds
     * at that point, so a change that made one of them answer the other's question would fail here. */
    @Test
    void theFrameTotalAndTheWindowCountAreReadApartAndReconciledWithTheBookkeeping() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);

        assertWindowReadings(scratch.claims, 0, 0, "no window is open yet");

        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        assertWindowReadings(scratch.claims, 1, 1, "the span that seated a window holds one frame");

        scratch.claims.ownerTaskBegin(PrtsPipelineRows.UNPLACED_WORLD, "no-such-mailbox");
        assertEquals(1L, scratch.claims.unclaimedWindowCount());
        assertWindowReadings(scratch.claims, 1, 1,
            "a span no declaration covered seats no window and holds no frame");

        scratch.claims.noteTick(TICK + 1);
        assertEquals(1L, scratch.claims.sweptCount(), "the frame left open is swept at the tick");
        assertWindowReadings(scratch.claims, 0, 0, "the sweep takes the frame with the window");

        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        scratch.claims.ownerTaskEnd(WORLD, MAILBOX);
        assertWindowReadings(scratch.claims, 0, 0, "the end of the span drops its frame");

        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        scratch.claims.refresh(false);
        assertWindowReadings(scratch.claims, 0, 0, "turning the window off drops every frame");
        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        assertWindowReadings(scratch.claims, 0, 0, "a window that is off opens no frame");
        scratch.claims.refresh(true);
    }

    /** The row the claim face publishes while the window is on, and its absence while it is off. The
     * key is additive: the count of open windows keeps its own row and its own value in both. */
    @Test
    void theReadoutNamesTheFrameTotalOnlyWhileTheWindowIsOn() {
        KernelModule module = KernelModule.instance();
        module.claims().refresh(false);
        try {
            List<String> off = KernelReadings.export(module);
            assertTrue(off.stream().anyMatch(line -> line.startsWith("r02.claim.open_windows=")),
                "the window count keeps its row while the table is off");
            assertFalse(off.stream().anyMatch(line -> line.startsWith("r02.claim.open_window_frames=")),
                "with the table off no window is open, so the frame total is not published");

            module.claims().refresh(true);
            List<String> on = KernelReadings.export(module);
            assertTrue(on.stream().anyMatch(line -> line.equals("r02.claim.open_window_frames=0")),
                "with the table on the frame total is published, zero included");
            assertTrue(on.stream().anyMatch(line -> line.equals("r02.claim.open_windows=0")),
                "and the window count is published next to it");
        } finally {
            module.claims().refresh(false);
        }
    }

    /** Drives both readings against what the bookkeeping holds: the frame total is the frames the
     * open windows hold, the window count is the threads that hold one. */
    private static void assertWindowReadings(PipelineWriteClaims claims, int frames, int threads,
                                             String where) {
        assertEquals(frames, claims.openWindowFrames(), () -> "frame total after " + where);
        assertEquals(threads, claims.openWindows(), () -> "window count after " + where);
        assertTrue(frames >= threads, () -> "a thread that holds a window holds at least one frame: "
            + where);
        assertEquals(frames == 0, threads == 0, () -> "both readings are zero together: " + where);
    }

    /** Drives one claim whose contract a fixture was moved out of, and answers the reason the write
     * face read. */
    private static String scratchOf(java.util.function.Consumer<Scratch> move) {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        move.accept(scratch);
        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        assertEquals(Verdict.REJECTED, scratch.judge(WritePath.BLOCK_WRITE.key()),
            "a covered write whose contract fails is refused");
        assertEquals(Verdict.REJECTED, scratch.slots.lastDecision().verdict());
        String reason = scratch.slots.lastRefusal().reason();
        assertNotNull(scratch.slots.lastRefusal().writeSetDigest());
        assertFalse(scratch.slots.lastRefusal().writeSetDigest().isEmpty());
        assertEquals(1L, scratch.slots.rejectedCount());
        assertEquals(0L, scratch.slots.capturedCount());
        assertEquals(0L, scratch.slots.defaultPathCount());
        assertTrue(scratch.slots.conservationHolds());
        return reason;
    }

    /** A declaration that carries a range: the read the plan froze out of it places the write set, so
     * the claim is seated and the register point is reached, and the range the slot names is the one
     * the declaration carried. */
    @Test
    void theRangeAFrozenDeclarationCarriesPlacesTheWriteSetAndReachesTheRegisterPoint() {
        Scratch scratch = new Scratch();
        scratch.claims.writeSpaces(KernelWriteRanges.reader());
        scratch.declareCarryingRange();

        List<PipelineWriteClaims.Claim> rows = scratch.claims.claims();
        assertEquals(1, rows.size(), () -> "claims=" + rows);
        assertEquals(SPACE, rows.get(0).space(), "the claim carries the range the declaration named");
        assertEquals(0L, scratch.claims.emptyWriteSetCount(),
            "a placeable range is not an unplaceable write set");

        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        assertEquals(1, scratch.slots.slots(), "the window of the job seats its claim");
        assertEquals(1L, scratch.slots.registeredCount(), "the claim reaches the register point");
        WriteControlledSlots.Slot seated = scratch.capture();
        assertEquals(WriteControlledSlots.SlotKey.segmentOf(SPACE), seated.key().segment(),
            "the slot declares the range it was claimed for");

        scratch.claims.ownerTaskEnd(WORLD, MAILBOX);
        assertEquals(0, scratch.slots.slots());
        assertEquals(1L, scratch.slots.capturedCount());
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void aPlanWithoutATaskBodySeatsItsDeclarationAndCoversTheWriteInItsCell() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        assertEquals(0, scratch.slots.slots(), "no slot before the declaration is seated");

        assertEquals(1, scratch.claims.seatDeclared(),
            "the declared write set is seated from the plan alone");
        assertEquals(1, scratch.slots.slots());
        assertEquals(1L, scratch.slots.registeredCount());
        assertEquals(0L, scratch.claims.seatedCount(), "no task window was needed");

        assertEquals(Verdict.CAPTURED, scratch.judge(WritePath.BLOCK_WRITE.key()));
        assertEquals(1, scratch.slots.lastDecision().covering(),
            "the write has a candidate and one covering range");
        assertFalse(scratch.slots.lastDecision().pathDigest().isEmpty());
        assertEquals(1L, scratch.slots.capturedCount());
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void aWriteOutsideTheDeclaredCellIsNotCapturedWithoutATaskBody() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        scratch.claims.seatDeclared();

        assertEquals(Verdict.DEFAULT_PATH, scratch.slots.judge(LEVEL, WritePath.BLOCK_WRITE.key(),
            WritePosition.at(8, 0, 8), TICK).verdict());
        assertEquals(0L, scratch.slots.capturedCount(), "outside the cell nothing is captured");
        assertEquals(0L, scratch.slots.rejectedCount(), "outside the cell nothing is refused");
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void seatingTheSameDeclarationTwiceKeepsOneSeat() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        assertEquals(1, scratch.claims.seatDeclared());
        assertEquals(1, scratch.claims.seatDeclared(),
            "a repeated declaration of the same plan is idempotent");
        assertEquals(1, scratch.slots.slots(), "no second slot leaks from the repeat");
        assertEquals(Verdict.CAPTURED, scratch.judge(WritePath.BLOCK_WRITE.key()));
    }

    @Test
    void theNextPlanReplacesTheSeatAndTheOldPlanLeavesNoSlot() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        assertEquals(1, scratch.claims.seatDeclared());
        scratch.declareNext(PLAN_SEQUENCE + 1);

        assertEquals(1, scratch.claims.seatDeclared());
        assertEquals(1, scratch.slots.slots(), "the old plan's seat is retired, the new one kept");
        assertEquals(Verdict.CAPTURED, scratch.judge(WritePath.BLOCK_WRITE.key()));
        assertEquals(PLAN_SEQUENCE + 1, scratch.slots.lastDecision().planSequence(),
            "the write is judged against the plan that declared it");
    }

    @Test
    void aTaskWindowOverADeclaredSeatDoesNotRetireIt() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        scratch.claims.seatDeclared();

        scratch.claims.ownerTaskBegin(WORLD, MAILBOX);
        assertEquals(1, scratch.slots.slots(), "the window renews the same seat, not a second one");
        scratch.capture();
        scratch.claims.ownerTaskEnd(WORLD, MAILBOX);
        assertEquals(1, scratch.slots.slots(),
            "the declaration still covers the span after its task window ends");
        assertEquals(0L, scratch.claims.disarmedCount());
        assertEquals(Verdict.CAPTURED, scratch.judge(WritePath.BLOCK_WRITE.key()));
        assertEquals(2L, scratch.slots.capturedCount());
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void aDeclaredSeatExpiresWhenItsWindowCloses() {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        scratch.claims.seatDeclared();
        scratch.tick = TICK + PipelineWriteClaims.HOLD_TICKS;

        assertEquals(Verdict.REJECTED, scratch.judge(WritePath.BLOCK_WRITE.key()));
        assertEquals("expiry", scratch.slots.lastRefusal().reason());
        assertEquals(0L, scratch.slots.capturedCount());
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void aDeclarationThatCannotBeSeatedLeavesNoHalfCoverage() {
        Scratch scratch = new Scratch();
        scratch.claims.writeSpaces(null);
        scratch.declare(ROUNDS);
        assertEquals(0, scratch.claims.active());

        assertEquals(0, scratch.claims.seatDeclared(), "no claim means no seat, never a half one");
        assertEquals(0, scratch.slots.slots());
        assertEquals(Verdict.DEFAULT_PATH, scratch.judge(WritePath.BLOCK_WRITE.key()));
        assertEquals(0L, scratch.slots.capturedCount());
    }

    @Test
    void declaredSeatsServeConcurrentJudgesWithoutTornState() throws Exception {
        Scratch scratch = new Scratch();
        scratch.declare(ROUNDS);
        scratch.claims.seatDeclared();
        int threads = 4;
        int perThread = 100;
        CountDownLatch done = new CountDownLatch(threads);
        List<Throwable> errors = new ArrayList<>();
        List<Thread> started = new ArrayList<>();
        for (int index = 0; index < threads; index++) {
            Thread thread = new Thread(() -> {
                try {
                    for (int n = 0; n < perThread; n++) {
                        Verdict verdict = scratch.slots.judge(LEVEL, WritePath.BLOCK_WRITE.key(),
                            WITNESS, TICK).verdict();
                        if (verdict != Verdict.CAPTURED && verdict != Verdict.REJECTED) {
                            throw new AssertionError("verdict=" + verdict);
                        }
                    }
                } catch (Throwable error) { // noqa: BLE001 - a torn read is exactly what this test hunts
                    synchronized (errors) {
                        errors.add(error);
                    }
                } finally {
                    done.countDown();
                }
            });
            started.add(thread);
        }
        started.forEach(Thread::start);
        assertTrue(done.await(30, TimeUnit.SECONDS), "the judges finish");
        assertTrue(errors.isEmpty(), () -> "errors=" + errors);
        assertTrue(scratch.slots.conservationHolds());
    }

    @Test
    void aSeatedDeclarationAttributesAWriteThroughItsClaimAndTheOwnerOrdinal() {
        Scratch scratch = new Scratch();
        scratch.claims.ledger(scratch.batches);
        scratch.claims.writeSpaces(node -> WriteSpace.of(CellKind.CHUNK, CELL));
        scratch.declare(ROUNDS);
        List<PipelineWriteClaims.Attributed> settled = new ArrayList<>();
        PipelineWriteClaims sink = new PipelineWriteClaims(scratch.versions,
            scratch.guard.worldEpochs(), () -> true, world -> LEVEL, settled::add);
        sink.table(scratch.slots);
        sink.writeSpaces(node -> WriteSpace.of(CellKind.CHUNK, CELL));
        sink.ledger(scratch.batches);
        sink.refresh(true);
        sink.declare(scratch.graph, PLAN_SEQUENCE, TICK);
        assertEquals(1, sink.seatDeclared(), "the declared seat is registered");

        assertEquals(Verdict.CAPTURED, scratch.slots.judge(LEVEL, WritePath.BLOCK_WRITE.key(),
            WITNESS, TICK).verdict());
        WriteControlledSlots.Slot slot = scratch.slots.lastCoveredSlot();
        assertNotNull(slot, "the write is seated against the declared claim");
        WorldWriteGuard.LandedRow row = new WorldWriteGuard.LandedRow(WORLD, DOMAIN,
            slot.planNodeKey(), PLAN_SEQUENCE, slot.position(), slot.expectedVersion(),
            slot.worldEpoch(), TICK, 8, 64, 8);
        sink.noteLanded(slot, WORLD, row);
        List<PipelineWriteClaims.Report> reports = new ArrayList<>();
        assertEquals(1, sink.reportLanded(reports::add));
        assertEquals(1, settled.size(), "the attributed payload reaches the settlement sink");
        PipelineWriteClaims.Attributed payload = settled.get(0);
        assertTrue(payload.attributed(), "a seated declaration and a real row name the batch");
        assertTrue(payload.rowsNamed(), "the row carries its identity");
        assertEquals(1, payload.cells().size(), "one declared cell, never a guessed one");
        PipelineWriteClaims.CellRows cell = payload.cells().get(0);
        assertEquals(CELL, cell.cell(), "the one declared cell the row position covers");
        long owner = scratch.batches.ownerOrdinal(scratch.claims.claims().get(0).batch(), CELL);
        assertEquals(owner, cell.taskOrdinal(), "the published binding's owner ordinal");
        assertEquals(1, cell.rows());
        assertTrue(WorldWriteGuard.LandedRow.rowIdentity(cell.rowContent().get(0)),
            "the row keeps the identity of the write path");
    }

    @Test
    void aDeclaredSlotAttributionFailsClosedOutsideItsRangeOrWithoutARow() {
        Scratch scratch = new Scratch();
        scratch.claims.ledger(scratch.batches);
        scratch.claims.writeSpaces(node -> WriteSpace.of(CellKind.CHUNK, CELL));
        scratch.declare(ROUNDS);
        List<PipelineWriteClaims.Attributed> settled = new ArrayList<>();
        PipelineWriteClaims sink = new PipelineWriteClaims(scratch.versions,
            scratch.guard.worldEpochs(), () -> true, world -> LEVEL, settled::add);
        sink.table(scratch.slots);
        sink.writeSpaces(node -> WriteSpace.of(CellKind.CHUNK, CELL));
        sink.ledger(scratch.batches);
        sink.refresh(true);
        sink.declare(scratch.graph, PLAN_SEQUENCE, TICK);
        assertEquals(1, sink.seatDeclared());
        assertEquals(Verdict.CAPTURED, scratch.slots.judge(LEVEL, WritePath.BLOCK_WRITE.key(),
            WITNESS, TICK).verdict());
        WriteControlledSlots.Slot slot = scratch.slots.lastCoveredSlot();
        assertNotNull(slot);

        WorldWriteGuard.LandedRow outside = new WorldWriteGuard.LandedRow(WORLD, DOMAIN,
            slot.planNodeKey(), PLAN_SEQUENCE, slot.position(), slot.expectedVersion(),
            slot.worldEpoch(), TICK, 200, 64, 200);
        sink.noteLanded(slot, WORLD, outside);
        sink.reportLanded(report -> { });
        assertEquals(1, settled.size());
        assertFalse(settled.get(0).attributed(), "a write outside the declared range is refused");
        assertTrue(settled.get(0).cells().isEmpty(), "no cell is guessed for it");

        sink.noteLanded(slot, WORLD, null);
        sink.reportLanded(report -> { });
        assertEquals(2, settled.size(), "the second landing is reported too");
        assertTrue(settled.get(1).cells().isEmpty(), "a row without identity names no cell");
        assertFalse(settled.get(1).rowsNamed(), "and the rows are not named");
    }

    /** The write paths, the registries and the claim table of one process, wired the way the kernel
     * wires them; only the level of a world and the step of the plan are fixtures. */
    private static final class Scratch {

        private boolean judge = true;
        private boolean levelPresent = true;
        private long tick = TICK;
        private JobGraph graph;

        private final WritePathCounters counters = new WritePathCounters();
        private final WriteLedger ledger = new WriteLedger();
        private final IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        private final OwnerRegistry owners = new OwnerRegistry();
        private final IntentQueue intents = new IntentQueue(() -> 8, () -> 2);
        private final WriteAuthority authority =
            new WriteAuthority(owners, intents, ledger, () -> false, () -> 2);
        private final WorldWriteGuard guard =
            new WorldWriteGuard(counters, authority, intents, payloads, ledger);
        private final WriteVersionSlots versions = new WriteVersionSlots();
        private final OwnerGrantPoint grants = new OwnerGrantPoint(owners, versions);
        private final FixtureSource source = new FixtureSource();
        private final WriteControlledSlots slots =
            new WriteControlledSlots(owners, versions, guard.worldEpochs(), source);
        private final PipelineWriteClaims claims = new PipelineWriteClaims(versions,
            guard.worldEpochs(), () -> judge, world -> levelPresent ? LEVEL : null);
        private final JobIntake intake = new JobIntake(() -> 16);
        private final PipelineBatchLedger batches = new PipelineBatchLedger();
        private final PipelineRoundJobs jobs = new PipelineRoundJobs();

        private Scratch() {
            intents.bindPayload(guard);
            guard.bindServerThread(Thread.currentThread(), SITE);
            guard.versionSlots(versions);
            guard.controlledSlots(slots);
            guard.claimLandedTap((slot, world, tick, row) -> claims.noteLanded(slot, world, row));
            guard.refresh(true, false, false, TICK);
            guard.noteLiveWorlds(List.of(WORLD, OTHER_WORLD));
            versions.refresh(true);
            slots.refresh(true);
            grants.refresh(true);
            claims.table(slots);
            claims.writeSpaces(node -> SPACE);
            claims.refresh(true);
        }

        /** Plans the rounds the pipeline ran and turns the declared write set into a claim, the way
         * the plan freeze does: version first, then the right, then the claim. */
        /** Captures one write of the running job through the claim table itself: the position a
         * write is made at reaches the table with the payload of the judged path, which is wired in
         * a later change, so the range is driven here with an explicit position. */
        private WriteControlledSlots.Slot capture() {
            assertEquals(Verdict.CAPTURED, judge(WritePath.BLOCK_WRITE.key()),
                () -> "refusal=" + slots.lastRefusal());
            return slots.lastCoveredSlot();
        }

        private Verdict judge(String domainKey) {
            return slots.judge(LEVEL, domainKey, WITNESS, tick).verdict();
        }

        private JobGraph declare(long tasks) {
            jobs.bind(batches);
            for (long task = 0; task < tasks; task++) {
                batches.mint(MAILBOX, WORLD, MAILBOX, CELL, TICK);
            }
            jobs.declareInto(intake, PLAN_SEQUENCE, TICK);
            return freeze(intake.take());
        }

        /** The round job of the pipeline declared with the range a producer that read one would hand
         * in: the mailbox face names a world and a mailbox and no cell, so this declaration stands for
         * the shape a range source has to produce, and the fixture freezes it verbatim. */
        private JobGraph declareCarryingRange() {
            JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, DOMAIN, 0);
            JobDeclaration declaration = new JobDeclaration(NODE_KEY, 1L, WORLD, DOMAIN, 0, List.of(),
                0, MAILBOX, "world:" + WORLD, List.of(ref), List.of(ref), ShareClass.OTHER,
                JobDeclaration.SiteClass.PARALLEL, 0, (int) ROUNDS,
                List.of(new JobDeclaration.OwnerDemand(WORLD, WriteLevel.REGION, DOMAIN,
                    HolderKind.REGISTERED, HOLDER, 40)),
                new JobDeclaration.WriteRange(JobDeclaration.RangeGranularity.SECTION,
                    new long[] {SectionPos.asLong(0, 4, 0)}));
            return freeze(List.of(declaration));
        }

        private JobGraph declareForeignDomain() {
            JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, "entity", 0);
            JobDeclaration declaration = new JobDeclaration("entity/" + WORLD + "/r.0.0", 1L, WORLD,
                "entity", 0, List.of(), 0, "world:" + WORLD, "world:" + WORLD, List.of(ref),
                List.of(ref), ShareClass.OTHER, JobDeclaration.SiteClass.PARALLEL, 0, 4,
                List.of(new JobDeclaration.OwnerDemand(WORLD, WriteLevel.REGION, "entity",
                    HolderKind.REGISTERED, "domain:entity", 40)));
            return freeze(List.of(declaration));
        }

        /** A declaration whose write set names another world than its own job: the planner refuses
         * the freeze instead of freezing a job that reaches past itself. */
        private JobGraphBuilder.Freeze reachingPastItsJob() {
            return JobGraphBuilder.freeze(List.of(declarationPastItsOwnJob()), TICK, PLAN_SEQUENCE,
                1L, 16);
        }

        /** The same shape, declared straight into the claim point: a graph no planner would freeze,
         * so the claim face is driven with it to show what it refuses on its own. */
        private JobGraph declarePastItsOwnJobByHand() {
            JobDeclaration.DomainRef mine = new JobDeclaration.DomainRef(WORLD, DOMAIN, 0);
            JobDeclaration.DomainRef other = new JobDeclaration.DomainRef(OTHER_WORLD, DOMAIN, 0);
            JobGraph.Node node = new JobGraph.Node(1L, NODE_KEY, 1L, WORLD, DOMAIN, 0, List.of(),
                List.of(), 0, "world:" + WORLD, "world:" + WORLD, List.of(mine),
                List.of(mine, other), declarationPastItsOwnJob().ownerDemands(), ShareClass.OTHER,
                JobDeclaration.SiteClass.PARALLEL, 0, 4, false, 0);
            JobGraph graph = new JobGraph(TICK, PLAN_SEQUENCE, 1L, List.of(node), List.of(1L),
                List.of(1L), List.of(), 16, 0);
            graph = publish(graph);
            return graph;
        }

        private static JobDeclaration declarationPastItsOwnJob() {
            JobDeclaration.DomainRef mine = new JobDeclaration.DomainRef(WORLD, DOMAIN, 0);
            JobDeclaration.DomainRef other = new JobDeclaration.DomainRef(OTHER_WORLD, DOMAIN, 0);
            return new JobDeclaration(NODE_KEY, 1L, WORLD, DOMAIN, 0, List.of(), 0,
                MAILBOX, "world:" + WORLD, List.of(mine), List.of(mine, other),
                ShareClass.OTHER, JobDeclaration.SiteClass.PARALLEL, 0, 4,
                List.of(new JobDeclaration.OwnerDemand(WORLD, WriteLevel.REGION, DOMAIN,
                    HolderKind.REGISTERED, HOLDER, 40)));
        }

        private JobGraph declareCrossWorldDemand() {
            JobDeclaration.DomainRef mine = new JobDeclaration.DomainRef(WORLD, DOMAIN, 0);
            JobDeclaration declaration = new JobDeclaration(NODE_KEY, 1L, WORLD, DOMAIN, 0, List.of(),
                0, "world:" + WORLD, "world:" + WORLD, List.of(mine), List.of(mine),
                ShareClass.OTHER, JobDeclaration.SiteClass.PARALLEL, 0, 4,
                List.of(new JobDeclaration.OwnerDemand(OTHER_WORLD, WriteLevel.REGION, DOMAIN,
                    HolderKind.REGISTERED, HOLDER, 40)));
            return freeze(List.of(declaration));
        }

        private JobGraph freeze(List<JobDeclaration> declarations) {
            JobGraphBuilder.Freeze frozen = JobGraphBuilder.freeze(declarations, TICK, PLAN_SEQUENCE,
                1L, 16);
            assertTrue(frozen.ok(), () -> "freeze refused: " + frozen.code() + " " + frozen.item());
            return publish(frozen.graph());
        }

        /** A next plan over the same declaration, as the next freeze would publish it. */
        private JobGraph declareNext(long planSequence) {
            JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, DOMAIN, 0);
            JobDeclaration declaration = new JobDeclaration(NODE_KEY, 1L, WORLD, DOMAIN, 0, List.of(),
                0, MAILBOX, "world:" + WORLD, List.of(ref), List.of(ref), ShareClass.OTHER,
                JobDeclaration.SiteClass.PARALLEL, 0, (int) ROUNDS,
                List.of(new JobDeclaration.OwnerDemand(WORLD, WriteLevel.REGION, DOMAIN,
                    HolderKind.REGISTERED, HOLDER, 40)));
            JobGraphBuilder.Freeze frozen = JobGraphBuilder.freeze(List.of(declaration), TICK,
                planSequence, 1L, 16);
            assertTrue(frozen.ok(), () -> "freeze refused: " + frozen.code() + " " + frozen.item());
            return publishAs(frozen.graph(), planSequence);
        }

        /** Grants the version of every declared write domain and publishes what those grants
         * answered as this plan's record, then hands the graph to the grant point and turns the
         * declared write sets into the claims of the next span. */
        private JobGraph publish(JobGraph frozen) {
            return publishAs(frozen, PLAN_SEQUENCE);
        }

        private JobGraph publishAs(JobGraph frozen, long planSequence) {
            graph = frozen;
            Map<OwnershipDomain, Long> granted = new HashMap<>();
            for (JobGraph.Node node : graph.nodes()) {
                for (JobDeclaration.DomainRef ref : node.writeSet()) {
                    OwnershipDomain domain =
                        new OwnershipDomain(ref.worldId(), WriteLevel.REGION, ref.domainId());
                    granted.put(domain,
                        versions.grant(ref.worldId(), WriteLevel.REGION, ref.domainId()));
                }
            }
            versions.noteGranted(planSequence, granted);
            grants.apply(graph, TICK);
            claims.declare(graph, planSequence, TICK);
            if (!graph.nodes().isEmpty()) {
                source.step = new WriteControlledSlots.Source.Step(planSequence,
                    graph.nodes().get(0).position());
            }
            return graph;
        }

        /** The claim source a deployment would wire: which world a level is, and which step the plan
         * holds for a claim. Both answers can be moved to drive the contract terms. */
        private static final class FixtureSource implements WriteControlledSlots.Source {

            private String world = WORLD;
            private boolean planHolds = true;
            private Step step = null;

            @Override
            public String worldOf(Object levelRef) {
                return levelRef == LEVEL ? world : "";
            }

            @Override
            public Step stepOf(String worldId, String domainId, String nodeKey) {
                return planHolds && nodeKey.startsWith(NODE_KEY) ? step : null;
            }
        }
    }
}
