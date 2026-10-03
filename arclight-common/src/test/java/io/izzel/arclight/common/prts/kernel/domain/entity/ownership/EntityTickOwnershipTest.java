/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership;

import io.izzel.arclight.common.prts.kernel.DomainReadings;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickModels;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The ownership fixture lifecycle: one token per eligible row, one outcome per row, a token that
 * cannot be used handed back in front of the host, and no lease that outlives its tick. */
class EntityTickOwnershipTest {

    private static final long TICK = 500L;
    private static final long WORLD_EPOCH = 7L;
    private static final byte CLEAN = 0;

    @BeforeEach
    void clearCounters() {
        EntityTickOwnership.reset();
    }

    @Test
    void nothingIsClaimedWhileTheFixtureIsOff() {
        assertFalse(EntityTickOwnership.live(), "a test run must not carry the fixture property");
        Map<String, Double> fields = new LinkedHashMap<>();
        EntityTickOwnership.readings(new DomainReadings() {

            @Override
            public void add(String name, double value) {
                fields.put(name, value);
            }

            @Override
            public void add(String name, long value) {
                fields.put(name, (double) value);
            }
        });
        assertEquals(16, fields.size(), "the field set changed");
        for (Map.Entry<String, Double> field : fields.entrySet()) {
            assertEquals(0.0, field.getValue(), field.getKey() + " is not zero while the arm is off");
        }
        assertTrue(EntityTickOwnership.evidenceLine().endsWith("live=0"),
            "the evidence line claims an arm that is not declared");
        assertEquals(EntityTickOwnership.RUN_HOST_TICK, EntityTickOwnership.onEntityTickPre(null),
            "the host entry did work while the arm is off");
    }

    @Test
    void aSettledTokenSkipsTheRowOnceAndIsSpent() {
        OwnershipLease lease = lease(1);
        issue(lease, 11, 40);
        lease.publish(direct(), 1);
        EntityTickOwnership.install(lease);
        assertEquals(OwnershipLease.SETTLED, lease.state(0));
        assertTrue(EntityTickOwnership.decideRow(11, WORLD_EPOCH, 41, CLEAN, false, 0.0, 0.0, 0.0) >= 0,
            "a settled token did not skip its row");
        assertEquals(OwnershipLease.CONSUMED, lease.state(0), "the spent token stayed usable");
        EntityTickOwnership.closeInstalled();
        assertEquals(1L, EntityTickOwnership.HOST_SKIPPED.sum());
        assertEquals(0L, EntityTickOwnership.HOST_EXECUTED.sum(), "a skipped row also ran");
        assertEquals(0L, EntityTickOwnership.ELIGIBLE.sum());
        assertEquals(1L, EntityTickOwnership.CANDIDATES.sum());
        assertEquals(0L, EntityTickOwnership.TOKEN_LEAK.sum());
        assertEquals(0L, EntityTickOwnership.INVARIANT_VIOLATIONS.sum());
        assertEquals(1L, EntityTickOwnership.ISSUED.sum() - EntityTickOwnership.REVOKED.sum(),
            "the row counted as owned is not the row that was skipped");
    }

    @Test
    void anUnsettledTokenIsHandedBackBeforeTheHostContinues() {
        OwnershipLease lease = lease(1);
        issue(lease, 12, 40);
        lease.publish(never(), 1);
        EntityTickOwnership.install(lease);
        assertEquals(OwnershipLease.PENDING, lease.state(0));
        assertTrue(EntityTickOwnership.decideRow(12, WORLD_EPOCH, 41, CLEAN, false, 0.0, 0.0, 0.0) < 0,
            "a row without an answer was skipped");
        assertEquals(1L, EntityTickOwnership.ELIGIBLE.sum(), "the fallback row is not the eligible one");
        assertEquals(1L, EntityTickOwnership.FALLBACK.sum(), "the withdrawal was not counted");
        assertEquals(0L, EntityTickOwnership.HOST_SKIPPED.sum());
        assertEquals(0L, EntityTickOwnership.HOST_EXECUTED.sum(),
            "a claimed row was counted as an unclaimed one");
        EntityTickOwnership.closeInstalled();
        assertEquals(0L, EntityTickOwnership.NOT_ENTERED.sum(),
            "a row that reached the host entry was recycled as one that did not");
        assertEquals(0L, EntityTickOwnership.TOKEN_LEAK.sum());
        assertEquals(0L, EntityTickOwnership.INVARIANT_VIOLATIONS.sum());
    }

