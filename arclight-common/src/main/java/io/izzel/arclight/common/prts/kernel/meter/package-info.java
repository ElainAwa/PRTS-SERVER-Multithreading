/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The per-class self timer and the window it publishes.
 *
 * <p>A sample is a bounded, fixed-size write into a per-thread meter: no lock, no allocation and no
 * virtual call on the recording path, and a row for every class even when nothing was sampled. Wait
 * time is kept strictly next to the classes, and the time the observation itself spends is a row of
 * its own, so the three self-monitoring values can be published.</p>
 */
package io.izzel.arclight.common.prts.kernel.meter;
