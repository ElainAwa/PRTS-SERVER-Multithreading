/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.plan;

import io.izzel.arclight.common.prts.kernel.jobs.JobDeclaration;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;
import io.izzel.arclight.common.prts.kernel.sites.WorldLifecycleOwner;
import io.izzel.arclight.common.prts.kernel.sites.WorldSetSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class I3dWorldSetPlanTest {

    private static JobDeclaration declaration(String key) {
        return new JobDeclaration(key, 1L, "w1", "entity", 0, List.of(), 0, "affinity", "cancel",
            List.of(), List.of(), ShareClass.AI, JobDeclaration.SiteClass.PARALLEL, 0, 8, List.of());
    }

    private static ShareTable table(long tick, List<String> worlds) {
        return new SharePlanner().plan(worlds, tick, Map.of());
    }

    private static TickPlanStore.Control control(long generation) {
        return TickPlanStore.Control.of(9L, generation, generation, 1.0, 0L, 0L, 4.0, "normal");
    }

    @Test
    void worldSetGenerationChangeRebuildsPlanWithNewSnapshot() {
        List<JobDeclaration> declarations = List.of(declaration("a/0"));
        TickPlanPlanner.Result first = TickPlanPlanner.plan(new TickPlanPlanner.Input(10L, 2L, 1L,
            List.of("w1"), List.of("entity"), control(1L), declarations, table(10L, List.of("w1")),
            64, TickPlanStore.Feedback.none()));
        TickPlanPlanner.Result rebuilt = TickPlanPlanner.plan(new TickPlanPlanner.Input(11L, 3L, 2L,
            List.of("w1", "w2"), List.of("entity"), control(2L), declarations,
            table(11L, List.of("w1", "w2")), 64, TickPlanStore.Feedback.none()));
        assertTrue(first.ok(), String.valueOf(first.code()) + ":" + first.item());
        assertTrue(rebuilt.ok(), String.valueOf(rebuilt.code()) + ":" + rebuilt.item());
        assertEquals(1L, first.plan().worldSetGeneration());
        assertEquals(2L, rebuilt.plan().worldSetGeneration());
        assertEquals(List.of("w1"), first.plan().worlds());
        assertEquals(List.of("w1", "w2"), rebuilt.plan().worlds());
        assertFalse(first.plan().contentHash() == rebuilt.plan().contentHash());
    }

    @Test
    void lifecycleLoadAndUnloadEventsDriveSnapshotInputGeneration() {
        WorldLifecycleOwner owner = new WorldLifecycleOwner();
        assertTrue(owner.bindOwner(Thread.currentThread()));
        assertTrue(owner.requestCreate("w1"));
        assertTrue(owner.transition("w1", WorldLifecycleOwner.State.LOADING));
        assertTrue(owner.transition("w1", WorldLifecycleOwner.State.READY));
        WorldSetSnapshot.Snapshot loaded = snapshot(owner, 1L);
        assertTrue(owner.requestUnload("w1"));
        assertTrue(owner.transition("w1", WorldLifecycleOwner.State.RECLAIMING));
        assertTrue(owner.transition("w1", WorldLifecycleOwner.State.GONE));
        WorldSetSnapshot.Snapshot gone = snapshot(owner, 2L);
        List<WorldSetSnapshot.Event> events = WorldSetSnapshot.diff(loaded, gone, 2L);
        assertFalse(events.isEmpty());
        WorldSetPlanInput oldInput = WorldSetPlanInput.capture(List.of("w1"), loaded.generation());
        WorldSetPlanInput newInput = WorldSetPlanInput.capture(
            gone.worlds().stream().map(WorldSetSnapshot.World::worldId).toList(), gone.generation());
        assertTrue(newInput.invalidates(oldInput));
    }

    private static WorldSetSnapshot.Snapshot snapshot(WorldLifecycleOwner owner, long generation) {
        WorldLifecycleOwner.State state = owner.stateOf("w1");
        WorldSetSnapshot.Lifecycle lifecycle = switch (state) {
            case CREATING -> WorldSetSnapshot.Lifecycle.CREATING;
            case LOADING -> WorldSetSnapshot.Lifecycle.LOADING;
            case READY -> WorldSetSnapshot.Lifecycle.READY;
            case PINNED -> WorldSetSnapshot.Lifecycle.PINNED;
            case UNLOADING -> WorldSetSnapshot.Lifecycle.UNLOADING;
            case RECLAIMING -> WorldSetSnapshot.Lifecycle.RECLAIMING;
            case GONE -> WorldSetSnapshot.Lifecycle.GONE;
        };
        return WorldSetSnapshot.capture(generation,
            List.of(new WorldSetSnapshot.World("w1", generation, lifecycle, false)));
    }
}
