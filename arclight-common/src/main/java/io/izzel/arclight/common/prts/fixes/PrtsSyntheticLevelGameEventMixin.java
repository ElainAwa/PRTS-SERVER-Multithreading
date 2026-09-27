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
 * Synthetic dimensions (levels that exist only in memory, e.g. the projectile simulation level some
 * mods create) have no {@code CraftWorld}. Dispatching a Bukkit {@code GenericGameEvent} there
 * throws from the world lookup instead of doing anything useful, which turns a simulation tick into
 * a server crash.
 *
 * <p>The guard runs at the head of {@code ServerLevel#gameEvent} and, for a level that is not
 * actual, cancels before the Bukkit dispatch of the upstream Arclight mixin. For every real level
 * the condition is false and behaviour is unchanged. Priority is set above the upstream
 * configuration so this callback is inserted (and therefore executed) first.</p>
 */
@Mixin(value = ServerLevel.class, priority = 1500)
public abstract class PrtsSyntheticLevelGameEventMixin {

    @Inject(method = "gameEvent", cancellable = true, at = @At("HEAD"))
    private void prts$skipGameEventOnSyntheticLevel(Holder<GameEvent> holder, Vec3 pos, GameEvent.Context context, CallbackInfo ci) {
        if (!((LevelAccessorBridge) this).arclight$isActual()) {
            ci.cancel();
        }
    }
}
