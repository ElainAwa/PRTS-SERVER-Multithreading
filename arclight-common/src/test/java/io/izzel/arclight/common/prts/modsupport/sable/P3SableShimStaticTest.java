/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.sable;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Old-red/new-green for the Sable physics shim: both whole methods are wrapped with
 * MixinExtras, not decorated at a callsite, and the native lock is balanced on exception. */
class P3SableShimStaticTest {

    @Test
    void bothPhysicsStepsUseWrapMethodWithABalancedLock() throws Exception {
        String source;
        try (InputStream stream = getClass().getClassLoader().getResourceAsStream(
            "io/izzel/arclight/common/prts/modsupport/sable/PrtsSablePhysicsPipelineMixin.java")) {
            if (stream != null) {
                source = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            } else {
                source = java.nio.file.Files.readString(java.nio.file.Path.of(
                    "src/main/java/io/izzel/arclight/common/prts/modsupport/sable/"
                        + "PrtsSablePhysicsPipelineMixin.java"));
            }
        }
        assertTrue(source.contains("@WrapMethod(method = \"prePhysicsTicks\""));
        assertTrue(source.contains("@WrapMethod(method = \"physicsTick\""));
        assertTrue(source.contains("Operation<Void> original"));
        assertTrue(source.contains("PrtsSableLocks.NativeCalls.lock()"));
        assertTrue(source.contains("PrtsSableLocks.NativeCalls.unlock()"));
        assertTrue(source.contains("PrtsSableLocks.NativeCalls.noteSerializedCall()"));
        assertTrue(source.contains("} finally {"));
        assertFalse(source.contains("@Decorate"), "a callsite decoration cannot wrap both entries");
    }
}
