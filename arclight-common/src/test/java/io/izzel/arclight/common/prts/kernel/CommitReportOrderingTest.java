/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The barrier and the bounds of the ordered report buffer, exercised against the real commit log.
 * The reports are synthetic seats; the commit log is the production one. */
class CommitReportOrderingTest {

    private static final String WORLD = "world-a";
    private static final String DOMAIN = "chunk-pipeline";

    private static PipelineWriteClaims.Report report(long plan, int position, int rows) {
        return new PipelineWriteClaims.Report(WORLD, DOMAIN, "seat/" + plan + "/" + position, plan, position,
            1L, 1L, 1L, rows);
    }

    private static final class Sink implements Consumer<PipelineWriteClaims.Report> {

        private final List<PipelineWriteClaims.Report> delivered = new ArrayList<>();
        private final Map<String, CommitLog.Resolved> seats = new LinkedHashMap<>();
        private final CommitLog log;

        private Sink() {
            log = new CommitLog(() -> 64, new CommitLog.OrderSource() {

                @Override
                public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
                    return seats.get(nodeKey);
                }

                @Override
                public CommitLog.Resolved resolveAt(long planSequence, long lifecycle,
                                                    String worldId, String domainId, String nodeKey) {
                    return seats.get(nodeKey);
                }
            });
        }

        @Override
        public void accept(PipelineWriteClaims.Report report) {
            seats.put(report.nodeKey(), new CommitLog.Resolved(report.planSequence(),
                report.position(), false));
            CommitLog.Verdict verdict = log.reach(new CommitLog.Batch(report.worldId(),
                report.domainId(), report.nodeKey(), CommitLog.Batch.Kind.APPLY, 1L, 1L,
                report.rows()), report.planSequence(), report.position(), report.lifecycle());
            assertTrue(verdict.logged(), "the buffered report is a stored commit line");
            assertEquals(CommitLog.Entry.Disposition.ACCEPTED, verdict.disposition());
            delivered.add(report);
        }

