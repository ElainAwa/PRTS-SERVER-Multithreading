/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.twoowner;

/** The greppable tick and stop settlement lines of the two-owner window. */
public final class TwoOwnerEvidence {

    /** The placeholder of a value the caller could not read. */
    public static final String NOT_AVAILABLE = "N/A";

    private TwoOwnerEvidence() {
    }

    /** One per-site tick line. */
    public static String tickLine(long tick, String siteId, long planTasks, long rows,
                                  long dispatched, long settled, long failed, long arenaPinned,
                                  long planRows, long doneRows, long conflictSkipped,
                                  String dueSource, long offeredTotal, long doneTotal) {
        return "PRTS two-owner tick=" + tick + " site=" + siteId
            + " plan_tasks=" + number(planTasks)
            + " rows=" + number(rows)
            + " dispatched=" + number(dispatched)
            + " settled=" + number(settled)
            + " failed=" + number(failed)
            + " arena_pinned=" + number(arenaPinned)
            + " accounts=" + NOT_AVAILABLE
            + " plan_rows=" + number(planRows)
            + " done_rows=" + number(doneRows)
            + " conflict_skipped=" + number(conflictSkipped)
            + " due_source=" + (dueSource == null ? NOT_AVAILABLE : dueSource)
            + " offered_total=" + number(offeredTotal)
            + " done_total=" + number(doneTotal);
    }

    /** One stop settlement line. */
    public static String settlementLine(String siteId, long arenaPinned, long arenaReleased,
                                        long claims, long accountsOpen, long accountsClosed,
                                        long intentsApplied, long intentsRefused,
                                        long intentsAbandoned, long rowsDoneTotal) {
        return "PRTS settlement site=" + siteId
            + " arena_pinned=" + number(arenaPinned)
            + " arena_released=" + number(arenaReleased)
            + " claims=" + number(claims)
            + " accounts_open=" + number(accountsOpen)
            + " accounts_closed=" + number(accountsClosed)
            + " intents_applied=" + number(intentsApplied)
            + " refused=" + number(intentsRefused)
            + " abandoned=" + number(intentsAbandoned)
            + " rows_done_total=" + number(rowsDoneTotal);
    }

    private static String number(long value) {
        return value < 0L ? NOT_AVAILABLE : Long.toString(value);
    }
}
