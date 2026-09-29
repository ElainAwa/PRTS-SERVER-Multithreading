/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.common.prts.modsupport;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.LoadIfMod;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.config.PrtsConfigManager;
import io.izzel.arclight.common.prts.support.PrtsSableVoxelCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BiFunction;

/**
 * Keeps the two voxel caches of the optional physics mod from being filled by two threads at once.
 *
 * <p>Each of the two answers of that class delegates to one memoizing function, and that call is
 * what is redirected here: the mod keeps its cache, its keys and its answers, and only the lookup
 * itself moves under a lock. Without the mod nothing of this class is loaded.</p>
 *
 * <p>The switch is on by default: the corruption this prevents is a crash of the server, not a
 * change in how much work the mod does.</p>
 */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@LoadIfMod(modid = "sable", condition = LoadIfMod.ModCondition.PRESENT)
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.physics.chunk.VoxelNeighborhoodState", remap = false)
public abstract class PrtsSableVoxelCacheMixin {

    /**
     * Runs the solidity lookup under the cache lock.
     *
     * @param lookup memoizing function of the mod
     * @param getter first argument of the lookup
     * @param state  second argument of the lookup
     * @return the answer of the lookup
     */
    @Redirect(method = "isSolid", at = @At(value = "INVOKE",
        target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
        remap = false)
    private static Object prts$serializeSolidLookup(BiFunction<Object, Object, Object> lookup, Object getter,
                                                     Object state) {
        if (!prts$voxelCacheEnabled()) {
            return lookup.apply(getter, state);
        }
        return PrtsSableVoxelCache.lookup(lookup, getter, state);
    }

    /**
     * Runs the full-block lookup under the cache lock.
     *
     * @param lookup memoizing function of the mod
     * @param getter first argument of the lookup
     * @param state  second argument of the lookup
     * @return the answer of the lookup
     */
    @Redirect(method = "isFullBlock", at = @At(value = "INVOKE",
        target = "Ljava/util/function/BiFunction;apply(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"),
        remap = false)
    private static Object prts$serializeFullBlockLookup(BiFunction<Object, Object, Object> lookup, Object getter,
                                                        Object state) {
        if (!prts$voxelCacheEnabled()) {
            return lookup.apply(getter, state);
        }
        return PrtsSableVoxelCache.lookup(lookup, getter, state);
    }

    /**
     * Reports whether the guard is switched on.
     *
     * @return the value of the switch in effect
     */
    private static boolean prts$voxelCacheEnabled() {
        return PrtsConfigManager.feature(PrtsConfigManager.MODSUPPORT, "guard-sable-voxel-cache");
    }
}
