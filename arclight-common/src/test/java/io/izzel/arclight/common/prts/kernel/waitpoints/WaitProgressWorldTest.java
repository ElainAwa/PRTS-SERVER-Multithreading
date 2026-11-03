/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-world value side of a progress row: only worlds the producer carries are read, the delta
 * is from the previous export of the same world and epoch, and a disappeared world publishes no zero. */
class WaitProgressWorldTest {

    private static final String WORLD = "minecraft:overworld";
    private static final String SECOND = "minecraft:the_end";

    @Test
    void worldsAndEpochsCarryTheirOwnValueAndDelta() {
        WaitProgress progress = new WaitProgress();
        AtomicInteger gen = new AtomicInteger(3);
        progress.bindWorlds("gen", "source", () -> List.of(
            new WaitProgress.WorldValue(WORLD, 1L, gen.get(), 2L),
            new WaitProgress.WorldValue(SECOND, 2L, 1L, 4L)));

        List<WaitProgress.WorldReading> first = progress.worldReadings("gen");
        assertEquals(List.of(WORLD, SECOND), first.stream()
            .map(WaitProgress.WorldReading::worldId).toList());
        assertEquals(3L, first.get(0).value());
        assertEquals(3L, first.get(0).delta());
        assertEquals(2L, first.get(0).lightValue());
        assertEquals(2L, first.get(0).lightDelta());
        assertEquals(1L, first.get(1).value());

        gen.set(5);
        List<WaitProgress.WorldReading> second = progress.worldReadings("gen");
        assertEquals(5L, second.get(0).value());
        assertEquals(2L, second.get(0).delta());
    }

    @Test
    void anAbsentWorldPublishesNoZeroAndLosesItsPreviousSlot() {
        WaitProgress progress = new WaitProgress();
        AtomicInteger present = new AtomicInteger(0);
        progress.bindWorlds("gen", "source", () -> present.get() == 0
            ? List.of(new WaitProgress.WorldValue(WORLD, 1L, 2L, 3L))
            : List.of());

        assertEquals(1, progress.worldReadings("gen").size());
        present.set(1);
        assertTrue(progress.worldReadings("gen").isEmpty());

        present.set(0);
        WaitProgress.WorldReading returned = progress.worldReadings("gen").get(0);
        assertEquals(2L, returned.value());
        assertEquals(2L, returned.delta(), "a reappeared world starts a fresh delta, not a stale one");
    }

    @Test
    void anEpochChangeRestartsTheDelta() {
        WaitProgress progress = new WaitProgress();
        AtomicInteger epoch = new AtomicInteger(1);
        AtomicInteger value = new AtomicInteger(2);
        progress.bindWorlds("gen", "source", () -> List.of(
            new WaitProgress.WorldValue(WORLD, epoch.get(), value.get(), 0L)));

        assertEquals(2L, progress.worldReadings("gen").get(0).delta());
        epoch.set(2);
        value.set(1);
        WaitProgress.WorldReading changed = progress.worldReadings("gen").get(0);
        assertEquals(1L, changed.value());
        assertEquals(1L, changed.delta());
    }

    @Test
    void unbindAndResetRemoveThePerWorldView() {
        WaitProgress progress = new WaitProgress();
        progress.bindWorlds("gen", "source", () -> List.of(
            new WaitProgress.WorldValue(WORLD, 1L, 2L, 1L)));
        assertEquals(1, progress.worldReadings("gen").size());
        progress.reset();
        WaitProgress.WorldReading afterReset = progress.worldReadings("gen").get(0);
        assertEquals(2L, afterReset.delta(), "reset clears previous, not the source binding");
        progress.unbindWorlds("gen");
        assertTrue(progress.worldReadings("gen").isEmpty());
    }
}
