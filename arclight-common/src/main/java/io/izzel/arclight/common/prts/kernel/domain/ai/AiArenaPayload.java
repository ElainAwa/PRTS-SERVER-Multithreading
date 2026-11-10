/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.ai;

import io.izzel.arclight.common.prts.kernel.arena.ArenaSlot;

import java.util.ArrayList;
import java.util.List;

/** Immutable AI read-set rows written through the P0 arena scratch; kind is carried in the flag. */
public final class AiArenaPayload {

    public enum Kind {
        ACTOR, NEIGHBOUR, TARGET, MEMORY, POI, NAV
    }

    public record Row(Kind kind, long entityId, double x, double y, double z,
                      long version, boolean visible) {
        public Row {
            if (kind == null) throw new IllegalArgumentException("kind is required");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
                throw new IllegalArgumentException("position must be finite");
            }
            if (version < 0L) throw new IllegalArgumentException("version is negative");
        }
    }

    public record Snapshot(long worldEpoch, long generation, List<Row> rows) {
        public Snapshot {
            if (worldEpoch < 0L || generation < 0L) {
                throw new IllegalArgumentException("payload generation is negative");
            }
            rows = List.copyOf(rows == null ? List.of() : rows);
        }
    }

    private AiArenaPayload() {
    }

    /** Writes rows in order; an unknown kind is rejected before any write. */
    public static void write(ArenaSlot.Lease lease, Snapshot snapshot) {
        if (lease == null || snapshot == null) throw new IllegalArgumentException("payload is required");
        for (Row row : snapshot.rows()) {
            if (row.kind() == null) throw new IllegalArgumentException("unknown AI row kind");
        }
        int index = 0;
        for (Row row : snapshot.rows()) {
            long flag = row.kind().ordinal() + 1L;
            // Metadata takes the payload-only columns: entity id, row version, visible flag.
            lease.scratch().write(index++, row.x(), row.y(), row.z(), row.entityId(), row.version(),
                row.visible() ? 1.0 : 0.0, 0.0, 0.0, flag);
        }
    }

    /** Reads the first count rows and restores id/version; an unknown flag or stale row is refused. */
    public static List<Row> read(ArenaSlot.Lease lease, int count, long expectedVersion) {
        if (lease == null || count < 0 || count > lease.scratch().filled()) {
            throw new IllegalArgumentException("invalid AI row count");
        }
        List<Row> rows = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long flag = lease.scratch().flags(index);
            int ordinal = (int) (flag - 1L);
            if (ordinal < 0 || ordinal >= Kind.values().length) {
                throw new IllegalArgumentException("unknown AI row flag " + flag);
            }
            long version = (long) lease.scratch().pitch(index);
            if (version != expectedVersion) {
                throw new IllegalStateException("stale AI row version " + version);
            }
            rows.add(new Row(Kind.values()[ordinal], (long) lease.scratch().yaw(index),
                lease.scratch().posX(index), lease.scratch().posY(index),
                lease.scratch().posZ(index), version, lease.scratch().velX(index) != 0.0));
        }
        return List.copyOf(rows);
    }
}
