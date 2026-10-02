/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.diff;

import java.util.List;
import java.util.Set;

/**
 * What a state hash is allowed to include, and what it must exclude.
 *
 * <p>The whitelist is validated before it is used: an empty field list, a field that is also
 * excluded, or a field outside the known vocabulary is an error rather than an empty hash. The
 * defaults are bit exact; the quantized form exists but is off, because a quantized comparison
 * gives up bits the two arms of a same-process run do not need to give up.</p>
 *
 * @param fields      the fields the hash folds, in fold order
 * @param excludes    the sources a hash must never read
 * @param quantized   whether double values are rounded before hashing
 * @param quantumBits how many low mantissa bits are dropped when quantized
 */
public record HashWhitelist(List<String> fields, List<String> excludes, boolean quantized,
                            int quantumBits) {

    /** The fields a hash may fold: the kinematic values, the entity identity and the segment header. */
    public static final Set<String> KNOWN_FIELDS = Set.of("position", "orientation", "velocity",
        "flags", "entitySeq", "layoutVersion", "slotGeneration", "segmentRef", "worldId", "regionId",
        "dataVersion");

    /**
     * The default field order: position, orientation, velocity, flags, the entity identity, then the
     * header versions. The identity is folded so a row cannot be substituted for another entity.
     */
    public static final List<String> DEFAULT_FIELDS = List.of("position", "orientation", "velocity",
        "flags", "entitySeq", "layoutVersion", "slotGeneration", "segmentRef", "worldId", "regionId",
        "dataVersion");

    /** The sources a hash must never read. */
    public static final List<String> DEFAULT_EXCLUDES = List.of("observation counters", "trace",
        "wall clock", "unregistered sites");

    /** Validates the declaration. */
    public HashWhitelist {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("a hash needs a non-empty field list");
        }
        if (excludes == null) {
            throw new IllegalArgumentException("a hash needs its exclusions stated");
        }
        fields = List.copyOf(fields);
        excludes = List.copyOf(excludes);
        Set<String> seen = new java.util.HashSet<>();
        for (String field : fields) {
            if (!KNOWN_FIELDS.contains(field)) {
                throw new IllegalArgumentException("unknown hash field: " + field);
            }
            if (excludes.contains(field)) {
                throw new IllegalArgumentException("hash field is also excluded: " + field);
            }
            if (!seen.add(field)) {
                throw new IllegalArgumentException("hash field repeated: " + field);
            }
        }
        for (String excluded : excludes) {
            if (fields.contains(excluded)) {
                throw new IllegalArgumentException("excluded source is whitelisted: " + excluded);
            }
        }
        if (quantized && (quantumBits < 1 || quantumBits > 52)) {
            throw new IllegalArgumentException("a quantized hash needs one to fifty-two bits");
        }
    }

    /** @return the bit-exact default, which is what a same-process comparison uses */
    public static HashWhitelist bitexact() {
        return new HashWhitelist(DEFAULT_FIELDS, DEFAULT_EXCLUDES, false, 0);
    }

    /**
     * Returns the quantized form, kept for a comparison across machines.
     *
     * @param quantumBits how many low mantissa bits are dropped
     * @return the quantized whitelist
     */
    public static HashWhitelist quantized(int quantumBits) {
        return new HashWhitelist(DEFAULT_FIELDS, DEFAULT_EXCLUDES, true, quantumBits);
    }
}
