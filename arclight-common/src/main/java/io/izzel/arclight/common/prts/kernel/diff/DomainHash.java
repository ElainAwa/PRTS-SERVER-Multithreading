/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.diff;

import java.util.LinkedHashMap;
import java.util.Map;

/** A hash that could not be taken is still a value: it answers {@code available = false} with the
 * reason, so the caller records a missing reading instead of comparing a placeholder number.
 *
 * <p>A taken hash carries its per-row sidecar ({@link RowDigests}), which is what lets a
 * difference be narrowed to one row. The older per-entity map is still readable through
 * {@link #entityDigests()}, rebuilt from the sidecar on demand. */
public record DomainHash(String domainId, long tickIndex, String algorithmId, long value,
                         boolean available, Failure failure, Map<String, Long> worldDigests,
                         Map<String, Long> regionDigests, Map<String, Long> batchDigests,
                         Map<String, Long> fieldDigests, RowDigests rows) {

    public enum Failure {

        EMPTY("empty"),
        CROSSED("crossed"),
        UNKNOWN_ALGORITHM("unknownAlgorithm");

        private final String key;

        Failure(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }
    }

    /** The per-row sidecar of one frame digest: the offset of a row is the ordinal a fork is named
     * by. The arrays belong to one frame and are never written after it is hashed. */
    public record RowDigests(String layoutId, long headerDigest, StateHasher.Slice[] rowSlices,
                             long[] rowDigests, long[] rowEntitySeqs) {

        private static final RowDigests NONE = new RowDigests("", 0L, new StateHasher.Slice[0],
            new long[0], new long[0]);

        public RowDigests {
            if (rowSlices == null || rowDigests == null || rowEntitySeqs == null
                || rowSlices.length != rowDigests.length
                || rowDigests.length != rowEntitySeqs.length) {
                throw new IllegalArgumentException("a row index needs three arrays of one length");
            }
        }

        /** The sidecar of a frame that kept no rows. */
        public static RowDigests none() {
            return NONE;
        }

        public int size() {
            return rowDigests.length;
        }

        /** Whether this sidecar can be used to name a row. */
        public boolean located() {
            return !layoutId.isEmpty() && rowDigests.length > 0;
        }

        /** The row the offset names; reads zero for an offset outside the frame. */
        public StateHasher.Slice slice(int ordinal) {
            return ordinal < 0 || ordinal >= rowSlices.length ? null : rowSlices[ordinal];
        }

        /** The entity id the offset names; reads minus one for an offset outside the frame. */
        public long entitySeq(int ordinal) {
            return ordinal < 0 || ordinal >= rowEntitySeqs.length ? -1L : rowEntitySeqs[ordinal];
        }

        /** The per-entity view of this frame, holding the keys and values the fold published. */
        public Map<String, Long> toEntityDigests() {
            Map<String, Long> digests = new LinkedHashMap<>(Math.max(16, rowDigests.length * 2));
            for (int ordinal = 0; ordinal < rowDigests.length; ordinal++) {
                StateHasher.Slice slice = rowSlices[ordinal];
                digests.put(slice.worldId() + "|" + slice.regionId() + "|" + slice.batchId() + "|"
                    + slice.entitySeq(), rowDigests[ordinal]);
            }
            return Map.copyOf(digests);
        }
    }

    public DomainHash {
        if (domainId == null || domainId.isEmpty()) {
            throw new IllegalArgumentException("a hash needs a domain");
        }
        worldDigests = Map.copyOf(worldDigests);
        regionDigests = Map.copyOf(regionDigests);
        batchDigests = Map.copyOf(batchDigests);
        fieldDigests = Map.copyOf(fieldDigests);
        rows = rows == null ? RowDigests.none() : rows;
    }

    /** Returns a hash that was not produced. */
    public static DomainHash unavailable(String domainId, long tickIndex, Failure failure) {
        return new DomainHash(domainId, tickIndex, "", 0L, false, failure, Map.of(), Map.of(),
            Map.of(), Map.of(), RowDigests.none());
    }

    public boolean comparable() {
        return available && algorithmId != null && !algorithmId.isEmpty();
    }

    /** The header the row digests of this frame were folded into; zero when no frame was hashed. */
    public long segmentHeaderDigest() {
        return rows.headerDigest();
    }

    /** The per-entity view of this frame, rebuilt from the row sidecar on demand. */
    public Map<String, Long> entityDigests() {
        return rows.toEntityDigests();
    }
}
