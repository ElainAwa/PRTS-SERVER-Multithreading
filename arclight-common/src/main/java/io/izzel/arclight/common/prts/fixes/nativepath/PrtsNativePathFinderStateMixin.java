/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.nativepath;

import io.izzel.arclight.common.prts.kernel.domain.entity.nativeexec.NativePathFinderState;
import net.minecraft.world.level.pathfinder.PathFinder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Behaviour: the visited-node cap the navigation's own finder was built with becomes readable.
 * Why here: a staged search built with another cap is a different search, so the cap is part of the
 * reuse key and must come from the live finder rather than from a re-derived attribute.
 * Invariant: the accessor reads the final field and writes nothing. Failure: none.
 */
@Mixin(PathFinder.class)
public interface PrtsNativePathFinderStateMixin extends NativePathFinderState {

    @Accessor("maxVisitedNodes")
    int prts$maxVisitedNodes();
}
