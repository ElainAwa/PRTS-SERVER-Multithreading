/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * The mob spawning call that reaches a chunk by block position and therefore has to have it in
 * memory before it can decide where a mob goes.
 *
 * The hook opens an observation at the head of the method and closes it at the return, and that is
 * all it does: no upper bound is read into a decision, no wait is shortened, delayed or cancelled,
 * and the call proceeds exactly as it did before. With no watcher installed both calls are a single
 * volatile read each.
 */
package io.izzel.arclight.common.prts.fixes.waitpoints;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsWaitSites;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.NaturalSpawner;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(NaturalSpawner.class)
public abstract class PrtsNaturalSpawnerWaitSiteMixin {

    @Inject(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;"
        + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)V",
        at = @At("HEAD"))
    private static void prts$openSpawnCategoryForPosition(MobCategory category, ServerLevel level,
                                                          BlockPos pos, CallbackInfo ci) {
        PrtsWaitSites.begin(PrtsWaitSites.NATURAL_SPAWNER_SPAWN_CATEGORY);
    }

    @Inject(method = "spawnCategoryForPosition(Lnet/minecraft/world/entity/MobCategory;"
        + "Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)V",
        at = @At("RETURN"))
    private static void prts$closeSpawnCategoryForPosition(MobCategory category, ServerLevel level,
                                                           BlockPos pos, CallbackInfo ci) {
        PrtsWaitSites.end(PrtsWaitSites.NATURAL_SPAWNER_SPAWN_CATEGORY, level);
    }
}
