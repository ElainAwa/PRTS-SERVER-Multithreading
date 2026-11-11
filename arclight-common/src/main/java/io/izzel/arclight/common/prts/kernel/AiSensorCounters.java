/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/** Internal counters for the single-method sensor carrier; no formal readout key is published. */
public final class AiSensorCounters {

    public static final String DIAG = "arclight.prts.aiDiag";
    public static final String CARRIER = "arclight.prts.aiSensorCore";
    private static final LongAdder MIXIN_APPLIED = new LongAdder();
    private static final LongAdder CORE_HOST = new LongAdder();
    private static final LongAdder CORE_WORKER = new LongAdder();
    private static final LongAdder BRIDGE_FAIL = new LongAdder();
    private static final LongAdder FALLBACK_HOST = new LongAdder();
    private static final LongAdder PROPERTY_OFF = new LongAdder();
    private static final LongAdder MOB_SERVER_AI = new LongAdder();
    private static final LongAdder BRAIN_TICK = new LongAdder();
    private static final LongAdder SENSOR_TICK = new LongAdder();
    private static final LongAdder NEAREST_DO_TICK = new LongAdder();
    private static final LongAdder HOSTILES_DO_TICK = new LongAdder();
    private static final LongAdder IS_MATCHING = new LongAdder();
    private static final LongAdder LEVEL_TICK = new LongAdder();
    private static final LongAdder ENTITY_TICK = new LongAdder();
    private static final LongAdder LIVING_TICK = new LongAdder();
    private static final LongAdder MOB_TICK = new LongAdder();
    private static final LongAdder TICK_NON_PASSENGER = new LongAdder();
    private static final LongAdder VILLAGER_TICK = new LongAdder();
    private static final LongAdder BATCH_STARTED = new LongAdder();
    private static final LongAdder WORKER_SUCCESS = new LongAdder();
    private static final LongAdder WORKER_FALLBACK = new LongAdder();
    private static final LongAdder HOST_NOT_REPEATED = new LongAdder();
    private static final LongAdder BATCH_NO_VISIBLE = new LongAdder();
    private static final LongAdder BATCH_EMPTY = new LongAdder();
    private static final LongAdder BATCH_CANCEL = new LongAdder();
    private static final LongAdder HOST_FALLBACK = new LongAdder();
    private static final LongAdder MEMORY_WRITES = new LongAdder();
    private static final LongAdder MEMORY_CLEARS = new LongAdder();
    private static final AtomicLong BATCH_LOG = new AtomicLong();
    private static final AtomicLong LOG_COUNTER = new AtomicLong();

    private AiSensorCounters() {
    }

    public static boolean enabled() {
        return Boolean.getBoolean(DIAG) || Boolean.getBoolean(CARRIER);
    }

    public static void mixinApplied() {
        MIXIN_APPLIED.increment();
        long count = LOG_COUNTER.incrementAndGet();
        if (enabled() && (count <= 3L || count % 100L == 0L)) {
            System.out.println("[PRTS] ai-diag sensor_counters " + line());
        }
    }
    public static void coreHost() { CORE_HOST.increment(); }
    public static void coreWorker() { CORE_WORKER.increment(); }
    public static void bridgeFail() { BRIDGE_FAIL.increment(); }
    public static void fallbackHost() { FALLBACK_HOST.increment(); }
    public static void propertyOff() { PROPERTY_OFF.increment(); }

