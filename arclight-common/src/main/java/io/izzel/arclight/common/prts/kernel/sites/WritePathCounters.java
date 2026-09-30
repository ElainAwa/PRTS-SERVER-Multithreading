/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;

import java.util.concurrent.atomic.AtomicLongArray;

/**
 * Counts every attempt a real write path makes, by point, thread and holder.
 *
 * <p>The counters are a flat array of plain long cells, so counting one attempt is a comparison and
 * an atomic add: no map lookup, no key string, nothing to allocate. That is what lets the short
 * path of the hot write path stay free of allocation.</p>
 *
 * <p>An attempt is counted when it enters and the disposition when it leaves, in two cells. A pair
 * whose three dispositions do not add up to its attempts therefore means a verdict was lost on the
 * way, which is exactly the failure the accounting check exists to see.</p>
 */
public final class WritePathCounters {

    private static final int ORIGIN_COUNT = ThreadOrigin.values().length;
    private static final int HOLDER_COUNT = HolderKind.values().length;
    private static final int DISPOSITION_COUNT = WriteDisposition.values().length;
    private static final int PAIRS_PER_PATH = ORIGIN_COUNT * HOLDER_COUNT;
    private static final int PAIR_COUNT = WritePath.values().length * PAIRS_PER_PATH;

    private final AtomicLongArray attempts = new AtomicLongArray(PAIR_COUNT);
    private final AtomicLongArray verdicts = new AtomicLongArray(PAIR_COUNT * DISPOSITION_COUNT);

    /**
     * Counts an attempt as it enters a write point.
     *
     * @param path   the write point
     * @param origin thread the attempt came from
     * @param holder where the writer comes from
     */
    public void noteAttempt(WritePath path, ThreadOrigin origin, HolderKind holder) {
        attempts.incrementAndGet(pairIndex(path, origin, holder));
    }

    /**
     * Counts how an attempt left.
     *
     * @param path        the write point
     * @param origin      thread the attempt came from
     * @param holder      where the writer comes from
     * @param disposition how the attempt left
     */
    public void noteVerdict(WritePath path, ThreadOrigin origin, HolderKind holder,
                            WriteDisposition disposition) {
        verdicts.incrementAndGet(verdictIndex(path, origin, holder, disposition));
    }

    /**
     * Returns the attempts one pair saw.
     *
     * @param path   the write point
     * @param origin thread the attempts came from
     * @param holder where the writers come from
     * @return the attempt count
     */
    public long attempts(WritePath path, ThreadOrigin origin, HolderKind holder) {
        return attempts.get(pairIndex(path, origin, holder));
    }

    /**
     * Returns one disposition count.
     *
     * @param path        the write point
     * @param origin      thread the attempts came from
     * @param holder      where the writers come from
     * @param disposition the disposition to read
     * @return the count of that disposition
     */
    public long count(WritePath path, ThreadOrigin origin, HolderKind holder,
                      WriteDisposition disposition) {
        return verdicts.get(verdictIndex(path, origin, holder, disposition));
    }

    /**
     * Returns all attempts at one write point.
     *
     * @param path the write point
     * @return the attempt count over every thread and holder
     */
    public long attemptsAt(WritePath path) {
        long total = 0L;
        for (int origin = 0; origin < ORIGIN_COUNT; origin++) {
            for (int holder = 0; holder < HOLDER_COUNT; holder++) {
                total += attempts.get(path.ordinal() * PAIRS_PER_PATH + origin * HOLDER_COUNT + holder);
            }
        }
        return total;
    }

    /**
     * Returns one disposition over every write point.
     *
     * @param disposition the disposition to read
     * @return the count
     */
    public long total(WriteDisposition disposition) {
        long total = 0L;
        for (int path = 0; path < WritePath.values().length; path++) {
            for (int origin = 0; origin < ORIGIN_COUNT; origin++) {
                for (int holder = 0; holder < HOLDER_COUNT; holder++) {
                    total += verdicts.get(verdictIndex(WritePath.values()[path],
                        ThreadOrigin.values()[origin], HolderKind.values()[holder], disposition));
                }
            }
        }
        return total;
    }

    /** @return every attempt counted, over all write points */
    public long totalAttempts() {
        long total = 0L;
        for (WritePath path : WritePath.values()) {
            total += attemptsAt(path);
        }
        return total;
    }

    /** @return attempts whose holder was never declared */
    public long unregisteredAttempts() {
        long total = 0L;
        for (WritePath path : WritePath.values()) {
            for (ThreadOrigin origin : ThreadOrigin.values()) {
                total += attempts(path, origin, HolderKind.UNREGISTERED);
            }
        }
        return total;
    }

    /**
     * Recomputes the accounting of every pair.
     *
     * @return {@code true} when every pair closes
     */
    public boolean closureHolds() {
        for (int path = 0; path < WritePath.values().length; path++) {
            for (int origin = 0; origin < ORIGIN_COUNT; origin++) {
                for (int holder = 0; holder < HOLDER_COUNT; holder++) {
                    int index = path * PAIRS_PER_PATH + origin * HOLDER_COUNT + holder;
                    long seen = 0L;
                    for (int disposition = 0; disposition < DISPOSITION_COUNT; disposition++) {
                        seen += verdicts.get(index * DISPOSITION_COUNT + disposition);
                    }
                    if (seen != attempts.get(index)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** @return the pairs the closing check walks */
    public int pairsChecked() {
        return PAIR_COUNT;
    }

    /** Clears every counter. Used by the readout reset and by tests. */
    public void reset() {
        for (int index = 0; index < attempts.length(); index++) {
            attempts.set(index, 0L);
        }
        for (int index = 0; index < verdicts.length(); index++) {
            verdicts.set(index, 0L);
        }
    }

    private static int pairIndex(WritePath path, ThreadOrigin origin, HolderKind holder) {
        return (path.ordinal() * ORIGIN_COUNT + origin.ordinal()) * HOLDER_COUNT + holder.ordinal();
    }

    private static int verdictIndex(WritePath path, ThreadOrigin origin, HolderKind holder,
                                    WriteDisposition disposition) {
        return pairIndex(path, origin, holder) * DISPOSITION_COUNT + disposition.ordinal();
    }
}
