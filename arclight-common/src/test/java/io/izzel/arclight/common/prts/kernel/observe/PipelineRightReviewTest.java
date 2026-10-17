/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.OwnerGrantPoint;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteAuthority;
import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.RejectTrigger;
import io.izzel.arclight.common.prts.kernel.intent.CommitSegment;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.jobs.JobIntake;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineBatchLedger;
import io.izzel.arclight.common.prts.kernel.jobs.PipelineRoundJobs;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.sites.IntentPayloadDirectory;
import io.izzel.arclight.common.prts.kernel.sites.WritePath;
import io.izzel.arclight.common.prts.kernel.sites.WritePathCounters;
import io.izzel.arclight.common.prts.kernel.sites.WorldWriteGuard;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The write right a job of the chunk pipeline holds, reviewed against the declaration that produced
 * it: which credential is in place, whether it names the version of its slot, whether the domains
 * held are covered by the declared write sets, and what a demand the point refuses leaves behind. */
class PipelineRightReviewTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String OTHER_WORLD = "minecraft:the_nether";
    private static final String SITE = PipelineRoundJobs.OWNER_SITE;
    private static final String OTHER_SITE = "domain:other";
    private static final String WRITE_SITE = "site:worker";
    private static final long TICK = 100L;
    private static final long HOST_WRITES = 17_327L;
    private static final Object LEVEL = new Object();

    @Test
    void aRightOfThePipelineIsHeldAndNamesTheVersionOfItsSlot() {
        Fixture fixture = new Fixture(true);
        fixture.declareOfThePipeline("light", 3L);

        PipelineRightReview.Review review = fixture.review();
        assertTrue(review.activeFromDeclaration(), "the credential is the one the declaration produced");
        assertTrue(review.versionMatchesSlot(), "the version it names is the one the slot carries");
        assertTrue(review.observedWithinDeclared(), "the domain held is covered by the declared set");
        assertTrue(review.conservationHolds());
        assertTrue(review.executable());

        assertEquals("1", fixture.line("r02.rights.active"));
        assertEquals("1", fixture.line("r02.rights.sole_path_ok"));
        assertEquals("0", fixture.line("r02.rights.demands_outside_declared"));
        assertEquals("1", fixture.line("r02.rights.declared_keys"));
        assertEquals("1", fixture.line("r02.rights.observed_keys"));
        assertEquals(fixture.line("r02.rights.declared_digest"),
            fixture.line("r02.rights.observed_digest"), "the held set is the declared set");
        assertEquals("0", fixture.line("r02.rights.version_mismatch"));
        assertEquals("1", fixture.line("r02.rights.rows"));

        String row = fixture.line("r02.right.1");
        assertTrue(row.startsWith("world=" + WORLD + "|level=REGION|domain="
            + PipelineRoundJobs.DOMAIN + "|holder=" + SITE + "|"), row);
        assertTrue(row.contains("expected_version=1|slot_version=1|version_match=1"), row);
        assertTrue(row.contains("in_declared=1"), row);
        assertTrue(row.contains("declared_write_set=" + WORLD + "/" + PipelineRoundJobs.DOMAIN + "@0"),
            row);
        assertTrue(row.contains("declaring_nodes=" + PipelineRoundJobs.keyOf(WORLD, "light")), row);
        assertTrue(row.contains("declaring_demands=1|declaring_outside_declared=0"), row);
    }

    @Test
    void aPeriodWithoutADeclarationIsNotExecutableRatherThanPassed() {
        Fixture fixture = new Fixture(true);
        fixture.freeze();

        PipelineRightReview.Review review = fixture.review();
        assertFalse(review.executable(), "an empty input is not a gate that passed");
        assertFalse(review.activeFromDeclaration());
        assertFalse(review.versionMatchesSlot());
        assertFalse(review.observedWithinDeclared());
        assertTrue(review.conservationHolds());
        assertEquals("1", fixture.line("r02.rights.not_executable"));
        assertEquals("0", fixture.line("r02.rights.declared_keys"));
        assertEquals("0", fixture.line("r02.rights.rows"));
    }

    @Test
    void aDemandPastTheWriteSetOfItsOwnJobIsRefusedAndPlaced() {
        Fixture fixture = new Fixture(true);
        fixture.freeze(declaration("chunk-pipeline/job/0", "other", 40));

        PipelineRightReview.Review review = fixture.review();
        assertEquals(0, fixture.owners.activeTokens(), "a right past the declaration is not minted");
        assertEquals(1L, fixture.grants.refusedUndeclaredCount());
        assertFalse(review.observedWithinDeclared());
        assertEquals("1", fixture.line("r02.rights.demands_outside_declared"));
        assertEquals("0", fixture.line("r02.rights.subset_ok"));
        assertRefusal(fixture, RejectCode.WRITE_DENIED_NOT_OWNER, RejectTrigger.DOMAIN_SET_NOT_SPLIT,
            WORLD);
    }

    @Test
    void aDomainWithoutAVersionIsRefusedAndPlaced() {
        Fixture fixture = new Fixture(true, false);
        fixture.declareOfThePipeline("light", 3L);

        assertEquals(0, fixture.owners.activeTokens());
        assertEquals(1L, fixture.grants.refusedNoVersionCount());
        assertFalse(fixture.review().versionMatchesSlot(), "no version means no right to name one");
        assertRefusal(fixture, RejectCode.VERSION_MISMATCH, RejectTrigger.VERSION_SLOT_MISMATCH, WORLD);
    }

    @Test
    void aDemandWhoseHoldWindowIsAlreadyOverIsRefusedAndPlaced() {
        Fixture fixture = new Fixture(true);
        fixture.freeze(declaration("chunk-pipeline/job/0", PipelineRoundJobs.DOMAIN, 0));

        assertEquals(0, fixture.owners.activeTokens());
        assertEquals(1L, fixture.grants.refusedExpiredCount());
        assertRefusal(fixture, RejectCode.VERSION_MISMATCH, RejectTrigger.TOKEN_EXPIRED, WORLD);
    }

    @Test
    void aDemandForAnotherWorldThanItsJobIsRefusedAndPlaced() {
        Fixture fixture = new Fixture(true);
        fixture.freeze(declarationOfAnotherWorld());

        assertEquals(0, fixture.owners.activeTokens());
        assertEquals(1L, fixture.grants.refusedCrossWorldCount());
        assertTrue(fixture.owners.lookup(OTHER_WORLD, WriteLevel.REGION, PipelineRoundJobs.DOMAIN)
            .isEmpty());
        assertRefusal(fixture, RejectCode.CROSS_WORLD_WRITE_DENIED,
            RejectTrigger.CROSS_WORLD_DIRECT_WRITE, OTHER_WORLD);
    }

    @Test
    void aSecondHolderOfTheSameDomainIsRefusedAndPlaced() {
        Fixture fixture = new Fixture(true);
        fixture.freeze(pipelineDeclaration("light", SITE), pipelineDeclaration("sorter", OTHER_SITE));

        assertEquals(1, fixture.owners.activeTokens());
        assertEquals(1L, fixture.grants.refusedDoubleHolderCount());
        assertRefusal(fixture, RejectCode.WRITE_DENIED_NOT_OWNER, RejectTrigger.OWNER_MISMATCH, WORLD,
            OTHER_SITE);
        assertEquals(SITE, fixture.owners.lookup(WORLD, WriteLevel.REGION, PipelineRoundJobs.DOMAIN)
            .orElseThrow().holderSiteId(), "the first holder keeps the domain");
    }

    @Test
    void aRightWhoseVersionTheSlotHasRetiredIsReported() {
        Fixture fixture = new Fixture(true);
        fixture.declareOfThePipeline("light", 1L);
        long held = fixture.versions.peek(WORLD, WriteLevel.REGION, PipelineRoundJobs.DOMAIN);
        long retired = fixture.versions.advance(WORLD, WriteLevel.REGION, PipelineRoundJobs.DOMAIN);

        PipelineRightReview.Review review = fixture.review();
        assertFalse(review.versionMatchesSlot(), "the slot moved on past the version the right names");
        assertEquals("1", fixture.line("r02.rights.version_mismatch"));
        assertEquals("0", fixture.line("r02.rights.version_ok"));
        assertEquals(held, fixture.owners.lookup(WORLD, WriteLevel.REGION, PipelineRoundJobs.DOMAIN)
            .orElseThrow().expectedVersion(), "the right in place keeps the value it was minted with");
        assertEquals(held + 1L, retired, "the retired value can never be minted again");
        assertTrue(retired > held);
    }

    @Test
    void aWriteThatNamesAVersionOtherThanItsSlotsIsRefusedAndPlaced() {
        Fixture fixture = new Fixture(true);
        long slot = fixture.versions.grant(WORLD, WriteLevel.REGION, WritePath.BLOCK_WRITE.key());
        assertTrue(fixture.owners.acquire(new OwnerToken(WORLD, WriteLevel.REGION,
            WritePath.BLOCK_WRITE.key(), 7L, WriteVersionSlots.NOT_CARRIED, TICK, TICK + 100L,
            HolderKind.REGISTERED, WRITE_SITE)));

        fixture.probe();

        WorldWriteGuard.WriteDecision decision = fixture.guard.lastDecision();
        assertNotNull(decision);
        assertEquals(RejectCode.VERSION_MISMATCH, decision.code());
        assertEquals(WRITE_SITE, decision.siteId());
        assertEquals(WORLD, decision.worldId());
        assertEquals(TICK, decision.tickIndex());
        assertFalse(decision.threadRef().isEmpty());
        assertTrue(slot > WriteVersionSlots.NOT_CARRIED, "the slot the attempt reads carries a value");
    }

    @Test
    void theThreeBalancesOfTheRightRegistryClose() {
        Fixture fixture = new Fixture(true);
        fixture.declareOfThePipeline("light", 2L);
        fixture.applyAgain();
        fixture.freeze(pipelineDeclaration("light", SITE), pipelineDeclaration("sorter", OTHER_SITE));

        OwnerRegistry owners = fixture.owners;
        assertEquals(owners.activeTokens(),
            owners.acquiredCount() - owners.releasedCount() - owners.expiredReclaimedCount(),
            "a right is active exactly while it was acquired and neither released nor reclaimed");
        assertEquals(owners.acquireCalls(),
            owners.acquiredCount() + owners.reacquiredCount() + owners.doubleHolderCount(),
            "every acquisition leaves by one of the three counts");
        assertEquals(owners.releaseCalls(),
            owners.releasedCount() + owners.releaseRefusedCount(),
            "every release leaves by one of the two counts");
        assertTrue(owners.doubleHolderCount() > 0L, "the second holder of the domain was refused");
        assertTrue(owners.reacquiredCount() > 0L, "the holder that asked again is no second");
        assertTrue(owners.conservationHolds());
        assertEquals("1", fixture.review().lines().stream()
            .filter(line -> line.startsWith("r02.rights.conservation_ok="))
            .findFirst().orElseThrow().substring("r02.rights.conservation_ok=".length()));
    }

    @Test
    void theReadoutPublishesTheReviewKeys() {
        List<String> export = KernelReadings.export(KernelModule.instance());
        for (String key : List.of("r02.rights.enabled=", "r02.rights.active=", "r02.rights.held=",
            "r02.rights.declared_count=", "r02.rights.granted_count=", "r02.rights.released_by_plan=",
            "r02.rights.refused=", "r02.rights.demands_in_plan=",
            "r02.rights.demands_outside_declared=", "r02.rights.declared_keys=",
            "r02.rights.declared_digest=", "r02.rights.observed_keys=", "r02.rights.observed_digest=",
            "r02.rights.observed_outside_declared=", "r02.rights.version_match=",
            "r02.rights.version_mismatch=", "r02.rights.subset_ok=", "r02.rights.version_ok=",
            "r02.rights.sole_path_ok=", "r02.rights.conservation_ok=",
            "r02.rights.host_writes_observed=", "r02.rights.claim_slots=",
            "r02.rights.physical_attribution_available=", "r02.rights.not_executable=",
            "r02.rights.rows=", "token.acquire_calls=", "token.release_calls=",
            "token.conservation_ok=")) {
            assertTrue(export.stream().anyMatch(line -> line.startsWith(key)), key);
        }
    }

    /** The five pieces of a refusal: the code, the site, the thread, the world and the tick. */
    private static void assertRefusal(Fixture fixture, RejectCode code, RejectTrigger trigger,
                                      String worldId) {
        assertRefusal(fixture, code, trigger, worldId, SITE);
    }

    private static void assertRefusal(Fixture fixture, RejectCode code, RejectTrigger trigger,
                                      String worldId, String siteId) {
        RejectTrigger.Diag5 refusal = fixture.grants.lastRefusal();
        assertNotNull(refusal, "a refusal that cannot be placed is not a refusal");
        assertEquals(code.name(), refusal.code());
        assertEquals(trigger, fixture.grants.lastRefusalTrigger());
        assertEquals(siteId, refusal.siteId());
        assertEquals(worldId, refusal.worldId());
        assertEquals(TICK, refusal.tickIndex());
        assertFalse(refusal.threadRef().isEmpty());
        assertTrue(fixture.owners.conservationHolds(), "the balance closes over a refusal too");
    }

    private static JobDeclaration declaration(String key, String writeDomain, int holdTicks) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, writeDomain, 0);
        JobDeclaration.OwnerDemand demand = new JobDeclaration.OwnerDemand(WORLD, WriteLevel.REGION,
            PipelineRoundJobs.DOMAIN, HolderKind.REGISTERED, SITE, holdTicks);
        return new JobDeclaration(key, 1L, WORLD, PipelineRoundJobs.DOMAIN, 0, List.of(), 0, key,
            "world:" + WORLD, List.of(ref), List.of(ref), ShareClass.OTHER,
            JobDeclaration.SiteClass.PARALLEL, 0, 4, List.of(demand));
    }

    private static JobDeclaration declarationOfAnotherWorld() {
        JobDeclaration.DomainRef ref =
            new JobDeclaration.DomainRef(WORLD, PipelineRoundJobs.DOMAIN, 0);
        JobDeclaration.OwnerDemand demand = new JobDeclaration.OwnerDemand(OTHER_WORLD,
            WriteLevel.REGION, PipelineRoundJobs.DOMAIN, HolderKind.REGISTERED, SITE, 40);
        return new JobDeclaration("chunk-pipeline/job/0", 1L, WORLD, PipelineRoundJobs.DOMAIN, 0,
            List.of(), 0, "job", "world:" + WORLD, List.of(ref), List.of(ref), ShareClass.OTHER,
            JobDeclaration.SiteClass.PARALLEL, 0, 4, List.of(demand));
    }

    private static JobDeclaration pipelineDeclaration(String mailbox, String site) {
        JobDeclaration.DomainRef ref =
            new JobDeclaration.DomainRef(WORLD, PipelineRoundJobs.DOMAIN, 0);
        JobDeclaration.OwnerDemand demand = new JobDeclaration.OwnerDemand(WORLD, WriteLevel.REGION,
            PipelineRoundJobs.DOMAIN, HolderKind.REGISTERED, site, 40);
        return new JobDeclaration(PipelineRoundJobs.keyOf(WORLD, mailbox), mailbox.hashCode(), WORLD,
            PipelineRoundJobs.DOMAIN, 0, List.of(), 0, mailbox, "world:" + WORLD, List.of(ref),
            List.of(ref), ShareClass.OTHER, JobDeclaration.SiteClass.PARALLEL, 0, 4, List.of(demand));
    }

    /** One process with the registries the right lives in, the point that turns a frozen declaration
     * into a credential, and the write paths the kernel wires them into. */
    private static final class Fixture {

        private final OwnerRegistry owners = new OwnerRegistry();
        private final WriteVersionSlots versions = new WriteVersionSlots();
        private final OwnerGrantPoint grants = new OwnerGrantPoint(owners, versions);
        private final WritePathCounters counters = new WritePathCounters();
        private final WriteLedger ledger = new WriteLedger();
        private final IntentPayloadDirectory payloads = new IntentPayloadDirectory();
        private final IntentQueue intents = new IntentQueue(() -> 8, () -> 2);
        private final CommitSegment segment = new CommitSegment(intents, () -> true, intents::capacity);
        private final WriteAuthority authority =
            new WriteAuthority(owners, intents, ledger, () -> false, () -> 2);
        private final WorldWriteGuard guard =
            new WorldWriteGuard(counters, authority, intents, payloads, ledger);

        private TickPlan plan;
        private PipelineRightReview.Review reading;

        private Fixture(boolean enabled) {
            this(enabled, true);
        }

        private Fixture(boolean enabled, boolean withSlots) {
            versions.refresh(withSlots);
            grants.refresh(enabled);
            segment.bindOwnerThread(Thread.currentThread());
            intents.bindPayload(guard);
            guard.bindServerThread(Thread.currentThread(), "host:server-thread");
            guard.versionSlots(versions);
            guard.refresh(true, false, false, TICK);
        }

        /** The tasks submitted on one mailbox become the batch the plan of this tick freezes,
         * exactly as the chunk pipeline declares them. */
        private JobGraph declareOfThePipeline(String mailbox, long tasks) {
            PipelineBatchLedger ledger = new PipelineBatchLedger();
            PipelineRoundJobs jobs = new PipelineRoundJobs();
            jobs.bind(ledger);
            JobIntake intake = new JobIntake(() -> 64);
            long cell = net.minecraft.world.level.ChunkPos.asLong(0, 0);
            for (long task = 0L; task < tasks; task++) {
                ledger.mint(mailbox, WORLD, mailbox, cell, TICK);
            }
            jobs.declareInto(intake, 1L, TICK);
            JobDeclaration[] declared = intake.take().toArray(new JobDeclaration[0]);
            freeze(declared);
            return plan.graph();
        }

        /** The same plan frozen again: the declaration is still there, so the point asks for the
         * domain the holder already holds. */
        private void applyAgain() {
            grants.apply(plan.graph(), TICK + 1L);
        }

        private void freeze(JobDeclaration... declarations) {
            JobGraphBuilder.Freeze frozen =
                JobGraphBuilder.freeze(List.of(declarations), TICK, 1L, 1L, 8);
            assertTrue(frozen.ok(), () -> "the freeze was refused: " + frozen.code());
            JobGraph graph = frozen.graph();
            assertNotNull(graph);
            for (JobGraph.Node node : graph.nodes()) {
                for (JobDeclaration.DomainRef ref : node.writeSet()) {
                    versions.grant(ref.worldId(), WriteLevel.REGION, ref.domainId());
                }
            }
            grants.apply(graph, TICK);
            plan = new TickPlan(TICK, 1L, 1L, List.of(WORLD), graph, List.of(), List.of(), null,
                List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
        }

        private PipelineRightReview.Review review() {
            reading = PipelineRightReview.of(plan, owners, versions, grants, HOST_WRITES, 0);
            return reading;
        }

        private String line(String prefix) {
            for (String line : reading.lines()) {
                if (line.startsWith(prefix + "=")) {
                    return line.substring(prefix.length() + 1);
                }
            }
            throw new AssertionError("no line starts with " + prefix);
        }

        /** One judged write of the holder the right was declared for. */
        private void probe() {
            Thread thread = new Thread(() -> guard.admitBlockWrite(LEVEL, WORLD, null),
                "worker-under-test");
            guard.registerHolder(thread, HolderKind.REGISTERED, WRITE_SITE);
            thread.start();
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
        }
    }
}
