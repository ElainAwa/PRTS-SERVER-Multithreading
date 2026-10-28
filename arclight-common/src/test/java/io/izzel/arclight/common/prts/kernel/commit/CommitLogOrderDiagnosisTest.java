/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The order one pair enforces at the commit log, isolated from the producer: the real {@link CommitLog}
 * and its ring, a synthetic order source, and the two trajectories the run diagnosis names. The
 * seats here are named by the order source, not by the raw run: this test proves the count and the
 * order semantics and the two possible trajectories, never which raw batch or node key produced them.
 *
 * <p>It also fixes the eleven-report shape of the run: eight accepted, three dropped (one unplanned,
 * two order violations), with the ring pushed and popped counts folding the same eleven lines.
 */
class CommitLogOrderDiagnosisTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "entity";
    private static final long LIFECYCLE = 1L;

    private static CommitLog.Resolved at(long planSequence, int position) {
        return new CommitLog.Resolved(planSequence, position, false);
    }

    private static CommitLog.Batch batch(String nodeKey) {
        return new CommitLog.Batch(WORLD, DOMAIN, nodeKey, CommitLog.Batch.Kind.APPLY, 7L, 4242L, 2);
    }

    /** An order source over synthetic seat names; an absent key is a line no plan ordered. */
    private static CommitLog.OrderSource order(Map<String, CommitLog.Resolved> steps) {
        return new CommitLog.OrderSource() {

            @Override
            public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
                return WORLD.equals(worldId) && DOMAIN.equals(domainId) ? steps.get(nodeKey) : null;
            }

            @Override
            public CommitLog.Resolved resolveAt(long planSequence, long lifecycle, String worldId,
                                                String domainId, String nodeKey) {
                return WORLD.equals(worldId) && DOMAIN.equals(domainId)
                    ? steps.get(nodeKey) : null;
            }
        };
    }

    private static CommitLog log(Map<String, CommitLog.Resolved> steps) {
        return new CommitLog(() -> 16, order(steps));
    }

    private static void assertOrderDrop(CommitLog.Verdict verdict) {
        assertTrue(verdict.logged(), "a dropped order line is stored before it is judged");
        assertEquals(CommitLog.Entry.Disposition.DROPPED, verdict.disposition());
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, verdict.code());
    }

    @Test
    void anOlderPlanAfterANewerPlanIsDroppedAndCounted() {
        Map<String, CommitLog.Resolved> steps = new LinkedHashMap<>();
        steps.put("new/5", at(2L, 5));
        steps.put("old/1", at(1L, 1));
        CommitLog log = log(steps);
        log.beginTick(10L);

        CommitLog.Verdict accepted = log.reach(batch("new/5"), 2L, 5, LIFECYCLE);
        assertTrue(accepted.logged());
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, accepted.disposition());

        CommitLog.Verdict dropped = log.reach(batch("old/1"), 1L, 1, LIFECYCLE);
        assertOrderDrop(dropped);

        assertEquals(1L, log.accepted());
        assertEquals(1L, log.dropped());
        assertEquals(1L, log.orderViolations());
        assertEquals(0L, log.unplanned(), "an old plan is not an unplanned line");
        assertEquals(1L, log.firstDivergencePosition());
    }

    @Test
    void aLowerPositionOfTheSamePlanAfterAHigherOneIsDroppedAndCounted() {
        Map<String, CommitLog.Resolved> steps = new LinkedHashMap<>();
        steps.put("same/5", at(1L, 5));
        steps.put("same/1", at(1L, 1));
        CommitLog log = log(steps);
        log.beginTick(10L);

        CommitLog.Verdict accepted = log.reach(batch("same/5"), 1L, 5, LIFECYCLE);
        assertTrue(accepted.logged());
        assertEquals(CommitLog.Entry.Disposition.ACCEPTED, accepted.disposition());

        CommitLog.Verdict dropped = log.reach(batch("same/1"), 1L, 1, LIFECYCLE);
        assertOrderDrop(dropped);

        assertEquals(1L, log.accepted());
        assertEquals(1L, log.dropped());
        assertEquals(1L, log.orderViolations());
        assertEquals(0L, log.unplanned());
        assertEquals(1L, log.firstDivergencePosition());
    }

    @Test
    void theFirstDivergenceIsResetByBeginTickButTheViolationTotalStays() {
        Map<String, CommitLog.Resolved> steps = new LinkedHashMap<>();
        steps.put("new/5", at(2L, 5));
        steps.put("old/1", at(1L, 1));
        steps.put("old/0", at(1L, 0));
        CommitLog log = log(steps);
        log.beginTick(10L);

        log.reach(batch("new/5"), 2L, 5, LIFECYCLE);
        assertOrderDrop(log.reach(batch("old/1"), 1L, 1, LIFECYCLE));
        assertEquals(1L, log.firstDivergencePosition());
        assertEquals(1L, log.orderViolations());

        log.beginTick(11L);
        assertEquals(-1L, log.firstDivergencePosition(),
            "beginTick starts a new per-tick divergence at none");
        assertEquals(1L, log.orderViolations(), "beginTick does not clear the cumulative count");

        // The dropped old plan moved the last seat to plan 1 / position 1, so plan 1 / position 0 is
        // the same-plan lower-position trajectory in the new tick.
        assertOrderDrop(log.reach(batch("old/0"), 1L, 0, LIFECYCLE));
        assertEquals(2L, log.orderViolations());
        assertEquals(0L, log.firstDivergencePosition(),
            "the first divergence of the new tick is the lower position");
    }

    @Test
    void elevenReportsFoldIntoEightAcceptedOneUnplannedAndTwoOrderDrops() {
        Map<String, CommitLog.Resolved> steps = new LinkedHashMap<>();
        for (int position = 0; position <= 3; position++) {
            steps.put("plan1/" + position, at(1L, position));
            steps.put("plan2/" + position, at(2L, position));
        }
        // The old plan after the new plan, and then the same plan at a lower position after the
        // dropped old line moved the last seat back to plan 1 / position 4.
        steps.put("plan1/4", at(1L, 4));
        CommitLog log = log(steps);
        log.beginTick(20L);

        for (int position = 0; position <= 3; position++) {
            CommitLog.Verdict accepted = log.reach(batch("plan1/" + position), 1L, position,
                LIFECYCLE);
            assertTrue(accepted.logged());
            assertEquals(CommitLog.Entry.Disposition.ACCEPTED, accepted.disposition());
        }
        for (int position = 0; position <= 3; position++) {
            CommitLog.Verdict accepted = log.reach(batch("plan2/" + position), 2L, position,
                LIFECYCLE);
            assertTrue(accepted.logged());
            assertEquals(CommitLog.Entry.Disposition.ACCEPTED, accepted.disposition());
        }

        CommitLog.Verdict unplanned = log.reach(batch("unplanned/0"));
        assertTrue(unplanned.logged());
        assertEquals(CommitLog.Entry.Disposition.DROPPED, unplanned.disposition());
        assertEquals(RejectCode.COMMIT_ORDER_VIOLATION, unplanned.code());

        assertOrderDrop(log.reach(batch("plan1/4"), 1L, 4, LIFECYCLE));
        assertOrderDrop(log.reach(batch("plan1/0"), 1L, 0, LIFECYCLE));

        assertEquals(8L, log.accepted());
        assertEquals(3L, log.dropped());
        assertEquals(1L, log.unplanned());
        assertEquals(2L, log.orderViolations());
        assertEquals(11L, log.sequence(), "eleven reports = accepted eight + dropped three");
        assertEquals(log.sequence(), log.accepted() + log.dropped());
        assertEquals(4L, log.firstDivergencePosition());

        assertEquals(1, log.ringCount());
        CommitRing ring = log.rings().get(0);
        assertEquals(11L, ring.pushed());
        assertEquals(0L, ring.popped());
        assertEquals(ring.pushed() - ring.popped(), ring.depth());

        log.beginTick(21L);
        assertEquals(-1L, log.firstDivergencePosition());
        assertEquals(2L, log.orderViolations());

        CommitLog.Replay replay = log.closeTick();
        assertEquals(11, replay.loggedSteps());
        assertEquals(2, replay.orderViolations());
        assertFalse(replay.orderMatches());
        assertEquals(11, replay.steps().size());
        assertEquals(11L, ring.pushed());
        assertEquals(11L, ring.popped());
        assertEquals(0, ring.depth());
        assertEquals(ring.pushed() - ring.popped(), ring.depth());
    }
}
