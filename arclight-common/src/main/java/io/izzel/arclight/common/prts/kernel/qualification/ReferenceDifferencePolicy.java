/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.qualification;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The approved-difference policy between reference A and B/C: business facts (state, events,
 * inventory, published revision, manifest, digest, POI ownership, completion set) can never be
 * relaxed; only a design-approved observation or RNG field may be approved. The default policy is
 * empty, so any difference fails the full three-reference pass. */
public final class ReferenceDifferencePolicy {

    private static final Set<String> BUSINESS_FACTS = Set.of("state", "events", "inventory",
        "publishedRevision", "manifest", "digest", "poiOwner", "completion");
    private static final Set<String> APPROVABLE = Set.of("streamPosition");

    private final Set<String> approved;

    private ReferenceDifferencePolicy(Set<String> approved) {
        this.approved = Set.copyOf(approved);
    }

    /** The default empty policy: every difference is a failure. */
    public static ReferenceDifferencePolicy empty() {
        return new ReferenceDifferencePolicy(Set.of());
    }

    /** Approves one observation/RNG field; a business fact or unknown field is refused. */
    public static ReferenceDifferencePolicy approving(String field) {
        Set<String> approved = new LinkedHashSet<>();
        approved.add(validate(field));
        return new ReferenceDifferencePolicy(approved);
    }

    /** Whether one difference field is inside the approved observation/RNG range. */
    public boolean allows(String field) {
        return approved.contains(field);
    }

    public boolean allowsAll(List<String> fields) {
        return fields.stream().allMatch(this::allows);
    }

    public Set<String> approved() {
        return approved;
    }

    private static String validate(String field) {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("an approved difference needs its field");
        }
        if (BUSINESS_FACTS.contains(field)) {
            throw new IllegalArgumentException("a business fact cannot be relaxed: " + field);
        }
        if (!APPROVABLE.contains(field)) {
            throw new IllegalArgumentException("not an approved observation/RNG field: " + field);
        }
        return field;
    }
}
