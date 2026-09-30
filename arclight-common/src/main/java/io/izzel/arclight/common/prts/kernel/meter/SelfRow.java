/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.meter;

/**
 * One published timer row.
 *
 * <p>The row exists for every class even when nothing was sampled: a missing row is an observation
 * failure, and a row of zeros is a statement. The two percentiles are always published as a pair.
 * </p>
 *
 * @param selfClass  the class this row belongs to
 * @param totalNanos accumulated time in the window
 * @param sharePct   share of the window total, in percent; zero when the window is empty
 * @param p50Nanos   median of the retained samples
 * @param p99Nanos   ninety-ninth percentile of the retained samples
 * @param samples    samples retained for the percentiles
 * @param lost       samples the ring had to drop
 */
public record SelfRow(SelfClass selfClass, long totalNanos, double sharePct, long p50Nanos,
                      long p99Nanos, long samples, long lost) {

    /** @return accumulated milliseconds of this row */
    public double totalMs() {
        return totalNanos / 1_000_000.0;
    }

    /** @return median in milliseconds */
    public double p50Ms() {
        return p50Nanos / 1_000_000.0;
    }

    /** @return ninety-ninth percentile in milliseconds */
    public double p99Ms() {
        return p99Nanos / 1_000_000.0;
    }
}
