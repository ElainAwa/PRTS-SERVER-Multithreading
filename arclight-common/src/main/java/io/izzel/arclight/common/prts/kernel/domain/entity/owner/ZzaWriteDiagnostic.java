/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import net.minecraft.world.entity.Entity;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Diagnostic-only record of the real PUTFIELD site that writes zza. The UUID and line limit are
 * JVM properties read once at class initialization; absent or zero keeps every shipped path silent.
 * A record is not a reading key: nothing reaches status, the native evidence line or commit. */
public final class ZzaWriteDiagnostic {

    private static final String TARGET = System.getProperty("arclight.prts.diag.targetUuid", "").trim();
    private static final int LIMIT = limit(System.getProperty("arclight.prts.diag.targetDump", ""));
    private static final AtomicInteger LINES = new AtomicInteger();
    private static volatile String testTarget = null;
    private static volatile int testLimit = -1;

    private ZzaWriteDiagnostic() {
    }

    private static int limit(String raw) {
        try { return Math.max(0, Integer.parseInt(raw.trim())); }
        catch (RuntimeException invalid) { return 0; }
    }

    /** Called immediately before the original zza write; the caller has duplicated (this, value)
     * so the write itself keeps its operands and timing. */
    public static void note(Entity entity, float value, String writer) {
        if (entity == null || writer == null) return;
        String target = testTarget != null ? testTarget : TARGET;
        int limit = testLimit >= 0 ? testLimit : LIMIT;
        if (target.isEmpty() || !target.equals(entity.getUUID().toString()) || limit <= 0) return;
        write(entity.getUUID(), entity.tickCount, value, writer);
    }

    /** The same record with explicit identity; public so a controlled unit test can drive it. */
    public static void write(UUID uuid, long tick, float value, String writer) {
        String target = testTarget != null ? testTarget : TARGET;
        int limit = testLimit >= 0 ? testLimit : LIMIT;
        if (uuid == null || writer == null || target.isEmpty()
            || !target.equals(uuid.toString()) || limit <= 0 || LINES.get() >= limit) return;
        LINES.incrementAndGet();
        System.out.println("[PRTS] zza.write|tick=" + tick + "|uuid=" + uuid
            + "|writer=" + writer + "|value=" + value);
    }

    /** Test-only target override; production always uses the class-initialized property. */
    public static void setTargetForTest(String uuid) { testTarget = uuid; }

    /** Test-only limit override. */
    public static void setLimitForTest(int limit) { testLimit = limit; }

    /** Test-only reset of the emitted-line counter and overrides. */
    public static void resetForTest() { testTarget = null; testLimit = -1; LINES.set(0); }

    /** Test-only count of emitted lines. */
    public static int linesForTest() { return LINES.get(); }
}