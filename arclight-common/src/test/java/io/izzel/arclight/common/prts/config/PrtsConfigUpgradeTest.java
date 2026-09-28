/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.config;

import io.izzel.arclight.common.prts.config.PrtsConfigManager.Entry;
import io.izzel.arclight.common.prts.config.PrtsConfigManager.Upgraded;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    /**
     * The features this build declares for the category under test, in the order it appends them.
     *
     * <p>The expectations below are derived from this declaration instead of naming the keys one by
     * one: a feature added to the build changes what an older file is missing, and an expectation
     * that spells out the count of a former build is a statement about that build, not about this
     * one.</p>
     */
    private static final Map<String, Boolean> FEATURES =
        PrtsConfigManager.entries().get(PrtsConfigManager.MODSUPPORT).features();

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
        Map<String, Boolean> appended = appended(stale);

        assertEquals("version 1 -> " + LAYOUT + ", " + addedKeys(appended.size()) + ", refreshed header",
            upgraded.detail());
        assertFalse(upgraded.content().contains("older build"), "the old comment block is gone");
        assertTrue(upgraded.content().contains("version: " + LAYOUT), "the version is written back");
        appended.forEach((name, value) -> assertTrue(
            upgraded.content().contains("  " + name + ": " + value),
            name + " is appended with the default of this build"));
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
        Map<String, Boolean> appended = appended(stale);

        // the version line is rewritten rather than added, and the group header of the features block
        // is not a key: what is added is the enabled key plus one entry per declared feature
        assertEquals("version 1 -> " + LAYOUT + ", " + addedKeys(1 + appended.size()) + ", refreshed header",
            upgraded.detail());
        assertTrue(upgraded.content().contains("enabled: true"), "the category default is written");
        assertTrue(upgraded.content().contains("features:"), "the group header is written");
        appended.forEach((name, value) -> assertTrue(
            upgraded.content().contains("  " + name + ": " + value),
            name + " is appended with the default of this build"));
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
        Map<String, Boolean> appended = appended(stale);

        assertEquals("version 1 -> " + LAYOUT + ", " + addedKeys(appended.size()) + ", refreshed header",
            upgraded.detail());
        assertFalse(upgraded.content().contains("features: {}"), "a flow mapping became a block");
        appended.forEach((name, value) -> assertTrue(
            upgraded.content().contains("  " + name + ": " + value),
            name + " is appended with the default of this build"));
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
        Map.Entry<String, Boolean> last = lastOf(appended(stale));
        assertTrue(upgraded.content().endsWith("  " + last.getKey() + ": " + last.getValue() + "\n\n"),
            "the blank line stays behind the block");
    }

    @Test
    void anOlderJournalFileGainsItsNumberKeys() {
        Entry entry = PrtsConfigManager.entries().get(PrtsConfigManager.OPTIONAL_SERVERCORE);
        String stale = OLD_HEADER + """
            version: 1
            enabled: false
            features: {}
            """;

        Upgraded upgraded = PrtsConfigManager.upgrade(entry, stale);

        assertFalse(upgraded.content().contains("features: {}"), "a flow mapping became a block");
        entry.features().forEach((name, value) -> assertTrue(
            upgraded.content().contains("  " + name + ": " + value),
            name + " is appended with the default of this build"));
        entry.numbers().forEach((name, setting) -> assertTrue(
            upgraded.content().contains("  " + name + ": " + setting.defaultValue()),
            name + " is appended with the default of this build"));
    }

    @Test
    void aDeclaredRangeRejectsADefaultOutsideIt() {
        assertThrows(IllegalArgumentException.class, () -> new PrtsConfigManager.IntSetting(0, 5, 10));
        assertThrows(IllegalArgumentException.class, () -> new PrtsConfigManager.IntSetting(5, 10, 5));
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

    /** Returns the wording the detail of an upgrade uses for a number of appended keys. */
    private static String addedKeys(int count) {
        return "added " + count + (count == 1 ? " key" : " keys");
    }

    /**
     * Returns the features this build declares and a fixture does not mention, in the order they are
     * appended -- the same rule the transform follows, so a feature this build adds is expected here
     * without an edit.
     *
     * @param fixture a configuration file content
     * @return feature name to default, in file order; empty when the fixture carries every feature
     */
    private static Map<String, Boolean> appended(String fixture) {
        Map<String, Boolean> appended = new LinkedHashMap<>(FEATURES);
        appended.keySet().removeIf(name -> fixture.contains("\n  " + name + ":"));
        return appended;
    }

    /**
     * Returns the entry appended last, which is the line in front of the trailing blank line.
     *
     * @param features the appended features, in file order
     * @return the last entry
     */
    private static Map.Entry<String, Boolean> lastOf(Map<String, Boolean> features) {
        List<Map.Entry<String, Boolean>> ordered = new ArrayList<>(features.entrySet());
        return ordered.get(ordered.size() - 1);
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
