/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.jobs;

import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The two read-only questions a published binding answers: whether it is published, and which
 * ordinal owns one of its cells. The ledger is the production object driven through its own mint,
 * enqueue and freeze. The forming window is installed through the same private mark the freeze
 * raises, so the shape a concurrent reader would see is measured without a test hook in the
 * production path. */
class PipelineBatchLedgerBindingPublishedTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String MAILBOX = "worldgen";
    private static final long TICK = 100L;
    private static final long PLAN = 7L;
    private static final long CELL = ChunkPos.asLong(3, -7);
    private static final long CELL2 = ChunkPos.asLong(4, -7);
    private static final long CELL3 = ChunkPos.asLong(5, -7);

    private PipelineBatchLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new PipelineBatchLedger(64, 1000);
    }

    private PipelineBatchLedger.TrackedTask submit(long cell) {
        PipelineBatchLedger.TrackedTask task = ledger.mint(new Object(), WORLD, MAILBOX, cell, TICK);
        ledger.enqueued(task);
        return task;
    }

    private PipelineBatchLedger.BatchId freeze(long plan) {
        PipelineBatchLedger.BatchId batch = ledger.freeze(WORLD, MAILBOX, plan, TICK);
        assertNotNull(batch, "the freeze cut the pending tasks");
        return batch;
    }

    private static PipelineBatchLedger.BatchId unknown() {
        return new PipelineBatchLedger.BatchId(WORLD, MAILBOX, 900L, PLAN);
    }

    @SuppressWarnings("unchecked")
    private static void setState(PipelineBatchLedger.TrackedTask task,
                                 PipelineBatchLedger.TaskState state) {
        try {
            Field field = PipelineBatchLedger.TrackedTask.class.getDeclaredField("state");
            field.setAccessible(true);
            ((AtomicReference<PipelineBatchLedger.TaskState>) field.get(task)).set(state);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private void setForming(long freezeOrdinal, boolean forming) {
        try {
            Field field = PipelineBatchLedger.class.getDeclaredField("forming");
            field.setAccessible(true);
            Set<Long> marks = (Set<Long>) field.get(ledger);
            if (forming) {
                marks.add(freezeOrdinal);
            } else {
                marks.remove(freezeOrdinal);
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void noUnknownOrMissingBatchIsPublished() {
        assertFalse(ledger.bindingPublished(null));
        assertFalse(ledger.bindingPublished(unknown()));
        assertEquals(-1L, ledger.ownerOrdinal(null, CELL));
        assertEquals(-1L, ledger.ownerOrdinal(unknown(), CELL));

        submit(CELL);
        assertFalse(ledger.bindingPublished(unknown()),
            "a pending task mints no batch, so no binding can be published for one");
    }

    @Test
    void aFreezePublishesItsBindingAndTheOldestOrdinalOfEachCell() {
        PipelineBatchLedger.TrackedTask owner = submit(CELL);
        PipelineBatchLedger.TrackedTask sameCell = submit(CELL);
        PipelineBatchLedger.TrackedTask otherCell = submit(CELL2);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);

        assertTrue(ledger.bindingPublished(batch));
        assertEquals(batch, new PipelineBatchLedger.BatchId(WORLD, MAILBOX, batch.freezeOrdinal(),
            batch.planSequence()), "the identity of the cut batch is the whole key");
        assertEquals(owner.id().ordinal(), ledger.ownerOrdinal(batch, CELL));
        assertTrue(sameCell.id().ordinal() > owner.id().ordinal());
        assertEquals(otherCell.id().ordinal(), ledger.ownerOrdinal(batch, CELL2));
        assertEquals(-1L, ledger.ownerOrdinal(batch, CELL3),
            "a cell the binding does not name has no owner");
        assertFalse(ledger.bindingPublished(unknown()));
    }

    @Test
    void aPendingTaskOfANamedCellNeverBecomesAnOwner() {
        PipelineBatchLedger.TrackedTask claimed = submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        PipelineBatchLedger.TrackedTask pending = submit(CELL2);

        assertEquals(claimed.id().ordinal(), ledger.ownerOrdinal(batch, CELL));
        assertEquals(-1L, ledger.ownerOrdinal(batch, CELL2),
            "a pending task names a cell but is not a member of the published binding");
        assertEquals(1, ledger.pendingCount() - ledger.tasksOf(batch).size(),
            "only the pending task is left outside the binding");
        assertTrue(pending.tracked());
    }

    @Test
    void aClaimWhoseBindingIsNotPublishedAnswersNoOwner() {
        PipelineBatchLedger.TrackedTask first = ledger.mint(new Object(), WORLD, MAILBOX, CELL, TICK);
        ledger.enqueued(first);
        PipelineBatchLedger.TrackedTask second = ledger.mint(new Object(), WORLD, MAILBOX, CELL, TICK);
        ledger.enqueued(second);
        PipelineBatchLedger.BatchId forming = new PipelineBatchLedger.BatchId(WORLD, MAILBOX, 500L,
            PLAN);
        setState(first, new PipelineBatchLedger.Claimed(forming, PipelineBatchLedger.Place.QUEUED));
        setState(second, new PipelineBatchLedger.Claimed(forming, PipelineBatchLedger.Place.QUEUED));
        setForming(forming.freezeOrdinal(), true);

        assertFalse(ledger.bindingPublished(forming));
        assertEquals(2, ledger.tasksOf(forming).size(),
            "the forming fallback still reads the members from the claims that carry the batch");
        assertEquals(-1L, ledger.ownerOrdinal(forming, CELL),
            "the owner question reads the published binding alone, never the forming members");
        assertEquals(-1L, ledger.ownerOrdinal(forming, CELL2));

        setForming(forming.freezeOrdinal(), false);
        assertTrue(ledger.tasksOf(forming).isEmpty(),
            "without the forming mark the unpublished binding answers no member at all");
        assertEquals(-1L, ledger.ownerOrdinal(forming, CELL));
    }

    @Test
    void aResetForgetsEveryPublishedBinding() {
        PipelineBatchLedger.TrackedTask first = submit(CELL);
        PipelineBatchLedger.TrackedTask second = submit(CELL2);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        assertTrue(ledger.bindingPublished(batch));

        ledger.reset();

        assertFalse(ledger.bindingPublished(batch));
        assertEquals(-1L, ledger.ownerOrdinal(batch, CELL));
        assertEquals(-1L, ledger.ownerOrdinal(batch, CELL2));
        assertFalse(first.live());
        assertFalse(second.live());
    }

    @Test
    void aBindingTheBoundedHistoryDroppedIsNotPublished() {
        PipelineBatchLedger.BatchId first = null;
        PipelineBatchLedger.BatchId last = null;
        for (int round = 0; round < 65; round++) {
            PipelineBatchLedger.TrackedTask task = submit(1000L + round);
            PipelineBatchLedger.BatchId batch = freeze(PLAN);
            if (first == null) {
                first = batch;
            }
            last = batch;
            assertTrue(ledger.runStart(task));
            ledger.runEnd(task);
        }

        assertNotNull(first);
        assertNotNull(last);
        assertEquals(0, ledger.cellsOf(first).length, "the first binding fell out of the history");
        assertFalse(ledger.bindingPublished(first));
        assertEquals(-1L, ledger.ownerOrdinal(first, 1000L));
        assertTrue(ledger.bindingPublished(last), "the newest binding stays published");
        assertEquals(-1L, ledger.ownerOrdinal(last, 1000L),
            "the newest cut a later cell, so an older cell has no owner in it");
    }

    @Test
    void theTwoReadersChangeNoCountAndNoState() {
        PipelineBatchLedger.TrackedTask owner = submit(CELL);
        submit(CELL);
        PipelineBatchLedger.BatchId batch = freeze(PLAN);
        long minted = ledger.mintedCount();
        int pending = ledger.pendingCount();
        long tracked = ledger.trackedCount();
        long claimed = ledger.claimedTaskCount();
        long resets = ledger.resetCount();
        PipelineBatchLedger.TaskState before = owner.state();

        assertTrue(ledger.bindingPublished(batch));
        assertEquals(owner.id().ordinal(), ledger.ownerOrdinal(batch, CELL));
        assertFalse(ledger.bindingPublished(unknown()));
        assertEquals(-1L, ledger.ownerOrdinal(unknown(), CELL));

        assertEquals(minted, ledger.mintedCount());
        assertEquals(pending, ledger.pendingCount());
        assertEquals(tracked, ledger.trackedCount());
        assertEquals(claimed, ledger.claimedTaskCount());
        assertEquals(resets, ledger.resetCount());
        assertSame(before, owner.state(), "the readers touch no task state");
    }
}
