/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.diff;

import java.util.List;
import java.util.Set;

/** What a state hash may include and what it must exclude; the declaration is validated before use,
 * so an empty field list or an unknown field is an error rather than an empty hash. */
public record HashWhitelist(List<String> fields, List<String> excludes, boolean quantized,
                            int quantumBits) {

/** The fields a hash may fold: kinematic values, entity identity and the segment header. */
    public static final Set<String> KNOWN_FIELDS = Set.of("position", "orientation", "velocity",
        "flags", "entitySeq", "layoutVersion", "slotGeneration", "segmentRef", "worldId", "regionId",
        "dataVersion");

/** The default field order: position, orientation, velocity, flags, entity identity, then header versions. */
    public static final List<String> DEFAULT_FIELDS = List.of("position", "orientation", "velocity",
        "flags", "entitySeq", "layoutVersion", "slotGeneration", "segmentRef", "worldId", "regionId",
        "dataVersion");

    /** The sources a hash must never read. */
    public static final List<String> DEFAULT_EXCLUDES = List.of("observation counters", "trace",
        "wall clock", "unregistered sites");

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

    public static HashWhitelist bitexact() {
        return new HashWhitelist(DEFAULT_FIELDS, DEFAULT_EXCLUDES, false, 0);
    }

    /** Returns the quantized form, kept for a comparison across machines. */
    public static HashWhitelist quantized(int quantumBits) {
        return new HashWhitelist(DEFAULT_FIELDS, DEFAULT_EXCLUDES, true, quantumBits);
    }
}
