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
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Compares the tick digests of two independent runs of one scenario.
 *
 * <p>It is the reader side of the digest window: a run exports one {@code digest.row} line per
 * (tick, world, probe), and this class rebuilds the row, folds it with the same state hash the run
 * folded it with, and compares the two runs with the same differential the kernel uses. Nothing is
 * recomputed by a second rule: a row that does not fold back to the value the run exported is an
 * error, not a difference.
 *
 * <p>The comparison is a tool, not a regression: it does nothing unless the two dumps are declared,
 * so an ordinary full test run passes over it. Every way of not being able to answer is a failure
 * rather than a pass - an empty dump, a row only one run has, a row the hash does not reproduce, a
 * comparison that covered no pair, and any difference at all. The first difference is answered as
 * the tick, the world, the region, the probe and the field it sits in.
 */
class TickDigestReplayTest {

    /** The judged fields of one row: the eight values the probe folded. */
    static final int FIELDS = 8;

    private static final Path DECLARATION = Path.of("/tmp/prts-digest-paths.txt");

    /** What a comparison of two dumps answered. */
    record Result(long pairs, long equal, long unattributed, List<String> unpaired, String fork,
                  long values, long ticks) {
    }

    @Test
    void theTwoRunsFoldToTheSameDigest() throws IOException {
        Map<String, String> declared = declaration();
        String left = declared.get("a");
        String right = declared.get("b");
        assumeTrue(left != null && right != null && !left.isBlank() && !right.isBlank(),
            "no two runs declared, so there is nothing to compare");
        Path report = declared.containsKey("report") ? Path.of(declared.get("report")) : null;
        long from = number(declared, "from", Long.MIN_VALUE);
        long to = number(declared, "to", Long.MAX_VALUE);
        Result result = compare(Path.of(left), Path.of(right), report, from, to);
        assertTrue(result.ticks() > 0L,
            "the declared window holds no tick both runs folded: [" + from + ", " + to + ")");
        assertEquals(0L, result.unattributed(),
            "a pair the comparison could not attribute: " + result.fork());
        assertTrue(result.pairs() > 0L, "the comparison covered no pair");
        String first = result.unpaired().isEmpty() ? "none"
            : result.unpaired().get(0) + " of " + result.unpaired().size() + " row(s)";
        assertTrue(result.unpaired().isEmpty(), "a digest row only one run has: " + first);
        assertEquals(result.pairs(), result.equal(), "the two runs differ: " + result.fork());
    }

