/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

/** The view is a struct of arrays: the parallel arm and the serial arm read the same instance, so
 * a comparison can never be explained by two different reads of the world. */
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

    public String worldId() {
        return worldId;
    }

    public long worldEpoch() {
        return worldEpoch;
    }

    public int count() {
        return count;
    }

    public long entitySeq(int index) {
        return entitySeq[index];
    }

    public int chunkX(int index) {
        return chunkX[index];
    }

    public int chunkZ(int index) {
        return chunkZ[index];
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

    /** Returns the same rows reordered by region and entity sequence. A region is one group of
     * {@code chunks} by {@code chunks} chunks. */
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

    /** Names the region one chunk belongs to. */
    public static String regionKey(int chunkX, int chunkZ, int chunks) {
        int side = Math.max(1, chunks);
        return "r" + Math.floorDiv(chunkX, side) + "." + Math.floorDiv(chunkZ, side);
    }

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

        public boolean full() {
            return count >= MAX_CANDIDATES;
        }

        public int size() {
            return count;
        }

        /** Adds one entity row. */
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
