/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.SharedRuntime;
import io.izzel.arclight.common.prts.kernel.auth.OwnerUnitPermit;
import io.izzel.arclight.common.prts.kernel.domain.entity.CancelToken;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import java.util.concurrent.TimeUnit;

/** The serial continuation of one navigation span: the host runs the owner body, hands only the
 * navigation span to one shared-runtime worker, parks until that span settles, and then lets the
 * host run the successor. It is opt-in and never returns to an inline span after admission. */
public final class OwnerNavigationWorker {

    /** The diagnostic switch of the serial continuation; off in every shipped build. */
    public static final String PROPERTY = "arclight.prts.ownerNavWorker";

    private static final long WAIT_MS = 5_000L;
    private static final ThreadLocal<Boolean> OWNER_BODY = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private OwnerNavigationWorker() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** Marks the current thread as running a walk owner body, the only place a span is handed off. */
    public static void enterOwnerBody() {
        OWNER_BODY.set(Boolean.TRUE);
    }

    public static void leaveOwnerBody() {
        OWNER_BODY.remove();
    }

    /** Runs one span on a shared worker and waits for it; false keeps the caller on the serial path. */
    public static boolean run(PathNavigation navigation) {
        if (!enabled() || navigation == null || !OWNER_BODY.get()) {
            return false;
        }
        SharedRuntime runtime = SharedRuntime.shared();
        if (runtime.pool() == null && !runtime.open()) {
            throw new IllegalStateException("owner navigation worker pool is unavailable");
        }
        StageCounters.note("ownerWorkerNavigationSpan");
        CancelToken cancel = new CancelToken(0L);
        WorkerPool.GenericHandle handle;
        try {
            handle = runtime.pool().submitGeneric(token -> {
                OwnerUnitPermit.enter("", false);
                try {
                    navigation.tick();
                } finally {
                    OwnerUnitPermit.exit();
                }
            }, cancel);
        } catch (Throwable submissionFailure) {
            StageCounters.note("ownerWorkerNavigationSpanFault");
            throw new IllegalStateException("owner navigation span submission failed", submissionFailure);
        }
        if (handle == null) {
            StageCounters.note("ownerWorkerNavigationSpanFault");
            throw new IllegalStateException("owner navigation span was refused by the pool");
        }
        try {
            MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
            if (server == null) {
                handle.await(WAIT_MS, TimeUnit.MILLISECONDS);
            } else {
                // The span may demand a chunk from the server thread; the host services those demands
                // while it is parked here instead of deadlocking on the joined future.
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WAIT_MS);
                server.managedBlock(() -> handle.future().isDone() || System.nanoTime() > deadline);
                if (!handle.future().isDone()) {
                    throw new IllegalStateException("owner navigation span timed out");
                }
                handle.future().get();
            }
        } catch (Throwable spanFailure) {
            StageCounters.note("ownerWorkerNavigationSpanFault");
            dumpWorkerStacks();
            throw new IllegalStateException("owner navigation span failed", spanFailure);
        }
        return true;
    }

    private static void dumpWorkerStacks() {
        Thread.getAllStackTraces().forEach((thread, stack) -> {
            if (!thread.getName().startsWith(SharedRuntime.THREAD_PREFIX)) {
                return;
            }
            StringBuilder line = new StringBuilder("[PRTS] owner worker stack " + thread.getName());
            for (StackTraceElement element : stack) {
                line.append(" <- ").append(element);
            }
            System.out.println(line);
        });
    }

    /** Stops the shared pool when the serial continuation opened it. */
    public static void close() {
        if (enabled()) {
            SharedRuntime.shared().close();
        }
    }
}