    @Test
    void aFailedWorkerHandsTheRowBackAndAnswersAreNeverAppliedLate() {
        OwnershipLease lease = lease(2);
        issue(lease, 13, 40);
        issue(lease, 14, 40);
        lease.publish(refuse(), 1);
        EntityTickOwnership.install(lease);
        assertEquals(OwnershipLease.FAILED, lease.state(0), "a refused chunk was not failed");
        assertEquals(OwnershipLease.FAILED, lease.state(1));
        assertTrue(EntityTickOwnership.decideRow(13, WORLD_EPOCH, 41, CLEAN, false, 0.0, 0.0, 0.0) < 0);
        assertTrue(EntityTickOwnership.decideRow(14, WORLD_EPOCH, 41, CLEAN, false, 0.0, 0.0, 0.0) < 0);
        assertEquals(2L, EntityTickOwnership.FALLBACK.sum());
        assertEquals(2L, EntityTickOwnership.ELIGIBLE.sum());
        assertEquals(0L, EntityTickOwnership.HOST_SKIPPED.sum());
        EntityTickOwnership.closeInstalled();
        assertEquals(2L, EntityTickOwnership.CANDIDATES.sum());
        assertEquals(0L, EntityTickOwnership.TOKEN_LEAK.sum());
        assertEquals(0L, EntityTickOwnership.INVARIANT_VIOLATIONS.sum());
        assertTrue(EntityTickOwnership.partitionOk() && EntityTickOwnership.closureOk());
    }

    @Test
    void aTokenThatNeverReachesTheHostEntryIsRecycledAtTheClose() {
        OwnershipLease lease = lease(2);
        issue(lease, 15, 40);
        issue(lease, 16, 40);
        lease.publish(never(), 1);
        EntityTickOwnership.install(lease);
        EntityTickOwnership.closeInstalled();
        assertEquals(2L, EntityTickOwnership.NOT_ENTERED.sum());
        assertEquals(2L, EntityTickOwnership.REVOKED.sum());
        assertEquals(0L, EntityTickOwnership.TOKEN_LEAK.sum(), "a lease outlived its tick");
        assertEquals(0L, EntityTickOwnership.CANDIDATES.sum());
        assertTrue(EntityTickOwnership.closureOk(), "the issued tokens are not accounted for");
        assertEquals(0.0, EntityTickOwnership.coverage());
    }

    @Test
    void aSecondEntryOfOneRowIsAConflictAndTheRowRuns() {
        OwnershipLease lease = lease(1);
        issue(lease, 17, 40);
        lease.publish(direct(), 1);
        EntityTickOwnership.install(lease);
        assertTrue(EntityTickOwnership.decideRow(17, WORLD_EPOCH, 41, CLEAN, false, 0.0, 0.0, 0.0) >= 0);
        assertTrue(EntityTickOwnership.decideRow(17, WORLD_EPOCH, 41, CLEAN, false, 0.0, 0.0, 0.0) < 0,
            "a second host entry of one row skipped it again");
        assertEquals(1L, EntityTickOwnership.OWNER_CONFLICT.sum());
        assertEquals(0L, EntityTickOwnership.HOST_SKIPPED.sum(),
            "the row stayed skipped and also ran");
        assertEquals(1L, EntityTickOwnership.ELIGIBLE.sum());
        assertEquals(1L, EntityTickOwnership.FALLBACK.sum());
        EntityTickOwnership.closeInstalled();
        assertEquals(0L, EntityTickOwnership.INVARIANT_VIOLATIONS.sum(),
            "a conflict broke the accounts");
        assertEquals(0L, EntityTickOwnership.TOKEN_LEAK.sum());
    }

    @Test
    void aTokenThatNoLongerMatchesItsRowIsWithdrawn() {
        OwnershipLease lease = lease(1);
        issue(lease, 18, 40);
        lease.publish(direct(), 1);
        EntityTickOwnership.install(lease);
        assertTrue(EntityTickOwnership.decideRow(18, WORLD_EPOCH + 1L, 41, CLEAN, false, 0.0, 0.0, 0.0) < 0,
            "a token of another generation skipped its row");
        assertEquals(1L, EntityTickOwnership.LIFECYCLE_REJECTED.sum());
        assertTrue(EntityTickOwnership.decideRow(19, WORLD_EPOCH, 41, CLEAN, false, 0.0, 0.0, 0.0) < 0,
            "a row nobody claimed was skipped");
        assertEquals(1L, EntityTickOwnership.HOST_EXECUTED.sum(),
            "the unclaimed row was not counted as one the host ran");
        EntityTickOwnership.closeInstalled();
        assertEquals(1L, EntityTickOwnership.CANDIDATES.sum() - EntityTickOwnership.HOST_EXECUTED.sum());
        assertEquals(0L, EntityTickOwnership.INVARIANT_VIOLATIONS.sum());
    }

