/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativeMoveRead;
import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathRegionState;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.Vec3;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/** Diagnostic-only per-tick trace of one named entity. Both the UUID and the line limit come from
 * JVM properties read once at class initialization; absent or zero keeps every shipped path silent.
 * Nothing here reaches status, the native evidence line, commit or any product default. */
public final class TargetEntityDiagnostic {

    private static final String UUID_PROPERTY = "arclight.prts.diag.targetUuid";
    private static final String LIMIT_PROPERTY = "arclight.prts.diag.targetDump";
    private static final String TARGET = System.getProperty(UUID_PROPERTY, "").trim();
    private static final int LIMIT = limit(System.getProperty(LIMIT_PROPERTY, ""));
    private static final AtomicInteger LINES = new AtomicInteger();
    private static volatile String testTarget = null;
    private static volatile int testLimit = -1;

    private TargetEntityDiagnostic() {
    }

    private static int limit(String raw) {
        try { return Math.max(0, Integer.parseInt(raw.trim())); }
        catch (RuntimeException invalid) { return 0; }
    }

    private static boolean enabled(UUID uuid) {
        if (uuid == null) return false;
        String target = testTarget != null ? testTarget : TARGET;
        int limit = testLimit >= 0 ? testLimit : LIMIT;
        return !target.isEmpty() && target.equals(uuid.toString()) && limit > 0
            && LINES.get() < limit;
    }

    private static void emit(String line) {
        System.out.println("[PRTS] target.dump|" + line);
        LINES.incrementAndGet();
    }

    /** The frozen read of one tick: request, box, region identity/origin/coverage, frozen collider
     * count and shape summary, and the coverage verdict. */
    public static void noteRead(long tick, UUID uuid, String rowKey, NativeMoveRead read) {
        if (!enabled(uuid)) return;
        StringBuilder shapes = new StringBuilder();
        for (int i = 0; i < Math.min(4, read.entityCollisions().size()); i++) {
            if (i > 0) shapes.append(';');
            shapes.append (read.entityCollisions().get(i).getClass().getSimpleName());
        }
        String region = read.region() instanceof NativePathRegionState state
            ? "PathNavigationRegion:origin=" + state.prts$centerX() + "," + state.prts$centerZ()
            : read.region().getClass().getSimpleName();
        Vec3 request = read.request();
        emit("kind=read|tick=" + tick + "|uuid=" + uuid + "|rowKey=" + rowKey
            + "|request=" + request.x + "," + request.y + "," + request.z
            + "|box=" + read.box() + "|coverage=" + read.coverage()
            + "|region=" + region + "|regionId=" + System.identityHashCode(read.region())
            + "|colliders=" + read.entityCollisions().size() + "|shapes=" + shapes
            + "|maxUpStep=" + read.maxUpStep() + "|onGround=" + read.onGround()
            + "|covers=" + read.covers(request));
    }

    /** The phase-boundary state: own motion and position, ground state, brain activity and goal
     * count. The caller supplies the summary through existing public accessors. */
    public static void notePhase(String phase, long tick, UUID uuid, Vec3 motion, double x,
                                 double y, double z, boolean onGround, String activity, int goals) {
        if (!enabled(uuid)) return;
        emit("kind=phase|tick=" + tick + "|uuid=" + uuid + "|phase=" + phase
            + "|motion=" + motion.x + "," + motion.y + "," + motion.z
            + "|pos=" + x + "," + y + "," + z + "|onGround=" + onGround
            + "|activity=" + activity + "|goals=" + goals);
    }
    /** The movement input that produced one real move argument, printed immediately before the
     * collide call. Unavailable fields are printed as NA rather than guessed. */
    public static void noteInput(long tick, java.util.UUID uuid, float xxa, float zza,
                                 boolean jumping, boolean onGround, String fluid,
                                 boolean stagedPath, String admission, Vec3 finalMove) {
        if (!enabled(uuid)) return;
        emit("kind=input|tick=" + tick + "|uuid=" + uuid
            + "|xxa=" + xxa + "|zza=" + zza + "|jumping=" + jumping
            + "|onGround=" + onGround + "|fluid=" + fluid
            + "|stagedPath=" + stagedPath + "|admission=" + admission
            + "|final=" + (finalMove == null ? "null" : finalMove.x + "," + finalMove.y + "," + finalMove.z));
    }
    /** The answer source of one collide call on the named entity. */
    public static void noteAnswer(String source, long tick, UUID uuid, Vec3 live) {
        if (!enabled(uuid)) return;
        emit("kind=answer|tick=" + tick + "|uuid=" + uuid + "|source=" + source
            + "|live=" + (live == null ? "null" : live.x + "," + live.y + "," + live.z));
    }

    /** Test-only target override; production always reads the class-initialized properties. */
    public static void setTargetForTest(String uuid) { testTarget = uuid; }

    /** Test-only limit override. */
    public static void setLimitForTest(int limit) { testLimit = limit; }

    /** Test-only reset of target, limit and emitted-line counter. */
    public static void resetForTest() {
        testTarget = null; testLimit = -1; LINES.set(0);
    }

    /** Test-only count of emitted lines. */
    public static int linesForTest() { return LINES.get(); }
}