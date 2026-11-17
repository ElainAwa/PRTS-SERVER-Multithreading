/* SPDX-License-Identifier: GPL-3.0-or-later */
/** The in-game product graph contract: device transfer and POI navigation graphs carry a topology
 * revision, recompute only their affected component under a declared budget, and refuse a full
 * rebuild without it through the existing unbounded-recompute recorder. */
package io.izzel.arclight.common.prts.kernel.domain.graph;
