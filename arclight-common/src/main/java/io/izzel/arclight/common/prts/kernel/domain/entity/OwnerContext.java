/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.auth.OwnerRegistry;
import io.izzel.arclight.common.prts.kernel.auth.WriteVersionSlots;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The owner context of one process: the one OwnerRegistry, WriteVersionSlots and ArenaLedger every
 * owner site of the process shares, and the sites that may own work under them. A second owner is
 * another site of the same context, not a second module; a build that injects nothing keeps the
 * single main site and behaves exactly as before. The context is internal wiring: it adds no
 * configuration key and no readout.
 */
public final class OwnerContext {

    private final OwnerRegistry registry;
    private final WriteVersionSlots versions;
    private final ArenaLedger arena;
    private final Map<String, OwnerSite> sites = new LinkedHashMap<>();

    private OwnerContext(OwnerRegistry registry, WriteVersionSlots versions, ArenaLedger arena,
                         OwnerSite primary) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.versions = Objects.requireNonNull(versions, "versions");
        this.arena = Objects.requireNonNull(arena, "arena");
        sites.put(primary.siteId(), primary);
    }

    /** The single-site context of a build that injects no second owner. */
    public static OwnerContext single(OwnerRegistry registry, WriteVersionSlots versions,
                                      ArenaLedger arena) {
        return new OwnerContext(registry, versions, arena, OwnerSite.main());
    }

    /** One more owner site of this context; a repeated site id returns the installed site. */
    public synchronized OwnerSite site(String siteId, HolderKind holderKind) {
        OwnerSite requested = new OwnerSite(siteId, holderKind);
        return sites.computeIfAbsent(siteId, key -> requested);
    }

    /** The site a domain runs as when nothing else was injected. */
    public OwnerSite primarySite() {
        return sites.values().iterator().next();
    }

    public synchronized List<OwnerSite> sites() {
        return List.copyOf(sites.values());
    }

    public OwnerRegistry registry() {
        return registry;
    }

    public WriteVersionSlots versions() {
        return versions;
    }

    public ArenaLedger arena() {
        return arena;
    }
}
