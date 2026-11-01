/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import io.izzel.arclight.common.prts.kernel.shares.OverrunRecord;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The trigger queue keeps the identity of every overrun, and the entity merge marks exactly the
 * records whose world really merged: the same-tick trigger of another world or of another class
 * stays unexecuted, a replay marks nothing, and a receipt that does not replay the frozen plan
 * marks nothing. */
class P2F05uB3TokenQueueTest {

    private static final long TICK = 11L;
    private static final long EPOCH = 4L;

    @Test
    void onlyTheMergedWorldEntityRecordIsAttributed() {
        SharePlanner shares = new SharePlanner();
        OverrunRecord entityA = trigger(shares, "b3-a", ShareClass.ENTITY);
        OverrunRecord blockA = trigger(shares, "b3-a", ShareClass.BLOCKENTITY);
        OverrunRecord entityB = trigger(shares, "b3-b", ShareClass.ENTITY);
        ArrayDeque<KernelModule.PendingB3> pending = queue(pending(shares, entityA),
            pending(shares, blockA), pending(shares, entityB));
        DegradeLadderFixture ladder = new DegradeLadderFixture();

        // The real frozen plan: world A carries two small regions that merge; world B carries one
        // region, so it has no adjacent pair and no merge of its own.
        WorkPlan source = WorkPlan.freeze(TICK, EPOCH,
            List.of(view("b3-a", 2, 0, 1), view("b3-b", 2, 0)), 1, 1L);
        WorkPlan.MergeResult merge = source.mergeAdjacent(4);
        assertEquals(1, merge.receipts().size(),
            "only the first world has an adjacent pair inside the cap");
        assertEquals("b3-a", merge.receipts().get(0).worldId());

        assertTrue(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            merge.receipts()));
        assertTrue(executed(shares, "b3-a", ShareClass.ENTITY),
            "the merged world's entity record is executed");
        assertFalse(executed(shares, "b3-a", ShareClass.BLOCKENTITY),
            "the other class of the same world did not merge");
        assertFalse(executed(shares, "b3-b", ShareClass.ENTITY),
            "the other world's entity plan did not merge");
        assertEquals(2L, shares.classOverrunCount(ShareClass.ENTITY));
        assertEquals(1L, shares.classOverrunCount(ShareClass.BLOCKENTITY));
        assertEquals(2L, shares.worldOverrunCount("b3-a"));
        assertEquals(1L, shares.worldOverrunCount("b3-b"));
        assertEquals(1L, shares.dualOverrunCount("b3-a", ShareClass.ENTITY));
        assertEquals(1L, shares.dualOverrunCount("b3-a", ShareClass.BLOCKENTITY));
        assertEquals(1L, shares.dualOverrunCount("b3-b", ShareClass.ENTITY));
        assertEquals(1L, ladder.effective());
    }

    @Test
    void everyMergedWorldIsAttributedAndAnUnmergedOneIsNot() {
        SharePlanner shares = new SharePlanner();
        OverrunRecord entityA = trigger(shares, "b3-a", ShareClass.ENTITY);
        OverrunRecord entityB = trigger(shares, "b3-b", ShareClass.ENTITY);
        OverrunRecord entityC = trigger(shares, "b3-c", ShareClass.ENTITY);
        ArrayDeque<KernelModule.PendingB3> pending = queue(pending(shares, entityA),
            pending(shares, entityB), pending(shares, entityC));
        DegradeLadderFixture ladder = new DegradeLadderFixture();

        WorkPlan source = WorkPlan.freeze(TICK, EPOCH, List.of(view("b3-a", 2, 0, 1),
            view("b3-b", 2, 0, 1), view("b3-c", 2, 0)), 1, 1L);
        WorkPlan.MergeResult merge = source.mergeAdjacent(4);
        assertEquals(2, merge.receipts().size(), "two worlds carry an adjacent pair");

        assertTrue(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            merge.receipts()));
        assertTrue(executed(shares, "b3-a", ShareClass.ENTITY));
        assertTrue(executed(shares, "b3-b", ShareClass.ENTITY));
        assertFalse(executed(shares, "b3-c", ShareClass.ENTITY),
            "the world without an adjacent pair is not attributed");
        assertEquals(1L, ladder.effective(), "the action became effective once for the tick");
    }

    @Test
    void aChainedMergeAttributesTheRunOnce() {
        SharePlanner shares = new SharePlanner();
        OverrunRecord entityA = trigger(shares, "b3-a", ShareClass.ENTITY);
        ArrayDeque<KernelModule.PendingB3> pending = queue(pending(shares, entityA));
        DegradeLadderFixture ladder = new DegradeLadderFixture();

        WorkPlan source = WorkPlan.freeze(TICK, EPOCH, List.of(view("b3-a", 2, 0, 1, 2)), 1, 1L);
        WorkPlan.MergeResult merge = source.mergeAdjacent(6);
        assertEquals(2, merge.receipts().size(), "two steps turn three tasks into one");
        assertEquals(2, merge.receipts().get(0).mergedTasks());
        assertEquals(3, merge.receipts().get(1).mergedTasks());
        assertEquals(1, merge.plan().taskCount());

        assertTrue(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            merge.receipts()));
        assertTrue(executed(shares, "b3-a", ShareClass.ENTITY));
        assertEquals(1L, ladder.effective());
        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            merge.receipts()), "a replay marks nothing new");
        assertEquals(1L, ladder.effective(), "a replay adds no effect");
    }

    @Test
    void aReplayOfTheReceiptListMarksNothingNew() {
        SharePlanner shares = new SharePlanner();
        OverrunRecord entityA = trigger(shares, "b3-a", ShareClass.ENTITY);
        long token = shares.tokenOf(entityA);
        ArrayDeque<KernelModule.PendingB3> pending = queue(pending(shares, entityA));
        DegradeLadderFixture ladder = new DegradeLadderFixture();
        WorkPlan source = WorkPlan.freeze(TICK, EPOCH, List.of(view("b3-a", 2, 0, 1)), 1, 1L);
        WorkPlan.MergeResult merge = source.mergeAdjacent(4);

        assertTrue(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            merge.receipts()));
        assertTrue(shares.isTokenExecuted(token));
        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            merge.receipts()));
        assertTrue(shares.isTokenExecuted(token));
        assertEquals(1L, ladder.effective());
    }

    @Test
    void otherClassesOfTheSameWorldStayUnexecuted() {
        SharePlanner shares = new SharePlanner();
        OverrunRecord entityA = trigger(shares, "b3-a", ShareClass.ENTITY);
        OverrunRecord regionA = trigger(shares, "b3-a", ShareClass.REGIONTICK);
        OverrunRecord blockA = trigger(shares, "b3-a", ShareClass.BLOCKENTITY);
        ArrayDeque<KernelModule.PendingB3> pending = queue(pending(shares, entityA),
            pending(shares, regionA), pending(shares, blockA));
        DegradeLadderFixture ladder = new DegradeLadderFixture();
        WorkPlan source = WorkPlan.freeze(TICK, EPOCH, List.of(view("b3-a", 2, 0, 1)), 1, 1L);
        WorkPlan.MergeResult merge = source.mergeAdjacent(4);

        assertTrue(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            merge.receipts()));
        assertTrue(executed(shares, "b3-a", ShareClass.ENTITY));
        assertFalse(executed(shares, "b3-a", ShareClass.REGIONTICK),
            "the region tick share is a different trigger row");
        assertFalse(executed(shares, "b3-a", ShareClass.BLOCKENTITY),
            "the block entity share is a different trigger row");
        assertEquals(1L, ladder.effective());
    }

    @Test
    void anEmptyMergeMarksNothingAndTheMissedRecordIsTheOnlyOneRemoved() {
        SharePlanner shares = new SharePlanner();
        OverrunRecord entityA = trigger(shares, "b3-a", ShareClass.ENTITY);
        OverrunRecord entityB = trigger(shares, "b3-b", ShareClass.ENTITY);
        ArrayDeque<KernelModule.PendingB3> pending = queue(pending(shares, entityA),
            pending(shares, entityB));
        DegradeLadderFixture ladder = new DegradeLadderFixture();
        WorkPlan source = WorkPlan.freeze(TICK, EPOCH, List.of(view("b3-a", 2, 0)), 1, 1L);
        WorkPlan.MergeResult merge = source.mergeAdjacent(4);
        assertTrue(merge.receipts().isEmpty(), "a single task has nothing to merge");

        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            merge.receipts()));
        assertFalse(executed(shares, "b3-a", ShareClass.ENTITY));
        assertFalse(executed(shares, "b3-b", ShareClass.ENTITY));
        assertEquals(0L, ladder.effective());

        long tokenA = shares.tokenOf(stored(shares, "b3-a", ShareClass.ENTITY));
        assertTrue(KernelModule.removePendingB3Token(pending, tokenA),
            "the missed merge removes the record it named");
        assertEquals(1, pending.size(), "the other world's record stays pending");
        assertEquals("b3-b", pending.peekFirst().worldId());
        assertFalse(KernelModule.removePendingB3Token(pending, 99999L));
    }

    @Test
    void receiptsOfAnotherPlanAreRejected() {
        SharePlanner shares = new SharePlanner();
        OverrunRecord entityA = trigger(shares, "b3-a", ShareClass.ENTITY);
        ArrayDeque<KernelModule.PendingB3> pending = queue(pending(shares, entityA));
        DegradeLadderFixture ladder = new DegradeLadderFixture();
        WorkPlan source = WorkPlan.freeze(TICK, EPOCH, List.of(view("b3-a", 2, 0, 1)), 1, 1L);

        WorkPlan olderSource = WorkPlan.freeze(TICK - 1L, EPOCH,
            List.of(view("b3-a", 2, 0, 1)), 1, 1L);
        WorkPlan.MergeResult olderMerge = olderSource.mergeAdjacent(4);
        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source,
            olderMerge.plan(), olderMerge.receipts()), "an older plan's receipts are not this plan's");
        WorkPlan otherSource = WorkPlan.freeze(TICK, EPOCH,
            List.of(view("b3-a", 2, 0, 1, 2)), 1, 1L);
        WorkPlan.MergeResult otherMerge = otherSource.mergeAdjacent(4);
        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source,
            otherMerge.plan(), otherMerge.receipts()),
            "a merge of another frozen plan at the same tick is not this plan's");
        assertFalse(executed(shares, "b3-a", ShareClass.ENTITY));
        assertEquals(0L, ladder.effective());
    }

    @Test
    void forgedOrDuplicatedReceiptsMarkNothing() {
        SharePlanner shares = new SharePlanner();
        OverrunRecord entityA = trigger(shares, "b3-a", ShareClass.ENTITY);
        ArrayDeque<KernelModule.PendingB3> pending = queue(pending(shares, entityA));
        DegradeLadderFixture ladder = new DegradeLadderFixture();
        WorkPlan source = WorkPlan.freeze(TICK, EPOCH, List.of(view("b3-a", 2, 0, 1)), 1, 1L);
        WorkPlan.MergeResult merge = source.mergeAdjacent(4);
        WorkPlan.MergeReceipt real = merge.receipts().get(0);

        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            List.of(real, real)), "a duplicated receipt does not replay the plan");
        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            List.of(forged(real, "b3-b", real.mergedTasks(), real.entities(), real.firstBatchId(),
                real.lastBatchId(), TICK, EPOCH))), "a foreign world is not this merge");
        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            List.of(forged(real, real.worldId(), real.mergedTasks(), real.entities(),
                real.firstBatchId(), real.lastBatchId() + 100L, TICK, EPOCH))),
            "a batch identity that is not in the source plan is not this merge");
        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            List.of(forged(real, real.worldId(), real.mergedTasks(), real.entities() + 1,
                real.firstBatchId(), real.lastBatchId(), TICK, EPOCH))),
            "an entity count the source tasks do not sum to is not this merge");
        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            List.of(forged(real, real.worldId(), real.mergedTasks(), real.entities(),
                real.firstBatchId(), real.lastBatchId(), TICK - 1L, EPOCH))),
            "a receipt stamped with another source tick is not this merge");
        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            List.of(forged(real, real.worldId(), real.mergedTasks(), real.entities(),
                real.firstBatchId(), real.lastBatchId(), TICK, EPOCH + 1L))),
            "a receipt stamped with another source epoch is not this merge");
        assertFalse(executed(shares, "b3-a", ShareClass.ENTITY));
        assertEquals(0L, ladder.effective());
    }

    @Test
    void wrongTickClassOrWorldMarksNothing() {
        SharePlanner shares = new SharePlanner();
        OverrunRecord entityA = trigger(shares, "b3-a", ShareClass.ENTITY);
        DegradeLadderFixture ladder = new DegradeLadderFixture();
        WorkPlan source = WorkPlan.freeze(TICK, EPOCH, List.of(view("b3-a", 2, 0, 1)), 1, 1L);
        WorkPlan.MergeResult merge = source.mergeAdjacent(4);

        ArrayDeque<KernelModule.PendingB3> wrongTick = queue(new KernelModule.PendingB3(
            shares.tokenOf(entityA), "b3-a", ShareClass.ENTITY, TICK + 1L));
        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), wrongTick, source,
            merge.plan(), merge.receipts()), "a trigger of another tick is not this merge");

        SharePlanner blockShares = new SharePlanner();
        OverrunRecord blockA = trigger(blockShares, "b3-a", ShareClass.BLOCKENTITY);
        ArrayDeque<KernelModule.PendingB3> wrongClass = queue(pending(blockShares, blockA));
        assertFalse(KernelModule.markB3Merge(blockShares, ladder.ladder(), wrongClass, source,
            merge.plan(), merge.receipts()), "a trigger of another class is not this merge");
        assertFalse(executed(blockShares, "b3-a", ShareClass.BLOCKENTITY));

        SharePlanner otherWorld = new SharePlanner();
        OverrunRecord entityB = trigger(otherWorld, "b3-b", ShareClass.ENTITY);
        ArrayDeque<KernelModule.PendingB3> wrongWorld = queue(pending(otherWorld, entityB));
        assertFalse(KernelModule.markB3Merge(otherWorld, ladder.ladder(), wrongWorld, source,
            merge.plan(), merge.receipts()), "a trigger of another world is not this merge");
        assertFalse(executed(otherWorld, "b3-b", ShareClass.ENTITY));
        assertEquals(0L, ladder.effective());
    }

    @Test
    void anAmbiguousIdentityIsNotAttributed() {
        SharePlanner shares = new SharePlanner();
        OverrunRecord first = trigger(shares, "b3-a", ShareClass.ENTITY);
        OverrunRecord second = trigger(shares, "b3-a", ShareClass.ENTITY);
        assertFalse(shares.tokenOf(first) == shares.tokenOf(second));
        ArrayDeque<KernelModule.PendingB3> pending = queue(pending(shares, first),
            pending(shares, second));
        assertEquals(-1L, KernelModule.pendingB3Token(pending, ShareClass.ENTITY, "b3-a", TICK),
            "two records of one identity cannot name one token");
        DegradeLadderFixture ladder = new DegradeLadderFixture();
        WorkPlan source = WorkPlan.freeze(TICK, EPOCH, List.of(view("b3-a", 2, 0, 1)), 1, 1L);
        WorkPlan.MergeResult merge = source.mergeAdjacent(4);
        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            merge.receipts()));
        ArrayDeque<KernelModule.PendingB3> repeated = queue(pending(shares, first),
            pending(shares, first));
        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), repeated, source,
            merge.plan(), merge.receipts()), "the same record queued twice is ambiguous too");
        assertFalse(executed(shares, "b3-a", ShareClass.ENTITY));
        assertEquals(0L, ladder.effective());
    }

    @Test
    void evictedUnknownOrResetTokensAreNotMarked() {
        SharePlanner shares = new SharePlanner();
        DegradeLadderFixture ladder = new DegradeLadderFixture();
        OverrunRecord entityA = trigger(shares, "b3-a", ShareClass.ENTITY);
        long tokenA = shares.tokenOf(entityA);
        for (int index = 0; index < 300; index++) {
            trigger(shares, "b3-a", ShareClass.ENTITY);
        }
        assertFalse(shares.hasToken(tokenA), "the record of the token was evicted");
        ArrayDeque<KernelModule.PendingB3> pending = queue(new KernelModule.PendingB3(tokenA,
            "b3-a", ShareClass.ENTITY, TICK));
        WorkPlan source = WorkPlan.freeze(TICK, EPOCH, List.of(view("b3-a", 2, 0, 1)), 1, 1L);
        WorkPlan.MergeResult merge = source.mergeAdjacent(4);
        assertFalse(KernelModule.markB3Merge(shares, ladder.ladder(), pending, source, merge.plan(),
            merge.receipts()), "an evicted token names no record to mark");
        assertEquals(0L, ladder.effective());

        SharePlanner resetShares = new SharePlanner();
        OverrunRecord resetRecord = trigger(resetShares, "b3-a", ShareClass.ENTITY);
        long resetToken = resetShares.tokenOf(resetRecord);
        resetShares.clearRecordTokens();
        ArrayDeque<KernelModule.PendingB3> resetPending = queue(new KernelModule.PendingB3(
            resetToken, "b3-a", ShareClass.ENTITY, TICK));
        assertFalse(KernelModule.markB3Merge(resetShares, ladder.ladder(), resetPending, source,
            merge.plan(), merge.receipts()), "a reset token names no record to mark");
        assertEquals(0L, ladder.effective());
    }

    @Test
    void theQueueIsBoundedAndDropsTheOldest() {
        ArrayDeque<KernelModule.PendingB3> pending = new ArrayDeque<>();
        for (long token = 1L; token <= KernelModule.MAX_PENDING_B3_TOKENS + 1L; token++) {
            KernelModule.addPendingB3Token(pending, new KernelModule.PendingB3(token, "b3-a",
                ShareClass.ENTITY, TICK));
        }
        assertEquals(KernelModule.MAX_PENDING_B3_TOKENS, pending.size());
        assertEquals(2L, pending.peekFirst().token());
        KernelModule.addPendingB3Token(pending, new KernelModule.PendingB3(0L, "b3-a",
            ShareClass.ENTITY, TICK));
        assertEquals(KernelModule.MAX_PENDING_B3_TOKENS, pending.size(),
            "a token that names no record is not queued");
    }

    @Test
    void theQueryNeedsTheWholeIdentityAndThePlanWorlds() {
        SharePlanner shares = new SharePlanner();
        OverrunRecord entityA = trigger(shares, "b3-a", ShareClass.ENTITY);
        OverrunRecord blockA = trigger(shares, "b3-a", ShareClass.BLOCKENTITY);
        long tokenA = shares.tokenOf(entityA);
        ArrayDeque<KernelModule.PendingB3> pending = queue(pending(shares, entityA),
            pending(shares, blockA));

        assertEquals(tokenA, KernelModule.pendingB3Token(pending, ShareClass.ENTITY, "b3-a", TICK));
        assertEquals(-1L, KernelModule.pendingB3Token(pending, ShareClass.ENTITY, "b3-b", TICK));
        assertEquals(-1L, KernelModule.pendingB3Token(pending, ShareClass.ENTITY, "b3-a",
            TICK + 1L));
        assertEquals(-1L, KernelModule.pendingB3Token(pending, ShareClass.REGIONTICK, "b3-a",
            TICK));

        WorkPlan covered = WorkPlan.freeze(TICK, EPOCH, List.of(view("b3-a", 2, 0, 1),
            view("b3-b", 2, 0)), 1, 1L);
        assertEquals(tokenA, KernelModule.pendingB3EntityToken(pending, covered));
        WorkPlan notCovered = WorkPlan.freeze(TICK, EPOCH, List.of(view("b3-c", 2, 0)), 1, 1L);
        assertEquals(-1L, KernelModule.pendingB3EntityToken(pending, notCovered),
            "a plan that does not carry the record's world has no entity trigger");
    }

    private static ShareTable.ShareRow over(String worldId, ShareClass shareClass) {
        return new ShareTable.ShareRow(worldId, shareClass, 1.0, 2.0);
    }

    private static OverrunRecord trigger(SharePlanner shares, String worldId, ShareClass shareClass) {
        OverrunRecord record = shares.record(over(worldId, shareClass), "runtime", TICK);
        shares.recordWouldDegrade(record);
        return record;
    }

    private static KernelModule.PendingB3 pending(SharePlanner shares, OverrunRecord record) {
        long token = shares.tokenOf(record);
        assertTrue(token > 0L, "a recorded trigger has a token");
        return new KernelModule.PendingB3(token, record.worldId(), record.overClass(),
            record.tickIndex());
    }

    private static ArrayDeque<KernelModule.PendingB3> queue(KernelModule.PendingB3... records) {
        ArrayDeque<KernelModule.PendingB3> pending = new ArrayDeque<>();
        for (KernelModule.PendingB3 record : records) {
            KernelModule.addPendingB3Token(pending, record);
        }
        return pending;
    }

    private static OverrunRecord stored(SharePlanner shares, String worldId, ShareClass shareClass) {
        for (OverrunRecord record : shares.records()) {
            if (record.tickIndex() == TICK && record.worldId().equals(worldId)
                && record.overClass() == shareClass) {
                return record;
            }
        }
        throw new IllegalStateException("the trigger record is not stored");
    }

    private static boolean executed(SharePlanner shares, String worldId, ShareClass shareClass) {
        return stored(shares, worldId, shareClass).actionExecuted();
    }

    private static WorkPlan.MergeReceipt forged(WorkPlan.MergeReceipt real, String worldId,
                                                int mergedTasks, int entities, long firstBatchId,
                                                long lastBatchId, long sourcePlanTick,
                                                long sourcePlanEpoch) {
        return new WorkPlan.MergeReceipt(worldId, mergedTasks, entities, firstBatchId, lastBatchId,
            sourcePlanTick, sourcePlanEpoch);
    }

    private static EntityCandidateView view(String worldId, int perRegion, int... regions) {
        EntityCandidateView.Builder builder = EntityCandidateView.builder(worldId, EPOCH);
        long seq = 1L;
        for (int region : regions) {
            for (int index = 0; index < perRegion; index++) {
                builder.add(seq++, region, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
            }
        }
        return builder.build();
    }

    /** A ladder that may act, entered at the rung the merge belongs to. */
    private static final class DegradeLadderFixture {

        private final io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder ladder =
            new io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder(() -> true);

        private DegradeLadderFixture() {
            ladder.noteEntered(DegradeLevel.B3, TICK);
        }

        private io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder ladder() {
            return ladder;
        }

        private long effective() {
            return ladder.counters(DegradeLevel.B3).effective();
        }
    }
}
