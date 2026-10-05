/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.support;

/** The seam the chunk pipeline's mailboxes report their flow through, with the queue depth the
 * mailbox held at that moment. Both calls are no-ops while no watcher is installed. */
public final class PrtsChunkFlow {

    public interface FlowTap {

        /** @param mailbox the name the mailbox was created with; @param depth items still queued */
        void submitted(String mailbox, int depth);

        /** @param mailbox the name the mailbox was created with; @param depth items still queued */
        void completed(String mailbox, int depth);
    }

    private static volatile FlowTap tap;

    private PrtsChunkFlow() {
    }

    public static void install(FlowTap watcher) {
        tap = watcher;
    }

    public static boolean installed() {
        return tap != null;
    }

    public static FlowTap watcher() {
        return tap;
    }

    public static void submitted(String mailbox, int depth) {
        FlowTap watcher = tap;
        if (watcher != null) {
            watcher.submitted(mailbox, depth);
        }
    }

    public static void completed(String mailbox, int depth) {
        FlowTap watcher = tap;
        if (watcher != null) {
            watcher.completed(mailbox, depth);
        }
    }
}
