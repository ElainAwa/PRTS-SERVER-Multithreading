/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.codes.RejectCode;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.ShareMeter;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The frozen order of a job graph, the refusals of the freeze, the scheduler that follows the order
 * and its gates, and the metering point of the layer. */
class JobGraphTest {

    private static final String WORLD = "world-a";

    private static JobDeclaration declaration(String key, int priority, ShareClass shareClass,
                                              JobDeclaration.SiteClass siteClass,
                                              List<String> predecessors, int bound) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, "entity", 0);
        return new JobDeclaration(key, key.hashCode(), WORLD, "entity", 0, predecessors, priority,
            key, "region", List.of(ref), List.of(ref), shareClass, siteClass, 0, bound);
    }

    private static List<JobDeclaration> chain() {
        List<JobDeclaration> declarations = new ArrayList<>();
        declarations.add(declaration("a/0", 0, ShareClass.ENTITY, JobDeclaration.SiteClass.PARALLEL,
            List.of(), 1));
        declarations.add(declaration("a/1", 1, ShareClass.ENTITY, JobDeclaration.SiteClass.PARALLEL,
            List.of("a/0"), 1));
        declarations.add(declaration("a/2", 2, ShareClass.ENTITY, JobDeclaration.SiteClass.UNKNOWN,
            List.of("a/1"), 1));
        return declarations;
    }

    @Test
    void theSameDeclarationsFreezeToTheSameOrder() {
        JobGraphBuilder.Freeze first = JobGraphBuilder.freeze(chain(), 10L, 1L, 1L, 64);
        List<JobDeclaration> reversed = new ArrayList<>(chain());
        java.util.Collections.reverse(reversed);
        JobGraphBuilder.Freeze second = JobGraphBuilder.freeze(reversed, 10L, 1L, 1L, 64);

        assertTrue(first.ok(), () -> "refused: " + first.code() + " " + first.item());
        assertTrue(second.ok());
        assertEquals(first.graph().order(), second.graph().order());
        assertEquals(3, first.graph().nodeCount());
        assertEquals(2, first.graph().edgeCount());
        assertEquals(List.of(1L), first.graph().ready());
        assertEquals(0, first.graph().splitIntents());
        assertEquals(3, first.graph().affinity().size());
    }

    @Test
    void aDeclarationTheContractRefusesIsNotFrozen() {
        JobDeclaration cyclicA = declaration("a/0", 0, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of("a/1"), 1);
        JobDeclaration cyclicB = declaration("a/1", 0, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of("a/0"), 1);
        assertEquals(RejectCode.DAG_CYCLE,
            JobGraphBuilder.freeze(List.of(cyclicA, cyclicB), 10L, 1L, 1L, 64).code());

        JobDeclaration duplicate = declaration("a/0", 1, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of(), 1);
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER,
            JobGraphBuilder.freeze(List.of(duplicate, duplicate), 10L, 1L, 1L, 64).code());

        JobDeclaration.DomainRef otherWorld = new JobDeclaration.DomainRef("world-b", "entity", 0);
        JobDeclaration crossing = new JobDeclaration("a/0", 1L, WORLD, "entity", 0, List.of(), 0,
            "r", "region", List.of(new JobDeclaration.DomainRef(WORLD, "entity", 0)), List.of(otherWorld),
            ShareClass.ENTITY, JobDeclaration.SiteClass.PARALLEL, 0, 1);
        assertEquals(RejectCode.CROSS_WORLD_WRITE_DENIED,
            JobGraphBuilder.freeze(List.of(crossing), 10L, 1L, 1L, 64).code());

        JobDeclaration unbounded = declaration("a/0", 0, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of(), 0);
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED,
            JobGraphBuilder.freeze(List.of(unbounded), 10L, 1L, 1L, 64).code());

        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED,
            JobGraphBuilder.freeze(chain(), 10L, 1L, 1L, 2).code());

        JobDeclaration orphan = declaration("a/0", 0, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of("nobody"), 1);
        assertEquals(RejectCode.WRITE_DENIED_NOT_OWNER,
            JobGraphBuilder.freeze(List.of(orphan), 10L, 1L, 1L, 64).code());
    }

    @Test
    void aCrossDomainWriteIsSplitIntoAnIntent() {
        JobDeclaration.DomainRef entity = new JobDeclaration.DomainRef(WORLD, "entity", 0);
        JobDeclaration.DomainRef lighting = new JobDeclaration.DomainRef(WORLD, "light", 0);
        JobDeclaration split = new JobDeclaration("a/0", 1L, WORLD, "entity", 0, List.of(), 0, "r",
            "region", List.of(entity, lighting), List.of(entity, lighting), ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, 0, 4);

        JobGraphBuilder.Freeze freeze = JobGraphBuilder.freeze(List.of(split), 10L, 1L, 1L, 64);

        assertTrue(freeze.ok());
        assertEquals(1, freeze.graph().splitIntents());
        assertTrue(freeze.graph().nodes().get(0).splitIntent());
    }

    @Test
    void theSchedulerHandsOutTheFrozenOrderAndKeepsTheAffinityOrder() {
        JobGraph graph = JobGraphBuilder.freeze(chain(), 10L, 1L, 1L, 64).graph();
        JobScheduler scheduler = new JobScheduler();
        scheduler.begin(graph);
        List<Long> handedOut = new ArrayList<>();
        JobScheduler.Step step;
        while ((step = scheduler.next()) != null) {
            handedOut.add(step.nodeId());
            assertEquals(step.position(), handedOut.size() - 1);
            scheduler.settle(step.nodeId());
        }

        assertEquals(graph.order(), handedOut);
        assertTrue(scheduler.closed());
        assertEquals(3, scheduler.lanes());
        assertEquals(3L, scheduler.dispatched());
    }

    @Test
    void theDeclaredBoundOfJobsInFlightRefusesInsteadOfGrowing() {
        JobDeclaration first = declaration("a/0", 0, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of(), 4);
        JobDeclaration second = declaration("a/1", 0, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of(), 4);
        JobDeclaration third = declaration("a/2", 0, ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, List.of("a/0"), 4);
        JobGraph graph = JobGraphBuilder.freeze(List.of(first, second, third), 10L, 1L, 1L, 64)
            .graph();
        JobScheduler scheduler = new JobScheduler();
        scheduler.begin(graph, 1);

        JobScheduler.Step dispatched = scheduler.next();
        assertNotNull(dispatched);
        assertEquals(1, scheduler.inFlightCap());
        assertNull(scheduler.next());
        assertEquals(1L, scheduler.backpressureHits());
        assertTrue(scheduler.depth() > 0);

        scheduler.settle(dispatched.nodeId());
        assertNotNull(scheduler.next());
    }

    @Test
    void aCancellationReachesItsScopeOnly() {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef(WORLD, "entity", 0);
        JobDeclaration first = new JobDeclaration("a/0", 1L, WORLD, "entity", 0, List.of(), 0, "r0",
            "first", List.of(ref), List.of(ref), ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, 0, 1);
        JobDeclaration sameScope = new JobDeclaration("a/1", 2L, WORLD, "entity", 0, List.of("a/0"),
            0, "r1", "first", List.of(ref), List.of(ref), ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, 0, 1);
        JobDeclaration otherScope = new JobDeclaration("a/2", 3L, WORLD, "entity", 0, List.of("a/0"),
            0, "r2", "second", List.of(ref), List.of(ref), ShareClass.ENTITY,
            JobDeclaration.SiteClass.PARALLEL, 0, 1);
        JobGraph graph = JobGraphBuilder.freeze(List.of(first, sameScope, otherScope), 10L, 1L, 1L,
            64).graph();
        JobScheduler scheduler = new JobScheduler();
        scheduler.begin(graph);

        JobScheduler.CancelReport report = scheduler.cancel(graph.nodeByKey("a/0").nodeId());

        assertEquals(2, report.cancelled().size());
        assertEquals(1, report.kept().size());
        assertNull(report.code());
        assertEquals(2L, scheduler.cancelledTotal());
        Set<Long> cancelled = new LinkedHashSet<>(report.cancelled());
        assertTrue(cancelled.contains(graph.nodeByKey("a/0").nodeId()));
        assertTrue(cancelled.contains(graph.nodeByKey("a/1").nodeId()));
        assertFalse(cancelled.contains(graph.nodeByKey("a/2").nodeId()));
    }

    @Test
    void theTimeoutGateAnswersTheReadingTheExecutorTook() {
        JobGraph graph = JobGraphBuilder.freeze(chain(), 10L, 1L, 1L, 64).graph();
        JobScheduler scheduler = new JobScheduler();
        scheduler.begin(graph);
        JobScheduler.Gate gate = scheduler.arm(5_000L);

        assertFalse(JobScheduler.expired(gate, 4_999L));
        assertTrue(JobScheduler.expired(gate, 5_000L));
        JobScheduler.CancelReport report = scheduler.noteTimedOut(graph.order().get(0));
        assertEquals(3, report.cancelled().size());
        assertEquals(1L, scheduler.timedOutTotal());
    }

    @Test
    void theIntakeRefusesPastItsDeclaredCapacity() {
        JobIntake intake = new JobIntake(() -> 2);
        assertTrue(intake.submit(chain().get(0)).accepted());
        assertTrue(intake.submit(chain().get(1)).accepted());
        JobIntake.Admission refusal = intake.submit(chain().get(2));

        assertFalse(refusal.accepted());
        assertEquals(RejectCode.QUEUE_CAP_EXCEEDED, refusal.code());
        assertEquals(1L, intake.refused());
        assertEquals(2, intake.take().size());
        assertEquals(0, intake.depth());
        assertEquals(2L, intake.submitted());
    }

    @Test
    void theMeteringPointBooksIntoTheShareReading() {
        ShareMeterPoint point = new ShareMeterPoint();
        point.note(SelfClass.ENTITY, WORLD, "job-graph", 2_000_000L);
        point.note(null, WORLD, "job-graph", 1_000L);

        long[] totals = new long[SelfClass.values().length];
        totals[SelfClass.ENTITY.ordinal()] = 2_000_000L;
        ShareMeter.TickReading reading = ShareMeterPoint.read(1L, true, Map.of(WORLD, totals));

        assertEquals(1L, point.notes());
        assertEquals(1L, point.refusedNotes());
        assertEquals(1, point.classes());
        assertEquals(ShareClass.ENTITY, ShareMeterPoint.shareClassOf(SelfClass.ENTITY));
        assertEquals(2.0, reading.row(ShareClass.ENTITY).usedMs(), 1.0e-9);
    }
}
