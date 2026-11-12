/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import io.izzel.arclight.common.prts.kernel.OwnerGrantPoint;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerAuthorityGenerationTest {

    private static final String WORLD = "w";
    private static final String DOMAIN = "d";

    @Test
    void releasedTokenIsNotAGrant() {
        OwnerRegistry owners = new OwnerRegistry();
        WriteAuthority authority = authority(owners);
        long tick = 10L;
        OwnerToken token = new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, 1L, 7L, tick,
            tick + 20L, HolderKind.REGISTERED, "site:a");
        assertTrue(owners.acquire(token));

        assertEquals(WriteDisposition.GRANT,
            authority.authorize(attempt(authority, token, 7L, tick)).disposition(),
            "a token the registry holds must grant");

        assertTrue(owners.release(token.domain(), "site:a"));
        WriteVerdict released = authority.authorize(attempt(authority, token, 7L, tick));
        assertEquals(WriteDisposition.DENY, released.disposition(),
            "a released token must not grant");
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER, released.code());
    }

    @Test
    void staleDeclaredTokenLosesToTheInstalledHolder() {
        OwnerRegistry owners = new OwnerRegistry();
        WriteAuthority authority = authority(owners);
        long tick = 10L;
        OwnerToken first = new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, 1L, 7L, tick,
            tick + 20L, HolderKind.REGISTERED, "site:a");
        assertTrue(owners.acquire(first));
        assertTrue(owners.release(first.domain(), "site:a"));
        OwnerToken second = new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, 2L, 7L, tick,
            tick + 20L, HolderKind.REGISTERED, "site:b");
        assertTrue(owners.acquire(second));

        assertEquals(WriteDisposition.DENY,
            authority.authorize(attempt(authority, first, 7L, tick)).disposition(),
            "the released token of the earlier holder must not grant");
        assertEquals(WriteDisposition.GRANT,
            authority.authorize(attempt(authority, second, 7L, tick)).disposition(),
            "the installed token must grant");
    }

    @Test
    void readingsResetRetiresTheRightsItGranted() {
        OwnerRegistry owners = new OwnerRegistry();
        WriteVersionSlots versions = new WriteVersionSlots();
        versions.refresh(true);
        long version = versions.grant(WORLD, WriteLevel.REGION, DOMAIN);
        OwnerGrantPoint grants = new OwnerGrantPoint(owners, versions);
        grants.refresh(true);
        long tick = 5L;
        grants.apply(graph(List.of(demand("site:a"))), tick);
        OwnerToken token = owners.lookup(WORLD, WriteLevel.REGION, DOMAIN).orElse(null);
        assertNotNull(token, "the frozen demand must install a token");

        WriteAuthority authority = authority(owners);
        assertEquals(WriteDisposition.GRANT,
            authority.authorize(attempt(authority, token, version, tick)).disposition());

        versions.reset();
        grants.reset();
        assertNull(owners.lookup(WORLD, WriteLevel.REGION, DOMAIN).orElse(null),
            "a readings reset must retire the rights it granted");
        assertEquals(WriteDisposition.DENY,
            authority.authorize(attempt(authority, token, version, tick)).disposition(),
            "a token from before the reset must not grant");
    }

    @Test
    void acquiringARetiredGenerationIsRefused() {
        OwnerRegistry owners = new OwnerRegistry();
        long tick = 10L;
        OwnerToken token = new OwnerToken(WORLD, WriteLevel.REGION, DOMAIN, 4L, 7L, tick,
            tick + 20L, HolderKind.REGISTERED, "site:a");
        assertTrue(owners.acquire(token));
        assertTrue(owners.release(token.domain(), "site:a"));
        assertFalse(owners.acquire(token), "the retired generation must not be installed again");
    }

    private static WriteAttempt attempt(WriteAuthority authority, OwnerToken token, long version,
                                        long tick) {
        return WriteAttempt.builder(authority.nextAttemptId(), WORLD, token.holderSiteId())
            .holder(HolderKind.REGISTERED, token.holderSiteId(), "test-thread")
            .target(WriteLevel.REGION, DOMAIN)
            .domains(Set.of(DOMAIN), Set.of(DOMAIN))
            .version(version, tick, 0L)
            .admitted(true)
            .declaredOwner(token)
            .build();
    }

    private static WriteAuthority authority(OwnerRegistry owners) {
        return new WriteAuthority(owners, new IntentQueue(() -> 8, () -> 0), new WriteLedger(),
            () -> false, () -> 0);
    }

    private static JobDeclaration.OwnerDemand demand(String site) {
        return new JobDeclaration.OwnerDemand(WORLD, WriteLevel.REGION, DOMAIN,
            HolderKind.REGISTERED, site, 10);
    }

    private static JobGraph graph(List<JobDeclaration.OwnerDemand> demands) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, DOMAIN, 0);
        JobDeclaration declaration = new JobDeclaration("job", 1L, WORLD, DOMAIN, 0, List.of(),
            0, "affinity", "cancel", List.of(ref), List.of(ref), ShareClass.OTHER,
            JobDeclaration.SiteClass.PARALLEL, 0, 4, demands, null);
        JobGraphBuilder.Freeze freeze = JobGraphBuilder.freeze(List.of(declaration), 5L, 1L, 1L, 16);
        assertTrue(freeze.ok(), "graph must freeze");
        assertTrue(freeze.graph().nodeCount() == 1);
        assertTrue(freeze.graph().nodes().get(0).ownerDemands().size() == demands.size());
        return freeze.graph();
    }
}
