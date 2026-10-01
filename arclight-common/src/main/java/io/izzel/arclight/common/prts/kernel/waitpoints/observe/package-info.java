/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The observation side of the wait point registry: it takes the waits the real call sites produce
 * and records them against the rows and the written-down list.
 */
/**
 * Records the waits the real call sites produce.
 *
 * <p>This package is the running half of the wait point registry. The registry itself owns the
 * written-down rows and the list of call sites; this half takes the waits those sites report through
 * the seam the world side knows, resolves each site number to its row and its call site reference,
 * and keeps the per-site readings the export publishes.</p>
 *
 * <p>It is a package of its own because the call site side and the registration side are two
 * different concerns with two different readers: one answers "where can a wait happen and what
 * covers it", the other answers "what did the waits of this run add up to". It sits inside the wait
 * point package rather than beside its two neighbours because it may not be used without them.</p>
 *
 * <p>Observation only: nothing here changes an upper bound, cancels a wait or executes a
 * convergence. It corresponds to the wait point registry section of the kernel design draft.</p>
 */
package io.izzel.arclight.common.prts.kernel.waitpoints.observe;
