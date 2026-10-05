/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The seam a chunk pipeline mailbox reports through: one call per task that ran, one per drain
 * round that carried work. Both are no-ops while no watcher is installed. */
package io.izzel.arclight.common.prts.support;

import java.util.Map;

/** The seam the chunk pipeline mailboxes open, identified by the name the pipeline gave them. */
public final class PrtsPipelineRows {

    /** The world id a row carries while the mailbox it came out of has not been placed yet. */
    public static final String UNPLACED_WORLD = "-";

    public interface MailboxRowTap {

        /** @param mailbox the mailbox name; @param nanos how long the task ran, in nanoseconds */
        void mailboxTask(String mailbox, long nanos);

        /** @param mailbox the mailbox name */
        void mailboxRound(String mailbox);
    }

    /** The same rows, told apart by the mailbox they came out of and by the world it belongs to. */
    public interface MailboxOwnerTap {

        void ownerTask(String world, String mailbox);

        void ownerRound(String world, String mailbox);
    }

    private static volatile MailboxRowTap tap;

    private static volatile MailboxOwnerTap ownerTap;

    private static volatile Map<Object, String> mailboxWorlds = Map.of();

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

    /** Installs the owner aware face, or removes it when handed null. */
    public static void installOwnerTap(MailboxOwnerTap watcher) {
        ownerTap = watcher;
    }

    /** @return whether the owner aware face has a watcher */
    public static boolean ownerTapInstalled() {
        return ownerTap != null;
    }

    /** Publishes the mailbox to world placement an observer read; an empty map places nothing. */
    public static void bindMailboxWorlds(Map<Object, String> worlds) {
        mailboxWorlds = worlds == null ? Map.of() : Map.copyOf(worlds);
    }

    /** @return the world of one mailbox, or {@link #UNPLACED_WORLD} while it has not been placed */
    public static String worldOf(Object mailbox) {
        String world = mailboxWorlds.get(mailbox);
        return world == null ? UNPLACED_WORLD : world;
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

    /** Reports one executed task to the owner aware face; a no-op while none is installed. */
    public static void ownerTask(Object mailbox, String name) {
        MailboxOwnerTap watcher = ownerTap;
        if (watcher != null) {
            watcher.ownerTask(worldOf(mailbox), name);
        }
    }

    /** Reports one drain round to the owner aware face; a no-op while none is installed. */
    public static void ownerRound(Object mailbox, String name) {
        MailboxOwnerTap watcher = ownerTap;
        if (watcher != null) {
            watcher.ownerRound(worldOf(mailbox), name);
        }
    }
}
