/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

/**
 * An immutable read-only view of the entity kinematics one world offers to a tick.
 *
 * <p>The view is a struct of arrays: the parallel arm and the serial arm read the same instance, so
 * a comparison can never be explained by two different reads of the world. It is built on the tick
 * thread from a plain snapshot of positions, orientations, velocities and flags, and it carries no
 * host object: a worker only sees primitive values and the entity sequence it was given.</p>
 *
 * <p>The view is bounded ({@link #MAX_CANDIDATES} entries per world). A world with more entities
 * than that contributes its first entries only; the cut is deterministic for one iteration order,
 * which is what the two arms of a comparison need.</p>
 */
public final class EntityCandidateView {

    /** Most entities one world contributes to a single tick. */
    public static final int MAX_CANDIDATES = 4096;

    private final String worldId;
    private final long worldEpoch;
    private final int count;
    private final long[] entitySeq;
    private final int[] chunkX;
    private final int[] chunkZ;
    private final double[] posX;
    private final double[] posY;
    private final double[] posZ;
    private final double[] yaw;
    private final double[] pitch;
    private final double[] velX;
    private final double[] velY;
    private final double[] velZ;
    private final long[] flags;

    private EntityCandidateView(Builder builder) {
        this.worldId = builder.worldId;
        this.worldEpoch = builder.worldEpoch;
        this.count = builder.count;
        this.entitySeq = java.util.Arrays.copyOf(builder.entitySeq, builder.count);
        this.chunkX = java.util.Arrays.copyOf(builder.chunkX, builder.count);
        this.chunkZ = java.util.Arrays.copyOf(builder.chunkZ, builder.count);
        this.posX = java.util.Arrays.copyOf(builder.posX, builder.count);
        this.posY = java.util.Arrays.copyOf(builder.posY, builder.count);
        this.posZ = java.util.Arrays.copyOf(builder.posZ, builder.count);
        this.yaw = java.util.Arrays.copyOf(builder.yaw, builder.count);
        this.pitch = java.util.Arrays.copyOf(builder.pitch, builder.count);
        this.velX = java.util.Arrays.copyOf(builder.velX, builder.count);
        this.velY = java.util.Arrays.copyOf(builder.velY, builder.count);
        this.velZ = java.util.Arrays.copyOf(builder.velZ, builder.count);
        this.flags = java.util.Arrays.copyOf(builder.flags, builder.count);
    }

    /** @return the world this view belongs to */
    public String worldId() {
        return worldId;
    }

    /** @return the world generation the view was taken from */
    public long worldEpoch() {
        return worldEpoch;
    }

    /** @return how many entities the view carries */
    public int count() {
        return count;
    }

    /** @param index entity position in the view @return the stable entity sequence number */
    public long entitySeq(int index) {
        return entitySeq[index];
    }

    /** @param index entity position in the view @return the chunk x coordinate */
    public int chunkX(int index) {
        return chunkX[index];
    }

    /** @param index entity position in the view @return the chunk z coordinate */
    public int chunkZ(int index) {
        return chunkZ[index];
    }

    /** @param index entity position in the view @return x position */
    public double posX(int index) {
        return posX[index];
    }

    /** @param index entity position in the view @return y position */
    public double posY(int index) {
        return posY[index];
    }

    /** @param index entity position in the view @return z position */
    public double posZ(int index) {
        return posZ[index];
    }

    /** @param index entity position in the view @return yaw in degrees */
    public double yaw(int index) {
        return yaw[index];
    }

    /** @param index entity position in the view @return pitch in degrees */
    public double pitch(int index) {
        return pitch[index];
    }

    /** @param index entity position in the view @return velocity on x */
    public double velX(int index) {
        return velX[index];
    }

    /** @param index entity position in the view @return velocity on y */
    public double velY(int index) {
        return velY[index];
    }

    /** @param index entity position in the view @return velocity on z */
    public double velZ(int index) {
        return velZ[index];
    }

    /** @param index entity position in the view @return the opaque flag word of the entity */
    public long flags(int index) {
        return flags[index];
    }

