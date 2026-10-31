/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The peek answers from a visible chunk only; cold/missing worlds return UNKNOWN/DEFAULT and record
 * bounded demand, without invoking the live view. */
class P2ACC06PeekBlockTest {

    private static final BlockPos POS = new BlockPos(10, 64, 10);

    @AfterEach
    void cleanup() {
        PeekBlockService.reset();
    }

    @Test
    void loadedChunkReturnsImmutableSnapshot() {
        Object state = new Object();
        PeekBlockService.Result result = PeekBlockService.decide(true, true, () -> state, null,
            "minecraft:overworld", POS);
        assertEquals(PeekBlockService.Status.LOADED, result.status());
        assertSame(state, result.block());
        assertEquals("chunk-now", result.source());
        assertEquals(0L, PeekBlockService.demandCount());
    }

    @Test
    void coldChunkNeverInvokesViewAndReturnsUnknown() {
        AtomicBoolean viewed = new AtomicBoolean();
        PeekBlockService.Result result = PeekBlockService.decide(true, false, () -> {
            viewed.set(true);
            return "stone";
        }, null, "minecraft:overworld", POS);
        assertEquals(PeekBlockService.Status.UNKNOWN, result.status());
        assertFalse(viewed.get());
        assertEquals(1L, PeekBlockService.demandCount());
    }

    @Test
    void noPlayerColdChunkIsUnknownWithoutView() {
        AtomicBoolean viewed = new AtomicBoolean();
        PeekBlockService.Result result = PeekBlockService.decide(true, false, () -> {
            viewed.set(true);
            return "stone";
        }, null, "minecraft:overworld", POS);
        assertEquals(PeekBlockService.Status.UNKNOWN, result.status());
        assertFalse(viewed.get());
    }

    @Test
    void coldChunkWithFallbackReturnsDefault() {
        Object fallback = new Object();
        PeekBlockService.Result result = PeekBlockService.decide(true, false, () -> new Object(),
            fallback, "minecraft:overworld", POS);
        assertEquals(PeekBlockService.Status.DEFAULT, result.status());
        assertSame(fallback, result.block());
    }

    @Test
    void missingWorldIsNoWorld() {
        PeekBlockService.Result result = PeekBlockService.decide(false, false, () -> new Object(),
            null, "minecraft:overworld", POS);
        assertEquals(PeekBlockService.Status.NO_WORLD, result.status());
        assertEquals(1L, PeekBlockService.demandCount());
    }

    @Test
    void worldsAndDemandAreIsolated() {
        PeekBlockService.decide(true, false, () -> new Object(), null, "world-a", POS);
        PeekBlockService.decide(true, false, () -> new Object(), null, "world-b", POS);
        assertEquals(2L, PeekBlockService.demandCount());
        assertTrue(PeekBlockService.demandRows().stream().anyMatch(row -> row.contains("world-a")));
        assertTrue(PeekBlockService.demandRows().stream().anyMatch(row -> row.contains("world-b")));
    }

    @Test
    void repeatedCoordinateAddsToCountAndOverflowIsBounded() {
        for (int index = 0; index < 2; index++) {
            PeekBlockService.decide(true, false, () -> new Object(), null, "world", POS);
        }
        assertEquals(1, PeekBlockService.demandRows().size());
        assertTrue(PeekBlockService.demandRows().get(0).endsWith("=2"));
        PeekBlockService.reset();
        for (int index = 0; index < 1025; index++) {
            PeekBlockService.decide(true, false, () -> new Object(), null, "world",
                new BlockPos(index, 64, index));
        }
        assertEquals(1024, PeekBlockService.demandRows().size());
        assertEquals(1L, PeekBlockService.demandOverflow());
    }
}
