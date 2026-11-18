/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueWindow;
import io.izzel.arclight.common.prts.support.EntityEligibility;

/** The owner-zone admission for the server-thread original-algorithm executor. AI rows are
 * host-only for the parallel dispatcher because it needs a model; the owner zone runs the vanilla
 * tick on the server thread and needs no model. PLAYER/THIRD_PARTY/LIFECYCLE/RIDING stay refused and
 * dispatch Eligibility itself is unchanged. */
public final class OwnerZoneAdmission {

    private OwnerZoneAdmission() {
    }

    public static boolean admits(EntityDueWindow.Row row) {
        if (!EntityRowOwnershipWindow.enabled() || row == null || row.removed()
            || row.passenger()) {
            return false;
        }
        return row.reason() == EntityEligibility.Reason.AI;
    }
}
