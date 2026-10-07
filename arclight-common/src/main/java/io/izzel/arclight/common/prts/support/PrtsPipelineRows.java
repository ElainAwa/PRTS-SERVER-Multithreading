/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The seam a chunk pipeline mailbox reports through: one call per task that ran, one per drain
 * round that carried work. Both are no-ops while no watcher is installed. */
package io.izzel.arclight.common.prts.support;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

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

    private static final CopyOnWriteArrayList<MailboxOwnerTap> ownerTaps =
        new CopyOnWriteArrayList<>();

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

    /** Adds a watcher to the owner aware face, or clears them all when handed null. The face serves
     * every watcher that asked for it: a second reader takes nothing away from the first. */
    public static void installOwnerTap(MailboxOwnerTap watcher) {
        if (watcher == null) {
            ownerTaps.clear();
            return;
        }
        ownerTaps.addIfAbsent(watcher);
    }

    /** Removes one watcher from the owner aware face and leaves the others in place. */
    public static void removeOwnerTap(MailboxOwnerTap watcher) {
        ownerTaps.remove(watcher);
    }

    /** @return whether the owner aware face has any watcher */
    public static boolean ownerTapInstalled() {
        return !ownerTaps.isEmpty();
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

    /** Reports one executed task to every watcher of the owner aware face. */
    public static void ownerTask(Object mailbox, String name) {
        String world = worldOf(mailbox);
        for (MailboxOwnerTap watcher : ownerTaps) {
            watcher.ownerTask(world, name);
        }
    }

    /** Reports one drain round to every watcher of the owner aware face. */
    public static void ownerRound(Object mailbox, String name) {
        String world = worldOf(mailbox);
        for (MailboxOwnerTap watcher : ownerTaps) {
            watcher.ownerRound(world, name);
        }
    }
}
