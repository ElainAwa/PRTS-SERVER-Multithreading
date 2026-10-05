/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.kernel.auth.WriteLedger;
import io.izzel.arclight.common.prts.kernel.diff.DomainHash;
import io.izzel.arclight.common.prts.kernel.diff.HashWhitelist;
import io.izzel.arclight.common.prts.kernel.diff.StateHasher;
import io.izzel.arclight.common.prts.support.PrtsPipelineRows;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.thread.ProcessorMailbox;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongPredicate;

/**
 * Folds what one tick of the chunk pipeline put into effect, per world and per probe, into the same
 * state digest the rest of the kernel uses.
 *
 * <p>A probe is one of the three load classes of the chunk pipeline batch interface: the generation
 * and materialization mailbox, the light and height map mailbox, and the block write boundary. For
 * every (tick, world, probe) the digest folds one row of eight values, so two runs of one scenario
 * can be compared tick by tick, world by world and probe by probe.
 *
 * <p>The row counts come from the mailbox rows the pipeline itself reports and from the write
 * ledger of the world write guard. Both are counted where the host runs them; this observer
 * cancels, delays and reorders nothing, and it holds no state a decision reads.
 *
 * <p><b>Off unless a window is declared.</b> The only way to arm it is the JVM property
 * {@code arclight.prts.digestWindow=<ticks>}; no configuration file, no reload and no command
 * reaches it, and the window only holds the newest {@code <ticks>} ticks. A process that declares
 * nothing installs no tap, folds no row and writes no reading of its own.
 *
 * <p>Every value of a row is a count or a raw bit pattern, never a wall clock and never a queue
 * depth: a digest of a schedule would differ between two runs of one scenario and could not serve
 * as the reference a controlled takeover arm is read against.
 */
public final class TickDigestObserver implements PrtsPipelineRows.MailboxOwnerTap {

    /** How many ticks of digest the window keeps; zero disarms the observer. */
    public static final String WINDOW_KEY = "arclight.prts.digestWindow";

    /** Which worlds the window folds, comma separated; empty folds every world the server has. */
    public static final String WORLDS_KEY = "arclight.prts.digestWorlds";

    /** The generation and materialization probe; the row unit of this probe is one mailbox task. */
    public static final String PROBE_CHUNK = "chunk";

    /** The light and height map probe; the row unit of this probe is one mailbox task. */
    public static final String PROBE_LIGHT = "light";

    /** The block write boundary probe; the row unit of this probe is one write attempt. */
    public static final String PROBE_WRITE = "write";

    /** The three probes, in the order their rows are folded and numbered. */
    public static final String[] PROBES = {PROBE_CHUNK, PROBE_LIGHT, PROBE_WRITE};

    /** The region each probe is anchored at, in the region id form the region identity publishes. */
    private static final String[] ANCHORS = {"r.0.3", "r.-2.-1", "r.-2.0"};

    /** The mailbox name that feeds each probe; the write probe has no mailbox. */
    private static final String[] MAILBOXES = {"worldgen", "light", ""};

    /** How many ticks between two reads of the mailbox to world placement. The placement of a world
     * is fixed once its chunk map exists, so a slow refresh cannot move a row between worlds. */
    private static final int PLACEMENT_REFRESH_TICKS = 20;

    /** The upper bound of the window, so a mistyped declaration cannot grow without end. */
    private static final int WINDOW_MAX = 4_096;

    /** One folded row: the tick, the identity of the probe, the eight values with their exact bits,
     * and the value the fold produced. The bits are what a comparison reads; the decimal form of a
     * double loses them. */
    public record Row(long tick, String world, String probe, int probeIndex, String regionId,
                      long entitySeq, long rows, long rounds, long rowsTotal, long roundsTotal,
                      long ticksActive, long tickFirst, long rowsPeak, long seen, long value,
                      String algorithmId, long headerDigest, long rowDigest) {
    }

