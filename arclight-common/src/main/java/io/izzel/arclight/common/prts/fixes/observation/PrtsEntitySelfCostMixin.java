/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Times the host tick of one non-player entity row so the per-class self timer and the attribution
 * face have a producer on a world with citizens, mobs or machines of its own. Nothing is cancelled
 * or delayed: the span is read around the call and reported after it. */
package io.izzel.arclight.common.prts.fixes.observation;

import io.izzel.arclight.api.ArclightPlatform;
import io.izzel.arclight.common.mod.mixins.annotation.OnlyInPlatform;
import io.izzel.arclight.common.prts.support.PrtsCpuClock;
import io.izzel.arclight.common.prts.support.PrtsEntityCosts;
import io.izzel.arclight.common.prts.support.PrtsSelfCosts;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** One entity ticks at a time on the server thread, so one start stamp is enough. */
@OnlyInPlatform(ArclightPlatform.NEOFORGE)
@Mixin(ServerLevel.class)
public abstract class PrtsEntitySelfCostMixin {

    @Unique
    private long prts$entityStartedAt;

    @Unique
    private long prts$entityCpuStartedAt;

    @Inject(method = "tickNonPassenger", at = @At("HEAD"))
    private void prts$openEntityTick(Entity entity, CallbackInfo ci) {
        if (PrtsSelfCosts.installed() || PrtsEntityCosts.installed()) {
            prts$entityStartedAt = System.nanoTime();
            prts$entityCpuStartedAt = PrtsCpuClock.now();
        }
    }

    @Inject(method = "tickNonPassenger", at = @At("RETURN"))
    private void prts$closeEntityTick(Entity entity, CallbackInfo ci) {
        boolean self = PrtsSelfCosts.installed();
        boolean attribution = PrtsEntityCosts.installed();
        if (!self && !attribution) {
            return;
        }
        ServerLevel level = (ServerLevel) (Object) this;
        String worldId = level.dimension().location().toString();
        long wall = System.nanoTime() - prts$entityStartedAt;
        if (self) {
            PrtsSelfCosts.entityRow(worldId, entity.getClass().getSimpleName(), wall);
        }
        if (attribution) {
            PrtsEntityCosts.entityTick(entity, level, worldId, wall,
                PrtsCpuClock.since(prts$entityCpuStartedAt));
        }
    }
}
