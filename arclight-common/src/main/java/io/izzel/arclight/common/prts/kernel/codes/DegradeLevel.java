/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.codes;

/**
 * The fixed let-way levels of the time-budget ladder.
 *
 * <p>This batch never executes a level: a refusal records what the level would be, and the
 * executed flag of the record stays false.</p>
 */
public enum DegradeLevel {

    /** No level would run. */
    NONE,
    /** Level one: reduce the AI decision rate. */
    B1,
    /** Level two: postpone the graph recompute. */
    B2,
    /** Level three: merge the entity and block entity batches. */
    B3,
    /** Level four: turn events into a queue. */
    B4,
    /** Level five: shrink the re-entry shape for this tick. */
    B5
}
