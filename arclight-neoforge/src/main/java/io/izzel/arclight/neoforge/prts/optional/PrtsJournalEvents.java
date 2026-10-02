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
 * Drives the recovery journal from the platform's own events, so the layer occupies no mixin anchor;
 * every callback asks {@link JournalSettings} first.
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

    /** Advances the current cycle, or opens the next one when the interval has passed. */
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

    /** Replays the journal of the level that was just created. */
    @SubscribeEvent
    public void onLevelLoad(LevelEvent.Load event) {
        if (!JournalSettings.reliableChunkSave()) {
            return;
        }
        if (event.getLevel() instanceof ServerLevel level) {
            ChunkJournal.recover(level);
        }
    }

    /** Drops the journal files of every level on the shutdown path. */
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
