/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsBlockEntityCosts;
import io.izzel.arclight.common.prts.support.PrtsEntityCosts;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.net.URL;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Predicate;
import java.util.function.ToLongFunction;

/**
 * Attributes the time spent ticking block entities and entities to the type, the mod and the colony
 * the instance belongs to, and keeps the attribution of each busy stall as one record.
 *
 * <p>The wall span is read around each tick; the cpu span is the same call's thread cpu time, so a
 * wall span much larger than its cpu span is a span the thread spent off-cpu (parked, blocked on io
 * or a lock, or held by a collection). Neither is an attribution of intent: the type says which code
 * the thread was inside, not why it was there. Identity, position and colony are resolved once per
 * instance; a weak key means an unloaded instance is forgotten rather than pinned.
 *
 * <p>The counters are written by the server thread and read by the command thread. The maps are
 * concurrent and the accumulated fields are volatile, so a read sees a value that was written, not a
 * torn one; a total and a top list are read a moment apart, which is stated here and not corrected.
 *
 * <p>The same rows are also classified against a batch predicate, because a block entity row is a
 * candidate for a batch only when the row is inside a legal batch boundary: the rows of one type of
 * one world inside one tick, at least two of them, all of them placed in a loaded chunk and none of
 * them a physical step or a structure assembly. The classification is counted per row where the type
 * and the position are already resolved, which is what makes a per row predicate affordable; it
 * counts and never delays, cancels or reorders a tick.
 */
