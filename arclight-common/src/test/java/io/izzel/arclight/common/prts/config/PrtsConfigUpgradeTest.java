/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.config;

import io.izzel.arclight.common.prts.config.PrtsConfigManager.Entry;
import io.izzel.arclight.common.prts.config.PrtsConfigManager.Upgraded;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The upgrade path of {@link PrtsConfigManager}: a file written by an older build is brought up to
 * the layout of this build without a value in it changing, and a file that already carries the
 * layout is not touched at all.
 *
 * <p>The tests drive the content transform on its own, because the transform is what decides
 * whether a start writes a file: an upgrade that hands back its input is exactly the case that
 * leaves the file on disk, and its modification time, alone.</p>
 */
class PrtsConfigUpgradeTest {

    /** Layout version these expectations are written against; a bump has to update them. */
    private static final String LAYOUT = "2";

    /** A comment block of the shape an older build wrote, which no current file carries. */
    private static final String OLD_HEADER = """
        # PRTS configuration. Generated on first start; an existing file is never overwritten.
        # This block was written by an older build and says something this build no longer writes.
        """;

    @Test
    void aGeneratedFileNeedsNoUpgrade() {
        PrtsConfigManager.entries().forEach((category, entry) -> {
            String generated = PrtsConfigManager.defaults(entry);
            Upgraded upgraded = PrtsConfigManager.upgrade(entry, generated);
            assertNull(upgraded.detail(), category);
            assertEquals(generated, upgraded.content(), category);
        });
    }

    @Test
    void anOlderFileLosesItsHeaderAndGainsTheMissingKey() {
        Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.MODSUPPORT);
        String stale = OLD_HEADER + """
            version: 1
            enabled: true
            features:
              preload-bungee-chat-classes: false
            """;

        Upgraded upgraded = PrtsConfigManager.upgrade(entry, stale);

        assertEquals("version 1 -> " + LAYOUT + ", added 1 key, refreshed header", upgraded.detail());
        assertFalse(upgraded.content().contains("older build"), "the old comment block is gone");
        assertTrue(upgraded.content().contains("version: " + LAYOUT), "the version is written back");
        assertTrue(upgraded.content().contains("disable-bukkit-reload-command: false"),
            "the missing key is appended with the default of this build");
        assertTrue(upgraded.content().contains("preload-bungee-chat-classes: false"),
            "the value the file carried is still there");
    }

    @Test
    void aValueTheOperatorChangedIsCarriedOverUnchanged() {
        Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.MODSUPPORT);
        String stale = OLD_HEADER + """
            version: 1
            enabled: false
            features:
              preload-bungee-chat-classes: false
            """;

        Upgraded upgraded = PrtsConfigManager.upgrade(entry, stale);

        // every value line of the file is still there, in place and unchanged: what this build adds
        // is appended behind them
        List<String> before = valueLines(stale);
        List<String> after = valueLines(upgraded.content());
        assertEquals(before, after.subList(0, before.size()));
    }

    @Test
    void aFileWithoutKeysGainsBothGroups() {
        Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.MODSUPPORT);
        String stale = OLD_HEADER + """
            version: 1
            """;

        Upgraded upgraded = PrtsConfigManager.upgrade(entry, stale);

        assertEquals("version 1 -> " + LAYOUT + ", added 3 keys, refreshed header", upgraded.detail());
        assertTrue(upgraded.content().contains("enabled: true"), "the category default is written");
        assertTrue(upgraded.content().contains("features:"), "the group header is written");
        assertTrue(upgraded.content().contains("preload-bungee-chat-classes: true"));
        assertTrue(upgraded.content().contains("disable-bukkit-reload-command: false"));
    }

    @Test
    void anEmptyFeatureBlockGainsTheKeysAsABlock() {
        Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.MODSUPPORT);
        String stale = OLD_HEADER + """
            version: 1
            enabled: true
            features: {}
            """;

        Upgraded upgraded = PrtsConfigManager.upgrade(entry, stale);

        assertEquals("version 1 -> " + LAYOUT + ", added 2 keys, refreshed header", upgraded.detail());
        assertFalse(upgraded.content().contains("features: {}"), "a flow mapping became a block");
        assertTrue(upgraded.content().contains("preload-bungee-chat-classes: true"));
        assertTrue(upgraded.content().contains("disable-bukkit-reload-command: false"));
    }

    @Test
    void aBlankLineInsideTheFeatureBlockDoesNotSplitIt() {
        Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.MODSUPPORT);
        String stale = OLD_HEADER + """
            version: 1
            enabled: true
            features:
              preload-bungee-chat-classes: true
            """ + "\n";

        Upgraded upgraded = PrtsConfigManager.upgrade(entry, stale);

        assertTrue(upgraded.content().contains("preload-bungee-chat-classes: true\n  # added in v"),
            "the appended key sits behind the entries that are there: " + upgraded.content());
        assertTrue(upgraded.content().endsWith("disable-bukkit-reload-command: false\n\n"),
            "the blank line stays behind the block");
    }

    @Test
    void aFileOfANewerBuildIsLeftAlone() {
        Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.FIXES);
        String newer = OLD_HEADER + """
            version: 9
            enabled: true
            features: {}
            """;

        Upgraded upgraded = PrtsConfigManager.upgrade(entry, newer);

        assertNull(upgraded.detail());
        assertEquals(newer, upgraded.content());
    }

    /**
     * Returns the lines that carry a value, without the layout version this build owns.
     *
     * @param content a configuration file
     * @return the value lines, in file order
     */
    private static List<String> valueLines(String content) {
        return content.lines()
            .filter(line -> line.contains(":") && !line.trim().startsWith("#"))
            .filter(line -> !line.trim().startsWith("version:"))
            .toList();
    }
}
