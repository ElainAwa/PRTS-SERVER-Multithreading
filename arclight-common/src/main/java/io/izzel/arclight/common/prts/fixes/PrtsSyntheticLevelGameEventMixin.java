/* SPDX-License-Identifier: GPL-3.0-or-later */
/* Ported from PRTS-1.21.1, commit c3fd159c03161e0b89d89b3c7c221f07a4a74791; see THIRD-PARTY.md. */
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
 * Cancels the Bukkit game-event dispatch on synthetic levels, which have no CraftWorld and would
 * throw from the world lookup. The raised priority makes this callback run before the upstream
 * injection; on a real level the condition is false and nothing changes.
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
