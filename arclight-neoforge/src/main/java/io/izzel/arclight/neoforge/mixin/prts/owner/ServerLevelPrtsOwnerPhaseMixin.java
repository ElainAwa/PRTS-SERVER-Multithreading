/* SPDX-License-Identifier: GPL-3.0-or-later */
package io.izzel.arclight.neoforge.mixin.prts.owner;

import io.izzel.arclight.common.prts.kernel.domain.entity.owner.ServerThreadOwnerPhase;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Behaviour: observe the final Pre verdict where the wrapper reads it, after dispatch. Why here: the
 * cancellation is final only at that point. Invariant: fire once, return the same event, record only a
 * pending row. Failure: nothing is re-fired or cancelled here. */
@Mixin(ServerLevel.class)
public abstract class ServerLevelPrtsOwnerPhaseMixin {

    @Redirect(method = "tickNonPassenger", at = @At(value = "INVOKE",
        target = "Lnet/neoforged/neoforge/event/EventHooks;"
            + "fireEntityTickPre(Lnet/minecraft/world/entity/Entity;)"
            + "Lnet/neoforged/neoforge/event/tick/EntityTickEvent$Pre;"))
    private EntityTickEvent.Pre prts$observePreVerdict(Entity entity) {
        EntityTickEvent.Pre pre = EventHooks.fireEntityTickPre(entity);
        if (ServerThreadOwnerPhase.hasPending(entity)) {
            ServerThreadOwnerPhase.observePreVerdict(entity, pre.isCanceled());
        }
        return pre;
    }
}