    private final Map<String, LongAdder> tickRows = new ConcurrentHashMap<>();
    private final Map<String, LongAdder> tickRounds = new ConcurrentHashMap<>();
    private final Map<String, long[]> tallies = new TreeMap<>();
    private final Map<Long, List<Row>> window = new LinkedHashMap<>();
    private final TreeSet<String> worldSet = new TreeSet<>();
    private volatile Map<Object, String> placement = Map.of();
    private volatile MinecraftServer source;
    private volatile WriteLedger ledger;
    private volatile Map<String, long[]> writeSeen = Map.of();
    private final int declaredWindow;
    private final List<String> declaredWorlds;
    private long lastPlacementTick = Long.MIN_VALUE;
    private volatile LongPredicate deviation = tick -> false;
    private long ticks;
    private long rowsFolded;
    private long unplaced;
    private long otherMailbox;
    private long droppedTicks;
    private long deviated;
    private long firstTick = -1L;
    private long lastTick = -1L;
    private boolean attached;

    public TickDigestObserver() {
        this(readInt(System.getProperty(WINDOW_KEY), 0), readWorlds(System.getProperty(WORLDS_KEY)));
    }

    TickDigestObserver(int window, List<String> worlds) {
        this.declaredWindow = Math.max(0, Math.min(WINDOW_MAX, window));
        this.declaredWorlds = List.copyOf(worlds);
    }

