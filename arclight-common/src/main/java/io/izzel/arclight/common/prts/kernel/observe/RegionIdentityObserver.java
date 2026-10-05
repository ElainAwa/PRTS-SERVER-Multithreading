/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Names the regions the loaded chunks of every world fall into, and publishes the identity a
 * stability window and a rollback gate are counted against.
 *
 * <p>A region is the connected component of the loaded chunks of one world under the eight
 * neighbour relation, so the region of a chunk is decided by the chunk set alone and not by any
 * insertion order. The identity is a digest of the whole partition - every world, every component,
 * every member chunk - so two reads agree exactly when the partition agrees.
 *
 * <p>This observer only reads. It merges nothing, splits nothing, skips no tick and sets no
 * barrier: the partition is recomputed at each read and the counters that would belong to those
 * actions are published at their zero. Every field is an observation request; no gate, verdict or
 * release reads one.
 *
 * <p>The two window lengths are declared values and not measured ones, and this batch adds no
 * configuration key, so they live here as constants.
 */
public final class RegionIdentityObserver {

    /** How long a partition has to hold before a merge or a split may take effect. Declared value. */
    public static final int STABILITY_WINDOW_TICKS = 20;

    /** How long a region has to stay inside its share before the isolation is lifted. Declared value. */
    public static final int ROLLBACK_GATE_TICKS = 20;

    /** Why the barrier counter can only be zero: no server wide barrier is built in this tree. */
    public static final String BARRIER_WAIT_SOURCE = "none";

