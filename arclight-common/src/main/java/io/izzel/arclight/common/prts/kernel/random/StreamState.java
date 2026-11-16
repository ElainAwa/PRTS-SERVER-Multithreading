/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.random;

import java.util.Objects;

/**
 * The versioned additional-data schema of one keyed stream.
 *
 * <p>Data version 1 stores the derived initial state, the number of consumed generator values,
 * the gaussian cache and the fork count. Restoring rebuilds the generator from the initial state
 * and replays the recorded number of values, so the running state is reproduced exactly while the
 * generator implementation stays the upstream one. A later data version may store the running
 * state directly; a state whose version is not current is refused. The save-file binding is not
 * part of this library: a persistence layer must attach a stream state to the publication revision
 * it was read at, so an older state is never replayed over a newer revision.
 */
public record StreamState(
    int dataVersion,
    StreamKey key,
    long seedLo,
    long seedHi,
    long draws,
    boolean hasGaussian,
    double gaussian,
    long forks) {

    /** The only data version this library writes or reads. */
    public static final int CURRENT_DATA_VERSION = 1;

    public StreamState {
        Objects.requireNonNull(key, "key");
        if (dataVersion != CURRENT_DATA_VERSION) {
            throw new IllegalArgumentException("unsupported stream state data version: " + dataVersion);
        }
        if (draws < 0L || forks < 0L) {
            throw new IllegalArgumentException("draw and fork counts must not be negative");
        }
    }
}
