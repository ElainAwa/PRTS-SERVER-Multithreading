/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import io.izzel.arclight.common.prts.kernel.commit.FrozenDeclarations;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealFixture;
import io.izzel.arclight.common.prts.kernel.commit.RangeSealTable;
import io.izzel.arclight.common.prts.support.PrtsChunkDemand;
import io.izzel.arclight.common.prts.support.PrtsPipelineBatches;
import net.minecraft.util.Unit;
import net.minecraft.util.thread.ProcessorHandle;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The status one real chunk pipeline body reads for its own task before the seal. The fixture uses
 * the real ledger, the real wrapper the sorter enqueues, the real demand seam and the real status
 * register; no server, world or instance is started. */
class PipelineRunSealTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String MAILBOX = "worldgen";
    private static final long TICK = 100L;
    private static final long PLAN = 7L;
    private static final long CELL = ChunkPos.asLong(3, -7);
    private static final long CELL2 = ChunkPos.asLong(4, -7);

    private PipelineBatchLedger ledger;
    private DemandShadowCandidates candidates;

    @BeforeEach
    void setUp() {
        ledger = new PipelineBatchLedger(8, 100);
        candidates = new DemandShadowCandidates(ledger.pendingLimit());
        PrtsChunkDemand.install(candidates);
        PrtsPipelineBatches.install(ledger, () -> TICK);
        PipelineRunSeal.install(candidates::statusFor);
    }

    @AfterEach
    void tearDown() {
        PipelineRunSeal.installRange(null);
        PipelineRunSeal.install(null);
        PrtsChunkDemand.install(null);
        PrtsPipelineBatches.uninstall();
    }

    private PipelineBatchLedger.TrackedTask submit(long cell) {
        PipelineBatchLedger.TrackedTask task = ledger.mint(new Object(), WORLD, MAILBOX, cell, TICK);
        ledger.enqueued(task);
        return task;
    }

    private PipelineBatchLedger.BatchId freeze(long plan) {
        return ledger.freeze(WORLD, MAILBOX, plan, TICK);
    }

    /** Applies the wrapped function and runs the body it produced, exactly as the sorter does. */
    private void runBody(PipelineBatchLedger.TrackedTask task, Runnable body) {
        Function<ProcessorHandle<Unit>, Runnable> delegate = reply -> body::run;
        Function<ProcessorHandle<Unit>, Runnable> wrapped =
            PipelineBatchWrapper.function(ledger, task, delegate);
        Runnable produced = wrapped.apply(null);
        assertNotNull(produced);
        produced.run();
    }

    private long demand(PipelineBatchLedger.TrackedTask task, String status) {
        return PrtsChunkDemand.demandDescriptorToken(WORLD, ChunkPos.getX(task.id().cell()),
            ChunkPos.getZ(task.id().cell()), status, true, task);
    }

    @SuppressWarnings("unchecked")
    private static void setState(PipelineBatchLedger.TrackedTask task,
                                 PipelineBatchLedger.TaskState state) throws Exception {
        Field field = PipelineBatchLedger.TrackedTask.class.getDeclaredField("state");
        field.setAccessible(true);
        ((AtomicReference<PipelineBatchLedger.TaskState>) field.get(task)).set(state);
    }

    @Test
    void aRunningPipelineTaskReadsItsOwnStatusBeforeTheFirstWrite() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        assertNotNull(batch);
        AtomicReference<DemandShadowCandidates.TaskStatus> seen = new AtomicReference<>();
        AtomicLong token = new AtomicLong();
        boolean[] wrote = {false};

        runBody(task, () -> {
            assertSame(task, PrtsPipelineBatches.currentTask());
            PipelineRunSeal seal = PipelineRunSeal.current();
            assertNotNull(seal);
            token.set(demand(task, "full"));
            assertFalse(wrote[0], "the status is read before the first write");
            seen.set(seal.status());
            assertTrue(seal.read());
            wrote[0] = true;
        });

        DemandShadowCandidates.TaskStatus status = seen.get();
        assertTrue(status.present());
        assertEquals("full", status.status());
        assertEquals(task.id().ordinal(), status.stamp().taskOrdinal());
        assertEquals(WORLD, status.stamp().world());
        assertEquals(MAILBOX, status.stamp().mailbox());
        assertEquals(CELL, status.stamp().cell());
        assertEquals(TICK, status.stamp().mintTick());
        assertEquals(batch.freezeOrdinal(), status.stamp().batchFreezeOrdinal());
        assertEquals(batch.planSequence(), status.stamp().planSequence());
        assertEquals(token.get(), status.stamp().demandToken());
        assertTrue(wrote[0]);
        assertNull(PipelineRunSeal.current(), "the window ends with the body");
    }

    @Test
    void theRunStartWindowReadsTheRegisterBeforeTheBodyRuns() {
        AtomicInteger reads = new AtomicInteger();
        PipelineRunSeal.install(taskToken -> {
            reads.incrementAndGet();
            return candidates.statusFor(taskToken);
        });
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        freeze(PLAN);

        runBody(task, () -> {
            assertEquals(1, reads.get(), "the run-start window reads the register");
            assertTrue(demand(task, "full") > 0L);
            assertTrue(PipelineRunSeal.current().status().present());
        });
        assertEquals(2, reads.get(), "the in-body seal reads the register again");
    }

    @Test
    void aReadAfterTheSealOrAfterTheBodyIsRefused() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        freeze(PLAN);
        AtomicReference<PipelineRunSeal> window = new AtomicReference<>();

        runBody(task, () -> {
            PipelineRunSeal seal = PipelineRunSeal.current();
            window.set(seal);
            assertTrue(demand(task, "full") > 0L);
            assertEquals("full", seal.status().status());
            assertTrue(demand(task, "features") > 0L);
            assertEquals("full", seal.status().status(), "a later demand is not adopted");
            assertEquals(1L, candidates.statusLate());
        });

        assertTrue(window.get().closed());
        assertFalse(window.get().status().present(), "a closed window answers nothing");
    }

    @Test
    void anEmptyFirstReadClosesTheWindowBeforeTheDemand() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        freeze(PLAN);
        AtomicReference<DemandShadowCandidates.TaskStatus> before = new AtomicReference<>();
        AtomicReference<DemandShadowCandidates.TaskStatus> after = new AtomicReference<>();

        runBody(task, () -> {
            PipelineRunSeal seal = PipelineRunSeal.current();
            before.set(seal.status());
            assertTrue(demand(task, "full") > 0L);
            after.set(seal.status());
        });

        assertFalse(before.get().present());
        assertFalse(after.get().present(), "a demand after the seal is not adopted");
    }

    @Test
    void aResetTaskAnswersNothingThroughTheWindow() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        freeze(PLAN);
        AtomicReference<DemandShadowCandidates.TaskStatus> seen = new AtomicReference<>();

        runBody(task, () -> {
            assertTrue(demand(task, "full") > 0L);
            ledger.reset();
            seen.set(PipelineRunSeal.current().status());
        });

        assertFalse(seen.get().present());
    }

    @Test
    void aTaskWhoseRunningBatchMovedIsRefusedByTheWindow() throws Exception {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        freeze(PLAN);
        AtomicReference<DemandShadowCandidates.TaskStatus> seen = new AtomicReference<>();

        runBody(task, () -> {
            assertTrue(demand(task, "full") > 0L);
            PipelineBatchLedger.BatchId other = new PipelineBatchLedger.BatchId(WORLD, MAILBOX,
                task.batch().freezeOrdinal() + 1L, PLAN + 1L);
            try {
                setState(task, new PipelineBatchLedger.Running(other));
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            seen.set(PipelineRunSeal.current().status());
        });

        assertFalse(seen.get().present());
    }

    @Test
    void twoDifferentStatusesOnOneTaskAreRefusedByTheWindow() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        freeze(PLAN);
        AtomicReference<DemandShadowCandidates.TaskStatus> seen = new AtomicReference<>();

        runBody(task, () -> {
            assertTrue(demand(task, "full") > 0L);
            assertTrue(demand(task, "features") > 0L);
            seen.set(PipelineRunSeal.current().status());
            assertEquals(1L, candidates.statusConflicts());
        });

        assertFalse(seen.get().present());
    }

    @Test
    void aFullRegisterRefusesTheNewTaskAndKeepsTheOldStatus() {
        candidates = new DemandShadowCandidates(1);
        PrtsChunkDemand.install(candidates);
        PipelineRunSeal.install(candidates::statusFor);
        PipelineBatchLedger.TrackedTask first = submit(CELL);
        PipelineBatchLedger.TrackedTask second = submit(CELL2);
        freeze(PLAN);
        AtomicReference<DemandShadowCandidates.TaskStatus> firstStatus = new AtomicReference<>();
        AtomicReference<DemandShadowCandidates.TaskStatus> secondStatus = new AtomicReference<>();

        runBody(first, () -> {
            assertTrue(demand(first, "full") > 0L);
            firstStatus.set(PipelineRunSeal.current().status());
            assertTrue(firstStatus.get().present());
            runBody(second, () -> {
                assertTrue(demand(second, "full") > 0L, "the ask itself is not blocked");
                secondStatus.set(PipelineRunSeal.current().status());
            });
            assertEquals("full", PipelineRunSeal.current().status().status(),
                "the outer window is restored after the nested one");
        });

        assertTrue(firstStatus.get().present());
        assertFalse(secondStatus.get().present(), "no old status is reused");
        assertEquals(1L, candidates.statusOverflows());
        assertEquals(1, candidates.statusHeld());
    }

    @Test
    void noWindowExistsOutsideAWrappedBody() {
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        freeze(PLAN);

        assertNull(PipelineRunSeal.current());
        assertTrue(ledger.runStart(task));
        assertNull(PipelineRunSeal.current());
        ledger.runEnd(task);
    }

    /** A range sink that answers one fixed seal, counts its calls and publishes through a real
     * table so the generation and the release path are the real ones. */
    private static final class FixedRangeSink implements PipelineRunSeal.RangeSink {

        private final RangeSealTable table;
        private final FrozenDeclarations.RangeSeal seal;
        private int calls;
        private int releases;

        private FixedRangeSink(RangeSealTable table, FrozenDeclarations.RangeSeal seal) {
            this.table = table;
            this.seal = seal;
        }

        @Override
        public long generation() {
            return table.generation();
        }

        @Override
        public FrozenDeclarations.RangeSeal seal(PipelineBatchLedger.TrackedTask task,
                                                 DemandShadowCandidates.TaskStatus status,
                                                 long captured) {
            calls++;
            if (table.generation() != captured) {
                return null;
            }
            return table.publish(seal, captured) == RangeSealTable.Publish.REJECTED ? null : seal;
        }

        @Override
        public void release(FrozenDeclarations.RangeSeal released) {
            releases++;
            table.release(released);
        }
    }

    @Test
    void theFirstPresentStatusSealsOneRangeAndLeavesTheStatusAnswerUntouched() {
        RangeSealTable table = new RangeSealTable(8);
        FixedRangeSink sink = new FixedRangeSink(table, new RangeSealFixture().build());
        PipelineRunSeal.installRange(sink);
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        freeze(PLAN);
        AtomicReference<DemandShadowCandidates.TaskStatus> answer = new AtomicReference<>();
        AtomicReference<FrozenDeclarations.RangeSeal> sealed = new AtomicReference<>();

        runBody(task, () -> {
            assertTrue(demand(task, "full") > 0L);
            answer.set(PipelineRunSeal.current().status());
            sealed.set(PipelineRunSeal.current().range());
        });

        assertTrue(answer.get().present());
        assertEquals("full", answer.get().status());
        assertNotNull(sealed.get());
        assertEquals(1, sink.calls);
        assertEquals(1, sink.releases, "the window close releases the published range");
        assertEquals(0, table.size(), "no obligation kept the fixed seal");
    }

    @Test
    void aSecondStatusReadDoesNotAskTheSinkAgainAndAnswersTheSameRange() {
        RangeSealTable table = new RangeSealTable(8);
        FixedRangeSink sink = new FixedRangeSink(table, new RangeSealFixture().build());
        PipelineRunSeal.installRange(sink);
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        freeze(PLAN);
        AtomicReference<FrozenDeclarations.RangeSeal> first = new AtomicReference<>();
        AtomicReference<FrozenDeclarations.RangeSeal> second = new AtomicReference<>();

        runBody(task, () -> {
            assertTrue(demand(task, "full") > 0L);
            PipelineRunSeal.current().status();
            first.set(PipelineRunSeal.current().range());
            PipelineRunSeal.current().status();
            second.set(PipelineRunSeal.current().range());
        });

        assertNotNull(first.get());
        assertSame(first.get(), second.get());
        assertEquals(1, sink.calls);
    }

    @Test
    void anEmptyFirstStatusLeavesTheWindowWithoutARange() {
        RangeSealTable table = new RangeSealTable(8);
        FixedRangeSink sink = new FixedRangeSink(table, new RangeSealFixture().build());
        PipelineRunSeal.installRange(sink);
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        freeze(PLAN);
        AtomicReference<DemandShadowCandidates.TaskStatus> before = new AtomicReference<>();
        AtomicReference<DemandShadowCandidates.TaskStatus> after = new AtomicReference<>();
        AtomicReference<FrozenDeclarations.RangeSeal> range = new AtomicReference<>();

        runBody(task, () -> {
            before.set(PipelineRunSeal.current().status());
            assertTrue(demand(task, "full") > 0L);
            after.set(PipelineRunSeal.current().status());
            range.set(PipelineRunSeal.current().range());
        });

        assertFalse(before.get().present());
        assertFalse(after.get().present(), "a demand after the seal is not adopted");
        assertNull(range.get());
        assertEquals(0, sink.calls, "an empty first read asks the sink nothing");
    }

    @Test
    void aClosedWindowAnswersNoRangeAndReleasesTheOneItPublished() {
        RangeSealTable table = new RangeSealTable(8);
        FixedRangeSink sink = new FixedRangeSink(table, new RangeSealFixture().build());
        PipelineRunSeal.installRange(sink);
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        freeze(PLAN);
        AtomicReference<PipelineRunSeal> window = new AtomicReference<>();

        runBody(task, () -> {
            assertTrue(demand(task, "full") > 0L);
            window.set(PipelineRunSeal.current());
            window.get().status();
            assertNotNull(window.get().range());
        });

        assertTrue(window.get().closed());
        assertNull(window.get().range(), "the closed window carries no range");
        assertEquals(1, sink.releases);
    }

    @Test
    void aTaskResetBeforeTheStatusSealsNoRange() {
        RangeSealTable table = new RangeSealTable(8);
        FixedRangeSink sink = new FixedRangeSink(table, new RangeSealFixture().build());
        PipelineRunSeal.installRange(sink);
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        freeze(PLAN);
        AtomicReference<DemandShadowCandidates.TaskStatus> answer = new AtomicReference<>();
        AtomicReference<FrozenDeclarations.RangeSeal> range = new AtomicReference<>();

        runBody(task, () -> {
            assertTrue(demand(task, "full") > 0L);
            ledger.reset();
            answer.set(PipelineRunSeal.current().status());
            range.set(PipelineRunSeal.current().range());
        });

        assertFalse(answer.get().present());
        assertNull(range.get());
        assertEquals(0, sink.calls);
    }

    @Test
    void twoDifferentStatusesOnOneTaskSealNoRange() {
        RangeSealTable table = new RangeSealTable(8);
        FixedRangeSink sink = new FixedRangeSink(table, new RangeSealFixture().build());
        PipelineRunSeal.installRange(sink);
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        freeze(PLAN);
        AtomicReference<DemandShadowCandidates.TaskStatus> answer = new AtomicReference<>();
        AtomicReference<FrozenDeclarations.RangeSeal> range = new AtomicReference<>();

        runBody(task, () -> {
            assertTrue(demand(task, "full") > 0L);
            assertTrue(demand(task, "features") > 0L);
            answer.set(PipelineRunSeal.current().status());
            range.set(PipelineRunSeal.current().range());
        });

        assertFalse(answer.get().present());
        assertNull(range.get());
        assertEquals(0, sink.calls);
        assertEquals(1L, candidates.statusConflicts());
    }

    @Test
    void aTableResetInsideTheWindowPublishesNoRangeOfTheOlderGeneration() {
        RangeSealTable table = new RangeSealTable(8);
        FixedRangeSink sink = new FixedRangeSink(table, new RangeSealFixture().build());
        PipelineRunSeal.installRange(sink);
        PipelineBatchLedger.TrackedTask task = submit(CELL);
        freeze(PLAN);
        AtomicReference<FrozenDeclarations.RangeSeal> range = new AtomicReference<>();

        runBody(task, () -> {
            assertTrue(demand(task, "full") > 0L);
            table.reset();
            PipelineRunSeal.current().status();
            range.set(PipelineRunSeal.current().range());
        });

        assertNull(range.get());
        assertEquals(1, sink.calls);
        assertEquals(0, table.size());
    }
}
