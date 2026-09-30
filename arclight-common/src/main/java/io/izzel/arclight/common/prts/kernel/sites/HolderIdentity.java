/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;

/**
 * Who is writing, as the write paths see them.
 *
 * <p>A holder is a registered site, the kernel itself, or a thread nobody ever declared. The third
 * case is not an error by itself: it is the case the write right decision exists for, and it is
 * never entered into the site registry.</p>
 *
 * @param kind   where the writing thread comes from
 * @param siteId readable identity of the writer
 */
public record HolderIdentity(HolderKind kind, String siteId) {

    public HolderIdentity {
        if (kind == null || siteId == null) {
            throw new IllegalArgumentException("a holder needs a kind and a site identity");
        }
    }

    /** @return a holder that passed admission */
    public static HolderIdentity registered(String siteId) {
        return new HolderIdentity(HolderKind.REGISTERED, siteId);
    }

    /** @return a holder that was never declared */
    public static HolderIdentity unregistered(String siteId) {
        return new HolderIdentity(HolderKind.UNREGISTERED, siteId);
    }

    /** @return the kernel's own holder */
    public static HolderIdentity kernel(String siteId) {
        return new HolderIdentity(HolderKind.KERNEL, siteId);
    }

    /** @return {@code true} when this holder carries a world write right */
    public boolean registered() {
        return kind == HolderKind.REGISTERED || kind == HolderKind.KERNEL;
    }
}
