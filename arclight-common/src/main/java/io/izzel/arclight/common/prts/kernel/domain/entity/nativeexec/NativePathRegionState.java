/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec;

import net.minecraft.world.level.chunk.ChunkAccess;

/** The section coverage of one navigation region. Two regions are the same search input only when
 * they cover the same sections with the same chunk objects: a chunk that unloaded or arrived since
 * the plan is a different region, so a staged path is never handed to it. */
public interface NativePathRegionState {

    int prts$centerX();

    int prts$centerZ();

    ChunkAccess[][] prts$chunks();
}
