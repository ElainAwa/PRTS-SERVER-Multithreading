/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.waitpoints;

/**
 * What the registry answers about one wait.
 *
 * <p>The answer is a reading, not a command: it reports the longest wait seen for the row, whether
 * this wait crossed the configured upper bound and the progress value that came with it. Nothing in
 * it makes a wait longer, shorter or cancelled.</p>
 *
 * @param maxWaitMs       longest wait observed for that row so far
 * @param overrun         whether this wait exceeded the configured upper bound
 * @param progressReading the progress value that came with the wait
 */
public record WaitObservation(long maxWaitMs, boolean overrun, String progressReading) {
}
