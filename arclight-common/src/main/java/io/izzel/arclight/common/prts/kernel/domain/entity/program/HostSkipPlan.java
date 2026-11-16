/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The dormant host-skip plan of one dispatch: it lists the rows whose original host tick the owner
 * intends to run, so the host entry can skip exactly those rows and never both. The plan is off
 * unless its internal property is set, an off plan lists nothing, and the listed rows are checked
 * against the rows the owner actually took so one row is never skipped and run.
 */
public final class HostSkipPlan {

    /** The internal dormant property of the host-skip plan; it is not a configuration key. */
    public static final String PROPERTY = "arclight.prts.hostSkip";

    /** The plan of one dispatch: whether it is armed and which rows it may skip. */
    public record Plan(List<Integer> skipRows) {

        public Plan {
            skipRows = List.copyOf(skipRows);
        }

        /** Whether the plan skips one row. */
        public boolean skips(int row) {
            return skipRows.contains(row);
        }

        /** The rows this plan skips, in one stable order. */
        public List<Integer> skipSet() {
            return skipRows;
        }
    }

    private static final Plan DORMANT = new Plan(List.of());

    private HostSkipPlan() {
    }

    /** Whether the dormant property is set; off in every shipped build. */
    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** The off plan: no row is skipped. */
    public static Plan dormant() {
        return DORMANT;
    }

    /**
     * Builds the plan of one dispatch. When the dormant property is off, nothing is skipped even
     * when rows were claimed; when it is on, the claimed rows are listed once each in ascending
     * order.
     */
    public static Plan of(List<Integer> claimedRows) {
        Objects.requireNonNull(claimedRows, "claimedRows");
        if (!enabled()) {
            return DORMANT;
        }
        List<Integer> rows = new ArrayList<>(claimedRows);
        rows.sort(Integer::compareTo);
        List<Integer> distinct = new ArrayList<>(rows.size());
        for (Integer row : rows) {
            if (row == null) {
                throw new IllegalArgumentException("a skipped row needs its number");
            }
            if (distinct.isEmpty() || !distinct.get(distinct.size() - 1).equals(row)) {
                distinct.add(row);
            }
        }
        return new Plan(distinct);
    }

    /** Refuses a plan that would skip a row the owner also runs: one row, one tick. */
    public static Plan verifyNoDoubleTick(Plan plan, List<Integer> ownerRows) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(ownerRows, "ownerRows");
        for (Integer skipped : plan.skipRows()) {
            if (ownerRows.contains(skipped)) {
                throw new IllegalStateException("row would be skipped and run: " + skipped);
            }
        }
        return plan;
    }
}
