/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.optional.servercore;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;

/**
 * The settings keys of the optional recovery journal, declared in the configuration layer; this class
 * only names them, so enabling the layer and enabling the journal stay two separate decisions. A key
 * whose declaration is gone makes the read throw, deliberately.
 */
public final class JournalSettings {

    public static final String CATEGORY = PrtsConfigManager.OPTIONAL_SERVERCORE;

    public static final String KEY_RELIABLE_CHUNK_SAVE = "reliable-chunk-save";

    public static final String KEY_INTERVAL_SECONDS = "journal-interval-seconds";

    public static final String KEY_CHUNKS_PER_TICK = "journal-chunks-per-tick";

    private JournalSettings() {
    }

    /** @return true when dirty chunks are journaled and replayed after an unclean exit */
    public static boolean reliableChunkSave() {
        return PrtsConfigManager.feature(CATEGORY, KEY_RELIABLE_CHUNK_SAVE);
    }

    /** @return the configured cycle length in seconds, already clamped to the declared range */
    public static int intervalSeconds() {
        return PrtsConfigManager.number(CATEGORY, KEY_INTERVAL_SECONDS);
    }

    /** @return the configured chunk budget per tick, already clamped to the declared range */
    public static int chunksPerTick() {
        return PrtsConfigManager.number(CATEGORY, KEY_CHUNKS_PER_TICK);
    }
}
