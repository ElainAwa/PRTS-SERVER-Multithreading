/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The refusal vocabulary of the kernel: the closed code set and the trigger table on top of it.
 *
 * <p>The set is closed on purpose. Every trigger of the four pieces is written down here once,
 * together with the disposition it produces, so a new trigger cannot appear at a call site without
 * a code and a reader can see the whole vocabulary in two files. Diagnostic information that every
 * refusal carries lives here as well.</p>
 */
package io.izzel.arclight.common.prts.kernel.codes;
