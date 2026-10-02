/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.optional.servercore;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;

/** The settings keys of the optional recovery journal: declaring them in the configuration layer
 * keeps the layer and the journal separate decisions; a key with no declaration makes the read throw. */
public final class JournalSettings {

    public static final String CATEGORY = PrtsConfigManager.OPTIONAL_SERVERCORE;

    public static final String KEY_RELIABLE_CHUNK_SAVE = "reliable-chunk-save";

    public static final String KEY_INTERVAL_SECONDS = "journal-interval-seconds";

    public static final String KEY_CHUNKS_PER_TICK = "journal-chunks-per-tick";

    private JournalSettings() {
    }

    public static boolean reliableChunkSave() {
        return PrtsConfigManager.feature(CATEGORY, KEY_RELIABLE_CHUNK_SAVE);
    }

    public static int intervalSeconds() {
        return PrtsConfigManager.number(CATEGORY, KEY_INTERVAL_SECONDS);
    }

    public static int chunksPerTick() {
        return PrtsConfigManager.number(CATEGORY, KEY_CHUNKS_PER_TICK);
    }
}
