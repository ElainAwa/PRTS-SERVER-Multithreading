/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.arena.ArenaPassthrough;
import io.izzel.arclight.common.prts.kernel.commit.CommitLog;
import io.izzel.arclight.common.prts.kernel.plan.TickPlan;
import io.izzel.arclight.common.prts.kernel.plan.TickPlanStore;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The internal P2-A02a world-set shape and the existing plan/arena/commit world-id fields. This is
 * a pre-leaf: the snapshot type is package-private and is not wired into the production tick path. */
class WorldSetSnapshotTest {

    private static final String A = "minecraft:overworld";
    private static final String B = "minecraft:the_nether";
    private static final String C = "minecraft:the_end";

    @Test
    void theSnapshotIsSortedCopiedAndImmutable() {
        WorldSetSnapshot.Snapshot snapshot = WorldSetSnapshot.capture(7L, List.of(
            world(B, 2L, WorldSetSnapshot.Lifecycle.PINNED, true),
            world(A, 1L, WorldSetSnapshot.Lifecycle.READY, false)));

        assertEquals(List.of(A, B), snapshot.worlds().stream().map(WorldSetSnapshot.World::worldId).toList());
        assertEquals(1L, snapshot.world(A).generation());
        assertEquals(WorldSetSnapshot.Lifecycle.PINNED, snapshot.world(B).lifecycle());
        assertThrows(UnsupportedOperationException.class,
            () -> snapshot.worlds().add(world(C, 3L, WorldSetSnapshot.Lifecycle.READY, false)));
        assertThrows(IllegalArgumentException.class, () -> WorldSetSnapshot.capture(7L, List.of(
            world(A, 1L, WorldSetSnapshot.Lifecycle.READY, false),
            world(A, 2L, WorldSetSnapshot.Lifecycle.READY, false))));
        assertThrows(IllegalArgumentException.class,
            () -> world(A, 1L, WorldSetSnapshot.Lifecycle.GONE, true));
    }

    @Test
    void theSnapshotCarriesIdsAndStatesButNoLoadedWorldHandle() {
        assertEquals(List.of("worldId", "generation", "lifecycle", "pinned"),
            Arrays.stream(WorldSetSnapshot.World.class.getRecordComponents())
                .map(component -> component.getName()).toList());
        WorldSetSnapshot.Snapshot snapshot = WorldSetSnapshot.capture(3L, List.of(
            world(C, 1L, WorldSetSnapshot.Lifecycle.GONE, false)));
        assertEquals(WorldSetSnapshot.Lifecycle.GONE, snapshot.world(C).lifecycle());
        assertEquals(null, snapshot.world(B));
    }

    @Test
    void theDiffReportsCreatedChangedAndRemovedDeterministically() {
        WorldSetSnapshot.Snapshot before = WorldSetSnapshot.capture(1L, List.of(
            world(A, 1L, WorldSetSnapshot.Lifecycle.READY, false),
            world(B, 2L, WorldSetSnapshot.Lifecycle.PINNED, true)));
        WorldSetSnapshot.Snapshot after = WorldSetSnapshot.capture(2L, List.of(
            world(A, 1L, WorldSetSnapshot.Lifecycle.READY, true),
            world(C, 1L, WorldSetSnapshot.Lifecycle.LOADING, false)));

        List<WorldSetSnapshot.Event> events = WorldSetSnapshot.diff(before, after, 9L);
        assertEquals(List.of(A, C, B), events.stream()
            .map(event -> event.current() == null ? event.previous().worldId()
                : event.current().worldId()).toList());
        assertEquals(List.of(WorldSetSnapshot.EventKind.CHANGED, WorldSetSnapshot.EventKind.CREATED,
            WorldSetSnapshot.EventKind.REMOVED), events.stream()
            .map(WorldSetSnapshot.Event::kind).toList());
        assertEquals(9L, events.get(0).tick());
    }

    @Test
    void aWorldSetGenerationChangeCountsAsAPlanRebuild() {
        TickPlanStore plans = new TickPlanStore(4);
        plans.publish(plan(1L, 1L, 1L));
        plans.publish(plan(1L, 2L, 1L));
        assertEquals(0L, plans.rebuilds(), "same world-set generation keeps the plan history");
        plans.publish(plan(2L, 3L, 2L));
        assertEquals(1L, plans.rebuilds(), "a changed world-set generation is a rebuild");
        assertEquals(2L, plans.latest().worldSetGeneration());
    }

    @Test
    void planArenaAndCommitWorldIdsAreCoveredOrRecordedAsGaps() {
        WorldSetSnapshot.Snapshot snapshot = WorldSetSnapshot.capture(1L, List.of(
            world(A, 1L, WorldSetSnapshot.Lifecycle.READY, false),
            world(B, 2L, WorldSetSnapshot.Lifecycle.READY, false)));
        TickPlan plan = plan(1L, 1L, 1L, List.of(
            step(0, A), step(1, B)));
        ArenaPassthrough arena = new ArenaPassthrough();
        arena.write(A, new byte[]{1});
        arena.write(B, new byte[]{2});
        List<CommitLog.Batch> batches = List.of(
            batch(A, 1L), batch(B, 2L));

        List<String> planIds = plan.commitOrder().stream().map(TickPlan.CommitStep::worldId).toList();
        List<String> arenaIds = List.copyOf(arena.states().keySet());
        List<String> commitIds = batches.stream().map(CommitLog.Batch::worldId).toList();
        assertTrue(WorldSetSnapshot.missingWorldIds(snapshot, planIds, arenaIds, commitIds).isEmpty());
        assertEquals(List.of(C), WorldSetSnapshot.missingWorldIds(snapshot,
            List.of(A, C), arenaIds, commitIds),
            "a world named outside the snapshot is recorded as a gap");
    }

    private static WorldSetSnapshot.World world(String id, long generation,
                                                WorldSetSnapshot.Lifecycle lifecycle, boolean pinned) {
        return new WorldSetSnapshot.World(id, generation, lifecycle, pinned);
    }

    private static TickPlan plan(long worldSetGeneration, long planSequence, long tick) {
        return plan(worldSetGeneration, planSequence, tick, List.of());
    }

    private static TickPlan plan(long worldSetGeneration, long planSequence, long tick,
                                 List<TickPlan.CommitStep> steps) {
        return new TickPlan(tick, planSequence, worldSetGeneration, List.of(A, B), null, List.of(),
            steps, null, List.of(), 0, 0, 0L, TickPlanStore.Feedback.none());
    }

    private static TickPlan.CommitStep step(int position, String worldId) {
        return new TickPlan.CommitStep(position, worldId, "chunk-pipeline",
            "chunk-pipeline/" + worldId + "/main", 1L, 1, 1, false);
    }

    private static CommitLog.Batch batch(String worldId, long digest) {
        return new CommitLog.Batch(worldId, "chunk-pipeline", "chunk-pipeline/" + worldId + "/main",
            CommitLog.Batch.Kind.APPLY, 1L, digest, 1);
    }
}
