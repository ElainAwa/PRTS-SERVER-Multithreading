/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from the FeudalKings fork of Arclight, commit a61faa4d02249a07226b7e3fc6b70525a32c9921
 * ("fix(remapper): key the plugin class cache by the version").
 * See THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.support;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class PrtsVersionTest {

    @Test
    void prefersTheVersionPublishedAtStartup() {
        String previous = System.getProperty("arclight.version");
        try {
            System.setProperty("arclight.version", "PRTS-test-1");
            assertEquals("PRTS-test-1", PrtsVersion.version());
        } finally {
            restore(previous);
        }
    }

    @Test
    void stillReportsAVersionWhenNothingWasPublished() {
        String previous = System.getProperty("arclight.version");
        try {
            System.clearProperty("arclight.version");
            assertFalse(PrtsVersion.version().isBlank());
        } finally {
            restore(previous);
        }
    }

    private static void restore(String previous) {
        if (previous == null) {
            System.clearProperty("arclight.version");
        } else {
            System.setProperty("arclight.version", previous);
        }
    }
}
