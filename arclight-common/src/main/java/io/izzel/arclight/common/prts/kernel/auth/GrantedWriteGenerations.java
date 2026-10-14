/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry.OwnershipDomain;

import java.util.Map;

/** The generations one plan's own {@link WriteVersionSlots#grant} returned, one record per plan: the
 * only source a row may wear a generation from. A plan the record does not belong to, and a domain
 * that plan did not grant, answer as an absence and never as {@link WriteVersionSlots#NOT_CARRIED}. */
public final class GrantedWriteGenerations {

    private record Taken(long planSequence, Map<OwnershipDomain, Long> granted) {
    }

    private static final Taken NONE = new Taken(0L, Map.of());

    private volatile Taken taken = NONE;

    /** Publishes one plan's grants in one move: the record is replaced whole and never edited. */
    public void note(long planSequence, Map<OwnershipDomain, Long> granted) {
        if (planSequence <= 0L || granted == null || granted.isEmpty()) {
            taken = NONE;
            return;
        }
        taken = new Taken(planSequence, Map.copyOf(granted));
    }

    /** What one plan's own grant returned for one domain, or null when that plan granted nothing. */
    public Long grantedFor(long planSequence, String worldId, WriteLevel level, String domainId) {
        if (worldId == null || domainId == null) {
            return null;
        }
        Taken current = taken;
        if (current.planSequence() != planSequence) {
            return null;
        }
        return current.granted().get(new OwnershipDomain(worldId, level, domainId));
    }

    public long planSequence() {
        return taken.planSequence();
    }

    public void reset() {
        taken = NONE;
    }
}
