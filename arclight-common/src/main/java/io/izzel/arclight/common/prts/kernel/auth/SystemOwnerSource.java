/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

/** Issues and installs the kernel-owned token of one system domain. The version comes from the
 * plan's WriteVersionSlots grant, so a system write carries the same expected version a plan does. */
public final class SystemOwnerSource {

    private final OwnerRegistry owners;
    private final WriteVersionSlots versions;

    public SystemOwnerSource(OwnerRegistry owners, WriteVersionSlots versions) {
        this.owners = owners;
        this.versions = versions;
    }

    /** Acquires the token for one world/domain between two ticks; null when another holder owns it. */
    public OwnerToken acquire(String worldId, String domainId, long tick, long expireTick) {
        long version = versions.grant(worldId, WriteLevel.REGION, domainId);
        OwnerToken token = new OwnerToken(worldId, WriteLevel.REGION, domainId, version, version,
            tick, expireTick, HolderKind.KERNEL, "system:" + domainId);
        return owners.acquire(token) ? token : null;
    }

    /** Looks up the live token of one system domain. */
    public OwnerToken lookup(String worldId, String domainId) {
        return owners.lookup(worldId, WriteLevel.REGION, domainId).orElse(null);
    }

    public int reclaimExpired(long tick) {
        return owners.reclaimExpired(tick);
    }
}
