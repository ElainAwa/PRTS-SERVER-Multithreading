/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.owner;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import io.izzel.arclight.common.prts.kernel.domain.entity.owner.OwnerBodyMarker;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import org.spongepowered.asm.mixin.Mixin;

/** Guards the per-level path type cache while opt-in parallel owner bodies search paths. The host
 * path never sets the marker, so its cache call is the original one; the lock only spans the cache
 * read/compute, not the whole search. */
@Mixin(PathTypeCache.class)
public abstract class PrtsPathTypeCacheMixin {

    private static final Object PRTS_LOCK = new Object();

    @WrapMethod(method = "getOrCompute")
    private PathType prts$guardedGetOrCompute(BlockGetter level, BlockPos pos,
                                               Operation<PathType> original) {
        if (!OwnerBodyMarker.active()) {
            return original.call(level, pos);
        }
        synchronized (PRTS_LOCK) {
            return original.call(level, pos);
        }
    }

    @WrapMethod(method = "invalidate")
    private void prts$guardedInvalidate(BlockPos pos, Operation<Void> original) {
        if (!OwnerBodyMarker.active()) {
            original.call(pos);
            return;
        }
        synchronized (PRTS_LOCK) {
            original.call(pos);
        }
    }
}
