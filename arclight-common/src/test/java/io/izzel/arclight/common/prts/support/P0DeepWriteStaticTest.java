/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old-red contract for the deep write path: the outer CraftBlock wrapper and its platform-data
 * copy are gone; the real Level.setBlock entry carries the lazy supplier and the same 23 seams. */
class P0DeepWriteStaticTest {

    @Test
    void theOuterCraftBlockWrapperAndItsCopyAreGone() {
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("io.izzel.arclight.common.prts.fixes.PrtsCraftBlockWriteTapMixin"));
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("io.izzel.arclight.common.prts.support.PrtsDeferredWrites$PlatformWrite"));
    }

    @Test
    void theRealLevelEntryUsesTheLazySupplier() throws Exception {
        Method method = PrtsWorldWriteTaps.BlockWriteTap.class.getMethod("admitBlockWriteLazy",
            Object.class, String.class, Supplier.class);
        assertNotNull(method);
        InputStream stream = getClass().getClassLoader().getResourceAsStream(
            "io/izzel/arclight/common/prts/fixes/PrtsLevelStructureGrowCaptureMixin.class");
        assertNotNull(stream);
        String bytes;
        try (stream) {
            bytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
        }
        assertTrue(bytes.contains("PrtsWorldWriteTaps"));
        assertTrue(bytes.contains("PrtsDeferredWrites$LevelWrite"));
        assertFalse(bytes.contains("PlatformWrite"));
    }

    @Test
    void theConfigDropsTheOuterTargetAndTheSeamCountStays() throws Exception {
        InputStream stream = getClass().getClassLoader().getResourceAsStream("prts-fixes.mixins.json");
        assertNotNull(stream);
        String config;
        try (stream) {
            config = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertFalse(config.contains("PrtsCraftBlockWriteTapMixin"));
        assertTrue(config.contains("PrtsLevelStructureGrowCaptureMixin"));
        assertEquals(23, PrtsSeams.kernelSeams().size());
        assertTrue(PrtsSeams.kernelSeams().stream()
            .anyMatch(seam -> seam.seamId().equals("write_path_platform")));
    }
}
