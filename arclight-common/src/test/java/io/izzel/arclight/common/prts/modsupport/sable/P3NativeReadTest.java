/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.sable;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old-red/new-green for the native readout and the real Sable callback signature. */
class P3NativeReadTest {

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path));
    }

    @Test
    void onlyTheRealSubstepsCapIsPublishedAndBacklogIsNot() throws Exception {
        String readings = read("src/main/java/io/izzel/arclight/common/prts/kernel/observe/KernelReadings.java");
        assertTrue(readings.contains("native.substeps_cap"));
        assertTrue(readings.contains("native.callback_count"));
        assertFalse(readings.contains("native.backlog_cap"),
            "no real native backlog hard bound exists, so the key is not published");
        String helper = read("src/main/java/io/izzel/arclight/common/prts/support/PrtsNativeReadings.java");
        assertTrue(helper.contains("substepsPerTick"));
        assertFalse(helper.contains("DEFAULT_RESIDENT_CAPACITY"));
    }

    @Test
    void theCallbackMixinsUseReturnableAndTheRealTargetReturnsACollisionResult() throws Exception {
        for (String name : new String[]{"PrtsSableBeltCallbackMixin.java",
            "PrtsSableFragileCallbackMixin.java"}) {
            String source = read("src/main/java/io/izzel/arclight/common/prts/modsupport/sable/" + name);
            assertTrue(source.contains("CallbackInfoReturnable<Object>"), name);
            assertFalse(source.contains("CallbackInfo ci"), name);
        }
        Path jar = Path.of("/home/elysia/project/927-p3-native/mods/sable-neoforge-1.21.1-2.0.3.jar");
        if (Files.exists(jar)) {
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                for (String entry : new String[]{
                    "dev/ryanhcode/sable/neoforge/physics/callback/BeltBlockCallback.class",
                    "dev/ryanhcode/sable/physics/callback/FragileBlockCallback.class"}) {
                    ZipEntry item = zip.getEntry(entry);
                    assertTrue(item != null, entry);
                    String bytes = new String(zip.getInputStream(item).readAllBytes(),
                        StandardCharsets.ISO_8859_1);
                    assertTrue(bytes.contains("sable$onCollision"), entry);
                    assertTrue(bytes.contains("CollisionResult;"), entry);
                }
            }
        }
    }
}
