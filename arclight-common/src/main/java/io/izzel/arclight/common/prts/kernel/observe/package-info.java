/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The readout: the export, the status lines, the self-check and the command extension behind
 * {@code /prts}. The only package allowed to read every piece below it, and nothing here changes
 * state. Design reference: the observation section.
 */
package io.izzel.arclight.common.prts.kernel.observe;
