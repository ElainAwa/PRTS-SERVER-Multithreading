/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.shares;

import io.izzel.arclight.common.prts.kernel.shares.SharePlanner.ConservationCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The two states of the share budget. Inside a state the classes keep to their own share and the
 * rest goes to the catch-all row; only the degraded state lets one class give way to another, and
 * then in the fixed order the ladder writes down. The state is judged once per tick by the control
 * plane and never allocates anything: this class only decides, it does not hand out shares. */
public final class BudgetStateMachine {

    public enum Phase {
        NORMAL,
        DEGRADED
    }

    /** One tick's verdict and the four conditions behind it. A reader can recompute the phase from
     * the booleans without repeating the judgement. */
    public record Decision(long tickIndex, Phase phase, boolean marginsWithin, boolean waitBoundClean,
                           boolean reserveWithin, boolean conservationHolds, long overrunHits,
                           String reason, long enteredTick, long normalTicks, long degradedTicks,
                           long enteredCount, long leftCount) {

        public boolean degraded() {
            return phase == Phase.DEGRADED;
        }
    }

    private Phase phase = Phase.NORMAL;
    private long lastEntryTick;
    private long normalTicks;
    private long degradedTicks;
    private long enteredCount;
    private long leftCount;

    /** Judges one tick. A tick whose table was never planned has no evidence of an overrun, so the
     * state stays where it is and the reading names the missing plan. */
    public synchronized Decision judge(long tickIndex, ShareTable table, long overrunHits,
                                       long waitBoundHits, ConservationCheck conservation) {
        boolean planned = table != null;
        boolean marginsWithin = overrunHits <= 0L && noNegativeMargin(table);
        boolean waitBoundClean = waitBoundHits <= 0L;
        boolean reserveWithin = !planned || reserveWithin(table);
        boolean conservationHolds = !planned || (conservation != null && conservation.ok());
        List<String> violations = new ArrayList<>(4);
        if (!marginsWithin) {
            violations.add("margin");
        }
        if (!waitBoundClean) {
            violations.add("wait_bound");
        }
        if (!reserveWithin) {
            violations.add("reserve");
        }
        if (!conservationHolds) {
            violations.add("conservation");
        }
        Phase next = violations.isEmpty() ? Phase.NORMAL : Phase.DEGRADED;
        if (next == Phase.DEGRADED && phase == Phase.NORMAL) {
            enteredCount++;
            lastEntryTick = tickIndex;
        } else if (next == Phase.NORMAL && phase == Phase.DEGRADED) {
            leftCount++;
        }
        phase = next;
        if (next == Phase.NORMAL) {
            normalTicks++;
        } else {
            degradedTicks++;
        }
        String reason = !planned ? "unplanned"
            : (violations.isEmpty() ? "none" : String.join(",", violations));
        return new Decision(tickIndex, next, marginsWithin, waitBoundClean, reserveWithin,
            conservationHolds, overrunHits, reason, lastEntryTick, normalTicks, degradedTicks,
            enteredCount, leftCount);
    }

    public synchronized Phase phase() {
        return phase;
    }

    public synchronized long lastEntryTick() {
        return lastEntryTick;
    }

    private static boolean noNegativeMargin(ShareTable table) {
        if (table == null) {
            return true;
        }
        for (ShareTable.ShareRow row : table.rows()) {
            if (row.overrun()) {
                return false;
            }
        }
        return true;
    }

    /** A pool that was never drawn from is not exhausted even when it is empty: a zero-sized
     * reserve that nothing took from says nothing about pressure, while a drawn pool that has
     * nothing left does. */
    private static boolean reserveWithin(ShareTable table) {
        return table.reserve().remainingMs() > 0.0 || table.reserve().usedMs() <= 0.0;
    }

    /** The phase as it appears in a reading. */
    public static String key(Phase phase) {
        return phase == null ? "none" : phase.name().toLowerCase(Locale.ROOT);
    }

    public synchronized void reset() {
        phase = Phase.NORMAL;
        lastEntryTick = 0L;
        normalTicks = 0L;
        degradedTicks = 0L;
        enteredCount = 0L;
        leftCount = 0L;
    }
}
