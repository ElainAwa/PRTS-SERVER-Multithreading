/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.sable;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old-red/new-green for the three formal native readout keys and the covered callback bases. */
class P3NativeReadTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void theThreeKeysComeFromTheRealSableSurface() throws Exception {
        String readings = read("src/main/java/io/izzel/arclight/common/prts/kernel/observe/KernelReadings.java");
        assertTrue(readings.contains("native.substeps_cap"));
        assertTrue(readings.contains("native.backlog_cap"));
        assertTrue(readings.contains("native.callback_count"));
        String helper = read("src/main/java/io/izzel/arclight/common/prts/support/PrtsNativeReadings.java");
        assertTrue(helper.contains("SubLevelPhysicsSystem"));
        assertTrue(helper.contains("getConfig"));
        assertTrue(helper.contains("substepsPerTick"));
        assertTrue(helper.contains("DEFAULT_RESIDENT_CAPACITY"));
        assertFalse(helper.contains("return new Snapshot(0,"),
            "the caps are not fabricated zeroes");
    }

    @Test
    void theCoveredCallbackBasesAreWrapped() throws Exception {
        String belt = read("src/main/java/io/izzel/arclight/common/prts/modsupport/sable/"
            + "PrtsSableBeltCallbackMixin.java");
        String fragile = read("src/main/java/io/izzel/arclight/common/prts/modsupport/sable/"
            + "PrtsSableFragileCallbackMixin.java");
        assertTrue(belt.contains("BeltBlockCallback"));
        assertTrue(fragile.contains("FragileBlockCallback"));
        assertTrue(belt.contains("PrtsNativeReadings.noteCallback()"));
        assertTrue(fragile.contains("PrtsNativeReadings.noteCallback()"));
    }
}
