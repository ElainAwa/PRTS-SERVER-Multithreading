/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The shared refusal vocabulary: codes, triggers, dispositions, conflict classes and levels.
 *
 * <p>This is the bottom of the kernel's internal order. Every type here is plain data or an enum,
 * and nothing here imports another kernel package, so the pieces above can agree on one vocabulary
 * without any of them depending on each other through it. Diagnostic information a refusal carries
 * lives here as well.</p>
 */
package io.izzel.arclight.common.prts.kernel.codes;
