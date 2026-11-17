/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The light and heightmap revision contract: a section's dirty revision, the boundary inflow it
 * was solved against and the converged revision that may be published, with the invalidation that
 * follows a neighbour boundary change and the late-result refusal after a holder generation moves.
 * The package is a dormant contract layer: it computes and publishes no light. */
package io.izzel.arclight.common.prts.kernel.domain.chunk.light;
