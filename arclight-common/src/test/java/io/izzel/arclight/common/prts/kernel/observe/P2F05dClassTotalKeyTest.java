/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.KernelModule;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The class total has its own key, so the official ACC-36 class-prefix sum cannot include the
 * total a second time; the per-class rows stay under the prefix. */
class P2F05dClassTotalKeyTest {

    @BeforeEach
    void enable() {
        System.setProperty("arclight.prts.kernel", "true");
    }

    @AfterEach
    void clear() {
        System.clearProperty("arclight.prts.kernel");
    }

    @Test
    void theClassTotalHasItsOwnKeyAndTheClassRowsKeepThePrefix() {
        List<String> lines = KernelReadings.export(KernelModule.instance());

        assertTrue(has(lines, "budget.class_overrun_total"));
        assertFalse(has(lines, "budget.over_class_total"));
        for (ShareClass shareClass : ShareClass.values()) {
            assertTrue(has(lines, "budget.over_class_" + shareClass.key()));
        }
        assertTrue(has(lines, "budget.world_overrun_total"));
    }

    private static boolean has(List<String> lines, String key) {
        for (String line : lines) {
            if (line.startsWith(key + "=")) {
                return true;
            }
        }
        return false;
    }
}
