/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The dispatch pipeline of one tick: the worker pool, cancellation, the merge back on the thread that
 * owns the world and the write-back of a settled batch. The rows it works on, the frame it freezes
 * and the step a worker runs belong to the entity domain. Separate so pool lifetime, deadline,
 * backpressure and failure classification can be read without the arena layout or the state hash.
 * Design reference: the dispatch and merge section.
 */
package io.izzel.arclight.common.prts.kernel.dispatch;
