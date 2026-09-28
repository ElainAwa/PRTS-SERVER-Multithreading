/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.optional.servercore;

import io.izzel.arclight.common.prts.optional.journal.ChunkJournal;
import io.izzel.arclight.common.prts.optional.journal.JournalSettings;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Drives the optional recovery journal: one flush cycle per interval, a replay while the levels are
 * created, and a cleanup on a clean shutdown.
 *
 * <p>All three handlers ask {@link JournalSettings} first, so a server that enables the layer but
 * not the journal pays one configuration lookup per tick and nothing else. The class is only
 * applied at all while the optional category is enabled, which is off by default.</p>
 *
 * <p><b>These three anchors sit on kernel seams</b> (the tick loop and the world lifecycle), so they
 * carry {@code require = 0}: a kernel that rewrites the methods must not be kept from starting by a
 * hook that no longer finds its anchor. The injection validation run treats such a hook as "must
 * match at least once" anyway, so a moved anchor is reported at start instead of failing silently.
 * When the kernel takes the tick loop and the world lifecycle over, these handlers move to its
 * tick-tail and world-lifecycle hooks; the journal itself stays where it is.</p>
 */
@Mixin(MinecraftServer.class)
public abstract class ChunkJournalMixin_Recovery {

    @Shadow
    public abstract Iterable<ServerLevel> getAllLevels();

    /** Ticks since the last cycle started; owned by the server thread, like the tick loop itself. */
    @Unique
    private int prts$journalTick;

    @Inject(method = "tickServer", at = @At("RETURN"), require = 0)
    private void prts$journalTick(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        if (!JournalSettings.reliableChunkSave()) {
            return;
        }
        int perTick = JournalSettings.chunksPerTick();
        if (ChunkJournal.isFlushing()) {
            ChunkJournal.flushTick(perTick);
            return;
        }
        int interval = Math.max(5, JournalSettings.intervalSeconds()) * 20;
        if (++this.prts$journalTick % interval != 0) {
            return;
        }
        ChunkJournal.beginFlush(this.getAllLevels());
        ChunkJournal.flushTick(perTick);
    }

    @Inject(method = "createLevels", at = @At("RETURN"), require = 0)
    private void prts$journalRecover(ChunkProgressListener listener, CallbackInfo ci) {
        if (!JournalSettings.reliableChunkSave()) {
            return;
        }
        for (ServerLevel level : this.getAllLevels()) {
            ChunkJournal.recover(level);
        }
    }

    @Inject(method = "stopServer", at = @At("HEAD"), require = 0)
    private void prts$journalClean(CallbackInfo ci) {
        if (!JournalSettings.reliableChunkSave()) {
            return;
        }
        for (ServerLevel level : this.getAllLevels()) {
            ChunkJournal.markClean(level);
        }
    }
}
