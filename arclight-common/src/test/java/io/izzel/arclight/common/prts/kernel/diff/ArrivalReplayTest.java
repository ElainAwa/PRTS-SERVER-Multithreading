/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.diff;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Reads the arrival face of two runs of one scenario, picks the ticks the two runs were handed the
 * same arrival on, and reads the execution face on those ticks.
 *
 * <p>Nothing here compares by a rule of its own: a row is folded and compared by
 * {@link TickDigestReplayTest}, the same reader, the same {@link StateHasher} and the same
 * {@link DiffProbe} the kernel uses, and a single tick is asked through the same comparison the
 * whole scope is asked through. What this class adds is which ticks to ask about.
 *
 * <p>Three answers are never softened: a scope that holds no row is refused, a row only one run has
 * is refused, and a row that does not fold back to the value its run exported is an error.
 */
class ArrivalReplayTest {

    private static final Path DECLARATION = Path.of("/tmp/prts-arrival-paths.txt");

    private static final String[] PROBES = {"chunk", "light", "write"};

    /** The ticks two runs were handed the same arrival on: every row of the tick is paired and
     * equal. A tick whose rows are not all paired is not shared either. */
    static List<Long> sharedArrivalTicks(Map<String, StateHasher.Slice> runA,
                                         Map<String, StateHasher.Slice> runB) throws IOException {
        List<Long> shared = new ArrayList<>();
        for (long tick : commonTicks(runA, runB)) {
            TickDigestReplayTest.Result one =
                TickDigestReplayTest.compare(atTick(runA, tick), atTick(runB, tick), null,
                    "tick=" + tick);
            if (one.pairs() > 0L && one.equal() == one.pairs() && one.unpaired().isEmpty()) {
                shared.add(tick);
            }
        }
        return shared;
    }

    /** The first tick the two runs were handed a different arrival on, or -1 when there is none. */
    static long firstArrivalFork(Map<String, StateHasher.Slice> runA,
                                 Map<String, StateHasher.Slice> runB) throws IOException {
        for (long tick : commonTicks(runA, runB)) {
            TickDigestReplayTest.Result one =
                TickDigestReplayTest.compare(atTick(runA, tick), atTick(runB, tick), null,
                    "tick=" + tick);
            if (!(one.pairs() > 0L && one.equal() == one.pairs() && one.unpaired().isEmpty())) {
                return tick;
            }
        }
        return -1L;
    }

    /** The (tick, world) cells two runs were handed the same arrival in: every arrival row of
     * that world on that tick is paired and equal. It is the same question the whole tick is asked,
     * at one world's address instead of all of them at once, so it is a weaker scope and is stated
     * as one wherever it is used. */
    static List<String> sharedArrivalCells(Map<String, StateHasher.Slice> runA,
                                           Map<String, StateHasher.Slice> runB) throws IOException {
        List<String> shared = new ArrayList<>();
        for (long tick : commonTicks(runA, runB)) {
            for (String world : worldsOf(runA, tick)) {
                Map<String, StateHasher.Slice> left = atCell(runA, tick, world);
                if (left.isEmpty() || atCell(runB, tick, world).isEmpty()) {
                    continue;
                }
                TickDigestReplayTest.Result one = TickDigestReplayTest.compare(left,
                    atCell(runB, tick, world), null, "cell=" + tick + "|" + world);
                if (one.pairs() > 0L && one.equal() == one.pairs() && one.unpaired().isEmpty()) {
                    shared.add(tick + "|" + world);
                }
            }
        }
        return shared;
    }

    /** The worlds one run folded a row for on one tick. */
    static List<String> worldsOf(Map<String, StateHasher.Slice> rows, long tick) {
        List<String> worlds = new ArrayList<>();
        String prefix = tick + "|";
        for (String key : rows.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String world = key.split("\\|", -1)[1];
            if (!worlds.contains(world)) {
                worlds.add(world);
            }
        }
        return worlds;
    }

