/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.domain.ai.AiArenaPayload;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.domain.ai.AiLiveCommit;
import io.izzel.arclight.common.prts.kernel.domain.ai.AiJobDeclaration;
import io.izzel.arclight.common.prts.kernel.domain.ai.AiJobGraph;
import io.izzel.arclight.common.prts.kernel.domain.ai.AiWorkBody;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;
import io.izzel.arclight.common.prts.kernel.domain.entity.CancelToken;
import io.izzel.arclight.common.prts.kernel.generic.GenericJobExecutor;
import io.izzel.arclight.common.prts.kernel.generic.JobExecutionContext;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import io.izzel.arclight.common.prts.kernel.domain.ai.ServerLevelAiHostCapture;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.AiOwnerCommit;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Host install entry for the AI producer. Default off: the property is absent, so the entity plan
 * is unchanged; setting it installs capture->declaration into the freeze hook. */
final class AiHostInstall {

    static final String PROPERTY = "arclight.prts.aiPlan";
    private static boolean installed;
    private static WorkerPool aiPool;
    private static ArenaLedger aiLedger;
    private static ArenaSlot aiSlot;
    private static GenericJobExecutor aiExecutor;

    private AiHostInstall() {
    }

    static synchronized void noteServer(MinecraftServer server) {
        if (!Boolean.getBoolean(PROPERTY)) {
            if (installed) {
                AiLiveProducer.uninstall();
                AiLiveCommitHook.setHook(null);
                installed = false;
            }
            return;
        }
        if (server == null || server.getAllLevels() == null) return;
        if (installed) return;
        AiDiagnostics.installAttempt();
        ServerLevel level = null;
        for (ServerLevel candidate : server.getAllLevels()) {
            if (candidate != null) { level = candidate; break; }
        }
        if (level == null) return;
        ServerLevel captureLevel = level;
        var actors = (java.util.function.Supplier<List<Villager>>) () -> {
            List<Villager> list = new ArrayList<>();
            MinecraftServer current = server;
            if (current == null) return list;
            int limit = AiScaleConfig.actors();
            for (ServerLevel candidate : current.getAllLevels()) {
                if (candidate == null) continue;
                for (net.minecraft.world.entity.Entity entity : candidate.getEntities().getAll()) {
                    if (entity instanceof Villager villager && villager.isAlive()) {
                        list.add(villager);
                        if (list.size() >= limit) break;
                    }
                }
                if (list.size() >= limit) break;
            }
            return list.size() > limit ? list.subList(0, limit) : list;
        };
        AiLiveProducer.install(captureLevel, actors, 1L, 1L);
        aiLedger = new ArenaLedger();
        aiSlot = aiLedger.claim(1L, "w", "ai", 1, 32);
        aiPool = WorkerPool.open(new WorkerPool.Spec(AiScaleConfig.workers(), "ai-worker-",
            Thread.NORM_PRIORITY, Math.max(8, AiScaleConfig.batch() * 2), 4),
            1, new DispatchReadings(), aiLedger);
        aiExecutor = new GenericJobExecutor(aiPool, new JobExecutionContext(Set.of("ai"),
            Set.of("ai"), Set.of("ai"), 1L, 100.0));
        AiDiagnostics.installSuccess();
        AiLiveCommitHook.setHook((plans, commits, intents, lifecycle) -> {
            long captureStarted = System.nanoTime();
            List<Villager> current = actors.get();
            if (current == null || current.isEmpty() || plans == null || plans.latest() == null) return;
            AiArenaPayload.Snapshot captured = ServerLevelAiHostCapture.capture(captureLevel, current,
                lifecycle, 1L, lifecycle);
            long captureNanos = System.nanoTime() - captureStarted;
            long workerStarted = System.nanoTime();
            List<Long> visible = captured.rows().stream()
                .filter(row -> (row.kind() == AiArenaPayload.Kind.TARGET
                    || row.kind() == AiArenaPayload.Kind.NEIGHBOUR) && row.visible())
                .map(AiArenaPayload.Row::entityId).distinct().toList();
            for (int from = 0; from < current.size(); from += AiScaleConfig.batch()) {
                int to = Math.min(current.size(), from + AiScaleConfig.batch());
                runWorkerBatch(current.subList(from, to), lifecycle);
            }
            long workerNanos = System.nanoTime() - workerStarted;
            long commitStarted = System.nanoTime();
            int commitBudget = AiScaleConfig.commitBatch();
            int committedThisFreeze = 0;
            for (Villager actor : current) {
                if (actor == null || !actor.isAlive()) continue;
                AiDiagnostics.hookCandidate();
                LivingEntity target = candidateOf(actor);
                long targetId;
                if (target != null) {
                    targetId = target.getId();
                    AiDiagnostics.candidateAccept(actor.getTarget() != null ? "get_target" : "nearest_visible",
                        actor.getType().toString());
                } else if (!visible.isEmpty()) {
                    targetId = visible.get(0);
                    AiDiagnostics.candidateAccept("capture_row", actor.getType().toString());
                } else {
                    AiDiagnostics.candidateSkip("no_visible_target", actor.getType().toString());
                    continue;
                }
                if (committedThisFreeze >= commitBudget) {
                    AiDiagnostics.commitDeferred();
                    continue;
                }
                committedThisFreeze++;
                AiDiagnostics.commitAttempt();
                long version = lifecycle <= 0L ? 1L : lifecycle;
                AiOwnerCommit owner = new AiOwnerCommit(version);
                var decision = AiLiveCommit.commit(owner, plans, actor.getId(),
                    targetId, version, commits, intents, lifecycle);
                if (decision == AiOwnerCommit.Decision.COMMITTED) {
                    AiDiagnostics.commitSuccess();
                } else {
                    AiDiagnostics.commitFallback();
                }
            }
            AiDiagnostics.timing(captureNanos, workerNanos, System.nanoTime() - commitStarted,
                current.size());
        });
        installed = true;
    }

    private static LivingEntity candidateOf(Villager actor) {
        LivingEntity current = actor.getTarget();
        return current != null && current.isAlive() ? current : null;
    }

    private static void runWorkerBatch(List<Villager> actors, long lifecycle) {
        if (aiExecutor == null || aiSlot == null || actors.isEmpty()) return;
        try {
            List<JobDeclaration> declarations = new ArrayList<>();
            Map<String, Runnable> bodies = new java.util.LinkedHashMap<>();
            var arena = aiSlot.lease(lifecycle <= 0L ? 1L : lifecycle);
            long handle = 1L;
            for (Villager actor : actors) {
                if (actor == null) continue;
                JobDeclaration declaration = AiJobDeclaration.of("w", "actor-" + actor.getId(), 1, handle++);
                declarations.add(declaration);
                bodies.put(declaration.key(), () -> {
                    try {
                        new AiWorkBody().run(null, arena.scratch(), new CancelToken(lifecycle));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
            if (declarations.isEmpty()) return;
            var freeze = AiJobGraph.freeze(declarations, lifecycle, lifecycle, lifecycle,
                Math.max(2, declarations.size()));
            if (!freeze.ok()) return;
            var handles = aiExecutor.execute(freeze.graph(), bodies);
            for (var result : handles) result.await(5, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
    }

    static synchronized void stop() {
        AiLiveProducer.uninstall();
        AiLiveCommitHook.setHook(null);
        if (aiPool != null) {
            aiPool.shutdown(true, true, 2000L);
            aiPool = null;
        }
        aiExecutor = null;
        aiLedger = null;
        aiSlot = null;
        installed = false;
    }
}
