/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

import java.util.Arrays;

/**
 * The reusable output buffer of one batch: the integrated position, orientation and velocity of a
 * contiguous entity range.
 *
 * <p>A scratch is written only by the thread that owns it, and it is reset before every use, so a
 * shorter batch can never read the tail of a longer one. It is the only object a worker body sees
 * besides its frozen batch, which keeps the worker away from the world and from the arena's
 * bookkeeping at the same time.</p>
 */
public final class ArenaScratch {

    private double[] posX = new double[0];
    private double[] posY = new double[0];
    private double[] posZ = new double[0];
    private double[] yaw = new double[0];
    private double[] pitch = new double[0];
    private double[] velX = new double[0];
    private double[] velY = new double[0];
    private double[] velZ = new double[0];
    private long[] flags = new long[0];
    private int filled;

    /**
     * Prepares the buffer for a range of the given length.
     *
     * @param capacity how many entities the next write covers
     */
    public void reset(int capacity) {
        int needed = Math.max(0, capacity);
        if (posX.length < needed) {
            posX = new double[needed];
            posY = new double[needed];
            posZ = new double[needed];
            yaw = new double[needed];
            pitch = new double[needed];
            velX = new double[needed];
            velY = new double[needed];
            velZ = new double[needed];
            flags = new long[needed];
        } else {
            Arrays.fill(flags, 0, filled, 0L);
        }
        filled = 0;
    }

    /**
     * Writes one integrated entity row.
     *
     * @param index  row position inside the range
     * @param x      integrated x position
     * @param y      integrated y position
     * @param z      integrated z position
     * @param yawDeg normalized yaw
     * @param pitchDeg clamped pitch
     * @param vx     velocity on x
     * @param vy     velocity on y
     * @param vz     velocity on z
     * @param flag   opaque flag word
     */
    public void write(int index, double x, double y, double z, double yawDeg, double pitchDeg,
                      double vx, double vy, double vz, long flag) {
        posX[index] = x;
        posY[index] = y;
        posZ[index] = z;
        yaw[index] = yawDeg;
        pitch[index] = pitchDeg;
        velX[index] = vx;
        velY[index] = vy;
        velZ[index] = vz;
        flags[index] = flag;
        if (index + 1 > filled) {
            filled = index + 1;
        }
    }

    /** @return how many rows were written since the last reset */
    public int filled() {
        return filled;
    }

    /** @param index row position @return integrated x position */
    public double posX(int index) {
        return posX[index];
    }

    /** @param index row position @return integrated y position */
    public double posY(int index) {
        return posY[index];
    }

    /** @param index row position @return integrated z position */
    public double posZ(int index) {
        return posZ[index];
    }

    /** @param index row position @return yaw in degrees */
    public double yaw(int index) {
        return yaw[index];
    }

    /** @param index row position @return pitch in degrees */
    public double pitch(int index) {
        return pitch[index];
    }

    /** @param index row position @return velocity on x */
    public double velX(int index) {
        return velX[index];
    }

    /** @param index row position @return velocity on y */
    public double velY(int index) {
        return velY[index];
    }

    /** @param index row position @return velocity on z */
    public double velZ(int index) {
        return velZ[index];
    }

    /** @param index row position @return the opaque flag word */
    public long flags(int index) {
        return flags[index];
    }
}
