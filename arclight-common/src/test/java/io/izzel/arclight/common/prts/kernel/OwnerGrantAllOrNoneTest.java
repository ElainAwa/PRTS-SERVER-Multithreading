/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerGrantAllOrNoneTest {

    @Test
    void aNodeHoldsEveryDemandOrNone() {
        Fixture fixture = new Fixture();
        fixture.versions.grant("w", WriteLevel.REGION, "a");
        long bVersion = fixture.versions.grant("w", WriteLevel.REGION, "b");
        long tick = 10L;
        OwnerToken other = new OwnerToken("w", WriteLevel.REGION, "b", 1L, bVersion, tick,
            tick + 50L, HolderKind.REGISTERED, "site:b");
        assertTrue(fixture.owners.acquire(other));

        fixture.grants.apply(fixture.graph(List.of(demand("a"), demand("b"))), tick);

        assertNull(fixture.owners.lookup("w", WriteLevel.REGION, "a").orElse(null),
            "the free demand must be released when its node cannot hold the whole set");
        assertNotNull(fixture.owners.lookup("w", WriteLevel.REGION, "b").orElse(null),
            "the right another holder already owns must stay with it");
        assertEquals(0L, fixture.grants.heldCount(),
            "a refused node must leave the point holding nothing");
    }

    @Test
    void aNodeInstallsEveryDemandWhenTheWholeSetIsFree() {
        Fixture fixture = new Fixture();
        fixture.versions.grant("w", WriteLevel.REGION, "a");
        fixture.versions.grant("w", WriteLevel.REGION, "b");

        fixture.grants.apply(fixture.graph(List.of(demand("a"), demand("b"))), 10L);

        assertEquals("site:a",
            fixture.owners.lookup("w", WriteLevel.REGION, "a").orElseThrow().holderSiteId());
        assertEquals("site:a",
            fixture.owners.lookup("w", WriteLevel.REGION, "b").orElseThrow().holderSiteId());
        assertEquals(2L, fixture.grants.heldCount());
    }

    @Test
    void aRefusedNodeCanBeRetriedAfterTheConflictIsGone() {
        Fixture fixture = new Fixture();
        fixture.versions.grant("w", WriteLevel.REGION, "a");
        long bVersion = fixture.versions.grant("w", WriteLevel.REGION, "b");
        long tick = 10L;
        OwnerToken other = new OwnerToken("w", WriteLevel.REGION, "b", 1L, bVersion, tick,
            tick + 50L, HolderKind.REGISTERED, "site:b");
        assertTrue(fixture.owners.acquire(other));
        JobGraph graph = fixture.graph(List.of(demand("a"), demand("b")));

        fixture.grants.apply(graph, tick);
        assertNull(fixture.owners.lookup("w", WriteLevel.REGION, "a").orElse(null));

        assertTrue(fixture.owners.release(other.domain(), "site:b"));
        fixture.grants.apply(graph, tick + 1L);

        assertNotNull(fixture.owners.lookup("w", WriteLevel.REGION, "a").orElse(null),
            "a retried node must install its whole set");
        assertNotNull(fixture.owners.lookup("w", WriteLevel.REGION, "b").orElse(null));
    }

    @Test
    void duplicateIdenticalDemandIsOneRight() {
        Fixture fixture = new Fixture();
        fixture.versions.grant("w", WriteLevel.REGION, "a");
        JobDeclaration.OwnerDemand same = demand("a");

        fixture.grants.apply(fixture.graph(List.of(same, same)), 10L);

        assertNotNull(fixture.owners.lookup("w", WriteLevel.REGION, "a").orElse(null));
        assertEquals(1, fixture.grants.heldCount(), "the duplicate names one right");
        assertEquals(1L, fixture.grants.grantedCount(), "the duplicate installs one right");
        assertEquals(1, fixture.owners.activeTokens());
        assertTrue(fixture.owners.conservationHolds());
    }

    @Test
    void conflictingDuplicateDemandInstallsNothing() {
        Fixture fixture = new Fixture();
        fixture.versions.grant("w", WriteLevel.REGION, "a");
        JobDeclaration.OwnerDemand first = demand("a");
        JobDeclaration.OwnerDemand second = new JobDeclaration.OwnerDemand("w", WriteLevel.REGION,
            "a", HolderKind.REGISTERED, "site:b", 10);

        fixture.grants.apply(fixture.graph(List.of(first, second)), 10L);

        assertNull(fixture.owners.lookup("w", WriteLevel.REGION, "a").orElse(null),
            "conflicting duplicate demands must install neither right");
        assertEquals(0, fixture.grants.heldCount());
        assertTrue(fixture.owners.conservationHolds());
    }

    @Test
    void resetDoesNotReleaseAnotherHoldersRight() {
        Fixture fixture = new Fixture();
        fixture.versions.grant("w", WriteLevel.REGION, "a");
        JobGraph graph = fixture.graph(List.of(demand("a")));
        fixture.grants.apply(graph, 10L);
        OwnerToken held = fixture.owners.lookup("w", WriteLevel.REGION, "a").orElseThrow();
        assertTrue(fixture.owners.release(held.domain(), held.holderSiteId()));

        OwnerToken other = new OwnerToken("w", WriteLevel.REGION, "a", 2L, held.expectedVersion(),
            10L, 100L, HolderKind.REGISTERED, "site:b");
        assertTrue(fixture.owners.acquire(other));

        fixture.grants.reset();

        assertNotNull(fixture.owners.lookup("w", WriteLevel.REGION, "a").orElse(null),
            "reset must not erase a right another holder acquired after expiry");
    }

    @Test
    void aRefusedSetRestoresTheBindingItRenewed() {
        Fixture fixture = new Fixture();
        long versionA = fixture.versions.grant("w", WriteLevel.REGION, "a");
        long versionB = fixture.versions.grant("w", WriteLevel.REGION, "b");
        long tick = 10L;
        OwnerToken prior = new OwnerToken("w", WriteLevel.REGION, "a", 1L, versionA, tick,
            tick + 100L, HolderKind.REGISTERED, "site:a");
        assertTrue(fixture.owners.acquire(prior));
        OwnerToken other = new OwnerToken("w", WriteLevel.REGION, "b", 2L, versionB, tick,
            tick + 100L, HolderKind.REGISTERED, "site:b");
        assertTrue(fixture.owners.acquire(other));

        fixture.grants.apply(fixture.graph(List.of(demand("a"), demand("b"))), tick);

        OwnerToken restored = fixture.owners.lookup("w", WriteLevel.REGION, "a").orElseThrow();
        assertEquals(prior.expectedVersion(), restored.expectedVersion(),
            "a refused set must not renew the first demand's binding");
        assertEquals(prior.expireTick(), restored.expireTick(),
            "a refused set must restore the prior hold window");
        assertEquals(0, fixture.grants.heldCount(),
            "the point must not track a binding the failed set did not acquire");
    }

    private static JobDeclaration.OwnerDemand demand(String domain) {
        return new JobDeclaration.OwnerDemand("w", WriteLevel.REGION, domain,
            HolderKind.REGISTERED, "site:a", 10);
    }

    private static final class Fixture {

        final OwnerRegistry owners = new OwnerRegistry();
        final WriteVersionSlots versions = new WriteVersionSlots();
        final OwnerGrantPoint grants = new OwnerGrantPoint(owners, versions);

        Fixture() {
            versions.refresh(true);
            grants.refresh(true);
        }

        JobGraph graph(List<JobDeclaration.OwnerDemand> demands) {
            JobDeclaration.DomainRef a = new JobDeclaration.DomainRef("w", "a", 0);
            JobDeclaration.DomainRef b = new JobDeclaration.DomainRef("w", "b", 0);
            JobDeclaration declaration = new JobDeclaration("job", 1L, "w", "a", 0, List.of(), 0,
                "affinity", "cancel", List.of(a, b), List.of(a, b), ShareClass.OTHER,
                JobDeclaration.SiteClass.PARALLEL, 0, 4, demands, null);
            JobGraphBuilder.Freeze freeze =
                JobGraphBuilder.freeze(List.of(declaration), 10L, 1L, 1L, 16);
            assertTrue(freeze.ok(), "graph must freeze");
            return freeze.graph();
        }
    }
}
