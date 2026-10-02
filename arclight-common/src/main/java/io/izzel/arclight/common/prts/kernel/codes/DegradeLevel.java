/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.codes;

/** The fixed let-way levels of the time-budget ladder. This batch never executes a level: a
 * refusal records what the level would be, and the executed flag of the record stays false. */
public enum DegradeLevel {

    NONE,
    B1,
    B2,
    B3,
    B4,
    B5
}
