/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.support.EntityEligibility;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The frozen row set of one world of one tick. It is a struct of arrays: the parallel arm and the
 * serial arm read the same instance, so a comparison can never be explained by two different reads
 * of the world. Every row carries its full identity and the eligibility reason the census reached. */
public final class EntityCandidateView {

    /** The legacy selection cap of one world; a row beyond it is kept as a refusal. The due path
     * declares its own bound instead, so a world's due rows are never cut at this number. */
    public static final int MAX_CANDIDATES = 4096;

    /** One row of a world above the selection cap: the frame keeps it instead of dropping it. */
    public record Refused(EntityRowIdentity identity, EntityEligibility.Reason reason) {

        /** Validates one retained refusal. */
        public Refused {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(reason, "reason");
        }
    }

    private final String worldId;
    private final long worldEpoch;
    private final int maxRows;
    private final int count;
    private final long[] entitySeq;
    private final long[] uuidHigh;
    private final long[] uuidLow;
    private final int[] tickCount;
    private final byte[] reasonOrdinal;
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
    private final List<Refused> refused;

    private EntityCandidateView(Builder builder) {
        this.worldId = builder.worldId;
        this.worldEpoch = builder.worldEpoch;
        this.maxRows = builder.maxRows;
        this.count = builder.count;
        this.entitySeq = java.util.Arrays.copyOf(builder.entitySeq, builder.count);
        this.uuidHigh = java.util.Arrays.copyOf(builder.uuidHigh, builder.count);
        this.uuidLow = java.util.Arrays.copyOf(builder.uuidLow, builder.count);
        this.tickCount = java.util.Arrays.copyOf(builder.tickCount, builder.count);
        this.reasonOrdinal = java.util.Arrays.copyOf(builder.reasonOrdinal, builder.count);
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
        this.refused = List.copyOf(builder.refused);
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

    /** The full identity of one selected row: world incarnation, UUID pair, id and ordinal witness. */
    public EntityRowIdentity identity(int index) {
        return new EntityRowIdentity(worldId, worldEpoch, uuidHigh[index], uuidLow[index],
            (int) entitySeq[index], tickCount[index]);
    }

    /** The eligibility reason the census reached for one selected row. */
    public EntityEligibility.Reason reason(int index) {
        return EntityEligibility.Reason.values()[reasonOrdinal[index]];
    }

    /** Whether the predicate admitted one selected row. */
    public boolean admitted(int index) {
        return reason(index) == EntityEligibility.Reason.ELIGIBLE;
    }

    /** The rows of this world the selection cap kept out of the frame; every one is retained. */
    public List<Refused> refused() {
        return refused;
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
        Builder builder = builder(worldId, worldEpoch, maxRows);
        for (int i = 0; i < count; i++) {
            int row = order[i];
            builder.add(identity(row), reason(row), chunkX[row], chunkZ[row], posX[row],
                posY[row], posZ[row], yaw[row], pitch[row], velX[row], velY[row], velZ[row],
                flags[row]);
        }
        for (Refused refusedRow : refused) {
            builder.refuse(refusedRow.identity(), refusedRow.reason());
        }
        return builder.build();
    }

    /** Names the region one chunk belongs to. */
    public static String regionKey(int chunkX, int chunkZ, int chunks) {
        int side = Math.max(1, chunks);
        return "r" + Math.floorDiv(chunkX, side) + "." + Math.floorDiv(chunkZ, side);
    }

    /** A builder with the legacy selection cap. */
    public static Builder builder(String worldId, long worldEpoch) {
        return new Builder(worldId, worldEpoch, MAX_CANDIDATES);
    }

    /** A builder bounded by the caller's declared row limit; the due path passes its own bound. */
    public static Builder builder(String worldId, long worldEpoch, int maxRows) {
        return new Builder(worldId, worldEpoch, maxRows);
    }

    /** Collects the entity rows of one world before they are frozen into a view. */
    public static final class Builder {

        private final String worldId;
        private final long worldEpoch;
        private final int maxRows;
        private int count;
        private long[] entitySeq = new long[16];
        private long[] uuidHigh = new long[16];
        private long[] uuidLow = new long[16];
        private int[] tickCount = new int[16];
        private byte[] reasonOrdinal = new byte[16];
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
        private final List<Refused> refused = new ArrayList<>();

        private Builder(String worldId, long worldEpoch, int maxRows) {
            if (maxRows <= 0) {
                throw new IllegalArgumentException("a row limit must be positive");
            }
            this.worldId = worldId;
            this.worldEpoch = worldEpoch;
            this.maxRows = maxRows;
        }

        public boolean full() {
            return count >= maxRows;
        }

        public int size() {
            return count;
        }

        /** The rows the selection cap kept out of the frame so far. */
        public List<Refused> refusedRows() {
            return List.copyOf(refused);
        }

        /**
         * Adds one row a fixture declares. The row carries a fixture identity: the numeric id inside
         * the view's world incarnation, with no host UUID or update ordinal.
         */
        public boolean add(long entitySeq, int chunkX, int chunkZ, double x, double y, double z,
                           double yawDeg, double pitchDeg, double vx, double vy, double vz,
                           long flagWord) {
            return add(EntityRowIdentity.fixture(worldId, worldEpoch, (int) entitySeq),
                EntityEligibility.Reason.UNKNOWN, chunkX, chunkZ, x, y, z, yawDeg, pitchDeg, vx,
                vy, vz, flagWord);
        }

        /**
         * Adds one observed row with its identity and reason. A row beyond the selection cap is
         * retained as a refusal and the call returns false, so the cap never drops a row silently.
         */
        public boolean add(EntityRowIdentity identity, EntityEligibility.Reason reason, int chunkX,
                           int chunkZ, double x, double y, double z, double yawDeg,
                           double pitchDeg, double vx, double vy, double vz, long flagWord) {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(reason, "reason");
            if (full()) {
                refuse(identity, reason);
                return false;
            }
            if (count == this.entitySeq.length) {
                grow();
            }
            this.entitySeq[count] = identity.entityId();
            this.uuidHigh[count] = identity.uuidHigh();
            this.uuidLow[count] = identity.uuidLow();
            this.tickCount[count] = identity.tickCount();
            this.reasonOrdinal[count] = (byte) reason.ordinal();
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
            return true;
        }

        /** Retains one row the selection cap kept out of the frame. */
        public void refuse(EntityRowIdentity identity, EntityEligibility.Reason reason) {
            refused.add(new Refused(identity, reason));
        }

        public EntityCandidateView build() {
            return new EntityCandidateView(this);
        }

        private void grow() {
            int next = Math.min(this.maxRows, this.entitySeq.length * 2);
            this.entitySeq = java.util.Arrays.copyOf(this.entitySeq, next);
            this.uuidHigh = java.util.Arrays.copyOf(this.uuidHigh, next);
            this.uuidLow = java.util.Arrays.copyOf(this.uuidLow, next);
            this.tickCount = java.util.Arrays.copyOf(this.tickCount, next);
            this.reasonOrdinal = java.util.Arrays.copyOf(this.reasonOrdinal, next);
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
