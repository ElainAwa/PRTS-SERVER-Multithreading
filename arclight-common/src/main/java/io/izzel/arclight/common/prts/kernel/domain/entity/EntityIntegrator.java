/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.arena.ArenaScratch;
import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkBatch;

/** The step advances the position by the current velocity, carries and normalizes the orientation
 * and carries the velocity. */
public final class EntityIntegrator implements WorkBody {

    /** The arena segment category of kinematic entity state. */
    public static final int SEGMENT_KIND = 7;

    /** How many entities are integrated between two cancellation checks. */
    public static final int CHECKPOINT_ENTITIES = 64;

    /** The shared body instance; it holds no state of its own. */
    public static final EntityIntegrator INSTANCE = new EntityIntegrator();

    /** Host step of a row whose own tick this body does not state: position advance and carry. */
    public static final long STEP_PLAIN = 0L;

    /** Host step of an armour stand that does not travel: drag, small speed zeroing, no move. */
    public static final long STEP_LIVING_NO_PHYSICS = 1L;

    /** Host step of an item off the ground with gravity off: move, then the air drag. */
    public static final long STEP_ITEM_AIR = 2L;

    private static final double LIVING_NO_AI_DRAG = 0.98;

    private static final double ITEM_AIR_DRAG_HORIZONTAL = 0.98F;

    private static final double ITEM_AIR_DRAG_VERTICAL = 0.98;

    /** Below this speed the host replaces a velocity component with zero. */
    public static final double MIN_MOVEMENT_DISTANCE = 0.003;

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;
    private static final ThreadLocal<ArenaScratch> MAIN_SCRATCH =
        ThreadLocal.withInitial(ArenaScratch::new);
    private static final ThreadLocal<ArenaScratch> REFERENCE_SCRATCH =
        ThreadLocal.withInitial(ArenaScratch::new);

    private EntityIntegrator() {
    }

    /** Returns the scratch of the calling tick thread, used by a fallback. */
    public static ArenaScratch mainScratch() {
        return MAIN_SCRATCH.get();
    }

    /** The reference arm and the check of a worker's answer read the same branch of the pure step
     * as the worker body, so a separate buffer keeps the two from overwriting each other inside
     * one batch of the frozen order. */
    public static ArenaScratch referenceScratch() {
        return REFERENCE_SCRATCH.get();
    }

    @Override
    public long run(WorkBatch batch, ArenaScratch target, CancelToken token) throws CancelledFault {
        return integrate(batch.view(), batch.rangeStart(), batch.rangeEnd(), target, token);
    }

    /** Integrates one range and writes it into the target. */
    public static long integrate(EntityCandidateView view, int from, int to, ArenaScratch target,
                                 CancelToken token) throws CancelledFault {
        int count = to - from;
        target.reset(count);
        long checksum = OFFSET_BASIS;
        for (int i = 0; i < count; i++) {
            if ((i & (CHECKPOINT_ENTITIES - 1)) == 0 && token.cancelled()) {
                throw new CancelledFault();
            }
            checksum = step(view, from + i, target, i, checksum);
        }
        return checksum;
    }

    /** Integrates one range for the serial arm without a cancellation check. */
    public static long integrateRangeSerial(EntityCandidateView view, int from, int to,
                                            ArenaScratch target) {
        int count = to - from;
        target.reset(count);
        long checksum = OFFSET_BASIS;
        for (int i = 0; i < count; i++) {
            checksum = step(view, from + i, target, i, checksum);
        }
        return checksum;
    }

    /** Integrates a whole view for the serial arm. The serial arm never checks a token: it is the
     * reference the parallel arm is compared with and must run to its end even while a deadline is
     * passing. */
    public static long integrateSerial(EntityCandidateView view, ArenaScratch target) {
        return integrateRangeSerial(view, 0, view.count(), target);
    }

    private static long step(EntityCandidateView view, int row, ArenaScratch target, int index,
                             long checksum) {
        long hostStep = view.flags(row);
        double vx = view.velX(row);
        double vy = view.velY(row);
        double vz = view.velZ(row);
        double x;
        double y;
        double z;
        if (hostStep == STEP_LIVING_NO_PHYSICS) {
            x = view.posX(row);
            y = view.posY(row);
            z = view.posZ(row);
            vx = zeroSmall(LIVING_NO_AI_DRAG * vx);
            vy = zeroSmall(LIVING_NO_AI_DRAG * vy);
            vz = zeroSmall(LIVING_NO_AI_DRAG * vz);
        } else if (hostStep == STEP_ITEM_AIR) {
            x = view.posX(row) + vx;
            y = view.posY(row) + vy;
            z = view.posZ(row) + vz;
            vx = ITEM_AIR_DRAG_HORIZONTAL * vx;
            vy = ITEM_AIR_DRAG_VERTICAL * vy;
            vz = ITEM_AIR_DRAG_HORIZONTAL * vz;
        } else {
            x = view.posX(row) + vx;
            y = view.posY(row) + vy;
            z = view.posZ(row) + vz;
        }
        double yaw = normalizeYaw(view.yaw(row));
        double pitch = clampPitch(view.pitch(row));
        target.write(index, x, y, z, yaw, pitch, vx, vy, vz, hostStep);
        checksum = mix(checksum, Double.doubleToLongBits(x));
        checksum = mix(checksum, Double.doubleToLongBits(y));
        checksum = mix(checksum, Double.doubleToLongBits(z));
        checksum = mix(checksum, Double.doubleToLongBits(yaw));
        checksum = mix(checksum, Double.doubleToLongBits(pitch));
        return checksum;
    }

    private static double zeroSmall(double value) {
        return Math.abs(value) < MIN_MOVEMENT_DISTANCE ? 0.0 : value;
    }

    /** Folds one written row into a running digest. The fold is the comparison the merge uses to
     * judge what a worker answered: two scratches fold to the same digest only when every value of
     * every row has the same raw bits. */
    public static long foldRow(ArenaScratch scratch, int index, long seed) {
        long running = seed;
        running = mix(running, Double.doubleToRawLongBits(scratch.posX(index)));
        running = mix(running, Double.doubleToRawLongBits(scratch.posY(index)));
        running = mix(running, Double.doubleToRawLongBits(scratch.posZ(index)));
        running = mix(running, Float.floatToRawIntBits((float) scratch.yaw(index)));
        running = mix(running, Float.floatToRawIntBits((float) scratch.pitch(index)));
        running = mix(running, Double.doubleToRawLongBits(scratch.velX(index)));
        running = mix(running, Double.doubleToRawLongBits(scratch.velY(index)));
        running = mix(running, Double.doubleToRawLongBits(scratch.velZ(index)));
        return running;
    }

    /** Folds a whole range of a scratch. */
    public static long foldRange(ArenaScratch scratch, int rows) {
        long running = OFFSET_BASIS;
        for (int index = 0; index < rows; index++) {
            running = foldRow(scratch, index, running);
        }
        return running;
    }

    /** Normalizes yaw into the half-open range of one turn. */
    public static double normalizeYaw(double yaw) {
        double wrapped = yaw % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        } else if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    /** Clamps pitch to the range a head can turn. */
    public static double clampPitch(double pitch) {
        if (pitch < -90.0) {
            return -90.0;
        }
        if (pitch > 90.0) {
            return 90.0;
        }
        return pitch;
    }

    private static long mix(long seed, long value) {
        return (seed ^ value) * PRIME;
    }
}
