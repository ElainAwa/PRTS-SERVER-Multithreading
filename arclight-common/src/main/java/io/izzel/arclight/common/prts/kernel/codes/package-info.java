/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The shared refusal vocabulary: reject codes, triggers, dispositions, conflict classes and levels.
 * The bottom of the kernel order - nothing here imports another kernel package, so the pieces above
 * can agree on one vocabulary without depending on each other through it. Design reference: the
 * rejection-code section.
 */
package io.izzel.arclight.common.prts.kernel.codes;
