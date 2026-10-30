/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityQueryReadings;
import io.izzel.arclight.common.prts.kernel.observe.KernelReadings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The module's own reset path must clear the ACC-09 rows; a test that resets the static counters
 * directly would not prove the path. */
class P2F05bQueryResetTest {

    private KernelModule module;

    @BeforeEach
    void open() {
        System.setProperty("arclight.prts.kernel", "true");
        module = KernelModule.instance();
        module.rollbackCommitChain();
        module.resetReadings();
    }

    @AfterEach
    void close() {
        module.rollbackCommitChain();
        System.clearProperty("arclight.prts.kernel");
    }

    @Test
    void theModuleResetClearsBothAcc09Rows() {
        EntityQueryReadings.noteQuery();
        EntityQueryReadings.noteQuery();
        EntityQueryReadings.noteQuery();
        EntityQueryReadings.noteEntityCount(7L);
        assertEquals("3", line(KernelReadings.export(module), "query.calls"));
        assertEquals("7", line(KernelReadings.export(module), "entity.count"));

        module.resetReadings();
        List<String> after = KernelReadings.export(module);
        assertEquals("0", line(after, "query.calls"),
            "the module reset did not reach the query counter");
        assertEquals("0", line(after, "entity.count"),
            "the module reset did not reach the current entity count");
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
