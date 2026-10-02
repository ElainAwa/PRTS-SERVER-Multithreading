/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.dispatch;

import io.izzel.arclight.common.prts.kernel.config.KernelSettings;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.kernel.meter.SelfClass;
import io.izzel.arclight.common.prts.kernel.meter.SelfTimers;
import io.izzel.arclight.common.prts.support.PrtsWorldWriteTaps;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;

import java.util.List;

/**
 * The deferred write of one merged batch: the values of a frozen range, applied at the commit.
 *
 * <p>The payload carries plain numbers and entity identities only. It is built on the tick thread
 * while the merge reads the arena slot and it is applied on the tick thread when the commit segment
 * reaches its intent, so no worker and no other thread ever sees it. The write itself is the
 * whitelist the first domain owns - position, yaw, pitch and velocity - and nothing else: no spawn,
 * no removal, no ticket and no interaction between entities.</p>
 *
 * <p>An entity that is no longer in its level when the commit reaches the batch is counted as gone
 * and skipped; the rest of the batch still lands, because one entity leaving the world is not a
 * reason to drop the values of its neighbours. The time the tick thread spends applying the batch is
 * recorded as entity work of that world, which is what makes the residue of the parallel arm
 * readable.</p>
 */
public final class BatchWriteBack implements PrtsWorldWriteTaps.DeferredWrite {

    private final List<StateHasher.Slice> rows;
    private final DispatchReadings readings;

    /**
     * Creates the payload of one batch.
     *
     * @param rows     the committed rows of the batch, in the frozen order
     * @param readings where the application publishes
     */
    public BatchWriteBack(List<StateHasher.Slice> rows, DispatchReadings readings) {
        this.rows = List.copyOf(rows);
        this.readings = readings;
    }

    /** @return the rows this payload carries */
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

    /** @return whether the world held the batch's entities but none of them was applied */
    static boolean noRowsLanded(boolean worldPresent, boolean identicalOnly, int written,
                                int identical, int kept) {
        if (!worldPresent) {
            return false;
        }
        return identicalOnly ? identical + kept == 0 : written == 0;
    }
}
