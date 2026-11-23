/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.nativepath;

import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathRegionState;
import net.minecraft.world.level.PathNavigationRegion;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Behaviour: one navigation region's section coverage and chunk objects become readable.
 * Why here: two regions are the same search input only when they hold the same chunk objects in the
 * same sections, and only the region can answer that. Invariant: the accessors read the live fields
 * and write nothing; the staged and the live region are both read through them. Failure: none.
 */
@Mixin(PathNavigationRegion.class)
public interface PrtsNativePathRegionStateMixin extends NativePathRegionState {

    @Accessor("centerX")
    int prts$centerX();

    @Accessor("centerZ")
    int prts$centerZ();

    @Accessor("chunks")
    ChunkAccess[][] prts$chunks();
}
