/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.diff;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The fold order is fixed - world, region, entity sequence, field order - and the scalars are
 * folded as their raw bits, so two frames that differ in one bit of one coordinate produce
 * different hashes.
 *
 * <p>The frame is folded once, into arrays: the header once, one digest per row into a flat
 * {@code long[]}, and one running value. The per-world, per-region and per-batch digests are the
 * only maps left on the row path and they hold one entry per group, not one per row; the
 * per-entity map of the frame is rebuilt from the row sidecar when a reader asks for it. */
public final class StateHasher {

    /** Identifier of the bit-exact algorithm. */
    public static final String ALGORITHM_ID = "prts-state-fnv1a64-bitexact-v1";

    /** Identifier of the quantized form. */
    public static final String QUANTIZED_ALGORITHM_ID = "prts-state-fnv1a64-quantized-v1";

    /** Identifier of the per-row sidecar a committed frame publishes. */
    public static final String ROW_INDEX_ID = "prts-row-index-v1";

    /** Layout version of the hashed segment header. */
    public static final long LAYOUT_VERSION = 1L;

    /** Data version of the hashed segment header. */
    public static final long DATA_VERSION = 1L;

    /** Layout version of the per-row sidecar. It is folded into the segment header, not into the
     * value, so a reader can tell which sidecar layout it holds without moving any hash value. */
    public static final long ROW_INDEX_VERSION = 1L;

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private StateHasher() {
    }

    /** One entity row of a committed frame. */
    public record Slice(String worldId, String regionId, long batchId, long entitySeq, double x,
                        double y, double z, double yaw, double pitch, double velX, double velY,
                        double velZ, long flags, long slotGeneration, long segmentRef) {
    }

    /** Hashes one committed frame. The value is unchanged by the sidecar: the header is folded the
     * way the value always folded it, every row digest is the same fold, and the rows are folded in
     * the same order, so a frame that hashed to a value before still hashes to it. */
    public static DomainHash hash(String domainId, long tickIndex, List<Slice> slices,
                                  HashWhitelist whitelist) {
        if (whitelist == null) {
            return DomainHash.unavailable(domainId, tickIndex, DomainHash.Failure.CROSSED);
        }
        if (slices == null || slices.isEmpty()) {
            return DomainHash.unavailable(domainId, tickIndex, DomainHash.Failure.EMPTY);
        }
        List<Slice> ordered = new ArrayList<>(slices);
        ordered.sort(Comparator.comparing(Slice::worldId).thenComparing(Slice::regionId)
            .thenComparingLong(Slice::entitySeq));
        String algorithm = whitelist.quantized() ? QUANTIZED_ALGORITHM_ID : ALGORITHM_ID;
        List<String> fields = whitelist.fields();
        int fieldCount = fields.size();
        int rowCount = ordered.size();
        long total = mixString(OFFSET_BASIS, algorithm);
        total = mixLong(total, rowCount);
        Map<String, Long> worldDigests = new LinkedHashMap<>();
        Map<String, Long> regionDigests = new LinkedHashMap<>();
        Map<String, Long> batchDigests = new LinkedHashMap<>();
        long[] fieldDigests = new long[fieldCount];
        Arrays.fill(fieldDigests, OFFSET_BASIS);
        long[] fieldValues = new long[fieldCount];
        Slice[] rowSlices = new Slice[rowCount];
        long[] rowDigests = new long[rowCount];
        long[] rowEntitySeqs = new long[rowCount];
        String lastWorld = null;
        String lastRegionId = null;
        String lastRegionKey = null;
        long lastBatchId = 0L;
        String lastBatchKey = null;
        for (int row = 0; row < rowCount; row++) {
            Slice slice = ordered.get(row);
            long sliceDigest = OFFSET_BASIS;
            for (int index = 0; index < fieldCount; index++) {
                long value = fieldValue(slice, fields.get(index), whitelist);
                fieldValues[index] = value;
                sliceDigest = mixLong(sliceDigest, value);
            }
            total = mixLong(total, sliceDigest);
            String world = slice.worldId();
            // The keys of the region and batch groups are built once per group, not once per row:
            // the rows arrive sorted, so a group repeats and its key is not rebuilt for every row.
            Long worldDigest = worldDigests.get(world);
            worldDigests.put(world, worldDigest == null ? mixLong(OFFSET_BASIS, sliceDigest)
                : mixLong(worldDigest, sliceDigest));
            if (lastRegionKey == null || !world.equals(lastWorld)
                || !slice.regionId().equals(lastRegionId)) {
                lastWorld = world;
                lastRegionId = slice.regionId();
                lastRegionKey = world + "|" + lastRegionId;
                lastBatchKey = null;
            }
            Long regionDigest = regionDigests.get(lastRegionKey);
            regionDigests.put(lastRegionKey, regionDigest == null ? mixLong(OFFSET_BASIS, sliceDigest)
                : mixLong(regionDigest, sliceDigest));
            if (lastBatchKey == null || lastBatchId != slice.batchId()) {
                lastBatchId = slice.batchId();
                lastBatchKey = lastRegionKey + "|" + slice.batchId();
            }
            Long batchDigest = batchDigests.get(lastBatchKey);
            batchDigests.put(lastBatchKey, batchDigest == null ? mixLong(OFFSET_BASIS, sliceDigest)
                : mixLong(batchDigest, sliceDigest));
            for (int index = 0; index < fieldCount; index++) {
                fieldDigests[index] = mixLong(fieldDigests[index], fieldValues[index]);
            }
            rowSlices[row] = slice;
            rowDigests[row] = sliceDigest;
            rowEntitySeqs[row] = slice.entitySeq();
        }
        Map<String, Long> fieldView = new LinkedHashMap<>();
        for (int index = 0; index < fieldCount; index++) {
            fieldView.put(fields.get(index), fieldDigests[index]);
        }
        long header = headerDigest(domainId, tickIndex, algorithm, rowCount, fields);
        return new DomainHash(domainId, tickIndex, algorithm, total, true, null, worldDigests,
            regionDigests, batchDigests, fieldView,
            new DomainHash.RowDigests(ROW_INDEX_ID, header, rowSlices, rowDigests, rowEntitySeqs));
    }

