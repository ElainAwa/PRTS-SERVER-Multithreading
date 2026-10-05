/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The single write entry: the order it accepts, the order it refuses, the bounded ring and the
 * comparison a replayed run is judged by. */
class CommitLogTest {

    private static final String WORLD = "world-a";

    /** A plan-shaped order: two steps of one pair, newest plan second. */
    private static CommitLog.OrderSource order() {
        return (worldId, domainId, nodeKey) -> {
            if (!worldId.equals(WORLD) || !domainId.equals("entity")) {
                return null;
            }
            return switch (nodeKey) {
                case "a/0" -> new CommitLog.Resolved(1L, 0, false);
                case "a/1" -> new CommitLog.Resolved(1L, 1, false);
                case "a/2" -> new CommitLog.Resolved(2L, 0, false);
                default -> null;
            };
        };
    }

    private static CommitLog.Batch batch(String key, CommitLog.Batch.Kind kind, long digest) {
        return new CommitLog.Batch(WORLD, "entity", key, kind, 1L, digest, 2);
    }

    @Test
    void thePlannedOrderIsAcceptedAndARisingOrderKeepsRising() {
        CommitLog log = new CommitLog(() -> 8, order());
        log.beginTick(10L);

        CommitLog.Verdict first = log.reach(batch("a/0", CommitLog.Batch.Kind.APPLY, 11L));
        CommitLog.Verdict second = log.reach(batch("a/1", CommitLog.Batch.Kind.MERGE, 12L));
        CommitLog.Verdict nextPlan = log.reach(batch("a/2", CommitLog.Batch.Kind.APPLY, 13L));

        assertTrue(first.logged());
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, first.disposition());
        assertEquals(CommitLog.Entry.Disposition.MERGED, second.disposition());
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, nextPlan.disposition());
        assertEquals(3L, log.accepted() + log.merged());
        assertEquals(0L, log.orderViolations());
        assertEquals(1, log.ringCount());
    }

    @Test
    void aCommitThatContradictsThePlannedOrderIsCountedAndDropped() {
        CommitLog log = new CommitLog(() -> 8, order());
        log.beginTick(10L);
        log.reach(batch("a/1", CommitLog.Batch.Kind.APPLY, 11L));

        CommitLog.Verdict back = log.reach(batch("a/0", CommitLog.Batch.Kind.APPLY, 12L));

        assertTrue(back.logged());
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, back.code());
        assertEquals(CommitLog.Entry.Disposition.DROPPED, back.disposition());
        assertEquals(1L, log.orderViolations());
        assertEquals(1L, log.dropped());
        assertEquals(0L, log.firstDivergencePosition());
    }

    @Test
    void aCommitNoPlanOrderedIsCountedAndDropped() {
        CommitLog log = new CommitLog(() -> 8, order());
        log.beginTick(10L);

        CommitLog.Verdict unplanned = log.reach(batch("nobody", CommitLog.Batch.Kind.APPLY, 11L));

        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, unplanned.code());
        assertEquals(1L, log.unplanned());
        assertEquals(1L, log.dropped());
        assertEquals(0L, log.orderViolations());
    }

    @Test
    void anIntentIsLoggedWithoutAPlannedPosition() {
        CommitLog log = new CommitLog(() -> 8, order());
        log.beginTick(10L);

        CommitLog.Verdict intent = log.reach(new CommitLog.Batch(WORLD, "intent", WORLD + "/intent",
            CommitLog.Batch.Kind.INTENT, 3L, 3L, 1));

        assertTrue(intent.logged());
        assertEquals(CommitLog.Entry.Disposition.INTENT, intent.disposition());
        assertNull(intent.code());
        assertEquals(1L, log.intents());
        assertEquals(0L, log.unplanned());
        assertEquals(0L, log.orderViolations());
    }

    @Test
    void aFullRingRefusesInsteadOfGrowing() {
        CommitLog log = new CommitLog(() -> 1, order());
        log.beginTick(10L);
        assertTrue(log.reach(batch("a/0", CommitLog.Batch.Kind.APPLY, 11L)).logged());

        CommitLog.Verdict full = log.reach(batch("a/1", CommitLog.Batch.Kind.APPLY, 12L));

        assertFalse(full.logged());
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, full.code());
        assertEquals(1L, log.refusedFull());
        assertEquals(1, log.ringDepth());
        assertEquals(1, log.ringCapacity());
    }

    @Test
    void theTraversalReadsTheRingsInAFixedOrderAndFoldsWhatItRead() {
        CommitLog log = new CommitLog(() -> 8, order());
        log.beginTick(10L);
        log.reach(batch("a/0", CommitLog.Batch.Kind.APPLY, 11L));
        log.reach(batch("a/1", CommitLog.Batch.Kind.APPLY, 12L));

        CommitLog.Replay replay = log.closeTick();

        assertEquals(2, replay.loggedSteps());
        assertEquals(2, replay.steps().size());
        assertEquals(1L, replay.steps().get(0).planSequence());
        assertEquals(0, replay.steps().get(0).position());
        assertEquals(1, replay.steps().get(1).position());
        assertTrue(replay.orderMatches());
        assertEquals(0, log.ringDepth());
        assertEquals(1L, log.ticks());
    }

    @Test
    void aRepeatedRunComparesEqualAndAChangedOneDoesNot() {
        CommitLog first = new CommitLog(() -> 8, order());
        first.beginTick(10L);
        first.reach(batch("a/0", CommitLog.Batch.Kind.APPLY, 11L));
        CommitLog.Replay firstRun = first.closeTick();

        CommitLog replayed = new CommitLog(() -> 8, order());
        replayed.beginTick(10L);
        replayed.reach(batch("a/0", CommitLog.Batch.Kind.APPLY, 11L));
        CommitLog.Replay sameRun = replayed.closeTick();

        CommitLog changed = new CommitLog(() -> 8, order());
        changed.beginTick(10L);
        changed.reach(batch("a/0", CommitLog.Batch.Kind.APPLY, 99L));
        CommitLog.Replay changedRun = changed.closeTick();

        assertEquals(0, CommitLog.compareRuns(firstRun, sameRun));
        assertEquals(1, CommitLog.compareRuns(firstRun, changedRun));
        assertEquals(-1, CommitLog.compareRuns(firstRun, null));
    }

    @Test
    void aReportBeyondTheRingCapacityOfThePairIsStillCounted() {
        CommitLog log = new CommitLog(() -> 2, order());
        log.beginTick(10L);
        log.reach(batch("a/0", CommitLog.Batch.Kind.RETRY, 11L));
        log.reach(batch("a/1", CommitLog.Batch.Kind.DROP, 12L));

        assertEquals(1L, log.retried());
        assertEquals(1L, log.dropped());
        assertNotNull(log.closeTick());
    }
}
