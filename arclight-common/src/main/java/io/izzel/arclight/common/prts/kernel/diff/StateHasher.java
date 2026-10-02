/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.diff;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Folds the whitelisted values of a committed frame into one 64-bit value.
 *
 * <p>The fold order is fixed - world, region, entity sequence, field order - and the scalars are
 * folded as their raw bits, so two frames that differ in one bit of one coordinate produce different
 * hashes. No clock, no random source and no iteration order of a hash table takes part. The
 * quantized form exists for a comparison across machines and is off unless a caller asks for it.</p>
 *
 * <p>The hasher also publishes per-level digests. They are what lets the comparison descend from a
 * mismatching tick to one entity and one field instead of stopping at "different".</p>
 */
public final class StateHasher {

    /** Identifier of the bit-exact algorithm. */
    public static final String ALGORITHM_ID = "prts-state-fnv1a64-bitexact-v1";

    /** Identifier of the quantized form. */
    public static final String QUANTIZED_ALGORITHM_ID = "prts-state-fnv1a64-quantized-v1";

    /** Layout version of the hashed segment header. */
    public static final long LAYOUT_VERSION = 1L;

    /** Data version of the hashed segment header. */
    public static final long DATA_VERSION = 1L;

    private static final long OFFSET_BASIS = 0xcbf29ce484222325L;
    private static final long PRIME = 0x100000001b3L;

    private StateHasher() {
    }

    /**
     * One entity row of a committed frame.
     *
     * @param worldId        the world the row belongs to
     * @param regionId       the region the row belongs to
     * @param batchId        the batch that produced the row
     * @param entitySeq      the entity sequence of the row
     * @param x              x position
     * @param y              y position
     * @param z              z position
     * @param yaw            yaw in degrees
     * @param pitch          pitch in degrees
     * @param velX           velocity on x
     * @param velY           velocity on y
     * @param velZ           velocity on z
     * @param flags          opaque flag word
     * @param slotGeneration the generation of the slot the row was read from
     * @param segmentRef     identity of the segment the row was read from
     */
    public record Slice(String worldId, String regionId, long batchId, long entitySeq, double x,
                        double y, double z, double yaw, double pitch, double velX, double velY,
                        double velZ, long flags, long slotGeneration, long segmentRef) {
    }

    /**
     * Hashes one committed frame.
     *
     * @param domainId   the domain the frame belongs to
     * @param tickIndex  the tick the frame was committed in
     * @param slices     the rows of the frame
     * @param whitelist  the fields to fold
     * @return the hash, or an unavailable hash when the range is empty
     */
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
        long total = mixString(OFFSET_BASIS, algorithm);
        total = mixLong(total, ordered.size());
        Map<String, Long> worldDigests = new LinkedHashMap<>();
        Map<String, Long> regionDigests = new LinkedHashMap<>();
        Map<String, Long> batchDigests = new LinkedHashMap<>();
        Map<String, Long> entityDigests = new LinkedHashMap<>();
        Map<String, Long> fieldDigests = new LinkedHashMap<>();
        for (String field : whitelist.fields()) {
            fieldDigests.put(field, OFFSET_BASIS);
        }
        for (Slice slice : ordered) {
            long sliceDigest = digestSlice(slice, whitelist);
            total = mixLong(total, sliceDigest);
            worldDigests.merge(slice.worldId(), mixLong(OFFSET_BASIS, sliceDigest),
                (left, right) -> mixLong(left, sliceDigest));
            String regionKey = slice.worldId() + "|" + slice.regionId();
            regionDigests.merge(regionKey, mixLong(OFFSET_BASIS, sliceDigest),
                (left, right) -> mixLong(left, sliceDigest));
            // Context-keyed so a descent can narrow a fork to its own world, region and batch.
            String batchKey = regionKey + "|" + slice.batchId();
            batchDigests.merge(batchKey, mixLong(OFFSET_BASIS, sliceDigest),
                (left, right) -> mixLong(left, sliceDigest));
            entityDigests.put(batchKey + "|" + slice.entitySeq(), sliceDigest);
            for (String field : whitelist.fields()) {
                fieldDigests.merge(field, fieldValue(slice, field, whitelist),
                    (left, right) -> mixLong(left, right));
            }
        }
        return new DomainHash(domainId, tickIndex, algorithm, total, true, null,
            worldDigests, regionDigests, batchDigests, entityDigests, fieldDigests);
    }

    private static long digestSlice(Slice slice, HashWhitelist whitelist) {
        long digest = OFFSET_BASIS;
        for (String field : whitelist.fields()) {
            digest = mixLong(digest, fieldValue(slice, field, whitelist));
        }
        return digest;
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

    /**
     * Mixes one value into a running FNV-1a fold.
     *
     * @param seed  the running value
     * @param value the value to mix
     * @return the next running value
     */
    public static long mixLong(long seed, long value) {
        return (seed ^ value) * PRIME;
    }

    /**
     * Mixes one string into a running FNV-1a fold.
     *
     * @param seed  the running value
     * @param value the string to mix
     * @return the next running value
     */
    public static long mixString(long seed, String value) {
        long running = seed;
        for (int i = 0; i < value.length(); i++) {
            running = mixLong(running, value.charAt(i));
        }
        return running;
    }
}