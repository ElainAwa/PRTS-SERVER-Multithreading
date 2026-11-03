/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.waitpoints.WaitProgress;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The explicit per-world gen/light readout: a safe world token plus value/delta, only for a world
 * the producer actually carries and only for a positive counter. */
class KernelReadingsWorldSignalTest {

    @Test
    void aPerWorldReadingBecomesASafeValueDeltaPairForEachFamily() {
        List<WaitProgress.WorldReading> readings = List.of(
            new WaitProgress.WorldReading("gen", "minecraft:overworld", 1L, true, "source",
                3L, 0L, 3L, 2L, 0L, 2L),
            new WaitProgress.WorldReading("gen", "minecraft:the_end", 2L, true, "source",
                1L, 0L, 1L, 0L, 0L, 0L));

        List<String> gen = new ArrayList<>();
        KernelReadings.worldGenSignals(gen, readings);
        assertEquals(List.of(
            "wp.signal.gen.minecraft_overworld.value=3",
            "wp.signal.gen.minecraft_overworld.delta=3",
            "wp.signal.gen.minecraft_the_end.value=1",
            "wp.signal.gen.minecraft_the_end.delta=1"), gen);

        List<String> light = new ArrayList<>();
        KernelReadings.worldLightSignals(light, readings);
        assertEquals(List.of(
            "wp.signal.light.minecraft_overworld.value=2",
            "wp.signal.light.minecraft_overworld.delta=2"), light);
    }

    @Test
    void oneReadCarriesBothFamiliesWithTheirRealDeltas() {
        WaitProgress progress = new WaitProgress();
        AtomicInteger sourceReads = new AtomicInteger();
        progress.bindWorlds("gen", "source", () -> {
            sourceReads.incrementAndGet();
            return List.of(new WaitProgress.WorldValue("minecraft:overworld", 1L, 3L, 2L));
        });
        List<String> lines = new ArrayList<>();
        KernelReadings.worldSignals(lines, progress);

        assertEquals(1, sourceReads.get(), "one export must read the per-world source once");
        assertEquals(List.of(
            "wp.signal.gen.minecraft_overworld.value=3",
            "wp.signal.gen.minecraft_overworld.delta=3",
            "wp.signal.light.minecraft_overworld.value=2",
            "wp.signal.light.minecraft_overworld.delta=2"), lines);
    }

    @Test
    void noReadingMeansNoZeroPair() {
        List<String> gen = new ArrayList<>();
        List<String> light = new ArrayList<>();
        KernelReadings.worldGenSignals(gen, List.of());
        KernelReadings.worldLightSignals(light, List.of());
        assertTrue(gen.isEmpty());
        assertTrue(light.isEmpty());
    }

    @Test
    void aLightOnlyWorldPublishesLightButNoZeroGenPair() {
        List<WaitProgress.WorldReading> readings = List.of(
            new WaitProgress.WorldReading("gen", "minecraft:overworld", 1L, true, "source",
                0L, 0L, 0L, 4L, 0L, 4L));

        List<String> gen = new ArrayList<>();
        KernelReadings.worldGenSignals(gen, readings);
        assertTrue(gen.isEmpty());

        List<String> light = new ArrayList<>();
        KernelReadings.worldLightSignals(light, readings);
        assertEquals(List.of(
            "wp.signal.light.minecraft_overworld.value=4",
            "wp.signal.light.minecraft_overworld.delta=4"), light);
    }
}
