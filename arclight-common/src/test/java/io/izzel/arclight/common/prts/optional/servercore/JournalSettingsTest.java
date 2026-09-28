/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.optional.servercore;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.optional.journal.JournalSettings;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ties the journal to the configuration layer: the keys the journal reads must be the keys the
 * layer declares, and the declared defaults are the documented ones. The two sides only meet at
 * these names, so a rename on either side has to fail here instead of turning into a journal that
 * runs on an undeclared default.
 */
class JournalSettingsTest {

    @Test
    void theJournalReadsDeclaredKeys() {
        Map<String, Boolean> features = declaredFeatures();
        assertTrue(features.containsKey(JournalSettings.KEY_RELIABLE_CHUNK_SAVE),
            "the journal switch is declared: " + features.keySet());
        assertEquals(Boolean.FALSE, features.get(JournalSettings.KEY_RELIABLE_CHUNK_SAVE),
            "the journal ships off");

        Map<String, PrtsConfigManager.IntSetting> numbers = declaredNumbers();
        assertTrue(numbers.containsKey(JournalSettings.KEY_INTERVAL_SECONDS),
            "the interval is declared: " + numbers.keySet());
        assertEquals(30, numbers.get(JournalSettings.KEY_INTERVAL_SECONDS).defaultValue());
        assertTrue(numbers.containsKey(JournalSettings.KEY_CHUNKS_PER_TICK),
            "the per-tick budget is declared: " + numbers.keySet());
        assertEquals(50, numbers.get(JournalSettings.KEY_CHUNKS_PER_TICK).defaultValue());
    }

    @Test
    void theCategoryShipsDisabled() {
        assertEquals(Boolean.FALSE,
            PrtsConfigManager.entries().get(JournalSettings.CATEGORY).defaultEnabled(),
            "the optional layer is opt-in");
    }

    @Test
    void anUnreadConfigurationFallsBackToTheDeclaredDefaults() {
        // no configuration directory is read in a unit test, so these are the declared defaults
        assertFalse(JournalSettings.reliableChunkSave(), "the journal is off until a file turns it on");
        assertEquals(30, JournalSettings.intervalSeconds());
        assertEquals(50, JournalSettings.chunksPerTick());
    }

    private static Map<String, Boolean> declaredFeatures() {
        return PrtsConfigManager.entries().get(JournalSettings.CATEGORY).features();
    }

    private static Map<String, PrtsConfigManager.IntSetting> declaredNumbers() {
        return PrtsConfigManager.entries().get(JournalSettings.CATEGORY).numbers();
    }
}
