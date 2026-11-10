/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.OwnerToken;

/** The internal context a live write tap hands to the gate. A null owner token means the call site
 * has no legal owner source; such a path is ENFORCE_NA and stays pass-through, it is never an
 * invented owner. */
public record WriteTapContext(String worldId, OwnerToken ownerToken, long expectedVersion,
                              long tickIndex, String domainId, String site) {

    public WriteTapContext {
        if (worldId == null) throw new IllegalArgumentException("worldId is null");
        if (domainId == null) throw new IllegalArgumentException("domainId is null");
        if (site == null) throw new IllegalArgumentException("site is null");
    }

    public boolean enforceNa() {
        return ownerToken == null || expectedVersion <= 0L;
    }
}