    @Test
    void theAccountsCloseOnAMixtureOfAllThreeOutcomes() {
        OwnershipLease lease = lease(4);
        issue(lease, 21, 40);
        issue(lease, 22, 40);
        issue(lease, 23, 40);
        issue(lease, 24, 40);
        lease.publish(refuse(), 1);
        EntityTickOwnership.install(lease);
        assertTrue(EntityTickOwnership.decideRow(21, WORLD_EPOCH, 41, CLEAN, false, 0.0, 0.0, 0.0) < 0);
        assertTrue(EntityTickOwnership.decideRow(22, WORLD_EPOCH, 41, CLEAN, true, 0.0, 0.0, 0.0) < 0);
        EntityTickOwnership.closeInstalled();
        assertEquals(4L, EntityTickOwnership.ISSUED.sum());
        assertEquals(2L, EntityTickOwnership.NOT_ENTERED.sum());
        assertEquals(2L, EntityTickOwnership.ELIGIBLE.sum());
        assertEquals(4L, EntityTickOwnership.REVOKED.sum(),
            "the withdrawn tokens and the recycled leases are not both counted");
        assertEquals(0L, EntityTickOwnership.HOST_SKIPPED.sum());
        assertEquals(2L, EntityTickOwnership.CANDIDATES.sum());
        assertEquals(0L, EntityTickOwnership.TOKEN_LEAK.sum());
        assertEquals(0L, EntityTickOwnership.ISSUED.sum() - EntityTickOwnership.REVOKED.sum(),
            "a row that never kept its token counted as owned");
        assertEquals(0L, EntityTickOwnership.INVARIANT_VIOLATIONS.sum());
        assertTrue(EntityTickOwnership.closureOk() && EntityTickOwnership.partitionOk());
    }

    @Test
    void theIndependentCounterIsComparedRowByRow() {
        int[] ids = {31, 32, 33, 34};
        byte[] skips = {1, 0, 0, 1};
        Map<Integer, Integer> brokenPath = Map.of(32, 1, 33, 2, 34, 1);
        Map<Integer, Integer> brokenBody = Map.of(32, 1, 33, 2, 34, 1);
        assertEquals(2L, EntityTickOwnership.crossCheckProbe(ids, ids.length, skips,
                id -> brokenPath.getOrDefault(id, 0), id -> brokenBody.getOrDefault(id, 0)),
            "the skipped row that ran and the row that ran twice were not both found");
        assertEquals(4L, EntityTickOwnership.PROBE_CHECKED.sum());
        assertEquals(2L, EntityTickOwnership.PROBE_MATCHED.sum(),
            "the rows the two records agree about were not counted");
        assertEquals(1L, EntityTickOwnership.PROBE_SKIPPED_RAN.sum());
        assertEquals(1L, EntityTickOwnership.PROBE_RAN_TWICE.sum());
        assertEquals(0L, EntityTickOwnership.PROBE_NEVER_RAN.sum());
        assertEquals(0L, EntityTickOwnership.crossCheckProbe(ids, ids.length, skips,
                id -> id == 31 || id == 34 ? 0 : 1, id -> id == 31 || id == 34 ? 0 : 1),
            "a record the counter agrees with was reported as a disagreement");
        assertEquals(6L, EntityTickOwnership.PROBE_MATCHED.sum());
        assertEquals(3L, EntityTickOwnership.PROBE_BODY_SEEN.sum());
    }

    @Test
    void aRowTheCounterNeverSawIsADisagreement() {
        assertEquals(1L, EntityTickOwnership.crossCheckProbe(new int[] {36}, 1,
            new byte[] {0}, id -> 0, id -> 0));
        assertEquals(1L, EntityTickOwnership.PROBE_NEVER_RAN.sum(),
            "a row the entry did not skip and the counter never saw was accepted");
        assertEquals(0L, EntityTickOwnership.PROBE_MATCHED.sum());
        assertEquals(1L, EntityTickOwnership.PROBE_CHECKED.sum());
    }

    @Test
    void aRowWhoseClassSkipsTheBaseBodyIsStillSeenOnTheHostPath() {
        assertEquals(0L, EntityTickOwnership.crossCheckProbe(new int[] {37}, 1,
            new byte[] {0}, id -> 1, id -> 0));
        assertEquals(1L, EntityTickOwnership.PROBE_MATCHED.sum());
        assertEquals(1L, EntityTickOwnership.PROBE_BODY_BYPASSED.sum(),
            "a class that ticks without the base body was reported as a disagreement");
        assertEquals(0L, EntityTickOwnership.PROBE_NEVER_RAN.sum());
        assertEquals(1L, EntityTickOwnership.crossCheckProbe(new int[] {38}, 1,
            new byte[] {0}, id -> 1, id -> 2));
        assertEquals(1L, EntityTickOwnership.PROBE_RAN_TWICE.sum(),
            "a body call beyond the host path was not reported");
    }

    private static OwnershipLease lease(int capacity) {
        return EntityTickOwnership.beginLease(TICK, capacity);
    }

    private static void issue(OwnershipLease lease, int entityId, int tickCount) {
        TickState state = new TickState();
        state.appliedScale = 1.0F;
        assertTrue(lease.issue(entityId, WORLD_EPOCH, entityId, tickCount + 1, CLEAN,
            TickModels.armorStand(), state) >= 0, "the row was not issued");
    }

    private static Executor direct() {
        return Runnable::run;
    }

    private static Executor never() {
        return task -> { };
    }

    private static Executor refuse() {
        return task -> {
            throw new RejectedExecutionException("declared");
        };
    }
}