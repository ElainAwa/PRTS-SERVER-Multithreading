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

/** The ownership fixture lifecycle: one frozen capability per claimed row, one outcome per claim, a
 * claim that cannot be used handed back in front of the host, and no lease that outlives its tick.
 * The three generations of a claim - of the row, of its world and of its segment - are checked
 * before a row is skipped, and a row that fails one of them runs on the host. */
class EntityTickOwnershipTest {

    private static final long TICK = 500L;
    private static final long WORLD_EPOCH = 7L;
    private static final long SEGMENT_EPOCH = 3L;
    private static final String WORLD = "minecraft:overworld";
    private static final byte CLEAN = 0;

    @BeforeEach
    void clearCounters() {
        EntityTickOwnership.reset();
        // The latch is deliberately not cleared by the readout reset: a failed process stays
        // disarmed. A test that drives a failure re-arms explicitly for the next case.
        EntityTickOwnership.rearmForTests();
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
        assertEquals(46, fields.size(), "the field set changed");
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
        SegmentWork segment = segment();
        OwnershipLease lease = lease(1);
        issue(lease, segment, 11, 40, true);
        lease.publish(direct(), 1);
        EntityTickOwnership.install(lease, segment);
        assertEquals(OwnershipLease.SETTLED, lease.state(0));
        assertTrue(decide(segment, live(11, 41, CLEAN, 0.0)) >= 0,
            "a settled token did not skip its row");
        assertEquals(OwnershipLease.CONSUMED, lease.state(0), "the spent token stayed usable");
        EntityTickOwnership.closeInstalled();
        assertEquals(1L, EntityTickOwnership.HOST_SKIPPED.sum());
        assertEquals(0L, EntityTickOwnership.HOST_EXECUTED.sum(), "a skipped row also ran");
        assertEquals(0L, EntityTickOwnership.ELIGIBLE.sum());
        assertEquals(1L, EntityTickOwnership.CANDIDATES.sum());
        assertEquals(0L, EntityTickOwnership.TOKEN_LEAK.sum());
        assertEquals(1L, EntityTickOwnership.ISSUED.sum() - EntityTickOwnership.WITHDRAWN.sum(),
            "the row counted as owned is not the row that was skipped");
        assertAccountsClose();
    }

