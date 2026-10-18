/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.commit;

import java.util.function.Supplier;

/** The holder stand-in for the range path: it answers the table it was handed, the freeze it judges
 * under, one live world generation and one admission number. Its range lookup is filtered by the
 * configured freeze while the seat question is answered across freezes, which is how a cross
 * lifecycle seal is driven to the fail-closed refusal. */
final class RangeSource implements IdentityWitness.Source {

    private final RangeSealTable table;
    private final FrozenDeclarations declarations;
    private final Supplier<FrozenDeclarations.FreezeKey> freeze;
    private final long worldEpoch;
    private final long authorization;

    RangeSource(RangeSealTable table, FrozenDeclarations declarations,
                FrozenDeclarations.FreezeKey freeze, long worldEpoch, long authorization) {
        this(table, declarations, () -> freeze, worldEpoch, authorization);
    }

    RangeSource(RangeSealTable table, FrozenDeclarations declarations,
                Supplier<FrozenDeclarations.FreezeKey> freeze, long worldEpoch, long authorization) {
        this.table = table;
        this.declarations = declarations;
        this.freeze = freeze;
        this.worldEpoch = worldEpoch;
        this.authorization = authorization;
    }

    @Override
    public FrozenDeclarations declarations(String worldId, String domainId, long planSequence) {
        if (declarations == null || declarations.planSequence() != planSequence) {
            return FrozenDeclarations.none();
        }
        return declarations;
    }

    @Override
    public long liveWorldEpoch(String worldId) {
        return worldEpoch;
    }

    @Override
    public IdentityWitness.Authorization liveAuthorization(long planSequence,
            CommitNamespaces.Namespace planController, String worldId, String domainId,
            String nodeKey) {
        if (planController == null) {
            return IdentityWitness.Authorization.refused("", 0L,
                IdentityWitness.Authorization.Face.notRead(), "no-controller");
        }
        return IdentityWitness.Authorization.answered(planController.lifecycleKey(), planSequence,
            authorization, IdentityWitness.Authorization.Face.present(authorization));
    }

    @Override
    public CommitNamespaces.Namespace currentController() {
        return null;
    }

    @Override
    public FrozenDeclarations.RangeSeal range(FrozenDeclarations.Seat seat, long cell) {
        return table.lookup(freeze.get(), seat, cell);
    }

    @Override
    public boolean hasRange(FrozenDeclarations.Seat seat) {
        return table.hasSeat(seat);
    }

    @Override
    public IdentityWitness.Obligation oblige(FrozenDeclarations.RangeSeal seal) {
        return table.oblige(seal);
    }
}
