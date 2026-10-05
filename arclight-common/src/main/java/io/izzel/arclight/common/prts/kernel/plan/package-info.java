/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The planning period of the kernel: once per tick one plan is frozen and then only read. A plan
 * carries the job graph of the tick, the topological order and the commit order derived from it, the
 * share table it was planned against, the mode and the reason of every (world, domain) pair, and the
 * identity of the world set it belongs to.
 *
 * <p>It is its own package because the planning period is the only place a decision about a tick is
 * made: the execution, the commit and the observation layers read a plan and never change one.
 *
 * <p>The design section this package answers to is 2.13; the planning period reads no wall clock, and
 * a machine check over the compiled classes of this package and of the job package is what keeps
 * that true.
 */
package io.izzel.arclight.common.prts.kernel.plan;
