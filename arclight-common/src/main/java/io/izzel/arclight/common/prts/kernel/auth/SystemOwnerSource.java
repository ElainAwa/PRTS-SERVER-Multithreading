/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.auth;

/** Issues and installs the kernel-owned token of one system domain. The version comes from the
 * plan's grant; the authorization generation is drawn from the registry and survives a renewal. */
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
        String site = "system:" + domainId;
        OwnerToken installed = owners.lookup(worldId, WriteLevel.REGION, domainId).orElse(null);
        long epoch = installed != null && installed.holderSiteId().equals(site)
            ? installed.epoch() : owners.mintEpoch();
        OwnerToken token = new OwnerToken(worldId, WriteLevel.REGION, domainId, epoch, version,
            tick, expireTick, HolderKind.KERNEL, site, 0L, owners.worldEpochOf(worldId));
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
