/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.observe;

import io.izzel.arclight.common.prts.support.PrtsPipelineRows;

import java.util.concurrent.atomic.LongAdder;

/**
 * Counts what the chunk pipeline's own mailboxes ran: a task is one work item that left a mailbox
 * and executed, a round is one drain that carried work and is the unit the batch interface merges.
 * A mailbox nobody named falls into one bucket of its own, so an unnamed mailbox is visible instead
 * of being folded into a named one.
 *
 * <p>The counters belong to the kernel module, which installs this observer while the kernel
 * category is on and removes it when the category goes off; nothing else writes them.
 */
public final class PipelineRowObserver implements PrtsPipelineRows.MailboxRowTap {

    public static final String WORLDGEN = "worldgen";

    public static final String LIGHT = "light";

    public static final String SORTER = "sorter";

    public static final String MAIN = "main";

    public static final String OTHER = "other";

    private static final String[] BUCKETS = {WORLDGEN, LIGHT, SORTER, MAIN, OTHER};

    private final LongAdder[] tasks = adders();

    private final LongAdder[] taskNanos = adders();

    private final LongAdder[] rounds = adders();

    @Override
    public void mailboxTask(String mailbox, long nanos) {
        int index = indexOf(mailbox);
        tasks[index].increment();
        if (nanos > 0L) {
            taskNanos[index].add(nanos);
        }
    }

    @Override
    public void mailboxRound(String mailbox) {
        rounds[indexOf(mailbox)].increment();
    }

    /** @return the bucket names, in index order */
    public static String[] buckets() {
        return BUCKETS.clone();
    }

    /** @return the bucket a mailbox name belongs to; a name no bucket claims answers {@link #OTHER} */
    public static int indexOf(String mailbox) {
        if (mailbox != null) {
            for (int index = 0; index < BUCKETS.length - 1; index++) {
                if (BUCKETS[index].equals(mailbox)) {
                    return index;
                }
            }
        }
        return BUCKETS.length - 1;
    }

    public long tasks(String bucket) {
        return tasks[indexOf(bucket)].sum();
    }

    /** @return the exact time the tasks of one bucket ran, in nanoseconds; the truncated whole
     *     milliseconds of an export are not what a unit cost may be divided by */
    public long taskNanos(String bucket) {
        return taskNanos[indexOf(bucket)].sum();
    }

    public long rounds(String bucket) {
        return rounds[indexOf(bucket)].sum();
    }

    public long tasksTotal() {
        long total = 0L;
        for (LongAdder cell : tasks) {
            total += cell.sum();
        }
        return total;
    }

    public long roundsTotal() {
        long total = 0L;
        for (LongAdder cell : rounds) {
            total += cell.sum();
        }
        return total;
    }

    /** Installs this observer as the watcher behind the seam. */
    public void attach() {
        PrtsPipelineRows.install(this);
    }

    /** Removes this observer from the seam, unless somebody else owns it by now. */
    public void detach() {
        if (PrtsPipelineRows.watcher() == this) {
            PrtsPipelineRows.install(null);
        }
    }

    /** Clears every counter. */
    public void reset() {
        for (int index = 0; index < BUCKETS.length; index++) {
            tasks[index].reset();
            taskNanos[index].reset();
            rounds[index].reset();
        }
    }

    private static LongAdder[] adders() {
        LongAdder[] cells = new LongAdder[BUCKETS.length];
        for (int index = 0; index < cells.length; index++) {
            cells[index] = new LongAdder();
        }
        return cells;
    }
}
