/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The commit layer: every write of one tick converges on one log, the log follows the commit order
 * the planning period froze and nothing else, the write set of every step is written down so a
 * replayed run can be compared with the run that happened, and the buffers are one ring per
 * (world, domain) with a declared capacity that refuses instead of growing.
 *
 * <p>It is its own package because the commit layer is the only place a write is ordered: the
 * producers hand it what they did, the log decides whether that was the planned order, and the
 * observation layer reads the answer.
 *
 * <p>The design section this package answers to is 2.16; a plan and not this layer assigns the order.
 */
package io.izzel.arclight.common.prts.kernel.commit;