    @Test
    void anUnsettledTokenIsHandedBackBeforeTheHostContinues() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(1);
        issue(lease, segment, 12, 40, true);
        lease.publish(never(), 1);
        EntityTickOwnership.install(lease, segment);
        assertEquals(OwnershipLease.PENDING, lease.state(0));
        assertTrue(decide(segment, live(12, 41, CLEAN, 0.0)) < 0,
            "a row without an answer was skipped");
        assertEquals(1L, EntityTickOwnership.ELIGIBLE.sum(), "the fallback row is not the eligible one");
        assertEquals(1L, EntityTickOwnership.FALLBACK.sum(), "the withdrawal was not counted");
        assertEquals(1L, EntityTickOwnership.CLAIM_EXECUTED.sum(),
            "a claim handed back without an answer was not booked as executed");
        assertEquals(0L, EntityTickOwnership.HOST_SKIPPED.sum());
        assertEquals(1L, EntityTickOwnership.HOST_EXECUTED.sum(),
            "a row whose claim was handed back was not counted as one the host runs");
        EntityTickOwnership.closeInstalled();
        assertEquals(0L, EntityTickOwnership.NOT_ENTERED.sum(),
            "a row that reached the host entry was recycled as one that did not");
        assertEquals(0L, EntityTickOwnership.TOKEN_LEAK.sum());
        assertAccountsClose();
    }

    @Test
    void aFailedWorkerHandsTheRowBackAndAnswersAreNeverAppliedLate() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(2);
        issue(lease, segment, 13, 40, true);
        issue(lease, segment, 14, 40, true);
        lease.publish(refuse(), 1);
        EntityTickOwnership.install(lease, segment);
        assertEquals(OwnershipLease.FAILED, lease.state(0), "a refused chunk was not failed");
        assertEquals(OwnershipLease.FAILED, lease.state(1));
        assertTrue(decide(segment, live(13, 41, CLEAN, 0.0)) < 0);
        assertTrue(decide(segment, live(14, 41, CLEAN, 0.0)) < 0);
        assertEquals(2L, EntityTickOwnership.FALLBACK.sum());
        assertEquals(2L, EntityTickOwnership.ELIGIBLE.sum());
        assertEquals(2L, EntityTickOwnership.CLAIM_EXECUTED.sum());
        assertEquals(0L, EntityTickOwnership.HOST_SKIPPED.sum());
        EntityTickOwnership.closeInstalled();
        assertEquals(2L, EntityTickOwnership.CANDIDATES.sum());
        assertEquals(2L, EntityTickOwnership.HOST_EXECUTED.sum());
        assertEquals(0L, EntityTickOwnership.TOKEN_LEAK.sum());
        assertAccountsClose();
    }

    @Test
    void aTokenThatNeverReachesTheHostEntryIsRecycledAtTheClose() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(2);
        issue(lease, segment, 15, 40, true);
        issue(lease, segment, 16, 40, true);
        lease.publish(never(), 1);
        EntityTickOwnership.install(lease, segment);
        EntityTickOwnership.closeInstalled();
        assertEquals(2L, EntityTickOwnership.NOT_ENTERED.sum());
        assertEquals(2L, EntityTickOwnership.WITHDRAWN.sum());
        assertEquals(0L, EntityTickOwnership.TOKEN_LEAK.sum(), "a lease outlived its tick");
        assertEquals(0L, EntityTickOwnership.CANDIDATES.sum());
        assertEquals(0L, EntityTickOwnership.HOST_EXECUTED.sum());
        assertEquals(0.0, EntityTickOwnership.coverage());
        assertAccountsClose();
    }

    @Test
    void aSecondEntryOfOneRowIsAConflictAndTheRowRuns() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(1);
        issue(lease, segment, 17, 40, true);
        lease.publish(direct(), 1);
        EntityTickOwnership.install(lease, segment);
        assertTrue(decide(segment, live(17, 41, CLEAN, 0.0)) >= 0);
        assertTrue(decide(segment, live(17, 41, CLEAN, 0.0)) < 0,
            "a second host entry of one row skipped it again");
        assertEquals(1L, EntityTickOwnership.OWNER_CONFLICT.sum());
        assertEquals(0L, EntityTickOwnership.HOST_SKIPPED.sum(),
            "the row stayed skipped and also ran");
        assertEquals(1L, EntityTickOwnership.ELIGIBLE.sum());
        assertEquals(1L, EntityTickOwnership.FALLBACK.sum());
        assertEquals(1L, EntityTickOwnership.CLAIM_REVOKED.sum(),
            "the claim undone by the conflict was not booked as revoked");
        assertEquals(1L, EntityTickOwnership.HOST_EXECUTED.sum(),
            "the row the conflict handed back was not counted as one the host runs");
        EntityTickOwnership.closeInstalled();
        assertEquals(0L, EntityTickOwnership.TOKEN_LEAK.sum());
        assertAccountsClose();
    }

    @Test
    void aTokenThatNoLongerMatchesItsRowIsWithdrawn() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(2);
        issue(lease, segment, 18, 40, true, WORLD_EPOCH + 1L, SEGMENT_EPOCH);
        issue(lease, segment, 19, 40, true);
        lease.publish(direct(), 1);
        EntityTickOwnership.install(lease, segment);
        assertTrue(decide(segment, live(18, 41, CLEAN, 0.0)) < 0,
            "a claim of another generation skipped its row");
        assertEquals(1L, EntityTickOwnership.LIFECYCLE_REJECTED.sum());
        assertEquals(1L, EntityTickOwnership.CLAIM_REVOKED.sum());
        assertEquals(0L, EntityTickOwnership.CLAIM_EXECUTED.sum());
        assertTrue(decide(segment, live(19, 41, CLEAN, 0.0)) >= 0,
            "a claim that still matched its row was not used to skip it");
        assertTrue(decide(segment, live(20, 41, CLEAN, 0.0)) < 0,
            "a row nobody claimed was skipped");
        assertEquals(1L, EntityTickOwnership.NEVER_CLAIMED.sum(),
            "the row nobody claimed was not counted as one the fixture never held");
        assertEquals(2L, EntityTickOwnership.HOST_EXECUTED.sum(),
            "the rows the host runs were not all counted");
        assertEquals(1L, EntityTickOwnership.HOST_SKIPPED.sum());
        EntityTickOwnership.closeInstalled();
        assertEquals(3L, EntityTickOwnership.CANDIDATES.sum());
        assertAccountsClose();
    }

    @Test
    void aRowThePlanPointCouldNotVouchForIsHandedBack() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(1);
        issue(lease, segment, 25, 40, false);
        lease.publish(direct(), 1);
        EntityTickOwnership.install(lease, segment);
        assertTrue(decide(segment, live(25, 41, CLEAN, 0.0)) < 0,
            "a row the frozen capability did not vouch for was skipped");
        assertEquals(1L, EntityTickOwnership.CLAIM_REVOKED.sum());
        assertEquals(1L, EntityTickOwnership.HOST_EXECUTED.sum());
        EntityTickOwnership.closeInstalled();
        assertAccountsClose();
    }

    @Test
    void aCapabilityThatMovedBeforeTheEntryIsWithdrawn() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(1);
        issue(lease, segment, 26, 40, true);
        lease.publish(direct(), 1);
        EntityTickOwnership.install(lease, segment);
        assertTrue(decide(segment, live(26, 41, CLEAN, 1.5)) < 0,
            "a row that moved after the plan point kept its claim");
        assertEquals(1L, EntityTickOwnership.CLAIM_REVOKED.sum());
        EntityTickOwnership.closeInstalled();
        assertAccountsClose();
    }

    @Test
    void theAccountsCloseOnAMixtureOfAllFourOutcomes() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(5);
        issue(lease, segment, 21, 40, true);
        issue(lease, segment, 22, 40, true, WORLD_EPOCH + 1L, SEGMENT_EPOCH);
        issue(lease, segment, 23, 40, true);
        issue(lease, segment, 24, 40, true);
        issue(lease, segment, 27, 40, true);
        lease.publish(refuse(), 1);
        EntityTickOwnership.install(lease, segment);
        assertTrue(decide(segment, live(21, 41, CLEAN, 0.0)) < 0);
        assertTrue(decide(segment, live(22, 41, CLEAN, 0.0)) < 0);
        EntityTickOwnership.closeInstalled();
        assertEquals(5L, EntityTickOwnership.CLAIMED.sum());
        assertEquals(3L, EntityTickOwnership.NOT_ENTERED.sum());
        assertEquals(2L, EntityTickOwnership.ELIGIBLE.sum());
        assertEquals(1L, EntityTickOwnership.CLAIM_EXECUTED.sum());
        assertEquals(1L, EntityTickOwnership.CLAIM_REVOKED.sum());
        assertEquals(5L, EntityTickOwnership.WITHDRAWN.sum(),
            "the withdrawn claims and the recycled leases are not both counted");
        assertEquals(0L, EntityTickOwnership.HOST_SKIPPED.sum());
        assertEquals(2L, EntityTickOwnership.HOST_EXECUTED.sum());
        assertEquals(2L, EntityTickOwnership.CANDIDATES.sum());
        assertEquals(0L, EntityTickOwnership.TOKEN_LEAK.sum());
        assertEquals(0L, EntityTickOwnership.ISSUED.sum() - EntityTickOwnership.WITHDRAWN.sum(),
            "a row that never kept its token counted as owned");
        assertAccountsClose();
    }

    @Test
    void aRowOfAnotherGenerationOfTheRowItselfIsHandedBack() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(1);
        issue(lease, segment, 28, 40, true);
        lease.publish(direct(), 1);
        EntityTickOwnership.install(lease, segment);
        // The kernel generation recorded for the id is not the one the row was frozen under.
        EntityTickOwnership.noteEntityEpoch(28, 999L);
        assertTrue(decide(segment, live(28, 41, CLEAN, 0.0)) < 0,
            "a row whose entity generation changed kept its claim");
        assertEquals(1L, EntityTickOwnership.CLAIM_REVOKED.sum());
        assertEquals(1L, EntityTickOwnership.HOST_EXECUTED.sum());
        EntityTickOwnership.closeInstalled();
        assertEquals(1L, EntityTickOwnership.LAYER_ENTITY_REJECTED.sum(),
            "the entity generation was not the layer that rejected the row");
        assertAccountsClose();
    }

    @Test
    void aRowOfAnotherWorldGenerationIsHandedBack() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(1);
        issue(lease, segment, 29, 40, true, WORLD_EPOCH + 1L, SEGMENT_EPOCH);
        lease.publish(direct(), 1);
        EntityTickOwnership.install(lease, segment);
        assertTrue(decide(segment, live(29, 41, CLEAN, 0.0)) < 0,
            "a row frozen under another world generation kept its claim");
        assertEquals(1L, EntityTickOwnership.HOST_EXECUTED.sum());
        EntityTickOwnership.closeInstalled();
        assertEquals(1L, EntityTickOwnership.LAYER_WORLD_REJECTED.sum(),
            "the world generation was not the layer that rejected the row");
        assertAccountsClose();
    }

    @Test
    void aRowOfAnotherSegmentGenerationIsHandedBack() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(1);
        issue(lease, segment, 30, 40, true, WORLD_EPOCH, SEGMENT_EPOCH + 1L);
        lease.publish(direct(), 1);
        EntityTickOwnership.install(lease, segment);
        assertTrue(decide(segment, live(30, 41, CLEAN, 0.0)) < 0,
            "a row frozen under another segment generation kept its claim");
        assertEquals(1L, EntityTickOwnership.HOST_EXECUTED.sum());
        EntityTickOwnership.closeInstalled();
        assertEquals(1L, EntityTickOwnership.LAYER_SEGMENT_REJECTED.sum(),
            "the segment generation was not the layer that rejected the row");
        assertAccountsClose();
    }

    @Test
    void aRowTheHostPassedOutOfOrderIsHandedBack() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(2);
        issue(lease, segment, 41, 40, true);
        issue(lease, segment, 42, 40, true);
        lease.publish(direct(), 1);
        EntityTickOwnership.install(lease, segment);
        assertTrue(decide(segment, live(42, 41, CLEAN, 0.0)) >= 0,
            "the first row of the frozen order was not skipped");
        assertTrue(decide(segment, live(41, 41, CLEAN, 0.0)) < 0,
            "a row the host had already passed in the frozen order was skipped");
        assertEquals(1L, EntityTickOwnership.HOST_SKIPPED.sum());
        EntityTickOwnership.closeInstalled();
        assertEquals(1L, EntityTickOwnership.ORDINAL_VIOLATIONS.sum(),
            "the host order violation was not counted");
        assertAccountsClose();
    }

    @Test
    void anObservedRowRunsOnTheHostAndIsNeverAnOwnershipRow() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(1);
        issue(lease, segment, 43, 40, true);
        segment.observe(44, 44L);
        lease.publish(never(), 1);
        EntityTickOwnership.install(lease, segment);
        assertTrue(decide(segment, live(44, 41, CLEAN, 0.0)) < 0,
            "a row nobody claimed was skipped");
        assertEquals(1L, EntityTickOwnership.NEVER_CLAIMED.sum(),
            "the observed row was not booked as one the fixture never held");
        assertEquals(0L, EntityTickOwnership.HOST_SKIPPED.sum());
        EntityTickOwnership.closeInstalled();
        assertEquals(1L, EntityTickOwnership.OBSERVED_ROWS.sum());
        assertEquals(1L, EntityTickOwnership.OBSERVED_ENTRIES.sum(),
            "the observed row that ran was not counted on the observation face");
        assertEquals(0L, EntityTickOwnership.UNBOOKED_COMMITS.sum());
        assertEquals(0L, EntityTickOwnership.SET_CONFLICTS.sum());
        assertAccountsClose();
    }

    @Test
    void aRowBookedInBothSetsIsReportedByTheFrame() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(1);
        issue(lease, segment, 45, 40, true);
        segment.conflictForFault(45, 45L, 0);
        lease.publish(never(), 1);
        EntityTickOwnership.install(lease, segment);
        EntityTickOwnership.closeInstalled();
        assertEquals(1L, EntityTickOwnership.SET_CONFLICTS.sum(),
            "the row booked as owned and observed was not reported");
        assertEquals(1L, EntityTickOwnership.BROKEN_FRAMES.sum());
        assertEquals(45, segment.conflictingEntity());
        assertEquals(0, segment.conflictingOrdinal());
        assertEquals(1L, EntityTickOwnership.OWNED_ROWS.sum());
        assertEquals(1L, EntityTickOwnership.OBSERVED_ROWS.sum());
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
            "a class that ticks without reaching the base body was reported as a disagreement");
        assertEquals(0L, EntityTickOwnership.PROBE_NEVER_RAN.sum());
        assertEquals(1L, EntityTickOwnership.crossCheckProbe(new int[] {38}, 1,
            new byte[] {0}, id -> 1, id -> 2));
        assertEquals(1L, EntityTickOwnership.PROBE_RAN_TWICE.sum(),
            "a body call beyond the host path was not reported");
    }

    @Test
    void oneValueIsWrittenPerTimelineColumn() {
        assertEquals(EntityTickOwnership.TIMELINE_COLUMNS.length,
            EntityTickOwnership.timelineRow(0).length,
            "the timeline row and its header do not have the same number of columns");
        assertTrue(EntityTickOwnership.TIMELINE_COLUMNS.length >= 20,
            "a column of the earlier batches disappeared");
    }

    @Test
    void aBrokenAccountDisarmsTheFixtureAndTheNextRowRunsOnTheHost() {
        assertFalse(EntityTickOwnership.latched(), "the latch started closed");
        // One skip the claim ledger does not know about: the accounts no longer close.
        EntityTickOwnership.HOST_SKIPPED.increment();
        EntityTickOwnership.closeInstalled();
        assertTrue(EntityTickOwnership.latched(), "a broken account did not disarm the fixture");
        assertEquals(1L, EntityTickOwnership.DISARMS.sum(), "the disarm was not counted once");
        assertTrue(EntityTickOwnership.evidenceLine().contains("disarms=1"),
            "the evidence line does not report the disarm");
        // The readout reset must not re-arm what a failure closed.
        EntityTickOwnership.reset();
        assertTrue(EntityTickOwnership.latched(), "the readout reset released the latch");
        assertFalse(EntityTickOwnership.evidenceLine().contains("armed=1"),
            "the evidence line claims an armed fixture after a failure");
    }

    @Test
    void aSecondEntryOfOneRowDisarmsTheFixture() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(1);
        issue(lease, segment, 51, 40, true);
        lease.publish(direct(), 1);
        EntityTickOwnership.install(lease, segment);
        assertTrue(decide(segment, live(51, 41, CLEAN, 0.0)) >= 0);
        assertTrue(decide(segment, live(51, 41, CLEAN, 0.0)) < 0);
        assertTrue(EntityTickOwnership.latched(), "a second entry of one row did not disarm");
        assertEquals(1L, EntityTickOwnership.DISARMS.sum());
    }

    @Test
    void aFrameThatDoesNotCloseDisarmsTheFixture() {
        SegmentWork segment = segment();
        OwnershipLease lease = lease(1);
        issue(lease, segment, 52, 40, true);
        segment.conflictForFault(52, 52L, 0);
        lease.publish(never(), 1);
        EntityTickOwnership.install(lease, segment);
        EntityTickOwnership.closeInstalled();
        assertEquals(1L, EntityTickOwnership.SET_CONFLICTS.sum());
        assertTrue(EntityTickOwnership.latched(), "a frame in both row sets did not disarm");
        assertEquals(1L, EntityTickOwnership.DISARMS.sum());
    }

    @Test
    void theTakeoverLineCarriesTheFiveQuantitiesOfTheBatch() {
        String line = EntityTickOwnership.takeoverLine("cafebabe", "prts-state-fnv1a64-bitexact-v1");
        for (String quantity : new String[] {"host_skip=", "host_execute=", "fallback=",
            "commit=", "hash=cafebabe"}) {
            assertTrue(line.contains(quantity), "the takeover line does not carry " + quantity);
        }
        assertTrue(line.contains("algorithm=prts-state-fnv1a64-bitexact-v1"),
            "the hash is published without the algorithm that produced it");
    }

    @Test
    void theNetReadingChargesTheFixedCostToTheEntryRow() {
        assertEquals(0.0, EntityTickOwnership.netPerUnit(), "a run that took nothing over has no net");
        assertEquals(0.0, EntityTickOwnership.netPerEntryRow());
        EntityTickOwnership.PLAN_NANOS.add(300L);
        EntityTickOwnership.ENTRY_NANOS.add(700L);
        EntityTickOwnership.COMMIT_NANOS.add(500L);
        EntityTickOwnership.HOST_SKIPPED.add(5L);
        assertEquals(300.0, EntityTickOwnership.kCarriedNanos(), 1.0e-9,
            "the per-carried-row cost is not the host-thread cost over the skipped rows");
    }

    @Test
    void theWhitelistCountsEveryClassAndNeverClaimsARowOutsideIt() {
        TickModels.all();
        for (int slot = 0; slot < TakeoverWhitelist.SLOTS; slot++) {
            TakeoverWhitelist.noteSeen(slot, null);
            TakeoverWhitelist.noteClaimed(slot);
            TakeoverWhitelist.noteEntry(slot, true);
            assertEquals(1L, TakeoverWhitelist.claimed(slot));
            assertEquals(1L, TakeoverWhitelist.skipped(slot));
        }
        TakeoverWhitelist.noteSeen(TickModels.OUTSIDE, String.class);
        TakeoverWhitelist.noteClaimed(TickModels.OUTSIDE);
        assertTrue(TakeoverWhitelist.evidenceLine().contains("outside_claimed=1"),
            "a claim outside the whitelist was not reported");
        TakeoverWhitelist.reset();
        assertTrue(TakeoverWhitelist.evidenceLine().contains("outside_claimed=0"),
            "the outside claim counter is not empty after a reset");
        for (int slot = 0; slot < TakeoverWhitelist.SLOTS; slot++) {
            assertEquals(0L, TakeoverWhitelist.claimed(slot));
        }
    }

    /** Every equation the fixture checks on a frame has to hold once the tick closed. */
    private static void assertAccountsClose() {
        assertEquals(0L, EntityTickOwnership.INVARIANT_VIOLATIONS.sum(), "the accounts do not close");
        assertTrue(EntityTickOwnership.claimLedgerOk(), "the claim ledger does not close");
        assertTrue(EntityTickOwnership.closureOk(), "the issued tokens are not accounted for");
        assertTrue(EntityTickOwnership.withdrawalOk(), "the withdrawals are not accounted for");
        assertTrue(EntityTickOwnership.partitionOk(), "the partition of the host entries is broken");
        assertTrue(EntityTickOwnership.hostPathOk(), "the host path of the tick is not accounted for");
    }

    private static SegmentWork segment() {
        return EntityTickOwnership.beginSegment(TICK, WORLD, WORLD_EPOCH, SEGMENT_EPOCH);
    }

    private static OwnershipLease lease(int capacity) {
        return EntityTickOwnership.beginLease(TICK, capacity);
    }

    private static void issue(OwnershipLease lease, SegmentWork segment, int entityId, int tickCount,
        boolean eligible) {
        issue(lease, segment, entityId, tickCount, eligible, WORLD_EPOCH, SEGMENT_EPOCH);
    }

    /** Issues one row into the lease and books it in the segment, exactly as the plan point does. */
    private static void issue(OwnershipLease lease, SegmentWork segment, int entityId, int tickCount,
        boolean eligible, long worldEpoch, long segmentEpoch) {
        TickState state = new TickState();
        state.appliedScale = 1.0F;
        int ordinal = segment.claim(entityId, entityId, uuidHigh(entityId), uuidLow(entityId));
        segment.readSet().freezeNeighbourVerdict(ordinal, true);
        OwnershipLease.EntityCapability capability = new OwnershipLease.EntityCapability(entityId,
            entityId, worldEpoch, tickCount + 1, true, eligible, CLEAN, 0.0, 0.0, 0.0,
            segmentEpoch, ordinal);
        int index = lease.issue(capability, TickModels.armorStand(), state);
        assertTrue(index >= 0, "the row was not issued");
        segment.book(ordinal, lease.token(index));
    }

    private static int decide(SegmentWork segment, EntityTickOwnership.LiveRow live) {
        return EntityTickOwnership.decideRow(live, segment);
    }

    private static EntityTickOwnership.LiveRow live(int entityId, int tickVersion, byte fingerprint,
        double xo) {
        return new EntityTickOwnership.LiveRow(entityId, uuidHigh(entityId), uuidLow(entityId),
            tickVersion, fingerprint, xo, 0.0, 0.0);
    }

    private static long uuidHigh(int entityId) {
        return 0x1000_0000L + entityId;
    }

    private static long uuidLow(int entityId) {
        return 0x2000_0000L + entityId;
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
