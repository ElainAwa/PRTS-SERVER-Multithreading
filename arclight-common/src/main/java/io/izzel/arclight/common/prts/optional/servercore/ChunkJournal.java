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
 * Write-ahead journal for chunk saves, opt-in through {@link JournalSettings}.
 *
 * <p>A cycle walks the chunks the server already keeps, takes the unsaved ones and serializes them
 * into {@code journal/<dimension>.jrn}, a few per tick, so the work is spread over the cycle instead
 * of landing in one tick. The file is fsynced and moved into place only when the whole cycle has
 * been serialized, which is what makes it a usable snapshot after an unclean exit: the region files
 * may be minutes behind, the journal is at most one interval behind. On the next start the journal
 * is replayed, and only entries whose {@code LastUpdate} is newer than what the region file already
 * holds are written back, so a replay can never replace newer data with an older snapshot. After a
 * successful replay the file is renamed to {@code .jrn.applied} and kept until the next cycle has
 * been published, which leaves the evidence of one unclean exit in place without replaying it twice
 * on purpose; a clean shutdown removes both files.</p>
 *
 * <p><b>Ownership and threading.</b> Every entry point runs on the server thread: the server tick
 * event that drives a cycle, the level-load event that replays, and the server-stopping event that
 * drops the files of a clean shutdown. The cycle state below is therefore owned by that thread, and
 * it is static because a dedicated server runs one set of levels per process. Serialization and the
 * file writes that finish a cycle do block that thread (disk I/O), which is bounded by the per-tick
 * budget and the fsync of one file, and is one of the reasons this layer is opt-in.</p>
 *
 * <p><b>Kernel seam.</b> The class occupies no tick-loop, world-lifecycle or shutdown seam: it is
 * driven by platform events, so a kernel that rewrites those methods keeps it running. The seam it
 * does sit on is storage and serialization, which is owned by the kernel; when the kernel takes it
 * over, this whole subtree is meant to be deleted. The journal writes only under its own
 * {@code journal} directory and never touches the region layout, so dropping it leaves nothing
 * behind.</p>
 */
public final class ChunkJournal {

    private static final Logger LOGGER = LogUtils.getLogger();

    /** Header of a journal file; a file without it is not ours and is dropped. */
    private static final String MAGIC = "PRTSJRNL";

    /** Layout version of the entries behind the header. */
    private static final int VERSION = 1;

    /** Directory the layer owns, relative to the server working directory. */
    private static final Path DIR = Path.of("journal");

    private static final String SUFFIX = ".jrn";
    private static final String TMP_SUFFIX = ".jrn.tmp";
    private static final String APPLIED_SUFFIX = ".jrn.applied";

    /** Upper bound of one serialized chunk; a longer entry means the file is not a journal. */
    private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;

    /**
     * Cycle state, owned by the server thread.
     *
     * <p>{@code flushing} is true from the moment a cycle opened its files until the cycle has been
     * published or abandoned; {@code PENDING} holds one entry per level that has work, and a level
     * whose journal file could not be opened never enters it.</p>
     */
    private static final List<PendingLevel> PENDING = new ArrayList<>();
    private static boolean flushing;

    private ChunkJournal() {
    }

    /**
     * Returns whether a cycle is in progress.
     *
     * @return {@code true} between the first serialization of a cycle and its publication
     */
    public static boolean isFlushing() {
        return flushing;
    }

