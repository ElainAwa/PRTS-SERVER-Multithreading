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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Asks one class of the arrival face at a time: in which cells two runs were handed the same values
 * of that one class, and what the execution face that carries that class did on those cells.
 *
 * <p>No comparison rule of its own lives here. A projected row is folded by {@link StateHasher} and
 * compared by {@link DiffProbe} through {@link TickDigestReplayTest}, the entry point every other
 * reader uses, and a scope that selected nothing is refused there instead of being read as an
 * equality. What this class adds is which of the eight arrival values make up one class, and which
 * probe rows carry that class on the execution side.
 *
 * <p>An arrival row is per tick and per world, so a class is asked per cell. The per tick face
 * carries the asks, the answers, the futures and the writes; a per mailbox class exists only in the
 * point face, whose counters are cumulative over a declared window.
 */
class ClassArrivalReplayTest {

    /** The eight arrival values of one row, in the order the face folds them. */
    static final int VALUE_COUNT = 8;

    /** The smallest number of same-arrival samples a class has to give before its execution face is
     * read as an answer. A declared value, not a calibrated one. */
    static final int MIN_SAMPLES = 7;

    /** The probe name a projected row is folded under, so no class can collide with another one or
     * with the row family it was read from. */
    static final String CLASS_PROBE = "arrival-class:";

    /** One class of the arrival face: the values that make it up and the probes that carry it. */
    record ClassSpec(String name, int[] values, String[] probes) {
    }

    static final ClassSpec ALL = new ClassSpec("all", new int[]{0, 1, 2, 3, 4, 5, 6, 7},
        new String[]{"chunk", "light", "write"});
    static final ClassSpec DEMAND = new ClassSpec("demand", new int[]{0, 1, 2, 3},
        new String[]{"chunk", "light"});
    static final ClassSpec FUTURE = new ClassSpec("future", new int[]{4, 5, 6}, new String[0]);
    static final ClassSpec WRITE = new ClassSpec("write", new int[]{7}, new String[]{"write"});
    static final List<ClassSpec> CLASSES = List.of(ALL, DEMAND, FUTURE, WRITE);

    /** The counted keys of one mailbox class in the point face, and the queue level that is not part
     * of its arrival. */
    static final String[] MAILBOX_COUNTED = {"submitted", "completed"};
    static final String[] MAILBOX_LEVEL = {"depth", "depth_peak"};

    /** Which probe carries the work of one mailbox class. */
    static final Map<String, String> MAILBOX_PROBE = Map.of("worldgen", "chunk", "light", "light");

    private static final Path DECLARATION = Path.of("/tmp/prts-class-arrival-decl.txt");

    /** One run of the scenario and the dumps the reader may read it through. */
    record Run(String tag, Path arrival, Path execution, Path classArrival) {
    }

    /** What one probe of one class answered over every pair it was asked about. The strict scope is
     * the class handed work in that cell, the wide one is every cell whose execution rows carry
     * work; the wide scope is reported and is not the population the verdict rests on. */
    static final class ProbeTally {
        long pairs;
        long equal;
        long usable;
        long widePairs;
        long wideEqual;
        String firstFork = "";
        String wideFirstFork = "";
    }

    /** What one class answered over every pair it was asked about. */
    static final class Tally {
        long pairs;
        long cells;
        long equalCells;
        long arrivalCells;
        long execCells;
        long strictCells;
        long contentCells;
        long levelCells;
        long noProbe;
        final Map<String, ProbeTally> probes = new LinkedHashMap<>();

        ProbeTally probe(String name) {
            return probes.computeIfAbsent(name, unused -> new ProbeTally());
        }
    }