    /** The rows of one (tick, world) cell, whatever row family the dump holds. */
    static Map<String, StateHasher.Slice> atCell(Map<String, StateHasher.Slice> rows, long tick,
                                                 String world) {
        return atKeyPrefix(rows, tick + "|" + world + "|");
    }

    private static Map<String, StateHasher.Slice> atKeyPrefix(Map<String, StateHasher.Slice> rows,
                                                              String prefix) {
        Map<String, StateHasher.Slice> selected = new TreeMap<>();
        for (Map.Entry<String, StateHasher.Slice> entry : rows.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                selected.put(entry.getKey(), entry.getValue());
            }
        }
        return selected;
    }

    /** Whether a cell's execution rows carry any work: the cumulative row or round count of the
     * probe is above zero. A cell that carries none is a degenerate pair and proves nothing. */
    static boolean carriesContent(Map<String, StateHasher.Slice> rows) {
        for (StateHasher.Slice slice : rows.values()) {
            if (slice.z() > 0.0 || slice.yaw() > 0.0) {
                return true;
            }
        }
        return false;
    }

    /** The rows of the named probes that sit on one of the given ticks. */
    static Map<String, StateHasher.Slice> atTicks(Map<String, StateHasher.Slice> rows,
                                                  List<Long> ticks, String[] probes) {
        java.util.Set<Long> wanted = new TreeSet<>(ticks);
        Map<String, StateHasher.Slice> selected = new TreeMap<>();
        for (Map.Entry<String, StateHasher.Slice> entry : rows.entrySet()) {
            String[] parts = entry.getKey().split("\\|", -1);
            if (!wanted.contains(Long.parseLong(parts[0]))) {
                continue;
            }
            for (String probe : probes) {
                if (probe.equals(parts[2])) {
                    selected.put(entry.getKey(), entry.getValue());
                }
            }
        }
        return selected;
    }

    /** The rows of one run with the first row of one tick moved: the negative control of this
     * reader, applied to a real dump, folded by the same rule the reader folds every other row by. */
    static Map<String, StateHasher.Slice> moved(Map<String, StateHasher.Slice> rows, long tick) {
        Map<String, StateHasher.Slice> movedRows = new TreeMap<>(rows);
        for (Map.Entry<String, StateHasher.Slice> entry : movedRows.entrySet()) {
            if (!entry.getKey().startsWith(tick + "|")) {
                continue;
            }
            StateHasher.Slice slice = entry.getValue();
            movedRows.put(entry.getKey(), new StateHasher.Slice(slice.worldId(), slice.regionId(),
                slice.batchId(), slice.entitySeq(), slice.x() + 1.0, slice.y(), slice.z(),
                slice.yaw(), slice.pitch(), slice.velX(), slice.velY(), slice.velZ(),
                slice.flags(), slice.slotGeneration(), slice.segmentRef()));
            break;
        }
        return movedRows;
    }

    private static Map<String, StateHasher.Slice> atTick(Map<String, StateHasher.Slice> rows,
                                                         long tick) {
        Map<String, StateHasher.Slice> selected = new TreeMap<>();
        String prefix = tick + "|";
        for (Map.Entry<String, StateHasher.Slice> entry : rows.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                selected.put(entry.getKey(), entry.getValue());
            }
        }
        return selected;
    }

    /** The ticks both runs folded a row for: a tick only one of them reached is not a tick the
     * two can be asked about, and asking about it would be an empty scope and not an answer. */
    static List<Long> commonTicks(Map<String, StateHasher.Slice> runA,
                                  Map<String, StateHasher.Slice> runB) {
        java.util.Set<Long> right = new TreeSet<>(ticksOf(runB));
        List<Long> both = new ArrayList<>();
        for (long tick : ticksOf(runA)) {
            if (right.contains(tick)) {
                both.add(tick);
            }
        }
        return both;
    }

    private static List<Long> ticksOf(Map<String, StateHasher.Slice> rows) {
        java.util.Set<Long> ticks = new TreeSet<>();
        for (String key : rows.keySet()) {
            ticks.add(Long.parseLong(key.substring(0, key.indexOf('|'))));
        }
        return new ArrayList<>(ticks);
    }

    @Test
    void theTwoRunsAreReadOnTheTicksTheyShareAnArrivalOn() throws IOException {
        Map<String, String> declared = declaration();
        String arrivalA = declared.get("a");
        String arrivalB = declared.get("b");
        assumeTrue(arrivalA != null && arrivalB != null && !arrivalA.isBlank()
            && !arrivalB.isBlank(), "no two runs declared, so there is nothing to compare");
        assumeTrue(declared.get("ea") != null && declared.get("eb") != null,
            "no execution dump declared for the two runs");
        long from = number(declared, "from", Long.MIN_VALUE);
        long to = number(declared, "to", Long.MAX_VALUE);
        Path report = path(declared.get("report"));
        Path execReport = path(declared.get("exec_report"));
        TickDigestReplayTest.Result arrival =
            TickDigestReplayTest.compare(Path.of(arrivalA), Path.of(arrivalB), report, from, to);
        assertTrue(arrival.pairs() > 0L, "the arrival comparison covered no pair");
        assertEquals(0L, arrival.unattributed(),
            "an arrival pair the comparison could not attribute: " + arrival.fork());
        List<String> lines = new ArrayList<>();
        lines.add("arrival.window=[" + from + ", " + to + ")");
        lines.add("arrival.ticks=" + arrival.ticks() + " pairs=" + arrival.pairs() + " equal="
            + arrival.equal() + " unpaired=" + arrival.unpaired().size());
        lines.add("arrival." + arrival.fork());
        Map<String, StateHasher.Slice> rowsA = TickDigestReplayTest.read(Path.of(arrivalA), from, to);
        Map<String, StateHasher.Slice> rowsB = TickDigestReplayTest.read(Path.of(arrivalB), from, to);
        Map<String, StateHasher.Slice> rowsEA = executionRows(declared, from, to, "a");
        Map<String, StateHasher.Slice> rowsEB = executionRows(declared, from, to, "b");
        List<Long> shared = sharedArrivalTicks(rowsA, rowsB);
        lines.add("arrival.shared_ticks=" + shared.size() + " of "
            + commonTicks(rowsA, rowsB).size());
        lines.add("arrival.first_fork_tick=" + firstArrivalFork(rowsA, rowsB));
        if (report != null) {
            lines.add("arrival.report=" + report);
        }
        if (!shared.isEmpty()) {
            TickDigestReplayTest.Result all = TickDigestReplayTest.compare(
                atTicks(rowsEA, shared, PROBES), atTicks(rowsEB, shared, PROBES), execReport,
                "same-arrival ticks=" + shared.size());
            lines.add("execution.pairs=" + all.pairs() + " equal=" + all.equal() + " unpaired="
                + all.unpaired().size() + " values=" + all.values());
            lines.add("execution." + all.fork());
            for (String probe : PROBES) {
                Map<String, StateHasher.Slice> left = atTicks(rowsEA, shared, new String[]{probe});
                if (left.isEmpty()) {
                    lines.add("execution." + probe + "=no row on the shared ticks");
                    continue;
                }
                TickDigestReplayTest.Result one = TickDigestReplayTest.compare(left,
                    atTicks(rowsEB, shared, new String[]{probe}), null,
                    "probe=" + probe + " same-arrival ticks=" + shared.size());
                lines.add("execution." + probe + ".pairs=" + one.pairs() + " equal=" + one.equal()
                    + " " + one.fork());
            }
        } else {
            lines.add("execution=not read: no tick carries the same arrival in both runs");
        }
        List<String> cells = sharedArrivalCells(rowsA, rowsB);
        long contentCells = 0L;
        long contentEqual = 0L;
        long degenerateCells = 0L;
        long degenerateEqual = 0L;
        long cellForkTick = -1L;
        String cellFork = "first_fork=none";
        for (String cell : cells) {
            int cut = cell.indexOf('|');
            long tick = Long.parseLong(cell.substring(0, cut));
            String world = cell.substring(cut + 1);
            Map<String, StateHasher.Slice> left = atCell(rowsEA, tick, world);
            Map<String, StateHasher.Slice> right = atCell(rowsEB, tick, world);
            if (left.isEmpty() || right.isEmpty()) {
                continue;
            }
            TickDigestReplayTest.Result one =
                TickDigestReplayTest.compare(left, right, null, "cell=" + cell);
            boolean content = carriesContent(left) || carriesContent(right);
            if (content) {
                contentCells++;
                if (one.equal() == one.pairs()) {
                    contentEqual++;
                } else if (cellForkTick < 0L) {
                    cellForkTick = tick;
                    cellFork = one.fork();
                }
            } else {
                degenerateCells++;
                if (one.equal() == one.pairs()) {
                    degenerateEqual++;
                }
            }
        }
        long candidateCells = 0L;
        for (long tick : commonTicks(rowsA, rowsB)) {
            for (String world : worldsOf(rowsA, tick)) {
                if (!atCell(rowsB, tick, world).isEmpty()) {
                    candidateCells++;
                }
            }
        }
        lines.add("cells.shared=" + cells.size() + " of " + candidateCells);
        lines.add("cells.content=" + contentCells + " equal=" + contentEqual + " degenerate="
            + degenerateCells + " equal=" + degenerateEqual);
        lines.add("cells.first_fork_tick=" + cellForkTick + " " + cellFork);
        if (!shared.isEmpty()) {
            long tampered = shared.get(0);
            Map<String, StateHasher.Slice> movedRun = moved(rowsB, tampered);
            TickDigestReplayTest.Result one = TickDigestReplayTest.compare(atTick(rowsA, tampered),
                atTick(movedRun, tampered), null, "tampered tick=" + tampered);
            List<Long> after = sharedArrivalTicks(rowsA, movedRun);
            assertTrue(one.equal() < one.pairs(),
                "the tamper moved no row of the tick it names: " + one.fork());
            assertTrue(one.fork().contains("first_fork tick=" + tampered), one.fork());
            assertFalse(after.contains(tampered),
                "a tampered arrival still counts as a shared tick");
            assertEquals(shared.size() - 1L, after.size(),
                "the tamper must move one tick and no other");
            lines.add("tamper.tick=" + tampered + " pairs=" + one.pairs() + " equal=" + one.equal()
                + " shared_after=" + after.size() + " of " + shared.size());
            lines.add("tamper." + one.fork());
        } else {
            lines.add("tamper=not run: no tick carries the same arrival in both runs");
        }
        Path summary = path(declared.get("summary"));
        if (summary != null) {
            Files.writeString(summary, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        }
        System.out.println(String.join("\n", lines));
    }

    private static Map<String, StateHasher.Slice> executionRows(Map<String, String> declared,
                                                                long from, long to, String side)
        throws IOException {
        return TickDigestReplayTest.read(Path.of(declared.get("e" + side)), from, to);
    }

    /** The arrival reader's own positive, negative and refusal cases. A moved arrival value has to
     * leave the tick out of the shared set and be located, two equal faces have to share every
     * tick, and a scope that holds no row has to be refused rather than read as an equality. */
    @Test
    void theDiscriminatorAnswersPositiveNegativeAndRefusal() throws IOException {
        Path dir = Files.createTempDirectory("prts-arrival");
        Path plain = dir.resolve("arrival-plain.tsv");
        Path same = dir.resolve("arrival-same.tsv");
        Path moved = dir.resolve("arrival-moved.tsv");
        Path unpaired = dir.resolve("arrival-unpaired.tsv");
        Path empty = dir.resolve("arrival-empty.tsv");
        Files.writeString(plain, dump(1.0, 4.0), StandardCharsets.UTF_8);
        Files.writeString(same, dump(1.0, 4.0), StandardCharsets.UTF_8);
        Files.writeString(moved, dump(2.0, 4.0), StandardCharsets.UTF_8);
        Files.writeString(unpaired, dump(1.0, 4.0).split("\n")[0] + "\n", StandardCharsets.UTF_8);
        Files.writeString(empty, "arrival.dump_begin=1\narrival.dump_end=1\n",
            StandardCharsets.UTF_8);
        TickDigestReplayTest.Result equal =
            TickDigestReplayTest.compare(plain, same, null, Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(2L, equal.pairs(), "two worlds were folded");
        assertEquals(2L, equal.equal());
        assertEquals(2L * 8L, equal.values());
        Map<String, StateHasher.Slice> plainRows =
            TickDigestReplayTest.read(plain, Long.MIN_VALUE, Long.MAX_VALUE);
        Map<String, StateHasher.Slice> sameRows =
            TickDigestReplayTest.read(same, Long.MIN_VALUE, Long.MAX_VALUE);
        Map<String, StateHasher.Slice> movedRows =
            TickDigestReplayTest.read(moved, Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(List.of(7L), sharedArrivalTicks(plainRows, sameRows));
        assertTrue(sharedArrivalTicks(plainRows, movedRows).isEmpty(),
            "a tick whose arrival moved is not a shared tick");
        assertEquals(7L, firstArrivalFork(plainRows, movedRows));
        TickDigestReplayTest.Result forked =
            TickDigestReplayTest.compare(plain, moved, null, Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(1L, forked.equal(), "only the overworld row moved");
        assertTrue(forked.fork().contains("first_fork tick=7"), forked.fork());
        assertTrue(forked.fork().contains("world=minecraft:overworld"), forked.fork());
        assertThrows(AssertionError.class,
            () -> TickDigestReplayTest.compare(empty, same, null, Long.MIN_VALUE, Long.MAX_VALUE));
        assertThrows(AssertionError.class,
            () -> TickDigestReplayTest.compare(plain, empty, null, Long.MIN_VALUE, Long.MAX_VALUE));
        assertThrows(AssertionError.class,
            () -> TickDigestReplayTest.compare(plain, same, null, 8L, 9L),
            "a declared window neither run folded is an empty scope and is refused");
        assertTrue(sharedArrivalTicks(plainRows,
            TickDigestReplayTest.read(unpaired, Long.MIN_VALUE, Long.MAX_VALUE)).isEmpty());
        assertThrows(AssertionError.class,
            () -> TickDigestReplayTest.compare(Map.of(), sameRows, null, "an empty subset"));
    }

    private static String dump(double overworldRequests, double netherRequests) {
        return TickDigestReplayTest.line("arrival.row=", 7L, "minecraft:overworld", "arrival", 0,
            "arrival", 12L, new double[]{overworldRequests, 1.0, 0.0, 0.0, 2.0, 2.0, 3.0, 5.0})
            + "\n" + TickDigestReplayTest.line("arrival.row=", 7L, "minecraft:the_nether", "arrival",
            0, "arrival", 4L, new double[]{netherRequests, 0.0, 0.0, 0.0, 1.0, 1.0, 0.0, 2.0})
            + "\n";
    }

    private static Map<String, String> declaration() throws IOException {
        Map<String, String> declared = new LinkedHashMap<>();
        for (String key : new String[]{"a", "b", "ea", "eb", "report", "exec_report", "summary",
            "from", "to"}) {
            put(declared, key, System.getenv("PRTS_ARRIVAL_" + key.toUpperCase()));
            put(declared, key, System.getProperty("prts.arrival." + key));
        }
        if (!Files.isReadable(DECLARATION)) {
            return declared;
        }
        for (String line : Files.readAllLines(DECLARATION, StandardCharsets.UTF_8)) {
            int split = line.indexOf('=');
            if (split > 0) {
                put(declared, line.substring(0, split).trim(), line.substring(split + 1).trim());
            }
        }
        return declared;
    }

    private static void put(Map<String, String> declared, String key, String value) {
        if (value != null && !value.isBlank()) {
            declared.put(key, value);
        }
    }

    private static Path path(String value) {
        return value == null || value.isBlank() ? null : Path.of(value);
    }

    private static long number(Map<String, String> declared, String key, long fallback) {
        String value = declared.get(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
