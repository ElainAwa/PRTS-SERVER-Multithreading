/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The cost of three segment digest shapes over the captured rows of the five replicated entity
 * classes, and whether a wrong row can still be located after a segment digest differs.
 *
 * The rows are the same vector the frame carries: one captured state per host entry row, populated
 * the way the capture step of its model populates it. The shapes differ only in what the segment
 * keeps: the production digest keeps a map entry per row (world, region, batch, entity, field), a
 * header-only shape keeps nothing per row, and a located shape keeps one row digest plus its
 * entity sequence in a flat array. A micro-measurement, not part of the test suite: it is run by
 * hand and writes to stdout.
 *
 *   measure <rows_per_segment> <segments> <rounds> <ticks> <warmup>
 *   locate  <rows_per_segment> <injected_ordinal> <repeats>
 */
package io.izzel.arclight.common.prts.kernel.diff;

import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.replica.TickState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SegmentDigestBench {

    /** The row mixture of the recorded load: armour stand, villager, bat, marker, cloud. */
    private static final int[] MIX = {18, 9, 4, 18, 12};
    private static final int MIX_ROWS = 61;
    private static final String WORLD = "bench:world";
    private static final String REGION = "r0.0";
    private static final String DOMAIN = "entity";
    private static final long TICK = 1200L;
    private static final long BASIS = 0xcbf29ce484222325L;
    private static final HashWhitelist WHITELIST = HashWhitelist.bitexact();

    private SegmentDigestBench() {
    }

    public static void main(String[] args) {
        String mode = args.length > 0 ? args[0] : "measure";
        if ("locate".equals(mode)) {
            int rowsPerSegment = Integer.parseInt(args[1]);
            int injected = Integer.parseInt(args[2]);
            int repeats = args.length > 3 ? Integer.parseInt(args[3]) : 2000;
            locate(rowsPerSegment, injected, repeats);
            return;
        }
        int rowsPerSegment = Integer.parseInt(args[1]);
        int segments = Integer.parseInt(args[2]);
        int rounds = args.length > 3 ? Integer.parseInt(args[3]) : 7;
        int ticks = args.length > 4 ? Integer.parseInt(args[4]) : 200;
        int warmup = args.length > 5 ? Integer.parseInt(args[5]) : 100;
        System.out.printf("# SegmentDigestBench rows_per_segment=%d segments=%d rounds=%d"
                + " ticks=%d warmup=%d java=%s%n", rowsPerSegment, segments, rounds, ticks, warmup,
            System.getProperty("java.version"));
        System.out.println("# shape\tround\trows\tns_tick\tns_row\tns_segment");
        List<List<StateHasher.Slice>> lists = segments(rowsPerSegment, segments);
        List<long[]> arrays = new ArrayList<>(segments);
        for (int index = 0; index < segments; index++) {
            arrays.add(new long[rowsPerSegment]);
        }
        for (int round = 0; round < rounds; round++) {
            run("rows", lists, arrays, ticks, warmup, round);
            run("mirror", lists, arrays, ticks, warmup, round);
            run("header", lists, arrays, ticks, warmup, round);
            run("located", lists, arrays, ticks, warmup, round);
        }
    }

    private static void run(String shape, List<List<StateHasher.Slice>> lists, List<long[]> arrays,
                            int ticks, int warmup, int round) {
        long nanos = 0L;
        long rows = 0L;
        long segments = lists.size();
        long sink = 0L;
        long measured = 0L;
        for (int index = 0; index < warmup + ticks; index++) {
            boolean counted = index >= warmup;
            long startedAt = System.nanoTime();
            switch (shape) {
                case "rows" -> {
                    for (List<StateHasher.Slice> list : lists) {
                        sink += StateHasher.hash(DOMAIN, TICK, list, WHITELIST).value();
                    }
                }
                case "mirror" -> sink += mirrorDigest(lists);
                case "header" -> {
                    for (int segment = 0; segment < segments; segment++) {
                        List<StateHasher.Slice> list = lists.get(segment);
                        long total = headerDigest(segment, list.size());
                        for (int local = 0; local < list.size(); local++) {
                            total = StateHasher.mixLong(total, rowDigest(list.get(local)));
                        }
                        sink += total;
                    }
                }
                case "located" -> {
                    for (int segment = 0; segment < segments; segment++) {
                        List<StateHasher.Slice> list = lists.get(segment);
                        long[] rowDigests = arrays.get(segment);
                        long total = headerDigest(segment, list.size());
                        for (int local = 0; local < list.size(); local++) {
                            long digest = rowDigest(list.get(local));
                            rowDigests[local] = digest;
                            total = StateHasher.mixLong(total, digest);
                        }
                        sink += total;
                    }
                }
                default -> throw new IllegalArgumentException("unknown shape " + shape);
            }
            long spent = System.nanoTime() - startedAt;
            if (counted) {
                nanos += spent;
                rows += (long) segments * lists.get(0).size();
                measured++;
            }
        }
        double ticksMeasured = (double) measured;
        double nsTick = nanos / ticksMeasured;
        double perTickRows = (double) segments * lists.get(0).size();
        double nsRow = nsTick / perTickRows;
        System.out.printf("%s\t%d\t%d\t%.2f\t%.4f\t%.2f%n", shape, round,
            (long) perTickRows, nsTick, nsRow, nsTick / segments);
        if (sink == Long.MIN_VALUE) {
            throw new IllegalStateException("the digest went missing");
        }
    }

    /** The production digest, step by step, so a cost can be attributed to each step. */
    private static long mirrorDigest(List<List<StateHasher.Slice>> lists) {
        long total = 0L;
        for (List<StateHasher.Slice> list : lists) {
            total += mirrorSegment(list).value();
        }
        return total;
    }

    private static DomainHash mirrorSegment(List<StateHasher.Slice> list) {
        {
            List<StateHasher.Slice> ordered = new ArrayList<>(list);
            ordered.sort(Comparator.comparing(StateHasher.Slice::worldId)
                .thenComparing(StateHasher.Slice::regionId).thenComparingLong(
                    StateHasher.Slice::entitySeq));
            long digest = StateHasher.mixString(BASIS, StateHasher.ALGORITHM_ID);
            digest = StateHasher.mixLong(digest, ordered.size());
            Map<String, Long> worldDigests = new LinkedHashMap<>();
            Map<String, Long> regionDigests = new LinkedHashMap<>();
            Map<String, Long> batchDigests = new LinkedHashMap<>();
            Map<String, Long> entityDigests = new LinkedHashMap<>();
            Map<String, Long> fieldDigests = new LinkedHashMap<>();
            for (String field : WHITELIST.fields()) {
                fieldDigests.put(field, BASIS);
            }
            for (StateHasher.Slice slice : ordered) {
                long sliceDigest = rowDigest(slice);
                digest = StateHasher.mixLong(digest, sliceDigest);
                worldDigests.merge(slice.worldId(), StateHasher.mixLong(BASIS, sliceDigest),
                    (left, right) -> StateHasher.mixLong(left, sliceDigest));
                String regionKey = slice.worldId() + "|" + slice.regionId();
                regionDigests.merge(regionKey, StateHasher.mixLong(BASIS, sliceDigest),
                    (left, right) -> StateHasher.mixLong(left, sliceDigest));
                String batchKey = regionKey + "|" + slice.batchId();
                batchDigests.merge(batchKey, StateHasher.mixLong(BASIS, sliceDigest),
                    (left, right) -> StateHasher.mixLong(left, sliceDigest));
                entityDigests.put(batchKey + "|" + slice.entitySeq(), sliceDigest);
                for (String field : WHITELIST.fields()) {
                    fieldDigests.merge(field, fieldValue(slice, field),
                        (left, right) -> StateHasher.mixLong(left, right));
                }
            }
            return new DomainHash(DOMAIN, TICK, StateHasher.ALGORITHM_ID, digest, true,
                null, worldDigests, regionDigests, batchDigests, entityDigests, fieldDigests);
        }
    }

    /** Whether a wrong row can be located, and what the location costs. */
    private static void locate(int rowsPerSegment, int injected, int repeats) {
        List<StateHasher.Slice> committed = slices(rowsPerSegment, 0);
        List<StateHasher.Slice> changed = new ArrayList<>(committed);
        StateHasher.Slice row = committed.get(injected);
        changed.set(injected, new StateHasher.Slice(row.worldId(), row.regionId(), row.batchId(),
            row.entitySeq(), row.x(), row.y(), row.z(), row.yaw(), row.pitch(), row.velX() + 1.0e-6,
            row.velY(), row.velZ(), row.flags(), row.slotGeneration(), row.segmentRef()));
        DomainHash before = StateHasher.hash(DOMAIN, TICK, committed, WHITELIST);
        DomainHash after = StateHasher.hash(DOMAIN, TICK, changed, WHITELIST);
        long[] digestsBefore = new long[rowsPerSegment];
        long[] digestsAfter = new long[rowsPerSegment];
        long[] seqs = new long[rowsPerSegment];
        long headerBefore = headerDigest(0, rowsPerSegment);
        long headerAfter = headerDigest(0, rowsPerSegment);
        long totalBefore = headerBefore;
        long totalAfter = headerAfter;
        for (int local = 0; local < rowsPerSegment; local++) {
            StateHasher.Slice slice = committed.get(local);
            seqs[local] = slice.entitySeq();
            digestsBefore[local] = rowDigest(slice);
            digestsAfter[local] = rowDigest(changed.get(local));
            totalBefore = StateHasher.mixLong(totalBefore, digestsBefore[local]);
            totalAfter = StateHasher.mixLong(totalAfter, digestsAfter[local]);
        }
        long committedTotal = before.value();
        long changedTotal = after.value();
        System.out.printf("# locate rows_per_segment=%d injected_ordinal=%d injected_entity=%d"
                + " repeats=%d%n", rowsPerSegment, injected, row.entitySeq(), repeats);
        System.out.println("# shape\tdiffers\tlocated\tordinal\tother_rows\tns_locate");
        report("rows", committedTotal != changedTotal, mapLocate(before, after, committed), repeats,
            () -> mapLocate(before, after, committed));
        report("header", totalBefore != totalAfter, new Located(-1L, -1L, 0L), repeats,
            () -> new Located(-1L, -1L, 0L));
        report("located", totalBefore != totalAfter, arrayLocate(digestsBefore, digestsAfter, seqs),
            repeats, () -> arrayLocate(digestsBefore, digestsAfter, seqs));
    }

    private static void report(String shape, boolean differs, Located located, int repeats,
                               Locator locator) {
        long startedAt = System.nanoTime();
        for (int index = 0; index < repeats; index++) {
            locator.locate();
        }
        long nanos = System.nanoTime() - startedAt;
        System.out.printf("%s\t%s\t%d\t%d\t%d\t%.1f%n", shape, differs, located.entityId(),
            located.ordinal(), located.others(), (double) nanos / repeats);
    }

    /** The production descent: the map keys that differ name the row. */
    private static Located mapLocate(DomainHash before, DomainHash after,
                                     List<StateHasher.Slice> committed) {
        long found = -1L;
        long others = 0L;
        for (Map.Entry<String, Long> entry : before.entityDigests().entrySet()) {
            Long other = after.entityDigests().get(entry.getKey());
            if (other == null || other.longValue() != entry.getValue().longValue()) {
                if (found < 0L) {
                    String key = entry.getKey();
                    found = Long.parseLong(key.substring(key.lastIndexOf('|') + 1));
                } else {
                    others++;
                }
            }
        }
        long ordinal = -1L;
        for (int index = 0; index < committed.size(); index++) {
            if (committed.get(index).entitySeq() == found) {
                ordinal = index;
                break;
            }
        }
        return new Located(found, ordinal, others);
    }

    private static Located arrayLocate(long[] before, long[] after, long[] seqs) {
        for (int index = 0; index < before.length; index++) {
            if (before[index] != after[index]) {
                long others = 0L;
                for (int rest = index + 1; rest < before.length; rest++) {
                    if (before[rest] != after[rest]) {
                        others++;
                    }
                }
                return new Located(seqs[index], index, others);
            }
        }
        return new Located(-1L, -1L, 0L);
    }

    private record Located(long entityId, long ordinal, long others) {
    }

    @FunctionalInterface
    private interface Locator {
        Located locate();
    }

    private static List<List<StateHasher.Slice>> segments(int rowsPerSegment, int segments) {
        List<List<StateHasher.Slice>> lists = new ArrayList<>(segments);
        for (int segment = 0; segment < segments; segment++) {
            lists.add(slices(rowsPerSegment, segment));
        }
        return lists;
    }

    private static List<StateHasher.Slice> slices(int rows, int segment) {
        List<StateHasher.Slice> list = new ArrayList<>(rows);
        for (int index = 0; index < rows; index++) {
            TickState state = row(segment * rows + index);
            list.add(new StateHasher.Slice(WORLD, REGION, segment + 1L, state.entityId, state.x,
                state.y, state.z, state.yRot, state.xRot, state.vx, state.vy, state.vz,
                state.onGround ? 1L : 0L, 0L, segment + 1L));
        }
        return list;
    }

    static long rowDigest(StateHasher.Slice slice) {
        long digest = BASIS;
        for (String field : WHITELIST.fields()) {
            digest = StateHasher.mixLong(digest, fieldValue(slice, field));
        }
        return digest;
    }

    private static long headerDigest(int segment, int rows) {
        long header = StateHasher.mixString(BASIS, DOMAIN);
        header = StateHasher.mixLong(header, TICK);
        header = StateHasher.mixLong(header, segment);
        header = StateHasher.mixLong(header, rows);
        header = StateHasher.mixString(header, WORLD);
        header = StateHasher.mixString(header, REGION);
        header = StateHasher.mixLong(header, StateHasher.LAYOUT_VERSION);
        header = StateHasher.mixLong(header, StateHasher.DATA_VERSION);
        return header;
    }

    private static long fieldValue(StateHasher.Slice slice, String field) {
        return switch (field) {
            case "position" -> StateHasher.mixLong(StateHasher.mixLong(
                StateHasher.mixLong(BASIS, bits(slice.x())), bits(slice.y())), bits(slice.z()));
            case "orientation" -> StateHasher.mixLong(StateHasher.mixLong(BASIS,
                bits(slice.yaw())), bits(slice.pitch()));
            case "velocity" -> StateHasher.mixLong(StateHasher.mixLong(StateHasher.mixLong(BASIS,
                bits(slice.velX())), bits(slice.velY())), bits(slice.velZ()));
            case "flags" -> StateHasher.mixLong(BASIS, slice.flags());
            case "entitySeq" -> StateHasher.mixLong(BASIS, slice.entitySeq());
            case "layoutVersion" -> StateHasher.mixLong(BASIS, StateHasher.LAYOUT_VERSION);
            case "slotGeneration" -> StateHasher.mixLong(BASIS, slice.slotGeneration());
            case "segmentRef" -> StateHasher.mixLong(BASIS, slice.segmentRef());
            case "worldId" -> StateHasher.mixString(BASIS, slice.worldId());
            case "regionId" -> StateHasher.mixString(BASIS, slice.regionId());
            case "dataVersion" -> StateHasher.mixLong(BASIS, StateHasher.DATA_VERSION);
            default -> throw new IllegalArgumentException("unknown hash field: " + field);
        };
    }

    private static long bits(double value) {
        long raw = Double.doubleToLongBits(value);
        if (WHITELIST.quantized() && WHITELIST.quantumBits() > 0) {
            long mask = (1L << WHITELIST.quantumBits()) - 1L;
            raw &= ~mask;
        }
        return raw;
    }

    /** One captured row of the mixture: the vector its model captures, not a synthetic block. */
    private static TickState row(int index) {
        TickState state = new TickState();
        state.entityId = 100000 + index;
        int slot = index % MIX_ROWS;
        int kind = MIX.length - 1;
        int cursor = 0;
        for (int candidate = 0; candidate < MIX.length; candidate++) {
            cursor += MIX[candidate];
            if (slot < cursor) {
                kind = candidate;
                break;
            }
        }
        state.x = 528.5 + (index % 64);
        state.y = 64.0;
        state.z = 528.5 + (index / 64);
        state.vx = 0.1;
        state.vy = -0.08;
        state.vz = 0.001 * index;
        state.xRot = 0.0F;
        state.yRot = index % 360;
        state.onGround = (index & 1) == 0;
        if (kind != 3) {
            state.tickCount = 1000 + index;
            state.xo = state.x;
            state.yo = state.y;
            state.zo = state.z;
            state.yRotO = state.yRot;
            state.xRotO = state.xRot;
            state.yBodyRot = state.yRot;
            state.yBodyRotO = state.yRot;
            state.yHeadRot = state.yRot;
            state.yHeadRotO = state.yRot;
            state.xxa = 0.0F;
            state.yya = 0.0F;
            state.zza = 0.0F;
            state.walkDist = 0.5F;
            state.walkDistO = 0.25F;
            state.animStep = 0.5F;
            state.animStepO = 0.25F;
            state.appliedScale = 1.0F;
            state.headTurnLimit = 75;
            state.bbHeight = kind == 2 ? 0.9F : 1.95F;
        }
        if (kind == 0) {
            state.lastPosMoved = true;
        } else if (kind == 1) {
            state.age = index % 2 == 0 ? 0 : -1;
        } else if (kind == 2) {
            state.resting = (index & 2) == 0;
        } else if (kind == 4) {
            state.walkDist = 0.5F;
            state.walkDistO = 0.25F;
            state.radius = 3.0F;
            state.radiusPerTick = 0.001F;
            state.waitTime = 0;
            state.duration = 200000;
        }
        return state;
    }
}
