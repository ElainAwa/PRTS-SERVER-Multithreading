/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport.sable;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.support.PrtsSableLocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BiFunction;

/**
 * Puts the two memoized voxel lookups under one lock: two threads that miss at the same time
 * would resize the same cache table and leave it inconsistent. The mod keeps its cache, keys
 * and answers; only the lookup moves under the lock.
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = "sable", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState", remap = false)
public abstract class PrtsSableVoxelCacheMixin {

    @Redirect(method = "isSolid", at = @At(value = "INVOKE",
        target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
        remap = false)
    private static Object prts$serializeSolidLookup(BiFunction<Object, Object, Object> lookup, Object getter,
                                                     Object state) {
        if (!prts$voxelCacheEnabled()) {
            return lookup.apply(getter, state);
        }
        return PrtsSableLocks.VoxelCache.lookup(lookup, getter, state);
    }

    @Redirect(method = "isFullBlock", at = @At(value = "INVOKE",
        target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
        remap = false)
    private static Object prts$serializeFullBlockLookup(BiFunction<Object, Object, Object> lookup, Object getter,
                                                        Object state) {
        if (!prts$voxelCacheEnabled()) {
            return lookup.apply(getter, state);
        }
        return PrtsSableLocks.VoxelCache.lookup(lookup, getter, state);
    }

    private static boolean prts$voxelCacheEnabled() {
        return PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, "guard-sable-voxel-cache");
    }
}
