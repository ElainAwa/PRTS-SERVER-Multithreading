/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.twoowner;

import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The conflict order of one frozen plan: the rows of both owner sites are ordered by the plan that
 * froze them, never by the order their workers happened to arrive. A task the plan does not name is
 * refused, a repeated arrival of the same task is refused, and the canonical order is stable for
 * every arrival order of the same planned set.
 */
public final class ConflictOrder {

    private ConflictOrder() {
    }

    /**
     * Orders the arrived tasks by their frozen plan position. The arrival order is evidence only and
     * does not move one row ahead of another; an unplanned task or a duplicate arrival is refused.
     */
    public static List<WorkTask> canonical(List<WorkTask> frozenTasks,
                                           Collection<WorkTask> arrived) {
        Objects.requireNonNull(frozenTasks, "frozenTasks");
        Objects.requireNonNull(arrived, "arrived");
        List<WorkTask> plan = List.copyOf(frozenTasks);
        Set<WorkTask> planned = new HashSet<>(plan);
        Set<WorkTask> seen = new HashSet<>();
        for (WorkTask task : arrived) {
            if (!planned.contains(task)) {
                throw new IllegalArgumentException("an arrived task is not in the frozen plan: "
                    + task);
            }
            if (!seen.add(task)) {
                throw new IllegalArgumentException("an arrived task is repeated: " + task);
            }
        }
        List<WorkTask> order = new ArrayList<>(arrived);
        order.sort(Comparator.comparingInt(plan::indexOf));
        return List.copyOf(order);
    }

    /** The frozen plan position of one arrived task, or -1 when the plan never named it. */
    public static int planPosition(List<WorkTask> frozenTasks, WorkTask task) {
        return frozenTasks.indexOf(task);
    }
}