public final class StallAttributionObserver
    implements PrtsBlockEntityCosts.CostTap, PrtsEntityCosts.CostTap,
    LoadThreadObserver.BoundaryListener {

    /** A single tick of one instance longer than this is kept as a long tick. Declared value. */
    public static final long LONG_TICK_NANOS = 100_000_000L;
    /** A tick that has attributed this much is dumped while it still runs. Declared value. */
    public static final long LIVE_DUMP_AFTER_NANOS = 5_000_000_000L;
    public static final long LIVE_DUMP_EVERY_NANOS = 10_000_000_000L;
    public static final int LONG_RING = 12;
    public static final int EPISODE_RING = 4;
    public static final int EPISODE_TOP = 8;
    public static final int TYPE_TOP = 24;
    /** The rows one type of one world must have inside one tick to be a batch. Declared value: the
     *  batch size a merge is worth is not measured, and two is the smallest size that is not a call
     *  per object. */
    public static final int ROW_BATCH_FLOOR = 2;
    /**
     * The block entity types whose tick carries a physical step or a structure assembly or
     * disassembly, which may not leave the owner thread and so never counts towards a batch. The list
     * is a registry and not a measurement: it is open, it starts with the moving block entity, and a
     * row of a type that is missing from it stays in the batch total as an upper bound.
     */
    public static final List<String> STRUCTURE_THIRD_TYPES = List.of("minecraft:piston");
    private static final int INSTANCE_CAP = 32_768;
    private static final int COLONY_CACHE_CAP = 65_536;
    private static final Logger LOGGER = LogManager.getLogger("PRTS-observe");
    private static final Object MISSING = new Object();
    private static final Map<String, Object> ACCESSORS = new ConcurrentHashMap<>();

    /** One aggregated row of a face: total span, tick count and, for a type, its instances. */
    public record Row(String key, long ticks, long wallNanos, long cpuNanos, long instances,
                      long maxInstanceNanos, String worst) {
    }

    /** One busy stall, closed at the first boundary that was not one. */
    public record Episode(long tick, long wallNanos, long cpuNanos, long parkNanos,
                          long blockEntityNanos, long blockEntityCpuNanos, long entityNanos,
                          long entityCpuNanos, long gcCollections, long gcMillis, long liveDumps,
                          List<Row> topTypes, List<Row> topMods, List<Row> topColonies) {
    }

    /** One tick of one instance that crossed the long-tick bound. */
    public record LongTick(boolean blockEntity, String type, String mod, String colony, String label,
                           long wallNanos, long cpuNanos, String top) {
    }

    private static final String KIND_TYPE = "type";
    private static final String KIND_MOD = "mod";
    private static final String KIND_COLONY = "colony";

    private static class Cell {

        final String key;
        final String kind;
        final boolean blockEntity;
        volatile long ticks;
        volatile long wallNanos;
        volatile long cpuNanos;
        long tickTicks;
        long tickWall;
        long tickCpu;
        long episodeTicks;
        long episodeWall;
        long episodeCpu;
        boolean tickTouched;

        Cell(String key, String kind, boolean blockEntity) {
            this.key = key;
            this.kind = kind;
            this.blockEntity = blockEntity;
        }

        final void note(long wall, long cpu) {
            ticks++;
            wallNanos += wall;
            tickTicks++;
            tickWall += wall;
            if (cpu > 0L) {
                cpuNanos += cpu;
                tickCpu += cpu;
            }
        }

        final void clearTick() {
            tickTicks = 0L;
            tickWall = 0L;
            tickCpu = 0L;
            tickTouched = false;
        }

        final void foldEpisode() {
            episodeTicks += tickTicks;
            episodeWall += tickWall;
            episodeCpu += tickCpu;
        }

        final void clearEpisode() {
            episodeTicks = 0L;
            episodeWall = 0L;
            episodeCpu = 0L;
        }

        Row row() {
            return new Row(key, ticks, wallNanos, cpuNanos, 0L, 0L, "-");
        }
    }

    private static final class TypeCell extends Cell {

        final Map<Object, Instance> instances = new java.util.WeakHashMap<>();
        final Map<String, BatchCell> batches = new ConcurrentHashMap<>();
        volatile long instanceCount;
        volatile long maxInstanceNanos;
        volatile String worst = "-";

        TypeCell(String key, boolean blockEntity) {
            super(key, KIND_TYPE, blockEntity);
        }

        /** The batch bucket of one world, created on the first row of that world and type. */
        BatchCell batch(String worldId) {
            return batches.computeIfAbsent(worldId, key -> new BatchCell());
        }

        @Override
        Row row() {
            return new Row(key, ticks, wallNanos, cpuNanos, instanceCount, maxInstanceNanos, worst);
        }
    }

    /** The rows one type of one world ticked inside the tick that is running now. */
    private static final class BatchCell {

        int rows;
        boolean touched;
    }

    private static final class Instance {

        final TypeCell type;
        final Cell mod;
        final Cell colony;
        final String label;
        final BatchCell batch;
        final boolean structureThird;
        final boolean regionless;
        long nanos;

        Instance(TypeCell type, Cell mod, Cell colony, String label, BatchCell batch,
                 boolean structureThird, boolean regionless) {
            this.type = type;
            this.mod = mod;
            this.colony = colony;
            this.label = label;
            this.batch = batch;
            this.structureThird = structureThird;
            this.regionless = regionless;
        }
    }

    /** One of the two tick faces: the block entity list and the non-passenger entity rows. */
    private final class Face {

        private final boolean blockEntity;
        private final Map<String, TypeCell> types = new ConcurrentHashMap<>();
        private final Map<String, Cell> mods = new ConcurrentHashMap<>();
        private final Map<Integer, Cell> colonies = new ConcurrentHashMap<>();
        private final Map<Object, Instance> instances = new java.util.WeakHashMap<>();
        private volatile long segmentWall;

        Face(boolean blockEntity) {
            this.blockEntity = blockEntity;
        }

        void note(Object identity, Object level, String worldId, long wall, long cpu) {
            if (identity == null || wall <= 0L) {
                return;
            }
            Instance instance = instances.get(identity);
            if (instance == null) {
                instance = create(identity, level, worldId);
            }
            instance.nanos += wall;
            note(instance.type, wall, cpu);
            note(instance.mod, wall, cpu);
            note(instance.colony, wall, cpu);
            if (blockEntity) {
                noteRow(instance);
            }
            if (instance.nanos > instance.type.maxInstanceNanos) {
                instance.type.maxInstanceNanos = instance.nanos;
                instance.type.worst = instance.label;
            }
            liveTickWall += wall;
            if (liveTickWall > liveTickPeak) {
                liveTickPeak = liveTickWall;
            }
            if (wall >= LONG_TICK_NANOS) {
                recordLongTick(instance, wall, cpu);
            }
            maybeLiveDump();
        }

        private void note(Cell cell, long wall, long cpu) {
            cell.note(wall, cpu);
            if (!cell.tickTouched) {
                cell.tickTouched = true;
                tickCells.add(cell);
            }
        }

        private Instance create(Object identity, Object level, String worldId) {
            String typeKey = blockEntity ? blockEntityType(identity) : entityType(identity);
            TypeCell type = types.computeIfAbsent(typeKey, key -> new TypeCell(key, blockEntity));
            String modKey = blockEntity ? modOfTypeKey(typeKey) : modIndex.modOf(identity.getClass());
            Cell mod = mods.computeIfAbsent(modKey, key -> new Cell(key, KIND_MOD, blockEntity));
            Object position = positionOf(identity);
            int colonyId = -1;
            String label = typeKey;
            if (position != null) {
                int x = intOf(position, "getX");
                int y = intOf(position, "getY");
                int z = intOf(position, "getZ");
                label = typeKey + "@" + x + "," + y + "," + z;
                colonyId = colonies2.colonyOf(level, worldId, position, x, z);
            }
            final int colonyKey = colonyId;
            Cell colony = colonies.computeIfAbsent(colonyId,
                key -> new Cell(colonyKey(colonyKey), KIND_COLONY, blockEntity));
            if (instances.size() >= INSTANCE_CAP) {
                instances.clear();
                instancesCapped = true;
            }
            boolean structureThird = blockEntity && STRUCTURE_THIRD_TYPES.contains(typeKey);
            boolean regionless = position == null;
            BatchCell batch = blockEntity && !structureThird && !regionless ? type.batch(worldId)
                : null;
            Instance instance = new Instance(type, mod, colony, label, batch, structureThird,
                regionless);
            instances.put(identity, instance);
            type.instanceCount++;
            return instance;
        }

        long totalWall() {
            long total = 0L;
            for (TypeCell cell : types.values()) {
                total += cell.wallNanos;
            }
            return total;
        }

        long totalCpu() {
            long total = 0L;
            for (TypeCell cell : types.values()) {
                total += cell.cpuNanos;
            }
            return total;
        }

        long totalTicks() {
            long total = 0L;
            for (TypeCell cell : types.values()) {
                total += cell.ticks;
            }
            return total;
        }

        long totalInstances() {
            long total = 0L;
            for (TypeCell cell : types.values()) {
                total += cell.instanceCount;
            }
            return total;
        }
    }

    private final Face blockEntities = new Face(true);
    private final Face entities = new Face(false);
    private final ModIndex modIndex = new ModIndex();
    private final ColonyIndex colonies2 = new ColonyIndex();
    private final List<Cell> tickCells = new ArrayList<>();
    private final Set<Cell> episodeCells = new LinkedHashSet<>();
    private final ConcurrentLinkedDeque<LongTick> longTicks = new ConcurrentLinkedDeque<>();
    private final ConcurrentLinkedDeque<Episode> episodes = new ConcurrentLinkedDeque<>();
    private volatile boolean installed;
    private volatile boolean instancesCapped;
    private final LongAdder widenedRows = new LongAdder();
    private final LongAdder singletonRows = new LongAdder();
    private final LongAdder structureThirdRows = new LongAdder();
    private final LongAdder regionlessRows = new LongAdder();
    private final LongAdder bucketCount = new LongAdder();
    private final LongAdder batchBucketCount = new LongAdder();
    private final LongAdder foldedTicks = new LongAdder();
    private final List<BatchCell> tickBatches = new ArrayList<>();
    private volatile long pendingRows;
    private int pendingStructureThird;
    private int pendingRegionless;
    private long liveTickWall;
    private long liveTickPeak;
    private long lastLiveDumpNanos;
    private long lastLongStackNanos;
    private long episodeWall;
    private long episodeCpu;
    private long episodePark;
    private long episodeGcCollections;
    private long episodeGcMillis;
    private long episodeLiveDumps;
    private long episodeEnteredTick;
    private long episodeCount;
    private long liveDumps;
    private boolean episodeOpen;
    private long previousGcCollections;
    private long previousGcMillis;

    public void attach() {
        installed = true;
        PrtsBlockEntityCosts.install(this);
        PrtsEntityCosts.install(this);
    }

    public void detach() {
        installed = false;
        PrtsBlockEntityCosts.install(null);
        PrtsEntityCosts.install(null);
    }

    public boolean installed() {
        return installed;
    }

    public String modSource() {
        return modIndex.fmlResolved() ? "fml-mod-list" : "archive-name";
    }

    public boolean colonyAvailable() {
        return colonies2.available();
    }

    public boolean instancesCapped() {
        return instancesCapped;
    }

    public long segmentWall(boolean blockEntity) {
        return blockEntity ? blockEntities.segmentWall : entities.segmentWall;
    }

    @Override
    public void segmentTick(String worldId, long wallNanos) {
        blockEntities.segmentWall += wallNanos;
    }

    @Override
    public void blockEntityTick(Object identity, Object level, String worldId, long wallNanos,
                                long cpuNanos) {
        blockEntities.note(identity, level, worldId, wallNanos, cpuNanos);
    }

    @Override
    public void entityTick(Object identity, Object level, String worldId, long wallNanos,
                           long cpuNanos) {
        entities.note(identity, level, worldId, wallNanos, cpuNanos);
    }

    @Override
    public void tickBoundary(LoadThreadObserver.Boundary boundary) {
        long gcCollections = boundary.gcCollections();
        long gcMillis = boundary.gcMillis();
        if (boundary.busyStall()) {
            if (!episodeOpen) {
                episodeOpen = true;
                episodeEnteredTick = boundary.tick();
                episodeCount++;
                episodeCells.clear();
                episodeWall = 0L;
                episodeCpu = 0L;
                episodePark = 0L;
                episodeLiveDumps = liveDumps;
                episodeGcCollections = previousGcCollections;
                episodeGcMillis = previousGcMillis;
            }
            episodeWall += boundary.bodyWallNanos();
            episodePark += boundary.bodyParkNanos();
            if (boundary.bodyCpuNanos() > 0L) {
                episodeCpu += boundary.bodyCpuNanos();
            }
            for (Cell cell : tickCells) {
                cell.foldEpisode();
                episodeCells.add(cell);
            }
        } else if (episodeOpen) {
            closeEpisode(gcCollections, gcMillis);
        }
        foldRows();
        for (Cell cell : tickCells) {
            cell.clearTick();
        }
        tickCells.clear();
        liveTickWall = 0L;
        liveTickPeak = 0L;
        previousGcCollections = gcCollections;
        previousGcMillis = gcMillis;
    }

    /** Counts one row into its batch bucket, or into the class that keeps it out of a batch. */
    private void noteRow(Instance instance) {
        pendingRows++;
        BatchCell cell = instance.batch;
        if (cell != null) {
            if (!cell.touched) {
                cell.touched = true;
                tickBatches.add(cell);
            }
            cell.rows++;
            return;
        }
        if (instance.structureThird) {
            pendingStructureThird++;
        } else {
            pendingRegionless++;
        }
    }

    /**
     * Closes the row buckets of the tick that just ended: a bucket that reached the floor carries
     * every row in it, a smaller one carries none, and the rows that were never candidates were
     * counted where they were seen. A bucket is one type of one world inside one tick, which is the
     * batch boundary; a bucket may still span two regions, so the widened total stays an upper bound.
     */
    private void foldRows() {
        for (BatchCell cell : tickBatches) {
            int rows = cell.rows;
            cell.rows = 0;
            cell.touched = false;
            bucketCount.increment();
            if (rows >= ROW_BATCH_FLOOR) {
                widenedRows.add(rows);
                batchBucketCount.increment();
            } else {
                singletonRows.add(rows);
            }
        }
        tickBatches.clear();
        if (pendingStructureThird > 0) {
            structureThirdRows.add(pendingStructureThird);
            pendingStructureThird = 0;
        }
        if (pendingRegionless > 0) {
            regionlessRows.add(pendingRegionless);
            pendingRegionless = 0;
        }
        pendingRows = 0L;
        foldedTicks.increment();
    }

    /** The block entity rows a batch could have carried: the rows of a bucket at or over the floor. */
    public long rowWidened() {
        return widenedRows.sum();
    }

    /** The block entity rows that were alone in their bucket, so a batch boundary does not cover them. */
    public long rowSingleton() {
        return singletonRows.sum();
    }

    /** The block entity rows whose type carries a physical step or a structure assembly. */
    public long rowStructureThird() {
        return structureThirdRows.sum();
    }

    /** The block entity rows that published no position, so no region could be named for them. */
    public long rowRegionless() {
        return regionlessRows.sum();
    }

    /** The rows of every class that are still in the tick that is running now: they are in none of
     *  the four classes yet, so the row count of the face is these plus the folded ones. */
    public long rowPending() {
        return pendingRows;
    }

    /** Every block entity row the classification folded; the four classes above add up to it, and
     *  these plus the pending rows are every row the face counted. */
    public long rowHost() {
        return widenedRows.sum() + singletonRows.sum() + structureThirdRows.sum()
            + regionlessRows.sum();
    }

    /** The buckets of the closed ticks, whether or not they reached the floor. */
    public long rowBuckets() {
        return bucketCount.sum();
    }

    /** The buckets that reached the floor and so were carried as one batch. */
    public long rowBatchBuckets() {
        return batchBucketCount.sum();
    }

    /** The tick boundaries the classification was folded at. */
    public long rowTicks() {
        return foldedTicks.sum();
    }

    /** @return the episode that is still open, as far as it has been attributed; null when none is */
    public Episode openEpisode() {
        return episodeOpen ? buildEpisode(previousGcCollections, previousGcMillis) : null;
    }

    private Episode buildEpisode(long gcCollections, long gcMillis) {
        long beWall = 0L;
        long beCpu = 0L;
        long entWall = 0L;
        long entCpu = 0L;
        for (Cell cell : episodeCells) {
            if (!(cell instanceof TypeCell)) {
                continue;
            }
            if (cell.blockEntity) {
                beWall += cell.episodeWall;
                beCpu += cell.episodeCpu;
            } else {
                entWall += cell.episodeWall;
                entCpu += cell.episodeCpu;
            }
        }
        return new Episode(episodeEnteredTick, episodeWall, episodeCpu, episodePark,
            beWall, beCpu, entWall, entCpu, gcCollections - episodeGcCollections,
            gcMillis - episodeGcMillis, liveDumps - episodeLiveDumps,
            top(episodeCells, KIND_TYPE, EPISODE_TOP, cell -> cell.episodeWall,
                StallAttributionObserver::episodeRow),
            top(episodeCells, KIND_MOD, EPISODE_TOP, cell -> cell.episodeWall,
                StallAttributionObserver::episodeRow),
            top(episodeCells, KIND_COLONY, EPISODE_TOP, cell -> cell.episodeWall,
                StallAttributionObserver::episodeRow));
    }

    private void closeEpisode(long gcCollections, long gcMillis) {
        episodeOpen = false;
        Episode episode = buildEpisode(gcCollections, gcMillis);
        episodes.addLast(episode);
        while (episodes.size() > EPISODE_RING) {
            episodes.pollFirst();
        }
        LOGGER.warn("[PRTS-observe] busy stall closed tick={} wall={}ms cpu={}ms park={}ms"
                + " block-entities={}ms entities={}ms gc={}ms top={}", episodeEnteredTick,
            episode.wallNanos() / 1_000_000L, episode.cpuNanos() / 1_000_000L,
            episode.parkNanos() / 1_000_000L, episode.blockEntityNanos() / 1_000_000L,
            episode.entityNanos() / 1_000_000L, episode.gcMillis(),
            joinTop(episode.topTypes()));
        for (Cell cell : episodeCells) {
            cell.clearEpisode();
        }
        episodeCells.clear();
    }

    private void recordLongTick(Instance instance, long wall, long cpu) {
        String stack = "-";
        long now = System.nanoTime();
        if (longTicks.size() < LONG_RING && now - lastLongStackNanos >= 1_000_000_000L) {
            lastLongStackNanos = now;
            stack = topFrames(new Throwable().getStackTrace(), 6);
        }
        longTicks.addLast(new LongTick(instance.type.blockEntity, instance.type.key,
            instance.mod.key, instance.colony.key, instance.label, wall, cpu, stack));
        while (longTicks.size() > LONG_RING) {
            longTicks.pollFirst();
        }
    }

    /** Writes one line while a tick that has already attributed several seconds still runs, so a
     * stall longer than the readout window leaves a trail in the console even if no export is
     * answered. The threshold and the interval are declared values. */
    private void maybeLiveDump() {
        if (liveTickWall < LIVE_DUMP_AFTER_NANOS) {
            return;
        }
        long now = System.nanoTime();
        if (lastLiveDumpNanos != 0L && now - lastLiveDumpNanos < LIVE_DUMP_EVERY_NANOS) {
            return;
        }
        lastLiveDumpNanos = now;
        liveDumps++;
        LOGGER.warn("[PRTS-observe] stall in progress tick attributed={}ms peak={}ms top={}",
            liveTickWall / 1_000_000L, liveTickPeak / 1_000_000L,
            joinTop(top(tickCells, KIND_TYPE, 5, cell -> cell.tickWall,
                StallAttributionObserver::tickRow)));
    }

    public List<Row> types(boolean blockEntity, int limit) {
        return top(face(blockEntity).types.values(), KIND_TYPE, limit, cell -> cell.wallNanos);
    }

    public List<Row> mods(boolean blockEntity) {
        return top(face(blockEntity).mods.values(), KIND_MOD, TYPE_TOP, cell -> cell.wallNanos);
    }

    public List<Row> colonies(boolean blockEntity) {
        return top(face(blockEntity).colonies.values(), KIND_COLONY, TYPE_TOP, cell -> cell.wallNanos);
    }

    public long totalWall(boolean blockEntity) {
        return face(blockEntity).totalWall();
    }

    public long totalCpu(boolean blockEntity) {
        return face(blockEntity).totalCpu();
    }

    public long totalTicks(boolean blockEntity) {
        return face(blockEntity).totalTicks();
    }

    public long totalInstances(boolean blockEntity) {
        return face(blockEntity).totalInstances();
    }

    public int typeCount(boolean blockEntity) {
        return face(blockEntity).types.size();
    }

    public long longTickCount() {
        return longTicks.size();
    }

    /** @return the newest long ticks first */
    public List<LongTick> longTicks() {
        List<LongTick> newest = new ArrayList<>(longTicks);
        newest.sort(Comparator.comparingLong(LongTick::wallNanos).reversed());
        return newest;
    }

    public long episodeCount() {
        return episodeCount;
    }

    public long liveDumps() {
        return liveDumps;
    }

    /** @return the newest closed episodes first */
    public List<Episode> episodes() {
        List<Episode> newest = new ArrayList<>(episodes);
        newest.sort(Comparator.comparingLong(Episode::tick).reversed());
        return newest;
    }

    private Face face(boolean blockEntity) {
        return blockEntity ? blockEntities : entities;
    }

    private static List<Row> top(java.util.Collection<? extends Cell> cells, String kind, int limit,
                                 ToLongFunction<Cell> measure) {
        return top(cells, kind, limit, measure, Cell::row);
    }

    private static List<Row> top(java.util.Collection<? extends Cell> cells, String kind, int limit,
                                 ToLongFunction<Cell> measure,
                                 java.util.function.Function<Cell, Row> mapper) {
        List<Cell> wanted = new ArrayList<>();
        for (Cell cell : cells) {
            if (kind.equals(cell.kind)) {
                wanted.add(cell);
            }
        }
        wanted.sort(Comparator.comparingLong(measure).reversed());
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < wanted.size() && index < limit; index++) {
            rows.add(mapper.apply(wanted.get(index)));
        }
        return rows;
    }

    /** A row of the episode in progress: its span is the episode's, not the instance's total. */
    private static Row episodeRow(Cell cell) {
        return new Row(cell.key, cell.episodeTicks, cell.episodeWall, cell.episodeCpu,
            cell instanceof TypeCell type ? type.instanceCount : 0L,
            cell instanceof TypeCell type ? type.maxInstanceNanos : 0L,
            cell instanceof TypeCell type ? type.worst : "-");
    }

    /** A row of the tick in progress: its span is that tick's, not the instance's total. */
    private static Row tickRow(Cell cell) {
        return new Row(cell.key, cell.tickTicks, cell.tickWall, cell.tickCpu, 0L, 0L, "-");
    }

    private static String joinTop(List<Row> rows) {
        if (rows.isEmpty()) {
            return "-";
        }
        StringBuilder builder = new StringBuilder();
        for (Row row : rows) {
            if (builder.length() > 0) {
                builder.append(',');
            }
            builder.append(row.key()).append(':').append(row.wallNanos() / 1_000_000L);
        }
        return builder.toString();
    }

    public synchronized void reset() {
        tickCells.clear();
        episodeCells.clear();
        longTicks.clear();
        episodes.clear();
        liveTickWall = 0L;
        liveTickPeak = 0L;
        lastLiveDumpNanos = 0L;
        lastLongStackNanos = 0L;
        episodeWall = 0L;
        episodeCpu = 0L;
        episodePark = 0L;
        episodeGcCollections = 0L;
        episodeGcMillis = 0L;
        episodeLiveDumps = 0L;
        episodeEnteredTick = 0L;
        episodeCount = 0L;
        liveDumps = 0L;
        episodeOpen = false;
        previousGcCollections = 0L;
        previousGcMillis = 0L;
        instancesCapped = false;
        widenedRows.reset();
        singletonRows.reset();
        structureThirdRows.reset();
        regionlessRows.reset();
        bucketCount.reset();
        batchBucketCount.reset();
        foldedTicks.reset();
        tickBatches.clear();
        pendingRows = 0L;
        pendingStructureThird = 0;
        pendingRegionless = 0;
        resetFace(blockEntities);
        resetFace(entities);
    }

    private static void resetFace(Face face) {
        face.types.clear();
        face.mods.clear();
        face.colonies.clear();
        face.instances.clear();
        face.segmentWall = 0L;
    }

    private static String colonyKey(int colony) {
        return colony < 0 ? "none" : Integer.toString(colony);
    }

    private static String blockEntityType(Object ticker) {
        Object type = invokeNoArg(ticker, "getType");
        return type == null ? simpleName(ticker.getClass()) : String.valueOf(type);
    }

    private static String entityType(Object entity) {
        return simpleName(entity.getClass());
    }

    private static String simpleName(Class<?> type) {
        String name = type.getSimpleName();
        return name.isEmpty() ? type.getName() : name;
    }

    private static String modOfTypeKey(String typeKey) {
        int colon = typeKey.indexOf(':');
        if (colon > 0 && colon < typeKey.length() - 1) {
            return typeKey.substring(0, colon);
        }
        return "unknown";
    }

    private static Object positionOf(Object identity) {
        Object position = invokeNoArg(identity, "getPos");
        return position != null ? position : invokeNoArg(identity, "blockPosition");
    }

    private static int intOf(Object target, String name) {
        Object value = invokeNoArg(target, name);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static Object invokeNoArg(Object target, String name) {
        Class<?> type = target.getClass();
        Object accessor = ACCESSORS.computeIfAbsent(type.getName() + '#' + name, ignored -> {
            try {
                Method method = type.getMethod(name);
                method.setAccessible(true);
                return method;
            } catch (Throwable failure) {
                return MISSING;
            }
        });
        if (accessor == MISSING) {
            return null;
        }
        try {
            return ((Method) accessor).invoke(target);
        } catch (Throwable failure) {
            return null;
        }
    }

    private static String topFrames(StackTraceElement[] frames, int limit) {
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < frames.length && index < limit; index++) {
            if (builder.length() > 0) {
                builder.append(" | ");
            }
            builder.append(frames[index].getClassName()).append('.')
                .append(frames[index].getMethodName());
        }
        return builder.length() == 0 ? "-" : builder.toString();
    }

    /** Resolves the mod of a class: the loader's mod list first, then the archive it came from. */
    static final class ModIndex {

        private final ClassValue<String> perClass = new ClassValue<>() {
            @Override
            protected String computeValue(Class<?> type) {
                return resolve(type);
            }
        };
        private volatile Map<String, String> jarToMod = Map.of();
        private volatile boolean fmlLoaded;
        private volatile boolean fmlResolved;

        String modOf(Class<?> type) {
            return type == null ? "unknown" : perClass.get(type);
        }

        boolean fmlResolved() {
            return fmlResolved;
        }

        private String resolve(Class<?> type) {
            String name = type.getName();
            if (name.startsWith("net.minecraft.")) {
                return "minecraft";
            }
            if (name.startsWith("io.izzel.arclight.")) {
                return "arclight";
            }
            String jar = sourceJar(type);
            if (jar == null) {
                return packageRoot(name);
            }
            String mod = loadFml().get(jar);
            if (mod != null) {
                fmlResolved = true;
                return mod;
            }
            return baseName(jar);
        }

        private Map<String, String> loadFml() {
            if (fmlLoaded) {
                return jarToMod;
            }
            fmlLoaded = true;
            Map<String, String> found = new java.util.HashMap<>();
            try {
                Class<?> modListType = Class.forName("net.neoforged.fml.ModList");
                Object modList = modListType.getMethod("get").invoke(null);
                for (Object info : (List<?>) modListType.getMethod("getMods").invoke(modList)) {
                    Object fileInfo = info.getClass().getMethod("getOwningFile").invoke(info);
                    Object modFile = fileInfo.getClass().getMethod("getFile").invoke(fileInfo);
                    Object path = modFile.getClass().getMethod("getFilePath").invoke(modFile);
                    String id = (String) info.getClass().getMethod("getModId").invoke(info);
                    if (path instanceof java.nio.file.Path file && id != null) {
                        found.put(file.getFileName().toString(), id);
                    }
                }
            } catch (Throwable ignored) {
                // No loader list: the archive name is the fallback and the readout says so.
            }
            jarToMod = Map.copyOf(found);
            return jarToMod;
        }

        private static String sourceJar(Class<?> type) {
            try {
                ProtectionDomain domain = type.getProtectionDomain();
                CodeSource source = domain == null ? null : domain.getCodeSource();
                URL location = source == null ? null : source.getLocation();
                if (location == null) {
                    return null;
                }
                String text = location.toString();
                int end = text.indexOf(".jar");
                if (end < 0) {
                    return null;
                }
                int start = text.lastIndexOf('/', end);
                return start < 0 ? null : text.substring(start + 1, end + 4);
            } catch (Throwable failure) {
                return null;
            }
        }

        private static String baseName(String jar) {
            String base = jar.endsWith(".jar") ? jar.substring(0, jar.length() - 4) : jar;
            int cut = base.length();
            for (int index = 0; index < base.length(); index++) {
                char character = base.charAt(index);
                if (character == ' ') {
                    cut = index;
                    break;
                }
                if (character == '-' && index + 1 < base.length()
                    && Character.isDigit(base.charAt(index + 1))) {
                    cut = index;
                    break;
                }
            }
            String name = base.substring(0, cut);
            return name.isEmpty() ? "unknown" : name;
        }

        private static String packageRoot(String name) {
            int first = name.indexOf('.');
            if (first < 0) {
                return "unknown";
            }
            int second = name.indexOf('.', first + 1);
            return second < 0 ? name : name.substring(0, second);
        }
    }

    /** Looks a position up in the colony claims of the loaded save, when the mod that owns them is
     * present. The lookup is cached per chunk, because a claim is a chunk property. */
    static final class ColonyIndex {

        private final Method instanceMethod;
        private final Method byPositionMethod;
        private final Method idMethod;
        private final Map<String, Map<Long, Integer>> byChunk = new ConcurrentHashMap<>();
        private volatile int cacheClears;

        ColonyIndex() {
            Method instance = null;
            Method byPosition = null;
            Method id = null;
            try {
                Class<?> manager = Class.forName("com.minecolonies.api.colony.IColonyManager");
                Class<?> level = Class.forName("net.minecraft.world.level.Level");
                Class<?> position = Class.forName("net.minecraft.core.BlockPos");
                Class<?> colony = Class.forName("com.minecolonies.api.colony.IColony");
                instance = manager.getMethod("getInstance");
                byPosition = manager.getMethod("getColonyByPosFromWorld", level, position);
                id = colony.getMethod("getID");
            } catch (Throwable ignored) {
                // The mod is absent or its api moved: every position answers "no colony".
            }
            this.instanceMethod = instance;
            this.byPositionMethod = byPosition;
            this.idMethod = id;
        }

        boolean available() {
            return byPositionMethod != null;
        }

        int cacheClears() {
            return cacheClears;
        }

        int colonyOf(Object level, String worldId, Object position, int x, int z) {
            if (byPositionMethod == null || level == null || position == null) {
                return -1;
            }
            Map<Long, Integer> chunks = byChunk.computeIfAbsent(worldId == null ? "-" : worldId,
                key -> new ConcurrentHashMap<>());
            long key = (((long) (x >> 4)) << 32) ^ ((z >> 4) & 0xffffffffL);
            Integer cached = chunks.get(key);
            if (cached != null) {
                return cached;
            }
            if (chunks.size() >= COLONY_CACHE_CAP) {
                chunks.clear();
                cacheClears++;
            }
            int colony = lookup(level, position);
            chunks.put(key, colony);
            return colony;
        }

        private int lookup(Object level, Object position) {
            try {
                Object manager = instanceMethod.invoke(null);
                if (manager == null) {
                    return -1;
                }
                Object colony = byPositionMethod.invoke(manager, level, position);
                if (colony == null) {
                    return -1;
                }
                Object id = idMethod.invoke(colony);
                return id instanceof Number number ? number.intValue() : -1;
            } catch (Throwable failure) {
                return -1;
            }
        }
    }
}