    @Test
    void everyClassIsAskedOfItsOwnExecutionFace() throws IOException {
        Map<String, String> declared = declaration();
        Path runsFile = file(declared.get("runs"));
        assumeTrue(runsFile != null && Files.isReadable(runsFile),
            "no run list declared, so there is no pair to ask");
        long from = number(declared, "from", Long.MIN_VALUE);
        long to = number(declared, "to", Long.MAX_VALUE);
        Path report = file(declared.get("report"));
        Path samples = file(declared.get("samples"));
        List<Run> runs = readRuns(runsFile);
        Map<String, Map<String, StateHasher.Slice>> arrivals = new LinkedHashMap<>();
        Map<String, Map<String, StateHasher.Slice>> executions = new LinkedHashMap<>();
        for (Run run : runs) {
            Map<String, StateHasher.Slice> arrivalRows = TickDigestReplayTest.read(run.arrival(), from, to);
            Map<String, StateHasher.Slice> executionRows = TickDigestReplayTest.read(run.execution(), from, to);
            assertTrue(!arrivalRows.isEmpty(), "the arrival dump of " + run.tag()
                + " folds no row in [" + from + ", " + to + "): " + run.arrival());
            assertTrue(!executionRows.isEmpty(), "the execution dump of " + run.tag()
                + " folds no row in [" + from + ", " + to + "): " + run.execution());
            arrivals.put(run.tag(), arrivalRows);
            executions.put(run.tag(), executionRows);
        }
        Map<String, Map<String, Map<String, StateHasher.Slice>>> projected = new LinkedHashMap<>();
        for (Run run : runs) {
            Map<String, Map<String, StateHasher.Slice>> byClass = new LinkedHashMap<>();
            for (ClassSpec spec : CLASSES) {
                byClass.put(spec.name(), project(arrivals.get(run.tag()), spec));
            }
            projected.put(run.tag(), byClass);
        }
        Map<String, Tally> tallies = new LinkedHashMap<>();
        for (ClassSpec spec : CLASSES) {
            tallies.put(spec.name(), new Tally());
        }
        Map<String, Tally> mailbox = new LinkedHashMap<>();
        List<String> lines = new ArrayList<>();
        List<String> sampleLines = new ArrayList<>();
        lines.add("runs=" + runs.size() + " window=[" + from + ", " + to + ")");
        for (int left = 0; left < runs.size(); left++) {
            for (int right = left + 1; right < runs.size(); right++) {
                for (ClassSpec spec : CLASSES) {
                    ask(spec, runs.get(left).tag(), runs.get(right).tag(), arrivals, projected,
                        executions, tallies.get(spec.name()), sampleLines, lines);
                }
                pointFace(runs.get(left), runs.get(right), executions, mailbox, lines);
            }
        }
        for (ClassSpec spec : CLASSES) {
            lines.add(render(spec, tallies.get(spec.name())));
        }
        for (Map.Entry<String, Tally> entry : mailbox.entrySet()) {
            lines.add(renderMailbox(entry.getKey(), entry.getValue()));
        }
        boolean judged = false;
        boolean wideJudged = false;
        for (ClassSpec spec : CLASSES) {
            judged |= tallies.get(spec.name()).strictCells >= MIN_SAMPLES;
            wideJudged |= tallies.get(spec.name()).execCells >= MIN_SAMPLES;
        }
        for (Tally tally : mailbox.values()) {
            judged |= tally.strictCells >= MIN_SAMPLES;
            wideJudged |= tally.contentCells >= MIN_SAMPLES;
        }
        lines.add("wide_scope_has_samples=" + (wideJudged ? "yes" : "no")
            + " (reported, not the population the verdict rests on)");
        lines.add("min_samples=" + MIN_SAMPLES + " (declared, not calibrated)");
        lines.add("judged=" + (judged ? "yes" : "no"));
        if (!judged) {
            lines.add("NOT-EXECUTABLE / insufficient same-arrival samples");
        }
        if (report != null) {
            Files.writeString(report, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
        }
        if (samples != null) {
            Files.writeString(samples, String.join("\n", sampleLines) + "\n", StandardCharsets.UTF_8);
        }
        System.out.println(String.join("\n", lines));
    }

    /** Asks one class about one pair: the cells the class arrived the same in, and the execution
     * rows of that class on them. */
    private static void ask(ClassSpec spec, String leftTag, String rightTag,
                            Map<String, Map<String, StateHasher.Slice>> arrivals,
                            Map<String, Map<String, Map<String, StateHasher.Slice>>> projected,
                            Map<String, Map<String, StateHasher.Slice>> executions, Tally tally,
                            List<String> sampleLines, List<String> lines) throws IOException {
        Map<String, StateHasher.Slice> left = projected.get(leftTag).get(spec.name());
        Map<String, StateHasher.Slice> right = projected.get(rightTag).get(spec.name());
        List<String> shared = sharedCells(left, right);
        List<String> arrivalCells = new ArrayList<>();
        List<String> execCells = new ArrayList<>();
        for (String cell : shared) {
            if (carriesClass(arrivals.get(leftTag), arrivals.get(rightTag), cell, spec)) {
                arrivalCells.add(cell);
            }
            boolean carries = false;
            for (String probe : spec.probes()) {
                carries |= executionCarries(executions, leftTag, rightTag, cell, probe);
            }
            if (carries) {
                execCells.add(cell);
            }
            if (carries || arrivalCells.contains(cell)) {
                sampleLines.add(sampleLine(spec, leftTag, rightTag, cell, !carries, arrivals));
            }
        }
        java.util.Set<String> execSet = new java.util.LinkedHashSet<>(execCells);
        List<String> strict = new ArrayList<>();
        for (String cell : arrivalCells) {
            if (execSet.contains(cell)) {
                strict.add(cell);
            }
        }
        tally.pairs++;
        tally.cells += candidateCells(left, right);
        tally.equalCells += shared.size();
        tally.arrivalCells += arrivalCells.size();
        tally.execCells += execCells.size();
        tally.strictCells += strict.size();
        for (String probe : spec.probes()) {
            ProbeTally probeTally = tally.probe(probe);
            askProbe(spec, probe, leftTag, rightTag, executions, strict, false, probeTally, lines);
            askProbe(spec, probe, leftTag, rightTag, executions, execCells, true, probeTally, lines);
            for (String cell : strict) {
                if (executionCarries(executions, leftTag, rightTag, cell, probe)) {
                    probeTally.usable++;
                }
            }
        }
    }

    /** One probe of one class over one scope, asked through the one comparison every reader uses. */
    private static void askProbe(ClassSpec spec, String probe, String leftTag, String rightTag,
                                 Map<String, Map<String, StateHasher.Slice>> executions,
                                 List<String> scope, boolean wide, ProbeTally probeTally,
                                 List<String> lines) throws IOException {
        Map<String, StateHasher.Slice> rowsLeft = atCells(executions.get(leftTag), scope, probe);
        Map<String, StateHasher.Slice> rowsRight = atCells(executions.get(rightTag), scope, probe);
        if (rowsLeft.isEmpty() || rowsRight.isEmpty()) {
            if (!wide && !scope.isEmpty()) {
                lines.add("no " + probe + " row on the same-arrival samples of " + spec.name()
                    + " for " + leftTag + " vs " + rightTag);
            }
            return;
        }
        TickDigestReplayTest.Result result = TickDigestReplayTest.compare(rowsLeft, rowsRight, null,
            spec.name() + " " + (wide ? "wide" : "strict") + " same-arrival " + leftTag + " vs "
                + rightTag + " cells=" + scope.size());
        if (wide) {
            probeTally.widePairs += result.pairs();
            probeTally.wideEqual += result.equal();
            if (result.equal() < result.pairs() && probeTally.wideFirstFork.isEmpty()) {
                probeTally.wideFirstFork = leftTag + " vs " + rightTag + " " + result.fork();
            }
            return;
        }
        probeTally.pairs += result.pairs();
        probeTally.equal += result.equal();
        if (result.equal() < result.pairs() && probeTally.firstFork.isEmpty()) {
            probeTally.firstFork = leftTag + " vs " + rightTag + " " + result.fork();
        }
        for (String only : result.unpaired()) {
            lines.add("unpaired " + spec.name() + " " + probe + " " + only);
        }
    }

    private static boolean executionCarries(Map<String, Map<String, StateHasher.Slice>> executions,
                                            String leftTag, String rightTag, String cell,
                                            String probe) {
        return ArrivalReplayTest.carriesContent(atCellProbe(executions.get(leftTag), cell, probe))
            || ArrivalReplayTest.carriesContent(
            atCellProbe(executions.get(rightTag), cell, probe));
    }

    /** Asks the per mailbox classes of the point face about one pair. */
    private static void pointFace(Run left, Run right,
                                  Map<String, Map<String, StateHasher.Slice>> executions,
                                  Map<String, Tally> mailbox, List<String> lines) throws IOException {
        if (left.classArrival() == null || right.classArrival() == null) {
            return;
        }
        Map<String, Map<String, Long>> leftClasses = readClassArrival(left.classArrival());
        Map<String, Map<String, Long>> rightClasses = readClassArrival(right.classArrival());
        for (Map.Entry<String, Map<String, Long>> entry : leftClasses.entrySet()) {
            Map<String, Long> other = rightClasses.get(entry.getKey());
            if (other == null) {
                continue;
            }
            Tally tally = mailbox.computeIfAbsent(entry.getKey(), unused -> new Tally());
            tally.pairs++;
            if (!vectorEqual(entry.getValue(), other, MAILBOX_COUNTED)) {
                continue;
            }
            tally.equalCells++;
            if (counted(entry.getValue(), MAILBOX_COUNTED) <= 0L
                && counted(other, MAILBOX_COUNTED) <= 0L) {
                continue;
            }
            tally.contentCells++;
            if (vectorEqual(entry.getValue(), other, MAILBOX_LEVEL)) {
                tally.levelCells++;
            }
            String probe = MAILBOX_PROBE.get(entry.getKey());
            if (probe == null) {
                tally.noProbe++;
                continue;
            }
            Map<String, StateHasher.Slice> rowsLeft = probes(executions.get(left.tag()), probe);
            Map<String, StateHasher.Slice> rowsRight = probes(executions.get(right.tag()), probe);
            boolean carries = ArrivalReplayTest.carriesContent(rowsLeft)
                || ArrivalReplayTest.carriesContent(rowsRight);
            if (carries) {
                tally.strictCells++;
            }
            if (rowsLeft.isEmpty() || rowsRight.isEmpty()) {
                lines.add("no " + probe + " row for the " + entry.getKey() + " class");
                continue;
            }
            TickDigestReplayTest.Result result = TickDigestReplayTest.compare(rowsLeft, rowsRight, null,
                entry.getKey() + " same-arrival " + left.tag() + " vs " + right.tag());
            ProbeTally probeTally = tally.probe(probe);
            probeTally.pairs += result.pairs();
            probeTally.equal += result.equal();
            probeTally.usable += carries ? 1L : 0L;
            if (result.equal() < result.pairs() && probeTally.firstFork.isEmpty()) {
                probeTally.firstFork = left.tag() + " vs " + right.tag() + " " + result.fork();
            }
        }
    }

    private static String render(ClassSpec spec, Tally tally) {
        StringBuilder text = new StringBuilder("class=").append(spec.name())
            .append(" run_pairs=").append(tally.pairs)
            .append(" cells_searched=").append(tally.cells)
            .append(" cells_arrival_equal=").append(tally.equalCells)
            .append(" cells_arrival_content=").append(tally.arrivalCells)
            .append(" cells_execution_content=").append(tally.execCells)
            .append(" samples_usable=").append(tally.strictCells);
        if (spec.probes().length == 0) {
            text.append(" probes=none");
        }
        for (Map.Entry<String, ProbeTally> entry : tally.probes.entrySet()) {
            ProbeTally probe = entry.getValue();
            text.append(" | ").append(spec.name()).append('.').append(entry.getKey())
                .append(" strict pairs=").append(probe.pairs)
                .append(" equal=").append(probe.equal)
                .append(" usable=").append(probe.usable)
                .append(" | wide pairs=").append(probe.widePairs)
                .append(" equal=").append(probe.wideEqual)
                .append(probe.firstFork.isEmpty() ? " first_fork=none" : " " + probe.firstFork)
                .append(probe.wideFirstFork.isEmpty() ? " wide_first_fork=none"
                    : " wide_first_fork " + probe.wideFirstFork);
        }
        text.append(" verdict=");
        if (spec.probes().length == 0) {
            text.append("no execution probe declared for this class");
        } else if (tally.strictCells >= MIN_SAMPLES) {
            text.append("JUDGED");
        } else {
            text.append("NOT-EXECUTABLE / insufficient same-arrival samples (samples_usable=")
                .append(tally.strictCells).append(" of ").append(tally.arrivalCells)
                .append(" arrival content cells and ").append(tally.execCells)
                .append(" execution content cells, min_samples=").append(MIN_SAMPLES).append(')');
        }
        return text.toString();
    }

    private static String renderMailbox(String name, Tally tally) {
        StringBuilder text = new StringBuilder("mailbox=").append(name)
            .append(" run_pairs=").append(tally.pairs)
            .append(" arrivals_equal=").append(tally.equalCells)
            .append(" arrivals_equal_with_content=").append(tally.contentCells)
            .append(" arrivals_equal_all_keys=").append(tally.levelCells)
            .append(" samples_usable=").append(tally.strictCells)
            .append(" no_probe=").append(tally.noProbe);
        for (Map.Entry<String, ProbeTally> entry : tally.probes.entrySet()) {
            ProbeTally probe = entry.getValue();
            text.append(" | ").append(name).append('.').append(entry.getKey())
                .append(" pairs=").append(probe.pairs)
                .append(" equal=").append(probe.equal)
                .append(probe.firstFork.isEmpty() ? " first_fork=none" : " " + probe.firstFork);
        }
        text.append(" verdict=");
        if (tally.noProbe > 0L && tally.probes.isEmpty()) {
            text.append("no execution probe declared for this class");
        } else if (tally.strictCells >= MIN_SAMPLES) {
            text.append("JUDGED");
        } else {
            text.append("NOT-EXECUTABLE / insufficient same-arrival samples (samples_usable=")
                .append(tally.strictCells).append(" of ").append(tally.contentCells)
                .append(" content pairs, min_samples=").append(MIN_SAMPLES).append(')');
        }
        return text.toString();
    }

    /** The cells two runs were handed the same values of one class in: the whole cell is paired and
     * equal, and it is the same comparison the whole scope is asked through. */
    static List<String> sharedCells(Map<String, StateHasher.Slice> left,
                                    Map<String, StateHasher.Slice> right) throws IOException {
        List<String> shared = new ArrayList<>();
        for (String cell : commonCells(left, right)) {
            int cut = cell.indexOf('|');
            long tick = Long.parseLong(cell.substring(0, cut));
            String world = cell.substring(cut + 1);
            TickDigestReplayTest.Result one = TickDigestReplayTest.compare(
                ArrivalReplayTest.atCell(left, tick, world),
                ArrivalReplayTest.atCell(right, tick, world), null, "cell=" + cell);
            if (one.pairs() > 0L && one.equal() == one.pairs() && one.unpaired().isEmpty()) {
                shared.add(cell);
            }
        }
        return shared;
    }

    /** The cells both runs folded a row for; a cell only one of them reached cannot be asked
     * about. */
    static List<String> commonCells(Map<String, StateHasher.Slice> left,
                                    Map<String, StateHasher.Slice> right) {
        java.util.Set<String> rightCells = new java.util.LinkedHashSet<>(cellsOf(right));
        List<String> cells = new ArrayList<>();
        for (String cell : cellsOf(left)) {
            if (rightCells.contains(cell)) {
                cells.add(cell);
            }
        }
        return cells;
    }

    private static List<String> cellsOf(Map<String, StateHasher.Slice> rows) {
        java.util.Set<String> cells = new java.util.LinkedHashSet<>();
        for (String key : rows.keySet()) {
            cells.add(key.substring(0, key.lastIndexOf('|')));
        }
        return new ArrayList<>(cells);
    }

    private static long candidateCells(Map<String, StateHasher.Slice> left,
                                       Map<String, StateHasher.Slice> right) {
        return commonCells(left, right).size();
    }

    /** One row of the arrival face with only the values of one class kept. */
    static Map<String, StateHasher.Slice> project(Map<String, StateHasher.Slice> rows,
                                                  ClassSpec spec) {
        Map<String, StateHasher.Slice> projected = new TreeMap<>();
        for (Map.Entry<String, StateHasher.Slice> entry : rows.entrySet()) {
            String[] parts = entry.getKey().split("\\|", -1);
            StateHasher.Slice source = entry.getValue();
            double[] values = new double[VALUE_COUNT];
            for (int index : spec.values()) {
                values[index] = slot(source, index);
            }
            StateHasher.Slice slice = new StateHasher.Slice(source.worldId(),
                CLASS_PROBE + spec.name(), 0L, 0L, values[0], values[1], values[2], values[3],
                values[4], values[5], values[6], values[7], source.flags(), source.slotGeneration(),
                source.segmentRef());
            projected.put(parts[0] + "|" + parts[1] + "|" + CLASS_PROBE + spec.name(), slice);
        }
        return projected;
    }

    /** Which of the eight arrival values one class is made of, read off the folded row. */
    static double slot(StateHasher.Slice slice, int index) {
        switch (index) {
            case 0:
                return slice.x();
            case 1:
                return slice.y();
            case 2:
                return slice.z();
            case 3:
                return slice.yaw();
            case 4:
                return slice.pitch();
            case 5:
                return slice.velX();
            case 6:
                return slice.velY();
            default:
                return slice.velZ();
        }
    }

    private static boolean carriesClass(Map<String, StateHasher.Slice> left,
                                        Map<String, StateHasher.Slice> right, String cell,
                                        ClassSpec spec) {
        int cut = cell.indexOf('|');
        long tick = Long.parseLong(cell.substring(0, cut));
        String world = cell.substring(cut + 1);
        return carriesClass(ArrivalReplayTest.atCell(left, tick, world), spec)
            || carriesClass(ArrivalReplayTest.atCell(right, tick, world), spec);
    }

    private static boolean carriesClass(Map<String, StateHasher.Slice> cell, ClassSpec spec) {
        for (StateHasher.Slice slice : cell.values()) {
            for (int index : spec.values()) {
                if (slot(slice, index) > 0.0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, StateHasher.Slice> atCellProbe(Map<String, StateHasher.Slice> rows,
                                                              String cell, String probe) {
        Map<String, StateHasher.Slice> selected = new TreeMap<>();
        StateHasher.Slice slice = rows.get(cell + "|" + probe);
        if (slice != null) {
            selected.put(cell + "|" + probe, slice);
        }
        return selected;
    }

    private static Map<String, StateHasher.Slice> atCells(Map<String, StateHasher.Slice> rows,
                                                          List<String> cells, String probe) {
        Map<String, StateHasher.Slice> selected = new TreeMap<>();
        for (String cell : cells) {
            selected.putAll(atCellProbe(rows, cell, probe));
        }
        return selected;
    }

    private static Map<String, StateHasher.Slice> probes(Map<String, StateHasher.Slice> rows,
                                                         String probe) {
        Map<String, StateHasher.Slice> selected = new TreeMap<>();
        for (Map.Entry<String, StateHasher.Slice> entry : rows.entrySet()) {
            String[] parts = entry.getKey().split("\\|", -1);
            if (parts[2].equals(probe)) {
                selected.put(entry.getKey(), entry.getValue());
            }
        }
        return selected;
    }

    private static String sampleLine(ClassSpec spec, String leftTag, String rightTag, String cell,
                                     boolean degenerate,
                                     Map<String, Map<String, StateHasher.Slice>> arrivals) {
        int cut = cell.indexOf('|');
        long tick = Long.parseLong(cell.substring(0, cut));
        String world = cell.substring(cut + 1);
        StringBuilder text = new StringBuilder(leftTag).append('\t').append(rightTag).append('\t')
            .append(spec.name()).append('\t').append(tick).append('\t').append(world)
            .append('\t').append(degenerate ? "execution_degenerate" : "execution_content");
        for (Map<String, StateHasher.Slice> side : List.of(arrivals.get(leftTag),
            arrivals.get(rightTag))) {
            StateHasher.Slice slice = ArrivalReplayTest.atCell(side, tick, world).values().iterator()
                .next();
            for (int index : spec.values()) {
                text.append('\t').append((long) slot(slice, index));
            }
        }
        return text.toString();
    }

    /** One point face dump: the counted values one mailbox class was handed over a window. */
    private static Map<String, Map<String, Long>> readClassArrival(Path dump) throws IOException {
        Map<String, Map<String, Long>> classes = new LinkedHashMap<>();
        for (String text : Files.readAllLines(dump, StandardCharsets.UTF_8)) {
            if (text.isBlank() || text.startsWith("#")) {
                continue;
            }
            String[] parts = text.split("\t", -1);
            if (parts.length < 3) {
                continue;
            }
            classes.computeIfAbsent(parts[0], unused -> new LinkedHashMap<>())
                .put(parts[1], Long.parseLong(parts[2].trim()));
        }
        return classes;
    }

    private static boolean vectorEqual(Map<String, Long> left, Map<String, Long> right,
                                       String[] keys) {
        for (String key : keys) {
            long leftValue = left.getOrDefault(key, 0L);
            if (leftValue != right.getOrDefault(key, 0L)) {
                return false;
            }
        }
        return true;
    }

    private static long counted(Map<String, Long> values, String[] keys) {
        long total = 0L;
        for (String key : keys) {
            total += values.getOrDefault(key, 0L);
        }
        return total;
    }

    private static List<Run> readRuns(Path file) throws IOException {
        List<Run> runs = new ArrayList<>();
        for (String text : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            if (text.isBlank() || text.startsWith("#")) {
                continue;
            }
            String[] parts = text.split("\t", -1);
            runs.add(new Run(parts[0], Path.of(parts[1]), Path.of(parts[2]),
                parts.length > 3 && !parts[3].isBlank() ? Path.of(parts[3]) : null));
        }
        return runs;
    }

    private static Map<String, String> declaration() throws IOException {
        Map<String, String> declared = new LinkedHashMap<>();
        for (String key : new String[]{"runs", "report", "samples", "from", "to"}) {
            String value = System.getProperty("prts.classarrival." + key);
            if (value != null && !value.isBlank()) {
                declared.put(key, value);
            }
        }
        if (!Files.isReadable(DECLARATION)) {
            return declared;
        }
        for (String line : Files.readAllLines(DECLARATION, StandardCharsets.UTF_8)) {
            int split = line.indexOf('=');
            if (split > 0) {
                declared.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
            }
        }
        return declared;
    }

    private static Path file(String value) {
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

    @Test
    void theClassDiscriminatorAnswersPositiveNegativeAndRefusal() throws IOException {
        Path dir = Files.createTempDirectory("prts-class-arrival");
        Path plain = dir.resolve("arrival-plain.tsv");
        Path same = dir.resolve("arrival-same.tsv");
        Path movedWrite = dir.resolve("arrival-moved-write.tsv");
        Path movedDemand = dir.resolve("arrival-moved-demand.tsv");
        Path empty = dir.resolve("arrival-empty.tsv");
        Files.writeString(plain, arrivalDump(4.0, 2.0), StandardCharsets.UTF_8);
        Files.writeString(same, arrivalDump(4.0, 2.0), StandardCharsets.UTF_8);
        Files.writeString(movedWrite, arrivalDump(4.0, 3.0), StandardCharsets.UTF_8);
        Files.writeString(movedDemand, arrivalDump(6.0, 2.0), StandardCharsets.UTF_8);
        Files.writeString(empty, "arrival.dump_begin=1\narrival.dump_end=1\n", StandardCharsets.UTF_8);
        Map<String, StateHasher.Slice> left =
            TickDigestReplayTest.read(plain, Long.MIN_VALUE, Long.MAX_VALUE);
        Map<String, StateHasher.Slice> right =
            TickDigestReplayTest.read(same, Long.MIN_VALUE, Long.MAX_VALUE);
        Map<String, StateHasher.Slice> write =
            TickDigestReplayTest.read(movedWrite, Long.MIN_VALUE, Long.MAX_VALUE);
        Map<String, StateHasher.Slice> demand =
            TickDigestReplayTest.read(movedDemand, Long.MIN_VALUE, Long.MAX_VALUE);
        for (ClassSpec spec : CLASSES) {
            assertEquals(List.of("7|minecraft:overworld"),
                sharedCells(project(left, spec), project(right, spec)),
                "two equal faces share every cell of every class: " + spec.name());
        }
        assertTrue(sharedCells(project(left, WRITE), project(write, WRITE)).isEmpty(),
            "a write value that moved leaves the write class");
        assertEquals(List.of("7|minecraft:overworld"),
            sharedCells(project(left, DEMAND), project(write, DEMAND)),
            "a moved write value must not move the demand class");
        assertTrue(sharedCells(project(left, DEMAND), project(demand, DEMAND)).isEmpty(),
            "a request value that moved leaves the demand class");
        assertEquals(List.of("7|minecraft:overworld"),
            sharedCells(project(left, WRITE), project(demand, WRITE)),
            "a moved request value must not move the write class");
        TickDigestReplayTest.Result forked = TickDigestReplayTest.compare(project(left, WRITE),
            project(write, WRITE), null, "moved write");
        assertTrue(forked.equal() < forked.pairs(), forked.fork());
        assertTrue(forked.fork().contains("tick=7"), forked.fork());
        assertTrue(forked.fork().contains("world=minecraft:overworld"), forked.fork());
        assertTrue(forked.fork().contains("field="), forked.fork());
        assertThrows(AssertionError.class,
            () -> TickDigestReplayTest.compare(empty, same, null, Long.MIN_VALUE, Long.MAX_VALUE),
            "an empty dump is refused, not read as an equality");
        assertThrows(AssertionError.class,
            () -> TickDigestReplayTest.compare(plain, same, null, 8L, 9L),
            "a window no run folded is an empty scope and is refused");
        assertThrows(AssertionError.class,
            () -> TickDigestReplayTest.compare(Map.of(), project(write, WRITE), null,
                "a class that selected nothing"),
            "a class with no sample is refused by the reader, not answered");
        assertTrue(FUTURE.probes().length == 0,
            "a class the execution face does not carry has no probe to ask");
    }

    private static String arrivalDump(double requests, double writes) {
        return TickDigestReplayTest.line("arrival.row=", 7L, "minecraft:overworld", "arrival", 0,
            "arrival", (long) requests,
            new double[]{requests, requests, 0.0, requests, 0.0, 0.0, 0.0, writes}) + "\n";
    }
}
