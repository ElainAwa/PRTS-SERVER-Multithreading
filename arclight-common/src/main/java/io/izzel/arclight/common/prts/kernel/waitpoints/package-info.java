/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The wait point registry: the one legal list of waits, the written-down call sites and the coverage
 * they prove. A row is complete only with producer, progress signal, timeout action and degradation
 * target; observation never changes what a wait does. Beside the rows sit the progress readings they
 * publish, the upper bound one wait is measured against, and the action a crossed wait would take
 * together with the gate that lets it back. Design reference: the bounded-wait section.
 */
package io.izzel.arclight.common.prts.kernel.waitpoints;
