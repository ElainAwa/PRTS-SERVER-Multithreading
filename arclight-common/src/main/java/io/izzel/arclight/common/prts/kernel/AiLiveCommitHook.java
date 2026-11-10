/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.intent.IntentQueue;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;

/** Dormant internal AI commit hook: null by default, so the entity commit path is unchanged. */
final class AiLiveCommitHook {

    @FunctionalInterface
    interface Hook {
        void afterFreeze(TickPlanStore plans, CommitLog commits, IntentQueue intents, long lifecycle);
    }

    private static volatile Hook hook;

    private AiLiveCommitHook() {
    }

    static void setHook(Hook value) {
        hook = value;
    }

    static void afterFreeze(TickPlanStore plans, CommitLog commits, IntentQueue intents,
                            long lifecycle) {
        Hook current = hook;
        if (current != null) current.afterFreeze(plans, commits, intents, lifecycle);
    }
}
