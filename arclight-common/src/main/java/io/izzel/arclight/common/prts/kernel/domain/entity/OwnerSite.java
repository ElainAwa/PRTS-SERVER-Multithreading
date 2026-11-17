/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;

import java.util.Objects;

/** One owner site of one process: its stable site identity and the holder kind it writes under. Two
 * sites of the same context share the registry, the version slots and the arena; the site identity
 * is what the owner tokens name, so two owners never write under one another's name. */
public record OwnerSite(String siteId, HolderKind holderKind) {

    public OwnerSite {
        Objects.requireNonNull(siteId, "siteId");
        Objects.requireNonNull(holderKind, "holderKind");
        if (siteId.isEmpty()) {
            throw new IllegalArgumentException("an owner site needs its identity");
        }
        if (holderKind == HolderKind.UNREGISTERED) {
            throw new IllegalArgumentException("an owner site cannot be unregistered");
        }
    }

    /** The site a build without owner injection uses. */
    public static OwnerSite main() {
        return new OwnerSite("main", HolderKind.REGISTERED);
    }
}