        private CommitLog.Replay close() {
            return log.closeTick();
        }
    }

    private static PipelineWriteClaims.ReportBuffer buffer() {
        return new PipelineWriteClaims.ReportBuffer(64, 100_000);
    }

    private static List<PipelineWriteClaims.Report> one(PipelineWriteClaims.Report report) {
        return List.of(report);
    }

    @Test
    void anOlderPlanIsDeliveredBeforeANewerPlanEvenWhenItArrivesLater() {
        PipelineWriteClaims.ReportBuffer buffer = buffer();
        Sink sink = new Sink();
        sink.log.beginTick(1L);

        buffer.add(2L, one(report(2L, 0, 1)), null);
        buffer.add(1L, one(report(1L, 5, 1)), null);

        assertEquals(0, buffer.flushClosed(List.of(2L, 1L), sink, null),
            "both plans are still open; neither may pass the other");
        assertEquals(1, buffer.flushClosed(List.of(2L), sink, null),
            "plan 1 closed; it is delivered before the still-open plan 2");
        assertEquals(1, sink.delivered.size());
        assertEquals(1L, sink.delivered.get(0).planSequence());
        assertEquals(1, buffer.flushClosed(List.of(), sink, null));
        assertEquals(2, sink.delivered.size());
        assertEquals(2L, sink.delivered.get(1).planSequence());
        CommitLog.Replay replay = sink.close();
        assertEquals(0, replay.orderViolations());
        assertEquals(2, replay.loggedSteps());
    }

    @Test
    void theSamePlanIsDeliveredByAscendingPosition() {
        PipelineWriteClaims.ReportBuffer buffer = buffer();
        Sink sink = new Sink();
        sink.log.beginTick(1L);

        buffer.add(1L, one(report(1L, 5, 1)), null);
        buffer.add(1L, one(report(1L, 1, 2)), null);
        assertEquals(2, buffer.flushClosed(List.of(), sink, null));

        assertEquals(1, sink.delivered.get(0).position());
        assertEquals(5, sink.delivered.get(1).position());
        CommitLog.Replay replay = sink.close();
        assertEquals(0, replay.orderViolations());
        assertEquals(2, replay.loggedSteps());
    }

    @Test
    void aPlanThatIsStillOpenIsNotDelivered() {
        PipelineWriteClaims.ReportBuffer buffer = buffer();
        Sink sink = new Sink();
        sink.log.beginTick(1L);
        buffer.add(1L, one(report(1L, 0, 1)), null);

        assertEquals(0, buffer.flushClosed(List.of(1L), sink, null));
        assertTrue(sink.delivered.isEmpty());
        assertEquals(0, sink.log.last() == null ? 0 : sink.log.last().loggedSteps());

        assertEquals(1, buffer.flushClosed(List.of(), sink, null));
        assertEquals(1, sink.delivered.size());
    }

    @Test
    void aClosedPlanLaterThanAnOpenPlanWaitsForTheBarrier() {
        PipelineWriteClaims.ReportBuffer buffer = buffer();
        Sink sink = new Sink();
        sink.log.beginTick(1L);
        buffer.add(1L, one(report(1L, 0, 1)), null);
        buffer.add(2L, one(report(2L, 0, 1)), null);

        assertEquals(1, buffer.flushClosed(List.of(2L), sink, null),
            "plan 1 is closed and earlier than the open plan 2");
        assertEquals(1L, sink.delivered.get(0).planSequence());
        assertEquals(0, buffer.flushClosed(List.of(1L), sink, null),
            "plan 2 is closed but a plan 1 that is open again blocks it");
        assertEquals(1, buffer.flushClosed(List.of(), sink, null));
        assertEquals(2L, sink.delivered.get(1).planSequence());
        assertEquals(0, sink.close().orderViolations());
    }

    @Test
    void theEndDrainFlushesOpenPlansAndLeavesTheBufferUsable() {
        PipelineWriteClaims.ReportBuffer buffer = buffer();
        Sink sink = new Sink();
        sink.log.beginTick(1L);
        buffer.add(1L, one(report(1L, 0, 1)), null);

        assertEquals(1, buffer.drain(sink, null, PipelineWriteClaims.DRAIN_PLAN_CAP));
        assertEquals(1, sink.delivered.size());
        // The end flush is not terminal: the next freeze/tick may still buffer and flush.
        buffer.add(1L, one(report(1L, 1, 1)), null);
        assertEquals(1, buffer.flushClosed(List.of(), sink, null));
        assertEquals(2, sink.delivered.size());
        assertFalse(buffer.failed());
    }

    @Test
    void aDeliveryOrderRegressionLeavesTheBufferedReportPending() {
        PipelineWriteClaims.ReportBuffer buffer = buffer();
        Sink sink = new Sink();
        sink.log.beginTick(1L);
        buffer.add(2L, one(report(2L, 0, 1)), null);
        assertEquals(1, buffer.flushClosed(List.of(), sink, null));
        assertEquals(1, sink.delivered.size());

        buffer.add(1L, one(report(1L, 0, 1)), null);
        assertThrows(IllegalStateException.class,
            () -> buffer.flushClosed(List.of(), sink, null),
            "a plan older than the last delivered one is rejected before any sink call");
        assertEquals(1, sink.delivered.size(), "no partial delivery on the order regression");
        assertEquals(1, buffer.pendingReports(), "the rejected plan remains buffered");
        assertTrue(buffer.failed());
    }

    @Test
    void aReportCapFailsClosedWithoutDroppingTheBufferedReports() {
        PipelineWriteClaims.ReportBuffer buffer = new PipelineWriteClaims.ReportBuffer(2, 100);
        List<PipelineWriteClaims.Report> delivered = new ArrayList<>();
        buffer.add(1L, one(report(1L, 0, 1)), null);
        buffer.add(1L, one(report(1L, 1, 1)), null);

        assertThrows(IllegalStateException.class,
            () -> buffer.add(1L, one(report(1L, 2, 1)), null));
        assertTrue(buffer.failed());
        assertEquals(2, buffer.pendingReports());
        assertThrows(IllegalStateException.class,
            () -> buffer.flushClosed(List.of(), delivered::add, null),
            "the failed buffer refuses to deliver a partial or a dropped line");
        assertTrue(delivered.isEmpty());
    }

    @Test
    void aSinkFailureLeavesEveryBufferedRowPending() {
        PipelineWriteClaims.ReportBuffer buffer = buffer();
        buffer.add(1L, List.of(report(1L, 0, 1), report(1L, 1, 1)), null);

        assertThrows(IllegalStateException.class,
            () -> buffer.flushClosed(List.of(), report -> {
                throw new IllegalStateException("sink failed");
            }, null));
        assertTrue(buffer.failed());
        assertEquals(2, buffer.pendingReports(), "a sink failure removes no buffered row");
        assertEquals(2L, buffer.pendingRows());
    }

    @Test
    void aDrainPlanCapFailsClosedWithoutDeliveringAPartialDrain() {
        PipelineWriteClaims.ReportBuffer buffer = buffer();
        List<PipelineWriteClaims.Report> delivered = new ArrayList<>();
        buffer.add(1L, one(report(1L, 0, 1)), null);
        buffer.add(2L, one(report(2L, 0, 1)), null);

        assertThrows(IllegalStateException.class,
            () -> buffer.drain(delivered::add, null, 1));
        assertTrue(buffer.failed());
        assertEquals(2, buffer.pendingReports());
        assertTrue(delivered.isEmpty());
    }

    @Test
    void theBufferConservesRowsAndDeliversEveryLineOnce() {
        PipelineWriteClaims.ReportBuffer buffer = buffer();
        List<PipelineWriteClaims.Report> delivered = new ArrayList<>();
        buffer.add(1L, one(report(1L, 0, 2)), null);
        buffer.add(1L, one(report(1L, 1, 3)), null);
        assertEquals(5L, buffer.pendingRows());

        assertEquals(2, buffer.flushClosed(List.of(), delivered::add, null));
        assertEquals(0, buffer.pendingReports());
        assertEquals(0L, buffer.pendingRows());
        assertEquals(5, delivered.stream().mapToInt(PipelineWriteClaims.Report::rows).sum());
        assertEquals(2, delivered.size());
        assertEquals(0, buffer.flushClosed(List.of(), delivered::add, null),
            "a delivered report is never delivered a second time");
    }

    @Test
    void theBufferDoesNotBlockTheCallingThread() {
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            PipelineWriteClaims.ReportBuffer buffer = new PipelineWriteClaims.ReportBuffer(4096,
                1_000_000);
            for (int i = 0; i < 512; i++) {
                buffer.add((long) (i / 8 + 1), one(report(i / 8 + 1L, i % 8, 1)), null);
            }
            assertEquals(512, buffer.flushClosed(List.of(), report -> { }, null));
        });
    }
}