    public static void mobServerAiStep() { trace(MOB_SERVER_AI, "mob_serverAiStep"); }
    public static void brainTick() { trace(BRAIN_TICK, "brain_tick"); }
    public static void sensorTick() { trace(SENSOR_TICK, "sensor_tick"); }
    public static void nearestLivingDoTick() { trace(NEAREST_DO_TICK, "nearest_living_dotick"); }
    public static void villagerHostilesDoTick() { trace(HOSTILES_DO_TICK, "villager_hostiles_dotick"); }
    public static void isMatchingCall() { trace(IS_MATCHING, "isMatching_calls"); }
    public static void levelTick() { trace(LEVEL_TICK, "level_tick"); }
    public static void entityTick() { trace(ENTITY_TICK, "entity_tick"); }
    public static void livingTick() { trace(LIVING_TICK, "living_tick"); }
    public static void mobTick() { trace(MOB_TICK, "mob_tick"); }
    public static void tickNonPassenger() { trace(TICK_NON_PASSENGER, "tick_non_passenger"); }
    public static void villagerTick() { trace(VILLAGER_TICK, "villager_tick"); }
    public static void batchStarted() {
        BATCH_STARTED.increment();
        long count = BATCH_LOG.incrementAndGet();
        if (enabled() && (count <= 3L || count % 100L == 0L)) {
            System.out.println("[PRTS] ai-diag batch_counters " + batchLine());
        }
    }
    public static void workerSuccess(int n) { WORKER_SUCCESS.add(n); }
    public static void workerFallback() { WORKER_FALLBACK.increment(); }
    public static void hostNotRepeated(int n) { HOST_NOT_REPEATED.add(n); }
    public static void batchNoVisible() { BATCH_NO_VISIBLE.increment(); }
    public static void batchEmpty() { BATCH_EMPTY.increment(); }
    public static void batchCancel() { BATCH_CANCEL.increment(); }
    public static void hostFallback() { HOST_FALLBACK.increment(); }
    public static void memoryWrite() { MEMORY_WRITES.increment(); }
    public static void memoryClear() { MEMORY_CLEARS.increment(); }

    private static void trace(LongAdder adder, String key) {
        if (!enabled()) return;
        adder.increment();
        long value = adder.sum();
        if (value <= 3L || value % 100L == 0L) {
            System.out.println("[PRTS] ai-diag ai_tick_trace " + key + "=" + value);
        }
    }

    public static String batchLine() {
        long hostFallback = HOST_FALLBACK.sum();
        long hostCalls = IS_MATCHING.sum();
        return "batch_started=" + BATCH_STARTED.sum() + " worker_success=" + WORKER_SUCCESS.sum()
            + " worker_fallback=" + WORKER_FALLBACK.sum() + " host_not_repeated="
            + HOST_NOT_REPEATED.sum() + " host_fallback=" + hostFallback
            + " host_nonfallback=" + Math.max(0L, hostCalls - hostFallback)
            + " memory_writes=" + MEMORY_WRITES.sum() + " memory_clears=" + MEMORY_CLEARS.sum()
            + " batch_cancel=" + BATCH_CANCEL.sum();
    }

    public static String line() {
        return "mixin_applied=" + MIXIN_APPLIED.sum() + " core_calls_host=" + CORE_HOST.sum()
            + " core_calls_worker=" + CORE_WORKER.sum() + " bridge_fail=" + BRIDGE_FAIL.sum()
            + " fallback_host=" + FALLBACK_HOST.sum() + " property_off=" + PROPERTY_OFF.sum()
            + " mob_serverAiStep=" + MOB_SERVER_AI.sum() + " brain_tick=" + BRAIN_TICK.sum()
            + " sensor_tick=" + SENSOR_TICK.sum() + " nearest_living_dotick=" + NEAREST_DO_TICK.sum()
            + " villager_hostiles_dotick=" + HOSTILES_DO_TICK.sum()
            + " isMatching_calls=" + IS_MATCHING.sum() + " level_tick=" + LEVEL_TICK.sum()
            + " entity_tick=" + ENTITY_TICK.sum() + " living_tick=" + LIVING_TICK.sum()
            + " mob_tick=" + MOB_TICK.sum() + " tick_non_passenger=" + TICK_NON_PASSENGER.sum()
            + " villager_tick=" + VILLAGER_TICK.sum() + " batch_started=" + BATCH_STARTED.sum()
            + " worker_success=" + WORKER_SUCCESS.sum() + " worker_fallback=" + WORKER_FALLBACK.sum()
            + " host_not_repeated=" + HOST_NOT_REPEATED.sum()
            + " batch_no_visible=" + BATCH_NO_VISIBLE.sum() + " batch_empty=" + BATCH_EMPTY.sum()
            + " batch_cancel=" + BATCH_CANCEL.sum() + " host_fallback_calls=" + HOST_FALLBACK.sum();
    }
}
