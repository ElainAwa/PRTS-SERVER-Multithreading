/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;

import java.util.concurrent.atomic.LongAdder;

/** The default-off real-entity owner body: it runs the original Villager tick on the real object
 * inside a handed-off row window. Only a REST row enters; no Level or Brain is copied and no main
 * thread is faked. The result distinguishes a safe pre-tick fallback from a terminal dirty run. */
public final class RealEntityOwnerBody {

    /** What one owner attempt may report; only SAFE_FALLBACK may return to the host tick. */
    public enum Result {
        EXECUTED,
        SAFE_FALLBACK,
        TERMINAL
    }

    private static final LongAdder RUNS = new LongAdder();
    private static final LongAdder FALLBACKS = new LongAdder();
    private static final LongAdder TERMINALS = new LongAdder();

    private RealEntityOwnerBody() {
    }

    /** Whether the owner body is declared; off in every shipped build. */
    public static boolean enabled() {
        return EntityRowOwnershipWindow.enabled();
    }

    /** The rows the REST body really executed, for the local evidence line. */
    static long runs() {
        return RUNS.sum();
    }

    /** The rows the REST body handed back before any effect. */
    static long fallbacks() {
        return FALLBACKS.sum();
    }

    /** The rows whose tick failed after entering the original algorithm. */
    static long terminals() {
        return TERMINALS.sum();
    }

    /** B3-A: runs the original REST tick on the server thread; false means safe rejection before
     * the tick, and a throwable after entering is recorded TERMINAL and rethrown unchanged. */
    public static boolean runRestedOnServer(ServerLevel level, Villager villager) {
        if (level == null || villager == null || villager.level() != level
            || villager.isRemoved() || !villager.isAlive()
            || villager.getPose() != net.minecraft.world.entity.Pose.SLEEPING
            || !villager.getBrain().isActive(Activity.REST)) {
            return false;
        }
        try {
            villager.tick();
        } catch (Throwable terminal) {
            TERMINALS.increment();
            RestOwnerWiring.record(villager, Result.TERMINAL);
            throw terminal;
        }
        RUNS.increment();
        RestOwnerWiring.record(villager, Result.EXECUTED);
        return true;
    }

    /** B3-S3: runs the original non-sleeping, non-PANIC/hurt walking AI tick on the server thread;
     * false is a safe rejection before the tick, and a throwable after entering is TERMINAL. */
    public static boolean runWalkOnServer(ServerLevel level, Villager villager) {
        if (level == null || villager == null || villager.level() != level
            || villager.isRemoved() || !villager.isAlive() || villager.isNoAi()
            || villager.isPassenger()
            || villager.getPose() == net.minecraft.world.entity.Pose.SLEEPING
            || villager.getBrain().isActive(Activity.REST)) {
            return false;
        }
        try {
            villager.tick();
        } catch (Throwable terminal) {
            TERMINALS.increment();
            RestOwnerWiring.record(villager, Result.TERMINAL);
            throw terminal;
        }
        RUNS.increment();
        RestOwnerWiring.record(villager, Result.EXECUTED);
        return true;
    }

    /** Runs the original REST tick on the real Villager inside the owner window. */
    public static Result runRest(EntityRowOwnershipWindow window, ServerLevel level,
                                 Villager villager) {
        if (!enabled() || window == null || level == null || villager == null
            || villager.isRemoved() || !villager.isAlive()) {
            FALLBACKS.increment();
            return Result.SAFE_FALLBACK;
        }
        try {
            window.assertOwnerRow(villager.getId());
            if (villager.level() != level) {
                FALLBACKS.increment();
                return Result.SAFE_FALLBACK;
            }
            if (!villager.getBrain().isActive(Activity.REST)) {
                FALLBACKS.increment();
                return Result.SAFE_FALLBACK;
            }
        } catch (RuntimeException refusedBeforeTick) {
            FALLBACKS.increment();
            return Result.SAFE_FALLBACK;
        }
        try {
            villager.tick();
        } catch (Throwable failedAfterEffect) {
            TERMINALS.increment();
            window.quarantine(failedAfterEffect.getClass().getSimpleName());
            RestOwnerWiring.record(villager, Result.TERMINAL);
            return Result.TERMINAL;
        }
        RUNS.increment();
        RestOwnerWiring.record(villager, Result.EXECUTED);
        return Result.EXECUTED;
    }
}
