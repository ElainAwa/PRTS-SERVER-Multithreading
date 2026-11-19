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

import io.izzel.arclight.common.prts.kernel.domain.entity.owner.ServerThreadOwnerPhase;

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

    /** Behaviour: hold the Post of a deferred row until the batch replays it in row order. Why here:
     * the wrapper would otherwise fire Post before the body ran. Invariant: every row gets exactly one
     * Post, either inline or at replay; failure: nothing extra is fired. */
    @Redirect(method = "tickNonPassenger", at = @At(value = "INVOKE",
        target = "Lnet/neoforged/neoforge/event/EventHooks;"
            + "fireEntityTickPost(Lnet/minecraft/world/entity/Entity;)V"))
    private void prts$deferPost(Entity entity) {
        if (ServerThreadOwnerPhase.isDeferred(entity)) {
            return;
        }
        EventHooks.fireEntityTickPost(entity);
    }
}