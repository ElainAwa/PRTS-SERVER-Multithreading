/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * PRTS category: performance work.
 *
 * <p>Only optimizations that do not land on a new-kernel seam (tick loop, chunk pipeline, entity</p>
 *
 * <p>query/tracking, lighting, network, world lifecycle, TickPlan/JobGraph, arena/segment, N1-N6,</p>
 *
 * <p>degradation ladder, WP-*, I/O and serialization) belong here; everything else is kernel work.</p>
 *
 * <p>See docs/PRTS-CONVENTIONS.md, C-003.</p>
 */
package io.izzel.arclight.common.prts.performance;