    /**
     * Opens a cycle: takes stock of the unsaved chunks of every level and opens one journal file per
     * level that has work. Called on the server thread.
     *
     * @param levels the levels to walk
     */
    public static void beginFlush(Iterable<ServerLevel> levels) {
        // a cycle that could not be published leaves its files behind; they are dropped rather than
        // published now, because the region files may have moved on since they were serialized
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
     * Serializes at most {@code perTick} chunks of the current cycle and publishes it when nothing
     * is left. Called on the server thread once per tick.
     *
     * @param perTick how many chunks this call may serialize; at least one
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
                    // vanilla saved this chunk after the cycle took stock of it: journaling the
                    // in-memory state now would publish a snapshot the region file has overtaken
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

    /**
     * Returns the fully loaded chunk of a holder, or {@code null} while it is not there yet.
     *
     * <p>A chunk that is still being generated or that failed to load is simply not part of this
     * cycle; the next one picks it up if it is unsaved by then.</p>
     *
     * @param holder a chunk holder of the level being walked
     * @return the loaded chunk, or {@code null}
     */
    private static ChunkAccess fullChunkOrNull(ChunkHolder holder) {
        try {
            ChunkResult<LevelChunk> result = holder.getFullChunkFuture().getNow(null);
            return result == null ? null : result.orElse(null);
        } catch (Exception failure) {
            return null;
        }
    }

    /**
     * Finishes every open journal file: flush, fsync, close and publish, then drop the evidence of
     * the previous replay. Called on the server thread.
     */
    private static void finishCycle() {
        for (PendingLevel pending : PENDING) {
            pending.finish();
        }
        cleanupApplied();
    }

    /**
     * Replays the journal of one level after an unclean exit. Called on the server thread while the
     * levels are being created, before any chunk of this level is loaded.
     *
     * <p>An entry is written back only when the journal is newer than the region file: the region
     * file can be ahead of the journal when vanilla saved between the last cycle and the crash, and
     * a replay must never undo that. The write itself goes through the level's own storage, so the
     * region layout stays the platform's.</p>
     *
     * @param level the level to replay into
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
            // an unclean exit inside the verification window: the applied file is the same
            // snapshot as the journal it came from, so replaying it again is idempotent
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
                        // the storage future is completed on the I/O thread; waiting here is
                        // startup work, before any player can join
                        storage.write(pos, tag).join();
                        recovered++;
                    }
                }
            }
            if (wasJournal) {
                // two-phase delete: keep the evidence of this replay until the next cycle has been
                // published, which is the point where the region files are safe to trust again
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

    /**
     * Decides whether one journal entry is newer than the region file.
     *
     * <p>{@code LastUpdate} is the game time the snapshot was serialized at, so it orders the two
     * copies without any extra bookkeeping. A region file that cannot be read makes the replay
     * conservative: writing the journal back is the safe direction there.</p>
     *
     * @param storage the level's own storage
     * @param pos     chunk the entry belongs to
     * @param journalTag the snapshot from the journal
     * @return {@code true} when the snapshot has to be written back
     */
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

    /**
     * Returns the game time a serialized chunk was written at.
     *
     * @param tag a serialized chunk
     * @return the {@code LastUpdate} value, zero when it carries none
     */
    private static long lastUpdate(CompoundTag tag) {
        return tag.getLong("LastUpdate");
    }

    /**
     * Removes the evidence of the previous replay once a new cycle has been published.
     *
     * <p>Called after every published cycle: from that moment on the region files hold at least as
     * much as the applied file did, so keeping it would only make the next start replay old data.</p>
     */
    private static void cleanupApplied() {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(DIR, "*" + APPLIED_SUFFIX)) {
            for (Path path : stream) {
                deleteQuietly(path);
            }
        } catch (IOException ignored) {
            // nothing to clean up when the directory does not exist yet
        }
    }

    /**
     * Removes the journal of one level on a clean shutdown, where the normal save has already
     * written everything the journal would have protected.
     *
     * @param level the level whose files are dropped
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

    /**
     * Returns the journal file of one level.
     *
     * @param level the level
     * @return {@code journal/<dimension>.jrn}
     */
    private static Path journalPath(ServerLevel level) {
        return DIR.resolve(name(level) + SUFFIX);
    }

    /**
     * Returns the applied file of one level.
     *
     * @param level the level
     * @return {@code journal/<dimension>.jrn.applied}
     */
    private static Path appliedPath(ServerLevel level) {
        return DIR.resolve(name(level) + APPLIED_SUFFIX);
    }

    /**
     * Returns the temporary file of one level.
     *
     * @param level the level
     * @return {@code journal/<dimension>.jrn.tmp}
     */
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

    /**
     * Serializes a chunk tag into the journal entry form.
     *
     * @param tag a serialized chunk
     * @return the unnamed NBT form of the tag
     * @throws IOException when the tag cannot be written
     */
    private static byte[] toBytes(CompoundTag tag) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        NbtIo.writeUnnamedTag(tag, new DataOutputStream(buffer));
        return buffer.toByteArray();
    }

    /** One level of the current cycle: the work left, the file being written and what went in. */
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

        /**
         * Opens the temporary file of this level and writes its header.
         *
         * @return {@code true} when the file is ready; the caller drops the level otherwise
         */
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

        /**
         * Appends one serialized chunk to the open file.
         *
         * @param pos  chunk the entry belongs to
         * @param tag  the serialized chunk
         * @throws IOException when the entry cannot be written
         */
        private void append(ChunkPos pos, CompoundTag tag) throws IOException {
            byte[] bytes = toBytes(tag);
            out.writeInt(pos.x);
            out.writeInt(pos.z);
            out.writeInt(bytes.length);
            out.write(bytes);
            written++;
        }

        /**
         * Publishes the file: flush, fsync, close and move into place. A level that produced no
         * entry drops its file instead of publishing an empty journal.
         */
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
                // the file is already on disk; a failed close does not invalidate it
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
                // the handle is closed below in any case
            }
            try {
                if (channel != null && channel.isOpen()) {
                    channel.close();
                }
            } catch (IOException ignored) {
                // nothing is read back from a file that is being dropped
            }
            deleteQuietly(tmp);
        }
    }

    /** One chunk the cycle took stock of. */
    private static final class Entry {

        private final ChunkPos pos;
        private final ChunkAccess chunk;

        private Entry(ChunkPos pos, ChunkAccess chunk) {
            this.pos = pos;
            this.chunk = chunk;
        }
    }
}
