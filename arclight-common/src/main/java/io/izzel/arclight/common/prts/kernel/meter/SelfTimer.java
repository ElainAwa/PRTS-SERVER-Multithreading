/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.meter;

/**
 * One bounded meter: a window total, a tick total, and a ring of samples for the percentiles.
 *
 * <p>The recording path is a handful of field writes with no allocation and no lock; the ring is
 * fixed, and a sample that no longer fits is counted as lost instead of growing the meter. Dropping
 * old samples is what keeps a busy class from turning the observation into the bottleneck it is
 * supposed to prove it is not.</p>
 */
public final class SelfTimer {

    private final long[] ring;
    private long windowNanos;
    private long tickNanos;
    private long recorded;
    private long lost;
    private int index;

    /**
     * Creates a meter.
     *
     * @param ringSize samples kept for the percentiles; at least one
     */
    public SelfTimer(int ringSize) {
        this.ring = new long[Math.max(1, ringSize)];
    }

    /**
     * Records one sample.
     *
     * @param nanos duration; callers pass positive values
     */
    public void add(long nanos) {
        windowNanos += nanos;
        tickNanos += nanos;
        recorded++;
        if (recorded > ring.length) {
            lost++;
        }
        ring[index] = nanos;
        index = (index + 1) % ring.length;
    }

    /** Clears every counter. */
    public void clear() {
        windowNanos = 0L;
        tickNanos = 0L;
        recorded = 0L;
        lost = 0L;
        index = 0;
    }

    /** @return time accumulated in the current window */
    public long windowNanos() {
        return windowNanos;
    }

    /** @return time accumulated in the tick that is running */
    public long tickNanos() {
        return tickNanos;
    }

    /** @return samples recorded since the window started */
    public long recorded() {
        return recorded;
    }

    /** @return samples the ring had to drop */
    public long lost() {
        return lost;
    }

    /** @return samples still in the ring */
    public int retained() {
        return (int) Math.min(recorded, ring.length);
    }

    /**
     * Copies the retained samples out of the ring.
     *
     * @param target array to fill
     * @param offset position to start at
     * @return the number of samples written
     */
    public int copySamples(long[] target, int offset) {
        int count = retained();
        for (int position = 0; position < count; position++) {
            target[offset + position] = ring[position];
        }
        return count;
    }

    /** Clears the window totals and the ring, keeping the meter itself. */
    public void clearWindow() {
        windowNanos = 0L;
        recorded = 0L;
        lost = 0L;
        index = 0;
    }

    /** Clears the tick total. */
    public void clearTick() {
        tickNanos = 0L;
    }
}
