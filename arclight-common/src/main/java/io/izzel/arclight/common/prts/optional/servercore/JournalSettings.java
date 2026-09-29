/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.optional.journal;

import io.izzel.arclight.common.prts.config.PrtsConfigManager;

/**
 * The settings of the optional recovery journal.
 *
 * <p>The names and the defaults live in the configuration layer, which is the single place a
 * generated file is rendered from; this class only names the keys the journal reads, so enabling
 * the layer and enabling the journal stay two separate decisions (the category is off by default
 * and so is the journal).</p>
 *
 * <p>A key whose declaration is gone makes the read throw, which is deliberate: the alternative is
 * a journal that silently reads a default nobody declared.</p>
 */
public final class JournalSettings {

    /** Category the journal belongs to; also its configuration file. */
    public static final String CATEGORY = PrtsConfigManager.OPTIONAL_SERVERCORE;

    /** Turns the journal on; off by default. */
    public static final String KEY_RELIABLE_CHUNK_SAVE = "reliable-chunk-save";

    /** Seconds between the start of two flush cycles. */
    public static final String KEY_INTERVAL_SECONDS = "journal-interval-seconds";

    /** Chunks one tick may serialize inside a cycle. */
    public static final String KEY_CHUNKS_PER_TICK = "journal-chunks-per-tick";

    private JournalSettings() {
    }

    /**
     * Returns whether the journal is enabled.
     *
     * @return {@code true} when dirty chunks are journaled and replayed after an unclean exit
     */
    public static boolean reliableChunkSave() {
        return PrtsConfigManager.feature(CATEGORY, KEY_RELIABLE_CHUNK_SAVE);
    }

    /**
     * Returns the length of one cycle in seconds.
     *
     * @return the configured interval, already clamped to the declared range
     */
    public static int intervalSeconds() {
        return PrtsConfigManager.number(CATEGORY, KEY_INTERVAL_SECONDS);
    }

    /**
     * Returns how many chunks one tick may serialize.
     *
     * @return the configured budget, already clamped to the declared range
     */
    public static int chunksPerTick() {
        return PrtsConfigManager.number(CATEGORY, KEY_CHUNKS_PER_TICK);
    }
}
