/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.dispatch.DispatchReadings;
import io.izzel.arclight.common.prts.kernel.dispatch.WorkerPool;
import io.izzel.arclight.common.prts.kernel.domain.ai.AiJobDeclaration;
import io.izzel.arclight.common.prts.kernel.domain.ai.AiJobGraph;
import io.izzel.arclight.common.prts.kernel.generic.GenericJobExecutor;
import io.izzel.arclight.common.prts.kernel.generic.JobExecutionContext;
import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Internal worker entry to the mixin-added VillagerHostilesSensor value core. */
public final class AiSensorValueBridge {

    static final String PROPERTY = "arclight.prts.aiSensorCore";
    private static WorkerPool pool;
    private static GenericJobExecutor executor;
    private static Object sensor;
    private static java.lang.reflect.Method core;
    private static volatile boolean fallbackMode;

    private AiSensorValueBridge() {
    }

    static boolean matchingValues(String targetTypeId, double distanceSq) {
        boolean[] result = matchingBatch(new String[] {targetTypeId}, new double[] {distanceSq});
        return result != null && result.length == 1 && result[0];
    }

    /** One real WorkerPool job computes the whole batch through the same mixin core. */
    public static boolean[] matchingBatch(String[] typeIds, double[] distanceSq) {
        fallbackMode = false;
        if (!Boolean.getBoolean(PROPERTY)) {
            AiSensorCounters.propertyOff();
            return null;
        }
        try {
            ensurePool();
            if (pool == null || executor == null) return null;
            boolean[] answers = new boolean[typeIds.length];
            JobDeclaration declaration = AiJobDeclaration.of("w", "sensor-batch", typeIds.length, 1L);
            var freeze = AiJobGraph.freeze(List.of(declaration), 1L, 1L, 1L, 2);
            if (!freeze.ok()) return null;
            var handles = executor.execute(freeze.graph(), Map.of(declaration.key(), () -> {
                for (int i = 0; i < typeIds.length; i++) {
                    try {
                        answers[i] = (boolean) core.invoke(sensor, typeIds[i], distanceSq[i]);
                    } catch (ReflectiveOperationException e) {
                        throw new RuntimeException(e);
                    }
                }
            }));
            for (var handle : handles) handle.await(5, TimeUnit.SECONDS);
            AiSensorCounters.coreWorker();
            return answers;
        } catch (Throwable t) {
            fallbackMode = true;
            AiSensorCounters.bridgeFail();
            if (Boolean.getBoolean("arclight.prts.aiDiag")) {
                System.out.println("[PRTS] ai-diag sensor_bridge_fail " + t.getClass().getName()
                    + ": " + t.getMessage());
            }
            return null;
        }
    }

    public static boolean fallbackMode() { return fallbackMode; }

    private static synchronized void ensurePool() {
        if (pool != null) return;
        try {
            Class<?> sensorClass = VillagerHostilesSensor.class;
            sensor = sensorClass.getDeclaredConstructor().newInstance();
            core = sensorClass.getDeclaredMethod("prts$matchingValues", String.class, double.class);
            core.setAccessible(true);
            var ledger = new ArenaLedger();
            pool = WorkerPool.open(new WorkerPool.Spec(2, "ai-worker-", Thread.NORM_PRIORITY, 8, 4),
                1, new DispatchReadings(), ledger);
            double shareMs = 60_000.0;
            try {
                shareMs = Double.parseDouble(System.getProperty("arclight.prts.aiSensorShareMs",
                    "60000"));
            } catch (NumberFormatException ignored) {
            }
            executor = new GenericJobExecutor(pool, new JobExecutionContext(Set.of("ai"),
                Set.of("ai"), Set.of("ai"), 1L, Math.max(100.0, shareMs)));
        } catch (ReflectiveOperationException e) {
            pool = null;
            executor = null;
        }
    }
}
