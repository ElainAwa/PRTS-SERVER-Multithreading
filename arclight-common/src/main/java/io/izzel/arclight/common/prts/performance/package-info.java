/**
 * PRTS category: performance work.
 *
 * <p>Only optimizations that do not land on a new-kernel seam (tick loop, chunk pipeline, entity query/tracking, lighting, network, world lifecycle, TickPlan/JobGraph, arena/segment, N1-N6, degradation ladder, WP-*, I/O and serialization) belong here; everything else is owned by the kernel.</p>
 *
 * <p>See docs/PRTS-CONVENTIONS.md, C-003.</p>
 */
package io.izzel.arclight.common.prts.performance;
