/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder;
import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import java.util.Set;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

/** Per-domain pure-compute metering for generic jobs; one charge per completed job body. */
public final class JobMetering {

    private final Map<String, EnumMap<ShareClass, DoubleAdder>> used =
        new ConcurrentHashMap<>();
    private final Set<String> chargedOnce = ConcurrentHashMap.newKeySet();

    /** Adds one job's measured milliseconds under its world/domain class. */
    public void charge(String worldId, String domainId, double millis) {
        ShareClass shareClass = classOf(domainId);
        used.computeIfAbsent(worldId == null ? "" : worldId,
            key -> new EnumMap<>(ShareClass.class))
            .computeIfAbsent(shareClass, key -> new DoubleAdder())
            .add(Math.max(0.0, millis));
    }

    /** Copies the accumulated values into the planner's used-map shape. */
    public Map<String, EnumMap<ShareClass, Double>> usedMs() {
        Map<String, EnumMap<ShareClass, Double>> copy = new HashMap<>();
        used.forEach((world, classes) -> {
            EnumMap<ShareClass, Double> values = new EnumMap<>(ShareClass.class);
            classes.forEach((shareClass, adder) -> values.put(shareClass, adder.sum()));
            copy.put(world, values);
        });
        return copy;
    }

    /** Charges one job once; a repeated key is ignored, a new key is counted again. */
    public boolean chargeOnce(String jobKey, String worldId, String domainId, double millis) {
        if (jobKey == null || !chargedOnce.add(jobKey)) {
            return false;
        }
        charge(worldId, domainId, millis);
        return true;
    }

    /** Enters the ladder rung of the largest overrun row and reports whether its action ran. */
    public boolean noteDegrade(DegradeLadder ladder, ShareTable table, long tick) {
        DegradeLevel target = DegradeLevel.NONE;
        for (ShareTable.ShareRow row : table.rows()) {
            if (!row.overrun()) continue;
            DegradeLevel level = SharePlanner.levelFor(row.shareClass());
            if (level.ordinal() > target.ordinal()) target = level;
        }
        if (target == DegradeLevel.NONE) {
            ladder.noteTick(false);
            return false;
        }
        ladder.noteEntered(target, tick);
        ladder.noteTick(true);
        return ladder.noteEffective(target);
    }

    /** Plans the two-level quota table with the metered work of this window. */
    public ShareTable plan(SharePlanner planner, java.util.List<String> worlds, long tick,
                           java.util.Map<String, Double> activityWeights) {
        return planner.planFair(worlds, tick, usedMs(), activityWeights);
    }

    private static ShareClass classOf(String domainId) {
        if (domainId == null) return ShareClass.OTHER;
        return switch (domainId) {
            case "entity" -> ShareClass.ENTITY;
            case "ai" -> ShareClass.AI;
            case "blockentity" -> ShareClass.BLOCKENTITY;
            case "graph" -> ShareClass.GRAPH;
            case "event" -> ShareClass.EVENT;
            default -> ShareClass.OTHER;
        };
    }
}
