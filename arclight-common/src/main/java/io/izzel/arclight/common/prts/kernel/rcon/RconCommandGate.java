/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.rcon;

import io.izzel.arclight.common.prts.kernel.codes.DegradeLevel;
import io.izzel.arclight.common.prts.kernel.shares.ShareClass;
import io.izzel.arclight.common.prts.kernel.shares.ShareTable;

/** The gate: a real EVENT row over its margin and the B4 rung, never queue pressure. */
public final class RconCommandGate {

    private RconCommandGate() {
    }

    /** Answers from the real EVENT row and ladder; both switches must allow the action. */
    public static boolean shouldDefer(ShareTable table, DegradeLevel deepest,
                                      boolean enabled, boolean degradeActions) {
        if (!enabled || !degradeActions || table == null || deepest == null) {
            return false;
        }
        if (deepest.ordinal() < DegradeLevel.B4.ordinal()) {
            return false;
        }
        for (ShareTable.ShareRow row : table.rows()) {
            if (row.shareClass() == ShareClass.EVENT && row.overrun()) {
                return true;
            }
        }
        return false;
    }
}
