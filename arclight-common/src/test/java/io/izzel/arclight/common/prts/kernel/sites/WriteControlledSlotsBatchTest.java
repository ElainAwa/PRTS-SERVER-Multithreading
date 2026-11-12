/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteLevel;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;
import net.minecraft.core.SectionPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WriteControlledSlotsBatchTest {

    private static final String WORLD = "w";

    @Test
    void aBatchSeatsAllRangesOrNone() {
        WriteControlledSlots table = table();
        WriteControlledSlots.Slot parent = slot("main", 1L, WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.CHUNK, 0L));
        WriteControlledSlots.Slot child = slot("site:b", 1L, WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.SECTION, SectionPos.asLong(0, 0, 0)));

        assertNotEquals(WriteControlledSlots.Refusal.NONE, table.registerAll(List.of(parent, child)));
        assertEquals(0, table.slots(), "a refused batch leaves no range seated");
    }

    @Test
    void aBatchWithNoConflictSeatsEveryRange() {
        WriteControlledSlots table = table();
        WriteControlledSlots.Slot first = slot("main", 1L, WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.CHUNK, 0L));
        WriteControlledSlots.Slot second = slot("site:b", 1L, WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.CHUNK, 4L));

        assertEquals(WriteControlledSlots.Refusal.NONE, table.registerAll(List.of(first, second)));
        assertEquals(2, table.slots());
    }

    @Test
    void aRefusedBatchRestoresTheSeatItWouldHaveReplaced() {
        WriteControlledSlots table = table();
        WriteControlledSlots.Slot first = slot("main", 1L, WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.CHUNK, 0L));
        assertEquals(WriteControlledSlots.Refusal.NONE, table.register(first));
        WriteControlledSlots.SlotHandle handle = table.seated(first.key());
        assertNotNull(handle);

        WriteControlledSlots.Slot replacement = slot("main", 2L, WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.CHUNK, 0L));
        WriteControlledSlots.Slot conflicting = slot("site:b", 1L, WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.SECTION, SectionPos.asLong(0, 0, 0)));
        assertNotEquals(WriteControlledSlots.Refusal.NONE,
            table.registerAll(List.of(replacement, conflicting)));

        assertTrue(table.holds(handle), "the refused batch must leave the older seat untouched");
        assertEquals(1, table.slots());
    }

    @Test
    void aTransferNeedsAQuiescentGenerationAndInvalidatesTheOldHandle() {
        WriteControlledSlots table = table();
        WriteControlledSlots.WriteSpace space = WriteControlledSlots.WriteSpace.of(
            WriteControlledSlots.CellKind.CHUNK, 0L);
        WriteControlledSlots.Slot first = slot("main", 1L, space);
        assertEquals(WriteControlledSlots.Refusal.NONE, table.register(first));
        WriteControlledSlots.SlotHandle handle = table.seated(first.key());

        WriteControlledSlots.Slot second = slot("site:b", 2L, space);
        table.beginUse(first.key());
        assertFalse(table.quiescent(first.key()));
        assertEquals(WriteControlledSlots.Refusal.NOT_QUIESCENT, table.register(second),
            "a captured write keeps the seated generation in place");
        assertTrue(table.holds(handle));

        table.endUse(first.key());
        assertTrue(table.quiescent(first.key()));
        assertEquals(WriteControlledSlots.Refusal.NONE, table.register(second));
        assertFalse(table.holds(handle), "the old handle names a generation that was replaced");
        assertTrue(table.holds(table.seated(second.key())));
    }

    private static WriteControlledSlots table() {
        return new WriteControlledSlots(new OwnerRegistry(), new WriteVersionSlots(),
            new WorldEpochs(), WriteControlledSlots.Source.NONE);
    }

    private static WriteControlledSlots.Slot slot(String site, long generation,
                                                  WriteControlledSlots.WriteSpace space) {
        WriteControlledSlots.SlotKey key = new WriteControlledSlots.SlotKey(WORLD,
            WritePath.BLOCK_WRITE.key(), WriteLevel.REGION,
            WriteControlledSlots.SlotKey.segmentOf(space), 1L);
        return new WriteControlledSlots.Slot(key, new Object(), "node", 0, 7L, generation, 1L,
            100L, HolderKind.REGISTERED, site, Set.of(WritePath.BLOCK_WRITE.key()), space);
    }
}
