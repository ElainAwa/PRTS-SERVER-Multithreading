/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.diff;

import java.util.Map;

/**
 * The hash of one committed frame, with the algorithm recorded and the descent maps kept for
 * locating a fork.
 *
 * <p>A hash that could not be taken is still a value: it answers {@code available = false} with the
 * reason, so the caller records a missing reading instead of comparing a placeholder number. The
 * descent maps are per-world, per-region, per-batch, per-entity and per-field digests, which is what
 * turns a mismatch into a location instead of a verdict.</p>
 *
 * @param domainId       the domain that was hashed
 * @param tickIndex      the tick the frame belongs to
 * @param algorithmId    the algorithm that produced the value
 * @param value          the folded value
 * @param available      whether a hash was produced at all
 * @param failure        why not, when none was produced
 * @param worldDigests   per-world digests
 * @param regionDigests  per-region digests, keyed world then region
 * @param batchDigests   per-batch digests
 * @param entityDigests  per-entity digests
 * @param fieldDigests   per-field digests
 */
public record DomainHash(String domainId, long tickIndex, String algorithmId, long value,
                         boolean available, Failure failure, Map<String, Long> worldDigests,
                         Map<String, Long> regionDigests, Map<Long, Long> batchDigests,
                         Map<Long, Long> entityDigests, Map<String, Long> fieldDigests) {

    /** Why a hash could not be taken. */
    public enum Failure {

        /** The range had no rows at all. */
        EMPTY("empty"),
        /** The whitelist and its exclusions overlapped. */
        CROSSED("crossed"),
        /** The algorithm identifier was missing. */
        UNKNOWN_ALGORITHM("unknownAlgorithm");

        private final String key;

        Failure(String key) {
            this.key = key;
        }

        /** @return the stable name this failure is published under */
        public String key() {
            return key;
        }
    }

    /** Validates the hash identity. */
    public DomainHash {
        if (domainId == null || domainId.isEmpty()) {
            throw new IllegalArgumentException("a hash needs a domain");
        }
        worldDigests = Map.copyOf(worldDigests);
        regionDigests = Map.copyOf(regionDigests);
        batchDigests = Map.copyOf(batchDigests);
        entityDigests = Map.copyOf(entityDigests);
        fieldDigests = Map.copyOf(fieldDigests);
    }

    /**
     * Returns a hash that was not produced.
     *
     * @param domainId  the domain that could not be hashed
     * @param tickIndex the tick it would have covered
     * @param failure   the reason
     * @return the unavailable hash
     */
    public static DomainHash unavailable(String domainId, long tickIndex, Failure failure) {
        return new DomainHash(domainId, tickIndex, "", 0L, false, failure, Map.of(), Map.of(),
            Map.of(), Map.of(), Map.of());
    }

    /** @return whether the hash carries the information a comparison needs */
    public boolean comparable() {
        return available && algorithmId != null && !algorithmId.isEmpty();
    }
}