    /** The four neighbour offsets that close the eight neighbour relation without visiting twice. */
    private static final int[][] FORWARD_NEIGHBOURS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}};

    /** One component of a partition: its lowest packed chunk, its size and its own digest. */
    public record Component(long anchor, int chunks, String hash) {
    }

    /** One world's partition: the components in anchor order, the chunks they cover and the digest. */
    public record Partition(int chunks, List<Component> components, String hash) {

        public int count() {
            return components.size();
        }
    }

    /** One world's loaded chunks, in the packed form {@link #pack(int, int)} returns. */
    public record WorldChunks(String worldId, long[] chunks) {
    }

    /** One world as the last read saw it. */
    public record WorldRow(String worldId, int components, int chunks) {
    }

    /** What one read of the partition found, plus the counters of the reads before it. */
    public record Reading(int available, int count, String identityHash, int chunks, int worlds,
                          List<WorldRow> perWorld, long partitionReads, long jitterEvents,
                          long lastChangeTick) {
    }

    private volatile MinecraftServer source;
    private volatile int count;
    private volatile String identityHash = "-";
    private volatile int chunks;
    private volatile int worlds;
    private volatile boolean available;
    private volatile List<WorldRow> perWorld = List.of();
    private volatile long partitionReads;
    private volatile long jitterEvents;
    private volatile long lastChangeTick = -1L;
    private String previousHash = "-";
    private boolean hasPrevious;

    /** Remembers the server the regions are read from at a read. */
    public void source(MinecraftServer server) {
        this.source = server;
    }

    public boolean installed() {
        return source != null;
    }

    /** The last read, without reading again. */
    public Reading reading() {
        return new Reading(available ? 1 : 0, count, identityHash, chunks, worlds, perWorld,
            partitionReads, jitterEvents, lastChangeTick);
    }

    /**
     * Reads the partition of every loaded world and folds it into the counters.
     *
     * @param tick the tick index the read happened on, kept when the partition changed
     * @return the read, with {@code available = 0} while no server has been seen or while the
     *     loaded chunk set could not be listed
     */
    public Reading read(long tick) {
        MinecraftServer server = source;
        if (server == null) {
            return reading();
        }
        List<WorldChunks> observed = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            long[] loaded = loadedChunks(level);
            if (loaded == null) {
                available = false;
                count = 0;
                identityHash = "-";
                chunks = 0;
                worlds = 0;
                perWorld = List.of();
                return reading();
            }
            observed.add(new WorldChunks(level.dimension().location().toString(), loaded));
        }
        observed.sort((left, right) -> left.worldId().compareTo(right.worldId()));
        int total = 0;
        int worldsSeen = 0;
        List<WorldRow> rows = new ArrayList<>(observed.size());
        StringBuilder canonical = new StringBuilder();
        for (WorldChunks world : observed) {
            Partition partition = partition(world.chunks());
            total += partition.count();
            worldsSeen += partition.chunks() > 0 ? 1 : 0;
            rows.add(new WorldRow(world.worldId(), partition.count(), partition.chunks()));
            canonical.append(world.worldId()).append(':').append(partition.hash()).append('\n');
        }
        String hash = digest(canonical.toString());
        count = total;
        chunks = sumChunks(rows);
        worlds = worldsSeen;
        identityHash = hash;
        perWorld = List.copyOf(rows);
        available = true;
        partitionReads++;
        if (hasPrevious && !hash.equals(previousHash)) {
            jitterEvents++;
            lastChangeTick = tick;
        }
        previousHash = hash;
        hasPrevious = true;
        return reading();
    }

    /** The number of regions that were skipped for being over their share. No region is skipped. */
    public long skip() {
        return 0L;
    }

    /** The number of ticks spent waiting for another region to reach a commit point. No such wait. */
    public long barrierWaitCount() {
        return 0L;
    }

    /** The number of merges and splits that were applied. The partition is never adopted. */
    public long divisionsApplied() {
        return 0L;
    }

    public int stabilityWindowTicks() {
        return STABILITY_WINDOW_TICKS;
    }

    public int rollbackGateTicks() {
        return ROLLBACK_GATE_TICKS;
    }

    /** Clears the counters but keeps the server the reads come from. */
    public synchronized void reset() {
        count = 0;
        identityHash = "-";
        chunks = 0;
        worlds = 0;
        available = false;
        perWorld = List.of();
        partitionReads = 0L;
        jitterEvents = 0L;
        lastChangeTick = -1L;
        previousHash = "-";
        hasPrevious = false;
    }

    /** Packs a chunk coordinate so that the order is by x and then by z; its own inverse. */
    public static long pack(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    public static int unpackX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackZ(long packed) {
        return (int) packed;
    }

    /**
     * Splits one world's loaded chunks into eight neighbour connected components.
     *
     * @param packed the chunk coordinates, in any order and with duplicates allowed
     * @return the components in anchor order, the chunks they cover and the partition digest
     */
    public static Partition partition(long[] packed) {
        long[] cells = packed.clone();
        Arrays.sort(cells);
        int size = 0;
        for (int index = 0; index < cells.length; index++) {
            if (index == 0 || cells[index] != cells[index - 1]) {
                cells[size++] = cells[index];
            }
        }
        cells = Arrays.copyOf(cells, size);
        Map<Long, Integer> index = new HashMap<>(Math.max(16, size * 2));
        for (int at = 0; at < size; at++) {
            index.put(cells[at], at);
        }
        int[] parent = new int[size];
        for (int at = 0; at < size; at++) {
            parent[at] = at;
        }
        for (int at = 0; at < size; at++) {
            int x = unpackX(cells[at]);
            int z = unpackZ(cells[at]);
            for (int[] offset : FORWARD_NEIGHBOURS) {
                Integer other = index.get(pack(x + offset[0], z + offset[1]));
                if (other != null) {
                    union(parent, at, other);
                }
            }
        }
        Map<Integer, List<Long>> groups = new HashMap<>();
        for (int at = 0; at < size; at++) {
            groups.computeIfAbsent(find(parent, at), root -> new ArrayList<>()).add(cells[at]);
        }
        List<Component> components = new ArrayList<>(groups.size());
        for (List<Long> members : groups.values()) {
            members.sort(null);
            components.add(new Component(members.get(0), members.size(), membersHash(members)));
        }
        components.sort((left, right) -> Long.compare(left.anchor(), right.anchor()));
        StringBuilder canonical = new StringBuilder();
        for (Component component : components) {
            canonical.append(component.anchor()).append(':').append(component.chunks()).append(':')
                .append(component.hash()).append('\n');
        }
        return new Partition(size, List.copyOf(components), digest(canonical.toString()));
    }

    /** The digest of a whole partition read, one world after another in world id order. */
    public static String identityHash(List<WorldChunks> worlds) {
        List<WorldChunks> ordered = new ArrayList<>(worlds);
        ordered.sort((left, right) -> left.worldId().compareTo(right.worldId()));
        StringBuilder canonical = new StringBuilder();
        for (WorldChunks world : ordered) {
            canonical.append(world.worldId()).append(':').append(partition(world.chunks()).hash())
                .append('\n');
        }
        return digest(canonical.toString());
    }

    /** The chunk coordinates of one world whose holder already carries a full chunk. */
    private static long[] loadedChunks(ServerLevel level) {
        Method method = Holders.GET_CHUNKS;
        if (method == null) {
            return null;
        }
        Object holders;
        try {
            holders = method.invoke(level.getChunkSource().chunkMap);
        } catch (Throwable failure) {
            return null;
        }
        if (!(holders instanceof Iterable<?> iterable)) {
            return null;
        }
        long[] buffer = new long[256];
        int size = 0;
        try {
            for (Object holder : iterable) {
                ChunkHolder chunk = (ChunkHolder) holder;
                if (chunk.getTickingChunk() == null) {
                    continue;
                }
                ChunkPos position = chunk.getPos();
                if (size == buffer.length) {
                    buffer = Arrays.copyOf(buffer, size * 2);
                }
                buffer[size++] = pack(position.x, position.z);
            }
        } catch (Throwable failure) {
            return null;
        }
        return Arrays.copyOf(buffer, size);
    }

    private static int sumChunks(List<WorldRow> rows) {
        int total = 0;
        for (WorldRow row : rows) {
            total += row.chunks();
        }
        return total;
    }

    private static String membersHash(List<Long> members) {
        StringBuilder canonical = new StringBuilder();
        for (Long member : members) {
            canonical.append(member).append('\n');
        }
        return digest(canonical.toString());
    }

    static String digest(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("MD5");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | RuntimeException failure) {
            return "unavailable";
        }
    }

    private static int find(int[] parent, int at) {
        int root = at;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[at] != root) {
            int next = parent[at];
            parent[at] = root;
            at = next;
        }
        return root;
    }

    private static void union(int[] parent, int left, int right) {
        int leftRoot = find(parent, left);
        int rightRoot = find(parent, right);
        if (leftRoot != rightRoot) {
            parent[Math.max(leftRoot, rightRoot)] = Math.min(leftRoot, rightRoot);
        }
    }

    /** Resolved on first use, so a test that only reads a partition never touches the game. */
    private static final class Holders {

        static final Method GET_CHUNKS = resolve();

        private static Method resolve() {
            try {
                Method method = ChunkMap.class.getDeclaredMethod("getChunks");
                method.setAccessible(true);
                return method;
            } catch (Throwable failure) {
                return null;
            }
        }
    }
}
