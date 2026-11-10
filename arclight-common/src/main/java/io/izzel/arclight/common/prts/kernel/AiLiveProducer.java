/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.domain.ai.AiJobDeclaration;
import io.izzel.arclight.common.prts.kernel.domain.ai.ServerLevelAiHostCapture;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Internal live-producer assembly: capture is only performed when a host/tests install it. */
final class AiLiveProducer {

    private AiLiveProducer() {
    }

    static void install(ServerLevel level, Supplier<List<Villager>> actors, long worldEpoch,
                        long generation) {
        AiPlanFreezeHook.setProducer((worlds, planSequence) -> {
            List<Villager> captured = actors == null ? List.of() : actors.get();
            if (level == null || captured == null) return List.of();
            ServerLevelAiHostCapture.capture(level, captured, planSequence, worldEpoch, generation);
            List<JobDeclaration> declarations = new ArrayList<>(captured.size());
            for (Villager actor : captured) {
                if (actor == null) continue;
                declarations.add(AiJobDeclaration.of(level.dimension().location().toString(),
                    String.valueOf(actor.getId()), 8));
            }
            return List.copyOf(declarations);
        });
    }

    static void uninstall() {
        AiPlanFreezeHook.setProducer(null);
    }
}
