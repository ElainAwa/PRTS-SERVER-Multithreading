/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * Reserved for the new kernel.
 *
 * <p>Placeholder: the multithreaded scheduling kernel will own this subtree together with its own
 * mixin configuration. No implementation is written here yet.</p>
 *
 * <p>Until that kernel lands, a change that touches a kernel seam must not be placed in
 * {@code io.izzel.arclight.common.prts.performance}. The seams are the tick loop, the chunk
 * pipeline, entity queries and tracking, lighting, networking, world lifecycle, commit ordering
 * and planning, arena and segment storage, observation counters, the degradation ladder, wait
 * points, and I/O or serialization; such work belongs to the kernel subtree.</p>
 */
package io.izzel.arclight.common.prts.kernel;
