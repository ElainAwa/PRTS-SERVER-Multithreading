/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One planned time budget: a row per world and class, plus the reserved column.
 *
 * <p>The table is planned once per tick and then only read. The conservation of the budget is
 * recomputed from the table rather than remembered from planning time, so a row that was edited
 * after the fact cannot report a budget that never held.</p>
 *
 * @param tickIndex     tick the table was planned for
 * @param rows          one row per world and share class
 * @param reserve       the single reserved column
 * @param hostOverheadMs fixed part of the budget the rows may not borrow
 * @param eBudgetMs     the whole budget of one tick
 */
public record ShareTable(long tickIndex, List<ShareRow> rows, ReserveRow reserve,
                         double hostOverheadMs, double eBudgetMs) {

    /**
     * One row of the table.
     *
     * <p>The margin is published as it is, negative values included: clamping it to zero would
     * hide an exhausted row.</p>
     *
     * @param worldId    the world this row belongs to
     * @param shareClass the class this row carries
     * @param shareMs    the share planned for the tick
     * @param usedMs     the time metered for the tick
     */
    public record ShareRow(String worldId, ShareClass shareClass, double shareMs, double usedMs) {

        /** @return the share minus the used time; may be negative */
        public double marginMs() {
            return shareMs - usedMs;
        }

        /** @return {@code true} when the row spent more than its share */
        public boolean overrun() {
            return marginMs() < 0.0;
        }
    }

    /**
     * The reserved column.
     *
     * @param reserveMs       size of the pool
     * @param usedMs          what has been consumed this tick
     * @param usedByPurpose   consumption per purpose, for the two entries that may draw
     */
    public record ReserveRow(double reserveMs, double usedMs, Map<ReservePurpose, Double> usedByPurpose) {

        /** @return what is left of the pool; may be negative when it was overspent */
        public double remainingMs() {
            return reserveMs - usedMs;
        }
    }

    /** @return the sum of every class share of every world */
    public double sumSharesMs() {
        double sum = 0.0;
        for (ShareRow row : rows) {
            sum += row.shareMs();
        }
        return sum;
    }

    /** @return the sum of the used time of every row */
    public double sumUsedMs() {
        double sum = 0.0;
        for (ShareRow row : rows) {
            sum += row.usedMs();
        }
        return sum;
    }

    /**
     * Finds one row.
     *
     * @param worldId    the world
     * @param shareClass the class
     * @return the row, or {@code null} when the table does not carry it
     */
    public ShareRow row(String worldId, ShareClass shareClass) {
        for (ShareRow row : rows) {
            if (row.worldId().equals(worldId) && row.shareClass() == shareClass) {
                return row;
            }
        }
        return null;
    }

    /**
     * Returns every row of one world, in declaration order.
     *
     * @param worldId the world
     * @return the rows of that world
     */
    public List<ShareRow> rowsOf(String worldId) {
        List<ShareRow> selected = new ArrayList<>();
        for (ShareRow row : rows) {
            if (row.worldId().equals(worldId)) {
                selected.add(row);
            }
        }
        return selected;
    }

    /** @return the distinct worlds the table carries, in row order */
    public List<String> worlds() {
        List<String> worlds = new ArrayList<>();
        for (ShareRow row : rows) {
            if (!worlds.contains(row.worldId())) {
                worlds.add(row.worldId());
            }
        }
        return worlds;
    }
}
