/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.schedule.Activity;

import java.util.concurrent.atomic.AtomicBoolean;
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

    private static final String DIAGNOSTIC_PROPERTY = "arclight.prts.diag.ownerBodyFailure";
    private static final String DIAGNOSTIC_MODE = System.getProperty(DIAGNOSTIC_PROPERTY, "");
    private static final AtomicBoolean DIAGNOSTIC_USED = new AtomicBoolean();
    private static String diagnosticTestMode;
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
        String diagnostic = takeDiagnostic();
        if ("FAILED".equals(diagnostic)) {
            return false;
        }
        if ("PARTIAL".equals(diagnostic)) {
            throw new IllegalStateException("diagnostic owner body failure");
        }
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
        String diagnostic = takeDiagnostic();
        if ("FAILED".equals(diagnostic)) {
            return false;
        }
        if ("PARTIAL".equals(diagnostic)) {
            throw new IllegalStateException("diagnostic owner body failure");
        }
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

    /** A test-only reset for the one-shot diagnostic; the property is the only production switch. */
    static void resetDiagnosticForTest() {
        DIAGNOSTIC_USED.set(false);
        diagnosticTestMode = null;
    }

    /** Test-only mode mirror; production reads only the class-initialized constant. */
    static void setDiagnosticForTest(String mode) {
        diagnosticTestMode = mode;
    }

    /** The class-initialized production mode, exposed only to assert tests do not change it. */
    static String diagnosticModeForTest() {
        return DIAGNOSTIC_MODE;
    }

    /** Whether the one-shot diagnostic has already been consumed; test-only observation. */
    static boolean diagnosticUsedForTest() {
        return DIAGNOSTIC_USED.get();
    }

    /** Consumes the one-shot diagnostic mode; absent/unknown values are a no-op. The mode is read
     * once at class initialization so the owner tick hot path never performs a property lookup. */
    private static String takeDiagnostic() {
        String mode = diagnosticTestMode != null ? diagnosticTestMode : DIAGNOSTIC_MODE;
        if (!"FAILED".equals(mode) && !"PARTIAL".equals(mode)) {
            return "";
        }
        return DIAGNOSTIC_USED.compareAndSet(false, true) ? mode : "";
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
