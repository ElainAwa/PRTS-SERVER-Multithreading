/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.fixes.owner;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import io.izzel.arclight.common.prts.kernel.domain.entity.owner.OwnerBodyMarker;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.pathfinder.PathTypeCache;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Behaviour: a path search started from an opt-in parallel owner body reads its block path types
 * from a cache it owns instead of the level's shared one. Why here: the context chooses its cache in
 * its constructor, before the search. Point: that one choice. Invariant: the cache instance lives
 * only for the search, so no other body sees an entry; the host search keeps the shared cache.
 */
@Mixin(PathfindingContext.class)
public abstract class PrtsPathfindingContextMixin {

    @WrapOperation(method = "<init>",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getPathTypeCache()"
                + "Lnet/minecraft/world/level/pathfinder/PathTypeCache;"))
    private PathTypeCache prts$perSearchCache(ServerLevel level, Operation<PathTypeCache> original) {
        return OwnerBodyMarker.active() ? new PathTypeCache() : original.call(level);
    }
}
