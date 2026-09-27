/* SPDX-License-Identifier: GPL-3.0-or-later */
/*
 * Ported from PRTS-1.21.1, commit c3fd159c03161e0b89d89b3c7c221f07a4a74791
 * ("fix gameEvent dispatch crash on synthetic levels without CraftWorld").
 * Reworked as a standalone mixin of the prts.fixes category; see THIRD-PARTY.md.
 */
package io.izzel.arclight.common.prts.fixes;

import io.izzel.arclight.common.bridge.core.world.level.LevelAccessorBridge;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Cancels the Bukkit game-event dispatch for levels that are not actual.
 *
 * <p>Synthetic dimensions — levels that exist only in memory, such as the projectile simulation
 * level some mods create — have no {@code CraftWorld}, so the event dispatch throws from the world
 * lookup and turns a simulation tick into a server crash. The guard runs at the head of
 * {@code ServerLevel#gameEvent} and cancels before the upstream Arclight injection is reached.</p>
 *
 * <p>The priority is raised above the upstream configuration so this callback is inserted (and
 * therefore executed) first. For every real level the condition is false and behaviour is
 * unchanged.</p>
 */
@Mixin(value = ServerLevel.class, priority = 1500)
public abstract class PrtsSyntheticLevelGameEventMixin {

    @Inject(method = "gameEvent", cancellable = true, at = @At("HEAD"))
    private void prts$skipGameEventOnSyntheticLevel(Holder<GameEvent> holder, Vec3 pos,
                                                    GameEvent.Context context, CallbackInfo ci) {
        if (!((LevelAccessorBridge) this).arclight$isActual()) {
            ci.cancel();
        }
    }
}
