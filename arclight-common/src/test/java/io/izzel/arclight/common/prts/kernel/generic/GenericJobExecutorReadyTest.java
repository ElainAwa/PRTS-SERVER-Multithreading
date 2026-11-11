/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraph;
import io.izzel.arclight.common.prts.kernel.jobs.JobGraphBuilder;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenericJobExecutorReadyTest {

    private WorkerPool pool;

    @AfterEach
    void stopPool() {
        if (pool != null) {
            pool.shutdown(true, true, 2000L);
            pool = null;
        }
    }

    @Test
    void independentNodeCompletesWhilePredecessorIsBlocked() throws Exception {
        pool = WorkerPool.open(new WorkerPool.Spec(2, "p0-f01-", Thread.NORM_PRIORITY, 8, 1),
            0, new DispatchReadings(), new ArenaLedger());
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicInteger bRuns = new AtomicInteger();
        AtomicInteger cRuns = new AtomicInteger();
        JobGraph graph = graph(List.of(job("A", List.of()), job("B", List.of("A")), job("C", List.of())));
        Map<String, Runnable> bodies = new LinkedHashMap<>();
        bodies.put("A", () -> {
            aEntered.countDown();
            await(releaseA);
        });
        bodies.put("B", bRuns::incrementAndGet);
        bodies.put("C", cRuns::incrementAndGet);

        GenericJobExecutor.Execution execution = new GenericJobExecutor(pool).executeCohort(graph, bodies);
        assertTrue(aEntered.await(2, TimeUnit.SECONDS), "A must be running");
        assertTrue(awaitTrue(() -> cRuns.get() == 1, 2_000), "C must finish while A is blocked");
        assertEquals(0, bRuns.get(), "B must wait for A");
        releaseA.countDown();
        assertTrue(execution.cohort().awaitDone(5, TimeUnit.SECONDS), "graph must settle");
        assertEquals(1, bRuns.get(), "B must run after A");
        assertEquals(ExecutionCohort.State.DONE, execution.cohort().state("A"));
        assertEquals(ExecutionCohort.State.DONE, execution.cohort().state("B"));
        assertEquals(ExecutionCohort.State.DONE, execution.cohort().state("C"));
    }

    @Test
    void successorWaitsForEveryPredecessor() throws Exception {
        pool = WorkerPool.open(new WorkerPool.Spec(2, "p0-f01-", Thread.NORM_PRIORITY, 8, 1),
            0, new DispatchReadings(), new ArenaLedger());
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch cEntered = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        CountDownLatch releaseC = new CountDownLatch(1);
        AtomicInteger bRuns = new AtomicInteger();
        JobGraph graph = graph(List.of(job("A", List.of()), job("C", List.of()),
            job("B", List.of("A", "C"))));
        Map<String, Runnable> bodies = new LinkedHashMap<>();
        bodies.put("A", () -> {
            aEntered.countDown();
            await(releaseA);
        });
        bodies.put("C", () -> {
            cEntered.countDown();
            await(releaseC);
        });
        bodies.put("B", bRuns::incrementAndGet);

        GenericJobExecutor.Execution execution = new GenericJobExecutor(pool).executeCohort(graph, bodies);
        assertTrue(aEntered.await(2, TimeUnit.SECONDS));
        assertTrue(cEntered.await(2, TimeUnit.SECONDS));
        releaseA.countDown();
        Thread.sleep(50L);
        assertEquals(0, bRuns.get(), "B must wait for both predecessors");
        releaseC.countDown();
        assertTrue(execution.cohort().awaitDone(5, TimeUnit.SECONDS), "graph must settle");
        assertEquals(1, bRuns.get(), "B must run once after both predecessors");
    }

    @Test
    void failedPredecessorDoesNotRunSuccessor() throws Exception {
        pool = WorkerPool.open(new WorkerPool.Spec(2, "p0-f01-", Thread.NORM_PRIORITY, 8, 1),
            0, new DispatchReadings(), new ArenaLedger());
        AtomicInteger bRuns = new AtomicInteger();
        JobGraph graph = graph(List.of(job("A", List.of()), job("B", List.of("A"))));
        Map<String, Runnable> bodies = new LinkedHashMap<>();
        bodies.put("A", () -> {
            throw new IllegalStateException("A failed");
        });
        bodies.put("B", bRuns::incrementAndGet);

        GenericJobExecutor.Execution execution = new GenericJobExecutor(pool).executeCohort(graph, bodies);
        assertTrue(execution.cohort().awaitDone(5, TimeUnit.SECONDS), "graph must settle");
        assertEquals(ExecutionCohort.State.FAILED, execution.cohort().state("A"));
        assertEquals(ExecutionCohort.State.CANCELLED, execution.cohort().state("B"));
        assertEquals(0, bRuns.get(), "B must not run after A failed");
    }

    @Test
    void cancelledPredecessorDoesNotRunSuccessor() throws Exception {
        pool = WorkerPool.open(new WorkerPool.Spec(2, "p0-f01-", Thread.NORM_PRIORITY, 8, 1),
            0, new DispatchReadings(), new ArenaLedger());
        CountDownLatch aEntered = new CountDownLatch(1);
        CountDownLatch releaseA = new CountDownLatch(1);
        AtomicInteger bRuns = new AtomicInteger();
        JobGraph graph = graph(List.of(job("A", List.of()), job("B", List.of("A"))));
        Map<String, Runnable> bodies = new LinkedHashMap<>();
        bodies.put("A", () -> {
            aEntered.countDown();
            await(releaseA);
        });
        bodies.put("B", bRuns::incrementAndGet);

        GenericJobExecutor.Execution execution = new GenericJobExecutor(pool).executeCohort(graph, bodies);
        assertTrue(aEntered.await(2, TimeUnit.SECONDS), "A must be running");
        assertTrue(execution.cohort().cancel("A"), "A must be cancellable");
        releaseA.countDown();
        assertTrue(execution.cohort().awaitDone(5, TimeUnit.SECONDS), "graph must settle");
        assertEquals(ExecutionCohort.State.CANCELLED, execution.cohort().state("A"));
        assertEquals(ExecutionCohort.State.CANCELLED, execution.cohort().state("B"));
        assertEquals(0, bRuns.get(), "B must not run after A cancelled");
    }

    @Test
    void accountingFailureAfterBodyDoesNotRerunIt() throws Exception {
        pool = WorkerPool.open(new WorkerPool.Spec(1, "p0-f01-", Thread.NORM_PRIORITY, 8, 1),
            0, new DispatchReadings(), new ArenaLedger());
        AtomicInteger runs = new AtomicInteger();
        JobDeclaration declaration = aiJob("A");
        JobGraph graph = graph(List.of(declaration));
        JobExecutionContext context = new JobExecutionContext(Set.of("ai"), Set.of("ai"),
            Set.of("ai"), 0L, 0.0);
        Map<String, Runnable> bodies = Map.of(declaration.key(), runs::incrementAndGet);

        GenericJobExecutor.Execution execution =
            new GenericJobExecutor(pool, context).executeCohort(graph, bodies);
        assertTrue(execution.cohort().awaitDone(5, TimeUnit.SECONDS), "graph must settle");
        assertEquals(1, runs.get(), "the body must run exactly once");
        assertEquals(ExecutionCohort.State.FAILED_AFTER_EFFECT, execution.cohort().state(declaration.key()));
    }

    @Test
    void rejectedSecondRootKeepsAcceptedPrefix() throws Exception {
        pool = WorkerPool.open(new WorkerPool.Spec(1, "p0-f01-", Thread.NORM_PRIORITY, 1, 1),
            0, new DispatchReadings(), new ArenaLedger());
        CountDownLatch release = new CountDownLatch(1);
        JobGraph graph = graph(List.of(job("A", List.of()), job("B", List.of())));
        Map<String, Runnable> bodies = new LinkedHashMap<>();
        bodies.put("A", () -> {
            await(release);
        });
        bodies.put("B", () -> {
            await(release);
        });

        GenericJobExecutor.Execution execution = new GenericJobExecutor(pool).executeCohort(graph, bodies);
        assertEquals(1, execution.cohort().accepted(), "exactly one root fits the pool");
        assertEquals(1, execution.cohort().rejected(), "the other root is rejected");
        release.countDown();
        assertTrue(execution.cohort().awaitDone(5, TimeUnit.SECONDS), "accepted prefix must settle");
        assertEquals(1, count(execution.cohort(), ExecutionCohort.State.DONE));
        assertEquals(1, count(execution.cohort(), ExecutionCohort.State.REJECTED));
    }

    private static int count(ExecutionCohort cohort, ExecutionCohort.State wanted) {
        int count = 0;
        for (long id : cohort.graph().order()) {
            JobGraph.Node node = cohort.graph().node(id);
            if (node != null && cohort.state(node.key()) == wanted) count++;
        }
        return count;
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static boolean awaitTrue(java.util.function.BooleanSupplier condition, long millis)
        throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(5L);
        }
        return condition.getAsBoolean();
    }

    private static JobGraph graph(List<JobDeclaration> declarations) {
        JobGraphBuilder.Freeze freeze = JobGraphBuilder.freeze(declarations, 1L, 1L, 1L, 16);
        assertTrue(freeze.ok(), "graph must freeze");
        return freeze.graph();
    }

    private static JobDeclaration job(String key, List<String> predecessors) {
        JobDeclaration.DomainRef ref = new JobDeclaration.DomainRef("w", "d", 0);
        return new JobDeclaration(key, Math.abs(key.hashCode()), "w", "d", 0, predecessors, 0,
            "affinity", "cancel", List.of(ref), List.of(ref), ShareClass.OTHER,
            JobDeclaration.SiteClass.PARALLEL, 0, 4, List.of(), null);
    }

    private static JobDeclaration aiJob(String key) {
        return io.izzel.arclight.common.prts.kernel.domain.ai.AiJobDeclaration.of("w", key, 4, 1L);
    }
}
