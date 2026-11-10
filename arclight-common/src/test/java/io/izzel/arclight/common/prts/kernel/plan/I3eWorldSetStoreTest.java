/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.plan;

import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I3eWorldSetStoreTest {

    private static JobDeclaration declaration(String key) {
        return new JobDeclaration(key, 1L, "w1", "entity", 0, List.of(), 0, "affinity", "cancel",
            List.of(), List.of(), ShareClass.AI, JobDeclaration.SiteClass.PARALLEL, 0, 8, List.of());
    }

    private static ShareTable table(long tick, List<String> worlds) {
        return new SharePlanner().plan(worlds, tick, Map.of());
    }

    private static TickPlanStore.Control control(long generation) {
        return TickPlanStore.Control.of(9L, generation, generation, 1.0, 0L, 0L, 4.0, "normal");
    }

    private static TickPlan plan(long tick, long sequence, long generation, List<String> worlds) {
        TickPlanPlanner.Result result = TickPlanPlanner.plan(new TickPlanPlanner.Input(tick, sequence,
            generation, worlds, List.of("entity"), control(generation), List.of(declaration("a/0")),
            table(tick, worlds), 64, TickPlanStore.Feedback.none()));
        assertTrue(result.ok(), String.valueOf(result.code()) + ":" + result.item());
        return result.plan();
    }

    @Test
    void worldSetChangeEvictsStaleHistoryAndKeepsNewPlan() {
        TickPlanStore store = new TickPlanStore(1);
        TickPlan oldPlan = plan(10L, 2L, 1L, List.of("w1"));
        store.publish(oldPlan);
        assertEquals(oldPlan, store.latest());
        TickPlan newPlan = plan(11L, 3L, 2L, List.of("w1", "w2"));
        store.publish(newPlan);
        assertEquals(newPlan, store.latest());
        assertEquals(2L, store.latest().worldSetGeneration());
        assertEquals(List.of("w1", "w2"), store.latest().worlds());
        assertEquals(1, store.historySize());
        assertNull(store.planOf(oldPlan.planSequence()));
        assertEquals(1L, store.rebuilds());
    }
}
