/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The table is planned once per tick and then only read. */
public record ShareTable(long tickIndex, List<ShareRow> rows, ReserveRow reserve,
                         double hostOverheadMs, double eBudgetMs) {

    /** One row of the table. The margin is published as it is, negative values included: clamping
     * it to zero would hide an exhausted row. */
    public record ShareRow(String worldId, ShareClass shareClass, double shareMs, double usedMs) {

        public double marginMs() {
            return shareMs - usedMs;
        }

        public boolean overrun() {
            return marginMs() < 0.0;
        }
    }

    /** The reserved column. */
    public record ReserveRow(double reserveMs, double usedMs, Map<ReservePurpose, Double> usedByPurpose) {

        public double remainingMs() {
            return reserveMs - usedMs;
        }
    }

    public double sumSharesMs() {
        double sum = 0.0;
        for (ShareRow row : rows) {
            sum += row.shareMs();
        }
        return sum;
    }

    public double sumUsedMs() {
        double sum = 0.0;
        for (ShareRow row : rows) {
            sum += row.usedMs();
        }
        return sum;
    }

    /** Finds one row. */
    public ShareRow row(String worldId, ShareClass shareClass) {
        for (ShareRow row : rows) {
            if (row.worldId().equals(worldId) && row.shareClass() == shareClass) {
                return row;
            }
        }
        return null;
    }

    /** Returns every row of one world, in declaration order. */
    public List<ShareRow> rowsOf(String worldId) {
        List<ShareRow> selected = new ArrayList<>();
        for (ShareRow row : rows) {
            if (row.worldId().equals(worldId)) {
                selected.add(row);
            }
        }
        return selected;
    }

    public List<String> worlds() {
        List<String> worlds = new ArrayList<>();
        for (ShareRow row : rows) {
            if (!worlds.contains(row.worldId())) {
                worlds.add(row.worldId());
            }
        }
        return worlds;
    }

    /** The two entries the reserved pool may pay for. The pool is a single column, deliberately
     * outside the class shares. */
    public enum ReservePurpose {

        FORCED_MATERIALIZE,
        MIGRATION_WAIT
    }
}
