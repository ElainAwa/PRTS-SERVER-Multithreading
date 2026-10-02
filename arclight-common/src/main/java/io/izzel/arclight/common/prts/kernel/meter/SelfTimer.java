/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.meter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.LongAdder;

/** The recording path is a handful of atomic adds with no lock and no allocation, and the ring is
 * fixed: a sample that no longer fits is counted as lost instead of growing the meter. */
public final class SelfTimer {

    private final AtomicLongArray ring;
    private final LongAdder windowNanos = new LongAdder();
    private final LongAdder tickNanos = new LongAdder();
    private final LongAdder recorded = new LongAdder();
    private final LongAdder lost = new LongAdder();
    private final AtomicInteger index = new AtomicInteger();

    /** Creates a meter. */
    public SelfTimer(int ringSize) {
        this.ring = new AtomicLongArray(Math.max(1, ringSize));
    }

    /** Records one sample. */
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

    public long windowNanos() {
        return windowNanos.sum();
    }

    public long tickNanos() {
        return tickNanos.sum();
    }

    public long recorded() {
        return recorded.sum();
    }

    public long lost() {
        return lost.sum();
    }

    public int retained() {
        return (int) Math.min(recorded.sum(), ring.length());
    }

    /** Copies the retained samples out of the ring. */
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
