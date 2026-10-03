/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * The read-only host-side observation hooks: each class times one host path a takeover decision
 * depends on and hands the duration to the entity census seam. Nothing here reads a binding, skips
 * a step or changes what the host does, and no reading takes part in a decision.
 */
package io.izzel.arclight.common.prts.fixes.observation;
