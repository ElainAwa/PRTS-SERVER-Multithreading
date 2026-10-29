/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.TaskLedger;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The offline entity/block-entity/AI share view: three rows per frozen batch, zero values
 * included, no takeover decision opened, and a zero fallback when the view is disabled. */
class P2D01EntityClassSharesTest {

    @Test
    void threeClassRowsArePublishedForEveryBatchWithShareUsedAndSelf() {
        EntityClassShares.Plan plan = EntityClassShares.fromPass(pass(), true,
            Map.of(ShareClass.ENTITY, 10.0, ShareClass.BLOCKENTITY, 5.0, ShareClass.AI, 3.0),
            used(), Map.of(ShareClass.ENTITY, 2.0, ShareClass.BLOCKENTITY, 1.0,
                ShareClass.AI, 0.5));

        assertEquals(EntityClassShares.CLASSES, List.of(ShareClass.ENTITY,
            ShareClass.BLOCKENTITY, ShareClass.AI));
        assertEquals(6, plan.rows().size());
        for (EntityClassShares.Row row : plan.rows()) {
            assertTrue(row.shareMs() > 0.0);
            assertFalse(row.zero());
            assertEquals(EntityTickOwnership.RUN_HOST_TICK, row.hostDecision());
        }
        EntityClassShares.Row entity = plan.rows().get(0);
        assertEquals(10.0, entity.shareMs(), 1.0e-9);
        assertEquals(4.0, entity.usedMs(), 1.0e-9);
        assertEquals(2.0, entity.selfMs(), 1.0e-9);
        assertFalse(plan.takeoverTriggered());
    }

    @Test
    void aRowNeverCrossesItsFrozenBatchBoundary() {
        EntityClassShares.Plan plan = EntityClassShares.fromPass(pass(), true,
            Map.of(ShareClass.ENTITY, 10.0), used(), Map.of());
        long first = plan.rows().get(0).batchId();
        long second = plan.rows().get(3).batchId();

        assertTrue(first != second);
        assertEquals(3, plan.rowsForBatch(first).size());
        assertEquals(3, plan.rowsForBatch(second).size());
        for (EntityClassShares.Row row : plan.rowsForBatch(first)) {
            assertEquals(first, row.batchId());
        }
    }

    @Test
    void theWaitUpperBoundAndZeroRowsArePublished() {
        EntityClassShares.Plan plan = EntityClassShares.fromPass(pass(), true,
            Map.of(), Map.of(), Map.of());

        assertEquals(1L, plan.waitUpperBound(1L));
        assertEquals(0L, plan.waitUpperBound(2L));
        for (EntityClassShares.Row row : plan.rows()) {
            assertTrue(row.zero());
            assertEquals(0.0, row.usedMs(), 1.0e-9);
            assertEquals(0.0, row.selfMs(), 1.0e-9);
        }
    }

    @Test
    void theDisabledDefaultPublishesTheZeroFallbackWithTheSameShape() {
        EntityClassShares.Plan plan = EntityClassShares.fromPass(pass(), false,
            Map.of(ShareClass.ENTITY, 10.0, ShareClass.BLOCKENTITY, 5.0, ShareClass.AI, 3.0),
            used(), Map.of(ShareClass.ENTITY, 2.0));

        assertFalse(plan.enabled());
        assertTrue(plan.fallback());
        assertFalse(plan.takeoverTriggered());
        assertEquals(6, plan.rows().size());
        for (EntityClassShares.Row row : plan.rows()) {
            assertTrue(row.zero());
            assertEquals(EntityTickOwnership.RUN_HOST_TICK, row.hostDecision());
        }
    }

    private static DispatchPass pass() {
        EntityCandidateView.Builder builder = EntityCandidateView.builder("p2d01-world", 1L);
        builder.add(1L, 0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        builder.add(2L, 0, 0, 1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        builder.add(3L, 20, 20, 2.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0L);
        WorkPlan plan = WorkPlan.freeze(1L, 1L, List.of(builder.build()), 4, 1L);
        return DispatchPass.serialFallback(plan, new DispatchReadings(), new TaskLedger(1L));
    }

    private static Map<String, EnumMap<ShareClass, Double>> used() {
        EnumMap<ShareClass, Double> row = new EnumMap<>(ShareClass.class);
        row.put(ShareClass.ENTITY, 4.0);
        row.put(ShareClass.BLOCKENTITY, 2.0);
        row.put(ShareClass.AI, 1.0);
        Map<String, EnumMap<ShareClass, Double>> used = new LinkedHashMap<>();
        used.put("p2d01-world", row);
        return used;
    }
}
