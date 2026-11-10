/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.plan;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Immutable world-set input of a plan freeze: sorted world ids plus the generation the plan was
 * built at. A change of ids or generation invalidates the previous plan input. */
public record WorldSetPlanInput(List<String> worldIds, long generation) {

    public WorldSetPlanInput {
        if (generation < 0L) {
            throw new IllegalArgumentException("world-set generation is negative");
        }
        List<String> copied = new ArrayList<>(worldIds == null ? List.of() : worldIds);
        copied.sort(Comparator.naturalOrder());
        worldIds = List.copyOf(copied);
    }

    public static WorldSetPlanInput capture(List<String> worldIds, long generation) {
        return new WorldSetPlanInput(worldIds, generation);
    }

    /** True when the two inputs carry the same world ids. */
    public boolean sameWorlds(WorldSetPlanInput other) {
        return other != null && worldIds.equals(other.worldIds);
    }

    /** True when ids or generation changed against the previous input. */
    public boolean invalidates(WorldSetPlanInput previous) {
        return previous == null || generation != previous.generation
            || !worldIds.equals(previous.worldIds);
    }

    /** Returns the same ids at the next generation. */
    public WorldSetPlanInput nextGeneration() {
        return new WorldSetPlanInput(worldIds, generation + 1L);
    }
}
