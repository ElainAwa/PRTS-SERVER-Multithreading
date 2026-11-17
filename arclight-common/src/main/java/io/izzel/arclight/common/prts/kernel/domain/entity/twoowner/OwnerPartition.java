/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.twoowner;

import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The dormant owner partition: with its internal property set, the tasks of one frozen plan are
 * dealt to the owner sites by their seat in the frozen order, so the union of the sites is the
 * whole plan, their intersection is empty and every site sees the plan order. Dealing by seat
 * keeps the split even for the neighbouring region names of one spawn area, which a hash of the
 * region name can put on one side. With the property off every task keeps site zero and the single
 * owner runs the whole plan unchanged.
 */
public final class OwnerPartition {

    /** The internal dormant property; it is not a configuration key. */
    public static final String PROPERTY = "arclight.prts.ownerPartition";

    private OwnerPartition() {
    }

    /** Whether the partition is declared; off in every shipped build. */
    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** The tasks one site runs, in the frozen plan order; off means the whole plan on site zero. */
    public static List<WorkTask> assign(List<WorkTask> tasks, int siteIndex, int siteCount) {
        Objects.requireNonNull(tasks, "tasks");
        if (siteCount <= 0 || siteIndex < 0 || siteIndex >= siteCount) {
            throw new IllegalArgumentException("a partition site is not inside its site count");
        }
        if (!enabled()) {
            return siteIndex == 0 ? List.copyOf(tasks) : List.of();
        }
        List<WorkTask> assigned = new ArrayList<>(tasks.size());
        for (int seat = 0; seat < tasks.size(); seat++) {
            if (Math.floorMod(seat, siteCount) == siteIndex) {
                assigned.add(tasks.get(seat));
            }
        }
        return List.copyOf(assigned);
    }
}
