/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The region and world lifecycle contract: a region is the eight-connected component of loaded
 * chunks and advances its topology generation only when components really merge or split, a slow
 * region is isolated without a server-wide barrier, a world's ledger stays apart from every other
 * world, and an unload walks four bounded steps and reaches GONE only when every step succeeded. */
package io.izzel.arclight.common.prts.kernel.domain.region;
