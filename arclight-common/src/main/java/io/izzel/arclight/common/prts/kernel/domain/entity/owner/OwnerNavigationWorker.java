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

/** Legacy serial continuation of one navigation span: the host hands the span to one shared-runtime
 * worker, parks until that span settles, and lets the successor follow. It has no production caller
 * any more and must not be wired back into a production path: a span that can demand a chunk needs
 * the registered wait-result continuation, not a parked host. */
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

    /** Legacy entry with no production caller; false keeps a caller on the serial path. Do not wire
     * this into a production path without the separately authorized wait-result continuation. */
    public static boolean run(PathNavigation navigation) {
        if (!enabled() || navigation == null || !OWNER_BODY.get()) {
            return false;
        }
        return runSpan(navigation);
    }

    /** Legacy entry with no production caller; kept only so the retired span path stays readable.
     * Do not wire this into a production path without the separately authorized continuation. */
    public static boolean runProgramSpan(PathNavigation navigation) {
        if (!enabled() || navigation == null) {
            return false;
        }
        return runSpan(navigation);
    }

    private static boolean runSpan(PathNavigation navigation) {
        SharedRuntime runtime = SharedRuntime.shared();
        if (runtime.pool() == null && !runtime.open()) {
            throw new IllegalStateException("owner navigation worker pool is unavailable");
        }
        StageCounters.note("ownerWorkerNavigationSpan");
        CancelToken cancel = new CancelToken(0L);
        WorkerPool.GenericHandle handle;
        try {
            handle = runtime.pool().submitGeneric(token -> {
                OwnerUnitPermit.enter("");
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
                // Not for production: this park is exactly the serial-continuation shape the segment
                // program replaces. A production span must be demand-free or use the registered
                // wait-result continuation; a parked host is never a production answer.
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