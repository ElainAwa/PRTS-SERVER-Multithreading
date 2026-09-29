/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.optional.servercore;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the property the layer was reworked for: it drives the journal from platform events and
 * occupies no mixin anchor, so the tick loop, the world lifecycle and the shutdown path can be
 * rewritten without touching it. A mixin handler here, or a mixin configuration applying one,
 * would quietly put the layer back onto one of those seams.
 */
class OptionalLayerSeamTest {

    @Test
    void noClassOfTheLayerIsAMixin() throws IOException {
        Path root = Path.of("src", "main", "java", "io", "izzel", "arclight", "common", "prts",
            "optional");
        assertTrue(Files.isDirectory(root), "the layer's source tree is where this test expects it: " + root);
        try (Stream<Path> files = Files.walk(root)) {
            List<Path> handlers = files
                .filter(path -> path.toString().endsWith(".java"))
                .filter(OptionalLayerSeamTest::declaresMixin)
                .toList();
            assertTrue(handlers.isEmpty(),
                "the optional layer stays off the mixin seams, but these declare one: " + handlers);
        }
    }

    @Test
    void noMixinConfigurationIsShippedForTheLayer() {
        assertNull(getClass().getClassLoader().getResource("prts-optional-servercore.mixins.json"),
            "the optional layer declares no mixin configuration");
    }

    private static boolean declaresMixin(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains("@Mixin(");
        } catch (IOException failure) {
            throw new IllegalStateException("cannot read " + path, failure);
        }
    }
}
