/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * Parallel dispatch of one tick of independent entity work: the frozen plan, the worker pool,
 * cancellation and the merge back on the thread that owns the world.
 *
 * <p>A plan freezes the tasks of one tick before anything runs; the pool executes each task on a
 * named worker that only writes its own arena slot; the merge walks the frozen order at the next
 * tick boundary and is the only place a result is accepted. The package is separate so the
 * scheduling concerns - pool lifetime, deadline, cancellation, backpressure and the failure
 * classification - can be read without the data layout or the state hash.</p>
 *
 * <p>Design reference: the dispatch and merge sections of the kernel design note.</p>
 */
package io.izzel.arclight.common.prts.kernel.dispatch;
