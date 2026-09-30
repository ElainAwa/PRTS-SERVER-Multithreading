/* SPDX-License-Identifier: GPL-3.0-or-later */

/**
 * The world write points this layer watches, and who is writing through them.
 *
 * <p>It stands apart from the decision point itself because it owns a different question: not how a
 * write is judged, but which real write paths reach the judgement and which thread and holder each
 * attempt belongs to. The fast path lives here too, so the hot case never builds a record.</p>
 *
 * <p>Design reference: the write right section of the kernel design note.</p>
 */
package io.izzel.arclight.common.prts.kernel.sites;
