/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityCandidateView;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** P0 WorkPlan/EntityCandidateView adapter: 8 AI villagers form one region batch. */
class AiWorkPlanAdapterTest {

    @Test
    void eightAiRowsFreezeIntoOneP0Task() {
        EntityCandidateView.Builder builder = EntityCandidateView.builder("world", 7L);
        for (int i = 0; i < 8; i++) {
            builder.add(i, i / 4, 0, i, 64, 0, 0, 0, 0, 0, 0, 0L);
        }
        EntityCandidateView view = builder.build();
        WorkPlan plan = WorkPlan.freeze(1L, 1L, List.of(view), 16, 1L);
        assertEquals(1, plan.taskCount());
        assertEquals(8, plan.tasks().get(0).entityCount());
        assertEquals("world", plan.tasks().get(0).worldId());
    }
}
