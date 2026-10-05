/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The safety net of the kernel: the layer that watches the write right, the version slot, the wait
 * bound and the degradation ladder, counts what it sees on two dimensions and reports it.
 *
 * <p>It is its own package because reporting is a concern of its own: every other layer either
 * decides or executes, and none of them may keep its own tally of what went wrong.
 *
 * <p>The design section this package answers to is 2.10 and 2.11; the net repairs nothing, so an
 * external side effect that already happened stays where it is and is only counted.
 */
package io.izzel.arclight.common.prts.kernel.safety;
