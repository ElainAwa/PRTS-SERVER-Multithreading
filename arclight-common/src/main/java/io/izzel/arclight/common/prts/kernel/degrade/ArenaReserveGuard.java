/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.degrade;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.shares.SharePlanner;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;

/** Draws an A-ring action from the reserved column; an insufficient reserve enters B5 instead of
 * overdrawing. */
public final class ArenaReserveGuard {

    public boolean consume(SharePlanner planner, DegradeLadder ladder,
                           ShareTable.ReservePurpose purpose, double millis, long tick) {
        if (planner == null || ladder == null || purpose == null || !ladder.actionsEnabled()) {
            return false;
        }
        ShareTable table = planner.lastTable();
        double remaining = table == null ? 0.0 : table.reserve().remainingMs();
        if (millis > remaining) {
            ladder.noteEntered(DegradeLevel.B5, tick);
            ladder.noteTick(true);
            return false;
        }
        planner.consumeReserve(purpose, millis);
        ladder.noteTick(false);
        return true;
    }
}
