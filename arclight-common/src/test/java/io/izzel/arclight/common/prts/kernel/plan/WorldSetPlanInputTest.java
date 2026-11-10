/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.plan;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldSetPlanInputTest {

    @Test
    void snapshotIsSortedImmutableAndGenerationAware() {
        List<String> source = new ArrayList<>(List.of("b", "a"));
        WorldSetPlanInput input = WorldSetPlanInput.capture(source, 4L);
        source.add("c");
        assertEquals(List.of("a", "b"), input.worldIds());
        assertThrows(UnsupportedOperationException.class, () -> input.worldIds().add("z"));
        assertTrue(new WorldSetPlanInput(List.of("b", "a"), 4L).sameWorlds(input));
        assertFalse(new WorldSetPlanInput(List.of("a"), 4L).sameWorlds(input));
        assertTrue(new WorldSetPlanInput(List.of("a", "b"), 5L).invalidates(input));
        assertEquals(5L, input.nextGeneration().generation());
    }
}
