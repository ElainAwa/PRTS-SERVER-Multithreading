/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.plan.WorldSetPlanInput;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSetSnapshotTest {

    private static WorldSetSnapshot.World world(String id) {
        return new WorldSetSnapshot.World(id, 1L, WorldSetSnapshot.Lifecycle.READY, false);
    }

    @Test
    void sortedImmutableAndDuplicateIdRejected() {
        WorldSetSnapshot.Snapshot first = WorldSetSnapshot.capture(1L,
            List.of(world("b"), world("a")));
        assertEquals(List.of("a", "b"), first.worlds().stream()
            .map(WorldSetSnapshot.World::worldId).toList());
        assertThrows(UnsupportedOperationException.class, () -> first.worlds().add(world("z")));
        assertThrows(IllegalArgumentException.class,
            () -> WorldSetSnapshot.capture(1L, List.of(world("a"), world("a"))));
    }

    @Test
    void pinnedWorldMustBeOnlineAndEventsInvalidateGeneration() {
        assertThrows(IllegalArgumentException.class, () -> new WorldSetSnapshot.World(
            "w", 1L, WorldSetSnapshot.Lifecycle.UNLOADING, true));
        WorldSetSnapshot.World pinned = new WorldSetSnapshot.World("w", 1L,
            WorldSetSnapshot.Lifecycle.PINNED, true);
        assertTrue(pinned.pinned());
        assertEquals(WorldSetSnapshot.Lifecycle.PINNED, pinned.lifecycle());
    }

    @Test
    void missingWorldIdsReportsPlanCommitMismatch() {
        WorldSetSnapshot.Snapshot snapshot = WorldSetSnapshot.capture(1L, List.of(world("a")));
        List<String> missing = WorldSetSnapshot.missingWorldIds(snapshot, List.of("a", "b"),
            List.of(), List.of("c"));
        assertTrue(missing.contains("b"));
        assertTrue(missing.contains("c"));
    }

    @Test
    void snapshotIdsFeedPlanInputAndInvalidateOnChange() {
        WorldSetSnapshot.Snapshot first = WorldSetSnapshot.capture(1L, List.of(world("a")));
        WorldSetPlanInput input = WorldSetPlanInput.capture(
            first.worlds().stream().map(WorldSetSnapshot.World::worldId).toList(),
            first.generation());
        WorldSetSnapshot.Snapshot second = WorldSetSnapshot.capture(2L,
            List.of(world("a"), world("b")));
        WorldSetPlanInput next = WorldSetPlanInput.capture(
            second.worlds().stream().map(WorldSetSnapshot.World::worldId).toList(),
            second.generation());
        assertTrue(next.invalidates(input));
        assertNull(WorldSetSnapshot.capture(1L, List.of()).world("a"));
    }

    @Test
    void diffReturnsCreateRemoveChanged() {
        WorldSetSnapshot.Snapshot first = WorldSetSnapshot.capture(1L, List.of(world("a")));
        WorldSetSnapshot.World changed = new WorldSetSnapshot.World("a", 2L,
            WorldSetSnapshot.Lifecycle.PINNED, true);
        WorldSetSnapshot.Snapshot second = WorldSetSnapshot.capture(2L,
            List.of(changed, world("b")));
        List<WorldSetSnapshot.Event> events = WorldSetSnapshot.diff(first, second, 2L);
        assertFalse(events.isEmpty());
        assertTrue(events.stream().anyMatch(e -> e.kind() == WorldSetSnapshot.EventKind.CREATED));
        assertTrue(events.stream().anyMatch(e -> e.kind() == WorldSetSnapshot.EventKind.CHANGED));
    }
}
