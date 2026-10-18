/* SPDX-License-Identifier: GPL-3.0-or-later */
/* The seam a chunk pipeline mailbox reports through: one call per task that ran, one per drain
 * round that carried work. Both are no-ops while no watcher is installed. */
package io.izzel.arclight.common.prts.support;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

        /** The same row with the mailbox it came out of; a watcher that sorts by identity overrides
         * this one instead of the name-only face. */
        default void mailboxTask(Object mailbox, String mailboxName, long nanos) {
            mailboxTask(mailboxName, nanos);
        }

        default void mailboxRound(Object mailbox, String mailboxName) {
            mailboxRound(mailboxName);
        }
    }

    /** The same rows, told apart by the mailbox they came out of and by the world it belongs to. */
    public interface MailboxOwnerTap {

        void ownerTask(String world, String mailbox);

        void ownerRound(String world, String mailbox);

        /** The same round with the mailbox object, so a watcher can tell two mailboxes of one name
         * apart; the world is the placement answer for that object. */
        default void ownerRound(Object mailbox, String world, String mailboxName) {
            ownerRound(world, mailboxName);
        }

        default void ownerTask(Object mailbox, String world, String mailboxName) {
            ownerTask(world, mailboxName);
        }

        default void ownerTaskBegin(String world, String mailbox) {
        }

        default void ownerTaskEnd(String world, String mailbox) {
        }

        /** The same span, with the token of the task it covers. A watcher that names no token is
         * still called, through the two-argument entry it already implements. */
        default void ownerTaskBegin(String world, String mailbox, Object token) {
            ownerTaskBegin(world, mailbox);
        }

        /** @param token the token the matching begin carried; ignored by a two-argument watcher */
        default void ownerTaskEnd(String world, String mailbox, Object token) {
            ownerTaskEnd(world, mailbox);
        }
    }

    private static volatile MailboxRowTap tap;

    private static final CopyOnWriteArrayList<MailboxOwnerTap> ownerTaps =
        new CopyOnWriteArrayList<>();

    private static volatile Map<Object, String> mailboxWorlds = Map.of();

    private static volatile Map<Object, Boolean> nonTargetMailboxes = Map.of();

    private static volatile Map<Object, Boolean> nonTargetSorters = Map.of();

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

    /** Publishes the mailboxes the placement walk classified as not part of the target pipeline.
     * The set is held by identity: two mailboxes of one name stay apart. */
    public static void bindNonTargetMailboxes(Set<Object> mailboxes) {
        nonTargetMailboxes = identitySet(mailboxes);
    }

    /** Publishes the sorters whose submits are not target pipeline submits. */
    public static void bindNonTargetSorters(Set<Object> sorters) {
        nonTargetSorters = identitySet(sorters);
    }

    /** @return whether the mailbox object was classified as not part of the target pipeline */
    public static boolean isNonTargetMailbox(Object mailbox) {
        return mailbox != null && nonTargetMailboxes.containsKey(mailbox);
    }

    /** @return whether the sorter object was classified as not the target pipeline sorter */
    public static boolean isNonTargetSorter(Object sorter) {
        return sorter != null && nonTargetSorters.containsKey(sorter);
    }

    private static Map<Object, Boolean> identitySet(Set<Object> objects) {
        if (objects == null || objects.isEmpty()) {
            return Map.of();
        }
        Map<Object, Boolean> byIdentity = new IdentityHashMap<>(objects.size());
        for (Object object : objects) {
            if (object != null) {
                byIdentity.put(object, Boolean.TRUE);
            }
        }
        return Collections.unmodifiableMap(byIdentity);
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

    /** Reports one executed task with the mailbox object, so the row face can classify it. */
    public static void task(Object mailbox, String name, long nanos) {
        MailboxRowTap watcher = tap;
        if (watcher != null) {
            watcher.mailboxTask(mailbox, name, nanos);
        }
    }

    /** Reports one drain round with the mailbox object, so the row face can classify it. */
    public static void round(Object mailbox, String name) {
        MailboxRowTap watcher = tap;
        if (watcher != null) {
            watcher.mailboxRound(mailbox, name);
        }
    }

    /** Reports one executed task to every watcher of the owner aware face. */
    public static void ownerTask(Object mailbox, String name) {
        String world = worldOf(mailbox);
        for (MailboxOwnerTap watcher : ownerTaps) {
            watcher.ownerTask(mailbox, world, name);
        }
    }

    /** Reports one drain round to every watcher of the owner aware face. */
    public static void ownerRound(Object mailbox, String name) {
        String world = worldOf(mailbox);
        for (MailboxOwnerTap watcher : ownerTaps) {
            watcher.ownerRound(mailbox, world, name);
        }
    }

    public static void ownerTaskBegin(Object mailbox, String name) {
        String world = worldOf(mailbox);
        for (MailboxOwnerTap watcher : ownerTaps) {
            watcher.ownerTaskBegin(world, name);
        }
    }

    public static void ownerTaskEnd(Object mailbox, String name) {
        String world = worldOf(mailbox);
        for (MailboxOwnerTap watcher : ownerTaps) {
            watcher.ownerTaskEnd(world, name);
        }
    }

    /** Reports one work item beginning, with the token of the task it covers. */
    public static void ownerTaskBegin(Object mailbox, String name, Object token) {
        String world = worldOf(mailbox);
        for (MailboxOwnerTap watcher : ownerTaps) {
            watcher.ownerTaskBegin(world, name, token);
        }
    }

    /** Reports one work item ending, with the token its begin carried. */
    public static void ownerTaskEnd(Object mailbox, String name, Object token) {
        String world = worldOf(mailbox);
        for (MailboxOwnerTap watcher : ownerTaps) {
            watcher.ownerTaskEnd(world, name, token);
        }
    }
}