    /** The once-per-frame header a located digest carries. It names the frame - domain, tick, the
     * algorithm, the row count, the versions of the layout and of the row sidecar, and the fields
     * that were folded - and it is deliberately not the value: a row whose values changed leaves it
     * untouched, so the header alone can never answer which row moved. */
    private static long headerDigest(String domainId, long tickIndex, String algorithm, int rows,
                                     List<String> fields) {
        long header = mixString(OFFSET_BASIS, domainId);
        header = mixLong(header, tickIndex);
        header = mixString(header, algorithm);
        header = mixLong(header, rows);
        header = mixLong(header, LAYOUT_VERSION);
        header = mixLong(header, DATA_VERSION);
        header = mixLong(header, ROW_INDEX_VERSION);
        header = mixLong(header, fields.size());
        for (String field : fields) {
            header = mixString(header, field);
        }
        return header;
    }

    private static long fieldValue(Slice slice, String field, HashWhitelist whitelist) {
        return switch (field) {
            case "position" -> mixLong(mixLong(mixLong(OFFSET_BASIS, bits(slice.x(), whitelist)),
                bits(slice.y(), whitelist)), bits(slice.z(), whitelist));
            case "orientation" -> mixLong(mixLong(OFFSET_BASIS, bits(slice.yaw(), whitelist)),
                bits(slice.pitch(), whitelist));
            case "velocity" -> mixLong(mixLong(mixLong(OFFSET_BASIS, bits(slice.velX(), whitelist)),
                bits(slice.velY(), whitelist)), bits(slice.velZ(), whitelist));
            case "flags" -> mixLong(OFFSET_BASIS, slice.flags());
            case "entitySeq" -> mixLong(OFFSET_BASIS, slice.entitySeq());
            case "layoutVersion" -> mixLong(OFFSET_BASIS, LAYOUT_VERSION);
            case "slotGeneration" -> mixLong(OFFSET_BASIS, slice.slotGeneration());
            case "segmentRef" -> mixLong(OFFSET_BASIS, slice.segmentRef());
            case "worldId" -> mixString(OFFSET_BASIS, slice.worldId());
            case "regionId" -> mixString(OFFSET_BASIS, slice.regionId());
            case "dataVersion" -> mixLong(OFFSET_BASIS, DATA_VERSION);
            default -> throw new IllegalArgumentException("unknown hash field: " + field);
        };
    }

    private static long bits(double value, HashWhitelist whitelist) {
        long raw = Double.doubleToLongBits(value);
        if (whitelist.quantized() && whitelist.quantumBits() > 0) {
            long mask = (1L << whitelist.quantumBits()) - 1L;
            raw &= ~mask;
        }
        return raw;
    }

    /** Mixes one value into a running FNV-1a fold. */
    public static long mixLong(long seed, long value) {
        return (seed ^ value) * PRIME;
    }

    /** Mixes one string into a running FNV-1a fold. */
    public static long mixString(long seed, String value) {
        long running = seed;
        for (int i = 0; i < value.length(); i++) {
            running = mixLong(running, value.charAt(i));
        }
        return running;
    }
}