    /**
     * Returns the same rows reordered by region and entity sequence.
     *
     * <p>A region is one group of {@code chunks} by {@code chunks} chunks. Reordering before the
     * plan is frozen is what makes a task range contiguous: the plan then cuts runs straight out of
     * the returned view, and both arms of a comparison see the same row order.</p>
     *
     * @param chunks how many chunks one region covers on a side
     * @return a new view with the rows grouped by region
     */
    public EntityCandidateView sortedByRegion(int chunks) {
        int side = Math.max(1, chunks);
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, java.util.Comparator
            .comparing((Integer i) -> regionKey(chunkX[i], chunkZ[i], side))
            .thenComparingLong(i -> entitySeq[i]));
        Builder builder = builder(worldId, worldEpoch);
        for (int i = 0; i < count; i++) {
            int row = order[i];
            builder.add(entitySeq[row], chunkX[row], chunkZ[row], posX[row], posY[row],
                posZ[row], yaw[row], pitch[row], velX[row], velY[row], velZ[row], flags[row]);
        }
        return builder.build();
    }

    /**
     * Names the region one chunk belongs to.
     *
     * @param chunkX chunk x coordinate
     * @param chunkZ chunk z coordinate
     * @param chunks how many chunks one region covers on a side
     * @return the stable region name
     */
    public static String regionKey(int chunkX, int chunkZ, int chunks) {
        int side = Math.max(1, chunks);
        return "r" + Math.floorDiv(chunkX, side) + "." + Math.floorDiv(chunkZ, side);
    }

    /** @param worldId the world the view belongs to @param worldEpoch the generation of that world
     * @return a builder for one view */
    public static Builder builder(String worldId, long worldEpoch) {
        return new Builder(worldId, worldEpoch);
    }

    /** Collects the entity rows of one world before they are frozen into a view. */
    public static final class Builder {

        private final String worldId;
        private final long worldEpoch;
        private int count;
        private long[] entitySeq = new long[16];
        private int[] chunkX = new int[16];
        private int[] chunkZ = new int[16];
        private double[] posX = new double[16];
        private double[] posY = new double[16];
        private double[] posZ = new double[16];
        private double[] yaw = new double[16];
        private double[] pitch = new double[16];
        private double[] velX = new double[16];
        private double[] velY = new double[16];
        private double[] velZ = new double[16];
        private long[] flags = new long[16];

        private Builder(String worldId, long worldEpoch) {
            this.worldId = worldId;
            this.worldEpoch = worldEpoch;
        }

        /** @return whether the view has reached its bound and refuses more rows */
        public boolean full() {
            return count >= MAX_CANDIDATES;
        }

        /** @return how many rows were added so far */
        public int size() {
            return count;
        }

        /**
         * Adds one entity row.
         *
         * @param entitySeq stable sequence number of the entity
         * @param chunkX    chunk x coordinate
         * @param chunkZ    chunk z coordinate
         * @param x         x position
         * @param y         y position
         * @param z         z position
         * @param yawDeg    yaw in degrees
         * @param pitchDeg  pitch in degrees
         * @param vx        velocity on x
         * @param vy        velocity on y
         * @param vz        velocity on z
         * @param flagWord  opaque flag word
         */
        public void add(long entitySeq, int chunkX, int chunkZ, double x, double y, double z,
                        double yawDeg, double pitchDeg, double vx, double vy, double vz,
                        long flagWord) {
            if (full()) {
                return;
            }
            if (count == this.entitySeq.length) {
                grow();
            }
            this.entitySeq[count] = entitySeq;
            this.chunkX[count] = chunkX;
            this.chunkZ[count] = chunkZ;
            this.posX[count] = x;
            this.posY[count] = y;
            this.posZ[count] = z;
            this.yaw[count] = yawDeg;
            this.pitch[count] = pitchDeg;
            this.velX[count] = vx;
            this.velY[count] = vy;
            this.velZ[count] = vz;
            this.flags[count] = flagWord;
            count++;
        }

        /** @return the frozen view */
        public EntityCandidateView build() {
            return new EntityCandidateView(this);
        }

        private void grow() {
            int next = Math.min(MAX_CANDIDATES, this.entitySeq.length * 2);
            this.entitySeq = java.util.Arrays.copyOf(this.entitySeq, next);
            this.chunkX = java.util.Arrays.copyOf(this.chunkX, next);
            this.chunkZ = java.util.Arrays.copyOf(this.chunkZ, next);
            this.posX = java.util.Arrays.copyOf(this.posX, next);
            this.posY = java.util.Arrays.copyOf(this.posY, next);
            this.posZ = java.util.Arrays.copyOf(this.posZ, next);
            this.yaw = java.util.Arrays.copyOf(this.yaw, next);
            this.pitch = java.util.Arrays.copyOf(this.pitch, next);
            this.velX = java.util.Arrays.copyOf(this.velX, next);
            this.velY = java.util.Arrays.copyOf(this.velY, next);
            this.velZ = java.util.Arrays.copyOf(this.velZ, next);
            this.flags = java.util.Arrays.copyOf(this.flags, next);
        }
    }
}