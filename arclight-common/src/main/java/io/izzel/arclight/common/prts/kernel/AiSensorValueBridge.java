/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import net.minecraft.world.entity.ai.sensing.VillagerHostilesSensor;

/**
 * Internal entry to the mixin-added VillagerHostilesSensor value core.
 *
 * <p>Retired side path: the program-shaped entity work reads sensor values in place instead of
 * handing them to a worker pool, so this bridge runs the same core loop on the calling thread. It
 * keeps its exact dormant behaviour for the existing evidence, and no new caller may depend on it.
 */
@Deprecated
public final class AiSensorValueBridge {

    static final String PROPERTY = "arclight.prts.aiSensorCore";
    private static Object sensor;
    private static java.lang.reflect.Method core;
    private static volatile boolean fallbackMode;

    private AiSensorValueBridge() {
    }

    static boolean matchingValues(String targetTypeId, double distanceSq) {
        boolean[] result = matchingBatch(new String[] {targetTypeId}, new double[] {distanceSq});
        return result != null && result.length == 1 && result[0];
    }

    /** One caller-thread loop over the same mixin core; no pool and no wait exist here. */
    public static boolean[] matchingBatch(String[] typeIds, double[] distanceSq) {
        fallbackMode = false;
        if (!Boolean.getBoolean(PROPERTY)) {
            AiSensorCounters.propertyOff();
            return null;
        }
        try {
            ensureCore();
            if (sensor == null || core == null) return null;
            boolean[] answers = new boolean[typeIds.length];
            for (int i = 0; i < typeIds.length; i++) {
                answers[i] = (boolean) core.invoke(sensor, typeIds[i], distanceSq[i]);
            }
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

    private static synchronized void ensureCore() {
        if (sensor != null && core != null) return;
        try {
            Class<?> sensorClass = VillagerHostilesSensor.class;
            sensor = sensorClass.getDeclaredConstructor().newInstance();
            core = sensorClass.getDeclaredMethod("prts$matchingValues", String.class, double.class);
            core.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            sensor = null;
            core = null;
        }
    }
}
