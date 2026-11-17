/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.support;

/** The closed trait set one supported entity class may declare. A class support is the declared
 * set of traits plus the program kind the per-class table derives from the highest trait. */
public enum EntityTrait {

    TICK,
    LIVING,
    MOB,
    BRAIN,
    GOAL,
    SENSOR,
    NAVIGATION,
    MOVE_CONTROL
}
