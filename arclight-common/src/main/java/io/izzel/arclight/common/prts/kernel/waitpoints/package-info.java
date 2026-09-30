/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The wait point registry: the one legal list of waits and the coverage it can prove.
 *
 * <p>A row is complete when it names its producer, its progress signal, its timeout action and its
 * degradation target; a row missing any of them is refused rather than stored. Observation never
 * changes what a wait does, an unregistered call site is counted and listed instead of refused, and
 * the forced convergence count stays at zero because this batch has no convergence to run.</p>
 */
package io.izzel.arclight.common.prts.kernel.waitpoints;
