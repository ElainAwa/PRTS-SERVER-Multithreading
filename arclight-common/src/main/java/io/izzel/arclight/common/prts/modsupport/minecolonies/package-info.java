/* SPDX-License-Identifier: GPL-3.0-or-later */
/**
 * MineColonies interoperability: the guarded compatibility discovery of that mod.
 *
 * <p>The single patch replays the mod's own discovery sequence step by step, so one failing step
 * cannot stop the rest. It stands alone because it is the only patch of that mod at present, and
 * it owns its switches and counters; a further patch of the same mod belongs next to it.
 * Design reference: the release-facing mod compatibility rounds.</p>
 */
package io.izzel.arclight.common.prts.modsupport.minecolonies;
