/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.fixes.ownership.PrtsLivingEntityTickStateMixin;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Diagnostic-only segment trace inside phase B and travel. Absent or zero properties keep every
 * shipped path silent; a line is not a reading key and never reaches status or commit. */
public final class PhaseBSegmentDiagnostic {

    private static final String TARGET = System.getProperty("arclight.prts.diag.targetUuid", "").trim();
    private static final int LIMIT = limit(System.getProperty("arclight.prts.diag.targetDump", ""));
    private static final AtomicInteger LINES = new AtomicInteger();
    private static volatile String testTarget = null;
    private static volatile int testLimit = -1;

    private PhaseBSegmentDiagnostic() {
    }

    private static int limit(String raw) {
        try { return Math.max(0, Integer.parseInt(raw.trim())); }
        catch (RuntimeException invalid) { return 0; }
    }

    /** Called at each frozen stage boundary with only own (this, tag) arguments. */
    public static void note(Entity entity, String segment) {
        if (entity == null || segment == null) return;
        String target = testTarget != null ? testTarget : TARGET;
        int limit = testLimit >= 0 ? testLimit : LIMIT;
        if (target.isEmpty() || !target.equals(entity.getUUID().toString())
            || limit <= 0 || LINES.get() >= limit) return;
        LINES.incrementAndGet();
        Vec3 motion = entity.getDeltaMovement();
        String goal = entity instanceof Mob mob
            ? mob.goalSelector.getAvailableGoals().stream()
                .filter(g -> g.isRunning()).map(g -> g.getGoal().getClass().getSimpleName())
                .findFirst().orElse("none")
            : "NA";
        boolean frozen = NativeMovePhaseContext.hasFrozenControl();
        emit(entity.getUUID(), entity.tickCount, segment,
            "|xxa=" + (entity instanceof PrtsLivingEntityTickStateMixin s ? s.prts$xxa() : Float.NaN)
            + "|zza=" + (entity instanceof PrtsLivingEntityTickStateMixin s2 ? s2.prts$zza() : Float.NaN)
            + "|motion=" + motion.x + "," + motion.y + "," + motion.z
            + "|pos=" + entity.getX() + "," + entity.getY() + "," + entity.getZ()
            + "|onGround=" + entity.onGround()
            + "|jumping=" + (entity instanceof PrtsLivingEntityTickStateMixin s3 && s3.prts$jumping())
            + "|fluid=" + entity.level().getFluidState(entity.blockPosition()).toString()
            + "|goal=" + goal + "|frozen=" + frozen);
    }

    /** The gated emit step, exposed so a controlled unit test can drive it without a live entity. */
    public static void emit(UUID uuid, long tick, String segment, String fields) {
        String target = testTarget != null ? testTarget : TARGET;
        int limit = testLimit >= 0 ? testLimit : LIMIT;
        if (uuid == null || segment == null || fields == null || target.isEmpty()
            || !target.equals(uuid.toString()) || limit <= 0 || LINES.get() >= limit) return;
        LINES.incrementAndGet();
        System.out.println("[PRTS] phaseb.dump|tick=" + tick + "|uuid=" + uuid + "|segment=" + segment + fields);
    }

    /** Test-only overrides and reset. */
    public static void setTargetForTest(String uuid) { testTarget = uuid; }
    public static void setLimitForTest(int limit) { testLimit = limit; }
    public static void resetForTest() { testTarget = null; testLimit = -1; LINES.set(0); }
    public static int linesForTest() { return LINES.get(); }
    public static String descriptor() { return "(Lnet/minecraft/world/entity/Entity;Ljava/lang/String;)V"; }
}