/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.sites;

import io.izzel.arclight.common.prts.kernel.auth.HolderKind;
import io.izzel.arclight.common.prts.kernel.codes.WriteDisposition;

import java.util.concurrent.atomic.AtomicLongArray;

/** The counters are a flat array of plain long cells, so counting one attempt is a comparison and
 * an atomic add: no map lookup, no key string, nothing to allocate. */
public final class WritePathCounters {

    private static final int ORIGIN_COUNT = ThreadOrigin.values().length;
    private static final int HOLDER_COUNT = HolderKind.values().length;
    private static final int DISPOSITION_COUNT = WriteDisposition.values().length;
    private static final int PAIRS_PER_PATH = ORIGIN_COUNT * HOLDER_COUNT;
    private static final int PAIR_COUNT = WritePath.values().length * PAIRS_PER_PATH;

    private final AtomicLongArray attempts = new AtomicLongArray(PAIR_COUNT);
    private final AtomicLongArray verdicts = new AtomicLongArray(PAIR_COUNT * DISPOSITION_COUNT);
    private final AtomicLongArray inFlight = new AtomicLongArray(PAIR_COUNT);
    private final AtomicLongArray versions = new AtomicLongArray(PAIR_COUNT);

    /** Counts an attempt as it enters a write point. */
    public void noteAttempt(WritePath path, ThreadOrigin origin, HolderKind holder) {
        int index = pairIndex(path, origin, holder);
        // The update is bracketed: a reader that sees an even version on both sides of its reads saw a
        // pair no writer was inside.
        versions.incrementAndGet(index);
        inFlight.incrementAndGet(index);
        attempts.incrementAndGet(index);
        versions.incrementAndGet(index);
    }

    /** Counts how an attempt left. */
    public void noteVerdict(WritePath path, ThreadOrigin origin, HolderKind holder,
                            WriteDisposition disposition) {
        int index = pairIndex(path, origin, holder);
        versions.incrementAndGet(index);
        verdicts.incrementAndGet(verdictIndex(path, origin, holder, disposition));
        inFlight.decrementAndGet(index);
        versions.incrementAndGet(index);
    }

    /** Counts an attempt whose verdict will never arrive. The pair then reads as a missing counter
     * rather than staying in flight forever. */
    public void noteUnjudged(WritePath path, ThreadOrigin origin, HolderKind holder) {
        int index = pairIndex(path, origin, holder);
        versions.incrementAndGet(index);
        inFlight.decrementAndGet(index);
        versions.incrementAndGet(index);
    }

    /** Returns the attempts one pair saw. */
    public long attempts(WritePath path, ThreadOrigin origin, HolderKind holder) {
        return attempts.get(pairIndex(path, origin, holder));
    }

    /** Returns one disposition count. */
    public long count(WritePath path, ThreadOrigin origin, HolderKind holder,
                      WriteDisposition disposition) {
        return verdicts.get(verdictIndex(path, origin, holder, disposition));
    }

    /** Returns all attempts at one write point. */
    public long attemptsAt(WritePath path) {
        long total = 0L;
        for (int origin = 0; origin < ORIGIN_COUNT; origin++) {
            for (int holder = 0; holder < HOLDER_COUNT; holder++) {
                total += attempts.get(path.ordinal() * PAIRS_PER_PATH + origin * HOLDER_COUNT + holder);
            }
        }
        return total;
    }

    /** Returns one disposition over every write point. */
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

    public long totalAttempts() {
        long total = 0L;
        for (WritePath path : WritePath.values()) {
            total += attemptsAt(path);
        }
        return total;
    }

    public long unregisteredAttempts() {
        long total = 0L;
        for (WritePath path : WritePath.values()) {
            for (ThreadOrigin origin : ThreadOrigin.values()) {
                total += attempts(path, origin, HolderKind.UNREGISTERED);
            }
        }
        return total;
    }

    /** An attempt that entered and whose verdict has not landed yet is subtracted first: a writer
     * between the two cells is in flight, not lost, so a check that runs across a concurrent
     * writer does not read a transient state as a missing counter. */
    public boolean closureHolds() {
        for (int path = 0; path < WritePath.values().length; path++) {
            for (int origin = 0; origin < ORIGIN_COUNT; origin++) {
                for (int holder = 0; holder < HOLDER_COUNT; holder++) {
                    int index = path * PAIRS_PER_PATH + origin * HOLDER_COUNT + holder;
                    long before = versions.get(index);
                    if ((before & 1L) != 0L) {
                        // A writer is inside this pair right now; that window is not a failure.
                        continue;
                    }
                    long seen = 0L;
                    for (int disposition = 0; disposition < DISPOSITION_COUNT; disposition++) {
                        seen += verdicts.get(index * DISPOSITION_COUNT + disposition);
                    }
                    long counted = attempts.get(index);
                    long flying = inFlight.get(index);
                    if (versions.get(index) != before) {
                        continue;
                    }
                    if (counted < seen || counted - seen > flying) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public long inFlightAttempts() {
        long total = 0L;
        for (int index = 0; index < PAIR_COUNT; index++) {
            total += inFlight.get(index);
        }
        return total;
    }

    public int pairsChecked() {
        return PAIR_COUNT;
    }

    /** Clears every counter. */
    public void reset() {
        for (int index = 0; index < attempts.length(); index++) {
            attempts.set(index, 0L);
        }
        for (int index = 0; index < verdicts.length(); index++) {
            verdicts.set(index, 0L);
        }
        for (int index = 0; index < inFlight.length(); index++) {
            inFlight.set(index, 0L);
        }
        for (int index = 0; index < versions.length(); index++) {
            versions.set(index, 0L);
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
