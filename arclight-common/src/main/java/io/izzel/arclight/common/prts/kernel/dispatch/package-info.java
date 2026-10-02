/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * Parallel dispatch of one tick: the frozen plan, the worker pool, cancellation and the merge back
 * on the thread that owns the world. Separate so pool lifetime, deadline, backpressure and failure
 * classification can be read without the arena layout or the state hash. Design reference: the
 * dispatch and merge section.
 */
package io.izzel.arclight.common.prts.kernel.dispatch;