    /** The two answers a comparison must never soften: a deviated row is a difference with a tick,
     * a world and a field, and a dump without a row is a refusal and not an equality. */
    @Test
    void aForkIsLocatedAndAnEmptyDumpIsRefused() throws IOException {
        Path dir = Files.createTempDirectory("prts-digest");
        Path left = dir.resolve("run-a.tsv");
        Path right = dir.resolve("run-b.tsv");
        Path moved = dir.resolve("run-c.tsv");
        Path empty = dir.resolve("run-empty.tsv");
        Files.writeString(left, dump(1.0, 2.0), StandardCharsets.UTF_8);
        Files.writeString(right, dump(1.0, 2.0), StandardCharsets.UTF_8);
        Files.writeString(moved, dump(1.0, 3.0), StandardCharsets.UTF_8);
        Files.writeString(empty, "digest.dump_begin=1\ndigest.dump_end=1\n", StandardCharsets.UTF_8);
        Result same = compare(left, right, null, Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(2L, same.pairs(), "two probes were folded");
        assertEquals(2L, same.equal());
        assertEquals(2L * FIELDS, same.values());
        assertEquals(1L, same.ticks());
        Result forked = compare(left, moved, null, Long.MIN_VALUE, Long.MAX_VALUE);
        assertEquals(2L, forked.pairs());
        assertEquals(1L, forked.equal(), "only the light row moved");
        assertTrue(forked.fork().contains("first_fork tick=7"), forked.fork());
        assertTrue(forked.fork().contains("world=minecraft:overworld"), forked.fork());
        assertTrue(forked.fork().contains("field=position"), forked.fork());
        assertThrows(AssertionError.class,
            () -> compare(empty, right, null, Long.MIN_VALUE, Long.MAX_VALUE));
        assertThrows(AssertionError.class,
            () -> compare(left, empty, null, Long.MIN_VALUE, Long.MAX_VALUE));
        assertThrows(AssertionError.class,
            () -> compare(left, right, null, 8L, 9L),
            "a declared window neither run folded is an empty scope and is refused");
    }

    /** Compares the part of two dumps that falls inside one declared tick window, and holds every
     * row to the value its run exported. */
    static Result compare(Path left, Path right, Path report, long from, long to)
        throws IOException {
        Map<String, StateHasher.Slice> runA = read(left, from, to);
        Map<String, StateHasher.Slice> runB = read(right, from, to);
        assertTrue(!runA.isEmpty(), "the first dump carries no digest row in [" + from + ", " + to
            + "): " + left);
        assertTrue(!runB.isEmpty(), "the second dump carries no digest row in [" + from + ", " + to
            + "): " + right);
        Map<String, DomainHash> hashedA = hashAll(runA);
        Map<String, DomainHash> hashedB = hashAll(runB);
        List<String> unpaired = unpaired(hashedA.keySet(), hashedB.keySet());
        DiffProbe probe = new DiffProbe();
        for (Map.Entry<String, DomainHash> entry : hashedA.entrySet()) {
            DomainHash other = hashedB.get(entry.getKey());
            if (other != null) {
                probe.compare(entry.getValue(), other);
            }
        }
        DiffProbe.DiffReport diff = probe.report();
        Set<Long> ticks = new java.util.TreeSet<>();
        for (String key : hashedA.keySet()) {
            ticks.add(Long.parseLong(key.substring(0, key.indexOf('|'))));
        }
        Result result = new Result(diff.tickPairs(), diff.equal(), diff.unattributed(), unpaired,
            diff.forkLine(), diff.tickPairs() * FIELDS, ticks.size());
        if (report != null) {
            Files.writeString(report, "window=[" + from + ", " + to + ") ticks=" + result.ticks()
                + " pairs=" + result.pairs() + " equal=" + result.equal() + " unattributed="
                + result.unattributed() + " unpaired=" + unpaired.size() + " fields=" + FIELDS
                + " values=" + result.values() + "\n" + result.fork()
                + (unpaired.isEmpty() ? "" : "\nfirst unpaired=" + unpaired.get(0)) + "\n",
                StandardCharsets.UTF_8);
        }
        return result;
    }

    /** One row of one probe of one tick, folded and rendered the way a run renders it. */
    static String line(long tick, String world, String probe, int probeIndex, String regionId,
                       long entitySeq, double[] values) {
        StateHasher.Slice slice = new StateHasher.Slice(world, regionId, probeIndex, entitySeq,
            values[0], values[1], values[2], values[3], values[4], values[5], values[6], values[7],
            0L, tick, probeIndex);
        DomainHash hash = StateHasher.hash(probe, tick, List.of(slice), HashWhitelist.bitexact());
        StringBuilder text = new StringBuilder("digest.row=");
        text.append(tick).append('|').append(world).append('|').append(probe).append('|')
            .append(regionId).append('|').append(probeIndex).append('|').append(entitySeq);
        for (double value : values) {
            text.append('|').append(Long.toHexString(Double.doubleToRawLongBits(value)));
        }
        text.append("|0|").append(tick).append('|').append(probeIndex).append('|')
            .append(Long.toHexString(hash.value())).append('|').append(hash.algorithmId())
            .append('|').append(Long.toHexString(hash.segmentHeaderDigest())).append('|')
            .append(Long.toHexString(hash.rows().rowDigests()[0]));
        return text.toString();
    }

    private static String dump(double chunkRows, double lightRows) {
        return line(7L, "minecraft:overworld", "chunk", 0, "r.0.3", 12L,
            new double[]{chunkRows, 0.0, 12.0, 0.0, 1.0, 7.0, chunkRows, 1.0})
            + "\n" + line(7L, "minecraft:overworld", "light", 1, "r.-2.-1", 4L,
            new double[]{lightRows, 0.0, 4.0, 0.0, 1.0, 7.0, lightRows, 1.0})
            + "\n";
    }

    /** The two dumps, from the process environment first and from a declaration file otherwise, so
     * a runner that cannot reach the test environment can still hand them over. */
    private static Map<String, String> declaration() throws IOException {
        Map<String, String> declared = new LinkedHashMap<>();
        put(declared, "a", System.getenv("PRTS_DIGEST_RUN_A"));
        put(declared, "b", System.getenv("PRTS_DIGEST_RUN_B"));
        put(declared, "report", System.getenv("PRTS_DIGEST_REPORT"));
        put(declared, "from", System.getenv("PRTS_DIGEST_FROM"));
        put(declared, "to", System.getenv("PRTS_DIGEST_TO"));
        put(declared, "a", System.getProperty("prts.digest.a"));
        put(declared, "b", System.getProperty("prts.digest.b"));
        put(declared, "report", System.getProperty("prts.digest.report"));
        put(declared, "from", System.getProperty("prts.digest.from"));
        put(declared, "to", System.getProperty("prts.digest.to"));
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

    private static Map<String, DomainHash> hashAll(Map<String, StateHasher.Slice> rows) {
        Map<String, DomainHash> hashed = new LinkedHashMap<>();
        for (Map.Entry<String, StateHasher.Slice> entry : rows.entrySet()) {
            String[] parts = entry.getKey().split("\\|", -1);
            DomainHash hash = StateHasher.hash(parts[2], Long.parseLong(parts[0]),
                List.of(entry.getValue()), HashWhitelist.bitexact());
            assertTrue(hash.comparable(), "a row did not hash: " + entry.getKey());
            hashed.put(entry.getKey(), hash);
        }
        return hashed;
    }

    private static List<String> unpaired(Set<String> left, Set<String> right) {
        List<String> only = new ArrayList<>();
        for (String key : left) {
            if (!right.contains(key)) {
                only.add(key);
            }
        }
        for (String key : right) {
            if (!left.contains(key)) {
                only.add(key);
            }
        }
        only.sort(null);
        return only;
    }

    /** Reads one dump. The key of a row is tick, world and probe: a window that wrapped twice over
     * one tick keeps its newest row, which is the one that run folded last. */
    private static Map<String, StateHasher.Slice> read(Path dump, long from, long to)
        throws IOException {
        Map<String, StateHasher.Slice> rows = new TreeMap<>();
        for (String text : Files.readAllLines(dump, StandardCharsets.UTF_8)) {
            if (!text.startsWith("digest.row=")) {
                continue;
            }
            String[] parts = text.substring("digest.row=".length()).split("\\|", -1);
            assertEquals(21, parts.length, "a digest row is not the shape this reader folds: " + text);
            long tick = Long.parseLong(parts[0]);
            if (tick < from || tick >= to) {
                continue;
            }
            StateHasher.Slice slice = new StateHasher.Slice(parts[1], parts[3],
                Long.parseLong(parts[4]), Long.parseLong(parts[5]), bits(parts[6]), bits(parts[7]),
                bits(parts[8]), bits(parts[9]), bits(parts[10]), bits(parts[11]), bits(parts[12]),
                bits(parts[13]), Long.parseLong(parts[14]), Long.parseLong(parts[15]),
                Long.parseLong(parts[16]));
            DomainHash rebuilt = StateHasher.hash(parts[2], tick, List.of(slice),
                HashWhitelist.bitexact());
            assertEquals(Long.parseUnsignedLong(parts[17], 16), rebuilt.value(),
                "a digest row does not fold back to the value its run exported: " + text);
            assertEquals(parts[18], rebuilt.algorithmId(),
                "a digest row names another algorithm than the fold: " + text);
            rows.put(parts[0] + "|" + parts[1] + "|" + parts[2], slice);
        }
        return rows;
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

    private static double bits(String hex) {
        return Double.longBitsToDouble(Long.parseUnsignedLong(hex, 16));
    }
}
