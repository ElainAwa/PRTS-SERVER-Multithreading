/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.twoowner;

import io.izzel.arclight.common.prts.kernel.SharedRuntime;
import io.izzel.arclight.common.prts.kernel.arena.ArenaLedger;
import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.domain.entity.OwnerContext;
import io.izzel.arclight.common.prts.kernel.domain.entity.OwnerSite;

import java.util.List;

/**
 * The two-owner dormant setup: with its internal property set, the process runs two owner sites of
 * one OwnerContext over the one SharedRuntime; with the property absent it names the single main
 * site and nothing else changes. The setup builds the ring metadata, the per-owner arena account
 * and the per-owner work accounts of the shared context, and never creates a second module or a
 * second runtime.
 */
public final class TwoOwnerSetup {

    /** The internal dormant property; it is not a configuration key. */
    public static final String PROPERTY = "arclight.prts.twoOwners";

    private TwoOwnerSetup() {
    }

    /** Whether the process declares two owner sites; off in every shipped build. */
    public static boolean enabled() {
        return Boolean.getBoolean(PROPERTY);
    }

    /** The owner sites of one context: the primary site alone, or the primary and one more. */
    public static List<OwnerSite> sites(OwnerContext context) {
        OwnerSite primary = context.primarySite();
        if (!enabled()) {
            return List.of(primary);
        }
        return List.of(primary, context.site("owner-b", HolderKind.REGISTERED));
    }

    /** The one shared runtime both owner sites execute on. */
    public static SharedRuntime sharedRuntime() {
        return SharedRuntime.shared();
    }

    /** The ring metadata of the owner lanes. */
    public static RingMetadata ringMetadata() {
        return new RingMetadata();
    }

    /** The per-owner arena account over the context's one arena ledger. */
    public static OwnerArenaAccount arenaAccount(OwnerContext context, List<OwnerSite> sites) {
        ArenaLedger ledger = context.arena();
        return new OwnerArenaAccount(ledger, sites);
    }

    /** The per-owner work accounts over the one shared runtime. */
    public static OwnerWorkAccounts workAccounts(List<OwnerSite> sites) {
        return OwnerWorkAccounts.open(sharedRuntime(), sites);
    }

    /** One owner lane key: the world incarnation and the domain of the ring. */
    public static RingKey lane(String worldId, long worldIncarnation, String domainId) {
        return new RingKey(worldId, worldIncarnation, domainId);
    }
}
