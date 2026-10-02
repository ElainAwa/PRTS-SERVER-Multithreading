/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The per-class self timer and the window it publishes. A sample is a bounded, lock-free write into
 * a per-thread meter; the time the observation itself spends is a row of its own. Design reference:
 * the observation section.
 */
package io.izzel.arclight.common.prts.kernel.meter;