    private static int readInt(String text, int fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(text.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static List<String> readWorlds(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> worlds = new ArrayList<>();
        for (String part : text.split(",")) {
            String world = part.trim();
            if (!world.isEmpty()) {
                worlds.add(world);
            }
        }
        return worlds;
    }

    /** Whether this process declared a digest window at all. */
    public boolean armed() {
        return declaredWindow > 0;
    }

    public int windowTicks() {
        return declaredWindow;
    }

    /** Remembers the server the placement and the world set are read from. */
    public void source(MinecraftServer server) {
        this.source = server;
    }

    /** Remembers the write ledger the write probe is sampled from. */
    public void ledger(WriteLedger writeLedger) {
        this.ledger = writeLedger;
    }

    /** The negative fixture of the two run comparison: a predicate that answers whether the row of
     * one tick has to carry a deviated value. It is off unless a process declares it. */
    public void deviation(LongPredicate declared) {
        this.deviation = declared == null ? tick -> false : declared;
    }

    /** Installs the owner aware face of the mailbox seam; a no-op unless a window is declared. */
    public synchronized void attach() {
        if (!armed() || attached) {
            return;
        }
        attached = true;
        PrtsPipelineRows.installOwnerTap(this);
    }

    /** Removes the owner aware face, unless somebody else owns it by now. */
    public synchronized void detach() {
        attached = false;
        if (PrtsPipelineRows.ownerTapInstalled()) {
            PrtsPipelineRows.installOwnerTap(null);
        }
        PrtsPipelineRows.bindMailboxWorlds(Map.of());
        placement = Map.of();
    }

    @Override
    public void ownerTask(String world, String mailbox) {
        note(world, probeOf(mailbox), true);
    }

    @Override
    public void ownerRound(String world, String mailbox) {
        note(world, probeOf(mailbox), false);
    }

    private void note(String world, String probe, boolean task) {
        if (probe == null) {
            otherMailbox++;
            return;
        }
        if (PrtsPipelineRows.UNPLACED_WORLD.equals(world)) {
            unplaced++;
            return;
        }
        String key = world + "|" + probe;
        if (task) {
            tickRows.computeIfAbsent(key, unused -> new LongAdder()).increment();
        } else {
            tickRounds.computeIfAbsent(key, unused -> new LongAdder()).increment();
        }
    }

    private static String probeOf(String mailbox) {
        if (mailbox != null) {
            for (int index = 0; index < PROBES.length; index++) {
                if (MAILBOXES[index].equals(mailbox)) {
                    return PROBES[index];
                }
            }
        }
        return null;
    }

    /**
     * Closes one tick: folds one row per (world, probe) and keeps the newest
     * {@link #windowTicks()} ticks.
     *
     * @param tick the tick index the server closed
     */
    public void noteTick(long fallbackTick) {
        if (!armed() || !attached) {
            return;
        }
        long tick = gameTime(fallbackTick);
        ticks++;
        if ((tick - lastPlacementTick) >= PLACEMENT_REFRESH_TICKS || placement.isEmpty()) {
            lastPlacementTick = tick;
            refreshPlacement();
        }
        List<String> worlds = liveWorlds();
        if (worlds.isEmpty()) {
            droppedTicks++;
            tickRows.clear();
            tickRounds.clear();
            return;
        }
        Map<String, long[]> writes = writeDeltas();
        List<Row> folded = new ArrayList<>(worlds.size() * PROBES.length);
        for (String world : worlds) {
            for (int index = 0; index < PROBES.length; index++) {
                String probe = PROBES[index];
                String key = world + "|" + probe;
                long rows = take(tickRows, key);
                long rounds = take(tickRounds, key);
                if (PROBE_WRITE.equals(probe)) {
                    long[] pair = writes.get(world);
                    rows = pair == null ? 0L : pair[0];
                    rounds = pair == null ? 0L : pair[1];
                }
                folded.add(fold(tick, world, probe, index, rows, rounds));
            }
        }
        tickRows.clear();
        tickRounds.clear();
        if (firstTick < 0L) {
            firstTick = tick;
        }
        lastTick = tick;
        rowsFolded += folded.size();
        worldSet.addAll(worlds);
        window.put(tick, folded);
        while (window.size() > declaredWindow) {
            Iterator<Long> oldest = window.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
    }

    private long take(Map<String, LongAdder> cells, String key) {
        LongAdder cell = cells.remove(key);
        if (cell == null) {
            return 0L;
        }
        return cell.sum();
    }

    private Row fold(long tick, String world, String probe, int index, long rows, long rounds) {
        String key = world + "|" + probe;
        long[] tally = tallies.computeIfAbsent(key, unused -> new long[]{0L, 0L, 0L, -1L, 0L});
        tally[0] += rows;
        tally[1] += rounds;
        long rowsTotalHere = tally[0];
        long roundsTotalHere = tally[1];
        if (rows > 0L) {
            tally[2]++;
            if (tally[3] < 0L) {
                tally[3] = tick;
            }
            if (rows > tally[4]) {
                tally[4] = rows;
            }
        }
        long ticksActive = tally[2];
        long tickFirst = tally[3];
        long rowsPeak = tally[4];
        long seen = ticksActive > 0L ? 1L : 0L;
        long effectiveRows = rows;
        if (deviation.test(tick)) {
            effectiveRows = rows + 1L;
            deviated++;
        }
        long entitySeq = rowsTotalHere;
        StateHasher.Slice slice = new StateHasher.Slice(world, ANCHORS[index], index, entitySeq,
            effectiveRows, rounds, rowsTotalHere, roundsTotalHere, ticksActive, tickFirst, rowsPeak,
            seen, 0L, tick, index);
        DomainHash hash = StateHasher.hash(probe, tick, List.of(slice), HashWhitelist.bitexact());
        return new Row(tick, world, probe, index, ANCHORS[index], entitySeq, effectiveRows, rounds,
            rowsTotalHere, roundsTotalHere, ticksActive, tickFirst, rowsPeak, seen, hash.value(),
            hash.algorithmId(), hash.segmentHeaderDigest(), hash.rows().rowDigests()[0]);
    }

    /**
     * The clock a row is keyed by: the game time of the overworld, which two runs of one world
     * snapshot share. The tick counter of the process is not that clock - it starts at zero on
     * every boot, so it would key the same scenario to different ticks in two runs and the
     * comparison would have nothing to line up.
     */
    private long gameTime(long fallback) {
        MinecraftServer server = source;
        if (server == null) {
            return fallback;
        }
        try {
            return server.overworld().getGameTime();
        } catch (Throwable failure) {
            return fallback;
        }
    }

    /** The worlds this tick folds: every world the server has, or the declared subset of them. */
    private List<String> liveWorlds() {
        MinecraftServer server = source;
        if (server == null) {
            return declaredWorlds;
        }
        List<String> worlds = new ArrayList<>();
        try {
            for (ServerLevel level : server.getAllLevels()) {
                String world = level.dimension().location().toString();
                if (declaredWorlds.isEmpty() || declaredWorlds.contains(world)) {
                    worlds.add(world);
                }
            }
        } catch (Throwable failure) {
            return List.of();
        }
        worlds.sort(null);
        return worlds;
    }

    /** Reads the block write ledger once and answers the attempt and grant deltas per world. */
    private Map<String, long[]> writeDeltas() {
        WriteLedger writeLedger = ledger;
        if (writeLedger == null) {
            return Map.of();
        }
        Map<String, long[]> totals = new TreeMap<>();
        try {
            for (Map.Entry<String, WriteLedger.Pair> entry : writeLedger.pairs().entrySet()) {
                int cut = entry.getKey().indexOf('|');
                if (cut <= 0) {
                    continue;
                }
                String world = entry.getKey().substring(0, cut);
                long[] sum = totals.computeIfAbsent(world, unused -> new long[2]);
                sum[0] += entry.getValue().attempts();
                sum[1] += entry.getValue().granted();
            }
        } catch (Throwable failure) {
            return Map.of();
        }
        Map<String, long[]> deltas = new TreeMap<>();
        for (Map.Entry<String, long[]> entry : totals.entrySet()) {
            long[] before = writeSeen.get(entry.getKey());
            long[] now = entry.getValue();
            deltas.put(entry.getKey(), new long[]{
                before == null ? now[0] : now[0] - before[0],
                before == null ? now[1] : now[1] - before[1]});
        }
        writeSeen = Map.copyOf(totals);
        return deltas;
    }

    /**
     * Places every mailbox of every world on its world. The chunk map of a world owns the mailboxes
     * of its chunk pipeline; a mailbox that is not reachable this way stays unplaced and its rows
     * are counted apart instead of being folded into a world nobody read.
     */
    private void refreshPlacement() {
        MinecraftServer server = source;
        if (server == null) {
            return;
        }
        Map<Object, String> placed = new java.util.IdentityHashMap<>();
        try {
            for (ServerLevel level : server.getAllLevels()) {
                String world = level.dimension().location().toString();
                Object chunkMap = level.getChunkSource().chunkMap;
                for (Object mailbox : mailboxesOf(chunkMap)) {
                    placed.put(mailbox, world);
                }
            }
        } catch (Throwable failure) {
            return;
        }
        placement = Map.copyOf(placed);
        PrtsPipelineRows.bindMailboxWorlds(placement);
    }

    /** The mailboxes one chunk map owns: the two named ones the sorter holds, plus its own sorter
     * mailbox. Reached reflectively so this observer needs no accessor and no hook of its own. */
    private static List<Object> mailboxesOf(Object chunkMap) {
        List<Object> found = new ArrayList<>(3);
        Object sorter = field(chunkMap, "queueSorter");
        if (sorter == null) {
            return found;
        }
        Object queues = field(sorter, "queues");
        if (queues instanceof Map<?, ?> map) {
            for (Object handle : map.keySet()) {
                if (handle instanceof ProcessorMailbox<?>) {
                    found.add(handle);
                }
            }
        }
        Object sorterMailbox = field(sorter, "mailbox");
        if (sorterMailbox instanceof ProcessorMailbox<?>) {
            found.add(sorterMailbox);
        }
        return found;
    }

    private static Object field(Object owner, String name) {
        try {
            Field resolved = owner.getClass().getDeclaredField(name);
            resolved.setAccessible(true);
            return resolved.get(owner);
        } catch (Throwable failure) {
            return null;
        }
    }

    /** The newest {@link #windowTicks()} ticks, oldest first. */
    public List<Row> rows() {
        List<Row> all = new ArrayList<>();
        for (List<Row> tick : window.values()) {
            all.addAll(tick);
        }
        return all;
    }

    public List<String> worlds() {
        return List.copyOf(worldSet);
    }

    public long ticks() {
        return window.size();
    }

    public long foldedTicks() {
        return ticks;
    }

    /** @return how many (tick, world, probe) rows the window folded, evicted ticks included */
    public long rowsFolded() {
        return rowsFolded;
    }

    public long unplacedRows() {
        return unplaced;
    }

    public long otherMailboxRows() {
        return otherMailbox;
    }

    public long droppedTicks() {
        return droppedTicks;
    }

    public long deviatedRows() {
        return deviated;
    }

    public long firstTick() {
        return firstTick;
    }

    public long lastTick() {
        return lastTick;
    }

    public int placedMailboxes() {
        return placement.size();
    }

    /** Clears the window and every counter but keeps the server and the ledger. */
    public synchronized void reset() {
        tickRows.clear();
        tickRounds.clear();
        tallies.clear();
        window.clear();
        worldSet.clear();
        writeSeen = Map.of();
        ticks = 0L;
        rowsFolded = 0L;
        unplaced = 0L;
        otherMailbox = 0L;
        droppedTicks = 0L;
        deviated = 0L;
        firstTick = -1L;
        lastTick = -1L;
        lastPlacementTick = Long.MIN_VALUE;
    }
}
