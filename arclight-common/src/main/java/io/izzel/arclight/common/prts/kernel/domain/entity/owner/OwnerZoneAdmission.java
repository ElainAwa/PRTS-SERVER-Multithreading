/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.owner;

import io.izzel.arclight.common.prts.kernel.domain.entity.EntityDueWindow;
import io.izzel.arclight.common.prts.support.EntityEligibility;

/** Owner-zone admission for the server-thread executor; dispatch Eligibility is unchanged. */
public final class OwnerZoneAdmission {

    /** The internal walking-slice switch; dynamic, non-shipped and off by default. */
    public static final String WALK_SLICE_PROPERTY = "arclight.prts.ownerWalkSlice";

    private OwnerZoneAdmission() {
    }

    /** Frozen-row admission shared by both owner slices. */
    public static boolean admits(EntityDueWindow.Row row) {
        return baseAdmission(row);
    }

    /** Whether the optional walking slice is declared for this owner process. */
    public static boolean walkSlice() {
        return Boolean.getBoolean(WALK_SLICE_PROPERTY);
    }

    /** Frozen-row admission for the walking slice; live pose/brain checks happen in select. */
    public static boolean admitsWalk(EntityDueWindow.Row row) {
        return walkSlice() && baseAdmission(row);
    }

    private static boolean baseAdmission(EntityDueWindow.Row row) {
        return enabled() && carriesRow(row) && isAi(row);
    }

    private static boolean enabled() {
        return EntityRowOwnershipWindow.enabled();
    }

    private static boolean carriesRow(EntityDueWindow.Row row) {
        return row != null && !row.removed() && !row.passenger();
    }

    private static boolean isAi(EntityDueWindow.Row row) {
        return row.reason() == EntityEligibility.Reason.AI;
    }
}
