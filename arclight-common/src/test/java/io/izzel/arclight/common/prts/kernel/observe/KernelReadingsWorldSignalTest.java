/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.waitpoints.WaitProgress;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The explicit per-world gen readout: a safe world token plus value/delta, only for a world the
 * producer actually carries. */
class KernelReadingsWorldSignalTest {

    @Test
    void aPerWorldReadingBecomesOneSafeValueDeltaPair() {
        List<String> lines = new ArrayList<>();
        KernelReadings.worldGenSignals(lines, List.of(
            new WaitProgress.WorldReading("gen", "minecraft:overworld", 1L, true, "source",
                3L, 0L, 3L, 2L, 0L, 2L),
            new WaitProgress.WorldReading("gen", "minecraft:the_end", 2L, true, "source",
                1L, 0L, 1L, 0L, 0L, 0L)));

        assertEquals(List.of(
            "wp.signal.gen.minecraft_overworld.value=3",
            "wp.signal.gen.minecraft_overworld.delta=3",
            "wp.signal.gen.minecraft_the_end.value=1",
            "wp.signal.gen.minecraft_the_end.delta=1"), lines);
    }

    @Test
    void noReadingMeansNoZeroPair() {
        List<String> lines = new ArrayList<>();
        KernelReadings.worldGenSignals(lines, List.of());
        assertTrue(lines.isEmpty());
    }

    @Test
    void aLightOnlyWorldDoesNotPublishAZeroGenPair() {
        List<String> lines = new ArrayList<>();
        KernelReadings.worldGenSignals(lines, List.of(
            new WaitProgress.WorldReading("gen", "minecraft:overworld", 1L, true, "source",
                0L, 0L, 0L, 4L, 0L, 4L)));
        assertTrue(lines.isEmpty());
    }
}
