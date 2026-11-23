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
import org.spongepowered.asm.mixin.Unique;

/**
 * Behaviour: a path type cache call from an opt-in parallel owner body runs under one static lock;
 * every other caller keeps the original unsynchronised call. Why here: the cache is one plain array
 * pair per level and several bodies would interleave its two-step read/write. Point: the only two
 * cache entry points. Invariant: the lock spans one cache read or invalidation, not a whole search;
 * a thread that never ran an owner body takes the original branch.
 */
@Mixin(PathTypeCache.class)
public abstract class PrtsPathTypeCacheMixin {

    @Unique
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
