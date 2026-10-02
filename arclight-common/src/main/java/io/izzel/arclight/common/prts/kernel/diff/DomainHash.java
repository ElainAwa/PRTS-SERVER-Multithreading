/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.diff;

import java.util.Map;

/** A hash that could not be taken is still a value: it answers {@code available = false} with the
 * reason, so the caller records a missing reading instead of comparing a placeholder number. */
public record DomainHash(String domainId, long tickIndex, String algorithmId, long value,
                         boolean available, Failure failure, Map<String, Long> worldDigests,
                         Map<String, Long> regionDigests, Map<String, Long> batchDigests,
                         Map<String, Long> entityDigests, Map<String, Long> fieldDigests) {

    /** Why a hash could not be taken. */
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

    /** Returns a hash that was not produced. */
    public static DomainHash unavailable(String domainId, long tickIndex, Failure failure) {
        return new DomainHash(domainId, tickIndex, "", 0L, false, failure, Map.of(), Map.of(),
            Map.of(), Map.of(), Map.of());
    }

    public boolean comparable() {
        return available && algorithmId != null && !algorithmId.isEmpty();
    }
}
