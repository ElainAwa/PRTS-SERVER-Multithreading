/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.TickingBlockEntity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Read-only block-entity attribution: one bounded row per tick, cpu -1 preserved, segment used only
 * for reconciliation, worlds isolated. */
class P2ACC30BlockEntityAttributionTest {

    private static final class FakeTicker implements TickingBlockEntity {
        private final BlockPos pos;
        private final String type;
        private boolean removed;

        FakeTicker(BlockPos pos, String type, boolean removed) {
            this.pos = pos;
            this.type = type;
            this.removed = removed;
        }

        @Override
        public void tick() {
        }

        @Override
        public boolean isRemoved() {
            return removed;
        }

        @Override
        public BlockPos getPos() {
            return pos;
        }

        @Override
        public String getType() {
            return type;
        }
    }

    @AfterEach
    void cleanup() {
        BlockEntityAttribution.reset();
        System.clearProperty(BlockEntityAttribution.PROPERTY);
    }

    @Test
    void disabledByDefault() {
        System.clearProperty(BlockEntityAttribution.PROPERTY);
        BlockEntityAttribution.noteTick(new FakeTicker(BlockPos.ZERO, "minecraft:chest", false),
            new Object(), "minecraft:overworld", 10L, -1L);
        assertEquals(0L, BlockEntityAttribution.rowsSeen());
    }

    @Test
    void oneRowWithFields() {
        System.setProperty(BlockEntityAttribution.PROPERTY, "true");
        BlockPos pos = new BlockPos(33, 64, -49);
        BlockEntityAttribution.noteTick(new FakeTicker(pos, "minecraft:furnace", true),
            new Object(), "minecraft:overworld", 123L, -1L);
        assertEquals(1L, BlockEntityAttribution.rowsSeen());
        BlockEntityAttribution.Row row = BlockEntityAttribution.rows().get(0);
        assertEquals("minecraft:overworld", row.worldId());
        assertEquals(pos, row.pos());
        assertEquals(2, row.chunkX());
        assertEquals(-4, row.chunkZ());
        assertEquals("minecraft:furnace", row.type());
        assertTrue(row.removed());
        assertEquals(123L, row.wallNanos());
        assertEquals(-1L, row.cpuNanos());
    }

    @Test
    void segmentReconcilesRowsButIsNotAddedToThem() {
        System.setProperty(BlockEntityAttribution.PROPERTY, "true");
        BlockEntityAttribution.noteTick(new FakeTicker(BlockPos.ZERO, "minecraft:chest", false),
            new Object(), "minecraft:overworld", 100L, -1L);
        BlockEntityAttribution.noteSegment("minecraft:overworld", 150L);
        assertEquals(100L, BlockEntityAttribution.perWorldWall("minecraft:overworld"));
        assertEquals(150L, BlockEntityAttribution.segmentWall("minecraft:overworld"));
        assertTrue(BlockEntityAttribution.reconciled("minecraft:overworld"));
        BlockEntityAttribution.noteTick(new FakeTicker(BlockPos.ZERO, "minecraft:chest", false),
            new Object(), "minecraft:overworld", 3_000_000L, -1L);
        BlockEntityAttribution.noteSegment("minecraft:overworld", 0L);
        assertFalse(BlockEntityAttribution.reconciled("minecraft:overworld"));
    }

    @Test
    void worldsStayIsolated() {
        System.setProperty(BlockEntityAttribution.PROPERTY, "true");
        BlockEntityAttribution.noteTick(new FakeTicker(BlockPos.ZERO, "minecraft:chest", false),
            new Object(), "minecraft:overworld", 10L, -1L);
        BlockEntityAttribution.noteTick(new FakeTicker(BlockPos.ZERO, "minecraft:chest", false),
            new Object(), "minecraft:the_nether", 20L, -1L);
        assertEquals(10L, BlockEntityAttribution.perWorldWall("minecraft:overworld"));
        assertEquals(20L, BlockEntityAttribution.perWorldWall("minecraft:the_nether"));
    }

    @Test
    void nonTickerAndOverflowAreBounded() {
        System.setProperty(BlockEntityAttribution.PROPERTY, "true");
        BlockEntityAttribution.noteTick(new Object(), new Object(), "minecraft:overworld", 1L, -1L);
        assertEquals(0L, BlockEntityAttribution.rowsSeen());
        FakeTicker ticker = new FakeTicker(BlockPos.ZERO, "minecraft:chest", false);
        for (int index = 0; index < 8193; index++) {
            BlockEntityAttribution.noteTick(ticker, new Object(), "minecraft:overworld", 1L, -1L);
        }
        assertEquals(8192, BlockEntityAttribution.rows().size());
        assertEquals(1L, BlockEntityAttribution.overflow());
    }
}
