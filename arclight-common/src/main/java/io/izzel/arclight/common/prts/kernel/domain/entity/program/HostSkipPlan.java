/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.program;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** The host-skip plan of one dispatch: the rows whose host tick the owner runs; the host entry
 * reads it through the bound predicate and the single-authority guard keeps one row on one side. */
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
    private static Plan currentPlan = DORMANT;
    private static long currentTick = Long.MIN_VALUE;

    private HostSkipPlan() {
    }

    /** Starts one tick: the previous tick's plan is cleared before anything can consume it. */
    public static synchronized void beginTick(long tick) {
        currentPlan = DORMANT;
        currentTick = tick;
    }

    /** Publishes this tick's plan; the caller is the one server tick thread of the domain. */
    public static synchronized void publish(Plan plan, long tick) {
        currentPlan = plan == null ? DORMANT : plan;
        currentTick = tick;
    }

    public static synchronized Plan current() {
        return currentPlan;
    }

    public static synchronized long publishedTick() {
        return currentTick;
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

    /** The single-authority guard: every owner row must be skipped here, and no host row may be. */
    public static Plan verifyNoDoubleTick(Plan plan, List<Integer> ownerRows,
                                          List<Integer> hostRows) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(ownerRows, "ownerRows");
        Objects.requireNonNull(hostRows, "hostRows");
        for (Integer owner : ownerRows) {
            if (!plan.skips(owner)) {
                throw new IllegalStateException("owner row is not in the skip plan: " + owner);
            }
        }
        for (Integer host : hostRows) {
            if (plan.skips(host)) {
                throw new IllegalStateException("host row is listed as skipped: " + host);
            }
        }
        return plan;
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
