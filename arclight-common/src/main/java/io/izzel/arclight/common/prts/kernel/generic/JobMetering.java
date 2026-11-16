/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.generic;

import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.degrade.DegradeLadder;
import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.DoubleAdder;

/**
 * Per-domain metering for generic jobs; one charge per completed job body. Wall time and cpu time are
 * kept in separate columns: a job that parks on a latch spends wall time while spending almost no
 * cpu time, and the two columns make that difference visible. {@link #usedMs()} stays the wall column
 * so existing budget planning keeps its historical input; {@link #wallMs()} and {@link #cpuMs()} are
 * read-only copies of both columns.
 */
public final class JobMetering {

    private final Map<String, EnumMap<ShareClass, DoubleAdder>> wallUsed = new ConcurrentHashMap<>();
    private final Map<String, EnumMap<ShareClass, DoubleAdder>> cpuUsed = new ConcurrentHashMap<>();
    private final Set<String> chargedOnce = ConcurrentHashMap.newKeySet();

    /** Adds one job's measured wall milliseconds under its world/domain class; cpu is zero. */
    public void charge(String worldId, String domainId, double millis) {
        charge(worldId, domainId, millis, 0.0);
    }

    /** Adds one job's measured wall and cpu milliseconds under its world/domain class. */
    public void charge(String worldId, String domainId, double wallMs, double cpuMs) {
        add(wallUsed, worldId, domainId, wallMs);
        add(cpuUsed, worldId, domainId, cpuMs);
    }

    /** Copies the accumulated wall values into the planner's used-map shape. */
    public Map<String, EnumMap<ShareClass, Double>> usedMs() {
        return copy(wallUsed);
    }

    /** Copies the accumulated wall values without changing the historical {@link #usedMs()} name. */
    public Map<String, EnumMap<ShareClass, Double>> wallMs() {
        return copy(wallUsed);
    }

    /** Copies the accumulated cpu values into the same world/class shape. */
    public Map<String, EnumMap<ShareClass, Double>> cpuMs() {
        return copy(cpuUsed);
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

    /** Plans the two-level quota table with the wall work of this window. */
    public ShareTable plan(SharePlanner planner, java.util.List<String> worlds, long tick,
                           java.util.Map<String, Double> activityWeights) {
        return planner.planFair(worlds, tick, usedMs(), activityWeights);
    }

    private static void add(Map<String, EnumMap<ShareClass, DoubleAdder>> target, String worldId,
                            String domainId, double millis) {
        target.computeIfAbsent(worldId == null ? "" : worldId,
                key -> new EnumMap<>(ShareClass.class))
            .computeIfAbsent(classOf(domainId), key -> new DoubleAdder())
            .add(nonNegative(millis));
    }

    private static Map<String, EnumMap<ShareClass, Double>> copy(
        Map<String, EnumMap<ShareClass, DoubleAdder>> source) {
        Map<String, EnumMap<ShareClass, Double>> result = new HashMap<>();
        source.forEach((world, classes) -> {
            EnumMap<ShareClass, Double> values = new EnumMap<>(ShareClass.class);
            classes.forEach((shareClass, adder) -> values.put(shareClass, adder.sum()));
            result.put(world, values);
        });
        return result;
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) && value > 0.0 ? value : 0.0;
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
