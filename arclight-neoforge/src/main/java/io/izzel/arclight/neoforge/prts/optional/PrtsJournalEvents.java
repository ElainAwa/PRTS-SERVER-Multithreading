/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.prts.optional;

import io.izzel.arclight.common.prts.optional.servercore.ChunkJournal;
import io.izzel.arclight.common.prts.optional.servercore.JournalSettings;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Drives the optional recovery journal from platform events instead of mixin anchors.
 *
 * <p>The three events carry exactly the three moments the journal needs, and the platform fires
 * them itself, so the layer occupies no mixin anchor and no kernel seam:</p>
 * <ul>
 *   <li>{@link ServerTickEvent.Post} is fired at the end of {@code MinecraftServer#tickServer},
 *       immediately before that method returns - the per-tick moment a flush cycle moves on;</li>
 *   <li>{@link LevelEvent.Load} is fired by {@code MinecraftServer#createLevels} for every level
 *       once it has been constructed and registered, before the spawn chunks are prepared - the
 *       moment a journal left by an unclean exit is replayed, still before any chunk of that level
 *       is read;</li>
 *   <li>{@link ServerStoppingEvent} is fired on the shutdown path immediately before
 *       {@code MinecraftServer#stopServer} saves the world - the moment the files of a clean
 *       shutdown are dropped.</li>
 * </ul>
 *
 * <p>The listeners are subscribed only while the optional category is enabled, so a server that
 * leaves the layer off pays nothing at all and never loads the journal. With the category on, every
 * callback asks {@link JournalSettings} first, so a server that enables the layer but not the
 * journal pays one configuration lookup per tick and nothing else.</p>
 *
 * <p>PRTS category: optional, NeoForge platform module.</p>
 */
public final class PrtsJournalEvents {

    /** Ticks since the last cycle started; owned by the server thread, like the tick event itself. */
    private int tick;

    private PrtsJournalEvents() {
    }

    /** Subscribes the layer for this server process; called once, while the category is enabled. */
    public static void register() {
        NeoForge.EVENT_BUS.register(new PrtsJournalEvents());
    }

    /**
     * Advances the current cycle, or opens the next one when the interval has passed.
     *
     * @param event the platform's end-of-tick event
     */
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (!JournalSettings.reliableChunkSave()) {
            return;
        }
        int perTick = JournalSettings.chunksPerTick();
        if (ChunkJournal.isFlushing()) {
            ChunkJournal.flushTick(perTick);
            return;
        }
        int interval = Math.max(5, JournalSettings.intervalSeconds()) * 20;
        if (++this.tick % interval != 0) {
            return;
        }
        ChunkJournal.beginFlush(event.getServer().getAllLevels());
        ChunkJournal.flushTick(perTick);
    }

    /**
     * Replays the journal of the level that was just created.
     *
     * @param event the platform's level-load event
     */
    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (!JournalSettings.reliableChunkSave()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            ChunkJournal.recover(level);
        }
    }

    /**
     * Drops the journal files of every level on the shutdown path.
     *
     * @param event the platform's server-stopping event
     */
    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        if (!JournalSettings.reliableChunkSave()) {
            return;
        }
        for (ServerLevel level : event.getServer().getAllLevels()) {
            ChunkJournal.markClean(level);
        }
    }
}
