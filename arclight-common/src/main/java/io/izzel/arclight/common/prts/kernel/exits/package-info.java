/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The two exits of the observation layer: a tick-level control plane the next planning period
 * consumes and a window-level judgement plane the acceptance readings consume.
 *
 * <p>It is its own package because the separation is the deliverable: the two exits share one
 * counter base and one reading name space, and what keeps them apart - the plane, the window and
 * the refusal of a window statistic on the control side - is a rule about this package alone.
 *
 * <p>The design section this package answers to is 2.10; neither exit writes anything, and a write
 * path that asks the judgement exit is refused and counted instead of served.
 */
package io.izzel.arclight.common.prts.kernel.exits;
