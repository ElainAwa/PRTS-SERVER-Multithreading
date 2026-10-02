/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.optional.servercore;

import com.mojang.logging.LogUtils;
import io.izzel.arclight.common.bridge.core.server.level.ChunkMapBridge;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.storage.ChunkSerializer;
import net.minecraft.world.level.chunk.storage.ChunkStorage;
import org.slf4j.Logger;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Write-ahead journal for chunk saves, opt-in through {@link JournalSettings}: a cycle serializes the
 * unsaved chunks of every level into a journal a few per tick and publishes it only when the cycle is
 * done. On the next start entries newer than the region file are replayed, so a replay never replaces
 * newer data with an older snapshot, and a clean shutdown drops the files. Driven by platform events,
 * so it occupies no tick-loop, world-lifecycle or shutdown seam.
 */
public final class ChunkJournal {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String MAGIC = "PRTSJRNL";

    private static final int VERSION = 1;

    private static final Path DIR = Path.of("journal");

    private static final String SUFFIX = ".jrn";
    private static final String TMP_SUFFIX = ".jrn.tmp";
    private static final String APPLIED_SUFFIX = ".jrn.applied";

    private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;

    /**
     * Cycle state, owned by the server thread: {@code flushing} between the first serialization and
     * publication, {@code PENDING} one entry per level that has work.
     */
    private static final List<PendingLevel> PENDING = new ArrayList<>();
    private static boolean flushing;

    private ChunkJournal() {
    }

    /** @return true between the first serialization of a cycle and its publication */
    public static boolean isFlushing() {
        return flushing;
    }

    /**
     * Opens a cycle: takes stock of the unsaved chunks of every level and opens one journal file per
     * level that has work. Called on the server thread.
     */
    public static void beginFlush(Iterable<ServerLevel> levels) {
        // A cycle that could not be published leaves its files behind; they are dropped, not published now,
        // because the region files may have moved on since they were serialized.
        for (PendingLevel leftover : PENDING) {
            leftover.discard();
        }
        PENDING.clear();
        flushing = false;
        for (ServerLevel level : levels) {
            PendingLevel pending = new PendingLevel(level);
            ServerChunkCache cache = (ServerChunkCache) level.getChunkSource();
            for (ChunkHolder holder : ((ChunkMapBridge) cache.chunkMap).bridge$getLoadedChunksIterable()) {
                ChunkAccess chunk = fullChunkOrNull(holder);
                if (chunk != null && chunk.isUnsaved()) {
                    pending.dirty.add(new Entry(chunk.getPos(), chunk));
                }
            }
            if (!pending.dirty.isEmpty() && pending.open()) {
                PENDING.add(pending);
            } else {
                pending.discard();
            }
        }
        flushing = !PENDING.isEmpty();
    }

    /**
     * Serializes at most {@code perTick} chunks of the current cycle and publishes it when nothing is
     * left. Called on the server thread once per tick.
     */
    public static void flushTick(int perTick) {
        if (!flushing) {
            return;
        }
        int budget = Math.max(1, perTick);
        for (PendingLevel pending : PENDING) {
            while (budget > 0 && !pending.dirty.isEmpty()) {
                Entry entry = pending.dirty.remove(pending.dirty.size() - 1);
                budget--;
                if (!entry.chunk.isUnsaved()) {
                    // Vanilla saved this chunk after the cycle took stock of it; journaling the in-memory
                    // state now would publish a snapshot the region file has overtaken.
                    continue;
                }
                try {
                    pending.append(entry.pos, ChunkSerializer.write(pending.level, entry.chunk));
                } catch (Exception failure) {
                    LOGGER.warn("[PRTS-Journal] journaling {} failed: {}", entry.pos, failure.toString());
                }
            }
            if (budget <= 0) {
                break;
            }
        }
        for (PendingLevel pending : PENDING) {
            if (!pending.dirty.isEmpty()) {
                return;
            }
        }
        finishCycle();
        PENDING.clear();
        flushing = false;
    }

