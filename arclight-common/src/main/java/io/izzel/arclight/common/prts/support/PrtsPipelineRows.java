/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The seam a chunk pipeline mailbox reports through: one call per task that ran, one per drain
 * round that carried work. Both are no-ops while no watcher is installed. */
package io.izzel.arclight.common.prts.support;

/** The seam the chunk pipeline mailboxes open, identified by the name the pipeline gave them. */
public final class PrtsPipelineRows {

    public interface MailboxRowTap {

        /** @param mailbox the name the mailbox was created with; @param nanos how long the task ran, in nanoseconds */
        void mailboxTask(String mailbox, long nanos);

        /** @param mailbox the name the mailbox was created with */
        void mailboxRound(String mailbox);
    }

    private static volatile MailboxRowTap tap;

    private PrtsPipelineRows() {
    }

    public static void install(MailboxRowTap watcher) {
        tap = watcher;
    }

    public static boolean installed() {
        return tap != null;
    }

    /** @return the installed watcher, or null when none is installed */
    public static MailboxRowTap watcher() {
        return tap;
    }

    public static void task(String mailbox, long nanos) {
        MailboxRowTap watcher = tap;
        if (watcher != null) {
            watcher.mailboxTask(mailbox, nanos);
        }
    }

    public static void round(String mailbox) {
        MailboxRowTap watcher = tap;
        if (watcher != null) {
            watcher.mailboxRound(mailbox);
        }
    }
}
