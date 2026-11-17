/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.twoowner;

import io.izzel.arclight.common.prts.kernel.domain.entity.WorkPlan.WorkTask;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The dormant owner partition: with its internal property set, the tasks of one frozen plan are
 * split between the owner sites by a stable hash of the task world and region, so the union of the
 * sites is the whole plan, their intersection is empty and every site sees the plan order. With the
 * property off every task keeps site zero and the single owner runs the whole plan unchanged.
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

    /** The stable site index of one task: the hash of its world and region, never the list index. */
    public static int siteIndexOf(WorkTask task, int siteCount) {
        Objects.requireNonNull(task, "task");
        if (siteCount <= 0) {
            throw new IllegalArgumentException("a partition needs at least one site");
        }
        return Math.floorMod(hash(task.worldId(), task.regionId()), siteCount);
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
        for (WorkTask task : tasks) {
            if (siteIndexOf(task, siteCount) == siteIndex) {
                assigned.add(task);
            }
        }
        return List.copyOf(assigned);
    }

    private static int hash(String worldId, String regionId) {
        int hash = 0x811c9dc5;
        hash = mix(hash, worldId);
        hash = mix(hash, regionId);
        return hash;
    }

    private static int mix(int hash, String value) {
        if (value == null) {
            return hash;
        }
        for (int index = 0; index < value.length(); index++) {
            hash = (hash ^ value.charAt(index)) * 0x01000193;
        }
        hash = (hash ^ 0x7C) * 0x01000193;
        return hash;
    }
}
