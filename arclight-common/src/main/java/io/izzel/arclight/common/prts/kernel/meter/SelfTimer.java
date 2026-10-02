/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.meter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/**
 * One bounded meter: a window total, a tick total, and a ring of samples for the percentiles.
 *
 * <p>The recording path is a handful of atomic adds with no lock and no allocation, and the ring is
 * fixed: a sample that no longer fits is counted as lost instead of growing the meter. Dropping old
 * samples is what keeps a busy class from turning the observation into the bottleneck it is supposed
 * to prove it is not.</p>
 *
 * <p>The counters are adders rather than plain fields because a meter of one thread can be read, and
 * reset, by another: the publication walks the meters of every thread while those threads keep
 * recording. An adder cannot lose a concurrent sample the way a plain {@code long} does, and the ring
 * is an atomic array so a sample published while it is being written is never a torn value. A sample
 * taken while a window is being published may land in either window, which is what a readout nothing
 * decides on can accept.</p>
 */
public final class SelfTimer {

    private final AtomicLongArray ring;
    private final LongAdder windowNanos = new LongAdder();
    private final LongAdder tickNanos = new LongAdder();
    private final LongAdder recorded = new LongAdder();
    private final LongAdder lost = new LongAdder();
    private final AtomicInteger index = new AtomicInteger();

    /**
     * Creates a meter.
     *
     * @param ringSize samples kept for the percentiles; at least one
     */
    public SelfTimer(int ringSize) {
        this.ring = new AtomicLongArray(Math.max(1, ringSize));
    }

    /**
     * Records one sample.
     *
     * @param nanos duration; callers pass positive values
     */
    public void add(long nanos) {
        windowNanos.add(nanos);
        tickNanos.add(nanos);
        recorded.increment();
        if (recorded.sum() > ring.length()) {
            lost.increment();
        }
        ring.set(index.getAndUpdate(position -> (position + 1) % ring.length()), nanos);
    }

    /** Clears every counter. */
    public void clear() {
        windowNanos.reset();
        tickNanos.reset();
        recorded.reset();
        lost.reset();
        index.set(0);
    }

    /** @return time accumulated in the current window */
    public long windowNanos() {
        return windowNanos.sum();
    }

    /** @return time accumulated in the tick that is running */
    public long tickNanos() {
        return tickNanos.sum();
    }

    /** @return samples recorded since the window started */
    public long recorded() {
        return recorded.sum();
    }

    /** @return samples the ring had to drop */
    public long lost() {
        return lost.sum();
    }

    /** @return samples still in the ring */
    public int retained() {
        return (int) Math.min(recorded.sum(), ring.length());
    }

    /**
     * Copies the retained samples out of the ring.
     *
     * @param target array to fill
     * @param offset position to start at
     * @return the number of samples written
     */
    public int copySamples(long[] target, int offset) {
        // The publication sizes its array from a count read a moment ago; a sample recorded in
        // between must not write past the end of it, so the copy is bounded by both.
        int count = Math.min(retained(), Math.max(0, target.length - offset));
        for (int position = 0; position < count; position++) {
            target[offset + position] = ring.get(position);
        }
        return count;
    }

    /** Clears the window totals and the ring, keeping the meter itself. */
    public void clearWindow() {
        windowNanos.reset();
        recorded.reset();
        lost.reset();
        index.set(0);
    }

    /** Clears the tick total. */
    public void clearTick() {
        tickNanos.reset();
    }
}
