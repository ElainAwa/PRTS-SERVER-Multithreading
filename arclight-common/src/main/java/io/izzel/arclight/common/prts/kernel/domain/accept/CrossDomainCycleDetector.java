/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.accept;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** The resource-wait deadlock detector: a waiting task that holds a resource its result holder still
 * needs forms a cycle (across domain, event, native and resume kinds), which is reported and refused
 * before admission, and a wait on an unregistered wait point never runs. */
public final class CrossDomainCycleDetector {

    /** The kind of wait edge; all four kinds enter the same cycle check. */
    public enum Kind {
        DOMAIN,
        EVENT,
        NATIVE,
        RESUME
    }

    /** One wait edge: the waiter task waits for the holder task. */
    public record WaitEdge(String waiterTask, String holderTask, Kind kind) {
    }

    /** One detected cycle: its task/resource nodes in order. */
    public record Cycle(boolean found, List<String> path) {
    }

    private final Map<String, Set<String>> waits = new LinkedHashMap<>();
    private final Map<String, Set<String>> held = new LinkedHashMap<>();
    private final Map<String, Set<String>> needs = new LinkedHashMap<>();
    private final Set<String> waitPoints = new LinkedHashSet<>();
    private long refused;

    /** Registers one wait point; a wait on anything else is refused. */
    public synchronized void registerWaitPoint(String name) {
        waitPoints.add(Objects.requireNonNull(name, "name"));
    }

    /** Records one wait edge; an unregistered wait point refuses the edge outright. */
    public synchronized boolean wait(WaitEdge edge) {
        Objects.requireNonNull(edge, "edge");
        String waitPoint = "wait:" + edge.kind() + ":" + edge.waiterTask() + "->"
            + edge.holderTask();
        if (!waitPoints.contains(waitPoint) && !waitPoints.contains("*")) {
            refused++;
            return false;
        }
        waits.computeIfAbsent(edge.waiterTask(), key -> new LinkedHashSet<>())
            .add(edge.holderTask());
        return true;
    }

    /** Records one resource held by a task. */
    public synchronized void hold(String task, String resource) {
        held.computeIfAbsent(task, key -> new LinkedHashSet<>()).add(resource);
    }

    /** Records one resource a task needs to run. */
    public synchronized void need(String task, String resource) {
        needs.computeIfAbsent(task, key -> new LinkedHashSet<>()).add(resource);
    }

    /** Searches the mixed wait/resource graph for a deadlock cycle before admission. */
    public synchronized Cycle detect() {
        for (Map.Entry<String, Set<String>> wait : waits.entrySet()) {
            for (String holder : wait.getValue()) {
                Set<String> needed = needs.getOrDefault(holder, Set.of());
                Set<String> holderHeld = held.getOrDefault(wait.getKey(), Set.of());
                for (String resource : needed) {
                    if (holderHeld.contains(resource)) {
                        refused++;
                        return new Cycle(true, List.of(wait.getKey(), holder, resource,
                            wait.getKey()));
                    }
                }
            }
        }
        return new Cycle(false, List.of());
    }

    public synchronized long refused() {
        return refused;
    }
}
