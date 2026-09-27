/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * PRTS category: correctness fixes.
 *
 * <p>Long-lived fixes for crashes, deadlocks, injection anchors and serialization fallbacks.</p>
 *
 * <p>Also hosts shared PRTS infrastructure (configuration layer, /prts reload).</p>
 *
 * <p>Nothing here may land on a new-kernel seam; work on the tick loop, the chunk pipeline, entity
 * queries and tracking, lighting, networking, world lifecycle, commit ordering, arena storage,
 * observation counters, wait points or I/O belongs to the kernel subtree.</p>
 */
package io.izzel.arclight.common.prts.fixes;
