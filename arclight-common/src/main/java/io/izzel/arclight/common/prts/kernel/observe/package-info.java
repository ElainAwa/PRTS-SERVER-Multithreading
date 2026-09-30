/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The readout of the four pieces: the export, the status section, the self-check and the command
 * extension that carries them into {@code /prts}.
 *
 * <p>This is the top of the kernel's internal order and the only package allowed to read every
 * piece below it. The field names published here are observation requests rather than an approved
 * counter table, which the export states and which is why every field is published even when its
 * value is zero. Nothing here changes state, and the self-check runs its matrix on scratch objects
 * so a running server can print it without touching the live counters.</p>
 */
package io.izzel.arclight.common.prts.kernel.observe;
