/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.List;

/** The payload carries plain numbers and entity identities only. */
public final class BatchWriteBack implements PrtsWorldWriteTaps.DeferredWrite {

    private final List<StateHasher.Slice> rows;
    private final Counters readings;

    /** Creates the payload of one batch. */
    public BatchWriteBack(List<StateHasher.Slice> rows, Counters readings) {
        this.rows = List.copyOf(rows);
        this.readings = readings;
    }

    public List<StateHasher.Slice> rows() {
        return rows;
    }

    @Override
    public boolean apply() {
        long startedAt = System.nanoTime();
        String worldId = rows.isEmpty() ? "" : rows.get(0).worldId();
        String regionId = rows.isEmpty() ? "" : rows.get(0).regionId();
        ServerLevel level = LiveEntityAccess.level(worldId);
        boolean identicalOnly = KernelSettings.dispatchIdenticalOnly();
        int written = 0;
        int gone = 0;
        int identical = 0;
        int kept = 0;
        if (level == null) {
            gone = rows.size();
        } else {
            for (StateHasher.Slice row : rows) {
                Entity entity = LiveEntityAccess.entity(level, (int) row.entitySeq());
                if (entity == null) {
                    gone++;
                    continue;
                }
                if (identicalOnly) {
                    // The takeover boundary: a row the host already holds is counted as taken over
                    // and left untouched, because writing the same value through the setters is not
                    // a no-op for the entity; a row that differs stays with the host path.
                    if (LiveEntityAccess.identical(entity, row)) {
                        identical++;
                    } else {
                        kept++;
                    }
                    continue;
                }
                LiveEntityAccess.write(entity, row.x(), row.y(), row.z(), row.yaw(), row.pitch(),
                    row.velX(), row.velY(), row.velZ());
                written++;
            }
        }
        long nanos = System.nanoTime() - startedAt;
        readings.noteWriteBack(written, gone, nanos);
        if (identicalOnly) {
            readings.noteWriteBackIdentity(identical, kept);
        }
        SelfTimers.note(SelfClass.ENTITY, worldId, regionId, nanos);
        // A batch whose world exists but that landed no row is a no-op, and it is counted as one
        // instead of passing silently. The settlement itself is unchanged: the tiers keep their
        // contracts, the world being there is still what "applied" means.
        if (noRowsLanded(level != null, identicalOnly, written, identical, kept)) {
            readings.noteWriteBackNoRows();
        }
        return level != null;
    }

    /** The counters a write-back feeds; the dispatch readings implement it, so the payload names
     * the counters it writes and never the class that holds them. */
    public interface Counters {

        void noteWriteBack(int written, int gone, long nanos);

        void noteWriteBackIdentity(int identical, int kept);

        void noteWriteBackNoRows();
    }

    /** Tells a settlement that landed nothing from one that landed no row: the world being there is
     * still what "applied" means, so only a present world with zero rows counts. */
    public static boolean noRowsLanded(boolean worldPresent, boolean identicalOnly, int written,
                                       int identical, int kept) {
        if (!worldPresent) {
            return false;
        }
        return identicalOnly ? identical + kept == 0 : written == 0;
    }
}
