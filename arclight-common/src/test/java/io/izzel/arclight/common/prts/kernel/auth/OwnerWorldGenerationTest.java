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
import io.izzel.arclight.common.prts.kernel.sites.WorldEpochs;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OwnerWorldGenerationTest {

    @Test
    void aReincarnatedWorldRetiresTheRightsOfTheOldIncarnation() {
        OwnerRegistry owners = new OwnerRegistry();
        WriteVersionSlots versions = new WriteVersionSlots();
        versions.refresh(true);
        long version = versions.grant("w", WriteLevel.REGION, "d");
        WorldEpochs epochs = new WorldEpochs();
        epochs.observe(List.of("w"));
        long first = epochs.epochOf("w");
        owners.noteWorldEpoch("w", first);
        OwnerGrantPoint grants = new OwnerGrantPoint(owners, versions, epochs::epochOf);
        grants.refresh(true);
        grants.apply(graph(), 5L);
        OwnerToken token = owners.lookup("w", WriteLevel.REGION, "d").orElse(null);
        assertNotNull(token);
        assertEquals(first, token.worldEpoch(), "the token is bound to the incarnation it was granted in");

        WriteAuthority authority = authority(owners);
        assertEquals(WriteDisposition.GRANT,
            authority.authorize(attempt(authority, token, version, first)).disposition());

        epochs.forget("w");
        epochs.observe(List.of("w"));
        long second = epochs.epochOf("w");
        assertNotEquals(first, second);
        owners.noteWorldEpoch("w", second);

        assertNull(owners.lookup("w", WriteLevel.REGION, "d").orElse(null),
            "the old incarnation's token must be retired");
        assertTrue(owners.conservationHolds());
        WriteVerdict stale = authority.authorize(attempt(authority, token, version, first));
        assertEquals(WriteDisposition.DENY, stale.disposition());
        assertEquals(RejectCode.WORLD_LIFECYCLE_DENIED, stale.code());
    }

    @Test
    void aStaleAttemptIsRefusedEvenWhenTheCurrentTokenIsValid() {
        OwnerRegistry owners = new OwnerRegistry();
        WriteVersionSlots versions = new WriteVersionSlots();
        versions.refresh(true);
        long version = versions.grant("w", WriteLevel.REGION, "d");
        WorldEpochs epochs = new WorldEpochs();
        epochs.observe(List.of("w"));
        long first = epochs.epochOf("w");
        owners.noteWorldEpoch("w", first);
        OwnerGrantPoint grants = new OwnerGrantPoint(owners, versions, epochs::epochOf);
        grants.refresh(true);
        grants.apply(graph(), 5L);
        OwnerToken token = owners.lookup("w", WriteLevel.REGION, "d").orElse(null);
        assertNotNull(token);

        epochs.forget("w");
        epochs.observe(List.of("w"));
        long second = epochs.epochOf("w");
        owners.noteWorldEpoch("w", second);
        grants.apply(graph(), 6L);
        OwnerToken current = owners.lookup("w", WriteLevel.REGION, "d").orElse(null);
        assertNotNull(current, "the new incarnation must be served again");

        WriteAuthority authority = authority(owners);
        assertEquals(WriteDisposition.DENY,
            authority.authorize(attempt(authority, current, version, first)).disposition(),
            "a write frozen against the old incarnation must not use the new right");
        assertEquals(WriteDisposition.GRANT,
            authority.authorize(attempt(authority, current, version, second)).disposition());
    }

    @Test
    void aDeclaredTokenFromAnOlderPlanDoesNotGrant() {
        OwnerRegistry owners = new OwnerRegistry();
        WriteVersionSlots versions = new WriteVersionSlots();
        versions.refresh(true);
        long version = versions.grant("w", WriteLevel.REGION, "d");
        WorldEpochs epochs = new WorldEpochs();
        epochs.observe(List.of("w"));
        long first = epochs.epochOf("w");
        owners.noteWorldEpoch("w", first);
        OwnerGrantPoint grants = new OwnerGrantPoint(owners, versions, epochs::epochOf);
        grants.refresh(true);
        grants.apply(graph(), 5L);
        OwnerToken token = owners.lookup("w", WriteLevel.REGION, "d").orElse(null);
        assertNotNull(token);
        assertEquals(1L, token.planSequence());

        WriteAuthority authority = authority(owners);
        assertEquals(WriteDisposition.DENY,
            authority.authorize(attempt(authority, token, version, first, 2L)).disposition(),
            "a declaration frozen for an older plan must not grant");
        assertEquals(WriteDisposition.GRANT,
            authority.authorize(attempt(authority, token, version, first, 1L)).disposition(),
            "the plan the token was granted for must still grant");
    }

    @Test
    void anUnliveWorldCannotUseTheTokenOfItsLastIncarnation() {
        OwnerRegistry owners = new OwnerRegistry();
        WriteVersionSlots versions = new WriteVersionSlots();
        versions.refresh(true);
        long version = versions.grant("w", WriteLevel.REGION, "d");
        WorldEpochs epochs = new WorldEpochs();
        epochs.observe(List.of("w"));
        long live = epochs.epochOf("w");
        owners.noteWorldEpoch("w", live);
        OwnerGrantPoint grants = new OwnerGrantPoint(owners, versions, epochs::epochOf);
        grants.refresh(true);
        grants.apply(graph(), 5L);
        OwnerToken token = owners.lookup("w", WriteLevel.REGION, "d").orElseThrow();

        epochs.forget("w");
        WriteAuthority authority = authority(owners);
        WriteVerdict verdict = authority.authorize(attempt(authority, token, version, -1L));

        assertEquals(WriteDisposition.DENY, verdict.disposition(),
            "a direct write against an unlive world must not pass");
        assertEquals(RejectCode.WORLD_LIFECYCLE_DENIED, verdict.code());
    }

    @Test
    void aDeclaredTokenThatDiffersFromTheLiveRecordDoesNotGrant() {
        OwnerRegistry owners = new OwnerRegistry();
        WriteVersionSlots versions = new WriteVersionSlots();
        versions.refresh(true);
        long version = versions.grant("w", WriteLevel.REGION, "d");
        WorldEpochs epochs = new WorldEpochs();
        epochs.observe(List.of("w"));
        long live = epochs.epochOf("w");
        owners.noteWorldEpoch("w", live);
        OwnerGrantPoint grants = new OwnerGrantPoint(owners, versions, epochs::epochOf);
        grants.refresh(true);
        grants.apply(graph(), 5L);
        OwnerToken token = owners.lookup("w", WriteLevel.REGION, "d").orElseThrow();
        OwnerToken forged = new OwnerToken(token.worldId(), token.level(), token.domainId(),
            token.epoch(), token.expectedVersion() + 1L, token.holdStartTick(), token.expireTick(),
            token.holderKind(), token.holderSiteId(), token.planSequence(), token.worldEpoch());
        WriteAuthority authority = authority(owners);

        assertEquals(WriteDisposition.DENY,
            authority.authorize(attempt(authority, forged, version, live)).disposition(),
            "a declared credential must be the exact live record, not a lookalike");
    }

    @Test
    void aLifecycleResetRetiresEveryInstalledRight() {
        OwnerRegistry owners = new OwnerRegistry();
        long tick = 10L;
        OwnerToken token = new OwnerToken("w", WriteLevel.REGION, "d", 3L, 7L, tick,
            tick + 20L, HolderKind.REGISTERED, "site:a");
        assertTrue(owners.acquire(token));
        long generation = owners.lifecycleGeneration();

        owners.resetLifecycle();

        assertTrue(owners.lifecycleGeneration() > generation);
        assertTrue(owners.lookup("w", WriteLevel.REGION, "d").isEmpty());
        assertFalse(owners.acquire(token), "no value from before the reset may be installed again");
        assertTrue(owners.conservationHolds());
    }

    private static WriteAttempt attempt(WriteAuthority authority, OwnerToken token, long version,
                                        long worldEpoch) {
        return attempt(authority, token, version, worldEpoch, 0L);
    }

    private static WriteAttempt attempt(WriteAuthority authority, OwnerToken token, long version,
                                        long worldEpoch, long planOrder) {
        return WriteAttempt.builder(authority.nextAttemptId(), "w", token.holderSiteId())
            .holder(HolderKind.REGISTERED, token.holderSiteId(), "test-thread")
            .target(WriteLevel.REGION, "d")
            .domains(Set.of("d"), Set.of("d"))
            .version(version, 5L, planOrder)
            .worldEpoch(worldEpoch)
            .admitted(true)
            .declaredOwner(token)
            .build();
    }

    private static WriteAuthority authority(OwnerRegistry owners) {
        return new WriteAuthority(owners, new IntentQueue(() -> 8, () -> 0), new WriteLedger(),
            () -> false, () -> 0);
    }

    private static JobGraph graph() {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef("w", "d", 0);
        JobDeclaration.OwnerDemand demand = new JobDeclaration.OwnerDemand("w", WriteLevel.REGION,
            "d", HolderKind.REGISTERED, "site:a", 10);
        JobDeclaration declaration = new JobDeclaration("job", 1L, "w", "d", 0, List.of(), 0,
            "affinity", "cancel", List.of(ref), List.of(ref), ShareClass.OTHER,
            JobDeclaration.SiteClass.PARALLEL, 0, 4, List.of(demand), null);
        JobGraphBuilder.Freeze freeze = JobGraphBuilder.freeze(List.of(declaration), 5L, 1L, 1L, 16);
        assertTrue(freeze.ok(), "graph must freeze");
        return freeze.graph();
    }
}
