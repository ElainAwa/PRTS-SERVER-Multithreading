/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.ai;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;

/** Internal OrderSource of AI commit seats: resolves from the published plan's commit order. */
public final class AiSeatOrderSource implements CommitLog.OrderSource {

    private final TickPlan plan;

    public static AiSeatOrderSource fromStore(io.izzel.arclight.common.prts.kernel.plan.TickPlanStore store) {
        return new AiSeatOrderSource(store == null ? null : store.latest());
    }

    public AiSeatOrderSource(TickPlan plan) {
        this.plan = plan;
    }

    @Override
    public CommitLog.Resolved resolve(String worldId, String domainId, String nodeKey) {
        if (plan == null) return null;
        for (TickPlan.CommitStep step : plan.commitOrder()) {
            if (step.worldId().equals(worldId) && step.domainId().equals(domainId)
                && step.nodeKey().equals(nodeKey)) {
                return new CommitLog.Resolved(plan.planSequence(), step.position(), step.intent());
            }
        }
        return null;
    }

    @Override
    public CommitLog.Resolved resolveAt(long planSequence, long lifecycle, String worldId,
                                        String domainId, String nodeKey) {
        return plan != null && plan.planSequence() == planSequence
            ? resolve(worldId, domainId, nodeKey) : null;
    }
}
