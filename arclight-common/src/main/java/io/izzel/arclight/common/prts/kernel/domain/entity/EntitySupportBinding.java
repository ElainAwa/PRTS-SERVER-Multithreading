/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity;

import io.izzel.arclight.common.prts.kernel.domain.entity.ownership.EntityModelRegistry;
import io.izzel.arclight.common.prts.kernel.domain.entity.support.EntitySupportMatrix;

/**
 * Binds the declared support matrix to the model registry as the production trait composite: a
 * supported class resolves to the trait-composite kind and every other class explicitly to C1. The
 * binding is dormant - nothing calls it by default - and installing twice is one install.
 */
public final class EntitySupportBinding {

    private static volatile boolean installed;

    private EntitySupportBinding() {
    }

    /** Installs the production trait composite from the declared support matrix. */
    public static synchronized void install() {
        if (installed) {
            return;
        }
        EntityModelRegistry.installProductionTraitProvider((type, profile) -> {
            EntitySupportMatrix.TraitDecision decision = EntitySupportMatrix.traitDecisionFor(type);
            if (decision.composite()) {
                return new EntityModelRegistry.Decision(EntityModelRegistry.Kind.TRAIT_COMPOSITE,
                    decision.modelId(), decision.traits(), decision.reason());
            }
            return new EntityModelRegistry.Decision(EntityModelRegistry.Kind.C1_FALLBACK, null,
                0L, decision.reason());
        });
        installed = true;
    }

    /** Whether the declared support matrix is the installed trait composite. */
    public static boolean installed() {
        return installed;
    }
}
