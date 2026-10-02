/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.arena.ArenaScratch;

/**
 * The first domain body: one kinematic step of a contiguous entity range.
 *
 * <p>The step advances the position by the current velocity, carries and normalizes the
 * orientation, keeps the velocity and the flag word, and does nothing else. There is no collision,
 * no gravity and no interaction between entities, so every entity of a range is independent of the
 * others and the same input always yields the same output. The body reads only the frozen view and
 * writes only the scratch it was handed, which is what keeps a worker unable to reach the world.</p>
 *
 * <p>The token is checked at a bounded checkpoint, so a cancelled batch leaves within one checkpoint
 * instead of running to its end.</p>
 */
public final class EntityIntegrator implements WorkBody {

    /** The arena segment category of kinematic entity state. */
    public static final int SEGMENT_KIND = 7;

    /** How many entities are integrated between two cancellation checks. */
    public static final int CHECKPOINT_ENTITIES = 64;

    /** The shared body instance; it holds no state of its own. */
    public static final EntityIntegrator INSTANCE = new EntityIntegrator();

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;
    private static final ThreadLocal<ArenaScratch> MAIN_SCRATCH =
        ThreadLocal.withInitial(ArenaScratch::new);

    private EntityIntegrator() {
    }

    /**
     * Returns the scratch of the calling tick thread, used by the serial arm and by a fallback.
     *
     * @return the reusable scratch of this thread
     */
    public static ArenaScratch mainScratch() {
        return MAIN_SCRATCH.get();
    }

    @Override
    public long run(WorkBatch batch, ArenaScratch target, CancelToken token) throws CancelledFault {
        return integrate(batch.view(), batch.rangeStart(), batch.rangeEnd(), target, token);
    }

    /**
     * Integrates one range and writes it into the target.
     *
     * @param view   the frozen view to read
     * @param from   first row of the range, inclusive
     * @param to     last row of the range, exclusive
     * @param target the scratch to write
     * @param token  the cancellation token, checked at every checkpoint
     * @return the checksum of the written values
     * @throws CancelledFault when the token was observed cancelled at a checkpoint
     */
    public static long integrate(EntityCandidateView view, int from, int to, ArenaScratch target,
                                 CancelToken token) throws CancelledFault {
        int count = to - from;
        target.reset(count);
        long checksum = OFFSET_BASIS;
        for (int i = 0; i < count; i++) {
            if ((i & (CHECKPOINT_ENTITIES - 1)) == 0 && token.cancelled()) {
                throw new CancelledFault();
            }
            int row = from + i;
            double x = view.posX(row) + view.velX(row);
            double y = view.posY(row) + view.velY(row);
            double z = view.posZ(row) + view.velZ(row);
            double yaw = normalizeYaw(view.yaw(row));
            double pitch = clampPitch(view.pitch(row));
            target.write(i, x, y, z, yaw, pitch, view.velX(row), view.velY(row), view.velZ(row),
                view.flags(row));
            checksum = mix(checksum, Double.doubleToLongBits(x));
            checksum = mix(checksum, Double.doubleToLongBits(y));
            checksum = mix(checksum, Double.doubleToLongBits(z));
            checksum = mix(checksum, Double.doubleToLongBits(yaw));
            checksum = mix(checksum, Double.doubleToLongBits(pitch));
        }
        return checksum;
    }

    /**
     * Integrates one range for the serial arm without a cancellation check.
     *
     * @param view   the frozen view to read
     * @param from   first row of the range, inclusive
     * @param to     last row of the range, exclusive
     * @param target the scratch to write
     * @return the checksum of the written values
     */
    public static long integrateRangeSerial(EntityCandidateView view, int from, int to,
                                            ArenaScratch target) {
        int count = to - from;
        target.reset(count);
        long checksum = OFFSET_BASIS;
        for (int i = 0; i < count; i++) {
            int row = from + i;
            double x = view.posX(row) + view.velX(row);
            double y = view.posY(row) + view.velY(row);
            double z = view.posZ(row) + view.velZ(row);
            double yaw = normalizeYaw(view.yaw(row));
            double pitch = clampPitch(view.pitch(row));
            target.write(i, x, y, z, yaw, pitch, view.velX(row), view.velY(row), view.velZ(row),
                view.flags(row));
            checksum = mix(checksum, Double.doubleToLongBits(x));
            checksum = mix(checksum, Double.doubleToLongBits(y));
            checksum = mix(checksum, Double.doubleToLongBits(z));
            checksum = mix(checksum, Double.doubleToLongBits(yaw));
            checksum = mix(checksum, Double.doubleToLongBits(pitch));
        }
        return checksum;
    }

    /**
     * Integrates a whole view for the serial arm.
     *
     * <p>The serial arm never checks a token: it is the reference the parallel arm is compared with
     * and must run to its end even while a deadline is passing.</p>
     *
     * @param view   the frozen view to read
     * @param target the scratch to write
     * @return the checksum of the written values
     */
    public static long integrateSerial(EntityCandidateView view, ArenaScratch target) {
        int count = view.count();
        target.reset(count);
        long checksum = OFFSET_BASIS;
        for (int row = 0; row < count; row++) {
            double x = view.posX(row) + view.velX(row);
            double y = view.posY(row) + view.velY(row);
            double z = view.posZ(row) + view.velZ(row);
            double yaw = normalizeYaw(view.yaw(row));
            double pitch = clampPitch(view.pitch(row));
            target.write(row, x, y, z, yaw, pitch, view.velX(row), view.velY(row), view.velZ(row),
                view.flags(row));
            checksum = mix(checksum, Double.doubleToLongBits(x));
            checksum = mix(checksum, Double.doubleToLongBits(y));
            checksum = mix(checksum, Double.doubleToLongBits(z));
            checksum = mix(checksum, Double.doubleToLongBits(yaw));
            checksum = mix(checksum, Double.doubleToLongBits(pitch));
        }
        return checksum;
    }

    /**
     * Normalizes yaw into the half-open range of one turn.
     *
     * @param yaw yaw in degrees
     * @return the equivalent yaw in {@code [-180, 180)}
     */
    public static double normalizeYaw(double yaw) {
        double wrapped = yaw % 360.0;
        if (wrapped >= 180.0) {
            wrapped -= 360.0;
        } else if (wrapped < -180.0) {
            wrapped += 360.0;
        }
        return wrapped;
    }

    /**
     * Clamps pitch to the range a head can turn.
     *
     * @param pitch pitch in degrees
     * @return pitch inside {@code [-90, 90]}
     */
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