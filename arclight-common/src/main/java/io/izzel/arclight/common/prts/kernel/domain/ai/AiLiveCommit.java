/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.ai;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.AiOwnerCommit;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;

/** Commits one AI candidate through the seat/order of the live plan store; empty/unseated -> fallback. */
public final class AiLiveCommit {

    private static final java.util.Set<String> LOGGED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private AiLiveCommit() {
    }

    private static void branch(String reason) {
        if (Boolean.getBoolean("arclight.prts.aiDiag") && LOGGED.add(reason)) {
            System.out.println("[PRTS] ai-diag commit_branch reason=" + reason);
        }
    }

    public static AiOwnerCommit.Decision commit(AiOwnerCommit owner, TickPlanStore store,
                                                long actorId, long targetId, long version,
                                                CommitLog log, IntentQueue intents, long lifecycle) {
        if (owner == null || store == null || store.latest() == null) {
            branch("no_store");
            return AiOwnerCommit.Decision.FALLBACK;
        }
        TickPlan plan = store.latest();
        TickPlan.CommitStep chosen = null;
        for (TickPlan.CommitStep step : plan.commitOrder()) {
            if (!"ai".equals(step.domainId())) continue;
            if (step.nodeKey().equals("ai/" + actorId)
                || step.nodeKey().endsWith("/" + actorId)) {
                chosen = step;
                break;
            }
            if (chosen == null) chosen = step;
        }
        if (chosen != null) {
            AiCommitSeat.Seat seat = new AiCommitSeat.Seat(plan.planSequence(), chosen.position(),
                chosen.worldId(), chosen.domainId(), chosen.nodeKey(), "ai.job");
            AiOwnerCommit.Decision decision = owner.commit(
                new AiOwnerCommit.Candidate(actorId, targetId, version), seat, log, intents, lifecycle);
            if (decision != AiOwnerCommit.Decision.COMMITTED) {
                branch("commitlog_rejected:" + decision);
            }
            return decision;
        }
        branch("no_ai_seat");
        return AiOwnerCommit.Decision.FALLBACK;
    }
}
