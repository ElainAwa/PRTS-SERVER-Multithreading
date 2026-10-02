/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.arena;

import java.util.Arrays;

/** A scratch is written only by the thread that owns it, and it is reset before every use, so a
 * shorter batch can never read the tail of a longer one. */
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

    /** Prepares the buffer for a range of the given length. */
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

    /** Writes one integrated entity row. */
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

    public int filled() {
        return filled;
    }

    public double posX(int index) {
        return posX[index];
    }

    public double posY(int index) {
        return posY[index];
    }

    public double posZ(int index) {
        return posZ[index];
    }

    public double yaw(int index) {
        return yaw[index];
    }

    public double pitch(int index) {
        return pitch[index];
    }

    public double velX(int index) {
        return velX[index];
    }

    public double velY(int index) {
        return velY[index];
    }

    public double velZ(int index) {
        return velZ[index];
    }

    public long flags(int index) {
        return flags[index];
    }
}