    private static ChunkAccess fullChunkOrNull(ChunkHolder holder) {
        try {
            ChunkResult<LevelChunk> result = holder.getFullChunkFuture().getNow(null);
            return result == null ? null : result.orElse(null);
        } catch (Exception failure) {
            return null;
        }
    }

    /** Flushes, fsyncs, closes and publishes every open journal file, then drops the previous replay evidence. */
    private static void finishCycle() {
        for (PendingLevel pending : PENDING) {
            pending.finish();
        }
        cleanupApplied();
    }

    /**
     * Replays the journal of one level after an unclean exit, before any chunk of it is loaded. An entry
     * is written back only when the journal is newer than the region file, so a replay can never undo a
     * save that happened after the cycle.
     */
    public static void recover(ServerLevel level) {
        Path journal = journalPath(level);
        Path applied = appliedPath(level);
        Path source;
        boolean wasJournal;
        if (Files.exists(journal)) {
            source = journal;
            wasJournal = true;
        } else if (Files.exists(applied)) {
            // An unclean exit inside the verification window: the applied file is the same snapshot, so a
            // replay of it is idempotent.
            source = applied;
            wasJournal = false;
        } else {
            return;
        }
        int recovered = 0;
        int inspected = 0;
        try {
            ChunkStorage storage = (ChunkStorage) ((ServerChunkCache) level.getChunkSource()).chunkMap;
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(source)))) {
                if (!MAGIC.equals(in.readUTF()) || in.readInt() != VERSION) {
                    LOGGER.warn("[PRTS-Journal] {}: bad header, dropped", source);
                    Files.delete(source);
                    return;
                }
                while (true) {
                    int x;
                    int z;
                    int length;
                    try {
                        x = in.readInt();
                        z = in.readInt();
                        length = in.readInt();
                    } catch (EOFException end) {
                        break;
                    }
                    if (length < 0 || length > MAX_ENTRY_BYTES) {
                        LOGGER.warn("[PRTS-Journal] {}: corrupt entry at x={} z={}, rest dropped",
                            source, x, z);
                        break;
                    }
                    byte[] bytes = new byte[length];
                    in.readFully(bytes);
                    CompoundTag tag = NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)));
                    inspected++;
                    ChunkPos pos = new ChunkPos(x, z);
                    if (tag != null && shouldReplay(storage, pos, tag)) {
                        // The storage future completes on the I/O thread; waiting here is startup work,
                        // before any player can join.
                        storage.write(pos, tag).join();
                        recovered++;
                    }
                }
            }
            if (wasJournal) {
                // Two-phase delete: keep the evidence of this replay until the next cycle has been published,
                // which is the point where the region files are safe to trust again.
                try {
                    Files.deleteIfExists(applied);
                    Files.move(source, applied, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException failure) {
                    LOGGER.warn("[PRTS-Journal] rename {} failed, deleting: {}", source, failure.toString());
                    deleteQuietly(source);
                }
            }
            LOGGER.info("[PRTS-Journal] recovered {} chunks ({} entries inspected) -> {}",
                recovered, inspected, level.dimension().location());
        } catch (IOException failure) {
            LOGGER.warn("[PRTS-Journal] recover failed ({}): {}", level.dimension().location(),
                failure.toString());
        }
    }

    // LastUpdate orders a snapshot against the region file; an unreadable region file makes the replay conservative.
    private static boolean shouldReplay(ChunkStorage storage, ChunkPos pos, CompoundTag journalTag) {
        try {
            CompoundTag existing = storage.read(pos).join().orElse(null);
            if (existing == null) {
                return true;
            }
            return lastUpdate(journalTag) > lastUpdate(existing);
        } catch (Exception failure) {
            LOGGER.warn("[PRTS-Journal] region compare for {} failed, replaying: {}", pos,
                failure.toString());
            return true;
        }
    }

    private static long lastUpdate(CompoundTag tag) {
        return tag.getLong("LastUpdate");
    }

    private static void cleanupApplied() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(DIR, "*" + APPLIED_SUFFIX)) {
            for (Path path : stream) {
                deleteQuietly(path);
            }
        } catch (IOException ignored) {
            // Nothing to clean up when the directory does not exist yet.
        }
    }

    /**
     * Removes the journal of one level on a clean shutdown, where the normal save already wrote everything
     * it protected.
     */
    public static void markClean(ServerLevel level) {
        int removed = 0;
        for (Path path : List.of(journalPath(level), appliedPath(level), tmpPath(level))) {
            if (deleteQuietly(path)) {
                removed++;
            }
        }
        if (removed > 0) {
            LOGGER.info("[PRTS-Journal] clean shutdown, removed {} file(s) for {}", removed,
                level.dimension().location());
        }
    }

    private static Path journalPath(ServerLevel level) {
        return DIR.resolve(name(level) + SUFFIX);
    }

    private static Path appliedPath(ServerLevel level) {
        return DIR.resolve(name(level) + APPLIED_SUFFIX);
    }

    private static Path tmpPath(ServerLevel level) {
        return DIR.resolve(name(level) + TMP_SUFFIX);
    }

    private static String name(ServerLevel level) {
        return level.dimension().location().toString().replace(':', '_');
    }

    private static boolean deleteQuietly(Path path) {
        try {
            return Files.deleteIfExists(path);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static byte[] toBytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        NbtIo.writeUnnamedTag(tag, new DataOutputStream(buffer));
        return buffer.toByteArray();
    }

    private static final class PendingLevel {

        private final ServerLevel level;
        private final List<Entry> dirty = new ArrayList<>();
        private final Path tmp;
        private final Path target;

        private FileChannel channel;
        private DataOutputStream out;
        private int written;

        private PendingLevel(ServerLevel level) {
            this.level = level;
            this.tmp = tmpPath(level);
            this.target = journalPath(level);
        }

        private boolean open() {
            try {
                Files.createDirectories(DIR);
                Files.deleteIfExists(tmp);
                channel = FileChannel.open(tmp, StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING);
                out = new DataOutputStream(new BufferedOutputStream(Channels.newOutputStream(channel)));
                out.writeUTF(MAGIC);
                out.writeInt(VERSION);
                return true;
            } catch (IOException failure) {
                LOGGER.warn("[PRTS-Journal] cannot open {}: {}", tmp, failure.toString());
                discard();
                return false;
            }
        }

        private void append(ChunkPos pos, CompoundTag tag) throws IOException {
            byte[] bytes = toBytes(tag);
            out.writeInt(pos.x);
            out.writeInt(pos.z);
            out.writeInt(bytes.length);
            out.write(bytes);
            written++;
        }

        /** Publishes the file: flush, fsync, close and move into place; a level with no entry drops its file. */
        private void finish() {
            try {
                out.flush();
                channel.force(true);
            } catch (IOException failure) {
                LOGGER.warn("[PRTS-Journal] writing {} failed: {}", tmp, failure.toString());
                discard();
                return;
            }
            try {
                channel.close();
            } catch (IOException ignored) {
                // The file is already on disk; a failed close does not invalidate it.
            }
            if (written == 0) {
                deleteQuietly(tmp);
                return;
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("[PRTS-Journal] flushed {} chunks -> {}", written, target);
            } catch (IOException failure) {
                LOGGER.warn("[PRTS-Journal] publishing {} failed: {}", target, failure.toString());
                deleteQuietly(tmp);
            }
        }

        /** Closes the file without publishing it and drops the temporary file. */
        private void discard() {
            try {
                if (out != null) {
                    out.close();
                }
            } catch (IOException ignored) {
                // The handle is closed below in any case.
            }
            try {
                if (channel != null && channel.isOpen()) {
                    channel.close();
                }
            } catch (IOException ignored) {
                // Nothing is read back from a file that is being dropped.
            }
            deleteQuietly(tmp);
        }
    }

    private static final class Entry {

        private final ChunkPos pos;
        private final ChunkAccess chunk;

        private Entry(ChunkPos pos, ChunkAccess chunk) {
            this.pos = pos;
            this.chunk = chunk;
        }
    }
}
