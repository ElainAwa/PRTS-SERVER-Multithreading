/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.TaskLedger;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The batch-bounded entity neighbourhood: a query sees only its own batch, refuses another
 * batch or world, and returns zero rows for an empty answer. */
class P2D02EntityNeighborhoodTest {

    @Test
    void aQueryStaysInsideItsBatchAndWorld() {
        DispatchPass pass = pass();
        long first = pass.entries().get(0).batch().batchId();
        EntityNeighborhood.Result result = EntityNeighborhood.aroundRow(pass, first,
            "p2d02-world", 1L, 0);

        assertTrue(result.accepted());
        assertEquals(2, result.candidates().size());
        assertEquals(1L, result.candidates().get(0).entitySeq());
        assertEquals(2L, result.candidates().get(1).entitySeq());
        assertEquals(EntityNeighborhood.Status.IN_BATCH, result.status());
        assertFalse(result.candidates().stream().anyMatch(row -> row.entitySeq() == 3L));
    }

    @Test
    void aRowOfAnotherBatchIsRefused() {
        DispatchPass pass = pass();
        long first = pass.entries().get(0).batch().batchId();
        long second = pass.entries().get(1).batch().batchId();

        EntityNeighborhood.Result foreignRow = EntityNeighborhood.aroundRow(pass, first,
            "p2d02-world", 3L, 0);
        EntityNeighborhood.Result foreignBatch = EntityNeighborhood.aroundRow(pass, second,
            "p2d02-world", 1L, 0);

        assertEquals(EntityNeighborhood.Status.CROSS_BATCH, foreignRow.status());
        assertTrue(foreignRow.candidates().isEmpty());
        assertEquals(EntityNeighborhood.Status.CROSS_BATCH, foreignBatch.status());
        assertTrue(foreignBatch.candidates().isEmpty());
    }

    @Test
    void anotherWorldIsRefused() {
        DispatchPass pass = pass();
        long first = pass.entries().get(0).batch().batchId();
        EntityNeighborhood.Result result = EntityNeighborhood.aroundRow(pass, first,
            "p2d02-other-world", 1L, 4);

        assertEquals(EntityNeighborhood.Status.CROSS_WORLD, result.status());
        assertTrue(result.candidates().isEmpty());
    }

    @Test
    void anEmptyAnswerAndAnEmptyPassArePublished() {
        DispatchPass pass = pass();
        long first = pass.entries().get(0).batch().batchId();
        EntityNeighborhood.Result empty = EntityNeighborhood.aroundBatch(pass, first, 100, 100, 0);
        assertEquals(EntityNeighborhood.Status.EMPTY_BATCH, empty.status());
        assertTrue(empty.candidates().isEmpty());

        WorkPlan emptyPlan = WorkPlan.freeze(1L, 1L,
            List.of(EntityCandidateView.builder("p2d02-empty", 1L).build()), 4, 1L);
        DispatchPass emptyPass = DispatchPass.serialFallback(emptyPlan, new DispatchReadings(),
            new TaskLedger(1L));
        EntityNeighborhood.Result missing = EntityNeighborhood.aroundBatch(emptyPass, 1L, 0, 0, 0);
        assertEquals(EntityNeighborhood.Status.CROSS_BATCH, missing.status());
        assertTrue(missing.candidates().isEmpty());
    }

    @Test
    void theZeroRadiusStillReturnsTheCentreRowsAndNoNeighbour() {
        DispatchPass pass = pass();
        long second = pass.entries().get(1).batch().batchId();
        EntityNeighborhood.Result result = EntityNeighborhood.aroundRow(pass, second,
            "p2d02-world", 3L, 0);

        assertTrue(result.accepted());
        assertEquals(1, result.candidates().size());
        assertEquals(3L, result.candidates().get(0).entitySeq());
    }

    private static DispatchPass pass() {
        EntityCandidateView.Builder builder = EntityCandidateView.builder("p2d02-world", 1L);
        builder.add(1L, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        builder.add(2L, 0, 0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        builder.add(3L, 20, 20, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        WorkPlan plan = WorkPlan.freeze(1L, 1L, List.of(builder.build()), 4, 1L);
        return DispatchPass.serialFallback(plan, new DispatchReadings(), new TaskLedger(1L));
    }
}
