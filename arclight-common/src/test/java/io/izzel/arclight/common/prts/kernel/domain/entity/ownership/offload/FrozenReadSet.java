/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.ownership.offload;

import java.util.Map;

/** Immutable read-set for a detached AI tick; unknown reads and every write fail explicitly. */
public final class FrozenReadSet {

    public record EntityState(long uuidMost, long uuidLeast, double x, double y, double z,
                              double vx, double vy, double vz) {
    }

    public static final class UnknownRead extends RuntimeException {
        public UnknownRead(String what) {
            super("unknown read: " + what);
        }
    }

    public static final class WriteRejected extends RuntimeException {
        public WriteRejected(String what) {
            super("write rejected: " + what);
        }
    }

    private final Map<Long, EntityState> entities;
    private final Map<Long, Integer> blocks;
    private final Map<Long, String> pois;
    private final long fingerprint;

    public FrozenReadSet(Map<Long, EntityState> entities, Map<Long, Integer> blocks,
                         Map<Long, String> pois, long fingerprint) {
        this.entities = Map.copyOf(entities);
        this.blocks = Map.copyOf(blocks);
        this.pois = Map.copyOf(pois);
        this.fingerprint = fingerprint;
    }

    public EntityState entity(long id) {
        EntityState state = entities.get(id);
        if (state == null) {
            throw new UnknownRead("entity " + id);
        }
        return state;
    }

    public int block(long pos) {
        Integer state = blocks.get(pos);
        if (state == null) {
            throw new UnknownRead("block " + pos);
        }
        return state;
    }

    public String poi(long pos) {
        String state = pois.get(pos);
        if (state == null) {
            throw new UnknownRead("poi " + pos);
        }
        return state;
    }

    public long fingerprint() {
        return fingerprint;
    }

    public void setBlock(long pos, int state) {
        throw new WriteRejected("block " + pos);
    }

    public void addEntity(long id, EntityState state) {
        throw new WriteRejected("entity " + id);
    }
}
