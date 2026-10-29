/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.dispatch.DispatchPass;
import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityTickOwnership;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** The offline per-class share view of entity, block-entity and AI work. Rows follow the frozen
 * batches of a dispatch pass, one row per class per batch, zero values included. The view is read
 * only: every row carries the host-run decision, so opening this view never triggers an entity
 * takeover. */
public final class EntityClassShares {

    /** The three classes this view publishes; the other share rows belong to another domain. */
    public static final List<ShareClass> CLASSES = List.of(ShareClass.ENTITY,
        ShareClass.BLOCKENTITY, ShareClass.AI);

    /** One class row of one batch. The host decision is always the run-host one here. */
    public record Row(ShareClass shareClass, String worldId, String regionId, long batchId,
                      double shareMs, double usedMs, double selfMs, int hostDecision, boolean zero) {
    }

    /** The frozen rows of one pass, plus the batch boundary and wait bound they were read under. */
    public record Plan(long tickIndex, long deadlineTick, boolean enabled, boolean fallback,
                       boolean takeoverTriggered, List<Row> rows) {
        public List<Row> rowsForBatch(long batchId) {
            List<Row> selected = new ArrayList<>();
            for (Row row : rows) {
                if (row.batchId() == batchId) {
                    selected.add(row);
                }
            }
            return List.copyOf(selected);
        }

        /** The declared deadline is the wait upper bound; no wait is performed by this view. */
        public long waitUpperBound(long tick) {
            return Math.max(0L, deadlineTick - tick);
        }
    }

    private EntityClassShares() {
    }

    /** Reads the per-class rows of one dispatch pass. A disabled view publishes the zero fallback
     * rows instead of shares; it still carries every batch and class so a rollback keeps the shape. */
    public static Plan fromPass(DispatchPass pass, boolean enabled, Map<ShareClass, Double> shareMs,
                                Map<String, EnumMap<ShareClass, Double>> usedMs,
                                Map<ShareClass, Double> selfMs) {
        if (pass == null) {
            throw new IllegalArgumentException("a share view needs a dispatch pass");
        }
        List<Row> rows = new ArrayList<>();
        for (DispatchPass.Entry entry : pass.entries()) {
            WorkPlan.WorkTask task = entry.batch().task();
            String worldId = task.worldId();
            String regionId = task.regionId();
            EnumMap<ShareClass, Double> used = usedMs == null ? null : usedMs.get(worldId);
            for (ShareClass shareClass : CLASSES) {
                double share = enabled ? value(shareMs, shareClass) : 0.0;
                double usedValue = enabled && used != null ? used.getOrDefault(shareClass, 0.0) : 0.0;
                double self = enabled ? value(selfMs, shareClass) : 0.0;
                rows.add(new Row(shareClass, worldId, regionId, task.batchId(), share, usedValue,
                    self, EntityTickOwnership.RUN_HOST_TICK,
                    share == 0.0 && usedValue == 0.0 && self == 0.0));
            }
        }
        return new Plan(pass.plan().tickIndex(), pass.plan().hardDeadlineTick(), enabled, !enabled,
            false, List.copyOf(rows));
    }

    private static double value(Map<ShareClass, Double> values, ShareClass shareClass) {
        if (values == null) {
            return 0.0;
        }
        Double value = values.get(shareClass);
        return value == null ? 0.0 : value;
    }
}
