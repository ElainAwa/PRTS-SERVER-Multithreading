/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

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
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
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

/** The write right a frozen job declares: how it becomes a token, how the four counts of the
 * registry stay in balance while it is held and released, and what a demand the point refuses leaves
 * behind. */
class OwnerGrantPointTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String OTHER_WORLD = "minecraft:the_nether";
    private static final String DOMAIN = "entity";
    private static final String SITE = "domain:entity";
    private static final String OTHER_SITE = "domain:other";
    private static final long TICK = 10L;
    private static final Object LEVEL = new Object();

    @Test
    void aDeclaredRightBecomesTheTokenOfItsHolder() {
        Fixture fixture = new Fixture(true);
        fixture.apply(fixture.graph(declaration("a/0", List.of(demand(SITE, 40)))), TICK);

        assertEquals(1, fixture.owners.activeTokens());
        assertEquals(1L, fixture.owners.acquiredCount());
        assertEquals(1L, fixture.grants.declaredCount());
        assertEquals(1L, fixture.grants.grantedCount());
        assertTrue(fixture.owners.conservationHolds());

        OwnerToken token = fixture.owners.lookup(WORLD, WriteLevel.REGION, DOMAIN).orElseThrow();
        assertEquals(WORLD, token.worldId());
        assertEquals(WriteLevel.REGION, token.level(), "the level the plan keeps the version at");
        assertEquals(DOMAIN, token.domainId());
        assertEquals(HolderKind.REGISTERED, token.holderKind());
        assertEquals(SITE, token.holderSiteId());
        assertEquals(TICK, token.holdStartTick());
        assertEquals(TICK + 40L, token.expireTick());
        assertTrue(token.expectedVersion() > WriteVersionSlots.NOT_CARRIED,
            "the token names the version the slot of its domain granted");
        assertFalse(token.expiredAt(TICK + 39L));
        assertTrue(token.expiredAt(TICK + 40L));
    }

    @Test
    void theRightIsHeldWhileTheJobIsDeclaredAndReleasedWhenThePlanDropsIt() {
        Fixture fixture = new Fixture(true);
        JobGraph plan = fixture.graph(declaration("a/0", List.of(demand(SITE, 40))));
        fixture.apply(plan, TICK);
        fixture.apply(plan, TICK + 1L);

        assertEquals(1, fixture.owners.activeTokens(), "the same holder asking again is no second");
        assertEquals(1L, fixture.owners.acquiredCount());
        assertEquals(1L, fixture.owners.reacquiredCount());
        assertEquals(0L, fixture.owners.doubleHolderCount());
        assertTrue(fixture.owners.conservationHolds());

        fixture.apply(fixture.graph(), TICK + 2L);

        assertEquals(0, fixture.owners.activeTokens());
        assertEquals(1L, fixture.owners.releasedCount());
        assertEquals(1L, fixture.grants.releasedByPlanCount());
        assertTrue(fixture.owners.conservationHolds());
    }

    @Test
    void theHoldWindowIsRenewedByTheNextDeclarationAfterTheSweep() {
        Fixture fixture = new Fixture(true);
        JobGraph plan = fixture.graph(declaration("a/0", List.of(demand(SITE, 40))));
        fixture.apply(plan, TICK);

        assertEquals(1L, fixture.owners.reclaimExpired(TICK + 40L));
        assertEquals(0, fixture.owners.activeTokens());
        assertTrue(fixture.owners.conservationHolds());

        fixture.apply(plan, TICK + 41L);

        assertEquals(2L, fixture.owners.acquiredCount());
        assertEquals(1, fixture.owners.activeTokens());
        assertEquals(TICK + 41L + 40L,
            fixture.owners.lookup(WORLD, WriteLevel.REGION, DOMAIN).orElseThrow().expireTick());
        assertTrue(fixture.owners.conservationHolds());
    }

    @Test
    void aSecondHolderOfTheSameDomainIsRefusedAndPlaced() {
        Fixture fixture = new Fixture(true);
        fixture.apply(fixture.graph(declaration("a/0", List.of(demand(SITE, 40))),
            declaration("a/1", List.of(demand(OTHER_SITE, 40)))), TICK);

        assertEquals(1, fixture.owners.activeTokens());
        assertEquals(1L, fixture.owners.doubleHolderCount());
        assertEquals(1L, fixture.grants.refusedDoubleHolderCount());
        assertEquals(1L, fixture.grants.refusedCount());
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER.name(), fixture.grants.lastRefusal().code());
        assertEquals(RejectTrigger.OWNER_MISMATCH, fixture.grants.lastRefusalTrigger());
        assertEquals(OTHER_SITE, fixture.grants.lastRefusal().siteId());
        assertEquals(WORLD, fixture.grants.lastRefusal().worldId());
        assertEquals(TICK, fixture.grants.lastRefusal().tickIndex());
        assertFalse(fixture.grants.lastRefusal().threadRef().isEmpty());
        assertEquals(SITE, fixture.owners.lookup(WORLD, WriteLevel.REGION, DOMAIN).orElseThrow()
            .holderSiteId(), "the first holder keeps the domain");
        assertTrue(fixture.owners.conservationHolds());
    }

    @Test
    void aDemandWhoseHoldWindowIsAlreadyOverIsRefusedAndPlaced() {
        Fixture fixture = new Fixture(true);
        fixture.apply(fixture.graph(declaration("a/0", List.of(demand(SITE, 0)))), TICK);

        assertEquals(0, fixture.owners.activeTokens(), "no credential was minted");
        assertEquals(1L, fixture.grants.refusedExpiredCount());
        assertEquals(RejectCode.VERSION_MISMATCH.name(), fixture.grants.lastRefusal().code());
        assertEquals(RejectTrigger.TOKEN_EXPIRED, fixture.grants.lastRefusalTrigger());
        assertEquals(SITE, fixture.grants.lastRefusal().siteId());
        assertEquals(TICK, fixture.grants.lastRefusal().tickIndex());
        assertTrue(fixture.owners.conservationHolds());
    }

    @Test
    void aDemandForAnotherWorldThanItsJobIsRefusedAndPlaced() {
        Fixture fixture = new Fixture(true);
        JobDeclaration.OwnerDemand cross = new JobDeclaration.OwnerDemand(OTHER_WORLD,
            WriteLevel.REGION, DOMAIN, HolderKind.REGISTERED, SITE, 40);
        fixture.apply(fixture.graph(declaration("a/0", List.of(cross))), TICK);

        assertEquals(0, fixture.owners.activeTokens());
        assertTrue(fixture.owners.lookup(OTHER_WORLD, WriteLevel.REGION, DOMAIN).isEmpty());
        assertEquals(1L, fixture.grants.refusedCrossWorldCount());
        assertEquals(RejectCode.CROSS_WORLD_WRITE_DENIED.name(), fixture.grants.lastRefusal().code());
        assertEquals(RejectTrigger.CROSS_WORLD_DIRECT_WRITE, fixture.grants.lastRefusalTrigger());
        assertEquals(OTHER_WORLD, fixture.grants.lastRefusal().worldId());
        assertEquals(TICK, fixture.grants.lastRefusal().tickIndex());
        assertTrue(fixture.owners.conservationHolds());
    }

    @Test
    void aDemandWhoseDomainHoldsNoVersionIsRefusedAndPlaced() {
        Fixture fixture = new Fixture(true, false);
        fixture.apply(fixture.graph(declaration("a/0", List.of(demand(SITE, 40)))), TICK);

        assertEquals(0, fixture.owners.activeTokens());
        assertEquals(1L, fixture.grants.refusedNoVersionCount());
        assertEquals(RejectCode.VERSION_MISMATCH.name(), fixture.grants.lastRefusal().code());
        assertEquals(RejectTrigger.VERSION_SLOT_MISMATCH, fixture.grants.lastRefusalTrigger());
        assertEquals(WORLD, fixture.grants.lastRefusal().worldId());
        assertTrue(fixture.owners.conservationHolds());
    }

    @Test
    void aDemandItsOwnJobDoesNotDeclareIsRefusedAndPlaced() {
        Fixture fixture = new Fixture(true);
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, "other", 0);
        JobDeclaration declaration = new JobDeclaration("a/0", 1L, WORLD, DOMAIN, 0, List.of(), 0,
            "a/0", "region", List.of(ref), List.of(ref), ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, 0, 4, List.of(demand(SITE, 40)));
        fixture.apply(fixture.graph(declaration), TICK);

        assertEquals(0, fixture.owners.activeTokens());
        assertEquals(1L, fixture.grants.refusedUndeclaredCount());
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER.name(), fixture.grants.lastRefusal().code());
        assertEquals(RejectTrigger.DOMAIN_SET_NOT_SPLIT, fixture.grants.lastRefusalTrigger());
    }

    @Test
    void thePointThatIsOffGrantsNothingAndLeavesTheWritePathAsItWas() {
        Fixture off = new Fixture(false);
        off.apply(off.graph(declaration("a/0", List.of(demand(SITE, 40)))), TICK);

        assertEquals(0, off.owners.activeTokens());
        assertEquals(0L, off.owners.acquiredCount());
        assertEquals(0L, off.grants.declaredCount());
        assertEquals(0L, off.grants.grantedCount());
        assertEquals(0L, off.grants.refusedCount());
        assertTrue(off.owners.conservationHolds());
        assertEquals(List.of(0, 0, 1, 0, 1), off.shortPathSequence());

        Fixture on = new Fixture(true);
        on.apply(on.graph(declaration("a/0", List.of(demand(SITE, 40)))), TICK);
        assertEquals(1, on.owners.activeTokens(), "a real holder holds the domain of the job");

        assertEquals(off.shortPathSequence(), on.shortPathSequence(),
            "the classification does not depend on whether a write right is held anywhere");
        on.probe();
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER, on.guard.lastDecision().code(),
            "the write path holds no owner of its own domain, so the write is not captured");
    }

    @Test
    void theReadoutPublishesTheFourCountsOfTheBalanceAndTheFiveElementsOfARefusal() {
        List<String> export = KernelReadings.export(KernelModule.instance());
        List<String> keys = List.of("token.active=", "token.acquired=", "token.reacquired=",
            "token.released=", "token.release_refused=", "token.expired_reclaimed=",
            "token.double_holder=", "token.conservation_ok=", "token.grants_enabled=",
            "token.declared=", "token.granted=", "token.released_by_plan=",
            "token.held_by_declarations=", "token.refused=", "token.refused.cross_world=",
            "token.refused.expired=", "token.refused.no_version=", "token.refused.double_holder=",
            "token.refused.undeclared=", "token.last_refusal.code=", "token.last_refusal.trigger=",
            "token.last_refusal.site=", "token.last_refusal.thread=", "token.last_refusal.world=",
            "token.last_refusal.tick=", "token.last_refusal.domain=");
        for (String key : keys) {
            assertTrue(export.stream().anyMatch(line -> line.startsWith(key)), key);
        }
    }

    /** The demand the real entity job makes: the level of the right is the level the plan keeps the
     * version of a declared domain at, which is not the level the job declares its ownership at. */
    private static JobDeclaration.OwnerDemand demand(String site, int holdTicks) {
        return new JobDeclaration.OwnerDemand(WORLD, WriteLevel.REGION, DOMAIN,
            HolderKind.REGISTERED, site, holdTicks);
    }

    private static JobDeclaration declaration(String key, List<JobDeclaration.OwnerDemand> demands) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, DOMAIN, 0);
        return new JobDeclaration(key, key.hashCode(), WORLD, DOMAIN, 0, List.of(), 0, key, "region",
            List.of(ref), List.of(ref), ShareClass.ENTITY, JobDeclaration.SiteClass.PARALLEL, 0, 4,
            demands);
    }

    /** One process with a write right registry, a version source and the point that turns a frozen
     * declaration into a token, next to the write paths the kernel wires them into. */
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

        private Fixture(boolean enabled) {
            this(enabled, true);
        }

        private Fixture(boolean enabled, boolean withSlots) {
            versions.refresh(withSlots);
            if (withSlots) {
                versions.grant(WORLD, WriteLevel.REGION, DOMAIN);
                versions.grant(WORLD, WriteLevel.REGION, WritePath.BLOCK_WRITE.key());
            }
            grants.refresh(enabled);
            segment.bindOwnerThread(Thread.currentThread());
            intents.bindPayload(guard);
            guard.bindServerThread(Thread.currentThread(), "host:server-thread");
            guard.versionSlots(versions);
            guard.refresh(true, false, false, TICK + 100L);
        }

        private void apply(JobGraph graph, long tickIndex) {
            grants.apply(graph, tickIndex);
        }

        private JobGraph graph(JobDeclaration... declarations) {
            JobGraphBuilder.Freeze freeze = JobGraphBuilder.freeze(List.of(declarations), TICK, 1L, 1L,
                8);
            assertTrue(freeze.ok(), () -> "the freeze was refused: " + freeze.code());
            assertNotNull(freeze.graph());
            return freeze.graph();
        }

        /** One judged write of a declared holder, made on the thread that holder was declared for. */
        private void probe() {
            Thread thread = new Thread(() -> guard.admitBlockWrite(LEVEL, WORLD, null),
                "worker-under-test");
            guard.registerHolder(thread, HolderKind.REGISTERED, "site:worker");
            run(thread);
        }

        /** The values the short path answers over the states a tick can be in. */
        private List<Integer> shortPathSequence() {
            guard.refresh(false, false, false, TICK);
            int inactive = guard.classifyBlockWrite(LEVEL);
            guard.refresh(true, false, false, TICK);
            int onTheServerThread = guard.classifyBlockWrite(LEVEL);
            int onAnotherThread = otherThreadClassification();
            guard.refresh(false, false, false, TICK);
            int inactiveAgain = guard.classifyBlockWrite(LEVEL);
            guard.refresh(true, false, false, TICK);
            return List.of(inactive, onTheServerThread, onAnotherThread, inactiveAgain,
                otherThreadClassification());
        }

        private int otherThreadClassification() {
            int[] answer = new int[1];
            Thread thread = new Thread(() -> answer[0] = guard.classifyBlockWrite(LEVEL),
                "another-thread");
            run(thread);
            return answer[0];
        }

        private void run(Thread thread) {
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
