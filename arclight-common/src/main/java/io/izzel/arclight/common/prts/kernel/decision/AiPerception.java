/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.decision;

import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** The first product AI slice: a non-player mob's nearest-player perception is computed on the
 * existing entity worker pool and committed on the server thread at the next AI step. The input is
 * frozen on the server thread; the worker never reads a level, entity or chunk. A stale, absent,
 * exceptional or already-targeted answer is dropped, so the host path always remains the fallback.
 *
 * <p>The slice is active only while the existing dispatch-parallel feature owns the pool; it adds
 * no setting, no readout key, no rejection code and no thread. Disabling that feature (or shutting
 * the pool down) removes the slice without touching host AI.</p>
 */
public final class AiPerception {

    /** One frozen player position; the worker sees only this record. */
    public record PlayerPos(UUID id, double x, double y, double z) {
    }

    /** The frozen input of one decision. */
    public record Request(UUID mobId, String worldId, long tick, double x, double y, double z,
                          double rangeSq, List<PlayerPos> players, long generation) {
    }

    /** The answer; a null target means no player was inside the frozen range. */
    public record Result(UUID target, double distanceSq) {
    }

    private record Pending(Request request, CompletableFuture<Result> future, long submittedTick) {
    }

    private static final Map<UUID, Pending> PENDING = new ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicLong GENERATION =
        new java.util.concurrent.atomic.AtomicLong();
    private static volatile WorkerPool pool;
    private static volatile long boundGeneration;

    private AiPerception() {
    }

    /** Binds the existing pool; the pool owns the workers, this class only offers them work. */
    public static void bind(WorkerPool workerPool) {
        if (workerPool == null) {
            return;
        }
        long generation = GENERATION.incrementAndGet();
        pool = workerPool;
        boundGeneration = generation;
        PENDING.clear();
    }

    /** Removes the binding; pending answers can never commit after this call. */
    public static void unbind(WorkerPool workerPool) {
        if (workerPool != null && pool == workerPool) {
            pool = null;
            GENERATION.incrementAndGet();
            PENDING.clear();
        }
    }

    /** Starts one server AI step: freeze and submit the next perception. */
    public static void beginServerAiStep(Mob mob) {
        if (!KernelSettings.dispatchParallel() || mob == null) {
            return;
        }
        submit(mob);
    }

    /** Ends one server AI step: commit only an answer that is ready, after the host AI body. */
    public static void endServerAiStep(Mob mob) {
        if (!KernelSettings.dispatchParallel() || mob == null) {
            return;
        }
        commit(mob);
    }

    private static void commit(Mob mob) {
        if (mob.isRemoved() || mob.isPassenger() || mob.isVehicle() || mob.isNoAi()) {
            PENDING.remove(mob.getUUID());
            return;
        }
        if (mob.getTarget() != null) {
            PENDING.remove(mob.getUUID());
            return;
        }
        Pending pending = PENDING.get(mob.getUUID());
        if (pending == null) {
            return;
        }
        if (pending.request().generation() != boundGeneration || !pending.future().isDone()) {
            return;
        }
        PENDING.remove(mob.getUUID());
        try {
            Result result = pending.future().join();
            if (result == null || result.target() == null) {
                return;
            }
            if (!mob.level().dimension().location().toString().equals(pending.request().worldId())) {
                return;
            }
            if (mob.level().getGameTime() < pending.request().tick()
                || mob.level().getGameTime() > pending.request().tick() + 2L) {
                return;
            }
            if (mob.getTarget() != null) {
                return;
            }
            if (!(mob.level() instanceof ServerLevel level)) {
                return;
            }
            Player target = null;
            for (Player player : level.players()) {
                if (player.getUUID().equals(result.target()) && !player.isSpectator()) {
                    target = player;
                    break;
                }
            }
            if (target == null || mob.distanceToSqr(target) > pending.request().rangeSq()) {
                return;
            }
            mob.setTarget(target);
            // The target is chosen from the frozen player list on the worker; the host
            // PathNavigation still owns the actual path and runs it here on the owner thread.
            // Path creation is not moved off-thread by this slice.
            if (mob.getNavigation().isDone()) {
                mob.getNavigation().moveTo(target, 1.0);
            }
        } catch (Throwable ignored) {
            // The host AI keeps its own path when the worker answer is unusable.
        }
    }

    private static void submit(Mob mob) {
        if (mob.isRemoved() || mob.isPassenger() || mob.isVehicle() || mob.isNoAi()
            || mob.getTarget() != null || PENDING.containsKey(mob.getUUID())) {
            return;
        }
        if (!(mob.level() instanceof ServerLevel level)) {
            return;
        }
        double range = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        if (!(range > 0.0)) {
            return;
        }
        List<PlayerPos> players = new ArrayList<>();
        for (Player player : level.players()) {
            if (player == null || player.isSpectator() || !player.isAlive()) {
                continue;
            }
            players.add(new PlayerPos(player.getUUID(), player.getX(), player.getY(), player.getZ()));
        }
        if (players.isEmpty()) {
            return;
        }
        Request request = new Request(mob.getUUID(),
            level.dimension().location().toString(), level.getGameTime(),
            mob.getX(), mob.getY(), mob.getZ(), range * range, List.copyOf(players), boundGeneration);
        CompletableFuture<Result> future = new CompletableFuture<>();
        WorkerPool current = pool;
        if (current == null || !current.submitControl(request.worldId(), () -> future.complete(nearest(request)))) {
            return;
        }
        PENDING.put(mob.getUUID(), new Pending(request, future, request.tick()));
    }

    /** The pure worker body: only the frozen records are read. */
    public static Result nearest(Request request) {
        UUID best = null;
        double bestDistance = Double.POSITIVE_INFINITY;
        for (PlayerPos player : request.players()) {
            double dx = player.x() - request.x();
            double dy = player.y() - request.y();
            double dz = player.z() - request.z();
            double distance = dx * dx + dy * dy + dz * dz;
            if (distance <= request.rangeSq() && distance < bestDistance) {
                bestDistance = distance;
                best = player.id();
            }
        }
        return new Result(best, best == null ? Double.NaN : bestDistance);
    }

    /** Test/diagnostic observation only; no readout key is published. */
    public static int pendingCount() {
        return PENDING.size();
    }

    public static void reset() {
        PENDING.clear();
        pool = null;
        GENERATION.incrementAndGet();
    }
}