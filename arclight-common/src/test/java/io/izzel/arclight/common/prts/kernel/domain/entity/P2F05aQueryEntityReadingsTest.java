/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The two ACC-09 rows read real counters: query calls are cumulative, the entity count is the
 * current snapshot and is replaced, never accumulated. The readout itself does not count. */
class P2F05aQueryEntityReadingsTest {

    @BeforeEach
    void enable() {
        System.setProperty("arclight.prts.kernel", "true");
        EntityQueryReadings.reset();
    }

    @AfterEach
    void clear() {
        EntityQueryReadings.reset();
        System.clearProperty("arclight.prts.kernel");
    }

    @Test
    void bothRowsStartAtZeroAndReadTheirOwnCounters() {
        List<String> zero = KernelReadings.export(KernelModule.instance());
        assertEquals("0", line(zero, "query.calls"));
        assertEquals("0", line(zero, "entity.count"));

        EntityQueryReadings.noteQuery();
        EntityQueryReadings.noteQuery();
        EntityQueryReadings.noteQuery();
        EntityQueryReadings.noteEntityCount(5L);
        List<String> after = KernelReadings.export(KernelModule.instance());
        assertEquals("3", line(after, "query.calls"));
        assertEquals("5", line(after, "entity.count"));

        List<String> readAgain = KernelReadings.export(KernelModule.instance());
        assertEquals("3", line(readAgain, "query.calls"), "reading the readout must not count a query");
        assertEquals("5", line(readAgain, "entity.count"));
    }

    @Test
    void theEntityCountIsReplacedNotAddedAndResetClearsBoth() {
        EntityQueryReadings.noteEntityCount(8L);
        assertEquals(8L, EntityQueryReadings.entityCount());
        EntityQueryReadings.noteEntityCount(2L);
        assertEquals(2L, EntityQueryReadings.entityCount());
        EntityQueryReadings.noteEntityCount(-1L);
        assertEquals(0L, EntityQueryReadings.entityCount());

        EntityQueryReadings.noteQuery();
        assertEquals(1L, EntityQueryReadings.queryCalls());
        EntityQueryReadings.reset();
        assertEquals(0L, EntityQueryReadings.queryCalls());
        assertEquals(0L, EntityQueryReadings.entityCount());
    }

    private static String line(List<String> lines, String key) {
        for (String line : lines) {
            if (line.startsWith(key + "=")) {
                return line.substring(key.length() + 1);
            }
        }
        return null;
    }
}
